package ai.opencode.app

import ai.opencode.core.ui.OpenCodeAppTheme
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.termux.app.UnifiedSetup

/**
 * The first screen of the unified app.
 *
 * It is the fork's startup flow with the WebView removed: bootstrap, runtime installation, daemon
 * start and pairing all happen here. At the end it hands the one-time pair link to the native chat
 * client and finishes, so the user never sees the native pairing screen.
 */
class UnifiedSetupActivity : ComponentActivity() {
    private var progress by mutableStateOf(
        UnifiedSetup.Progress(UnifiedSetup.PHASE_BOOTSTRAP, -1, "", null),
    )
    private var error by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runSetup()
        setContent {
            OpenCodeAppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SetupScreen(progress, error, ::runSetup)
                }
            }
        }
    }

    private fun runSetup() {
        error = null
        UnifiedSetup.start(this, object : UnifiedSetup.Listener {
            override fun onProgress(next: UnifiedSetup.Progress) {
                progress = next
            }

            override fun onReady(result: UnifiedSetup.Result) {
                if (isFinishing || isDestroyed) return
                UnifiedHandoff.openChat(this@UnifiedSetupActivity, result, ::onHandoffError)
            }

            override fun onError(message: String) {
                error = message
            }
        })
    }

    private fun onHandoffError(message: String) {
        if (!isFinishing && !isDestroyed) error = message
    }

    override fun onDestroy() {
        UnifiedSetup.cancel()
        super.onDestroy()
    }
}

private val UnifiedSetup.Progress.labelRes: Int
    get() = when (phase) {
        UnifiedSetup.PHASE_BOOTSTRAP -> R.string.oc_setup_bootstrap
        UnifiedSetup.PHASE_RUNTIME -> R.string.oc_setup_runtime
        UnifiedSetup.PHASE_SERVICE -> R.string.oc_setup_service
        UnifiedSetup.PHASE_PAIR -> R.string.oc_setup_pair
        else -> R.string.oc_setup_preparing
    }

@androidx.compose.runtime.Composable
private fun SetupScreen(
    progress: UnifiedSetup.Progress,
    error: String?,
    onRetry: () -> Unit,
) {
    val errorMessage = error
    Box(Modifier.fillMaxSize().padding(32.dp)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.oc_setup_title),
                style = OpenCodeTextStyles.title1,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.oc_setup_subtitle),
                style = OpenCodeTextStyles.body,
                color = OpenCodeTheme.colors.secondaryText,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )

            if (errorMessage == null) {
                val label = stringResource(progress.labelRes)
                val indeterminate = progress.percent < 0
                if (indeterminate) {
                    CircularProgressIndicator(
                        Modifier.padding(top = 40.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { progress.percent / 100f },
                        modifier = Modifier
                            .padding(top = 40.dp)
                            .fillMaxWidth(),
                    )
                }
                Text(
                    text = progress.message.ifEmpty { label },
                    style = OpenCodeTextStyles.headline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 20.dp),
                )
                if (!progress.detail.isNullOrEmpty()) {
                    Text(
                        text = progress.detail,
                        style = OpenCodeTextStyles.footnote,
                        color = OpenCodeTheme.colors.secondaryText,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Text(
                    text = label,
                    style = OpenCodeTextStyles.caption1,
                    color = OpenCodeTheme.colors.secondaryText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                Text(
                    text = stringResource(R.string.oc_setup_error_title),
                    style = OpenCodeTextStyles.headline,
                    color = OpenCodeTheme.colors.warning,
                    modifier = Modifier.padding(top = 40.dp),
                )
                Text(
                    text = errorMessage,
                    style = OpenCodeTextStyles.footnote,
                    color = OpenCodeTheme.colors.secondaryText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Button(onClick = onRetry, modifier = Modifier.padding(top = 24.dp)) {
                    Text(stringResource(R.string.oc_setup_retry))
                }
            }
        }
    }
}
