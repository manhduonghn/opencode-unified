package ai.opencode.feature.settings.onboarding

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What carries each message between the devices, over both of them: a pearl of light with a
 * continuous comet tail for what the person says, leaving the phone's ↑ and absorbing into the
 * laptop's bubble, and the dark OpenCode webpage screenshot itself for the proof, lifting off the
 * browser and landing seamlessly on its tile in the phone's transcript. [targets] are in px, in the
 * scene's own coordinates.
 */
@Composable
internal fun WelcomeCarriers(frame: WelcomeFrame, targets: Map<WelcomeTarget, Rect>, layout: WelcomeSceneLayout, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    Box(modifier.size(layout.size.width.dp, layout.size.height.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = max(2.8f, layout.size.width * 0.011f) * density
            val from = targets[WelcomeTarget.PhoneSend]
            val prompt = targets[WelcomeTarget.DeskPrompt]
            val ship = targets[WelcomeTarget.DeskShip]
            val lift = frame.pearlArc.toFloat()
            if (frame.outbound != null && from != null && prompt != null) WelcomeCarriers.drawPearl(this, frame.outbound, from, prompt, lift, radius)
            if (frame.outbound2 != null && from != null && ship != null) WelcomeCarriers.drawPearl(this, frame.outbound2, from, ship, lift, radius)
        }
        val page = targets[WelcomeTarget.BrowserPage]
        val tile = targets[WelcomeTarget.PhoneShot]
        if (frame.inbound != null && page != null && tile != null) {
            WelcomeScreenshotCarrier(
                flight = frame.inbound,
                from = page,
                to = tile,
                lift = frame.shotArc.toFloat(),
                apexScale = frame.shotScale.toFloat(),
                fromRadius = 8.5f * layout.laptopScale * density,
                toRadius = WelcomePhone.TILE_RADIUS * layout.phoneScale * density,
            )
        }
    }
}

internal object WelcomeCarriers {
    /**
     * Smooth thrown-arc progress [tau] seconds into a trip of [travel] seconds: crisp launch
     * impulse off the button, visible arc across the gap, and a zero-overshoot glide into the
     * destination.
     */
    fun along(tau: Double, travel: Double): Double {
        if (tau <= 0) return 0.0
        if (tau >= travel || travel <= 0) return 1.0
        val u = (tau / travel).clamped01()
        return 1 - (1 - u).pow(2.4)
    }

    /** A point on the thrown quadratic Bézier arc from [a] to [b], rising by [lift] of the distance between them at the apex. */
    fun arc(s: Double, a: Offset, b: Offset, lift: Float): Offset {
        val distance = hypot(b.x - a.x, b.y - a.y)
        val control = Offset((a.x + b.x) / 2, min(a.y, b.y) - distance * lift)
        val t = s.toFloat()
        return Offset(
            (1 - t) * (1 - t) * a.x + 2 * (1 - t) * t * control.x + t * t * b.x,
            (1 - t) * (1 - t) * a.y + 2 * (1 - t) * t * control.y + t * t * b.y,
        )
    }

    private val pearl = Color(1f, 0.96f, 0.9f)
    private val corona = Color(0.45f, 0.68f, 1f)

