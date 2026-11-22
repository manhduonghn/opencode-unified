package ai.opencode.core.diagnostics

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Callable
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The timeline on disk, a JSON-lines file per run plus one older segment, written in batches at
 * most [WRITE_INTERVAL_MS] apart. A foreground marker (ON_RESUME/ON_STOP) tells whether the last
 * run ended in the foreground.
 */
class DiagnosticsJournal(
    val directory: File,
    val segmentBytes: Int = 768 * 1_024,
    /** "version (build)", written into the foreground marker. */
    val build: String,
) {
    data class PreviousRun(
        /** The run ended in the foreground without backgrounding first: a crash, an ANR kill, or a debugger stop. */
        val endedUnexpectedly: Boolean,
    )

    private val queue: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { Thread(it, "opencode.diagnostics.journal").apply { isDaemon = true } }

    // Confined to `queue`.
    private var stream: FileOutputStream? = null
    private var bytes = 0L
    private var isRecording = false

    // Lines waiting for the next write, confined to `queue`.
    private var pending = ByteArrayOutputStream()

    // A write is already scheduled on `queue`.
    private var writeScheduled = false

    // Batches written to disk, for tests: events are batched, not one per write.
    private var batches = 0

    private val current get() = File(directory, "current.jsonl")
    private val older get() = File(directory, "current.1.jsonl")
    private val previous get() = listOf(File(directory, "previous.1.jsonl"), File(directory, "previous.jsonl"))
    private val marker get() = File(directory, "foreground")

    private fun <T> sync(block: () -> T): T = queue.submit(Callable { block() }).get()

    private fun async(block: () -> Unit) {
        queue.execute(block)
    }

    /** Sets the previous run aside and starts this one. Call once, before appending. */
    fun open(recording: Boolean): PreviousRun {
        val markedBuild = runCatching { marker.readText() }.getOrNull()
        val hadEvents = older.exists() || current.exists()
        async {
            directory.mkdirs()
            marker.delete()
            if (hadEvents) {
                for ((source, destination) in listOf(older, current).zip(previous)) {
                    destination.delete()
                    source.renameTo(destination)
                }
            }
            isRecording = recording
        }
        return PreviousRun(endedUnexpectedly = markedBuild == build && hadEvents)
    }

    fun append(event: DiagnosticsEvent) = async {
        isRecording = true
        val line = runCatching { (json.encodeToString(DiagnosticsEvent.serializer(), event) + "\n").toByteArray() }.getOrNull() ?: return@async
        // A segment never takes a batch it can't hold: what's waiting goes first.
        if (pending.size() + line.size > segmentBytes) write()
        pending.write(line)
        if (pending.size() >= BATCH_BYTES) {
            write()
        } else if (!writeScheduled) {
            writeScheduled = true
            val delayedWrite = Runnable {
                writeScheduled = false
                write()
            }
            queue.schedule(delayedWrite, WRITE_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** Writes what's waiting, rotating first if the segment has no room. Runs on `queue`. */
    private fun write() {
        if (pending.size() == 0) return
        val batch = pending.toByteArray()
        pending = ByteArrayOutputStream()
        if (bytes + batch.size > segmentBytes && stream != null) rotate()
        val out = stream ?: openCurrent() ?: return
        try {
            out.write(batch)
            out.flush()
            bytes += batch.size
            batches += 1
        } catch (_: Exception) {
            runCatching { out.close() }
            stream = null
        }
    }

    /** Forgets this run and the previous one. */
    fun clear() = async {
        runCatching { stream?.close() }
        stream = null
        bytes = 0
        pending = ByteArrayOutputStream()
        isRecording = false
        (listOf(current, older) + previous).forEach { it.delete() }
    }

    /** Rewrites every segment without voice excerpts: Include Transcripts was turned off. */
    fun removeExcerpts() = async {
        write()
        runCatching { stream?.close() }
        stream = null
        for (file in listOf(older, current) + previous) {
            val events = events(file)
            if (events.none { it.excerpt != null }) continue
            val text = buildString {
                for (event in events) {
                    val line = runCatching { json.encodeToString(DiagnosticsEvent.serializer(), event.copy(excerpt = null)) }.getOrNull() ?: continue
                    append(line).append('\n')
                }
            }
            val temporary = File(directory, file.name + ".tmp")
            runCatching {
                temporary.writeText(text)
                temporary.renameTo(file)
            }
        }
        bytes = 0
    }

    /** The previous run's events, oldest first. Reads up to two segments, so call it when building a report, not at launch. */
    fun previousRunEvents(): List<DiagnosticsEvent> = sync { previous.flatMap(::events) }

    /** This run's events as written, oldest first. */
    fun currentRunEvents(): List<DiagnosticsEvent> = sync {
        write()
        runCatching { stream?.fd?.sync() }
        listOf(older, current).flatMap(::events)
    }

    /** Writes what's waiting; tests and teardown use it. */
    fun flush() = sync { write() }

    /** Batches written to disk, for tests: events are batched, not one per write. */
    val writeBatches: Int get() = sync { batches }

    fun markForeground() = async {
        if (!isRecording) return@async
        runCatching {
            directory.mkdirs()
            marker.writeText(build)
        }
    }

    /** `waits` only where an async removal might never run. */
    fun markBackground(waits: Boolean = false) {
        val leave = {
            // The next event may never come: what's waiting goes now.
            write()
            marker.delete()
            Unit
        }
        if (waits) sync(leave) else async(leave)
    }

    /** Follows the app's foreground state for the unclean-exit marker. */
    fun followApplicationState() {
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) = markForeground()
                override fun onStop(owner: LifecycleOwner) = markBackground()
            })
        }
    }

    private fun rotate() {
        runCatching { stream?.close() }
        stream = null
        bytes = 0
        older.delete()
        current.renameTo(older)
    }

    private fun openCurrent(): FileOutputStream? {
        directory.mkdirs()
        val opened = runCatching { FileOutputStream(current, true) }.getOrNull() ?: return null
        bytes = current.length()
        stream = opened
        return opened
    }

    companion object {
        /** How long an event waits for the write that carries it. */
        const val WRITE_INTERVAL_MS = 250L

        /** A batch this large is written at once, whatever the interval. */
        const val BATCH_BYTES = 64 * 1_024

        private val json = Json { encodeDefaults = false; explicitNulls = false; ignoreUnknownKeys = true }

        /** `noBackupFilesDir/Diagnostics`, following the app's foreground state. */
        fun live(context: Context): DiagnosticsJournal {
            val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
            val journal = DiagnosticsJournal(
                directory = File(context.noBackupFilesDir, "Diagnostics"),
                build = "${info?.versionName ?: "?"} (${info?.longVersionCode ?: "?"})",
            )
            journal.followApplicationState()
            return journal
        }

        /** A torn last line from a crash is skipped, not fatal. */
        private fun events(file: File): List<DiagnosticsEvent> {
            if (!file.exists()) return emptyList()
            return runCatching { file.readLines() }.getOrDefault(emptyList()).mapNotNull { line ->
                if (line.isBlank()) null else runCatching { json.decodeFromString(DiagnosticsEvent.serializer(), line) }.getOrNull()
            }
        }
    }
}
