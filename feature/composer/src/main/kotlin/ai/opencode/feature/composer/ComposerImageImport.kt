package ai.opencode.feature.composer

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import ai.opencode.feature.media.PromptImageFormat
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The downscale and re-encode applied to every imported image: long edge at most [MAX_PIXEL_SIZE],
 * JPEG at [COMPRESSION_QUALITY]. Re-encoding drops EXIF and GPS metadata; `ImageDecoder` applies
 * the EXIF orientation first.
 */
object SharedImageEncoder {
    const val MAX_SOURCE_BYTES = 20_000_000
    const val MAX_PIXEL_SIZE = PromptImageFormat.MAX_PIXEL_SIZE
    const val COMPRESSION_QUALITY = PromptImageFormat.COMPRESSION_QUALITY
    const val MIME = PromptImageFormat.MIME

    fun jpeg(data: ByteArray): ByteArray? {
        if (data.size > MAX_SOURCE_BYTES) return null
        return jpeg(ImageDecoder.createSource(ByteBuffer.wrap(data)))
    }

    /** Decodes straight from the file so a large original is never held in memory whole. */
    fun jpeg(file: File): ByteArray? {
        if (!file.isFile || file.length() > MAX_SOURCE_BYTES) return null
        return jpeg(ImageDecoder.createSource(file))
    }

    fun thumbnail(data: ByteArray, maxPixelSize: Int): Bitmap? =
        decode(ImageDecoder.createSource(ByteBuffer.wrap(data)), maxPixelSize)

    private fun jpeg(source: ImageDecoder.Source): ByteArray? {
        val bitmap = decode(source, MAX_PIXEL_SIZE) ?: return null
        return try {
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, (COMPRESSION_QUALITY * 100).roundToInt(), out)) null else out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** Never upscales: only images larger than [maxPixelSize] on their long edge shrink. */
    private fun decode(source: ImageDecoder.Source, maxPixelSize: Int): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val size = info.size
            val longEdge = max(size.width, size.height)
            if (longEdge > maxPixelSize) {
                val scale = maxPixelSize.toDouble() / longEdge
                decoder.setTargetSize(
                    max(1, (size.width * scale).roundToInt()),
                    max(1, (size.height * scale).roundToInt()),
                )
            }
        }
    }.getOrNull()
}

object ImageAttachmentImporter {
    const val MAX_SOURCE_BYTES = SharedImageEncoder.MAX_SOURCE_BYTES

    /**
     * Downscale and re-encode imported images so prompts stay small. The share sheet uses the
     * same encoder, so shared and picked images match. Runs off the main thread.
     */
    suspend fun process(
        data: ByteArray,
        id: UUID = UUID.randomUUID(),
        videoDuration: Double? = null,
        localVideoURI: String? = null,
    ): OpenCodeOutgoingAttachment? = withContext(Dispatchers.Default) {
        val jpeg = SharedImageEncoder.jpeg(data) ?: return@withContext null
        OpenCodeOutgoingAttachment(
            id = id.toString(), data = jpeg, mime = SharedImageEncoder.MIME,
            videoDuration = videoDuration, localVideoURI = localVideoURI,
        )
    }
}

/**
 * Loads a picked, dropped or pasted image's bytes. A movie becomes its first frame (long edge ≤
 * 1600, JPEG 0.85); anything else reads its bytes and lets the encoder decide.
 */
object ComposerItemProviderLoader {
    private const val MOVIE_FRAME_MAX = 1600
    private const val MOVIE_FRAME_QUALITY = 85

    suspend fun load(uri: Uri, context: Context = dependency(AppContext)): ByteArray? = loadSource(uri, context = context)?.bytes

    internal suspend fun loadSource(
        uri: Uri,
        picked: Boolean = false,
        context: Context = dependency(AppContext),
    ): ImportedPoster? = withContext(Dispatchers.IO) {
        val type = context.contentResolver.getType(uri)
        if (type?.startsWith("video/") == true) return@withContext movieFrame(uri, context, picked)
        readBytes(uri, context)?.let { ImportedPoster(it) }
    }

    private fun readBytes(uri: Uri, context: Context): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            // One byte past the limit is enough to know it's oversized.
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                total += n
                if (total > ImageAttachmentImporter.MAX_SOURCE_BYTES) break
            }
            out.toByteArray()
        }
    }.getOrNull()

    private fun movieFrame(uri: Uri, context: Context, picked: Boolean): ImportedPoster? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val frame = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, MOVIE_FRAME_MAX, MOVIE_FRAME_MAX)
                ?: return null
            val out = ByteArrayOutputStream()
            try {
                if (!frame.compress(Bitmap.CompressFormat.JPEG, MOVIE_FRAME_QUALITY, out)) return null
            } finally {
                frame.recycle()
            }
            if (!picked) return ImportedPoster(out.toByteArray())
            val seconds = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.div(1000.0)
            // ACTION_PICK_IMAGES grants initially last only for this app session. If the
            // provider refuses persistence, the poster still works after access expires.
            val original = if (uri.scheme == "content") runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                uri.toString()
            }.getOrNull() else null
            ImportedPoster(out.toByteArray(), seconds, original)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}

