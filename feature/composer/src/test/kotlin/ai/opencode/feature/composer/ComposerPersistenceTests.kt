package ai.opencode.feature.composer

import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Its tests run the persistence client through the stores that host it. Where
 * a store only forwards to the client (`HomeFeature`'s session deletion calls
 * `removeSession`, `AppFeature`'s server deletion calls `removeServer`; see
 * `HomeFeatureDrawerState.kt` and `AppFeatureServers.kt`), the test calls the
 * client the same way here.
 */
class ComposerPersistenceTests {
    private val server = OpenCodeServer(url = "http://localhost:9", username = "opencode", password = "pw")
    private val otherServer = OpenCodeServer(url = "http://localhost:10", username = "opencode", password = "pw")
    private val sessionID = "ses_1"

    private val tempDirectories = mutableListOf<File>()

    private fun makeTempDirectory(): File =
        Files.createTempDirectory("ComposerPersistenceTests-").toFile().also { tempDirectories += it }

    @After fun removeTempDirectories() {
        tempDirectories.forEach { it.deleteRecursively() }
    }

    @Test fun sessionDeletionServerDeletionAndStalenessCleanUpPersistedState() = runBlocking {
        val tempDir = makeTempDirectory()
        var currentTime = Instant.ofEpochSecond(1_000_000)
        val persistence = ComposerPersistence.make(
            storage = ComposerPersistenceStorage.live(tempDir),
            debounce = 60.seconds,
            now = { currentTime },
        )

        val attachmentA = OpenCodeOutgoingAttachment(
            id = "00000000-0000-0000-0000-000000000031",
            data = "a".toByteArray(),
            mime = "image/png",
            name = "a.png",
        )
        val attachmentB = OpenCodeOutgoingAttachment(
            id = "00000000-0000-0000-0000-000000000032",
            data = "b".toByteArray(),
            mime = "image/png",
            name = "b.png",
        )
        val attachmentStale = OpenCodeOutgoingAttachment(
            id = "00000000-0000-0000-0000-000000000033",
            data = "stale".toByteArray(),
            mime = "image/png",
            name = "stale.png",
        )

        persistence.scheduleDraft(server.persistenceID, sessionID, null, DraftState(text = "Draft in ses_1", attachments = listOf(attachmentA)))
        persistence.scheduleDraft(otherServer.persistenceID, "ses_other", null, DraftState(text = "Draft on other server", attachments = listOf(attachmentB)))
        persistence.scheduleDraft(server.persistenceID, "ses_stale", null, DraftState(text = "Stale draft", attachments = listOf(attachmentStale)))
        persistence.flush()

        fun blob(attachment: OpenCodeOutgoingAttachment) = File(tempDir, "ComposerAttachments/${attachment.id}")
        val blobA = blob(attachmentA)
        val blobB = blob(attachmentB)
        val blobStale = blob(attachmentStale)
        assertTrue(blobA.exists())
        assertTrue(blobB.exists())
        assertTrue(blobStale.exists())

        // Delete session `ses_1`.
        persistence.removeSession(server.persistenceID, sessionID)
        persistence.flush()

        assertNull(persistence.loadDraft(server.persistenceID, sessionID))
        assertFalse("Deleted session's attachment blob is cleaned up", blobA.exists())
        assertEquals("Draft on other server", persistence.loadDraft(otherServer.persistenceID, "ses_other")?.draft?.text)

        // Delete `otherServer`.
        persistence.removeServer(otherServer.persistenceID)
        persistence.flush()

        assertNull(persistence.loadDraft(otherServer.persistenceID, "ses_other"))
        assertFalse("Deleted server's attachment blob is cleaned up", blobB.exists())

        // Advance past 30 days and reload from disk: stale entry is pruned and its attachment blob discarded.
        currentTime = Instant.ofEpochSecond(1_000_000 + PersistedComposerState.STALENESS_INTERVAL_SECONDS + 60)
        val relaunchedPersistence = ComposerPersistence.make(
            storage = ComposerPersistenceStorage.live(tempDir),
            debounce = 60.seconds,
            now = { currentTime },
        )
        assertNull(relaunchedPersistence.loadDraft(server.persistenceID, "ses_stale"))
        assertFalse("Stale draft's attachment blob is cleaned up", blobStale.exists())
    }
}
