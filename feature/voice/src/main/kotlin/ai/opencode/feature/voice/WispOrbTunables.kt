package ai.opencode.feature.voice

import ai.opencode.core.tuning.Tunable
import ai.opencode.core.tuning.TuningStore
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** The voice orb's look, at Wisp's `OrbTuning` defaults. */
object WispOrbTunables {
    val size = Tunable("call.wispOrb.size", "Orb Size", default = 87.0, range = 44.0..120.0, step = 1.0)
    val diameter = Tunable("call.wispOrb.diameter", "Lattice Size (pt)", default = 88.0, range = 60.0..128.0, step = 1.0)
    val restScale = Tunable("call.wispOrb.restScale", "Rest Scale", default = 0.76, range = 0.6..1.0, step = 0.01)
    val pitch = Tunable("call.wispOrb.pitch", "Dot Pitch", default = 2.2, range = 1.5..4.0, step = 0.05)
    val dotSize = Tunable("call.wispOrb.dotSize", "Dot Size", default = 0.78, range = 0.4..1.2, step = 0.02)
    val glow = Tunable("call.wispOrb.glow", "Glow", default = 0.42, range = 0.0..1.2, step = 0.02)
    val brightness = Tunable("call.wispOrb.brightness", "Brightness", default = 1.15, range = 0.4..2.2, step = 0.05)
    val contrast = Tunable("call.wispOrb.contrast", "Contrast", default = 2.6, range = 1.0..4.5, step = 0.05)
    val response = Tunable("call.wispOrb.response", "Voice Response", default = 2.9, range = 0.5..5.0, step = 0.05)
    val interference = Tunable("call.wispOrb.interference", "Interference", default = 0.68, range = 0.0..1.5, step = 0.02)
    val phosphor = Tunable("call.wispOrb.phosphor", "Phosphor Trail", default = 0.62, range = 0.1..1.4, step = 0.02)
    val coupling = Tunable("call.wispOrb.coupling", "Transition Coupling", default = WispOrbCoupling.harmonicTwist)
    val gazeBlend = Tunable("call.wispOrb.gazeBlend", "Eye Blend", default = 0.92, range = 0.0..1.0, step = 0.02)
    val gazeReach = Tunable("call.wispOrb.gazeReach", "Eye Reach (°)", default = 40.0, range = 5.0..75.0, step = 1.0)
    val pupilSize = Tunable("call.wispOrb.pupilSize", "Pupil Size", default = 0.19, range = 0.06..0.42, step = 0.01)
    val pupilWell = Tunable("call.wispOrb.pupilWell", "Pupil Well", default = 0.96, range = 0.0..1.0, step = 0.02)
    val irisWidth = Tunable("call.wispOrb.irisWidth", "Iris Width", default = 0.30, range = 0.04..0.6, step = 0.01)
    val rimGlow = Tunable("call.wispOrb.rimGlow", "Rim Glow", default = 0.85, range = 0.0..2.0, step = 0.05)

    fun register(store: TuningStore = TuningStore.shared) {
        store.register(size)
        store.register(diameter, restScale, pitch, dotSize, glow, brightness, contrast, response, interference, phosphor)
        store.register(coupling)
        store.register(gazeBlend, gazeReach, pupilSize, pupilWell, irisWidth, rimGlow)
    }
}

/** Wisp's `OrbTuning`, read once per frame and clamped to its ranges. */
data class WispOrbTuning(
    val diameter: Float = 88f,
    val restScale: Float = 0.76f,
    val dotPitch: Float = 2.2f,
    val dotSize: Float = 0.78f,
    val glow: Float = 0.42f,
    val brightness: Float = 1.15f,
    val contrast: Float = 2.6f,
    val response: Float = 2.9f,
    val interference: Float = 0.68f,
    val phosphor: Float = 0.62f,
    val coupling: WispOrbCoupling = WispOrbCoupling.harmonicTwist,
    val gazeBlend: Float = 0.92f,
    val gazeReachDeg: Float = 40f,
    val pupilSize: Float = 0.19f,
    val pupilWell: Float = 0.96f,
    val irisWidth: Float = 0.30f,
    val rimGlow: Float = 0.85f,
) {
    fun clamped(): WispOrbTuning {
        fun c(v: Float, lo: Float, hi: Float, fallback: Float) = if (v.isFinite()) min(max(v, lo), hi) else fallback
        return copy(
            diameter = c(diameter, 32f, 220f, 88f),
            restScale = c(restScale, 0.55f, 1f, 0.76f),
            dotPitch = c(dotPitch, 1.2f, 6f, 2.2f),
            dotSize = c(dotSize, 0.3f, 1.5f, 0.78f),
            glow = c(glow, 0f, 2f, 0.42f),
            brightness = c(brightness, 0.2f, 3f, 1.15f),
            contrast = c(contrast, 0.6f, 6f, 2.6f),
            response = c(response, 0.2f, 8f, 2.9f),
            interference = c(interference, 0f, 2.5f, 0.68f),
            phosphor = c(phosphor, 0.05f, 2f, 0.62f),
            gazeBlend = c(gazeBlend, 0f, 1f, 0.92f),
            gazeReachDeg = c(gazeReachDeg, 5f, 75f, 40f),
            pupilSize = c(pupilSize, 0.06f, 0.42f, 0.19f),
            pupilWell = c(pupilWell, 0f, 1f, 0.96f),
            irisWidth = c(irisWidth, 0.04f, 0.6f, 0.30f),
            rimGlow = c(rimGlow, 0f, 2f, 0.85f),
        )
    }

    /** Columns (and rows) of the dot lattice. */
    val columns: Int get() = min(max(ceil(diameter / dotPitch).toInt(), 8), 160)

    companion object {
        val current: WispOrbTuning
            get() {
                val t = WispOrbTunables
                return WispOrbTuning(
                    diameter = t.diameter.value.toFloat(), restScale = t.restScale.value.toFloat(),
                    dotPitch = t.pitch.value.toFloat(), dotSize = t.dotSize.value.toFloat(),
                    glow = t.glow.value.toFloat(), brightness = t.brightness.value.toFloat(),
                    contrast = t.contrast.value.toFloat(), response = t.response.value.toFloat(),
                    interference = t.interference.value.toFloat(), phosphor = t.phosphor.value.toFloat(),
                    coupling = t.coupling.value, gazeBlend = t.gazeBlend.value.toFloat(),
                    gazeReachDeg = t.gazeReach.value.toFloat(), pupilSize = t.pupilSize.value.toFloat(),
                    pupilWell = t.pupilWell.value.toFloat(), irisWidth = t.irisWidth.value.toFloat(),
                    rimGlow = t.rimGlow.value.toFloat(),
                ).clamped()
            }
    }
}
