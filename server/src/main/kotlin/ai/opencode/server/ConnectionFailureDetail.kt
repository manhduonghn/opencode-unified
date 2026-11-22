package ai.opencode.server

import ai.opencode.core.strings.localized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * IOS `URLError.Code` values, so failure evidence and copy read the same on both platforms.
 * [fromThrowable] maps the JVM/OkHttp exception to its closest code.
 */
object UrlErrorCode {
    const val UNKNOWN = -1
    const val CANCELLED = -999
    const val BAD_URL = -1000
    const val TIMED_OUT = -1001
    const val UNSUPPORTED_URL = -1002
    const val CANNOT_FIND_HOST = -1003
    const val CANNOT_CONNECT_TO_HOST = -1004
    const val NETWORK_CONNECTION_LOST = -1005
    const val DNS_LOOKUP_FAILED = -1006
    const val HTTP_TOO_MANY_REDIRECTS = -1007
    const val RESOURCE_UNAVAILABLE = -1008
    const val NOT_CONNECTED_TO_INTERNET = -1009
    const val REDIRECT_TO_NON_EXISTENT_LOCATION = -1010
    const val BAD_SERVER_RESPONSE = -1011
    const val USER_CANCELLED_AUTHENTICATION = -1012
    const val USER_AUTHENTICATION_REQUIRED = -1013
    const val CANNOT_PARSE_RESPONSE = -1017
    const val INTERNATIONAL_ROAMING_OFF = -1018
    const val CALL_IS_ACTIVE = -1019
    const val DATA_NOT_ALLOWED = -1020
    const val APP_TRANSPORT_SECURITY_REQUIRES_SECURE_CONNECTION = -1022
    const val SECURE_CONNECTION_FAILED = -1200
    const val SERVER_CERTIFICATE_HAS_BAD_DATE = -1201
    const val SERVER_CERTIFICATE_UNTRUSTED = -1202
    const val SERVER_CERTIFICATE_HAS_UNKNOWN_ROOT = -1203
    const val SERVER_CERTIFICATE_NOT_YET_VALID = -1204
    const val CLIENT_CERTIFICATE_REJECTED = -1205
    const val CLIENT_CERTIFICATE_REQUIRED = -1206
    const val CANNOT_LOAD_FROM_NETWORK = -2000

    fun fromThrowable(error: Throwable): Int? = when (error) {
        is CancellationException -> CANCELLED
        is UnknownHostException -> CANNOT_FIND_HOST
        is NoRouteToHostException -> CANNOT_CONNECT_TO_HOST
        is PortUnreachableException -> CANNOT_CONNECT_TO_HOST
        is ConnectException ->
            if (error.message?.contains("ENETUNREACH") == true || error.message?.contains("Network is unreachable") == true) {
                NOT_CONNECTED_TO_INTERNET
            } else CANNOT_CONNECT_TO_HOST
        is SocketTimeoutException -> TIMED_OUT
        is SSLPeerUnverifiedException -> SERVER_CERTIFICATE_UNTRUSTED
        is SSLHandshakeException -> if (error.hasCertificateCause()) SERVER_CERTIFICATE_UNTRUSTED else SECURE_CONNECTION_FAILED
        is SSLException -> SECURE_CONNECTION_FAILED
        // Android's network security config refusing cleartext.
        is UnknownServiceException -> APP_TRANSPORT_SECURITY_REQUIRES_SECURE_CONNECTION
        is InterruptedIOException -> if (error.message == "timeout") TIMED_OUT else if (error.message == "Canceled") CANCELLED else TIMED_OUT
        is EOFException -> NETWORK_CONNECTION_LOST
        is SocketException -> NETWORK_CONNECTION_LOST
        is IOException -> if (error.message == "Canceled") CANCELLED else NETWORK_CONNECTION_LOST
        else -> null
    }

    internal fun Throwable.hasCertificateCause(): Boolean {
        var current: Throwable? = cause
        while (current != null) {
            if (current is CertificateException || current is CertPathValidatorException) return true
            current = current.cause
        }
        return false
    }
}

