@file:Suppress("LocalVariableName", "FunctionName")

package ai.opencode.feature.voice

import ai.opencode.feature.voice.WispOrbUniforms.Companion as S
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Wisp's per-cell light: one LED cell per lattice point, read by [WispOrbShaders]' raster fragment.
 * AGSL has no compute stage, so Android evaluates the cells on the CPU each frame (40 × 40 at the
 * default tuning) and uploads them as a float texture, as the old Living Orb field did. Constants,
 * curves and the uniform layout are the shader's.
 */
object WispOrbCells {
    /** One cell: (dot radius, halo radius, dot alpha, halo alpha), colour (rgb, 1). */
    const val floatsPerCell = 8

    /** Newest [trace] steps of both voices' wave history the lens reads (`WISP_TRACE`). */
    const val trace = 64

    internal const val TAU = 6.28318530718f
    internal const val PIf = 3.14159265359f
    internal const val STEP = 1f / 240f
    internal const val BLEED = 10f

    internal val USER_BASE = V3(0.60f, 0.90f, 0.98f)
    internal val USER_CORE = V3(0.96f, 0.99f, 1.00f)
    internal val AGENT_BASE = V3(0.44f, 0.91f, 0.68f)
    internal val AGENT_CORE = V3(0.99f, 0.93f, 0.72f)
    internal val FRINGE_RGB = V3(1.00f, 0.95f, 0.78f)
    internal val RUNNING_ACCENT = V3(0.52f, 0.75f, 1.00f)
    internal val PHOSPHOR_TEAL = V3(0.30f, 0.86f, 0.78f)
    internal val PHOSPHOR_PEARL = V3(0.93f, 1.00f, 0.99f)
    internal val MUTED_DOT = V3(0.50f, 0.25f, 0.27f)
    internal val MUTED_PLATE = V3(26f / 255f, 14f / 255f, 16f / 255f)
    internal val MUTED_RIM = V3(248f / 255f, 113f / 255f, 113f / 255f)

    internal val KIT_POLE = V3(-0.62f, -0.20f, 0.758f)
    internal val WISP_POLE = V3(0.62f, 0.16f, 0.768f)
    internal val RELAY_ORIGIN = V3(-0.8321f, 0.0832f, 0.5484f)
    internal val SPIRAL_AXIS = V3(-0.2592f, 0.8674f, 0.4249f)
    internal val SPIRAL_E1 = V3(0.9585f, 0.1798f, 0.2214f)
    internal val SPIRAL_AXIS_B = V3(0.3124f, 0.8486f, 0.4269f)
    internal val SPIRAL_E1_B = V3(0.9410f, -0.2215f, -0.2558f)

    /** Metal's `float3`, with the few operations the kernel uses. */
    internal data class V3(val x: Float, val y: Float, val z: Float) {
        operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
        operator fun times(s: Float) = V3(x * s, y * s, z * s)
        operator fun times(o: V3) = V3(x * o.x, y * o.y, z * o.z)
        operator fun unaryMinus() = V3(-x, -y, -z)
        infix fun dot(o: V3) = x * o.x + y * o.y + z * o.z
        infix fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
        val length: Float get() = sqrt(dot(this))
        fun normalized(): V3 = this * (1 / length)
        fun max(o: V3) = V3(max(x, o.x), max(y, o.y), max(z, o.z))
        fun min(o: V3) = V3(min(x, o.x), min(y, o.y), min(z, o.z))

        companion object {
            val zero = V3(0f, 0f, 0f)
            fun all(v: Float) = V3(v, v, v)
        }
    }

    internal operator fun Float.times(v: V3) = v * this

    /** The uniforms as Metal reads them: slot `.x/.y/.z/.w`. */
    @JvmInline
    internal value class U(val d: FloatArray) {
        fun x(slot: Int) = d[slot * 4]
        fun y(slot: Int) = d[slot * 4 + 1]
        fun z(slot: Int) = d[slot * 4 + 2]
        fun w(slot: Int) = d[slot * 4 + 3]
        fun xyz(slot: Int) = V3(d[slot * 4], d[slot * 4 + 1], d[slot * 4 + 2])
        fun yzw(slot: Int) = V3(d[slot * 4 + 1], d[slot * 4 + 2], d[slot * 4 + 3])
    }

    // Wisp's smoothstep accepts reversed edges, which Metal's leaves undefined.
    internal fun ws(a: Float, b: Float, x: Float): Float {
        val t = clamp((x - a) / (b - a), 0f, 1f)
        return t * t * (3f - 2f * t)
    }

    internal fun clamp(v: Float, lo: Float, hi: Float): Float = min(max(v, lo), hi)
    internal fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
    internal fun mix(a: V3, b: V3, t: Float) = a + (b - a) * t
    internal fun bump(d: Float, w: Float): Float {
        val q = d / w
        return exp(-q * q)
    }

    internal fun mix3(a: V3, b: V3, t: Float) = mix(a, b, clamp(t, 0f, 1f))

    /** `fast_pow01`: 0 at or below 1e-6, 1 at or above 1. */
    internal fun pow01(x: Float, y: Float): Float = when {
        x <= 1e-6f -> 0f
        x >= 1f -> 1f
        else -> x.pow(y)
    }

    internal fun spow(x: Float, y: Float): Float = if (x <= 0f) 0f else x.pow(y)
    internal fun geodesic(n: V3, pole: V3): Float = acos(clamp(n dot pole, -1f, 1f))
    internal fun euclid(x: Float, m: Float): Float = x - m * floor(x / m)
    internal fun fract(x: Float): Float = x - floor(x)

    // Per-cell light

    internal class GazeCell(var n: V3, var gain: Float, var light: Float, var tint: Float)

