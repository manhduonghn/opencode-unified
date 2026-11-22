package ai.opencode.feature.session.header

import ai.opencode.core.ui.OpenCodeTextStyles
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * What sits behind the session header: a background-coloured wash (and optional tonal surface)
 * under the controls. With no system scroll-edge effect, [Environment] defaults to the app-drawn
 * band. Distances are dp relative to the header's bottom edge (the controls row, 52 below the safe
 * area at the default font scale); negative is above it.
 */
@Immutable
data class SessionHeaderStyle(
    /** How far the top-edge pocket reaches past the header; null turns the edge band off. */
    val pocketExtension: Float? = null,
    /** The system's hard edge, which draws a dividing line. */
    val hairline: Boolean = false,
    val light: Appearance = Appearance(),
    val dark: Appearance = Appearance(),
) {
    enum class Curve { Linear, Smoothstep }

    enum class Blur { UltraThin, Thin, Regular }

    @Immutable
    data class Appearance(
        /** The wash's opacity down to [fadeStart]. */
        val opacity: Double = 0.0,
        /** Where the wash and blur begin to ease out. */
        val fadeStart: Float = 0f,
        /** Where they reach zero. */
        val fadeEnd: Float = 0f,
        val curve: Curve = Curve.Smoothstep,
        /** A material under the wash, masked like it (Android: a tonal surface). */
        val blur: Blur? = null,
        /**
         * Scroll distance over which the backdrop appears, so a transcript at rest isn't washed.
         * 0 shows it at once.
         */
        val fadeIn: Float = 24f,
    ) {
        /** 1 down to [fadeStart], easing to 0 at [fadeEnd]. */
        fun strength(y: Float): Double {
            if (y <= fadeStart) return 1.0
            if (fadeEnd <= fadeStart || y >= fadeEnd) return 0.0
            val t = ((y - fadeStart) / (fadeEnd - fadeStart)).toDouble()
            return when (curve) {
                Curve.Linear -> 1 - t
                Curve.Smoothstep -> 1 - t * t * (3 - 2 * t)
            }
        }

        /**
         * Gradient stops (fraction, colour) over a band from the top of the screen to [fadeEnd]
         * below the safe area, for `Brush.verticalGradient(*stops)`.
         */
        fun stops(safeTop: Float, color: Color, opacity: Double): List<Pair<Float, Color>> {
            val height = safeTop + fadeEnd
            if (height <= 0) return emptyList()
            val start = safeTop + min(max(fadeStart, -safeTop), fadeEnd)
            val samples = (0..8).map { start + (height - start) * it / 8f }
            return (listOf(0f) + samples).map { y ->
                (y / height) to color.copy(alpha = (color.alpha * opacity * strength(y - safeTop)).toFloat().coerceIn(0f, 1f))
            }
        }

        /** How much of the backdrop shows once content has scrolled [distance] under the header. */
        fun revealed(distance: Float): Double {
            if (fadeIn <= 0) return 1.0
            return min(max(distance / fadeIn, 0f), 1f).toDouble()
        }
    }

    /** What differs between phones. */
    @Immutable
    data class Environment(
        /**
         * Android has no system edge: kept for parity with [forceAppBlur] defaulting to true, so
         * the app draws its own band.
         */
        val osVersion: String = "27.0",
        /** Developer Mode's Force App Blur. Always on for Android (see [osVersion]). */
        val forceAppBlur: Boolean = true,
        /** Android has no system setting; hosts pass false. */
        val reduceTransparency: Boolean = false,
        val increaseContrast: Boolean = false,
        val isDark: Boolean = false,
        /** For diagnostics; font scale reaches the result through [headerHeight]. */
        val sizeCategory: String = "large",
        /** The header as laid out, below the safe area (dp). */
        val headerHeight: Float = DEFAULT_HEADER_HEIGHT,
    )

    /** The backdrop and edge to draw. Distances are below the safe area. */
    @Immutable
    data class Resolved(
        val blur: ResolvedBlur,
        val backdrop: Appearance?,
        val contentTop: Float,
        val header: Float = 0f,
    ) {
        data class SystemEdge(val pocket: Float, val isHard: Boolean)

        /**
         * The app's progressive band: full down to [fadeStart], gone at [fadeEnd], shown as content
         * scrolls [fadeIn] under the header. [radius] only gates whether the band draws (radius >
         * 0).
         */
        data class AppBlur(
            val fadeStart: Float,
            val fadeEnd: Float,
            val fadeIn: Float = 24f,
            val radius: Float = 8f,
        )

        sealed interface ResolvedBlur {
            data object None : ResolvedBlur
            data class System(val edge: SystemEdge) : ResolvedBlur
            data class App(val blur: AppBlur) : ResolvedBlur
        }

        /** What the transcript needs: where content rests, the edge, and how far a scroll reveals the backdrop. */
        data class TranscriptTop(
            val contentTop: Float = MINIMUM_CONTENT_TOP,
            val systemEdge: SystemEdge? = null,
            val revealDistance: Float = 0f,
            /** The header's own height below the safe area. */
            val header: Float = 0f,
        )

        val systemEdge: SystemEdge? get() = (blur as? ResolvedBlur.System)?.edge
        val appBlur: AppBlur? get() = (blur as? ResolvedBlur.App)?.blur

        val transcriptTop: TranscriptTop
            get() = TranscriptTop(
                contentTop = contentTop,
                systemEdge = systemEdge,
                revealDistance = max(backdrop?.fadeIn ?: 0f, appBlur?.fadeIn ?: 0f),
                header = header,
            )
    }

    /**
     * Every phone gets the same structure. The OS picks only where the blur comes from: the same
     * pocket, as the system edge or the app's band. Reduce Transparency trades both for an opaque
     * fade under the header; Increase Contrast makes the wash opaque through the header. Font
     * scale reaches the result only through the measured height.
     */
    fun resolved(environment: Environment): Resolved {
        val header = max(environment.headerHeight, 0f)
        val contentTop = max(MINIMUM_CONTENT_TOP, header + RESTING_GAP)
        if (environment.reduceTransparency) {
            // Always shown, and ending where content rests, so nothing moves under a translucent band.
            val solid = Appearance(opacity = 1.0, fadeStart = header, fadeEnd = contentTop, fadeIn = 0f)
            return Resolved(blur = Resolved.ResolvedBlur.None, backdrop = solid, contentTop = contentTop, header = header)
        }
        val blur: Resolved.ResolvedBlur = pocketExtension?.let { extension ->
            val pocket = max(header + extension, 0f)
            if (usesAppBlur(environment.osVersion, environment.forceAppBlur)) {
                Resolved.ResolvedBlur.App(Resolved.AppBlur(fadeStart = max(pocket - 12, 0f), fadeEnd = pocket + 16))
            } else {
                Resolved.ResolvedBlur.System(Resolved.SystemEdge(pocket = pocket, isHard = hairline))
            }
        } ?: Resolved.ResolvedBlur.None
        val base = if (environment.isDark) dark else light
        var backdrop = base.copy(fadeStart = base.fadeStart + header, fadeEnd = base.fadeEnd + header)
        if (environment.increaseContrast) {
            backdrop = backdrop.copy(
                opacity = max(backdrop.opacity, 0.9),
                fadeStart = max(backdrop.fadeStart, header),
                fadeEnd = max(backdrop.fadeEnd, header + 24),
            )
        }
        val draws = backdrop.fadeEnd > 0 && (backdrop.opacity > 0 || backdrop.blur != null)
        return Resolved(blur = blur, backdrop = if (draws) backdrop else null, contentTop = contentTop, header = header)
    }

    companion object {
        /** The header's height at the default font scale: 8 above a 44 row. */
        const val DEFAULT_HEADER_HEIGHT = 52f

        /** The soft edge over the header, nothing else. */
        val shipped = SessionHeaderStyle(pocketExtension = 0f)

        /**
         * Where the transcript's first row rests: `TranscriptMetrics.headerBand`, or 8
         * (`TranscriptMetrics.clustered`) below a taller header.
         */
        const val MINIMUM_CONTENT_TOP = 60f
        const val RESTING_GAP = 8f

        /** Android always gets the app's band. */
        fun usesAppBlur(osVersion: String, forced: Boolean): Boolean =
            forced || (osVersion.split(".").firstOrNull()?.toIntOrNull() ?: 0) < 27
    }
}