/** Display/share-only evidence. Never interpolate this value into AppLog. */
data class ConnectionFailureDetail(
    val endpoint: String,
    val host: String,
    val urlErrorCode: Int?,
    val statusCode: Int?,
    val addresses: List<String>,
) {
    constructor(url: HttpUrl, error: Throwable, addresses: List<String> = emptyList()) : this(
        endpoint = endpointOf(url),
        host = url.host,
        urlErrorCode = if (error is OpenCodeClientError) error.urlErrorCode else UrlErrorCode.fromThrowable(error),
        statusCode = (error as? OpenCodeClientError)?.statusCode,
        addresses = addresses.sorted(),
    )

    val hasTailnetDNSMismatch: Boolean
        get() = host.lowercase().trim('.').endsWith(".ts.net") &&
            addresses.isNotEmpty() && addresses.none(::isTailnetAddress)

    val errorLabel: String
        get() {
            statusCode?.let { return "HTTP $it" }
            val code = urlErrorCode ?: return localized(R.string.server_connection_failed)
            val name = when (code) {
                UrlErrorCode.TIMED_OUT -> "timedOut"
                UrlErrorCode.CANNOT_FIND_HOST -> "cannotFindHost"
                UrlErrorCode.CANNOT_CONNECT_TO_HOST -> "cannotConnectToHost"
                UrlErrorCode.DNS_LOOKUP_FAILED -> "dnsLookupFailed"
                UrlErrorCode.NETWORK_CONNECTION_LOST -> "networkConnectionLost"
                UrlErrorCode.NOT_CONNECTED_TO_INTERNET -> "notConnectedToInternet"
                UrlErrorCode.SECURE_CONNECTION_FAILED -> "secureConnectionFailed"
                UrlErrorCode.SERVER_CERTIFICATE_UNTRUSTED -> "serverCertificateUntrusted"
                UrlErrorCode.SERVER_CERTIFICATE_HAS_BAD_DATE -> "serverCertificateHasBadDate"
                UrlErrorCode.SERVER_CERTIFICATE_HAS_UNKNOWN_ROOT -> "serverCertificateHasUnknownRoot"
                UrlErrorCode.SERVER_CERTIFICATE_NOT_YET_VALID -> "serverCertificateNotYetValid"
                UrlErrorCode.APP_TRANSPORT_SECURITY_REQUIRES_SECURE_CONNECTION -> "appTransportSecurityRequiresSecureConnection"
                UrlErrorCode.CANCELLED -> "cancelled"
                UrlErrorCode.BAD_URL -> "badURL"
                UrlErrorCode.UNSUPPORTED_URL -> "unsupportedURL"
                UrlErrorCode.RESOURCE_UNAVAILABLE -> "resourceUnavailable"
                UrlErrorCode.HTTP_TOO_MANY_REDIRECTS -> "httpTooManyRedirects"
                UrlErrorCode.REDIRECT_TO_NON_EXISTENT_LOCATION -> "redirectToNonExistentLocation"
                UrlErrorCode.BAD_SERVER_RESPONSE -> "badServerResponse"
                UrlErrorCode.USER_AUTHENTICATION_REQUIRED -> "userAuthenticationRequired"
                UrlErrorCode.USER_CANCELLED_AUTHENTICATION -> "userCancelledAuthentication"
                UrlErrorCode.CLIENT_CERTIFICATE_REJECTED -> "clientCertificateRejected"
                UrlErrorCode.CLIENT_CERTIFICATE_REQUIRED -> "clientCertificateRequired"
                UrlErrorCode.INTERNATIONAL_ROAMING_OFF -> "internationalRoamingOff"
                UrlErrorCode.CALL_IS_ACTIVE -> "callIsActive"
                UrlErrorCode.DATA_NOT_ALLOWED -> "dataNotAllowed"
                UrlErrorCode.CANNOT_LOAD_FROM_NETWORK -> "cannotLoadFromNetwork"
                else -> "urlLoadingFailed"
            }
            return "URLError $code $name"
        }

    val text: String
        get() {
            val lines = mutableListOf(
                "$errorLabel · $endpoint",
                if (addresses.isEmpty()) localized(R.string.server_connection_resolved_address_unknown)
                else localized(R.string.server_connection_resolved_address, addresses.joinToString(", ")),
            )
            if (hasTailnetDNSMismatch) {
                lines += localized(R.string.server_connection_tailnet_dns_mismatch)
            }
            return lines.joinToString("\n")
        }

    /** The only projection allowed into the log: no names, URLs or addresses. */
    val logSummary: String
        get() = "code=${urlErrorCode?.toString() ?: "unknown"} tailnetAddress=${if (addresses.isEmpty()) "unknown" else addresses.all(::isTailnetAddress).toString()}"

    companion object {
        private fun endpointOf(url: HttpUrl): String {
            val host = url.host
            val scheme = url.scheme.lowercase()
            val port = url.port
            val shown = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
            return "$scheme://$shown:$port"
        }

        fun isTailnetAddress(address: String): Boolean = HostAddress.of(address)?.route == ConnectionRoute.Tailscale

        /**
         * Best-effort system DNS, only after a failed connection probe: at most one
         * second, empty on cancellation or failure. No IP fallback.
         */
        suspend fun resolve(host: String): List<String> {
            if (host.isEmpty()) return emptyList()
            // The lookup blocks and can't be interrupted, so it runs detached: a late answer is
            // dropped instead of holding the connection flow past the second.
            val lookup = LOOKUP_SCOPE.async {
                runCatching {
                    InetAddress.getAllByName(host).mapNotNull { it.hostAddress?.let(::numericHost) }.toSortedSet().toList()
                }.getOrDefault(emptyList())
            }
            return try {
                withTimeoutOrNull(1.seconds) { lookup.await() } ?: emptyList()
            } catch (_: CancellationException) {
                emptyList()
            }
        }

        private val LOOKUP_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * An address as `getnameinfo(NI_NUMERICHOST)` writes it: Java spells IPv6 out in full
         * (`fe80:0:0:0:0:0:0:1%wlan0`), `inet_ntop` compresses the longest run of two or more
         * zero groups (`fe80::1%wlan0`).
         */
        internal fun numericHost(address: String): String {
            if (!address.contains(':')) return address
            val percent = address.indexOf('%')
            val bare = if (percent >= 0) address.substring(0, percent) else address
            val zone = if (percent >= 0) address.substring(percent) else ""
            val bytes = HostAddress.parseIPv6(bare) ?: return address
            val groups = (0 until 8).map { (bytes[it * 2] shl 8) or bytes[it * 2 + 1] }
            var bestStart = -1
            var bestLength = 0
            var start = -1
            for (index in 0..8) {
                if (index < 8 && groups[index] == 0) {
                    if (start < 0) start = index
                } else if (start >= 0) {
                    if (index - start > bestLength) {
                        bestStart = start
                        bestLength = index - start
                    }
                    start = -1
                }
            }
            if (bestLength < 2) bestStart = -1
            val text = StringBuilder()
            var index = 0
            while (index < 8) {
                if (index == bestStart) {
                    text.append("::")
                    index += bestLength
                    continue
                }
                if (text.isNotEmpty() && !text.endsWith(":")) text.append(':')
                text.append(Integer.toHexString(groups[index]))
                index++
            }
            return text.toString() + zone
        }
    }
}
