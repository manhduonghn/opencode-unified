package ai.opencode.feature.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Device-code response. The device code itself is only used for the token exchange, never shown. */
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

internal data class AuthSnapshot(
    val detail: String,
    val login: String,
)

internal data class RepoSnapshot(
    val repos: List<Repo>,
    val error: String?,
)

/** Failure kinds carry no localized text; the screen maps them to resources. */
internal enum class GitHubLoginFailure {
    MissingCode,
    Expired,
    Denied,
    Timeout,
    Unexpected,
    Http,
}

internal class GitHubLoginException(
    val failure: GitHubLoginFailure,
    val detail: String = "",
) : IllegalStateException(if (detail.isBlank()) failure.name else detail)

/** Accepts full URLs, `github.com/owner/repo` and `owner/repo`, just like the fork. */
internal fun normalizeCloneUrl(repo: String?): String {
    var url = repo?.trim().orEmpty()
    if (url.isEmpty()) return url
    while (url.endsWith("/")) url = url.dropLast(1)
    when {
        url.startsWith("github.com/") || url.startsWith("www.github.com/") -> url = "https://$url"
        !url.contains("://") && !url.startsWith("git@") -> {
            // A bare "owner" is not a repository; let the screen show its validation error.
            if (url.indexOf('/') <= 0) return ""
            url = "https://github.com/$url"
        }
    }
    if (url.startsWith("https://github.com/") && !url.endsWith(".git")) url += ".git"
    return url
}

internal fun repoNameFromUrl(url: String): String {
    val name = url.substringAfterLast('/').removeSuffix(".git")
    return if (name.isEmpty()) "repository" else name
}

internal fun parseRepos(text: String): List<Repo>? = runCatching {
    val start = text.indexOf('[')
    val end = text.lastIndexOf(']')
    if (start < 0 || end <= start) return null
    val array = Json.parseToJsonElement(text.substring(start, end + 1)).jsonArray
    array.map { element ->
        val item = element.jsonObject
        Repo(
            nameWithOwner = item.stringOrEmpty("nameWithOwner"),
            description = item.stringOrEmpty("description"),
            url = item.stringOrEmpty("url"),
        )
    }
}.getOrNull()

private fun JsonObject.stringOrEmpty(key: String): String {
    return when (val value = this[key]) {
        is JsonNull -> ""
        is JsonPrimitive -> value.content
        else -> ""
    }
}

internal fun parseAuthStatus(result: String): String {
    val line = result.lineSequence().firstOrNull { it.contains("Logged in to") }
        ?: result.lineSequence().firstOrNull { it.contains("not logged in", ignoreCase = true) }
    return line?.trim()
        ?: result.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}

/** `gh api user -q .login` prints just the login; skip JSON wrappers and stack-trace lines. */
internal fun parseAccount(result: String): String = result.lineSequence()
    .map { it.trim() }
    .firstOrNull { line ->
        line.isNotEmpty() &&
            !line.startsWith("{") &&
            !line.startsWith("}") &&
            !line.startsWith("\"") &&
            !line.startsWith("java.") &&
            !line.contains(':')
    }
    .orEmpty()

internal fun repoError(text: String, fallback: String): String = text.lineSequence()
    .firstOrNull { it.isNotBlank() && !it.startsWith("java.") }
    ?.trim()
    ?.take(200)
    .takeUnless { it.isNullOrEmpty() }
    ?: fallback
