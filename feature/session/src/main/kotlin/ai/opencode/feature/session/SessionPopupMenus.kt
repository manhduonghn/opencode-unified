package ai.opencode.feature.session

import ai.opencode.core.ui.AccessibilityFocusTarget
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.SFSymbol
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** The system lists a shown window for accessibility on its 100 ms accessibility event interval; twice that, to be sure. */
private const val MENU_WINDOW_LISTED_MS = 200L

/** Material's menu margin from the window's edge (`MenuVerticalMargin`). */
internal val menuWindowMargin = 48.dp

/** How long the menu's button waits for TalkBack's focus, so it isn't lost with the menu or what the menu opened. */
private const val MENU_FOCUS_DELAY_MS = 150L

@Composable
internal fun rememberTalkBackOn(): Boolean {
    val context = LocalContext.current
    val talkBack = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    return talkBack?.isTouchExplorationEnabled == true
}

/**
 * A non-focusable menu (it leaves the keyboard up) is still a pane to TalkBack: the screen behind
 * leaves the accessibility tree once [MenuTakesTalkBackFocus] has moved the focus into the menu,
 * and [button] gets the focus back as the menu closes.
 */
@Composable
internal fun MenuReturnsTalkBackFocus(expanded: Boolean, button: AccessibilityFocusTarget) {
    val hostView = LocalView.current
    val talkBackOn = rememberTalkBackOn()
    DisposableEffect(expanded, hostView) {
        if (!expanded || !talkBackOn) return@DisposableEffect onDispose { }
        val previous = hostView.importantForAccessibility
        onDispose {
            hostView.importantForAccessibility = previous
            hostView.focusMenuButtonForAccessibility(button)
        }
    }
}

/** Called inside the menu's content with the screen's view: TalkBack's focus moves to [firstRow]. */
@Composable
internal fun MenuTakesTalkBackFocus(hostView: View, expanded: Boolean, firstRow: AccessibilityFocusTarget) {
    if (!rememberTalkBackOn()) return
    val isOpen by rememberUpdatedState(expanded)
    val menuView = LocalView.current
    LaunchedEffect(menuView) {
        // Focus sent before the system lists the popup's window for accessibility is
        // dropped: the window is listed once shown.
        while (!menuView.isAttachedToWindow) withFrameNanos { }
        withTimeoutOrNull(MENU_WINDOW_LISTED_MS) { menuView.awaitFrameCommit() }
        delay(MENU_WINDOW_LISTED_MS)
        val focused = firstRow.focus()
        // Hidden only once the menu has the focus: TalkBack otherwise recovers from losing the
        // button by focusing the keyboard.
        if (focused && isOpen) hostView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
}

/** Resumes once this view's window commits its next frame. */
private suspend fun View.awaitFrameCommit() = suspendCancellableCoroutine { continuation ->
    val observer = viewTreeObserver
    val callback = Runnable { if (continuation.isActive) continuation.resume(Unit) }
    observer.registerFrameCommitCallback(callback)
    continuation.invokeOnCancellation { if (observer.isAlive) observer.unregisterFrameCommitCallback(callback) }
    invalidate()
}

/** TalkBack back on the menu's button, once the menu or what it opened has gone, unless a dialog it opened has the focus. */
internal fun View.focusMenuButtonForAccessibility(button: AccessibilityFocusTarget) {
    if (context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled != true) return
    postDelayed({
        if (hasWindowFocus()) button.focus()
    }, MENU_FOCUS_DELAY_MS)
}

@Composable
internal fun MenuItem(label: String, symbol: SFSymbol, modifier: Modifier = Modifier, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { OpenCodeIcon(symbol, contentDescription = null, size = OpenCodeIconSize.small) },
        onClick = onClick,
        modifier = modifier,
        contentPadding = MenuDefaults.DropdownMenuItemContentPadding,
    )
}
