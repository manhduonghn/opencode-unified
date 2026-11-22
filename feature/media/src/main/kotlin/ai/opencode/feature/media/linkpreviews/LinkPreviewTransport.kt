package ai.opencode.feature.media

import ai.opencode.server.retry.NetworkPathMonitor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A preview request's own idle timeout, attached as an OkHttp request tag; without one the
 * transport's 6 s applies.
 */
data class LinkPreviewTimeout(val amount: Long, val unit: TimeUnit)

/** A preview service's answer: its status and at most the capped body. */
class LinkPreviewResponse(val data: ByteArray, val statusCode: Int, val mimeType: String? = null)

/**
 * Data Saver restricts this app. Android has no per-request flag, so the transport refuses before
 * sending.
 */
class ConstrainedNetworkException : IOException("constrained network")

/** The answer is larger than the transport's cap. */
class LinkPreviewTooLargeException : IOException("data length exceeds maximum")

/**
 * Preview services cannot redirect a request to a page an agent supplied. No cookie jar or
 * app/server credentials are attached, and byte limits apply while receiving (not after an
 * unbounded download has finished).
 */
object LinkPreviewTransport {
    /** The JSON cap (1 MB); `Accept: image/…` requests get [IMAGE_LIMIT]. */
    const val JSON_LIMIT = 1 shl 20
    /** The artwork and icon cap (2 MB). */
    const val IMAGE_LIMIT = 2 shl 20

    /**
     * Ephemeral and cookie-free, no HTTP cache, no redirects, 6 s idle and 10 s overall
     * timeouts, at most two connections per host, and nothing sent on a constrained path.
     */
    val live: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 2 })
            .addInterceptor(ConstrainedRefusal)
            .build()
    }

    private object ConstrainedRefusal : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            if (NetworkPathMonitor.shared?.isConstrained == true) throw ConstrainedNetworkException()
            return chain.proceed(chain.request())
        }
    }

    private val timedClients = ConcurrentHashMap<LinkPreviewTimeout, OkHttpClient>()

    /** [live], with the request's own [LinkPreviewTimeout] when it carries one. */
    internal fun client(request: Request): OkHttpClient {
        val timeout = request.tag(LinkPreviewTimeout::class.java) ?: return live
        return timedClients.getOrPut(timeout) {
            live.newBuilder()
                .connectTimeout(timeout.amount, timeout.unit)
                .readTimeout(timeout.amount, timeout.unit)
                .writeTimeout(timeout.amount, timeout.unit)
                .build()
        }
    }

    suspend fun fetch(request: Request): LinkPreviewResponse {
        val limit = if (request.header("Accept")?.startsWith("image/") == true) IMAGE_LIMIT else JSON_LIMIT
        val call = client(request).newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use { read(it, limit) } }
                    if (result.isFailure) call.cancel()
                    result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                }
            })
        }
    }

    /** Reads at most [limit] bytes, failing as soon as more arrive. */
    private fun read(response: Response, limit: Int): LinkPreviewResponse {
        val body = response.body
        if (body.contentLength() > limit) throw LinkPreviewTooLargeException()
        val source = body.source()
        val buffer = Buffer()
        while (true) {
            val read = source.read(buffer, 8_192)
            if (read == -1L) break
            if (buffer.size > limit) throw LinkPreviewTooLargeException()
        }
        val mime = body.contentType()?.let { "${it.type}/${it.subtype}".lowercase() }
        return LinkPreviewResponse(buffer.readByteArray(), response.code, mime)
    }
}

/** `Data`'s Codable form: base64 in JSON. */
internal object Base64ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor = PrimitiveSerialDescriptor("Base64ByteArray", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ByteArray) = encoder.encodeString(Base64.getEncoder().encodeToString(value))
    override fun deserialize(decoder: Decoder): ByteArray = Base64.getDecoder().decode(decoder.decodeString())
}