internal data class ImportedPoster(val bytes: ByteArray, val videoDuration: Double? = null, val localVideoURI: String? = null)

/**
 * Where a composer image comes from. `NSItemProvider` and `PhotosPickerItem` are both content
 * [Uri]s on Android; `Transferable` images are [Data].
 */
sealed interface ComposerImageSource {
    /** A dropped or pasted item. */
    data class ItemProvider(val uri: Uri) : ComposerImageSource

    /** A photo-picker item; [attachmentID] chosen up front keeps it in sync with the selection. */
    data class PhotosPickerItem(val uri: Uri, override val attachmentID: UUID? = null) : ComposerImageSource

    class Data(val data: ByteArray) : ComposerImageSource

    /** [attachmentID]: chosen up front, so what it becomes is known before it loads (a photo flying into its thumbnail). */
    class Loader(
        override val attachmentID: UUID? = null,
        val videoDuration: Double? = null,
        val localVideoURI: String? = null,
        val load: suspend () -> ByteArray?,
    ) : ComposerImageSource

    val attachmentID: UUID? get() = null
}

private suspend fun ComposerImageSource.loadPoster(): ImportedPoster? = when (this) {
    is ComposerImageSource.ItemProvider -> ComposerItemProviderLoader.loadSource(uri)
    is ComposerImageSource.PhotosPickerItem -> ComposerItemProviderLoader.loadSource(uri, picked = true)
    is ComposerImageSource.Data -> ImportedPoster(data)
    is ComposerImageSource.Loader -> load()?.let { ImportedPoster(it, videoDuration, localVideoURI) }
}

/**
 * Runs one image import at a time; starting another or cancelling supersedes the current one, whose
 * late results are discarded. Callbacks run on the main thread; [isLoading] is Compose state.
 */
class ComposerImageImporter(private val scope: CoroutineScope) {
    data class Result(val addedCount: Int = 0, val sawOversizedSource: Boolean = false)

    private var job: Job? by mutableStateOf(null)
    private var cancelHandler: (() -> Unit)? = null

    val isLoading: Boolean get() = job != null

    fun start(
        sources: List<ComposerImageSource>,
        process: (suspend (ByteArray, UUID) -> OpenCodeOutgoingAttachment?)? = null,
        onAttachment: (OpenCodeOutgoingAttachment) -> Unit,
        onFailure: (UUID?) -> Unit = {},
        onCancel: () -> Unit = {},
        onCompletion: (Result) -> Unit = {},
        /** Pastes, shares and quotes: four images at a time. */
        limit: Int = 4,
    ) {
        cancel()
        for (dropped in sources.drop(limit)) onFailure(dropped.attachmentID)
        val kept = sources.take(limit)
        if (kept.isEmpty()) {
            onCancel()
            return
        }
        cancelHandler = onCancel

        // Supersession always cancels on the main thread first, so cancellation alone
        // identifies a stale import. Check it after every suspension and callback:
        // `onAttachment` itself may cancel or start another import.
        lateinit var current: Job
        current = scope.launch(Dispatchers.Main) {
            var result = Result()
            for (source in kept) {
                if (!isActive) return@launch
                val poster = source.loadPoster()
                if (!isActive) return@launch
                if (poster == null) {
                    onFailure(source.attachmentID)
                    continue
                }
                if (poster.bytes.size > ImageAttachmentImporter.MAX_SOURCE_BYTES) {
                    result = result.copy(sawOversizedSource = true)
                    onFailure(source.attachmentID)
                    continue
                }
                val id = source.attachmentID ?: UUID.randomUUID()
                val attachment = if (process == null) {
                    ImageAttachmentImporter.process(poster.bytes, id, poster.videoDuration, poster.localVideoURI)
                } else {
                    process(poster.bytes, id)
                }
                if (!isActive) return@launch
                if (attachment == null) {
                    onFailure(source.attachmentID)
                    continue
                }
                onAttachment(attachment)
                result = result.copy(addedCount = result.addedCount + 1)
            }
            if (!isActive) return@launch
            cancelHandler = null
            onCompletion(result)
            // Still loading while `onCompletion` runs, unless it superseded this import.
            if (job === current && isActive) job = null
        }
        job = current
    }

    fun cancel() {
        job?.cancel()
        job = null
        val handler = cancelHandler
        cancelHandler = null
        handler?.invoke()
    }
}
