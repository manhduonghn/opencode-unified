package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeTransport
import ai.opencode.feature.voice.VoiceFeatureTestSupport.LockIsolated
import ai.opencode.feature.voice.VoiceFeatureTestSupport.append
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.UUID
import kotlin.time.Duration

/**
 * `WireFixture` (a scripted server answering the live client's requests) and `ScriptedClock` (from
 * `NetworkRetryTests`).
 */
internal object VoiceLatencyBudgetTestSupport {
    /** Sleeps return at once and are recorded; time moves by them and [advance]. */
    class ScriptedClock : Clock {
        private var time = Duration.ZERO
        private val recorded = mutableListOf<Duration>()
        val sleeps: List<Duration> get() = synchronized(this) { recorded.toList() }
        override fun now(): Duration = synchronized(this) { time }
        override suspend fun sleep(duration: Duration) {
            synchronized(this) {
                recorded += maxOf(Duration.ZERO, duration)
                time += maxOf(Duration.ZERO, duration)
            }
        }

        /** Time passing inside an attempt (a request that takes a while to fail). */
        fun advance(by: Duration) = synchronized(this) { time += by }
    }

    /** Every request to [server] is answered by the handler, no network. */
    class WireFixture(private val handler: (Request) -> Reply) {
        data class Request(val method: String, val path: String, val query: Map<String, String>, val body: String?) {
            val route: String get() = "$method $path"
            fun queryValue(name: String): String? = query[name]
            fun json(): JsonObject = Json.parseToJsonElement(body ?: "{}") as JsonObject
        }

        sealed interface Reply {
            data class Status(val code: Int) : Reply
            data class Json(val status: Int, val body: String) : Reply
            data class Failure(val error: IOException) : Reply
        }

        companion object {
            fun status(code: Int) = Reply.Status(code)
            fun json(status: Int, body: String) = Reply.Json(status, body)

            val networkConnectionLost: Reply get() = Reply.Failure(SocketException("Connection reset"))

            val timedOut: Reply get() = Reply.Failure(SocketTimeoutException("timeout"))

            val cannotConnectToHost: Reply get() = Reply.Failure(ConnectException("Failed to connect"))
        }

        private val recorded = LockIsolated(listOf<Request>())
        val requests: List<Request> get() = recorded.value
        val server = OpenCodeServer(url = "http://wire-${UUID.randomUUID().toString().take(8)}.test:4096", username = "", password = "")

        private val interceptor = Interceptor { chain ->
            val request = chain.request()
            val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }
            val query = (0 until request.url.querySize).associate { request.url.queryParameterName(it) to (request.url.queryParameterValue(it) ?: "") }
            val recordedRequest = Request(request.method, request.url.encodedPath, query, body)
            recorded.append(recordedRequest)
            when (val reply = handler(recordedRequest)) {
                is Reply.Failure -> throw reply.error
                is Reply.Status -> Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(reply.code).message("")
                    .body("".toResponseBody(null)).build()
                is Reply.Json -> Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(reply.status).message("")
                    .body(reply.body.toResponseBody("application/json".toMediaType())).build()
            }
        }

        val transport: OpenCodeTransport = OkHttpClient.Builder().addInterceptor(interceptor).build().let { client ->
            OpenCodeTransport(request = client, measuredRequest = client, longPoll = client, eventStream = client)
        }

        fun close() {}
    }
}
