package ai.opencode.feature.voice

import ai.opencode.core.R as CoreR
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/**
 * Where the card's or sheet's content is laid out this frame (dp): its size, how far it is laid
 * out as the full sheet, and the bloom's centre and radius there.
 *
 * @param fullness 0 laid out as the card, 1 as the full sheet.
 * @param lineWidth the card's width: the transcript keeps it on the sheet too, so its lines never
 * re-wrap as the shape grows or shrinks.
 */
data class FloatingCallStageContentLayout(
    val width: Float,
    val height: Float,
    val fullness: Float,
    val bloom: FloatingCallStageGeometry.Bloom,
    val lineWidth: Float = width,
)

/**
 * What the card and the full sheet show, laid out at the size it's given: the session and duration
 * above the bloom, the call's details (full sheet only) and status below it, the live transcript,
 * and the controls. The shape's surface and dot field are drawn by the floating call's surface.
 * Sizes in dp.
 *
 * [layout] is read while measuring and drawing, not composing: a drag between the card and the
 * sheet moves and resizes it every frame without recomposing anything inside it.
 */
@Composable
fun FloatingCallStageContent(
    store: VoiceStoreOf,
    layout: () -> FloatingCallStageContentLayout,
    status: String,
    onMinimize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentLayout by rememberUpdatedState(layout)
    val detailsHidden by remember { derivedStateOf { currentLayout().fullness < 0.5f } }
    val details by store.observe { "${it.resolvedEngine.title} · ${it.directoryName}" }
    Layout(
        content = {
            // It drags: up to the sheet, down to the orb.
            Box(
                Modifier
                    .size(36.dp, 5.dp)
                    .background(OpenCodeTheme.colors.tertiaryText, CircleShape)
                    .clearAndSetSemantics {},
            )
            FloatingCallStageHeader(store = store, status = status, onMinimize = onMinimize)
            Text(
                details,
                style = OpenCodeTextStyles.footnote,
                color = OpenCodeTheme.colors.tertiaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    // In once the status has moved down clear of it.
                    .graphicsLayer { alpha = smooth(0.7f, 1f, currentLayout().fullness) }
                    .then(if (detailsHidden) Modifier.semantics { hideFromAccessibility() } else Modifier),
            )
            Text(
                status,
                style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium),
                color = OpenCodeTheme.colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("floating-call-status"),
            )
            FloatingCallTranscript(store)
            FloatingCallStageControls(store = store, scale = { floatingCallControlDiameter(currentLayout().fullness) / CONTROL_DIAMETER })
        },
        modifier = modifier,
    ) { measurables, _ ->
        val now = currentLayout()
        val full = now.fullness
        val below = (now.bloom.y + now.bloom.radius).toFloat()
        val controlsBottom = lerp(14f, 40f, full)
        val row = max(44f, floatingCallControlDiameter(full))
        val lineWidth = max(0f, min(now.width, now.lineWidth) - 48)
        val width = now.width.dp.roundToPx().coerceAtLeast(0)
        val height = now.height.dp.roundToPx().coerceAtLeast(0)
        fun px(value: Float) = value.dp.roundToPx()
        fun widthOnly(value: Int) = Constraints(minWidth = value, maxWidth = value)
        val grabber = measurables[0].measure(Constraints())
        // At least a tap target tall, taller when large text needs it.
        val header = measurables[1].measure(Constraints(minWidth = width, maxWidth = width, minHeight = px(48f)))
        val detailsText = measurables[2].measure(widthOnly(width))
        val statusText = measurables[3].measure(widthOnly(width))
        // Below the bloom, then each line below the one above it, so large text pushes down
        // rather than overlapping.
        val detailsY = px(below + 10)
        val statusY = max(px(below + lerp(8f, 34f, full)), detailsY + (detailsText.height * full).toInt())
        val transcriptY = max(statusY + px(26f), statusY + statusText.height + px(6f))
        val transcriptHeight = max(0, px(now.height - controlsBottom - row - 12) - transcriptY)
        val transcript = measurables[4].measure(Constraints.fixed(px(lineWidth), transcriptHeight))
        val controlsWidth = max(0, width - px(20f))
        val controls = measurables[5].measure(Constraints.fixed(controlsWidth, px(CONTROL_DIAMETER)))
        layout(width, height) {
            grabber.place((width - grabber.width) / 2, px(6f))
            header.place(0, px(lerp(14f, 16f, full)))
            detailsText.place(0, detailsY)
            statusText.place(0, statusY)
            transcript.place((width - transcript.width) / 2, transcriptY)
            // The controls grow toward the sheet's size about their centre line.
            controls.place(px(10f), px(now.height - controlsBottom - row / 2) - controls.height / 2)
        }
    }
}

