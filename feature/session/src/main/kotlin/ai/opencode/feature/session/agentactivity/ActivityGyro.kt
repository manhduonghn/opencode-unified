package ai.opencode.feature.session.agentactivity

import ai.opencode.feature.session.AgentActivity
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// The activity glyph's engine. The per-state designs live in one table, `ActivityGyro.designs`.
//
// A glyph is up to four strokes on an LED dot sphere. A stroke is the set of dots where dot(p, n) ≈
// s: a great circle (s = 0), a smaller circle, or, seen edge-on, a straight line. Windows along it
// make dashes; a pitch term tilts it into a spiral stripe. Every design is a pose of these same
// numbers, so a change of state morphs the dots rather than cross-fading them.
//
// Angles never double back: each either spins (its speed a spring) or holds a pose, and a new pose
// picks the next equivalent angle ahead, far enough for a critically damped spring to decelerate
// into it without overshooting.

enum class ActivityAccent { orange, red, green, blue }

private typealias A = GyroAngleKey
private typealias K = GyroSpringKey

/** Half away from zero. */
internal fun roundHalfAway(x: Double): Double = if (x >= 0) floor(x + 0.5) else ceil(x - 0.5)

/** A 3-vector. */
class Vec3(@JvmField val x: Double, @JvmField val y: Double, @JvmField val z: Double) {
    fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z

    companion object {
        val zero = Vec3(0.0, 0.0, 0.0)
    }
}

// MARK: Springs (prototypes/ios-kit/spring.js)

data class GyroSpring(val response: Double, val damping: Double) {
    /** Advances a damped oscillator by [t] from displacement [x0] and velocity [v0]. */
    fun solve(x0: Double, v0: Double, t: Double): Pair<Double, Double> {
        val w = 2 * PI / max(1e-4, response)
        val z = damping
        if (z < 1) {
            val wd = w * sqrt(1 - z * z)
            val e = exp(-z * w * t)
            val c = cos(wd * t)
            val s = sin(wd * t)
            val b = (v0 + z * w * x0) / wd
            return (e * (x0 * c + b * s)) to (e * ((-z * w) * (x0 * c + b * s) + (-x0 * wd * s + b * wd * c)))
        }
        if (z == 1.0) {
            val e = exp(-w * t)
            val b = v0 + w * x0
            return (e * (x0 + b * t)) to (e * (b - w * (x0 + b * t)))
        }
        val r = sqrt(z * z - 1)
        val r1 = -w * (z - r)
        val r2 = -w * (z + r)
        val c1 = (v0 - r2 * x0) / (r1 - r2)
        val c2 = x0 - c1
        val e1 = exp(r1 * t)
        val e2 = exp(r2 * t)
        return (c1 * e1 + c2 * e2) to (c1 * r1 * e1 + c2 * r2 * e2)
    }

    companion object {
        fun smooth(duration: Double) = GyroSpring(response = duration, damping = 1.0)
    }
}

/** A spring-driven value. */
class GyroValue(
    var value: Double = 0.0,
    var config: GyroSpring = Gyro.MOVE,
    var precision: Double = 0.005,
) {
    var target: Double = value
    var velocity: Double = 0.0

    fun copy(): GyroValue = GyroValue(value, config, precision).also { it.target = target; it.velocity = velocity }

    fun to(target: Double, config: GyroSpring? = null, immediate: Boolean = false) {
        if (config != null) this.config = config
        this.target = target
        if (immediate) { value = target; velocity = 0.0 }
    }

    fun set(value: Double) { this.value = value; target = value; velocity = 0.0 }

    val settled: Boolean get() = abs(value - target) < precision && abs(velocity) < precision * 10

    /** False once settled. */
    fun step(dt: Double): Boolean {
        if (settled) { value = target; velocity = 0.0; return false }
        val (x, v) = config.solve(value - target, velocity, dt)
        value = target + x
        velocity = v
        return true
    }
}

// MARK: Constants and helpers

object Gyro {
    const val TAU = PI * 2
    const val HALF = PI / 2
    val MOVE = GyroSpring.smooth(0.6)
    val SLOW = GyroSpring.smooth(1.2)
    val FAST = GyroSpring.smooth(0.3)
    val FADE = GyroSpring.smooth(0.45)
    val RM = GyroSpring.smooth(0.35)
    const val W_MOVE = TAU / 0.6
    const val W_MIN = TAU / 1.6
    const val W_MAX = TAU / 0.22

    /** Below this angular speed (rad/s) a pose change may take the nearest equivalent, either way. */
    const val MOVING = 0.6

    fun clamp(x: Double, a: Double, b: Double): Double = if (x < a) a else if (x > b) b else x
    fun sstep(a: Double, b: Double, x: Double): Double {
        val t = clamp((x - a) / (b - a), 0.0, 1.0)
        return t * t * (3 - 2 * t)
    }
    fun wrap(a: Double): Double = a - TAU * floor((a + PI) / TAU)
    fun gauss(x: Double, s: Double): Double = exp(-(x * x) / (2 * s * s))
    fun sign(x: Double): Double = if (x > 0) 1.0 else if (x < 0) -1.0 else 0.0

    /** Latitude as an angle: S(φ) falls from 1.1 to −1.1 over each turn, so a stroke rolling past one pole comes back from the other. */
    fun S(phi: Double): Double = 1.1 - 2.2 * (phi / TAU - floor(phi / TAU))
    fun Sinv(s: Double): Double = TAU * (1.1 - s) / 2.2

    /** Strokes fade out towards the wrap at |s| = 1.1 over their own width and pitch. */
    fun visS(s: Double, w: Double, pitch: Double): Double {
        val fw = min(0.6, 0.06 + 2.2 * w + 0.5 * pitch)
        return sstep(1.1, 1.1 - fw, abs(s))
    }
    fun ringS(rho: Double): Double = sqrt(1 - rho * rho)

    /** The equivalent of x (mod period) nearest the angle's current value. */
    fun near(ang: GyroAngle, x: Double, period: Double = TAU): Double = x + period * roundHalfAway((ang.value - x) / period)

    data class Frame(val n: Vec3, val u: Vec3, val v: Vec3)

    /** n, u, v for angles (θ, e, a, r): n = Rz(r)·Rx(a)·(cos e cos θ, sin e, cos e sin θ); u, v span its circle. */
    fun frame(th: Double, e: Double, a: Double, r: Double): Frame {
        val ce = cos(e); val se = sin(e); val ct = cos(th); val st = sin(th)
        val ca = cos(a); val sa = sin(a); val cr = cos(r); val sr = sin(r)
        fun rot(x: Double, y: Double, z: Double): Vec3 {
            val y1 = y * ca - z * sa
            val z1 = y * sa + z * ca
            return Vec3(x * cr - y1 * sr, x * sr + y1 * cr, z1)
        }
        return Frame(rot(ce * ct, se, ce * st), rot(-st, 0.0, ct), rot(-se * ct, ce, -se * st))
    }
}

