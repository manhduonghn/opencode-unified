package ai.opencode.feature.transcript.rows

import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.appFormattingLocale
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.LocalTranscriptRowContext
import ai.opencode.feature.transcript.R
import ai.opencode.feature.transcript.RowProps
import ai.opencode.feature.transcript.TranscriptAction
import ai.opencode.feature.transcript.TranscriptDisclosureState
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.links.LinkPreviewReplyEnd
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.feature.transcript.markdown.rememberMarkdownBlocks
import ai.opencode.feature.transcript.markdown.ui.DisclosedContent
import ai.opencode.feature.transcript.markdown.ui.LocalQuoteTextHandler
import ai.opencode.feature.transcript.markdown.ui.LocalSessionLinks
import ai.opencode.feature.transcript.markdown.ui.LocalMarkdownSecondary
import ai.opencode.feature.transcript.markdown.ui.MarkdownBlocksView
import ai.opencode.feature.transcript.markdown.ui.QuoteTextHandler
import ai.opencode.feature.transcript.markdown.ui.SessionLinkContext
import ai.opencode.server.model.OpenCodeCompaction
import ai.opencode.server.model.OpenCodeTranscriptItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.unit.dp
import java.text.NumberFormat

/**
 * iOS `TranscriptEntryView`: every non-user row (assistant Markdown, tools and Used N groups,
 * compaction, notices, model switches, run errors, time stamps, undone, reply end, Since You
 * Left). [rowID] is the row's stable identity (`TranscriptRowIdentityPlan`): it keys a Used N
 * group's disclosure, so the group stays open when a member joins ahead of its first one and its
 * entry ID changes. Rows carry no outer padding: the list applies `rowInsets`.
 */
@Composable
fun TranscriptEntryView(
    entry: TranscriptEntry,
    rowID: String,
    props: RowProps,
    modifier: Modifier = Modifier,
) {
    val context = LocalTranscriptRowContext.current
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
        when (entry) {
            is TranscriptEntry.Single -> ItemView(entry, entry.item, rowID, props)
            is TranscriptEntry.UserGroup -> Unit
            is TranscriptEntry.ToolGroup -> InlineToolGroupView(entry, rowID, entry.tools, props.subagents)
            is TranscriptEntry.ReplyEnd -> {
                val actions: @Composable () -> Unit = {
                    ReplyActions(entry.texts) { context.send(TranscriptAction.CopyTextTapped(it)) }
                }
                // The reply's links sit above its Copy row (iOS `LinkPreviewReplyEnd`).
                LinkPreviewReplyEnd(entry.texts, key = rowID, entryID = entry.id, actions = actions)
            }
        }
    }
}

@Composable
private fun ItemView(entry: TranscriptEntry, item: OpenCodeTranscriptItem, rowID: String, props: RowProps) {
    val context = LocalTranscriptRowContext.current
    val colors = OpenCodeTheme.colors
    when (item) {
        is OpenCodeTranscriptItem.UserImages, is OpenCodeTranscriptItem.UserFiles,
        is OpenCodeTranscriptItem.UserText, is OpenCodeTranscriptItem.SendFailure,
        is OpenCodeTranscriptItem.RunRetry,
        -> Unit

        is OpenCodeTranscriptItem.Info ->
            if (item.id.startsWith(TranscriptTimeStamps.PREFIX)) {
                TranscriptTimeStamp(item.text)
            } else {
                Text(item.text, Modifier.fillMaxWidth(), style = OpenCodeTextStyles.footnote, color = colors.tertiaryText, textAlign = TextAlign.Center)
            }

        is OpenCodeTranscriptItem.AssistantMarkdown -> {
            val parsed = rememberMarkdownBlocks(item.id, item.text, isStreaming = props.isGrowing)
            // Appended PR cards belong in the finished reply's link stack. Filter before layout
            // so hidden cards leave no paragraph gaps.
            val blocks = remember(parsed) { parsed.filter { it.kind !is OpenCodeMarkdownBlock.Kind.PullRequest } }
            val quote = remember(context) { QuoteTextHandler { context.send(TranscriptAction.QuoteTextTapped(it)) } }
            CompositionLocalProvider(
                LocalQuoteTextHandler provides quote,
                LocalSessionLinks provides SessionLinkContext(props.sessionLinks, rowID),
            ) {
                MarkdownBlocksView(blocks, Modifier.fillMaxWidth(), rowID = item.id, isGrowing = props.isGrowing, selectionItemID = item.id)
            }
        }

        is OpenCodeTranscriptItem.Tool -> ToolView(entry, item.id, item.call, props.subagents)

        is OpenCodeTranscriptItem.Compaction ->
            CompactionView(entry, item.id, item.info ?: OpenCodeCompaction(OpenCodeCompaction.Status.Completed))

        is OpenCodeTranscriptItem.ModelSwitch ->
            ModelSwitchRule(ModelSwitchLabel(item.model, item.previous, item.name), item.model.providerID)

        is OpenCodeTranscriptItem.Notice ->
            Text(
                item.text,
                Modifier.fillMaxWidth(),
                style = OpenCodeTextStyles.footnote,
                color = if (item.isError) colors.danger else colors.secondaryText,
            )

        is OpenCodeTranscriptItem.RunError ->
            RunErrorRow(
                item.id,
                item.error,
                actions = props.runErrorActions,
                perform = { context.send(TranscriptAction.RunErrorActionTapped(it)) },
                copy = { context.send(TranscriptAction.CopyTextTapped(it)) },
            )

        is OpenCodeTranscriptItem.Undone -> UndoneRow(redo = { context.send(TranscriptAction.RedoTapped) })
    }
}

