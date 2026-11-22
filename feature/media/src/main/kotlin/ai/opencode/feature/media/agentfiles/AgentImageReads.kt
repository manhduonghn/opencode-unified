package ai.opencode.feature.media

import ai.opencode.core.dependencies.dependency
import ai.opencode.server.OpenCodeTransport
import ai.opencode.server.retry.NetworkPathMonitor
import android.graphics.BitmapFactory
import android.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An image's display size in pixels. */
data class AgentImageSize(val width: Double, val height: Double)

/**
 * A read of an image file: the authenticated `GET /api/fs/read` request (built by
 * `OpenCodeServer.fileReadRequest`) and whether it may use a constrained network.
 */
data class AgentImageRequest(val request: Request, val allowsConstrainedNetworkAccess: Boolean = false)

/** Bytes appended in place, read without copying ([bytes] up to [count]). */
internal class AgentImageBytes(initial: Int = 64 * 1024) {
    var bytes = ByteArray(initial)
        private set
    var count = 0
        private set

    fun append(source: ByteArray, length: Int) {
        if (count + length > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, count + length))
        System.arraycopy(source, 0, bytes, count, length)
        count += length
    }

    fun toByteArray(): ByteArray = bytes.copyOf(count)
}

/**
 * The pixel size an image will show at, read from its first bytes: PNG, JPEG, HEIC, GIF and WebP
 * headers through the platform decoder's bounds pass, with EXIF orientation applied, so a preview
 * can hold its final shape before its pixels arrive.
 */
object AgentImageHeader {
    /** `final` says the bytes are the whole file; the bounds pass reads either the same way. */
    @Suppress("UNUSED_PARAMETER")
    fun size(data: ByteArray, count: Int = data.size, final: Boolean = false): AgentImageSize? {
        if (count <= 0) return null
        return decoderSize(data, count) ?: pngSize(data, count) ?: jpegSize(data, count)
    }

    private fun decoderSize(data: ByteArray, count: Int): AgentImageSize? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(data, 0, count, options) }
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0) return null
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(data, 0, count))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        // 5–8 are the quarter turns: the picture shows rotated.
        return if (orientation in 5..8) AgentImageSize(height.toDouble(), width.toDouble())
        else AgentImageSize(width.toDouble(), height.toDouble())
    }

    private val pngSignature = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val ihdr = intArrayOf(0x49, 0x48, 0x44, 0x52)

    /** `IHDR` follows the 8-byte signature: width and height, big-endian. */
    fun pngSize(data: ByteArray, count: Int = data.size): AgentImageSize? {
        if (count < 24) return null
        fun byte(at: Int) = data[at].toInt() and 0xFF
        if (pngSignature.indices.any { byte(it) != pngSignature[it] }) return null
        if (ihdr.indices.any { byte(12 + it) != ihdr[it] }) return null
        fun word(at: Int): Long = (0 until 4).fold(0L) { acc, i -> (acc shl 8) or byte(at + i).toLong() }
        val width = word(16)
        val height = word(20)
        return if (width > 0 && height > 0) AgentImageSize(width.toDouble(), height.toDouble()) else null
    }

    /** The first start-of-frame marker's height and width. Orientation is left to the decoder; this only answers when it couldn't. */
    fun jpegSize(data: ByteArray, count: Int = data.size): AgentImageSize? {
        val length = minOf(count, 256 * 1024)
        fun byte(at: Int) = data[at].toInt() and 0xFF
        if (length <= 4 || byte(0) != 0xFF || byte(1) != 0xD8) return null
        var index = 2
        while (index + 9 < length) {
            if (byte(index) != 0xFF) return null
            val marker = byte(index + 1)
            if (marker == 0xFF) {
                index += 1
                continue
            }
            val segment = (byte(index + 2) shl 8) or byte(index + 3)
            // Baseline, extended, progressive and lossless frames; not DHT (C4), JPG (C8) or DAC (CC).
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val height = (byte(index + 5) shl 8) or byte(index + 6)
                val width = (byte(index + 7) shl 8) or byte(index + 8)
                return if (width > 0 && height > 0) AgentImageSize(width.toDouble(), height.toDouble()) else null
            }
            if (segment < 2) return null
            index += 2 + segment
        }
        return null
    }
}

