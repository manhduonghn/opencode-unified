package ai.opencode.feature.drawer.catchup

import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.transitionBlurEffect
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.RollingNumber
import ai.opencode.core.ui.RollingNumberAlignment
import ai.opencode.core.ui.RollingNumberStyle
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.drawer.R
import ai.opencode.feature.drawer.style.DrawerMetrics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Catch Up's finish: the last card gone, Catch Up closes itself and the drawer's Catch Up row rolls
 * its count to 0, draws the 0 into a checkmark, turns its title to All Caught Up and sweeps a
 * shimmer across once (a soft band, then a narrow glint that overtakes it), rests, then folds away
 * like any shortcut. It plays while Catch Up is still closing (the return takes 0.45 s), so the
 * close, the check and the glow read as one motion rather than waiting for the exit.
 * [CatchUpFeature] owns the moment (`justFinished`, the `.success` haptic, the rest); the drawer
 * list's shortcut motion clocks it and [DrawerCatchUpFinishedLabel] draws it.
 *
 * As baked defaults (seconds unless noted).
 */
object CatchUpFinishTunables {
    /** Close once emptied elsewhere (s). */
    const val closeDelay = 0.1
    /** Row starts as Catch Up closes (s). */
    const val landDelay = 0.06
    /** Count roll (s). */
    const val roll = 0.26
    /** Check starts after landing (s). */
    const val checkDelay = 0.22
    /** 0 fade (s). */
    const val zeroFade = 0.16
    /** Check draw (s). */
    const val checkDraw = 0.32
    /** Title swap (s). */
    const val titleSwap = 0.32
    /** Title travel (pt). */
    const val titleTravel = 8.0
    /** Swap blur (pt). */
    const val blur = 3.0
    /** Glow starts after check (s). */
    const val glowDelay = 0.04
    /** Shimmer sweep (s). */
    const val glowDuration = 1.25
    /** Glow opacity. */
    const val glowOpacity = 0.75
    /** Glint opacity. */
    const val glintOpacity = 0.55
    /** Row tint at peak. */
    const val glowTint = 0.10
    /** Stays for (s). */
    const val rest = 4.0
    /** Reduce Motion: row starts as Catch Up closes (s). */
    const val reducedLandDelay = 0.06
    /** Reduce Motion: check starts after landing (s). */
    const val reducedCheckDelay = 0.16
}

/**
 * When each part of the finish happens and how the row looks at a moment, in seconds from the row's
 * landing (`landDelay` after Catch Up starts closing).
 */
