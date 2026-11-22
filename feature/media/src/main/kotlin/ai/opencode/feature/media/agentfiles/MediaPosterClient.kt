package ai.opencode.feature.media

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import ai.opencode.server.retry.NetworkPathMonitor
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** A video's or audio file's tile: a frame (video only) and the length, when known. */
class MediaPoster(val frame: Bitmap?, val durationMs: Long?)

/**
 * Posters for video and audio tiles. The file is downloaded into the file viewer's own cache (so
 * opening it later reuses the copy), up to [MAX_BYTES], and read once; results stay in memory.
 * Only uploads and files inside the session's folder are read, as the transcript's cards are.
 * While large prefetches are deferred (Data Saver, metered with battery saver), only a copy
 * already on disk is read; otherwise the tile stays plain and nothing is fetched.
 */
class MediaPosterClient(
    private val load: suspend (server: OpenCodeServer, reference: AgentFileReference, directory: String?) -> MediaPoster?,
) {
    suspend fun poster(server: OpenCodeServer, reference: AgentFileReference, directory: String?): MediaPoster? = load(server, reference, directory)

    companion object Key : DependencyKey<MediaPosterClient> {
        const val MAX_BYTES: Long = 40L * 1024 * 1024
        private const val FRAME_SIDE = 360

        private val cache = object : LruCache<String, MediaPoster>(48) {}
        private val permits = Semaphore(2)

        /** The file a poster reads: the copy on disk while large prefetches wait, else [download]'s. */
        internal suspend fun posterFile(destination: File, deferred: Boolean, download: suspend () -> File): File? =
            if (deferred) destination.takeIf { it.isFile && it.length() > 0 } else download()

        /** Whether a tile may read [reference] without a tap. */
        fun reads(reference: AgentFileReference, directory: String?): Boolean {
            if (!reference.kind.isTimeBased) return false
            if (reference.media != null) return true
            val target = reference.target(directory) ?: return false
            return AgentImagePlan.isInside(target, directory)
        }

        override val liveValue: MediaPosterClient by lazy {
            MediaPosterClient { server, reference, directory ->
                if (!reads(reference, directory)) return@MediaPosterClient null
                val original = AgentFileLoader.original(server, reference, directory) ?: return@MediaPosterClient null
                val key = "${server.persistenceID}|${original.cacheKey}"
                cache.get(key)?.let { return@MediaPosterClient it }
                permits.withPermit {
                    cache.get(key)?.let { return@withPermit it }
                    try {
                        val destination = AgentFileLoader.cacheURL(server, original.cacheKey, reference.name, dependency(AppContext).cacheDir)
                        val deferred = NetworkPathMonitor.shared?.shouldDeferLargePrefetch == true
                        val file = posterFile(destination, deferred) {
                            AgentFileLoader.download(original.request, destination, server, maxBytes = MAX_BYTES) { _, _ -> }
                        } ?: return@withPermit null
                        read(file, reference.kind == AgentFileReference.Kind.Video)?.also { cache.put(key, it) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        AppLog.network.debug("Media poster: read failed")
                        null
                    }
                }
            }
        }

        override val testValue: MediaPosterClient get() = MediaPosterClient { _, _, _ -> null }

        private suspend fun read(file: File, video: Boolean): MediaPoster? = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                val frame = if (video) {
                    val at = ((duration ?: 0L) / 3).coerceAtMost(2_000L) * 1000
                    retriever.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, FRAME_SIDE, FRAME_SIDE)
                } else null
                MediaPoster(frame, duration)
            } catch (error: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
}