    /** `gaze_cell`, Refractive Lens. */
    internal fun gazeCell(u: U, n: V3, r: Float): GazeCell {
        val out = GazeCell(n, 1f, 0f, 0f)
        val mixG = u.x(S.gazeA)
        if (mixG <= 1e-4f) return out
        val warp = u.y(S.gazeA)
        val blend = u.z(S.gazeA)
        val pupilR = u.w(S.gazeA)
        val irisW = u.x(S.gazeB)
        val pupilWell = u.y(S.gazeB)
        val bright = u.z(S.gazeB)
        val limbK = u.w(S.gazeB)
        val axis = u.xyz(S.gazeAxis)
        val e1 = u.xyz(S.gazeE1)
        val e2 = u.xyz(S.gazeE2)
        val towardX = u.x(S.gazeDir)
        val towardY = u.y(S.gazeDir)
        val limbDirX = u.z(S.gazeDir)
        val limbDirY = u.w(S.gazeDir)

        val c = clamp(n dot axis, -1f, 1f)
        val ang = acos(c)
        val x = n dot e1
        val y = n dot e2
        val s = sqrt(x * x + y * y)
        val cp = if (s > 1e-5f) x / s else 1f
        val sp = if (s > 1e-5f) y / s else 0f
        val face = cp * towardX + sp * towardY
        val rhoI = pupilR + irisW
        val skirt = clamp(rhoI * 2.6f, 0.6f, 1.7f)
        val uu = min(ang / skirt, 1f)
        val env = 0.5f + 0.5f * cos(PIf * uu)
        val gather = sin(PIf * uu)

        val crescentK = 0.3f + 0.7f * spow(0.5f + 0.5f * face, 2.5f)
        val caustic = 1.3f * bump(ang - rhoI, 0.05f + 0.2f * irisW) * crescentK
        val lip = 0.3f * bump(ang - pupilR * 1.2f, 0.055f)
        val dang = warp * 0.7f * rhoI * gather
        val dphi = warp * 0.22f * env
        val shRing = caustic + lip

        val hemi = ws(-0.35f, 0.55f, c)
        val advect = warp * 0.20f * sin(ang) * hemi
        val ang2 = clamp(ang + dang + advect, 0f, PIf)
        val sd = sin(dphi)
        val cd = cos(dphi)
        val cp2 = cp * cd - sp * sd
        val sp2 = sp * cd + cp * sd
        val sa = sin(ang2)
        val ca = cos(ang2)
        out.n = (e1 * cp2 + e2 * sp2) * sa + axis * ca

        val well = 1f - ws(pupilR * 0.35f, pupilR * 1.35f, ang)
        val ring = shRing * (1f - 0.85f * well)
        val facing = if (r > 1e-3f) max((n.x * limbDirX + n.y * limbDirY) / r, 0f) else 0f
        val limb = spow(1f - n.z, 1.4f) * facing * facing * limbK
        val deep = 0.88f * pupilWell * well * mixG
        val gathered = blend * mixG
        val periphery = (1f - env) * hemi
        val crescent = ring * (0.35f + 0.65f * (0.5f + 0.5f * face))
        out.gain = (1f - deep) * (1f - 0.6f * gathered * env) * (1f - 0.26f * gathered * periphery) *
            (1f + 2.2f * gathered * ring + 4.0f * 0.26f * gathered * crescent)
        val add = 0.22f + 0.6f * (1f - blend)
        out.light = mixG * bright * (add * ring + 0.5f * 0.26f * crescent) + 0.8f * limb
        out.tint = min(mixG * (0.4f * env + 0.9f * ring) + 0.8f * limb, 1f)
        return out
    }

    internal class CouplingCell(
        var nu: V3, var na: V3,
        var kU: Float = 1f, var kA: Float = 1f, var argU: Float = 0f, var argA: Float = 0f,
        var liftU: Float = 0f, var liftA: Float = 0f, var crisp: Float = 0f, var thinkU: Float = 0f, var thinkA: Float = 0f,
    )

    internal fun wispTilt(n: V3, tx: Float, ty: Float): V3 {
        val a = sqrt(tx * tx + ty * ty)
        if (a < 1e-5f) return n
        val kx = ty / a
        val ky = -tx / a
        val s = sin(a)
        val c = cos(a)
        val kv = kx * n.x + ky * n.y
        val cr = V3(ky * n.z, -kx * n.z, kx * n.y - ky * n.x)
        return V3(n.x * c + cr.x * s + kx * kv * (1f - c), n.y * c + cr.y * s + ky * kv * (1f - c), n.z * c + cr.z * s)
    }

    internal fun toolPulseLight(u: U, n: V3): Float {
        var sum = 0f
        for (i in 0 until 2) {
            val env = if (i == 0) u.x(S.toolPulseEnv) else u.y(S.toolPulseEnv)
            if (env <= 1e-3f) continue
            val slot = S.toolPulse0 + i
            val d = geodesic(n, u.xyz(slot))
            val front = u.w(slot)
            val crest = bump(d - front, 0.30f)
            val fill = 0.32f * (1f - ws(0f, max(front + 0.25f, 0.25f), d))
            sum += env * (crest + fill)
        }
        return min(sum, 1.1f)
    }

