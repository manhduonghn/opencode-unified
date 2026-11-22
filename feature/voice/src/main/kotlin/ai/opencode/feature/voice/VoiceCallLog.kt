package ai.opencode.feature.voice

import ai.opencode.core.dependencies.*
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.InstantSerializer
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.server.retry.NetworkPathMonitor
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.time.Instant
import java.util.UUID
import kotlin.math.max

object VoiceLogUUIDSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("VoiceLogUUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder) = UUID.fromString(decoder.decodeString())
}

/** Four call timeline tracks plus notes. Raw speech stays in private app storage;
 * only an explicit snapshot attachment is redacted and bounded. Never AppLog it. */
@Serializable
data class VoiceCallLog(
    @Serializable(with = VoiceLogUUIDSerializer::class) val id: UUID,
    @Serializable(with = InstantSerializer::class) val startedAt: Instant,
    @Serializable(with = InstantSerializer::class) val endedAt: Instant? = null,
    val title: String, val engine: String,
    val spans: List<Span> = emptyList(), val notes: List<Note> = emptyList(),
) {
    @Serializable enum class Track { user, voice, delegator, tool }
    @Serializable data class Span(val id: String, val track: Track, val start: Double, val end: Double? = null,
        val label: String, val detail: String? = null, val failed: Boolean = false)
    @Serializable data class Note(val id: Int, val t: Double, val kind: Kind, val text: String) {
        @Serializable enum class Kind { marker, received, injected, audio }
    }
    val duration get() = maxOf(endedAt?.let { seconds(startedAt, it) } ?: 0.0,
        spans.maxOfOrNull { it.end ?: it.start } ?: 0.0, notes.maxOfOrNull { it.t } ?: 0.0)
    fun trimmed(at: Instant = dependency(DateClient).now(), window: Double = 600.0, maxFieldBytes: Int = 4000): VoiceCallLog {
        val nowT = max(duration, seconds(startedAt, at)); val cutoff = max(0.0, nowT - window)
        return copy(spans = spans.filter { (it.end ?: nowT) >= cutoff }.map { it.copy(label = capUTF8(it.label, maxFieldBytes), detail = it.detail?.let { d -> capUTF8(d, maxFieldBytes) }) },
            notes = notes.filter { it.t >= cutoff }.map { it.copy(text = capUTF8(it.text, maxFieldBytes)) })
    }
    fun redacted() = copy(title = redactText(title), spans = spans.map { it.copy(label = redactText(it.label), detail = it.detail?.let(::redactText)) },
        notes = notes.map { it.copy(text = redactText(it.text)) })
    fun boundedJSON(maxBytes: Int = 512000): ByteArray {
        var current = this; var data = json.encodeToString(current).toByteArray()
        while (data.size > maxBytes && (current.spans.isNotEmpty() || current.notes.isNotEmpty())) {
            current = current.copy(spans = current.spans.drop(max(1, current.spans.size / 4)), notes = current.notes.drop(max(1, current.notes.size / 4)))
            data = json.encodeToString(current).toByteArray()
        }
        return data
    }
    companion object {
        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        internal fun seconds(start: Instant, end: Instant) = java.time.Duration.between(start, end).toNanos() / 1e9
        private fun capUTF8(text: String, maxBytes: Int): String {
            val bytes = text.toByteArray()
            return if (bytes.size <= maxBytes) text else bytes.copyOf(maxBytes.coerceAtLeast(0)).toString(Charsets.UTF_8) + "…"
        }
        private val redactionRules = listOf(
            Regex("sk-[A-Za-z0-9_-]{8,}") to "sk-<redacted>",
            Regex("(?i)(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+") to "$1 <redacted>",
            Regex("\\b[0-9]{10,}\\.[A-Za-z0-9_-]{20,}\\b") to "<redacted-token>",
            Regex("((?:https?|wss?)://)[^/\\s?#@\"'`]+@") to "$1<redacted>@",
            Regex("(/auth/connect/)[^/\\s?#@\"'`]+") to "$1<redacted>",
            Regex("(?i)([?&](?:token|key|api_key|password)=)[^&\\s#\"'`]+") to "$1<redacted>",
        )
        fun redactText(text: String) = redactionRules.fold(text) { result, (regex, replacement) -> regex.replace(result, replacement) }
    }
}

