@file:Suppress("EnumEntryName", "LocalVariableName")

package ai.opencode.feature.voice

import ai.opencode.core.tuning.TunableChoice
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// The pieces `WispOrbSim` steps at 240 Hz: a one-for-one port of Wisp's `wisp-orb` crate (voice.rs,
// mode.rs, power.rs, coupling.rs, choreo.rs). Names and constants follow the Rust so the three can
// be read side by side.

object WispOrb {
    const val step: Float = 1f / 240f
    const val history = 512
    const val attackSecs: Float = 0.035f
    const val releaseSecs: Float = 0.095f
    const val kickOmega: Float = (2 * PI * 1.3).toFloat()
    const val kickZeta: Float = 0.22f
    const val cometRevPerSec: Float = 1.1f
    const val breathPeriod: Float = 5.5f
    val speeds = floatArrayOf(0.8f, 1.25f, 1.85f, 2.6f)
    val base = floatArrayOf(0.55f, 0.42f, 0.30f, 0.20f)
    val userSeeds = floatArrayOf(0.15f, 1.72f, 3.31f, 4.64f)
    val agentSeeds = floatArrayOf(2.42f, 0.63f, 5.12f, 1.91f)

    /** Clear margin (dp) around the diameter for the shadow, glow and rim. */
    const val bleed: Float = 10f
    const val tau: Float = (2 * PI).toFloat()
}

/** Wisp's `OrbState`, without the two the phone never shows (Pulsing's beacon rings and Error's red wash). */
enum class WispOrbState {
    sleeping, connecting, listening, speaking, bothSpeaking, thinking, confirming;

    val isRest: Boolean get() = this == sleeping
}

/** How the dot sphere hands motion from one state to the next. Wisp ships Harmonic Twist; Voice Carry is the other coupled medium. */
enum class WispOrbCoupling(override val rawValue: String) : TunableChoice {
    harmonicTwist("harmonicTwist"),
    voiceCarry("voiceCarry"),
}

/** Thinking's running-tool look (`ToolChoreoKind`). */
enum class WispOrbChoreo {
    reasoning, scan, spark;

    companion object {
        private val scanWords = setOf(
            "read", "reading", "grep", "glob", "look", "looking", "search", "searching", "context", "find",
            "websearch", "webfetch", "fetch", "fetching", "inspect", "inspecting", "scan", "scanning",
            "check", "checking", "snapshot", "snapshotting", "list", "listing", "browse", "browsing",
            "doctor", "xray",
        )
        private val sparkWords = setOf(
            "edit", "editing", "write", "writing", "run", "running", "click", "clicking", "move", "moving",
            "bash", "shell", "computer", "act", "acting", "choose", "execute", "executing", "spotlight",
            "spotlighting", "guide", "guiding", "patch", "patching", "control", "controlling", "update",
            "updating", "type", "typing", "press", "pressing", "scroll", "scrolling", "drag", "dragging",
            "mac", "seer",
        )
        private val reasoningWords = setOf(
            "thinking", "think", "delegating", "delegate", "subagent", "researching", "research", "asking",
            "planning", "reasoning", "pondering", "task", "agent", "sessions", "say", "confirm",
        )

        /** `classify_orb_tool_choreo`: the first keyword token in the tool name wins. */
        fun classify(tool: String?): WispOrbChoreo {
            val raw = tool?.trim(' ', '\t')?.takeIf { it.isNotEmpty() } ?: return reasoning
            val tokens = raw.split { !(it.code < 128 && it.isLetterOrDigit()) }
            for (token in tokens) {
                if (token.isEmpty()) continue
                val word = token.lowercase()
                if (word in scanWords) return scan
                if (word in sparkWords) return spark
                if (word in reasoningWords) return reasoning
            }
            return reasoning
        }

        private inline fun String.split(isSeparator: (Char) -> Boolean): List<String> {
            val out = mutableListOf<String>()
            val current = StringBuilder()
            for (c in this) {
                if (isSeparator(c)) {
                    if (current.isNotEmpty()) out += current.toString()
                    current.setLength(0)
                } else {
                    current.append(c)
                }
            }
            if (current.isNotEmpty()) out += current.toString()
            return out
        }
    }
}

/** A colour (or any three floats). */
data class WispVec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: WispVec3) = WispVec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: WispVec3) = WispVec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = WispVec3(x * s, y * s, z * s)

    companion object {
        val zero = WispVec3(0f, 0f, 0f)
    }
}

internal fun wispSmoothstep(a: Float, b: Float, x: Float): Float {
    val t = min(max((x - a) / (b - a), 0f), 1f)
    return t * t * (3 - 2 * t)
}

