package ai.opencode.feature.transcript

import ai.opencode.core.motion.MotionTunables
import ai.opencode.core.tuning.Tunable
import ai.opencode.core.tuning.TunableChoice
import ai.opencode.core.tuning.TuningPreset
import ai.opencode.core.tuning.TuningPresets
import ai.opencode.core.tuning.TuningStore
import ai.opencode.feature.media.AgentImageTunables
import ai.opencode.feature.transcript.markdown.ui.SessionLinkTunables
import ai.opencode.feature.transcript.markdown.ui.TextRevealTunables
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The transcript's layout and motion tunables (iOS `TranscriptTunables`, Developer Mode →
 * Tuning Panel → Transcript). Same keys, labels, defaults, ranges and steps as iOS; the
 * declarations live in [Knobs] and each value reads live through its getter, so call sites read
 * plain numbers. Motion parameters are captured when each motion begins, so dragging a slider
 * mid-animation never causes a jump. Points port 1:1 to dp/sp.
 */
object TranscriptTunables {
    /** The Tuning Panel sections it registers (the key segment after the group), in order (iOS `sectionTitles`). */
    val sectionTitles = listOf(
        "Arrivals", "Text Reveal", "Promotion", "Reveal", "Divider", "Typography", "Row", "Bubble", "Card",
    )

    /** The declarations (iOS `TranscriptTunables.<name>` tunables). */
    object Knobs {
        // Arrivals
        val textFadeDuration = Tunable("transcript.textReveal.duration", "Reveal duration (s)", default = 0.2, range = 0.05..0.8, step = 0.01)

        // Promotion
        val promotionLabelRise = Tunable("transcript.promotion.labelRise", "Label exit rise (pt)", default = 6f, range = 0.0..20.0, step = 1.0)

        // Reveal
        val agentGlowRise = Tunable("transcript.reveal.agentGlowRise", "Agent glow rise (s)", default = 0.07, range = 0.02..0.25, step = 0.01)
        val agentGlowDuration = Tunable("transcript.reveal.agentGlowDuration", "Agent glow decay (s)", default = 1.39, range = 0.4..3.0, step = 0.05)

        // Divider (iOS declares these beside `NewMessagesDivider`)
        val dividerGlowLift = Tunable("transcript.divider.glowLift", "New divider glow lift", default = 0.45, range = 0.0..1.0, step = 0.05)
        val dividerGlowDuration = Tunable("transcript.divider.glowDuration", "New divider glow dim (s)", default = 1.0, range = 0.2..3.0, step = 0.05)

        // Row, Bubble & Card Layout
        val flowHalf = Tunable("transcript.row.flowHalf", "Reply row half-gap", default = 8f, range = 2.0..20.0, step = 1.0)
        val turnHalf = Tunable("transcript.row.turnHalf", "Turn half-gap", default = 12f, range = 4.0..28.0, step = 1.0)
        val sectionHalf = Tunable("transcript.row.sectionHalf", "Section rule half-gap", default = 16f, range = 6.0..36.0, step = 1.0)
        val contentGutter = Tunable("transcript.row.contentGutter", "Horizontal gutter", default = 16f, range = 8.0..32.0, step = 1.0)
        val bubbleVertical = Tunable("transcript.bubble.verticalPadding", "Bubble vertical padding", default = 8f, range = 6.0..20.0, step = 1.0)
        val bubbleCornerRadius = Tunable("transcript.bubble.cornerRadius", "Bubble corner radius", default = 10f, range = 8.0..28.0, step = 1.0)
        val bubbleMaxFraction = Tunable("transcript.bubble.maxFraction", "Bubble max width", default = 0.82f, range = 0.55..0.95, step = 0.01)
        val cardCornerRadius = Tunable("transcript.card.cornerRadius", "Card corner radius", default = 8f, range = 6.0..24.0, step = 1.0)

