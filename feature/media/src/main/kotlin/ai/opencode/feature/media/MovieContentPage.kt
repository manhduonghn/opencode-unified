package ai.opencode.feature.media

import android.graphics.Bitmap
import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.roundToInt

/** Only the selected local page owns a player. For a remote page borrow the file loader's player. */
@Composable
internal fun rememberLocalMoviePlayer(uri: Uri): ExoPlayer? {
    val context = LocalContext.current
    var player by remember(context, uri) { mutableStateOf<ExoPlayer?>(null) }
    DisposableEffect(context, uri) {
        val local = if (uri.scheme == "file" || uri.scheme == "content") {
            ExoPlayer.Builder(context).setHandleAudioBecomingNoisy(true).build().apply {
                setAudioAttributes(AudioAttributes.DEFAULT, true)
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                play()
            }
        } else null
        player = local
        onDispose { local?.release() }
    }
    return player
}

/** No viewer controls here. Only a first frame on this surface enables the host's Draw action. */
@Composable
internal fun MovieContentPage(
    player: Player,
    label: String,
    onTap: () -> Unit,
    onZoomChange: (Boolean) -> Unit,
    onFrame: (Rect) -> Unit,
    onCaptureChanged: ((() -> Bitmap?)?) -> Unit,
    modifier: Modifier = Modifier,
    isHidden: Boolean = false,
) = key(player) {
    var texture by remember { mutableStateOf<TextureView?>(null) }
    var rendered by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(player.videoSize) }
    var failed by remember { mutableStateOf(player.playerError != null) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) { size = videoSize }
            override fun onRenderedFirstFrame() { rendered = true }
            override fun onSurfaceSizeChanged(width: Int, height: Int) {
                if (width == 0 || height == 0) rendered = false
            }
            override fun onPlayerError(error: PlaybackException) { failed = true }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val pixels = if (size.width > 0 && size.height > 0) {
        Size(size.width * size.pixelWidthHeightRatio, size.height.toFloat())
    } else Size.Zero
    val ready = rendered && !failed && texture != null && pixels != Size.Zero
    val registerCapture by rememberUpdatedState(onCaptureChanged)
    DisposableEffect(ready, texture, pixels) {
        var mounted = true
        if (ready) registerCapture {
            val view = texture
            if (!mounted || !rendered || failed || view?.isAvailable != true) null else {
                player.pause()
                // Copy the decoded sample before its layout transform, never a poster,
                // screenshot or approximate seek via a second network connection.
                val scale = minOf(1f, PromptImageFormat.MAX_PIXEL_SIZE / maxOf(pixels.width, pixels.height))
                try {
                    view.getBitmap((pixels.width * scale).roundToInt().coerceAtLeast(1),
                        (pixels.height * scale).roundToInt().coerceAtLeast(1))
                } catch (_: IllegalStateException) { null }
            }
        } else registerCapture(null)
        onDispose { mounted = false; registerCapture(null) }
    }
    // Give the player a surface before it knows the video's dimensions.
    val visualSize = pixels.takeUnless { it == Size.Zero } ?: Size(16f, 9f)
    Box(modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        ZoomableVisualPage(visualSize, label, true, onSingleTap = onTap, onZoomChange = onZoomChange, onFrame = onFrame) { zoom ->
            AndroidView(
                factory = { TextureView(it).also { view -> texture = view; player.setVideoTextureView(view) } },
                onRelease = { view -> player.clearVideoTextureView(view); texture = null; rendered = false },
                modifier = Modifier.layout { measurable, constraints ->
                    val fit = MediaZoomGeometry.fittedSize(visualSize, Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()))
                    val child = measurable.measure(Constraints.fixed(fit.width.roundToInt().coerceAtLeast(1), fit.height.roundToInt().coerceAtLeast(1)))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        child.placeWithLayer(0, 0) {
                            transformOrigin = TransformOrigin(0f, 0f)
                            scaleX = zoom.zoomScale
                            scaleY = zoom.zoomScale
                            translationX = zoom.imageFrame.left
                            translationY = zoom.imageFrame.top
                            alpha = if (isHidden) 0f else 1f
                        }
                    }
                },
            )
        }
        if (failed) Text(stringResource(R.string.media_video_play_failed), color = Color.White)
    }
}