    private fun paint(color: Color, alpha: Double, blur: Float = 0f, stroke: Float = 0f, round: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.copy(alpha = (color.alpha * alpha).toFloat().coerceIn(0f, 1f)).toArgb()
        if (blur > 0.5f) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        if (stroke > 0) {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            if (round) {
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
        }
    }

    private fun circle(center: Offset, radius: Float) = RectF(center.x - radius, center.y - radius, center.x + radius, center.y + radius)

    /**
     * The spoken message's light: a press bloom + ring where ↑ fires, a continuous tapered comet
     * ribbon along the arc, a velocity-aligned pearl head, and a bubble-shaped arrival ripple that
     * cools smoothly. Lengths are px.
     */
    fun drawPearl(scope: DrawScope, flight: WelcomeFrame.Flight, from: Rect, to: Rect, lift: Float, r: Float) = scope.drawIntoCanvas { canvas ->
        val c = canvas.nativeCanvas
        val start = from.center
        val end = to.center
        val tau = flight.tau
        val travel = flight.travel
        val opacity = flight.opacity

        // Leaving: ↑ flashes with a crisp press ring and warm bloom.
        val launch = (tau / 0.22).clamped01()
        if (launch < 1) {
            val cool = (1 - launch) * (1 - launch)
            val size = r * (2.2f + 3.2f * launch.toFloat())
            c.drawOval(circle(start, size), paint(pearl, 0.55 * cool * opacity, blur = r * 1.8f))
            val ring = r * (1.6f + 3.4f * launch.toFloat())
            c.drawOval(circle(start, ring), paint(pearl, 0.45 * cool * opacity, stroke = max(0.5f, r * 0.28f * (1 - launch).toFloat())))
        }

        // Continuous comet tail: stroked round-capped segments so the ribbon stays seamless at any
        // speed and cools into the bubble on landing.
        val tailSpan = 0.17
        if (tau < travel + tailSpan) {
            val headTau = min(tau, travel)
            val tailTau = max(0.0, tau - tailSpan)
            val postFade = 1 - ((tau - travel) / tailSpan).clamped01()
            val steps = 18
            val points = (0..steps).map { step ->
                val f = step.toDouble() / steps
                arc(along(tailTau + (headTau - tailTau) * f, travel), start, end, lift)
            }
            val trail = android.graphics.Path().apply {
                moveTo(points[0].x, points[0].y)
                for (point in points.drop(1)) lineTo(point.x, point.y)
            }
            c.drawPath(trail, paint(corona, 0.28 * opacity * postFade, blur = r * 1.5f, stroke = r * 1.4f, round = true))
            val midS = along((tailTau + headTau) * 0.5, travel)
            val depth = sin(PI * midS).toFloat()
            for (step in 0 until steps) {
                val f = (step + 1).toFloat() / steps
                val width = r * (0.28f + 1.35f * f * f) * (1 + 0.22f * depth) * postFade.toFloat()
                if (width <= 0.25f) continue
                val tint = if (f > 0.55f) pearl else corona
                val alpha = (0.05 + 0.36 * f * f) * postFade * opacity
                c.drawLine(points[step].x, points[step].y, points[step + 1].x, points[step + 1].y, paint(tint, alpha, stroke = width, round = true))
            }
        }

        if (tau < travel) {
            val s = along(tau, travel)
            val ahead = along(min(travel, tau + 0.016), travel)
            val depth = sin(PI * s).toFloat()
            val point = arc(s, start, end, lift)
            val next = arc(ahead, start, end, lift)
            val speed = min(1f, hypot(next.x - point.x, next.y - point.y) / max(1f, r * 3.2f))
            val angle = atan2(next.y - point.y, next.x - point.x)
            val size = r * (0.82f + 0.18f * launch.toFloat()) * (1 + 0.28f * depth)

            // Atmospheric dual halo (cool blue outer + warm inner).
            c.drawOval(circle(point, size * 3), paint(corona, 0.32 * opacity, blur = size * 2.4f))
            c.drawOval(circle(point, size * 2), paint(pearl, 0.46 * opacity, blur = size * 1.3f))

            // Velocity-stretched pearl head.
            c.save()
            c.translate(point.x, point.y)
            c.rotate(Math.toDegrees(angle.toDouble()).toFloat())
            val stretchX = 1 + 0.16f * speed
            val stretchY = 1 / sqrt(stretchX)
            val rx = size * stretchX
            val ry = size * stretchY
            val alpha = (opacity * 255).roundToInt().coerceIn(0, 255)
            val head = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    -rx * 0.22f, -ry * 0.28f, max(0.01f, rx * 1.25f),
                    intArrayOf(Color.White.toArgb(), pearl.toArgb(), Color(0.78f, 0.86f, 1f).toArgb()),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP,
                )
                this.alpha = alpha
            }
            c.drawOval(RectF(-rx, -ry, rx, ry), head)
            c.drawOval(RectF(-rx * 0.48f, -ry * 0.62f, 0f, -ry * 0.26f), paint(Color.White, 0.92 * opacity))
            c.restore()
        }