// MARK: Unwrapped angle with momentum

class GyroAngle(initial: Double = 0.0) {
    enum class Mode { hold, spin }

    var value = initial
    var vel = 0.0
    var mode = Mode.hold
    var anchor = initial
    var cfg = Gyro.MOVE
    var speed = GyroValue(0.0, config = Gyro.MOVE, precision = 1e-3)
    var lastAngle = initial
    var lastPeriod = Gyro.TAU

    fun set(v: Double) {
        value = v; anchor = v; vel = 0.0; mode = Mode.hold; speed.set(0.0); lastAngle = v; lastPeriod = Gyro.TAU
    }

    fun copy(o: GyroAngle) {
        value = o.value; vel = o.vel; mode = o.mode; anchor = o.anchor; cfg = o.cfg; speed = o.speed.copy()
        lastAngle = o.lastAngle; lastPeriod = o.lastPeriod
    }

    /** Spin at w rad/s. A spin never reverses a visible motion: against the current direction it takes w's magnitude, not its sign. */
    fun spin(w0: Double, cfg: GyroSpring = Gyro.MOVE, immediate: Boolean = false) {
        var w = w0
        if (abs(vel) > Gyro.MOVING && w != 0.0 && Gyro.sign(w) != Gyro.sign(vel)) w = -w
        if (mode != Mode.spin) { speed.set(vel); mode = Mode.spin }
        speed.to(w, cfg)
        if (immediate) { speed.set(w); vel = w }
    }

    /**
     * Come to rest on [angle] or any angle [period] away from it: moving, the first equivalent
     * ahead by at least the spring's natural stopping distance, approached by a critically damped
     * spring that never overshoots.
     */
    fun hold(angle: Double, period: Double = Gyro.TAU, immediate: Boolean = false, cfg: GyroSpring? = null, decel: Boolean = true) {
        if (mode == Mode.spin) vel = speed.value
        mode = Mode.hold
        lastAngle = angle; lastPeriod = period
        val v = vel
        if (immediate) {
            anchor = angle + period * roundHalfAway((value - angle) / period)
            value = anchor; vel = 0.0
            return
        }
        if (abs(v) < Gyro.MOVING) {
            anchor = angle + period * roundHalfAway((value - angle) / period)
            this.cfg = cfg ?: Gyro.MOVE
            return
        }
        val dir = Gyro.sign(v)
        val lead = abs(v) / Gyro.W_MOVE
        val k = if (dir > 0) ceil((value + lead - angle) / period) else floor((value - lead - angle) / period)
        anchor = angle + k * period
        val d = abs(anchor - value)
        var w = cfg?.let { Gyro.TAU / it.response } ?: Gyro.W_MOVE
        if (decel) w = Gyro.clamp(1.5 * abs(v) / d, Gyro.W_MIN, Gyro.W_MAX)
        w = max(w, 1.05 * abs(v) / d)
        this.cfg = GyroSpring(response = Gyro.TAU / w, damping = 1.0)
    }

    /** Start spinning from [value] already moving at [vel]. */
    fun launch(value: Double, vel: Double) { this.value = value; this.vel = vel; mode = Mode.spin; speed.set(vel) }

    /** Track a moving target as a spring. */
    fun follow(target: Double, cfg: GyroSpring = Gyro.MOVE) {
        if (mode == Mode.spin) vel = speed.value
        mode = Mode.hold; anchor = target; this.cfg = cfg
    }

    /** Push forward by about [d] radians: in a spin, extra travel; holding, on to the next equivalent pose. */
    fun kick(d: Double, cfg: GyroSpring? = null) {
        if (mode == Mode.spin) {
            val w = Gyro.TAU / speed.config.response
            speed.velocity += d * w * w
            return
        }
        vel = Gyro.sign(d) * max(abs(vel), Gyro.MOVING + 0.1)
        hold(lastAngle, lastPeriod, cfg = cfg, decel = cfg == null)
    }

    val moving: Boolean
        get() {
            if (mode == Mode.spin) return abs(speed.value) > 1e-3 || !speed.settled
            return abs(value - anchor) > 1e-4 || abs(vel) > 1e-3
        }

    fun step(dt: Double) {
        if (mode == Mode.spin) {
            val v0 = speed.value
            speed.step(dt)
            value += 0.5 * (v0 + speed.value) * dt
            vel = speed.value
            return
        }
        val (x, v) = cfg.solve(value - anchor, vel, dt)
        value = anchor + x; vel = v
        if (abs(x) < 1e-4 && abs(v) < 1e-3) { value = anchor; vel = 0.0 }
    }
}

// MARK: Strokes

enum class GyroAngleKey { theta, e, a, r, phi, psi, psiD, psiG, psiN, rho }
enum class GyroSpringKey { w, k, pitch, arc, floor, tail, dash, glint, notch, clip, cone }

class GyroStroke {
    val angles: List<GyroAngle> = GyroAngleKey.entries.map { GyroAngle() }
    val springs: List<GyroValue> = GyroSpringKey.entries.map { GyroValue(neutral[it] ?: 0.0, config = Gyro.MOVE, precision = 0.002) }
    var m = 11.0
    var pitch0 = 0.0

    init {
        this[GyroAngleKey.a].set(0.45)
        this[GyroAngleKey.phi].set(Gyro.Sinv(0.0))
    }

    operator fun get(key: GyroAngleKey): GyroAngle = angles[key.ordinal]
    operator fun get(key: GyroSpringKey): GyroValue = springs[key.ordinal]

    fun copyPose(o: GyroStroke) {
        for (key in GyroAngleKey.entries) this[key].copy(o[key])
        for (key in GyroSpringKey.entries) {
            if (key == GyroSpringKey.k) continue
            this[key].value = o[key].value; this[key].velocity = o[key].velocity; this[key].target = o[key].target
        }
        m = o.m; pitch0 = o.pitch0
    }

    val s: Double get() = Gyro.S(this[GyroAngleKey.phi].value)
    val visible: Boolean get() = this[GyroSpringKey.k].value > 0.02

    companion object {
        val neutral: Map<GyroSpringKey, Double> = mapOf(
            GyroSpringKey.w to 0.2, GyroSpringKey.pitch to 0.0, GyroSpringKey.arc to 3.6, GyroSpringKey.floor to 0.0,
            GyroSpringKey.tail to 0.0, GyroSpringKey.dash to 0.0, GyroSpringKey.glint to 0.0, GyroSpringKey.notch to 0.0,
            GyroSpringKey.clip to 2.0, GyroSpringKey.cone to 0.0,
        )
    }
}

/** A target for one of a stroke's angles. */
sealed interface GyroPose {
    data class Hold(val value: Double, val period: Double = Gyro.TAU, val cfg: GyroSpring? = null) : GyroPose

    /** Spins at the speed; under Reduce Motion it holds [still]. */
    data class Spin(val w: Double, val still: Double? = null, val cfg: GyroSpring? = null) : GyroPose
}

