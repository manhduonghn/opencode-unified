package ai.opencode.feature.composer

import ai.opencode.core.motion.Motion
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.semantics.clearAndSetSemantics

/** The + button's curves. */
object AttachmentMenuMotion {
    /** The + turns into a ×. */
    fun <T> open(reduceMotion: Boolean): FiniteAnimationSpec<T> = Motion.panelOpen.animation(reduceMotion = reduceMotion)

    fun <T> close(reduceMotion: Boolean): FiniteAnimationSpec<T> = Motion.panelClose.animation(reduceMotion = reduceMotion)
}

/** The video badge's values. Points. */
object PlusMenuTunables {
    const val VIDEO_BADGE_INSET = 6f
    const val VIDEO_BADGE_RADIUS = 6f
    const val VIDEO_BADGE_OPACITY = 0.58
}

/**
 * What the composer's dismiss area closes. It reaches over the whole transcript, so it's mounted
 * only for something on screen.
 */
enum class ComposerDismissTarget {
    AttachmentMenu,
    Autocomplete,
    ;

    companion object {
        fun current(attachmentMenuOpen: Boolean, autocompleteOpen: Boolean, autocompleteDrawn: Boolean): ComposerDismissTarget? {
            if (attachmentMenuOpen) return AttachmentMenu
            // A request panel or a call's rows hold the glass: its rows aren't there.
            if (autocompleteOpen && autocompleteDrawn) return Autocomplete
            return null
        }
    }
}

/** Dismisses the menu on a tap or a downward drag. */
@Composable
fun AttachmentMenuDismissArea(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
            .pointerInput(onDismiss) {
                val tracker = VelocityTracker()
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = {
                        total = 0f
                        tracker.resetTracking()
                    },
                    onDragEnd = {
                        val predicted = total + tracker.calculateVelocity().y * 0.5f
                        if (total.toDp().value > 30 || predicted.toDp().value > 120) onDismiss()
                    },
                ) { change, amount ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    total += amount
                }
            },
    ) {}
}
