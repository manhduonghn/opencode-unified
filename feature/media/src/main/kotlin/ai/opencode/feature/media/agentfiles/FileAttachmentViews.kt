package ai.opencode.feature.media

import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.drawerPanHorizontalScroller
import ai.opencode.server.model.AgentFileReference
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// `FileAttachmentCard` and `AgentFileCards`. They live here, below the composer and the transcript,
// because media's image gallery shows file cards between its previews; the composer's own chips
// (`ComposerFileChip`, `TranscriptFileChips`) stay in `:feature:composer`.

/**
 * The two-line file card desktop uses for attachments (session-ui `AttachmentCard`): the name above
 * a type label, here with a type glyph. [symbolName] is an SF Symbol name.
 */
@Composable
fun FileAttachmentCard(
    name: String,
    symbolName: String,
    modifier: Modifier = Modifier,
    width: Dp = 200.dp,
    height: Dp? = null,
    detailLines: Int = 1,
    /** A quiet glyph after the text, such as play for media. */
    trailingSymbolName: String? = null,
    detail: @Composable () -> Unit,
) {
    Row(
        modifier
            .width(width)
            .heightIn(min = height ?: 0.dp)
            .padding(horizontal = TranscriptMetrics.standard, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            OpenCodeIcon(symbolName, contentDescription = null, size = OpenCodeIconSize.medium, tint = OpenCodeTheme.colors.secondaryText)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                name,
                style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium),
                color = OpenCodeTheme.colors.text,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            CompositionLocalProvider(
                LocalContentColor provides OpenCodeTheme.colors.secondaryText,
                LocalTextStyle provides OpenCodeTextStyles.caption1.copy(color = OpenCodeTheme.colors.secondaryText),
                LocalFileAttachmentDetailLines provides detailLines,
            ) { detail() }
        }
        if (trailingSymbolName != null) {
            OpenCodeIcon(trailingSymbolName, contentDescription = null, size = 14.dp, tint = OpenCodeTheme.colors.tertiaryText)
        }
    }
}

/** How many lines a card's detail may take. */
internal val LocalFileAttachmentDetailLines = compositionLocalOf { 1 }

/** A card's detail text with the card's line limit. */
@Composable
fun FileAttachmentDetail(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(
        text,
        modifier = modifier,
        color = color,
        maxLines = LocalFileAttachmentDetailLines.current,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Media and documents the agent pointed at, beneath the paragraph that names them. Each opens in
 * the viewer, which flies from its card, and drags the file out to other apps with
 * [LocalMediaDragContext]'s server.
 */
@Composable
fun AgentFileCards(files: List<AgentFileReference>, onOpen: (AgentFileReference) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(TranscriptMetrics.cardCornerRadius)
    val listState = rememberLazyListState()
    LazyRow(
        modifier.fillMaxWidth().drawerPanHorizontalScroller({ listState.canScrollBackward }, { listState.canScrollForward }),
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered),
    ) {
        items(files, key = { it.path }) { file ->
            val openDescription = stringResource(R.string.media_file_card_open_description, file.name)
            FileAttachmentCard(
                name = file.name,
                symbolName = file.symbolName,
                width = if (files.size == 1) 260.dp else 220.dp,
                trailingSymbolName = if (file.kind.isTimeBased) "play.fill" else "chevron.right",
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    // Holding drags the file out, served by the row's context; outside the clip and
                    // fill so the lifted card is the card as drawn.
                    .mediaDragSource(MediaDragPayload.AgentFile(file))
                    .clip(shape)
                    .background(OpenCodeTheme.colors.userBubble)
                    .mediaZoomSource(file.zoomSourceID, TranscriptMetrics.cardCornerRadius)
                    .clickable(role = Role.Button) { onOpen(file) }
                    .clearAndSetSemantics {
                        role = Role.Button
                        contentDescription = openDescription
                        stateDescription = file.typeLabel
                        testTag = "agent-file-card"
                        onClick { onOpen(file); true }
                    },
            ) { FileAttachmentDetail(file.typeLabel) }
        }
    }
}
