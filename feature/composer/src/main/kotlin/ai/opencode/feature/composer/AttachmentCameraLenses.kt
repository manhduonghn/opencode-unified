package ai.opencode.feature.composer

import ai.opencode.core.strings.localized
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.appFormattingLocale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What the camera seam reports about the active capture device, in the device's own zoom space (a
 * logical multi-camera's 1.0 is its widest lens).
 *
 * On Android it comes from CameraX's `CameraInfo`: `ZoomState.minZoomRatio`/`maxZoomRatio`
 * and the physical cameras behind a logical camera (see [AttachmentCameraView]).
 */
data class CameraDeviceDescription(
    val position: Position,
    /** Physical lenses behind the device, any order. */
    val lenses: List<Lens>,
    /** Where each narrower lens takes over. */
    val switchOverFactors: List<Double> = emptyList(),
    val minZoom: Double = 1.0,
    val maxZoom: Double = 1.0,
    /** Full-quality sensor crops, like Camera's 2×. */
    val nativeCropFactors: List<Double> = emptyList(),
) {
    enum class Position { Back, Front }

    /** Ordered widest first. */
    enum class Lens { UltraWide, Wide, Telephoto }
}

/**
 * Lens buttons and zoom limits for one device. Displayed factors are relative to the wide lens, so
 * 1× is the wide lens even when the device's 1.0 is the ultra wide.
 */
class CameraZoomModel(device: CameraDeviceDescription) {
    data class Preset(
        val lens: CameraDeviceDescription.Lens,
        val deviceFactor: Double,
        val displayFactor: Double,
    ) {
        val id: Double get() = displayFactor

        val accessibilityLabel: String
            get() = when (lens) {
                CameraDeviceDescription.Lens.UltraWide -> localized(R.string.composer_camera_lens_ultra_wide_description)
                CameraDeviceDescription.Lens.Wide ->
                    if (displayFactor > 1.01) {
                        localized(R.string.composer_camera_lens_wide_zoom_description, number(displayFactor))
                    } else {
                        localized(R.string.composer_camera_lens_wide_description)
                    }
                CameraDeviceDescription.Lens.Telephoto -> localized(R.string.composer_camera_lens_telephoto_description, number(displayFactor))
            }
    }

    /** Device factor shown as 1×. */
    val wideFactor: Double
    val presets: List<Preset>

    /** Usable device zoom range (inclusive). */
    val range: ClosedFloatingPointRange<Double>

    init {
        val lenses = device.lenses.toSet().sorted()
        // The widest lens starts at 1.0; each narrower one at its switch-over factor.
        var factors = mutableListOf<Pair<CameraDeviceDescription.Lens, Double>>()
        lenses.forEachIndexed { index, lens ->
            if (index == 0) {
                factors.add(lens to 1.0)
            } else if (index - 1 < device.switchOverFactors.size) {
                factors.add(lens to device.switchOverFactors[index - 1])
            }
        }
        if (factors.isEmpty()) factors = mutableListOf(CameraDeviceDescription.Lens.Wide to 1.0)
        val wide = factors.firstOrNull { it.first == CameraDeviceDescription.Lens.Wide }?.second ?: factors[0].second
        wideFactor = wide

        val lower = max(1.0, device.minZoom)
        val cap = if (device.position == CameraDeviceDescription.Position.Front) MAX_DISPLAY_ZOOM_FRONT else MAX_DISPLAY_ZOOM_BACK
        val longestLens = factors.maxOfOrNull { it.second } ?: wide
        val upper = max(lower, min(max(device.maxZoom, lower), max(wide * cap, longestLens)))
        val zoomRange = lower..upper
        range = zoomRange

        val built = factors
            .filter { it.second in zoomRange }
            .map { Preset(lens = it.first, deviceFactor = it.second, displayFactor = it.second / wide) }
            .toMutableList()
        // Native crops of the wide lens (Camera's 2×), short of the next lens.
        val nextLens = factors.firstOrNull { it.second > wide }?.second ?: Double.POSITIVE_INFINITY
        for (crop in device.nativeCropFactors.sorted()) {
            if (!(crop > wide && crop < nextLens && crop in zoomRange)) continue
            val display = crop / wide
            if (built.any { abs(it.displayFactor - display) < 0.1 }) continue
            built.add(Preset(lens = CameraDeviceDescription.Lens.Wide, deviceFactor = crop, displayFactor = display))
        }
        presets = built.sortedBy { it.displayFactor }
    }

    val canZoom: Boolean get() = range.endInclusive - range.start > 0.01
    val defaultFactor: Double get() = clamp(wideFactor)

    fun clamp(factor: Double): Double = min(max(factor, range.start), range.endInclusive)

    fun displayed(factor: Double): Double = factor / wideFactor

    /** Direct manipulation: the pinch's scale multiplies the zoom it began at. */
    fun pinched(from: Double, scale: Double): Double = clamp(from * max(scale, 0.01))

    /** The lens button standing for [factor]: the narrowest preset at or below it. */
    fun activePreset(factor: Double): Preset? {
        val display = displayed(factor) + 0.005
        return presets.lastOrNull { it.displayFactor <= display } ?: presets.firstOrNull()
    }

