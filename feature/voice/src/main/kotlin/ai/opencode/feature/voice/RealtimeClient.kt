package ai.opencode.feature.voice

import ai.opencode.core.dependencies.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okio.ByteString
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/** The preflight lives in RealtimePreflight.kt. */
class RealtimeConnection(
    override val events: Flow<RealtimeServerEvent>,
    override val send: suspend (RealtimeCommand) -> Unit,
    override val close: suspend () -> Unit,
    override val startAudio: (suspend (Boolean) -> VoiceAudioSession)? = null,
) : VoiceSocketConnection<RealtimeServerEvent, RealtimeCommand>

class RealtimeClient(val connect: suspend (String, RealtimeSessionConfig) -> RealtimeConnection) {
    companion object Key : DependencyKey<RealtimeClient> {
        override val liveValue = RealtimeClient { apiKey, config ->
            if (config.engine == VoiceEngine.grok) RealtimeSocket(apiKey, config).open()
            else dependency(LiveTransportChoice).connect(
                webRTC = { RealtimeRTC.connect(apiKey, config) },
                webSocket = { RealtimeSocket(apiKey, config).open() },
            )
        }
        override val testValue = RealtimeClient { _, _ -> RealtimeConnection(emptyFlow(), {}, {}) }
    }
}

/** Why a Realtime connection ended. */
sealed class RealtimeClientError(override val message: String) : Exception(message), CarriesRealtimeTransportDiagnostic {
    data object InvalidURL : RealtimeClientError("The voice service address is invalid.")
    data class Closed(val reason: String) : RealtimeClientError(reason)

    /** The service ended the session: its time limit, or a going-away close. */
    data class SessionEnded(val diagnostic: RealtimeTransportDiagnostic) : RealtimeClientError("Call ended")

    /** The socket failed or closed under the call. */
    data class TransportLost(val diagnostic: RealtimeTransportDiagnostic) : RealtimeClientError("The voice connection was lost.")

    /** Reconnecting failed; the call is over. */
    data object Dropped : RealtimeClientError("Call ended")

    override val transportDiagnostic: RealtimeTransportDiagnostic?
        get() = when (this) {
            is SessionEnded -> diagnostic
            is TransportLost -> diagnostic
            InvalidURL, is Closed, Dropped -> null
        }

    companion object {
        fun from(error: Throwable, closeCode: Int = 0, closeReason: String? = null): RealtimeClientError {
            val diagnostic = RealtimeTransportDiagnostic.of(error, closeCode, closeReason)
            return if (closeCode == 1001) SessionEnded(diagnostic) else TransportLost(diagnostic)
        }
    }
}

class RealtimeSocket(private val apiKey: String, private val config: RealtimeSessionConfig) {
    suspend fun open(): RealtimeConnection {
        val targetURL: String
        val bearerToken: String
        val server = config.serverVoice
        if (server != null) {
            val transport = VoiceBrokerTransport(server).webSocket(config.model, config.sessionPayload(true))
            targetURL = transport.url; bearerToken = transport.token
        } else {
            val lab = VoiceLabEnvironment.string("OPENCODE_LAB_REALTIME_URL")
            targetURL = if (lab != null) {
                voiceSocketRequest(lab).url.newBuilder().query(null).addQueryParameter("model", config.model).build().toString()
            } else config.webSocketURL ?: throw RealtimeClientError.InvalidURL
            bearerToken = apiKey
        }
        val socket = VoiceWebSocket(targetURL, bearerToken)
        socket.sendVoiceHandshake("realtime.session_update") { socket.sendUTF8(RealtimeCommand.SessionUpdate(config).encoded()) }
        return RealtimeConnection(
            events = socket.voiceEventStream { listOf(RealtimeServerEvent.decode(it)) },
            send = { command -> socket.sendUTF8(command.encoded(), audio = command is RealtimeCommand.AppendAudio) },
            close = { socket.close() },
        )
    }
}

internal fun voiceSocketRequest(url: String, apiKey: String? = null): Request = try {
    Request.Builder().url(url).apply { if (apiKey != null) header("Authorization", "Bearer $apiKey") }.build()
} catch (_: IllegalArgumentException) { throw RealtimeClientError.InvalidURL }

