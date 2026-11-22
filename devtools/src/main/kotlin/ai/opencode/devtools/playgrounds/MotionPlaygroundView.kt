package ai.opencode.devtools.playgrounds

import ai.opencode.core.motion.AmbientClock
import ai.opencode.core.motion.AmbientTimeline
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionPresence
import ai.opencode.core.motion.MotionRelease
import ai.opencode.core.motion.MotionRollDirection
import ai.opencode.core.motion.MotionRubberBand
import ai.opencode.core.motion.MotionSwap
import ai.opencode.core.motion.motionPress
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.tuning.TuningContext
import ai.opencode.core.tuning.TuningStore
import ai.opencode.core.ui.OpenCodeSecondaryButton
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeSpacing
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.ui.OpenCodeSwitch
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** One example per motion role, with a Reduce Motion switch. */
@Composable
fun MotionPlaygroundView(modifier: Modifier = Modifier) {
    val systemReduceMotion = rememberReduceMotion()
    var reduceMotion by remember { mutableStateOf(systemReduceMotion) }
    DisposableEffect(Unit) {
        val token = UUID.randomUUID()
        Motion.register(TuningStore.shared)
        TuningStore.shared.setPlaygroundContext(TuningContext.motionPlayground, token)
        onDispose { TuningStore.shared.clearPlaygroundContext(token) }
    }
    Column(modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).stageBackground().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Reduce Motion", Modifier.weight(1f), style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text)
            OpenCodeSwitch(checked = reduceMotion, onCheckedChange = { reduceMotion = it })
        }
        MicroStage(reduceMotion)
        PanelStage(reduceMotion)
        LayoutStage(reduceMotion)
        SwapStage(reduceMotion)
        PressStage(reduceMotion)
        ReleaseStage(reduceMotion)
        AmbientStage(reduceMotion)
    }
}

/** A title, an optional action, and the example on a plain surface. */
@Composable
private fun MotionStage(
    title: String,
    action: String? = null,
    onAction: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OpenCodeSpacing.s8)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = OpenCodeTextStyles.headline, color = OpenCodeTheme.colors.text)
            if (action != null) OpenCodeSecondaryButton(action, onClick = onAction)
        }
        Box(Modifier.fillMaxWidth().stageBackground().padding(16.dp)) { content() }
    }
}

@Composable
private fun Modifier.stageBackground(): Modifier =
    clip(OpenCodeShape.panel).background(OpenCodeSurfaces.color).border(OpenCodeSurfaces.border, OpenCodeShape.panel)

/** A neutral desktop tile (light fill and hairline) for the examples that move. */
@Composable
private fun Modifier.exampleTile(): Modifier =
    clip(OpenCodeShape.card).background(OpenCodeSurfaces.neutralFill).border(OpenCodeSurfaces.border, OpenCodeShape.card)

