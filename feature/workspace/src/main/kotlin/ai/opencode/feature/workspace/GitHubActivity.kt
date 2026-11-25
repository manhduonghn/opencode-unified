package ai.opencode.feature.workspace

import ai.opencode.core.ui.OpenCodeAppTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/** Host for the fork-style GitHub device login, repository list and clone screen. Opened from Files. */
class GitHubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val targetDirectory = intent.getStringExtra(EXTRA_TARGET_DIRECTORY)
        setContent {
            OpenCodeAppTheme {
                GitHubScreen(targetDirectory = targetDirectory)
            }
        }
    }

    companion object {
        /** Folder the file browser had open; clones go here instead of the Termux home. */
        const val EXTRA_TARGET_DIRECTORY =
            "ai.opencode.feature.workspace.GITHUB_TARGET_DIRECTORY"
    }
}