        // Absorbed: a rounded-rect glow and ripple conform to the prompt bubble and cool smoothly,
        // rather than cutting a circular ring across its text.
        val landed = tau - travel
        if (landed >= 0) {
            val p = (landed / WelcomeFrame.Flight.AFTERGLOW).clamped01()
            val fade = (1 - p) * (1 - p)
            val corner = min(to.height / 2, r * 2.4f)
            val glow = to.inflate(r * 0.8f)
            c.drawRoundRect(RectF(glow.left, glow.top, glow.right, glow.bottom), corner, corner, paint(corona, 0.34 * fade * opacity, blur = r * 2.4f))
            val spread = r * 2.6f * (1 - (1 - p) * (1 - p)).toFloat()
            val ripple = RectF(to.left - spread, to.top - spread * 0.75f, to.right + spread, to.bottom + spread * 0.75f)
            val rippleCorner = corner + spread * 0.6f
            c.drawRoundRect(ripple, rippleCorner, rippleCorner, paint(pearl, 0.52 * fade * opacity, stroke = max(0.5f, r * 0.32f * (1 - p).toFloat())))
            val sink = r * 0.85f * (1 - (landed / 0.12).clamped01()).toFloat()
            if (sink > 0.05f) c.drawOval(circle(end, sink), paint(pearl, opacity))
        }
    }
}

/**
 * The dark OpenCode website screenshot crossing from the laptop browser to the phone: it lifts off
 * the page along an arc with subtle bank and depth, carries a crisp rim catchlight so dark-on-dark
 * stays unmistakable in flight, and cools seamlessly over the resting tile with zero opacity dip.
 * Rects and radii are px.
 */
@Composable
private fun WelcomeScreenshotCarrier(
    flight: WelcomeFrame.Flight,
    from: Rect,
    to: Rect,
    lift: Float,
    apexScale: Float,
    fromRadius: Float,
    toRadius: Float,
) {
    val density = LocalDensity.current.density
    val tau = flight.tau
    val s = WelcomeCarriers.along(tau, flight.travel)
    val landed = max(0.0, tau - flight.travel)
    val handoff = 1 - (landed / 0.16).clamped01()
    val rimFade = handoff.toFloat()
    val t = s.toFloat()
    val depth = sin(PI * s).toFloat()
    val width = from.width + (to.width - from.width) * t
    val height = from.height + (to.height - from.height) * t
    val center = WelcomeCarriers.arc(s, from.center, to.center, lift)
    val radius = fromRadius + (toRadius - fromRadius) * t
    val shape = RoundedCornerShape((radius / density).dp)
    Box(
        Modifier
            .layout { measurable, _ ->
                val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1)))
                layout(0, 0) { placeable.place((center.x - width / 2).roundToInt(), (center.y - height / 2).roundToInt()) }
            }
            .graphicsLayer {
                alpha = (flight.opacity * handoff).toFloat()
                rotationZ = -1.6f * depth
                val scale = 1 + apexScale * depth
                scaleX = scale
                scaleY = scale
            }
            .drawBehind {
                // Two drop shadows, near and far, deepening at the apex.
                fun shadow(alpha: Float, blur: Float, dy: Float) {
                    if (alpha <= 0.001f) return
                    drawIntoCanvas {
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.TRANSPARENT
                            setShadowLayer(max(0.5f, blur * density), 0f, dy * density, Color.Black.copy(alpha = alpha.coerceIn(0f, 1f)).toArgb())
                        }
                        it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, paint)
                    }
                }
                shadow((0.35f + 0.25f * depth) * rimFade, 3 + 4 * depth, 2 + 2 * depth)
                shadow((0.28f + 0.38f * depth) * rimFade, 8 + 16 * depth, 4 + 12 * depth)
            }
            .clip(shape)
            .drawWithContent {
                drawContent()
                val inset = 0.375.dp.toPx()
                drawRoundRect(
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = ((0.28f + 0.36f * depth) * rimFade).coerceIn(0f, 1f)),
                            Color.White.copy(alpha = ((0.12f + 0.18f * depth) * rimFade).coerceIn(0f, 1f)),
                        ),
                    ),
                    topLeft = Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset),
                    cornerRadius = CornerRadius(max(0f, radius - inset)),
                    style = Stroke(0.75.dp.toPx()),
                )
            },
    ) {
        WelcomeScreenshot.Image(Modifier.requiredSize((width / density).dp, (height / density).dp))
    }
}