internal fun wispMix3(a: WispVec3, b: WispVec3, t: Float): WispVec3 = a + (b - a) * min(max(t, 0f), 1f)

internal fun wispClamp(v: Float, lo: Float, hi: Float): Float = min(max(v, lo), hi)

internal fun wispEuclid(v: Float, m: Float): Float {
    val r = v % m
    return if (r < 0) r + m else r
}

/** Critically damped spring (ζ = 1), integrated analytically. */
data class WispSpring(var x: Float = 0f, var v: Float = 0f) {
    /** Steps toward [target] with visual [duration] seconds (ω = 2π / duration). */
    fun settle(target: Float, duration: Float, h: Float) {
        val w = WispOrb.tau / max(duration, 1e-3f)
        val y = x - target
        val c = v + w * y
        val decay = exp(-w * h)
        x = target + (y + c * h) * decay
        v = (v - w * c * h) * decay
    }

    companion object {
        fun at(x: Float) = WispSpring(x, 0f)
    }
}

// Voice

/** 35 ms attack / 95 ms release envelope, onset kick and band follower. */
data class WispVoiceChannel(
    var level: Float = 0f,
    var reservoir: Float = 0f,
    var energy: Float = 0f,
    var kick: Float = 0f,
    var kickVelocity: Float = 0f,
    var band: Float = 0f,
) {
    fun setStill(rawInput: Float) {
        val lvl = if (rawInput.isFinite()) wispClamp(rawInput, 0f, 1f) else 0f
        level = lvl
        reservoir = 1.75f * lvl * lvl * 1.8f
        energy = 1 - exp(-reservoir * 1.45f)
        kick = 0f
        kickVelocity = 0f
        band = lvl
    }

    fun step(rawInput: Float, h: Float) {
        val raw = if (rawInput.isFinite()) wispClamp(rawInput, 0f, 1f) else 0f
        val previous = level
        val tau = if (raw > level) WispOrb.attackSecs else WispOrb.releaseSecs
        level += (raw - level) * (1 - exp(-h / tau))
        val rise = max((level - previous) / h, 0f)
        reservoir = max(reservoir + (1.75f * level * level - reservoir / 1.8f) * h, 0f)
        energy = 1 - exp(-reservoir * 1.45f)
        val w = WispOrb.kickOmega
        val z = WispOrb.kickZeta
        kickVelocity += (-w * w * kick - 2 * z * w * kickVelocity + 15 * rise + 2.2f * level) * h
        kick = wispClamp(kick + kickVelocity * h, -1.5f, 2.5f)
        val bandTau = if (raw > band) 0.025f else 0.14f
        band += (raw - band) * (1 - exp(-h / bandTau))
    }
}

/**
 * The 512-sample delayed wave history a voice leaves across the lens. Only channels 0
 * (amplitude) and 1 (first harmonic) are ever read; they're interleaved in [history].
 */
class WispWaveTrace(seeds: FloatArray) {
    var envelope = 0f
    var amplitude = 0f
    var amplitudeVelocity = 0f
    var glowFast = 0f
    var glowSpring = 0f
    var glowSpringVelocity = 0f
    var glowSlow = 0f
    var glow = 0f
    var phases: FloatArray = seeds.copyOf()

    /** (amplitude, first harmonic) per step, ring buffer, interleaved. */
    val history = FloatArray(WispOrb.history * 2)
    var head = 0
        private set

    fun copy(): WispWaveTrace = WispWaveTrace(phases).also {
        it.envelope = envelope
        it.amplitude = amplitude
        it.amplitudeVelocity = amplitudeVelocity
        it.glowFast = glowFast
        it.glowSpring = glowSpring
        it.glowSpringVelocity = glowSpringVelocity
        it.glowSlow = glowSlow
        it.glow = glow
        history.copyInto(it.history)
        it.head = head
    }

    private fun write(index: Int, voice: WispVoiceChannel, amplitude: Float, gate: Float) {
        val emphasis = 0.35f + 0.65f * min(voice.band / (voice.band + 0.06f), 2f)
        val amp = max(amplitude, 0f) * gate
        history[index * 2] = amp
        history[index * 2 + 1] = amp * WispOrb.base[0] * emphasis
    }

    fun setStill(voice: WispVoiceChannel, gate: Float, seeds: FloatArray) {
        envelope = voice.level
        amplitude = voice.level
        amplitudeVelocity = 0f
        glowFast = voice.level
        glowSpring = voice.level
        glowSpringVelocity = 0f
        glowSlow = voice.level
        val swell = 0.8f * max(glowSpring - 0.22f, 0f)
        glow = min(swell + 0.42f * glowSlow + 0.22f * voice.energy, 1.25f) * gate
        phases = seeds.copyOf()
        for (i in 0 until WispOrb.history) write(i, voice, amplitude, gate)
        head = 0
    }

