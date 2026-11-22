package ai.opencode.feature.composer

import ai.opencode.core.R as CoreR
import androidx.compose.foundation.lazy.rememberLazyListState
import ai.opencode.core.ui.rememberOverscrollIfContentScrolls
import ai.opencode.core.ui.OpenCodeTopBar
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticEffect
import ai.opencode.core.ui.OpenCodeFonts
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.server.model.PastedText
import ai.opencode.server.model.PastedTextSummary
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * A long paste ([PastedText]) as a card: its name and length over its first lines. In the composer
 * it sits with images and files; on a sent message, where its images go. A tap opens the whole text
 * ([onOpen] presents [PastedTextViewer]; the media area's presenter owns the zoom from the card).
 *
 * [summary] is computed once with the text; the card never reads the text itself.
 */
@Composable
fun PastedTextCard(
    summary: PastedTextSummary,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 240.dp,
    height: Dp? = null,
    fill: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape: Shape = RoundedCornerShape(OpenCodeRadius.fill),
    /** Room on the header's trailing end for a remove button over the corner. */
    headerTrailingInset: Dp = 0.dp,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .width(width)
            .heightIn(min = height ?: 0.dp)
            .background(fill, shape)
            .clickable(onClickLabel = stringResource(R.string.composer_pasted_text_open_action), role = Role.Button, onClick = onOpen)
            .semantics {
                contentDescription = PastedText.title
                stateDescription = summary.summary
                testTag = "pasted-text-card"
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = headerTrailingInset)) {
            OpenCodeIcon(
                SFSymbol.DocPlaintext,
                contentDescription = null,
                size = OpenCodeIconSize.small,
                tint = OpenCodeTheme.colors.secondaryText,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                PastedText.title,
                style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium),
                color = OpenCodeTheme.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // The spacer gives way before the title does.
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                summary.length,
                style = OpenCodeTextStyles.caption1.copy(fontFeatureSettings = "tnum"),
                color = OpenCodeTheme.colors.secondaryText,
                maxLines = 1,
            )
        }
        // The text goes on past the card.
        Column(
            verticalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Black,
                            0.45f to Color.Black,
                            1f to Color.Black.copy(alpha = 0.25f),
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            for (line in summary.preview) {
                Text(
                    line,
                    style = OpenCodeTextStyles.caption2.copy(fontFamily = OpenCodeFonts.monospace),
                    color = OpenCodeTheme.colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The whole paste, with Copy and Share: numbered lines (wrapped, or not), built off the main
 * thread; past the code viewer's 2 MB limit, or with a line longer than 16 KB, as plain selectable
 * text laid out a chunk at a time.
 *
 * Copy and Share go through the caller ([onCopy] → `PasteboardClient`, [onShare] → the share
 * sheet); the viewer only shows “Copied” for 1.5 s and plays `tap`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PastedTextViewer(
    text: String,
    summary: PastedTextSummary,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var document by remember(text) { mutableStateOf<PastedTextDocument?>(null) }
    var wrapsLines by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    var copyPresses by remember { mutableIntStateOf(0) }
    HapticEffect(Haptic.tap, copyPresses) { _, new -> new > 0 }
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(1_500)
        copied = false
    }
    LaunchedEffect(text) {
        if (document == null) document = withContext(Dispatchers.Default) { PastedTextDocument.make(text, summary.opensAsPlainText) }
    }
    Scaffold(
        modifier = modifier.semantics { testTag = "pasted-text-viewer" },
        containerColor = OpenCodeTheme.colors.canvas,
        topBar = {
            OpenCodeTopBar(
                centerTitle = true,
                title = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.semantics(mergeDescendants = true) { heading() },
                    ) {
                        Text(PastedText.title, style = OpenCodeTextStyles.headline)
                        Text(summary.summary, style = OpenCodeTextStyles.caption1, color = OpenCodeTheme.colors.secondaryText)
                    }
                },
                navigation = {
                    TextButton(onClick = onDone, modifier = Modifier.semantics { testTag = "pasted-text-done" }) { Text(stringResource(CoreR.string.common_done)) }
                },
                actions = {
                    if (!summary.opensAsPlainText) {
                        IconButton(onClick = { wrapsLines = !wrapsLines }) {
                            OpenCodeIcon(
                                if (wrapsLines) SFSymbol.ArrowLeftAndRightTextVertical else SFSymbol.TextWordSpacing,
                                contentDescription = if (wrapsLines) stringResource(R.string.composer_pasted_text_unwrap_description) else stringResource(R.string.composer_pasted_text_wrap_description),
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            copyPresses += 1
                            onCopy(text)
                            copied = true
                        },
                        modifier = Modifier.semantics { testTag = "pasted-text-copy" },
                    ) {
                        OpenCodeIcon(
                            if (copied) SFSymbol.Checkmark else SFSymbol.DocOnDoc,
                            contentDescription = if (copied) stringResource(CoreR.string.common_copied) else stringResource(CoreR.string.common_copy),
                        )
                    }
                    IconButton(onClick = { onShare(text) }) {
                        OpenCodeIcon(SFSymbol.SquareAndArrowUp, contentDescription = stringResource(CoreR.string.common_share))
                    }
                },
            )
        },
    ) { padding ->
        val current = document
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                current == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                summary.opensAsPlainText -> Box(Modifier.semantics { testTag = "pasted-text-plain" }) { PlainTextContent(current) }
                else -> NumberedTextContent(current, wrapsLines)
            }
        }
    }
}