    /** TalkBack's adjustable stops: every lens plus each whole displayed zoom. */
    fun step(from: Double, up: Boolean): Double {
        val stops = presets.map { it.deviceFactor }.toMutableSet()
        var whole = 1.0
        while (whole * wideFactor <= range.endInclusive + 0.001) {
            stops.add(whole * wideFactor)
            whole += 1
        }
        stops.add(range.start)
        stops.add(range.endInclusive)
        val sorted = stops.filter { it in range }.sorted()
        val target = if (up) sorted.firstOrNull { it > from + 0.01 } else sorted.lastOrNull { it < from - 0.01 }
        return target ?: from
    }

    override fun equals(other: Any?): Boolean =
        other is CameraZoomModel && wideFactor == other.wideFactor && presets == other.presets && range == other.range

    override fun hashCode(): Int = listOf(wideFactor, presets, range).hashCode()

    companion object {
        const val MAX_DISPLAY_ZOOM_BACK = 10.0
        const val MAX_DISPLAY_ZOOM_FRONT = 2.0

        /** "1.6×" for the live value, ".5" or "3" for an inactive lens button. */
        fun label(display: Double, active: Boolean): String {
            val text = number(display)
            if (active) return "$text×"
            return if (text.length > 1 && text.startsWith("0")) text.drop(1) else text
        }

        fun number(display: Double): String {
            val tenths = Math.round(display * 10) / 10.0
            if (display >= 10 || abs(tenths - Math.round(tenths).toDouble()) < 0.001) {
                return display.roundToInt().toString()
            }
            return String.format(appFormattingLocale(), "%.1f", tenths)
        }
    }
}

/**
 * The zoom the controls show, reconciling direct manipulation with the device's reports. Pinch and
 * TalkBack set the zoom directly; only a lens button's ramp takes the device's progress, matched by
 * the ramp's ID so late reports from an earlier ramp never pull the zoom back. A mutable value
 * type; [copy] gives an independent one.
 */
class CameraZoomState {
    data class Ramp(val id: UUID, val target: Double)

    /** Result of [select]: the zoom to set and, when it animates, the ramp whose progress to accept. */
    data class Selection(val target: Double, val ramp: UUID?)

    /** The device's zoom as last set or reported. */
    var factor: Double = 1.0
        private set
    var isPinching: Boolean = false
        private set

    /** A lens button's ramp still under way. */
    var ramp: Ramp? = null
        private set
    private var pinchStart = 1.0

    /** A tapped lens shows as chosen from the tap, not as the ramp passes the lenses between. */
    val shownFactor: Double get() = ramp?.target ?: factor

    fun reset(factor: Double) {
        this.factor = factor
        isPinching = false
        ramp = null
    }

    fun beginPinch() {
        isPinching = true
        ramp = null
        pinchStart = factor
    }

    /** The zoom to set, or null when no pinch began. */
    fun pinch(scale: Double, model: CameraZoomModel): Double? {
        if (!isPinching) return null
        factor = model.pinched(from = pinchStart, scale = scale)
        return factor
    }

    fun endPinch() {
        isPinching = false
    }

    fun select(preset: CameraZoomModel.Preset, model: CameraZoomModel, animated: Boolean, id: UUID = UUID.randomUUID()): Selection {
        val target = model.clamp(preset.deviceFactor)
        isPinching = false
        if (!animated || abs(target - factor) <= 0.001) {
            ramp = null
            factor = target
            return Selection(target, null)
        }
        ramp = Ramp(id, target)
        return Selection(target, id)
    }

    fun step(up: Boolean, model: CameraZoomModel): Double {
        factor = model.step(from = shownFactor, up = up)
        ramp = null
        return factor
    }

    fun rampProgressed(id: UUID, value: Double) {
        val current = ramp ?: return
        if (current.id != id) return
        factor = value
        if (abs(value - current.target) < 0.001) ramp = null
    }

    fun copy(): CameraZoomState = CameraZoomState().also {
        it.factor = factor
        it.isPinching = isPinching
        it.ramp = ramp
        it.pinchStart = pinchStart
    }

    override fun equals(other: Any?): Boolean = other is CameraZoomState &&
        factor == other.factor && isPinching == other.isPinching && ramp == other.ramp && pinchStart == other.pinchStart

    override fun hashCode(): Int = listOf(factor, isPinching, ramp, pinchStart).hashCode()
}

enum class AttachmentCameraFlash {
    Off,
    Auto,
    On,
    ;

    val next: AttachmentCameraFlash
        get() = when (this) {
            Off -> Auto
            Auto -> On
            On -> Off
        }

    val systemImage: SFSymbol
        get() = when (this) {
            Off -> SFSymbol.BoltSlashFill
            Auto -> SFSymbol.BoltBadgeAutomaticFill
            On -> SFSymbol.BoltFill
        }

    val accessibilityValue: String
        get() = when (this) {
            Off -> localized(R.string.composer_camera_flash_off)
            Auto -> localized(R.string.composer_camera_flash_auto)
            On -> localized(R.string.composer_camera_flash_on)
        }
}
