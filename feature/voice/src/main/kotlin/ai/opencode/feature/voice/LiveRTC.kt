package ai.opencode.feature.voice

import ai.opencode.core.dependencies.*
import ai.opencode.core.log.AppLog
import ai.opencode.server.OpenCodeTransport
import android.content.Context
import android.media.AudioFormat
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Opus/RTP for speech (jitter buffer, loss concealment, FEC), JSON on the
 * pre-negotiated event channel. WebSocket audio otherwise queues behind lost TCP packets. */
object LiveRTC {
    const val sessionsURL = "https://api.openai.com/v1/live/sessions"
    const val dataChannelID = 4
    val connectLimit = 8.seconds
    val disconnectLimit = 5.seconds
    const val fieldTrials = "WebRTC-ForceDtls13/Enabled/WebRTC-Sctp-Snap/Enabled/WebRTC-IceHandshakeDtls/Enabled/"
    private var initialized = false

    @Synchronized internal fun initialize(context: Context) {
        if (initialized) return
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
            .setFieldTrials(fieldTrials)
            // Native diagnostics can contain candidates/SDP. Never forward them to app logs.
            .setInjectableLogger({ _, _, _ -> }, Logging.Severity.LS_NONE).createInitializationOptions())
        initialized = true
    }

    /** A free model request keeps the pooled TLS connection warm. The warmer owns/cancels this job. */
    fun warmConnection(apiKey: String): Job {
        val http = dependency(OpenCodeTransport).request.withVoiceTimeout(10)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + DependencyValues.current.asContextElement())
        return scope.launch {
            try {
                val request = Request.Builder().url("https://api.openai.com/v1/models/${LiveSessionConfig.defaultModel}")
                    .header("Authorization", "Bearer $apiKey").build()
                http.voiceSend(request)
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { /* Warming is opportunistic, not a key preflight. */
            } finally { scope.cancel() }
        }
    }

    suspend fun connect(apiKey: String, config: LiveSessionConfig): LiveConnection {
        val broker = config.serverVoice?.let(::VoiceBrokerTransport)
        val setup = VoiceRTCSetup(
            engine = "GPT-Live",
            request = { sdp -> Request.Builder()
                .url(VoiceLabEnvironment.string("OPENCODE_LAB_LIVE_RTC_URL") ?: sessionsURL)
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody(config, sdp).toRequestBody(voiceJsonMediaType)).build() },
            answer = { data ->
                val root = VoiceWireJson.parseToJsonElement(data.decodeToString()).jsonObject
                // An answer requires both session.id and transport.sdp.
                require(root["session"]?.jsonObject?.get("id")?.stringValue != null)
                root["transport"]?.jsonObject?.get("sdp")?.stringValue ?: throw ServerVoiceError.InvalidResponse
            },
            exchange = broker?.let { { sdp -> it.exchange(config, sdp) } },
            closeMessage = LiveCommand.Close().encoded(),
            startedType = "session.started", closedType = "session.closed",
        )
        val session = VoiceRTCSession.open(setup)
        return LiveConnection(
            events = session.eventStream { signal -> when (signal) {
                is VoiceRTCSignal.Message -> runCatching { LiveServerEvent.decode(signal.data) }.getOrNull()
                is VoiceRTCSignal.Audio -> LiveServerEvent.OutputAudio(signal.pcm, null, null)
                is VoiceRTCSignal.LinkQuality -> LiveServerEvent.LinkQuality(signal.poor)
            } },
            send = { command -> when (command) {
                is LiveCommand.AppendAudio -> session.deliverAudio(command.data)
                is LiveCommand.SessionStart -> Unit // HTTP already started the session.
                else -> session.sendJSON(command.encoded())
            } },
            close = session::close, startAudio = session::startAudio,
        )
    }

    /** Audio format is negotiated in SDP, not in the session JSON. */
    fun requestBody(config: LiveSessionConfig, sdp: String): ByteArray = buildJsonObject {
        put("session", config.sessionPayload(false))
        putJsonObject("transport") { put("type", "webrtc"); put("sdp", sdp); put("dcid", dataChannelID) }
    }.encodedBytes()
}

/** A refusal would fail on WebSocket too, so transport choice must not retry it. */
data class LiveRTCRejected(val status: Int, override val message: String) : Exception(message)

sealed interface VoiceRTCSignal {
    class Message(val data: ByteArray) : VoiceRTCSignal
    class Audio(val pcm: ByteArray) : VoiceRTCSignal
    data class LinkQuality(val poor: Boolean) : VoiceRTCSignal
}

data class VoiceRTCSetup(
    val engine: String,
    val request: (sdp: String) -> Request,
    val answer: (ByteArray) -> String,
    val exchange: (suspend (sdp: String) -> String)? = null,
    val closeMessage: ByteArray?,
    val startedType: String,
    val closedType: String?,
)

/** All peer/data-channel callbacks hop off WebRTC's signaling thread onto one
 * serial dispatcher. Closing/disposal blocks native signaling, never its callback thread. */
