package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.feature.media.LocalAgentImageOccurrence
import ai.opencode.feature.media.LocalAgentImageSiblings
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.server.model.AgentFileReference
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * iOS `MarkdownBlocksView`: a run of blocks with the transcript's spacing between them
 * (`MarkdownStyle.gap(after:before:)`).
 *
 * @param rowID the row's identity: keys the reveal records when [revealItem] isn't given.
 * @param isGrowing whether the reply can still grow (its session is running). Once it can't,
 *   text that streamed in settles.
 * @param revealItem a streamed reply's item: text that arrives fades in. Null shows the
 *   blocks as history (compaction summaries, the file viewer).
 * @param imageScope the reply these blocks belong to, when they're only part of it.
 * @param selectionItemID the reply row these blocks are (iOS `selectionItemID`): their text
 *   joins the list's one selection across the reply. Null keeps each block selectable alone.
 */
@Composable
fun MarkdownBlocksView(
    blocks: List<OpenCodeMarkdownBlock>,
    modifier: Modifier = Modifier,
    rowID: String? = null,
    isGrowing: Boolean = false,
    revealItem: String? = rowID,
    imageScope: AgentImageScope? = null,
    selectionItemID: String? = null,
) {
    val style = currentMarkdownStyle()
    // The list's reveal when it provides one; otherwise this row's own (a row LazyColumn
    // brings back starts as history, which is what iOS's list-owned records give it too).
    val reveal = LocalTranscriptTextReveal.current ?: remember(rowID) { TranscriptTextReveal() }
    val arrivals = if (revealItem != null) reveal.arrivals(revealItem, blocks, reveal.now()) else emptyMap()
    if (revealItem != null && !isGrowing) reveal.settle(revealItem)
    // Image Link Previews (experimental): thumbnails under prose blocks with direct-image links.
    val imageLinks = ai.opencode.feature.transcript.links.rememberImageLinkPlan(blocks, isGrowing)
    androidx.compose.runtime.CompositionLocalProvider(
        LocalTranscriptTextReveal provides reveal,
        LocalAgentImageOccurrence provides (imageScope?.occurrence ?: rowID ?: LocalAgentImageOccurrence.current),
        LocalAgentImageSiblings provides (imageScope?.siblings ?: remember(blocks) { namedImages(blocks) }),
        LocalLinkPreviewRow provides (rowID ?: LocalLinkPreviewRow.current),
    ) {
        Column(modifier) {
            for ((index, block) in blocks.withIndex()) {
                key(block.id) {
                    androidx.compose.runtime.CompositionLocalProvider(ai.opencode.feature.media.LocalAgentImageBlock provides block.id) {
                    Column {
                    MarkdownBlockView(
                        block,
                        gap = style.gap(if (index == 0) null else blocks[index - 1].kind, block.kind),
                        arrivals = arrivals[block.id].orEmpty(),
                        isGrowingTail = isGrowing && index == blocks.size - 1,
                        selectionItemID = selectionItemID,
                    )
                    imageLinks[block.id]?.let { ai.opencode.feature.transcript.links.BlockImageLinks(block, it) }
                    }
                    }
                }
            }
        }
    }
}

/** The images the blocks name, in reading order (iOS `MarkdownBlocksView.namedImages(in:)`). */
fun namedImages(blocks: List<OpenCodeMarkdownBlock>): List<AgentFileReference> = blocks.flatMap { block ->
    when (val kind = block.kind) {
        is OpenCodeMarkdownBlock.Kind.Files -> kind.files.filter { it.kind == AgentFileReference.Kind.Image }
        is OpenCodeMarkdownBlock.Kind.Image -> listOfNotNull(AgentFileReference.fromLink(kind.source))
        else -> emptyList()
    }
}