    /** `coupling_cell`, Harmonic Twist (mode 0) or Voice Carry (mode 1). */
    internal fun couplingCell(u: U, n: V3): CouplingCell {
        val c = CouplingCell(n, n)
        if (u.x(S.couplingA) < 0.5f) return c
        val twist = u.z(S.couplingA)
        val spin = u.w(S.couplingA)
        val run = u.z(S.couplingC)
        c.liftU = u.x(S.couplingC)
        c.liftA = u.y(S.couplingC)
        c.crisp = 1f
        val crown = 0.76f + 0.24f * max(n.z, 0f)
        val pulse = toolPulseLight(u, n)
        c.thinkU = (0.72f - 0.16f * run) * crown + 0.42f * pulse
        c.thinkA = (0.14f + 0.46f * run) * crown + 0.38f * pulse
        if (u.y(S.couplingA) > 0.5f) {
            val a = spin + 1.2f * twist * n.y
            val s = sin(a)
            val co = cos(a)
            val rotU = V3(n.x * co + n.z * s, n.y, n.z * co - n.x * s)
            val sa = sin(-0.65f * a)
            val ca = cos(-0.65f * a)
            val rotA = V3(n.x * ca + n.z * sa, n.y, n.z * ca - n.x * sa)
            val m = mix(rotU, rotA, clamp(run, 0f, 1f))
            c.nu = rotU
            c.na = m * (1f / sqrt(max(m dot m, 1e-6f)))
            c.argU = u.x(S.couplingB) * sin(4.2f * geodesic(n, KIT_POLE) - u.z(S.couplingB)) + 0.55f * pulse
            c.argA = u.y(S.couplingB) * sin(4.2f * geodesic(n, WISP_POLE) - u.w(S.couplingB)) - 0.45f * pulse
        } else {
            var m = n
            if (abs(twist) > 1e-4f) {
                val a = spin + 1.1f * twist * n.y
                val s = sin(a)
                val co = cos(a)
                m = V3(n.x * co + n.z * s, n.y, n.z * co - n.x * s)
            }
            c.nu = wispTilt(m, u.x(S.couplingTilt), u.y(S.couplingTilt))
            c.na = wispTilt(n, u.z(S.couplingTilt), u.w(S.couplingTilt))
            val open = u.w(S.couplingC)
            c.kU = open
            c.kA = open * (1f - 0.06f * run)
            val lu = kotlin.math.asin(clamp(c.nu.y, -1f, 1f))
            val la = kotlin.math.asin(clamp(c.na.y, -1f, 1f))
            c.argU = u.x(S.couplingBand) * sin(5f * lu + u.z(S.couplingBand)) + 0.60f * pulse
            c.argA = u.y(S.couplingBand) * sin(5f * la - u.w(S.couplingBand)) - 0.52f * pulse
        }
        return c
    }

    /** `PowerFrame::sample`: (phase shift, flat light, wave light). */
    internal fun powerSample(u: U, nz: Float): V3 {
        val wakeAge = u.y(S.power)
        val sleepAge = u.z(S.power)
        val cool = u.w(S.power)
        if (!(wakeAge >= 0f || sleepAge >= 0f || cool > 1e-3f)) return V3.zero
        val rho = acos(clamp(nz, 0f, 1f))
        var arg = 0f
        var flat = 0f
        var wave = 0f
        val anticipate = 0.035f
        val wakeSpring = 0.36f
        val wakeWave = 0.52f
        val sleepWave = 0.68f
        if (wakeAge >= 0f) {
            if (wakeAge < anticipate) {
                val g = clamp(wakeAge / anticipate, 0f, 1f)
                arg -= 0.55f * g * rho
                val k = 0.48f * g * bump(rho, 0.38f)
                flat += 0.38f * k
                wave += 0.62f * k
            } else {
                val t = wakeAge - anticipate
                val uu = clamp(t / wakeSpring, 0f, 1.35f)
                val front = 1.65f * (1f - exp(-2.15f * uu))
                val rise = ws(0f, 0.04f, t)
                val env = rise * (1f - ws(0.24f, wakeWave - anticipate, t))
                arg += 1.05f * env * sin(3.6f * rho - 8.8f * t)
                val crest = bump(rho - front, 0.30f)
                val coreFill = 1f - ws(0f, max(front + 0.24f, 0.25f), rho)
                val k = env * (0.64f * crest + 0.26f * coreFill)
                flat += 0.22f * k
                wave += 0.78f * k
            }
        }
        if (sleepAge >= 0f || cool > 1e-3f) {
            val age = max(sleepAge, 0f)
            val rise = if (sleepAge >= 0f) ws(0f, 0.075f, age) else 1f
            val pull = rise * (1f - ws(0.12f, sleepWave, age)) * cool
            arg += 0.95f * pull * sin(3.4f * rho + 6.8f * age)
            val coreFocus = 0.24f + 0.76f * nz * nz
            val k = rise * cool * coreFocus * 1.15f
            flat += 0.20f * k
            wave += 0.80f * k
        }
        return V3(arg, flat, wave)
    }

    internal fun ignitionSpiral(u: U, rm: Boolean, n: V3, theta: Float, phi: Float): Float {
        val ignite = clamp(u.w(S.clock), 0f, 1f)
        val depth = 0.24f + 0.76f * ws(-0.55f, 0.55f, n.z)
        if (rm) {
            val band = PIf * 0.5f - 0.12f * (1f - ignite)
            return 0.56f * bump(theta - band, 0.22f) * depth
        }
        val spin = u.z(S.clock)
        val centre = 1.28f + 0.29f * ignite
        val delta = spin - phi
        val skewed = delta - 0.30f * sin(delta)
        val swell = 0.22f + 0.78f * spow(0.5f + 0.5f * cos(skewed), 1.45f)
        val width = 0.19f + 0.05f * (1f - max(n.z, 0f))
        val wave = 0.045f * sin(delta - 0.6f * u.x(S.clock))
        val mainRibbon = bump(theta - (centre + wave), width) * swell * depth
        val e2b = SPIRAL_AXIS_B cross SPIRAL_E1_B
        val thetaB = acos(clamp(n dot SPIRAL_AXIS_B, -1f, 1f))
        val phiB = atan2(n dot e2b, n dot SPIRAL_E1_B)
        val deltaB = 0.78f * spin + 2.1f - phiB
        val swellB = 0.20f + 0.80f * spow(0.5f + 0.5f * cos(deltaB), 1.5f)
        val subRibbon = bump(thetaB - 1.54f, 0.21f) * swellB * (0.24f + 0.16f * ignite) * depth
        return 1.08f * mainRibbon + subRibbon
    }

