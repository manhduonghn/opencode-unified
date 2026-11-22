@file:Suppress("PrivatePropertyName")

package ai.opencode.feature.voice

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Wisp's `OrbSim`: the 240 Hz state machine that steps voices, mode mixes, power, gaze and the
 * coupled medium, then hands one frame of uniforms to the cell pass and the fragment shader. A
 * plain object stepped by the renderer at display rate; nothing here touches the store.
 */
class WispOrbSim {
    var state = WispOrbState.sleeping
        private set
    var reduceMotion = false
        private set
    private var initialized = false
    private var pending = 0f
    private var renderDt = WispOrb.step
    private var timeAcc = 0.0
    private var phaseAcc = 0.0
    private var stateElapsed = 0f
    private var cometAngle = 0f
    private var swirlVelocity = 0f
    private var pourActive = false
    private var pourProgress = 0f

    private val userVoice = WispVoiceChannel()
    private val agentVoice = WispVoiceChannel()
    var userTrace = WispWaveTrace(WispOrb.userSeeds)
        private set
    var agentTrace = WispWaveTrace(WispOrb.agentSeeds)
        private set

    private var mix = WispModeMix.target(WispOrbState.sleeping, muted = false)
    private var muted = false
    private var workingRunning = false
    private var toolHash: ULong? = null
    private var toolStepSeq: Int = 0
    private var runningTint = 0f

    private var tintBase: WispVec3
    private var tintCore: WispVec3
    private var tintBaseVel = WispVec3.zero
    private var tintCoreVel = WispVec3.zero
    private var rimUserTint = 0f
    private var rimAgentTint = 0f
    private var ambientGlowBase = 0f
    private var power = WispPowerSim()
    private var gaze = WispGazeSim()
    private var coupling = WispCouplingSim()
    private var idle = WispIdleSim()
    private val choreo = WispChoreoSim()
    private var attentive = WispAttentiveSim()
    private var fidget = WispFidgetSim()
    private var ignition = 0f
    private var duck = 0f
    private var voicePush = 0f

    init {
        val (base, core) = WispModeMix.palette(WispOrbState.sleeping, 0f)
        tintBase = base
        tintCore = core
    }

    // Inputs

    /**
     * `set_working_tool`: a running tool name (null while delegating or reasoning) lights
     * Phase B, picks its choreography, and a new tool fires a cadence pulse.
     */
    fun setWorkingTool(tool: String?) {
        val next = hashTool(tool)
        val prev = toolHash
        if (prev != null && next != null && prev != next) toolStepSeq += 1
        toolHash = next
        workingRunning = next != null
        choreo.target = WispOrbChoreo.classify(tool)
    }

    fun setMuted(muted: Boolean) {
        this.muted = muted
    }

    // Step

    /** Advances by [dtSecs] (clamped to 0…0.25) in fixed 1/240 s steps. */
    fun step(dtSecs: Float, user: Float, agent: Float, next: WispOrbState, rm: Boolean, tuning: WispOrbTuning) {
        val dt = if (dtSecs.isFinite()) wispClamp(dtSecs, 0f, 0.25f) else 0f
        val u = clean(user)
        val a = clean(agent)
        val wasInitialized = initialized
        if (!wasInitialized) {
            state = next
            runningTint = if (next == WispOrbState.thinking && workingRunning) 1f else 0f
            val (base, core) = WispModeMix.palette(next, runningTint)
            tintBase = base
            tintCore = core
            tintBaseVel = WispVec3.zero
            tintCoreVel = WispVec3.zero
            mix = WispModeMix.target(next, muted)
            power.snap(next, tuning.restScale)
            choreo.snap(workingRunning)
            attentive.snapInitial(listening = next == WispOrbState.listening, reduceMotion = rm)
            fidget.snapInitial(thinking = next == WispOrbState.thinking, reduceMotion = rm)
            val (offset, weight) = fidget.ponderGaze
            gaze.setPonder(offset, weight)
            if (!rm && weight > 1e-4f) gaze.snap()
            ambientGlowBase = rawAmbientGlowBase(tuning)
            initialized = true
        } else if (next != state) {
            if (state == WispOrbState.connecting && next in pourStates && !rm) {
                swirlVelocity = min(swirlVelocity + WispOrb.cometRevPerSec * WispOrb.tau * 0.62f, 14f)
                pourActive = true
                pourProgress = 0f
            }
            if (!rm) {
                val wake = power.onStateChange(state, next, max(userVoice.energy, agentVoice.energy), tuning.restScale)
                if (wake > 0) swirlVelocity = min(swirlVelocity + wake, 14f)
            }
            if (next == WispOrbState.sleeping && mix.sleep < 0.02f) idle.restart()
            if (next == WispOrbState.connecting && mix.comet < 0.02f) ignition = 0f
            if (next != WispOrbState.listening) attentive.onLeaveListening()
            if (next != WispOrbState.thinking) fidget.onLeaveThinking()
            state = next
            stateElapsed = 0f
        }

        reduceMotion = rm
        if (rm) {
            stepStill(dt, u, a, wasInitialized, tuning)
            return
        }

        renderDt = min(renderDt + dt, 0.25f)
        pending += dt
        while (pending >= WispOrb.step) {
            pending -= WispOrb.step
            tick(u, a, tuning)
        }
    }

