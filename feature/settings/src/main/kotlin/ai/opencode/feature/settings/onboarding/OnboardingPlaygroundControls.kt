package ai.opencode.feature.settings.onboarding

import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.Action
import ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.Control
import ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.Failure
import ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.Latency
import ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.Phase
import ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.Variant
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The playground's control bar: Material controls on the field, nothing behind them. Every
 * button interrupts whatever is running.
 */
@Composable
internal fun OnboardingPlaygroundControls(
    store: StoreOf<OnboardingPlaygroundFeature.State, Action>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val variant by store.observe { it.variant }
    val phase by store.observe { it.phase }
    val latency by store.observe { it.latency }
    val failure by store.observe { it.failure }

    Column(
        modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Variant.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = variant == option,
                    onClick = { store.send(Action.VariantChanged(option)) },
                    shape = SegmentedButtonDefaults.itemShape(index, Variant.entries.size),
                    icon = {},
                    modifier = Modifier.semantics { contentDescription = option.title },
                ) { Text(option.shortTitle, maxLines = 1) }
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.size(width = 2.dp, height = 0.dp))
            Control.entries.forEach { control ->
                PhaseButton(control, current = control == control(phase)) { store.send(Action.ControlTapped(control)) }
            }
            Spacer(Modifier.size(width = 2.dp, height = 0.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f).semantics { contentDescription = "Reply Latency" }) {
                Latency.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = latency == option,
                        onClick = { store.send(Action.LatencyChanged(option)) },
                        shape = SegmentedButtonDefaults.itemShape(index, Latency.entries.size),
                        icon = {},
                    ) { Text(option.title, maxLines = 1) }
                }
            }
            FailureMenu(failure) { store.send(Action.FailureChanged(it)) }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { store.send(Action.ReplayTapped) }, shape = OpenCodeShape.control) {
                OpenCodeIcon(SFSymbol.ArrowCounterclockwise, contentDescription = null, size = OpenCodeIconSize.small)
                Spacer(Modifier.size(6.dp))
                Text("Replay")
            }
            Spacer(Modifier.weight(1f))
            OpenCodeIconButton(SFSymbol.ChevronDown, "Hide Controls", onClick = { store.send(Action.ControlsHiddenChanged(true)) })
            OpenCodeIconButton(SFSymbol.Xmark, "Close Playground", onClick = {
                store.send(Action.CloseTapped)
                onClose()
            })
        }
    }
}

/** What the simulated server does with the next code. */
@Composable
private fun FailureMenu(failure: Failure?, onChange: (Failure?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val title = failure?.title ?: "Answers"
    Box {
        FilledTonalButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            modifier = Modifier.semantics {
                contentDescription = "Server"
                stateDescription = title
            },
            shape = OpenCodeShape.control,
        ) {
            OpenCodeIcon(
                if (failure == null) SFSymbol.CheckmarkCircle else SFSymbol.ExclamationmarkTriangle,
                contentDescription = null,
                size = OpenCodeIconSize.small,
            )
            Spacer(Modifier.size(6.dp))
            Text(title, style = OpenCodeTextStyles.subheadline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val options = listOf<Failure?>(null) + Failure.entries
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option?.title ?: "Answers") },
                    onClick = {
                        expanded = false
                        onChange(option)
                    },
                    trailingIcon = if (option == failure) {
                        { OpenCodeIcon(SFSymbol.Checkmark, contentDescription = null, size = OpenCodeIconSize.small) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** The button for what's on screen carries a lit dot and selection. */
@Composable
private fun PhaseButton(control: Control, current: Boolean, onClick: () -> Unit) {
    val reduceMotion = rememberReduceMotion()
    val dot by animateFloatAsState(
        if (current) 1f else 0.18f,
        animationSpec = OnboardingMotion.layout(reduceMotion),
        label = "phaseDot",
    )
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp).semantics { selected = current },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        shape = OpenCodeShape.control,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .alpha(dot)
                .background(LocalContentColor.current, CircleShape),
        )
        Spacer(Modifier.size(6.dp))
        Text(control.title, style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

internal fun control(phase: Phase): Control = when (phase) {
    Phase.welcome -> Control.welcome
    Phase.story -> Control.story
    Phase.camera -> Control.camera
    Phase.waiting -> Control.scan
    Phase.connected -> Control.reply
    Phase.session -> Control.firstSession
    Phase.failed -> Control.fail
}
