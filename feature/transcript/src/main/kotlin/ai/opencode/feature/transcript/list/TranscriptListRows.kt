package ai.opencode.feature.transcript.list

import ai.opencode.core.R as CoreR
import ai.opencode.core.motion.AmbientClock
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.MotionTunables
import ai.opencode.core.motion.motionBlurReveal
import ai.opencode.core.motion.motionFrameLoop
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.R
import ai.opencode.server.model.OpenCodeQueuedInput
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay

/** Earlier history: a delayed spinner while paging, the error with Retry, else a 1 dp spacer (iOS `TranscriptHistoryStatusView`). */
@Composable
fun TranscriptHistoryStatusView(isLoading: Boolean, error: String?, retry: () -> Unit, modifier: Modifier = Modifier) {
    val loadingDescription = stringResource(R.string.transcript_history_loading_description)
    Column(
        modifier.fillMaxWidth().padding(vertical = TranscriptMetrics.standard),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered),
    ) {
        when {
            // Every open pages once to learn whether history exists; healthy round trips don't flash activity.
            isLoading -> DelayedProgressView(Modifier.semantics { contentDescription = loadingDescription })
            error != null -> {
                Text(error, style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText, textAlign = TextAlign.Center)
                TextButton(onClick = retry, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(CoreR.string.common_retry), style = OpenCodeTextStyles.footnote)
                }
            }
            else -> Spacer(Modifier.height(1.dp).clearAndSetSemantics { invisibleToUser() })
        }
    }
}

/** A spinner that only appears once a wait has lasted long enough to notice (iOS `DelayedProgressView`, 400 ms). */
@Composable
fun DelayedProgressView(modifier: Modifier = Modifier, delayMillis: Long = 400) {
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMillis)
        isVisible = true
    }
    CircularProgressIndicator(modifier.size(20.dp).alpha(if (isVisible) 1f else 0f), strokeWidth = 2.dp)
}

/** Tunables for the empty and loading session state (iOS `EmptyStateTunables`), baked defaults. */
object EmptyStateTunables {
    /** The Tuning Panel sections it registers (the key segment after the group), in order. */
    val sectionTitles = listOf("Empty")

    const val breathMinOpacity = 0.45
    const val breathMinScale = 0.94f
    const val reducedLoadingOpacity = 0.5
    const val glyphSize = 48f
    const val glyphSpacing = 14f
    const val subtitleSpacing = 6f
    const val verticalOffset = 0f
}

/**
 * How long a load waits before the mark shows (iOS `MarkLoadingTunables.appearDelay`). Its fade
 * is `Motion.swap`'s.
 */
object MarkLoadingTunables {
    const val appearDelay = 0.3
}

/**
 * Presentation time only (iOS `MarkLoadingTiming`). A null start is a cached transcript: no
 * loading treatment. Times in seconds on the list's clock.
 */
data class MarkLoadingTiming(
    val start: Double?,
    val delay: Double = MarkLoadingTunables.appearDelay,
    /** The mark arrives on `Motion.swap`'s entrance. */
    val fadeIn: Double = MotionTunables.swapInDuration.value,
) {
    fun opacity(now: Double): Double {
        val start = start ?: return 0.0
        val progress = min(1.0, max(0.0, (now - start - delay) / fadeIn))
        return progress * progress * (3 - 2 * progress)
    }

    fun isSettled(now: Double): Boolean {
        val start = start ?: return true
        return now >= start + delay + fadeIn
    }
}

/** A cosine pulse from full down to the breath trough and back; Reduce Motion holds a steady dim (iOS `LoadingGlyphPulse`). */
object LoadingGlyphPulse {
    fun excursion(elapsed: Double?): Double {
        val e = elapsed ?: return 0.0
        val period = AmbientClock.breathPeriod
        return 0.5 - 0.5 * cos(max(0.0, e) * 2 * PI / period)
    }

    fun opacity(elapsed: Double?, reducedMotion: Boolean): Double {
        if (reducedMotion) return EmptyStateTunables.reducedLoadingOpacity
        return 1 - excursion(elapsed) * (1 - EmptyStateTunables.breathMinOpacity)
    }

    fun scale(elapsed: Double?, reducedMotion: Boolean): Float {
        if (reducedMotion) return 1f
        return 1 - excursion(elapsed).toFloat() * (1 - EmptyStateTunables.breathMinScale)
    }
}

/** The list's monotonic clock in seconds (iOS `AnimationTimeScale.referenceNow`). */
fun transcriptNow(): Double = ai.opencode.core.tuning.AnimationTimeScale.shared.now()

/** The pulse runs only while a load shows on screen; under Reduce Motion it stops once in (iOS `MarkLoadingView.isPaused`). */
internal fun markLoadingIsPaused(loadingSince: Double?, isHidden: Boolean, settled: Boolean, reducedMotion: Boolean): Boolean =
    isHidden || loadingSince == null || (settled && reducedMotion)

/**
 * The chat-bubbles glyph, pulsing while waiting inside the delayed fade (iOS `MarkLoadingView`).
 * [isHidden] pauses it (a background scene, a hidden list, an off-screen row).
 */