/** What a read answered: the file, or that the copy already shown is current. */
sealed interface AgentImageResponse {
    class File(val data: ByteArray, val validators: AgentImageValidators) : AgentImageResponse
    data object NotModified : AgentImageResponse
}

/** The server's validators for a file, sent back to recheck it cheaply. */
data class AgentImageValidators(val entityTag: String? = null, val lastModified: String? = null) {
    val isEmpty: Boolean get() = entityTag == null && lastModified == null

    fun apply(request: Request.Builder) {
        entityTag?.let { request.header("If-None-Match", it) }
        lastModified?.let { request.header("If-Modified-Since", it) }
    }
}

/**
 * One request with its body streamed to [block], cancelled with the calling coroutine. Cancellation
 * surfaces as [CancellationException].
 */
internal suspend fun <T> OkHttpClient.agentImageStreaming(request: Request, block: (Response) -> T): T = withContext(Dispatchers.IO) {
    val call = newCall(request)
    val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
    try {
        call.execute().use(block)
    } catch (error: IOException) {
        currentCoroutineContext().ensureActive()
        throw error
    } finally {
        handle.dispose()
    }
}

/**
 * One image's read: its size as soon as the header arrives, then the bytes. One request, so the
 * shape is known about as early as the first byte. It goes through its own connections with no HTTP
 * cache (OkHttp keeps none unless asked), so nothing is kept on disk and a render overwritten in
 * place is never served stale.
 */
object AgentImageDownload {
    enum class Failure {
        NotFound,
        TooLarge,
        NotImage,
        /**
         * The constrained network refused an automatic prefetch; not retried automatically, and
         * shown as Tap to load until the person asks for it.
         */
        Constrained,
        /** The network, or the server, failed this time: worth another try. */
        Failed,
    }

    class FailureException(val failure: Failure) : Exception(failure.name)

    /**
     * How much of a growing read is searched for the header. PNG, GIF, WebP and JPEG headers are
     * all within this; past it the shape comes from the finished file.
     */
    const val HEADER_INSPECTION_BYTES = 256 * 1024

    /**
     * The transport's pools' configuration, its own connections, URLSession's 60 s idle timeout.
     */
    val client: OkHttpClient by lazy {
        dependency(OpenCodeTransport).request.newBuilder()
            .connectionPool(ConnectionPool())
            .cache(null)
            .readTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val noRedirects: OkHttpClient by lazy { client.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    /** Whether the request would be refused by the constrained path. */
    internal fun isConstrainedRefusal(request: AgentImageRequest): Boolean =
        !request.allowsConstrainedNetworkAccess && NetworkPathMonitor.shared?.isConstrained == true

    /**
     * The whole file, after [onSize] has heard its shape (when the header could be read before
     * the end), or [AgentImageResponse.NotModified] for [validators]. Throws [FailureException].
     */
    suspend fun load(
        request: AgentImageRequest,
        validators: AgentImageValidators?,
        maxBytes: Int,
        requiresImageContentType: Boolean = false,
        client: OkHttpClient? = null,
        onSize: (AgentImageSize) -> Unit,
    ): AgentImageResponse {
        currentCoroutineContext().ensureActive()
        if (isConstrainedRefusal(request)) throw FailureException(Failure.Constrained)
        val builder = request.request.newBuilder()
        validators?.apply(builder)
        // A redirect is refused when the content type matters: the redirect itself answers.
        val http = client ?: if (requiresImageContentType) noRedirects else this.client
        return try {
            http.agentImageStreaming(builder.build()) { response -> receive(response, maxBytes, requiresImageContentType, onSize) }
        } catch (error: FailureException) {
            throw error
        } catch (error: IOException) {
            throw FailureException(Failure.Failed)
        }
    }

    private fun receive(response: Response, maxBytes: Int, requiresImageContentType: Boolean, onSize: (AgentImageSize) -> Unit): AgentImageResponse {
        val status = response.code
        when {
            status == 304 -> return AgentImageResponse.NotModified
            status == 404 -> throw FailureException(Failure.NotFound)
            status !in 200..299 -> throw FailureException(Failure.Failed)
            requiresImageContentType && !isImageResponse(response) -> throw FailureException(Failure.NotImage)
            response.body.contentLength() > maxBytes -> throw FailureException(Failure.TooLarge)
        }
        val validators = AgentImageValidators(response.header("ETag"), response.header("Last-Modified"))
        val data = AgentImageBytes()
        var sized = false
        val chunk = ByteArray(64 * 1024)
        response.body.byteStream().use { stream ->
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                if (read == 0) continue
                data.append(chunk, read)
                if (data.count > maxBytes) throw FailureException(Failure.TooLarge)
                // Only the first bytes are inspected, so a long read doesn't decode a growing
                // buffer on every chunk. A header past this point is read from the finished file.
                val inspectedBefore = data.count - read
                if (!sized && inspectedBefore < HEADER_INSPECTION_BYTES) {
                    val inspected = minOf(data.count, HEADER_INSPECTION_BYTES)
                    val size = AgentImageHeader.size(data.bytes, inspected) ?: continue
                    sized = true
                    onSize(size)
                }
            }
        }
        return AgentImageResponse.File(data.toByteArray(), validators)
    }

    fun isImageResponse(response: Response): Boolean {
        val mime = response.body.contentType()?.let { "${it.type}/${it.subtype}" }?.lowercase()
            ?: response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
            ?: return false
        return mime.startsWith("image/") && mime.length > "image/".length
    }
}

/** What a header probe found. */
sealed interface AgentImageProbeResult {
    data class Size(val size: AgentImageSize, val entityTag: String?) : AgentImageProbeResult
    /** The shape remembered for this ETag still holds. */
    data object NotModified : AgentImageProbeResult
    data object NotFound : AgentImageProbeResult
    /** The first bytes didn't say (an unusual header, a server error, the network). */
    data object Unknown : AgentImageProbeResult

