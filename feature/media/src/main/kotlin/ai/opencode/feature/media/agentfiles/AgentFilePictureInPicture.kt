package ai.opencode.feature.media

import ai.opencode.core.log.AppLog
import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import kotlin.math.roundToInt

/**
 * Picture in Picture for the file viewer's video. AVKit's behaviour, on Android's activity-level
 * PiP: the controls' button starts it; going home while a video plays starts it too; the small
 * window plays and pauses; closing it pauses; expanding it returns to the viewer. The viewer fills
 * the window, so in PiP the page shows only the video ([isActive]).
 *
 * A plain object the player composable owns: it keeps the activity's `PictureInPictureParams`
 * (aspect ratio, source rect, auto-enter, the play/pause action) in step with the player.
 */
@Stable
@androidx.annotation.OptIn(UnstableApi::class)
internal class AgentFilePictureInPicture(private val activity: ComponentActivity, private val player: Player) {
    /** The activity is in Picture in Picture: the page shows only the video. */
    var isActive by mutableStateOf(activity.isInPictureInPictureMode)
        private set

    /** Where the video sits in the window, for the system's enter animation. */
    var videoFrame: Rect? = null
        set(value) {
            if (field == value) return
            field = value
            update()
        }

    val isSupported: Boolean =
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private val action = "${activity.packageName}.agentfile.pip.playPause"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != action) return
            // As the viewer's own button: an ended video starts over (AVKit's play after the end).
            Util.handlePlayPauseButtonAction(player)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = update()
        override fun onPlaybackStateChanged(playbackState: Int) = update()
        override fun onVideoSizeChanged(videoSize: VideoSize) = update()
    }

    private val modeListener = Consumer<PictureInPictureModeChangedInfo> { info ->
        isActive = info.isInPictureInPictureMode
        AppLog.app.debug { "Agent file PiP ${if (info.isInPictureInPictureMode) "began" else "ended"}" }
        // Closed with its ×: the activity has stopped (expanding it leaves it started). AVKit pauses then.
        if (!info.isInPictureInPictureMode && !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            player.pause()
        }
    }

    fun attach() {
        player.addListener(listener)
        activity.addOnPictureInPictureModeChangedListener(modeListener)
        activity.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        update()
    }

    fun detach() {
        player.removeListener(listener)
        activity.removeOnPictureInPictureModeChangedListener(modeListener)
        runCatching { activity.unregisterReceiver(receiver) }
        // The activity's params outlive the page: nothing starts PiP once the video is gone.
        if (isSupported) {
            runCatching {
                activity.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).setActions(emptyList()).build())
            }
        }
    }

    /** The controls' button. */
    fun start() {
        if (!isSupported || isActive) return
        val started = runCatching { activity.enterPictureInPictureMode(params()) }.getOrDefault(false)
        if (!started) AppLog.app.notice { "Agent file PiP refused" }
    }

    private fun update() {
        if (!isSupported) return
        runCatching { activity.setPictureInPictureParams(params()) }
            .onFailure { AppLog.app.notice { "Agent file PiP params: ${it::class.java.simpleName}" } }
    }

    private fun params(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            // Like AVKit's, the window follows the playing video home.
            .setAutoEnterEnabled(player.isPlaying)
            .setSeamlessResizeEnabled(true)
            .setActions(listOf(playPauseAction()))
        aspectRatio()?.let(builder::setAspectRatio)
        videoFrame?.let { frame ->
            builder.setSourceRectHint(
                android.graphics.Rect(frame.left.roundToInt(), frame.top.roundToInt(), frame.right.roundToInt(), frame.bottom.roundToInt()),
            )
        }
        return builder.build()
    }

    /** The video's shape, within what Android's PiP window allows (2.39:1 either way). */
    private fun aspectRatio(): Rational? {
        val size = player.videoSize
        if (size.width <= 0 || size.height <= 0) return null
        val width = (size.width * size.pixelWidthHeightRatio).roundToInt().coerceAtLeast(1)
        val ratio = width.toFloat() / size.height
        return when {
            ratio > MAX_RATIO -> Rational(239, 100)
            ratio < 1 / MAX_RATIO -> Rational(100, 239)
            else -> Rational(width, size.height)
        }
    }

    private fun playPauseAction(): RemoteAction {
        val playing = !Util.shouldShowPlayButton(player)
        val title = if (playing) activity.getString(R.string.media_player_pause) else activity.getString(R.string.media_player_play)
        val icon = Icon.createWithResource(activity, if (playing) ai.opencode.core.R.drawable.ms_pause_fill else ai.opencode.core.R.drawable.ms_play_arrow_fill)
        val intent = PendingIntent.getBroadcast(
            activity,
            REQUEST_PLAY_PAUSE,
            Intent(action).setPackage(activity.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return RemoteAction(icon, title, title, intent)
    }

    private companion object {
        const val MAX_RATIO = 2.39f
        const val REQUEST_PLAY_PAUSE = 0x5050
    }
}

/** The player's Picture in Picture, or null where there's no activity (previews) or it's audio. */
@Composable
internal fun rememberAgentFilePictureInPicture(player: Player, isAudio: Boolean): AgentFilePictureInPicture? {
    val activity = LocalContext.current.findComponentActivity() ?: return null
    if (isAudio) return null
    val pip = remember(activity, player) { AgentFilePictureInPicture(activity, player) }
    DisposableEffect(pip) {
        pip.attach()
        onDispose { pip.detach() }
    }
    return pip
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is Activity -> null
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
