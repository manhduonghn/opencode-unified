package ai.opencode.feature.session

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.sound.SoundEffectsClient
import ai.opencode.core.sound.soundEffectsSend
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.composer.DraftState
import ai.opencode.feature.composer.PendingPrompt
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.awaitCancellation
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * The session's sounds, Needs Input beside a docked request's attention, Error beside a failed
 * send's, and Send with its tap.
 */
class SoundEffectsTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = SessionTestSupport.testServer
    private val session = OpenCodeSession(id = "ses_1", serverTitle = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = Instant.now())
    private val permission = OpenCodePermissionRequest(id = "per_1", sessionID = "ses_1", action = "shell", resources = listOf("git status"))
    private val cleanup = OpenCodePermissionRequest(id = "per_2", sessionID = "ses_1", action = "shell", resources = listOf("rm -rf build"))
    private val none = OpenCodePendingRequests(permissions = emptyList(), forms = emptyList())

    private fun features(on: Boolean) = ExperimentalFeatures.enabling(if (on) setOf(ExperimentalFeature.soundEffects) else emptySet())

    /** Each sound lands in the haptics log as `sound.<name>`. */
    private fun recording(log: LockIsolated<List<String>>) = SoundEffectsClient(
        play = { effect -> log.append("sound.${effect.rawValue}") },
        prepare = { log.append("sound.prepare") },
    )

    private fun haptics(log: LockIsolated<List<String>>) = HapticsClient(play = { log.append(it.name) })

    private fun Harness.initial(inCall: Boolean = false, draft: String = ""): SessionFeature.State {
        val state = state { SessionFeature.State.session(server = server, session = session, cachedItems = emptyList()) }.copy(draft = draft)
        if (!inCall) return state
        val voice = state { VoiceFeature.State(server, session) }.withPhase(VoiceFeature.State.Phase.Live)
        return state.copy(voice = voice)
    }

    private fun Harness.sessionStore(
        state: SessionFeature.State,
        on: Boolean,
        log: LockIsolated<List<String>>,
        client: (OpenCodeClient) -> OpenCodeClient = { it },
    ) = store(state) {
        this[UuidClient] = UuidClient.incrementing()
        this[HapticsClient] = haptics(log)
        this[SoundEffectsClient] = recording(log)
        this[ExperimentalFeatures] = features(on)
        this[OpenCodeClient] = client(
            this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, _ -> },
                waitForIdle = { _, _ -> awaitCancellation() },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
                pendingInputs = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> none },
            ),
        )
    }.also { it.exhaustive = false }

    private fun Harness.exhaustiveSessionStore(on: Boolean, log: LockIsolated<List<String>>) = store(initial()) {
        this[HapticsClient] = haptics(log)
        this[SoundEffectsClient] = recording(log)
        this[ExperimentalFeatures] = features(on)
    }

    // region Needs Input

    @Test fun aDockedRequestPlaysNeedsInputWithItsAttention() {
        for ((on, inCall, expected) in listOf(
            Triple(true, false, listOf("attention", "sound.needsInput")),
            Triple(false, false, listOf("attention")),
            Triple(true, true, emptyList()),
        )) sessionTest { h ->
            val log = LockIsolated(listOf<String>())
            val store = h.sessionStore(h.initial(inCall), on, log)
            store.send(Action.PendingRequestsResponse(session.id, Result.success(none)))
            store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = listOf(permission), forms = emptyList()))))
            assertEquals("on=$on inCall=$inCall", expected, log.value)
            h.skipInFlightEffects()
        }
    }

    /** What's waiting when the screen opens is the baseline: its haptic, no sound. A request new after it arrived live and plays Needs Input. */
    @Test fun requestsWaitingAtOpenPlayNoSoundButOneArrivingAfterDoes() = sessionTest { h ->
        val log = LockIsolated(listOf<String>())
        val store = h.exhaustiveSessionStore(on = true, log)
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = listOf(permission), forms = emptyList())))) {
            it.withPermissionRequests(listOf(permission))
                .withRequestArrivalsAreLive(true)
                .beginAnswering()
        }
        assertEquals(listOf("attention"), log.value)

        // The same snapshot again (a later poll): nothing new.
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = listOf(permission), forms = emptyList()))))
        assertEquals(listOf("attention"), log.value)

        // An event's reread brings a new one.
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = listOf(permission, cleanup), forms = emptyList())))) {
            it.withPermissionRequests(listOf(permission, cleanup))
        }
        assertEquals(listOf("attention", "attention", "sound.needsInput"), log.value)
    }

    /** Losing the connection drops the baseline: the reconnect's snapshot lists what arrived in the gap as already waiting, without a sound. */
    @Test fun aReconnectSnapshotPlaysNoSound() = sessionTest { h ->
        val log = LockIsolated(listOf<String>())
        val store = h.exhaustiveSessionStore(on = true, log)
        store.send(Action.PendingRequestsResponse(session.id, Result.success(none))) {
            it.withRequestArrivalsAreLive(true)
        }
        store.send(Action.RequestsConnectionChanged(false)) {
            it.withRequestsConnectionIsAvailable(false)
                .withRequestFailureAwaitingReconnect(true)
                .withRequestArrivalsAreLive(false)
        }
        // The reconnect's poll delivers this read (driven end to end below).
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = listOf(permission), forms = emptyList())))) {
            it.withPermissionRequests(listOf(permission))
                .withRequestFailureAwaitingReconnect(false)
                .withRequestArrivalsAreLive(true)
                .beginAnswering()
        }
        assertEquals(listOf("attention"), log.value)
    }

    @Test fun aReconnectThroughThePollPlaysNoSound() = sessionTest { h ->
        val log = LockIsolated(listOf<String>())
        val requests = LockIsolated(none)
        val store = h.sessionStore(h.initial(), on = true, log) { it.copy(familyPendingRequests = { _, _, _ -> requests.value }) }
        store.send(Action.PendingRequestsResponse(session.id, Result.success(none)))
        store.send(Action.RequestsConnectionChanged(false))
        requests.setValue(OpenCodePendingRequests(permissions = listOf(permission), forms = emptyList()))
        store.send(Action.RequestsConnectionChanged(true))
        store.receive(Action.PendingRequestsResponse::class)
        assertEquals(listOf(permission), store.state.permissionRequests)
        assertFalse("sound.needsInput" in log.value)
        h.skipInFlightEffects()
    }

    // endregion

    // region Error

    @Test fun aFailedSendPlaysErrorWithItsHaptic() {
        for ((on, inCall, expected) in listOf(
            Triple(true, false, listOf("error", "sound.error")),
            Triple(false, false, listOf("error")),
            Triple(true, true, listOf("error")),
        )) sessionTest { h ->
            val log = LockIsolated(listOf<String>())
            val store = h.sessionStore(h.initial(inCall), on, log)
            store.send(
                Action.PromptFailed(
                    session.id,
                    OpenCodeClientError("Offline"),
                    PendingPrompt(messageID = "msg_1", text = "Ship it", attachments = emptyList(), requestEpoch = 1),
                ),
            )
            assertEquals("on=$on inCall=$inCall", expected, log.value)
            h.skipInFlightEffects()
        }
    }

    // endregion

    // region Send

    @Test fun sendPlaysOnlyWithSendSound() {
        for ((on, sendSound, expected) in listOf(
            Triple(true, true, listOf("tap", "sound.send")),
            Triple(true, false, listOf("tap")),
            Triple(false, true, listOf("tap")),
        )) sessionTest { h ->
            h.state { shared(SharedKeys.soundEffectsSend).set(sendSound) }
            val log = LockIsolated(listOf<String>())
            val store = h.sessionStore(h.initial(draft = "Ship it"), on, log)
            store.send(Action.SendTapped)
            assertEquals("on=$on sendSound=$sendSound", expected, log.value)
            h.skipInFlightEffects()
        }
    }

    // endregion
}
