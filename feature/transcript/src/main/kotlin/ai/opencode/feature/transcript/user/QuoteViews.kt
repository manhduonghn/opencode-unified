package ai.opencode.feature.transcript.user

import ai.opencode.core.R as CoreR
import ai.opencode.core.ui.OpenCodeAlertDialog
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.R
import ai.opencode.core.ui.drawerPanHorizontalScroller
import ai.opencode.server.model.OpenCodeQuote
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Desktop's `outline-quote` icon.
private val QuoteGlyph: ImageVector by lazy {
    val marks = listOf(
        "M9.5 8.49927C10.8333 8.49927 12.1667 8.49927 13.5 8.49927C13.5 9.74927 13.5 11.2493 13.5 12.4993C12.1667 12.4993 10.8333 12.4993 9.5 12.4993V7C9.5 5 10.5 3.5 13 3.5001",
        "M2.5 8.49927C3.83333 8.49927 5.16667 8.49927 6.5 8.49927C6.5 9.74927 6.5 11.2493 6.5 12.4993C5.16667 12.4993 3.83333 12.4993 2.5 12.4993V7C2.5 5 3.5 3.5 6 3.5001",
    )
    val builder = ImageVector.Builder("quote", 16.dp, 16.dp, 16f, 16f)
    for (mark in marks) {
        builder.addPath(
            pathData = addPathNodes(mark),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.25f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    builder.build()
}

fun quoteLine(text: String): String {
    val parts = ArrayList<String>()
    for (line in text.lines()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("```")) continue
        parts.add(trimmed)
    }
    return parts.joinToString(" ")
}

private val QuoteCardStyle = OpenCodeTextStyles.caption1
private val QuoteCardPadding = 8.dp
private val QuoteCardStart = 10.dp
private val QuoteCardGap = 6.dp
private val QuoteCardDetailHeight = 16.dp
private val QuoteCardWidth = 168.dp

/** The card's height at the current font size: two lines and the padding around them. */
@Composable
fun quoteCardHeight(): Dp {
    val line = with(LocalDensity.current) { QuoteCardStyle.lineHeight.toDp() }
    return QuoteCardPadding * 2 + line + QuoteCardGap + maxOf(line, QuoteCardDetailHeight)
}

/**
 * Desktop's quote card: the comment, else the quote, on one line, over a muted line with the
 * quote glyph and the quote (when commented) or “Quote”. With [offersComment] an uncommented
 * card's second line offers “Add Comment”, as desktop's does on touch.
 */
@Composable
fun QuoteCard(
    text: String,
    comment: String?,
    offersComment: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    titleEndPadding: Dp = QuoteCardStart,
) {
    val colors = OpenCodeTheme.colors
    val line = quoteLine(text)
    val spoken = line.trimEnd { it in ",;:." }
    val description = if (comment != null) {
        stringResource(R.string.transcript_quote_comment_description, spoken, comment)
    } else {
        stringResource(R.string.transcript_quote_description, spoken)
    }
    val openLabel = when {
        !offersComment -> stringResource(R.string.transcript_quote_show)
        comment != null -> stringResource(R.string.transcript_quote_edit_comment)
        else -> stringResource(R.string.transcript_quote_add_comment)
    }
    val density = LocalDensity.current
    val iconSize = with(density) { 12.sp.toDp() }
    // Larger text widens the card a little so its second line still reads.
    val width = QuoteCardWidth * density.fontScale.coerceIn(1f, 1.5f)
    Column(
        modifier
            .width(width)
            .clickable(onClickLabel = openLabel, onClick = onOpen)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(openLabel) { onOpen(); true }
            }
            .padding(start = QuoteCardStart, top = QuoteCardPadding, bottom = QuoteCardPadding),
        verticalArrangement = Arrangement.spacedBy(QuoteCardGap),
    ) {
        Text(
            comment ?: line,
            style = QuoteCardStyle.copy(fontWeight = FontWeight.Medium),
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = titleEndPadding),
        )
        Row(
            Modifier.heightIn(min = QuoteCardDetailHeight).padding(end = QuoteCardStart),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (offersComment && comment == null) {
                OpenCodeIcon(SFSymbol.BubbleLeft, contentDescription = null, size = iconSize, tint = colors.secondaryText)
                Text(stringResource(R.string.transcript_quote_add_comment), style = QuoteCardStyle, color = colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Icon(QuoteGlyph, contentDescription = null, modifier = Modifier.size(iconSize), tint = colors.secondaryText)
                Text(
                    if (comment != null) line else stringResource(R.string.transcript_quote),
                    style = QuoteCardStyle,
                    color = colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun QuotePreview(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val colors = OpenCodeTheme.colors
    Row(modifier.height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.dp))
                .background(colors.border),
        )
        Text(
            text.trim(),
            style = OpenCodeTextStyles.subheadline,
            color = colors.secondaryText,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
    }
}

@Composable
private fun QuoteDialog(quote: OpenCodeQuote, onDismiss: () -> Unit) {
    OpenCodeAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.common_done)) } },
        title = { Text(stringResource(R.string.transcript_quote)) },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    QuotePreview(quote.text, Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()))
                    quote.comment?.let { Text(it, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text) }
                }
            }
        },
    )
}

@Composable
internal fun TranscriptQuoteCards(quotes: List<OpenCodeQuote>, trailingCorners: UserSurfaceCorners, modifier: Modifier = Modifier) {
    var opened by remember { mutableStateOf<OpenCodeQuote?>(null) }
    opened?.let { QuoteDialog(it, onDismiss = { opened = null }) }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        val scroll = rememberScrollState()
        Row(
            Modifier.drawerPanHorizontalScroller(scroll).horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered),
        ) {
            for ((index, quote) in quotes.withIndex()) {
                val corners = if (index == quotes.lastIndex) trailingCorners else UserSurfaceCorners.round
                val shape = corners.shape(TranscriptMetrics.cardCornerRadius)
                QuoteCard(
                    quote.text,
                    quote.comment,
                    offersComment = false,
                    onOpen = { opened = quote },
                    modifier = Modifier.clip(shape).background(cardFill(), shape),
                )
            }
        }
    }
}