/** The shipped value is [SystemEdge]. */
enum class HeaderBlurMode { SystemEdge, CustomBlur, Off }

/** The header's title and project text styles. */
enum class HeaderTextStyle(val textStyle: TextStyle) {
    Headline(OpenCodeTextStyles.headline),
    Body(OpenCodeTextStyles.body),
    Callout(OpenCodeTextStyles.callout),
    Subheadline(OpenCodeTextStyles.subheadline),
    Footnote(OpenCodeTextStyles.footnote),
    Caption(OpenCodeTextStyles.caption1),
    Caption2(OpenCodeTextStyles.caption2),
}

/** The session header's top band, wash and layout, at the shipped defaults. */
object HeaderBlurTunables {
    val MODE = HeaderBlurMode.SystemEdge
    const val RADIUS = 4.5f
    const val FADE_START = -20f
    const val FADE_END = 64f
    const val WASH_OPACITY = 0f
    const val POCKET_EXTENSION = 0f

    /** Title capsule inset. */
    val titleInset: Dp = 18.dp

    /** Header button size. */
    val buttonSize: Dp = 44.dp

    val titleFont = HeaderTextStyle.Subheadline
    val projectFont = HeaderTextStyle.Footnote

    /** Resolves the shipped header style with the baked tuning knobs. */
    fun resolved(environment: SessionHeaderStyle.Environment): SessionHeaderStyle.Resolved {
        val mode = MODE
        val radius = RADIUS
        val fadeStart = FADE_START
        val fadeEnd = FADE_END
        val washOpacity = WASH_OPACITY
        val pocketExtension = POCKET_EXTENSION

        val base = SessionHeaderStyle.shipped.copy(pocketExtension = if (mode == HeaderBlurMode.Off) null else pocketExtension)
        val resolved = base.resolved(environment)
        if (environment.reduceTransparency) return resolved

        val header = max(environment.headerHeight, 0f)
        val extra = max(header - SessionHeaderStyle.DEFAULT_HEADER_HEIGHT, 0f)
        val start = fadeStart + extra
        val end = max(start, fadeEnd + extra)

        val radiusAdjusted = radius != RADIUS
        val fadeAdjusted = fadeStart != FADE_START || fadeEnd != FADE_END
        val customKnobAdjusted = radiusAdjusted || (washOpacity == 0f && pocketExtension == 0f && fadeAdjusted)

        val custom: SessionHeaderStyle.Resolved.ResolvedBlur =
            if (radius > 0 && end >= 0) {
                SessionHeaderStyle.Resolved.ResolvedBlur.App(SessionHeaderStyle.Resolved.AppBlur(fadeStart = start, fadeEnd = end, radius = radius))
            } else {
                SessionHeaderStyle.Resolved.ResolvedBlur.None
            }
        val blur: SessionHeaderStyle.Resolved.ResolvedBlur = when (mode) {
            HeaderBlurMode.Off -> SessionHeaderStyle.Resolved.ResolvedBlur.None
            HeaderBlurMode.CustomBlur -> custom
            HeaderBlurMode.SystemEdge -> {
                // Untouched knobs keep the shipped style on every OS, the app blur's own stops included.
                if (customKnobAdjusted) custom else resolved.blur
            }
        }

        var backdrop = resolved.backdrop
        if (washOpacity > 0) {
            var wash = backdrop ?: SessionHeaderStyle.Appearance(opacity = washOpacity.toDouble(), fadeStart = start, fadeEnd = end)
            wash = wash.copy(opacity = max(wash.opacity, washOpacity.toDouble()))
            if (backdrop == null) wash = wash.copy(fadeStart = start, fadeEnd = end)
            if (wash.fadeEnd > 0) backdrop = wash
        }

        return SessionHeaderStyle.Resolved(blur = blur, backdrop = backdrop, contentTop = resolved.contentTop, header = resolved.header)
    }
}
