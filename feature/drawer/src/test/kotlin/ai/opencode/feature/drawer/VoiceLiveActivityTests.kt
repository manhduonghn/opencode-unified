package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeatureTestSupport.LockIsolated
import ai.opencode.feature.drawer.HomeFeatureTestSupport.append
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.HomeFeatureTestSupport.increment
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receiveBounded
import ai.opencode.feature.drawer.HomeFeatureTestSupport.settle
import ai.opencode.feature.drawer.VoiceHomeTestSupport.copy
import ai.opencode.feature.drawer.VoiceHomeTestSupport.live
import ai.opencode.feature.drawer.VoiceHomeTestSupport.receiveVoice
import ai.opencode.feature.drawer.VoiceHomeTestSupport.voice
import ai.opencode.feature.drawer.VoiceHomeTestSupport.voiceCall
import ai.opencode.feature.drawer.VoiceHomeTestSupport.voiceEvent
import ai.opencode.feature.drawer.VoiceHomeTestSupport.withCall
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.updateObservedSession
import ai.opencode.feature.system.liveactivity.VoiceCallActivityAttributes
import ai.opencode.feature.system.liveactivity.VoiceCallCommand
import ai.opencode.feature.system.liveactivity.VoiceLiveActivity
import ai.opencode.feature.system.liveactivity.VoiceLiveActivityClient
import ai.opencode.feature.system.liveactivity.VoiceCallActivityAttributes.ContentState.Phase
import ai.opencode.feature.voice.DotScopeSimulation
import ai.opencode.feature.voice.RealtimePreflightClient
import ai.opencode.feature.voice.VoiceCallEvent
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class VoiceLiveActivityTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_voice", serverTitle = "Fix flaky upload test", projectID = "p", directory = "/Users/kit/project", updatedAt = HomeFeatureTestSupport.distantPast)
    private val t0: Instant = Instant.ofEpochSecond(1_000)

    private fun content(phase: VoiceCallActivityAttributes.ContentState.Phase, isMuted: Boolean = false, startedAt: Instant? = null) =
        VoiceCallActivityAttributes.ContentState(phase = phase, isMuted = isMuted, startedAt = startedAt)

    private fun home(): HomeFeature.State =
        HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session), activeSessionIDs = emptySet())).openingDetail(session)

    private fun pushRecorder(pushes: LockIsolated<List<VoiceCallActivityAttributes.ContentState?>>) =
        VoiceLiveActivityClient(update = { content, _ -> pushes.append(content) }, endAll = {}, commands = { kotlinx.coroutines.flow.emptyFlow() })

    private fun ai.opencode.core.dependencies.DependencyValues.Builder.startableCall(ends: LockIsolated<Int>? = null) {
        voiceCall { copy(start = { _, _ -> flow { awaitCancellation() } }, setMuted = { }, end = { ends?.increment() }) }
        this[RealtimePreflightClient] = RealtimePreflightClient(verify = { _, _ -> })
    }

    /** Lets launched effects reach their sleep first, then reduces what they send. */
    private suspend fun advance(store: TestStore<HomeFeature.State, HomeFeature.Action>, clock: TestClock, by: kotlin.time.Duration) {
        settle()
        clock.advance(by)
        settle()
        store.skipReceivedActions()
        settle()
        store.skipReceivedActions()
    }

    // MARK: Pushing from Home

    @Test fun homePushesOnlyWhenWhatTheActivityShowsChanges() = homeTest { h ->
        h.setShared(SharedKeys.openAIAPIKey, "sk-test")
        val pushes = LockIsolated(listOf<VoiceCallActivityAttributes.ContentState?>())
        val clock = TestClock()
        val store = h.store(h.state { home() }) {
            this[DateClient] = DateClient.constant(t0)
            this[Clock] = clock
            startableCall()
            this[VoiceLiveActivityClient] = pushRecorder(pushes)
        }
        store.exhaustive = false

        // What the activity shows, each time it changes.
        val shown = mutableListOf<VoiceCallActivityAttributes.ContentState?>()
        fun note() {
            val current = store.state.voiceCallActivity
            if (shown.isEmpty() || shown.last() != current) shown += current
        }
        suspend fun voiceAction(action: VoiceFeature.Action) {
            store.send(voice(action))
            note()
        }

        store.send(HomeFeature.Action.detail(SessionFeature.Action.VoiceTapped))
        note()
        voiceAction(VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected))
        voiceAction(VoiceFeature.Action.CallEvent(VoiceCallEvent.AssistantDelta(itemID = "a", delta = "Sure.")))
        voiceAction(VoiceFeature.Action.CallEvent(VoiceCallEvent.AssistantDelta(itemID = "a", delta = " The upload")))
        voiceAction(VoiceFeature.Action.CallEvent(VoiceCallEvent.UserTranscript(itemID = "u", text = "hm", final = false)))
        voiceAction(VoiceFeature.Action.MuteTapped)
        // A reconnect shows only once it passes a second.
        voiceAction(VoiceFeature.Action.CallEvent(VoiceCallEvent.Reconnecting))
        advance(store, clock, 500.milliseconds)
        note()
        voiceAction(VoiceFeature.Action.CallEvent(VoiceCallEvent.Reconnected))
        advance(store, clock, 1.seconds)
        note()
        voiceAction(VoiceFeature.Action.CallEvent(VoiceCallEvent.Reconnecting))
        settle()
        clock.advance(1.seconds)
        store.receiveVoice(VoiceFeature.Action.ReconnectingShown::class)
        note()
        voiceAction(VoiceFeature.Action.CallEvent(VoiceCallEvent.Reconnected))
        voiceAction(VoiceFeature.Action.EndTapped)
        settle()
        store.skipReceivedActions()
        settle()
        note()
        // Hung up: dismissed at once, though the call stays while its bar sweeps out.
        assertEquals(VoiceFeature.State.Phase.Ended, store.state.sessionDetail?.voice?.phase)
        assertNull(store.state.voiceCallActivity)
        settle()
        store.skipReceivedActions()
        advance(store, clock, (DotScopeSimulation.exitDuration * 1_000).toLong().milliseconds)
        assertNull(store.state.sessionDetail?.voice)

        // Speech, captions, and a reconnect too short to show change nothing.
        assertEquals(
            listOf(
                content(VoiceCallActivityAttributes.ContentState.Phase.Connecting),
                content(VoiceCallActivityAttributes.ContentState.Phase.Live, startedAt = t0),
                content(VoiceCallActivityAttributes.ContentState.Phase.Live, isMuted = true, startedAt = t0),
                content(VoiceCallActivityAttributes.ContentState.Phase.Reconnecting, isMuted = true, startedAt = t0),
                content(VoiceCallActivityAttributes.ContentState.Phase.Live, isMuted = true, startedAt = t0),
                null,
            ),
            shown,
        )
        assertNull(store.state.voiceCallActivity)
        // Android: the call's foreground service shows it; nothing is posted through the client.
        assertTrue(pushes.value.isEmpty())
        h.skipInFlightEffects()
    }

    // MARK: Commands

    @Test fun homeWithoutACallEndsAStrayActivity() = homeTest { h ->
        val pushes = LockIsolated(listOf<VoiceCallActivityAttributes.ContentState?>())
        val store = h.store(h.state { home().copy(voiceCallActivity = content(VoiceCallActivityAttributes.ContentState.Phase.Live, startedAt = t0)) }) {
            this[DateClient] = DateClient.constant(t0)
            this[VoiceLiveActivityClient] = pushRecorder(pushes)
        }
        store.exhaustive = false
        store.send(HomeFeature.Action.VoiceCallCommandReceived(VoiceCallCommand.ToggleMute))
        store.finish()
        assertEquals(listOf<VoiceCallActivityAttributes.ContentState?>(null), pushes.value)
        assertNull(store.state.voiceCallActivity)
    }

    @Test fun homeForwardsCommandsToTheCall() = homeTest { h ->
        h.setShared(SharedKeys.openAIAPIKey, "sk-test")
        val clock = TestClock()
        val ends = LockIsolated(0)
        val store = h.store(h.state { home() }) {
            this[DateClient] = DateClient.constant(t0)
            this[Clock] = clock
            startableCall(ends)
        }
        store.exhaustive = false

        // Without a call the commands only end the activity they came from.
        store.send(HomeFeature.Action.VoiceCallCommandReceived(VoiceCallCommand.End))

        store.send(HomeFeature.Action.detail(SessionFeature.Action.VoiceTapped))
        store.send(voiceEvent(VoiceCallEvent.Connected))
        store.send(HomeFeature.Action.VoiceCallCommandReceived(VoiceCallCommand.ToggleMute))
        store.receiveVoice(VoiceFeature.Action.MuteTapped::class)
        assertEquals(true, store.state.sessionDetail?.voice?.isMuted)
        store.send(HomeFeature.Action.VoiceCallCommandReceived(VoiceCallCommand.End))
        store.receiveVoice(VoiceFeature.Action.EndTapped::class)
        store.skipReceivedActions()
        assertEquals(VoiceFeature.State.Phase.Ended, store.state.sessionDetail?.voice?.phase)
        // The ended call sweeping out ignores the activity's buttons.
        store.send(HomeFeature.Action.VoiceCallCommandReceived(VoiceCallCommand.ToggleMute))
        store.send(HomeFeature.Action.VoiceCallCommandReceived(VoiceCallCommand.End))
        assertEquals(true, store.state.sessionDetail?.voice?.isMuted)
        settle()
        store.skipReceivedActions()
        advance(store, clock, (DotScopeSimulation.exitDuration * 1_000).toLong().milliseconds)
        assertNull(store.state.sessionDetail?.voice)
        settle()
        assertEquals(1, ends.value)
        h.skipInFlightEffects()
    }

    @Test fun aDroppedOrFailedCallIgnoresTheButtons() = homeTest { h ->
        val store = h.store(h.state { home().withCall(VoiceFeature.State(server, session).withPhase(VoiceFeature.State.Phase.Dropped)) }) {
            this[DateClient] = DateClient.constant(t0)
        }
        store.exhaustive = false
        store.send(HomeFeature.Action.VoiceCallCommandReceived(VoiceCallCommand.ToggleMute))
        store.send(HomeFeature.Action.VoiceCallCommandReceived(VoiceCallCommand.End))
        // The card stays until the user closes or rejoins it.
        assertEquals(VoiceFeature.State.Phase.Dropped, store.state.sessionDetail?.voice?.phase)
        assertEquals(false, store.state.sessionDetail?.voice?.isMuted)
        h.skipInFlightEffects()
    }

    // MARK: Renames

    @Test fun renamingTheCallsSessionRenamesTheCallButPushesNothing() = homeTest { h ->
        val home = h.state {
            val home = home().withCall(VoiceFeature.State(server, session).live())
            // A draft call's new session may not be discovered as family yet.
            home.copy(sessionDetail = home.sessionDetail!!.copy(familySessions = emptyList()))
        }
        val pushes = LockIsolated(listOf<VoiceCallActivityAttributes.ContentState?>())
        val store = h.store(home) {
            this[DateClient] = DateClient.constant(t0)
            this[VoiceLiveActivityClient] = pushRecorder(pushes)
        }
        store.exhaustive = false

        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.SessionRenamed(sessionID = session.id, title = "Upload retries")))
        store.receiveBounded({ it.detailAction is SessionFeature.Action.FamilySessionRenamed }, "sessionDetail.familySessionRenamed")
        assertEquals("Upload retries", store.state.sessionDetail?.voice?.sessionTitle)

        // Another session's rename leaves the call alone.
        store.send(HomeFeature.Action.detail(SessionFeature.Action.FamilySessionRenamed(sessionID = "ses_other", title = "Other")))
        assertEquals("Upload retries", store.state.sessionDetail?.voice?.sessionTitle)

        settle()
        // The activity shows no title.
        assertTrue(pushes.value.isEmpty())
        h.skipInFlightEffects()
    }

    @Test fun anObservedTitleRenamesTheCall() = homeTest { h ->
        h.state {
            val home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session), activeSessionIDs = emptySet()))
            var detail = home.makeDetail(session).second
            detail = detail.copy(voice = VoiceFeature.State(server, session).live())
            // The server's automatic title for the session a draft call created.
            detail = detail.updateObservedSession(session.copy(serverTitle = "Upload retries"))
            assertEquals("Upload retries", detail.voice?.sessionTitle)

            detail = detail.updateObservedSession(session.copy(id = "ses_other", serverTitle = "Other"))
            assertEquals("Upload retries", detail.voice?.sessionTitle)
        }
    }

    // MARK: Mapping

    private val t1: Instant = Instant.ofEpochSecond(1_060)

    private fun HomeFeatureTestSupport.Harness.voice(phase: VoiceFeature.State.Phase, configure: (VoiceFeature.State) -> VoiceFeature.State = { it }): VoiceFeature.State =
        configure(state { VoiceFeature.State(server, session) }.withPhase(phase))

    private fun map(call: VoiceFeature.State?, previous: VoiceCallActivityAttributes.ContentState?, now: Instant) =
        VoiceLiveActivity.content(call?.let(::liveActivityCall), previous, now)

    @Test fun noCallAndHangUpShowNothing() = homeTest { h ->
        assertNull(map(null, null, t0))
        val live = content(Phase.Live, startedAt = t0)
        assertNull(map(h.voice(VoiceFeature.State.Phase.Ended), live, t1))
    }

    @Test fun phasesMapToTheActivityPhase() = homeTest { h ->
        val live = content(Phase.Live, startedAt = t0)
        val cases = listOf(
            VoiceFeature.State.Phase.Connecting to Phase.Connecting,
            VoiceFeature.State.Phase.Live to Phase.Live,
            VoiceFeature.State.Phase.EndingAfterGoodbye(spoken = false) to Phase.Live,
            VoiceFeature.State.Phase.EndingAfterGoodbye(spoken = true) to Phase.Live,
            VoiceFeature.State.Phase.Dropped to Phase.Ended,
            VoiceFeature.State.Phase.Failed("Couldn’t connect") to Phase.Ended,
        )
        for ((phase, expected) in cases) assertEquals("$phase", expected, map(h.voice(phase), live, t1)?.phase)
    }

    @Test fun onlyAVisibleReconnectReadsAsReconnecting() = homeTest { h ->
        val live = content(Phase.Live, startedAt = t0)
        assertEquals(content(Phase.Reconnecting, startedAt = t0), map(h.voice(VoiceFeature.State.Phase.Live) { it.copy(isReconnecting = true) }, live, t1))
        assertEquals(Phase.Reconnecting, map(h.voice(VoiceFeature.State.Phase.EndingAfterGoodbye(spoken = false)) { it.copy(isReconnecting = true) }, live, t1)?.phase)
        // A drop that couldn't be restored ends the call.
        assertEquals(Phase.Ended, map(h.voice(VoiceFeature.State.Phase.Dropped) { it.copy(isReconnecting = true) }, live, t1)?.phase)
    }

    @Test fun whatTheCallIsDoingDoesNotShow() = homeTest { h ->
        val live = content(Phase.Live, startedAt = t0)
        val busy = h.voice(VoiceFeature.State.Phase.Live) {
            val captured = it.tail.capture()
            it.copy(
                isAssistantSpeaking = true,
                pendingWork = listOf(VoiceFeature.State.PendingWork(callID = "c", capture = captured.capture)),
                targetNeedsInput = true,
                isAudioInterrupted = true,
            )
        }
        assertEquals(live, map(busy, live, t1))
    }

    @Test fun muteIsCarried() = homeTest { h ->
        val live = content(Phase.Live, startedAt = t0)
        assertEquals(content(Phase.Live, isMuted = true, startedAt = t0), map(h.voice(VoiceFeature.State.Phase.Live) { it.copy(isMuted = true) }, live, t1))
        assertEquals(
            content(Phase.Reconnecting, isMuted = true, startedAt = t0),
            map(h.voice(VoiceFeature.State.Phase.Live) { it.copy(isReconnecting = true, isMuted = true) }, live, t1),
        )
    }

    @Test fun theTimerStartsWhenTheCallConnectsAndSurvivesReconnects() = homeTest { h ->
        val connecting = map(h.voice(VoiceFeature.State.Phase.Connecting), null, t0)
        assertNull(connecting?.startedAt)
        val live = map(h.voice(VoiceFeature.State.Phase.Live), connecting, t0)
        assertEquals(t0, live?.startedAt)
        val reconnecting = map(h.voice(VoiceFeature.State.Phase.Live) { it.copy(isReconnecting = true) }, live, t1)
        assertEquals(t0, reconnecting?.startedAt)
        val ended = map(h.voice(VoiceFeature.State.Phase.Dropped), reconnecting, t1)
        assertEquals(content(Phase.Ended, startedAt = t0), ended)
        // Rejoin starts a new call and a new timer.
        val rejoining = map(h.voice(VoiceFeature.State.Phase.Connecting), ended, t1)
        assertNull(rejoining?.startedAt)
        assertEquals(t1, map(h.voice(VoiceFeature.State.Phase.Live), rejoining, t1)?.startedAt)
        // A call that never connected has no timer to show.
        assertNull(map(h.voice(VoiceFeature.State.Phase.Failed("No key")), connecting, t1)?.startedAt)
    }

    @Test fun theAttributesNameTheCallsSession() = homeTest { h ->
        val state = h.voice(VoiceFeature.State.Phase.Live)
        assertEquals(session.id, VoiceLiveActivity.attributes(liveActivityCall(state)).sessionID)
        assertEquals(server.id, VoiceLiveActivity.attributes(liveActivityCall(state)).serverID)

        val draft = h.state { VoiceFeature.State(server, draftDirectory = "/x", model = null) }
        assertEquals("", VoiceLiveActivity.attributes(liveActivityCall(draft)).sessionID)
    }
}
