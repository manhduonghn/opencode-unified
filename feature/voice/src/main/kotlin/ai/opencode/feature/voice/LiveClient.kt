package ai.opencode.feature.voice

import ai.opencode.core.dependencies.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Commands/events are encoded/decoded here; nothing above this layer sees JSON/base64. */
class LiveConnection(
    override val events: Flow<LiveServerEvent>,
    override val send: suspend (LiveCommand) -> Unit,
    override val close: suspend () -> Unit,
    override val startAudio: (suspend (Boolean) -> VoiceAudioSession)? = null,
) : VoiceSocketConnection<LiveServerEvent, LiveCommand>

class LiveClient(val connect: suspend (String, LiveSessionConfig) -> LiveConnection) {
    companion object Key : DependencyKey<LiveClient> {
        override val liveValue = LiveClient { apiKey, config ->
            if (config.serverVoice != null) {
                try { LiveRTC.connect(apiKey, config) }
                catch (error: LiveRTCRejected) { throw RealtimeClientError.Closed(error.message) }
            } else dependency(LiveTransportChoice).connect(
                webRTC = { LiveRTC.connect(apiKey, config) },
                webSocket = { LiveSocket(apiKey, config).open() },
            )
        }
        override val testValue = LiveClient { _, _ -> LiveConnection(emptyFlow(), {}, {}) }
    }
}
class LiveSocket(private val apiKey: String, private val config: LiveSessionConfig) {
    @Volatile private var remoteClosed = false
    private val closing = AtomicBoolean(false)
    private val closed = CompletableDeferred<Unit>()
    private val clock = dependency(Clock)
    suspend fun open(): LiveConnection {
        val url = VoiceLabEnvironment.string("OPENCODE_LAB_LIVE_URL")
            ?: VoiceLabEnvironment.string("OPENCODE_LAB_REALTIME_URL") ?: LiveSocketWarmer.liveURL
        val warm = dependency(LiveSocketWarmerClient).take(url, apiKey)
        val socket = if (warm != null) {
            try { start(warm); warm } catch (error: CancellationException) { throw error
            } catch (_: Exception) { currentCoroutineContext().ensureActive(); VoiceWebSocket(url, apiKey).also { start(it) } }
        } else VoiceWebSocket(url, apiKey).also { start(it) }
        return LiveConnection(
            events = socket.voiceEventStream { data ->
                val event = LiveServerEvent.decode(data)
                if (event is LiveServerEvent.SessionClosed) remoteClosed = true
                listOf(event)
            },
            send = { command ->
                if (closing.get()) throw RealtimeClientError.Closed("The voice connection is closed.")
                socket.sendUTF8(command.encoded(), audio = command is LiveCommand.AppendAudio)
            },
            close = {
                withContext(NonCancellable) {
                    if (!closing.compareAndSet(false, true)) closed.await()
                    else try {
                        if (!remoteClosed && socket.isRunning) {
                            socket.beginGracefulClose()
                            coroutineScope {
                                val send = launch {
                                    try { socket.sendUTF8(LiveCommand.Close().encoded()) }
                                    catch (error: Exception) { VoiceControlSend.recordFailure("live.close", error) }
                                }
                                clock.sleep(50.milliseconds)
                                send.cancelAndJoin()
                            }
                        }
                    } finally { socket.close(); closed.complete(Unit) }
                }
            },
        )
    }
    private suspend fun start(socket: VoiceWebSocket) {
        socket.sendVoiceHandshake("live.session_start") { socket.sendUTF8(LiveCommand.SessionStart(config).encoded()) }
    }
}

/** WebRTC unless it recently failed on this network. A successful WebSocket
 * fallback sticks ten minutes so reconnects do not spend the connect limit again. */
