package ai.opencode.feature.terminal

import ai.opencode.core.ui.OpenCodeAppTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/** Host for the Termux shell. Opened with `opencode://terminal` from Settings. */
class TerminalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            OpenCodeAppTheme {
                TerminalScreen()
            }
        }
    }
}
