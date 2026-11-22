package ai.opencode.feature.composer

import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodeModelRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * The storage half: a session's model pick and its requests' answers in progress outlive a
 * relaunch.
 */
class SessionStatePersistenceTests {
    private val server = OpenCodeServer(url = "https://state.example", username = "", password = "")
    private val sessionID = "ses_state"
    private val pick = OpenCodeModelRef(id = "picked", providerID = "example")

    private fun answers() = PersistedSessionState.RequestAnswers(
        formAnswers = mapOf("frm_state" to mapOf("q0" to OpenCodeFormValue.Str("Native")), "frm_gone" to mapOf("q0" to OpenCodeFormValue.Str("Stale"))),
        answerDrafts = mapOf("frm_state/q1" to "Half typed", "frm_gone/q0" to "Stale"),
        questionPageIndices = mapOf("frm_state" to 1),
    )

    @Test fun savedStateSurvivesARelaunchAndClearsWithItsSession() = runBlocking {
        val directory = Files.createTempDirectory("session-state-").toFile()
        try {
            val first = ComposerPersistence.make(ComposerPersistenceStorage.live(directory), debounce = 60.seconds)
            first.scheduleSessionState(server.persistenceID, sessionID, PersistedSessionState.Update(pendingModel = pick, requests = answers()))
            // Nothing known about requests yet keeps what was saved for them.
            first.scheduleSessionState(server.persistenceID, sessionID, PersistedSessionState.Update(pendingModel = pick, requests = null))
            first.flush()

            val relaunched = ComposerPersistence.make(ComposerPersistenceStorage.live(directory), debounce = 60.seconds)
            assertEquals(PersistedSessionState(pendingModel = pick, requests = answers()), relaunched.loadSessionState(server.persistenceID, sessionID))
            relaunched.removeSession(server.persistenceID, sessionID)
            assertNull(relaunched.loadSessionState(server.persistenceID, sessionID))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun nothingLeftToKeepDropsTheRecord() {
        var persisted = PersistedComposerState()
        val at = Instant.ofEpochSecond(1)
        val saved = persisted.applySessionState(PersistedSessionState.Update(pendingModel = pick), "s", "a", at)
        assertNotNull(saved)
        persisted = checkNotNull(saved)
        val unchanged = persisted.applySessionState(PersistedSessionState.Update(pendingModel = pick), "s", "a", at)
        val cleared = persisted.applySessionState(PersistedSessionState.Update(pendingModel = null, requests = PersistedSessionState.RequestAnswers()), "s", "a", at)
        assertNull(unchanged)
        assertNotNull(cleared)
        persisted = checkNotNull(cleared)
        assertNull(persisted.sessionStates)
        assertTrue(persisted.isEmpty)
    }

    @Test fun filesWrittenBeforeSessionStatesStillDecode() {
        val decoded = PersistedComposerState.json.decodeFromString(
            PersistedComposerState.serializer(),
            """{"version":1,"drafts":[],"outbox":[],"undelivered":[]}""",
        )
        assertNull(decoded.sessionStates)
    }
}