    /** The Reduce Motion branch of [step]: everything snaps still. */
    private fun stepStill(dt: Float, user: Float, agent: Float, wasInitialized: Boolean, tuning: WispOrbTuning) {
        pending = 0f
        renderDt = 0f
        timeAcc = 0.0
        phaseAcc = 0.0
        stateElapsed = 0f
        cometAngle = 0.35f
        swirlVelocity = 0f
        pourActive = false
        pourProgress = 0f
        voicePush = 0f
        choreo.snap(workingRunning)
        attentive.resetStill()
        fidget.resetStill()
        gaze.setPonder(WispVec2.zero, 0f)
        ignition = 1f
        duck = 0f
        tintBaseVel = WispVec3.zero
        tintCoreVel = WispVec3.zero
        power.snap(state, tuning.restScale)
        coupling = WispCouplingSim()
        runningTint = if (state == WispOrbState.thinking && workingRunning) 1f else 0f
        val (goalBase, goalCore) = WispModeMix.palette(state, runningTint)
        val goalMix = WispModeMix.target(state, muted)
        if (!wasInitialized || dt <= 0) {
            tintBase = goalBase
            tintCore = goalCore
            mix = goalMix
        } else {
            mix.follow(goalMix, reduceMotion = true, h = dt)
            val alpha = 1 - exp(-dt / 0.055f)
            tintBase = snapClose(tintBase + (goalBase - tintBase) * alpha, goalBase)
            tintCore = snapClose(tintCore + (goalCore - tintCore) * alpha, goalCore)
        }
        val gate = mix.activeGate
        val (uRaw, aRaw) = mix.effectiveInputs(state, user, agent, reduceMotion = true)
        userVoice.setStill(uRaw)
        agentVoice.setStill(aRaw)
        rimUserTint = wispSmoothstep(0.02f, 0.30f, userVoice.level)
        rimAgentTint = wispSmoothstep(0.02f, 0.30f, agentVoice.level)
        userTrace.setStill(userVoice, gate, WispOrb.userSeeds)
        agentTrace.setStill(agentVoice, gate, WispOrb.agentSeeds)
        gaze.snap()
        ambientGlowBase = rawAmbientGlowBase(tuning)
    }

    private fun rawAmbientGlowBase(tuning: WispOrbTuning): Float {
        val gazeMix = gaze.frame(tuning).mix
        val p = power.frame(reduceMotion)
        val comet = 0.30f * mix.comet
        val think = 0.35f * mix.think
        val confirm = 0.42f * mix.confirm
        val awake = 0.08f * (1 - mix.sleep)
        val personality = 0.10f * wispClamp(attentive.mix.x, 0f, 1f) + 0.12f * wispClamp(fidget.mix.x, 0f, 1f)
        return (userTrace.glow + agentTrace.glow) * 0.55f + comet + think + confirm + awake +
            personality + 0.22f * gazeMix + WispPowerSim.ambientBoost(p)
    }

