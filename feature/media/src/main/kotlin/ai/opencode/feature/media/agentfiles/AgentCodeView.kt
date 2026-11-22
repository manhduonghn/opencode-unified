package ai.opencode.feature.media

import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import ai.opencode.core.ui.OpenCodeFonts
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/** The `path:42` line. */
private val FocusLineBackground = Color(red = 1f, green = 0.8f, blue = 0f, alpha = 0.22f)

/** The widest a line lays out unwrapped. */
private val MaxUnwrappedWidth: Dp = 16_384.dp

/** Subheadline, monospaced. */
private val NumberedCodeStyle: TextStyle = OpenCodeTextStyles.subheadline.copy(fontFamily = OpenCodeFonts.monospace)

/** Footnote 13, monospaced. */
private val LazyCodeStyle: TextStyle = OpenCodeTextStyles.footnote.copy(fontFamily = OpenCodeFonts.monospace)

/**
 * Highlighted source with line numbers for small files, or a lazy unnumbered list of lines for
 * large ones. Lines scroll sideways together, or wrap; a `path:42` reference opens centred on line
 * 42. Only the lines on screen lay out.
 */
@Composable
fun AgentCodeView(document: AgentTextDocument, wrapsLines: Boolean, modifier: Modifier = Modifier) {
    val numbered = !document.usesLazyTextView
    val style = if (numbered) NumberedCodeStyle else LazyCodeStyle
    val palette = SyntaxHighlighter.Palette.current
    val colors = OpenCodeTheme.colors
    val textStyle = style.copy(color = colors.text)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val digits = max(document.displayLineCount.toString().length, 2)
    val charWidth: Dp = remember(measurer, style, density) {
        with(density) { measurer.measure("0".repeat(10), style).size.width.toDp() / 10 }
    }
    val lineHeight: Float = remember(measurer, style, density) { measurer.measure("0", style).size.height.toFloat() }
    val gutterWidth = if (numbered) charWidth * digits + 4.dp else 0.dp
    val longestLine = remember(document) { (0 until document.displayLineCount).maxOfOrNull { document.lineRange(it).count() } ?: 0 }
    val listState = rememberLazyListState()

    BoxWithConstraints(modifier.fillMaxSize().testTag("agent-file-code")) {
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        // A `path:42` reference opens centred on line 42.
        LaunchedEffect(document) {
            val target = when {
                numbered -> document.focusLine?.minus(1)
                else -> document.focusRange?.takeIf { it.first <= AgentTextDocument.maxHighlightedCharacters }?.let {
                    (0 until document.displayLineCount).firstOrNull(document::isFocused)
                }
            } ?: return@LaunchedEffect
            val rowsAbove = if (lineHeight > 0) (viewportHeightPx / lineHeight / 2).toInt() else 0
            listState.scrollToItem(max(0, target - rowsAbove))
        }

        val horizontalPadding = TranscriptMetrics.standard * 2
        val gutterSpacing = if (numbered) TranscriptMetrics.standard else 0.dp
        // One character of slack: the estimate rounds, and an unwrapped line must never wrap.
        val unwrappedWidth = (gutterWidth + gutterSpacing + charWidth * (longestLine + 1) + horizontalPadding).coerceIn(maxWidth, MaxUnwrappedWidth)
        val contentWidth = if (wrapsLines) maxWidth else unwrappedWidth
        val scroll = rememberScrollState()
        SelectionContainer(
            if (wrapsLines) Modifier.fillMaxSize() else Modifier.fillMaxSize().horizontalScroll(scroll),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.width(contentWidth).fillMaxHeight(),
                // The last lines clear the navigation bar.
                contentPadding = PaddingValues(
                    top = TranscriptMetrics.standard,
                    bottom = TranscriptMetrics.standard + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
            ) {
                items(count = document.displayLineCount, key = { it }, contentType = { 0 }) { index ->
                    val focused = document.isFocused(index)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (focused) Modifier.background(FocusLineBackground) else Modifier)
                            .padding(horizontal = TranscriptMetrics.standard, vertical = if (numbered) 1.dp else 0.dp),
                    ) {
                        if (numbered) {
                            Text(
                                text = (index + 1).toString(),
                                style = style.copy(color = colors.tertiaryText, textAlign = TextAlign.End),
                                modifier = Modifier.width(gutterWidth).alignByBaseline().clearAndSetSemantics {},
                                maxLines = 1,
                                softWrap = false,
                            )
                            Spacer(Modifier.width(gutterSpacing))
                        }
                        Text(
                            text = document.line(index, palette),
                            style = textStyle,
                            softWrap = wrapsLines,
                            modifier = Modifier.weight(1f).alignByBaseline(),
                        )
                    }
                }
            }
        }
    }
}
