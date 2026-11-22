package ai.opencode.feature.workspace

import ai.opencode.core.ui.OpenCodeAppTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * Host for the file browser. Opened with `opencode://workspace/files?path=...` from Settings or
 * the share sheet. The path is validated again by [WorkspaceScreen] against the runtime roots.
 */
class WorkspaceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val initialPath = intent.data?.takeIf { it.scheme == "opencode" }?.getQueryParameter("path")
        setContent {
            OpenCodeAppTheme {
                WorkspaceScreen(initialPath = initialPath)
            }
        }
    }
}
