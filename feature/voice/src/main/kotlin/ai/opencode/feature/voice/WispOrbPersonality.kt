package ai.opencode.feature.voice

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Wisp's in-call personality: the ponder gaze (gaze.rs, ponder path only), Attentive Presence
// (attentive.rs), Thinking Fidgets (fidget.rs) and the Breathing Ember idle (idle.rs). A
// one-for-one port; Wisp's window-look, cursor and other idle variants have no phone equivalent and
// are left out.

internal fun wispHash01(seq: Int): Float {
    var x = (seq * 0x9E3779B9.toInt()) xor 0xC2B2AE35.toInt()
    x = x xor (x ushr 16)
    x *= 0x7FEB352D
    x = x xor (x ushr 15)
    return (x ushr 8).toFloat() / (1 shl 24).toFloat()
}

/** A point or direction in the lens. */
data class WispVec2(val x: Float, val y: Float) {
    operator fun plus(o: WispVec2) = WispVec2(x + o.x, y + o.y)
    operator fun times(s: Float) = WispVec2(x * s, y * s)
    val length: Float get() = sqrt(x * x + y * y)

    companion object {
        val zero = WispVec2(0f, 0f)
    }
}

/** The user's side of the lens (their ribbon enters from the left). */
internal val wispKitLook = WispVec2(-0.12f, -0.04f)

// Gaze

/** The eye the field gathers into while Thinking ponders (`GazeSim`). */
class WispGazeSim {
    var axis = WispVec3(0f, 0f, 1f)
    var theta: Float = horizonPark
    var dir = WispVec2(-0.62f, -0.78f)
    var mix = 0f
    var clock = 0f
    var ponderOffset = WispVec2(-0.24f, -0.18f)
    var ponderWeight = 0f

    fun setPonder(offset: WispVec2, weight: Float) {
        ponderOffset = offset
        ponderWeight = if (weight.isFinite()) wispClamp(weight, 0f, 1f) else 0f
    }

    private fun syncAxis() {
        val d = unit2(dir) ?: WispVec2(0f, -1f)
        axis = WispVec3(sin(theta) * d.x, sin(theta) * d.y, cos(theta))
    }

    private fun applyPonder() {
        theta = wispClamp(ponderOffset.length * ponderWeight, 0f, 1.2f)
        dir = unit2(ponderOffset) ?: WispVec2(-0.8f, -0.6f)
        syncAxis()
        mix = wispClamp(0.68f * ponderWeight, 0f, 1f)
    }

    private fun park() {
        mix = 0f
        theta = horizonPark
        axis = WispVec3(0f, 0f, 1f)
    }

    fun tick(h: Float) {
        if (ponderWeight > 1e-4f) {
            applyPonder()
            clock = (clock + h) % 600f
        } else {
            park()
        }
    }

    /** Reduce Motion: the eye sits still. */
    fun snap() {
        if (ponderWeight > 1e-4f) applyPonder() else park()
        clock = 0f
    }

    val horizonVisibility: Float get() = 1 - wispSmoothstep(1.36f, 1.98f, theta)

    /** `GazeFrame`, Refractive Lens, no live window cue. */
    fun frame(t: WispOrbTuning): WispGazeFrame {
        val effMix = mix * horizonVisibility
        if (!(effMix > 1e-4f || mix > 1e-3f)) return WispGazeFrame()
        val len = sqrt(axis.x * axis.x + axis.y * axis.y + axis.z * axis.z)
        val a = if (len.isFinite() && len > 1e-5f) axis * (1 / len) else WispVec3(0f, 0f, 1f)
        val k = 1 / max(1 + a.z, 1e-4f)
        val e1 = WispVec3(1 - a.x * a.x * k, -a.x * a.y * k, -a.x)
        val e2 = WispVec3(-a.x * a.y * k, 1 - a.y * a.y * k, -a.y)
        val limbDir = unit2(dir) ?: WispVec2(-0.62f, -0.78f)
        val toward = unit2(WispVec2(limbDir.x * e1.x + limbDir.y * e1.y, limbDir.x * e2.x + limbDir.y * e2.y)) ?: WispVec2(1f, 0f)
        val reach = max(t.gazeReachDeg * Math.PI.toFloat() / 180f, 0.05f)
        val ringScale = 1 + 0.022f * sin(WispOrb.tau * 5.7f * clock) * sin(WispOrb.tau * 0.8f * clock + 0.6f)
        val limbEnv = wispSmoothstep(2.10f, reach, theta) * mix * 0.36f
        return WispGazeFrame(
            mix = effMix,
            warp = effMix * t.gazeBlend,
            blend = t.gazeBlend,
            axis = a,
            e1 = e1,
            e2 = e2,
            toward = toward,
            pupilR = t.pupilSize,
            irisW = t.irisWidth * ringScale,
            pupilWell = t.pupilWell,
            bright = 1 + 0.07f * sin(WispOrb.tau * 3.1f * clock + 1.1f),
            clock = clock,
            limb = limbEnv * t.rimGlow,
            limbDir = limbDir,
        )
    }