    /** `IdleFrame::light`, Breathing Ember. */
    internal fun idleLight(u: U, n: V3): Float {
        val weight = u.x(S.idleA)
        if (weight <= 1e-3f) return 0f
        val sy = u.x(S.idleB)
        val cy = u.y(S.idleB)
        val sp = u.z(S.idleB)
        val cp = u.w(S.idleB)
        val x1 = n.x * cy - n.z * sy
        val z1 = n.x * sy + n.z * cy
        val q = V3(x1, n.y * cp - z1 * sp, n.y * sp + z1 * cp)
        if (q.z <= 0f) return 0f
        val t = u.y(S.idleA)
        val perk = u.z(S.idleA)
        val breath = 0.5f + 0.5f * sin(t * TAU / 5.4f)
        val ex = 0.22f * sin(0.41f * t) - 0.14f * perk
        val ey = 0.18f + 0.14f * cos(0.31f * t + 1.1f) - 0.10f * perk
        val coreD = sqrt((q.x - ex) * (q.x - ex) + (q.y - ey) * (q.y - ey))
        val core = bump(coreD, 0.34f + 0.08f * breath) * (0.28f + 0.18f * breath + 0.18f * perk)
        val lon = atan2(q.x, q.z)
        val lat = kotlin.math.asin(clamp(q.y, -1f, 1f))
        val waveA = 0.22f * sin(1.5f * lon - 0.58f * t) + 0.14f
        val waveB = -0.18f * sin(2.0f * lon + 0.43f * t + 1.7f) - 0.06f
        val tideA = bump(lat - waveA, 0.19f) * (0.45f + 0.55f * cos(lon - 0.62f * t))
        val tideB = bump(lat - waveB, 0.22f) * (0.45f + 0.55f * cos(lon + 0.48f * t + 2.1f))
        val light = core + 0.24f * tideA + 0.17f * tideB
        return weight * light * ws(0f, 0.25f, q.z)
    }

    /** `AttentiveFrame::light`: blinking eyes and their meniscus. */
    internal fun attentiveLight(u: U, n: V3): Float {
        val weight = u.x(S.attentiveA)
        if (weight <= 1e-3f) return 0f
        val sy = u.x(S.attentiveC)
        val cy = u.y(S.attentiveC)
        val sp = u.z(S.attentiveC)
        val cp = u.w(S.attentiveC)
        val x1 = n.x * cy - n.z * sy
        val z1 = n.x * sy + n.z * cy
        val q = V3(x1, n.y * cp - z1 * sp, n.y * sp + z1 * cp)
        if (q.z <= 0f) return 0f
        val t = u.y(S.attentiveA)
        val blink = u.z(S.attentiveA)
        val perk = u.w(S.attentiveA)
        val breath = 0.5f + 0.5f * sin(t * TAU / 4.6f)
        val aura = (0.04f + 0.04f * breath) * spow(q.z, 0.6f)
        val open = (1f - 0.90f * blink) * (1f + 0.25f * perk)
        val ex = u.x(S.attentiveB)
        val ey = -0.06f + u.y(S.attentiveB) - 0.03f * perk
        val halfH = max(0.118f * open, 0.014f)
        val eyeL = 1f - ws(0.62f, 1.24f, len2((q.x - (ex - 0.25f)) / 0.082f, (q.y - ey) / halfH))
        val eyeR = 1f - ws(0.62f, 1.24f, len2((q.x - (ex + 0.25f)) / 0.082f, (q.y - ey) / halfH))
        val eyes = max(eyeL, eyeR) * (0.86f + 0.14f * perk)
        val lid = 0.42f * blink * bump(q.y - ey, 0.032f) * (1f - ws(0.32f, 0.48f, abs(q.x - ex)))
        val arcR = len2(q.x - ex, q.y - (ey + 0.07f))
        val meniscus = bump(arcR - 0.16f, 0.036f) * ws(ey + 0.13f, ey + 0.21f, q.y) * (0.18f + 0.52f * perk)
        return weight * (max(max(eyes, lid), meniscus) + aura) * ws(0f, 0.25f, q.z)
    }

    internal fun len2(x: Float, y: Float) = sqrt(x * x + y * y)

    /** `FidgetFrame::light`: Thinking's pole bounce or aurora. */
    internal fun fidgetLight(u: U, n: V3): Float {
        if (n.z <= 0f) return 0f
        val kind = u.x(S.fidgetA)
        val env = u.y(S.fidgetA)
        val front = ws(0f, 0.22f, n.z)
        if (kind > 0.5f && kind < 1.5f) {
            val posX = u.x(S.fidgetB)
            val posY = u.y(S.fidgetB)
            val dx = (n.x - posX) * u.z(S.fidgetB)
            val dy = (n.y - posY) * u.w(S.fidgetB)
            val head = 1.15f * exp(-0.5f * (dx * dx + dy * dy))
            val velY = u.x(S.fidgetC)
            val behindY = (posY - n.y) * (if (velY >= 0f) 1f else -1f)
            val wakeEnv = ws(-0.04f, 0.05f, behindY) * exp(-4.2f * max(behindY, 0f)) * abs(velY)
            val wake = 0.62f * wakeEnv * bump(n.x - posX, 0.085f)
            val cap = u.z(S.fidgetC) * bump(n.y - u.y(S.fidgetC), 0.14f) * bump(n.x, 0.24f)
            return env * (head + wake + cap) * front
        }
        if (kind > 1.5f) {
            val phase = u.w(S.fidgetB)
            val lon = atan2(n.x, n.z)
            val crest1 = 0.14f * sin(2f * lon - phase)
            val crest2 = -0.12f * sin(2f * lon + phase + 1.1f)
            val band1 = bump((n dot u.xyz(S.fidgetB)) - crest1, 0.18f) * (0.55f + 0.45f * cos(lon - phase))
            val band2 = bump((n dot u.xyz(S.fidgetC)) - crest2, 0.20f) * (0.55f + 0.45f * cos(lon + phase + 0.8f))
            return env * (0.62f * band1 + 0.48f * band2) * front
        }
        return 0f
    }