/** One stroke's retarget, like the prototype's spec objects. */
data class GyroSpec(
    val angles: Map<GyroAngleKey, GyroPose> = emptyMap(),
    /** Latitude: a number holds it there; a pose rolls it. */
    val s: GyroPose? = null,
    val springs: Map<GyroSpringKey, Double> = emptyMap(),
    val k: Double? = null,
    val m: Double? = null,
    val pitch0: Double? = null,
    val cfg: GyroSpring? = null,
) {
    /** `{ ...self, ...other }`. */
    fun with(
        angles: Map<GyroAngleKey, GyroPose> = emptyMap(),
        s: GyroPose? = null,
        latitude: Double? = null,
        springs: Map<GyroSpringKey, Double> = emptyMap(),
        k: Double? = null,
        m: Double? = null,
        pitch0: Double? = null,
        cfg: GyroSpring? = null,
    ): GyroSpec {
        var spec = copy(angles = this.angles + angles, springs = this.springs + springs)
        if (latitude != null) spec = spec.copy(s = GyroPose.Hold(Gyro.Sinv(latitude)))
        if (s != null) spec = spec.copy(s = s)
        return spec.copy(k = k ?: spec.k, m = m ?: spec.m, pitch0 = pitch0 ?: spec.pitch0, cfg = cfg ?: spec.cfg)
    }

    companion object {
        val FACE = GyroSpec(
            angles = mapOf(
                GyroAngleKey.theta to GyroPose.Hold(Gyro.HALF), GyroAngleKey.e to GyroPose.Hold(0.0),
                GyroAngleKey.a to GyroPose.Hold(0.0), GyroAngleKey.r to GyroPose.Hold(0.0),
            ),
        )
        val LEVEL = GyroSpec(
            angles = mapOf(
                GyroAngleKey.theta to GyroPose.Hold(Gyro.HALF), GyroAngleKey.e to GyroPose.Hold(Gyro.HALF),
                GyroAngleKey.a to GyroPose.Hold(0.0), GyroAngleKey.r to GyroPose.Hold(0.0),
            ),
        )

        fun MERIDIAN(w: Double = Gyro.TAU / 1.8) = GyroSpec(
            angles = mapOf(
                GyroAngleKey.theta to GyroPose.Spin(w), GyroAngleKey.e to GyroPose.Hold(0.0),
                GyroAngleKey.a to GyroPose.Hold(0.45), GyroAngleKey.r to GyroPose.Hold(0.0),
            ),
            s = GyroPose.Hold(Gyro.Sinv(0.0)),
        )
    }
}

// MARK: Designs

/** One state's design: how it enters, what it does every frame, and how it answers tool calls and output. */
class GyroDesign(
    val id: String,
    val enter: (ActivityGyro) -> Unit,
    val tick: ((ActivityGyro, Double) -> Unit)? = null,
    val onTool: ((ActivityGyro) -> Unit)? = null,
    val onOutput: ((ActivityGyro) -> Unit)? = null,
    /** Where a tick settles under Reduce Motion. */
    val stillAge: Double? = null,
    /** Per-dot phosphor while in this state ([GyroPhosphor]); none means dots follow the field at once. */
    val afterglow: GyroAfterglow? = null,
    /** Dots drawn over the strokes while in this state. */
    val pattern: GyroPattern? = null,
    /** A flash on arrival that cools into the settled pose. */
    val flash: GyroFlash? = null,
)

// MARK: The glyph

class ActivityGyro(op: AgentActivity = AgentActivity.idle, val reduceMotion: Boolean = false, seed: Double = 0.0) {
    enum class Global { body, flash, glow, ring, comet, orange, red, green, blue }

    var op: AgentActivity = op
        private set
    var design: GyroDesign = designs.getValue(op)
        private set
    val strokes: List<GyroStroke> = List(4) { GyroStroke() }
    val globals: List<GyroValue> = Global.entries.map { GyroValue(0.0, config = Gyro.FADE, precision = 0.002) }
    var immediate = false
    private var snap = false
    var age = 0.0
    private var t: Double = seed

    /** Nothing will change until the next [set] or [event]. */
    var isStatic = false
        private set

    // Rim chase's entry state.
    var chaseFacing: Double? = null
    var chaseRolls: Boolean? = null
    var chaseS0: Double? = null

    /** Afterglow and flash heat over the field, advanced by [render]. */
    val phosphor = GyroPhosphor()

    init {
        strokes.forEachIndexed { i, b -> b[GyroAngleKey.theta].set(seed + i * 1.1) }
        this[Global.flash].config = GyroSpring.smooth(0.32)
        set(op, immediate = true)
    }

    operator fun get(key: Global): GyroValue = globals[key.ordinal]

    fun set(op: AgentActivity, immediate: Boolean = false) {
        this.op = op
        design = designs.getValue(op)
        age = 0.0
        this.immediate = immediate
        for (accent in ActivityAccent.entries) {
            global(mapOf(accentGlobal(accent) to if (stateAccent[op] == accent) 1.0 else 0.0))
        }
        // Afterglow decays rather than switching off: it shrinks with the band's speed as it goes.
        global(mapOf(Global.comet to 0.0), Gyro.SLOW)
        for (b in strokes) {
            for ((key, value) in GyroStroke.neutral) b[key].to(value, if (reduceMotion) Gyro.RM else Gyro.MOVE, immediate = immediate)
        }
        design.enter(this)
        phosphor.enter(design, reduceMotion = reduceMotion, immediate = immediate)
        if (reduceMotion || immediate) design.tick?.invoke(this, if (reduceMotion) design.stillAge ?: 0.5 else 0.0)
        if (immediate) {
            for (b in strokes) {
                for (angle in b.angles) if (angle.mode == GyroAngle.Mode.hold) { angle.value = angle.anchor; angle.vel = 0.0 }
                for (spring in b.springs) spring.set(spring.target)
            }
        }
        this.immediate = false
        isStatic = false
    }

    /** Retargets one stroke. A hidden stroke about to show starts as a copy of stroke 0, so new strokes split off the band rather than fading in. */
    fun stroke(i: Int, spec: GyroSpec) {
        val b = strokes[i]
        if (!b.visible && (spec.k ?: 0.0) > 0 && !immediate) {
            val lead = strokes[0]
            if (i > 0 && lead.visible) { b.copyPose(lead); b[GyroSpringKey.k].set(0.0) } else snap = true
        }
        val cfg = if (reduceMotion) Gyro.RM else spec.cfg ?: Gyro.MOVE
        val now = immediate || snap
        spec.m?.let { b.m = it }
        spec.pitch0?.let { b.pitch0 = it }
        for ((key, pose) in spec.angles.entries.sortedBy { it.key.ordinal }) angle(b[key], pose, cfg, now, i)
        val s = spec.s
        if (s != null) {
            // A number holds the latitude with the stroke's spring; a pose rolls it.
            if (s is GyroPose.Hold && s.cfg == null) b[GyroAngleKey.phi].hold(s.value, Gyro.TAU, immediate = now, cfg = cfg)
            else angle(b[GyroAngleKey.phi], s, cfg, now, i)
        }
        for ((key, value) in spec.springs) b[key].to(value, cfg, immediate = now)
        spec.k?.let { b[GyroSpringKey.k].to(it, if (reduceMotion) Gyro.RM else Gyro.FADE, immediate = immediate) }
        snap = false
    }

