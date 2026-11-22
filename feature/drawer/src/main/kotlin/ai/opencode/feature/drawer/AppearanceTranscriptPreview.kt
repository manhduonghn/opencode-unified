package ai.opencode.feature.drawer

import ai.opencode.core.strings.localized
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.LocalTranscriptLayoutTuning
import ai.opencode.feature.transcript.LocalTranscriptRowContext
import ai.opencode.feature.transcript.RowProps
import ai.opencode.feature.transcript.TranscriptDisclosureState
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.TranscriptLayoutTuning
import ai.opencode.feature.transcript.TranscriptRowContext
import ai.opencode.feature.transcript.user.TranscriptMessageRow
import ai.opencode.feature.transcript.user.UserMessageRun
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeTranscriptItem
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * The real transcript rows behind Settings › Appearance › Themes: a user bubble and an assistant
 * Markdown reply with inline code and a code band, drawn by `TranscriptMessageRow` at real
 * transcript spacing. `:feature:settings` sits beside `:feature:transcript`, so Home provides this
 * through `LocalAppearanceTranscriptPreview`; Settings themes it, caps its type and labels it.
 */
internal val AppearanceTranscriptPreviewRows: @Composable () -> Unit = {
    val disclosure = remember { TranscriptDisclosureState() }
    val context = remember(disclosure) {
        TranscriptRowContext(server = previewServer, sessionID = null, directory = null, disclosure = disclosure, send = {})
    }
    CompositionLocalProvider(
        LocalTranscriptRowContext provides context,
        LocalTranscriptLayoutTuning provides previewTuning,
    ) {
        Column(Modifier.fillMaxWidth()) {
            previewEntries.forEach { entry ->
                TranscriptMessageRow(
                    rowID = entry.id,
                    entry = entry,
                    queued = null,
                    failure = null,
                    deliveryNote = null,
                    props = RowProps(),
                    run = UserMessageRun.alone,
                    revealArrival = null,
                    // At the readable width on a wider window.
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentWidth()
                        .widthIn(max = TranscriptMetrics.readableColumnWidth)
                        .fillMaxWidth()
                        .padding(horizontal = TranscriptMetrics.contentGutter),
                )
            }
        }
    }
}

private val previewServer = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "")

private val previewTuning = TranscriptLayoutTuning()

private val previewEntries: List<TranscriptEntry>
    get() = listOf(
    TranscriptEntry.UserGroup(
        groupID = "msg_preview_user",
        items = listOf(
            OpenCodeTranscriptItem.UserText(
                id = "msg_preview_user",
                text = localized(R.string.drawer_appearance_preview_user_text),
            ),
        ),
        startsTurn = false,
    ),
    TranscriptEntry.Single(
        OpenCodeTranscriptItem.AssistantMarkdown(
            id = "msg_preview_assistant",
            text = localized(R.string.drawer_appearance_preview_assistant_text) + "\n\n" + """
                |```swift
                |struct SessionSummary: Equatable {
                |  var title: String
                |  var isRunning: Bool
                |  var detail = "A deliberately long line to inspect horizontal scrolling on a narrow phone display"
                |}
                |```
            """.trimMargin(),
        ),
    ),
)
