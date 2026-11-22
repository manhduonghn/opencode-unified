package ai.opencode.feature.settings.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Pieces both devices' screens draw with. The screens are illustrations at fixed logical sizes,
 * scaled into the devices, so their type is fixed too: the welcome's own copy carries font scaling
 * and TalkBack.
 */

internal object WelcomeInk {
    fun gray(white: Double, opacity: Double = 1.0): Color = Color(white.toFloat(), white.toFloat(), white.toFloat(), opacity.toFloat())

    fun hex(value: Long, opacity: Double = 1.0): Color = Color(0xFF000000 or value).copy(alpha = opacity.toFloat())

    /** Dark mode's secondary and tertiary labels. */
    val label2 = Color(235 / 255f, 235 / 255f, 245 / 255f, 0.6f)
    val label3 = Color(235 / 255f, 235 / 255f, 245 / 255f, 0.3f)
}

/**
 * Shows a row by its entrance progress [p] (0…1), rising [dy] points and sharpening from a blur,
 * optionally leaving by [out] (0…1). Reduce Motion keeps only the fade.
 */
internal fun Modifier.welcomeShow(
    p: Double,
    dy: Float = 6f,
    blur: Float = 3f,
    scale: Float = 1f,
    out: Double = 0.0,
    anchor: TransformOrigin = TransformOrigin.Center,
    reduced: Boolean,
): Modifier {
    val q = (1 - p).toFloat()
    val radius = if (reduced) 0f else blur * q.coerceIn(0f, 1f) + blur * out.toFloat()
    return graphicsLayer {
        alpha = ((p * 1.5).clamped01() * (1 - out)).toFloat()
        val s = if (reduced) 1f else 1 - (1 - scale) * q
        scaleX = s
        scaleY = s
        transformOrigin = anchor
        translationY = if (reduced) 0f else dy.dp.toPx() * q
    }.welcomeBlur(radius)
}

/** A blur of [radius] points, skipped when it wouldn't show. */
internal fun Modifier.welcomeBlur(radius: Float): Modifier =
    if (radius > 0.05f) blur(radius.dp, BlurredEdgeTreatment.Unbounded) else this

/**
 * The desktop's live status: the text dims a little and a light runs through it, by [amount] (0 at
 * rest). Still under Reduce Motion.
 */
internal fun Modifier.welcomeShimmer(amount: Double, wall: Double, reduced: Boolean): Modifier {
    val shown = if (reduced) 0.0 else amount.clamped01()
    if (shown <= 0.01) return this
    return graphicsLayer {
        alpha = (1 - 0.45 * shown).toFloat()
        compositingStrategy = CompositingStrategy.Offscreen
    }.drawWithContent {
        drawContent()
        val phase = ((wall / 1.5) % 1.0).toFloat()
        val width = size.width * 0.6f
        val left = (phase * 1.6f - 0.6f) * size.width
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, Color.White, Color.Transparent), startX = left, endX = left + width),
            topLeft = Offset(left, 0f),
            size = Size(width, size.height),
            alpha = shown.toFloat(),
            blendMode = BlendMode.SrcAtop,
        )
    }
}

/** Labels in one place, crossfading by weight. */
@Composable
internal fun WelcomeStack(
    labels: List<String>,
    weights: List<Double>,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.CenterStart,
    reduced: Boolean = false,
) {
    Box(modifier, contentAlignment = alignment) {
        labels.forEachIndexed { index, label ->
            // Each label mostly clears before the next lands, so two never read at once.
            val shown = ((weights[index] - 0.35) / 0.65).clamped01()
            Text(
                label,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .welcomeBlur(if (reduced) 0f else (2.5 * (1 - shown)).toFloat())
                    .alpha(shown.toFloat()),
            )
        }
    }
}

/**
 * Text arriving a word at a time; `progress` counts how far in it is. The words still to come are
 * laid out clear, so the text's lines never move.
 */
internal object WelcomeStreamedText {
    fun attributed(words: List<String>, progress: Double, color: Color, fade: Double = 2.2, code: FontFamily? = null): AnnotatedString =
        buildAnnotatedString {
            words.forEachIndexed { index, word ->
                val isCode = code != null && word.startsWith("`")
                val shown = if (isCode) word.replace("`", "") else word
                val run = if (index == words.size - 1) shown else "$shown "
                val alpha = ((progress - index) / fade).clamped01().toFloat()
                withStyle(SpanStyle(color = color.copy(alpha = color.alpha * alpha), fontFamily = if (isCode) code else null)) { append(run) }
            }
        }
}

/** A row's place in [WelcomeColumn]. */
private data class WelcomeRowData(val open: Float, val lift: Float) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = this@WelcomeRowData
}

/**
 * A row in [WelcomeColumn] that takes [open] of its height, [lift] of the way from its place down
 * to the composer.
 */
internal fun Modifier.welcomeRow(open: Double, lift: Double = 0.0): Modifier =
    then(WelcomeRowData(max(0.0, open).toFloat(), lift.toFloat())).alpha(if (open > 0.001) 1f else 0f)

/**
 * A transcript's rows, pinned to the latest like the app's list: stacked from the top, and scrolled
 * up once the last would pass the bottom. Each row takes its height times its open fraction, so a
 * row opening on a spring pushes what follows (and the scroll) smoothly; a sent message rises from
 * the composer, [liftDrop] (points) below the column's bottom.
 */
