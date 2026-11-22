package ai.opencode.feature.media

import ai.opencode.core.log.AppLog
import ai.opencode.server.OpenCodeServer
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/**
 * Serves agent files dragged out of the app: [register] hands out a `content://` URI at drag start,
 * and the file is downloaded through [AgentFileLoader] into its usual cache
 * (`AgentFileLoader.cacheURL`, shared with the viewer, revalidated with its validators) only when
 * the drop target opens or queries it. A failed read falls back to the decoded image the tile
 * already shows, as a PNG, when there is one. Pixels known only in memory (and those fallbacks) are
 * written under `AgentFiles/Drag/` and swept after an hour.
 */
class AgentFileDragProvider : FileProvider(R.xml.agent_file_drag_paths) {
    /** Where a dragged file's bytes come from. */
    sealed interface Source {
        data class Server(val server: OpenCodeServer, val request: Request, val absolutePath: String, val name: String) : Source
        data class Image(val name: String) : Source
    }

    private class Pending(
        val source: Source,
        val destination: File,
        val mime: String,
        val suggestedName: String,
        val cachedImage: Bitmap?,
    ) {
        val lock = Mutex()
        /** The bytes once read for this drag: one download serves every read of it. */
        @Volatile var ready: File? = null
    }

    override fun getType(uri: Uri): String? = pending(uri)?.mime ?: super.getType(uri)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val entry = pending(uri) ?: return super.query(uri, projection, selection, selectionArgs, sortOrder)
        val file = runCatching { resolve(entry) }.getOrNull()
        val columns = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
        val cursor = MatrixCursor(columns.toTypedArray(), 1)
        cursor.addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) entry.suggestedName else file?.length() })
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val entry = pending(uri) ?: return super.openFile(uri, mode)
        if (mode != "r") throw SecurityException("Dragged files are read-only")
        return ParcelFileDescriptor.open(resolve(entry), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** The file's bytes for [entry]: downloaded, or the cached image as a PNG. */
    private fun resolve(entry: Pending): File = runBlocking(Dispatchers.IO) {
        entry.lock.withLock {
            entry.ready?.takeIf { it.exists() }?.let { return@withLock it }
            val file = when (val source = entry.source) {
                is Source.Server -> try {
                    AgentFileLoader.download(source.request, entry.destination, source.server) { _, _ -> }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    AppLog.app.notice { "Agent file drag: read failed (${error.javaClass.simpleName})" }
                    entry.cachedImage?.let { writePNG(it, fallbackFile(entry)) } ?: throw FileNotFoundException("unreadable")
                }
                is Source.Image -> writePNG(entry.cachedImage ?: throw FileNotFoundException("no image"), entry.destination)
            }
            entry.ready = file
            file
        }
    }

    private fun fallbackFile(entry: Pending): File {
        val root = context?.cacheDir ?: throw FileNotFoundException("no context")
        return File(File(dragDirectory(root), UUID.randomUUID().toString().uppercase()), entry.suggestedName.substringBeforeLast('.') + ".png")
    }

    private fun writePNG(image: Bitmap, file: File): File {
        file.parentFile?.mkdirs()
        val written = runCatching { file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }.getOrDefault(false)
        if (!written) throw FileNotFoundException("unreadable")
        return file
    }

    private fun pending(uri: Uri): Pending? = synchronized(registry) { registry[uri.toString()] }

    companion object {
        /** Drags kept answerable at once; older URIs still serve whatever they cached. */
        private const val MAX_PENDING = 64
        private const val SWEEP_AFTER_MS = 60 * 60 * 1000L

        private val registry = object : LinkedHashMap<String, Pending>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pending>?): Boolean = size > MAX_PENDING
        }

        fun authority(context: Context): String = "${context.packageName}.agentfiles.drag"

        private fun dragDirectory(cacheDirectory: File) = File(File(cacheDirectory, "AgentFiles"), "Drag")

        /**
         * A `content://` URI for a dragged file whose bytes come from [source], read only when
         * the drop target opens it. [cachedImage] stands in when the read fails.
         */
        fun register(context: Context, source: Source, mime: String, suggestedName: String, cachedImage: Bitmap?): Uri {
            val cache = context.cacheDir
            sweep(cache)
            val destination = when (source) {
                is Source.Server -> AgentFileLoader.cacheURL(source.server, source.absolutePath, source.name, cache)
                is Source.Image -> File(File(dragDirectory(cache), UUID.randomUUID().toString().uppercase()), suggestedName)
            }
            val uri = FileProvider.getUriForFile(context, authority(context), destination)
            synchronized(registry) { registry[uri.toString()] = Pending(source, destination, mime, suggestedName, cachedImage) }
            return uri
        }

        /** In-memory pixels written for earlier drags, once no drop can still be reading them. */
        private fun sweep(cacheDirectory: File) {
            val cutoff = System.currentTimeMillis() - SWEEP_AFTER_MS
            dragDirectory(cacheDirectory).listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
        }
    }
}
