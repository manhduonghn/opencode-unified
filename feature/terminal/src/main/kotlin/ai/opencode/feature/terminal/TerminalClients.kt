package ai.opencode.feature.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.mutableStateOf
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants
import com.termux.shared.termux.terminal.io.BellHandler
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient

/**
 * The fork's terminal clients, reduced to the behavior that matters for a single Termux shell.
 * Terminal output is never logged: logging stays inert like the upstream base clients.
 */
internal class TerminalClients(
    private val context: Context,
    private val host: TerminalHost,
) : TerminalSessionClient, TerminalViewClient {

    private val clipboard: ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    /** Latched extra-key modifiers, mirroring the fork's Ctrl/Alt buttons. */
    val controlDown = mutableStateOf(false)
    val altDown = mutableStateOf(false)

    fun toggleControl() {
        controlDown.value = !controlDown.value
    }

    fun toggleAlt() {
        altDown.value = !altDown.value
    }

    // TerminalSessionClient

    override fun onTextChanged(changedSession: TerminalSession) {
        if (changedSession === host.view?.mTermSession) host.view?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) {
        if (finishedSession === host.view?.mTermSession) host.onSessionFinished(finishedSession)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        clipboard?.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val text = clipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: return
        val emulator = host.view?.mEmulator
        if (emulator != null && session === host.view?.mTermSession) {
            emulator.paste(text)
        } else if (session != null) {
            val bytes = text.toByteArray()
            session.write(bytes, 0, bytes.size)
        }
        host.view?.invalidate()
    }

    override fun onBell(session: TerminalSession) {
        val behaviour = TermuxAppSharedProperties.getProperties()?.bellBehaviour
        if (behaviour == TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_VIBRATE ||
            behaviour == TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_BEEP
        ) {
            BellHandler.getInstance(context).doBell()
        }
    }

    override fun onColorsChanged(session: TerminalSession) = Unit

    override fun onTerminalCursorStateChange(state: Boolean) {
        host.view?.setTerminalCursorBlinkerState(state, false)
    }

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit

    override fun getTerminalCursorStyle(): Int? = TermuxAppSharedProperties.getProperties()?.terminalCursorStyle

    override fun logError(tag: String, message: String) = Unit
    override fun logWarn(tag: String, message: String) = Unit
    override fun logInfo(tag: String, message: String) = Unit
    override fun logDebug(tag: String, message: String) = Unit
    override fun logVerbose(tag: String, message: String) = Unit
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
    override fun logStackTrace(tag: String, e: Exception) = Unit

    // TerminalViewClient

    override fun onScale(scale: Float): Float {
        val preferences = TermuxAppSharedPreferences.build(context) ?: return 1f
        preferences.changeFontSize(scale > 1f)
        host.view?.setTextSize(preferences.getFontSize())
        // The view handles pinch zoom itself in Termux; font size is the zoom.
        return 1f
    }

    override fun onSingleTapUp(e: MotionEvent) {
        val view = host.view ?: return
        view.requestFocus()
        val input = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        input?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean =
        TermuxAppSharedProperties.getProperties()?.isBackKeyTheEscapeKey() == true

    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = controlDown.value
    override fun readAltKey(): Boolean = altDown.value
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() {
        host.view?.setTerminalCursorBlinkerState(true, true)
    }
}