/** :app/devtools installs upload when Developer Mode is enabled; no upward edge. */
class VoiceCallLogUploadClient(val upload: suspend (VoiceCallLog) -> Boolean = { false }) {
    /** :app passes `this[VoiceCallLogUploadClient] = VoiceCallLogUploadClient { log -> … }`
     * to DependencyValues.installRoot before resolving VoiceCallLogClient. The suspend
     * callback uploads the completed log through Developer Mode's diagnostics uploader,
     * honoring its transcript/privacy settings, and says whether it went up. The recorder
     * gates automatic uploads on Developer Mode; the adapter must not put log contents or
     * upload URLs in AppLog. */
    companion object Key : DependencyKey<VoiceCallLogUploadClient> {
        override val liveValue = VoiceCallLogUploadClient()
        override val testValue = VoiceCallLogUploadClient()
    }
}

class VoiceCallLogRecorder(
    private val directory: File? = null,
    private val date: DateClient = dependency(DateClient),
    private val upload: VoiceCallLogUploadClient = dependency(VoiceCallLogUploadClient),
    /** Seconds between in-progress writes of an open call. */
    private val liveInterval: Double = liveWriteInterval,
    private val watchesNetwork: Boolean = true,
    private val isDeveloperMode: () -> Boolean = { shared(SharedKeys.developerMode).value },
    private val writeFile: (ByteArray, File) -> Unit = ::writeAtomically,
    private val readFile: (File) -> ByteArray = { it.readBytes() },
) {
    private data class Open(var log: VoiceCallLog, var nextNote: Int = 0, var openVoiceSpan: String? = null)
    /** Monotonic nanos of the last in-progress write, and whether a trailing one is queued. */
    private data class Throttle(var lastWrite: Long? = null, var pending: Boolean = false)
    private val lock = Any()
    private val diskLock = Any()
    private val open = linkedMapOf<UUID, Open>()
    /** Every call begun in this process. Launch recovery never touches their in-progress
     * files, even after hang-up removes them from `open`. */
    private val startedHere = mutableSetOf<UUID>()
    private var recoveryRan = false
    private val throttles = mutableMapOf<UUID, Throttle>()
    private val networkWatches = mutableMapOf<UUID, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saving = mutableMapOf<UUID, VoiceCallLog>()
    /**
     * Serial: live writes, the save and delete after a hang-up, and launch recovery's claim never
     * overtake each other.
     */
    private val liveQueue: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "voice-call-log-live").apply { isDaemon = true }
    }

    fun begin(id: UUID, title: String, engine: String, at: Instant = date.now()) {
        // A call torn down without hanging up (its window closed) is saved now.
        val stale = synchronized(lock) { open.keys.filter { it != id } }
        for (other in stale) finish(other, at)
        synchronized(lock) {
            startedHere += id
            open[id] = Open(VoiceCallLog(id, at, title = title, engine = engine))
        }
        if (watchesNetwork) watchNetwork(id)
        markDirty(id)
    }
    val hasOpenCalls: Boolean get() = synchronized(lock) { open.isNotEmpty() }
    /** Notes the network's changes during the call. Reads the app's one path monitor
     * ([NetworkPathMonitor]). */
    private fun watchNetwork(id: UUID) {
        val monitor = NetworkPathMonitor.shared ?: return
        stopWatchingNetwork(id)
        val updates = monitor.updates()
        val watch = scope.launch {
            var last: String? = null
            updates.collect { snapshot ->
                val text = describe(snapshot)
                if (text == last) return@collect
                last = text
                note(id, VoiceCallLog.Note.Kind.audio, "Network: $text")
            }
        }
        val previous = synchronized(lock) {
            val old = networkWatches[id]
            networkWatches[id] = watch
            old
        }
        previous?.cancel()
    }
    private fun stopWatchingNetwork(id: UUID) {
        val watch = synchronized(lock) { networkWatches.remove(id) } ?: return
        watch.cancel()
    }
    fun span(id: UUID, track: VoiceCallLog.Track, spanID: String, label: String? = null, detail: String? = null,
        end: Boolean = false, failed: Boolean = false, at: Instant = date.now()) {
        val changed = synchronized(lock) {
            val call = open[id] ?: return@synchronized false
            val t = VoiceCallLog.seconds(call.log.startedAt, at); val spans = call.log.spans.toMutableList(); val index = spans.indexOfFirst { it.id == spanID }
            if (index >= 0) {
                val old = spans[index]; spans[index] = old.copy(label = label ?: old.label, detail = detail ?: old.detail,
                    end = if (end) max(t, old.start) else old.end, failed = failed || old.failed)
            } else spans.add(VoiceCallLog.Span(spanID, track, t, if (end) t else null, label ?: "", detail, failed))
            if (track == VoiceCallLog.Track.voice) call.openVoiceSpan = if (end) null else spanID
            call.log = call.log.copy(spans = spans)
            true
        }
        if (changed) markDirty(id)
    }
    fun endVoice(id: UUID, label: String?, at: Instant = date.now()) {
        val spanID = synchronized(lock) { open[id]?.openVoiceSpan } ?: return
        span(id, VoiceCallLog.Track.voice, spanID, label, end = true, at = at)
    }
    fun note(id: UUID, kind: VoiceCallLog.Note.Kind, text: String, at: Instant = date.now()) {
        val changed = synchronized(lock) {
            val call = open[id] ?: return@synchronized false
            call.log = call.log.copy(notes = call.log.notes + VoiceCallLog.Note(call.nextNote++, VoiceCallLog.seconds(call.log.startedAt, at), kind, text))
            true
        }
        if (changed) markDirty(id)
    }
    fun noteCurrent(kind: VoiceCallLog.Note.Kind, text: String, at: Instant = date.now()) {
        val id = synchronized(lock) { open.keys.firstOrNull() } ?: return
        note(id, kind, text, at)
    }
    fun setTitle(id: UUID, title: String) {
        val changed = synchronized(lock) { open[id]?.let { it.log = it.log.copy(title = title) } != null }
        if (changed) markDirty(id)
    }
    fun setEngine(id: UUID, engine: String) {
        val changed = synchronized(lock) { open[id]?.let { it.log = it.log.copy(engine = engine) } != null }
        if (changed) markDirty(id)
    }
    fun snapshot(id: UUID, at: Instant = date.now(), window: Double = 600.0): VoiceCallLog? {
        val raw = synchronized(lock) { open[id]?.log ?: saving[id] } ?: list().firstOrNull { it.id == id }
        return raw?.trimmed(at, window)?.redacted()
    }
    /**
     * Closes everything still open and saves the call. The reducer calls this on the main thread,
     * so the save runs on [liveQueue]; [list] shows the log meanwhile.
     */
    fun finish(id: UUID, at: Instant = date.now()) {
        stopWatchingNetwork(id)
        val original = synchronized(lock) {
            throttles.remove(id)
            open.remove(id)?.log
        }
        val t = original?.let { VoiceCallLog.seconds(it.startedAt, at) }
        val log = original?.copy(endedAt = at, spans = original.spans.map { if (it.end == null) it.copy(end = max(t!!, it.start)) else it })
            ?.takeIf { it.spans.isNotEmpty() || it.notes.isNotEmpty() }
        if (log == null) {
            removeLive(id)
            return
        }
        synchronized(lock) { saving[id] = log }
        val shouldUpload = directory != null && isDeveloperMode()
        liveQueue.execute {
            try { save(log) } finally { synchronized(lock) { saving.remove(id) } }
            liveFile(id)?.let(::removeFile)
            if (shouldUpload) scope.launch { upload.upload(log) }
        }
    }

    // In-progress copy

    /** A call's log is also kept in `InProgress/` while it runs, so an app update or crash
     * mid-call doesn't lose it. Hang-up deletes it. */
    private val liveDirectory: File? get() = directory?.let { File(it, "InProgress") }
    private fun liveFile(id: UUID): File? = liveDirectory?.let { File(it, "$id.json") }
    private fun savedFile(id: UUID): File? = directory?.let { File(it, "$id.json") }

    /** Writes the open call's log at most every [liveInterval] seconds; a change inside the
     * window schedules one trailing write. */
    private fun markDirty(id: UUID) {
        if (directory == null) return
        val now = System.nanoTime()
        // null: skip; 0: write now; else the nanos to wait.
        val wait = synchronized(lock) {
            val state = throttles.getOrPut(id) { Throttle() }
            if (state.pending) return@synchronized null
            val wait = state.lastWrite?.let { (liveInterval * 1e9).toLong() - (now - it) } ?: 0L
            if (wait <= 0) {
                state.lastWrite = now
                0L
            } else {
                state.pending = true
                wait
            }
        } ?: return
        if (wait == 0L) {
            liveQueue.execute { writeLive(id) }
        } else {
            liveQueue.schedule({
                synchronized(lock) { throttles[id]?.let { it.pending = false; it.lastWrite = System.nanoTime() } }
                writeLive(id)
            }, wait, TimeUnit.NANOSECONDS)
        }
    }

    /** Runs on [liveQueue], reading the log at write time: after a hang-up it finds nothing
     * and writes nothing. */
    private fun writeLive(id: UUID) {
        val dir = liveDirectory ?: return
        val file = liveFile(id) ?: return
        val log = synchronized(lock) { open[id]?.log } ?: return
        try {
            dir.mkdirs()
            writeFile(VoiceCallLog.json.encodeToString(log).toByteArray(), file)
        } catch (error: Exception) {
            AppLog.voice.error("Couldn’t save the in-progress call log (${error.javaClass.simpleName})")
        }
    }

    /** Writes every open call now (the app is going to the background) and returns once
     * they're on disk. */
    suspend fun persistOpenCalls() {
        if (directory == null) return
        val ids = synchronized(lock) { open.keys.toList() }
        if (ids.isEmpty()) return
        synchronized(lock) {
            val now = System.nanoTime()
            for (id in ids) throttles.getOrPut(id) { Throttle() }.lastWrite = now
        }
        val written = CompletableDeferred<Unit>()
        liveQueue.execute {
            for (id in ids) writeLive(id)
            written.complete(Unit)
        }
        written.await()
    }

    /** Blocks until queued live writes are done. */
    fun waitForLiveWrites() {
        liveQueue.submit {}.get()
    }

    private fun removeLive(id: UUID) {
        val file = liveFile(id) ?: return
        liveQueue.execute { removeFile(file) }
    }

    /** At launch, once: saves each call log a previous run left in progress as interrupted,
     * and uploads it once when Developer Mode is on. Returns the recovered logs. */
    suspend fun recoverInterrupted(): List<VoiceCallLog> {
        val first = synchronized(lock) { (!recoveryRan).also { recoveryRan = true } }
        if (!first || directory == null) return emptyList()
        val claimed = CompletableDeferred<List<VoiceCallLog>>()
        liveQueue.execute { claimed.complete(runCatching { claimInterrupted() }.getOrDefault(emptyList())) }
        val recovered = claimed.await()
        if (recovered.isEmpty()) return emptyList()
        var uploaded = 0
        if (isDeveloperMode()) for (log in recovered) if (upload.upload(log)) uploaded += 1
        AppLog.voice.notice("Recovered ${recovered.size} interrupted call logs, uploaded $uploaded")
        return recovered
    }

    /** Runs on [liveQueue]. Leaves alone any call begun in this process, and keeps a file it
     * couldn't read (storage still locked) or save for the next launch. */
    private fun claimInterrupted(): List<VoiceCallLog> {
        val liveDir = liveDirectory ?: return emptyList()
        val candidates = liveDir.listFiles()?.filter { it.extension == "json" } ?: return emptyList()
        if (candidates.isEmpty()) return emptyList()
        val recovered = mutableListOf<VoiceCallLog>()
        var kept = 0
        for (file in candidates) {
            val id = runCatching { UUID.fromString(file.nameWithoutExtension) }.getOrNull()
            if (id == null) {
                removeFile(file)
                continue
            }
            if (synchronized(lock) { id in startedHere }) continue
            if (savedFile(id)?.exists() == true) {
                removeFile(file)
                continue
            }
            val data = try {
                readFile(file)
            } catch (error: Exception) {
                if (isProtectedData(error)) {
                    kept += 1
                } else {
                    AppLog.voice.error("Couldn’t read an in-progress call log (${error.javaClass.simpleName})")
                    removeFile(file)
                }
                continue
            }
            val decoded = runCatching { VoiceCallLog.json.decodeFromString<VoiceCallLog>(data.decodeToString()) }.getOrNull()
            if (decoded == null || decoded.id != id || (decoded.spans.isEmpty() && decoded.notes.isEmpty())) {
                removeFile(file)
                continue
            }
            val last = decoded.duration
            val log = decoded.copy(
                endedAt = decoded.startedAt.plusNanos((last * 1e9).toLong()),
                spans = decoded.spans.map { if (it.end == null) it.copy(end = max(last, it.start)) else it },
                notes = decoded.notes + VoiceCallLog.Note(
                    (decoded.notes.maxOfOrNull { it.id } ?: -1) + 1, last, VoiceCallLog.Note.Kind.marker, interruptedNote,
                ),
            )
            if (!save(log)) {
                kept += 1
                continue
            }
            removeFile(file)
            recovered += log
        }
        if (kept > 0) AppLog.voice.notice("Kept $kept in-progress call logs for the next launch")
        return recovered
    }

    // Storage

    private fun save(log: VoiceCallLog): Boolean = synchronized(diskLock) {
        val dir = directory ?: return@synchronized false
        val file = savedFile(log.id) ?: return@synchronized false
        try {
            dir.mkdirs()
            writeFile(VoiceCallLog.json.encodeToString(log).toByteArray(), file)
        } catch (error: Exception) {
            AppLog.voice.error("Couldn’t save a call log (${error.javaClass.simpleName})")
            return@synchronized false
        }
        // Oldest go first, decoded only once there are too many.
        val count = dir.listFiles()?.count { it.extension == "json" } ?: 0
        if (count > keep) for (old in savedLogs().drop(keep)) deleteSaved(old.id)
        true
    }
    /** Disk I/O: callers must invoke off the main thread (the reducer uses an effect). */
    /** Saved logs, including one whose save is still writing. */
    fun list(): List<VoiceCallLog> {
        if (directory == null) return emptyList()
        val pending = synchronized(lock) { saving.values.toList() }
        val saved = savedLogs()
        return (pending.filter { log -> saved.none { it.id == log.id } } + saved).sortedByDescending { it.startedAt }
    }
    private fun savedLogs(): List<VoiceCallLog> = synchronized(diskLock) {
        directory?.listFiles()?.filter { it.extension == "json" }?.mapNotNull { runCatching { VoiceCallLog.json.decodeFromString<VoiceCallLog>(it.readText()) }.getOrNull() }
            ?.sortedByDescending { it.startedAt } ?: emptyList()
    }
    /**
     * Runs on [liveQueue], after any save a hang-up queued: a log deleted just after its call
     * ended would otherwise be written back by that save. Blocks; call it off the main thread.
     */
    fun delete(id: UUID) {
        if (directory == null) return
        liveQueue.submit { deleteSaved(id) }.get()
    }
    private fun deleteSaved(id: UUID) {
        synchronized(diskLock) { directory?.let { File(it, "$id.json").delete() } }
    }
    fun deleteAll() { list().forEach { delete(it.id) } }
    companion object {
        const val keep = 40
        /** Each write re-encodes the whole growing log; backgrounding and hang-up write at once,
         * so this only bounds what a crash can lose. */
        const val liveWriteInterval = 10.0
        const val interruptedNote = "⚠︎ Call interrupted: the app quit before hang-up"

        /**
         * The default write: a sibling temp file renamed over the target. Credential-encrypted
         * storage: readable after the first unlock.
         */
        fun writeAtomically(data: ByteArray, file: File) {
            val temp = File(file.parentFile, "${file.name}.pending")
            temp.writeBytes(data)
            if (!temp.renameTo(file)) {
                temp.delete()
                throw IOException("rename failed")
            }
        }

        private fun removeFile(file: File) {
            if (!file.exists()) return
            if (!file.delete()) AppLog.voice.error("Couldn’t delete a call log file")
        }

        /** A read refused because the storage is locked. */
        fun isProtectedData(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(4).any { candidate ->
            candidate is AccessDeniedException ||
                (candidate is IOException && candidate.message?.let { message ->
                    listOf("EACCES", "EPERM", "Permission denied", "Operation not permitted").any { it in message }
                } == true)
        }

        fun describe(path: NetworkPathMonitor.Snapshot): String {
            if (!path.isSatisfied) {
                return if (path.status == NetworkPathMonitor.Snapshot.Status.RequiresConnection) "waiting for a connection" else "no connection"
            }
            val kind = when (path.interfaces.firstOrNull()?.type) {
                "wifi" -> "Wi-Fi"
                "cellular" -> "cellular"
                "wiredEthernet" -> "wired"
                else -> "other"
            }
            val parts = mutableListOf(kind)
            if (path.routeSignature.size > 1) parts.add("VPN")
            if (path.isExpensive) parts.add("expensive")
            if (path.isConstrained) parts.add("Low Data Mode")
            return parts.joinToString(" · ")
        }
    }
}
class VoiceCallLogClient(val recorder: VoiceCallLogRecorder? = null) {
    fun noteCurrent(kind: VoiceCallLog.Note.Kind, text: String) { recorder?.noteCurrent(kind, text) }
    fun noteCurrent(kind: VoiceCallLog.Note.Kind, text: String, at: Instant) { recorder?.noteCurrent(kind, text, at) }
    fun list() = recorder?.list() ?: emptyList()
    fun finish(id: UUID) { recorder?.finish(id) }
    /** Saves the open call's log once the app leaves the foreground, under a background task
     * so the write completes. */
    suspend fun persistOpenCalls() {
        val recorder = recorder ?: return
        if (!recorder.hasOpenCalls) return
        val end = dependency(BackgroundTaskClient).begin("Save call log")
        try { recorder.persistOpenCalls() } finally { end() }
    }
    suspend fun recoverInterrupted() { recorder?.recoverInterrupted() }
    fun delete(id: UUID) { recorder?.delete(id) }
    fun deleteAll() { recorder?.deleteAll() }
    companion object Key : DependencyKey<VoiceCallLogClient> {
        override val liveValue by lazy { val context = dependency(AppContext); VoiceCallLogClient(VoiceCallLogRecorder(File(context.noBackupFilesDir, "VoiceCallLogs"))) }
        override val testValue = VoiceCallLogClient()
    }
}
