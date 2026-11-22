package ai.opencode.devtools

import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.devtools.tuning.SlowMotion
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.tuning.InternalBuild
import ai.opencode.devtools.perf.LayoutBoundsOverlay
import ai.opencode.devtools.perf.PerfHud
import ai.opencode.devtools.tuning.TuningFeature
import ai.opencode.devtools.tuning.TuningPanel
import ai.opencode.core.store.collectState
import ai.opencode.core.ui.OpenCodeAppTheme
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Wraps the app's root content with the developer tools. Outside debug builds with Developer Mode
 * off it is just [content].
 */
@Composable
fun DevToolsOverlay(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val developerModeShared = remember { shared(SharedKeys.developerMode) }
    val developerMode by developerModeShared.flow.collectAsState()
    val available = InternalBuild.isDebug || developerMode
    if (!available) {
        Box(modifier) { content() }
        return
    }
    val perfHud by remember { shared(DevToolsKeys.perfHud) }.flow.collectAsState()
    val layoutBounds by remember { shared(DevToolsKeys.layoutBounds) }.flow.collectAsState()
    val tuning by DevTools.tuningStore.collectState()
    Box(modifier.summonGesture(onSummon = { DevTools.summon() }, onToggleSlowMotion = { DevTools.toggleSlowMotion() })) {
        content()
        OpenCodeAppTheme {
            if (layoutBounds) LayoutBoundsOverlay(Modifier.fillMaxSize())
            if (perfHud) PerfHud()
            TuningPanel(DevTools.tuningStore)
            AnimatedVisibility(
                visible = tuning.slowMotion.isSlowed,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
            ) { SlowMotionBadge(tuning.slowMotion.scale) }
            if (tuning.showsDeveloperTools) {
                val close = { DevTools.tuningStore.send(TuningFeature.Action.DeveloperToolsClosed) }
                Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = false)) {
                    val view = LocalView.current
                    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
                    SideEffect {
                        (view.parent as? DialogWindowProvider)?.window?.let { window ->
                            WindowCompat.getInsetsController(window, view).run {
                                isAppearanceLightStatusBars = light
                                isAppearanceLightNavigationBars = light
                            }
                        }
                    }
                    DevToolsView(DevTools.developerStore, Modifier.fillMaxSize(), onClose = close)
                }
            }
        }
    }
}

/**
 * Slow Motion is on: a speed tag under the status bar that never takes a touch, so a recording
 * shows its speed.
 */
@Composable
private fun SlowMotionBadge(scale: Double) {
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.getTop(density) / density.density
    val label = SlowMotion.label(scale)
    Text(
        label,
        modifier = Modifier
            .offset(y = (maxOf(top - 6f, 10f) - 8f).dp)
            .background(Color(0xFFFF9500), CircleShape)
            .height(16.dp)
            .padding(horizontal = 6.dp)
            .wrapContentHeight(Alignment.CenterVertically)
            .clearAndSetSemantics {
                contentDescription = "Slow Motion"
                stateDescription = label
            },
        style = OpenCodeTextStyles.caption2.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
        color = Color.White,
    )
}

/**
 * Three-finger double tap, or four fingers held for 0.45 s, summons; a two-finger triple tap
 * toggles Slow Motion. Observes in the Initial pass and never consumes.
 */
fun Modifier.summonGesture(onSummon: () -> Unit, onToggleSlowMotion: () -> Unit = {}): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        val taps = SummonTaps()
        awaitPointerEventScope {
            while (true) {
                summonOnce(taps, onSummon, onToggleSlowMotion)
            }
        }
    },
)

/** The taps seen so far: the last three-finger tap, and the run of two-finger taps. */
private class SummonTaps {
    var lastTripleTapUp = 0L
    var twoFingerTaps = 0
    var lastTwoFingerTapUp = 0L
}

private const val TAP_MAX_MS = 350L
private const val DOUBLE_TAP_WINDOW_MS = 500L
private const val HOLD_MS = 450L

/** Follows one gesture (first finger down to last up), counting it into [taps]. */
private suspend fun AwaitPointerEventScope.summonOnce(taps: SummonTaps, onSummon: () -> Unit, onToggleSlowMotion: () -> Unit) {
    var event = awaitPointerEvent(PointerEventPass.Initial)
    if (event.changes.none { it.pressed }) return
    val start = event.changes.first().uptimeMillis
    var maxPressed = 0
    var fourSince = 0L
    var held = false
    while (true) {
        val pressed = event.changes.count { it.pressed }
        maxPressed = maxOf(maxPressed, pressed)
        val now = event.changes.maxOf { it.uptimeMillis }
        if (pressed == 4) {
            if (fourSince == 0L) fourSince = now
            if (!held && now - fourSince >= HOLD_MS) {
                held = true
                onSummon()
            }
        } else {
            fourSince = 0L
        }
        if (pressed == 0) {
            val isTap = !held && now - start <= TAP_MAX_MS
            if (isTap && maxPressed == 2) {
                val continues = taps.lastTwoFingerTapUp != 0L && now - taps.lastTwoFingerTapUp <= DOUBLE_TAP_WINDOW_MS
                taps.twoFingerTaps = if (continues) taps.twoFingerTaps + 1 else 1
                taps.lastTwoFingerTapUp = now
                if (taps.twoFingerTaps == 3) {
                    taps.twoFingerTaps = 0
                    taps.lastTwoFingerTapUp = 0L
                    onToggleSlowMotion()
                }
                return
            }
            taps.twoFingerTaps = 0
            taps.lastTwoFingerTapUp = 0L
            if (isTap && maxPressed == 3) {
                if (taps.lastTripleTapUp != 0L && now - taps.lastTripleTapUp <= DOUBLE_TAP_WINDOW_MS) {
                    onSummon()
                    taps.lastTripleTapUp = 0L
                } else {
                    taps.lastTripleTapUp = now
                }
            }
            return
        }
        // Four fingers resting still send no events: wake when the hold is due.
        val timeout = if (fourSince != 0L && !held) (HOLD_MS - (now - fourSince)).coerceAtLeast(1L) else Long.MAX_VALUE
        val next = if (timeout == Long.MAX_VALUE) awaitPointerEvent(PointerEventPass.Initial) else withTimeoutOrNull(timeout) { awaitPointerEvent(PointerEventPass.Initial) }
        if (next == null) {
            if (!held) {
                held = true
                onSummon()
            }
            event = awaitPointerEvent(PointerEventPass.Initial)
        } else {
            event = next
        }
    }
}
