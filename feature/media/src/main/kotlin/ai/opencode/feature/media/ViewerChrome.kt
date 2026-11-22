package ai.opencode.feature.media

import ai.opencode.core.R as CoreR
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.SFSymbol
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** What the chrome shows around a gallery page. */
internal class ViewerChromeModel(
    val title: String?,
    val detail: String?,
    val index: Int,
    val count: Int,
    val movie: Player?,
    val showsActions: Boolean,
    val removes: Boolean,
    val removeEnabled: Boolean,
    val autoHides: Boolean,
    val onClose: () -> Unit,
    val onRemove: () -> Unit,
    val onToggle: () -> Unit,
    val actions: @Composable () -> Unit,
)

/**
 * Bars on soft gradients that come and go together on a tap. [fadeSeconds] is set while the
 * presentation hides the chrome for a flight, so it fades on the flight's own timing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoxScope.ViewerChrome(visible: Boolean, fadeSeconds: Double?, model: ViewerChromeModel) {
    val reduceMotion = rememberReduceMotion()
    val safe = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
    val fadeIn = fadeIn(MotionRole.Swap.animation(MotionPhase.Enter, reduceMotion))
    val fadeOut = if (fadeSeconds != null) fadeOut(tween(Motion.millis(fadeSeconds))) else fadeOut(MotionRole.Swap.animation(MotionPhase.Exit, reduceMotion))
    val slides = fadeSeconds == null && !reduceMotion
    val slideIn = MotionRole.PanelOpen.animation<IntOffset>(MotionPhase.Enter, reduceMotion)
    val slideOut = MotionRole.PanelClose.animation<IntOffset>(MotionPhase.Exit, reduceMotion)
    val topEnter = if (slides) fadeIn + slideInVertically(slideIn) { -it / 8 } else fadeIn
    val topExit = if (slides) fadeOut + slideOutVertically(slideOut) { -it / 8 } else fadeOut
    val bottomEnter = if (slides) fadeIn + slideInVertically(slideIn) { it / 8 } else fadeIn
    val bottomExit = if (slides) fadeOut + slideOutVertically(slideOut) { it / 8 } else fadeOut

    val movie = model.movie
    var touches by remember { mutableIntStateOf(0) }
    if (movie != null && model.autoHides) MovieAutoHide(movie, visible, touches, model.onToggle)

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        AnimatedVisibility(visible, enter = topEnter, exit = topExit, modifier = Modifier.align(Alignment.TopCenter)) {
            ChromeTop(model, safe)
        }
        AnimatedVisibility(visible, enter = bottomEnter, exit = bottomExit, modifier = Modifier.align(Alignment.BottomCenter)) {
            ChromeBottom(model, safe) { touches += 1 }
        }
        if (movie != null) {
            AnimatedVisibility(visible, enter = fadeIn, exit = fadeOut, modifier = Modifier.align(Alignment.Center)) {
                MoviePlayButton(movie) { touches += 1 }
            }
        }
    }
}

/** While a video plays, the chrome steps aside after a few seconds untouched, and comes back when it stops. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun MovieAutoHide(player: Player, visible: Boolean, touches: Int, onToggle: () -> Unit) {
    val play = rememberPlayPauseButtonState(player)
    val toggle by rememberUpdatedState(onToggle)
    val playing = !play.showPlay
    LaunchedEffect(visible, playing, touches) {
        if (visible && playing) {
            delay(Motion.millis(3.0).toLong())
            toggle()
        }
    }
    var wasPlaying by remember { mutableStateOf(playing) }
    LaunchedEffect(playing) {
        if (wasPlaying && !playing && !visible) toggle()
        wasPlaying = playing
    }
}

private val scrimTop = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.62f), Color.Black.copy(alpha = 0.28f), Color.Transparent))
private val scrimBottom = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.32f), Color.Black.copy(alpha = 0.7f)))

@Composable
private fun ChromeTop(model: ViewerChromeModel, safe: WindowInsets) {
    Box(Modifier.fillMaxWidth().background(scrimTop)) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(safe).padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenCodeIconButton(SFSymbol.ChevronBackward, stringResource(CoreR.string.common_back), model.onClose,
                Modifier.testTag("image-gallery-close"), style = OpenCodeIconButtonStyle.Standard)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                model.title?.let { title ->
                    Text(title, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                        style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium), modifier = Modifier.testTag("image-gallery-title"))
                }
                model.detail?.let { detail ->
                    Text(detail, maxLines = 1, overflow = TextOverflow.StartEllipsis, color = Color.White.copy(alpha = 0.7f),
                        style = OpenCodeTextStyles.caption1, modifier = Modifier.testTag("image-gallery-detail"))
                }
            }
        }
    }
}

@Composable
private fun ChromeBottom(model: ViewerChromeModel, safe: WindowInsets, onTouch: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(scrimBottom).windowInsetsPadding(safe).padding(top = 36.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        model.movie?.let { MovieSeekBar(it, onTouch) }
        if (model.count > 1) PageDots(model.index, model.count, Modifier.padding(top = 8.dp, bottom = 4.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (model.showsActions) model.actions()
            if (model.removes) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.error) {
                    OpenCodeIconButton(SFSymbol.Trash, stringResource(CoreR.string.common_remove), model.onRemove,
                        Modifier.testTag("image-gallery-remove"), style = OpenCodeIconButtonStyle.Standard, enabled = model.removeEnabled)
                }
            }
        }
    }
}

/** The selected dot stretches into a bar; long galleries show a count. Each page already reads its place to TalkBack. */
@Composable
private fun PageDots(index: Int, count: Int, modifier: Modifier) {
    if (count > 9) {
        Text(stringResource(R.string.media_viewer_page_counter, index + 1, count), color = Color.White.copy(alpha = 0.8f),
            style = OpenCodeTextStyles.caption1.copy(fontFeatureSettings = "tnum"), modifier = modifier.clearAndSetSemantics { })
        return
    }
    val reduceMotion = rememberReduceMotion()
    Row(modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (dot in 0 until count) {
            val selected = dot == index
            val width by animateDpAsState(if (selected) 16.dp else 6.dp, MotionRole.Layout.animation(MotionPhase.Enter, reduceMotion), label = "dot")
            val color by animateColorAsState(if (selected) Color.White else Color.White.copy(alpha = 0.38f),
                MotionRole.Swap.animation(MotionPhase.Enter, reduceMotion), label = "dotColor")
            Box(Modifier.size(width = width, height = 6.dp).clip(CircleShape).background(color))
        }
    }
}

