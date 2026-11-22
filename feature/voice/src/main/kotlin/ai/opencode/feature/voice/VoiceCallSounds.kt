package ai.opencode.feature.voice

import ai.opencode.core.dependencies.*
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.math.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the call's sound cues ([VoiceCallCue]) and the scanner's haptics from a single
 * call-scoped clock and shares the active stage's start time with `DotScopeView`, so visuals and
 * sound stay in sync. As in Wisp: the call start plays over the connecting loop; on connect the
 * loop lets go as the connect cue plays; tool cues play while live.
 */
class VoiceCallSounds(
    private val clock: Clock = dependency(Clock),
    private val haptics: VoiceHapticPlaying = dependency(VoiceHapticEngine),
    /** Runs the connecting loop and delayed fallbacks; tests pass one on their virtual time. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    @Serializable data class ScheduledCue(val cue: DotScopeSimulation.Cue, val time: Double)
    @Serializable data class MeterSample(val time: Double, val rms: Float, val peak: Float)
    @Serializable data class RecordedHaptic(val haptic: VoiceCallHaptic, val time: Double)
    @Serializable data class PlaybackTelemetry(val buffers: Int, val underruns: Int, val renderedGaps: Int, val renderedGapMilliseconds: Int)
    @Serializable data class TelemetrySnapshot(val scheduledCues: List<ScheduledCue>, val meterSamples: List<MeterSample>, val hapticEvents: List<RecordedHaptic> = emptyList(), val playback: PlaybackTelemetry? = null)
    data class FirstCue(val cue: VoiceCallCue, val milliseconds: Int, val audioReadyMilliseconds: Int) {
        val logLine get() = "Call cue: first ${cue.rawValue} heard $milliseconds ms after the tap (audio up at $audioReadyMilliseconds ms)"
    }
    data class EndingShape(val drawn: Boolean, val origin: Float, val overshoot: Float, val width: Float)
    private val lock = Any()
    private var callID: UUID? = null
    private var startedAt = clock.now()
    private var stage: DotScopeSimulation.Stage? = null
    private var stageStartedAt = clock.now()
    private var scheduler = DotScopeSimulation.SoundScheduler()
    private var playHandler: ((FloatArray) -> Unit)? = null
    /** Starts (samples) or lets go of (null) the connecting loop. */
    private var loopHandler: ((FloatArray?) -> Unit)? = null
    /** Plays a tool cue unless speech is playing. */
    private var toolHandler: ((FloatArray) -> Unit)? = null
    private var toolThrottle = VoiceToolCueThrottle()
    private var ringTask: Job? = null
    private val scheduledCues = mutableListOf<ScheduledCue>()
    private val meterSamples = mutableListOf<MeterSample>()
    private val hapticEvents = mutableListOf<RecordedHaptic>()
    private var tappedAt: Duration? = null
    private var cueLatency = 0.0
    private var scopeWidth = 400f
    private var scheduledTickPass: Int? = null
    private var hangUpHapticAt: Duration? = null
    private var playback: PlaybackTelemetry? = null
    private var audioReadyAfter: Double? = null
    private var reportFirstCue: ((FirstCue) -> Unit)? = null
    private var lastCancel: Duration? = null
    private var fallbackHangUp: Job? = null
    private val fallbackTicks = mutableListOf<Job>()
    val isTelemetryEnabled get() = !VoiceLabEnvironment.string("OPENCODE_LAB_VOICE_SOUND_LOG").isNullOrEmpty()
    fun snapshot() = synchronized(lock) { TelemetrySnapshot(scheduledCues.toList(), meterSamples.toList(), hapticEvents.toList(), playback) }
    fun recordPlayback(playback: PlaybackTelemetry) = synchronized(lock) { this.playback = playback }
    fun recordHaptic(haptic: VoiceCallHaptic) {
        val now = clock.now()
        synchronized(lock) {
            when (haptic) {
                VoiceCallHaptic.callStarted -> { tappedAt = now; if (callID == null) { stage = DotScopeSimulation.Stage.connecting; stageStartedAt = now; hangUpHapticAt = null } }
                VoiceCallHaptic.ended -> { hangUpHapticAt = now; tappedAt = null; if (callID == null) { stage = DotScopeSimulation.Stage.ending; stageStartedAt = now } }
                else -> Unit
            }
            hapticEvents.add(RecordedHaptic(haptic, max(0.0, seconds(now - startedAt))))
        }
        flushDebugLogAsync()
    }
    /** Reducer's endPressed uses this at the press, then voice(ended) at the
     * landing. endTapped (agent/notification) uses only voice(ended). */
    fun cancelPressed() {
        synchronized(lock) { lastCancel = clock.now() }
        dependency(HapticsClient).play(Haptic.cancel)
    }
    fun voice(haptic: VoiceCallHaptic) = synchronized(lock) {
        val ending = if (haptic == VoiceCallHaptic.ended) endingShape() else null
        recordHaptic(haptic)
        if (haptic == VoiceCallHaptic.callStarted) { fallbackHangUp?.cancel(); fallbackHangUp = null }
        when (haptic) {
            VoiceCallHaptic.ended -> ending?.let { voiceHangUp(it.drawn, it.origin, it.overshoot, it.width, clock.now()) }
            VoiceCallHaptic.muted, VoiceCallHaptic.unmuted -> {
                val pattern = VoiceHapticDesign.control(haptic)!!
                if (!haptics.isCallActive || !haptics.play(pattern, clock.now())) fallback(haptic)
            }
            else -> fallback(haptic)
        }
    }
    private fun fallback(haptic: VoiceCallHaptic) { dependency(HapticsClient).play(haptic.fallback) }
    private fun cancelVoiceTicks() {
        haptics.cancel(VoiceHapticEngine.Kind.tick)
        fallbackTicks.forEach { it.cancel() }; fallbackTicks.clear()
    }
    /** Whether the tick will play: false once the call moved on. */
    internal fun voiceTick(cue: DotScopeSimulation.Cue, at: Duration, audioDelay: Double): Boolean {
        if (!ticksAreDue) return false
        val pattern = VoiceHapticDesign.tick(cue, audioDelay)
        if (haptics.play(pattern, at, VoiceHapticEngine.Kind.tick)) return true
        val due = at + (pattern.transients.firstOrNull() ?: 0.0).seconds
        fallbackTicks.removeAll { it.isCompleted }
        fallbackTicks.add(scope.launch {
            clock.sleep(maxOf(Duration.ZERO, due - clock.now()))
            if (ticksAreDue) fallback(VoiceCallHaptic.sweepPass)
        })
        return true
    }
    internal fun voiceHangUp(drawn: Boolean, origin: Float, overshoot: Float, width: Float, at: Duration): Boolean {
        cancelVoiceTicks()
        val pattern = VoiceHapticDesign.hangUp(drawn, origin, overshoot, width)
        val due = at + (pattern.transients.firstOrNull() ?: 0.0).seconds
        val press = lastCancel; val pressed = press != null && at - press < 1.seconds
        if (pressed && due - press < VoiceHapticDesign.minimumGap.seconds) return false
        if (haptics.isCallActive && haptics.play(pattern, at)) return true
        if (pressed) return false // The fallback would repeat the very same cancel.
        fallbackHangUp?.cancel()
        fallbackHangUp = scope.launch { clock.sleep(maxOf(Duration.ZERO, due - clock.now())); fallback(VoiceCallHaptic.ended) }
        return false
    }
    val sinceCallStart get() = synchronized(lock) { clock.now() - startedAt }
    val ticksAreDue get() = synchronized(lock) { callID != null && stage == DotScopeSimulation.Stage.connecting && hangUpHapticAt == null }
    fun noteScopeWidth(width: Float) { if (width > 0) synchronized(lock) { scopeWidth = width } }
    fun endingShape(): EndingShape = synchronized(lock) {
        val elapsed = stageElapsed(DotScopeSimulation.Stage.connecting) ?: return@synchronized EndingShape(true, 0f, 0f, scopeWidth)
        val beam = DotScopeSimulation.Scan.connectingBeam(elapsed.toFloat())
        EndingShape(false, beam.progress, beam.overshoot, scopeWidth)
    }
    fun stageElapsed(requestedStage: DotScopeSimulation.Stage): Double? = synchronized(lock) {
        val now = clock.now()
        if (stage == requestedStage) max(0.0, seconds(now - stageStartedAt))
        else if (requestedStage == DotScopeSimulation.Stage.connecting && callID == null) recentTap(now)?.let { max(0.0, seconds(now - it)) }
        else null
    }
    fun cancelPendingStart(began: Duration = clock.now()) = synchronized(lock) {
        if (callID == null && (tappedAt == null || tappedAt!! <= began)) { tappedAt = null; if (stage == DotScopeSimulation.Stage.connecting) stage = null }
    }
    suspend fun <T> pendingStart(body: suspend () -> T): T {
        val began = clock.now()
        try { return body() } catch (error: Throwable) { cancelPendingStart(began); throw error }
    }
    private fun recentTap(now: Duration) = tappedAt?.takeIf { now - it < tapWindow }
    private fun recordCues(cues: List<DotScopeSimulation.Cue>, elapsed: Double, recordHaptics: Boolean = true) {
        for (cue in cues) { scheduledCues.add(ScheduledCue(cue, elapsed)); if (recordHaptics) hapticEvents.add(RecordedHaptic(VoiceCallHaptic.forCue(cue), elapsed)) }
    }
    /** Reports the call's first cue once. */
    private fun takeFirstCue(cue: VoiceCallCue, elapsed: Double) {
        val ready = audioReadyAfter; val report = reportFirstCue
        if (ready != null && report != null) {
            audioReadyAfter = null; reportFirstCue = null
            val first = FirstCue(cue, ((elapsed + cueLatency) * 1000).roundToInt(), (ready * 1000).roundToInt())
            scope.launch { report(first) }
        }
    }
    /**
     * Starts the call-scoped connecting clock once the audio engine is ready, on the timeline
     * the scanner has run since the tap, and plays the call start over the connecting loop. The
     * scanner's turnarounds tick (haptics only) from the next one the beam reaches. `latency` is
     * how long a cue buffer scheduled now takes to be heard.
     */
    fun startCallClock(
        id: UUID,
        latency: Double = 0.0,
        reportFirstCue: ((FirstCue) -> Unit)? = null,
        loop: ((FloatArray?) -> Unit)? = null,
        playTool: ((FloatArray) -> Unit)? = null,
        play: (FloatArray) -> Unit,
    ) {
        val now = clock.now()
        synchronized(lock) {
            ringTask?.cancel()
            val tapped = recentTap(now); val origin = tapped ?: now
            callID = id; startedAt = origin; stage = DotScopeSimulation.Stage.connecting; stageStartedAt = origin
            scheduler = DotScopeSimulation.SoundScheduler().apply { setStage(DotScopeSimulation.Stage.connecting, seconds(now - origin)) }
            playHandler = play; loopHandler = loop; toolHandler = playTool; toolThrottle = VoiceToolCueThrottle()
            cueLatency = latency; scheduledTickPass = null; hangUpHapticAt = null
            scheduledCues.clear(); meterSamples.clear(); hapticEvents.clear(); playback = null
            if (tapped != null) hapticEvents.add(RecordedHaptic(VoiceCallHaptic.callStarted, 0.0))
            audioReadyAfter = tapped?.let { seconds(now - it) }; this.reportFirstCue = reportFirstCue
            takeFirstCue(VoiceCallCue.callStart, seconds(now - origin))
            tappedAt = null
            ringTask = scope.launch { runConnectingLoop(id) }
        }
        val start = VoiceCallSoundBank.samples(VoiceCallCue.callStart)
        if (start.isNotEmpty()) play(start)
        val bed = VoiceCallSoundBank.samples(VoiceCallCue.connectingLoop)
        if (bed.isNotEmpty()) loop?.invoke(bed)
        haptics.prepare()
    }
    /** The agent called a tool: its cue ([VoiceCallCue.tool]) plays while the call is live, spaced as Wisp spaces them. */
    fun toolCalled(name: String, code: String?) {
        val cue = VoiceCallCue.tool(name, code) ?: return
        val now = clock.now()
        val play = synchronized(lock) {
            val handler = toolHandler
            if (callID == null || stage != DotScopeSimulation.Stage.live || handler == null || !toolThrottle.admit(cue, seconds(now - startedAt))) null else handler
        } ?: return
        val raw = VoiceCallSoundBank.samples(cue)
        if (raw.isNotEmpty()) play(raw)
    }

    /**
     * The app captured its own screen. While a call owns the audio (tapped, connecting or live)
     * the shutter never goes to the system: it plays as a tool cue on the call's engine once live
     * (echo-cancelled, never over the agent's speech) and not before. Returns whether a call owns it.
     */
    fun shutter(): Boolean {
        val now = clock.now()
        var play: ((FloatArray) -> Unit)? = null
        val owned = synchronized(lock) {
            val owned = callID != null || recentTap(now) != null
            if (callID != null && stage == DotScopeSimulation.Stage.live) play = toolHandler
            owned
        }
        play?.let { handler ->
            val raw = VoiceShutterSound.samples()
            if (raw.isNotEmpty()) handler(raw)
        }
        return owned
    }

    fun noteConnected(id: UUID) {
        val now = clock.now()
        synchronized(lock) {
            if (callID != id) return
            ringTask?.cancel(); ringTask = null
            val elapsed = if (stage == DotScopeSimulation.Stage.connecting) seconds(now - stageStartedAt).toFloat() else 0f
            val beam = DotScopeSimulation.Scan.connectingBeam(elapsed)
            if (stage != DotScopeSimulation.Stage.live) { stage = DotScopeSimulation.Stage.live; stageStartedAt = now }
            scheduler.setStage(DotScopeSimulation.Stage.live)
            val cues = scheduler.poll(); recordCues(cues, seconds(now - startedAt))
            cancelVoiceTicks()
            // The connect cue plays once the loop has let go.
            val latency = cueLatency + (if (loopHandler == null) 0.0 else loopRelease)
            loopHandler?.invoke(null)
            cues.forEach { cue -> VoiceCallSoundBank.samples(cue).takeIf { it.isNotEmpty() }?.let { playHandler?.invoke(it) } }
            if (DotScopeSimulation.Cue.connected in cues && !haptics.play(VoiceHapticDesign.connect(latency, beam.progress, beam.overshoot, scopeWidth), now)) fallback(VoiceCallHaptic.connected)
        }
        flushDebugLogAsync()
    }
    fun endCallClock(id: UUID, playEndCue: Boolean): FloatArray? = synchronized(lock) {
        if (callID != id) return@synchronized null
        val now = clock.now(); ringTask?.cancel(); ringTask = null; callID = null; playHandler = null; reportFirstCue = null
        val loop = loopHandler; loopHandler = null; toolHandler = null
        stage = DotScopeSimulation.Stage.ending; stageStartedAt = now; scheduler.setStage(DotScopeSimulation.Stage.ending)
        val cues = if (playEndCue) scheduler.poll() else emptyList()
        recordCues(cues, seconds(now - startedAt), false)
        cancelVoiceTicks()
        if (DotScopeSimulation.Cue.ended in cues && (hangUpHapticAt?.let { now - it > 2.seconds } ?: true)) {
            hangUpHapticAt = now; hapticEvents.add(RecordedHaptic(VoiceCallHaptic.ended, seconds(now - startedAt)))
            voiceHangUp(true, 0f, 0f, scopeWidth, now)
        }
        loop?.invoke(null)
        haptics.endCall(); flushDebugLogAsync()
        if (playEndCue && DotScopeSimulation.Cue.ended in cues) VoiceCallSoundBank.samples(DotScopeSimulation.Cue.ended).takeIf { it.isNotEmpty() } else null
    }
    fun noteEndTailStarted(latency: Double) { haptics.play(VoiceHapticDesign.glide(latency), clock.now()) }
    fun cancelActiveTail() { AudioSessionGeneration.bump() }
    private suspend fun runConnectingLoop(id: UUID) {
        while (currentCoroutineContext().isActive) {
            val wait = synchronized(lock) {
                if (callID != id || stage != DotScopeSimulation.Stage.connecting) return
                val ring = scheduler.nextRing ?: return
                if (scheduledTickPass != ring.pass) {
                    scheduledTickPass = ring.pass
                    voiceTick(ring.cue, stageStartedAt + ring.elapsed.seconds, cueLatency)
                }
                scheduler.nextRingDelay ?: return
            }
            clock.sleep(max(1.0, ceil(wait * 1000)).milliseconds)
            synchronized(lock) {
                if (callID != id || stage != DotScopeSimulation.Stage.connecting) return
                val now = clock.now(); scheduler.advance(max(0.0, seconds(now - stageStartedAt) - scheduler.elapsed))
                val cues = scheduler.poll(); recordCues(cues, seconds(now - startedAt))
                cues.forEach { cue -> VoiceCallSoundBank.samples(cue).takeIf { it.isNotEmpty() }?.let { playHandler?.invoke(it) } }
                if (cues.isNotEmpty()) flushDebugLogAsync()
            }
        }
    }
    fun recordMixerBuffer(buffer: FloatArray, count: Int = buffer.size) {
        if (count <= 0) return
        var sum = 0f; var peak = 0f
        for (i in 0 until count) { val v = abs(buffer[i]); sum += v * v; peak = max(peak, v) }
        val rms = sqrt(sum / count); if (rms <= 5e-4f) return
        synchronized(lock) {
            meterSamples.add(MeterSample(seconds(clock.now() - startedAt), rms, peak))
            if (meterSamples.size > 512) meterSamples.subList(0, meterSamples.size - 512).clear()
        }
    }
    fun flushDebugLogAsync() {
        val path = VoiceLabEnvironment.string("OPENCODE_LAB_VOICE_SOUND_LOG")?.takeIf { it.isNotEmpty() } ?: return
        val snap = snapshot()
        scope.launch(Dispatchers.IO) { runCatching { File(path).writeText(Json.encodeToString(snap)) } }
    }
    companion object Key : DependencyKey<VoiceCallSounds> {
        val tapWindow = 30.seconds
        /** How long the connecting loop takes to let go; the connect cue plays after it. */
        const val loopRelease = 0.03
        override val liveValue by lazy { VoiceCallSounds() }
        override val testValue get() = VoiceCallSounds(clock = dependency(Clock), haptics = VoiceHapticEngine.testValue)
        val shared get() = dependency(Key)
        private fun seconds(duration: Duration) = duration.inWholeNanoseconds / 1e9
    }
}
