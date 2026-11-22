package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.experiments.InAppNoticeMode
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.sound.SoundEffectsClient
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.catchup.CatchUpFeature
import ai.opencode.feature.drawer.catchup.CatchUpFinishTimeline
import ai.opencode.feature.drawer.notices.AccessibilityAnnouncer
import ai.opencode.feature.drawer.notices.DeliveredNotificationsClient
import ai.opencode.feature.drawer.notices.InAppNoticeSettings
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.Collections
import kotlin.time.Duration.Companion.seconds

/**
 * Home's and Catch Up's half: a notice's sound beside its haptic, silence during a call and for the
 * backlog before notices settle, and Catch Up Done with the finish's success.
 */
class SoundEffectsTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")

    private fun features(on: Boolean) = ExperimentalFeatures.enabling(if (on) setOf(ExperimentalFeature.soundEffects) else emptySet())

    private fun log(): MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun recording(log: MutableList<String>) = SoundEffectsClient(
        play = { log += "sound.${it.rawValue}" },
        prepare = { log += "sound.prepare" },
    )

    private fun session(id: String, title: String) = OpenCodeSession(
        id = id, serverTitle = title, projectID = "p", directory = "/project",
        updatedAt = Instant.ofEpochSecond(10), idleMilliseconds = 1_000, viewedMilliseconds = 1_000,
    )

    private fun home(inCall: Boolean = false, settled: Boolean = true): HomeFeature.State {
        val sessions = listOf(session("upload", "Upload tests"), session("login", "Fix login flow"))
        var state = HomeFeature.State(server, OpenCodeHomeSnapshot(emptyList(), sessions, emptySet()))
        state = state.copy(
            connection = state.connection.copy(hasConnected = true),
            noticeReadiness = if (settled) HomeFeature.State.NoticeReadiness.settled else HomeFeature.State.NoticeReadiness.unsettled,
        )
        state = state.openingDraft()
        var detail = checkNotNull(state.sessionDetail).copy(isVisible = true)
        if (inCall) detail = detail.copy(voice = VoiceFeature.State(server, sessions[0]).withPhase(VoiceFeature.State.Phase.Live))
        return state.copy(sessionDetail = detail)
    }

    private fun CoroutineScope.homeStore(state: HomeFeature.State, on: Boolean, log: MutableList<String>): TestStore<HomeFeature.State, Action> {
        val store = TestStore(state, HomeFeature(), this) {
            this[Clock] = TestClock()
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(100))
            this[InAppNoticeSettings] = InAppNoticeSettings.constant(InAppNoticeMode.all)
            this[HapticsClient] = HapticsClient(play = { log += it.name })
            this[SoundEffectsClient] = recording(log)
            this[ExperimentalFeatures] = features(on)
            this[AccessibilityAnnouncer] = AccessibilityAnnouncer { _, _ -> }
            this[DeliveredNotificationsClient] = DeliveredNotificationsClient(removeAll = { }, removeNeedsInput = { })
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                viewSession = { _, _, _ -> },
                transcript = { _, _, _ -> emptyList() },
                homeAttention = { _, _, _ -> emptySet() },
                homeSnapshot = { throw OpenCodeClientError("offline") },
            )
        }
        store.exhaustive = false
        return store
    }

    private suspend fun finishLogin(store: TestStore<HomeFeature.State, Action>) {
        val sessions = store.state.sessions.map { if (it.id == "login") it.copy(idleMilliseconds = 2_000) else it }
        store.send(Action.SnapshotRefreshed(OpenCodeHomeSnapshot(emptyList(), sessions, emptySet())))
    }

    @Test fun aDoneNoticePlaysReplyDone() {
        for ((on, inCall, expected) in listOf(
            Triple(true, false, listOf("sound.replyDone")),
            Triple(false, false, emptyList()),
            Triple(true, true, emptyList()),
        )) runTest {
            val log = log()
            val store = homeStore(home(inCall = inCall), on, log)
            finishLogin(store)
            assertEquals("Done", store.state.inAppNotice?.status)
            assertEquals("on=$on inCall=$inCall", expected, log.toList())
            store.skipInFlightEffects()
        }
    }

    @Test fun aNeedsInputNoticePlaysNeedsInputWithItsAttention() {
        for ((on, inCall, expected) in listOf(
            Triple(true, false, listOf("attention", "sound.needsInput")),
            Triple(false, false, listOf("attention")),
            Triple(true, true, emptyList()),
        )) runTest {
            val log = log()
            val store = homeStore(home(inCall = inCall), on, log)
            store.send(Action.AttentionRefreshed(setOf("upload")))
            assertEquals("Needs your input", store.state.inAppNotice?.status)
            assertEquals("on=$on inCall=$inCall", expected, log.toList())
            store.skipInFlightEffects()
        }
    }

    /** What finished while away (launch, reconnect) shows no notice and plays nothing. */
    @Test fun theBacklogBeforeNoticesSettleIsSilent() = runTest {
        val log = log()
        val store = homeStore(home(settled = false), true, log)
        finishLogin(store)
        store.send(Action.AttentionRefreshed(setOf("upload")))
        assertNull(store.state.inAppNotice)
        assertTrue(log.isEmpty())
        store.skipInFlightEffects()
    }

    // Catch Up Done

    @Test fun finishingCatchUpPlaysCatchUpDoneWithItsSuccess() {
        for ((on, expected) in listOf(true to listOf("success", "sound.catchUpDone"), false to listOf("success"))) runTest {
            val clock = TestClock()
            val log = log()
            val stack = CatchUpFeature.Stack(queue = emptyList(), returnSessionID = "read")
            val store = TestStore(CatchUpFeature.State(stack = stack), CatchUpFeature(), this) {
                this[Clock] = clock
                this[HapticsClient] = HapticsClient(play = { log += it.name })
                this[SoundEffectsClient] = recording(log)
                this[ExperimentalFeatures] = features(on)
            }
            val timeline = CatchUpFinishTimeline.current(reducedMotion = false)

            store.send(CatchUpFeature.Action.Finished(reducedMotion = false)) {
                it.copy(stack = null, exitingStack = stack, justFinished = true, finishes = 1)
            }
            clock.advance(timeline.checkTime.seconds)
            store.receive(CatchUpFeature.Action.FinishChecked)
            assertEquals("on=$on", expected, log.toList())
            clock.advance(timeline.restEnd.seconds)
            store.receive(CatchUpFeature.Action.FinishRested) { it.copy(justFinished = false) }
            assertEquals("on=$on", expected, log.toList())
            store.finish()
        }
    }
}
