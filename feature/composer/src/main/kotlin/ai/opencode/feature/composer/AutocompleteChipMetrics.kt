package ai.opencode.feature.composer

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Metrics for inline solid chips, matching `CHIP` in `model.js`. Lengths in dp, font in sp. */
object AutocompleteChipMetrics {
    const val height: Double = 22.0
    const val leadingPad: Double = 6.0
    const val iconSize: Double = 14.0
    const val gap: Double = 5.0
    const val trailingPad: Double = 8.0
    const val cornerRadius: Double = 7.0
    const val fontSize: Double = 15.0
    /** Solid opaque dark tone `rgb(56, 56, 59)`. */
    const val chipToneDark: Double = 56.0
    const val chipToneLight: Double = 224.0

    /**
     * `max(height, ceil(lineHeight + 4))` of the medium 15 pt subheadline font, scaled.
     * [fontLineHeight] is the scaled chip font's line height in dp.
     */
    fun scaledHeight(fontLineHeight: Double): Double = max(height, ceil(fontLineHeight + 4))

    /**
     * The chip's full width for a title measured at [titleWidth] dp in the chip font, never wider
     * than [maxWidth] (but at least 44 dp).
     */
    fun width(titleWidth: Double, maxWidth: Double = Double.MAX_VALUE): Double {
        val textWidth = ceil(titleWidth) + 3
        val full = leadingPad + iconSize + gap + textWidth + trailingPad
        return min(full, max(44.0, maxWidth))
    }
}
