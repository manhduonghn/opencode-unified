package ai.opencode.feature.drawer.list

import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionReduce
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.rememberReduceMotion
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlin.math.abs

/**
 * A row's lead while the drawer selects: [rest] (the project tile, or the Inbox Sidebar's dot)
 * crossfades and shrinks into the [SelectionCheck], and the lead widens from [restWidth] to
 * [selectWidth]. The held row's check pops in first; the others follow in a wave out from it, one
 * stagger step per row's height away. Leaving plays it back at once. Reduce Motion fades.
 */
@Composable
internal fun SelectionLead(
    checked: Boolean?,
    restWidth: Dp,
    selectWidth: Dp,
    checkSize: Dp,
    modifier: Modifier = Modifier,
    rest: @Composable () -> Unit,
) {
    val reduce = rememberReduceMotion()
    val selection = LocalDrawerSelection.current
    val selecting = checked != null
    // The check keeps its last state while it fades away.
    var shownChecked by remember { mutableStateOf(checked == true) }
    if (checked != null) shownChecked = checked
    val mode = remember { Animatable(if (selecting) 1f else 0f) }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val wasSelecting = remember { booleanArrayOf(selecting) }
    val rowStep = with(LocalDensity.current) { 48.dp.toPx() }

    // The held row is the one chosen as selection begins. Side effects run before any row's
    // launched effect, so the wave knows where it starts.
    SideEffect {
        if (selecting && !wasSelecting[0] && checked == true) selection?.originY = coordinates[0]?.centerY()
        wasSelecting[0] = selecting
    }
    LaunchedEffect(selecting) {
        if (!selecting) {
            mode.animateTo(0f, MotionRole.Swap.animation(MotionPhase.Exit, reduce))
            return@LaunchedEffect
        }
        val origin = selection?.originY
        val here = coordinates[0]?.centerY()
        val steps = if (reduce || origin == null || here == null) 0 else (abs(here - origin) / rowStep + 0.5f).toInt()
        val spec = when {
            reduce -> MotionRole.Swap.animation<Float>(MotionPhase.Enter, reduceMotion = true)
            steps == 0 -> MotionRole.MicroIn.animation(MotionPhase.Enter)
            else -> MotionRole.Swap.staggered(steps, MotionPhase.Enter)
        }
        mode.animateTo(1f, spec)
    }

    val showsRest by remember { derivedStateOf { mode.value < 1f } }
    val showsCheck by remember { derivedStateOf { mode.value > 0f } }
    val width = if (reduce) {
        if (selecting || showsCheck) selectWidth else restWidth
    } else {
        lerp(restWidth, selectWidth, mode.value.coerceIn(0f, 1f))
    }
    Box(
        modifier.width(width).onGloballyPositioned { coordinates[0] = it },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (showsRest) {
            Box(
                Modifier.graphicsLayer {
                    val m = mode.value
                    alpha = (1f - m).coerceIn(0f, 1f)
                    scaleX = MotionReduce.scale(1f - 0.4f * m, reduce)
                    scaleY = scaleX
                },
            ) { rest() }
        }
        if (showsCheck) {
            SelectionCheck(
                shownChecked,
                Modifier.graphicsLayer {
                    val m = mode.value
                    alpha = m.coerceIn(0f, 1f)
                    scaleX = MotionReduce.scale(0.6f + 0.4f * m, reduce)
                    scaleY = scaleX
                },
                size = checkSize,
            )
        }
    }
}

private fun LayoutCoordinates.centerY(): Float? = if (isAttached) boundsInRoot().center.y else null

/** How much of a chosen row's tint shows: it fades in and out with the choice. */
@Composable
internal fun rememberCheckedTintAlpha(checked: Boolean): () -> Float {
    val reduce = rememberReduceMotion()
    val alpha = remember { Animatable(if (checked) 1f else 0f) }
    LaunchedEffect(checked) {
        alpha.animateTo(
            if (checked) 1f else 0f,
            MotionRole.Swap.animation(if (checked) MotionPhase.Enter else MotionPhase.Exit, reduce),
        )
    }
    return { alpha.value }
}

/**
 * The title band's swap between the server title (with search) and [DrawerSelectionBar]: the
 * title rises and fades out as the bar rises into place, and back again, quicker, on leaving.
 * Reduce Motion crossfades.
 */
@Composable
internal fun SelectionHeaderSwap(
    active: Boolean,
    bar: @Composable () -> Unit,
    title: @Composable () -> Unit,
) {
    val reduce = rememberReduceMotion()
    val progress = remember { Animatable(if (active) 1f else 0f) }
    LaunchedEffect(active) {
        progress.animateTo(
            if (active) 1f else 0f,
            MotionRole.Swap.animation(if (active) MotionPhase.Enter else MotionPhase.Exit, reduce),
        )
    }
    val showsTitle by remember { derivedStateOf { progress.value < 1f } }
    val showsBar by remember { derivedStateOf { progress.value > 0f } }
    val rise = with(LocalDensity.current) { 10.dp.toPx() }
    if (showsTitle) {
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val t = progress.value
                    alpha = (1f - t * 2.2f).coerceIn(0f, 1f)
                    translationY = MotionReduce.offset(-rise * t, reduce)
                },
            contentAlignment = Alignment.CenterEnd,
        ) { title() }
    }
    if (showsBar) {
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val t = progress.value
                    alpha = ((t - 0.4f) / 0.6f).coerceIn(0f, 1f)
                    translationY = MotionReduce.offset(rise * (1f - t), reduce)
                },
        ) { bar() }
    }
}
