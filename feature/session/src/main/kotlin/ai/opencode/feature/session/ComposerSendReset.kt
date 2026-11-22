package ai.opencode.feature.session

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import android.graphics.Bitmap
import android.graphics.Picture
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * On a send the composer card fades out a bitmap of its old content while
 * `animateContentSize(alignment = BottomStart)` slides its top edge down. The draft clears in the
 * store at once, so the outgoing content has to be a picture taken just before.
 */
@Stable
internal class ComposerSendReset {
    private var view: View? = null
    internal var cardBounds = Rect.Zero
    /** The trailing controls, in root space: they stay live through the fade, so their own motion shows. */
    internal var trailingBounds = Rect.Zero
    internal var outgoing: Bitmap? = null
        private set
    internal val outgoingAlpha = Animatable(1f)
    private val slide = Animatable(0f)

    var isResetting by mutableStateOf(false)
        private set

    /** Call just before the send clears the draft. */
    fun start() {
        val view = view ?: return
        val width = cardBounds.width.toInt()
        val height = cardBounds.height.toInt()
        if (width <= 0 || height <= 0) return
        // A software canvas throws on RuntimeShader (the Working orb); a Picture renders on the GPU.
        val picture = Picture()
        val canvas = picture.beginRecording(width, height)
        canvas.translate(-cardBounds.left, -cardBounds.top)
        view.draw(canvas)
        picture.endRecording()
        outgoing = Bitmap.createBitmap(picture, width, height, Bitmap.Config.ARGB_8888)
        isResetting = true
    }

    @Composable
    fun Animate() {
        val currentView = LocalView.current
        SideEffect { view = currentView }
        LaunchedEffect(isResetting) {
            if (!isResetting) return@LaunchedEffect
            coroutineScope {
                launch { outgoingAlpha.animateTo(0f, Motion.easeOut(OUTGOING_FADE)) }
                slide.snapTo(0f)
                slide.animateTo(1f, SEND_RESET_SLIDE.spec())
            }
            isResetting = false
            outgoing = null
            outgoingAlpha.snapTo(1f)
        }
    }
}

/** Goes outside the card's `animateContentSize`. */
internal fun Modifier.composerSendReset(reset: ComposerSendReset): Modifier = this
    .onGloballyPositioned { reset.cardBounds = it.boundsInRoot() }
    .drawWithContent {
        drawContent()
        val bitmap = reset.outgoing
        if (!reset.isResetting || bitmap == null || reset.outgoingAlpha.value <= 0f) return@drawWithContent
        val trailing = reset.trailingBounds.translate(-reset.cardBounds.topLeft)
        clipRect(trailing.left, trailing.top, trailing.right, trailing.bottom, ClipOp.Difference) {
            drawImage(
                image = bitmap.asImageBitmap(),
                topLeft = Offset(0f, size.height - bitmap.height),
                alpha = reset.outgoingAlpha.value,
            )
        }
    }

/** Material 3's fast spatial spring. */
internal val SEND_RESET_SLIDE = MotionSpring(0.17)

private const val OUTGOING_FADE = 0.08
