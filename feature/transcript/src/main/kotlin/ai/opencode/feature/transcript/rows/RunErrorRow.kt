package ai.opencode.feature.transcript.rows

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.strings.localized
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.media.withinNodeLimit
import ai.opencode.feature.transcript.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.opencode.server.model.OpenCodeRunError

/** iOS `OpenCodeRunError.Action.symbol` (the model in `:server` carries the titles). */
val OpenCodeRunError.Action.symbol: String
    get() = when (this) {
        OpenCodeRunError.Action.TryAgain -> "arrow.clockwise"
        OpenCodeRunError.Action.Edit -> "pencil"
        OpenCodeRunError.Action.Compact -> "rectangle.compress.vertical"
        OpenCodeRunError.Action.SwitchModel -> "cpu"
    }

/** The hold menu and TalkBack name Edit by what it edits. */
private fun menuTitle(action: OpenCodeRunError.Action): String =
    if (action == OpenCodeRunError.Action.Edit) localized(R.string.transcript_run_error_edit_last_message) else action.title

/** iOS `@ScaledMetric(relativeTo: .body)`: [base] dp scaled with the font, like Dynamic Type. */
@Composable
internal fun scaledDp(base: Float) = (base * LocalDensity.current.fontScale).dp

/**
 * A run that ended early, in the Working line's place and type (iOS `RunErrorRow`):
 * the Working orb settled on Failed, what happened in plain words, and one way forward
 * trailing it like Move to Background. Holding the line shows the raw error and every action.
 * Once the reader prompts again only the quiet line stays.
 */
@Composable
fun RunErrorRow(
    id: String,
    error: OpenCodeRunError,
    modifier: Modifier = Modifier,
    /** What it offers now, its own action first; empty in history. */
    actions: List<OpenCodeRunError.Action> = emptyList(),
    perform: (OpenCodeRunError.Action) -> Unit = {},
    copy: (String) -> Unit = {},
) {
    val primary = actions.firstOrNull()
    var menuOpen by remember { mutableStateOf(false) }
    val orbSize = scaledDp(18f)
    val spacing = scaledDp(8f)
    val colors = OpenCodeTheme.colors
    val haptics = remember { dependency(HapticsClient) }
    val copyErrorLabel = stringResource(R.string.transcript_run_error_copy)

    val line: @Composable (Modifier) -> Unit = { lineModifier ->
        Row(
            lineModifier.clearAndSetSemantics {
                contentDescription = error.line
                customActions = actions.drop(1).map { action ->
                    CustomAccessibilityAction(menuTitle(action)) { perform(action); true }
                }.plus(CustomAccessibilityAction(copyErrorLabel) { copy(error.details); true }).withinNodeLimit()
            },
            horizontalArrangement = Arrangement.spacedBy(spacing),
        ) {
            // The glyph centres on the text's cap height, like iOS's baseline guide.
            Box(Modifier.padding(top = scaledDp(2f)).size(orbSize), contentAlignment = Alignment.Center) {
                if (error.isStop) {
                    OpenCodeIcon("hand.raised.fill", contentDescription = null, size = OpenCodeIconSize.small, tint = colors.secondaryText)
                } else {
                    AgentActivityGlyph(AgentGlyphState("run-error|$id", AgentGlyphState.Phase.Failed, size = orbSize))
                }
            }
            Text(error.line, style = OpenCodeTextStyles.body, color = colors.secondaryText)
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            // iOS `contextMenu`: the hold answers with the menu's tap.
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    haptics.play(Haptic.tap)
                    menuOpen = true
                })
            },
    ) {
        if (isAccessibilityTextSize()) {
            Column {
                line(Modifier)
                if (primary != null) RunErrorAction(primary, Modifier.padding(start = orbSize + spacing)) { perform(primary) }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                line(Modifier.weight(1f).alignByBaseline())
                if (primary != null) RunErrorAction(primary, Modifier.alignByBaseline()) { perform(primary) }
            }
        }
        DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
            // iOS `Section(error.details)`: the raw error heads the menu.
            Text(
                error.details,
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(280.dp),
                style = OpenCodeTextStyles.footnote,
                color = colors.secondaryText,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            DropdownMenuItem(
                text = { Text(copyErrorLabel) },
                leadingIcon = { OpenCodeIcon("square.on.square", contentDescription = null) },
                onClick = { menuOpen = false; copy(error.details) },
            )
            if (actions.isNotEmpty()) {
                HorizontalDivider()
                for (action in actions) {
                    DropdownMenuItem(
                        text = { Text(menuTitle(action)) },
                        leadingIcon = { OpenCodeIcon(action.symbol, contentDescription = null) },
                        onClick = { menuOpen = false; perform(action) },
                    )
                }
            }
        }
    }
}

/**
 * The line's one way forward, trailing it: glyph and title in secondary, its target in the row's
 * halves. Chromeless like iOS's `.plain` button; the press ripples in a capsule around the label
 * (as a Material text button), not the target's bare rectangle.
 */
@Composable
private fun RunErrorAction(action: OpenCodeRunError.Action, modifier: Modifier, onClick: () -> Unit) {
    val colors = OpenCodeTheme.colors
    Row(
        modifier.rowHitTarget(target = Modifier.rowClickable(onClick = onClick)),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeIcon(action.symbol, contentDescription = null, size = OpenCodeIconSize.small, tint = colors.secondaryText)
        Text(action.title, style = OpenCodeTextStyles.body, color = colors.secondaryText, maxLines = 1)
    }
}

/**
 * Where an undo or Revert to Here hid messages (iOS `UndoneRow`): “Reverted from here” in one
 * quiet line with Undo trailing, as the TUI's “1 message reverted”.
 */
@Composable
fun UndoneRow(redo: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    val spacing = scaledDp(8f)
    val glyphSize = scaledDp(18f)
    val undoHint = stringResource(R.string.transcript_undone_undo_hint)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier
                .alignByBaseline()
                // iOS `accessibilityHint`: read after the line.
                .semantics(mergeDescendants = true) { stateDescription = undoHint },
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(glyphSize), contentAlignment = Alignment.Center) {
                OpenCodeIcon("arrow.uturn.backward", contentDescription = null, size = OpenCodeIconSize.small, tint = colors.secondaryText)
            }
            Text(stringResource(R.string.transcript_undone_title), style = OpenCodeTextStyles.body, color = colors.secondaryText)
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .alignByBaseline()
                .rowHitTarget(
                    target = Modifier.rowClickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.transcript_undone_undo_click_label),
                        onClick = redo,
                    ),
                ),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenCodeIcon("arrow.uturn.forward", contentDescription = null, size = OpenCodeIconSize.small, tint = colors.secondaryText)
            Text(stringResource(R.string.transcript_undone_undo), style = OpenCodeTextStyles.body, color = colors.secondaryText)
        }
    }
}