/** URLSession's voice WebSocket helpers translated to OkHttp. One owned serialized
 * callback queue per socket. No automatic reconnect: the call owns its retry budget.
 * Retains events received before the coordinator subscribes. */
class VoiceWebSocket internal constructor(url: String, apiKey: String? = null) {
    private val request = voiceSocketRequest(url, apiKey)
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + DependencyValues.current.asContextElement())
    private val messages = Channel<ByteArray>(Channel.UNLIMITED)
    private val sendLock = Mutex()
    private val clock = dependency(Clock)
    private val opened = CompletableDeferred<Unit>()
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).readTimeout(0, TimeUnit.MILLISECONDS).build()
    @Volatile private var finished = false
    @Volatile private var open = false
    private var localClosing = false
    private var failure: Throwable? = null
    private var closeCode = 0
    private var closeReason: String? = null
    private var task: WebSocket? = null
    val isRunning: Boolean get() = !finished

    init {
        scope.launch { try {
            task = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { scope.launch {
                    if (!finished) { open = true; opened.complete(Unit) }
                } }
                override fun onMessage(webSocket: WebSocket, text: String) = receive(text.toByteArray())
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = receive(bytes.toByteArray())
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { scope.launch {
                    closeCode = code; closeReason = reason
                    webSocket.close(code, null)
                    finish(if (localClosing) null else RealtimeClientError.from(IOException("WebSocket closed"), code, reason))
                } }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { scope.launch {
                    closeCode = code; closeReason = reason
                    finish(if (localClosing) null else RealtimeClientError.from(IOException("WebSocket closed"), code, reason))
                } }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { scope.launch {
                    finish(if (localClosing) null else RealtimeClientError.from(t, closeCode, closeReason))
                } }
            })
        } catch (error: Exception) { finish(error) } }
    }
    private fun receive(bytes: ByteArray) { scope.launch {
        if (finished) return@launch
        if (bytes.size > maximumMessageSize) finish(RealtimeClientError.from(IOException("WebSocket message too large")))
        else messages.trySend(bytes)
    } }
    suspend fun sendVoiceHandshake(named: String, operation: suspend () -> Unit) {
        try { opened.await(); operation() } catch (error: Throwable) {
            VoiceControlSend.recordFailure(named, error); close(); throw error
        }
    }
    internal suspend fun sendUTF8(data: ByteArray, audio: Boolean = false): Unit = sendLock.withLock { withContext(dispatcher) {
        if (finished) {
            if (audio) return@withContext
            throw failure ?: RealtimeClientError.Closed("The voice connection is closed.")
        }
        if (audio && !open) return@withContext
        if (task?.send(data.decodeToString()) != true) {
            val error = RealtimeClientError.from(IOException("WebSocket send failed"), closeCode, closeReason)
            finish(error); throw error
        }
        // OkHttp.send only enqueues. Backpressure until its writer takes the frame,
        // rather than letting a capture loop pile seconds of audio behind a stall.
        // Like URLSession.send this is NOT a server acknowledgement.
        while (!finished && (task?.queueSize() ?: 0) > 0) clock.sleep(1.milliseconds)
        if (finished) throw failure ?: RealtimeClientError.Closed("The voice connection is closed.")
    } }
    internal fun <Event> voiceEventStream(decode: (ByteArray) -> List<Event>): Flow<Event> = flow {
        // Collection owns receiving only. The coordinator explicitly closes the
        // connection, allowing LiveSocket to send session.close before teardown.
        for (data in messages) {
            val decoded = try { decode(data) } catch (error: Exception) {
                throw RealtimeClientError.from(error, closeCode, closeReason)
            }
            decoded.forEach { emit(it) }
        }
    }
    suspend fun close() = withContext(NonCancellable + dispatcher) { finish(null) }
    internal suspend fun beginGracefulClose() = withContext(dispatcher) { localClosing = true }
    internal fun discard() { scope.launch { finish(null) } }
    private fun finish(error: Throwable?) {
        if (finished) return
        finished = true; open = false; failure = error
        opened.completeExceptionally(error ?: RealtimeClientError.Closed("The voice connection is closed."))
        messages.close(error)
        task?.close(1000, null); task?.cancel(); task = null
        client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); scope.cancel()
    }
    companion object { const val maximumMessageSize = 8 * 1024 * 1024 }
}
