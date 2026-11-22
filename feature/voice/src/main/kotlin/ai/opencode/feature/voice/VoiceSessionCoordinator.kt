package ai.opencode.feature.voice

import ai.opencode.core.dependencies.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

interface VoiceSocketConnection<ServerEvent, Command> {
    val events: Flow<ServerEvent>
    val send: suspend (Command) -> Unit
    val close: suspend () -> Unit

    /** Android WebRTC owns the hardware. The coordinator first adopts prepared
     * VoiceAudioClient audio into its call-scoped VoiceRTCAudioContext, then invokes
     * this to release local hardware and activate ADM. Leave standbys inactive.
     * startAudio retains the engine/cues across RTC rotation and WS fallback;
     * the coordinator stops the returned audio only at call end.
     * Without that context the connection owns/stops its own engine on close.
     * Null means WebSocket: use VoiceAudioClient and forward capture/output normally. */
    val startAudio: (suspend (atEar: Boolean) -> VoiceAudioSession)? get() = null

    suspend fun sendControl(command: Command, named: String) {
        try { send(command) } catch (error: Exception) {
            VoiceControlSend.recordFailure(named, error)
            throw error
        }
    }
    suspend fun sendControlIfPossible(command: Command, named: String): Boolean =
        try { sendControl(command, named); true }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { false }
}

class VoiceCallSocket<E, C, A>(
    val id: Int,
    val connection: VoiceSocketConnection<E, C>,
    val openedAt: Instant,
    val seeded: Boolean,
    var task: Job? = null,
    var expiresAt: Instant? = null,
    var ready: Boolean = false,
    var attached: Boolean = false,
    val pendingAppends: MutableList<A> = mutableListOf(),
)

sealed interface VoiceSessionLoss {
    data class Closed(val error: Throwable?) : VoiceSessionLoss
    data class Expired(val reason: String, val message: String = reason) : VoiceSessionLoss
    val transportError: Throwable? get() = (this as? Closed)?.error
    val expiredReason: String? get() = (this as? Expired)?.reason
    val callError: Throwable get() = when (this) {
        is Closed -> error ?: RealtimeClientError.Closed("The voice connection closed.")
        is Expired -> RealtimeClientError.Closed(message)
    }
}

class VoiceSessionLifecycle<E, C, A> {
    var audio: VoiceAudioSession? = null
    var active: VoiceCallSocket<E, C, A>? = null
    var standby: VoiceCallSocket<E, C, A>? = null
    var nextSocketID = 0
    var hasConnected = false
    var isReconnecting = false
    var atEar = false
    var reconnectAttempts = 0
    val tasks = mutableListOf<Job>()
    var rotationTask: Job? = null
    var reconnectTask: Job? = null
    var uplink = VoiceUplinkWatch()
    var uplinkTask: Job? = null
    var linkPoor = false
    var reportedPoor = false
    var finished = false
}

/** Shared actor-confined socket rotation, reconnect backoff and audio lifecycle.
 * All external calls enter [serialized]; child tasks inherit the same dispatcher
 * and dependency context. Suspension permits actor reentrancy, not parallel mutation.
 */