    /** The trace name. */
    val traceName: String
        get() = when (this) {
            is Size -> "size"
            NotModified -> "notModified"
            NotFound -> "notFound"
            Unknown -> "unknown"
        }
}

/**
 * A header-only read: the first bytes of an image (`Range`, which V2's `/api/fs/read` honours; a
 * server that ignores it sends the whole file and the probe stops once it has the header), through
 * its own connections so it never waits behind pixel reads. It ends as soon as the shape is known.
 */
object AgentImageProbe {
    /** Enough for PNG, GIF and WebP headers and a JPEG's EXIF before its frame. */
    const val BYTES = 128 * 1024

    /**
     * Header reads are small and many arrive at once (a session opening); one held by a silent
     * server mustn't keep the rest waiting: their own pool, a 20 s idle timeout.
     */
    val client: OkHttpClient by lazy {
        dependency(OpenCodeTransport).request.newBuilder()
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .cache(null)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** The image's shape from its first bytes; [entityTag], when given, asks the server to answer 304 if the file hasn't changed. */
    suspend fun load(request: AgentImageRequest, entityTag: String?): AgentImageProbeResult {
        currentCoroutineContext().ensureActive()
        if (AgentImageDownload.isConstrainedRefusal(request)) return AgentImageProbeResult.Unknown
        val builder = request.request.newBuilder().header("Range", "bytes=0-${BYTES - 1}")
        entityTag?.let { builder.header("If-None-Match", it) }
        return try {
            client.agentImageStreaming(builder.build(), ::receive)
        } catch (error: IOException) {
            AgentImageProbeResult.Unknown
        }
    }

    private fun receive(response: Response): AgentImageProbeResult {
        when (response.code) {
            304 -> return AgentImageProbeResult.NotModified
            404 -> return AgentImageProbeResult.NotFound
            !in 200..299 -> return AgentImageProbeResult.Unknown
        }
        val tag = response.header("ETag")
        val data = AgentImageBytes(16 * 1024)
        val chunk = ByteArray(16 * 1024)
        return try {
            response.body.byteStream().use { stream ->
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    if (read == 0) continue
                    data.append(chunk, read)
                    AgentImageHeader.size(data.bytes, data.count)?.let { return AgentImageProbeResult.Size(it, tag) }
                    if (data.count >= BYTES) {
                        return AgentImageHeader.size(data.bytes, data.count, final = true)
                            ?.let { AgentImageProbeResult.Size(it, tag) } ?: AgentImageProbeResult.Unknown
                    }
                }
            }
            AgentImageHeader.size(data.bytes, data.count, final = true)?.let { AgentImageProbeResult.Size(it, tag) }
                ?: AgentImageProbeResult.Unknown
        } catch (error: IOException) {
            AgentImageProbeResult.Unknown
        }
    }
}
