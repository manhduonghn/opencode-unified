package ai.opencode.feature.drawer

import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.composer.PendingPrompt
import ai.opencode.feature.composer.PromptOutbox
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.model.OpenCodeHomeSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WorkingOrderTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
    private fun home() = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet()))
    private fun outbox() = PromptOutbox(scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)).also {
        it.recordAdmitted(
            PendingPrompt(messageID = "msg_VOICE", text = "x", attachments = emptyList(), requestEpoch = 0),
            sessionID = "ses_elsewhere", serverKey = server.persistenceID,
        )
    }

    /** Home hears every session's deliveries, so a send it recorded reads as sent wherever the screen is. */
    @Test
    fun homeMarksDeliveriesForEverySession() = runTest {
        val outbox = outbox()
        val store = TestStore(home(), HomeFeature(), backgroundScope) { this[PromptOutbox] = outbox }
        store.exhaustive = false
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.InputQueueChanged(sessionID = "ses_elsewhere", inputID = "msg_VOICE")))
        assertEquals(listOf(false), outbox.contents(serverKey = server.persistenceID, sessionID = "ses_elsewhere").admitted.map { it.wasRunning })
    }

    /** Withdrawn from the inbox before delivery, a recorded send doesn't come back as a pending row on entry. */
    @Test
    fun aCancelledRecordedSendIsForgotten() = runTest {
        val outbox = outbox()
        val store = TestStore(home(), HomeFeature(), backgroundScope) { this[PromptOutbox] = outbox }
        store.exhaustive = false
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.InputCancelled(sessionID = "ses_elsewhere", inputID = "msg_VOICE")))
        assertTrue(outbox.contents(serverKey = server.persistenceID, sessionID = "ses_elsewhere").admitted.isEmpty())
    }
}
