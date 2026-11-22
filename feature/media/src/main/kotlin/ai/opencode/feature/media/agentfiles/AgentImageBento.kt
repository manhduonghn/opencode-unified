package ai.opencode.feature.media

import ai.opencode.core.tuning.Tunable
import ai.opencode.core.tuning.TuningStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A frame in points (dp). */
data class BentoRect(val x: Double, val y: Double, val width: Double, val height: Double) {
    val minX: Double get() = x
    val minY: Double get() = y
    val maxX: Double get() = x + width
    val maxY: Double get() = y + height
}

/** The corners that lie on the bento's own corners (layout-relative). */
data class BentoCorners(
    val topStart: Boolean,
    val topEnd: Boolean,
    val bottomStart: Boolean,
    val bottomEnd: Boolean,
) {
    companion object {
        val all = BentoCorners(true, true, true, true)
        val none = BentoCorners(false, false, false, false)
    }
}

/**
 * How a batch of a reply's images is arranged: one full width up to `loneMaxHeight`; two side by
 * side, or stacked when both are wide; three as a hero beside (or above) the other two; four or
 * more as justified rows. The arrangement that crops least and stands closest to a comfortable
 * height wins, ties to the first. It depends only on the shapes and the width, so a settled batch
 * (`AgentImagePreviews.settleBento`) always lays out the same. Units are dp.
 */