    fun tick(voice: WispVoiceChannel, gate: Float, motionSpeed: Float, phosphor: Float, response: Float, h: Float) {
        fun follow(value: Float, target: Float, rise: Float, fall: Float): Float {
            val tau = if (target > value) rise else fall
            return value + (target - value) * (1 - exp(-h / tau))
        }
        val release = wispClamp(0.06f + 0.12f * phosphor, 0.05f, 0.30f)
        envelope = follow(envelope, voice.level, 0.022f, release)
        val w = WispOrb.tau * wispClamp(response, 0.5f, 6f)
        val z = 0.52f
        amplitudeVelocity += (-w * w * (amplitude - envelope) - 2 * z * w * amplitudeVelocity) * h
        amplitude += amplitudeVelocity * h

        glowFast = follow(glowFast, voice.level, 0.045f, 0.13f)
        val gw = WispOrb.tau * 3.6f
        val gz = 0.5f
        glowSpringVelocity += (-gw * gw * (glowSpring - glowFast) - 2 * gz * gw * glowSpringVelocity) * h
        glowSpring += glowSpringVelocity * h
        glowSlow = follow(glowSlow, voice.level, 0.15f, 0.70f)

        val swell = 0.8f * max(glowSpring - 0.22f, 0f) + 0.05f * max(voice.kick, -0.5f)
        glow = min(max(swell, 0f) + 0.42f * glowSlow + 0.22f * voice.energy, 1.25f) * gate

        val go = 0.6f + 1.1f * min(envelope * 1.6f, 1f)
        for (i in 0 until 4) {
            phases[i] = wispEuclid(phases[i] + h * WispOrb.tau * WispOrb.speeds[i] * go * motionSpeed, WispOrb.tau)
        }
        write(head, voice, amplitude, gate)
        head = (head + 1) % WispOrb.history
    }

    /** The amplitude `i` steps before the newest, so `past(delay)` on the GPU lerps `i = delay / STEP` and `i + 1`. */
    fun newestAmplitude(i: Int): Float = history[slot(i) * 2]

    /** The first harmonic `i` steps before the newest. */
    fun newestHarmonic(i: Int): Float = history[slot(i) * 2 + 1]

    private fun slot(i: Int): Int {
        val n = WispOrb.history
        return (head + 2 * n - 1 - i) % n
    }
}

// Mode

