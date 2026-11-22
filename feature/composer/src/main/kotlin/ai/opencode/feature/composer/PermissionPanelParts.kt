package ai.opencode.feature.composer

import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.ui.openCodeHairline
import androidx.compose.material3.MaterialTheme
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.server.model.OpenCodePermissionRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import ai.opencode.core.ui.OpenCodeFonts
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * A permission in a line or two: its command flattened onto one line, the file it edits (with `+N
 * −M`), or what it reaches.
 */
data class PermissionSummary(val text: String, val isCode: Boolean) {
    companion object {
        operator fun invoke(permission: OpenCodePermissionRequest, command: String?): PermissionSummary {
            val preview = permission.filePreview
            if (preview != null) {
                val (file, patch) = preview
                val lines = patch.split("\n")
                val added = lines.count { it.startsWith("+") && !it.startsWith("+++") }
                val removed = lines.count { it.startsWith("-") && !it.startsWith("---") }
                return PermissionSummary("${lastPathComponent(file)}  +$added −$removed", isCode = false)
            }
            if (command != null) return PermissionSummary(flattened(command), isCode = true)
            return PermissionSummary(flattened(permission.resources.joinToString(" ")), isCode = permission.resources.isNotEmpty())
        }

        /** Every Unicode whitespace, newlines included. */
        fun flattened(text: String): String = buildString {
            var pending = false
            for (char in text) {
                if (char.isWhitespace()) {
                    pending = isNotEmpty()
                } else {
                    if (pending) append(' ')
                    pending = false
                    append(char)
                }
            }
        }
    }
}

/** `NSString.lastPathComponent`. */
internal fun lastPathComponent(path: String): String {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty()) return if (path.isEmpty()) "" else "/"
    return trimmed.substringAfterLast('/')
}

/** The permission actions' sizes. */
object RequestTunables {
    /** `requests.permission.scopeMaxHeight`: the scope list's maximum height (120…400). */
    val scopeMaxHeight = 220.dp
    /** `requests.permission.labelHeight`: the action label's height (20…44); with the button's padding, a 48 dp target. */
    val labelHeight = 30.dp
    /** `requests.permission.buttonRadius`: the actions' corner radius (6…24), the card's family rather than a capsule. */
    val buttonRadius = 8.dp
}

/**
 * Transcript fonts and corner the request panel borrows (the transcript module sits beside this
 * one).
 */
internal object RequestTranscriptMetrics {
    /** `TranscriptMetrics.toolFont`: footnote, monospaced. */
    val toolFont: TextStyle get() = OpenCodeTextStyles.footnote.copy(fontFamily = OpenCodeFonts.monospace)
    /** `TranscriptMetrics.codeFont`: subheadline, monospaced. */
    val codeFont: TextStyle get() = OpenCodeTextStyles.subheadline.copy(fontFamily = OpenCodeFonts.monospace)
    /** `TranscriptMetrics.controlCornerRadius`. */
    val controlCornerRadius = 6.dp
    /** `TranscriptBandStyle.verticalPadding`: from a hairline to the content's first line box. */
    val bandVerticalPadding = 12.dp
}

/**
 * A permission action: normal height, its corners [RequestTunables.buttonRadius]. Primary is the
 * accent fill (Always Allow), secondary the desktop's neutral hairline one (Allow Once).
 */
@Composable
internal fun RequestPermissionButton(
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(RequestTunables.buttonRadius)
    val padding = PaddingValues(horizontal = 16.dp, vertical = 9.dp)
    val sized = modifier.heightIn(min = RequestTunables.labelHeight + 18.dp)
    if (primary) {
        Button(onClick, sized, enabled, shape = shape, colors = ButtonDefaults.buttonColors(), contentPadding = padding, content = content)
    } else {
        // The desktop's neutral button: a light neutral fill and a hairline.
        FilledTonalButton(
            onClick, sized, enabled, shape = shape,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = OpenCodeSurfaces.neutralFill, contentColor = OpenCodeTheme.colors.text),
            border = OpenCodeSurfaces.border,
            contentPadding = padding, content = content,
        )
    }
}

/**
 * Always Allow's scope, shown in full before it's given: every pattern
 * wraps, selectable, and a long list scrolls above the confirm, which stays in view.
 */
