package ai.opencode.server.model

import ai.opencode.server.internal.ElementSerializer
import ai.opencode.server.internal.UrlParts
import ai.opencode.server.internal.lenient
import ai.opencode.server.internal.obj
import ai.opencode.server.internal.str
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Project icon metadata (`url`, `override`, `color`); each field decodes leniently and trims to null. */
@Serializable(with = OpenCodeProjectIconSerializer::class)
class OpenCodeProjectIcon(url: String? = null, override: String? = null, color: String? = null) {
    val url: String? = nonEmpty(url)
    val override: String? = nonEmpty(override)
    val color: String? = nonEmpty(color)

    /** Desktop precedence: user `override`, a chosen `color` tile (null), the built-in OpenCode icon, then `url`. */
    val resolvedSource: String? get() = ProjectMark.avatarSource(null, this)

    val isEmpty: Boolean get() = url == null && override == null && color == null

    override fun equals(other: Any?): Boolean =
        other is OpenCodeProjectIcon && other.url == url && other.override == override && other.color == color

    override fun hashCode(): Int = listOf(url, override, color).hashCode()
    override fun toString(): String = "OpenCodeProjectIcon(url=$url, override=$override, color=$color)"

    private companion object {
        fun nonEmpty(value: String?): String? = value?.trimmedWhitespace()?.ifEmpty { null }
    }
}

internal object OpenCodeProjectIconSerializer : ElementSerializer<OpenCodeProjectIcon>(
    decode = { element ->
        val o = element.obj()
        OpenCodeProjectIcon(o.lenient("url") { it.str() }, o.lenient("override") { it.str() }, o.lenient("color") { it.str() })
    },
    encode = { icon ->
        JsonObject(buildMap {
            icon.url?.let { put("url", JsonPrimitive(it)) }
            icon.override?.let { put("override", JsonPrimitive(it)) }
            icon.color?.let { put("color", JsonPrimitive(it)) }
        })
    },
)

/** Deterministic project name, initial and source precedence shared with desktop (`@opencode/ui/project-avatar`). */
object ProjectMark {
    /** Project IDs for the OpenCode repository. */
    val openCodeProjectIDs: Set<String> = setOf(
        "012780c4098d08caa4ea8c479ed0a4690489f38d",
        "476fbfe6befa9e63ddd7f9ae71655999a0104256",
        "4b0ea68d7af9a6031a7ffda7ad66e0cb83315750",
    )

    const val OPEN_CODE_FAVICON_URL = "https://opencode.ai/favicon.svg"

    fun avatarSource(projectID: String? = null, icon: OpenCodeProjectIcon?): String? {
        icon?.override?.takeIf { it.isNotEmpty() }?.let { return it }
        if (!icon?.color.isNullOrEmpty()) return null
        if (projectID != null && projectID in openCodeProjectIDs) return OPEN_CODE_FAVICON_URL
        return icon?.url
    }

    /** A clean repository name from a git remote URL or plain name (`git@github.com:anomalyco/hex.git` → `hex`). */
    fun repositorySlug(raw: String?): String? {
        val value = raw?.trimmedWhitespace()?.ifEmpty { null } ?: return null
        if (!value.contains("/") && !value.contains(":")) return value
        if (value.lowercase().startsWith("file:")) return null
        if (value.contains("://")) {
            val parts = UrlParts.parse(value) ?: return null
            if (parts.host.isNullOrEmpty()) return null
            return lastPathSegment(parts.path)
        }
        val colon = value.indexOf(':')
        if (colon >= 0) {
            val head = value.substring(0, colon)
            val path = value.substring(colon + 1)
            val at = head.indexOf('@')
            if (at <= 0 || at + 1 >= head.length || head.contains("/")) return null
            return lastPathSegment(path)
        }
        return lastPathSegment(value)
    }

    private fun lastPathSegment(rawPath: String): String? {
        var cleaned = rawPath.trimmedWhitespace().trim('/')
        if (cleaned.lowercase().endsWith(".git")) cleaned = cleaned.dropLast(4).trimEnd('/')
        if (cleaned.isEmpty()) return null
        val tail = cleaned.split("/").lastOrNull { it.isNotEmpty() }?.trimmedWhitespace()
        return tail?.ifEmpty { null }
    }

    /** The folder basename of a path. */
    fun folderName(path: String): String {
        var trimmed = path.trimmedWhitespace()
        while (trimmed.length > 1 && (trimmed.endsWith("/") || trimmed.endsWith("\\"))) trimmed = trimmed.dropLast(1)
        val last = trimmed.split('/', '\\').lastOrNull { it.isNotEmpty() } ?: return path
        return last
    }

    /** Explicit `name`, git `repository` name, or the folder basename. */
    fun displayName(name: String?, repository: String?, canonical: String): String {
        name?.trimmedWhitespace()?.takeIf { it.isNotEmpty() }?.let { return it }
        repositorySlug(repository)?.let { return it }
        return folderName(canonical).ifEmpty { canonical }
    }

    /** The single-letter fallback initial (`opencode-console` → `C`, `opencode` → `O`). */
    fun initial(slugOrName: String): String {
        val raw = slugOrName.trimmedWhitespace()
        if (raw.isEmpty()) return "?"
        val base = raw.split('/', '\\').lastOrNull { it.isNotEmpty() } ?: raw
        val stripped = stripOrgPrefix(base)
        stripped.firstOrNull { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }?.let { return it.uppercase() }
        return stripped.firstOrNull()?.uppercase() ?: "?"
    }

    private fun stripOrgPrefix(text: String): String {
        val lower = text.lowercase()
        for (prefix in listOf("opencode-", "opencode_", "opencode ", "anomaly-", "anomaly_", "anomaly ")) {
            if (lower.startsWith(prefix) && text.length > prefix.length) {
                val remainder = text.substring(prefix.length).trimmedWhitespace()
                if (remainder.any { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }) return remainder
            }
        }
        return text
    }
}

/** Readable display names for model provider IDs. */
object ModelProviderGroup {
    fun title(providerID: String): String = when (providerID) {
        "opencode" -> "OpenCode Zen"
        "openai" -> "OpenAI"
        "anthropic" -> "Anthropic"
        "google" -> "Google"
        "openrouter" -> "OpenRouter"
        "github-copilot" -> "GitHub Copilot"
        "xai" -> "xAI"
        "deepseek" -> "DeepSeek"
        "mistral" -> "Mistral"
        "groq" -> "Groq"
        "amazon-bedrock" -> "Amazon Bedrock"
        "google-vertex" -> "Google Vertex"
        "azure" -> "Azure"
        else -> providerID.split("-").filter { it.isNotEmpty() }
            .joinToString(" ") { it.take(1).uppercase() + it.drop(1) }
    }

    /** Drops a `console-` connection prefix and names a few more vendors. */
    fun canonicalVendorTitle(providerID: String): String {
        val cleaned = providerID.removePrefix("console-")
        return when (cleaned) {
            "meta" -> "Meta"
            "fireworks-ai" -> "Fireworks AI"
            "nvidia" -> "NVIDIA"
            "novita", "novita-chat-completion" -> "Novita"
            else -> title(cleaned)
        }
    }
}
