package ai.opencode.core.diagnostics

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

/**
 * The diagnostics journal writes events in batches, and at once when the app may not come back to
 * write them.
 */
class DiagnosticsJournalWriteTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun eventsAreWrittenInBatchesAndAtOnceOnBackground() {
        val directory = Files.createTempDirectory("journal-batch-test-").toFile()
        try {
            val journal = DiagnosticsJournal(directory = directory, build = "1 (1)")
            journal.open(recording = true)
            for (index in 0 until 20) journal.append(event("event_$index", at = index.toDouble()))
            journal.flush()
            assertTrue("twenty events, not twenty writes", journal.writeBatches < 20)
            assertEquals(20, journal.currentRunEvents().size)

            // What's waiting goes at once when the app may not come back.
            for (index in 20 until 30) journal.append(event("event_$index", at = index.toDouble()))
            val written = journal.writeBatches
            journal.markBackground(waits = true)
            assertEquals(written + 1, journal.writeBatches)
            assertEquals("event_29", journal.currentRunEvents().last().name)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun event(name: String, at: Double): DiagnosticsEvent = DiagnosticsEvent(
        id = UUID.randomUUID(), kind = DiagnosticsEvent.Kind.Mark, monotonicSeconds = at,
        wallDate = Instant.ofEpochSecond(1_000), name = name,
    )
}
