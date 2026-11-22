package ai.opencode.feature.composer

import ai.opencode.core.R as CoreR
import ai.opencode.core.motion.Motion
import ai.opencode.core.strings.localized
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.motionPressEffect
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp

/**
 * The `ComposerTunables` defaults the + reads: `innerHorizontal` 6, `plusOffset` 28, `plusScale`
 * 17, `buttonDiameter` 34.
 */
private object PlusButtonComposerTunables {
    const val INNER_HORIZONTAL = 6f
    const val PLUS_OFFSET = 28f
    const val PLUS_SCALE = 17f
    const val BUTTON_DIAMETER = 34f
}

/**
 * The composer's + button. A tap opens the attachment menu, a dropdown hanging from the button.
 * While the menu is open the + turns into a × that closes it. While dictating the same + turns into
 * the recording's ×, which cancels it, and a filled circle grows in behind it in the same spring.
 */
object AttachmentPickerButton {
    /** The recording the × cancels while dictating. */
    data class Dictation(
        /** Sliding the held mic left has armed cancel. */
        val isCancelArmed: Boolean = false,
        /** Finishing after Stop: nothing left to cancel. */
        val isEnabled: Boolean = true,
    )

    /** The + menu takes up to 8 photos. */
    const val MAX_PHOTO_SELECTION = 8
}

/**
 * @param menu the attachment menu, anchored to the button.
 * @param glyphOffset moves the glyph's ink inside the target (the composer lines its + up with
 *   the placeholder's leading edge); the target stays put. Dp.
 */
