package ai.opencode.feature.media

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.PopupProperties

/**
 * A drag source for a decoded image media draws in the transcript, given by the transcript, whose
 * drag source also owns the card's hold menu. Holding the surface runs `onLift` (where the card
 * opens its menu) and moving the held finger drags, closing that menu (`onDragStart`). Null
 * (outside the transcript) keeps the card's own long press.
 */
val LocalTranscriptImageDragSource = staticCompositionLocalOf<((image: Bitmap, name: String, cornerRadius: Dp, onLift: () -> Unit, onDragStart: () -> Unit) -> Modifier)?> { null }

/**
 * Whether a finger still holds a lifted transcript surface. A hold menu opened by the lift is a
 * focusable popup window, and a focusable window appearing cancels the touch still going to the
 * surface, so the drag could never start. Menus opened by a lift stay unfocusable while held
 * ([liftMenuProperties]) and become focusable (outside taps and Back dismiss them) once the finger
 * lifts.
 */
object TranscriptLiftHold {
    var isHeld by mutableStateOf(false)
}

/** The popup properties for a menu a transcript surface's lift opens (see [TranscriptLiftHold]). */
fun liftMenuProperties(): PopupProperties = PopupProperties(focusable = !TranscriptLiftHold.isHeld)