class LiveTransportChoice(private val date: DateClient = dependency(DateClient)) {
    private var failedAt: Instant? = null
    val prefersWebRTC: Boolean
        @Synchronized get() {
            when (VoiceLabEnvironment.string("OPENCODE_LAB_LIVE_TRANSPORT")) {
                "websocket" -> return false
                "webrtc" -> return true
            }
            val failed = failedAt ?: return true
            if (java.time.Duration.between(failed, date.now()).toMillis() / 1000.0 > webSocketFor) {
                failedAt = null; return true
            }
            return false
        }
    @Synchronized fun webRTCFailed(at: Instant = date.now()) { failedAt = at }
    @Synchronized fun webRTCConnected() { failedAt = null }
    suspend fun <Connection> connect(webRTC: suspend () -> Connection, webSocket: suspend () -> Connection): Connection {
        if (VoiceLabEnvironment.string("OPENCODE_LAB_LIVE_URL") != null ||
            VoiceLabEnvironment.string("OPENCODE_LAB_REALTIME_URL") != null || !prefersWebRTC) return webSocket()
        val rtcError = try { return webRTC().also { webRTCConnected() }
        } catch (error: LiveRTCRejected) { throw RealtimeClientError.Closed(error.message)
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { currentCoroutineContext().ensureActive(); error }
        val log = dependency(VoiceCallLogClient)
        log.noteCurrent(VoiceCallLog.Note.Kind.audio, "⚠︎ WebRTC couldn't connect (${RealtimeTransportDiagnostic.of(rtcError).logSummary}); trying a WebSocket")
        try {
            return webSocket().also {
                webRTCFailed(); log.noteCurrent(VoiceCallLog.Note.Kind.audio, "Connected over a WebSocket instead of WebRTC")
            }
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) { currentCoroutineContext().ensureActive(); throw rtcError }
    }
    companion object Key : DependencyKey<LiveTransportChoice> {
        const val webSocketFor = 10 * 60.0
        override val liveValue by lazy { LiveTransportChoice() }
        override val testValue get() = LiveTransportChoice()
    }
}

/** One bare socket opened ahead of a call; taking it transfers exclusive ownership. */
class LiveSocketWarmer(private val date: DateClient = dependency(DateClient)) {
    private data class Warm(val url: String, val apiKey: String, val socket: VoiceWebSocket, val openedAt: Instant)
    private var current: Warm? = null
    private var warming: Job? = null
    @Synchronized fun warm(apiKey: String, url: String = liveURL, now: Instant = date.now()) {
        if (dependency(LiveTransportChoice).prefersWebRTC) {
            warming?.cancel(); warming = LiveRTC.warmConnection(apiKey); return
        }
        val old = current
        if (old != null && old.url == url && old.apiKey == apiKey && age(old, now) < maxAge / 2 && old.socket.isRunning) return
        current = Warm(url, apiKey, VoiceWebSocket(url, apiKey), now)
        old?.socket?.discard()
    }
    @Synchronized fun take(url: String, apiKey: String, now: Instant = date.now()): VoiceWebSocket? {
        val taken = current ?: return null
        current = null
        if (taken.url == url && taken.apiKey == apiKey && age(taken, now) < maxAge && taken.socket.isRunning) return taken.socket
        taken.socket.discard(); return null
    }
    @Synchronized fun cool() { current?.socket?.discard(); current = null; warming?.cancel(); warming = null }
    private fun age(warm: Warm, now: Instant) = java.time.Duration.between(warm.openedAt, now).toMillis() / 1000.0
    companion object {
        const val maxAge = 20.0
        const val liveURL = "wss://api.openai.com/v1/live/sessions"
        val rewarmEvery = (maxAge / 2).seconds
        val window = 60.seconds

        suspend fun warmWindow(warm: () -> Unit) {
            repeat((window / rewarmEvery).toInt()) {
                warm()
                delay(rewarmEvery)
            }
        }
    }
}
class LiveSocketWarmerClient(
    val warm: (String) -> Unit,
    val take: (String, String) -> VoiceWebSocket? = { _, _ -> null },
    val cool: () -> Unit,
) {
    companion object Key : DependencyKey<LiveSocketWarmerClient> {
        override val liveValue by lazy {
            val warmer = LiveSocketWarmer()
            LiveSocketWarmerClient({ warmer.warm(it) }, { url, key -> warmer.take(url, key) }, warmer::cool)
        }
        override val testValue = LiveSocketWarmerClient({}, { _, _ -> null }, {})
    }
}