    companion object {
        const val horizonPark: Float = 1.97f

        internal fun unit2(v: WispVec2): WispVec2? {
            val len = v.length
            return if (len.isFinite() && len > 1e-3f) v * (1 / len) else null
        }
    }
}

data class WispGazeFrame(
    val mix: Float = 0f,
    val warp: Float = 0f,
    val blend: Float = 0f,
    val axis: WispVec3 = WispVec3(0f, 0f, 1f),
    val e1: WispVec3 = WispVec3(1f, 0f, 0f),
    val e2: WispVec3 = WispVec3(0f, 1f, 0f),
    val toward: WispVec2 = WispVec2(1f, 0f),
    val pupilR: Float = 0.19f,
    val irisW: Float = 0.22f,
    val pupilWell: Float = 0f,
    val bright: Float = 1f,
    val clock: Float = 0f,
    val limb: Float = 0f,
    val limbDir: WispVec2 = WispVec2(0f, -1f),
)

// Attentive Presence

/**
 * Quiet open-call pauses (Listening, quiet over 4 s): blinking eyes that glance toward the user and
 * perk up when they speak.
 */
class WispAttentiveSim {
    var quietSecs = 0f
    var t = 0f
    var mix = WispSpring()
    var perk = WispSpring()
    var look = arrayOf(WispSpring(), WispSpring())
    var blinkAge = 1f
    var nextBlink = 5.8f
    var seq: Int = 0

    fun snapInitial(listening: Boolean, reduceMotion: Boolean) {
        if (!listening || reduceMotion) {
            quietSecs = 0f
            mix = WispSpring.at(0f)
            perk = WispSpring.at(0f)
            return
        }
        mix = WispSpring.at(wispSmoothstep(delay, delay + enterSecs, quietSecs))
        val g = glanceGoal(0f)
        look = arrayOf(WispSpring.at(g.x), WispSpring.at(g.y))
    }

    fun resetStill() {
        quietSecs = 0f
        t = 0f
        mix = WispSpring()
        perk = WispSpring()
        look = arrayOf(WispSpring(), WispSpring())
        blinkAge = 1f
        nextBlink = 5.8f
        seq = 0
    }

    fun onLeaveListening() {
        quietSecs = 0f
    }

    private fun glanceGoal(perk: Float): WispVec2 {
        val wander = WispVec2(0.045f * sin(0.41f * t), 0.026f * sin(0.31f * t + 1.1f))
        val base = wispKitLook * 0.65f + wander * (1 - 0.5f * perk)
        return base * (1 - perk) + wispKitLook * perk
    }

    fun tick(h: Float, user: Float, agent: Float, listening: Boolean) {
        val quiet = listening && user <= 0.04f && agent <= 0.03f
        val heard = listening && user > 0.04f
        quietSecs = if (quiet) min(quietSecs + h, 3_600f) else 0f
        val activeOrCooling = listening || mix.x > 1e-4f || abs(mix.v) > 1e-3f || perk.x > 1e-4f
        if (!activeOrCooling) {
            t = 0f
            blinkAge = 1f
            nextBlink = 5.8f
            seq = 0
            look = arrayOf(WispSpring.at(0f), WispSpring.at(0f))
            return
        }
        t += h
        blinkAge += h
        perk.settle(if (heard) 1f else 0f, if (heard) perkAttackSecs else perkReleaseSecs, h)
        val p = wispClamp(perk.x, 0f, 1f)
        val want = listening && quietSecs > delay
        mix.settle(if (want) 1f else 0f, if (want) enterSecs else if (listening) perkDissolveSecs else exitSecs, h)
        val goal = glanceGoal(p)
        look[0].settle(goal.x, lookSecs, h)
        look[1].settle(goal.y, lookSecs, h)
        if (want && t >= nextBlink) {
            blinkAge = 0f
            seq += 1
            nextBlink = t + 3.0f + 2.6f * wispHash01(seq + 19)
        }
    }