    private fun angle(ang: GyroAngle, pose: GyroPose, cfg: GyroSpring, now: Boolean, i: Int) {
        when (pose) {
            is GyroPose.Spin ->
                if (reduceMotion) ang.hold(pose.still ?: (0.55 + i * 0.9), Gyro.TAU, immediate = now, cfg = Gyro.RM)
                else ang.spin(pose.w, pose.cfg ?: cfg, immediate = now)
            is GyroPose.Hold -> ang.hold(pose.value, pose.period, immediate = now, cfg = pose.cfg ?: if (reduceMotion) Gyro.RM else null)
        }
    }

    /** Fades strokes out while drawing them toward stroke 0, so they merge rather than vanish in place. */
    fun off(vararg indices: Int) {
        val lead = strokes[0]
        for (i in indices) {
            val b = strokes[i]
            b[GyroSpringKey.k].to(0.0, if (reduceMotion) Gyro.RM else Gyro.FADE, immediate = immediate)
            if (!b.visible || immediate) continue
            for (key in listOf(GyroAngleKey.e, GyroAngleKey.a, GyroAngleKey.r)) b[key].hold(lead[key].anchor, Gyro.TAU)
            val theta = lead[GyroAngleKey.theta]
            if (theta.mode == GyroAngle.Mode.spin) b[GyroAngleKey.theta].spin(theta.speed.target) else b[GyroAngleKey.theta].hold(theta.anchor, Gyro.TAU)
            // Stripes carry on the way they were going and settle onto the lead's latitude.
            val phi = lead[GyroAngleKey.phi]
            if (phi.mode == GyroAngle.Mode.hold) b[GyroAngleKey.phi].hold(phi.anchor, Gyro.TAU)
        }
    }

    fun global(values: Map<Global, Double>, cfg: GyroSpring? = null) {
        for ((key, value) in values) this[key].to(value, if (reduceMotion) Gyro.RM else cfg ?: Gyro.FADE, immediate = immediate)
    }

    /** Adds roughly [peak] to a global spring and lets it decay. */
    fun impulse(key: Global, peak: Double) {
        if (reduceMotion || immediate) return
        val w = Gyro.TAU / this[key].config.response
        this[key].velocity += peak * Math.E * w
    }

    enum class Event { tool, output }

    fun event(kind: Event) {
        if (reduceMotion) return
        isStatic = false
        when (kind) {
            Event.tool -> design.onTool?.invoke(this)
            Event.output -> design.onOutput?.invoke(this)
        }
    }

    /** Advances by dt. False once nothing will change until the next [set] or [event]. */
    fun step(dt: Double): Boolean {
        if (isStatic) return false
        t += dt
        age += dt
        if (!reduceMotion) design.tick?.invoke(this, age)
        var moving = !reduceMotion && design.tick != null
        for (b in strokes) {
            val k = b[GyroSpringKey.k]
            for (angle in b.angles) {
                angle.step(dt)
                if (k.value > 0.003 || k.target > 0) moving = angle.moving || moving
            }
            for (spring in b.springs) moving = spring.step(dt) || moving
        }
        for (g in globals) moving = g.step(dt) || moving
        isStatic = !moving
        return true
    }

    // MARK: The LED field

    private class Live(
        val b: GyroStroke, val k: Double, val s: Double, val w: Double, val n: Vec3, val u: Vec3, val v: Vec3,
        val taps: List<Vec3>, val pitch: Double, val arc: Double, val floor: Double, val tail: Double, val dir: Double,
        val dash: Double, val glint: Double, val notch: Double, val clip: Double, val psi: Double, val psiD: Double,
        val psiG: Double, val psiN: Double,
    )

