package ai.opencode.feature.voice

import kotlin.math.*

/** RMS over 5 ms, sampled every millisecond, normalized to the loudest window. */
class VoiceCueEnvelope(samples: FloatArray, sampleRate: Double = PCM.sampleRate) {
    val levels: FloatArray
    val onset: Double
    val peak: Double
    val end: Double
    init {
        val hop = max(1, (sampleRate * step).toInt()); val half = max(1, (sampleRate * 0.0025).toInt())
        val energy = DoubleArray(samples.size + 1)
        for (i in samples.indices) energy[i + 1] = energy[i] + (samples[i] * samples[i]).toDouble()
        val raw = FloatArray(samples.size / hop + 1) { i ->
            val lo = max(0, i * hop - half); val hi = min(samples.size, i * hop + half)
            if (hi > lo) sqrt((energy[hi] - energy[lo]) / (hi - lo)).toFloat() else 0f
        }
        val top = raw.maxOrNull() ?: 0f
        levels = if (top > 0) FloatArray(raw.size) { raw[it] / top } else raw
        val peakIndex = max(0, levels.indexOfFirst { it == 1f }); peak = peakIndex * step
        val rise = (0..peakIndex).firstOrNull { levels[it] >= 0.5f } ?: 0
        onset = if (rise > 0) (rise - 1 + ((0.5f - levels[rise - 1]) / max(levels[rise] - levels[rise - 1], 1e-6f)).toDouble()) * step else 0.0
        end = max(0, levels.indexOfLast { it >= floor }) * step
    }
    fun level(at: Double): Float {
        if (levels.isEmpty()) return 0f
        val position = max(0.0, at / step); val index = position.toInt()
        if (index + 1 >= levels.size) return levels.last()
        val fraction = (position - index).toFloat()
        return levels[index] * (1 - fraction) + levels[index + 1] * fraction
    }
    companion object { const val step = 0.001; const val floor = 0.0316f }
}

/** Platform-independent events and curves; times are seconds from the anchor. */
data class VoiceHapticPattern(
    val events: List<Event> = emptyList(), val shaped: List<Event> = emptyList(),
    val intensity: List<Point> = emptyList(), val sharpness: List<Point> = emptyList(),
) {
    data class Event(val time: Double, val intensity: Float, val sharpness: Float, val duration: Double = 0.0, val decay: Double? = null) {
        val isTransient get() = duration == 0.0
        val end get() = time + duration
    }
    data class Point(val time: Double, val value: Float)
    val isEmpty get() = events.isEmpty() && shaped.isEmpty()
    val duration get() = (events + shaped).maxOfOrNull { it.end } ?: 0.0
    val transients get() = events.filter { it.isTransient }.map { it.time }
    fun late(by: Double): VoiceHapticPattern {
        if (by <= 0) return this
        fun shift(events: List<Event>) = events.mapNotNull { e ->
            if (e.isTransient) { if (e.time - by >= -0.03) e.copy(time = max(0.0, e.time - by)) else null }
            else if (e.end - by > 0.01) e.copy(time = max(0.0, e.time - by), duration = e.end - by - max(0.0, e.time - by)) else null
        }
        fun points(points: List<Point>): List<Point> {
            val first = points.indexOfFirst { it.time - by >= 0 }
            if (first < 0) return points.lastOrNull()?.let { listOf(Point(0.0, it.value)) } ?: emptyList()
            val shifted = points.drop(first).map { Point(it.time - by, it.value) }.toMutableList()
            if (first > 0 && shifted[0].time > 0) {
                val a = points[first - 1]; val b = points[first]; val fraction = ((by - a.time) / max(b.time - a.time, 1e-6)).toFloat()
                shifted.add(0, Point(0.0, a.value + (b.value - a.value) * fraction))
            }
            return shifted
        }
        return VoiceHapticPattern(shift(events), shift(shaped), points(intensity), points(sharpness))
    }
    companion object { const val maxCurvePoints = 16 }
}

/**
 * The voice call's haptics, designed to its sound cues and scanner timeline. Each cue's events
 * are placed by measuring the bundled sound itself ([VoiceCueEnvelope] of the chosen pack's
 * cue), offset by how long a buffer scheduled now takes to be heard (`audioDelay`). Events tied
 * to the picture (a turnaround, a wavefront reaching a button) come from the scanner's own
 * geometry, offset by [displayDelay].
 */
