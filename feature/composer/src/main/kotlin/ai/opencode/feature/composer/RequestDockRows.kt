package ai.opencode.feature.composer

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.strings.localized
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.R as ServerR
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** A choice: its mark on the title's baseline, the title 12 dp after it. */
@Composable
fun RequestOptionRow(
    title: String,
    description: String?,
    selected: Boolean,
    multiple: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = OpenCodeTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = if (multiple) Role.Checkbox else Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SelectionMark(selected, multiple)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = OpenCodeTextStyles.body, color = colors.text)
            description?.ifEmpty { null }?.let {
                Text(it, style = OpenCodeTextStyles.footnote, color = colors.secondaryText)
            }
        }
    }
}

/** Hairlines start at the titles. */
@Composable
fun RequestOptionRowDivider() {
    HorizontalDivider(Modifier.padding(start = SelectionMarkWidth + 12.dp), color = OpenCodeTheme.colors.border)
}

/** [SelectionMark]'s width. */
val SelectionMarkWidth: Dp = 22.dp

/**
 * The empty or filled mark beside a choice: a circle for one answer, a rounded square for
 * several. Filled is the cue beyond colour.
 */
@Composable
fun SelectionMark(selected: Boolean, multiple: Boolean) {
    val colors = OpenCodeTheme.colors
    val reducedMotion = rememberReduceMotion()
    val symbol = when {
        selected && multiple -> SFSymbol.CheckmarkSquareFill
        selected -> SFSymbol.CheckmarkCircleFill
        multiple -> SFSymbol.Square
        else -> SFSymbol.Circle
    }
    // Sits on the title's first line (body, 22 sp line).
    Box(Modifier.width(SelectionMarkWidth).heightIn(min = 22.dp), contentAlignment = Alignment.Center) {
        if (reducedMotion) {
            OpenCodeIcon(symbol, contentDescription = null, size = SelectionMarkWidth, tint = if (selected) colors.text else colors.tertiaryText)
        } else {
            // Crossfade's fade without its transition machinery per row: a panel of choices builds in one cheap pass.
            var leaving by remember { mutableStateOf<Pair<SFSymbol, Color>?>(null) }
            var current by remember { mutableStateOf(symbol to (if (selected) colors.text else colors.tertiaryText)) }
            val fade = remember { Animatable(1f) }
            val tint = if (selected) colors.text else colors.tertiaryText
            LaunchedEffect(symbol, tint) {
                if (current.first == symbol) {
                    current = symbol to tint
                    return@LaunchedEffect
                }
                leaving = current
                current = symbol to tint
                fade.snapTo(0f)
                fade.animateTo(1f, Motion.easeOut(Motion.fadeFast))
                leaving = null
            }
            leaving?.let { (old, oldTint) ->
                OpenCodeIcon(old, contentDescription = null, size = SelectionMarkWidth, tint = oldTint, modifier = Modifier.graphicsLayer { alpha = 1f - fade.value })
            }
            OpenCodeIcon(current.first, contentDescription = null, size = SelectionMarkWidth, tint = current.second, modifier = Modifier.graphicsLayer { alpha = fade.value })
        }
    }
}

/**
 * One quiet line in the panel's footnote size: “Couldn’t send answer”, with the server's reason
 * after it unless it only says the server couldn't be reached (Retry says as much).
 */
@Composable
fun RequestErrorLine(text: String, modifier: Modifier = Modifier, detail: String? = null) {
    val colors = OpenCodeTheme.colors
    val shownDetail = RequestErrorLine.detail(text, detail)
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeIcon(SFSymbol.ExclamationmarkCircle, contentDescription = null, size = 16.dp, tint = colors.secondaryText)
        Text(
            if (shownDetail != null) "$text · $shownDetail" else text,
            style = OpenCodeTextStyles.footnote,
            color = colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

object RequestErrorLine {
    /** The reason shown after the text: none when it only says the server couldn't be reached, or repeats the text. */
    fun detail(text: String, detail: String?): String? {
        if (detail == null || detail == OpenCodeClientError.UNREACHABLE_MESSAGE || detail == text) return null
        return brief(detail)
    }

    private val httpStatus = Regex("HTTP (\\d{3})")

    /** The server's reason short enough to fit the line: “The server returned HTTP 500.” reads “HTTP 500”. */
    fun brief(detail: String): String {
        var brief = detail.trim()
        val status = httpStatus.find(brief)?.groupValues?.get(1)?.toIntOrNull()
        if (status != null && brief == localized(ServerR.string.server_error_http_status, status)) return "HTTP $status"
        if (brief.endsWith(".")) brief = brief.dropLast(1)
        return brief
    }
}
