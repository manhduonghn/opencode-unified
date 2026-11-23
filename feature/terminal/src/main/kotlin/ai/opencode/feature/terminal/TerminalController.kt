package ai.opencode.feature.terminal

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties
import com.termux.shared.termux.shell.TermuxShellManager
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.util.Properties

/** What the terminal clients need from the object that owns the view and the shell. */
internal interface TerminalHost {
    val view: TerminalView?
    fun onSessionFinished(session: TerminalSession)
}

/**
 * Owns the Termux shell the way the fork's `TermuxService` did, but scoped to the terminal screen:
 * sessions are created with `TermuxSession.execute`, so they get the real Termux environment,
 * transcript size and login shell instead of a hand-rolled `TerminalSession`.
 */
internal class TerminalController(
    private val context: Context,
    private val onExit: () -> Unit,
) : TerminalHost {

    val clients = TerminalClients(context, this)

    override var view: TerminalView? = null
        private set

    private var session: TermuxSession? = null

    fun createView(context: Context): TerminalView {
        loadColors()
        val preferences = TermuxAppSharedPreferences.build(context)
        val properties = TermuxAppSharedProperties.getProperties()

        val terminalView = TerminalView(context, null).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            isFocusable = true
            isFocusableInTouchMode = true
            setTerminalViewClient(clients)
            // The fork's view client did this from preferences before the first layout.
            setTextSize(
                preferences?.getFontSize() ?: TermuxAppSharedPreferences.getDefaultFontSizes(context)[0],
            )
            val font = TermuxConstants.TERMUX_FONT_FILE
            if (font.isFile) {
                runCatching { Typeface.createFromFile(font) }.getOrNull()?.let(::setTypeface)
            }
            setTerminalCursorBlinkerRate(properties?.terminalCursorBlinkRate ?: 0)
            keepScreenOn = preferences?.shouldKeepScreenOn() == true
        }
        view = terminalView
        startSession()
        terminalView.requestFocus()
        return terminalView
    }

    /** Termux restarts a finished login shell with a fresh one instead of leaving a dead screen. */
    override fun onSessionFinished(session: TerminalSession) {
        // A session we already replaced can exit afterwards; only restart the visible one.
        if (session === this.session?.terminalSession) startSession()
    }

    fun newSession() {
        session?.finish()
        startSession()
    }

    /** Control characters (arrows, Esc, Tab) must not go through the emulator's paste sanitizer. */
    fun sendKey(sequence: String) {
        val terminal = view?.mTermSession ?: return
        val bytes = sequence.toByteArray()
        terminal.write(bytes, 0, bytes.size)
        view?.invalidate()
    }

    /** Typed/pasted text uses the emulator's bracketed-paste handling, like the fork. */
    fun sendText(text: String) {
        val emulator = view?.mEmulator
        if (emulator == null) {
            sendKey(text)
        } else {
            emulator.paste(text)
            view?.invalidate()
        }
    }

    private fun startSession() {
        val properties = TermuxAppSharedProperties.getProperties()
        val command = ExecutionCommand(
            TermuxShellManager.getNextShellId(),
            null, // A null executable selects the Termux login shell in TermuxSession.execute().
            null,
            null,
            properties?.defaultWorkingDirectory,
            ExecutionCommand.Runner.TERMINAL_SESSION.name,
            false,
        )
        command.setShellCommandShellEnvironment = true
        command.terminalTranscriptRows = properties?.terminalTranscriptRows ?: 500
        command.shellName = "bash"

        val next = TermuxSession.execute(
            context,
            command,
            clients,
            null,
            TermuxShellEnvironment(),
            null,
            false,
        )
        if (next == null) {
            onExit()
            return
        }
        session = next
        view?.attachSession(next.terminalSession)
    }

    private fun loadColors() {
        val colorsFile = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE
        if (!colorsFile.isFile) return
        val properties = Properties()
        runCatching { colorsFile.inputStream().use(properties::load) }
            .onSuccess { TerminalColors.COLOR_SCHEME.updateWith(properties) }
    }
}
