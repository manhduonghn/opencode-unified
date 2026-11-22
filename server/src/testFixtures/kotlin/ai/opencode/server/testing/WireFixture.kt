package ai.opencode.server.testing

import ai.opencode.server.OpenCodeServer
import ai.opencode.server.TestNetworkBlock
import ai.opencode.server.UrlErrorCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.util.UUID
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * Serves scripted replies for one test's unique host (`wire-<uuid>.invalid`). Requests to any other
 * host are never intercepted. Requests go through the real client code and OkHttp
 * (`OpenCodeTransport.testValue`'s [TestNetworkBlock] routes this host here), so tests see exactly
 * what the app sends.
 *
 * ```kotlin
 * val fixture = WireFixture { Reply.Json(200, """{"ok":true}""") }
 * try { requestHealth(fixture.server) } finally { fixture.close() }
 * fixture.requests.single().route // "GET /api/health"
 * ```
 */
class WireFixture(
    scheme: String = "https",
    /** Models a final response URL that differs after redirect handling. */
    private val responseURL: HttpUrl? = null,
    private val reply: (Request) -> Reply,
) : AutoCloseable {
    sealed interface Reply {
        data class Json(val status: Int, val body: String, val headers: Map<String, String> = emptyMap()) : Reply

        /** No HTTP response: the connection failed this way. */
        data class Failure(val code: Int) : Reply

        /** Sends [status] and [body], then holds the connection open without another byte: a stream stranded on a route that went away. */
        data class Open(val status: Int, val body: String) : Reply

        /** Never answers at all: a blackholed address. */
        data object Silent : Reply

        companion object {
            /** A bodiless reply, such as 204 or 404. */
            fun status(status: Int): Reply = Json(status, "")
        }
    }

    class Request(
        val method: String,
        val path: String,
        /** The percent-encoded query, or null. */
        val query: String?,
        val headers: Map<String, String>,
        val body: ByteArray,
        /** The client's read timeout, in seconds. */
        val timeoutInterval: Double = 0.0,
        val url: HttpUrl,
    ) {
        /** `"GET /api/info"`: the method and path, for matching and assertions. */
        val route: String get() = "$method $path"

        val queryItems: List<Pair<String, String?>>
            get() = (0 until url.querySize).map { url.queryParameterName(it) to url.queryParameterValue(it) }

        fun queryValue(name: String): String? = url.queryParameter(name)

        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        fun json(): JsonObject = Json.parseToJsonElement(body.decodeToString()).jsonObject

        val bodyText: String get() = body.decodeToString()

        override fun toString(): String = route
    }

    val host: String = "wire-${UUID.randomUUID().toString().lowercase()}.invalid"
    private val scheme = scheme
    private val lock = Any()
    private val captured = mutableListOf<Request>()

    val url: String get() = "$scheme://$host"
    val server: OpenCodeServer get() = OpenCodeServer(url = url, username = "user", password = "secret")

    val requests: List<Request> get() = synchronized(lock) { captured.toList() }

    init {
        TestNetworkBlock.registerMock(host, Interceptor { chain -> respond(chain) })
    }

    override fun close() = TestNetworkBlock.unregisterMock(host)

    private fun respond(chain: Interceptor.Chain): Response {
        val urlRequest = chain.request()
        val body = urlRequest.body?.let { requestBody -> Buffer().also(requestBody::writeTo).readByteArray() } ?: ByteArray(0)
        val headers = LinkedHashMap<String, String>()
        urlRequest.headers.forEach { (name, value) -> headers[name] = value }
        urlRequest.body?.contentType()?.let { type -> if (headers.keys.none { it.equals("Content-Type", true) }) headers["Content-Type"] = type.toString() }
        val request = Request(
            method = urlRequest.method,
            path = urlRequest.url.encodedPath,
            query = urlRequest.url.encodedQuery,
            headers = headers,
            body = body,
            timeoutInterval = chain.readTimeoutMillis() / 1000.0,
            url = urlRequest.url,
        )
        synchronized(lock) { captured += request }
        return when (val answer = reply(request)) {
            is Reply.Failure -> throw failure(answer.code)
            is Reply.Open -> Response.Builder()
                .request(urlRequest)
                .protocol(Protocol.HTTP_1_1)
                .code(answer.status)
                .message("")
                .header("Content-Type", "text/event-stream")
                .body(HeldOpenSource(answer.body.encodeToByteArray(), chain).buffer().asResponseBody("text/event-stream".toMediaType()))
                .build()
            Reply.Silent -> {
                waitUntilCancelled(chain)
                throw IOException("Canceled")
            }
            is Reply.Json -> {
                val finalRequest = responseURL?.let { urlRequest.newBuilder().url(it).build() } ?: urlRequest
                Response.Builder()
                    .request(finalRequest)
                    .protocol(Protocol.HTTP_1_1)
                    .code(answer.status)
                    .message("")
                    .apply { answer.headers.forEach { (name, value) -> header(name, value) } }
                    .apply { if (answer.headers.keys.none { it.equals("Content-Type", true) }) header("Content-Type", "application/json") }
                    .body(answer.body.toResponseBody((answer.headers["Content-Type"] ?: "application/json").toMediaType()))
                    .build()
            }
        }
    }

    /** Sends [body], then blocks each read until the call is cancelled or its read timeout passes. */
    private class HeldOpenSource(body: ByteArray, private val chain: Interceptor.Chain) : Source {
        private val pending = Buffer().write(body)

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (!pending.exhausted()) return pending.read(sink, byteCount)
            waitUntilCancelled(chain)
            throw IOException("Canceled")
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {}
    }

    companion object {
        /**
         * Waits as a socket with nothing to read would: until the call is cancelled, or its read
         * timeout passes (then it throws the timeout the socket would).
         */
        private fun waitUntilCancelled(chain: Interceptor.Chain) {
            val limit = chain.readTimeoutMillis().toLong()
            val started = System.nanoTime()
            while (!chain.call().isCanceled()) {
                if (limit > 0 && (System.nanoTime() - started) / 1_000_000 >= limit) throw SocketTimeoutException("timeout")
                Thread.sleep(5)
            }
        }

        /** The JVM exception `UrlErrorCode.fromThrowable` reads back as [code]. */
        fun failure(code: Int): IOException = when (code) {
            UrlErrorCode.NOT_CONNECTED_TO_INTERNET -> ConnectException("Network is unreachable")
            UrlErrorCode.CANNOT_FIND_HOST, UrlErrorCode.DNS_LOOKUP_FAILED -> UnknownHostException("fixture")
            UrlErrorCode.CANNOT_CONNECT_TO_HOST -> ConnectException("Connection refused")
            UrlErrorCode.TIMED_OUT -> SocketTimeoutException("timeout")
            UrlErrorCode.NETWORK_CONNECTION_LOST -> SocketException("Connection reset")
            UrlErrorCode.CANCELLED -> IOException("Canceled")
            UrlErrorCode.APP_TRANSPORT_SECURITY_REQUIRES_SECURE_CONNECTION -> UnknownServiceException("CLEARTEXT communication not permitted")
            UrlErrorCode.SECURE_CONNECTION_FAILED -> SSLHandshakeException("fixture")
            UrlErrorCode.BAD_SERVER_RESPONSE, UrlErrorCode.CANNOT_PARSE_RESPONSE -> EOFException("fixture")
            else -> if (code <= UrlErrorCode.SECURE_CONNECTION_FAILED) SSLException("fixture") else SocketException("fixture $code")
        }
    }
}