/** A large glyph on a soft shadow rather than a disc, so it reads over any frame. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun MoviePlayButton(player: Player, onTouch: () -> Unit) {
    val play = rememberPlayPauseButtonState(player)
    val description = stringResource(if (play.showPlay) R.string.media_player_play else R.string.media_player_pause)
    Box(
        Modifier
            .size(88.dp)
            .background(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.4f), Color.Black.copy(alpha = 0.18f), Color.Transparent)), CircleShape)
            .clip(CircleShape)
            .clickable(enabled = play.isEnabled, interactionSource = remember { MutableInteractionSource() }, indication = ripple()) {
                play.onClick()
                onTouch()
            }
            .semantics { contentDescription = description }
            .testTag("image-gallery-video-play"),
        contentAlignment = Alignment.Center,
    ) {
        OpenCodeIcon(if (play.showPlay) SFSymbol.PlayFill else SFSymbol.PauseFill, contentDescription = null,
            modifier = Modifier.size(52.dp), tint = Color.White)
    }
}

/**
 * A thin line that thickens under the finger, with a thumb that grows while held and the time
 * over it. Scrubbing pauses and turns on ExoPlayer's scrubbing mode, so frames follow the finger.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun MovieSeekBar(player: Player, onTouch: () -> Unit) {
    val progress = rememberProgressStateWithTickInterval(player, 100L)
    val play = rememberPlayPauseButtonState(player)
    val reduceMotion = rememberReduceMotion()
    val duration = progress.durationMs.coerceAtLeast(0)
    var scrub by remember(player) { mutableStateOf<Float?>(null) }
    var resumes by remember(player) { mutableStateOf(false) }
    var width by remember { mutableFloatStateOf(0f) }
    val fraction = scrub ?: if (duration > 0) (progress.currentPositionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val buffered = if (duration > 0) (player.bufferedPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val held = scrub != null
    val track by animateDpAsState(if (held) 6.dp else 3.dp, MotionRole.Press.animation(MotionPhase.Enter, reduceMotion), label = "track")
    val thumb by animateDpAsState(if (held) 18.dp else 12.dp, MotionRole.Press.animation(MotionPhase.Enter, reduceMotion), label = "thumb")

    fun begin() {
        if (scrub != null) return
        onTouch()
        resumes = !play.showPlay
        player.pause()
        (player as? ExoPlayer)?.isScrubbingModeEnabled = true
    }
    fun move(x: Float) {
        if (width <= 0f || duration <= 0) return
        val value = (x / width).coerceIn(0f, 1f)
        scrub = value
        player.seekTo((value * duration).toLong())
    }
    fun end() {
        val value = scrub ?: return
        player.seekTo((value * duration).toLong())
        (player as? ExoPlayer)?.isScrubbingModeEnabled = false
        if (resumes) player.play()
        scrub = null
        onTouch()
    }

    val positionDescription = stringResource(R.string.media_video_position_description)
    val positionText = timeText((fraction * duration).toLong())
    val durationText = timeText(duration)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Box(Modifier.fillMaxWidth().height(28.dp)) {
            if (held) {
                Text(positionText,
                    style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                    modifier = Modifier.align(Alignment.BottomStart).offset {
                        IntOffset((fraction * width - 18.dp.toPx()).roundToInt().coerceAtLeast(0), 0)
                    })
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .onSizeChanged { width = it.width.toFloat() }
                .testTag("image-gallery-video-position")
                .semantics {
                    contentDescription = positionDescription
                    stateDescription = "$positionText / $durationText"
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    if (duration > 0) setProgress { value ->
                        player.seekTo((value.coerceIn(0f, 1f) * duration).toLong())
                        true
                    }
                }
                .pointerInput(player, duration) {
                    detectTapGestures(onPress = { offset ->
                        begin()
                        move(offset.x)
                        tryAwaitRelease()
                        end()
                    })
                }
                .pointerInput(player, duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> begin(); move(offset.x) },
                        onHorizontalDrag = { change, _ -> change.consume(); move(change.position.x) },
                        onDragEnd = { end() },
                        onDragCancel = { end() },
                    )
                },
        ) {
            val h = track.toPx()
            val y = size.height / 2
            val corner = CornerRadius(h / 2, h / 2)
            val played = size.width * fraction
            drawRoundRect(Color.White.copy(alpha = 0.24f), Offset(0f, y - h / 2), Size(size.width, h), corner)
            if (buffered > fraction) {
                drawRoundRect(Color.White.copy(alpha = 0.4f), Offset(0f, y - h / 2), Size(size.width * buffered, h), corner)
            }
            drawRoundRect(Color.White, Offset(0f, y - h / 2), Size(played, h), corner)
            val t = thumb.toPx()
            drawCircle(Color.Black.copy(alpha = 0.18f), t / 2 + 1.5.dp.toPx(), Offset(played, y + 0.5.dp.toPx()))
            drawCircle(Color.White, t / 2, Offset(played, y))
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            TimeLabel(positionText, Modifier.weight(1f))
            TimeLabel(durationText, Modifier)
        }
    }
}

@Composable
private fun TimeLabel(text: String, modifier: Modifier) {
    Text(text, color = Color.White.copy(alpha = 0.85f),
        style = OpenCodeTextStyles.caption1.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"), modifier = modifier)
}

private fun timeText(ms: Long): String {
    val seconds = ms / 1000
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
