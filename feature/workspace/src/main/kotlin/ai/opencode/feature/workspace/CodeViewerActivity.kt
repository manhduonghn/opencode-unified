package ai.opencode.feature.workspace

import ai.opencode.core.ui.OpenCodeAppTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/** Host for the Compose text editor, opened from the file browser. */
class CodeViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) {
            finish()
            return
        }
        setContent {
            OpenCodeAppTheme {
                CodeViewerScreen(path = path)
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "ai.opencode.feature.workspace.path"
    }
}
