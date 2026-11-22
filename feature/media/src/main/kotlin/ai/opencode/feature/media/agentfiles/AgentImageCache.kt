package ai.opencode.feature.media

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import android.graphics.Bitmap
import android.os.storage.StorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * What a session's images share across the transcript and the full-screen viewer, under one
 * identity per file ([key]: the server's persistence ID and the absolute path, or an upload's
 * `media:<hash>`).
 *
 * - Freshness: a file validated (200 or 304) is trusted without another request until the
 *   server's files may have changed ([invalidate], from
 *   `AgentFileChecks.invalidate`: a session opening or resuming, its run state changing, a reply
 *   settling). Uploads are content-addressed, so always fresh.
 * - Decodes of the same file coalesce ([decode]); results go to [DecodedImageCache]'s bounded budget.
 * - Downloads to the same destination run one at a time ([exclusive]), so the second finds the
 *   first's copy fresh.
 * - The disk copies (`AgentFileLoader.cacheURL`) keep the app's cache within its system quota ([trim]).
 */
class AgentImageCache(
    private val decoded: DecodedImageCache,
    /** The app's cache quota in bytes for a cache directory; null when unknown. Off the main thread. */
    private val cacheQuota: (File) -> Long? = { null },
    /** Decodes a downloaded copy at full resolution (the viewer's decode). */
    private val fileDecoder: suspend (File) -> Bitmap? = { AgentFileViewerModel.loadImage(it) },
) {
    private val lock = Any()
    private val validated = HashMap<String, Int>()
    private val epochs = HashMap<String, Int>()
    private val decodes = HashMap<String, Deferred<Bitmap?>>()
    private val locks = HashMap<String, Mutex>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The server's files may have changed: nothing of it is fresh any more. */
    fun invalidate(server: String) = synchronized(lock) { epochs[server] = (epochs[server] ?: 0) + 1 }

    fun validated(key: String) = synchronized(lock) {
        validated[key] = epochs[serverOf(key)] ?: 0
    }

    fun isFresh(key: String): Boolean = synchronized(lock) {
        if (key.startsWith(MEDIA)) return true
        validated[key] == (epochs[serverOf(key)] ?: 0)
    }

    /** The decoded pixels resident for [key], if any. */
    fun image(key: String): Bitmap? = decoded.image(DECODED + key)

    fun store(key: String, image: Bitmap) = decoded.insert(image, DECODED + key)

    /** One decode per file at a time; every caller gets the same bitmap, kept in the decoded budget. */
    suspend fun decode(key: String, file: File, replacing: Boolean = false, decode: suspend (File) -> Bitmap?): Bitmap? {
        if (!replacing) image(key)?.let { return it }
        val job = synchronized(lock) {
            // A changed file never joins a decode of its previous copy.
            decodes[key]?.takeIf { !replacing } ?: scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                decode(file)?.also { store(key, it) }
            }.also { job ->
                decodes[key] = job
                job.invokeOnCompletion { synchronized(lock) { if (decodes[key] === job) decodes.remove(key) } }
                job.start()
            }
        }
        return job.await()
    }

    /** [decode] with the viewer's full-resolution decode of a downloaded copy. */
    suspend fun decodeFile(key: String, file: File, replacing: Boolean = false): Bitmap? = decode(key, file, replacing, fileDecoder)

    /** Runs [block] alone for [path] (a download destination). */
    suspend fun <T> exclusive(path: String, block: suspend () -> T): T {
        val mutex = synchronized(lock) { locks.getOrPut(path) { Mutex() } }
        return mutex.withLock { block() }
    }

    /**
     * Deletes the least recently written disk copies until the whole app cache fits its quota (the
     * quota counts all of it; only these copies are ours to drop). No quota: Android cleans up.
     */
    fun trim(cacheDirectory: File) {
        val quota = cacheQuota(cacheDirectory)?.takeIf { it > 0 } ?: return
        var total = cacheDirectory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        if (total <= quota) return
        // `agent-file-*` are downloads and stores still being written.
        val files = File(cacheDirectory, "AgentFiles").walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".validators") && !it.name.startsWith("agent-file-") }.toList()
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= quota) break
            val sidecar = File(file.path + ".validators")
            total -= file.length() + sidecar.length()
            file.delete()
            sidecar.delete()
        }
    }

    private fun serverOf(key: String): String = key.substringBefore(SEPARATOR)

    companion object Key : DependencyKey<AgentImageCache> {
        private const val MEDIA = "media:"
        private const val DECODED = "agent-image:"

        /** Never in a server's persistence ID (which joins its username and addresses with `|`) or a path. */
        private const val SEPARATOR = '\u0000'

        fun key(key: AgentImageKey): String = key.server + SEPARATOR + key.path

        /** The one identity of a file a page or card shows; null when it can't be resolved. */
        fun key(server: OpenCodeServer, reference: AgentFileReference, directory: String?): String? {
            reference.media?.let { return MEDIA + it.hash }
            val target = reference.target(directory) ?: return null
            return key(AgentImageKey(server.persistenceID, target.absolutePath))
        }

        /**
         * The preview cache's bitmap of [key] when it holds every pixel of the file (previews decode
         * at most 2048 on the long side), so full screen shows the same bitmap and never a thumbnail
         * scaled up. Main thread.
         */
        fun fullResolutionPreview(previews: AgentImagePreviews, key: AgentImageKey): Bitmap? {
            val image = previews.image(key) ?: return null
            val size = previews.entry(key).size ?: return null
            val width = kotlin.math.round(size.width).toInt()
            val height = kotlin.math.round(size.height).toInt()
            val whole = (image.width >= width && image.height >= height) || (image.width >= height && image.height >= width)
            return image.takeIf { whole }
        }

        override val liveValue: AgentImageCache by lazy {
            val storage = runCatching { dependency(AppContext).getSystemService(StorageManager::class.java) }.getOrNull()
            AgentImageCache(DecodedImageCache.liveValue, cacheQuota = { directory ->
                storage?.let { runCatching { it.getCacheQuotaBytes(it.getUuidForPath(directory)) }.getOrNull() }
            })
        }

        /** Its own empty caches each time, so tests never share state. */
        override val testValue: AgentImageCache get() = AgentImageCache(DecodedImageCache())
    }
}