/**
 * iOS `compactionView`: a labelled rule like the TUI's: running, completed (with the request's
 * own token usage), provider compaction, cancelled, or failed with its error. A completed local
 * summary expands in place.
 */
@Composable
private fun CompactionView(entry: TranscriptEntry, id: String, info: OpenCodeCompaction) {
    val context = LocalTranscriptRowContext.current
    val colors = OpenCodeTheme.colors
    val disclosure = context.disclosure
    val expandable = info.status == OpenCodeCompaction.Status.Completed && info.summary.isNotEmpty()
    val isExpanded = expandable && disclosure.isOpen(TranscriptDisclosureState.Kind.Compactions, id)
    val failed = info.status == OpenCodeCompaction.Status.Failed
    val title = when (info.status) {
        OpenCodeCompaction.Status.Running -> stringResource(R.string.transcript_compaction_running)
        OpenCodeCompaction.Status.Completed -> if (info.isProvider) stringResource(R.string.transcript_compaction_provider) else stringResource(R.string.transcript_compaction_completed)
        OpenCodeCompaction.Status.Cancelled -> stringResource(R.string.transcript_compaction_cancelled)
        OpenCodeCompaction.Status.Failed -> stringResource(R.string.transcript_compaction_failed)
    }
    val usage = info.inputTokens?.let { input ->
        info.outputTokens?.let { output ->
            val format = NumberFormat.getIntegerInstance(appFormattingLocale())
            stringResource(R.string.transcript_compaction_usage, format.format(input), format.format(output))
        }
    }
    val label = listOfNotNull(title, usage).joinToString(", ")
    val toggle = { disclosure.toggle(TranscriptDisclosureState.Kind.Compactions, id, entry.id) }
    val expandedLabel = stringResource(R.string.transcript_expanded)
    val collapsedLabel = stringResource(R.string.transcript_collapsed)
    val showSummaryLabel = stringResource(R.string.transcript_compaction_show_summary_click_label)
    val rule: @Composable (Modifier) -> Unit = { ruleModifier ->
        TranscriptRule(
            ruleModifier,
            lineColor = if (failed) colors.danger.copy(alpha = 0.5f) else quaternaryLine(),
            labelColor = if (failed) colors.danger else colors.secondaryText,
        ) {
            if (info.status == OpenCodeCompaction.Status.Running) {
                CircularProgressIndicator(Modifier.size(10.dp), color = LocalContentColor.current, strokeWidth = 1.5.dp)
            } else if (failed) {
                OpenCodeIcon("xmark.circle.fill", contentDescription = null, size = 12.dp)
            }
            Text(title)
            if (usage != null) Text("· $usage", style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"))
        }
    }
    Column {
        Column(verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.standard)) {
            if (expandable) {
                rule(
                    Modifier.rowHitTarget(
                        top = TranscriptMetrics.sectionHalf,
                        bottom = TranscriptMetrics.sectionHalf,
                        target = Modifier
                            .rowClickable(onClick = toggle)
                            .clearAndSetSemantics {
                                contentDescription = label
                                stateDescription = if (isExpanded) expandedLabel else collapsedLabel
                                role = Role.Button
                                onClick(showSummaryLabel) { toggle(); true }
                            },
                    ),
                )
            } else {
                rule(Modifier.clearAndSetSemantics { contentDescription = label })
            }
            if (failed) {
                info.errorMessage?.let { message ->
                    SelectionContainer {
                        Text(message, Modifier.fillMaxWidth(), style = OpenCodeTextStyles.footnote, color = colors.danger)
                    }
                }
            }
        }
        DisclosedContent(isExpanded) {
            val blocks = rememberMarkdownBlocks("$id-summary", info.summary)
            CompositionLocalProvider(LocalContentColor provides colors.secondaryText, LocalMarkdownSecondary provides true) {
                MarkdownBlocksView(blocks, Modifier.fillMaxWidth().padding(start = 16.dp, top = TranscriptMetrics.standard))
            }
        }
    }
}