/** The card's control circles, and the sheet's, a little bigger. */
private const val CONTROL_DIAMETER = 54f

private fun floatingCallControlDiameter(fullness: Float): Float = lerp(CONTROL_DIAMETER, 62f, fullness)

@Composable
private fun FloatingCallStageHeader(store: VoiceStoreOf, status: String, onMinimize: () -> Unit, modifier: Modifier = Modifier) {
    val title by store.observe { it.sessionTitle }
    val connectedAt by store.observe { it.connectedAt }
    val minimizeDescription = stringResource(R.string.voice_stage_minimize_description)
    Row(
        modifier.padding(start = 18.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(stringResource(R.string.voice_stage_talking_in), style = OpenCodeTextStyles.caption1, color = OpenCodeTheme.colors.secondaryText)
            Text(
                if (title == VoiceFeature.State.newSessionTitle) stringResource(R.string.voice_session_new_title) else title,
                style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
                color = OpenCodeTheme.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FloatingCallDuration(connectedAt, status, Modifier.padding(end = 4.dp))
        Box(
            Modifier
                .size(48.dp)
                .clickable(role = Role.Button, onClick = onMinimize)
                .openCodeTapTarget()
                .semantics { contentDescription = minimizeDescription },
            contentAlignment = Alignment.Center,
        ) {
            OpenCodeIcon(SFSymbol.ChevronDown, null, tint = OpenCodeTheme.colors.secondaryText, size = 20.dp)
        }
    }
}

/** One row of equal circles on one baseline; names only for TalkBack. */
@Composable
private fun FloatingCallStageControls(store: VoiceStoreOf, scale: () -> Float, modifier: Modifier = Modifier) {
    val diameter = CONTROL_DIAMETER
    val isMuted by store.observe { it.isMuted }
    val isLive by store.observe { it.phase == VoiceFeature.State.Phase.Live }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        FloatingCallControl(
            name = if (isMuted) stringResource(R.string.voice_unmute) else stringResource(R.string.voice_mute),
            symbol = if (isMuted) SFSymbol.MicSlashFill else SFSymbol.MicFill,
            diameter = diameter,
            scale = scale,
            isOn = isMuted,
            isEnabled = isLive,
        ) { store.send(VoiceFeature.Action.MuteTapped) }
        FloatingCallControl(name = stringResource(R.string.voice_stage_pause), symbol = SFSymbol.PauseFill, diameter = diameter, scale = scale, isPlaceholder = true) {}
        FloatingCallControl(name = stringResource(R.string.voice_stage_camera), symbol = SFSymbol.VideoFill, diameter = diameter, scale = scale, isPlaceholder = true) {}
        FloatingCallControl(name = stringResource(CoreR.string.common_add), symbol = SFSymbol.Plus, diameter = diameter, scale = scale, isPlaceholder = true) {}
        FloatingCallControl(name = stringResource(R.string.voice_floating_end_description), symbol = SFSymbol.PhoneDownFill, diameter = diameter, scale = scale, isDestructive = true) {
            store.send(VoiceFeature.Action.EndPressed)
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun smooth(low: Float, high: Float, value: Float): Float {
    val t = min(1f, max(0f, (value - low) / (high - low)))
    return t * t * (3 - 2 * t)
}

/** Monotonic seconds: the clock the transcript's reveal runs on (and Compose's frame time). */
private fun transcriptNow(): Double = System.nanoTime() / 1e9

/**
 * The call so far, you and the agent, newest at the bottom: the column is anchored to the bottom
 * and older lines rise out under a fade. The agent's words arrive one by one as they stream
 * ([CaptionWordRenderer]). It never scrolls, so a vertical drag anywhere moves the sheet.
 */
@Composable
fun FloatingCallTranscript(store: VoiceStoreOf, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val targets by store.observe { it.captionTargets }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // Opening onto a call in progress shows what's there from the first frame.
    var reveal by remember {
        val opening = CaptionReveal().sync(store.state.captionTargets, revealed = true)
        mutableStateOf(opening.trim(force = true) ?: opening)
    }
    var now by remember { mutableDoubleStateOf(transcriptNow()) }
    val queueChanged = remember { Channel<Unit>(Channel.CONFLATED) }

    val animationEnd = reveal.lastReveal + CaptionWordRenderer.duration
    val isActive = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(animationEnd, isActive) {
        // Paused when settled or in the background; a paused timeline's time is stale, so
        // settled drawing reads the real time.
        now = transcriptNow()
        if (!animationEnd.isFinite() || !isActive) return@LaunchedEffect
        while (now < animationEnd) withFrameNanos { now = it / 1e9 }
        now = transcriptNow()
        // Settled: the lines that have risen out of the column are dropped, so a long call
        // never re-lays out, or re-matches, everything said so far.
        reveal.trim()?.let { reveal = it }
    }
    // Reveals queued words for the call's lifetime, waking when lines change.
    LaunchedEffect(Unit) {
        queueChanged.trySend(Unit)
        for (signal in queueChanged) {
            while (true) {
                // Reads the state itself: a value remembered at composition goes stale while this
                // loop runs without suspending, and then it never ends.
                val wait = reveal.nextRevealDelay(transcriptNow()) ?: break
                if (wait > 0) delay((wait * 1000).roundToInt().toLong().coerceAtLeast(1))
                reveal = reveal.revealNext(transcriptNow())
            }
        }
    }
    val synced = remember { mutableStateOf(targets) }
    LaunchedEffect(targets) {
        if (synced.value == targets) return@LaunchedEffect
        synced.value = targets
        reveal = reveal.sync(targets)
        queueChanged.trySend(Unit)
    }

    // A line takes space from its first word, so lines still queued behind another leave no gap.
    val lines = reveal.lines.filter { it.revealedAt.isNotEmpty() }.takeLast(16)
    Box(
        modifier
            .clipToBounds()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fade = 24.dp.toPx()
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = fade),
                    blendMode = BlendMode.DstIn,
                )
            }
            .testTag("floating-call-transcript"),
        contentAlignment = Alignment.BottomStart,
    ) {
        // The column keeps its own height, hung from the bottom of the room it's given, and glides
        // up when it grows rather than jumping a line.
        val glide = remember { FloatingCallTranscriptGlide() }
        Column(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Bottom, unbounded = true)
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val measuredAt = transcriptNow()
                    glide.measured(placeable.height, measuredAt, glides = !reduceMotion && animationEnd - measuredAt >= FloatingCallTranscriptGlide.duration)
                    layout(placeable.width, placeable.height) {
                        placeable.placeWithLayer(0, 0) { translationY = glide.offset(now) }
                    }
                },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (lines.isEmpty()) {
                Text(stringResource(R.string.voice_transcript_empty), style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.secondaryText)
            }
            for (line in lines) {
                androidx.compose.runtime.key(line.id) {
                    Column {
                        if (line.isUser) {
                            Text(
                                stringResource(R.string.voice_transcript_you),
                                style = OpenCodeTextStyles.caption1.copy(fontWeight = FontWeight.SemiBold),
                                color = OpenCodeTheme.colors.tertiaryText,
                                modifier = Modifier.clearAndSetSemantics {},
                            )
                        }
                        FloatingCallCaptionLine(line, { now }, reduceMotion)
                    }
                }
            }
        }
    }
}

