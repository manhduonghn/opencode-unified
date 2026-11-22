package ai.opencode.server

import ai.opencode.core.strings.localized
import ai.opencode.server.internal.UrlParts
import ai.opencode.server.internal.percentEncodePath
import ai.opencode.server.internal.percentEncodeQueryComponent
import ai.opencode.server.model.trimmedWhitespace
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.Base64

/**
 * A saved server: where it is, and how to authorize.
 *
 * Manual entry: `OpenCodeServer(url = "http://192.168.1.5:4096", username = "opencode", password = "…",
 * headers = listOf(OpenCodeServer.Header("CF-Access-Client-Id", "…")))`. From a pairing code:
 * `OpenCodePairingLink.decode(text)` then `OpenCodeClient.redeemPairing(link)`.
 */
@Serializable
data class OpenCodeServer(
    /** The address currently in use; every request goes here. */
    val url: String,
    val username: String,
    val password: String,
    /**
     * Other addresses the same server answers on — a pairing code reports its
     * loopback, LAN and tailnet URLs at once. Probed in parallel when connecting.
     */
    val alternateURLs: List<String> = emptyList(),
    /** The name the user gave this server on this phone; null shows [defaultName]. */
    val name: String? = null,
    /** Sent with every request to this server (a proxy such as Cloudflare Access may need them). */
    val headers: List<Header> = emptyList(),
    /**
     * The addresses the pairing code itself listed (`opencode pair --url`'s address, say), as
     * opposed to those the server reported once paired. When one of them answers it's used, even
     * if another answers sooner ([ServerLinkStore.race]). Empty for a server typed in or saved
     * before this was kept: every address is equal, as before.
     */
    val preferredURLs: List<String> = emptyList(),
) {
    @Serializable
    data class Header(val name: String, val value: String) {
        /** An HTTP field name: one or more token characters (RFC 9110 §5.6.2). */
        val hasValidName: Boolean get() = name.isNotEmpty() && name.all { it in TOKEN_CHARACTERS }

        /** A field value can't carry a line break, which would start another header. */
        val hasValidValue: Boolean get() = value.none { it in NEWLINES }

        private companion object {
            const val TOKEN_CHARACTERS = "!#$%&'*+-.^_`|~0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            const val NEWLINES = "\n\r\u000B\u000C\u0085\u2028\u2029"
        }
    }

    /** The address currently in use. Saved entries are deduplicated by [persistenceID]. */
    val id: String get() = url

    /** Stable across whichever LAN/tailnet address won the latest connection. */
    val persistenceID: String get() = (listOf(username) + candidateURLs.sorted()).joinToString("|")

    /** The same server under a changed address set or username still replaces its old entry. */
    fun supersedes(saved: OpenCodeServer): Boolean = saved.persistenceID == persistenceID || saved.url == url

    /** Every address to try, primary first, deduplicated. */
    val candidateURLs: List<String> get() = (listOf(url) + alternateURLs).distinct()

    /**
     * This server with [preferred]'s address order, when both list the same addresses. Pairing
     * or the person set that order; which address answered last is runtime state
     * ([ServerLinkClient]) and never reorders it.
     */
    fun keepingAddressOrder(preferred: OpenCodeServer?): OpenCodeServer {
        if (preferred == null || preferred.persistenceID != persistenceID) return this
        return copy(url = preferred.url, alternateURLs = preferred.alternateURLs)
    }

    /**
     * The addresses an address race tries at once; the rest wait ([ServerLinkStore.race]). A
     * pairing code's own addresses when any is still saved, plus the server's direct ones when the
     * code named only relayed ones (the desktop's and `opencode pair --remote`'s tunnel): a LAN or
     * tailnet address that answers is the same server, faster. Otherwise every address.
     */
    val firstAttemptURLs: List<String>
        get() {
            val preferred = candidateURLs.filter { it in preferredURLs }
            if (preferred.isEmpty()) return candidateURLs
            val preferredIsDirect = preferred.any { ConnectionRoute.of(it).isDirect }
            val first = mutableListOf<String>()
            for (candidate in candidateURLs) {
                if (candidate in preferred) {
                    first += candidate
                } else if (!preferredIsDirect && ConnectionRoute.of(candidate).isDirect) {
                    first += candidate
                }
            }
            return first
        }

    fun at(url: String): OpenCodeServer = copy(url = url, alternateURLs = candidateURLs.filter { it != url })

    /** "127.0.0.1:4096" — the address in use, without its scheme. */
    val host: String
        get() {
            val parts = UrlParts.parse(url) ?: return url
            val host = parts.host ?: return url
            val port = parts.port ?: return host
            return "$host:$port"
        }

    /** What the app calls this server: the user's name for it, else [defaultName]. */
    val displayName: String
        get() {
            val chosen = name?.trimmedWhitespace() ?: ""
            return chosen.ifEmpty { defaultName }
        }

    /**
     * The machine's name when an address carries one: the first label of a tailnet
     * (`basebook.tail1234.ts.net`) or Bonjour (`basebook.local`) name. Otherwise the
     * address in use, shortened the same way.
     */
    val defaultName: String
        get() {
            val named = candidateURLs.asSequence()
                .mapNotNull { UrlParts.parse(it)?.host?.lowercase() }
                .firstOrNull { it.endsWith(".ts.net") || it.endsWith(".local") }
            return machineName(named ?: host)
        }

    /** Adds this server's custom headers, then its credentials: the password always supplies `Authorization`. */
    fun authorize(request: Request.Builder): Request.Builder {
        for (header in headers) {
            if (!header.hasValidName || !header.hasValidValue) continue
            // OkHttp refuses control and non-ASCII characters that URLSession would send; skip those.
            try { request.header(header.name, header.value) } catch (_: IllegalArgumentException) {}
        }
        basicAuthorization?.let { request.header("Authorization", it) }
        return request
    }

    /** Whether [url] shares scheme, host, and effective port with any of this server's addresses. */
    fun matchesOrigin(url: String): Boolean {
        val target = HttpOrigin.of(UrlParts.parse(url) ?: return false) ?: return false
        return candidateURLs.any { candidate ->
            val parts = UrlParts.parse(candidate.trimmedWhitespace()) ?: return@any false
            HttpOrigin.of(parts) == target
        }
    }

    fun matchesOrigin(url: HttpUrl): Boolean = matchesOrigin(url.toString())

    private data class HttpOrigin(val scheme: String, val host: String, val zone: String?, val port: Int) {
        companion object {
            fun of(parts: UrlParts): HttpOrigin? {
                val scheme = parts.scheme?.lowercase() ?: return null
                if (scheme != "http" && scheme != "https") return null
                val rawHost = parts.host ?: return null
                val address = HostAddress.of(rawHost) ?: return null
                val trimmedHost = rawHost.lowercase().trim('[', ']')
                val zone: String? = if (address.hasZone) {
                    val percent = trimmedHost.indexOf('%')
                    if (percent < 0) return null
                    trimmedHost.substring(percent + 1).ifEmpty { return null }
                } else null
                return HttpOrigin(scheme, address.host, zone, parts.port ?: if (scheme == "https") 443 else 80)
            }
        }
    }

    /** A re-paired server keeps the name and headers its saved entry had: a pairing code carries neither. */
    fun keepingLocalSettings(saved: List<OpenCodeServer>): OpenCodeServer {
        val old = saved.firstOrNull(::supersedes) ?: return this
        // A name the user gave wins over the one the computer reports.
        return copy(name = old.name ?: name, headers = headers.ifEmpty { old.headers })
    }

    /** No saved entry is this server or shares one of its addresses: pairing it adds a computer. */
    fun isNewComputer(saved: List<OpenCodeServer>): Boolean {
        val addresses = candidateURLs.toSet()
        return saved.none { supersedes(it) || it.candidateURLs.any(addresses::contains) }
    }

    val isSecure: Boolean get() = url.lowercase().startsWith("https")

    /** Paired via a V2 `opencode pair` link: the password is a signed session token (`<expiry>.<signature>`). */
    val usesPairingToken: Boolean get() = PAIRING_TOKEN.matches(password)

    /**
     * [path] on this server, replacing any query or fragment in the saved address.
     * Null unless the trimmed address is HTTP(S) with a host.
     */
    fun endpoint(path: String, query: List<Pair<String, String>> = emptyList()): HttpUrl? {
        val trimmed = url.trimmedWhitespace()
        val parts = UrlParts.parse(trimmed) ?: return null
        val scheme = parts.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (parts.host.isNullOrEmpty()) return null
        val base = trimmed.toHttpUrlOrNull() ?: return null
        val builder = base.newBuilder()
            .encodedPath(percentEncodePath(path).let { if (it.startsWith("/")) it else "/$it" })
            .query(null)
            .fragment(null)
        // Encoded as `URLComponents.queryItems` does: `/`, `:`, `?` stay literal, `[` `]` don't.
        for ((name, value) in query) builder.addEncodedQueryParameter(percentEncodeQueryComponent(name), percentEncodeQueryComponent(value))
        return builder.build()
    }

    /** The `Authorization` header value, or null when no password is set. */
    val basicAuthorization: String?
        get() {
            if (password.isEmpty()) return null
            return "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
        }

    /**
     * The request for an image a transcript references: an HTTP(S) URL as is, or a
     * project file (`file://`, `sandbox:`, absolute under [directory], or relative)
     * through this server's authenticated file reader.
     */
    fun imageRequest(source: String, directory: String?): Request? {
        val scheme = UrlParts.parse(source)?.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            val url = source.toHttpUrlOrNull() ?: return null
            return Request.Builder().url(url).build()
        }
        if (directory == null) return null
        val path = when {
            source.startsWith("file://") -> (try { URI(source).path } catch (_: Exception) { null }) ?: source
            source.startsWith("sandbox:") -> source.removePrefix("sandbox:")
            else -> source
        }
        val relativePath = when {
            path.startsWith("$directory/") -> path.substring(directory.length + 1)
            path.startsWith("/") -> return null
            else -> path
        }
        // Only the open session's own directory is read, so this boots no extra locations.
        return fileReadRequest(location = directory, relativePath = relativePath)
    }

    /**
     * `GET /api/fs/read/<relative>?location[directory]=<location>`, authenticated. V2 scopes reads
     * with `location[directory]` and ignores a bare `directory`.
     */
    fun fileReadRequest(location: String, relativePath: String): Request? {
        val url = endpoint("/api/fs/read/$relativePath", listOf("location[directory]" to location)) ?: return null
        return authorize(Request.Builder().url(url)).build()
    }

    /**
     * An OkHttp client (sharing [base]'s pools) that authorizes every request to this server's
     * origins, for image loaders and media players. Requests elsewhere go out untouched.
     */
    fun httpClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .addInterceptor(Interceptor { chain ->
            val request = chain.request()
            if (!matchesOrigin(request.url)) return@Interceptor chain.proceed(request)
            chain.proceed(authorize(request.newBuilder()).build())
        })
        .build()

    companion object {
        private val PAIRING_TOKEN = Regex("^[0-9]{10,}\\.[A-Za-z0-9_-]{20,}$")

        /**
         * "http://100.64.0.3:4096": scheme, host and port, lowercased, with the scheme's default
         * port filled in, so two spellings of one address match.
         */
        fun origin(url: String): String? {
            val parts = UrlParts.parse(url.trim()) ?: return null
            val scheme = parts.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = parts.host?.lowercase()
            if (host.isNullOrEmpty()) return null
            val port = parts.port ?: if (scheme == "https") 443 else 80
            return "$scheme://$host:$port"
        }

        /** A host name's first label ("basebook" of basebook.tail1234.ts.net:4443), or the whole address for an IP. */
        fun machineName(host: String): String {
            val address = host.split(":", limit = 2).firstOrNull() ?: host
            val labels = address.split(".").filter { it.isNotEmpty() }
            if (labels.size <= 1 || address.all { it.isDigit() || it == '.' }) return host
            return labels.first()
        }

        /** A server only reachable from the machine running it: the phone can never connect. */
        fun isLoopback(url: String): Boolean = HostAddress.ofUrl(url)?.isLoopback ?: false

        /** Prefer addresses that remain reachable away from the server's local network. */
        fun reachabilityRank(url: String): Int {
            val parts = UrlParts.parse(url)
            if (parts?.host.isNullOrEmpty()) return 8
            val scope = when (ConnectionRoute.of(url)) {
                ConnectionRoute.Internet -> 0
                ConnectionRoute.Tailscale -> 1
                ConnectionRoute.LocalNetwork -> 2
                ConnectionRoute.Loopback -> 3
            }
            return scope * 2 + if (parts.scheme?.lowercase() == "https") 0 else 1
        }
    }
}