    private fun tick(user: Float, agent: Float, tuning: WispOrbTuning) {
        val h = WispOrb.step
        mix.follow(WispModeMix.target(state, muted), reduceMotion = false, h = h)
        val (uRaw, aRaw) = mix.effectiveInputs(state, user, agent, reduceMotion = false)
        userVoice.step(uRaw, h)
        agentVoice.step(aRaw, h)

        val rimAlpha = 1 - exp(-h / 0.18f)
        rimUserTint += (wispSmoothstep(0.02f, 0.30f, userVoice.level) - rimUserTint) * rimAlpha
        rimAgentTint += (wispSmoothstep(0.02f, 0.30f, agentVoice.level) - rimAgentTint) * rimAlpha

        power.tick(h, state, tuning.restScale)
        choreo.tick(h, workingRunning)
        attentive.tick(h, user, agent, listening = state == WispOrbState.listening)
        fidget.tick(h, thinking = state == WispOrbState.thinking)
        val (offset, weight) = fidget.ponderGaze
        gaze.setPonder(offset, weight)
        idle.tick(h, user, sleeping = state == WispOrbState.sleeping)
        gaze.tick(h)
        val gazeMix = gaze.frame(tuning).mix
        val speech = wispSmoothstep(0.03f, 0.22f, userVoice.level + agentVoice.level)
        val duckGoal = wispClamp(max(0.80f * gazeMix, 0.55f * speech), 0f, 1f)
        duck += (duckGoal - duck) * (1 - exp(-h / 0.16f))

        val gate = mix.activeGate
        userTrace.tick(userVoice, gate, motionSpeed = 1f, phosphor = tuning.phosphor, response = tuning.response, h = h)
        agentTrace.tick(agentVoice, gate, motionSpeed = 1f, phosphor = tuning.phosphor, response = tuning.response, h = h)

        val runGoal = if (state == WispOrbState.thinking && workingRunning) 1f else 0f
        runningTint += (runGoal - runningTint) * (1 - exp(-h / 0.18f))
        val (goalBase, goalCore) = WispModeMix.palette(state, runningTint)
        val w = WispOrb.tau / 0.34f
        val decay = exp(-w * h)
        val yb = tintBase - goalBase
        val cb = tintBaseVel + yb * w
        tintBase = goalBase + (yb + cb * h) * decay
        tintBaseVel = (tintBaseVel - cb * (w * h)) * decay
        val yc = tintCore - goalCore
        val cc = tintCoreVel + yc * w
        tintCore = goalCore + (yc + cc * h) * decay
        tintCoreVel = (tintCoreVel - cc * (w * h)) * decay

        timeAcc += h.toDouble()
        stateElapsed += h
        if (state == WispOrbState.connecting) ignition = wispSmoothstep(0f, 1.6f, stateElapsed)
        val spin = WispOrb.cometRevPerSec * (0.45f + 0.55f * ignition)
        cometAngle = wispEuclid(cometAngle + h * WispOrb.tau * spin, WispOrb.tau)

        if (pourActive) {
            pourProgress += h / 0.46f
            if (pourProgress >= 1) {
                pourProgress = 1f
                pourActive = false
            }
        }

        val carry = coupling.tick(
            h, tuning.coupling, state, workingRunning, toolStepSeq,
            user = userVoice, agent = agentVoice, userAmp = userTrace.amplitude,
        )

        swirlVelocity *= exp(-h / 0.42f)
        val glowGoal = rawAmbientGlowBase(tuning)
        ambientGlowBase += (glowGoal - ambientGlowBase) * (1 - exp(-h / 0.18f))

        val drift = 0.30f * mix.sleep + 0.88f * (1 - mix.sleep)
        val voice = (userVoice.level + agentVoice.level) * tuning.response
        val pushTau = if (voice > voicePush) 0.14f else 0.45f
        voicePush += (voice - voicePush) * (1 - exp(-h / pushTau))
        val thinkDrive = 0.36f * mix.think
        val voiceDrive = voice + (voicePush - voice) * mix.think
        val stillness = 1 - 0.8f * mix.muted
        val dPhase = (drift + voiceDrive + thinkDrive + swirlVelocity * 0.42f + carry) * stillness
        phaseAcc += (h * dPhase).toDouble()
    }

    // Frame

