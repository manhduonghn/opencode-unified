package ai.opencode.feature.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
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
import androidx.compose.ui.unit.dp
import com.termux.app.OpenCodeBootstrap
import com.termux.app.RuntimeShell
import com.termux.app.RuntimeStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
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

private data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val interval: Int,
    val expiresIn: Long,
)

private data class Repo(
    val nameWithOwner: String,
    val description: String,
    val url: String,
)

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

    var status by rememberSaveable { mutableStateOf("") }
    var account by rememberSaveable { mutableStateOf("") }
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

    val cloneDirectory = remember(targetDirectory) { cloneDirectory(targetDirectory) }
    val notInstalledText = stringResource(R.string.oc_workspace_github_not_installed)
    // Resolve strings in composition so a configuration change cannot leave a stale Context.
    val sFetchingCode = stringResource(R.string.oc_workspace_github_fetching_code)
    val sWaitingAuth = stringResource(R.string.oc_workspace_github_waiting_auth)
    val sInstallingBeforeLogin = stringResource(R.string.oc_workspace_github_installing_before_login)
    val sToolInstallFailed = stringResource(R.string.oc_workspace_github_tool_install_failed)
    val sEnterCode = stringResource(R.string.oc_workspace_github_enter_code)
    val sCodeHint = stringResource(R.string.oc_workspace_github_code_hint)
    val sLoginNotRecorded = stringResource(R.string.oc_workspace_github_login_not_recorded)
    val sLoginFailed = stringResource(R.string.oc_workspace_github_login_failed)
    val sInvalidRepo = stringResource(R.string.oc_workspace_github_invalid_repo)
    val sInvalidDestination = stringResource(R.string.oc_workspace_github_invalid_destination)
    val sCloning = stringResource(R.string.oc_workspace_github_cloning)
    val sCloneDone = stringResource(R.string.oc_workspace_github_clone_done)
    val sCloneFailed = stringResource(R.string.oc_workspace_github_clone_failed)
    val sReposError = stringResource(R.string.oc_workspace_github_repos_error)

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

    /** Repairs a skipped/failed bootstrap install silently, with no manual install button. */
    fun ensureTools(onDone: () -> Unit) {
        OpenCodeBootstrap.ensureGitHubTools(context) {
            updateTools()
            onDone()
        }
    }

    fun refreshState() {
        if (!updateTools()) {
            status = notInstalledText
            account = ""
            return
        }
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
        if (!updateTools()) {
            repos = emptyList()
            reposError = null
            return
        }
        scope.launch(Dispatchers.IO) {
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
                    reposError = result.lineSequence()
                        .firstOrNull { it.isNotBlank() && !it.startsWith("java.") }
                        ?.trim()
                        ?.take(200)
                        .takeUnless { it.isNullOrEmpty() }
                        ?: format(sReposError, "")
                }
            }
        }
    }

    fun login() {
        if (busy) return
        scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                busy = true
                error = null
                output = ""
                status = sFetchingCode
            }
            try {
                if (!updateTools()) {
                    withContext(Dispatchers.Main) { status = sInstallingBeforeLogin }
                }
                OpenCodeBootstrap.ensureGitHubToolsBlocking(context)
                if (!updateTools()) throw IllegalStateException(sToolInstallFailed)

                val device = requestDeviceCode()
                withContext(Dispatchers.Main) {
                    userCode = device.userCode
                    verificationUri = device.verificationUri
                    codeHint = format(sCodeHint, maxOf(1L, device.expiresIn / 60L).toString())
                    // The code itself is rendered from `userCode` in composition below.
                    status = format(sEnterCode, device.userCode)
                    copyText(context, device.userCode)
                    openExternal(context, device.verificationUri)
                }

                withContext(Dispatchers.Main) {
                    status = sWaitingAuth
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
                    busy = false
                    toolsReady = true
                    refreshState()
                    if (loggedIn) {
                        userCode = ""
                        verificationUri = ""
                        codeHint = ""
                        loadRepos()
                    } else {
                        val detail = authOutput.lineSequence()
                            .firstOrNull { it.isNotBlank() }
                            ?.trim()
                            .orEmpty()
                        error = if (detail.isEmpty() || detail.startsWith("java.")) {
                            sLoginNotRecorded
                        } else {
                            format(sLoginFailed, detail.take(200))
                        }
                    }
                }
            } catch (ex: Exception) {
                withContext(Dispatchers.Main) {
                    busy = false
                    error = format(sLoginFailed, ex.message ?: ex.toString())
                }
            }
        }
    }

    fun logout() {
        scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { busy = true }
            RuntimeShell.run(
                context,
                arrayOf(RuntimeShell.executable("gh"), "auth", "logout", "-h", "github.com"),
                60_000,
            ) { line -> report(line) }
            withContext(Dispatchers.Main) {
                busy = false
                repos = emptyList()
                account = ""
                status = ""
                refreshState()
            }
        }
    }

    fun clone(value: String, label: String? = null) {
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
                status = format(sCloning, label ?: url)
            }
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
            withContext(Dispatchers.Main) {
                busy = false
                if (ok) {
                    repo = ""
                    output += sCloneDone + "\n"
                } else {
                    error = sCloneFailed
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        // Bootstrap normally installs these; this repairs an older/skipped install silently.
        ensureTools {
            refreshState()
            loadRepos()
        }
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
            Text(status.ifEmpty { stringResource(R.string.oc_workspace_github_not_logged_in) })
            if (account.isNotEmpty()) {
                Text(stringResource(R.string.oc_workspace_github_account, account))
            }
            TextButton(onClick = {
                ensureTools {
                    refreshState()
                    loadRepos()
                }
            }) { Text(stringResource(R.string.oc_workspace_github_reload)) }
            TextButton(onClick = { login() }, enabled = !busy) {
                Text(stringResource(R.string.oc_workspace_github_login))
            }
            if (userCode.isNotEmpty()) {
                Text(stringResource(R.string.oc_workspace_github_enter_code, userCode))
                TextButton(onClick = { copyText(context, userCode) }) {
                    Text(stringResource(R.string.oc_workspace_github_copy))
                }
                Text(verificationUri)
                Text(codeHint)
                TextButton(onClick = { openExternal(context, verificationUri) }) {
                    Text(stringResource(R.string.oc_workspace_github_open_verification))
                }
            }
            TextButton(onClick = { logout() }, enabled = !busy && toolsReady) {
                Text(stringResource(R.string.oc_workspace_github_logout))
            }

            Text(stringResource(R.string.oc_workspace_github_clone_into, cloneDirectory.absolutePath))
            OutlinedTextField(
                value = repo,
                onValueChange = { repo = it },
                label = { Text(stringResource(R.string.oc_workspace_github_repo)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { clone(repo) }, enabled = !busy && toolsReady) {
                Text(stringResource(R.string.oc_workspace_github_run))
            }
            if (!toolsReady) Text(notInstalledText)
            if (error != null) Text(error!!)

            Text(stringResource(R.string.oc_workspace_github_repos))
            TextButton(onClick = { loadRepos() }, enabled = !busy && toolsReady) {
                Text(stringResource(R.string.oc_workspace_github_reload))
            }
            if (reposError != null && reposError!!.isNotBlank()) Text(reposError!!)
            repos.forEach { item ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(item.nameWithOwner, maxLines = 1)
                    if (item.description.isNotBlank()) {
                        Text(item.description, maxLines = 2)
                    }
                    TextButton(onClick = { clone(item.url, item.nameWithOwner) }, enabled = !busy) {
                        Text(stringResource(R.string.oc_workspace_github_run))
                    }
                }
            }

            if (busy) Text("…")
            Text(stringResource(R.string.oc_workspace_github_output))
            Text(output)
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
        throw IllegalStateException("GitHub không trả về mã đăng nhập: $detail")
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

        when (val code = json.optString("error", "")) {
            "authorization_pending" -> delay(interval * 1000L)
            "slow_down" -> {
                interval += 5
                delay(interval * 1000L)
            }
            "expired_token" -> throw IllegalStateException(
                "Mã đăng nhập đã hết hạn. Bấm Đăng nhập lại để lấy mã mới.",
            )
            "access_denied" -> throw IllegalStateException(
                "Bạn đã từ chối cấp quyền cho ứng dụng.",
            )
            else -> throw IllegalStateException(
                json.optString("error_description", code).ifEmpty { "Không lấy được access token." },
            )
        }
    }
    throw IllegalStateException("Hết thời gian chờ xác thực. Bấm Đăng nhập lại để lấy mã mới.")
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
        if (stream == null) throw IllegalStateException("GitHub trả về HTTP $code")
        val response = stream.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
        }
        if (response.isBlank()) throw IllegalStateException("GitHub trả về HTTP $code")
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

private fun parseAuthStatus(result: String): String {
    val line = result.lineSequence().firstOrNull { it.contains("Logged in to") }
        ?: result.lineSequence().firstOrNull { it.contains("not logged in", ignoreCase = true) }
    return line?.trim() ?: result.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}

private fun ghLoggedIn(context: Context): Boolean = RuntimeShell.run(
    context,
    arrayOf(RuntimeShell.executable("gh"), "auth", "status", "-h", "github.com"),
    30_000,
).contains("Logged in to github.com", ignoreCase = true)

private fun parseRepos(text: String): List<Repo>? = runCatching {
    val start = text.indexOf('[')
    val end = text.lastIndexOf(']')
    if (start < 0 || end <= start) return null
    val array = JSONArray(text.substring(start, end + 1))
    (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        Repo(
            nameWithOwner = item.optString("nameWithOwner", ""),
            description = item.optString("description", ""),
            url = item.optString("url", ""),
        )
    }
}.getOrNull()

/** Matches the fork: the folder the file manager had open, or the Termux home as fallback. */
private fun cloneDirectory(targetDirectory: String?): File {
    if (!targetDirectory.isNullOrBlank()) {
        val candidate = File(targetDirectory)
        if (candidate.isDirectory && candidate.canWrite()) return candidate
    }
    return RuntimeStorage.home()
}

/** Accepts full URLs, `github.com/owner/repo` and `owner/repo`, just like the fork. */
private fun normalizeCloneUrl(repo: String?): String {
    var url = repo?.trim().orEmpty()
    if (url.isEmpty()) return url
    while (url.endsWith("/")) url = url.dropLast(1)
    if (url.startsWith("github.com/") || url.startsWith("www.github.com/")) {
        url = "https://$url"
    } else if (!url.contains("://") && !url.startsWith("git@") && url.indexOf('/') > 0) {
        url = "https://github.com/$url"
    }
    if (url.startsWith("https://github.com/") && !url.endsWith(".git")) url += ".git"
    return url
}

private fun repoNameFromUrl(url: String): String {
    val name = url.substringAfterLast('/').removeSuffix(".git")
    return if (name.isEmpty()) "repository" else name
}