/** How the phone reaches the server, from the address in use. */
enum class ConnectionRoute(val rawValue: String) {
    Tailscale("Tailscale"),
    LocalNetwork("Local Network"),
    Loopback("Loopback"),
    Internet("Internet");

    /** What the route reads as on screen; [rawValue] stays English for logs. */
    val title: String
        get() = when (this) {
            Tailscale -> rawValue
            LocalNetwork -> localized(R.string.server_connection_route_local_network)
            Loopback -> localized(R.string.server_connection_route_loopback)
            Internet -> localized(R.string.server_connection_route_internet)
        }

    /** Straight to the computer (a LAN or tailnet address), not through a relay or the internet. */
    val isDirect: Boolean get() = this == Tailscale || this == LocalNetwork

    companion object {
        fun of(url: String): ConnectionRoute = HostAddress.ofUrl(url)?.route ?: Internet
    }
}

/**
 * A URL's host, classified once: an IPv4 or IPv6 literal (parsed, never guessed from
 * its spelling, so `fd.example.com` is a name) or a name.
 */
data class HostAddress(
    /** Lowercased, without IPv6 brackets or zone (`fe80::1%en0` → `fe80::1`). */
    val host: String,
    val kind: Kind,
    /** An IPv6 literal scoped to an interface (`%en0`). */
    val hasZone: Boolean,
) {
    sealed interface Kind {
        data class IPv4(val bytes: List<Int>) : Kind
        data class IPv6(val bytes: List<Int>) : Kind
        data object Name : Kind
    }

    val isIP: Boolean get() = kind != Kind.Name

    val isLoopback: Boolean
        get() = when (kind) {
            is Kind.IPv4 -> kind.bytes[0] == 127 // 127.0.0.0/8
            is Kind.IPv6 -> kind.bytes == List(15) { 0 } + 1
            Kind.Name -> host == "localhost"
        }

    val route: ConnectionRoute
        get() {
            if (isLoopback) return ConnectionRoute.Loopback
            return when (kind) {
                is Kind.IPv4 -> {
                    val b = kind.bytes
                    if (b[0] == 100 && b[1] in 64..127) return ConnectionRoute.Tailscale // Tailscale's CGNAT range
                    val isPrivate = b[0] == 10 || (b[0] == 172 && b[1] in 16..31) ||
                        (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254)
                    if (isPrivate) ConnectionRoute.LocalNetwork else ConnectionRoute.Internet
                }
                is Kind.IPv6 -> {
                    val b = kind.bytes
                    if (b.subList(0, 6) == listOf(0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0)) return ConnectionRoute.Tailscale // fd7a:115c:a1e0::/48
                    val uniqueLocal = b[0] and 0xfe == 0xfc // fc00::/7
                    val linkLocal = b[0] == 0xfe && b[1] and 0xc0 == 0x80 // fe80::/10
                    if (uniqueLocal || linkLocal) ConnectionRoute.LocalNetwork else ConnectionRoute.Internet
                }
                Kind.Name -> when {
                    host.endsWith(".ts.net") -> ConnectionRoute.Tailscale
                    host.endsWith(".local") -> ConnectionRoute.LocalNetwork
                    else -> ConnectionRoute.Internet
                }
            }
        }

    companion object {
        fun ofUrl(url: String): HostAddress? = UrlParts.parse(url)?.host?.let(::of)

        fun of(raw: String): HostAddress? {
            var host = raw.lowercase().trim('[', ']')
            if (host.isEmpty()) return null
            var zoned = false
            if (host.contains(":")) {
                val percent = host.indexOf('%')
                if (percent >= 0) {
                    host = host.substring(0, percent)
                    zoned = true
                }
            }
            val v4 = parseIPv4(host)
            val kind: Kind = if (v4 != null) {
                Kind.IPv4(v4)
            } else {
                val v6 = parseIPv6(host)
                if (v6 != null) {
                    // An IPv4-mapped address (::ffff:0:0/96) is its IPv4 address.
                    val mapped = v6.subList(0, 10).all { it == 0 } && v6[10] == 0xff && v6[11] == 0xff
                    if (mapped) Kind.IPv4(v6.subList(12, 16)) else Kind.IPv6(v6)
                } else {
                    Kind.Name
                }
            }
            return HostAddress(host, kind, zoned)
        }

        /** `inet_pton(AF_INET)`: four decimal octets, no leading zeros. */
        internal fun parseIPv4(text: String): List<Int>? {
            val parts = text.split('.')
            if (parts.size != 4) return null
            return parts.map { part ->
                if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
                if (part.length > 1 && part[0] == '0') return null
                val value = part.toInt()
                if (value > 255) return null
                value
            }
        }

        /** `inet_pton(AF_INET6)`, including `::` compression and a trailing dotted IPv4. */
        internal fun parseIPv6(text: String): List<Int>? {
            if (text.isEmpty() || !text.contains(':')) return null
            val doubleColon = text.indexOf("::")
            if (doubleColon >= 0 && text.indexOf("::", doubleColon + 1) >= 0) return null
            fun groups(part: String): List<Int>? {
                if (part.isEmpty()) return emptyList()
                val pieces = part.split(':')
                val result = mutableListOf<Int>()
                for ((index, piece) in pieces.withIndex()) {
                    if (index == pieces.lastIndex && piece.contains('.')) {
                        val v4 = parseIPv4(piece) ?: return null
                        result += (v4[0] shl 8) or v4[1]
                        result += (v4[2] shl 8) or v4[3]
                        continue
                    }
                    if (piece.isEmpty() || piece.length > 4) return null
                    if (!piece.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                    result += piece.toInt(16)
                }
                return result
            }
            val words: List<Int> = if (doubleColon >= 0) {
                val head = groups(text.substring(0, doubleColon)) ?: return null
                val tail = groups(text.substring(doubleColon + 2)) ?: return null
                val missing = 8 - head.size - tail.size
                if (missing < 1) return null
                head + List(missing) { 0 } + tail
            } else {
                val all = groups(text) ?: return null
                if (all.size != 8) return null
                all
            }
            return words.flatMap { listOf((it shr 8) and 0xff, it and 0xff) }
        }
    }
}
