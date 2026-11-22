package ai.opencode.server.internal

import java.net.URI
import java.net.URLDecoder

/**
 * The parts of a URL string as Foundation's `URLComponents(string:)` reads them,
 * without OkHttp's normalisation: nil when the string isn't a valid URI.
 */
internal data class UrlParts(
    val scheme: String?,
    val user: String?,
    val password: String?,
    /** Percent-decoded, without IPv6 brackets. Empty for `http://`. */
    val host: String?,
    val port: Int?,
    /** Percent-decoded. */
    val path: String,
    val rawQuery: String?,
    val rawFragment: String?,
    /** `host[:port]` with any userinfo, as written (IPv6 brackets kept). */
    val rawAuthority: String? = null,
) {
    companion object {
        fun parse(text: String): UrlParts? {
            val uri = try { URI(text) } catch (_: Exception) { return null }
            if (uri.isOpaque) {
                // `localhost:4096`, `mailto:x`: a scheme and an opaque path, like URLComponents.
                return UrlParts(uri.scheme, null, null, null, null, uri.schemeSpecificPart ?: "", null, uri.rawFragment, null)
            }
            var user: String? = null
            var password: String? = null
            var host: String? = null
            var port: Int? = null
            val authority = uri.rawAuthority
            if (authority != null) {
                var hostPort = authority
                val at = authority.lastIndexOf('@')
                if (at >= 0) {
                    val info = authority.substring(0, at)
                    hostPort = authority.substring(at + 1)
                    val colon = info.indexOf(':')
                    if (colon >= 0) {
                        user = decode(info.substring(0, colon))
                        password = decode(info.substring(colon + 1))
                    } else {
                        user = decode(info)
                    }
                }
                val portText: String?
                if (hostPort.startsWith("[")) {
                    val close = hostPort.indexOf(']')
                    if (close < 0) return null
                    host = hostPort.substring(1, close)
                    val rest = hostPort.substring(close + 1)
                    portText = if (rest.startsWith(":")) rest.substring(1) else if (rest.isEmpty()) null else return null
                } else {
                    val colon = hostPort.lastIndexOf(':')
                    if (colon >= 0) {
                        host = decode(hostPort.substring(0, colon))
                        portText = hostPort.substring(colon + 1)
                    } else {
                        host = decode(hostPort)
                        portText = null
                    }
                }
                if (portText != null && portText.isNotEmpty()) {
                    if (!portText.all { it in '0'..'9' }) return null
                    port = portText.toIntOrNull() ?: return null
                }
            }
            return UrlParts(uri.scheme, user, password, host, port, uri.path ?: "", uri.rawQuery, uri.rawFragment, authority)
        }

        private fun decode(text: String): String =
            try { URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { text }
    }
}

private const val HEX = "0123456789ABCDEF"

/** Percent-encodes for a URL path as Foundation's `urlPathAllowed` does (`/` kept unless [keepSlash] is false). */
internal fun percentEncodePath(text: String, keepSlash: Boolean = true): String {
    val allowed = "!$&'()*+,-.:;=@_~"
    val builder = StringBuilder()
    for (byte in text.toByteArray(Charsets.UTF_8)) {
        val c = (byte.toInt() and 0xff).toChar()
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in allowed || (keepSlash && c == '/')) {
            builder.append(c)
        } else {
            builder.append('%').append(HEX[(byte.toInt() shr 4) and 0xf]).append(HEX[byte.toInt() and 0xf])
        }
    }
    return builder.toString()
}

/** Foundation's `URLComponents.queryItems`: `name=value` pairs, percent-decoded (`+` kept), value null without `=`. */
internal fun parseQueryItems(rawQuery: String?): List<Pair<String, String?>>? {
    rawQuery ?: return null
    if (rawQuery.isEmpty()) return emptyList()
    return rawQuery.split("&").map { item ->
        val equals = item.indexOf('=')
        if (equals < 0) percentDecode(item) to null
        else percentDecode(item.substring(0, equals)) to percentDecode(item.substring(equals + 1))
    }
}

internal fun percentDecode(text: String): String =
    try { URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { text }

/** Percent-encodes a query item name or value, keeping what Foundation keeps in a query. */
internal fun percentEncodeQueryComponent(text: String): String {
    val allowed = "-._~!$'()*,;:@/?"
    val builder = StringBuilder()
    for (byte in text.toByteArray(Charsets.UTF_8)) {
        val c = (byte.toInt() and 0xff).toChar()
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in allowed) builder.append(c)
        else builder.append('%').append(HEX[(byte.toInt() shr 4) and 0xf]).append(HEX[byte.toInt() and 0xf])
    }
    return builder.toString()
}