@Composable
internal fun WelcomeColumn(modifier: Modifier = Modifier, liftDrop: Float = 0f, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val placeables = measurables.map { it.measure(Constraints(maxWidth = width)) }
        val rows = measurables.map { it.parentData as? WelcomeRowData ?: WelcomeRowData(1f, 0f) }
        val heights = placeables.mapIndexed { index, placeable -> placeable.height * rows[index].open }
        val drop = liftDrop.dp.toPx()
        layout(width, height) {
            var y = min(0f, height - heights.sum())
            placeables.forEachIndexed { index, placeable ->
                val from = height + drop
                placeable.place(0, (y + (from - y) * rows[index].lift).toInt())
                y += heights[index]
            }
        }
    }
}

/** The places the scene's lights fly between, wherever the devices' layout puts them. */
internal enum class WelcomeTarget { PhoneSend, PhoneShot, DeskPrompt, DeskShip, BrowserPage }

/**
 * Where each [WelcomeTarget] is in the scene, in px: written as the screens are placed, read by the
 * carriers as they draw.
 */
internal class WelcomeTargets {
    var scene: LayoutCoordinates? = null
    val rects = HashMap<WelcomeTarget, Rect>()
}

internal val LocalWelcomeTargets = staticCompositionLocalOf<WelcomeTargets?> { null }

/** Marks this view's bounds as [target] for the scene's lights. */
internal fun Modifier.welcomeTarget(target: WelcomeTarget, targets: WelcomeTargets?): Modifier {
    if (targets == null) return this
    return onGloballyPositioned { coordinates ->
        val scene = targets.scene?.takeIf { it.isAttached } ?: return@onGloballyPositioned
        targets.rects[target] = scene.localBoundingBoxOf(coordinates, clipBounds = false)
    }
}

/** The apps' working glyph: a 3 × 3 dot matrix with a light running through it. */
@Composable
internal fun WelcomeWorkingDots(wall: Double, modifier: Modifier = Modifier, dot: Float = 3f, gap: Float = 2f, color: Color = Color.White, reduced: Boolean = false) {
    Canvas(modifier.size((dot * 3 + gap * 2).dp)) {
        val d = dot.dp.toPx()
        val g = gap.dp.toPx()
        for (index in 0 until 9) {
            val row = index / 3
            val column = index % 3
            val wave = 0.5 + 0.5 * sin((wall * 2.4 - (row + column) * 0.33) * PI * 2)
            val opacity = if (reduced) 0.6 else 0.22 + 0.78 * wave * wave
            drawCircle(color.copy(alpha = color.alpha * opacity.toFloat()), radius = d / 2, center = Offset(column * (d + g) + d / 2, row * (d + g) + d / 2))
        }
    }
}

/**
 * A pairing code's look: finder squares and seeded modules around a hole for the mark. Not a
 * readable code.
 */
internal object WelcomeQRShape {
    const val MODULES = 29
    const val HOLE = 11

    fun path(rect: Rect): Path {
        val unit = min(rect.width, rect.height) / MODULES
        val path = Path().apply { fillType = PathFillType.EvenOdd }
        for ((x, y) in cells) path.addRect(Rect(Offset(rect.left + x * unit, rect.top + y * unit), Size(unit, unit)))
        for ((cx, cy) in listOf(0 to 0, MODULES - 7 to 0, 0 to MODULES - 7)) {
            val x = rect.left + cx * unit
            val y = rect.top + cy * unit
            path.addRect(Rect(Offset(x, y), Size(unit * 7, unit * 7)))
            path.addRect(Rect(Offset(x + unit, y + unit), Size(unit * 5, unit * 5)))
            path.addRect(Rect(Offset(x + unit * 2, y + unit * 2), Size(unit * 3, unit * 3)))
        }
        return path
    }

    private val cells: List<Pair<Int, Int>> = buildList {
        var seed = 7u
        val low = (MODULES - HOLE) / 2
        val high = low + HOLE
        for (y in 0 until MODULES) for (x in 0 until MODULES) {
            val finder = (y < 8 && x < 8) || (y < 8 && x >= MODULES - 8) || (y >= MODULES - 8 && x < 8)
            val inHole = y in low until high && x in low until high
            seed = seed * 1_103_515_245u + 12345u
            if (!finder && !inHole && (seed and 0x7fff_ffffu).toDouble() / 0x7fff_ffff.toDouble() < 0.48) add(x to y)
        }
    }
}

/** The pairing code with the OpenCode mark in its hole. */
@Composable
internal fun WelcomeQRCode(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val side = min(size.width, size.height)
        val origin = Offset((size.width - side) / 2, (size.height - side) / 2)
        drawPath(WelcomeQRShape.path(Rect(origin, Size(side, side))), Color.White)
        // The mark (viewBox 46…466): a gray block under a white frame with a window.
        val mark = side * WelcomeQRShape.HOLE / WelcomeQRShape.MODULES * 0.7f
        val unit = mark / 420f
        val o = Offset(origin.x + (side - mark) / 2, origin.y + (side - mark) / 2)
        fun r(l: Float, t: Float, rr: Float, b: Float) = Rect(o.x + (l - 46) * unit, o.y + (t - 46) * unit, o.x + (rr - 46) * unit, o.y + (b - 46) * unit)
        drawRect(Color(0xFF5A5858), r(192f, 224f, 320f, 352f).topLeft, r(192f, 224f, 320f, 352f).size)
        val frame = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(r(128f, 96f, 384f, 416f))
            addRect(r(192f, 160f, 320f, 352f))
        }
        drawPath(frame, Color.White)
    }
}
