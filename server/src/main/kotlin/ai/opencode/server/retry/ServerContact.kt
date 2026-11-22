package ai.opencode.server.retry

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.awaitClose
import okhttp3.HttpUrl

/**
 * Hearing from a server again: any successful answer to a request, or its event stream
 * connecting, proves the route is back. Retries waiting out a connection failure's
 * backoff then try again at once. Keyed by `OpenCodeServer.persistenceID`.
 */
class ServerContact {
    private val lock = Any()
    private val observers = HashMap<String, MutableList<(Unit) -> Unit>>()
    private val answeredHTTPOrigins = HashSet<String>()

    fun hasAnsweredHTTPOrigin(url: HttpUrl): Boolean {
        val origin = httpOrigin(url) ?: return false
        return synchronized(lock) { origin in answeredHTTPOrigins }
    }

    /** Each answer from [server] from now on (newest one buffered). */
    fun answers(server: String): Flow<Unit> = callbackFlow {
        val observer: (Unit) -> Unit = { trySend(Unit) }
        synchronized(lock) { observers.getOrPut(server) { mutableListOf() }.add(observer) }
        awaitClose {
            synchronized(lock) {
                observers[server]?.remove(observer)
                if (observers[server]?.isEmpty() == true) observers.remove(server)
            }
        }
    }.buffer(1, BufferOverflow.DROP_OLDEST)

    /** Returns at the next answer from [server], or when cancelled. */
    suspend fun next(server: String) {
        answers(server).first()
    }

    fun heard(server: String, url: HttpUrl? = null) {
        url?.let(::httpOrigin)?.let { origin -> synchronized(lock) { answeredHTTPOrigins.add(origin) } }
        val waiting = synchronized(lock) { observers[server]?.toList() ?: emptyList() }
        for (observer in waiting) observer(Unit)
    }

    companion object {
        val shared = ServerContact()

        /** Scheme, host and port only. Another address, HTTPS, or another port lends nothing. */
        fun httpOrigin(url: HttpUrl): String? {
            if (url.scheme.lowercase() != "http") return null
            return "http://${url.host.lowercase()}:${url.port}"
        }
    }
}