    /** `special_field_light`: ignition spiral and pour, confirm, and the personalities. */
    internal fun specialLight(u: U, rm: Boolean, n: V3, r: Float, breath: Float, bodyWave: Float): Float {
        var light = 0f
        val cometMix = u.y(S.mixA)
        val pour = u.w(S.tintCore)
        val pourActive = pour >= 0f
        if (cometMix > 0.005f || pourActive) {
            val theta = acos(clamp(n dot SPIRAL_AXIS, -1f, 1f))
            val e2 = SPIRAL_AXIS cross SPIRAL_E1
            val phi = atan2(n dot e2, n dot SPIRAL_E1)
            if (cometMix > 0.005f) light += cometMix * ignitionSpiral(u, rm, n, theta, phi)
            if (pourActive) {
                val spread = 0.18f + 0.85f * pour
                val fade = ws(0f, 0.24f, pour) * (1f - ws(0.46f, 1f, pour))
                val depth = 0.38f + 0.62f * ws(-0.35f, 0.55f, n.z)
                light += fade * bump(theta - 1.48f, spread) * 0.54f * depth
            }
        }
        val swirlMix = u.w(S.mixA)
        if (swirlMix > 0.005f) {
            val swirl = if (rm) {
                0.42f * (1f - 0.35f * r)
            } else {
                val theta = atan2(n.y, n.x)
                val arms = 0.5f + 0.5f * cos(3f * theta + u.y(S.clock) * 1.85f - 3.1f * r)
                arms * r * (1.15f - 0.35f * r) * 0.75f
            }
            light += swirlMix * swirl * (1f - u.w(S.phosphor))
        }
        val confirm = u.x(S.mixB)
        if (confirm > 0.005f) {
            val pulse = if (rm) 0.5f else 0.5f + 0.5f * sin(u.x(S.clock) * TAU * 0.75f)
            val alert = 0.48f * bump(r - 0.76f, 0.16f) + (0.20f + 0.18f * pulse) * (0.4f + 0.6f * bodyWave)
            light += confirm * alert
        }
        return light + idleLight(u, n) + attentiveLight(u, n) + fidgetLight(u, n)
    }

    /** `ChoreoFrame::excite`: Thinking's tool choreography on both lattices. */
    internal fun choreoExcite(u: U, n: V3, baseU: Float, baseA: Float, bodyU: Float, bodyA: Float): FloatArray {
        if (u.x(S.choreoA) < 0.5f) return floatArrayOf(baseU * (0.05f + 0.95f * bodyU), baseA * (0.05f + 0.95f * bodyA))
        val crown = 0.70f + 0.30f * max(n.z, 0f)
        var outU = 0f
        var outA = 0f
        val wDelegate = u.y(S.choreoA)
        val wReason = u.z(S.choreoA)
        val wScan = u.w(S.choreoA)
        val wSpark = u.x(S.choreoB)
        fun circle(i: Int) = u.xyz(S.circles + i)
        if (wDelegate > 1e-4f) {
            val depth = 0.34f + 0.66f * ws(-0.35f, 0.55f, n.z)
            val theta = acos(clamp(n dot RELAY_ORIGIN, -1f, 1f))
            val travel = 2.15f * theta - u.y(S.choreoB)
            val c = 0.5f + 0.5f * cos(travel)
            val crest = c * c * c
            val arcEnv = spow(sin(theta), 0.65f) * depth
            val progress = clamp(theta / PIf, 0f, 1f)
            val uRelay = (0.76f * (1f - 0.55f * progress) * crest + 0.12f * (1f - progress)) * arcEnv
            val aRelay = (0.72f * (0.35f + 0.65f * progress) * crest + 0.10f * progress) * arcEnv
            outU += wDelegate * (0.28f * baseU * (0.08f + 0.92f * bodyU) + uRelay)
            outA += wDelegate * (0.28f * baseA * (0.08f + 0.92f * bodyA) + aRelay)
        }
        if (wReason > 1e-4f) {
            val n0 = circle(0)
            val e10 = circle(1)
            val e20 = circle(2)
            val n1 = circle(3)
            val e11 = circle(4)
            val e21 = circle(5)
            val az0 = atan2(n dot e20, n dot e10)
            val ring0 = bump((n dot n0) + 0.045f * sin(2f * az0 + u.y(S.choreoD)), 0.165f)
            val az1 = atan2(n dot e21, n dot e11)
            val ring1 = bump((n dot n1) + 0.045f * sin(2f * az1 - u.x(S.choreoD)), 0.165f)
            val node = 0.28f * ring0 * ring1
            outU += wReason * (0.48f * baseU * (0.06f + 0.94f * bodyU) + (0.50f * ring0 + node) * crown)
            outA += wReason * (0.48f * baseA * (0.06f + 0.94f * bodyA) + (0.48f * ring1 + node) * crown)
        }
        if (wScan > 1e-4f) {
            val depth = 0.30f + 0.70f * ws(-0.42f, 0.52f, n.z)
            val streams = FloatArray(2)
            for (k in 0 until 2) {
                val sn = circle(6 + 3 * k)
                val se1 = circle(7 + 3 * k)
                val se2 = circle(8 + 3 * k)
                val az = atan2(n dot se2, n dot se1)
                val behind = euclid((if (k == 0) u.z(S.choreoB) else u.w(S.choreoB)) - az, TAU)
                val env = ws(0f, 0.72f, behind) * exp(-0.44f * behind) * (1f - ws(TAU - 0.65f, TAU, behind))
                streams[k] = bump(n dot sn, 0.185f) * (0.22f + 0.92f * env)
            }
            val scanU = (0.64f * streams[0] + 0.34f * streams[1]) * depth
            val scanA = (0.34f * streams[0] + 0.62f * streams[1]) * depth
            outU += wScan * (0.34f * baseU * (0.10f + 0.90f * bodyU) + scanU)
            outA += wScan * (0.34f * baseA * (0.10f + 0.90f * bodyA) + scanA)
        }
        if (wSpark > 1e-4f) {
            val depth = 0.34f + 0.66f * ws(-0.40f, 0.55f, n.z)
            var surges = 0f
            var wakes = 0f
            for (k in 0 until 3) {
                val head = when (k) {
                    0 -> u.x(S.choreoC)
                    1 -> u.y(S.choreoC)
                    else -> u.z(S.choreoC)
                }
                val mn = circle(12 + 3 * k)
                val me1 = circle(13 + 3 * k)
                val me2 = circle(14 + 3 * k)
                val crossD = n dot mn
                val band = bump(crossD, 0.135f)
                if (band < 1e-3f) continue
                val psi = atan2(n dot me2, n dot me1)
                val behind = euclid(head - psi, TAU)
                val dHead = if (behind < PIf) behind else behind - TAU
                surges += bump(dHead, 0.36f) * bump(crossD, 0.155f)
                val wakeEnv = ws(0f, 0.55f, behind) * exp(-0.82f * behind) * (1f - ws(TAU - 0.65f, TAU, behind))
                wakes += wakeEnv * band
            }
            val poles = 0.12f * (bump(n.y + 0.78f, 0.22f) + bump(n.y - 0.78f, 0.22f))
            val sparkU = (0.78f * min(surges, 1.2f) + 0.44f * min(wakes, 1.2f) + poles) * depth * crown
            val sparkA = (0.58f * min(surges, 1.2f) + 0.34f * min(wakes, 1.2f)) * depth * crown
            outU += wSpark * (0.34f * baseU * (0.10f + 0.90f * bodyU) + sparkU)
            outA += wSpark * (0.34f * baseA * (0.10f + 0.90f * bodyA) + sparkA)
        }
        return floatArrayOf(outU, outA)
    }