    private val blink: Float
        get() {
            val a = blinkAge
            val closed = if (a < 0.055f) a / 0.055f else 1 - (a - 0.055f) / 0.115f
            return wispSmoothstep(0f, 1f, closed)
        }

    /** (weight, t, blink, perk), (look x, y), yaw (sin, cos), pitch (sin, cos). */
    fun frame(gazeMix: Float, reduceMotion: Boolean): Array<FloatArray> {
        val off = arrayOf(FloatArray(4), FloatArray(4), floatArrayOf(0f, 1f, 0f, 1f))
        if (reduceMotion) return off
        val weight = wispClamp(wispClamp(mix.x, 0f, 1f) * (1 - wispClamp(gazeMix, 0f, 1f)), 0f, 1f)
        if (weight <= 1e-3f) return off
        val lx = look[0].x
        val ly = look[1].x
        val yaw = 0.45f * lx + 0.08f * sin(0.23f * t)
        val pitch = -0.38f * ly + 0.06f * sin(0.19f * t + 0.8f)
        return arrayOf(
            floatArrayOf(weight, t, blink, wispClamp(perk.x, 0f, 1f)),
            floatArrayOf(lx, ly, 0f, 0f),
            floatArrayOf(sin(yaw), cos(yaw), sin(pitch), cos(pitch)),
        )
    }

    companion object {
        const val delay: Float = 4.0f
        const val enterSecs: Float = 0.55f
        const val perkDissolveSecs: Float = 0.46f
        const val exitSecs: Float = 0.34f
        const val perkAttackSecs: Float = 0.22f
        const val perkReleaseSecs: Float = 0.58f
        const val lookSecs: Float = 0.34f
    }
}

// Thinking Fidgets

/** Past 5 s of Thinking, 4.2 s beats: ponder gaze, pole bounce, aurora. */
class WispFidgetSim {
    var thinkingElapsed = 0f
    var clock = 0f
    var mix = WispSpring()
    var ponder = WispSpring()

    fun snapInitial(thinking: Boolean, reduceMotion: Boolean) {
        if (!thinking || reduceMotion) {
            resetStill()
            return
        }
        val w = wispSmoothstep(start, start + enterSecs, thinkingElapsed)
        clock = max(thinkingElapsed - start, 0f)
        mix = WispSpring.at(w)
        ponder = WispSpring.at(rawPonderGoal(thinking) * w)
    }

    fun resetStill() {
        thinkingElapsed = 0f
        clock = 0f
        mix = WispSpring()
        ponder = WispSpring()
    }

    fun onLeaveThinking() {
        thinkingElapsed = 0f
    }

    private val beatAndU: Pair<Int, Float>
        get() {
            val cycles = max(clock / beat, 0f)
            val whole = floor(cycles)
            return (whole.toInt() % 3) to (cycles - whole)
        }

    private fun rawPonderGoal(thinking: Boolean): Float {
        if (!thinking || thinkingElapsed <= start) return 0f
        val (beat, u) = beatAndU
        return if (beat == 0) wispSmoothstep(0f, 0.18f, u) * (1 - wispSmoothstep(0.80f, 1f, u)) else 0f
    }

    fun tick(h: Float, thinking: Boolean) {
        if (thinking) {
            thinkingElapsed = min(thinkingElapsed + h, 3_600f)
            if (thinkingElapsed > start) {
                clock = thinkingElapsed - start
                mix.settle(1f, enterSecs, h)
            } else {
                mix.settle(0f, yieldSecs, h)
            }
        } else {
            thinkingElapsed = 0f
            mix.settle(0f, yieldSecs, h)
            if (mix.x > 1e-4f || abs(mix.v) > 1e-3f || ponder.x > 1e-4f) {
                clock += h
            } else {
                mix = WispSpring.at(0f)
                clock = 0f
            }
        }
        val goal = rawPonderGoal(thinking) * wispClamp(mix.x, 0f, 1f)
        ponder.settle(goal, if (goal > ponder.x) 0.30f else ponderSecs, h)
    }