@Composable
private fun MicroStage(reduceMotion: Boolean) {
    var isShown by remember { mutableStateOf(true) }
    MotionStage("Micro", if (isShown) "Hide" else "Show", { isShown = !isShown }) {
        Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
            MotionPresence(isOpen = isShown, reduceMotion = reduceMotion) {
                Text(
                    "3 New",
                    Modifier.clip(OpenCodeShape.control).background(OpenCodeTheme.colors.accent).padding(horizontal = 12.dp, vertical = 6.dp),
                    color = OpenCodeTheme.colors.variant.accent.highContrastForeground.color,
                    style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}

@Composable
private fun PanelStage(reduceMotion: Boolean) {
    var isOpen by remember { mutableStateOf(false) }
    val phase = if (isOpen) MotionPhase.Enter else MotionPhase.Exit
    val height by animateDpAsState(if (isOpen) 180.dp else 56.dp, Motion.panelOpen.animation(phase, reduceMotion), label = "panel")
    MotionStage("Panel", if (isOpen) "Close" else "Open", { isOpen = !isOpen }) {
        Box(
            Modifier.fillMaxWidth().height(height).exampleTile().clickable { isOpen = !isOpen },
            contentAlignment = Alignment.TopStart,
        ) {
            Text("Panel", Modifier.padding(16.dp), style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text)
        }
    }
}

@Composable
private fun LayoutStage(reduceMotion: Boolean) {
    var isExpanded by remember { mutableStateOf(false) }
    val phase = if (isExpanded) MotionPhase.Enter else MotionPhase.Exit
    val height by animateDpAsState(if (isExpanded) 120.dp else 44.dp, Motion.layout.animation(phase, reduceMotion), label = "layout")
    MotionStage("Layout", if (isExpanded) "Collapse" else "Expand", { isExpanded = !isExpanded }) {
        Column(Modifier.fillMaxWidth().height(height)) {
            Text("opencode-ios", style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text)
            Text("3 sessions · 2 running", color = OpenCodeTheme.colors.secondaryText, style = OpenCodeTextStyles.subheadline)
        }
    }
}

@Composable
private fun SwapStage(reduceMotion: Boolean) {
    var count by remember { mutableIntStateOf(1) }
    MotionStage("Swap", "Next", { count += 1 }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            MotionSwap(value = count, reduceMotion = reduceMotion) { Text("Step $it", style = OpenCodeTextStyles.headline, color = OpenCodeTheme.colors.text) }
            MotionSwap(value = count, roll = MotionRollDirection.Up, reduceMotion = reduceMotion) {
                Text("$it", style = OpenCodeTextStyles.headline, color = OpenCodeTheme.colors.text)
            }
        }
    }
}

@Composable
private fun PressStage(reduceMotion: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    MotionStage("Press") {
        Column(
            Modifier.fillMaxWidth().motionPress(interaction, reduceMotion).exampleTile()
                .clickable(interactionSource = interaction, indication = null) {}.padding(16.dp),
        ) {
            Text("Studio", style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text)
            Text("Connected", style = OpenCodeTextStyles.subheadline, color = OpenCodeTheme.colors.secondaryText)
        }
    }
}

/**
 * Drag the card between two slots; the release rule picks the side and the band resists past the
 * ends.
 */
@Composable
private fun ReleaseStage(reduceMotion: Boolean) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val position = remember { Animatable(0f) }
    var side by remember { mutableIntStateOf(0) }
    var raw by remember { mutableStateOf(0.0) }
    MotionStage("Release") {
        BoxWithConstraints(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.CenterStart) {
            val card = 64.dp
            val travel = with(density) { (maxWidth - card).toPx() }.coerceAtLeast(1f).toDouble()
            val band = MotionRubberBand(dimension = travel)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                repeat(2) { Box(Modifier.size(card).border(1.dp, OpenCodeTheme.colors.border, OpenCodeShape.card)) }
            }
            Box(
                Modifier.offset { IntOffset(position.value.roundToInt(), 0) }.size(card).clip(OpenCodeShape.card)
                    .background(OpenCodeTheme.colors.accent)
                    .draggable(
                        state = rememberDraggableState { delta ->
                            raw += delta
                            scope.launch { position.snapTo(band.band(raw, 0.0..travel).toFloat()) }
                        },
                        orientation = Orientation.Horizontal,
                        onDragStarted = { raw = band.unband(position.value.toDouble(), 0.0..travel) },
                        onDragStopped = { velocity ->
                            val start = side * travel
                            val direction = if (side == 0) 1.0 else -1.0
                            val translation = (raw - start) * direction
                            if (MotionRelease.shouldCommit(translation, velocity * direction, travel)) side = 1 - side
                            position.animateTo(
                                (side * travel).toFloat(),
                                Motion.release.animation(MotionPhase.Enter, reduceMotion),
                                initialVelocity = Motion.release.initialVelocity(velocity, reduceMotion),
                            )
                        },
                    ),
            )
        }
    }
}

/** A breathing dot and a sweep bar on the one ambient clock. */
@Composable
private fun AmbientStage(reduceMotion: Boolean) {
    MotionStage("Ambient") {
        AmbientTimeline(reduceMotion = reduceMotion) { sample: AmbientClock.Sample ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    Modifier.size(14.dp).graphicsLayer { alpha = 0.35f + 0.65f * sample.breath.toFloat() }
                        .clip(CircleShape).background(OpenCodeTheme.colors.accent),
                )
                BoxWithConstraints(Modifier.weight(1f).height(6.dp).clip(OpenCodeShape.capsule).background(OpenCodeSurfaces.neutralFill)) {
                    val sweep = if (sample.isSettled) 0f else sample.sweep.toFloat()
                    Box(Modifier.offset(x = (maxWidth - 48.dp) * sweep).size(48.dp, 6.dp).clip(OpenCodeShape.capsule).background(OpenCodeTheme.colors.accent))
                }
            }
        }
    }
}
