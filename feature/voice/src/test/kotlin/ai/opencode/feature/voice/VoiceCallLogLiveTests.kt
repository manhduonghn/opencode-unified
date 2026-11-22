package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.testing.TestIsolationRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class VoiceCallLogLiveTests {
    @get:Rule val isolation = TestIsolationRule()
    @get:Rule val temporary = TemporaryFolder()

    private class Uploads {
        private val ids = mutableListOf<UUID>()
        val all: List<UUID> get() = synchronized(ids) { ids.toList() }
        fun add(log: VoiceCallLog) = synchronized(ids) { ids += log.id }
        fun waitFor(count: Int) {
            repeat(200) {
                if (all.size >= count) return
                Thread.sleep(10)
            }
        }
    }

    private class WriteRefused : IOException("refused")

    private fun makeRecorder(
        directory: File,
        developerMode: Boolean = true,
        uploads: Uploads = Uploads(),
        interval: Double = 0.0,
        failSavedWrites: AtomicBoolean = AtomicBoolean(false),
        readFile: ((File) -> ByteArray)? = null,
    ) = VoiceCallLogRecorder(
        directory = directory,
        date = DateClient { Instant.now() },
        upload = VoiceCallLogUploadClient { uploads.add(it); true },
        liveInterval = interval,
        watchesNetwork = false,
        isDeveloperMode = { developerMode },
        writeFile = { data, file ->
            if (failSavedWrites.get() && file.parentFile?.name != "InProgress") throw WriteRefused()
            VoiceCallLogRecorder.writeAtomically(data, file)
        },
        readFile = readFile ?: { it.readBytes() },
    )

    private fun temporaryDirectory(): File = File(temporary.root, "voice-live-${UUID.randomUUID()}")

    private fun liveFiles(directory: File): List<File> =
        File(directory, "InProgress").listFiles()?.filter { it.extension == "json" } ?: emptyList()

    /** A previous launch's call, left in progress: its recorder is gone. */
    private fun leaveInterruptedCall(directory: File, id: UUID = UUID.randomUUID()): UUID {
        val old = makeRecorder(directory)
        old.begin(id, title = "Voice lab", engine = "gptLive", at = Instant.ofEpochSecond(100))
        old.span(id, VoiceCallLog.Track.voice, "a1", label = "Working", at = Instant.ofEpochSecond(105))
        old.waitForLiveWrites()
        return id
    }

    @Test fun midCallLogIsPersistedWithoutHangUp() {
        val directory = temporaryDirectory()
        val recorder = makeRecorder(directory)
        val id = UUID.randomUUID()
        recorder.begin(id, title = "Voice lab", engine = "gptLive", at = Instant.ofEpochSecond(0))
        recorder.span(id, VoiceCallLog.Track.user, "u1", label = "Check it", at = Instant.ofEpochSecond(1))
        recorder.waitForLiveWrites()

        val file = liveFiles(directory).firstOrNull()
        assertNotNull(file)
        val live = VoiceCallLog.json.decodeFromString<VoiceCallLog>(file!!.readText())
        assertEquals(id, live.id)
        assertEquals(listOf("Check it"), live.spans.map { it.label })
        assertNull(live.endedAt)
        assertTrue("Only the in-progress copy exists mid-call", recorder.list().isEmpty())
        recorder.finish(id)
    }

    @Test fun throttleCoalescesBurstsAndBackgroundFlushWritesNow() = runBlocking {
        val directory = temporaryDirectory()
        val recorder = makeRecorder(directory, interval = 60.0)
        val id = UUID.randomUUID()
        recorder.begin(id, title = "Voice lab", engine = "gptLive")
        recorder.waitForLiveWrites()
        recorder.note(id, VoiceCallLog.Note.Kind.marker, "later")
        recorder.waitForLiveWrites()
        val file = liveFiles(directory).firstOrNull()
        assertNotNull(file)
        assertFalse("The second change waits inside the window", file!!.readText().contains("later"))

        recorder.persistOpenCalls()
        assertTrue(file.readText().contains("later"))
        recorder.finish(id)
    }

    @Test fun launchRecoversAnInterruptedCallAndUploadsOnce() = runBlocking {
        val directory = temporaryDirectory()
        val id = leaveInterruptedCall(directory)

        val uploads = Uploads()
        val next = makeRecorder(directory, uploads = uploads)
        val recovered = next.recoverInterrupted()
        assertEquals(listOf(id), recovered.map { it.id })
        assertEquals(listOf(id), uploads.all)

        val saved = next.list().firstOrNull()
        assertNotNull(saved)
        assertEquals(id, saved!!.id)
        assertNotNull(saved.endedAt)
        assertTrue(saved.spans.all { it.end != null })
        assertTrue(saved.notes.last().text.contains("interrupted"))
        assertTrue(liveFiles(directory).isEmpty())

        assertTrue("Recovery runs once per launch", next.recoverInterrupted().isEmpty())
        assertTrue("The next launch finds nothing", makeRecorder(directory, uploads = uploads).recoverInterrupted().isEmpty())
        assertEquals(listOf(id), uploads.all)
    }

    @Test fun cleanHangUpLeavesNothingToRecoverAndUploadsOnce() = runBlocking {
        val directory = temporaryDirectory()
        val uploads = Uploads()
        val recorder = makeRecorder(directory, uploads = uploads)
        val id = UUID.randomUUID()
        recorder.begin(id, title = "Voice lab", engine = "gptLive")
        recorder.note(id, VoiceCallLog.Note.Kind.marker, "hello")
        recorder.finish(id)
        recorder.note(id, VoiceCallLog.Note.Kind.marker, "after hang-up")
        recorder.waitForLiveWrites()
        uploads.waitFor(1)

        assertTrue(liveFiles(directory).isEmpty())
        assertTrue(makeRecorder(directory, uploads = uploads).recoverInterrupted().isEmpty())
        assertEquals(listOf(id), uploads.all)
        assertEquals(1, recorder.list().size)
    }

    @Test fun developerModeOffSavesRecoveredLogButNeverUploads() = runBlocking {
        val directory = temporaryDirectory()
        leaveInterruptedCall(directory)

        val uploads = Uploads()
        val next = makeRecorder(directory, developerMode = false, uploads = uploads)
        assertEquals(1, next.recoverInterrupted().size)
        assertTrue(uploads.all.isEmpty())
        assertEquals(1, next.list().size)
    }

    // Review findings

    /** Finding 1: recovery never touches a call begun in this process, running or just hung
     * up (its file can outlive `open` for a moment). */
    @Test fun recoveryLeavesThisLaunchsCallsAlone() = runBlocking {
        val directory = temporaryDirectory()
        val uploads = Uploads()
        val recorder = makeRecorder(directory, uploads = uploads)
        val hungUp = UUID.randomUUID()
        recorder.begin(hungUp, title = "Voice lab", engine = "gptLive")
        recorder.note(hungUp, VoiceCallLog.Note.Kind.marker, "done")
        recorder.waitForLiveWrites()
        val hungUpFile = liveFiles(directory).first()
        val hungUpData = hungUpFile.readBytes()
        recorder.finish(hungUp)
        recorder.waitForLiveWrites()
        uploads.waitFor(1)

        val running = UUID.randomUUID()
        recorder.begin(running, title = "Voice lab", engine = "gptLive")
        recorder.note(running, VoiceCallLog.Note.Kind.marker, "live")
        recorder.waitForLiveWrites()
        // As if recovery scanned between hang-up's removal from `open` and its delete.
        hungUpFile.writeBytes(hungUpData)

        assertTrue(recorder.recoverInterrupted().isEmpty())
        assertEquals("Hang-up's upload is the only one", listOf(hungUp), uploads.all)
        assertEquals("Neither this launch's running call nor its stray copy is touched", 2, liveFiles(directory).size)
        assertEquals(listOf(hungUp), recorder.list().map { it.id })
        recorder.finish(running)
    }

    /** Finding 2: a save that fails (locked storage) keeps the in-progress file for the next
     * launch, as does a read refused for data protection. */
    @Test fun failedSaveOrProtectedReadKeepsTheInProgressFile() = runBlocking {
        val directory = temporaryDirectory()
        val id = leaveInterruptedCall(directory)

        val uploads = Uploads()
        val locked = makeRecorder(directory, uploads = uploads, readFile = { throw AccessDeniedException(it.path) })
        assertTrue(locked.recoverInterrupted().isEmpty())
        assertEquals(1, liveFiles(directory).size)

        val refusing = makeRecorder(directory, uploads = uploads, failSavedWrites = AtomicBoolean(true))
        assertTrue(refusing.recoverInterrupted().isEmpty())
        assertEquals(1, liveFiles(directory).size)
        assertTrue(uploads.all.isEmpty())

        val unlocked = makeRecorder(directory, uploads = uploads)
        assertEquals(listOf(id), unlocked.recoverInterrupted().map { it.id })
        assertTrue(liveFiles(directory).isEmpty())
        assertEquals(listOf(id), uploads.all)
    }

    @Test fun liveCopiesAreWrittenAtMostEveryTenSeconds() {
        // A long call's log re-encodes whole on every write; background and hang-up still write at once.
        assertEquals(10.0, VoiceCallLogRecorder.liveWriteInterval, 0.0)
    }

    @Test fun protectedDataErrorsAreRecognised() {
        assertTrue(VoiceCallLogRecorder.isProtectedData(AccessDeniedException("/data/InProgress/a.json")))
        assertTrue(VoiceCallLogRecorder.isProtectedData(IOException("open failed: EPERM (Operation not permitted)")))
        assertFalse(VoiceCallLogRecorder.isProtectedData(IOException("unexpected end of stream")))
    }

    /** Finding 3: launch reads nothing when there's nothing to recover, and a leftover whose
     * log was already saved is deleted unread. */
    @Test fun launchReadsNothingItDoesNotNeed() = runBlocking {
        val directory = temporaryDirectory()
        val reads = AtomicInteger(0)
        val counting: (File) -> ByteArray = { reads.incrementAndGet(); it.readBytes() }

        assertTrue(makeRecorder(directory, readFile = counting).recoverInterrupted().isEmpty())
        assertEquals(0, reads.get())

        val id = leaveInterruptedCall(directory)
        val leftover = liveFiles(directory).first()
        File(directory, "$id.json").writeBytes(leftover.readBytes())
        val uploads = Uploads()
        assertTrue(makeRecorder(directory, uploads = uploads, readFile = counting).recoverInterrupted().isEmpty())
        assertEquals(0, reads.get())
        assertTrue(liveFiles(directory).isEmpty())
        assertTrue(uploads.all.isEmpty())
    }

    /** Finding 4: a new call saves one that was torn down without hanging up. */
    @Test fun beginSavesAStrandedCall() {
        val directory = temporaryDirectory()
        val uploads = Uploads()
        val recorder = makeRecorder(directory, uploads = uploads)
        val stranded = UUID.randomUUID()
        recorder.begin(stranded, title = "Voice lab", engine = "gptLive")
        recorder.note(stranded, VoiceCallLog.Note.Kind.marker, "torn down")
        val next = UUID.randomUUID()
        recorder.begin(next, title = "Voice lab", engine = "gptLive")
        recorder.waitForLiveWrites()
        uploads.waitFor(1)

        assertEquals(listOf(stranded), recorder.list().map { it.id })
        assertNotNull(recorder.list().first().endedAt)
        assertEquals(listOf(next.toString()), liveFiles(directory).map { it.nameWithoutExtension })
        assertEquals(listOf(stranded), uploads.all)
        recorder.finish(next)
    }
}