@Composable
fun MarkLoadingView(loadingSince: Double?, isHidden: Boolean = false, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val timing = remember(loadingSince) { MarkLoadingTiming(loadingSince) }
    var now by remember { mutableDoubleStateOf(transcriptNow()) }
    var settled by remember(loadingSince) { mutableStateOf(false) }
    val paused = markLoadingIsPaused(loadingSince, isHidden, settled, reduceMotion)
    LaunchedEffect(paused, loadingSince) {
        if (paused) return@LaunchedEffect
        motionFrameLoop {
            now = transcriptNow()
            if (timing.isSettled(now)) settled = true
            MotionFrameAction.Continue
        }
    }
    val glyph = with(LocalDensity.current) { (EmptyStateTunables.glyphSize * fontScale).dp }
    OpenCodeIcon(
        "bubble.left.and.bubble.right",
        contentDescription = null,
        modifier = modifier.size(glyph).graphicsLayer {
            val elapsed = loadingSince?.let { now - it - timing.delay }
            val scale = LoadingGlyphPulse.scale(elapsed, reduceMotion)
            scaleX = scale
            scaleY = scale
            alpha = (timing.opacity(now) * LoadingGlyphPulse.opacity(elapsed, reduceMotion)).toFloat()
        },
        tint = OpenCodeTheme.colors.secondaryText,
    )
}

/**
 * The empty and loading session state (iOS `TranscriptEmptyView`): the pulsing mark while a load
 * shows, the bubble glyph above “No messages yet” once it settles empty, revealed with
 * [motionBlurReveal] on the reveal spring (subtitle 0.04 s later). Centred in the fitted viewport
 * height by the list.
 */
@Composable
fun TranscriptEmptyView(
    isLoading: Boolean,
    showsEmptyState: Boolean,
    loadingSince: Double?,
    pulsePaused: Boolean,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val showsText = !isLoading && showsEmptyState
    val phase = if (showsText) MotionPhase.Enter else MotionPhase.Exit
    val target = if (showsText) 1f else 0f
    val title by animateFloatAsState(target, Motion.layout.animation(phase, reduceMotion))
    val subtitleDelay = if (showsText) MotionRole.staggerDelay(1) else 0.0
    val subtitle by animateFloatAsState(target, Motion.layout.animation(phase, reduceMotion, subtitleDelay))
    val loadingSessionDescription = stringResource(R.string.transcript_empty_loading_description)
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(vertical = TranscriptMetrics.standard),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!showsText) {
                MarkLoadingView(
                    loadingSince = loadingSince,
                    isHidden = pulsePaused,
                    modifier = Modifier.semantics { if (isLoading) contentDescription = loadingSessionDescription else invisibleToUser() },
                )
            } else {
                val glyph = with(LocalDensity.current) { (EmptyStateTunables.glyphSize * fontScale).dp }
                OpenCodeIcon(
                    "bubble.left.and.bubble.right",
                    contentDescription = null,
                    modifier = Modifier.size(glyph),
                    tint = OpenCodeTheme.colors.secondaryText,
                )
            }
            Spacer(Modifier.height(EmptyStateTunables.glyphSpacing.dp))
            Text(
                stringResource(R.string.transcript_empty_title),
                modifier = Modifier.motionBlurReveal(title, MotionTunables.blurPanel.value, MotionTunables.entranceRise.value, reduceMotion)
                    .then(if (!showsText) Modifier.clearAndSetSemantics { } else Modifier),
                style = OpenCodeTextStyles.title2.copy(fontWeight = FontWeight.Bold),
                color = OpenCodeTheme.colors.text,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(EmptyStateTunables.subtitleSpacing.dp))
            Text(
                stringResource(R.string.transcript_empty_subtitle),
                modifier = Modifier.motionBlurReveal(subtitle, MotionTunables.blurPanel.value, MotionTunables.entranceRise.value, reduceMotion)
                    .then(if (!showsText) Modifier.clearAndSetSemantics { } else Modifier),
                style = OpenCodeTextStyles.subheadline,
                color = OpenCodeTheme.colors.secondaryText,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Pending compaction (iOS `TranscriptQueuedInputView`); plain pending messages render through the
 * user message row, and pending notices fold into Used N groups.
 */
@Composable
fun TranscriptQueuedInputView(input: OpenCodeQueuedInput, modifier: Modifier = Modifier) {
    if (!input.isCompaction) return
    val compactionQueued = stringResource(R.string.transcript_compaction_queued)
    // Admitted but not started; the durable row takes over while running.
    Row(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = compactionQueued },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TranscriptMetrics.standard),
    ) {
        val line = OpenCodeTheme.colors.border
        Box(Modifier.weight(1f).height(1.dp).background(line))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OpenCodeIcon("diamond", contentDescription = null, modifier = Modifier.size(11.dp), tint = OpenCodeTheme.colors.tertiaryText)
            Text(compactionQueued, style = OpenCodeTextStyles.caption1, color = OpenCodeTheme.colors.tertiaryText)
        }
        Box(Modifier.weight(1f).height(1.dp).background(line))
    }
}

/**
 * iOS `TranscriptTimelineGate`: the list's rows' running timelines (`InlineRowPulse`, the
 * session's `AgentActivityGlyph` and `WorkingLineRow`) stop whenever the reader can't see the
 * list. Plain text and code rows never read it.
 */
object TranscriptTimelineGate {
    /**
     * Paused whenever the reader can't see the list: the list off screen, the app in the
     * background, another screen over it, or the list not drawn.
     */
    fun pauses(listHidden: Boolean, sceneInBackground: Boolean, covered: Boolean, drawn: Boolean): Boolean =
        listHidden || sceneInBackground || covered || !drawn
}

/** Whether the list's row timelines are paused ([TranscriptTimelineGate]); false outside a list. */
val LocalTranscriptTimelinePaused = compositionLocalOf { false }