data class WispModeMix(
    var sleep: Float = 0f,
    var comet: Float = 0f,
    var think: Float = 0f,
    var confirm: Float = 0f,
    var live: Float = 0f,
    var muted: Float = 0f,
    var uGain: Float = 0f,
    var aGain: Float = 0f,
    val vel: FloatArray = FloatArray(8),
) {
    fun follow(target: WispModeMix, reduceMotion: Boolean, h: Float) {
        if (reduceMotion) {
            vel.fill(0f)
            muted = target.muted
            fun ease(v: Float, goal: Float): Float {
                val next = v + (goal - v) * (1 - exp(-h / 0.055f))
                return if (abs(next - goal) < 5e-4f) goal else next
            }
            sleep = ease(sleep, target.sleep)
            comet = ease(comet, target.comet)
            think = ease(think, target.think)
            confirm = ease(confirm, target.confirm)
            live = ease(live, target.live)
            uGain = ease(uGain, target.uGain)
            aGain = ease(aGain, target.aGain)
            return
        }
        fun spring(value: Float, i: Int, goal: Float, dur: Float): Float {
            val s = WispSpring(value, vel[i])
            s.settle(goal, dur, h)
            return if (abs(s.x - goal) < 5e-4f && abs(s.v) < 2e-3f) {
                vel[i] = 0f
                goal
            } else {
                vel[i] = s.v
                s.x
            }
        }
        sleep = spring(sleep, 0, target.sleep, 0.38f)
        comet = spring(comet, 1, target.comet, 0.34f)
        think = spring(think, 2, target.think, 0.34f)
        confirm = spring(confirm, 3, target.confirm, 0.30f)
        muted = spring(muted, 4, target.muted, 0.30f)
        live = spring(live, 5, target.live, 0.36f)
        uGain = spring(uGain, 6, target.uGain, 0.32f)
        aGain = spring(aGain, 7, target.aGain, 0.32f)
    }

    val activeGate: Float get() = wispClamp(0.25f + 0.75f * live, 0f, 1f)

    fun effectiveInputs(state: WispOrbState, user: Float, agent: Float, reduceMotion: Boolean): Pair<Float, Float> {
        if (reduceMotion) {
            return when (state) {
                WispOrbState.listening -> 0.36f to 0f
                WispOrbState.speaking -> 0f to 0.42f
                WispOrbState.bothSpeaking -> 0.38f to 0.40f
                else -> 0f to 0f
            }
        }
        return user * uGain to agent * aGain
    }

    fun copy(): WispModeMix = WispModeMix(sleep, comet, think, confirm, live, muted, uGain, aGain, vel.copyOf())

    override fun equals(other: Any?): Boolean = other is WispModeMix &&
        sleep == other.sleep && comet == other.comet && think == other.think &&
        confirm == other.confirm && live == other.live && muted == other.muted &&
        uGain == other.uGain && aGain == other.aGain && vel.contentEquals(other.vel)

    override fun hashCode(): Int = listOf(sleep, comet, think, confirm, live, muted, uGain, aGain).hashCode() * 31 +
        vel.contentHashCode()

    companion object {
        fun gains(state: WispOrbState): Pair<Float, Float> = when (state) {
            WispOrbState.sleeping -> 0.38f to 0.08f
            WispOrbState.connecting -> 0.25f to 0.25f
            WispOrbState.listening -> 1.0f to 0.35f
            WispOrbState.speaking -> 0.35f to 1.0f
            WispOrbState.bothSpeaking -> 1.0f to 1.0f
            WispOrbState.thinking -> 0.30f to 0.20f
            WispOrbState.confirming -> 0.35f to 0.20f
        }

        fun target(state: WispOrbState, muted: Boolean): WispModeMix {
            val (u, a) = gains(state)
            fun on(case: WispOrbState) = if (state == case) 1f else 0f
            return WispModeMix(
                sleep = on(WispOrbState.sleeping),
                comet = on(WispOrbState.connecting),
                think = on(WispOrbState.thinking),
                confirm = on(WispOrbState.confirming),
                live = if (state in liveStates) 1f else 0f,
                muted = if (muted) 1f else 0f,
                uGain = u,
                aGain = a,
            )
        }

        private val liveStates = setOf(
            WispOrbState.listening, WispOrbState.speaking, WispOrbState.bothSpeaking,
            WispOrbState.thinking, WispOrbState.confirming,
        )

        /** `state_palette` with split thinking (every coupled medium). */
        fun palette(state: WispOrbState, running: Float): Pair<WispVec3, WispVec3> = when (state) {
            WispOrbState.sleeping -> WispVec3(0.52f, 0.70f, 0.65f) to WispVec3(0.84f, 0.93f, 0.90f)
            WispOrbState.connecting -> WispVec3(0.56f, 0.85f, 0.95f) to WispVec3(0.93f, 0.98f, 1.00f)
            WispOrbState.listening -> WispVec3(0.64f, 0.90f, 0.96f) to WispVec3(0.97f, 0.99f, 1.00f)
            WispOrbState.thinking ->
                wispMix3(WispVec3(0.55f, 0.72f, 0.98f), WispVec3(0.66f, 0.54f, 0.98f), running) to
                    wispMix3(WispVec3(0.93f, 0.96f, 1.00f), WispVec3(0.94f, 0.89f, 1.00f), running)
            WispOrbState.speaking -> WispVec3(0.44f, 0.91f, 0.68f) to WispVec3(0.99f, 0.93f, 0.72f)
            WispOrbState.bothSpeaking -> WispVec3(0.56f, 0.92f, 0.82f) to WispVec3(0.99f, 0.96f, 0.82f)
            WispOrbState.confirming -> WispVec3(0.96f, 0.35f, 0.28f) to WispVec3(1.00f, 0.78f, 0.60f)
        }
    }
}

// Power

/** Wake bloom and hang-up contraction of the sphere inside the fixed lattice. */
class WispPowerSim {
    var scale = WispSpring.at(0.76f)
    var gatherFrom: Float = 0.76f
    var wakeAge: Float? = null
    var sleepAge: Float? = null
    var coolEmber: Float = 0f

    fun snap(state: WispOrbState, restScale: Float) {
        val target = targetScale(state, restScale)
        scale = WispSpring.at(target)
        gatherFrom = target
        wakeAge = null
        sleepAge = null
        coolEmber = 0f
    }

