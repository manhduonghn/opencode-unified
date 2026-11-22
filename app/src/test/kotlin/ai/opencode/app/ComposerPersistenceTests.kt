package ai.opencode.app

import ai.opencode.core.sharing.shared
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.composer.ComposerPersistence
import ai.opencode.feature.composer.ComposerPersistenceStorage
import ai.opencode.feature.composer.DraftState
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class ComposerPersistenceTests {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()
    private val server = OpenCodeServer(url = "https://saved.example", username = "kit", password = "test")
    private val otherServer = OpenCodeServer(url = "https://other.example", username = "kit", password = "test")

    @Test
    fun sessionDeletionServerDeletionAndStalenessCleanUpPersistedState() = runTest {
        val tempDir = Files.createTempDirectory("ComposerPersistenceTests-").toFile()
        try {
            val persistence = ComposerPersistence.make(ComposerPersistenceStorage.live(tempDir), debounce = 60.seconds, now = { Instant.ofEpochSecond(1_000_000) })
            val attachmentA = OpenCodeOutgoingAttachment(id = "00000000-0000-0000-0000-000000000031", data = "a".toByteArray(), mime = "image/png", name = "a.png")
            val attachmentB = OpenCodeOutgoingAttachment(id = "00000000-0000-0000-0000-000000000032", data = "b".toByteArray(), mime = "image/png", name = "b.png")
            persistence.scheduleDraft(server.persistenceID, "ses_1", null, DraftState(text = "Draft in ses_1", attachments = listOf(attachmentA)))
            persistence.scheduleDraft(otherServer.persistenceID, "ses_other", null, DraftState(text = "Draft on other server", attachments = listOf(attachmentB)))
            persistence.flush()
            val blobA = File(tempDir, "${ComposerPersistenceStorage.ATTACHMENTS_DIRECTORY_NAME}/${attachmentA.id}")
            val blobB = File(tempDir, "${ComposerPersistenceStorage.ATTACHMENTS_DIRECTORY_NAME}/${attachmentB.id}")
            assertTrue(blobB.exists())

            // Delete `otherServer` via AppFeature.serverDeleted.
            shared.run { shared(ServerSharedKeys.savedServers).set(listOf(server, otherServer)) }
            val effects = detachedScope(coroutineContext)
            val store = shared.appStore(shared.appState(), effects) { this[ComposerPersistence] = persistence }
            store.send(AppFeature.Action.ServerDeleted(otherServer))
            assertEquals(listOf(server), shared.run { shared(ServerSharedKeys.savedServers).value })
            store.finish()
            effects.skipInFlightEffects()
            persistence.flush()

            assertNull(persistence.loadDraft(otherServer.persistenceID, "ses_other"))
            assertFalse("Deleted server's attachment blob is cleaned up", blobB.exists())
            assertEquals("Draft in ses_1", persistence.loadDraft(server.persistenceID, "ses_1")?.draft?.text)
            assertTrue("The other server's attachment stays", blobA.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