data class CatchUpFinishTimeline(
    val landDelay: Double,
    val roll: Double,
    val checkDelay: Double,
    val zeroFade: Double,
    val checkDraw: Double,
    val titleSwap: Double,
    val titleTravel: Double,
    val blur: Double,
    val glowDelay: Double,
    val glowDuration: Double,
    val glowOpacity: Double,
    val glintOpacity: Double,
    val glowTint: Double,
    val rest: Double,
    val fade: Double,
    val reducedMotion: Boolean,
) {
    /** Seconds after Catch Up starts closing that the check starts and `.success` plays. */
    val checkTime: Double get() = landDelay + checkDelay

    /** Seconds after Catch Up starts closing that the row folds away. */
    val restEnd: Double get() = max(landDelay + rest, settlesAt)

    /** Seconds after Catch Up starts closing that the row stops moving. */
    val settlesAt: Double
        get() = if (reducedMotion) checkTime + fade
        else checkTime + maxOf(glowDelay + glowDuration + 0.15, checkDraw * 2, titleSwap * 2)

    data class Frame(
        val showsZero: Boolean,
        val countOpacity: Double,
        val countBlur: Double,
        val countScale: Double,
        val checkTrim: Double,
        val checkOpacity: Double,
        val oldTitleOpacity: Double,
        val oldTitleOffset: Double,
        val oldTitleBlur: Double,
        val newTitleOpacity: Double,
        val newTitleOffset: Double,
        val newTitleBlur: Double,
        /** 0 (off the leading edge) to 1 (off the trailing edge). */
        val sweepPosition: Double,
        val sweepOpacity: Double,
        /** A narrow glint that sets off after the sweep and overtakes it. */
        val glintPosition: Double,
        val glintOpacity: Double,
        val tint: Double,
    )

    /** The row [u] seconds after it lands; before landing it still reads Catch Up. */
    fun frame(u: Double): Frame {
        val t2 = checkDelay
        if (reducedMotion) {
            // Calm fallback: the count fades out as the check fades in, and the title
            // cross-fades, without travel, blur or sweep.
            val s = easeOut(segment(u, t2, fade))
            return Frame(
                showsZero = false, countOpacity = 1 - s, countBlur = 0.0, countScale = 1.0,
                checkTrim = 1.0, checkOpacity = s,
                oldTitleOpacity = 1 - s, oldTitleOffset = 0.0, oldTitleBlur = 0.0,
                newTitleOpacity = s, newTitleOffset = 0.0, newTitleBlur = 0.0,
                sweepPosition = 0.0, sweepOpacity = 0.0, glintPosition = 0.0, glintOpacity = 0.0, tint = 0.0,
            )
        }
        val z = easeOut(segment(u, t2 - 0.04, zeroFade))
        val trim = spring(u - t2, checkDraw)
        val s = spring(u - t2, titleSwap)
        val sweepStart = t2 + glowDelay
        val k = segment(u, sweepStart, glowDuration)
        val glint = segment(u, sweepStart + glowDuration * 0.2, glowDuration * 0.5)
        val tintK = segment(u, sweepStart - 0.1, glowDuration + 0.25)
        return Frame(
            showsZero = u >= 0,
            countOpacity = 1 - z,
            countBlur = blur * z,
            countScale = 1 - 0.14 * z,
            checkTrim = trim,
            checkOpacity = 1.0,
            oldTitleOpacity = 1 - clamp(s * 1.3),
            oldTitleOffset = -titleTravel * s,
            oldTitleBlur = blur * clamp(s),
            newTitleOpacity = clamp(s * 1.15),
            newTitleOffset = titleTravel * (1 - s),
            newTitleBlur = blur * (1 - clamp(s)),
            sweepPosition = easeInOut(k),
            sweepOpacity = sin(PI * k) * glowOpacity,
            glintPosition = easeInOut(glint),
            glintOpacity = sin(PI * glint) * glintOpacity,
            tint = glowTint * sin(PI * tintK),
        )
    }

    companion object {
        fun current(reducedMotion: Boolean): CatchUpFinishTimeline {
            val t = CatchUpFinishTunables
            return CatchUpFinishTimeline(
                landDelay = if (reducedMotion) t.reducedLandDelay else t.landDelay,
                roll = t.roll,
                checkDelay = if (reducedMotion) t.reducedCheckDelay else t.checkDelay,
                zeroFade = t.zeroFade,
                checkDraw = t.checkDraw,
                titleSwap = t.titleSwap,
                titleTravel = t.titleTravel,
                blur = t.blur,
                glowDelay = t.glowDelay,
                glowDuration = t.glowDuration,
                glowOpacity = t.glowOpacity,
                glintOpacity = t.glintOpacity,
                glowTint = t.glowTint,
                rest = t.rest,
                fade = MotionRole.Reduced.curve().duration,
                reducedMotion = reducedMotion,
            )
        }

        /**
         * The finish's reference clock in seconds: the monotonic `System.nanoTime` base Compose's
         * frame clock also uses. The drawer list records `start` from this when it first shows the
         * finished row.
         */
        fun referenceNow(): Double = System.nanoTime() / 1e9

        /** Seconds since [start] at [now] (both on [referenceNow]'s clock). */
        fun elapsed(since: Double, now: Double = referenceNow()): Double = now - since

        fun clamp(x: Double): Double = min(max(x, 0.0), 1.0)
        fun segment(t: Double, from: Double, over: Double): Double = clamp((t - from) / max(over, 0.0001))
        fun easeOut(x: Double): Double = 1 - (1 - clamp(x)).pow(3)
        fun easeInOut(x: Double): Double {
            val u = clamp(x)
            return if (u < 0.5) 4 * u * u * u else 1 - (-2 * u + 2).pow(3) / 2
        }

        /** A critically damped spring's 0→1 step response (`ω = 2π / duration`). */
        fun spring(t: Double, duration: Double): Double {
            if (t <= 0) return 0.0
            val omega = 2 * PI / max(duration, 0.0001)
            return 1 - (1 + omega * t) * exp(-omega * t)
        }
    }
}

