package ai.opencode.feature.composer

import ai.opencode.server.model.OpenCodeOutgoingAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class ComposerAttachmentPersistenceTests {
    data class Counts(val writeAttempts: List<UUID> = emptyList(), val scans: Int = 0, val manifests: Int = 0)

    enum class Point { Write, Scan }

    class Hold(val point: Point) {
        private val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)

        fun waitUntilEntered() {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
        }

        fun letGo() = release.countDown()

        fun pause() {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
    }

    class CountingDisk {
        private val lock = Any()
        private val memory = HashMap<UUID, ByteArray>()
        private var manifest: ByteArray? = null
        private var hold: Hold? = null
        var counts = Counts()
            get() = synchronized(lock) { field }
            private set
        @Volatile var failWrites = false
        @Volatile var failManifest = false

        val blobs: Map<UUID, ByteArray> get() = synchronized(lock) { HashMap(memory) }

        fun holdNext(point: Point): Hold = Hold(point).also { synchronized(lock) { hold = it } }

        fun resetCounts() = synchronized(lock) { counts = Counts() }

        private fun pause(point: Point) {
            val held = synchronized(lock) {
                val current = hold
                if (current == null || current.point != point) return@synchronized null
                hold = null
                current
            }
            held?.pause()
        }

        val storage = ComposerPersistenceStorage(
            identity = "counting",
            readStateData = { synchronized(lock) { manifest } },
            writeStateData = { data ->
                synchronized(lock) { counts = counts.copy(manifests = counts.manifests + 1) }
                if (failManifest) {
                    false
                } else {
                    synchronized(lock) { manifest = data }
                    true
                }
            },
            readAttachment = { id -> synchronized(lock) { memory[id] } },
            writeAttachment = { id, data ->
                synchronized(lock) { counts = counts.copy(writeAttempts = counts.writeAttempts + id) }
                pause(Point.Write)
                if (failWrites) {
                    false
                } else {
                    synchronized(lock) { memory[id] = data }
                    true
                }
            },
            removeAttachments = { keeping ->
                synchronized(lock) { counts = counts.copy(scans = counts.scans + 1) }
                pause(Point.Scan)
                synchronized(lock) { memory.keys.retainAll(keeping) }
            },
            removeAll = {
                synchronized(lock) {
                    memory.clear()
                    manifest = null
                }
            },
        )

        fun launch(): ComposerPersistence = ComposerPersistence.make(storage = storage, debounce = 60.seconds)
    }

    private val serverID = "srv"
    private val sessionID = "ses_1"

    private fun attachment(byte: Int, size: Int = 512 * 1024) =
        OpenCodeOutgoingAttachment(data = ByteArray(size) { byte.toByte() }, mime = "image/jpeg")

    private fun pending(messageID: String, attachments: List<OpenCodeOutgoingAttachment>) =
        PendingPrompt(messageID = messageID, text = "Look", attachments = attachments, requestEpoch = 1)

    private val OpenCodeOutgoingAttachment.uuid: UUID get() = UUID.fromString(id)

    private fun assertBytes(expected: List<OpenCodeOutgoingAttachment>, actual: List<OpenCodeOutgoingAttachment>?) {
        assertEquals(expected.size, actual?.size)
        for (index in expected.indices) assertArrayEquals(expected[index].data, actual!![index].data)
    }

    @Test fun typingWritesEachAttachmentOnceAndNeverScansTheDirectory() = runBlocking {
        val disk = CountingDisk()
        val persistence = disk.launch()
        val a = attachment(1)
        val b = attachment(2)

        persistence.scheduleDraft(serverID, sessionID, null, DraftState(text = "h", attachments = listOf(a, b)))
        persistence.flush()
        assertEquals(setOf(a.uuid, b.uuid), disk.counts.writeAttempts.toSet())
        assertEquals(2, disk.counts.writeAttempts.size)
        assertEquals(2, disk.blobs.size)

        disk.resetCounts()
        for (edit in 1..20) {
            persistence.scheduleDraft(serverID, sessionID, null, DraftState(text = "hello $edit", attachments = listOf(a, b)))
            persistence.flush()
        }
        assertEquals(Counts(writeAttempts = emptyList(), scans = 0, manifests = 20), disk.counts)

        val restored = disk.launch().loadDraft(serverID, sessionID)
        assertEquals("hello 20", restored?.draft?.text)
        assertBytes(listOf(a, b), restored?.draft?.attachments)
    }

    @Test fun bytesStayFromDraftToOutboxToFailedSendAndGoAtSettlement() = runBlocking {
        val disk = CountingDisk()
        val persistence = disk.launch()
        val photo = attachment(3, size = 2 * 1024 * 1024)

        persistence.scheduleDraft(serverID, sessionID, null, DraftState(text = "Look", attachments = listOf(photo)))
        persistence.flush()
        assertEquals(photo.data.size, persistence.retainedAttachmentBytes())

        val prompt = pending("msg_1", listOf(photo))
        persistence.clearDraft(serverID, sessionID, false)
        persistence.recordOutbox(serverID, sessionID, null, "/repo", "ses_new", null, prompt, false, null)
        persistence.flush()
        assertEquals(photo.data.size, persistence.retainedAttachmentBytes())
        assertArrayEquals(photo.data, disk.blobs[photo.uuid])

        persistence.markUndelivered(prompt, "Couldn’t send message", serverID, sessionID)
        persistence.flush()
        assertEquals(photo.data.size, persistence.retainedAttachmentBytes())
        assertEquals("Moving between draft, outbox and failed send never rewrites it", listOf(photo.uuid), disk.counts.writeAttempts)
        assertBytes(listOf(photo), disk.launch().loadUndelivered(serverID, sessionID).firstOrNull()?.pending?.attachments)

        persistence.settlePrompts(setOf("msg_1"))
        persistence.flush()
        assertEquals(0, persistence.retainedAttachmentBytes())
        assertTrue(disk.blobs.isEmpty())
    }

    @Test fun aFailedBlobWriteKeepsThePreviousStateUntilARetryLands() = runBlocking {
        val disk = CountingDisk()
        val persistence = disk.launch()
        val a = attachment(4)
        val b = attachment(10)
        persistence.scheduleDraft(serverID, sessionID, null, DraftState(text = "Before", attachments = listOf(a)))
        persistence.flush()

        disk.failWrites = true
        persistence.scheduleDraft(serverID, sessionID, null, DraftState(text = "After", attachments = listOf(a, b)))
        persistence.flush()
        assertNull(disk.blobs[b.uuid])
        assertEquals("The failed bytes are kept for the retry", a.data.size + b.data.size, persistence.retainedAttachmentBytes())

        val killed = disk.launch().loadDraft(serverID, sessionID)
        assertEquals("Before", killed?.draft?.text)
        assertBytes(listOf(a), killed?.draft?.attachments)

        disk.failWrites = false
        persistence.flush()
        assertEquals(listOf(a.uuid, b.uuid, b.uuid), disk.counts.writeAttempts)
        val restored = disk.launch().loadDraft(serverID, sessionID)
        assertEquals("After", restored?.draft?.text)
        assertBytes(listOf(a, b), restored?.draft?.attachments)
    }

    @Test fun oldBlobsStayUntilTheManifestWithoutThemLands() = runBlocking {
        val disk = CountingDisk()
        val persistence = disk.launch()
        val a = attachment(11)
        val b = attachment(12)
        persistence.scheduleDraft(serverID, sessionID, null, DraftState(text = "Look", attachments = listOf(a)))
        persistence.flush()

        disk.failManifest = true
        persistence.scheduleDraft(serverID, sessionID, null, DraftState(text = "Look", attachments = listOf(b)))
        persistence.flush()
        assertEquals("No clean after a manifest that didn't land", 1, disk.counts.scans)
        assertBytes(listOf(a), disk.launch().loadDraft(serverID, sessionID)?.draft?.attachments)

        disk.failManifest = false
        persistence.flush()
        assertEquals(setOf(b.uuid), disk.blobs.keys)
        assertBytes(listOf(b), disk.launch().loadDraft(serverID, sessionID)?.draft?.attachments)
    }

    @Test fun anAttachmentAddedWhileAFlushIsWritingIsWrittenNext() = runBlocking {
        val disk = CountingDisk()
        val persistence = disk.launch()
        val a = attachment(5)
        val b = attachment(6)

        persistence.scheduleDraft(serverID, sessionID, null, DraftState(text = "one", attachments = listOf(a)))
        val hold = disk.holdNext(Point.Write)
        val flushing = async(Dispatchers.IO) { persistence.flush() }
        hold.waitUntilEntered()
        persistence.scheduleDraft(serverID, sessionID, null, DraftState(text = "two", attachments = listOf(a, b)))
        hold.letGo()
        flushing.await()
        persistence.flush()

        assertEquals(listOf(a.uuid, b.uuid), disk.counts.writeAttempts)
        val restored = disk.launch().loadDraft(serverID, sessionID)
        assertEquals("two", restored?.draft?.text)
        assertBytes(listOf(a, b), restored?.draft?.attachments)
    }

    @Test fun anAttachmentPutBackWhileItsBlobIsCleanedAwayIsWrittenAgain() = runBlocking {
        val disk = CountingDisk()
        val persistence = disk.launch()
        val photo = attachment(7)

        persistence.scheduleDraft(serverID, sessionID, null, DraftState(text = "Look", attachments = listOf(photo)))
        persistence.flush()

        persistence.scheduleDraft(serverID, sessionID, null, DraftState(text = "Look"))
        val hold = disk.holdNext(Point.Scan)
        val flushing = async(Dispatchers.IO) { persistence.flush() }
        hold.waitUntilEntered()
        persistence.scheduleDraft(serverID, sessionID, null, DraftState(text = "Look", attachments = listOf(photo)))
        hold.letGo()
        flushing.await()
        assertTrue(disk.blobs.isEmpty())

        persistence.flush()
        assertArrayEquals(photo.data, disk.blobs[photo.uuid])
        assertBytes(listOf(photo), disk.launch().loadDraft(serverID, sessionID)?.draft?.attachments)
    }

    @Test fun aRelaunchCleansOrphansOnceAndKeepsBlobsAlreadyOnDisk() = runBlocking {
        val disk = CountingDisk()
        val photo = attachment(8)
        val first = disk.launch()
        first.scheduleDraft(serverID, sessionID, null, DraftState(text = "Look", attachments = listOf(photo)))
        first.flush()
        disk.storage.writeAttachment(UUID.randomUUID(), "left behind".toByteArray())
        disk.resetCounts()

        val relaunched = disk.launch()
        assertBytes(listOf(photo), relaunched.loadDraft(serverID, sessionID)?.draft?.attachments)
        for (edit in 1..5) {
            relaunched.scheduleDraft(serverID, sessionID, null, DraftState(text = "Look $edit", attachments = listOf(photo)))
            relaunched.flush()
        }
        assertEquals(Counts(writeAttempts = emptyList(), scans = 1, manifests = 5), disk.counts)
        assertEquals(setOf(photo.uuid), disk.blobs.keys)
    }

    @Test fun aDeliveredSendLetsGoOfItsBytesOnceItsNoLongerShown() {
        var clock = Instant.ofEpochSecond(1_000)
        val outbox = PromptOutbox(now = { clock })
        val photo = attachment(9)

        outbox.recordAdmitted(pending("msg_1", listOf(photo)), sessionID = sessionID, serverKey = serverID)
        assertEquals(photo.data.size, outbox.retainedAttachmentBytes)
        assertEquals(1, outbox.contents(serverKey = serverID, sessionID = sessionID).admitted.size)

        clock = clock.plusSeconds(PromptOutbox.ADMITTED_MEMORY_SECONDS.toLong() + 1)
        assertTrue(outbox.contents(serverKey = serverID, sessionID = sessionID).admitted.isEmpty())
        assertEquals(0, outbox.retainedAttachmentBytes)
    }
}
