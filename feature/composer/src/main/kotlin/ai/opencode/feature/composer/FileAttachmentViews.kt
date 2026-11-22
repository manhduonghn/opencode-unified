package ai.opencode.feature.composer

import ai.opencode.core.files.AttachmentFileType
import ai.opencode.core.strings.localized
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.appFormattingLocale
import ai.opencode.feature.media.mediaZoomSource
import ai.opencode.feature.media.FileAttachmentCard
import ai.opencode.feature.media.FileAttachmentDetail
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.NumberFormat

/**
 * The transcript metrics these chips share. `FileAttachmentCard`, `FileAttachmentDetail` and
 * `AgentFileCards` live in `:feature:media`, which the transcript and its image gallery use too.
 */
object FileAttachmentMetrics {
    val cardCornerRadius: Dp = 14.dp
    val clustered: Dp = 8.dp
    val standard: Dp = 12.dp
}

/** A composer file: name, size, and upload progress, or a retry when the upload failed. */
object ComposerFileChip {
    /** The least height: larger text grows the chip rather than clipping it. */
    val height: Dp = 76.dp
    val width: Dp = 220.dp
    val cornerRadius: Dp = 22.dp

    fun subtitle(file: ComposerFileAttachment): String = AttachmentFileType.subtitle(file.name, file.mime, file.size)

    /** “42%”, as [appFormattingLocale] writes it. */
    fun percent(value: Double): String =
        NumberFormat.getPercentInstance(appFormattingLocale()).apply { maximumFractionDigits = 0 }.format(value)

    fun statusDescription(file: ComposerFileAttachment): String = when (val status = file.status) {
        is ComposerFileAttachment.Status.Uploading -> localized(R.string.composer_file_uploading_description, percent(file.progress))
        is ComposerFileAttachment.Status.Uploaded -> subtitle(file)
        is ComposerFileAttachment.Status.Failed -> status.message
    }
}

@Composable
fun ComposerFileChip(
    file: ComposerFileAttachment,
    canRetry: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = ComposerFileChip.cornerRadius,
    onOpen: (() -> Unit)? = null,
) {
    val retries = file.isFailed && canRetry
    val retryDescription = stringResource(R.string.composer_file_retry_description, file.name)
    val fileDescription = stringResource(R.string.composer_file_attachment_description, file.name)
    val open = onOpen?.takeIf { ComposerPhotoViewer.isVisualFile(file) }
    val action = if (retries) onRetry else open
    FileAttachmentCard(
        name = file.name,
        symbolName = AttachmentFileType.symbolName(file.name, file.mime),
        width = ComposerFileChip.width,
        height = ComposerFileChip.height,
        detailLines = if (file.isFailed) 2 else 1,
        modifier = modifier
            .then(if (open != null) Modifier.mediaZoomSource(ComposerPhotoViewer.mediaID(file.id.toString()), cornerRadius) else Modifier)
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(if (action != null) Modifier.clickable(role = Role.Button, onClick = action) else Modifier)
            .clearAndSetSemantics {
                contentDescription = if (retries) retryDescription else fileDescription
                stateDescription = ComposerFileChip.statusDescription(file)
                testTag = "pending-file-${file.name}"
                if (action != null) {
                    role = Role.Button
                    onClick { action(); true }
                }
            },
    ) {
        when (val status = file.status) {
            is ComposerFileAttachment.Status.Uploading -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                FileAttachmentDetail(stringResource(R.string.composer_file_uploading_progress, ComposerFileChip.percent(file.progress)))
                LinearProgressIndicator(
                    progress = { file.progress.toFloat() },
                    modifier = Modifier.widthIn(max = 120.dp).fillMaxWidth(),
                )
            }
            is ComposerFileAttachment.Status.Uploaded -> FileAttachmentDetail(ComposerFileChip.subtitle(file))
            is ComposerFileAttachment.Status.Failed -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Top) {
                OpenCodeIcon(
                    if (canRetry) SFSymbol.ArrowClockwise else SFSymbol.ExclamationmarkTriangle,
                    contentDescription = null,
                    size = 14.dp,
                    tint = OpenCodeTheme.colors.danger,
                )
                FileAttachmentDetail(status.message, color = OpenCodeTheme.colors.danger)
            }
        }
    }
}
