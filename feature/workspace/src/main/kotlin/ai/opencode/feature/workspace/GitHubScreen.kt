package ai.opencode.feature.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.termux.app.OpenCodeBootstrap
import com.termux.app.RuntimeShell
import com.termux.app.RuntimeStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Which long-running action owns the screen's single busy slot. */
private enum class Work { Login, Logout, Clone }

/**
 * Compose replacement for the fork's `GitHubActivity`.
 *
 * Login is the fork's OAuth device-code flow: the code is shown and copied, the verification page
 * opens in the browser, and the resulting token is piped straight into `gh auth login --with-token`
 * and `gh auth setup-git`. The token is never saved in app state or preferences; `gh` owns the
 * credential store. Cloning targets the directory that was open in the file browser, like the
 * fork, and accepts full URLs, `github.com/owner/repo` and `owner/repo`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubScreen(targetDirectory: String? = null, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val formatLocale = remember(LocalConfiguration.current) {
        context.resources.configuration.locales[0]
    }

    var status by rememberSaveable { mutableStateOf("") }
    var account by rememberSaveable { mutableStateOf("") }
    var userCode by remember { mutableStateOf("") }
    var verificationUri by remember { mutableStateOf("") }
    var expiresAt by remember { mutableStateOf(0L) }
    var tick by remember { mutableStateOf(SystemClock.uptimeMillis()) }
    var repo by rememberSaveable { mutableStateOf("") }
    var repoFilter by rememberSaveable { mutableStateOf("") }
    var repos by remember { mutableStateOf<List<Repo>>(emptyList()) }
    var reposError by remember { mutableStateOf<String?>(null) }
    var reposLoading by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var outputExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var work by remember { mutableStateOf<Work?>(null) }
    var loginJob by remember { mutableStateOf<Job?>(null) }
    var cloningTarget by remember { mutableStateOf<String?>(null) }
    var toolsReady by remember { mutableStateOf(false) }

    val cloneDirectory = remember(targetDirectory) { cloneDirectory(targetDirectory) }

    // Resolve strings in composition so a configuration change cannot leave a stale Context, and so
    // background coroutines never need to hold one.
    val sNotInstalled = stringResource(R.string.oc_workspace_github_not_installed)
    val sFetchingCode = stringResource(R.string.oc_workspace_github_fetching_code)
    val sWaitingAuth = stringResource(R.string.oc_workspace_github_waiting_auth)
    val sInstallingBeforeLogin = stringResource(R.string.oc_workspace_github_installing_before_login)
    val sToolInstallFailed = stringResource(R.string.oc_workspace_github_tool_install_failed)
    val sLoginCancelled = stringResource(R.string.oc_workspace_github_login_cancelled)
    val sLoginNotRecorded = stringResource(R.string.oc_workspace_github_login_not_recorded)
    val sLoginFailed = stringResource(R.string.oc_workspace_github_login_failed)
    val sNoCode = stringResource(R.string.oc_workspace_github_no_code)
    val sHttp = stringResource(R.string.oc_workspace_github_http_error)
    val sExpired = stringResource(R.string.oc_workspace_github_code_expired)
    val sDenied = stringResource(R.string.oc_workspace_github_code_denied)
    val sTimedOut = stringResource(R.string.oc_workspace_github_code_timed_out)
    val sInvalidRepo = stringResource(R.string.oc_workspace_github_invalid_repo)
    val sInvalidDestination = stringResource(R.string.oc_workspace_github_invalid_destination)
    val sCloning = stringResource(R.string.oc_workspace_github_cloning)
    val sCloneDone = stringResource(R.string.oc_workspace_github_clone_done)
    val sCloneFailed = stringResource(R.string.oc_workspace_github_clone_failed)
    val sReposError = stringResource(R.string.oc_workspace_github_repos_error)
    val sUnknownError = stringResource(R.string.oc_workspace_github_unknown_error)

    fun format(template: String, value: Any): String =
        String.format(formatLocale, template, value)

    fun report(line: String) {
        // Shell output arrives on a reader thread; marshal state writes back to Main.
        scope.launch(Dispatchers.Main) {
            output = (output + line + "\n").takeLast(8000)
        }
    }

    fun fail(message: String) {
        error = message
        outputExpanded = true
    }

    fun clearDeviceCode() {
        userCode = ""
        verificationUri = ""
        expiresAt = 0L
        tick = SystemClock.uptimeMillis()
    }

    fun deviceError(ex: DeviceFlowException): String {
        val detail = (ex.message ?: "").take(160)
        return when (ex.failure) {
            DeviceFlowFailure.NoCode -> format(sNoCode, detail)
            DeviceFlowFailure.Http -> format(sHttp, detail)
            DeviceFlowFailure.Tools -> sToolInstallFailed
            DeviceFlowFailure.Expired -> sExpired
            DeviceFlowFailure.Denied -> sDenied
            DeviceFlowFailure.TimedOut -> sTimedOut
            DeviceFlowFailure.Unknown -> format(sLoginFailed, detail)
        }
    }

    fun toolsInstalled(): Boolean =
        RuntimeShell.hasExecutable("gh") && RuntimeShell.hasExecutable("git")

    /** Repairs a skipped/failed bootstrap install silently, with no manual install button. */
    fun ensureTools(onDone: () -> Unit) {
        OpenCodeBootstrap.ensureGitHubTools(context) {
            toolsReady = toolsInstalled()
            onDone()
        }
    }

    fun refreshState() {
        if (!toolsInstalled()) {
            toolsReady = false
            status = sNotInstalled
            account = ""
            return
        }
        toolsReady = true
        scope.launch(Dispatchers.IO) {
            val gh = RuntimeShell.executable("gh")
            val auth = RuntimeShell.run(context, arrayOf(gh, "auth", "status", "-h", "github.com"), 30_000)
            val login = RuntimeShell.run(context, arrayOf(gh, "api", "user", "-q", ".login"), 30_000)
            withContext(Dispatchers.Main) {
                status = parseAuthStatus(auth)
                account = login.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("{") && !it.startsWith("java.") }
                    .orEmpty()
            }
        }
    }

    fun loadRepos() {
        if (!toolsInstalled()) {
            toolsReady = false
            repos = emptyList()
            reposError = null
            return
        }
        toolsReady = true
        reposLoading = true
        scope.launch(Dispatchers.IO) {
            try {
                val gh = RuntimeShell.executable("gh")
                val result = RuntimeShell.run(
                    context,
                    arrayOf(
                        gh, "repo", "list", "--limit", "100",
                        "--json", "nameWithOwner,description,url",
                    ),
                    60_000,
                )
                val parsed = parseRepos(result)
                withContext(Dispatchers.Main) {
                    if (parsed != null) {
                        repos = parsed
                        reposError = null
                    } else {
                        repos = emptyList()
                        val detail = result.lineSequence()
                            .firstOrNull { it.isNotBlank() && !it.startsWith("java.") }
                            ?.trim()
                            ?.take(160)
                            .orEmpty()
                        reposError = format(sReposError, detail.ifEmpty { sUnknownError })
                    }
                }
            } finally {
                withContext(Dispatchers.Main + NonCancellable) { reposLoading = false }
            }
        }
    }

    fun login() {
        if (work != null) return
        error = null
        output = ""
        work = Work.Login
        // LAZY keeps the job handle available to the Cancel button before the first line of work.
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                withContext(Dispatchers.Main) { status = sFetchingCode }
                if (!toolsInstalled()) withContext(Dispatchers.Main) { status = sInstallingBeforeLogin }
                OpenCodeBootstrap.ensureGitHubToolsBlocking(context)
                if (!toolsInstalled()) throw DeviceFlowException(DeviceFlowFailure.Tools)
                withContext(Dispatchers.Main) { toolsReady = true }

                val device = requestDeviceCode()
                withContext(Dispatchers.Main) {
                    userCode = device.userCode
                    verificationUri = device.verificationUri
                    expiresAt = SystemClock.uptimeMillis() + device.expiresIn * 1000L
                    tick = SystemClock.uptimeMillis()
                    status = sWaitingAuth
                    copyText(context, device.userCode)
                    openExternal(context, device.verificationUri)
                }

                // The token only lives on this coroutine's stack until it is handed to gh.
                val token = pollAccessToken(device)
                val gh = RuntimeShell.executable("gh")
                val authOutput = RuntimeShell.runWithInput(
                    context,
                    token,
                    arrayOf(gh, "auth", "login", "--with-token", "-h", "github.com"),
                    60_000,
                ) { line -> report(line) }
                RuntimeShell.run(
                    context,
                    arrayOf(gh, "auth", "setup-git"),
                    60_000,
                ) { line -> report(line) }

                val loggedIn = ghLoggedIn(context)
                withContext(Dispatchers.Main) {
                    clearDeviceCode()
                    status = ""
                    if (loggedIn) {
                        refreshState()
                        loadRepos()
                    } else {
                        val detail = authOutput.lineSequence()
                            .firstOrNull { it.isNotBlank() }
                            ?.trim()
                            .orEmpty()
                        fail(
                            if (detail.isEmpty() || detail.startsWith("java.")) sLoginNotRecorded
                            else format(sLoginFailed, detail.take(160)),
                        )
                    }
                }
            } catch (ex: CancellationException) {
                withContext(Dispatchers.Main + NonCancellable) {
                    clearDeviceCode()
                    status = sLoginCancelled
                }
            } catch (ex: DeviceFlowException) {
                withContext(Dispatchers.Main + NonCancellable) {
                    clearDeviceCode()
                    status = ""
                    fail(deviceError(ex))
                }
            } catch (ex: Exception) {
                withContext(Dispatchers.Main + NonCancellable) {
                    clearDeviceCode()
                    status = ""
                    fail(format(sLoginFailed, (ex.message ?: ex.toString()).take(160)))
                }
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    work = null
                    loginJob = null
                }
            }
        }
        loginJob = job
        job.start()
    }

    fun logout() {
        if (work != null) return
        error = null
        work = Work.Logout
        scope.launch(Dispatchers.IO) {
            try {
                RuntimeShell.run(
                    context,
                    arrayOf(RuntimeShell.executable("gh"), "auth", "logout", "-h", "github.com"),
                    60_000,
                ) { line -> report(line) }
                withContext(Dispatchers.Main) {
                    repos = emptyList()
                    account = ""
                    status = ""
                    refreshState()
                }
            } catch (ex: Exception) {
                withContext(Dispatchers.Main) {
                    fail(format(sLoginFailed, (ex.message ?: ex.toString()).take(160)))
                }
            } finally {
                withContext(Dispatchers.Main + NonCancellable) { work = null }
            }
        }
    }

    fun clone(value: String, label: String? = null) {
        if (work != null) return
        val url = normalizeCloneUrl(value)
        if (url.isEmpty()) {
            fail(sInvalidRepo)
            return
        }
        if (!cloneDirectory.isDirectory || !cloneDirectory.canWrite()) {
            fail(sInvalidDestination)
            return
        }
        error = null
        output = ""
        work = Work.Clone
        cloningTarget = label ?: url
        scope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { status = format(sCloning, label ?: url) }
                val result = RuntimeShell.runInDirectory(
                    context,
                    cloneDirectory,
                    arrayOf(
                        "env", "GIT_TERMINAL_PROMPT=0",
                        RuntimeShell.executable("git"), "clone", "--progress", "--", url,
                    ),
                    900_000,
                ) { line -> report(line) }
                val cloned = File(cloneDirectory, repoNameFromUrl(url))
                val ok = File(cloned, ".git").isDirectory &&
                    !result.contains("fatal:", ignoreCase = true)
                withContext(Dispatchers.Main) {
                    status = ""
                    if (ok) {
                        repo = ""
                        output += sCloneDone + "\n"
                    } else {
                        fail(sCloneFailed)
                    }
                }
            } catch (ex: Exception) {
                withContext(Dispatchers.Main) {
                    status = ""
                    fail(sCloneFailed)
                }
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    work = null
                    cloningTarget = null
                }
            }
        }
    }

    LaunchedEffect(expiresAt) {
        // Ticks only while a device code is live; no timer runs on the signed-in screen.
        while (expiresAt > 0L) {
            tick = SystemClock.uptimeMillis()
            if (tick >= expiresAt) break
            delay(1000L)
        }
    }
    val secondsLeft = if (expiresAt == 0L) 0L else ((expiresAt - tick) / 1000L).coerceAtLeast(0L)
    val minutes = secondsLeft / 60L
    val seconds = secondsLeft % 60L
    val countdown = String.format(formatLocale, "%02d:%02d", minutes, seconds)

    LaunchedEffect(Unit) {
        // Bootstrap normally installs these; this repairs an older/skipped install silently.
        ensureTools {
            refreshState()
            loadRepos()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.oc_workspace_github_title)) },
                actions = {
                    TextButton(
                        onClick = {
                            ensureTools {
                                refreshState()
                                loadRepos()
                            }
                        },
                        enabled = work == null,
                    ) { Text(stringResource(R.string.oc_workspace_github_reload)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            error?.let { message ->
                ErrorBanner(message, stringResource(R.string.oc_workspace_dismiss)) { error = null }
                Spacer(Modifier.height(12.dp))
            }
            if (!toolsReady && work == null) {
                Text(sNotInstalled, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
            }

            StatusBadge(
                text = when {
                    account.isNotEmpty() -> format(
                        stringResource(R.string.oc_workspace_github_account),
                        account,
                    )
                    status.isNotEmpty() -> status
                    else -> stringResource(R.string.oc_workspace_github_not_logged_in)
                },
                positive = account.isNotEmpty(),
            )
            Spacer(Modifier.height(12.dp))

            SectionCard(stringResource(R.string.oc_workspace_github_sign_in_title)) {
                when {
                    userCode.isNotEmpty() -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.oc_workspace_github_enter_code),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SelectionContainer {
                                Text(
                                    userCode,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(sWaitingAuth, style = MaterialTheme.typography.bodySmall)
                        Text(
                            stringResource(R.string.oc_workspace_github_code_expires, countdown),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            verificationUri,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalButton(onClick = { copyText(context, userCode) }) {
                                Text(stringResource(R.string.oc_workspace_github_copy))
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { openExternal(context, verificationUri) }) {
                                Text(stringResource(R.string.oc_workspace_github_open_verification))
                            }
                        }
                        TextButton(
                            onClick = { loginJob?.cancel() },
                            enabled = work == Work.Login,
                        ) { Text(stringResource(R.string.oc_workspace_cancel)) }
                    }
                    account.isNotEmpty() -> {
                        Text(
                            format(stringResource(R.string.oc_workspace_github_account), account),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = { logout() },
                            enabled = work == null,
                        ) { Text(stringResource(R.string.oc_workspace_github_logout)) }
                    }
                    else -> {
                        Text(
                            stringResource(R.string.oc_workspace_github_sign_in_hint),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { login() },
                            enabled = work == null,
                        ) { Text(stringResource(R.string.oc_workspace_github_login)) }
                    }
                }
                if (work == Work.Login && userCode.isEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(status, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { loginJob?.cancel() }) {
                        Text(stringResource(R.string.oc_workspace_cancel))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            SectionCard(stringResource(R.string.oc_workspace_github_clone_title)) {
                val normalized = normalizeCloneUrl(repo)
                Text(
                    stringResource(
                        R.string.oc_workspace_github_clone_into,
                        cloneDirectory.absolutePath,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = repo,
                    onValueChange = { repo = it },
                    label = { Text(stringResource(R.string.oc_workspace_github_repo)) },
                    placeholder = { Text("owner/repo") },
                    supportingText = {
                        Text(
                            if (normalized.isEmpty()) {
                                stringResource(R.string.oc_workspace_github_clone_hint)
                            } else {
                                format(
                                    stringResource(R.string.oc_workspace_github_clone_preview),
                                    normalized,
                                )
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = {
                        if (work == null && normalized.isNotEmpty()) clone(repo)
                    }),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { clone(repo) },
                    enabled = work == null && toolsReady && normalized.isNotEmpty(),
                ) { Text(stringResource(R.string.oc_workspace_github_run)) }
            }
            Spacer(Modifier.height(12.dp))

            SectionCard(stringResource(R.string.oc_workspace_github_repos)) {
                OutlinedTextField(
                    value = repoFilter,
                    onValueChange = { repoFilter = it },
                    label = { Text(stringResource(R.string.oc_workspace_github_filter_repos)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (reposLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                reposError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                val visible = repos.filter {
                    it.nameWithOwner.contains(repoFilter.trim(), ignoreCase = true) ||
                        it.description.contains(repoFilter.trim(), ignoreCase = true)
                }
                if (!reposLoading && reposError == null && visible.isEmpty()) {
                    Text(
                        stringResource(R.string.oc_workspace_github_no_repos),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                visible.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.nameWithOwner, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (item.description.isNotBlank()) {
                                Text(
                                    item.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        if (cloningTarget == item.url) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(
                                onClick = { clone(item.url, item.nameWithOwner) },
                                enabled = work == null && toolsReady,
                            ) { Text(stringResource(R.string.oc_workspace_github_run)) }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            Spacer(Modifier.height(12.dp))

            TextButton(onClick = { outputExpanded = !outputExpanded }) {
                Text(
                    stringResource(
                        if (outputExpanded) R.string.oc_workspace_github_hide_output
                        else R.string.oc_workspace_github_show_output,
                    ),
                )
            }
            if (outputExpanded) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        output.ifEmpty { stringResource(R.string.oc_workspace_github_no_output) },
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun StatusBadge(text: String, positive: Boolean) {
    val container = if (positive) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.secondaryContainer
    val content = if (positive) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(shape = RoundedCornerShape(12.dp), color = container) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = content,
        )
    }
}

@Composable
private fun ErrorBanner(message: String, dismissLabel: String, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onDismiss) {
                Text(dismissLabel, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

private fun copyText(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("GitHub device code", text))
}

private fun openExternal(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun ghLoggedIn(context: Context): Boolean = RuntimeShell.run(
    context,
    arrayOf(RuntimeShell.executable("gh"), "auth", "status", "-h", "github.com"),
    30_000,
).contains("Logged in to github.com", ignoreCase = true)

/** Matches the fork: the folder the file manager had open, or the Termux home as fallback. */
private fun cloneDirectory(targetDirectory: String?): File {
    if (!targetDirectory.isNullOrBlank()) {
        val candidate = File(targetDirectory)
        if (candidate.isDirectory && candidate.canWrite()) return candidate
    }
    return RuntimeStorage.home()
}