class VoiceRTCSession private constructor(private val setup: VoiceRTCSetup) {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + DependencyValues.current.asContextElement() + CoroutineExceptionHandler { _, error ->
            if (error !is CancellationException) {
                AppLog.voice.error("WebRTC session task failed (${error.javaClass.simpleName})")
            }
        },
    )
    private val context = dependency(AppContext)
    private val clock = dependency(Clock)
    private val voiceCallLog = dependency(VoiceCallLogClient)
    private val http = dependency(OpenCodeTransport).request.newBuilder().retryOnConnectionFailure(false).build().withVoiceTimeout(15)
    private val startedAt = clock.now()
    private val signals = Channel<VoiceRTCSignal>(Channel.UNLIMITED)
    private val gathered = CompletableDeferred<Unit>()
    private val opened = CompletableDeferred<Unit>()
    private val closedByServer = CompletableDeferred<Unit>()
    private val shutdown = CompletableDeferred<Unit>()
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var channel: DataChannel? = null
    private var source: AudioSource? = null
    private var microphone: AudioTrack? = null
    private var device: LiveRTCAudioDevice? = null
    private val callAudioContext = dependency(VoiceRTCAudioContext)
    private val audioContext = callAudioContext ?: VoiceRTCAudioContext()
    private var startTask: Deferred<Unit>? = null
    private var audioTask: Deferred<VoiceAudioSession>? = null
    private var audio: VoiceAudioSession? = null
    private var finished = false
    private var closing = false
    private var remoteClosed = false
    private var connected = false
    @Volatile private var failure: Throwable? = null
    private var disconnectTask: Job? = null
    private var statsTask: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var linkPoor = false
    private val watch = LiveRTCLinkWatch()
    private val network = LiveRTCNetworkWatch()

    companion object {
        suspend fun open(setup: VoiceRTCSetup): VoiceRTCSession {
            val session = VoiceRTCSession(setup)
            try {
                val start = session.scope.async { session.start() }
                session.startTask = start
                start.await()
                return session
            } catch (error: Throwable) {
                // A native failure cancels the in-flight offer/HTTP work. That
                // cancellation is not the caller cancelling: preserve its RTC
                // diagnostic so transport choice can still try the WebSocket.
                val reported = if (currentCoroutineContext().isActive) session.failure ?: error else error
                withContext(NonCancellable + session.dispatcher) { session.shutDown(reported) }
                throw reported
            }
        }
    }

    fun <Event> eventStream(map: (VoiceRTCSignal) -> Event?): Flow<Event> = flow {
        // Cancellation stops receiving, not the coordinator-owned RTC session.
        for (signal in signals) map(signal)?.let { emit(it) }
    }

    private fun requireOpen() {
        if (finished || closing) throw failure ?: RealtimeClientError.Closed("The voice connection is closed.")
    }
    private fun callback(block: suspend () -> Unit) { scope.launch {
        if (finished) return@launch
        try { block() } catch (error: CancellationException) { throw error
        } catch (error: Exception) { shutDown(error) }
    } }
    private fun note(text: String) = voiceCallLog.noteCurrent(VoiceCallLog.Note.Kind.audio, text)
    private fun elapsed() = (clock.now() - startedAt).inWholeNanoseconds / 1e9

    private suspend fun start() {
        watchNetwork()
        LiveRTC.initialize(context)
        val audioDevice = LiveRTCAudioDevice(context, setup.engine == "Realtime") { pcm ->
            callback { signals.trySend(VoiceRTCSignal.Audio(pcm)) }
        }
        device = audioDevice
        val factory = PeerConnectionFactory.builder().setAudioDeviceModule(audioDevice.module).createPeerConnectionFactory()
        this.factory = factory
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }
        val peer = factory.createPeerConnection(config, observer)
            ?: throw RealtimeClientError.Closed("The voice connection couldn't be set up.")
        this.peer = peer
        // Standbys negotiate but NEVER capture/play. Activation belongs to the coordinator.
        peer.setAudioRecording(false); peer.setAudioPlayout(false)
        audioDevice.bind(peer)
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", (!audioDevice.hardwareAEC).toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", (!audioDevice.hardwareNS).toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
        }
        val source = factory.createAudioSource(constraints)
        this.source = source
        val track = factory.createAudioTrack("microphone", source)
        microphone = track
        peer.addTrack(track, listOf("microphone"))
        val channel = peer.createDataChannel("oai-events", DataChannel.Init().apply {
            negotiated = true; id = LiveRTC.dataChannelID; ordered = true
        }) ?: throw RealtimeClientError.Closed("The voice connection couldn't be set up.")
        this.channel = channel
        channel.registerObserver(channelObserver)
        val offer = createOffer(peer)
        requireOpen()
        setDescription(peer, offer, local = true)
        requireOpen()
        if (peer.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) gathered.await()
        requireOpen()
        val sdp = peer.localDescription?.description ?: throw RealtimeClientError.Closed("The voice connection couldn't be set up.")
        var answer = setup.exchange?.invoke(sdp) ?: run {
            val response = http.voiceSend(setup.request(sdp))
            if (response.statusCode !in 200..299) {
                val detail = runCatching {
                    VoiceWireJson.parseToJsonElement(response.body.decodeToString()).jsonObject["error"]?.jsonObject?.get("message")?.stringValue
                }.getOrNull()
                throw LiveRTCRejected(response.statusCode, detail ?: "The voice service refused the call (HTTP ${response.statusCode}).")
            }
            setup.answer(response.body)
        }
        requireOpen()
        note(String.format(Locale.US, "%s WebRTC session created at %.2f s", setup.engine, elapsed()))
        answer = routeLabRelay(answer)
        requireOpen()
        setDescription(peer, SessionDescription(SessionDescription.Type.ANSWER, answer), local = false)
        requireOpen()
        val limit = scope.launch { clock.sleep(LiveRTC.connectLimit); opened.completeExceptionally(rtcError(1, "No connection to the voice service")) }
        try { if (channel.state() != DataChannel.State.OPEN) opened.await() } finally { limit.cancel() }
        requireOpen()
    }

    /** Start ONLY after retiring any previous hardware owner. Capture is telemetry;
     * RTP already carries it. Output events describe PCM already played by WebRTC. */
    suspend fun startAudio(atEar: Boolean): VoiceAudioSession = withContext(dispatcher) {
        requireOpen()
        audio?.let { return@withContext it }
        val task = audioTask ?: scope.async {
            val session = audioContext.start(atEar, requireNotNull(device))
            requireOpen()
            audio = session
            session
        }.also { audioTask = it }
        try { task.await() } catch (error: Throwable) {
            val reported = if (currentCoroutineContext().isActive) failure ?: error else error
            withContext(NonCancellable) { shutDown(reported) }
            throw reported
        }
    }

    /** ADM supplies the microphone directly. Never create a second AudioRecord or
     * feed captured telemetry back into RTP (which would send each sample twice). */
    suspend fun deliverAudio(@Suppress("UNUSED_PARAMETER") pcm: ByteArray) = withContext(dispatcher) { requireOpen() }

    suspend fun sendJSON(data: ByteArray) = withContext(dispatcher) {
        requireOpen()
        val channel = channel
        val sent = runCatching {
            channel?.state() == DataChannel.State.OPEN && channel.send(DataChannel.Buffer(ByteBuffer.wrap(data), false))
        }.getOrDefault(false)
        if (!sent) throw RealtimeClientError.Closed("The voice connection is closed.")
    }

    suspend fun close() = withContext(NonCancellable + dispatcher) {
        if (finished || closing) { shutdown.await(); return@withContext }
        closing = true
        val channel = channel
        if (!remoteClosed && setup.closeMessage != null && runCatching { channel?.state() == DataChannel.State.OPEN }.getOrDefault(false)) {
            val sent = runCatching { channel?.send(DataChannel.Buffer(ByteBuffer.wrap(setup.closeMessage), false)) == true }.getOrDefault(false)
            if (!sent)
                VoiceControlSend.recordFailure("rtc.close", RealtimeClientError.Closed("The voice connection is closed."))
            val limit = scope.launch { clock.sleep(600.milliseconds); closedByServer.complete(Unit) }
            try { closedByServer.await() } finally { limit.cancel() }
        }
        shutDown(null)
    }

    private suspend fun shutDown(error: Throwable?): Unit = withContext(NonCancellable + dispatcher) {
        if (finished) { shutdown.await(); return@withContext }
        finished = true; failure = error
        opened.completeExceptionally(error ?: RealtimeClientError.Closed("The voice connection is closed."))
        gathered.complete(Unit); closedByServer.complete(Unit)
        disconnectTask?.cancel(); statsTask?.cancel()
        signals.close(error)
        try {
            networkCallback?.let { cb ->
                networkCallback = null
                runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
            }
            startTask?.takeIf { !it.isCompleted }?.cancelAndJoin()
            audioTask?.takeIf { !it.isCompleted }?.cancelAndJoin()
            device?.let { audioContext.detach(it) }
            channel?.let { ch ->
                channel = null
                runCatching { ch.unregisterObserver() }
                runCatching { ch.close() }
                runCatching { ch.dispose() }
            }
            device?.detachTracks()
            peer?.let { pc ->
                peer = null
                runCatching { pc.close() }
                runCatching { pc.dispose() }
            }
            microphone?.let { mic -> microphone = null; runCatching { mic.dispose() } }
            source?.let { src -> source = null; runCatching { src.dispose() } }
            factory?.let { f -> factory = null; runCatching { f.dispose() } }
            device?.release()
            if (callAudioContext == null) audioContext.stop()
            audio = null
        } finally { shutdown.complete(Unit); scope.cancel() }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) = callback { connectionChanged(state) }
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = callback {
            if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
        }
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = callback {
            val track = runCatching { receiver.track() as? AudioTrack }.getOrNull() ?: return@callback
            device?.attach(track)
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceCandidate(candidate: IceCandidate) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {
            callback { runCatching { channel.close() }; runCatching { channel.dispose() } }
        }
        override fun onRenegotiationNeeded() {}
    }
    private val channelObserver = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) {}
        override fun onStateChange() = callback {
            when (runCatching { channel?.state() }.getOrNull()) {
                DataChannel.State.OPEN -> {
                    note(String.format(Locale.US, "WebRTC event channel open at %.2f s", elapsed())); opened.complete(Unit)
                }
                DataChannel.State.CLOSED -> if (!closing) shutDown(rtcError(5, "Event channel closed"))
                else -> Unit
            }
        }
        override fun onMessage(buffer: DataChannel.Buffer) {
            // Native buffers are borrowed only until this callback returns.
            val data = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
            callback {
                val type = runCatching { VoiceWireJson.parseToJsonElement(data.decodeToString()).jsonObject["type"]?.stringValue }.getOrNull()
                if (setup.closedType != null && type == setup.closedType) { remoteClosed = true; closedByServer.complete(Unit) }
                if (type == setup.startedType) note(String.format(Locale.US, "%s session started at %.2f s", setup.engine, elapsed()))
                device?.observe(data)
                signals.trySend(VoiceRTCSignal.Message(data))
            }
        }
    }

    private suspend fun connectionChanged(state: PeerConnection.PeerConnectionState) {
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                disconnectTask?.cancel(); disconnectTask = null; watch.transportDown = false
                if (!connected) {
                    connected = true
                    val time = elapsed()
                    val report = statistics()
                    note(String.format(Locale.US, "WebRTC connected over %s in %.2f s", report?.let(LiveRTCLinkSample::from)?.path?.uppercase() ?: "an unknown path", time))
                    watchLink()
                } else note("WebRTC connection recovered")
            }
            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                note("⚠︎ WebRTC connection interrupted"); watch.transportLost(); linkChanged(true)
                disconnectTask?.cancel()
                disconnectTask = scope.launch {
                    clock.sleep(LiveRTC.disconnectLimit)
                    note("⚠︎ WebRTC connection lost"); shutDown(rtcError(2, "Connection interrupted"))
                }
            }
            PeerConnection.PeerConnectionState.FAILED -> { note("⚠︎ WebRTC connection failed"); shutDown(rtcError(3, "Connection failed")) }
            PeerConnection.PeerConnectionState.CLOSED -> if (!closing) shutDown(rtcError(4, "Connection closed"))
            else -> Unit
        }
    }
    private fun watchNetwork() {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(value: Network, capabilities: NetworkCapabilities) {
                val transport = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> "other"
                }
                callback {
                    if (network.update("$transport:${value.networkHandle}")) {
                        note("⚠︎ WebRTC: the network changed ($transport); reconnecting")
                        shutDown(rtcError(6, "Network changed"))
                    }
                }
            }
            // Losing connectivity alone is not a change; the disconnect timer rides it out.
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { AppLog.voice.notice("WebRTC network callback unavailable (${it.javaClass.simpleName})") }
    }
    private fun watchLink() {
        if (finished || statsTask != null) return
        statsTask = scope.launch {
            var tick = 0
            while (isActive) {
                clock.sleep(1.seconds)
                if (finished) break
                val sample = statistics()?.let(LiveRTCLinkSample::from) ?: continue
                val backlog = runCatching { channel?.bufferedAmount() ?: 0L }.getOrDefault(0L)
                watch.add(sample, backlog)?.let(::linkChanged)
                tick++
                if (tick % 5 == 0) watch.summary()?.let(::note)
            }
        }
    }
    private suspend fun statistics(): RTCStatsReport? {
        val peer = peer ?: return null
        return suspendCancellableCoroutine { continuation ->
            try {
                peer.getStats { report ->
                    if (continuation.isActive) continuation.resume(report)
                }
            } catch (_: IllegalStateException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }
    private fun linkChanged(poor: Boolean) {
        if (finished || linkPoor == poor) return
        linkPoor = poor; signals.trySend(VoiceRTCSignal.LinkQuality(poor))
    }
    private fun rtcError(code: Int, reason: String) = RealtimeClientError.TransportLost(
        RealtimeTransportDiagnostic("WebRTC", code, closeReason = reason))

    private suspend fun createOffer(peer: PeerConnection): SessionDescription = suspendCancellableCoroutine { continuation ->
        peer.createOffer(object : VoiceSDPObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) { if (continuation.isActive) continuation.resume(sdp) }
            override fun onCreateFailure(reason: String) { if (continuation.isActive) continuation.resumeWithException(RealtimeClientError.Closed("The voice connection couldn't be set up.")) }
        }, MediaConstraints().apply { mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")) })
    }
    private suspend fun setDescription(peer: PeerConnection, sdp: SessionDescription, local: Boolean): Unit = suspendCancellableCoroutine { continuation ->
        val observer = object : VoiceSDPObserver() {
            override fun onSetSuccess() { if (continuation.isActive) continuation.resume(Unit) }
            override fun onSetFailure(reason: String) { if (continuation.isActive) continuation.resumeWithException(RealtimeClientError.Closed("The voice connection couldn't be set up.")) }
        }
        if (local) peer.setLocalDescription(observer, sdp) else peer.setRemoteDescription(observer, sdp)
    }
    private suspend fun routeLabRelay(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        if (VoiceLabEnvironment.string("OPENCODE_LAB_RTC_ONLY") == "tcp") lines.removeAll { it.startsWith("a=candidate:") && it.contains(" udp ") }
        val relay = VoiceLabEnvironment.string("OPENCODE_LAB_RTC_RELAY") ?: return lines.joinToString("\r\n")
        val port = relay.substringAfterLast(':').toIntOrNull() ?: return lines.joinToString("\r\n")
        val host = relay.substringBeforeLast(':')
        val index = lines.indexOfFirst { it.startsWith("a=candidate:") && it.contains(" udp ") }
        if (index < 0) return sdp
        val fields = lines[index].split(' ')
        if (fields.size <= 5) return sdp
        val control = "http://$host:${port + 1}/target".toHttpUrl().newBuilder()
            .addQueryParameter("ip", fields[4]).addQueryParameter("port", fields[5]).build()
        try { http.voiceSend(Request.Builder().url(control).post(ByteArray(0).toRequestBody()).build()) }
        catch (error: CancellationException) { throw error } catch (_: Exception) { }
        lines.removeAll { it.startsWith("a=candidate:") }
        lines.add(min(index, lines.size), "a=candidate:1 1 udp 2130706431 $host $port typ host")
        return lines.joinToString("\r\n")
    }
}

private open class VoiceSDPObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(reason: String) {}
    override fun onSetFailure(reason: String) {}
}

