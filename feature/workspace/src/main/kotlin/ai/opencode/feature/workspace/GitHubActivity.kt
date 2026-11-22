package ai.opencode.feature.workspace

import ai.opencode.core.ui.OpenCodeAppTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/** Host for the `gh`-backed device login and clone screen. */
class GitHubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            OpenCodeAppTheme {
                GitHubScreen()
            }
        }
    }
}