@Composable
fun AttachmentPickerButton(
    isLoading: Boolean,
    isOpen: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    dictation: AttachmentPickerButton.Dictation? = null,
    onCancelDictation: () -> Unit = {},
    glyphOffset: Float = 0f,
    menu: @Composable () -> Unit = {},
) {
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val fontScale = minOf(density.fontScale, 1.6f)
    val isDictating = dictation != null
    val showsX = isOpen || isDictating
    val cancelArmed = dictation?.isCancelArmed == true
    var isTouching by remember { mutableStateOf(false) }

    // A touch that began on + (the menu's) isn't the recording's.
    LaunchedEffect(isDictating) {
        if (isDictating) isTouching = false
    }

    val visualOffsetX = PlusButtonComposerTunables.PLUS_OFFSET - (PlusButtonComposerTunables.INNER_HORIZONTAL + 22f)
    val rotation by animateFloatAsState(
        if (showsX) 45f else 0f,
        if (showsX) AttachmentMenuMotion.open(reduceMotion) else AttachmentMenuMotion.close(reduceMotion),
        label = "plus-rotation",
    )
    // Arming cancel swells the × in; letting go settles it back.
    val armedPhase = if (cancelArmed) MotionPhase.Enter else MotionPhase.Exit
    val armedSpec = Motion.microIn.animation<Float>(armedPhase, reduceMotion)
    val glyphScale by animateFloatAsState(if (cancelArmed) 1.1f else 1f, armedSpec, label = "plus-armed")
    val dictationSpec = if (isDictating) AttachmentMenuMotion.open<Float>(reduceMotion) else AttachmentMenuMotion.close(reduceMotion)
    val circleScale by animateFloatAsState(
        if (reduceMotion) 1f else if (isDictating) (if (cancelArmed) 1.1f else 1f) else 0.6f,
        if (cancelArmed || !isDictating) armedSpec else dictationSpec,
        label = "dictation-circle-scale",
    )
    val circleAlpha by animateFloatAsState(if (isDictating) 1f else 0f, dictationSpec, label = "dictation-circle")
    val circleColor by animateColorAsState(
        if (cancelArmed) SystemRed else MaterialTheme.colorScheme.surfaceContainerHighest,
        Motion.microIn.animation(armedPhase, reduceMotion),
        label = "dictation-circle-color",
    )
    val glyphX by animateFloatAsState(if (isDictating) 0f else glyphOffset, dictationSpec, label = "plus-glyph-x")
    val glyphColor = if (cancelArmed) Color.White else OpenCodeTheme.colors.text
    val label = when {
        isDictating -> stringResource(R.string.composer_plus_cancel_dictation_description)
        isLoading -> stringResource(R.string.composer_plus_adding_attachment_description)
        isOpen -> stringResource(CoreR.string.common_close)
        else -> stringResource(R.string.composer_plus_add_attachment_description)
    }
    val enabled = if (isDictating) dictation?.isEnabled != false else !isLoading

    fun cancelDictation() {
        if (dictation?.isEnabled == true) onCancelDictation()
    }

    val onTap by rememberUpdatedState {
        if (isDictating) cancelDictation() else if (!isLoading) onToggle()
    }
    val tap = Modifier.pointerInput(isDictating) {
        awaitEachGesture {
            awaitFirstDown().consume()
            isTouching = !isDictating
            val up = waitForUpOrCancellationCompat()
            isTouching = false
            if (up) onTap()
        }
    }

    Box(
        modifier
            .size(48.dp)
            .then(tap)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = label
                testTag = "attachment-menu-button"
                if (!enabled) disabled()
                onClick {
                    onTap()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .offset(x = visualOffsetX.dp)
                .motionPressEffect(isTouching && !showsX, reduceMotion),
            contentAlignment = Alignment.Center,
        ) {
            val diameter = minOf(34f * fontScale * (PlusButtonComposerTunables.BUTTON_DIAMETER / 34f), 44f)
            // Decoration under the mic, which owns the touch.
            Box(
                Modifier
                    .size(diameter.dp)
                    .graphicsLayer {
                        scaleX = circleScale
                        scaleY = circleScale
                        alpha = circleAlpha
                    }
                    .background(circleColor, CircleShape),
            )
            Box(
                Modifier.offset(x = glyphX.dp),
                contentAlignment = Alignment.Center,
            ) {
                val glyphSize = (PlusButtonComposerTunables.PLUS_SCALE * 1.3f * fontScale).dp
                if (isLoading && !isDictating) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else if (reduceMotion) {
                    // Under Reduce Motion + and × cross-fade.
                    val xAlpha by animateFloatAsState(if (showsX) 1f else 0f, Motion.easeInOut(0.2), label = "plus-fade")
                    OpenCodeIcon(SFSymbol.Plus, null, Modifier.alpha(1 - xAlpha), size = glyphSize, tint = glyphColor)
                    OpenCodeIcon(SFSymbol.Plus, null, Modifier.graphicsLayer { rotationZ = 45f }.alpha(xAlpha), size = glyphSize, tint = glyphColor)
                } else {
                    // The × is always the turned +.
                    OpenCodeIcon(
                        SFSymbol.Plus,
                        null,
                        Modifier.graphicsLayer {
                            rotationZ = rotation
                            scaleX = glyphScale
                            scaleY = glyphScale
                        },
                        size = glyphSize,
                        tint = glyphColor,
                    )
                }
            }
        }
        menu()
    }
}

private val SystemRed = Color(0xFFFF3B30)

/** True on an up, false on a cancel (the gesture moved away or was consumed). */
private suspend fun AwaitPointerEventScope.waitForUpOrCancellationCompat(): Boolean =
    waitForUpOrCancellation()?.also { it.consume() } != null

/** What the menu offers. */
enum class AttachmentMenuItem(@StringRes private val titleRes: Int, val systemImage: SFSymbol) {
    Camera(R.string.composer_attachment_menu_camera, SFSymbol.Camera),
    Photos(R.string.composer_attachment_menu_photos, SFSymbol.PhotoOnRectangle),
    Files(R.string.composer_attachment_menu_files, SFSymbol.Folder),
    Location(R.string.composer_attachment_menu_location, SFSymbol.Location),
    Paste(R.string.composer_attachment_menu_paste, SFSymbol.DocOnClipboard);

    val title: String get() = localized(titleRes)
}

/** Frames other panels lay themselves out against, in root coordinates. */
@Stable
class AttachmentMenuAnchors {
    /** The composer surface. */
    var composer: Rect by mutableStateOf(Rect.Zero)
}