/**
 * One call's external engine across WebRTC replacements. The coordinator creates
 * this once, installs it with withDependenciesSuspending for all connects, and
 * stops it at call end. A standby doesn't attach until startAudio is called.
 * Moving devices keeps focus, meters, mute, audio event consumers and the call
 * sound clock: no false hang-up/connect cues on a rotation or a network switch.
 * WebSocket temporarily returns the same engine to local hardware; only call end
 * stops the context, so RTC → WebSocket → RTC retains one session and consumer.
 */
class VoiceRTCAudioContext {
    private val lock = Mutex()
    @Volatile private var device: LiveRTCAudioDevice? = null
    @Volatile private var muted = false
    @Volatile private var interrupted = false
    @Volatile private var volume = 1f
    private var engine: VoiceAudioEngine? = null
    private var audio: VoiceAudioSession? = null
    private var stopped = false
    private val control = object : VoiceExternalAudioControl {
        override fun setMuted(muted: Boolean) { this@VoiceRTCAudioContext.muted = muted; device?.setMuted(muted) }
        override fun setPlaybackVolume(volume: Float) { this@VoiceRTCAudioContext.volume = volume; device?.setPlaybackVolume(volume) }
        override fun flush() { device?.flush() }
        override fun interrupted(interrupted: Boolean) { this@VoiceRTCAudioContext.interrupted = interrupted; device?.interrupted(interrupted) }
        override fun stop() { device?.stop() }
    }

