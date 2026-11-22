package ai.opencode.feature.voice

import ai.opencode.core.haptics.Haptic
import kotlinx.serialization.Serializable
import kotlin.math.*

/** Pixel-exact LED grid. An odd row count puts a flat trace on one row. */
data class DotScopeGrid(val pixelWidth: Int, val pixelHeight: Int, val scale: Float, val requestedPitch: Float = 3.5f) {
    // Kotlin `round` would round 10.5 to 10.
    val pitch = max(3f, (requestedPitch * scale).roundToInt().toFloat())
    val width = pixelWidth / scale
    val height = pixelHeight / scale
    val rows = max(5, floor(pixelHeight / pitch).toInt() - 1).let { if (it % 2 == 0) it - 1 else it }
    val columns = max(1, floor(pixelWidth / pitch).toInt())
    val originX = floor((pixelWidth - columns * pitch) / 2)
    val originY = floor((pixelHeight - rows * pitch) / 2)
    val count get() = columns * rows
    fun columnX(column: Float) = (originX + (column + 0.5f) * pitch) / scale
}

@Serializable
enum class VoiceCallHaptic {
    callStarted, sweepPass, connected, muted, unmuted, ended;
    val fallback: Haptic get() = when (this) {
        callStarted -> Haptic.confirm
        connected -> Haptic.success
        ended -> Haptic.cancel
        sweepPass, muted, unmuted -> Haptic.selection
    }
    companion object {
        fun forCue(cue: DotScopeSimulation.Cue) = when (cue) {
            DotScopeSimulation.Cue.sweepLeft, DotScopeSimulation.Cue.sweepRight -> sweepPass
            DotScopeSimulation.Cue.connected -> connected
            DotScopeSimulation.Cue.ended -> ended
        }
        fun forMute(isMuted: Boolean) = if (isMuted) muted else unmuted
    }
}

data class ScopeVector2(val x: Float = 0f, val y: Float = 0f)

/** Plain, fixed-240-Hz simulation. The renderer reads a packed xyzw light field;
 * no per-frame reducer actions, platform graphics objects or audio side effects.
 *
 * The call only shares the retired call bar's sweep geometry ([Scan]): its cues and haptics are
 * still timed from where the beam would turn. Nothing here draws a call. */
class DotScopeSimulation {
    /** The call's stage on its cue clock, for `VoiceCallSounds`. */
    enum class Stage { connecting, live, ending }
    @Serializable enum class Cue { sweepRight, sweepLeft, connected, ended }

    class SoundScheduler(stage: Stage? = null) {
        var stage = stage; private set
        var elapsed = 0.0; private set
        var hasConnected = false; private set
        private val pending = mutableListOf<Cue>()
        fun setStage(newStage: Stage, elapsed: Double = 0.0) {
            if (newStage == stage) return
            val previous = stage; stage = newStage; this.elapsed = max(0.0, elapsed)
            when (newStage) {
                Stage.connecting -> Unit
                Stage.live -> { if (previous == Stage.connecting && !hasConnected) pending.add(Cue.connected); hasConnected = true }
                Stage.ending -> if (hasConnected) pending.add(Cue.ended)
            }
        }
        fun advance(by: Double) {
            if (by <= 0) return
            val previous = elapsed; elapsed += by
            if (stage != Stage.connecting || hasConnected || elapsed < pingTime(1)) return
            val first = floor((previous - passOffset) / passDuration).toInt() + 1
            val last = floor((elapsed - passOffset) / passDuration).toInt()
            if (last >= max(1, first)) pending.add(cue(last)) // Never burst after a stalled clock.
        }
        fun poll(): List<Cue> = pending.toList().also { pending.clear() }
        data class Ring(val pass: Int, val cue: Cue, val elapsed: Double)
        val nextRing: Ring? get() {
            if (stage != Stage.connecting || hasConnected) return null
            val pass = max(1, floor((elapsed - passOffset) / passDuration).toInt() + 1)
            return Ring(pass, cue(pass), pingTime(pass))
        }
        val nextRingDelay get() = nextRing?.let { max(0.001, it.elapsed - elapsed) }
        companion object {
            val passDuration = Scan.sweepPass.toDouble()
            const val passOffset = 0.02
            fun pingTime(pass: Int) = passOffset + pass * passDuration
            fun cue(forPass: Int) = if (forPass % 2 == 1) Cue.sweepRight else Cue.sweepLeft
        }
    }

