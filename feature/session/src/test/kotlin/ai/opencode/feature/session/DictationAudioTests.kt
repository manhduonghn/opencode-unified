package ai.opencode.feature.session

import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.store.PresentationAction
import ai.opencode.feature.composer.DictationClient
import ai.opencode.feature.composer.DictationHoldRelease
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.voice.VoiceCallClient
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class DictationHoldReportingTests {
    private val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
    private val session = OpenCodeSession(id = "ses_1", serverTitle = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = Instant.EPOCH)

    private fun Harness.screen(): SessionFeature.State =
        state { SessionFeature.State.session(server = server, session = session, cachedItems = emptyList()).copy(isVisible = true) }

    /** What starting a call on the session does to the screen. */
    private fun Harness.startedCall(state: SessionFeature.State): SessionFeature.State {
        val voice = state { VoiceFeature.State(server, session) }.let {
            it.copy(replyWatch = it.replyWatch.copy(activeSessionIDs = state.activeFamilySessionIDs), viewing = state.browser.viewing)
        }
        return state { state.copy(voice = voice).syncVoiceTarget() }
    }

    @Test fun theComposerLetsAHeldSessionGo() = sessionTest { h ->
        val releases = LockIsolated(listOf<DictationHoldRelease>())
        val store = h.store(h.screen()) {
            this[UuidClient] = UuidClient.incrementing()
            this[DictationClient] = DictationClient(releaseHold = { releases.append(it) })
        }

        // Send with an empty draft still means the composer moved on.
        store.send(Action.SendTapped)
        store.send(Action.VisibilityChanged(false)) { it.copy(isVisible = false, isLoading = false) }
        // A call takes the mic: any hold ends. Nothing held: it starts at once.
        store.send(Action.VoiceTapped) { h.startedCall(it) }
        store.send(Action.Voice(PresentationAction.Dismiss)) { it.copy(voice = null) }
        store.finish()
        assertEquals(listOf(DictationHoldRelease.Sent, DictationHoldRelease.Left, DictationHoldRelease.Call), releases.value)
    }

    /**
     * Dictation still holding the session: the call starts only once it has
     * let go, so its setup never races dictation's deactivation.
     */
    @Test fun aCallStartsOnlyOnceDictationHasLetGo() = sessionTest { h ->
        val releases = LockIsolated(listOf<DictationHoldRelease>())
        val letGo = CompletableDeferred<Unit>()
        val store = h.store(h.screen()) {
            this[UuidClient] = UuidClient.incrementing()
            this[DictationClient] = DictationClient(
                holdsAudio = { true },
                releaseHold = { reason ->
                    val first = releases.withValue { val next = it + reason; next to (next.size == 1) }
                    if (first) letGo.await()
                },
            )
        }

        store.send(Action.VoiceTapped)
        repeat(500) { if (releases.value.isEmpty()) delay(2) }
        assertEquals(listOf(DictationHoldRelease.Call), releases.value)
        assertNull("the call started before dictation let go", store.state.voice)
        letGo.complete(Unit)
        store.receive(Action.CallAudioYielded::class) { h.startedCall(it) }
        store.send(Action.Voice(PresentationAction.Dismiss)) { it.copy(voice = null) }
        store.finish()
        assertEquals(listOf(DictationHoldRelease.Call, DictationHoldRelease.Call), releases.value)
    }

    /** Raise to Talk waits the same way, and the call hears it's at the ear. */
    @Test fun raiseToTalkWaitsForDictationToo() = sessionTest { h ->
        h.sharedStore.reference(SharedKeys.voiceModeEnabled).set(true)
        h.sharedStore.reference(SharedKeys.openAIAPIKey).set("sk-test")
        val routes = LockIsolated(listOf<Boolean>())
        val store = h.store(h.screen()) {
            this[UuidClient] = UuidClient.incrementing()
            this[DictationClient] = DictationClient(holdsAudio = { true }, releaseHold = {})
            this[VoiceCallClient] = VoiceCallClient(
                start = { _, _ -> emptyFlow() },
                completeFunction = { _, _ -> },
                setMuted = {},
                setAtEar = { routes.append(it) },
                updateInstructions = { _, _ -> },
                end = {},
            )
        }
        store.exhaustive = false

        store.send(Action.RaisedToEar)
        assertNull(store.state.voice)
        store.receive(Action.CallAudioYielded::class)
        store.receive(Action.AtEarChanged::class)
        store.receive({ it is Action.Voice }, "voice")
        assertEquals(true, store.state.voice?.isAtEar)
        h.skipInFlightEffects()
    }
}