@Composable
fun PermissionScopeConfirm(save: List<String>, confirm: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    Column(modifier.width(280.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.composer_request_scope_title),
            style = OpenCodeTextStyles.footnote.copy(fontWeight = FontWeight.Medium),
            color = colors.secondaryText,
        )
        SelectionContainer(
            Modifier
                .heightIn(max = RequestTunables.scopeMaxHeight)
                .verticalScroll(rememberScrollState())
                .testTag("request-scope-list"),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (pattern in save) {
                    Text(pattern, style = RequestTranscriptMetrics.toolFont, color = colors.text, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        RequestPermissionButton(primary = true, onClick = confirm, modifier = Modifier.fillMaxWidth().testTag("request-always-confirm")) {
            Text(
                stringResource(R.string.composer_request_always_allow),
                style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
        }
    }
}

/** What a permission would do: the command as a full-width band, or the file and its diff. */
@Composable
fun PermissionPreview(permission: OpenCodePermissionRequest, command: String?, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    val preview = permission.filePreview
    Column(modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            preview != null -> {
                SelectionContainer {
                    Text(
                        preview.first,
                        style = RequestTranscriptMetrics.codeFont,
                        color = colors.secondaryText,
                        modifier = Modifier.padding(horizontal = RequestDockMetrics.edge),
                    )
                }
                DiffPreview(preview.second, Modifier.padding(horizontal = RequestDockMetrics.edge))
            }
            command != null -> PermissionBand(command)
            permission.resources.isNotEmpty() -> PermissionBand(permission.resources.joinToString("\n"))
        }
    }
}

@Composable
private fun PermissionBand(text: String) {
    val colors = OpenCodeTheme.colors
    val styled = remember(text, colors.tertiaryText) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = colors.tertiaryText)) { append("$ ") }
            append(text)
        }
    }
    val rule = colors.border
    SelectionContainer {
        Text(
            styled,
            style = RequestTranscriptMetrics.codeFont,
            color = colors.text,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.text.copy(alpha = if (colors.isDark) 0.035f else 0.025f))
                // The hairlines overlay the band's top and bottom edges (they add no height).
                .drawWithContent {
                    drawContent()
                    val px = 1f
                    drawRect(rule, topLeft = Offset.Zero, size = Size(size.width, px))
                    drawRect(rule, topLeft = Offset(0f, size.height - px), size = Size(size.width, px))
                }
                .padding(vertical = RequestTranscriptMetrics.bandVerticalPadding, horizontal = RequestDockMetrics.edge),
        )
    }
}

/** A unified diff's changed lines, tinted, without the file headers. */
@Composable
private fun DiffPreview(patch: String, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    val lines = remember(patch) { DiffPreviewLines.lines(patch) }
    // The desktop diff block: a hairline ring around the lines.
    Column(modifier.clip(RoundedCornerShape(RequestTranscriptMetrics.controlCornerRadius)).openCodeHairline(RoundedCornerShape(RequestTranscriptMetrics.controlCornerRadius))) {
        for (line in lines) {
            val (foreground, fill) = when (line.kind) {
                DiffPreviewLines.Kind.Added -> colors.diffAddedText to colors.diffAddedFill
                DiffPreviewLines.Kind.Removed -> colors.diffRemovedText to colors.diffRemovedFill
                DiffPreviewLines.Kind.Context -> colors.text to Color.Transparent
                DiffPreviewLines.Kind.Hunk -> colors.secondaryText to Color.Transparent
            }
            val body = line.text.drop(1)
            val spoken = when (line.kind) {
                DiffPreviewLines.Kind.Added -> stringResource(R.string.composer_diff_added_description, body)
                DiffPreviewLines.Kind.Removed -> stringResource(R.string.composer_diff_removed_description, body)
                DiffPreviewLines.Kind.Context, DiffPreviewLines.Kind.Hunk -> body
            }
            Text(
                line.text.ifEmpty { " " },
                style = RequestTranscriptMetrics.toolFont,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(fill)
                    .padding(horizontal = 6.dp)
                    .semantics { contentDescription = spoken },
            )
        }
    }
}

internal object DiffPreviewLines {
    enum class Kind { Added, Removed, Context, Hunk }

    data class Line(val kind: Kind, val text: String)

    fun lines(patch: String, limit: Int = 60): List<Line> {
        val result = mutableListOf<Line>()
        for (line in patch.split("\n")) {
            if (line.startsWith("Index:") || line.startsWith("===") || line.startsWith("---") || line.startsWith("+++") || line.startsWith("\\")) continue
            val kind = when {
                line.startsWith("@@") -> Kind.Hunk
                line.startsWith("+") -> Kind.Added
                line.startsWith("-") -> Kind.Removed
                else -> Kind.Context
            }
            if (kind == Kind.Context && line.isEmpty() && result.isEmpty()) continue
            result += Line(kind, line)
            if (result.size == limit) break
        }
        while (result.lastOrNull()?.let { it.kind == Kind.Context && it.text.isEmpty() } == true) result.removeAt(result.lastIndex)
        return result
    }
}