abstract class VoiceSessionCoordinator<E, C, A>(
    val timing: VoiceCallTiming = VoiceCallTiming.live,
    val diagnostics: VoiceCallDiagnostics = dependency(VoiceCallDiagnostics),
    /** The actor's single lane. Tests pass their virtual-time dispatcher. */
    internal val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : VoiceCallTestingCoordinator {
    private val rtcAudio = VoiceRTCAudioContext()
    private val values = DependencyValues.current.with(VoiceRTCAudioContext, rtcAudio)
    internal val scope = CoroutineScope(SupervisorJob() + dispatcher + values.asContextElement())
    internal val clock = dependency(Clock)
    private val date = dependency(DateClient)
    val lifecycle = VoiceSessionLifecycle<E, C, A>()
    internal var audio: VoiceAudioSession?
        get() = lifecycle.audio
        set(value) { lifecycle.audio = value }
    internal var active: VoiceCallSocket<E, C, A>?
        get() = lifecycle.active
        set(value) { lifecycle.active = value }
    internal var standby: VoiceCallSocket<E, C, A>?
        get() = lifecycle.standby
        set(value) { lifecycle.standby = value }
    internal val finished get() = lifecycle.finished
    internal val connection get() = active?.connection
    internal val events = Channel<VoiceCallEvent>(Channel.UNLIMITED)
    private var streamMade = false
    private var audioClient: VoiceAudioClient? = null
    private var audioUsesRTC = false
    private var captureTask: Job? = null
    private var audioEventTask: Job? = null
    /** The audio session last told the call connected: each hears it once. */
    private var connectedAudio: VoiceAudioSession? = null
    abstract val defaultSessionLimit: Double
    abstract val defaultDiagnosticDomain: String
    abstract val expiredDiagnosticDomain: String
    abstract val canSwapToStandby: Boolean

    suspend fun <T> serialized(block: suspend () -> T): T = withContext(dispatcher + values.asContextElement()) { block() }
    override suspend fun checkIsFinished(): Boolean = serialized { finished }
    internal fun emit(event: VoiceCallEvent) { if (!finished) events.trySend(event) }
    internal suspend fun running(): Boolean = !finished && currentCoroutineContext().isActive
    internal abstract suspend fun open(seeded: Boolean): VoiceCallSocket<E, C, A>
    internal abstract suspend fun handle(event: E, socket: Int)
    internal abstract suspend fun activate(socket: VoiceCallSocket<E, C, A>)
    internal abstract fun retireSessionState()
    internal abstract suspend fun forwardCapture(chunk: ByteArray)
    internal abstract suspend fun handleAudio(event: VoiceAudioEvent)
    protected abstract suspend fun completeFunctionImpl(callID: String, output: String)
    protected abstract suspend fun closeDelegationImpl(callID: String, reason: VoiceDelegationCloseReason)
    protected abstract suspend fun setMutedImpl(muted: Boolean)
    protected open suspend fun retargetImpl() {}
    protected open fun finishing() {}
    abstract suspend fun updateInstructions(full: String, delta: String? = null)

    suspend fun completeFunction(callID: String, output: String) = serialized { if (!finished) completeFunctionImpl(callID, output) }
    suspend fun closeDelegation(callID: String, reason: VoiceDelegationCloseReason) = serialized { if (!finished) closeDelegationImpl(callID, reason) }
    suspend fun setMuted(muted: Boolean) = serialized { if (!finished) setMutedImpl(muted) }
    suspend fun retarget() = serialized { if (!finished) retargetImpl() }
    override suspend fun setAtEar(atEar: Boolean) = serialized {
        if (!finished) { lifecycle.atEar = atEar; audio?.setAtEar?.invoke(atEar) }
    }
    override suspend fun end() = serialized { finish(null) }

    /** Realtime/Gemini prepare audio first; Live overlaps audio with negotiation.
     * RTC always negotiates with its ADM inactive, then adopts the prepared engine
     * and releases local hardware before activating its own microphone.
     */
    internal suspend fun startAudioAndSocket(atEar: Boolean, audioClient: VoiceAudioClient, concurrently: Boolean = false): Flow<VoiceCallEvent> {
        check(!streamMade)
        if (!running()) throw CancellationException()
        lifecycle.atEar = atEar
        this.audioClient = audioClient
        try {
            coroutineScope {
                val startedAudio = async(start = CoroutineStart.UNDISPATCHED) {
                    val started = audioClient.start(atEar)
                    adoptAudio(started) // Cancellation while prewarm starts must still stop it.
                    adoptAudio(rtcAudio.adopt(started))
                }
                if (!concurrently) startedAudio.await()
                if (!running()) throw CancellationException()
                val socket = open(false)
                if (!running()) { discard(socket); throw CancellationException() }
                active = socket // Retain teardown responsibility while audio starts.
                startedAudio.await()
            }
            val socket = requireNotNull(active)
            activateAudio(socket)
            if (!running()) throw CancellationException()
            // Only an ear change made while the audio started.
            if (lifecycle.atEar != atEar) audio?.setAtEar?.invoke(lifecycle.atEar)
            startSocketEvents(socket)
            return makeEventStream(requireNotNull(audio))
        } catch (error: Throwable) {
            withContext(NonCancellable) { finish(error) }
            throw error
        }
    }

    private fun makeEventStream(audio: VoiceAudioSession): Flow<VoiceCallEvent> {
        streamMade = true
        wireAudio(audio)
        lifecycle.uplinkTask = scope.launch {
            while (isActive && !finished) {
                clock.sleep(250.milliseconds)
                val (next, change) = lifecycle.uplink.check(date.now())
                lifecycle.uplink = next
                if (change != null) reportConnectionQuality()
            }
        }
        return flow {
            try { for (event in events) emit(event) }
            finally { withContext(NonCancellable) { end() } }
        }
    }

    private suspend fun adoptAudio(started: VoiceAudioSession) {
        if (!running()) {
            withContext(NonCancellable) { started.stop() }
            throw CancellationException()
        }
        audio = started
    }
    private fun wireAudio(audio: VoiceAudioSession) {
        captureTask?.cancel(); audioEventTask?.cancel()
        captureTask = scope.launch { audio.capture.collect { if (!finished) forwardCapture(it) } }
        audioEventTask = scope.launch { audio.events.collect { if (!finished) handleAudio(it) } }
    }

    internal suspend fun openSocket(seeded: Boolean, connect: suspend () -> VoiceSocketConnection<E, C>, seed: suspend (VoiceSocketConnection<E, C>) -> Unit = {}): VoiceCallSocket<E, C, A> {
        if (!running()) throw CancellationException()
        val connection = try { connect() } catch (error: Throwable) {
            if (error !is CancellationException) diagnostics.recordVoiceSocket("voice_connect_failed", RealtimeTransportDiagnostic.of(error), error)
            throw error
        }
        try {
            if (!running()) throw CancellationException()
            if (seeded) seed(connection)
            return VoiceCallSocket(lifecycle.nextSocketID++, connection, date.now(), seeded)
        } catch (error: Throwable) {
            withContext(NonCancellable) { connection.close() }
            throw error
        }
    }

    // Attach identity before collecting: an immediately buffered session.started must
    // not be lost while open() is still returning the socket.
    internal fun startSocketEvents(socket: VoiceCallSocket<E, C, A>) {
        if (socket.task != null || finished) return
        socket.task = scope.launch {
            try {
                socket.connection.events.collect { if (!finished) handle(it, socket.id) }
                socketClosed(socket.id, null)
            } catch (error: CancellationException) { throw error
            } catch (error: Throwable) { socketClosed(socket.id, error) }
        }
    }

    internal suspend fun noteSessionConfirmed(expiresAt: Instant?) {
        active?.ready = true
        active?.expiresAt = expiresAt
        if (!lifecycle.hasConnected) {
            lifecycle.hasConnected = true
            audio?.let { connectedAudio = it; it.noteConnected() }
            emit(VoiceCallEvent.Connected)
        }
    }

    internal fun noteSocketAttached(resetLinkQuality: Boolean = false) {
        lifecycle.reconnectAttempts = 0
        if (resetLinkQuality) { lifecycle.linkPoor = false; reportConnectionQuality() }
        if (lifecycle.isReconnecting) {
            diagnostics.recordVoiceMark("voice_socket_reconnected")
            lifecycle.isReconnecting = false
            emit(VoiceCallEvent.Reconnected)
        }
    }

    internal suspend fun activateAudio(socket: VoiceCallSocket<E, C, A>) {
        if (!running()) { discard(socket); return }
        val start = socket.connection.startAudio
        if (start != null) {
            if (!audioUsesRTC && !rtcAudio.owns(audio)) { audio?.stop?.invoke(); audio = null }
            val previous = audio
            adoptAudio(start(lifecycle.atEar))
            audioUsesRTC = true
            if (audio !== previous && streamMade) wireAudio(requireNotNull(audio))
        } else if (audioUsesRTC) {
            // Release the ADM before ordinary capture. A live engine keeps the
            // exact same session/consumers; injected clients may replace theirs.
            val previous = audio
            val reused = rtcAudio.useWebSocket()
            if (reused != null) adoptAudio(reused)
            else {
                audio?.stop?.invoke(); audio = null
                adoptAudio(requireNotNull(audioClient).start(lifecycle.atEar))
            }
            audioUsesRTC = false
            if (audio !== previous && streamMade) wireAudio(requireNotNull(audio))
        }
        // Audio that joins after the connect (or replaces the engine) hears it once; a
        // rotation or reconnect on the same audio never replays the connect chord.
        val current = audio
        if (lifecycle.hasConnected && current != null && current !== connectedAudio) {
            connectedAudio = current
            current.noteConnected()
        }
        startSocketEvents(socket)
    }

    internal suspend fun discardStandby() {
        val dead = standby ?: return
        standby = null
        discard(dead)
    }
    internal suspend fun discardStandbyAndRescheduleRotation() {
        if (standby == null) return
        discardStandby()
        active?.takeIf { it.ready }?.let(::scheduleRotation)
    }
    internal fun scheduleRotation(socket: VoiceCallSocket<E, C, A>) {
        lifecycle.rotationTask?.cancel()
        val deadlines = timing.rotationDeadlines(socket.openedAt, socket.expiresAt, defaultSessionLimit)
        lifecycle.rotationTask = scope.launch {
            sleepUntil(deadlines.prepareAt)
            if (!running()) return@launch
            prepareStandby()
            sleepUntil(deadlines.forceAt)
            if (running()) swapToStandby(force = true)
        }
    }
    private suspend fun sleepUntil(time: Instant) {
        clock.sleep(maxOf(0L, java.time.Duration.between(date.now(), time).toMillis()).milliseconds)
    }
    internal suspend fun prepareStandby() {
        if (finished || standby != null || active == null) return
        val socket = try { open(true) } catch (error: CancellationException) { throw error } catch (_: Exception) { return }
        if (!running() || standby != null || active == null) { discard(socket); return }
        standby = socket
        startSocketEvents(socket)
    }
    internal suspend fun discard(socket: VoiceCallSocket<E, C, A>) {
        // A turn-boundary swap can run inside the retired socket's collector.
        // Closing its connection ends that collector after attachment completes.
        if (socket.task != currentCoroutineContext()[Job]) socket.task?.cancel()
        socket.connection.close()
    }
    internal suspend fun swapToStandby(force: Boolean = false) {
        val next = standby?.takeIf { it.ready } ?: return
        if (finished || active == null || (!force && !canSwapToStandby)) return
        diagnostics.recordVoiceMark(if (force) "voice_rotation_forced" else "voice_rotation_swapped")
        activate(next)
    }
    internal suspend fun socketClosed(id: Int, error: Throwable?) {
        if (standby?.id == id) { discardStandby(); return }
        socketLost(id, VoiceSessionLoss.Closed(error))
    }
    internal suspend fun socketLost(id: Int, loss: VoiceSessionLoss) {
        val lost = active?.takeIf { it.id == id } ?: return
        if (finished) return
        diagnostics.recordVoiceLoss(loss.expiredReason, loss.transportError, defaultDiagnosticDomain, expiredDiagnosticDomain, lost.ready)
        active = null
        val caller = currentCoroutineContext()[Job]
        if (lost.task != caller) lost.task?.cancel()
        lifecycle.rotationTask?.cancel(); lifecycle.rotationTask = null
        if (lifecycle.reconnectTask != caller) lifecycle.reconnectTask?.cancel()
        lifecycle.reconnectTask = null
        retireSessionState()
        if (!lifecycle.hasConnected) {
            finish(loss.callError)
            withContext(NonCancellable) { lost.connection.close() }
            return
        }
        val ready = standby?.takeIf { it.ready }
        if (ready != null) {
            diagnostics.recordVoiceMark("voice_standby_promoted")
            activate(ready)
            withContext(NonCancellable) { lost.connection.close() }
            return
        }
        val failed = standby
        standby = null
        failed?.task?.cancel()
        if (!lifecycle.isReconnecting) {
            lifecycle.isReconnecting = true
            emit(VoiceCallEvent.Reconnecting)
        }
        lifecycle.reconnectTask = scope.launch { reconnect(loss.transportError) }
        withContext(NonCancellable) { lost.connection.close(); failed?.connection?.close?.invoke() }
    }
    private suspend fun reconnect(error: Throwable?) {
        var lastError = error
        while (lifecycle.reconnectAttempts < timing.retryDelays.size) {
            clock.sleep(timing.retryDelays[lifecycle.reconnectAttempts++].seconds)
            if (!running()) return
            try { activate(open(true)); return }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Throwable) { if (!running()) return; lastError = failure }
        }
        diagnostics.recordVoiceSocket("voice_reconnect_exhausted", lastError?.let(RealtimeTransportDiagnostic::of)
            ?: RealtimeTransportDiagnostic(defaultDiagnosticDomain, 0), lastError)
        finish(RealtimeClientError.Dropped)
    }
    internal fun uplinkSendStarted() { lifecycle.uplink = lifecycle.uplink.sendStarted(date.now()) }
    internal fun uplinkSendFinished() {
        val (next, change) = lifecycle.uplink.sendFinished(date.now())
        lifecycle.uplink = next
        if (change != null) reportConnectionQuality()
    }
    internal fun setLinkPoor(poor: Boolean) { lifecycle.linkPoor = poor; reportConnectionQuality() }
    private fun reportConnectionQuality() {
        val poor = lifecycle.uplink.isPoor || lifecycle.linkPoor
        if (poor != lifecycle.reportedPoor) { lifecycle.reportedPoor = poor; emit(VoiceCallEvent.ConnectionPoor(poor)) }
    }

    internal suspend fun finish(error: Throwable?) {
        if (finished) return
        lifecycle.finished = true
        finishing()
        val sockets = listOfNotNull(active, standby)
        active = null; standby = null
        val audio = this.audio
        this.audio = null
        scope.cancel()
        withContext(NonCancellable) {
            try { audio?.stop?.invoke() } finally {
                events.close(error)
                rtcAudio.stop()
                for (socket in sockets) socket.connection.close()
            }
        }
    }
}
