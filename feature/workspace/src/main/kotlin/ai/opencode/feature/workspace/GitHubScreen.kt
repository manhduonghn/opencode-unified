package ai.opencode.feature.workspace

import ai.opencode.core.diagnostics.HidesFromScreenshots
import ai.opencode.core.log.AppLog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.termux.app.OpenCodeBootstrap
import com.termux.app.RuntimeShell
import com.termux.app.RuntimeStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** OAuth client used by the fork's device-code flow. It is public, not a secret. */
private const val CLIENT_ID = "178c6fc778ccc68e1d6a"
private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
private const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
private const val DEVICE_VERIFY_URL = "https://github.com/login/device"
/**
 * The same full scope set the fork asks for. GitHub rejects the whole request if one scope is
 * unknown, so keep exactly the documented scopes below.
 */
private const val SCOPES =
    "repo workflow write:packages read:packages delete:packages security_events " +
        "admin:repo_hook write:repo_hook read:repo_hook " +
        "admin:org write:org read:org " +
        "admin:public_key write:public_key read:public_key " +
        "admin:gpg_key write:gpg_key read:gpg_key " +
        "admin:ssh_signing_key write:ssh_signing_key read:ssh_signing_key " +
        "gist notifications user read:user user:email user:follow " +
        "project read:project delete_repo " +
        "write:discussion read:discussion codespace copilot"

