package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.core.ui.OpenCodeColors
import ai.opencode.core.ui.OpenCodeFonts
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.LocalTranscriptLayoutTuning
import ai.opencode.feature.transcript.TranscriptLayoutTuning
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Spacing and type for long-form Markdown. Sizes are sp, so they follow the system font scale.
 *
 * Every transcript text uses greedy line breaking ([LineBreak.Simple], no hyphens): appending text
 * never moves a word already laid out, so streamed text keeps its lines.
 */
@Immutable
class MarkdownStyle(
    val tuning: TranscriptLayoutTuning,
    val colors: OpenCodeColors,
) {
    private fun textStyle(size: Float, lineHeight: Float, weight: FontWeight = FontWeight.Normal) = TextStyle(
        fontFamily = OpenCodeFonts.system,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.Both),
        lineBreak = LineBreak.Simple,
        hyphens = Hyphens.None,
    )

    /** `.body` at `bodySize` (17), with `bodyLeading` (4.5) between lines. */
    val bodyStyle: TextStyle
        get() = textStyle(tuning.bodySize, bodyLineHeight(tuning.bodySize) + tuning.bodyLeading)

    /** The body's text size, for inline math (`bodyMathFontSize`). */
    val bodySize: TextUnit get() = tuning.bodySize.sp

    /** Prose at 92% primary so bold pops at 100%, full primary under Increase Contrast. */
    val bodyColor: Color
        get() = if (colors.highContrast) colors.strongText else colors.bodyText(tuning.bodyTone)

    /** `listNumSize` (13, `.footnote`) with tabular digits. */
    val listNumberStyle: TextStyle
        get() = textStyle(tuning.listNumSize, footnoteLineHeight(tuning.listNumSize)).copy(fontFeatureSettings = "tnum")

    fun headingSize(level: Int): Float = when (level) {
        1 -> tuning.h1Size
        2 -> tuning.h2Size
        3 -> tuning.h3Size
        // Deeper headings stay at H3's desktop size.
        else -> 15f
    }

    fun headingWeight(level: Int): FontWeight = when (level) {
        1 -> FontWeight.SemiBold
        2 -> FontWeight.SemiBold
        3 -> tuning.h3Weight
        else -> FontWeight.SemiBold
    }

    /** H1 `.title2` bold, H2 `.title3` semibold, H3 `h3Size` in `h3Weight`, deeper `.headline`. Line spacing 0. */
    fun headingStyle(level: Int): TextStyle {
        val size = headingSize(level)
        val lineHeight = when (level) {
            1 -> if (size == 22f) 28f else size * 1.27f
            2 -> if (size == 20f) 25f else size * 1.25f
            else -> if (size == 17f) 22f else size * 1.28f
        }
        return textStyle(size, lineHeight, headingWeight(level))
    }

    /** Whether bold spans lift to full primary (`tonesBody`, `bodyTone < 1`, not Increase Contrast). */
    val liftsStrong: Boolean get() = !colors.highContrast && tuning.bodyTone < 0.999

    /** `paragraphGap` (16 by default: `flowHalf * 2`). */
    val paragraphGap: Dp get() = tuning.paragraphGap.dp

    /** Space between two blocks. */
    fun gap(previous: OpenCodeMarkdownBlock.Kind?, block: OpenCodeMarkdownBlock.Kind): Dp {
        if (previous == null) return 0.dp
        if (previous is OpenCodeMarkdownBlock.Kind.Heading) return headingBelow
        if (block is OpenCodeMarkdownBlock.Kind.Heading) return headingAbove
        if (isListItem(previous) && isListItem(block)) return listGap
        if (standsApart(previous) || standsApart(block)) return blockGap
        return paragraphGap
    }

    companion object {
        val listGap: Dp = TranscriptMetrics.clustered
        val headingAbove: Dp = 24.dp
        val headingBelow: Dp = TranscriptMetrics.clustered
        /** Code, tables, images, cards, and dividers stand apart from prose. */
        val blockGap: Dp = TranscriptMetrics.turn
        /** Each nesting level of a list steps in this far. */
        val listIndent: Dp = 20.dp

        /** The default body size, for surfaces without a tuning snapshot (display math). */
        val bodyTextSize: TextUnit = 15.sp

        fun codeTextStyle(codeLeading: Float = 3f): TextStyle = OpenCodeTextStyles.footnote.copy(
            fontFamily = OpenCodeFonts.monospace,
            lineHeight = (18f + codeLeading).sp,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.Both),
            lineBreak = LineBreak.Simple,
            hyphens = Hyphens.None,
        )

        /** SF's `.body` leading at 17 (22), proportionally for a tuned size. */
        private fun bodyLineHeight(size: Float): Float = if (size == 17f) 22f else size * 22f / 17f

        private fun footnoteLineHeight(size: Float): Float = when (size) {
            13f -> 18f
            15f -> 20f
            16f -> 21f
            17f -> 22f
            else -> size * 18f / 13f
        }

        private fun isListItem(kind: OpenCodeMarkdownBlock.Kind): Boolean = when (kind) {
            is OpenCodeMarkdownBlock.Kind.NumberedItem, is OpenCodeMarkdownBlock.Kind.ListItem -> true
            // Bullets cached before list items had their own block.
            is OpenCodeMarkdownBlock.Kind.Paragraph -> kind.text.text.firstOrNull() == '•'
            else -> false
        }

        private fun standsApart(kind: OpenCodeMarkdownBlock.Kind): Boolean = when (kind) {
            is OpenCodeMarkdownBlock.Kind.Code, is OpenCodeMarkdownBlock.Kind.Table, is OpenCodeMarkdownBlock.Kind.Image,
            is OpenCodeMarkdownBlock.Kind.PullRequest, is OpenCodeMarkdownBlock.Kind.Files, OpenCodeMarkdownBlock.Kind.Divider,
            is OpenCodeMarkdownBlock.Kind.Math, is OpenCodeMarkdownBlock.Kind.Mermaid -> true
            else -> false
        }
    }
}

/** The style for the current theme and tuning. */
@Composable
@ReadOnlyComposable
fun currentMarkdownStyle(): MarkdownStyle = MarkdownStyle(LocalTranscriptLayoutTuning.current, OpenCodeTheme.colors)
