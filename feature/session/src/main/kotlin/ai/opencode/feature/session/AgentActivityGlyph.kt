package ai.opencode.feature.session

import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.session.agentactivity.AgentActivityGlyphModel
import ai.opencode.feature.session.agentactivity.AgentActivityGlyphModels
import ai.opencode.feature.session.agentactivity.animationKey
import ai.opencode.feature.session.agentactivity.drawAgentActivityOrb
import ai.opencode.feature.transcript.list.LocalTranscriptTimelinePaused
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

// The models and the orb renderer live in `agentactivity/`.

/**
 * Scopes cached glyph models: with a scope, a glyph whose source has an animation key keeps its
 * motion across remounts under `scope|key`.
 */
val LocalAgentActivityScope = compositionLocalOf<String?> { null }

/** 60 Hz looks better than 30; Apple would allow lower. */
internal const val GLYPH_FRAME_INTERVAL = 1.0 / 60.0

/**
 * The agent activity glyph. The dots and glow are the dot orb's
 * (`prototypes/ios-kit/dot-orb/layer.js`): a 7 × 7 grid masked to a disc. It redraws only while
 * something moves or cools; Done and Failed flash, settle and stop, and Reduce Motion shows each
 * state's still pose.
 *
 * @param source where its state comes from ([AgentActivitySource.Running] for work with no finer signal).
 * @param size the layout box. The orb is 2 dp narrower (4 dp from 24 dp up), and its glow spills past it.
 * @param isPaused hidden behind something that stands in for it: hold the pose, draw nothing new.
 */
@Composable
fun AgentActivityGlyph(
    source: AgentActivitySource,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    isPaused: Boolean = false,
) {
    val scope = LocalAgentActivityScope.current
    val localModel = remember { AgentActivityGlyphModel() }
    val key = source.animationKey
    val model = if (scope != null && key != null) remember(scope, key) { AgentActivityGlyphModels.model("$scope|$key") } else localModel
    val still = rememberReduceMotion() || rememberTouchExplorationEnabled()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // Hosted transcript rows also hold while the list's timelines are paused (covered, off screen,
    // not drawn).
    val active = lifecycle.isAtLeast(Lifecycle.State.RESUMED) && !LocalTranscriptTimelinePaused.current
    val dark = OpenCodeTheme.colors.isDark
    val lab = AgentActivityLab.override
    val currentSource by rememberUpdatedState(source)
    val currentLab by rememberUpdatedState(lab)
    // Bumped per drawn frame: only the Canvas's draw reads it, so frames never recompose.
    var tick by remember { mutableLongStateOf(0L) }
    var shown by remember { mutableStateOf(source.activity(AgentActivityClock.now(), lab)) }

    LaunchedEffect(model, isPaused, active, still) {
        fun draw(now: Double) {
            val previousField = model.frame.field
            val frame = model.advance(now, currentSource, currentLab, still)
            if (previousField == null || !still || !frame.resting || frame.activity != shown) tick += 1
            if (frame.activity != shown) shown = frame.activity
        }
        // Hidden or in the background it stops; resuming steps on from the held pose (each step
        // is at most 1/30 s), so nothing jumps.
        if (isPaused || !active) {
            // Held: the pose as it stands, redrawn only when the source changes.
            snapshotFlow { currentSource to currentLab }.collect { draw(AgentActivityClock.now()) }
            return@LaunchedEffect
        }
        var lastDrawn = Double.NEGATIVE_INFINITY
        while (true) {
            val src = currentSource
            val l = currentLab
            val now = AgentActivityClock.now()
            val current = src.activity(now, l)
            val resting = model.rests(current)
            if (current.isFinal && resting) {
                // Done and Failed hold until the source changes.
                snapshotFlow { currentSource to currentLab }.first { it != (src to l) }
                continue
            }
            if (still && resting) {
                // Every state holds a still pose; a slow tick still notices the next state.
                delay(500)
                draw(AgentActivityClock.now())
                continue
            }
            withFrameNanos { }
            val frameNow = AgentActivityClock.now()
            if (frameNow - lastDrawn < GLYPH_FRAME_INTERVAL - 0.002) continue
            lastDrawn = frameNow
            draw(frameNow)
        }
    }

    val label = shown.label
    val isFinal = shown.isFinal
    val runningDescription = stringResource(R.string.session_activity_running_description)
    Canvas(
        modifier
            .size(size)
            .semantics {
                contentDescription = if (isFinal) label else runningDescription
                stateDescription = if (isFinal) "" else label
            },
    ) {
        tick
        drawAgentActivityOrb(model.frame.field, dark = dark)
    }
}

@Composable
internal fun rememberTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        if (manager == null) return@DisposableEffect onDispose {}
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        enabled = manager.isTouchExplorationEnabled
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}
