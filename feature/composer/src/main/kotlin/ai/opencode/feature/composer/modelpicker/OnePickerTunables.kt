package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionTunables
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.BrandAssets
import ai.opencode.core.ui.TemplateAssetImage
import ai.opencode.core.ui.rememberHighContrast
import ai.opencode.server.model.ModelProviderGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One Picker's layout metrics and options at their shipped defaults; its timing is
 * [OnePickerTiming].
 */
object OnePickerTunables {
    /** "Compact height" (320…640). */
    const val COMPACT_HEIGHT = 460.0

    /** "Side inset" (0…24). */
    const val SIDE_INSET = 8.0

    /** "Bottom inset" (0…24). */
    const val BOTTOM_INSET = 8.0

    /** "Search keyboard gap" (0…20). */
    const val KEYBOARD_GAP = 6.0

    /** "Top corners" (16…48). */
    const val TOP_RADIUS = 16.0

    /** "Bottom corners" (16…56). */
    const val BOTTOM_RADIUS = 16.0

    /** "Row height" (44…62). */
    const val ROW_HEIGHT = 50.0

    /** "Recent rows" (0…6). */
    const val RECENTS = 3

    /** "Level dimness" (0.25…0.85). */
    const val LEVEL_ALPHA = 0.50

    /** "Scroll edge fade" (0…40). */
    const val EDGE_FADE = 24.0

    /** "Drag closes past" (32…220). */
    const val DRAG_CLOSES = 88.0

    /** "Throw closes at (pt/s)" (200…2400). */
    const val THROW_CLOSES = 680.0

    /** "Show providers below Recent". */
    const val SHOWS_ALL_MODELS = true

    /** "Provider logo on chip". */
    const val CHIP_SHOWS_LOGO = false

    /** "Arc glyph on chip". */
    const val CHIP_SHOWS_ARC = false

    /** "Arc glyph on rows". */
    const val ROW_SHOWS_ARC = false

    /** "Level word on chip". */
    const val CHIP_SHOWS_LEVEL_WORD = true

    /** "Chevron on chip". */
    const val CHIP_SHOWS_CHEVRON = true
}

/**
 * One Picker's motion, on the shared roles. It grows out of the chip on `Motion.panelOpen` and
 * folds back on `Motion.panelClose`, its bottom edge riding the keyboard on `Motion.keyboard`; a
 * rest changes on `Motion.layout`, and a drag let go settles on `Motion.release`.
 */
object OnePickerTiming {
    /** Narrow to wide, the width gets there first: `panelOpen` quickened by `widthLead`. */
    val widthDuration: Double
        get() = Motion.panelOpen.curve().duration / max(MotionTunables.widthLead.value, 1.0)
}

/**
 * The provider's real monochrome mark (`provider-<id>` in [BrandAssets]) when there is one, or a
 * filled rounded-square monogram with the bold initial knocked out, for unknown or custom local
 * providers.
 */
object OnePickerProviderMark {
    private val assetAliases: Map<String, String> = mapOf(
        "ollama" to "ollama-cloud",
        "bedrock" to "amazon-bedrock",
        "vertex" to "google-vertex",
        "copilot" to "github-copilot",
        "moonshot" to "moonshotai",
        "zhipu" to "zhipuai",
        "siliconflow" to "siliconflow-cn",
        "minimax" to "minimax-cn",
    )

    /**
     * A real asset name, or null when the provider is unknown, so callers draw the filled fallback
     * instead of the stroked `provider-synthetic` sparkles.
     */
    fun resolvedAssetName(providerID: String, canonical: String?, exists: (String) -> Boolean = BrandAssets::exists): String? {
        val stripped = if (providerID.startsWith("console-")) providerID.drop(8) else null
        val expanded = buildList {
            for (item in listOfNotNull(providerID, canonical, stripped)) {
                add(item)
                assetAliases[item.lowercase()]?.let(::add)
            }
        }
        return expanded.asSequence()
            .filter { it != "synthetic" }
            .map { "provider-$it" }
            .firstOrNull(exists)
    }

    /** The uppercase initial for the filled fallback mark. */
    fun fallbackInitial(providerID: String): String {
        val title = ModelProviderGroup.canonicalVendorTitle(providerID).trim()
        val first = title.firstOrNull { it.isLetterOrDigit() } ?: return "M"
        return first.toString().uppercase()
    }
}

/** Decorative, [size] square, in [tint] (or the content colour). */
@Composable
fun OnePickerProviderMark(
    providerID: String,
    modifier: Modifier = Modifier,
    canonical: String? = null,
    size: Dp = 20.dp,
    tint: Color = Color.Unspecified,
) {
    val asset = remember(providerID, canonical) { OnePickerProviderMark.resolvedAssetName(providerID, canonical) }
    if (asset != null) {
        TemplateAssetImage(asset, modifier.size(size), tint)
        return
    }
    val initial = remember(providerID) { OnePickerProviderMark.fallbackInitial(providerID) }
    val color = if (tint.isSpecified) tint else LocalContentColor.current
    val measurer = rememberTextMeasurer()
    Canvas(
        modifier
            .size(size)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .clearAndSetSemantics {},
    ) {
        val inset = this.size.width * 0.06f
        val side = this.size.width - inset * 2
        drawRoundRect(color, Offset(inset, inset), Size(side, side), CornerRadius(side * 0.28f))
        val text = measurer.measure(
            initial,
            TextStyle(fontSize = (size.value * 0.56f).roundToInt().sp, fontWeight = FontWeight.Bold),
        )
        drawText(
            text,
            color = Color.Black,
            topLeft = Offset((this.size.width - text.size.width) / 2, (this.size.height - text.size.height) / 2),
            blendMode = BlendMode.Clear,
        )
    }
}

/**
 * The level arc sweeping on `Motion.microIn` as the level changes. Off by default
 * (`CHIP_SHOWS_ARC`, `ROW_SHOWS_ARC`).
 */
@Composable
fun AnimatedLevelArc(
    scale: ModelLevelScale,
    level: String?,
    modifier: Modifier = Modifier,
    size: Dp = 15.dp,
    color: Color = LocalContentColor.current,
) {
    val reduceMotion = rememberReduceMotion()
    val pose = LevelArc.pose(scale.index(level), max(scale.count, 1))
    val fill by animateFloatAsState(pose.fill.toFloat(), Motion.microIn.animation(reduceMotion = reduceMotion), label = "arcFill")
    val lit by animateFloatAsState(min(pose.lit, 1.0).toFloat(), Motion.microIn.animation(reduceMotion = reduceMotion), label = "arcLit")
    val increased = rememberHighContrast()
    val track = if (increased) min(LevelArc.TRACK_OPACITY * 1.7, 0.7) else LevelArc.TRACK_OPACITY
    val span = LevelScrubberTunables.arcSpan
    Canvas(modifier.size(size).clearAndSetSemantics {}) {
        val strokePx = LevelArc.stroke(size.value.toDouble(), needle = false).toFloat() * density
        LevelArc.draw(this, fill = 1.0, span = span, color = color.copy(alpha = color.alpha * track.toFloat()), stroke = strokePx)
        LevelArc.draw(this, fill = fill.toDouble(), span = span, color = color.copy(alpha = color.alpha * lit), stroke = strokePx)
    }
}