    class Voice {
        var level = 0f; var reservoir = 0f; var energy = 0f; var kick = 0f; var kickVelocity = 0f
        val rawBands = FloatArray(bandCount); val bands = FloatArray(bandCount)
    }
    class Trace(val phases: FloatArray) {
        var envelope = 0f; var amplitude = 0f; var amplitudeVelocity = 0f
        var glowFollower = 0f; var glowSpring = 0f; var glowSpringVelocity = 0f; var glowBody = 0f; var glow = 0f
        val history = FloatArray(historyLength * 5); var head = 0
    }
    var windowInsets = ScopeVector2(windowInset, windowInset)
    val you = Voice()
    val agent = Voice()
    private val traces = arrayOf(Trace(floatArrayOf(0.1f, 1.7f, 3.3f, 4.6f)), Trace(floatArrayOf(2.4f, 0.6f, 5.1f, 1.9f)))
    var live = 0f; private set
    var carrier = 0f; private set
    var time = 0.0; private set
    private var pending = 0.0
    private val ys = arrayOf(FloatArray(0), FloatArray(0))
    private val brightness = arrayOf(FloatArray(0), FloatArray(0))
    private val previousYs = arrayOf(FloatArray(0), FloatArray(0))
    private val beamSpeeds = arrayOf(FloatArray(0), FloatArray(0))
    private var instant = FloatArray(0); private var core = FloatArray(0)
    private var head = FloatArray(0); private var tail = FloatArray(0); private var lit = FloatArray(0)
    private var rasterDistances = FloatArray(0); private var rasterNearest = IntArray(0)
    private var beamGrid: DotScopeGrid? = null
    private var primed = false
    private var steps = 0
    /**
     * The retired call bar's sweep, kept because the call's cues and haptics are still timed from
     * it (`VoiceCallSounds`, `VoiceHapticDesign`). Nothing draws it.
     */
    object Scan {
        data class Beam(val progress: Float, val along: Float, val overshoot: Float, val head: Float)
        data class Arrivals(val mute: Float, val end: Float)
        const val sweepPass = 0.55f; const val bloomDuration = 0.48f; const val edgeInset = 62f
        const val launchInset = 28f; const val launchIgnition = 0.24f
        const val endingPass = 0.48f; const val endingStart = 44f; const val endingStop = 36f
        fun beamX(progress: Float, width: Float, leftInset: Float = edgeInset, rightInset: Float = edgeInset) = leftInset + max(0f, width - leftInset - rightInset) * progress.coerceIn(0f, 1f)
        fun passPosition(p: Float) = 0.5f - 0.5f * cos(PI.toFloat() * p.coerceIn(0f, 1f))
        fun connectingHead(along: Float) = 2.5f + sin(PI.toFloat() * along.coerceIn(0f, 1f))
        @Suppress("UNUSED_PARAMETER")
        fun connectingBeam(elapsed: Float, leftInset: Float = edgeInset, rightInset: Float = edgeInset): Beam {
            val t = max(0f, elapsed); val pass = t / sweepPass; val along = pass - floor(pass); val index = floor(pass).toInt()
            val head = connectingHead(along)
            if (index > 0) return Beam(passPosition(if (index % 2 == 1) along else 1 - along), along, 0f, head)
            val left = 1 - 0.5f * (sin(PI.toFloat() / 2 * along) + passPosition(along))
            return Beam(left, along, (rightInset - launchInset) * left, head * (0.35f + 0.65f * smoothstep(0f, launchIgnition, t)))
        }
        fun passTime(covering: Float) = acos(1 - 2 * covering.coerceIn(0f, 1f)) / PI.toFloat()
        fun bloomArrivals(origin: Float, overshoot: Float = 0f, width: Float): Arrivals {
            val x0 = beamX(origin, width) + overshoot; val span = width + 20
            return Arrivals(bloomDuration * passTime((x0 - windowInset) / span), bloomDuration * passTime((width - windowInset - x0) / span))
        }
        fun endingArrival(drawn: Boolean, origin: Float, overshoot: Float = 0f, width: Float): Float {
            val start = if (drawn) endingStart else beamX(origin, width) + overshoot
            val span = width - endingStop - start
            return if (span <= 0) 0f else endingPass * passTime((width - windowInset - start) / span)
        }
    }
    val glow get() = ScopeVector2(traces[0].glow, traces[1].glow)
    val breath get() = 0.5f + 0.5f * sin(time.toFloat() * tau / 5.5f)
    fun advance(by: Double, you: Float, agent: Float, youBands: FloatArray? = null, agentBands: FloatArray? = null, connected: Boolean) {
        pending += min(by, 0.25)
        while (pending >= stepDuration - 1e-9) { step(you, agent, youBands, agentBands, connected); pending -= stepDuration }
    }
    private fun step(youLevel: Float, agentLevel: Float, youBands: FloatArray?, agentBands: FloatArray?, connected: Boolean) {
        val h = stepDuration.toFloat()
        fillBands(you.rawBands, youBands, youLevel, time.toFloat()); fillBands(agent.rawBands, agentBands, agentLevel, time.toFloat())
        stepVoice(you, youLevel, h); stepVoice(agent, agentLevel, h)
        live += ((if (connected) 1f else 0f) - live) * (1 - exp(-h / 0.55f))
        tick(traces[0], you, h); tick(traces[1], agent, h)
        carrier = (carrier + h * tau * (0.14f + 0.22f * max(you.energy, agent.energy))) % tau
        time += stepDuration; steps++
        if (steps % beamEvery == 0) beamGrid?.let { beam(it, true) }
    }
    private fun tick(f: Trace, v: Voice, h: Float) {
        f.envelope += (v.level - f.envelope) * (1 - exp(-h / if (v.level > f.envelope) 0.022f else 0.15f))
        val w = tau * 6.5f; val z = 0.52f
        f.amplitudeVelocity += (-w * w * (f.amplitude - f.envelope) - 2 * z * w * f.amplitudeVelocity) * h
        f.amplitude += f.amplitudeVelocity * h
        f.glowFollower += (v.level - f.glowFollower) * (1 - exp(-h / if (v.level > f.glowFollower) 0.045f else 0.13f))
        val gw = tau * 3.6f; val gz = 0.5f
        f.glowSpringVelocity += (-gw * gw * (f.glowSpring - f.glowFollower) - 2 * gz * gw * f.glowSpringVelocity) * h
        f.glowSpring += f.glowSpringVelocity * h
        f.glowBody += (v.level - f.glowBody) * (1 - exp(-h / if (v.level > f.glowBody) 0.15f else 0.7f))
        val swell = 0.8f * max(0f, f.glowSpring - 0.25f) + 0.04f * max(-0.5f, v.kick)
        f.glow = min(1.15f, max(0f, swell) + 0.42f * f.glowBody + 0.2f * v.energy) * live
        val go = 0.6f + 1.1f * min(1f, f.envelope * 1.6f)
        var mean = 0f
        for (c in 0..3) { f.phases[c] = (f.phases[c] + h * tau * speeds[c] * go) % tau; for (j in 0..3) mean += v.bands[c * 4 + j] / 16 }
        val o = f.head * 5; val a = max(0f, f.amplitude) * live; f.history[o] = a
        for (c in 0..3) { var group = 0f; for (j in 0..3) group += v.bands[c * 4 + j] / 4
            f.history[o + 1 + c] = a * base[c] * (0.35f + 0.65f * min(2f, group / (mean + 0.06f))) }
        f.head = (f.head + 1) % historyLength
    }
    /** Four floats per dot: user, agent, freshness, unused. Caller owns/reuses the field. */
    fun render(grid: DotScopeGrid, into: FloatArray) {
        require(into.size >= grid.count * 4)
        if (grid != beamGrid) { beamGrid = grid; primed = false }
        if (!primed) beam(grid, false)
        lit.copyInto(into)
    }
    private fun beam(grid: DotScopeGrid, decays: Boolean) {
        val samples = (grid.columns - 1) * subsamples + 1
        if (!primed || ys[0].size != samples) {
            for (k in 0..1) { ys[k] = FloatArray(samples); brightness[k] = FloatArray(samples); previousYs[k] = FloatArray(samples); beamSpeeds[k] = FloatArray(samples) }
            instant = FloatArray(grid.count * 2); core = FloatArray(grid.count * 2); head = FloatArray(grid.count * 2); tail = FloatArray(grid.count * 2); lit = FloatArray(grid.count * 4)
            rasterDistances = FloatArray(grid.rows); rasterNearest = IntArray(grid.rows)
        }
        buildTraces(grid, samples)
        val hb = beamEvery * stepDuration.toFloat()
        for (k in 0..1) {
            for (s in 0 until samples) {
                if (decays && primed) beamSpeeds[k][s] += (abs(ys[k][s] - previousYs[k][s]) / hb - beamSpeeds[k][s]) * (1 - exp(-hb / 0.05f))
                previousYs[k][s] = ys[k][s]
            }
            for (s in 0 until samples) {
                val slope = (ys[k][min(samples - 1, s + 1)] - ys[k][max(0, s - 1)]) * subsamples / 2
                val lingering = 1 / sqrt((1 + beamSpeeds[k][s] / 22) * (1 + 0.22f * slope * slope))
                brightness[k][s] *= 1 - dwell + dwell * lingering
            }
        }
        raster(grid, samples)
        val headKeep = if (decays) exp(-hb / headDecay) else 1f
        val tailKeep = if (decays) exp(-hb / tailDecay) else 1f
        for (n in 0 until grid.count) {
            var now = 0f; var total = 0f
            for (k in 0..1) {
                val m = n * 2 + k
                head[m] = max(head[m] * headKeep, core[m]); tail[m] = max(tail[m] * tailKeep, core[m])
                val light = max(instant[m], max(head[m], tailBrightness * tail[m]))
                lit[n * 4 + k] = light; now += instant[m]; total += light
            }
            lit[n * 4 + 2] = min(1f, now / max(total, 1e-4f))
        }
        primed = true
    }
    private fun raster(grid: DotScopeGrid, samples: Int) {
        val dx = 1f / subsamples
        val distances = rasterDistances; val nearest = rasterNearest
        for (k in 0..1) {
            val ys = ys[k]; val brightness = brightness[k]
            for (i in 0 until grid.columns) {
                val s0 = max(0, (i - 3) * subsamples); val s1 = min(samples - 1, (i + 3) * subsamples)
                distances.fill(1e9f); nearest.fill(i * subsamples)
                // A segment's geometry is identical for every row in this column. Visit
                // segments in the same order (including ties), but compute it just once.
                for (s in s0 until s1) {
                    val ay = ys[s]; val dy = ys[s + 1] - ay
                    val px = i - s * dx; val projectionX = px * dx
                    val lengthSquared = dx * dx + dy * dy
                    for (j in 0 until grid.rows) {
                        val py = j - ay
                        val t = ((projectionX + py * dy) / lengthSquared).coerceIn(0f, 1f)
                        val ex = px - t * dx; val ey = py - t * dy; val d2 = ex * ex + ey * ey
                        if (d2 < distances[j]) { distances[j] = d2; nearest[j] = s }
                    }
                }
                for (j in 0 until grid.rows) {
                    val best = distances[j]; val bright = brightness[nearest[j]]
                    val m = (j * grid.columns + i) * 2 + k; val onBeam = exp(-best / 0.25f)
                    instant[m] = min(1.2f, onBeam + 0.5f * exp(-sqrt(best) / 0.8f)) * bright
                    core[m] = onBeam * bright
                }
            }
        }
    }
    private fun buildTraces(grid: DotScopeGrid, samples: Int) {
        val width = grid.width
        val mid = (grid.rows - 1) / 2f; val half = mid - 0.55f
        val rest = (0.55f + 0.35f * breath) * (0.4f + 0.6f * live)
        val leftInset = windowInsets.x; val rightInset = windowInsets.y
        val leftCalm = leftInset + flatten
        val rightCalm = rightInset + flatten
        for (k in 0..1) {
            val f = traces[k]; val sign = if (k == 0) 1 else -1
            val delayInset = windowInset
            for (s in 0 until samples) {
                val x = grid.columnX(s.toFloat() / subsamples)
                val own = if (k == 0) x else width - x; val xn = own / width
                val ends = min(smoothstep(leftInset, leftCalm, x), smoothstep(rightInset, rightCalm, width - x))
                val reach = 1 - 0.8f * smoothstep(100f, width - 16, own); val delay = max(0f, own - delayInset) / velocity
                val a = past(f, delay, 0); var spread = 0f
                for (c in 0..3) spread += past(f, delay, 1 + c) * sin(tau * spatial[c] * xn - f.phases[c])
                val helix = (rest + half * 0.55f * a * reach) * sin(tau * helix * (x / width) - carrier)
                val y = half * tanh((helix + half * 0.7f * spread * reach) / half) * ends
                ys[k][s] = mid - sign * y
                brightness[k][s] = (0.3f + 0.7f * min(1f, a * 1.8f)) * (0.3f + 0.7f * (1 - smoothstep(110f, width - 6, own))) * (0.45f + 0.55f * ends)
            }
        }
    }
    companion object {
        const val exitDuration = 0.55; const val bandCount = 16; const val stepDuration = 1.0 / 240
        private const val historyLength = 512; private const val rate = 240f
        private val spatial = floatArrayOf(3.3f, 5.7f, 9.1f, 13.7f)
        private val speeds = floatArrayOf(0.8f, 1.25f, 1.85f, 2.6f)
        private val base = floatArrayOf(0.55f, 0.42f, 0.3f, 0.2f)
        private const val helix = 2.2f; private const val velocity = 430f
        const val windowInset = 50f; const val windowFade = 30f; private const val flatten = 46f
        private const val beamEvery = 2; private const val headDecay = 0.15f; private const val tailDecay = 0.6f
        private const val tailBrightness = 0.55f; private const val dwell = 0.55f; private const val subsamples = 3
        private val tau = 2 * PI.toFloat()
        fun still(connected: Boolean) = DotScopeSimulation().apply { live = if (connected) 1f else 0f; time = 5.5 * 3 / 4 }
        fun smoothstep(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
        private fun past(f: Trace, delay: Float, c: Int): Float {
            val d = min((historyLength - 2).toFloat(), delay * rate); val i = d.toInt(); val k = d - i
            val a = ((f.head - 1 - i) % historyLength + historyLength) % historyLength; val b = (a - 1 + historyLength) % historyLength
            return f.history[a * 5 + c] * (1 - k) + f.history[b * 5 + c] * k
        }
        private fun fillBands(out: FloatArray, bands: FloatArray?, level: Float, t: Float) {
            if (bands != null && bands.size >= bandCount) { bands.copyInto(out, endIndex = bandCount); return }
            for (j in 0 until bandCount) out[j] = level * (0.55f + 0.45f * sin(j * 0.9f + t * 7 + sin(t * 3.1f + j)))
        }
        private fun stepVoice(v: Voice, raw: Float, h: Float) {
            val previous = v.level
            v.level += (raw - v.level) * (1 - exp(-h / if (raw > v.level) 0.035f else 0.095f))
            val rise = max(0f, (v.level - previous) / h)
            v.reservoir += (1.75f * v.level * v.level - v.reservoir / 1.8f) * h; v.energy = 1 - exp(-v.reservoir * 1.45f)
            val w = tau * 1.3f; val z = 0.22f
            v.kickVelocity += (-w * w * v.kick - 2 * z * w * v.kickVelocity + 15 * rise + 2.2f * v.level) * h
            v.kick += v.kickVelocity * h
            for (j in 0 until bandCount) { val x = v.rawBands[j]; val y = v.bands[j]; v.bands[j] = y + (x - y) * (1 - exp(-h / if (x > y) 0.025f else 0.14f)) }
        }
    }
}