/**
 * Compose replacement for the fork's `GitHubActivity`.
 *
 * This is the only GitHub surface: it is opened from the file browser's GitHub button, which passes
 * the folder currently on screen as the clone destination. Sign-in is the fork's OAuth device-code
 * flow; the token is piped straight into `gh auth login --with-token` and never saved by the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubScreen(targetDirectory: String? = null, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("") }
    var account by rememberSaveable { mutableStateOf("") }
    var cloneStatus by rememberSaveable { mutableStateOf("") }
    var userCode by remember { mutableStateOf("") }
    var verificationUri by remember { mutableStateOf("") }
    var codeHint by remember { mutableStateOf("") }
    var repo by rememberSaveable { mutableStateOf("") }
    var repos by remember { mutableStateOf<List<Repo>>(emptyList()) }
    var reposError by remember { mutableStateOf<String?>(null) }
    var output by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var toolsReady by remember { mutableStateOf(false) }
    var showOutput by remember { mutableStateOf(false) }

    val cloneDirectory = remember(targetDirectory) { cloneDirectory(targetDirectory) }

    val sNotLoggedIn = stringResource(R.string.oc_workspace_github_not_logged_in)
    val sSignedInAs = stringResource(R.string.oc_workspace_github_account)
    val sLogin = stringResource(R.string.oc_workspace_github_login)
    val sLogout = stringResource(R.string.oc_workspace_github_logout)
    val sReload = stringResource(R.string.oc_workspace_github_reload)
    val sNotInstalled = stringResource(R.string.oc_workspace_github_not_installed)
    val sToolInstallFailed = stringResource(R.string.oc_workspace_github_tool_install_failed)
    val sFetchingCode = stringResource(R.string.oc_workspace_github_fetching_code)
    val sWaitingAuth = stringResource(R.string.oc_workspace_github_waiting_auth)
    val sEnterCode = stringResource(R.string.oc_workspace_github_enter_code)
    val sCodeHint = stringResource(R.string.oc_workspace_github_code_hint)
    val sCopy = stringResource(R.string.oc_workspace_github_copy)
    val sOpenVerification = stringResource(R.string.oc_workspace_github_open_verification)
    val sLoginFailed = stringResource(R.string.oc_workspace_github_login_failed)
    val sLoginNotRecorded = stringResource(R.string.oc_workspace_github_login_not_recorded)
    val sErrorNoCode = stringResource(R.string.oc_workspace_github_error_no_code)
    val sErrorExpired = stringResource(R.string.oc_workspace_github_error_expired)
    val sErrorDenied = stringResource(R.string.oc_workspace_github_error_denied)
    val sErrorTimeout = stringResource(R.string.oc_workspace_github_error_timeout)
    val sErrorUnexpected = stringResource(R.string.oc_workspace_github_error_unexpected)
    val sErrorHttp = stringResource(R.string.oc_workspace_github_error_http)
    val sCloneInto = stringResource(R.string.oc_workspace_github_clone_into)
    val sRepoLabel = stringResource(R.string.oc_workspace_github_repo)
    val sCloneAction = stringResource(R.string.oc_workspace_github_run)
    val sInvalidRepo = stringResource(R.string.oc_workspace_github_invalid_repo)
    val sInvalidDestination = stringResource(R.string.oc_workspace_github_invalid_destination)
    val sCloning = stringResource(R.string.oc_workspace_github_cloning)
    val sCloneDone = stringResource(R.string.oc_workspace_github_clone_done)
    val sCloneFailed = stringResource(R.string.oc_workspace_github_clone_failed)
    val sRepos = stringResource(R.string.oc_workspace_github_repos)
    val sReposEmpty = stringResource(R.string.oc_workspace_github_repos_empty)
    val sReposError = stringResource(R.string.oc_workspace_github_repos_error)
    val sOutput = stringResource(R.string.oc_workspace_github_output)
    val sShowOutput = stringResource(R.string.oc_workspace_github_show_output)
    val sHideOutput = stringResource(R.string.oc_workspace_github_hide_output)
    val sWorking = stringResource(R.string.oc_workspace_github_working)

    fun format(template: String, value: Any): String =
        String.format(Locale.getDefault(), template, value)

    fun report(line: String) {
        // Shell output arrives on a reader thread; marshal state writes back to Main.
        scope.launch(Dispatchers.Main) {
            output = (output + line + "\n").takeLast(8000)
        }
    }

    fun updateTools(): Boolean {
        val ready = RuntimeShell.hasExecutable("gh") && RuntimeShell.hasExecutable("git")
        toolsReady = ready
        return ready
    }

    /** Repairs a skipped/failed bootstrap install silently; the callback runs on the UI thread. */
    fun ensureTools(onDone: () -> Unit) {
        OpenCodeBootstrap.ensureGitHubTools(context) {
            updateTools()
            onDone()
        }
    }

    suspend fun readAuth(): AuthSnapshot = withContext(Dispatchers.IO) {
        val gh = RuntimeShell.executable("gh")
        val auth = RuntimeShell.run(
            context,
            arrayOf(gh, "auth", "status", "-h", "github.com"),
            30_000,
        )
        val signedIn = auth.contains("Logged in to github.com", ignoreCase = true)
        val login = if (signedIn) {
            parseAccount(RuntimeShell.run(context, arrayOf(gh, "api", "user", "-q", ".login"), 30_000))
        } else {
            ""
        }
        AuthSnapshot(parseAuthStatus(auth), login)
    }

    suspend fun readRepos(): RepoSnapshot = withContext(Dispatchers.IO) {
        val result = RuntimeShell.run(
            context,
            arrayOf(
                RuntimeShell.executable("gh"), "repo", "list", "--limit", "100",
                "--json", "nameWithOwner,description,url",
            ),
            60_000,
        )
        val parsed = parseRepos(result)
        if (parsed != null) {
            RepoSnapshot(parsed, null)
        } else {
            RepoSnapshot(emptyList(), repoError(result, format(sReposError, "")))
        }
    }

    fun applyAuth(snapshot: AuthSnapshot) {
        status = snapshot.detail
        account = snapshot.login
    }

    fun refresh() {
        ensureTools {
            val ready = updateTools()
            if (!ready) {
                status = sToolInstallFailed
                account = ""
                repos = emptyList()
                reposError = null
            } else {
                scope.launch(Dispatchers.Main) {
                    applyAuth(readAuth())
                    val reposResult = readRepos()
                    repos = reposResult.repos
                    reposError = reposResult.error
                    AppLog.app.info("github repos refreshed: count=${reposResult.repos.size}")
                }
            }
        }
    }

    fun loginError(ex: GitHubLoginException): String = when (ex.failure) {
        GitHubLoginFailure.MissingCode -> format(sErrorNoCode, ex.detail)
        GitHubLoginFailure.Expired -> sErrorExpired
        GitHubLoginFailure.Denied -> sErrorDenied
        GitHubLoginFailure.Timeout -> sErrorTimeout
        GitHubLoginFailure.Unexpected -> format(sErrorUnexpected, ex.detail)
        GitHubLoginFailure.Http -> format(sErrorHttp, ex.detail)
    }

    fun login() {
        if (busy || !toolsReady) return
        scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                busy = true
                error = null
                output = ""
                showOutput = false
                status = sFetchingCode
            }
            AppLog.app.info("github login started")
            try {
                val device = requestDeviceCode()
                withContext(Dispatchers.Main) {
                    userCode = device.userCode
                    verificationUri = device.verificationUri
                    codeHint = format(sCodeHint, maxOf(1L, device.expiresIn / 60L).toString())
                    // The code itself is only in the non-saveable state below, never in `status`.
                    status = sWaitingAuth
                    copyText(context, device.userCode)
                    openExternal(context, device.verificationUri)
                }
                AppLog.app.info("github login device code issued")

                // The token only lives on this coroutine's stack until it is handed to gh.
                val token = pollAccessToken(device)
                val gh = RuntimeShell.executable("gh")
                RuntimeShell.runWithInput(
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
                AppLog.app.info("github login token handed to gh")

                val snapshot = readAuth()
                val reposResult = readRepos()
                withContext(Dispatchers.Main) {
                    busy = false
                    applyAuth(snapshot)
                    repos = reposResult.repos
                    reposError = reposResult.error
                    userCode = ""
                    verificationUri = ""
                    codeHint = ""
                    cloneStatus = ""
                    if (snapshot.login.isEmpty() &&
                        snapshot.detail.contains("Logged in to github.com", ignoreCase = true)
                    ) {
                        error = sLoginNotRecorded
                    }
                }
                AppLog.app.info("github login completed: repos=${reposResult.repos.size}")
            } catch (ex: GitHubLoginException) {
                AppLog.app.info("github login failed: ${ex.failure.name}")
                withContext(Dispatchers.Main) {
                    busy = false
                    error = format(sLoginFailed, loginError(ex))
                    showOutput = true
                }
            } catch (ex: Exception) {
                AppLog.app.info("github login failed: ${ex.javaClass.simpleName}")
                withContext(Dispatchers.Main) {
                    busy = false
                    error = format(sLoginFailed, format(sErrorUnexpected, ""))
                    showOutput = true
                }
            }
        }
    }

    fun logout() {
        if (busy || !toolsReady) return
        scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                busy = true
                error = null
                output = ""
                showOutput = false
            }
            AppLog.app.info("github logout started")
            RuntimeShell.run(
                context,
                arrayOf(RuntimeShell.executable("gh"), "auth", "logout", "-h", "github.com"),
                60_000,
            ) { line -> report(line) }
            val snapshot = readAuth()
            withContext(Dispatchers.Main) {
                busy = false
                repos = emptyList()
                reposError = null
                cloneStatus = ""
                applyAuth(snapshot)
            }
            AppLog.app.info("github logout completed")
        }
    }

    fun clone(value: String, label: String? = null) {
        if (busy || !toolsReady) return
        val url = normalizeCloneUrl(value)
        if (url.isEmpty()) {
            error = sInvalidRepo
            return
        }
        if (!cloneDirectory.isDirectory || !cloneDirectory.canWrite()) {
            error = sInvalidDestination
            return
        }
        scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                busy = true
                error = null
                output = ""
                showOutput = false
                cloneStatus = format(sCloning, label ?: url)
            }
            AppLog.app.info("github clone started")
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
            val ok = cloned.isDirectory && !result.contains("fatal:", ignoreCase = true)
            AppLog.app.info(if (ok) "github clone succeeded" else "github clone failed")
            withContext(Dispatchers.Main) {
                busy = false
                cloneStatus = if (ok) sCloneDone else sCloneFailed
                if (ok) {
                    repo = ""
                } else {
                    error = sCloneFailed
                    showOutput = true
                }
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // The device code is a short-lived credential; keep the whole screen out of screenshots while
    // it is visible. The code is still copied to the clipboard only on an explicit tap.
    HidesFromScreenshots(userCode.isNotEmpty())

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.oc_workspace_github_title)) }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (error != null) {
                Text(error!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        if (account.isEmpty()) sNotLoggedIn else format(sSignedInAs, account),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (status.isNotEmpty()) {
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { login() }, enabled = !busy && toolsReady) {
                            Text(sLogin)
                        }
                        OutlinedButton(
                            onClick = { logout() },
                            enabled = !busy && toolsReady && account.isNotEmpty(),
                        ) {
                            Text(sLogout)
                        }
                        TextButton(onClick = { refresh() }, enabled = !busy) {
                            Text(sReload)
                        }
                    }
                    if (!toolsReady) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            sNotInstalled,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (userCode.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(format(sEnterCode, userCode), style = MaterialTheme.typography.titleSmall)
                        Text(verificationUri, style = MaterialTheme.typography.bodySmall)
                        Text(codeHint, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { copyText(context, userCode) }) { Text(sCopy) }
                            OutlinedButton(onClick = { openExternal(context, verificationUri) }) {
                                Text(sOpenVerification)
                            }
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        format(sCloneInto, cloneDirectory.absolutePath),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = repo,
                        onValueChange = { repo = it },
                        label = { Text(sRepoLabel) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                        enabled = !busy && toolsReady,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { clone(repo) },
                        enabled = !busy && toolsReady && repo.isNotBlank(),
                    ) {
                        Text(sCloneAction)
                    }
                    if (cloneStatus.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(cloneStatus, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(sRepos, style = MaterialTheme.typography.titleMedium)
                    when {
                        !reposError.isNullOrBlank() -> {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                reposError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        repos.isEmpty() -> {
                            Spacer(Modifier.height(8.dp))
                            Text(sReposEmpty, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    repos.forEach { item ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(
                                item.nameWithOwner,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (item.description.isNotBlank()) {
                                Text(
                                    item.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(
                                onClick = { clone(item.url, item.nameWithOwner) },
                                enabled = !busy && toolsReady,
                            ) {
                                Text(sCloneAction)
                            }
                        }
                    }
                }
            }

            if (busy) {
                Text(sWorking, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { showOutput = !showOutput }) {
                Text(if (showOutput) sHideOutput else sOutput)
            }
            if (showOutput) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        output,
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun requestDeviceCode(): DeviceCode {
    val json = JSONObject(
        httpPostForm(
            DEVICE_CODE_URL,
            mapOf("client_id" to CLIENT_ID, "scope" to SCOPES),
        ),
    )
    val deviceCode = json.optString("device_code", "")
    val userCode = json.optString("user_code", "")
    if (deviceCode.isEmpty() || userCode.isEmpty()) {
        val detail = json.optString("error_description", json.optString("error", ""))
        throw GitHubLoginException(GitHubLoginFailure.MissingCode, detail.take(200))
    }
    return DeviceCode(
        deviceCode = deviceCode,
        userCode = userCode,
        verificationUri = json.optString("verification_uri", DEVICE_VERIFY_URL),
        interval = maxOf(5, json.optInt("interval", 5)),
        expiresIn = maxOf(60L, json.optLong("expires_in", 900L)),
    )
}

private suspend fun pollAccessToken(device: DeviceCode): String {
    val deadline = SystemClock.uptimeMillis() + device.expiresIn * 1000L
    var interval = maxOf(5, device.interval)
    while (SystemClock.uptimeMillis() < deadline) {
        val json = JSONObject(
            httpPostForm(
                ACCESS_TOKEN_URL,
                mapOf(
                    "client_id" to CLIENT_ID,
                    "device_code" to device.deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                ),
            ),
        )
        val token = json.optString("access_token", "")
        if (token.isNotEmpty()) return token

        when (json.optString("error", "")) {
            "authorization_pending" -> delay(interval * 1000L)
            "slow_down" -> {
                interval += 5
                delay(interval * 1000L)
            }
            "expired_token" -> throw GitHubLoginException(GitHubLoginFailure.Expired)
            "access_denied" -> throw GitHubLoginException(GitHubLoginFailure.Denied)
            else -> throw GitHubLoginException(
                GitHubLoginFailure.Unexpected,
                json.optString("error_description", "").take(200),
            )
        }
    }
    throw GitHubLoginException(GitHubLoginFailure.Timeout)
}

private fun httpPostForm(url: String, params: Map<String, String>): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.connectTimeout = 20_000
        connection.readTimeout = 20_000
        connection.doOutput = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.setRequestProperty("User-Agent", "Termux-OpenCode")

        val body = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val code = connection.responseCode
        val stream = if (code >= 400) connection.errorStream else connection.inputStream
        if (stream == null) throw GitHubLoginException(GitHubLoginFailure.Http, code.toString())
        val response = stream.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
        }
        if (response.isBlank()) throw GitHubLoginException(GitHubLoginFailure.Http, code.toString())
        return response
    } finally {
        connection.disconnect()
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

/** Matches the fork: the folder the file manager had open, or the Termux home as fallback. */
private fun cloneDirectory(targetDirectory: String?): File {
    if (!targetDirectory.isNullOrBlank()) {
        val candidate = File(targetDirectory)
        if (candidate.isDirectory && candidate.canWrite()) return candidate
    }
    return RuntimeStorage.home()
}
