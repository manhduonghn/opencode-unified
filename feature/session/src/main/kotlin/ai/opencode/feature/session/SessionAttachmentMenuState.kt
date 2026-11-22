package ai.opencode.feature.session

import ai.opencode.core.ui.AccessibilityFocusTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** The + menu's state, owned by `SessionView`. */
@Stable
class SessionAttachmentMenuState {
    var plusMenuIsOpen by mutableStateOf(false)
    /** The keyboard comes back as the menu closes. */
    var attachmentMenuRestoresFocus by mutableStateOf(false)
    /** The clipboard held an image as the menu opened. */
    var clipboardHasImages by mutableStateOf(false)
    /** Location stays a row while the menu closes after choosing it. */
    var keepingLocationRow by mutableStateOf(false)
    /** The + button, which gets TalkBack's focus back as the menu closes. */
    val plusButton = AccessibilityFocusTarget()

    fun closeAttachmentMenu() {
        plusMenuIsOpen = false
    }
}

@Composable
fun rememberSessionAttachmentMenuState(): SessionAttachmentMenuState = remember { SessionAttachmentMenuState() }

/** The screen's + menu state, null outside a session screen. */
internal val LocalSessionAttachmentMenu = staticCompositionLocalOf<SessionAttachmentMenuState?> { null }