    /**
     * `render_rgba`'s frame assembly: consumes the time since the last frame for the phosphor
     * fall-off and returns the uniforms both passes read.
     */
    fun frame(tuning: WispOrbTuning, view: WispOrbPlacement): WispOrbUniforms {
        val dt = wispClamp(renderDt, 0f, 0.25f)
        renderDt = 0f
        val fastTau = max(0.045f + 0.04f * tuning.phosphor, 0.015f)
        val slowTau = max(0.14f + 0.58f * tuning.phosphor, 0.05f)
        val trail = wispClamp(0.18f + 0.34f * tuning.phosphor, 0f, 0.72f)
        val still = reduceMotion || dt <= 0
        val fastFall = if (still) 0f else exp(-dt / fastTau)
        val slowFall = if (still) 0f else exp(-dt / slowTau)

        val p = power.frame(reduceMotion)
        val coupled = !reduceMotion
        val g = gaze.frame(tuning)
        val choreoActive = coupled && mix.think > 2e-3f
        val att = attentive.frame(gazeMix = g.mix, reduceMotion = reduceMotion)
        val fid = fidget.frame(reduceMotion)
        val idl = idle.frame(weight = mix.sleep, reduceMotion = reduceMotion)

        val u = WispOrbUniforms()
        val radius = max(tuning.diameter * 0.5f * p[0] * (1 - 0.045f * g.mix), 4f)
        val cols = tuning.columns.toFloat()
        val maxNormR = max(1 - 0.85f / radius, 0f)
        u[WispOrbUniforms.grid] = floatArrayOf(cols, tuning.dotPitch, radius, maxNormR * maxNormR)
        u[WispOrbUniforms.clock] = floatArrayOf(
            (timeAcc % 3600).toFloat(), (phaseAcc % (WispOrb.tau.toDouble() * 512)).toFloat(), cometAngle, ignition,
        )
        u[WispOrbUniforms.levels] = floatArrayOf(userVoice.level, agentVoice.level, userVoice.kick, agentVoice.kick)
        u[WispOrbUniforms.mixA] = floatArrayOf(mix.sleep, mix.comet, mix.think, 0f)
        u[WispOrbUniforms.mixB] = floatArrayOf(mix.confirm, mix.muted, 0f, 0f)
        u[WispOrbUniforms.tintBase] = floatArrayOf(tintBase.x, tintBase.y, tintBase.z, if (reduceMotion) 1f else 0f)
        u[WispOrbUniforms.tintCore] = floatArrayOf(tintCore.x, tintCore.y, tintCore.z, if (pourActive) pourProgress else -1f)
        u[WispOrbUniforms.phosphor] = floatArrayOf(fastFall, slowFall, trail, wispClamp(duck, 0f, 1f))
        u[WispOrbUniforms.tuneA] = floatArrayOf(tuning.contrast, tuning.brightness, tuning.response, tuning.interference)
        u[WispOrbUniforms.tuneB] = floatArrayOf(tuning.dotSize, tuning.glow, userTrace.phases[0] - agentTrace.phases[0], tuning.diameter)
        u[WispOrbUniforms.power] = p
        u[WispOrbUniforms.rim] = floatArrayOf(rimUserTint, rimAgentTint, 0f, 0f)

        u[WispOrbUniforms.gazeA] = floatArrayOf(g.mix, g.warp, g.blend, g.pupilR)
        u[WispOrbUniforms.gazeB] = floatArrayOf(g.irisW, g.pupilWell, g.bright, g.limb)
        u[WispOrbUniforms.gazeAxis] = floatArrayOf(g.axis.x, g.axis.y, g.axis.z, g.clock)
        u[WispOrbUniforms.gazeE1] = floatArrayOf(g.e1.x, g.e1.y, g.e1.z, 0f)
        u[WispOrbUniforms.gazeE2] = floatArrayOf(g.e2.x, g.e2.y, g.e2.z, 0f)
        u[WispOrbUniforms.gazeDir] = floatArrayOf(g.toward.x, g.toward.y, g.limbDir.x, g.limbDir.y)

        if (coupled) {
            val c = coupling
            val userAmp = max(userTrace.amplitude, 0f)
            val agentAmp = max(agentTrace.amplitude, 0f)
            val liftU = max(c.memoryU - userAmp, 0f) * wispClamp(c.holdGate.x, 0f, 1f)
            val unwindA = max(0.28f * min(c.release, 1f) - agentAmp, 0f) * wispClamp(c.speakGate.x, 0f, 1f)
            u[WispOrbUniforms.couplingA] = floatArrayOf(1f, if (tuning.coupling == WispOrbCoupling.voiceCarry) 1f else 0f, c.twist.x, c.spin)
            u[WispOrbUniforms.couplingB] = floatArrayOf(c.pushU.x, c.pushA.x, c.radU, c.radA)
            u[WispOrbUniforms.couplingC] = floatArrayOf(liftU * tuning.response * 0.88f, unwindA * tuning.response * 0.72f, c.runningMix, c.open.x)
            u[WispOrbUniforms.couplingTilt] = floatArrayOf(c.tiltU[0].x, c.tiltU[1].x, c.tiltA[0].x, c.tiltA[1].x)
            u[WispOrbUniforms.couplingBand] = floatArrayOf(c.bandU.x, c.bandA.x, c.bandPhaseU, c.bandPhaseA)
            val p0 = c.pulses[0]
            val p1 = c.pulses[1]
            u[WispOrbUniforms.toolPulse0] = floatArrayOf(p0.axis.x, p0.axis.y, p0.axis.z, p0.age * 2.35f)
            u[WispOrbUniforms.toolPulse1] = floatArrayOf(p1.axis.x, p1.axis.y, p1.axis.z, p1.age * 2.35f)
            u[WispOrbUniforms.toolPulseEnv] = floatArrayOf(p0.env, p1.env, 0f, 0f)
        } else {
            u[WispOrbUniforms.couplingC] = floatArrayOf(0f, 0f, 0f, 1f)
            u[WispOrbUniforms.toolPulse0] = floatArrayOf(0f, 0f, 1f, 0f)
            u[WispOrbUniforms.toolPulse1] = floatArrayOf(0f, 0f, 1f, 0f)
        }

        if (choreoActive) {
            val w = choreo.weights
            u[WispOrbUniforms.choreoA] = floatArrayOf(1f, w[0], w[1], w[2])
            u[WispOrbUniforms.choreoB] = floatArrayOf(w[3], choreo.relayPhase, choreo.orbitPhases[0], choreo.orbitPhases[1])
            u[WispOrbUniforms.choreoC] = floatArrayOf(choreo.sparkPhases[0], choreo.sparkPhases[1], choreo.sparkPhases[2], 0f)
            u[WispOrbUniforms.choreoD] = floatArrayOf(choreo.ringPhases[0], choreo.ringPhases[1], 0f, 0f)
            val bases = choreo.rings + choreo.scanOrbits + choreo.meridians
            for ((i, b) in bases.withIndex()) {
                u[WispOrbUniforms.circles + 3 * i] = floatArrayOf(b.normal.x, b.normal.y, b.normal.z, 0f)
                u[WispOrbUniforms.circles + 3 * i + 1] = floatArrayOf(b.e1.x, b.e1.y, b.e1.z, 0f)
                u[WispOrbUniforms.circles + 3 * i + 2] = floatArrayOf(b.e2.x, b.e2.y, b.e2.z, 0f)
            }
        } else {
            u[WispOrbUniforms.choreoA] = floatArrayOf(0f, 1f, 0f, 0f)
        }

        u[WispOrbUniforms.attentiveA] = att[0]
        u[WispOrbUniforms.attentiveB] = att[1]
        u[WispOrbUniforms.attentiveC] = att[2]
        u[WispOrbUniforms.fidgetA] = fid[0]
        u[WispOrbUniforms.fidgetB] = fid[1]
        u[WispOrbUniforms.fidgetC] = fid[2]
        u[WispOrbUniforms.idleA] = idl[0]
        u[WispOrbUniforms.idleB] = idl[1]

        val glowSource = if (initialized) ambientGlowBase else rawAmbientGlowBase(tuning)
        val ambient = glowSource * tuning.glow * (1 - 0.85f * mix.muted)
        val ptPerPx = 1 / view.pixelsPerPoint
        val aa = max(0.78f * ptPerPx, 1e-4f)
        val maxHaloR = 2 * 0.42f * tuning.dotPitch * (tuning.dotSize / 0.78f)
        u[WispOrbUniforms.view] = floatArrayOf(view.centerX, view.centerY, view.pixelsPerPoint, aa)
        u[WispOrbUniforms.glow] = floatArrayOf(
            0.17f * wispClamp(ambient, 0f, 1.2f),
            max(radius * (0.82f + 0.35f * min(ambient, 1f)), 1e-3f),
            (maxHaloR + aa) / tuning.dotPitch,
            2.8f * p[0],
        )
        return u
    }