        // Typography
        val bodySize = Tunable("transcript.typography.bodySize", "Body size (pt)", default = 15f, range = 14.0..20.0, step = 0.5)
        val bodyLeading = Tunable("transcript.typography.bodyLeading", "Body leading (pt)", default = 4.5f, range = 0.0..8.0, step = 0.5)
        val bodyTone = Tunable("transcript.typography.bodyTone", "Body tone", default = 0.92, range = 0.75..1.0, step = 0.01)
        val h1Size = Tunable("transcript.typography.h1Size", "H1 size (pt)", default = 19f, range = 18.0..28.0, step = 1.0)
        val h2Size = Tunable("transcript.typography.h2Size", "H2 size (pt)", default = 17f, range = 16.0..24.0, step = 1.0)
        val h3Size = Tunable("transcript.typography.h3Size", "H3 size (pt)", default = 15f, range = 12.0..20.0, step = 0.5)
        val h3Weight = Tunable("transcript.typography.h3Weight", "H3 weight", default = TranscriptFontWeight.Bold)
        val metaSize = Tunable("transcript.typography.metaSize", "Used N & Working size (pt)", default = 14f, range = 11.0..17.0, step = 0.5)
        val listNumSize = Tunable("transcript.typography.listNumSize", "List number size (pt)", default = 13f, range = 11.0..17.0, step = 0.5)
        val codeLeading = Tunable("transcript.typography.codeLeading", "Code leading (pt)", default = 3f, range = 0.0..6.0, step = 0.5)
        /** `0` inherits `flowHalf * 2`; any positive value is used directly. */
        val paragraphGap = Tunable("transcript.typography.paragraphGap", "Paragraph gap (pt, 0 = 2 × flowHalf)", default = 0f, range = 0.0..24.0, step = 1.0)
    }

    // Arrivals
    val textFadeDuration: Double get() = Knobs.textFadeDuration.value

    // Promotion
    val promotionLabelRise: Float get() = Knobs.promotionLabelRise.value

    // Reveal
    val agentGlowRise: Double get() = Knobs.agentGlowRise.value
    val agentGlowDuration: Double get() = Knobs.agentGlowDuration.value

    // Divider
    /** The first time a mark's divider is on screen it starts this much brighter and dims. */
    val dividerGlowLift: Double get() = Knobs.dividerGlowLift.value
    val dividerGlowDuration: Double get() = Knobs.dividerGlowDuration.value

    // Row, bubble & card layout
    val flowHalf: Float get() = Knobs.flowHalf.value
    val turnHalf: Float get() = Knobs.turnHalf.value
    val sectionHalf: Float get() = Knobs.sectionHalf.value
    val contentGutter: Float get() = Knobs.contentGutter.value
    val bubbleVertical: Float get() = Knobs.bubbleVertical.value
    val bubbleCornerRadius: Float get() = Knobs.bubbleCornerRadius.value
    val bubbleMaxFraction: Float get() = Knobs.bubbleMaxFraction.value
    val cardCornerRadius: Float get() = Knobs.cardCornerRadius.value

    // Typography
    val bodySize: Float get() = Knobs.bodySize.value
    val bodyLeading: Float get() = Knobs.bodyLeading.value
    val bodyTone: Double get() = Knobs.bodyTone.value
    val h1Size: Float get() = Knobs.h1Size.value
    val h2Size: Float get() = Knobs.h2Size.value
    val h3Size: Float get() = Knobs.h3Size.value
    val h3Weight: FontWeight get() = Knobs.h3Weight.value.weight
    val metaSize: Float get() = Knobs.metaSize.value
    val listNumSize: Float get() = Knobs.listNumSize.value
    val codeLeading: Float get() = Knobs.codeLeading.value

    /** `0` inherits `flowHalf * 2` (`MarkdownStyle.inheritedParagraphGap`). */
    val paragraphGap: Float get() = Knobs.paragraphGap.value

    fun resolvedParagraphGap(paragraphGap: Float, flowHalf: Float): Float =
        if (paragraphGap > 0) paragraphGap else flowHalf * 2

