package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.feature.transcript.R
import ai.opencode.feature.transcript.markdown.MarkdownQuote
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuDropdownProvider
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.dp

/** TalkBack's whole-block Quote action for the enclosing reply block. */
internal val LocalQuoteAccessibilityAction = compositionLocalOf<CustomAccessibilityAction?> { null }

/**
 * A block whose text can be quoted whole: TalkBack's Quote action. A selection's Quote is the reply
 * selection's menu.
 */
class QuoteSelectionBlockSource private constructor(private val kind: OpenCodeMarkdownBlock.Kind) {
    /** The whole block, for TalkBack's Quote action. */
    fun fullBlockQuoteText(): String? = when (kind) {
        is OpenCodeMarkdownBlock.Kind.Paragraph -> kind.text.text.takeIf { MarkdownQuote.blockquote(it) != null }
        is OpenCodeMarkdownBlock.Kind.Blockquote -> kind.text.text.takeIf { MarkdownQuote.blockquote(it) != null }
        is OpenCodeMarkdownBlock.Kind.Heading -> kind.text.text.takeIf { MarkdownQuote.blockquote(it) != null }
        is OpenCodeMarkdownBlock.Kind.NumberedItem -> kind.text.text.takeIf { MarkdownQuote.blockquote(it) != null }?.let { "${kind.marker} $it" }
        is OpenCodeMarkdownBlock.Kind.ListItem -> kind.text.text.takeIf { MarkdownQuote.blockquote(it) != null }?.let {
            val prefix = when (kind.marker) {
                OpenCodeMarkdownBlock.doneTask -> "- [x]"
                OpenCodeMarkdownBlock.openTask -> "- [ ]"
                else -> "-"
            }
            "$prefix $it"
        }
        is OpenCodeMarkdownBlock.Kind.Code ->
            if (MarkdownQuote.blockquote(kind.code.source) != null) MarkdownQuote.fencedCode(kind.code.source, kind.code.language) else null
        else -> null
    }

    companion object {
        fun of(kind: OpenCodeMarkdownBlock.Kind): QuoteSelectionBlockSource? = when (kind) {
            is OpenCodeMarkdownBlock.Kind.Paragraph -> prose(kind, kind.text.text)
            is OpenCodeMarkdownBlock.Kind.Blockquote -> prose(kind, kind.text.text)
            is OpenCodeMarkdownBlock.Kind.Heading -> prose(kind, kind.text.text)
            is OpenCodeMarkdownBlock.Kind.NumberedItem -> prose(kind, kind.text.text)
            is OpenCodeMarkdownBlock.Kind.ListItem -> prose(kind, kind.text.text)
            is OpenCodeMarkdownBlock.Kind.Code ->
                if (kind.code.source.isBlank()) null else QuoteSelectionBlockSource(kind)
            else -> null
        }

        private fun prose(kind: OpenCodeMarkdownBlock.Kind, text: String) =
            if (text.isBlank()) null else QuoteSelectionBlockSource(kind)
    }
}

/**
 * A block's selectable text. In a reply ([inReply]) the list's reply selection selects across all
 * of the reply's text, Copy and Quote included, so the text here only anchors to it (through
 * `ArrivingText`'s `selectionKey`) and TalkBack gets a Quote action for the whole block, sent as
 * `TranscriptAction.QuoteTextTapped` by the row's [LocalQuoteTextHandler]. Elsewhere (a compaction
 * summary, a file in the viewer) the block is selectable on its own.
 */
@Composable
fun QuoteSelectionContainer(
    kind: OpenCodeMarkdownBlock.Kind,
    inReply: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!inReply) {
        SelectionContainer(modifier) { content() }
        return
    }
    val handler = LocalQuoteTextHandler.current
    val source = remember(kind, handler != null) { if (handler != null) QuoteSelectionBlockSource.of(kind) else null }
    if (handler == null || source == null) {
        Box(modifier) { content() }
        return
    }
    val currentHandler by rememberUpdatedState(handler)
    val currentSource by rememberUpdatedState(source)
    // Attached directly to the block's Text semantics (via LocalQuoteAccessibilityAction) rather
    // than an unmerged wrapper Box.
    val quoteLabel = stringResource(R.string.transcript_quote)
    val quoteAction = remember {
        CustomAccessibilityAction(quoteLabel) {
            currentSource.fullBlockQuoteText()?.let { currentHandler.quote(it) }
            true
        }
    }
    CompositionLocalProvider(LocalQuoteAccessibilityAction provides quoteAction) {
        Box(modifier) { content() }
    }
}

/**
 * One selection toolbar and one dropdown for every [SelectionContainer] in [content].
 *
 * A SelectionContainer with no menu providers above it sets up its own: a toolbar that watches
 * snapshot state, a dropdown host, and a position callback that runs on every frame it moves.
 * The transcript has one container per Markdown block, so a fling paid for that on each block
 * that scrolled in and on every frame each one moved. Here one empty container sets them up
 * once and [content] reuses them. The toolbar places itself from the selection's own bounds,
 * so where the empty container sits doesn't matter.
 *
 * Emits the empty container beside [content], so call it inside a Box. Only for content that
 * shows its text in this window: the toolbar belongs to this window, not to a dialog's.
 */
@Composable
fun SharedTextContextMenus(content: @Composable () -> Unit) {
    val shared = remember { arrayOfNulls<TextContextMenuProvider>(2) }
    // Nothing in it is selectable, and it never takes focus.
    SelectionContainer(Modifier.size(0.dp).focusProperties { canFocus = false }) {
        // Read in the same pass, before content composes, so no block ever sets up its own.
        shared[0] = LocalTextContextMenuToolbarProvider.current
        shared[1] = LocalTextContextMenuDropdownProvider.current
    }
    CompositionLocalProvider(
        LocalTextContextMenuToolbarProvider provides shared[0],
        LocalTextContextMenuDropdownProvider provides shared[1],
    ) {
        content()
    }
}