    val ponderGaze: Pair<WispVec2, Float>
        get() {
            val w = wispClamp(ponder.x, 0f, 1f)
            if (w <= 1e-4f) return ponderBase to 0f
            val offset = ponderBase + WispVec2(0.032f * sin(1.45f * clock), 0.024f * sin(1.05f * clock + 0.7f))
            return offset to w
        }

    /**
     * Kind (0 off, 1 bounce, 2 aurora) and envelope, then the beat's shape: bounce (pos x, y,
     * 1/rx, 1/ry), (vel y, pole y, pole glow, 0); aurora n1 (xyz, phase), n2.
     */
    fun frame(reduceMotion: Boolean): Array<FloatArray> {
        val off = arrayOf(FloatArray(4), FloatArray(4), FloatArray(4))
        if (reduceMotion) return off
        val m = wispClamp(mix.x, 0f, 1f)
        if (m <= 1e-3f) return off
        val (beat, u) = beatAndU
        val env = m * wispSmoothstep(0f, 0.16f, u) * (1 - wispSmoothstep(0.84f, 1f, u))
        if (env <= 1e-3f || beat == 0) return off
        if (beat == 1) {
            val phase = WispOrb.tau * 1.5f * u
            val s = sin(phase)
            val c = cos(phase)
            val y = -0.76f * (0.90f * c + 0.10f * cos(3 * phase))
            val x = 0.11f * s
            val speed = abs(s)
            val rx = 0.138f - 0.056f * speed
            val ry = 0.072f + 0.076f * speed
            val contact = (1 - speed).pow(3)
            return arrayOf(
                floatArrayOf(1f, env, 0f, 0f),
                floatArrayOf(x, y, 1 / rx, 1 / ry),
                floatArrayOf(0.76f * s, if (c >= 0) -0.80f else 0.80f, 0.34f * contact, 0f),
            )
        }
        val phase = WispOrb.tau * u
        val a1 = 0.42f + 0.28f * sin(phase)
        val a2 = -0.38f + 0.24f * cos(phase + 1.2f)
        return arrayOf(
            floatArrayOf(2f, env, 0f, 0f),
            floatArrayOf(0.32f * cos(a1), 0.90f, -0.28f * sin(a1), phase),
            floatArrayOf(-0.36f * sin(a2), 0.88f, 0.30f * cos(a2), 0f),
        )
    }

    companion object {
        const val start: Float = 5.0f
        const val beat: Float = 4.2f
        const val enterSecs: Float = 0.45f
        const val yieldSecs: Float = 0.20f
        const val ponderSecs: Float = 0.20f
        val ponderBase = WispVec2(-0.24f, -0.18f)
    }
}

// Breathing Ember

/** The sleeping orb's calm tide and drifting ember (`IdleSim`, Breathing Ember). */
class WispIdleSim {
    var t = 0f
    var perk = WispSpring()

    fun restart() {
        t = 0f
        perk = WispSpring()
    }

    fun tick(h: Float, user: Float, sleeping: Boolean) {
        t += h
        val heard = sleeping && user > 0.04f
        perk.settle(if (heard) 1f else 0f, if (heard) 0.25f else 0.6f, h)
    }

    /** (weight, t, perk, 0), yaw/pitch (sin, cos, sin, cos). */
    fun frame(weight: Float, reduceMotion: Boolean): Array<FloatArray> {
        val tt = if (reduceMotion) 0f else t
        val yaw = 0.16f * sin(0.21f * tt)
        val pitch = 0.09f * sin(0.17f * tt + 1)
        return arrayOf(
            floatArrayOf(wispClamp(weight, 0f, 1f), tt, wispClamp(perk.x, 0f, 1f), 0f),
            floatArrayOf(sin(yaw), cos(yaw), sin(pitch), cos(pitch)),
        )
    }
}