    /**
     * Session view typography presets: 1 is what shipped (Refined Standard with Used N
     * and Working kept at 17 pt body size), 2 is the previous baseline.
     */
    val typography: TuningPresets by lazy {
        with(Knobs) {
            TuningPresets(
                "transcript.typography", "Typography",
                listOf(
                    TuningPreset("Today"),
                    TuningPreset(
                        "Before D-52",
                        bodyLeading.preset(4f), bodyTone.preset(1.0),
                        h3Size.preset(17f), h3Weight.preset(TranscriptFontWeight.Semibold),
                        codeLeading.preset(0f),
                    ),
                    TuningPreset(
                        "Crisp Technical",
                        bodySize.preset(16f), bodyLeading.preset(3.5f), bodyTone.preset(0.88),
                        h1Size.preset(20f), h2Size.preset(17f), h3Size.preset(13f), h3Weight.preset(TranscriptFontWeight.Semibold),
                        metaSize.preset(12f), codeLeading.preset(4f), paragraphGap.preset(14f),
                    ),
                    TuningPreset(
                        "Editorial",
                        bodyLeading.preset(5f), bodyTone.preset(0.9),
                        h1Size.preset(23f), h3Size.preset(13f), h3Weight.preset(TranscriptFontWeight.Semibold),
                        metaSize.preset(13f), paragraphGap.preset(18f),
                    ),
                ),
            )
        }
    }

    /** Card-sized choices: each variant resets only the family it names. */
    val cardPresets: List<TuningPresets> by lazy {
        with(Knobs) {
            listOf(
                TuningPresets(
                    "transcript.arrivals.presets", "Arrivals",
                    listOf(
                        TuningPreset("Native"),
                        TuningPreset("Snappy", textFadeDuration.preset(0.14)),
                        TuningPreset("Gentle", textFadeDuration.preset(0.26)),
                    ),
                    resetting = listOf(textFadeDuration).map { it.descriptor },
                ),
                TuningPresets(
                    "transcript.promotion.presets", "Steering",
                    listOf(
                        TuningPreset("Native"),
                        TuningPreset("Snappy", promotionLabelRise.preset(4f)),
                        TuningPreset("Gentle", promotionLabelRise.preset(5f)),
                    ),
                    resetting = listOf(promotionLabelRise.descriptor),
                ),
                TuningPresets(
                    "transcript.reveal.presets", "Return & Glow",
                    listOf(
                        TuningPreset("Native"),
                        TuningPreset("Snappy", agentGlowRise.preset(0.05), agentGlowDuration.preset(1.05)),
                        TuningPreset("Gentle", agentGlowRise.preset(0.1), agentGlowDuration.preset(1.7)),
                    ),
                    resetting = listOf(agentGlowRise.descriptor, agentGlowDuration.descriptor),
                ),
                TuningPresets(
                    "transcript.typography.presets", "Typography",
                    listOf(TuningPreset("Native")) + typography.presets.drop(1),
                    resetting = listOf(
                        bodySize.descriptor, bodyLeading.descriptor, bodyTone.descriptor,
                        h1Size.descriptor, h2Size.descriptor, h3Size.descriptor, h3Weight.descriptor,
                        metaSize.descriptor, listNumSize.descriptor, codeLeading.descriptor, paragraphGap.descriptor,
                    ),
                ),
            )
        }
    }

    /**
     * iOS `TranscriptTunables.register(in:)`: lists the transcript's knobs (and the text reveal's
     * and motion's) in the Tuning Panel before their first read. A no-op while tuning is off.
     */
    fun register(store: TuningStore = TuningStore.shared) {
        if (!store.isEnabled) return
        with(Knobs) {
            store.register(textFadeDuration)
            store.register(promotionLabelRise)
            store.register(agentGlowRise, agentGlowDuration)
            store.register(dividerGlowLift, dividerGlowDuration)
            store.register(typography)
            for (presets in cardPresets) store.register(presets)
            TextRevealTunables.register(store)
            // iOS registers these through `InlineLinkTunables.register`.
            SessionLinkTunables.register(store)
            MotionTunables.register(store)
            store.register(flowHalf, turnHalf, sectionHalf, contentGutter, bubbleVertical, bubbleCornerRadius, bubbleMaxFraction, cardCornerRadius)
            store.register(bodySize, bodyLeading, h1Size, h2Size, h3Size, metaSize, listNumSize, codeLeading, paragraphGap)
            store.register(bodyTone)
            store.register(h3Weight)
        }
    }