    /** Adopt the reducer's prewarm before opening a socket. An injected session
     * without a live engine keeps its own lifecycle and is never cast to hardware. */
    internal suspend fun adopt(session: VoiceAudioSession): VoiceAudioSession = lock.withLock {
        check(!stopped)
        val engine = session.engine ?: return@withLock session
        check(this.engine == null)
        this.engine = engine
        wrap(session)
    }

    private fun wrap(session: VoiceAudioSession): VoiceAudioSession = VoiceAudioSession(
        session.capture, session.events, session.enqueue, session.flush, session.setMuted,
        ::stop, session.setAtEar, session.noteConnected, session.cancel, session.quality, session.engine,
    ).also { audio = it }

    internal fun owns(session: VoiceAudioSession?) = session != null && session === audio

    internal suspend fun useWebSocket(): VoiceAudioSession? = lock.withLock {
        check(!stopped)
        device?.stop(); device?.engine = null; device = null
        engine?.useExternalOwner(null)
        audio
    }

    internal suspend fun start(atEar: Boolean, next: LiveRTCAudioDevice): VoiceAudioSession = lock.withLock {
        check(!stopped) { "The voice connection is closed." }
        if (device !== next) {
            device?.stop(); device?.engine = null
            device = next
        }
        try {
            if (engine == null) {
                val engine = VoiceAudioEngine(atEar = atEar, externalOwner = true, externalControl = control)
                this.engine = engine
                next.engine = engine
                val session = engine.start()
                wrap(session)
            } else {
                engine?.useExternalOwner(control)
                next.engine = engine
                engine?.setAtEar(atEar)
            }
            next.setMuted(muted); next.setPlaybackVolume(volume)
            next.interrupted(interrupted); next.activate()
            requireNotNull(audio)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                next.stop(); next.engine = null; device = null
                engine?.stop(); engine = null; audio = null
            }
            throw error
        }
    }

    internal suspend fun detach(old: LiveRTCAudioDevice) = lock.withLock {
        old.stop(); old.engine = null
        if (device === old) {
            device = null
            engine?.externalPlaybackDrained()
        }
    }

    suspend fun stop(): Unit = withContext(NonCancellable) { lock.withLock {
        if (!stopped) {
            stopped = true
            try { engine?.stop() }
            finally { device?.engine = null; device = null; engine = null; audio = null }
        }
    } }

    companion object Key : DependencyKey<VoiceRTCAudioContext?> {
        override val liveValue: VoiceRTCAudioContext? = null
        override val testValue: VoiceRTCAudioContext? = null
    }
}