    fun fill(cells: List<GyroCell>): GyroField {
        val comet = max(0.0, this[Global.comet].value)
        val live = ArrayList<Live>(4)
        for (b in strokes) {
            val k = b[GyroSpringKey.k].value * Gyro.visS(b.s, b[GyroSpringKey.w].value, b[GyroSpringKey.pitch].value)
            if (k < 0.003) continue
            val cone = b[GyroSpringKey.cone].value
            val rho = b[GyroAngleKey.rho].value
            val a = b[GyroAngleKey.a].value + cone * sin(rho)
            val r = b[GyroAngleKey.r].value + cone * cos(rho)
            val th = b[GyroAngleKey.theta]
            val e = b[GyroAngleKey.e]
            val frame = Gyro.frame(th.value, e.value, a, r)
            val taps = ArrayList<Vec3>(3)
            if (comet > 0.01) {
                for (j in 1..3) {
                    val lag = j * 0.05
                    taps.add(Gyro.frame(th.value - th.vel * lag, e.value - e.vel * lag, a - b[GyroAngleKey.a].vel * lag, r - b[GyroAngleKey.r].vel * lag).n)
                }
            }
            val psiVel = b[GyroAngleKey.psi].vel
            live.add(
                Live(
                    b = b, k = k, s = b.s, w = b[GyroSpringKey.w].value, n = frame.n, u = frame.u, v = frame.v, taps = taps,
                    pitch = b[GyroSpringKey.pitch].value, arc = b[GyroSpringKey.arc].value, floor = b[GyroSpringKey.floor].value,
                    tail = b[GyroSpringKey.tail].value, dir = if (Gyro.sign(psiVel) == 0.0) -1.0 else Gyro.sign(psiVel),
                    dash = b[GyroSpringKey.dash].value, glint = b[GyroSpringKey.glint].value, notch = b[GyroSpringKey.notch].value,
                    clip = b[GyroSpringKey.clip].value, psi = b[GyroAngleKey.psi].value, psiD = b[GyroAngleKey.psiD].value,
                    psiG = b[GyroAngleKey.psiG].value, psiN = b[GyroAngleKey.psiN].value,
                ),
            )
        }
        val ring = this[Global.ring].value
        val flash = max(0.0, this[Global.flash].value)
        val body = this[Global.body].value
        val ringN = if (ring > 1e-3) Gyro.frame(0.0, Gyro.HALF, strokes[0][GyroAngleKey.a].value, 0.0).n else Vec3.zero
        val accent = Gyro.clamp(ActivityAccent.entries.sumOf { max(0.0, this[accentGlobal(it)].value) }, 0.0, 1.0)
        val dots = ArrayList<GyroField.Dot>(cells.size)
        for (cell in cells) {
            val p = Vec3(cell.nx, cell.ny, cell.nz)
            var lit = 0.0
            var fresh = 0.0
            for (L in live) {
                val dn = p.dot(L.n)
                val needLam = L.pitch > 1e-3 || L.arc < 3.4 || L.dash > 1e-3 || L.glint > 1e-3 || L.notch > 1e-3
                val pu = p.dot(L.u)
                val pv = p.dot(L.v)
                val lam = if (needLam) atan2(pv, pu) else 0.0
                // Angle along the stroke is undefined on its axis; windows fade out there instead of flipping.
                val on = if (needLam) Gyro.sstep(0.04, 0.3, sqrt(pu * pu + pv * pv)) else 1.0
                val d = dn - L.s + (if (L.pitch > 1e-3) 0.5 * on * L.pitch * cos(lam - L.b.pitch0) else 0.0)
                var g = Gyro.gauss(d, L.w) * L.k * (0.65 + 0.35 * cell.nz)
                if (L.arc < 3.4) {
                    val ds = Gyro.wrap(lam - L.psi)
                    var win = Gyro.sstep(L.arc + 0.12, L.arc - 0.12, abs(ds))
                    if (L.tail > 1e-3 && ds * L.dir < 0) win = max(win, L.tail * exp(-max(0.0, abs(ds) - L.arc) / 0.55))
                    g *= 1 - on * (1 - max(L.floor, win))
                }
                // A few dashes are clusters a couple of dots long; many are single dots.
                if (L.dash > 1e-3) {
                    g *= 1 - on * L.dash * (1 - (0.5 + 0.5 * cos(L.b.m * (lam - L.psiD))).pow(if (L.b.m <= 4) 1.5 else 4.0))
                }
                if (L.glint > 1e-3) g *= 1 - on * L.glint * (1 - Gyro.gauss(Gyro.wrap(lam - L.psiG), 0.42))
                if (L.notch > 1e-3) g *= 1 - on * L.notch * Gyro.gauss(Gyro.wrap(lam - L.psiN), 0.5)
                if (L.clip < 1.5) g *= Gyro.sstep(0.12, -0.12, cell.nx - L.clip)
                L.taps.forEachIndexed { j, tap ->
                    val dt = p.dot(tap) - L.s
                    g = max(g, Gyro.gauss(dt, L.w) * L.k * comet * 0.5.pow(j + 1) * 0.9)
                }
                lit = 1 - (1 - lit) * (1 - min(1.0, g))
                fresh = max(fresh, g)
            }
            var extra = 0.0
            if (ring > 1e-3) { val d = p.dot(ringN); extra += ring * exp(-d * d / 0.045) }
            val light = (0.14 + 0.4 * cell.shade.pow(1.2)) * body + 0.95 * lit + extra + flash * (0.25 + 0.45 * cell.shade)
            dots.add(GyroField.Dot(light = light, fresh = fresh * 0.8, accent = accent * Gyro.sstep(0.3, 0.65, light), size = cell.size))
        }
        val weights = HashMap<ActivityAccent, Double>(4)
        for (a in ActivityAccent.entries) weights[a] = max(0.0, this[accentGlobal(a)].value)
        return GyroField(dots = dots, glow = this[Global.glow].value + 0.6 * flash, weights = weights)
    }

    /** The field as drawn: the engine's, through the afterglow and flash, [dt] after the last frame. */
    fun render(cells: List<GyroCell>, dt: Double): GyroField {
        val field = fill(cells)
        phosphor.apply(field, cells, dt)
        return field
    }

    /** Nothing moves or cools until the next state or event. */
    val isResting: Boolean get() = isStatic && phosphor.isSettled

