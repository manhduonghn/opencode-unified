package ai.opencode.server

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException

/**
 * Under tests every request through [OpenCodeTransport.testValue] passes [interceptor]. It answers
 * registered fixture hosts, lets loopback through (and everything inside [withRealNetworkAllowed]),
 * and fails the rest as unreachable, reporting method, scheme and host to [onBlocked].
 */
object TestNetworkBlock {
    private val lock = Any()
    private val mocks = HashMap<String, Interceptor>()
    private var realNetworkDepth = 0

    /** Called with (method, scheme, host) for every blocked request. */
    @Volatile
    var onBlocked: (method: String, scheme: String, host: String) -> Unit = { _, _, _ -> }

    private val loopbackHosts = setOf("localhost", "127.0.0.1", "::1", "[::1]")

    /** Routes every request to [host] (case-insensitive) to [responder] instead of the network. */
    fun registerMock(host: String, responder: Interceptor) {
        synchronized(lock) { mocks[host.lowercase()] = responder }
    }

    fun unregisterMock(host: String) {
        synchronized(lock) { mocks.remove(host.lowercase()) }
    }

    fun registeredMockHosts(): Set<String> = synchronized(lock) { mocks.keys.toSet() }

    fun clearMocks() {
        synchronized(lock) { mocks.clear() }
    }

    /** Runs [block] with real network allowed (an opt-in live contract test). */
    inline fun <R> withRealNetworkAllowed(block: () -> R): R {
        enterRealNetwork()
        try {
            return block()
        } finally {
            exitRealNetwork()
        }
    }

    @PublishedApi internal fun enterRealNetwork() = synchronized(lock) { realNetworkDepth += 1 }

    @PublishedApi internal fun exitRealNetwork() = synchronized(lock) { realNetworkDepth -= 1 }

    val interceptor: Interceptor = Interceptor { chain -> dispatch(chain, chain.request(), redirects = 0) }

    private fun dispatch(chain: Interceptor.Chain, request: Request, redirects: Int): Response {
        val host = request.url.host.lowercase()
        val (mock, allowed) = synchronized(lock) { mocks[host] to (realNetworkDepth > 0 || host in loopbackHosts) }
        if (mock == null) {
            if (allowed) return chain.proceed(request)
            // OkHttp still runs application interceptors for a call cancelled before it started,
            // often after its fixture closed; it would never reach the network.
            if (chain.call().isCanceled()) throw IOException("Canceled")
            val scheme = request.url.scheme
            onBlocked(request.method, scheme, host)
            throw ConnectException("Blocked unmocked network request (${request.method} $scheme://$host) in unit test")
        }
        val response = mock.intercept(MockChain(chain, request))
        val location = response.header("Location")
        if (response.code !in 300..399 || location == null || !chain.followRedirects) return response
        if (redirects >= MAX_REDIRECTS) throw IOException("Too many follow-up requests: ${redirects + 1}")
        val target = response.request.url.resolve(location) ?: return response
        val keepsBody = response.code == 307 || response.code == 308
        val next = request.newBuilder().url(target).apply {
            if (!keepsBody && request.method != "GET" && request.method != "HEAD") method("GET", null)
        }.build()
        response.close()
        return dispatch(chain, next, redirects + 1)
    }

    /** The chain a mock sees: its own request, and [proceed] answered by the mock itself. */
    private class MockChain(private val base: Interceptor.Chain, private val request: Request) : Interceptor.Chain by base {
        override fun request(): Request = request
        override fun proceed(request: Request): Response =
            throw IOException("A mocked host can't proceed to the network")
    }

    private const val MAX_REDIRECTS = 20
}