/**
 * The transcript column's rise: when a word wraps or a line starts, the column grows by a line
 * and, hung from the bottom, would jump up at once; instead it starts where it was and eases up
 * while the word fades in. A shrink (settled lines trimmed) is never glided.
 */
private class FloatingCallTranscriptGlide {
    private var height = -1
    private var from = 0f
    private var startedAt = Double.NEGATIVE_INFINITY

    fun measured(newHeight: Int, now: Double, glides: Boolean) {
        if (height >= 0 && newHeight > height && glides) {
            from = offset(now) + (newHeight - height)
            startedAt = now
        } else if (newHeight != height) {
            from = 0f
            startedAt = Double.NEGATIVE_INFINITY
        }
        height = newHeight
    }

    /** How far below its place the column is drawn at [now] (px). */
    fun offset(now: Double): Float {
        val progress = (now - startedAt) / duration
        if (progress >= 1) return 0f
        if (progress <= 0) return from
        val remaining = 1 - progress
        return from * (remaining * remaining * remaining).toFloat()
    }

    companion object {
        /** Shorter than a word's fade, so the reveal's frame clock outlasts it. */
        const val duration = 0.3
    }
}

/**
 * One line of the transcript, laid out as its words appear (one view per word), so an appended
 * word never moves an earlier one. TalkBack hears “You:” before yours.
 */