object VoiceHapticDesign {
    const val displayDelay = 0.025
    const val minimumGap = 0.06
    private val envelopes = java.util.concurrent.ConcurrentHashMap<Pair<VoiceCallSoundPack, VoiceCallCue>, VoiceCueEnvelope>()
    /** The envelope of [cue] in [pack] at Wisp's level, measured once. */
    fun envelope(cue: VoiceCallCue, pack: VoiceCallSoundPack = VoiceCallSoundBank.pack): VoiceCueEnvelope =
        envelopes.getOrPut(pack to cue) { VoiceCueEnvelope(VoiceCallSoundBank.rawSamples(cue, pack)) }
    /** The turnaround ticks keep the old pings' notes: G4 on the right, D5 on the left. */
    fun pitch(cue: DotScopeSimulation.Cue): Float = when (cue) {
        DotScopeSimulation.Cue.sweepRight -> 392f
        DotScopeSimulation.Cue.sweepLeft -> 587.33f
        DotScopeSimulation.Cue.connected -> VoiceCallSoundBank.pack.rootHz
        DotScopeSimulation.Cue.ended -> VoiceCallSoundBank.pack.rootHz * 2
    }
    fun sharpness(forPitch: Float): Float {
        val low = 250f; val high = 1046.5f
        return 0.15f + 0.75f * log2(forPitch.coerceIn(low, high) / low) / log2(high / low)
    }
    /** A crisp tick as the connecting beam turns around (under the connecting loop, when a cue scheduled then is heard), crisper on the left. */
    fun tick(cue: DotScopeSimulation.Cue, audioDelay: Double) = VoiceHapticPattern(events = listOf(
        VoiceHapticPattern.Event(audioDelay, 0.5f, sharpness(pitch(cue)))))
    fun connect(audioDelay: Double, origin: Float, overshoot: Float = 0f, width: Float): VoiceHapticPattern {
        val envelope = envelope(VoiceCallCue.connect)
        val start = audioDelay + envelope.onset; val end = audioDelay + envelope.end
        val events = mutableListOf(VoiceHapticPattern.Event(start, 0.6f, sharpness(pitch(DotScopeSimulation.Cue.connected))))
        val steps = VoiceHapticPattern.maxCurvePoints - 2
        val intensity = (0..steps).map { i -> val time = start + (end - start) * i / steps; VoiceHapticPattern.Point(time, sqrt(envelope.level(time - audioDelay))) } + VoiceHapticPattern.Point(end + 0.01, 0f)
        val sharpness = (0..6).map { i -> val p = i / 6f; VoiceHapticPattern.Point(displayDelay + DotScopeSimulation.Scan.bloomDuration * p, -0.2f + 0.45f * DotScopeSimulation.Scan.passPosition(p)) }
        val arrivals = DotScopeSimulation.Scan.bloomArrivals(origin, overshoot, width)
        val taps = listOf(arrivals.mute.toDouble(), arrivals.end.toDouble()).map { displayDelay + it }.filter { it > start + minimumGap }.sorted()
        if (taps.size == 2 && taps[1] - taps[0] < minimumGap) events.add(VoiceHapticPattern.Event((taps[0] + taps[1]) / 2, 0.7f, 0.8f))
        else taps.forEach { events.add(VoiceHapticPattern.Event(it, 0.55f, 0.8f)) }
        return VoiceHapticPattern(events, listOf(VoiceHapticPattern.Event(start, 0.75f, 0.35f, end - start)), intensity, sharpness)
    }
    fun hangUp(drawn: Boolean, origin: Float, overshoot: Float = 0f, width: Float): VoiceHapticPattern {
        val arrival = displayDelay + DotScopeSimulation.Scan.endingArrival(drawn, origin, overshoot, width)
        return VoiceHapticPattern(events = listOf(VoiceHapticPattern.Event(arrival, 0.6f, 0.2f), VoiceHapticPattern.Event(arrival, 0.45f, 0.1f, 0.16, 0.14)))
    }
    /** A soft rumble under the hang-up cue that fades with it and dulls as it falls, from an octave over the pack's root to its thump under it. */
    fun glide(audioDelay: Double): VoiceHapticPattern {
        val envelope = envelope(VoiceCallCue.hangUp); val start = audioDelay + envelope.onset; val end = audioDelay + envelope.end
        val glideStart = pitch(DotScopeSimulation.Cue.ended); val glideEnd = VoiceCallSoundBank.pack.rootHz * 0.41f
        val top = sharpness(glideStart); val length = max(0.01, end - start)
        val ratio = glideEnd / glideStart
        val intensity = mutableListOf<VoiceHapticPattern.Point>(); val sharpness = mutableListOf<VoiceHapticPattern.Point>()
        for (i in 0..10) {
            val time = start + (end - start) * i / 10; val soundTime = time - audioDelay
            intensity.add(VoiceHapticPattern.Point(time, sqrt(envelope.level(soundTime))))
            val pitch = glideStart * ratio.pow(min(1.0, max(0.0, soundTime - envelope.onset) / length).toFloat())
            sharpness.add(VoiceHapticPattern.Point(time, 0.6f * (sharpness(pitch) - top)))
        }
        intensity.add(VoiceHapticPattern.Point(end + 0.01, 0f))
        return VoiceHapticPattern(shaped = listOf(VoiceHapticPattern.Event(start, 0.5f, 0.4f, end - start)), intensity = intensity, sharpness = sharpness)
    }
    fun control(haptic: VoiceCallHaptic): VoiceHapticPattern? {
        val event = when (haptic) {
            VoiceCallHaptic.muted -> VoiceHapticPattern.Event(0.0, 0.6f, 0.15f)
            VoiceCallHaptic.unmuted -> VoiceHapticPattern.Event(0.0, 0.65f, 0.65f)
            VoiceCallHaptic.callStarted -> VoiceHapticPattern.Event(0.0, 0.75f, 0.5f)
            else -> return null
        }
        return VoiceHapticPattern(events = listOf(event))
    }
}