    companion object {
        fun accentGlobal(accent: ActivityAccent): Global = when (accent) {
            ActivityAccent.orange -> Global.orange
            ActivityAccent.red -> Global.red
            ActivityAccent.green -> Global.green
            ActivityAccent.blue -> Global.blue
        }

        val stateAccent: Map<AgentActivity, ActivityAccent> = mapOf(
            AgentActivity.waiting to ActivityAccent.orange, AgentActivity.failed to ActivityAccent.red,
            AgentActivity.done to ActivityAccent.green, AgentActivity.handoff to ActivityAccent.blue,
        )

        /** One design per state, from `prototypes/activity-glyph/kit-set.json`. */
        val designs: Map<AgentActivity, GyroDesign> by lazy { makeDesigns() }

        private fun makeDesigns(): Map<AgentActivity, GyroDesign> {
            val G = Gyro
            val TAU = G.TAU
            val HALF = G.HALF
            return mapOf(
                // Shimmer: no band, a sparse slow glitter across a dimmed orb; Done cools into it slowly.
                AgentActivity.idle to GyroDesign(
                    id = "shimmer",
                    enter = { g ->
                        g.stroke(0, GyroSpec(angles = mapOf(A.theta to GyroPose.Spin(0.0, still = 0.55)), k = 0.0)); g.off(1, 2, 3)
                        g.global(mapOf(Global.body to 0.68, Global.glow to 0.14, Global.ring to 0.0))
                    },
                    afterglow = GyroAfterglow(attack = 0.15, decay = 0.5), pattern = GyroPattern.shimmer,
                ),
                // Condense: dots sparkle in at random and gather onto Thinking's band, which
                // turns underneath at Thinking's speed and brightens, so Thinking takes over in motion.
                AgentActivity.startup to GyroDesign(
                    id = "condense",
                    enter = { g ->
                        g.stroke(0, GyroSpec.MERIDIAN().with(k = 0.02)); g.off(1, 2, 3)
                        g.global(mapOf(Global.body to 0.8, Global.glow to 0.5, Global.ring to 0.0))
                    },
                    tick = { g, age -> g.strokes[0][K.k].to(0.95 * G.sstep(0.2, 1.1, age), GyroSpring.smooth(0.25)) },
                    stillAge = 1.4, afterglow = GyroAfterglow(attack = 0.05, decay = 0.35), pattern = GyroPattern.condense,
                ),
                // Comet: the band trails phosphor afterglow behind its motion.
                AgentActivity.thinking to GyroDesign(
                    id = "comet",
                    enter = { g ->
                        g.stroke(0, GyroSpec.MERIDIAN().with(k = 0.95)); g.off(1, 2, 3)
                        g.global(mapOf(Global.body to 1.0, Global.glow to 0.5, Global.ring to 0.1, Global.comet to 1.0))
                    },
                ),
                // Spiral churn: a three-stripe spiral turns on a tilted axis, its pitch slowly breathing.
                AgentActivity.longWork to GyroDesign(
                    id = "helix",
                    enter = { g ->
                        for (i in 0 until 3) {
                            g.stroke(
                                i,
                                GyroSpec(
                                    angles = mapOf(A.theta to GyroPose.Hold(HALF), A.e to GyroPose.Hold(HALF), A.a to GyroPose.Hold(0.4), A.r to GyroPose.Hold(0.2)),
                                    springs = mapOf(K.w to 0.13, K.pitch to 0.5), k = 0.8,
                                ),
                            )
                        }
                        g.stroke(0, GyroSpec(s = GyroPose.Spin(TAU / 3, still = G.Sinv(0.2))))
                        g.off(3); g.global(mapOf(Global.body to 0.9, Global.glow to 0.55, Global.ring to 0.0))
                    },
                    tick = { g, age ->
                        train(g, 3, TAU / 3)
                        for (i in 0 until 3) g.strokes[i][K.pitch].to(0.5 + 0.25 * sin(age * 0.7), G.SLOW)
                    },
                    stillAge = 2.0,
                ),
                // Spin kick: each call kicks the turning band faster and flashes the orb.
                AgentActivity.tool to GyroDesign(
                    id = "kick",
                    enter = { g ->
                        g.stroke(0, GyroSpec.MERIDIAN().with(k = 0.95)); g.off(1, 2, 3)
                        g.global(mapOf(Global.body to 1.0, Global.glow to 0.55, Global.ring to 0.2))
                    },
                    onTool = { g -> g.strokes[0][A.theta].kick(TAU * 0.45); g.impulse(Global.flash, 0.08) },
                ),
                // Spiral: tilted stripes rise out of the lower pole and into the upper, faster as output pours in.
                AgentActivity.streaming to GyroDesign(
                    id = "spiral",
                    enter = { g ->
                        for (i in 0 until 3) g.stroke(i, GyroSpec.LEVEL.with(springs = mapOf(K.w to 0.12, K.pitch to 0.55), k = 0.85))
                        g.stroke(0, GyroSpec(s = GyroPose.Spin(TAU / 2.2, still = G.Sinv(0.3))))
                        g.off(3); g.global(mapOf(Global.body to 0.85, Global.glow to 0.45, Global.ring to 0.0))
                    },
                    tick = { g, _ -> train(g, 3, TAU / 3) },
                    onOutput = { g -> g.strokes[0][A.phi].kick(0.35) },
                ),
                // Rim chase: the band turns face-on and breaks into three bright clusters that chase slowly round the rim.
                AgentActivity.waiting to GyroDesign(
                    id = "chase",
                    enter = { g ->
                        // A turning band's front arc moves left for θ increasing; the clusters peel off at the top
                        // going the same way (anticlockwise), at the band's speed, then ease down to the chase.
                        val b = g.strokes[0]
                        val theta = b[A.theta]
                        val w = if (theta.mode == GyroAngle.Mode.spin) theta.speed.value else theta.vel
                        val dir = if (abs(w) > G.MOVING) G.sign(w) else -1.0
                        if (!g.immediate && !g.reduceMotion && b.visible && b[K.dash].value < 0.05 && abs(w) > G.MOVING) {
                            b[A.psiD].launch(-HALF, dir * min(abs(w), TAU / 0.8))
                        }
                        g.stroke(
                            0,
                            GyroSpec.FACE.with(
                                angles = mapOf(A.psiD to GyroPose.Spin(dir * TAU / 4.5, still = -HALF, cfg = G.SLOW)), latitude = G.ringS(0.8),
                                springs = mapOf(K.w to 0.24, K.dash to 0.8), k = 1.0, m = 3.0,
                            ),
                        )
                        g.off(1, 2, 3); g.global(mapOf(Global.body to 0.9, Global.glow to 0.45, Global.ring to 0.0))
                        if (!g.immediate && !g.reduceMotion) chaseGate(g)
                    },
                    tick = { g, _ -> if (!g.reduceMotion) chaseGate(g) },
                ),
                // Slide right: upright bands slide off to the right, one after another.
                AgentActivity.handoff to GyroDesign(
                    id = "slide",
                    enter = { g ->
                        val upright = mapOf(A.theta to GyroPose.Hold(PI), A.e to GyroPose.Hold(0.0), A.a to GyroPose.Hold(0.0), A.r to GyroPose.Hold(0.16))
                        g.stroke(0, GyroSpec(angles = upright, s = GyroPose.Spin(TAU / 1.15, still = G.Sinv(0.2)), springs = mapOf(K.w to 0.17), k = 0.9))
                        g.stroke(1, GyroSpec(angles = upright, springs = mapOf(K.w to 0.17), k = 0.9))
                        g.off(2, 3); g.global(mapOf(Global.body to 0.9, Global.glow to 0.45, Global.ring to 0.0))
                    },
                    tick = { g, _ -> train(g, 2, PI) },
                ),
                // Ring: the band sweeps on, face-on, into a green ring; a flash sweeps round it.
                AgentActivity.done to GyroDesign(
                    id = "ring",
                    enter = { g ->
                        g.stroke(0, GyroSpec.FACE.with(latitude = 0.5, springs = mapOf(K.w to 0.2), k = 1.0)); g.off(1, 2, 3)
                        g.global(mapOf(Global.body to 0.75, Global.glow to 0.6, Global.ring to 0.0))
                    },
                    flash = GyroFlash(amount = 0.8, decay = 0.9, spread = GyroFlash.Spread.sweep, bloom = 0.1),
                ),
                // ×: the band levels, splits and rolls into a red ×; a soft ember flash fades slowly.
                AgentActivity.failed to GyroDesign(
                    id = "cross",
                    enter = { g ->
                        g.stroke(0, GyroSpec.LEVEL.with(angles = mapOf(A.r to GyroPose.Hold(PI / 4, period = PI)), latitude = 0.0, springs = mapOf(K.w to 0.11), k = 0.95))
                        g.stroke(1, GyroSpec.LEVEL.with(angles = mapOf(A.r to GyroPose.Hold(-PI / 4, period = PI)), latitude = 0.0, springs = mapOf(K.w to 0.11), k = 0.95))
                        g.off(2, 3); g.global(mapOf(Global.body to 0.8, Global.glow to 0.45, Global.ring to 0.0))
                    },
                    flash = GyroFlash(amount = 0.6, decay = 1.2, spread = GyroFlash.Spread.all, bloom = 0.06),
                ),
            )
        }

        /**
         * Rim chase entering from a band: the band lies down onto the rim, then draws in to its
         * ring and breaks into clusters. Nothing sweeps through the centre.
         */
        private fun chaseGate(g: ActivityGyro) {
            val b = g.strokes[0]
            // Only ever grows, so a band that must turn the long way round doesn't push the ring out and back.
            val now = Gyro.sstep(
                0.55, 0.97,
                cos(Gyro.wrap(b[GyroAngleKey.theta].value - Gyro.HALF)) * cos(b[GyroAngleKey.a].value) * cos(b[GyroAngleKey.e].value),
            )
            val facing = if (g.age == 0.0) now else max(g.chaseFacing ?: 0.0, now)
            g.chaseFacing = facing
            // Entering while the stripes roll: commit once to the ring's latitude ahead of them and
            // decelerate into it. Otherwise follow the band down onto the rim and in to its ring.
            val phi = b[GyroAngleKey.phi]
            if (g.age == 0.0 || g.chaseRolls == null) g.chaseRolls = abs(phi.vel) > Gyro.MOVING
            if (g.chaseRolls == true) {
                if (phi.mode != GyroAngle.Mode.hold || g.age == 0.0) phi.hold(Gyro.Sinv(Gyro.ringS(0.8)), Gyro.TAU)
            } else {
                if (g.age == 0.0) g.chaseS0 = Gyro.clamp(b.s, -1.0, 1.0)
                val s0 = g.chaseS0 ?: 0.0
                phi.follow(Gyro.near(phi, Gyro.Sinv(s0 + (Gyro.ringS(0.8) - s0) * facing)), Gyro.FAST)
            }
            b[GyroSpringKey.dash].to(0.8 * Gyro.sstep(0.4, 1.0, facing), Gyro.FAST)
        }

        /** Strokes 1…n−1 follow stroke 0's latitude, each [offset] further on. Trains only run forward. */
        private fun train(g: ActivityGyro, n: Int, offset: Double, cfg: GyroSpring = Gyro.FAST) {
            val lead = g.strokes[0][GyroAngleKey.phi].value
            val w = Gyro.TAU / cfg.response
            for (i in 1 until n) {
                val b = g.strokes[i][GyroAngleKey.phi]
                val target = Gyro.near(b, lead + i * offset)
                b.follow(max(target, b.value + max(0.0, b.vel) / w), cfg)
            }
        }
    }
}

