package ai.opencode.server

import ai.opencode.core.strings.localized
import ai.opencode.server.internal.UrlParts
import ai.opencode.server.internal.WireJson
import ai.opencode.server.internal.parseQueryItems
import ai.opencode.server.internal.percentEncodeQueryComponent
import ai.opencode.server.model.trimmedWhitespace
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.util.Base64

/**
 * A one-time `opencode pair` code: JSON `{"code","urls","name"?}`, the `https://opencode.ai/pair#…` page,
 * an `opencode(-ios)://pair?code=…&url=…` deep link, or one `…/auth/connect/<code>` link. Never log
 * [code].
 */
data class OpenCodePairingLink(
    /** Scheme, host and port only; the first listed address. */
    val origin: String,
    val code: String,
    /** The other addresses a QR payload listed, redeemed concurrently with [origin]. */
    val alternateOrigins: List<String> = emptyList(),
    /** The name the link suggests for the server it pairs, such as “Anomaly Box”. */
    val name: String? = null,
) {
    /** Every address to redeem at, in listed order, deduplicated. */
    val candidateOrigins: List<String> get() = (listOf(origin) + alternateOrigins).distinct()

    /**
     * A custom-scheme deep link that opens the app without a web preview fetching (and
     * spending) the code: `opencode-ios://pair?code=…&url=…`.
     */
    val deepLinkURL: String
        get() {
            val items = listOf("code" to code) + candidateOrigins.map { "url" to it } + listOfNotNull(name?.let { "name" to it })
            return "opencode-ios://pair?" + items.joinToString("&") {
                percentEncodeQueryComponent(it.first) + "=" + percentEncodeQueryComponent(it.second)
            }
        }

    /**
     * The deduplicated origins redemption will contact, omitting loopback addresses whenever
     * a non-loopback one is present. Deduplicated by (scheme, host, effective port).
     */
    val redeemOrigins: List<String>
        get() {
            val reachable = candidateOrigins.filter { !OpenCodeServer.isLoopback(it) }
            val candidates = reachable.ifEmpty { candidateOrigins }
            val seen = HashSet<String>()
            return candidates.filter { seen.add(originDedupKey(it)) }
        }

    /** A copy restricted to [redeemOrigins], so confirmation and redemption use the same list. */
    val normalizedForRedemption: OpenCodePairingLink
        get() {
            val origins = redeemOrigins
            val first = origins.firstOrNull() ?: return this
            return OpenCodePairingLink(first, code, origins.drop(1), name)
        }

    /** The unauthenticated server descriptor for this link's reachable addresses. */
    val server: OpenCodeServer
        get() {
            val candidates = redeemOrigins
            return OpenCodeServer(url = candidates[0], username = "opencode", password = "", alternateURLs = candidates.drop(1), name = name)
        }

    /** Every saved server entry this pairing link would replace. */
    fun replacedServers(savedServers: List<OpenCodeServer>): List<OpenCodeServer> {
        val candidate = server
        val origins = redeemOrigins
        val seen = HashSet<String>()
        return savedServers.filter { saved ->
            val matches = candidate.supersedes(saved) || origins.any { origin ->
                saved.url == origin || origin in saved.candidateURLs || saved.matchesOrigin(origin)
            }
            matches && seen.add(saved.persistenceID)
        }
    }

    /** No candidate is reachable from another device. */
    val isLoopback: Boolean get() = candidateOrigins.all(OpenCodeServer::isLoopback)

    /** An address in a pairing code the app can't use, and why ([reason] finishes “it …”). */
    data class UnusableAddress(val address: String, val reason: String)

    companion object {
        fun decode(text: String): OpenCodePairingLink? {
            val trimmed = text.trimmedWhitespace()
            if (trimmed.startsWith("{")) return decodePayload(trimmed)?.let(::validatedLink)
            return decodeDeepLink(trimmed)?.let(::validatedLink)
                ?: decodePairPage(trimmed)?.let(::validatedLink)
                ?: decodeLink(trimmed)
        }

        /**
         * The first address that keeps [text], an otherwise well-formed pairing code, from
         * decoding; null when it isn't one or every address is fine. For the error only: one
         * unusable address still rejects the whole code.
         */
        fun unusableAddress(text: String): UnusableAddress? {
            val trimmed = text.trimmedWhitespace()
            val raw = if (trimmed.startsWith("{")) decodePayload(trimmed) else decodeDeepLink(trimmed) ?: decodePairPage(trimmed)
            if (raw == null) return null
            for (address in raw.urls) {
                val reason = originProblem(address) ?: continue
                return UnusableAddress(shownAddress(address), reason)
            }
            return null
        }

        /** [address] as an error may show it: no user name, password, query or fragment, at most 80 characters. */
        private fun shownAddress(address: String): String {
            var shown = address.trimmedWhitespace()
            val end = shown.indexOfFirst { it == '?' || it == '#' }
            if (end >= 0) shown = shown.substring(0, end)
            shown = shown.replace(userInfo, "://")
            if (shown.length > 80) shown = shown.take(79) + "…"
            return shown
        }

        private val userInfo = Regex("""://[^/]*@""")

        /**
         * [decode], or else the pairing link in text as people copy it: wrapped across
         * lines by a terminal (`opencode pair --remote`'s 88-character link plus its indent passes
         * 80 columns, and the TUI's `/pair` box is 29 wide), inside the command's whole output or a
         * sentence, or with brackets or punctuation around it. Every link in it with the first
         * one's code becomes one link with all their addresses (the TUI lists one per address). For
         * pasted, scanned and typed text; opened URLs are exact ([decode]).
         */
        fun decodeCopied(text: String): OpenCodePairingLink? {
            decode(text)?.let { return it }
            // A wrapped link goes on after the line break and the next line's indent; a blank line
            // or a line starting another link ends it. Taken first, so a code cut by the wrap isn't
            // read short from the first line.
            val unwrapped = text.replace(wrapBreak, "")
            val links = copiedLinks(unwrapped).ifEmpty { copiedLinks(text) }
            val first = links.firstOrNull() ?: return null
            val origins = links.filter { it.code == first.code }.flatMap { it.candidateOrigins }.distinct()
            return OpenCodePairingLink(origins[0], first.code, origins.drop(1))
        }

        private val wrapBreak = Regex("""[ \t]*\r?\n[ \t]*(?![ \t]*\r?\n|(?i:https?|opencode(?:-ios)?)://)""")
        private val copiedLink = Regex("""(?i)(?:https?|opencode(?:-ios)?)://[^\s<>"'`()\[\]{}]+""")

        private fun copiedLinks(text: String): List<OpenCodePairingLink> =
            copiedLink.findAll(text).mapNotNull { decode(it.value.trimEnd('.', ',', ';', ':', '!', '?')) }.toList()

        /** Whether [text] is `https://opencode.ai/pair`, even with a malformed fragment. */
        fun isPairPage(text: String): Boolean {
            val parts = UrlParts.parse(text.trimmedWhitespace()) ?: return false
            if (parts.scheme?.lowercase() != "https" || parts.host?.lowercase() != "opencode.ai") return false
            return parts.path == "/pair" || parts.path == "/pair/"
        }

        /** `https://opencode.ai/pair#<payload>`: base64url JSON in the fragment. Never log the fragment. */
        private fun decodePairPage(text: String): Payload? {
            if (!isPairPage(text)) return null
            val parts = UrlParts.parse(text) ?: return null
            if (parts.user != null || parts.password != null || parts.port != null || parts.rawQuery != null) return null
            val fragment = parts.rawFragment?.ifEmpty { null } ?: return null
            val json = base64URLDecoded(fragment) ?: return null
            return decodePayload(json)
        }

        /** Base64url with or without padding; null for anything else. */
        private fun base64URLDecoded(text: String): String? {
            if (!text.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' || it == '=' }) return null
            var base64 = text.replace('-', '+').replace('_', '/').trim('=')
            base64 += "=".repeat((4 - base64.length % 4) % 4)
            val data = try { Base64.getDecoder().decode(base64) } catch (_: IllegalArgumentException) { return null }
            return try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString() } catch (_: Exception) { null }
        }

        /**
         * Whether [text] uses the app's pairing deep-link scheme and host (`opencode://pair`,
         * `opencode-ios://pair`, `…://connect`), even when its query or code is malformed.
         */
        fun isPairingScheme(text: String): Boolean {
            val parts = UrlParts.parse(text.trimmedWhitespace()) ?: return false
            val scheme = parts.scheme?.lowercase() ?: return false
            if (scheme != "opencode" && scheme != "opencode-ios") return false
            val host = parts.host?.lowercase() ?: return false
            return host == "pair" || host == "connect"
        }

        /** Whether [text] is an HTTP(S) URL targeting the reserved `/auth/connect` pairing route. */
        fun isPairingHTTPPath(text: String): Boolean {
            val parts = UrlParts.parse(text.trimmedWhitespace()) ?: return false
            val scheme = parts.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return false
            if (parts.host.isNullOrEmpty()) return false
            val segments = parts.path.split("/").filter { it.isNotEmpty() }
            return segments.size >= 2 && segments[0] == "auth" && segments[1] == "connect"
        }

        /** Whether [text] targets a pairing deep link or the `/auth/connect` endpoint, decodable or not. */
        fun isPairingEndpoint(text: String): Boolean = isPairingScheme(text) || isPairingHTTPPath(text) || isPairPage(text)

        fun shouldHandleOpenURL(url: String): Boolean = decode(url) != null || isPairingEndpoint(url)

        /**
         * The URL to copy or share: for a valid pairing link its non-fetching deep link, so a
         * preview can't consume the single-use code; null for a malformed pairing link; else [url].
         */
        fun shareableURL(url: String): String? {
            if (!shouldHandleOpenURL(url)) return url
            return decode(url)?.deepLinkURL
        }

        /** A code and its addresses as listed, before [validatedLink] checks them. */
        @Serializable
        private data class Payload(val code: String, val urls: List<String>, val name: String? = null)

        private fun decodePayload(text: String): Payload? {
            val payload = try { WireJson.decodeFromString(Payload.serializer(), text) } catch (_: Exception) { return null }
            if (!isCode(payload.code)) return null
            return payload
        }

        private fun decodeDeepLink(text: String): Payload? {
            if (!isPairingScheme(text)) return null
            val link = UrlParts.parse(text) ?: return null
            if (link.user != null || link.password != null || link.port != null || link.rawFragment != null) return null
            val queryItems = parseQueryItems(link.rawQuery)?.ifEmpty { null } ?: return null
            val pathCode: String? = if (link.path.isEmpty() || link.path == "/") null else {
                val parts = link.path.split("/")
                if (parts.size != 2 || parts[0].isNotEmpty() || parts[1].isEmpty()) return null
                parts[1]
            }
            var queryCode: String? = null
            val rawURLs = mutableListOf<String>()
            var linkName: String? = null
            for ((name, value) in queryItems) {
                when (name.lowercase()) {
                    "code" -> {
                        if (queryCode != null || value.isNullOrEmpty()) return null
                        queryCode = value
                    }
                    "url" -> {
                        if (value.isNullOrEmpty()) return null
                        rawURLs += value
                    }
                    "urls" -> {
                        val trimmed = value?.trimmedWhitespace()?.ifEmpty { null } ?: return null
                        if (trimmed.startsWith("[")) {
                            val decoded = try {
                                WireJson.decodeFromString(ListSerializer(String.serializer()), trimmed)
                            } catch (_: Exception) { return null }
                            if (decoded.isEmpty()) return null
                            rawURLs += decoded
                        } else {
                            rawURLs += trimmed
                        }
                    }
                    "name" -> {
                        if (linkName != null) return null
                        linkName = value.orEmpty()
                    }
                    // Reject credential-bearing (`username`, `password`, `token`) or unknown parameters.
                    else -> return null
                }
            }
            if (pathCode != null && queryCode != null && pathCode != queryCode) return null
            val code = queryCode ?: pathCode ?: return null
            if (!isCode(code)) return null
            return Payload(code, rawURLs, linkName)
        }

        /** One malformed address rejects the whole code. A blank name counts as none. */
        private fun validatedLink(listed: Payload): OpenCodePairingLink? {
            val code = listed.code
            val addresses = listed.urls.map(::normalizedOrigin)
            if (addresses.isEmpty() || addresses.any { it == null }) return null
            val origins = addresses.filterNotNull().distinct()
            val name = listed.name?.trimmedWhitespace()?.ifEmpty { null }
            return OpenCodePairingLink(origins[0], code, origins.drop(1), name)
        }

        private fun decodeLink(text: String): OpenCodePairingLink? {
            val link = UrlParts.parse(text) ?: return null
            val parts = link.path.split("/")
            if (parts.size != 4 || parts[0].isNotEmpty() || parts[1] != "auth" || parts[2] != "connect" || !isCode(parts[3])) return null
            val scheme = link.scheme ?: return null
            val authority = link.rawAuthority ?: return null
            var origin = "$scheme://$authority"
            link.rawQuery?.let { origin += "?$it" }
            link.rawFragment?.let { origin += "#$it" }
            val address = normalizedOrigin(origin) ?: return null
            return OpenCodePairingLink(address, parts[3])
        }

        /** A server base URL normalized to its origin: HTTP(S), a host, no credentials, query, fragment or path. */
        private fun normalizedOrigin(address: String): String? {
            if (originProblem(address) != null) return null
            val parts = UrlParts.parse(address) ?: return null
            val scheme = parts.scheme?.lowercase() ?: return null
            val authority = (parts.rawAuthority ?: return null).removeSuffix(":")
            return "$scheme://$authority"
        }

        /** Why [address] can't be a server's base URL, finishing “it …”; null when it can. */
        private fun originProblem(address: String): String? {
            val parts = UrlParts.parse(address) ?: return localized(R.string.server_pairing_origin_not_web_address)
            val scheme = parts.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return localized(R.string.server_pairing_origin_bad_scheme)
            if (parts.host.isNullOrEmpty() || parts.rawAuthority == null) return localized(R.string.server_pairing_origin_no_host)
            if (parts.user != null || parts.password != null) return localized(R.string.server_pairing_origin_credentials)
            if (parts.rawQuery != null || parts.rawFragment != null) return localized(R.string.server_pairing_origin_query)
            parts.port?.let { if (it !in 1..65535) return localized(R.string.server_pairing_origin_port) }
            if (parts.path.isNotEmpty() && parts.path != "/") return localized(R.string.server_pairing_origin_path)
            return null
        }

        private fun originDedupKey(origin: String): String {
            val parts = UrlParts.parse(origin.trimmedWhitespace())
            val scheme = parts?.scheme?.lowercase()
            val host = parts?.host?.lowercase()
            if (scheme == null || host.isNullOrEmpty()) return origin.lowercase()
            val port = parts.port ?: if (scheme == "https") 443 else 80
            return "$scheme://$host:$port"
        }

        /** Base64url, as the server mints them. */
        private fun isCode(code: String): Boolean =
            code.isNotEmpty() && code.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
    }
}
