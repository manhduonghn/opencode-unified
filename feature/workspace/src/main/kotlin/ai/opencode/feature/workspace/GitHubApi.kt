package ai.opencode.feature.workspace

import android.os.SystemClock
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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

internal data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val interval: Int,
    val expiresIn: Long,
)

internal data class Repo(
    val nameWithOwner: String,
    val description: String,
    val url: String,
)

/** Device-flow outcomes the UI knows how to explain without echoing raw GitHub JSON. */
internal enum class DeviceFlowFailure { NoCode, Http, Tools, Expired, Denied, TimedOut, Unknown }

internal class DeviceFlowException(
    val failure: DeviceFlowFailure,
    detail: String = "",
) : IllegalStateException(detail)

internal fun requestDeviceCode(): DeviceCode {
    val json = JSONObject(
        httpPostForm(
            DEVICE_CODE_URL,
            mapOf("client_id" to CLIENT_ID, "scope" to SCOPES),
        ),
    )
    val deviceCode = json.optString("device_code", "")
    val userCode = json.optString("user_code", "")
    if (deviceCode.isBlank() || userCode.isBlank()) {
        throw DeviceFlowException(
            DeviceFlowFailure.NoCode,
            json.optString("error_description", json.optString("error", "")),
        )
    }
    return DeviceCode(
        deviceCode = deviceCode,
        userCode = userCode,
        verificationUri = json.optString("verification_uri", DEVICE_VERIFY_URL),
        interval = maxOf(5, json.optInt("interval", 5)),
        expiresIn = maxOf(60L, json.optLong("expires_in", 900L)),
    )
}

/**
 * Polls until GitHub authorizes the device. The returned token never leaves this coroutine's
 * stack: the caller hands it straight to `gh auth login --with-token`.
 */
internal suspend fun pollAccessToken(device: DeviceCode): String {
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
        if (token.isNotBlank()) return token

        when (val code = json.optString("error", "")) {
            "authorization_pending" -> delay(interval * 1000L)
            "slow_down" -> {
                interval += 5
                delay(interval * 1000L)
            }
            "expired_token" -> throw DeviceFlowException(DeviceFlowFailure.Expired)
            "access_denied" -> throw DeviceFlowException(DeviceFlowFailure.Denied)
            else -> throw DeviceFlowException(
                if (code.startsWith("http", ignoreCase = true)) DeviceFlowFailure.Http
                else DeviceFlowFailure.Unknown,
                json.optString("error_description", code),
            )
        }
    }
    throw DeviceFlowException(DeviceFlowFailure.TimedOut)
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
        val stream = (if (code >= 400) connection.errorStream else connection.inputStream)
            ?: throw DeviceFlowException(DeviceFlowFailure.Http, "HTTP $code")
        val response = stream.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
        }
        if (response.isBlank()) throw DeviceFlowException(DeviceFlowFailure.Http, "HTTP $code")
        return response
    } catch (ex: DeviceFlowException) {
        throw ex
    } catch (ex: Exception) {
        throw DeviceFlowException(DeviceFlowFailure.Http, ex.message ?: ex.toString())
    } finally {
        connection.disconnect()
    }
}

internal fun parseAuthStatus(result: String): String =
    result.lineSequence().firstOrNull { it.contains("Logged in to") }
        ?: result.lineSequence().firstOrNull { it.contains("not logged in", ignoreCase = true) }
        ?: result.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

internal fun parseRepos(text: String): List<Repo>? = runCatching {
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

private val REPO_PART = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")

/**
 * Accepts `owner/repo`, `github.com/owner/repo` and full HTTPS/SSH GitHub URLs; rejects other
 * hosts, query strings and path traversal. Everything else becomes a canonical GitHub URL so a
 * typo cannot be passed to `git` as an unexpected flag or remote.
 */
internal fun normalizeCloneUrl(value: String?): String {
    var input = value?.trim().orEmpty()
    if (input.isEmpty() || input.any { it.isWhitespace() || it == '?' || it == '#' }) return ""

    var ssh = false
    when {
        input.startsWith("git@github.com:") -> {
            ssh = true
            input = input.removePrefix("git@github.com:")
        }
        input.startsWith("https://github.com/") -> input = input.removePrefix("https://github.com/")
        input.startsWith("https://www.github.com/") -> {
            input = input.removePrefix("https://www.github.com/")
        }
        input.startsWith("http://github.com/") -> input = input.removePrefix("http://github.com/")
        input.startsWith("www.github.com/") -> input = input.removePrefix("www.github.com/")
        input.startsWith("github.com/") -> input = input.removePrefix("github.com/")
        input.contains("://") || input.startsWith("git@") -> return ""
    }

    val parts = input.trim('/').removeSuffix(".git").split('/')
    if (parts.size != 2 || parts.any { !REPO_PART.matches(it) }) return ""
    val slug = "${parts[0]}/${parts[1]}"
    return if (ssh) "git@github.com:$slug.git" else "https://github.com/$slug.git"
}

internal fun repoNameFromUrl(url: String): String =
    url.substringAfterLast('/')
        .substringAfterLast(':')
        .removeSuffix(".git")
        .ifEmpty { "repository" }