/**
 * One dot of the orb: its place in the grid, the sphere normal under it, a top-left key light,
 * and a size that shrinks towards the limb.
 */
data class GyroCell(
    val column: Int,
    val row: Int,
    val nx: Double,
    val ny: Double,
    val nz: Double,
    val shade: Double,
    val size: Double,
) {
    companion object {
        /** The orb's cells for an n × n grid (n odd), masked to a disc. */
        fun orb(n: Int): List<GyroCell> {
            val half = (n - 1) / 2.0
            val R = n / 2.0 + 0.1
            val lx = -0.42; val ly = -0.52; val lz = 0.74
            val ln = sqrt(lx * lx + ly * ly + lz * lz)
            val cells = mutableListOf<GyroCell>()
            for (row in 0 until n) {
                for (column in 0 until n) {
                    val x = column - half
                    val y = row - half
                    if (sqrt(x * x + y * y) > n / 2.0) continue
                    val nx = x / R
                    val ny = y / R
                    val nz = sqrt(max(0.0, 1 - nx * nx - ny * ny))
                    val shade = max(0.0, (nx * lx + ny * ly + nz * lz) / ln)
                    cells.add(GyroCell(column, row, nx, ny, nz, shade, 0.7 + 0.3 * nz))
                }
            }
            return cells
        }

        val seven: List<GyroCell> by lazy { orb(7) }
    }
}

/**
 * What one frame draws: per dot its light, freshness, accent and size, and the orb's glow and
 * accent colour weights. Mutable: the phosphor rewrites it in place.
 */
class GyroField(
    val dots: MutableList<Dot>,
    var glow: Double,
    var weights: Map<ActivityAccent, Double>,
    /** How hot the orb's hottest dot is (0…1): the renderer warms its neutral colour toward the flash's. */
    var heat: Double = 0.0,
) {
    data class Dot(val light: Double, val fresh: Double, val accent: Double, val size: Double)

    companion object {
        fun empty() = GyroField(mutableListOf(), 0.0, emptyMap())
    }
}

// MARK: Afterglow and flash (`prototypes/activity-glyph/round4.html`)

/**
 * A phosphor's time constants (seconds): light rises toward the field over [attack] and falls
 * behind it over [decay]; after the state, the afterglow itself eases off over [release], so its
 * trail outlives the hand-off.
 */
data class GyroAfterglow(val attack: Double, val decay: Double, val release: Double = 0.35)

/**
 * A flash on arrival: each dot is driven hot for `window`, starting `start` after the state
 * begins plus its place in the spread, rises over `attack`, then cools over [decay]. [amount]
 * scales how far it overshoots the settled pose; [bloom] how much larger a hot dot draws.
 */
data class GyroFlash(val amount: Double, val decay: Double, val spread: Spread, val bloom: Double) {
    enum class Spread { all, ripple, sweep }

    /** When the flash reaches this dot, after `start`: at once, outward from the centre, or clockwise from the top. */
    fun delay(cell: GyroCell): Double = when (spread) {
        Spread.all -> 0.0
        Spread.ripple -> min(1.0, sqrt(cell.nx * cell.nx + cell.ny * cell.ny)) * 0.24
        Spread.sweep -> {
            val a = atan2(cell.ny, cell.nx) + Gyro.HALF
            (a - Gyro.TAU * floor(a / Gyro.TAU)) / Gyro.TAU * 0.36
        }
    }

    /** The last dot's flash has been driven. */
    val driven: Double get() = START + WINDOW + (if (spread == Spread.sweep) 0.36 else if (spread == Spread.ripple) 0.24 else 0.0)

    companion object {
        const val START = 0.2
        const val WINDOW = 0.1
        const val ATTACK = 0.03
    }
}

/**
 * Dots drawn over the strokes, from each cell, how much of the strokes' shape covers it (0…1),
 * and the pattern's own clock. A state names one; each fades in over 60 ms and out over 200 ms
 * on its own clock, so one state's pattern turns into the next's.
 */
enum class GyroPattern {
    /** Starting Up: dots sparkle in at random, then only on the band's shape, as it condenses. */
    condense,

    /** Idle: every dot drifts on its own slow sine and only the crests show, so a few glint at once. */
    shimmer;

    fun light(cell: GyroCell, shape: Double, t: Double): Double = when (this) {
        condense -> {
            val phase = t * 3 + hash(cell)
            val sparkle = Gyro.gauss(phase - floor(phase) - 0.5, 0.06)
            sparkle * (1 - Gyro.sstep(0.1, 1.0, t) * (1 - shape))
        }
        shimmer -> shimmer(cell, t) * Gyro.sstep(0.0, 0.6, t)
    }

    /** The still pose under Reduce Motion. */
    fun still(cell: GyroCell): Double = when (this) {
        condense -> if (hash(cell) > 0.72) 0.8 else 0.0
        shimmer -> shimmer(cell, 2.4)
    }

    companion object {
        const val FADE_IN = 0.06
        const val FADE_OUT = 0.2

        /** 0.22 · s¹⁰ of a sine at 0.14–0.24 Hz with its own phase per dot. */
        fun shimmer(cell: GyroCell, t: Double): Double {
            val i = cell.row * 7 + cell.column
            val f = 0.14 + 0.1 * hash(i + 17)
            val s = 0.5 + 0.5 * sin(Gyro.TAU * (t * f + hash(i)))
            return 0.22 * s.pow(10)
        }

        /** A fixed pseudo-random number per cell (the prototypes' `hash(row × 7 + column)`). */
        fun hash(cell: GyroCell): Double = hash(cell.row * 7 + cell.column)
        fun hash(i: Int): Double {
            val x = sin(i * 12.9898 + 4.1) * 43758.5453
            return x - floor(x)
        }
    }
}

