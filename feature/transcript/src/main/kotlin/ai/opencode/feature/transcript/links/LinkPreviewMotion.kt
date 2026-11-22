package ai.opencode.feature.transcript.links

import ai.opencode.core.motion.MotionTunables
import ai.opencode.core.motion.motionBlurReveal
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.feature.media.LinkPreview
import ai.opencode.feature.media.LinkPreviewStore
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.list.MessageRow
import ai.opencode.feature.transcript.list.TranscriptItemID
import ai.opencode.feature.transcript.list.TranscriptListSnapshot
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import kotlin.math.max
import kotlin.math.min

/** iOS `AnimationTimeScale.shared.referenceNow()`: seconds on the frame clock's timebase. */
internal fun referenceNow(): Double = System.nanoTime() / 1e9

/**
 * Lives with the transcript, not a row's composition (iOS `LinkPreviewRowPresentation`): the
 * list's `LinkPreviewStore` keeps one per row (`presentation(row) { LinkPreviewRowPresentation() }`).
 * Recomposing or disposing a row must neither replay an arrival nor lose the pixels from before
 * metadata landed.
 */
class LinkPreviewRowPresentation {
    data class Layer(val preview: LinkPreview, val opacity: Double)

    data class Change(
        val preview: LinkPreview,
        val previous: List<Layer>,
        val started: Double,
        val duration: Double,
    ) {
        val progress: Double get() = progress(referenceNow())

        /** Eased out, as `Motion.swap` fades. */
        fun progress(now: Double): Double {
            val t = min(1.0, max(0.0, (now - started) / max(0.01, duration)))
            return 1 - (1 - t) * (1 - t)
        }

        fun layers(now: Double): List<Layer> {
            val p = progress(now)
            return previous.map { Layer(it.preview, it.opacity * (1 - p)) }.filter { it.opacity > 0 } +
                (if (p > 0) listOf(Layer(preview, p)) else emptyList())
        }
    }

    var arrives: Boolean = false
    private val changes = HashMap<String, Change>()

    fun change(to: LinkPreview, now: Double = referenceNow(), duration: Double): Change {
        val old = changes[to.id]
        if (old != null && old.preview == to) return old
        val change = Change(
            preview = to,
            previous = old?.layers(now) ?: emptyList(),
            started = if (old != null || arrives) now else Double.NEGATIVE_INFINITY,
            duration = duration,
        )
        changes[to.id] = change
        return change
    }
}

/** One clock for the pixels only (iOS `ArrivingLinkPreview`): layout takes its final size at once. */
@Composable
internal fun ArrivingLinkPreview(preview: LinkPreview, presentation: LinkPreviewRowPresentation) {
    // A preview materialises on `Motion.swap`, out of a subtle blur.
    val change = presentation.change(preview, duration = MotionTunables.swapInDuration.value)
    Box {
        LinkPreviewButton(preview) {
            LinkPreviewMaterialisation(change)
        }
    }
}

/**
 * iOS `TranscriptListViewController+Snapshots`: the reply end the Copy handoff brings as a run
 * finishes is already drawn, but its links are genuinely new. Marks its presentation `arrives`,
 * so they fade in and grow like an image that loaded. Fed every list update from composition,
 * after the controller applied it; a plain object kept by the list.
 */
class LinkPreviewArrivals(private val store: LinkPreviewStore) {
    private var lastSnapshot: TranscriptListSnapshot? = null
    private var wasRunning = false

    fun update(snapshot: TranscriptListSnapshot, isRunning: Boolean, hasLaidOutContent: Boolean) {
        val previous = lastSnapshot
        if (previous === snapshot) return
        lastSnapshot = snapshot
        val previouslyRunning = wasRunning
        wasRunning = isRunning
        if (previous == null || !hasLaidOutContent || !previouslyRunning) return
        val old = previous.items.mapTo(HashSet()) { it.key }
        for (item in snapshot.items) {
            if (item.key in old || item !is TranscriptItemID.Message) continue
            val entry = (snapshot.rows[item.stableID] as? MessageRow.Entry)?.entry as? TranscriptEntry.ReplyEnd ?: continue
            val blocks = entry.texts.flatMap { OpenCodeMarkdownBlock.blocks(it) }.linkTextBlocks()
            if (store.targets(blocks, entry.id).isEmpty()) continue
            store.presentation(item.stableID) { LinkPreviewRowPresentation() }.arrives = true
        }
    }
}

@Composable
private fun LinkPreviewMaterialisation(change: LinkPreviewRowPresentation.Change) {
    // A new change wakes the clock; idle rows keep their resting pixels.
    key(change.started) {
        var progress by remember { mutableDoubleStateOf(change.progress) }
        LaunchedEffect(Unit) {
            while (progress < 1) {
                withFrameNanos { progress = change.progress(it / 1e9) }
            }
        }
        LinkPreviewEntry(change.preview, change = change, progress = progress)
    }
}

/**
 * Only replaced text fades (iOS `LinkPreviewTextTransition`). A favicon-only update must leave
 * its title and host fully opaque and sharp, including when updates interrupt each other.
 */
@Composable
internal fun LinkPreviewTextTransition(
    current: AnnotatedString?,
    previous: List<Pair<AnnotatedString?, Double>>,
    progress: Double,
    modifier: Modifier = Modifier,
    style: TextStyle,
    maxLines: Int,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    trailing: Boolean = false,
    softWrap: Boolean = true,
) {
    val reduceMotion = rememberReduceMotion()
    val shared = previous.filter { it.first == current }.sumOf { it.second }
    val opacity = progress + shared * (1 - progress)
    val overlays = if (progress < 1) previous.filter { it.first != current && it.first != null } else emptyList()
    // The current label alone owns layout. Overlays never negotiate a second width or scale the
    // text; their leading/trailing anchor belongs to the slot.
    Layout(
        content = {
            Text(
                current ?: AnnotatedString(""),
                Modifier.motionBlurReveal(opacity.toFloat(), maxBlur = MotionTunables.blurSubtle.value, reduceMotion = reduceMotion),
                style = style, maxLines = maxLines, overflow = overflow, softWrap = softWrap,
            )
            for ((text, layerOpacity) in overlays) {
                Text(
                    text!!,
                    Modifier
                        .graphicsLayer { alpha = layerOpacity.toFloat() }
                        .motionBlurReveal((1 - progress).toFloat(), maxBlur = MotionTunables.blurSubtle.value, reduceMotion = reduceMotion)
                        .clearAndSetSemantics {},
                    style = style, maxLines = maxLines, overflow = overflow, softWrap = softWrap,
                )
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val main = measurables.first().measure(constraints)
        // Proposed the slot's width, as tall as they need (`fixedSize(horizontal: false, vertical: true)`).
        val layers = measurables.drop(1).map { it.measure(Constraints(maxWidth = main.width)) }
        layout(main.width, main.height) {
            main.place(0, 0)
            for (layer in layers) layer.place(if (trailing) main.width - layer.width else 0, 0)
        }
    }
}
