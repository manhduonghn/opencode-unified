package ai.opencode.server.live

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.strings.localized
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.R
import ai.opencode.server.OpenCodeTransport
import ai.opencode.server.ServerLinkClient
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RequestIdempotency
import ai.opencode.server.retry.ServerContact
import ai.opencode.server.retry.TransportFailure
import okhttp3.Request
import java.io.IOException
import java.util.UUID

/**
 * One `GET /api/experimental/media/<hash>` on the transport's uncached `media` pool, through
 * `NetworkRetry`. It lives here because it shares `perform`'s correlation, capabilities and error
 * mapping; `:feature:media`'s `MediaReferenceLoader` owns the caches and coalescing.
 */
object MediaReferenceFetch {
    data class Response(val statusCode: Int, val data: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Response && statusCode == other.statusCode && data.contentEquals(other.data)

        override fun hashCode(): Int = 31 * statusCode + data.contentHashCode()
    }

    /** Enough of a hash to tell logs apart. */
    fun shortHash(hash: String): String = hash.take(12)

    /** Throws [OpenCodeClientError]. */
    suspend fun live(server: OpenCodeServer, hash: String): Response {
        val path = "/api/experimental/media/$hash"
        val url = dependency(ServerLinkClient).resolve(server).endpoint(path) ?: throw OpenCodeClientError(localized(R.string.server_error_invalid_url))
        val contact = server.persistenceID
        val transport = dependency(OpenCodeTransport)
        return NetworkRetry.run(
            NetworkRetry.currentPolicy(),
            idempotency = RequestIdempotency.Idempotent,
            label = "GET /api/experimental/media/${shortHash(hash)}",
            reachable = { ServerContact.shared.next(contact) },
        ) {
            // The `media` pool has no cache and OkHttp keeps no cookies, so no header is needed.
            val builder = Request.Builder().url(url).get().header("Accept", "*/*")
            server.authorize(builder)
            applyCapabilities(builder)
            val correlation = NetworkRetry.correlation.current() ?: NetworkRetry.Correlation()
            correlation.identify(builder, UUID.randomUUID().toString().uppercase())
            val response = try {
                transport.media.newCall(builder.build()).awaitResponse()
            } catch (error: IOException) {
                throw transportError(error, TransportFailure.of(error, url, ServerContact.shared))
            }
            val status = response.code
            if (status !in 200..299) throw statusError(status, response.headers["Retry-After"])
            ServerContact.shared.heard(contact, response.url)
            Response(status, response.body)
        }
    }
}