    /** `WaveTrace::past`: lerp between steps `delay / STEP` and the one before; 4 floats per step. */
    internal fun tracePast(traces: FloatArray, delay: Float): FloatArray {
        val d = clamp(delay / STEP, 0f, (trace - 2).toFloat())
        val i = d.toInt()
        val k = d - i
        return FloatArray(4) { c -> traces[i * 4 + c] * (1f - k) + traces[(i + 1) * 4 + c] * k }
    }

    // Cells

    /**
     * Evaluates every cell (`wispOrbCells`): [uniforms] is [WispOrbUniforms.data], [phosphor] the
     * per-cell trail (4 floats per cell, read and written), [traces] the newest [trace] wave steps
     * (4 floats each, newest first) and [cells] receives [floatsPerCell] floats per cell.
     */
    fun evaluate(uniforms: FloatArray, phosphor: FloatArray, traces: FloatArray, cells: FloatArray) {
        val u = U(uniforms)
        val cols = u.x(S.grid).toInt()
        for (gy in 0 until cols) for (gx in 0 until cols) cell(u, phosphor, traces, cells, cols, gx, gy)
    }

    private fun cell(u: U, phosphor: FloatArray, traces: FloatArray, cells: FloatArray, cols: Int, gx: Int, gy: Int) {
        val idx = gy * cols + gx
        val p4 = idx * 4
        val c8 = idx * floatsPerCell
        val pitch = u.y(S.grid)
        val radius = u.z(S.grid)
        val off = (cols.toFloat() - 1f) * 0.5f
        val cx = (gx.toFloat() - off) * pitch
        val cy = (gy.toFloat() - off) * pitch
        val invRadius = 1f / radius
        val nx = cx * invRadius
        val ny = cy * invRadius
        val ny2 = ny * ny
        val r2 = nx * nx + ny2
        val fastFall = u.x(S.phosphor)
        val slowFall = u.y(S.phosphor)
        val trail = u.z(S.phosphor)
        val duck = u.w(S.phosphor)
        if (r2 >= u.w(S.grid)) {
            phosphor[p4] *= fastFall
            phosphor[p4 + 1] *= slowFall
            phosphor[p4 + 2] *= fastFall
            phosphor[p4 + 3] *= slowFall
            for (k in 0 until floatsPerCell) cells[c8 + k] = 0f
            return
        }

        val rm = u.w(S.tintBase) > 0.5f
        val time = u.x(S.clock)
        val phase = u.y(S.clock)
        val userLevel = u.x(S.levels)
        val agentLevel = u.y(S.levels)
        val sleepMix = u.x(S.mixA)
        val cometMix = u.y(S.mixA)
        val thinkMix = u.z(S.mixA)
        val mutedMix = u.y(S.mixB)
        val contrast = u.x(S.tuneA)
        val brightness = u.y(S.tuneA)
        val response = u.z(S.tuneA)
        val interference = u.w(S.tuneA)
        val dotSize = u.x(S.tuneB)
        val glowKnob = u.y(S.tuneB)
        val phaseDiff = u.z(S.tuneB)
        val coupled = u.x(S.couplingA) > 0.5f

        val breath = if (rm) 0.5f else 0.5f + 0.5f * sin(time * TAU / 5.5f)
        val sleepPresence = clamp(0.34f + 0.40f * userLevel, 0.34f, 0.58f)
        val presence = clamp(sleepPresence * sleepMix + 0.92f * (1f - sleepMix), 0.24f, 1f)
        val duo = ws(0.03f, 0.14f, min(userLevel, agentLevel))
        val toneThink = (if (coupled) ws(0.08f, 0.36f, thinkMix) else 0f) * (1f - duo)
        val toneTalk = ws(0.28f, 0.72f, thinkMix) * ws(0.02f, 0.14f, userLevel) * (1f - duo)
        val bandGain = 1f - 0.72f * duck
        val sleepFloor = sleepMix
        val uKickGain = clamp(u.z(S.levels), -0.4f, 1.2f) * 0.24f
        val aKickGain = clamp(u.w(S.levels), -0.4f, 1.2f) * 0.24f
        val sinUx = sin(phase * 0.41f)
        val cosUx = cos(phase * 0.41f)
        val sinUy = sin(phase * 0.31f)
        val cosUy = cos(phase * 0.31f)
        val aPhase = -phase + 2.15f
        val sinAx = sin(aPhase * 0.39f)
        val cosAx = cos(aPhase * 0.39f)
        val sinAy = sin(aPhase * 0.29f)
        val cosAy = cos(aPhase * 0.29f)

        val r = sqrt(r2)
        val mask = ws(0.85f, 4.2f, radius * (1f - r))
        val z = sqrt(max(1f - r2, 0f))
        val n = V3(nx, ny, z)
        val pwr = powerSample(u, z)
        val gc = gazeCell(u, n, r)
        val wx = gc.n.x
        val wy = gc.n.y
        val cc = couplingCell(u, gc.n)

        val delayU = ((nx + 1f) * 0.5f) * 0.24f
        val uTrace = tracePast(traces, delayU)
        val uAmp = uTrace[0]
        val uH1 = uTrace[1]
        val onsetU = if (rm || abs(uKickGain) < 1e-4f) 0f
        else sin(sqrt((nx + 0.42f) * (nx + 0.42f) + ny2) * 9.5f - phase * 2.3f) * uKickGain
        val uu = cc.nu.x * cosUx + cc.nu.z * sinUx
        val uv = cc.nu.y * cosUy - cc.nu.z * sinUy
        val kU = 5.1f * cc.kU
        val waveU = sin(kU * (uu + uv * 0.46f) + phase * 1.12f + onsetU + 1.15f * uH1 + cc.argU + pwr.x) *
            cos(kU * (uv - uu * 0.34f) - phase * 0.82f)
        val bodyU = pow01(max(0.5f + 0.5f * waveU, 0f), contrast)
        var waveK = waveU
        var bodyV = bodyU
        if (toneTalk > 1e-3f) {
            val m = gc.n
            val ku = m.x * cosUx + m.z * sinUx
            val kv = m.y * cosUy - m.z * sinUy
            waveK = sin(5.1f * (ku + kv * 0.46f) + phase * 1.12f + onsetU + 1.15f * uH1) * cos(5.1f * (kv - ku * 0.34f) - phase * 0.82f)
            val bk = pow01(max(0.5f + 0.5f * waveK, 0f), contrast)
            bodyV = bodyU + (bk - bodyU) * toneTalk
        }
        val reachU = 1f - 0.34f * ws(-0.25f, 0.95f, nx) * (1f - min(uAmp, 1f))

        // The agent's counter-rotating ribbon enters from the right.
        val delayA = ((1f - nx) * 0.5f) * 0.24f
        val aTrace = tracePast(traces, delayA)
        val aAmp = aTrace[2]
        val aH1 = aTrace[3]
        val onsetA = if (rm || abs(aKickGain) < 1e-4f) 0f
        else sin(sqrt((nx - 0.42f) * (nx - 0.42f) + ny2) * 9.5f - phase * 2.1f) * aKickGain
        val au = cc.na.x * cosAx + cc.na.z * sinAx
        val av = cc.na.y * cosAy - cc.na.z * sinAy
        val kA = 4.9f * cc.kA
        val waveA = sin(kA * (au - av * 0.44f) - phase * 1.08f + onsetA + 1.15f * aH1 + cc.argA - 0.72f * pwr.x) *
            cos(kA * (av + au * 0.36f) + phase * 0.78f)
        val bodyA = pow01(max(0.5f + 0.5f * waveA, 0f), contrast)
        val reachA = 1f - 0.34f * ws(-0.25f, 0.95f, -nx) * (1f - min(aAmp, 1f))

        val special = specialLight(u, rm, n, r, breath, bodyU)
        val pwrLight = pwr.y + pwr.z * bodyU
        val awakeQuiet = 1f - 0.28f * u.x(S.attentiveA) * (1f - 0.70f * u.w(S.attentiveA))
        val idleFloor = 0.032f * (1f - 0.48f * sleepMix) +
            (0.055f + 0.055f * breath) * sleepFloor * (0.18f + 0.82f * bodyU) +
            0.11f * (1f - sleepMix) * awakeQuiet * bodyU * (1f - 0.65f * cometMix)

        val thinkExc = choreoExcite(u, gc.n, cc.thinkU, cc.thinkA, bodyU, bodyA)
        val voiceU = bodyV * uAmp * response * 0.92f * reachU
        var uExc = clamp(
            (idleFloor + voiceU + special + pwrLight + cc.liftU * (0.06f + 0.94f * bodyU) + thinkMix * bandGain * thinkExc[0]) * brightness,
            0f, 1.15f,
        )
        var aExc = clamp(
            (bodyA * aAmp * response * 0.92f * reachA + cc.liftA * (0.08f + 0.92f * bodyA) + thinkMix * bandGain * thinkExc[1]) * brightness,
            0f, 1.15f,
        )

        // `crisp_overlap`: crossing crests meet softly under a shared ceiling.
        if (cc.crisp > 0f) {
            val voiceDuo = ws(0.04f, 0.30f, sqrt(max(uAmp, 0f) * max(aAmp, 0f)))
            val thinkDuo = thinkMix * bandGain * ws(0.24f, 0.50f, cc.thinkA)
            val duoK = cc.crisp * max(voiceDuo, 0.75f * thinkDuo)
            if (duoK > 0f) {
                val crest = 0.26f * duoK * sqrt(bodyU * bodyA) * brightness
                val ceiling = 1.15f - 0.27f * duoK
                val nu = clamp(uExc * (1f - 0.34f * duoK * (1f - sqrt(bodyA))) + crest, 0f, ceiling)
                val na = clamp(aExc * (1f - 0.34f * duoK * (1f - sqrt(bodyU))) + crest, 0f, ceiling)
                uExc = nu
                aExc = na
            }
        }

        // Standing full-duplex interference fringes where the voices meet.
        val overlap = sqrt(max(uAmp, 0f) * max(aAmp, 0f) * reachU * reachA)
        val standing = max(0.5f + 0.5f * cos(13f * wx + phaseDiff) * cos(6.5f * wy), 0f)
        val fringe = clamp(overlap * interference * (standing * sqrt(standing)) * 0.85f * brightness, 0f, 1f)
        val thinkBand = thinkMix * bandGain * (cc.thinkU + cc.thinkA)
        val band = (waveU * cc.thinkU + waveA * cc.thinkA) / (cc.thinkU + cc.thinkA + 1e-3f)
        var weave = 0f
        if (toneTalk > 1e-3f) {
            val meet = sqrt(max(uAmp, 0f) * reachU * max(thinkBand, 0f))
            val st = 0.5f * (waveK + band)
            weave = toneTalk * meet * st * st
        }
        weave = clamp(weave * (0.6f + 0.4f * interference) * brightness, 0f, 1f)
        val kitShare = voiceU * brightness / (uExc + aExc + 0.03f)

        val inU = min((uExc + 0.55f * fringe + 0.70f * weave) * gc.gain + gc.light, 1.15f)
        val inA = min((aExc + 0.55f * fringe + 0.25f * weave) * gc.gain, 1.15f)
        if (rm) {
            phosphor[p4] = inU
            phosphor[p4 + 1] = inU
            phosphor[p4 + 2] = inA
            phosphor[p4 + 3] = inA
        } else {
            phosphor[p4] = max(inU, phosphor[p4] * fastFall)
            phosphor[p4 + 1] = max(inU, phosphor[p4 + 1] * slowFall)
            phosphor[p4 + 2] = max(inA, phosphor[p4 + 2] * fastFall)
            phosphor[p4 + 3] = max(inA, phosphor[p4 + 3] * slowFall)
        }

        val eu = phosphor[p4] * (1f - trail) + phosphor[p4 + 1] * trail
        val ea = phosphor[p4 + 2] * (1f - trail) + phosphor[p4 + 3] * trail
        val power = clamp(1f - (1f - min(eu, 1f)) * (1f - min(ea, 1f)), 0f, 1f)
        val fresh = clamp((inU + inA + 0.012f) / (eu + ea + 0.024f), 0f, 1f)

        // `Tone::cell`.
        var base = u.xyz(S.tintBase)
        var core = u.xyz(S.tintCore)
        val total = eu + ea
        val energyGate = ws(0.004f, 0.055f, total)
        val agentShare = clamp(0.5f + 0.5f * ((ea - eu) / (total + 0.025f)) * energyGate, 0f, 1f)
        if (toneThink > 1e-3f) base = mix3(base, RUNNING_ACCENT, agentShare * energyGate * thinkMix * 0.34f * toneThink)
        if (toneTalk > 1e-3f) {
            val kit = 0.88f * toneTalk * ws(0.02f, 0.10f, eu) * clamp(kitShare, 0f, 1f)
            base = mix3(base, USER_BASE, kit)
            core = mix3(core, USER_CORE, kit)
            val f = clamp(weave * 1.3f, 0f, 0.7f) * toneTalk
            base = mix3(base, FRINGE_RGB, f * 0.45f)
            core = mix3(core, FRINGE_RGB, f)
        }
        if (duo > 1e-3f) {
            val b = mix3(USER_BASE, AGENT_BASE, agentShare)
            val c = mix3(USER_CORE, AGENT_CORE, agentShare)
            val fMix = clamp(fringe * 1.15f, 0f, 0.85f)
            val d = duo * energyGate
            base = mix3(base, mix3(b, FRINGE_RGB, fMix * 0.65f), d)
            core = mix3(core, mix3(c, FRINGE_RGB, fMix), d)
        }
        if (gc.tint > 1e-3f) {
            base = mix3(base, PHOSPHOR_TEAL, gc.tint * 0.85f)
            core = mix3(core, PHOSPHOR_PEARL, gc.tint)
        }

        // `build_cell_render`.
        val p = clamp(power, 1e-5f, 1f)
        val floorAlpha = 0.068f * (1f - 0.70f * ws(0.35f, 0.95f, r)) * presence
        var dotA = (floorAlpha + (0.96f - floorAlpha) * pow01(p, 0.88f * (1.4f - 0.4f * fresh))) * mask
        val shrink = 1f + ((0.82f + 0.18f * fresh) - 1f) * ws(0.03f, 0.20f, p)
        var dotR = (0.25f + 0.17f * pow01(p, 0.6f)) * shrink * pitch * (dotSize / 0.78f)
        var haloA = if (p > 0.04f) clamp(0.132f * p * p * mask * clamp(glowKnob / 0.42f, 0f, 3f), 0f, 0.85f) else 0f
        val coreT = clamp(p * p * 1.1f, 0f, 0.90f) * (0.30f + 0.70f * fresh)
        var rgb = mix3(base, core, coreT)
        val haloR = if (haloA > 0f) dotR * 2f else dotR

        // `dim_cells_for_mute`.
        if (mutedMix > 1e-3f && dotA > 0f) {
            val rr = len2(cx, cy) / max(radius, 1f)
            dotA *= 1f - mutedMix * (0.42f + 0.45f * bump(rr, 0.42f))
            haloA *= 1f - 0.9f * mutedMix
            dotR *= 1f - 0.12f * mutedMix
            rgb = mix3(rgb, MUTED_DOT, 0.85f * mutedMix)
        }
        cells[c8] = dotR
        cells[c8 + 1] = haloR
        cells[c8 + 2] = dotA
        cells[c8 + 3] = haloA
        cells[c8 + 4] = rgb.x
        cells[c8 + 5] = rgb.y
        cells[c8 + 6] = rgb.z
        cells[c8 + 7] = 0f
    }
}
