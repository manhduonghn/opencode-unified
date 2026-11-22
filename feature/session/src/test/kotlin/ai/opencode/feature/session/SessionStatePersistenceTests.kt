package ai.opencode.feature.session

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.composer.ComposerPersistence
import ai.opencode.feature.composer.PersistedSessionState
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** The session half: arriving requests take back the answers saved for them. */
class SessionStatePersistenceTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "https://state.example", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_state", serverTitle = "State", projectID = "proj", directory = "/tmp/state", updatedAt = Instant.EPOCH)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun form(): OpenCodeFormRequest = json.decodeFromString(
        OpenCodeFormRequest.serializer(),
        """{"id":"frm_state","sessionID":"ses_state","title":"Questions","metadata":{"kind":"question"},"fields":[{"key":"q0","type":"string","title":"First"},{"key":"q1","type":"string","title":"Second"}]}""",
    )

    private fun answers() = PersistedSessionState.RequestAnswers(
        formAnswers = mapOf("frm_state" to mapOf("q0" to OpenCodeFormValue.Str("Native")), "frm_gone" to mapOf("q0" to OpenCodeFormValue.Str("Stale"))),
        answerDrafts = mapOf("frm_state/q1" to "Half typed", "frm_gone/q0" to "Stale"),
        questionPageIndices = mapOf("frm_state" to 1),
    )

    @Test fun arrivingRequestsTakeBackTheirSavedAnswers() = sessionTest { h ->
        val persistence = ComposerPersistence.inMemory(debounce = 60.seconds)
        persistence.scheduleSessionState(server.persistenceID, session.id, PersistedSessionState.Update(pendingModel = null, requests = answers()))
        val request = form()
        val state = h.state { SessionFeature.State.session(server = server, session = session, cachedItems = emptyList()) }
        val store = h.store(state) { this[ComposerPersistence] = persistence }
        store.exhaustive = false
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = listOf(request)))))
        assertEquals(mapOf("frm_state" to mapOf("q0" to OpenCodeFormValue.Str("Native"))), store.state.formAnswers)
        assertEquals(mapOf("frm_state" to 1), store.state.questionPageIndices)
        assertEquals(mapOf("frm_state/q1" to "Half typed"), store.state.answerDrafts)
        h.skipInFlightEffects()
    }
}
