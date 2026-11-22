package ai.opencode.feature.terminal

import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.io.File

/** A Termux shell hosted inside the unified app, without the old Termux Activity. */
@Composable
fun TerminalScreen(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = ::createTerminalView,
        update = { view -> view.updateSize() },
    )
}

private fun createTerminalView(context: Context): TerminalView {
    val environment = TermuxShellEnvironment().getEnvironment(context, false)
    val env = environment.map { (key, value) -> "$key=$value" }.toTypedArray()

    val home = environment["HOME"]?.takeIf { File(it).isDirectory } ?: context.filesDir.absolutePath
    val prefix = environment["PREFIX"]
    val shell = listOfNotNull(prefix?.let { File(it, "bin/bash") }, File("/system/bin/sh"))
        .firstOrNull { it.exists() && it.canExecute() }
        ?.absolutePath
        ?: "/system/bin/sh"

    val client = TerminalClients(context)
    val session = TerminalSession(shell, home, arrayOf("-l"), env, 500, client)

    return TerminalView(context, null).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        isFocusable = true
        isFocusableInTouchMode = true
        setTerminalViewClient(client)
        attachSession(session)
        updateSize()
        requestFocus()
    }
}
