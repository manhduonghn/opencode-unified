package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.composer.DictationClient
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.feature.composer.SharedDraftTarget
import ai.opencode.feature.drawer.HomeFeatureTestSupport.LockIsolated
import ai.opencode.feature.drawer.HomeFeatureTestSupport.append
import ai.opencode.feature.drawer.HomeFeatureTestSupport.detail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receiveBounded
import ai.opencode.feature.drawer.HomeFeatureTestSupport.settle
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.isDictating
import ai.opencode.feature.system.liveactivity.DictationActivityAttributes
import ai.opencode.feature.system.liveactivity.DictationCommand
import ai.opencode.feature.system.liveactivity.DictationLiveActivity
import ai.opencode.feature.system.liveactivity.DictationLiveActivityClient
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class DictationLiveActivityTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
    private val first = OpenCodeSession(id = "ses_first", serverTitle = "First", projectID = "p", directory = "/work", updatedAt = Instant.ofEpochSecond(2_000))
    private val second = OpenCodeSession(id = "ses_second", serverTitle = "Second", projectID = "p", directory = "/work", updatedAt = Instant.ofEpochSecond(1_000))
    private val t0: Instant = Instant.ofEpochSecond(3_000)

    private fun home(): HomeFeature.State =
        HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(first, second), activeSessionIDs = emptySet()))
            .copy(transcriptCache = mapOf(first.id to emptyList(), second.id to emptyList()))
            .openingDetail(first, cachedItems = emptyList())

    private fun dictation(match: (DictationFeature.Action) -> Boolean): (HomeFeature.Action) -> Boolean = { action ->
        val presented = (action as? HomeFeature.Action.SessionDetail)?.action as? PresentationAction.Presented
        val dictation = (presented?.action as? SessionFeature.Action.Dictation)?.action
        dictation != null && match(dictation)
    }

    /**
     * A held mic shows nothing; locked, the recording gets its activity,
     * which follows it to another session and ends the moment Stop is
     * tapped on it. Stop leaves the words in the draft, unsent.
     */
    @Test
    fun aHandsFreeRecordingShowsItsActivityUntilStop() = homeTest { h ->
        val feed = Channel<String>(Channel.UNLIMITED)
        val pushes = LockIsolated(listOf<DictationActivityAttributes.ContentState?>())
        val sent = LockIsolated(listOf<String>())
        val store = h.store(h.state { home() }) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(t0)
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeAttention = { _, _, _ -> emptySet() },
                sendPrompt = { _, sessionID, _, _, _, _, _ -> sent.append(sessionID) },
            )
            this[DictationClient] = DictationClient(
                start = { feed },
                stop = {
                    feed.trySend("the words")
                    feed.close()
                },
            )
            this[DictationLiveActivityClient] = DictationLiveActivityClient(update = { content, _ -> pushes.append(content) }, endAll = {}, commands = { emptyFlow() })
        }
        store.exhaustive = false

        store.send(detail(SessionFeature.Action.micPressBegan))
        store.receive(detail(SessionFeature.Action.dictationReady))
        settle(20)
        assertTrue("a held mic goes out with its press", pushes.value.isEmpty())

        store.send(detail(SessionFeature.Action.micPressLocked))
        store.send(HomeFeature.Action.SessionTapped(second.id))
        feed.trySend("the")
        store.receiveBounded(dictation { it is DictationFeature.Action.Updated }, "dictationUpdated")

        store.send(HomeFeature.Action.DictationCommandReceived(DictationCommand.Stop))
        store.receive(detail(SessionFeature.Action.dictationStopRequested))
        store.receiveBounded(dictation { it is DictationFeature.Action.Stopped }, "dictationStopped")
        repeat(100) { if (pushes.value.size < 3) settle(1) }

        assertEquals(
            listOf(
                DictationActivityAttributes.ContentState(title = "First", startedAt = t0),
                DictationActivityAttributes.ContentState(title = "Second", startedAt = t0),
                null,
            ),
            pushes.value,
        )
        assertNull(store.state.dictationActivity)
        assertEquals(second, store.state.sessionDetail?.session)
        assertEquals("the words", store.state.sessionDetail?.draft)
        assertEquals(true, store.state.sessionDetail?.items?.isEmpty())
        assertTrue("Stop on the activity must not send", sent.value.isEmpty())
        h.skipInFlightEffects()
    }

    /** A Stop that no recording receives (the app relaunched) ends the activity. */
    @Test
    fun aStopWithNoRecordingEndsTheActivity() = homeTest { h ->
        val pushes = LockIsolated(listOf<DictationActivityAttributes.ContentState?>())
        val store = h.store(h.state { home().copy(dictationActivity = DictationActivityAttributes.ContentState(title = "First", startedAt = t0)) }) {
            this[DateClient] = DateClient.constant(t0)
            this[DictationLiveActivityClient] = DictationLiveActivityClient(update = { content, _ -> pushes.append(content) }, endAll = {}, commands = { emptyFlow() })
        }
        store.exhaustive = false
        store.send(HomeFeature.Action.DictationCommandReceived(DictationCommand.Stop))
        store.finish()
        assertEquals(listOf<DictationActivityAttributes.ContentState?>(null), pushes.value)
        assertNull(store.state.dictationActivity)
    }

    /**
     * A message from the first session still finalizing when the screen moves
     * on is never sent into the next one: its words wait in the first
     * session's composer, and the recording that followed carries on.
     */
    @Test
    fun aFinalizingMessageStaysWithItsSessionAcrossASwitch() = homeTest { h ->
        val firstWords = Channel<String>(Channel.UNLIMITED)
        val secondWords = Channel<String>(Channel.UNLIMITED)
        val streams = LockIsolated(listOf(firstWords, secondWords))
        val sent = LockIsolated(listOf<String>())
        val store = h.store(h.state { home() }) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(t0)
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeAttention = { _, _, _ -> emptySet() },
                sendPrompt = { _, sessionID, _, _, _, _, _ -> sent.append(sessionID) },
            )
            this[DictationClient] = DictationClient(
                start = {
                    synchronized(streams) {
                        val next = streams.value.first()
                        streams.setValue(streams.value.drop(1))
                        next
                    }
                },
                stop = { },
            )
        }
        store.exhaustive = false

        store.send(detail(SessionFeature.Action.micPressBegan))
        store.receive(detail(SessionFeature.Action.dictationReady))
        firstWords.trySend("first message")
        store.receiveBounded(dictation { it == DictationFeature.Action.Updated("first message", 0) }, "dictationUpdated(first message)")
        store.send(detail(SessionFeature.Action.micPressReleased))
        store.send(detail(SessionFeature.Action.micPressBegan))
        store.receiveBounded(dictation { it == DictationFeature.Action.Ready }, "dictationReady")

        store.send(HomeFeature.Action.SessionTapped(second.id))
        assertEquals(second, store.state.sessionDetail?.session)
        firstWords.trySend("first message, all of it")
        firstWords.close()
        store.receiveBounded(dictation { it == DictationFeature.Action.Stopped(null, 0) }, "dictationStopped(nil, 0)")
        store.receiveBounded(
            { action ->
                val delegate = (action.detailAction as? SessionFeature.Action.Delegate)?.delegate as? SessionFeature.Delegate.KeepDraft
                delegate?.target == SharedDraftTarget.Session(first.id) && delegate.draft.text == "first message, all of it"
            },
            "delegate.keepDraft(first)",
        )
        assertEquals("first message, all of it", store.withDependencies { store.state.sharedDrafts[SharedDraftTarget.Session(first.id)]?.text })
        assertEquals(true, store.state.sessionDetail?.isDictating)

        // Back in the first session, the words are in its composer.
        store.send(HomeFeature.Action.SessionTapped(first.id))
        assertEquals("first message, all of it", store.state.sessionDetail?.draft)
        assertEquals(true, store.state.sessionDetail?.isDictating)
        assertTrue("A message from another session must not send here", sent.value.isEmpty())
        h.skipInFlightEffects()
    }

    /**
     * Only hands-free once the mic is live, outside a call: a held mic shows nothing, and a call
     * that failed or dropped doesn't count as a call.
     */
    @Test fun onlyALiveHandsFreeRecordingOutsideACallShows() = homeTest { h ->
        var detail = requireNotNull(h.state { home() }.sessionDetail)
        fun edit(change: (DictationFeature.State) -> DictationFeature.State) { detail = detail.copy(dictation = change(detail.dictation)) }
        edit { it.copy(phase = DictationFeature.Phase.Preparing) }
        assertNull(liveActivityRecording(detail))
        edit { it.copy(phase = DictationFeature.Phase.Recording) }
        assertEquals(DictationLiveActivity.Recording(title = "First", serverID = server.id), liveActivityRecording(detail))
        edit { it.copy(hold = DictationFeature.Hold(text = "")) }
        assertNull(liveActivityRecording(detail))
        edit { it.copy(hold = it.hold?.copy(isLocked = true)) }
        assertNotNull(liveActivityRecording(detail))
        val call = h.state { VoiceFeature.State(server, first) }.withPhase(VoiceFeature.State.Phase.Live)
        detail = detail.copy(voice = call)
        assertNull(liveActivityRecording(detail))
        detail = detail.copy(voice = call.withPhase(VoiceFeature.State.Phase.Dropped))
        assertNotNull(liveActivityRecording(detail))
    }
}
