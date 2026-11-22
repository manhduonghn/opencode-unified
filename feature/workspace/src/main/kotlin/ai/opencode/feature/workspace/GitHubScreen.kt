package ai.opencode.feature.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.termux.app.RuntimeShell
import com.termux.app.RuntimeStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val HTTPS_GITHUB = Regex("^https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/?$")

/**
 * Compose replacement for the fork's `GitHubActivity`.
 *
 * Authentication is owned by `gh`'s credential store: the token is fed to stdin and never written
 * to app preferences, and no OAuth client id/scopes are embedded in this APK.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by rememberSaveable { mutableStateOf("") }
    // Never saveable: a pasted token must not survive process death or land in saved state.
    var token by remember { mutableStateOf("") }
    var repo by rememberSaveable { mutableStateOf("") }
    var destination by rememberSaveable { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val toolsReady = remember {
        RuntimeShell.hasExecutable("gh") && RuntimeShell.hasExecutable("git")
    }

    fun report(line: String) {
        output += line + "\n"
        if (output.length > 8000) output = output.takeLast(8000)
    }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            RuntimeShell.run(context, arrayOf("gh", "auth", "status"), 30_000)
        }
        status = parseAuthStatus(result)
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.oc_workspace_github_title)) }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(stringResource(R.string.oc_workspace_github_status))
            Text(status.ifEmpty { stringResource(R.string.oc_workspace_github_not_installed) })
            TextButton(onClick = {
                runCommand(scope, context, arrayOf("pkg", "install", "-y", "git", "gh"),
                    onBusy = { busy = it }, onLine = ::report) {
                    status = parseAuthStatus(it)
                }
            }) { Text(stringResource(R.string.oc_workspace_github_install)) }

            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(stringResource(R.string.oc_workspace_github_token)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/tokens")),
                    )
                }
            }) { Text(stringResource(R.string.oc_workspace_github_create_token)) }
            TextButton(onClick = {
                if (token.isBlank()) return@TextButton
                scope.launch(Dispatchers.IO) {
                    busy = true
                    val result = RuntimeShell.runWithInput(
                        context,
                        token,
                        arrayOf("gh", "auth", "login", "--with-token", "-h", "github.com"),
                        60_000,
                    ) { line -> report(line) }
                    token = ""
                    status = parseAuthStatus(result)
                    busy = false
                }
            }) { Text(stringResource(R.string.oc_workspace_github_sign_in)) }

            OutlinedTextField(
                value = repo,
                onValueChange = { repo = it },
                label = { Text(stringResource(R.string.oc_workspace_github_repo)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = destination,
                onValueChange = { destination = it },
                label = { Text(stringResource(R.string.oc_workspace_github_destination)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = {
                val target = destination.ifBlank {
                    File(RuntimeStorage.home(), "repos").absolutePath
                }
                when {
                    !toolsReady -> error = stringResource(R.string.oc_workspace_github_not_installed)
                    !HTTPS_GITHUB.matches(repo.trim()) ->
                        error = stringResource(R.string.oc_workspace_github_invalid_repo)
                    !isInsideHome(target) ->
                        error = stringResource(R.string.oc_workspace_github_invalid_destination)
                    else -> {
                        error = null
                        runCommand(
                            scope,
                            context,
                            arrayOf(
                                "env", "GIT_TERMINAL_PROMPT=0", "git", "clone",
                                "--progress", "--", repo.trim(), target,
                            ),
                            onBusy = { busy = it },
                            onLine = ::report,
                        ) { output += "done\n" }
                    }
                }
            }) { Text(stringResource(R.string.oc_workspace_github_run)) }
            if (error != null) Text(error!!)
            if (busy) Text("…")
            Text(stringResource(R.string.oc_workspace_github_output))
            Text(output)
        }
    }
}

private fun runCommand(
    scope: kotlinx.coroutines.CoroutineScope,
    context: Context,
    args: Array<String>,
    onBusy: (Boolean) -> Unit,
    onLine: (String) -> Unit,
    onDone: (String) -> Unit,
) {
    scope.launch(Dispatchers.IO) {
        onBusy(true)
        val result = RuntimeShell.run(context, args, 600_000) { line -> onLine(line) }
        onDone(result)
        onBusy(false)
    }
}

private fun parseAuthStatus(result: String): String {
    val line = result.lineSequence().firstOrNull { it.contains("Logged in to") }
        ?: result.lineSequence().firstOrNull { it.contains("not logged in", ignoreCase = true) }
    return line?.trim() ?: result.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}

/** Guards the clone destination so a deep link or typo cannot write into the runtime prefix. */
private fun isInsideHome(path: String): Boolean = runCatching {
    val home = RuntimeStorage.home().canonicalFile
    val target = File(path).canonicalFile
    target.startsWith(home)
}.getOrDefault(false)