    /** Returns a forward angular-velocity impulse for the swirl. */
    @Suppress("UNUSED_PARAMETER")
    fun onStateChange(prev: WispOrbState, next: WispOrbState, maxEnergy: Float, restScale: Float): Float {
        if (prev.isRest && !next.isRest) {
            gatherFrom = scale.x
            wakeAge = 0f
            return 2.35f
        }
        if (!prev.isRest && next.isRest) {
            sleepAge = 0f
            coolEmber = min(max(coolEmber, 0.40f + 0.36f * wispClamp(maxEnergy, 0f, 1f)), 0.84f)
        }
        return 0f
    }

    fun kick(v: Float) {
        scale.v = min(scale.v + v, 1.4f)
    }

    fun tick(h: Float, state: WispOrbState, restScale: Float) {
        val restTarget = targetScale(state, restScale)
        val wake = wakeAge
        if (wake != null) {
            val age = wake + h
            if (age < anticipate) {
                scale.settle(max(gatherFrom - gatherDip, 0.5f), gatherDuration, h)
            } else {
                scale.settle(1f, wakeSpring, h)
            }
            wakeAge = if (age >= wakeWave && abs(scale.x - 1) < 8e-4f) null else age
        } else {
            scale.settle(restTarget, if (state.isRest) sleepSpring else wakeSpring, h)
        }
        sleepAge?.let { val age = it + h; sleepAge = if (age >= sleepWave) null else age }
        if (coolEmber > 0) {
            coolEmber *= exp(-h / 0.22f)
            if (coolEmber < 1e-3f) coolEmber = 0f
        }
    }
    /** (scale, wake age, sleep age, cool ember); ages are -1 when idle. */
    fun frame(reduceMotion: Boolean): FloatArray {
        val s = wispClamp(scale.x, 0.5f, 1.08f)
        if (reduceMotion) return floatArrayOf(s, -1f, -1f, 0f)
        return floatArrayOf(s, wakeAge ?: -1f, sleepAge ?: -1f, coolEmber)
    }

    companion object {
        const val anticipate: Float = 0.035f
        const val gatherDip: Float = 0.038f
        const val gatherDuration: Float = 0.042f
        const val wakeSpring: Float = 0.36f
        const val sleepSpring: Float = 0.42f
        const val wakeWave: Float = 0.52f
        const val sleepWave: Float = 0.68f

        fun targetScale(state: WispOrbState, restScale: Float): Float =
            if (state.isRest) wispClamp(restScale, 0.55f, 1f) else 1f

        /** `PowerFrame::ambient_boost`. */
        fun ambientBoost(f: FloatArray): Float {
            var wake = 0f
            if (f[1] >= anticipate) {
                val t = f[1] - anticipate
                wake = 0.28f * wispSmoothstep(0f, 0.09f, t) * (1 - wispSmoothstep(0.18f, 0.46f, t))
            }
            val sleepRise = if (f[2] >= 0) wispSmoothstep(0f, 0.08f, f[2]) else 1f
            return wake + 0.22f * f[3] * sleepRise
        }
    }
}

// Coupling

data class WispToolPulse(val axis: WispVec3, var age: Float, val strength: Float) {
    val env: Float
        get() {
            if (strength <= 0 || age >= life) return 0f
            return strength * wispSmoothstep(0f, 0.06f, age) * (1 - wispSmoothstep(0.26f * life, life, age))
        }

    companion object {
        const val life: Float = 0.78f
        val dead get() = WispToolPulse(WispVec3(0f, 0f, 1f), life, 0f)
    }
}

/** The shared wave medium (`CouplingSim`), Voice Carry and Harmonic Twist. */
class WispCouplingSim {
    var momentum = 0f
    var release = 0f
    var speakGate = WispSpring()
    var carry = WispSpring()
    var twist = WispSpring()
    var spin = 0f
    var pushU = WispSpring()
    var pushA = WispSpring()
    var radU = 0f
    var radA = 0f
    var memoryU = 0f
    var holdGate = WispSpring()
    var running = WispSpring()
    val tiltU = arrayOf(WispSpring(), WispSpring())
    val tiltA = arrayOf(WispSpring(), WispSpring())
    var open = WispSpring.at(1f)
    var bandU = WispSpring()
    var bandA = WispSpring()
    var bandPhaseU = 0f
    var bandPhaseA = 0f
    var precess = 0f
    var wasThinking = false
    var wasRunning = false
    var seenToolSeq: Int = 0
    var cadenceClock = 0f
    var pulseSeq: Int = 0
    val pulses = arrayOf(WispToolPulse.dead, WispToolPulse.dead)