    companion object {
        private val pourStates = setOf(WispOrbState.listening, WispOrbState.bothSpeaking, WispOrbState.speaking, WispOrbState.thinking)

        private fun clean(v: Float): Float = if (v.isFinite()) wispClamp(v, 0f, 1f) else 0f

        private fun snapClose(v: WispVec3, goal: WispVec3): WispVec3 = WispVec3(
            if (abs(v.x - goal.x) < 5e-4f) goal.x else v.x,
            if (abs(v.y - goal.y) < 5e-4f) goal.y else v.y,
            if (abs(v.z - goal.z) < 5e-4f) goal.z else v.z,
        )

        /** FNV-1a over the trimmed name's UTF-8 bytes; null for no tool. */
        fun hashTool(tool: String?): ULong? {
            val s = tool?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            var h: ULong = 0xcbf29ce484222325uL
            for (b in s.encodeToByteArray()) {
                h = h xor (b.toUByte().toULong())
                h *= 0x100000001b3uL
            }
            return h
        }
    }
}

/** Where the orb sits in the drawable: its centre in pixels and how many pixels one of Wisp's points (dp) spans. */
data class WispOrbPlacement(val centerX: Float, val centerY: Float, val pixelsPerPoint: Float)

/**
 * One frame for the Wisp orb's passes, kept as one flat array so it uploads as a single `float4`
 * array uniform.
 */
