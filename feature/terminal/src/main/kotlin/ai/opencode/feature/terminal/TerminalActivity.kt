package ai.opencode.feature.terminal

import ai.opencode.core.ui.OpenCodeAppTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/** Host for the real Termux shell. Opened with `opencode://terminal` from Settings. */
class TerminalActivity : ComponentActivity() {
    private lateinit var controller: TerminalController

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        controller = TerminalController(this) { finish() }
        setContent {
            OpenCodeAppTheme {
                TerminalScreen(controller)
            }
        }
    }
}