    private fun fire(axis: WispVec3, strength: Float) {
        val slot = if (pulses[0].env <= pulses[1].env) 0 else 1
        pulses[slot] = WispToolPulse(axis, 0f, strength)
        cadenceClock = 0f
    }

    private fun tickCadence(h: Float, thinking: Boolean, workingRunning: Boolean, toolSeq: Int) {
        val activeRun = thinking && workingRunning
        running.settle(if (activeRun) 1f else 0f, 0.38f, h)
        for (i in 0 until 2) pulses[i].age = min(pulses[i].age + h, WispToolPulse.life)
        if (activeRun && !wasRunning) {
            fire(WispVec3(0f, 0f, 1f), 0.78f)
        } else if (activeRun && toolSeq != seenToolSeq) {
            pulseSeq += 1
            val a = (pulseSeq.toUInt().toFloat()) * golden
            fire(WispVec3(0.24f * cos(a), 0.24f * sin(a), 0.970_773f), 0.66f)
        } else if (activeRun) {
            cadenceClock += h
            if (cadenceClock >= cadence) {
                pulseSeq += 1
                val a = (pulseSeq.toUInt().toFloat()) * golden
                fire(WispVec3(0.18f * cos(a), 0.18f * sin(a), 0.983_667f), 0.38f)
            }
        } else {
            cadenceClock = 0f
        }
        wasRunning = activeRun
        seenToolSeq = toolSeq
    }

    /** One fixed step; returns the extra forward phase velocity. */
    fun tick(
        h: Float, mode: WispOrbCoupling, state: WispOrbState, workingRunning: Boolean, toolSeq: Int,
        user: WispVoiceChannel, agent: WispVoiceChannel, userAmp: Float,
    ): Float {
        val listening = state == WispOrbState.listening || state == WispOrbState.bothSpeaking
        val thinking = state == WispOrbState.thinking
        val speaking = state == WispOrbState.speaking || state == WispOrbState.bothSpeaking
        tickCadence(h, thinking, workingRunning, toolSeq)

        val du = min(user.energy + 0.22f * max(user.kick, 0f), 1.4f)
        val da = min(agent.energy + 0.22f * max(agent.kick, 0f), 1.4f)
        val charge = if (listening) 1.5f * du else if (thinking) 0.22f else 0f
        val tau = if (thinking) 3.4f else if (speaking) 0.72f else if (listening) 2.4f else 1.0f
        speakGate.settle(if (speaking) 1f else 0f, 0.28f, h)
        val speakW = wispClamp(speakGate.x, 0f, 1f)
        release = (momentum / 0.72f) * speakW
        momentum = wispClamp(momentum + (charge - momentum / tau) * h, 0f, 1.6f)
        val stored = min(momentum, 1f)
        val run = wispClamp(running.x, 0f, 1f)

        val amp = max(userAmp, 0f)
        val hold = if (thinking) (0.22f + 0.14f * stored) * (1 - 0.35f * run) else 0f
        val target = max(amp, hold)
        val memTau = if (target > memoryU) 0.06f else if (thinking) 0.85f else 0.30f
        memoryU += (target - memoryU) * (1 - exp(-h / memTau))
        holdGate.settle(if (thinking) 1f else 0f, 0.28f, h)

        val rel = min(release, 1.2f)
        when (mode) {
            WispOrbCoupling.voiceCarry -> {
                val carryGoal = if (thinking) (0.55f - 0.28f * run) + 0.35f * stored
                else if (speaking) 0.18f + 0.58f * rel
                else if (listening) 0.26f * stored else 0.20f * stored
                carry.settle(carryGoal, 0.42f, h)
                val twistGoal = if (thinking) (0.66f - 0.22f * run) + 0.36f * stored
                else if (listening && !speaking) 0.18f * du else 0f
                twist.settle(twistGoal, 0.50f, h)
                spin = wispEuclid(spin + h * (0.65f + 0.35f * run) * twist.x, WispOrb.tau)
                pushU.settle(if (thinking) 0.22f + 0.18f * stored else 0.9f * du, 0.24f, h)
                pushA.settle(if (thinking) 0.35f * run else da + 0.8f * rel, 0.24f, h)
                val thinkURate = if (thinking) 1.1f + 0.7f * stored else 0f
                val thinkARate = if (thinking) 1.3f * run else 0f
                radU = wispEuclid(radU + h * (0.9f + 4.5f * du + thinkURate), WispOrb.tau)
                radA = wispEuclid(radA + h * (0.9f + 4.5f * da + 3.0f * rel + thinkARate), WispOrb.tau)
            }
            WispOrbCoupling.harmonicTwist -> {
                val carryGoal = if (thinking) (0.58f - 0.30f * run) + 0.34f * stored
                else if (speaking) 0.16f + 0.52f * rel
                else if (listening) 0.28f * stored else 0.16f * stored
                carry.settle(carryGoal, 0.42f, h)
                tickHarmonicTwist(h, state, du, da, stored, rel, run)
            }
        }
        wasThinking = thinking
        return carry.x
    }

