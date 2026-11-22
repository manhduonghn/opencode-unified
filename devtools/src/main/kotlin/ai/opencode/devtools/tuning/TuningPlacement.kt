package ai.opencode.devtools.tuning

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.max

/**
 * Where the tuning panel floats. The height is a share of the free band above the keyboard, so it
 * survives rotation; sides are physical.
 */
data class TuningPlacement(
    val side: Side = Side.Trailing,
    /** The centre's height as a share of the free band: 0 is the top corner. */
    val y: Double = 0.55,
    val isTucked: Boolean = false,
) {
    enum class Side { Leading, Trailing }

    /** Stored as JSON in app storage. */
    val text: String
        get() = runCatching {
            json.encodeToString(Stored.serializer(), Stored(if (side == Side.Leading) Stored.Side.Leading else Stored.Side.Trailing, y, isTucked))
        }.getOrDefault("")

    companion object {
        /** Anything unreadable (including a missing field) is the default. */
        fun fromText(text: String): TuningPlacement = runCatching {
            val stored = json.decodeFromString(Stored.serializer(), text)
            TuningPlacement(if (stored.side == Stored.Side.Leading) Side.Leading else Side.Trailing, stored.y, stored.isTucked)
        }.getOrDefault(TuningPlacement())

        private val json = Json { ignoreUnknownKeys = true }
    }

    /** The wire form, field for field. */
    @Serializable
    private class Stored(val side: Side, val y: Double, val isTucked: Boolean) {
        @Serializable
        enum class Side {
            @SerialName("leading") Leading,
            @SerialName("trailing") Trailing,
        }
    }
}

/** The geometry a placement resolves in. All values are dp; velocities are dp a second. */
data class TuningPlacementLayout(
    /** Where the panel may sit: the safe area above the keyboard, inset. */
    val bounds: Rect,
    /** The whole screen: thrown past its sides, the panel tucks away. */
    val screen: Rect,
) {
    /** The centre of something [size] at [placement] (the tab when tucked). */
    fun center(placement: TuningPlacement, size: Size): Offset {
        if (placement.isTucked) {
            val half = TabSize.width / 2 + 2
            val x = if (placement.side == TuningPlacement.Side.Leading) screen.left + half else screen.right - half
            return Offset(x, y(placement.y, TabSize.height))
        }
        val half = size.width / 2
        val x = if (placement.side == TuningPlacement.Side.Leading) bounds.left + half else bounds.right - half
        return Offset(x, y(placement.y, size.height))
    }

    /**
     * Where something [size] lands when let go at [releasedAt] moving at [velocity] (dp/s): the
     * side and height its throw carries it to, tucked if it carries far past the edge.
     */
    fun placement(releasedAt: Offset, velocity: Offset, size: Size): TuningPlacement {
        val projected = Offset(releasedAt.x + velocity.x * PROJECTION, releasedAt.y + velocity.y * PROJECTION)
        val side = if (projected.x < screen.center.x) TuningPlacement.Side.Leading else TuningPlacement.Side.Trailing
        val half = size.width / 2
        val past = if (side == TuningPlacement.Side.Leading) screen.left - (projected.x - half) else (projected.x + half) - screen.right
        val low = bounds.top + size.height / 2
        val high = max(low, bounds.bottom - size.height / 2)
        var share = 0.5
        if (high > low) {
            share = when {
                projected.y - low < CORNER_SNAP -> 0.0
                high - projected.y < CORNER_SNAP -> 1.0
                else -> ((projected.y - low) / (high - low)).toDouble()
            }
        }
        return TuningPlacement(side = side, y = share.coerceIn(0.0, 1.0), isTucked = past > size.width * TUCK_SHARE)
    }

    private fun y(share: Double, height: Float): Float {
        val low = bounds.top + height / 2
        val high = max(low, bounds.bottom - height / 2)
        return low + (high - low) * share.coerceIn(0.0, 1.0).toFloat()
    }

    companion object {
        const val MARGIN = 8f
        val TabSize = Size(44f, 72f)
        const val TAB_GLASS_WIDTH = 22f
        /** How far a throw carries, in seconds of its release velocity. */
        const val PROJECTION = 0.2f
        /** Within this of the band's top or bottom, it settles into the corner. */
        const val CORNER_SNAP = 36f
        /** How much of the panel past the edge tucks it. */
        const val TUCK_SHARE = 0.35f

        /** The safe area inset by [MARGIN], cut above the keyboard. */
        fun of(safeArea: Rect, screen: Rect, keyboardTop: Float? = null): TuningPlacementLayout {
            var bounds = safeArea.deflate(MARGIN)
            if (keyboardTop != null && keyboardTop - MARGIN < bounds.bottom) {
                bounds = Rect(bounds.left, bounds.top, bounds.right, bounds.top + max(0f, keyboardTop - MARGIN - bounds.top))
            }
            return TuningPlacementLayout(bounds, screen)
        }

        /**
         * Whether a drag on the tab pulls the panel back out: a tap, or a pull (or flick) away
         * from its edge.
         */
        fun pullsOut(translation: Float, velocity: Float, side: TuningPlacement.Side): Boolean {
            val inward = if (side == TuningPlacement.Side.Leading) 1f else -1f
            return translation * inward > 24 || velocity * inward > 300 || abs(translation) < 4
        }
    }
}
