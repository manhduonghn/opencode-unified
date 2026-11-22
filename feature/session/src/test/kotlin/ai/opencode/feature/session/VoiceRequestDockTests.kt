package ai.opencode.feature.session

import ai.opencode.core.experiments.key
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.composer.DraftState
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * A request raised during a call shows its form while the call floats over the screen, and the
 * voice agent's steered approvals answer it.
 */
class VoiceRequestDockTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_home", serverTitle = "HOME", projectID = "p", directory = "/Users/kit/home", updatedAt = Instant.EPOCH)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val cleanup: OpenCodeFormRequest
        get() = json.decodeFromString(
            OpenCodeFormRequest.serializer(),
            """{"id":"frm_cleanup","sessionID":"ses_home","title":"Seer cleanup","metadata":{"kind":"question"},"fields":[
              {"key":"q0","type":"string","title":"Shipped cards","options":[{"value":"done","label":"Mark them Done"},{"value":"skip","label":"Leave them"}]}]}""",
        )

    private fun Harness.calling(): SessionFeature.State = state {
        val voice = VoiceFeature.State(server, session).withPhase(VoiceFeature.State.Phase.Live)
        SessionFeature.State.session(server = server, session = session, cachedItems = emptyList())
            .copy(voice = voice, hasLoadedPendingRequests = true)
    }

    @Test fun aFloatingCallDocksTheRequestAndOnlyTheServerClearsIt() = sessionTest { h ->
        val initial = h.calling()
        val cleanup = cleanup
        val pending = LockIsolated(OpenCodePendingRequests(permissions = emptyList(), forms = listOf(cleanup)))
        val store = h.store(initial) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                familyPendingRequests = { _, ids, _ ->
                    assertEquals(listOf("ses_home"), ids.toList())
                    pending.value
                },
            )
        }

        store.send(Action.RequestsChanged)
        store.receive(Action.PendingRequestsResponse::class) {
            it.withRequestArrivalsAreLive(true)
                .withFormRequests(listOf(cleanup))
                .withFormAnswers(mapOf(cleanup.id to cleanup.defaultAnswers))
                .copy(voice = it.voice?.copy(targetNeedsInput = true))
                .beginAnswering()
        }
        h.state {
            assertTrue(store.state.callOwnsAudio)
            assertTrue("The floating call leaves the composer, so the request docks there", store.state.isAnsweringRequest)
        }
        assertEquals(DraftState(), store.state.parkedComposer)

        // A steered “yes” went in as a message: the server still lists the form, so the dock
        // and “Needs your input” stay.
        store.send(Action.RequestsChanged)
        store.receive(Action.PendingRequestsResponse::class)

        // Answered (by the voice agent's answer_question or anywhere else): the server's
        // snapshot clears both.
        pending.setValue(OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()))
        store.send(Action.RequestsChanged)
        store.receive(Action.PendingRequestsResponse::class) {
            it.withFormRequests(emptyList())
                .withFormAnswers(emptyMap())
                .copy(voice = it.voice?.copy(targetNeedsInput = false))
                .endAnswering()
        }
        assertFalse(store.state.isAnsweringRequest)
    }
}