    private fun tickHarmonicTwist(h: Float, state: WispOrbState, du: Float, da: Float, stored: Float, release: Float, run: Float) {
        val thinking = state == WispOrbState.thinking
        if (thinking && !wasThinking) {
            val tx = tiltU[0].x
            val ty = tiltU[1].x
            precess = if (hypot(tx, ty) > 0.04f) atan2(ty, tx) else atan2(kitDir[1], kitDir[0])
        }
        var tu = floatArrayOf(0f, 0f)
        var ta = floatArrayOf(0f, 0f)
        var openGoal = 1f
        var bu = 0f
        var ba = 0f
        var tw = 0f
        when (state) {
            WispOrbState.listening -> {
                tu = scaled(kitDir, 0.32f + 0.45f * du)
                ta = tu
                bu = 0.22f + 0.50f * du
                tw = 0.16f * du
            }
            WispOrbState.thinking -> {
                val rate = (1.85f - 0.48f * run) + (0.72f - 0.30f * run) * stored
                precess = wispEuclid(precess + h * rate, WispOrb.tau)
                val s = sin(precess)
                val c = cos(precess)
                val cone = 0.44f + 0.12f * run
                tu = floatArrayOf(cone * c, cone * s)
                val opp = 1 - 1.92f * run
                ta = floatArrayOf(0.48f * opp * c, 0.48f * opp * s)
                openGoal = 1.22f - 0.28f * run
                bu = 0.56f - 0.10f * run
                ba = 0.14f + 0.36f * run
                tw = (0.68f + 0.26f * stored) * (1 - 0.65f * run)
            }
            WispOrbState.speaking -> {
                ta = scaled(wispDir, 0.20f + 0.36f * da + 0.14f * release)
                openGoal = 0.84f
                ba = 0.32f + 0.50f * da + 0.24f * release
            }
            WispOrbState.bothSpeaking -> {
                tu = scaled(kitDir, 0.30f + 0.40f * du)
                ta = scaled(wispDir, 0.30f + 0.40f * da)
                openGoal = 0.92f
                bu = 0.22f + 0.40f * du
                ba = 0.22f + 0.40f * da
                tw = 0.08f * (du + da)
            }
            else -> Unit
        }
        for (k in 0 until 2) {
            tiltU[k].settle(tu[k], 0.52f, h)
            tiltA[k].settle(ta[k], 0.52f, h)
        }
        open.settle(openGoal, 0.42f, h)
        bandU.settle(bu, 0.36f, h)
        bandA.settle(ba, 0.36f, h)
        twist.settle(tw, 0.44f, h)
        spin = wispEuclid(spin + h * 0.55f * twist.x, WispOrb.tau)
        val thinkU = if (thinking) 1.35f - 0.40f * run else 0f
        val thinkA = if (thinking) 0.85f + 0.65f * run else 0f
        bandPhaseU = wispEuclid(bandPhaseU + h * (1.2f + 3.0f * du + thinkU), WispOrb.tau)
        bandPhaseA = wispEuclid(bandPhaseA + h * (1.2f + 3.0f * da + 1.6f * release + thinkA), WispOrb.tau)
    }

    val runningMix: Float get() = wispClamp(running.x, 0f, 1f)

    companion object {
        const val golden: Float = 2.399_963f
        const val cadence: Float = 1.35f
        val kitDir = floatArrayOf(-0.94f, -0.34f)
        val wispDir = floatArrayOf(0.94f, 0.30f)

        private fun scaled(v: FloatArray, s: Float) = floatArrayOf(v[0] * s, v[1] * s)
    }
}

// Choreography

