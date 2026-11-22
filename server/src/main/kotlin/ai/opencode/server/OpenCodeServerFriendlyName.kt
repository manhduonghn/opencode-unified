package ai.opencode.server

import ai.opencode.core.sharing.shared
import ai.opencode.core.strings.localized
import ai.opencode.server.internal.UrlParts

/**
 * Moved down to `:server` because App and Drawer both name servers with it. A name to show, never
 * an address: the user's name, else the machine's from a tailnet or Bonjour address, else the
 * address in use when it's a name ("my-mac" → "My Mac"). An IP the server hasn't named reads as
 * "Server" and its last part, as a last resort.
 */
val OpenCodeServer.friendlyName: String
    get() = friendlyName(among = shared(ServerSharedKeys.savedServers).value)

/**
 * [friendlyName] among [among] (the saved servers): an unnamed server whose name another saved
 * server also reads, on another port, gets its port (“Server 2 · 4197”), so two servers on one
 * machine can be told apart.
 */
fun OpenCodeServer.friendlyName(among: List<OpenCodeServer>): String {
    val base = baseFriendlyName
    if (name?.trim().orEmpty().isNotEmpty()) return base
    val port = port ?: return base
    val collides = among.any { other ->
        other.persistenceID != persistenceID && other.port != port && other.baseFriendlyName == base
    }
    return if (collides) "$base · $port" else base
}

private val OpenCodeServer.baseFriendlyName: String
    get() {
        val chosen = name?.trim().orEmpty()
        if (chosen.isNotEmpty()) return chosen
        val named = candidateURLs.asSequence()
            .mapNotNull { UrlParts.parse(it)?.host?.lowercase() }
            .firstOrNull { it.endsWith(".ts.net") || it.endsWith(".local") }
        return friendlyServerName(named ?: url)
    }

/** The port of the address in use, or its scheme's. */
private val OpenCodeServer.port: Int?
    get() {
        val parts = UrlParts.parse(url) ?: return null
        return parts.port ?: when (parts.scheme?.lowercase()) {
            "http" -> 80
            "https" -> 443
            else -> null
        }
    }

/** A friendly name for an address: a URL, `host`, `host:port`, `[v6]:port` or a bare IPv6 address. */
fun friendlyServerName(address: String): String {
    val trimmed = address.trim()
    // `UrlParts` reads the host as URLComponents does: `java.net.URI.host` is null for names
    // like `my_mac`, which would leave the whole address to name the server. An unbracketed
    // IPv6 address isn't an authority to URLComponents either (no host), so it falls through.
    val parsed = UrlParts.parse(if (trimmed.contains("://")) trimmed else "http://$trimmed")
        ?.takeUnless { parts ->
            val hostPort = parts.rawAuthority?.substringAfterLast('@') ?: return@takeUnless false
            !hostPort.startsWith('[') && hostPort.count { it == ':' } > 1
        }
        ?.host
    // A bare IPv6 address doesn't parse as an authority; its colons say what it is.
    var host = (parsed ?: trimmed).trim('[', ']')
    host.indexOf('%').takeIf { it >= 0 }?.let { host = host.substring(0, it) }
    if (host.contains(":")) {
        // `fe80::` ends in "fe80".
        val last = host.split(":").lastOrNull { it.isNotEmpty() }.orEmpty()
        return if (last.isEmpty()) localized(R.string.server_friendly_name_fallback) else localized(R.string.server_friendly_name_numbered, last)
    }
    val labels = host.split(".").filter { it.isNotEmpty() }
    if (labels.size == 4 && labels.all { label -> label.all(Char::isDigit) }) return localized(R.string.server_friendly_name_numbered, labels[3])
    val first = labels.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return localized(R.string.server_friendly_name_fallback)
    return first.split('-', '_').filter { it.isNotEmpty() }
        .joinToString(" ") { it.substring(0, 1).uppercase() + it.substring(1) }
}
