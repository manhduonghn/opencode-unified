package ai.opencode.feature.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** A real Termux shell in the native client, with the familiar extra-key row. */
@Composable
internal fun TerminalScreen(controller: TerminalController) {
    Column(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { context -> controller.createView(context) },
            update = { view -> view.updateSize() },
        )
        ExtraKeys(controller)
    }
}

@Composable
private fun ExtraKeys(controller: TerminalController) {
    val clients = controller.clients
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyButton("Esc") { controller.sendKey("\u001B") }
        KeyButton("Tab") { controller.sendKey("\t") }
        KeyButton("Ctrl", clients.controlDown.value) { clients.toggleControl() }
        KeyButton("Alt", clients.altDown.value) { clients.toggleAlt() }
        KeyButton("←") { controller.sendKey("\u001B[D") }
        KeyButton("↓") { controller.sendKey("\u001B[B") }
        KeyButton("↑") { controller.sendKey("\u001B[A") }
        KeyButton("→") { controller.sendKey("\u001B[C") }
        KeyButton("+") { controller.newSession() }
    }
}

@Composable
private fun KeyButton(label: String, active: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(38.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
    ) {
        Text(
            text = if (active) "[$label]" else label,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
