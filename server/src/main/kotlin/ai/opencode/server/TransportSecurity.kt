package ai.opencode.server

import okhttp3.Interceptor
import java.net.UnknownServiceException

/**
 * Plain HTTP only to private networks. Android's network security config can't name address ranges,
 * so the manifest permits cleartext and this refuses the rest with the platform's own
 * [UnknownServiceException].
 */
object TransportSecurity {
    /** Overlay and private-use names ATS's local networking misses. */
    private val exceptionDomains = listOf("ts.net", "netbird.cloud", "netbird.selfhosted", "home.arpa", "internal")

    /** Whether a plain-HTTP load to [host] is allowed. */
    fun allowsInsecureHTTP(host: String): Boolean {
        val address = HostAddress.of(host) ?: return false
        return when (val kind = address.kind) {
            is HostAddress.Kind.IPv4 -> {
                val b = kind.bytes
                address.isLoopback ||
                    b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) || // RFC 1918
                    (b[0] == 169 && b[1] == 254) || // link-local
                    (b[0] == 100 && b[1] in 64..127) // CGNAT 100.64.0.0/10: Tailscale, NetBird, WARP…
            }
            is HostAddress.Kind.IPv6 -> {
                val b = kind.bytes
                address.isLoopback ||
                    (b[0] and 0xfe) == 0xfc || // unique local fc00::/7 (Tailscale fd7a:115c:a1e0::/48)
                    (b[0] == 0xfe && (b[1] and 0xc0) == 0x80) // link-local fe80::/10
            }
            HostAddress.Kind.Name -> {
                val name = address.host.trimEnd('.')
                // Unqualified names and .local (mDNS) are local networking.
                !name.contains('.') || name.endsWith(".local") ||
                    exceptionDomains.any { name == it || name.endsWith(".$it") }
            }
        }
    }

    /** Refuses plain HTTP to hosts [allowsInsecureHTTP] rejects, before anything is sent. */
    val interceptor = Interceptor { chain ->
        val url = chain.request().url
        if (!url.isHttps && !allowsInsecureHTTP(url.host)) {
            throw UnknownServiceException("CLEARTEXT communication to ${url.host} not permitted by network security policy")
        }
        chain.proceed(chain.request())
    }
}