data class WispCircleBasis(
    val normal: WispVec3 = WispVec3(0f, 0f, 1f),
    val e1: WispVec3 = WispVec3(1f, 0f, 0f),
    val e2: WispVec3 = WispVec3(0f, 1f, 0f),
) {
    companion object {
        fun ring(tilt: Float, az: Float): WispCircleBasis {
            val st = sin(tilt)
            val ct = cos(tilt)
            val sa = sin(az)
            val ca = cos(az)
            return WispCircleBasis(WispVec3(st * ca, st * sa, ct), WispVec3(-sa, ca, 0f), WispVec3(-ct * ca, -ct * sa, st))
        }

        fun meridian(az: Float): WispCircleBasis {
            val sa = sin(az)
            val ca = cos(az)
            val rawN = WispVec3(0.96f * ca, 0.22f * sa, -0.96f * sa)
            val normal = rawN * (1f / sqrt(max(dot(rawN, rawN), 1e-6f)))
            val proj = -normal.y
            val rawE1 = WispVec3(-proj * normal.x, -1 - proj * normal.y, -proj * normal.z)
            val e1 = rawE1 * (1f / sqrt(max(dot(rawE1, rawE1), 1e-6f)))
            val e2 = WispVec3(
                normal.y * e1.z - normal.z * e1.y,
                normal.z * e1.x - normal.x * e1.z,
                normal.x * e1.y - normal.y * e1.x,
            )
            return WispCircleBasis(normal, e1, e2)
        }

        private fun dot(a: WispVec3, b: WispVec3) = a.x * b.x + a.y * b.y + a.z * b.z
    }
}

/** Thinking's Delegating handoff and Reasoning / Scan / Spark crossfade. */
class WispChoreoSim {
    var target = WispOrbChoreo.reasoning
    var wDelegate = WispSpring.at(1f)
    var wReason = WispSpring.at(0f)
    var wScan = WispSpring.at(0f)
    var wSpark = WispSpring.at(0f)
    var relayPhase = 0f
    val orbitPhases = floatArrayOf(0.40f, 3.35f)
    val sparkPhases = floatArrayOf(0.35f, 2.45f, 4.55f)
    val ringPhases = floatArrayOf(0.20f, 1.85f)

    private fun desired(running: Boolean): FloatArray {
        if (!running && target == WispOrbChoreo.reasoning) return floatArrayOf(1f, 0f, 0f, 0f)
        return when (target) {
            WispOrbChoreo.reasoning -> floatArrayOf(0f, 1f, 0f, 0f)
            WispOrbChoreo.scan -> floatArrayOf(0f, 0f, 1f, 0f)
            WispOrbChoreo.spark -> floatArrayOf(0f, 0f, 0f, 1f)
        }
    }

    fun snap(running: Boolean) {
        val g = desired(running)
        wDelegate = WispSpring.at(g[0])
        wReason = WispSpring.at(g[1])
        wScan = WispSpring.at(g[2])
        wSpark = WispSpring.at(g[3])
    }

    fun tick(h: Float, running: Boolean) {
        val g = desired(running)
        wDelegate.settle(g[0], springSecs, h)
        wReason.settle(g[1], springSecs, h)
        wScan.settle(g[2], springSecs, h)
        wSpark.settle(g[3], springSecs, h)
        relayPhase = wispEuclid(relayPhase + h * relayRate, WispOrb.tau)
        for (i in 0 until 2) orbitPhases[i] = wispEuclid(orbitPhases[i] + h * orbitRates[i], WispOrb.tau)
        for (i in 0 until 3) sparkPhases[i] = wispEuclid(sparkPhases[i] + h * sparkRates[i], WispOrb.tau)
        for (i in 0 until 2) ringPhases[i] = wispEuclid(ringPhases[i] + h * ringRates[i], WispOrb.tau)
    }

    /** Normalised weights (delegate, reason, scan, spark). */
    val weights: FloatArray
        get() {
            val w = floatArrayOf(wDelegate.x, wReason.x, wScan.x, wSpark.x).map { wispClamp(it, 0f, 1f) }
            val sum = max(w.sum(), 1e-4f)
            return FloatArray(4) { w[it] / sum }
        }

    val rings: List<WispCircleBasis>
        get() = listOf(WispCircleBasis.ring(0.92f, ringPhases[0]), WispCircleBasis.ring(0.96f, -ringPhases[1] + 1.92f))

    val scanOrbits: List<WispCircleBasis>
        get() = listOf(
            WispCircleBasis.ring(0.68f, 0.35f + 0.22f * ringPhases[0]),
            WispCircleBasis.ring(0.68f, 2.25f - 0.18f * ringPhases[1]),
        )

    val meridians: List<WispCircleBasis>
        get() = listOf(-0.88f, 0.16f, 1.06f).map { WispCircleBasis.meridian(it + 0.16f * ringPhases[1]) }

    companion object {
        const val springSecs: Float = 0.26f
        const val relayRate: Float = 3.10f
        val orbitRates = floatArrayOf(1.85f, 1.48f)
        val sparkRates = floatArrayOf(1.65f, 2.05f, 2.42f)
        val ringRates = floatArrayOf(1.28f, 0.88f)
    }
}