    /** iOS `layoutSnapshot(in:)`: the live layout values. */
    fun layoutSnapshot(): TranscriptLayoutTuning {
        val flow = flowHalf
        return TranscriptLayoutTuning(
            flowHalf = flow,
            turnHalf = turnHalf,
            sectionHalf = sectionHalf,
            contentGutter = contentGutter,
            bubbleVertical = bubbleVertical,
            bubbleCornerRadius = bubbleCornerRadius,
            bubbleMaxFraction = bubbleMaxFraction,
            cardCornerRadius = cardCornerRadius,
            bodySize = bodySize,
            bodyLeading = bodyLeading,
            bodyTone = bodyTone,
            h1Size = h1Size,
            h2Size = h2Size,
            h3Size = h3Size,
            h3Weight = h3Weight,
            metaSize = metaSize,
            listNumSize = listNumSize,
            codeLeading = codeLeading,
            paragraphGap = resolvedParagraphGap(paragraphGap, flow),
            linkMaxHeight = AgentImageTunables.linkMaxHeight.value,
        )
    }
}

/** iOS `DrawerFontWeight` as the H3 weight tunable holds it (same raw values). */
enum class TranscriptFontWeight(override val rawValue: String, val weight: FontWeight) : TunableChoice {
    Regular("regular", FontWeight.Normal),
    Medium("medium", FontWeight.Medium),
    Semibold("semibold", FontWeight.SemiBold),
    Bold("bold", FontWeight.Bold),
}

/**
 * Snapshot of layout-affecting transcript tunables (iOS `TranscriptLayoutTuning`). Motion-only
 * tunables are excluded so adjusting one never re-lays rows out.
 */
@Immutable
data class TranscriptLayoutTuning(
    val flowHalf: Float = TranscriptTunables.flowHalf,
    val turnHalf: Float = TranscriptTunables.turnHalf,
    val sectionHalf: Float = TranscriptTunables.sectionHalf,
    val contentGutter: Float = TranscriptTunables.contentGutter,
    val bubbleVertical: Float = TranscriptTunables.bubbleVertical,
    val bubbleCornerRadius: Float = TranscriptTunables.bubbleCornerRadius,
    val bubbleMaxFraction: Float = TranscriptTunables.bubbleMaxFraction,
    val cardCornerRadius: Float = TranscriptTunables.cardCornerRadius,
    val bodySize: Float = TranscriptTunables.bodySize,
    val bodyLeading: Float = TranscriptTunables.bodyLeading,
    val bodyTone: Double = TranscriptTunables.bodyTone,
    val h1Size: Float = TranscriptTunables.h1Size,
    val h2Size: Float = TranscriptTunables.h2Size,
    val h3Size: Float = TranscriptTunables.h3Size,
    val h3Weight: FontWeight = TranscriptTunables.h3Weight,
    val metaSize: Float = TranscriptTunables.metaSize,
    val listNumSize: Float = TranscriptTunables.listNumSize,
    val codeLeading: Float = TranscriptTunables.codeLeading,
    val paragraphGap: Float = TranscriptTunables.resolvedParagraphGap(TranscriptTunables.paragraphGap, TranscriptTunables.flowHalf),
    /** `AgentImageTunables.linkMaxHeight` default. */
    val linkMaxHeight: Float = 220f,
)

/** iOS `EnvironmentValues.transcriptLayoutTuning`. */
val LocalTranscriptLayoutTuning = staticCompositionLocalOf { TranscriptLayoutTuning() }

// iOS `TranscriptMetrics` is `:feature:media`'s [ai.opencode.feature.media.TranscriptMetrics] (media renderers use it).

/** iOS `InlineRowMetrics`: Used N rows inside the band sit on a 28 pt pitch. */
object InlineRowMetrics {
    /** Above and below each row's line inside the band. */
    val rowPadding: Dp = 4.dp
    /** iOS `TranscriptBandStyle.verticalPadding` (12) minus [rowPadding]. */
    val bandPadding: Dp = 8.dp
}

/** A row's outer halves (iOS `rowInsets`), in dp. */
data class RowInsets(val top: Dp, val bottom: Dp)