/**
 * Per-dot afterglow and flash heat over the engine's field. Light follows the field through a
 * phosphor while a state has an afterglow (each state its own attack and decay; one without eases
 * back to none, so a trail outlives the hand-off). Colour fades with the light: each dot's accent
 * mix comes from its afterglowed light, and the accent weights fall over the same decay, so
 * Done's ring cooling into Idle stays green as it dims rather than flashing grey. A flash is
 * energy in each dot that rises fast and cools over its decay, overshooting the light, warming the
 * colour toward the hot one and blooming the dot. Under Reduce Motion there is neither, and
 * patterns hold their still poses.
 */
class GyroPhosphor {
    private class PatternState(val amount: Double, val age: Double)

    private var light = DoubleArray(0)
    /** Each dot's flash heat. */
    internal var heat = DoubleArray(0)
        private set

    /** The accent weights as drawn. */
    private val weights = HashMap<ActivityAccent, Double>()
    var attack = 0.0
        private set
    var decay = 0.0
        private set
    private var release = 0.35

    /** Each pattern showing: how much (fading in and out with its state) and its own clock. */
    private val patterns = LinkedHashMap<GyroPattern, PatternState>()

    /** The flash that heat cools by; kept after its state so heat decays on. */
    private var flash: GyroFlash? = null
    private var flashPending = false
    private var design: GyroDesign? = null
    private var reduceMotion = false

    /** Since the state began. */
    var age = 0.0
        private set

    /** Nothing will change until the next state. */
    var isSettled = true
        private set

    fun amount(pattern: GyroPattern): Double = patterns[pattern]?.amount ?: 0.0

    fun enter(design: GyroDesign, reduceMotion: Boolean, immediate: Boolean) {
        this.design = design
        this.reduceMotion = reduceMotion
        age = 0.0
        isSettled = false
        design.afterglow?.let { attack = it.attack; decay = it.decay; release = it.release }
        design.pattern?.let { next ->
            val amount = if (immediate || reduceMotion) 1.0 else patterns[next]?.amount ?: 0.0
            patterns[next] = PatternState(amount, if (amount < 1e-3) 0.0 else patterns[next]?.age ?: 0.0)
        }
        design.flash?.let { flash = it }
        // First sight is the settled state, never an entrance.
        flashPending = design.flash != null && !immediate && !reduceMotion
        if (immediate) {
            light = DoubleArray(0); heat = DoubleArray(0); weights.clear()
            patterns.keys.retainAll { it == design.pattern }
        }
    }

    /** The field as drawn after [dt] more seconds (rewrites [field] in place). */
    fun apply(field: GyroField, cells: List<GyroCell>, dt: Double) {
        val design = design ?: return
        age += dt
        val rm = reduceMotion
        if (design.afterglow == null) {
            val k = 1 - exp(-dt / release)
            attack -= attack * k; decay -= decay * k
            if (decay < 0.005) { attack = 0.0; decay = 0.0 }
        }
        for ((pattern, state) in patterns.entries.toList()) {
            val on = pattern == design.pattern
            val target = if (on) 1.0 else 0.0
            val amount = if (rm) target else state.amount + (target - state.amount) * (1 - exp(-dt / (if (on) GyroPattern.FADE_IN else GyroPattern.FADE_OUT)))
            if (!on && amount < 0.002) patterns.remove(pattern) else patterns[pattern] = PatternState(amount, state.age + dt)
        }
        val count = field.dots.size
        if (light.size != count) {
            light = DoubleArray(count) { field.dots[it].light }
            heat = DoubleArray(count)
        }

        // The accent weights fall over the afterglow's decay, never faster than the light.
        var lag = 0.0
        var hottest = 0.0
        val fall = if (rm || decay < 1e-3) 1.0 else 1 - exp(-dt / decay)
        for (accent in ActivityAccent.entries) {
            val target = field.weights[accent] ?: 0.0
            val held = weights[accent] ?: target
            val next = if (target >= held) target else held + (target - held) * fall
            weights[accent] = next
            lag = max(lag, next - target)
        }
        field.weights = HashMap(weights)
        val tint = Gyro.clamp(weights.values.sumOf { max(0.0, it) }, 0.0, 1.0)

        var maxFresh = 1e-3
        for (dot in field.dots) maxFresh = max(maxFresh, dot.fresh)
        val flashing = if (flashPending && !rm) design.flash else null
        val inFlashState = design.flash != null
        val active = patterns.entries.filter { it.value.amount > 1e-3 }
        for (i in cells.indices) {
            if (i >= count) break
            val cell = cells[i]
            val dot = field.dots[i]
            val shape = dot.fresh / maxFresh
            var P = dot.light
            var fresh = dot.fresh
            for ((pattern, state) in active) {
                val p = state.amount * (if (rm) pattern.still(cell) else pattern.light(cell, shape, state.age))
                P = min(1.2, P + 0.95 * p); fresh = max(fresh, 0.8 * p)
            }
            val v = light[i]
            val tau = if (rm) 0.0 else if (P > v) attack else decay
            val next = if (tau < 1e-3) P else v + (P - v) * (1 - exp(-dt / tau))
            light[i] = next
            lag = max(lag, abs(next - P))

            var drive = 0.0
            if (flashing != null) {
                val x = age - GyroFlash.START - flashing.delay(cell)
                if (x >= 0 && x < GyroFlash.WINDOW) drive = 0.15 + 0.85 * shape
            }
            val h = heat[i]
            val ht = if (drive > h) GyroFlash.ATTACK else flash?.decay ?: 1.0
            val hot = if (rm) 0.0 else h + (drive - h) * (1 - exp(-dt / ht))
            heat[i] = if (hot < 1e-4 && drive == 0.0) 0.0 else hot
            val a = (flash?.amount ?: 0.0) * heat[i]
            hottest = max(hottest, a)
            field.dots[i] = GyroField.Dot(
                light = next + 0.7 * a,
                // Settled, a flash state's dots keep a quieter core, so the colour reads full rather than pale.
                fresh = if (inFlashState) max(fresh * (0.3 + 0.7 * a), a) else max(fresh, a),
                // The accent mix follows the afterglowed light, so colour cools with it.
                accent = tint * Gyro.sstep(0.3, 0.65, next) * (1 - a),
                size = dot.size * (1 + (flash?.bloom ?: 0.0) * heat[i]),
            )
        }
        if (flashing != null && age >= flashing.driven) flashPending = false
        field.heat = hottest
        field.glow += 0.9 * hottest
        isSettled = rm || (!flashPending && hottest < 0.005 && lag < 0.002 && patterns.isEmpty())
    }
}