class AgentImageBento(
    aspects: List<Double>,
    width: Double,
    spacing: Double = 4.0,
    loneMaxHeight: Double = 360.0,
) {
    enum class Arrangement(val rawValue: String) {
        Single("single"), Pair("pair"), Stack("stack"), HeroLeading("heroLeading"), HeroTop("heroTop"), Rows("rows")
    }

    /** [corners]: the corners on the bento's outer edge, rounded like a card. */
    data class Tile(val frame: BentoRect, val corners: BentoCorners)

    val arrangement: Arrangement
    val tiles: List<Tile>
    val height: Double
    val frames: List<BentoRect> get() = tiles.map { it.frame }

    init {
        val clamped = aspects.map { if (it.isFinite() && it > 0) min(max(it, ASPECT_MIN), ASPECT_MAX) else 1.0 }
        if (clamped.isEmpty() || width <= 0) {
            arrangement = Arrangement.Single
            tiles = emptyList()
            height = 0.0
        } else {
            val solver = Solver(clamped, width, spacing, loneMaxHeight)
            var best: Triple<Arrangement, List<BentoRect>, Double>? = null
            for ((arrangement, frames) in solver.candidates()) {
                val cost = solver.cost(frames, arrangement)
                if (best == null || cost < best.third - 1e-9) best = Triple(arrangement, frames, cost)
            }
            val (arrangement, frames, _) = best!!
            val height = frames.maxOfOrNull { it.maxY } ?: 0.0
            this.arrangement = arrangement
            this.height = height
            tiles = frames.map { frame ->
                Tile(frame, if (clamped.size == 1) BentoCorners.all else corners(frame, width, height))
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is AgentImageBento && other.arrangement == arrangement && other.tiles == tiles && other.height == height

    override fun hashCode(): Int = (arrangement.hashCode() * 31 + tiles.hashCode()) * 31 + height.hashCode()

    private class Solver(val aspects: List<Double>, val width: Double, val spacing: Double, val loneMaxHeight: Double) {
        val count: Int get() = aspects.size
        /** The tallest a bento of several images stands. */
        val maxHeight: Double get() = min(width * 1.2, 600.0)
        /** No tile's short side under this. */
        val minTile: Double get() = max(56.0, width * 0.2)

        fun candidates(): List<kotlin.Pair<Arrangement, List<BentoRect>>> = when (count) {
            1 -> listOf(Arrangement.Single to single())
            2 -> listOf(
                Arrangement.Pair to capped(row(listOf(0, 1), y = 0.0, width = width).first),
                Arrangement.Stack to capped(column(listOf(0, 1), x = 0.0, width = width)),
            )
            3, 4 -> buildList {
                add(Arrangement.HeroLeading to capped(heroLeading()))
                add(Arrangement.HeroTop to capped(heroTop()))
                if (count == 4) add(Arrangement.Rows to capped(rows()))
            }
            else -> listOf(Arrangement.Rows to capped(rows()), Arrangement.HeroTop to capped(heroTop()))
        }

        /**
         * Full width up to `loneMaxHeight`. A tall image that would stand much narrower keeps its
         * shape and its width, leading; one only a little narrower fills the width, cropping gently.
         */
        fun single(): List<BentoRect> {
            val aspect = aspects[0]
            val natural = width / aspect
            if (natural <= loneMaxHeight) return listOf(BentoRect(0.0, 0.0, width, natural))
            val w = loneMaxHeight * aspect
            return listOf(BentoRect(0.0, 0.0, if (w >= width * 0.8) width else w, loneMaxHeight))
        }

        /**
         * `indices` side by side across `width`, sharing one height: their own height, kept
         * between a third of the width and the tallest allowed, with no tile narrower than
         * `minTile`. Returns the frames and the row's natural height.
         */
        fun row(indices: List<Int>, x: Double = 0.0, y: Double, width: Double, height: Double? = null): kotlin.Pair<List<BentoRect>, Double> {
            val room = width - spacing * (indices.size - 1)
            val floor = min(minTile, room / max(indices.size, 1))
            val widths = DoubleArray(indices.size)
            val locked = BooleanArray(indices.size)
            while (true) {
                val taken = widths.indices.filter { locked[it] }.sumOf { widths[it] }
                val remaining = max(0.0, room - taken)
                val openTotal = widths.indices.filter { !locked[it] }.sumOf { aspects[indices[it]] }
                if (openTotal <= 0) break
                var clampedAny = false
                for (i in widths.indices) {
                    if (locked[i]) continue
                    val share = remaining * aspects[indices[i]] / openTotal
                    if (share < floor - 1e-6) {
                        widths[i] = floor
                        locked[i] = true
                        clampedAny = true
                    } else {
                        widths[i] = share
                    }
                }
                if (!clampedAny) break
            }
            val naturalHeights = widths.indices.map { widths[it] / aspects[indices[it]] }
            val natural = sqrt((naturalHeights.minOrNull() ?: 0.0) * (naturalHeights.maxOrNull() ?: 0.0))
            val h = height ?: min(maxOf(natural, this.width * 0.3, minTile), maxHeight)
            val frames = ArrayList<BentoRect>(indices.size)
            var left = x
            for ((n, w) in widths.withIndex()) {
                // The last tile takes what's left, so the row ends exactly at its edge.
                val tileWidth = if (n == indices.size - 1) x + width - left else w
                frames.add(BentoRect(left, y, tileWidth, h))
                left += tileWidth + spacing
            }
            return frames to natural
        }

        /** `indices` stacked, each `width` wide at its own height. */
        fun column(indices: List<Int>, x: Double, width: Double, y: Double = 0.0): List<BentoRect> {
            val frames = ArrayList<BentoRect>(indices.size)
            var top = y
            for (index in indices) {
                val h = width / aspects[index]
                frames.add(BentoRect(x, top, width, h))
                top += h + spacing
            }
            return frames
        }

        /**
         * The first image on the leading side, the rest stacked beside it, both sides the same
         * height. Solved exactly for the shapes when the hero takes 45–72% of the width; otherwise
         * the hero keeps that share and the stack shares its height out by shape, cropping.
         */
        fun heroLeading(): List<BentoRect> {
            val hero = aspects[0]
            val rest = (1 until count).toList()
            val gaps = spacing * (rest.size - 1)
            val inverse = rest.sumOf { 1 / aspects[it] }
            // hero width = hero · (stack width · inverse + gaps); both widths fill the row.
            var stack = (width - spacing - hero * gaps) / (1 + hero * inverse)
            var lead = width - spacing - stack
            val share = lead / width
            if (!(share >= 0.45 && share <= 0.72) || stack <= 0) {
                lead = width * min(max(if (share.isFinite()) share else 0.6, 0.45), 0.72)
                stack = width - spacing - lead
            }
            val height = lead / hero
            val frames = arrayListOf(BentoRect(0.0, 0.0, lead, height))
            // The stack fills the hero's height, each tile by its own shape.
            val room = height - gaps
            var top = 0.0
            for ((n, index) in rest.withIndex()) {
                val h = if (n == rest.size - 1) height - top else room * (1 / aspects[index]) / inverse
                frames.add(BentoRect(lead + spacing, top, stack, h))
                top += h + spacing
            }
            return frames
        }

        /** The first image full width (at most three quarters of the width tall), the rest in one row under it. */
        fun heroTop(): List<BentoRect> {
            val h = min(width / aspects[0], width * 0.75)
            val below = row((1 until count).toList(), y = h + spacing, width = width)
            return listOf(BentoRect(0.0, 0.0, width, h)) + below.first
        }

        /** Justified rows of two or three (one alone only when it's wide), the breaks chosen in order to keep each row near square tiles. */
        fun rows(): List<BentoRect> {
            val n = count
            val best = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
            val from = IntArray(n + 1)
            best[0] = 0.0
            for (end in 1..n) {
                for (size in 1..3) {
                    if (end - size < 0 || best[end - size] == Double.POSITIVE_INFINITY) continue
                    val indices = ((end - size) until end).toList()
                    if (size == 1 && aspects[indices[0]] < 1.7) continue
                    val total = best[end - size] + rowCost(indices)
                    if (total < best[end]) {
                        best[end] = total
                        from[end] = end - size
                    }
                }
            }
            if (best[n] == Double.POSITIVE_INFINITY) return row((0 until n).toList(), y = 0.0, width = width).first
            val breaks = ArrayList<IntRange>()
            var end = n
            while (end > 0) {
                breaks.add(0, from[end] until end)
                end = from[end]
            }
            val frames = ArrayList<BentoRect>(n)
            var y = 0.0
            for (range in breaks) {
                val laid = row(range.toList(), y = y, width = width)
                frames += laid.first
                y = (laid.first.firstOrNull()?.maxY ?: y) + spacing
            }
            return frames
        }

        private fun rowCost(indices: List<Int>): Double {
            val laid = row(indices, y = 0.0, width = width)
            val target = width / indices.size * (if (indices.size == 1) 0.5 else 0.95)
            val h = laid.first[0].height
            var cost = ((h - target) / width).pow(2)
            for ((frame, index) in laid.first.zip(indices)) cost += 4 * crop(frame.width, frame.height, aspects[index]).pow(2)
            return cost
        }

        /** Shrunk to `maxHeight` when taller: heights scale, widths stay, so tiles crop rather than leave a ragged edge. */
        fun capped(frames: List<BentoRect>): List<BentoRect> {
            val height = frames.maxOfOrNull { it.maxY } ?: 0.0
            if (height <= maxHeight) return frames
            // Gaps keep their size; only the tiles shrink.
            val scale = maxHeight / height
            return frames.map { frame ->
                val top = frame.minY * scale
                val bottom = frame.maxY * scale
                val gapAbove = if (frame.minY > 0.5) spacing * (1 - scale) else 0.0
                BentoRect(frame.minX, top + gapAbove, frame.width, max(1.0, bottom - top - gapAbove))
            }
        }

        /** Lower is better: crops (the worst counts most), distance from a comfortable height for the count, and any tile too small to read. */
        fun cost(frames: List<BentoRect>, arrangement: Arrangement): Double {
            if (frames.size != count) return Double.POSITIVE_INFINITY
            val crops = frames.zip(aspects).map { (frame, aspect) -> crop(frame.width, frame.height, aspect) }
            var cost = 4 * (crops.maxOrNull() ?: 0.0).pow(2) + 2 * crops.sum() / count
            val height = frames.maxOfOrNull { it.maxY } ?: 0.0
            val target = when (count) {
                1 -> height / width
                2 -> 0.7
                3 -> 0.85
                4 -> 0.95
                else -> 1.05
            }
            cost += (height / width - target).pow(2)
            for (frame in frames) {
                val short = min(frame.width, frame.height)
                if (short < minTile - 1e-6) cost += 40 * ((minTile - short) / width).pow(2) + 1
            }
            // Two wide images read best stacked; otherwise side by side.
            if (arrangement == Arrangement.Stack && !aspects.all { it >= 1.6 }) cost += 0.25
            return cost
        }
    }

    companion object {
        /** Shapes are laid out within this range; a stranger one shows whole in its tile. */
        const val ASPECT_MIN = 0.2
        const val ASPECT_MAX = 8.0
        /** A tile cropping more than this shows its image whole instead ([fits]). */
        const val MAX_CROP = 0.3

        /** The corners of `frame` that lie on the bento's own corners. */
        fun corners(frame: BentoRect, width: Double, height: Double): BentoCorners {
            val left = frame.minX < 0.5
            val right = frame.maxX > width - 0.5
            val top = frame.minY < 0.5
            val bottom = frame.maxY > height - 0.5
            return BentoCorners(topStart = left && top, topEnd = right && top, bottomStart = left && bottom, bottomEnd = right && bottom)
        }

        /** How much of an image of shape `aspect` a tile of `width × height` cuts away when it fills it (0 when it keeps its shape). */
        fun crop(width: Double, height: Double, aspect: Double): Double {
            if (width <= 0 || height <= 0 || aspect <= 0) return 0.0
            val ratio = (width / height) / aspect
            return 1 - min(ratio, 1 / ratio)
        }

        /** Whether an image of shape `aspect` shows whole in a tile of `width × height` (letterboxed on the tile) rather than filling it. */
        fun fits(width: Double, height: Double, aspect: Double): Boolean =
            aspect !in ASPECT_MIN..ASPECT_MAX || crop(width, height, aspect) > MAX_CROP
    }
}

/**
 * Lays out a batch's tiles at their [AgentImageBento] frames. Children are placed in order; a lone
 * tall image stands narrower than the width, and the layout is then its size.
 */
@Composable
fun AgentImageBentoLayout(
    aspects: List<Double>,
    modifier: Modifier = Modifier,
    spacing: Dp = AgentImageTunables.gutter,
    loneMaxHeight: Dp = 360.dp,
    content: @Composable () -> Unit,
) {
    SideEffect { AgentImageTunables.register() }
    val cache = remember(aspects, spacing, loneMaxHeight) { BentoCache() }
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val widthPoints = if (constraints.hasBoundedWidth) constraints.maxWidth / density.toDouble() else 360.0
        val bento = cache.bento(widthPoints) {
            AgentImageBento(aspects, widthPoints, spacing.value.toDouble(), loneMaxHeight.value.toDouble())
        }
        fun px(points: Double): Int = (points * density).roundToInt()
        val placeables = measurables.mapIndexedNotNull { index, measurable ->
            val tile = bento.tiles.getOrNull(index) ?: return@mapIndexedNotNull null
            val frame = tile.frame
            val w = max(0, px(frame.maxX) - px(frame.minX))
            val h = max(0, px(frame.maxY) - px(frame.minY))
            Triple(measurable.measure(Constraints.fixed(w, h)), px(frame.minX), px(frame.minY))
        }
        val layoutWidth = px(bento.frames.maxOfOrNull { it.maxX } ?: widthPoints)
        layout(layoutWidth, px(bento.height)) {
            for ((placeable, x, y) in placeables) placeable.placeRelative(x, y)
        }
    }
}

private class BentoCache {
    private var width = -1.0
    private var bento: AgentImageBento? = null

    fun bento(width: Double, make: () -> AgentImageBento): AgentImageBento {
        bento?.let { if (abs(this.width - width) < 0.5) return it }
        return make().also {
            this.width = width
            bento = it
        }
    }
}

/**
 * Image Previews' bento and grow tunables: live in the Tuning Panel; the plain properties read the
 * default while tuning is off.
 */
object AgentImageTunables {
    /** The Tuning Panel sections it registers (the key segment after the group), in order. */
    val sectionTitles = listOf("Images")

    val gutterTunable = Tunable("transcript.images.gutter", "Bento gutter (pt)", default = 4.dp, range = 0.0..12.0, step = 0.5)
    val linkMaxHeightTunable = Tunable("transcript.images.linkMaxHeight", "Image link height (pt)", default = 220.dp, range = 160.0..300.0, step = 10.0)
    /** Corners inside the bento; its outer ones are the card corner radius. */
    val innerRadiusTunable = Tunable("transcript.images.innerRadius", "Bento inner corners (pt)", default = 4.dp, range = 0.0..14.0, step = 1.0)

    /** Bento gutter. */
    val gutter: Dp get() = gutterTunable.value
    /** Image link height. */
    val linkMaxHeight: Dp get() = linkMaxHeightTunable.value
    /** Corners inside the bento. */
    val innerRadius: Dp get() = innerRadiusTunable.value

    fun register(store: TuningStore = TuningStore.shared) {
        store.register(gutterTunable, innerRadiusTunable, linkMaxHeightTunable)
    }
}