/**
 * JavaAudioDeviceModule is the one hardware owner; VoiceAudioEngine(externalOwner=true) still owns
 * focus, routing, mute, cues, meters and tracking. SamplesReadyCallback feeds telemetry, never
 * another AudioRecord; the remote AudioTrack sink reports decoded playout. Native jitter-buffer PCM
 * is converted to 24-kHz mono and emitted in 40-ms chunks. ADM mute sends zero frames without
 * stopping the RTP timeline.
 */
class LiveRTCAudioDevice(
    context: Context,
    private val realtime: Boolean,
    private val onPlayout: (ByteArray) -> Unit,
) : VoiceExternalAudioControl {
    val hardwareAEC = JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()
    val hardwareNS = JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "voice-rtc-adm") }
    private val lock = Any()
    // Native stop/removal can wait on a sink callback. Never hold the PCM/state lock
    // while calling native control, and never call the engine while holding either lock.
    private val hardwareLock = Any()
    @Volatile var engine: VoiceAudioEngine? = null
    private var peer: PeerConnection? = null
    private val tracks = mutableSetOf<AudioTrack>()
    private var active = false
    @Volatile private var released = false
    private var muted = false
    private var interrupted = false
    private var volume = 1f
    private val speech = RealtimeRTCSpeech()
    private val chunk = ShortArray(chunkFrames)
    private var used = 0
    private var phase = 0.0
    private var last = 0.0
    private var hangover = 0
    private var playing = false
    private var flushed = false
    private val muteOutput = VoiceLabEnvironment.string("OPENCODE_LAB_VOICE_MUTE_OUTPUT") != null
    val module: JavaAudioDeviceModule = JavaAudioDeviceModule.builder(context)
        .setScheduler(scheduler).setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        .setAudioFormat(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate)
        .setUseHardwareAcousticEchoCanceler(hardwareAEC).setUseHardwareNoiseSuppressor(hardwareNS)
        .setUseStereoInput(false).setUseStereoOutput(false).setEnableVolumeLogger(false)
        .setAudioAttributes(LiveVoiceAudioSession.attributes(false))
        .setSamplesReadyCallback { samples ->
            if (samples.audioFormat == AudioFormat.ENCODING_PCM_16BIT) {
                val shorts = ShortArray(samples.data.size / 2)
                ByteBuffer.wrap(samples.data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                engine?.feedCapture(shorts, shorts.size, samples.sampleRate, samples.channelCount)
            }
        }.createAudioDeviceModule()

    internal fun bind(peer: PeerConnection) = synchronized(hardwareLock) { this.peer = peer }
    internal fun activate() = synchronized(hardwareLock) {
        val enabled = synchronized(lock) { check(!released); active = true; !interrupted }
        module.setMicrophoneMute(muted || !enabled); module.setSpeakerMute(muteOutput)
        runCatching { peer?.setAudioRecording(enabled) }
        runCatching { peer?.setAudioPlayout(enabled) }
    }
    internal fun attach(track: AudioTrack) = synchronized(hardwareLock) {
        if (!released && tracks.add(track)) {
            val attached = runCatching {
                track.setVolume(volume.toDouble())
                track.addSink(sink)
                true
            }.getOrDefault(false)
            if (!attached) tracks.remove(track)
        }
    }
    internal fun detachTracks() = synchronized(hardwareLock) {
        tracks.forEach { track -> runCatching { track.removeSink(sink) } }
        tracks.clear()
        peer = null
    }
    internal fun observe(data: ByteArray) = synchronized(hardwareLock) {
        val resume = synchronized(lock) {
            if (!realtime) false else {
                speech.observe(data)
                (speech.speaking && flushed).also { if (it) flushed = false }
            }
        }
        if (resume && !released) module.setSpeakerMute(muteOutput)
    }
    private val sink = AudioTrackSink { buffer, bits, rate, channels, frames, _ ->
        val completed = mutableListOf<Playout>()
        synchronized(lock) {
            if (!active || interrupted || released || rate <= 0 || channels <= 0 || bits != 16) return@synchronized
            val shorts = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val count = min(frames, shorts.remaining() / channels)
            for (frame in 0 until count) {
                var value = 0.0
                repeat(channels) { value += shorts.get() }
                value /= channels
                while (phase <= 0) {
                    chunk[used++] = (last + (value - last) * (1 + phase)).toInt().coerceIn(-32768, 32767).toShort()
                    phase += rate.toDouble() / sampleRate
                    if (used == chunk.size) { completed += takeChunk(); used = 0 }
                }
                phase -= 1; last = value
            }
        }
        for (playout in completed) {
            if (playout.audible) engine?.recordExternalPlayback(playout.samples, playout.itemID)
            if (playout.drained) engine?.externalPlaybackDrained()
            onPlayout(playout.pcm)
        }
    }
    private data class Playout(val pcm: ByteArray, val samples: FloatArray, val itemID: String?, val audible: Boolean, val drained: Boolean)
    private fun takeChunk(): Playout {
        val pcm = ByteBuffer.allocate(chunk.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { asShortBuffer().put(chunk) }.array()
        val audible = if (realtime) speech.event(pcm) != null else {
            if (PCM.hasSpeechEnergy(pcm)) { hangover = 300; true }
            else (hangover > 0).also { hangover = max(0, hangover - 40) }
        }
        val drained = playing && (!audible || flushed)
        playing = audible && !flushed
        return Playout(pcm, FloatArray(chunk.size) { chunk[it] / 32768f }, speech.itemID, playing, drained)
    }
    override fun setMuted(muted: Boolean) = synchronized(hardwareLock) {
        val mute = synchronized(lock) { this.muted = muted; muted || interrupted }
        if (!released) module.setMicrophoneMute(mute)
    }
    override fun setPlaybackVolume(volume: Float) = synchronized(hardwareLock) {
        this.volume = volume
        val iterator = tracks.iterator()
        while (iterator.hasNext()) {
            val track = iterator.next()
            if (runCatching { track.setVolume(volume.toDouble()) }.isFailure) {
                iterator.remove()
            }
        }
    }
    override fun flush() {
        synchronized(lock) { used = 0; hangover = 0; playing = false; flushed = realtime }
        // Realtime clears server output as part of barge-in. Suppress the local tail
        // until the next output_audio_buffer.started; no second playout queue exists.
        // This ADM setter only changes a mute flag. A native track operation here
        // could wait on the sink while VoiceAudioEngine.flush holds its tracking lock.
        if (realtime && !released) module.setSpeakerMute(true)
    }
    override fun interrupted(interrupted: Boolean) = synchronized(hardwareLock) {
        val enabled = synchronized(lock) { this.interrupted = interrupted; active && !interrupted }
        if (!released) {
            module.setMicrophoneMute(muted || interrupted)
            runCatching { peer?.setAudioRecording(enabled) }
            runCatching { peer?.setAudioPlayout(enabled) }
        }
    }
    override fun stop() = synchronized(hardwareLock) {
        synchronized(lock) { active = false }
        if (!released) {
            runCatching { peer?.setAudioRecording(false) }
            runCatching { peer?.setAudioPlayout(false) }
        }
    }
    internal fun release() = synchronized(hardwareLock) {
        if (!released) {
            synchronized(lock) { released = true }
            module.release(); scheduler.shutdownNow(); engine = null
        }
    }
    companion object {
        const val sampleRate = 24000
        const val pullFrames = sampleRate / 100
        const val chunkFrames = pullFrames * 4
    }
}

/** One second of cumulative counters; never contains candidate addresses. */
data class LiveRTCLinkSample(
    val path: String? = null, val roundTrip: Double? = null,
    val packetsReceived: Double = 0.0, val packetsLost: Double = 0.0,
    val samplesReceived: Double = 0.0, val samplesConcealed: Double = 0.0,
    val silentConcealedSamples: Double = 0.0, val jitter: Double? = null, val outboundLoss: Double? = null,
) {
    companion object {
        fun from(report: RTCStatsReport): LiveRTCLinkSample {
            var result = LiveRTCLinkSample()
            val stats = report.statsMap
            val selected = stats.values.lastOrNull { it.type == "transport" }?.members?.get("selectedCandidatePairId")
            for (stat in stats.values) {
                val values = stat.members
                fun number(key: String) = (values[key] as? Number)?.toDouble()
                when {
                    stat.type == "inbound-rtp" && values["kind"] == "audio" -> result = result.copy(
                        packetsReceived = number("packetsReceived") ?: 0.0,
                        packetsLost = max(0.0, number("packetsLost") ?: 0.0),
                        samplesReceived = number("totalSamplesReceived") ?: 0.0,
                        samplesConcealed = number("concealedSamples") ?: 0.0,
                        silentConcealedSamples = number("silentConcealedSamples") ?: 0.0, jitter = number("jitter"),
                    )
                    stat.type == "remote-inbound-rtp" -> result = result.copy(
                        outboundLoss = number("fractionLost"), roundTrip = result.roundTrip ?: number("roundTripTime"))
                    stat.type == "candidate-pair" && stat.id == selected -> result = result.copy(
                        roundTrip = number("currentRoundTripTime") ?: result.roundTrip,
                        path = stats[values["localCandidateId"]]?.members?.get("protocol") as? String)
                }
            }
            return result
        }
    }
}

/** Broken/stalled links warn at once; degradation needs two of three seconds.
 * Recovery requires three healthy seconds, so concealed lone losses don't flicker. */
class LiveRTCLinkWatch {
    var transportDown = false
    var isPoor = false; private set
    private var previous: LiveRTCLinkSample? = null
    private var healthyStreak = 0
    private var stalledSeconds = 0
    private val recentDegraded = ArrayDeque<Boolean>()
    private val window = ArrayDeque<Second>()
    data class Second(val loss: Double, val concealed: Double, val roundTrip: Double?, val jitter: Double?, val outboundLoss: Double?, val path: String?)
    fun transportLost() { transportDown = true; isPoor = true; healthyStreak = 0 }
    fun add(sample: LiveRTCLinkSample, channelBacklog: Long = 0): Boolean? {
        val old = previous; previous = sample
        if (old == null) return null
        val received = max(0.0, sample.packetsReceived - old.packetsReceived)
        val lost = max(0.0, sample.packetsLost - old.packetsLost)
        val samples = max(0.0, sample.samplesReceived - old.samplesReceived)
        val concealed = max(0.0, max(0.0, sample.samplesConcealed - old.samplesConcealed) - max(0.0, sample.silentConcealedSamples - old.silentConcealedSamples))
        val loss = if (received + lost > 0) lost / (received + lost) else 0.0
        val concealedRatio = if (samples > 0) min(1.0, concealed / samples) else 0.0
        stalledSeconds = if (received == 0.0) stalledSeconds + 1 else 0
        window.addLast(Second(loss, concealedRatio, sample.roundTrip, sample.jitter, sample.outboundLoss, sample.path))
        if (window.size > 5) window.removeFirst()
        val broken = transportDown || stalledSeconds >= 2 || channelBacklog > backlogLimit
        val degraded = loss > lossLimit || concealedRatio > concealedLimit || (sample.outboundLoss ?: 0.0) > lossLimit || (sample.roundTrip ?: 0.0) > roundTripLimit
        recentDegraded.addLast(degraded)
        if (recentDegraded.size > degradedWindow) recentDegraded.removeFirst()
        val lasting = recentDegraded.count { it } >= degradedAfter
        healthyStreak = if (broken || degraded || received == 0.0) 0 else healthyStreak + 1
        if (!isPoor && (broken || lasting)) { isPoor = true; return true }
        if (isPoor && healthyStreak >= recoverAfter) { isPoor = false; return false }
        return null
    }
    fun summary(): String? {
        if (window.isEmpty()) return null
        val loss = window.map { it.loss }.average(); val concealed = window.map { it.concealed }.average()
        val worstLoss = window.maxOf { it.loss }; val last = window.last()
        val parts = mutableListOf("WebRTC" + (last.path?.let { " · $it" } ?: ""))
        fun format(pattern: String, vararg values: Double) = String.format(Locale.US, pattern, *values.toTypedArray())
        last.roundTrip?.let { parts += format("rtt %.0f ms", it * 1000) }
        parts += format("in loss %.1f%% (worst %.0f%%)", loss * 100, worstLoss * 100)
        last.outboundLoss?.let { parts += format("out loss %.1f%%", it * 100) }
        last.jitter?.let { parts += format("jitter %.0f ms", it * 1000) }
        parts += format("concealed %.1f%%", concealed * 100)
        return (if (isPoor || worstLoss > lossLimit || concealed > concealedLimit) "⚠︎ " else "") + parts.joinToString(" · ")
    }
    companion object {
        const val lossLimit = 0.08; const val concealedLimit = 0.15; const val roundTripLimit = 0.8
        const val backlogLimit = 64 * 1024L; const val recoverAfter = 3
        const val degradedAfter = 2; const val degradedWindow = 3
    }
}

/** Losing connectivity isn't a replacement; a short gap belongs to WebRTC. */
class LiveRTCNetworkWatch {
    var current: String? = null; private set
    fun update(value: String?): Boolean {
        if (value == null) return false
        val old = current; current = value
        return old != null && old != value
    }
}

object RealtimeRTC {
    const val callsURL = "https://api.openai.com/v1/realtime/calls"
    suspend fun connect(apiKey: String, config: RealtimeSessionConfig): RealtimeConnection {
        val setup = VoiceRTCSetup(
            engine = "Realtime", request = { sdp -> request(apiKey, config, sdp) },
            answer = { it.decodeToString() },
            exchange = config.serverVoice?.let { server -> { sdp ->
                VoiceBrokerTransport(server).exchange(config.model, config.sessionPayload(false), sdp, LiveRTC.dataChannelID)
            } },
            closeMessage = null, startedType = "session.created", closedType = null,
        )
        val session = VoiceRTCSession.open(setup)
        val speech = RealtimeRTCSpeech()
        return RealtimeConnection(
            events = session.eventStream { signal -> when (signal) {
                is VoiceRTCSignal.Message -> { speech.observe(signal.data); runCatching { RealtimeServerEvent.decode(signal.data) }.getOrNull() }
                is VoiceRTCSignal.Audio -> speech.event(signal.pcm)
                is VoiceRTCSignal.LinkQuality -> RealtimeServerEvent.LinkQuality(signal.poor)
            } },
            send = { command -> when (command) {
                is RealtimeCommand.AppendAudio -> session.deliverAudio(command.data)
                is RealtimeCommand.SessionUpdate -> session.sendJSON(command.config.sessionUpdate(false).encodedBytes())
                else -> session.sendJSON(command.encoded())
            } },
            close = session::close, startAudio = session::startAudio,
        )
    }
    /** Multipart SDP + session, with the pre-negotiated channel id in the query. */
    fun request(apiKey: String, config: RealtimeSessionConfig, sdp: String): Request {
        val url = callsURL.toHttpUrl().newBuilder().addQueryParameter("dcid", LiveRTC.dataChannelID.toString()).build()
        val boundary = "opencode-${dependency(UuidClient).next()}"
        val body = buildString {
            for ((name, value) in listOf("sdp" to sdp, "session" to config.sessionPayload(false).toString()))
                append("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
            append("--$boundary--\r\n")
        }
        return Request.Builder().url(url).header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody("multipart/form-data; boundary=$boundary".toMediaType())).build()
    }
}

/** Tags jitter-buffer speech with response/item IDs for barge-in. A stopped buffer
 * leaves 200 ms for the last words; a cleared (interrupted) buffer has no tail. */
class RealtimeRTCSpeech {
    var speaking = false; private set
    var responseID: String? = null; private set
    var itemID: String? = null; private set
    private var tail = 0
    fun observe(data: ByteArray) {
        val peek = runCatching { VoiceWireJson.parseToJsonElement(data.decodeToString()).jsonObject }.getOrNull() ?: return
        when (peek["type"]?.stringValue) {
            "response.created" -> responseID = (peek["response"] as? JsonObject)?.get("id")?.stringValue ?: responseID
            "response.output_item.added" -> (peek["item"] as? JsonObject)?.let { if (it["type"]?.stringValue == "message") itemID = it["id"]?.stringValue ?: itemID }
            "response.output_audio_transcript.delta" -> itemID = peek["item_id"]?.stringValue ?: itemID
            "output_audio_buffer.started" -> { speaking = true; tail = 0; responseID = peek["response_id"]?.stringValue ?: responseID }
            "output_audio_buffer.stopped" -> { if (speaking) tail = tailMilliseconds; speaking = false }
            "output_audio_buffer.cleared" -> { speaking = false; tail = 0 }
        }
    }
    fun event(pcm: ByteArray): RealtimeServerEvent.OutputAudio? {
        if (!speaking) {
            if (tail <= 0) return null
            tail -= max(1, pcm.size / 2 * 1000 / LiveRTCAudioDevice.sampleRate)
        }
        return RealtimeServerEvent.OutputAudio(responseID, itemID, pcm)
    }
    companion object { const val tailMilliseconds = 200 }
}