class WispOrbUniforms {
    val data = FloatArray(count * 4).also {
        fun put(slot: Int, a: Float, b: Float, c: Float, d: Float) {
            it[slot * 4] = a; it[slot * 4 + 1] = b; it[slot * 4 + 2] = c; it[slot * 4 + 3] = d
        }
        put(gazeAxis, 0f, 0f, 1f, 0f)
        put(gazeE1, 1f, 0f, 0f, 0f)
        put(gazeE2, 0f, 1f, 0f, 0f)
        put(gazeDir, 1f, 0f, 0f, -1f)
        put(attentiveC, 0f, 1f, 0f, 1f)
        put(idleB, 0f, 1f, 0f, 1f)
        put(clipShape, -1f, 0f, 1e9f, 1f)
    }

    operator fun get(slot: Int): FloatArray = data.copyOfRange(slot * 4, slot * 4 + 4)

    operator fun set(slot: Int, value: FloatArray) {
        require(value.size == 4)
        value.copyInto(data, slot * 4)
    }

    @Suppress("ConstPropertyName")
    companion object {
        /** Columns, pitch (dp), lens radius (dp), max normalised r². */
        const val grid = 0
        /** Time, phase, comet angle, ignition. */
        const val clock = 1
        /** User and agent level, then kick. */
        const val levels = 2
        /** Sleep, comet, think, think swirl. */
        const val mixA = 3
        /** Confirm, muted, then two unused. */
        const val mixB = 4
        /** Tint base, Reduce Motion. */
        const val tintBase = 5
        /** Tint core, pour progress (-1 when not pouring). */
        const val tintCore = 6
        /** Fast and slow fall, trail mix, band duck. */
        const val phosphor = 7
        /** Contrast, brightness, response, interference. */
        const val tuneA = 8
        /** Dot size, glow, phase difference of the voices' first harmonics, diameter. */
        const val tuneB = 9
        /** Scale, wake age, sleep age, cool ember. */
        const val power = 10
        /** Rim user and agent tint. */
        const val rim = 11
        const val gazeA = 12
        const val gazeB = 13
        const val gazeAxis = 14
        const val gazeE1 = 15
        const val gazeE2 = 16
        const val gazeDir = 17
        /** Active, mode (0 Harmonic Twist, 1 Voice Carry), twist, spin. */
        const val couplingA = 18
        const val couplingB = 19
        /** Lift u, lift a, running, open. */
        const val couplingC = 20
        const val couplingTilt = 21
        const val couplingBand = 22
        /** Axis and front of the two tool pulses, then their envelopes. */
        const val toolPulse0 = 23
        const val toolPulse1 = 24
        const val toolPulseEnv = 25
        /** Active, delegate, reason, scan weights. */
        const val choreoA = 26
        /** Spark weight, relay phase, orbit phases. */
        const val choreoB = 27
        const val choreoC = 28
        const val choreoD = 29
        /** Two rings, two scan orbits, three meridians: (normal, e1, e2) each, 21 slots. */
        const val circles = 30
        const val attentiveA = 51
        const val attentiveB = 52
        const val attentiveC = 53
        const val fidgetA = 54
        const val fidgetB = 55
        const val fidgetC = 56
        const val idleA = 57
        const val idleB = 58
        /** Centre (px), pixels per point, anti-alias width (dp). */
        const val view = 59
        /** Glow strength, glow reach (dp), dot search reach (cells), shadow drop (dp). */
        const val glow = 60
        /** The glass a grown orb feathers into (px: min x, y, max x, y). */
        const val clipRect = 61
        /** Its corner and feather (px; corner -1 for none), and the bottom the dots fade out above, over how many pixels. */
        const val clipShape = 62
        const val count = 63
    }
}