/**
 * The Catch Up row once Catch Up is finished. [start] is the reference time
 * ([CatchUpFinishTimeline.referenceNow]) the drawer first showed it; `null` holds it at Catch Up
 * with its count. Per-frame values are read in draw/layer blocks, so the row recomposes only when
 * the count first shows 0.
 *
 * The drawer list's Catch Up row (`DrawerListSupportCells`) hosts it in its shortcut label.
 */
@Composable
fun DrawerCatchUpFinishedLabel(
    start: Double?,
    fromCount: Int,
    reducedMotion: Boolean,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
) {
    val timeline = remember(reducedMotion) { CatchUpFinishTimeline.current(reducedMotion) }
    val elapsed = remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(start, timeline) {
        if (start == null) {
            elapsed.doubleValue = 0.0
            return@LaunchedEffect
        }
        // Runs until settled.
        while (true) {
            val now = withFrameNanos { it / 1e9 }
            val e = CatchUpFinishTimeline.elapsed(start, now)
            elapsed.doubleValue = e
            if (e >= timeline.settlesAt) break
        }
    }
    val frameState = remember(timeline) { derivedStateOf { timeline.frame(elapsed.doubleValue - timeline.landDelay) } }
    val showsZero by remember(frameState) { derivedStateOf { frameState.value.showsZero } }

    val tone = CatchUpMetrics.readColor
    val background = OpenCodeTheme.colors.canvas
    val catchUpTitle = stringResource(R.string.drawer_catch_up_title)
    val allCaughtUp = stringResource(R.string.drawer_catch_up_all_caught_up)
    val shape = RoundedCornerShape(cornerRadius)
    val rollStyle = remember(timeline) { RollingNumberStyle.Native.copy(duration = timeline.roll, bounce = 0.0) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DrawerMetrics.rowHeight)
            .clip(shape)
            .drawBehind {
                val frame = frameState.value
                drawRect(background)
                drawRect(tone.copy(alpha = frame.tint.toFloat().coerceIn(0f, 1f)))
                if (frame.sweepOpacity > 0.001) {
                    val bandWidth = 150.dp.toPx()
                    val x = (-160.dp.toPx() + (size.width + 170.dp.toPx()) * frame.sweepPosition.toFloat())
                    val alpha = frame.sweepOpacity.toFloat().coerceIn(0f, 1f)
                    val brush = Brush.linearGradient(
                        0f to tone.copy(alpha = 0f),
                        0.38f to tone.copy(alpha = 0.30f * alpha),
                        0.5f to Color.White.copy(alpha = 0.16f * alpha),
                        0.62f to tone.copy(alpha = 0.30f * alpha),
                        1f to tone.copy(alpha = 0f),
                        start = Offset(x, size.height * 0.41f),
                        end = Offset(x + bandWidth, size.height * 0.59f),
                    )
                    drawRect(brush, topLeft = Offset(x, 0f), size = Size(bandWidth, size.height))
                }
                if (frame.glintOpacity > 0.001) {
                    // A 30-wide band twice the row's height, leaning 18°, lightening what's under it.
                    val glintWidth = 30.dp.toPx()
                    val x = -40.dp.toPx() + (size.width + 50.dp.toPx()) * frame.glintPosition.toFloat()
                    val alpha = frame.glintOpacity.toFloat().coerceIn(0f, 1f)
                    val brush = Brush.horizontalGradient(
                        0f to Color.White.copy(alpha = 0f),
                        0.5f to Color.White.copy(alpha = 0.7f * alpha),
                        1f to Color.White.copy(alpha = 0f),
                        startX = x,
                        endX = x + glintWidth,
                    )
                    rotate(18f, pivot = Offset(x + glintWidth / 2, size.height / 2)) {
                        drawRect(brush, topLeft = Offset(x, -size.height / 2), size = Size(glintWidth, size.height * 2), blendMode = BlendMode.Plus)
                    }
                }
            }
            .padding(start = 10.dp, end = 12.dp)
            .clearAndSetSemantics { contentDescription = allCaughtUp },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeIcon(
            SFSymbol.RectangleStack,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(22.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            val textStyle = OpenCodeTextStyles.body
            val oldTitleLayer = rememberGraphicsLayer()
            val newTitleLayer = rememberGraphicsLayer()
            Text(
                catchUpTitle,
                style = textStyle,
                maxLines = 1,
                modifier = Modifier.drawWithContent {
                    val frame = frameState.value
                    val alpha = frame.oldTitleOpacity.toFloat().coerceIn(0f, 1f)
                    if (alpha <= 0.001f) return@drawWithContent
                    oldTitleLayer.alpha = alpha
                    oldTitleLayer.translationY = frame.oldTitleOffset.toFloat().dp.toPx()
                    oldTitleLayer.renderEffect = transitionBlurEffect(frame.oldTitleBlur.toFloat().dp.toPx())
                    oldTitleLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(oldTitleLayer)
                },
            )
            Text(
                allCaughtUp,
                style = textStyle,
                maxLines = 1,
                modifier = Modifier.drawWithContent {
                    val frame = frameState.value
                    val alpha = frame.newTitleOpacity.toFloat().coerceIn(0f, 1f)
                    if (alpha <= 0.001f) return@drawWithContent
                    newTitleLayer.alpha = alpha
                    newTitleLayer.translationY = frame.newTitleOffset.toFloat().dp.toPx()
                    newTitleLayer.renderEffect = transitionBlurEffect(frame.newTitleBlur.toFloat().dp.toPx())
                    newTitleLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(newTitleLayer)
                },
            )
        }
        Spacer(Modifier.width(4.dp))
        Box(Modifier.widthIn(min = 18.dp), contentAlignment = Alignment.CenterEnd) {
            val countLayer = rememberGraphicsLayer()
            RollingNumber(
                value = if (showsZero) 0 else fromCount,
                style = OpenCodeTextStyles.subheadline,
                color = OpenCodeTheme.colors.secondaryText,
                motion = rollStyle,
                alignment = RollingNumberAlignment.End,
                modifier = Modifier.drawWithContent {
                    val frame = frameState.value
                    val alpha = frame.countOpacity.toFloat().coerceIn(0f, 1f)
                    if (alpha <= 0.001f) return@drawWithContent
                    val scale = frame.countScale.toFloat()
                    countLayer.alpha = alpha
                    countLayer.scaleX = scale
                    countLayer.scaleY = scale
                    countLayer.pivotOffset = Offset(size.width, size.height / 2f)
                    countLayer.renderEffect = transitionBlurEffect(frame.countBlur.toFloat().dp.toPx())
                    countLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(countLayer)
                },
            )
            CatchUpFinishCheck(
                tone = tone,
                trim = { frameState.value.checkTrim.toFloat() },
                opacity = { frameState.value.checkOpacity.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** The trailing checkmark the 0 becomes, drawn by trimming. */
@Composable
private fun CatchUpFinishCheck(tone: Color, trim: () -> Float, opacity: () -> Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        // The prototype's `M5.2 12.6 l4.3 4.3 9.3-10` in a 24-point box.
        val alpha = opacity()
        if (alpha <= 0.001f) return@Canvas
        val sx = size.width / 24f
        val sy = size.height / 24f
        val path = Path().apply {
            moveTo(5.2f * sx, 12.6f * sy)
            lineTo(9.5f * sx, 16.9f * sy)
            lineTo(18.8f * sx, 6.9f * sy)
        }
        val t = trim().coerceIn(0f, 1f)
        if (t <= 0f) return@Canvas
        val measure = PathMeasure().apply { setPath(path, false) }
        val trimmed = Path()
        measure.getSegment(0f, measure.length * t, trimmed, true)
        drawPath(
            trimmed,
            tone.copy(alpha = tone.alpha * alpha),
            style = Stroke(width = 1.95.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
