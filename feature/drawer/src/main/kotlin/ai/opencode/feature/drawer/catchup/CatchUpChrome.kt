package ai.opencode.feature.drawer.catchup

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.AccessibilityFocusRequest
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeSecondaryButton
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTopBar
import ai.opencode.core.ui.RollingNumber
import ai.opencode.core.ui.RollingNumberAlignment
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.accessibilityFocusRequest
import ai.opencode.core.ui.highContrastForeground
import ai.opencode.feature.drawer.R
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The stage around the Catch Up card: progress and Undo above it, the two dispositions below it,
 * and the Drift field dot-matrix backdrop behind it. Once the last card goes the chrome fades as
 * Catch Up closes itself (`CatchUpFinish.kt`).
 *
 * Draw it edge to edge under [CatchUpStack]; it pads itself by the system bars and cutout, not the
 * keyboard (the card owns keyboard avoidance; the stage stays put beneath the keys).
 *
 * [onDismiss] and [onUndo] should go through [CatchUpStageProxy] so the buttons fly the card like a
 * swipe does.
 */
@Composable
fun CatchUpChrome(
    remaining: Int,
    canUndo: Boolean,
    onExit: () -> Unit,
    onUndo: () -> Unit,
    onDismiss: (CatchUpDisposition) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = rememberReduceMotion()
    var displayedRemaining by remember { mutableIntStateOf(1) }
    SideEffect { if (remaining > 0) displayedRemaining = remaining }
    val shownRemaining = if (remaining > 0) remaining else displayedRemaining
    val finished = remaining == 0
    // The chrome leaves on `microOut`, a fade under Reduce Motion.
    val spec = MotionRole.MicroIn.animation<Float>(if (finished) MotionPhase.Exit else MotionPhase.Enter, reducedMotion)
    val finishedProgress by animateFloatAsState(if (finished) 1f else 0f, spec, label = "CatchUpChromeFinish")
    // One move per stage the chrome is composed for.
    val openFocus = remember { AccessibilityFocusRequest() }

    Box(modifier.fillMaxSize()) {
        CatchUpDriftFieldView(isFinished = finished, modifier = Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
        ) {
            OpenCodeTopBar(
                title = {
                    RollingNumber(
                        text = pluralStringResource(R.plurals.drawer_catch_up_remaining, shownRemaining, shownRemaining),
                        style = OpenCodeTextStyles.headline.copy(fontWeight = FontWeight.SemiBold),
                        alignment = RollingNumberAlignment.Start,
                        modifier = Modifier
                            .graphicsLayer { alpha = 1f - finishedProgress }
                            .then(
                                if (finished) Modifier.clearAndSetSemantics {}
                                else Modifier.semantics { heading() },
                            ),
                    )
                },
                modifier = Modifier.height(CatchUpMetrics.headerHeight),
                navigation = {
                    OpenCodeIconButton(
                        symbol = SFSymbol.ChevronBackward,
                        contentDescription = stringResource(R.string.drawer_catch_up_leave_description),
                        onClick = onExit,
                        // The stage opens on its first element, as VoiceOver does on a screen change.
                        modifier = Modifier.accessibilityFocusRequest(openFocus),
                        style = OpenCodeIconButtonStyle.Standard,
                    )
                },
                actions = {
                    TextButton(onClick = onUndo, enabled = canUndo) {
                        Text(stringResource(R.string.drawer_catch_up_undo), maxLines = 1)
                    }
                },
                containerColor = Color.Transparent,
                hairline = false,
                windowInsets = WindowInsets(0, 0, 0, 0),
            )

            Spacer(Modifier.weight(1f))

            Row(
                Modifier
                    .fillMaxWidth()
                    .height(CatchUpMetrics.footerHeight)
                    .padding(horizontal = 16.dp)
                    .graphicsLayer {
                        alpha = 1f - finishedProgress
                        translationY = if (reducedMotion) 0f else 6.dp.toPx() * finishedProgress
                    }
                    .then(if (finished) Modifier.clearAndSetSemantics {} else Modifier),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OpenCodeSecondaryButton(
                    onClick = { onDismiss(CatchUpDisposition.keepUnread) },
                    enabled = !finished,
                    modifier = Modifier.weight(1f),
                ) { FittingLabel(stringResource(R.string.drawer_catch_up_keep_unread)) }
                val readTint = CatchUpMetrics.readControlColor
                MaterialTheme(
                    colorScheme = MaterialTheme.colorScheme.copy(primary = readTint, onPrimary = readTint.highContrastForeground()),
                ) {
                    OpenCodePrimaryButton(
                        onClick = { onDismiss(CatchUpDisposition.markRead) },
                        enabled = !finished,
                        modifier = Modifier.weight(1f),
                    ) { FittingLabel(stringResource(R.string.drawer_mark_as_read)) }
                }
            }
        }
    }
}

/** One line, shrinking to 75 % before it would wrap. */
@Composable
private fun FittingLabel(text: String) {
    val style = MaterialTheme.typography.labelLarge
    val size = if (style.fontSize.isSp) style.fontSize else 14.sp
    BasicText(
        text = text,
        style = style.merge(TextStyle(color = LocalContentColor.current, textAlign = TextAlign.Center)),
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = size * 0.75f, maxFontSize = size),
    )
}
