package ai.opencode.feature.voice

import ai.opencode.core.diagnostics.DiagnosticsErrorCategory
import ai.opencode.server.UrlErrorCode

/**
 * What a failed or closed Realtime socket reported, for diagnostics: the
 * error's domain and code (and the network error under it), and the
 * WebSocket close code and reason when the service sent them.
 *
 * On Android an exception has no `NSError` domain/code: a network exception reads; anything else as
 * its class name with code 0.
 */
data class RealtimeTransportDiagnostic(
    val domain: String,
    val code: Int,
    /** `URLSessionWebSocketTask.CloseCode.invalid` (0) when the socket sent none. */
    val closeCode: Int = CLOSE_CODE_INVALID,
    val underlyingDomain: String? = null,
    val underlyingCode: Int? = null,
    val closeReason: String? = null,
) {
    val errorCategory: DiagnosticsErrorCategory
        get() {
            val category = DiagnosticsErrorCategory.of(domain, code)
            return if (category == DiagnosticsErrorCategory.Transport && underlyingDomain != null && underlyingCode != null)
                DiagnosticsErrorCategory.of(underlyingDomain, underlyingCode) else category
        }

    /** For the app log: codes only, never the close reason, which is the service's own text. */
    val logSummary: String
        get() {
            val parts = mutableListOf("domain=$domain", "code=$code", "closeCode=$closeCode")
            if (underlyingDomain != null && underlyingCode != null) parts += "underlying=$underlyingDomain:$underlyingCode"
            return parts.joinToString(" ")
        }

    val summary: String
        get() {
            val parts = mutableListOf("domain=$domain", "code=$code", "closeCode=$closeCode")
            if (underlyingDomain != null && underlyingCode != null) parts += "underlying=$underlyingDomain:$underlyingCode"
            if (closeReason != null) parts += "closeReason=$closeReason"
            return parts.joinToString(" ")
        }

    companion object {
        const val CLOSE_CODE_INVALID = 0
        private const val URL_ERROR_DOMAIN = "NSURLErrorDomain"

        /** The designated init: an empty close reason is none. */
        fun make(
            domain: String,
            code: Int,
            closeCode: Int = CLOSE_CODE_INVALID,
            underlyingDomain: String? = null,
            underlyingCode: Int? = null,
            closeReason: String? = null,
        ) = RealtimeTransportDiagnostic(
            domain, code, closeCode, underlyingDomain, underlyingCode,
            closeReason?.trim()?.takeIf { it.isNotEmpty() },
        )

        /** A transport error that already carries a diagnostic returns it. */
        fun of(error: Throwable, closeCode: Int = CLOSE_CODE_INVALID, closeReason: String? = null): RealtimeTransportDiagnostic {
            (error as? CarriesRealtimeTransportDiagnostic)?.transportDiagnostic?.let { return it }
            val (domain, code) = domainAndCode(error)
            val underlying = error.cause?.let(::domainAndCode)
            return make(
                domain = domain,
                code = code,
                closeCode = closeCode,
                underlyingDomain = underlying?.first,
                underlyingCode = underlying?.second,
                closeReason = closeReason,
            )
        }

        private fun domainAndCode(error: Throwable): Pair<String, Int> {
            val category = DiagnosticsErrorCategory.of(error)
            when (category) {
                DiagnosticsErrorCategory.Timeout -> return URL_ERROR_DOMAIN to UrlErrorCode.TIMED_OUT
                DiagnosticsErrorCategory.Offline -> return URL_ERROR_DOMAIN to (UrlErrorCode.fromThrowable(error) ?: UrlErrorCode.NETWORK_CONNECTION_LOST)
                DiagnosticsErrorCategory.Cancelled -> return URL_ERROR_DOMAIN to UrlErrorCode.CANCELLED
                else -> Unit
            }
            // The shared URL helper treats every IOException as network loss.
            // Diagnostics must leave unclassified I/O/socket failures as transport.
            UrlErrorCode.fromThrowable(error)?.takeUnless {
                it == UrlErrorCode.NETWORK_CONNECTION_LOST || it == UrlErrorCode.TIMED_OUT || it == UrlErrorCode.CANCELLED
            }?.let { return URL_ERROR_DOMAIN to it }
            return (error::class.java.simpleName.ifEmpty { "Throwable" }) to 0
        }
    }
}

/**
 * Implemented by the Realtime transport's error for its cases that carry a diagnostic
 * (`sessionEnded`, `transportLost`), so [RealtimeTransportDiagnostic.of] returns it unchanged.
 */
interface CarriesRealtimeTransportDiagnostic {
    val transportDiagnostic: RealtimeTransportDiagnostic?
}