@Composable
private fun FloatingCallCaptionLine(line: CaptionReveal.Line, now: () -> Double, reduceMotion: Boolean) {
    val style = OpenCodeTextStyles.body
    val measurer = rememberTextMeasurer()
    val spaceWidth = remember(style, measurer) { measurer.measure(" ", style).size.width }
    val color = if (line.isUser) OpenCodeTheme.colors.secondaryText else OpenCodeTheme.colors.text
    val density = LocalDensity.current.density
    val composedAt = transcriptNow()
    val userLineDescription = stringResource(R.string.voice_transcript_user_line_description, line.text)
    Layout(
        modifier = Modifier.clearAndSetSemantics { contentDescription = if (line.isUser) userLineDescription else line.text },
        content = {
            for (index in line.revealedAt.indices) {
                val at = line.revealedAt[index]
                // Only words still revealing redraw frame by frame.
                val settled = at + CaptionWordRenderer.duration <= composedAt
                Text(
                    line.words[index],
                    style = style,
                    color = color,
                    modifier = if (settled) Modifier else Modifier.graphicsLayer {
                        val eased = CaptionWordRenderer.eased(at, now())
                        alpha = eased
                    },
                )
            }
        },
        measurePolicy = floatingCallWordFlow(spaceWidth),
    )
}

/** Words wrapped greedily, one view each: a word never moves once placed. */
private fun floatingCallWordFlow(spaceWidth: Int) = MeasurePolicy { measurables, constraints ->
    val width = constraints.maxWidth
    var x = 0
    var rowY = 0
    var rowHeight = 0
    val placed = measurables.map { measurable ->
        // Measured once, as Compose requires: a word wider than the line wraps within its own row.
        val placeable = measurable.measure(Constraints(maxWidth = width))
        if (x > 0 && x + spaceWidth + placeable.width > width) {
            rowY += rowHeight
            rowHeight = 0
            x = 0
        } else if (x > 0) {
            x += spaceWidth
        }
        val position = IntOffset(x, rowY)
        x += placeable.width
        rowHeight = max(rowHeight, placeable.height)
        placeable to position
    }
    val maxX = placed.maxOfOrNull { (p, pos) -> pos.x + p.width } ?: 0
    val maxY = placed.maxOfOrNull { (p, pos) -> pos.y + p.height } ?: 0
    layout(maxX.coerceIn(constraints.minWidth, constraints.maxWidth), maxY.coerceAtLeast(constraints.minHeight)) {
        for ((p, pos) in placed) p.place(pos.x, pos.y)
    }
}