/** Plain lines, never highlighted; very long lines are split into chunks so only what's on screen is laid out. */
private class PastedTextDocument(val lines: List<String>, val chunks: List<String>) {
    companion object {
        /** The plain view's unit of layout: lines joined up to about this many characters. */
        private const val CHUNK_CHARACTERS = 4_096

        fun make(text: String, plain: Boolean): PastedTextDocument {
            if (!plain) {
                val lines = text.split("\n").let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
                return PastedTextDocument(lines, emptyList())
            }
            val chunks = ArrayList<String>()
            var start = 0
            while (start < text.length) {
                var end = minOf(text.length, start + CHUNK_CHARACTERS)
                // Prefer to break after a newline; never inside a surrogate pair.
                val newline = text.lastIndexOf('\n', end - 1)
                if (end < text.length && newline >= start) end = newline + 1
                if (end < text.length && Character.isHighSurrogate(text[end - 1])) end -= 1
                chunks += text.substring(start, end)
                start = end
            }
            return PastedTextDocument(emptyList(), chunks)
        }
    }
}

@Composable
private fun NumberedTextContent(document: PastedTextDocument, wrapsLines: Boolean) {
    val codeStyle = OpenCodeTextStyles.footnote.copy(fontFamily = OpenCodeFonts.monospace)
    val numberWidth = (document.lines.size.toString().length * 8 + 8).dp
    val horizontal = rememberScrollState()
    val list = rememberLazyListState()
    SelectionContainer {
        LazyColumn(
            state = list,
            overscrollEffect = rememberOverscrollIfContentScrolls(list),
            contentPadding = PaddingValues(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .then(if (wrapsLines) Modifier else Modifier.horizontalScroll(horizontal)),
        ) {
            itemsIndexed(document.lines) { index, line ->
                Row(Modifier.then(if (wrapsLines) Modifier.fillMaxWidth() else Modifier)) {
                    Text(
                        (index + 1).toString(),
                        style = codeStyle,
                        color = OpenCodeTheme.colors.tertiaryText,
                        modifier = Modifier.width(numberWidth).semantics { contentDescription = "" },
                        softWrap = false,
                    )
                    Text(
                        line,
                        style = codeStyle,
                        color = OpenCodeTheme.colors.text,
                        softWrap = wrapsLines,
                        modifier = if (wrapsLines) Modifier.weight(1f) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlainTextContent(document: PastedTextDocument) {
    val codeStyle = OpenCodeTextStyles.footnote.copy(fontFamily = OpenCodeFonts.monospace)
    val list = rememberLazyListState()
    SelectionContainer {
        LazyColumn(
            state = list,
            overscrollEffect = rememberOverscrollIfContentScrolls(list),
            contentPadding = PaddingValues(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(document.chunks) { _, chunk ->
                Text(chunk.removeSuffix("\n"), style = codeStyle, color = OpenCodeTheme.colors.text)
            }
        }
    }
}
