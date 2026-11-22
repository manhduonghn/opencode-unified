package ai.opencode.server.live

import ai.opencode.core.strings.localized
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.R
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/** A cached `childID → parentID` answer: [parent] null for a root. */
internal data class KnownParent(val parent: String?)

/**
 * Process-wide decoded reads shared by every screen: model catalogs for 30 s, session parents for
 * the app's lifetime, missing sessions for 5 s, in-flight session lookups joined, and a per-server
 * lookup permit pool.
 */
internal class OpenCodeReadCache {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun now(): Duration = System.nanoTime().nanoseconds

    private class CatalogEntry(val catalog: OpenCodeModelCatalog, val storedAt: Duration)

    private val modelCatalogs = HashMap<String, CatalogEntry>()

    fun modelCatalog(key: String): OpenCodeModelCatalog? = synchronized(lock) {
        val entry = modelCatalogs[key] ?: return null
        if (now() - entry.storedAt >= MODEL_CATALOG_LIFETIME) return null
        entry.catalog
    }

    /** An empty catalog is not kept, so "No Models … then try again" can succeed once a provider connects. */
    fun storeModelCatalog(catalog: OpenCodeModelCatalog, key: String) {
        synchronized(lock) {
            if (catalog.models.isEmpty()) modelCatalogs.remove(key) else modelCatalogs[key] = CatalogEntry(catalog, now())
        }
    }

    fun forgetModelCatalogs(server: String) {
        synchronized(lock) { modelCatalogs.keys.removeAll { it.startsWith("$server|") } }
    }

    private val sessionParents = HashMap<String, HashMap<String, String?>>()
    private val missingSessions = HashMap<String, HashMap<String, Duration>>()

    fun sessionParents(server: String): Map<String, KnownParent> = synchronized(lock) {
        sessionParents[server]?.mapValues { KnownParent(it.value) } ?: emptyMap()
    }

    fun sessionParent(sessionID: String, server: String): KnownParent? = synchronized(lock) {
        val parents = sessionParents[server] ?: return null
        if (!parents.containsKey(sessionID)) return null
        KnownParent(parents[sessionID])
    }

    fun storeSessionParent(parentID: String?, sessionID: String, server: String) {
        synchronized(lock) {
            missingSessions[server]?.remove(sessionID)
            val parents = sessionParents.getOrPut(server) { HashMap() }
            if (parents.size >= SESSION_PARENT_LIMIT) parents.clear()
            parents[sessionID] = parentID
        }
    }

    fun storeMissingSession(sessionID: String, server: String) {
        synchronized(lock) {
            if (sessionParents[server]?.containsKey(sessionID) == true) return
            val missing = missingSessions.getOrPut(server) { HashMap() }
            if (missing.size >= SESSION_PARENT_LIMIT) missing.clear()
            missing[sessionID] = now() + MISSING_SESSION_TTL
        }
    }

    fun isSessionMissing(sessionID: String, server: String): Boolean = synchronized(lock) {
        val expiresAt = missingSessions[server]?.get(sessionID) ?: return false
        if (now() < expiresAt) return true
        missingSessions[server]?.remove(sessionID)
        false
    }

    fun noteActiveSnapshot(server: String) {
        synchronized(lock) { missingSessions.remove(server) }
    }

    private val permits = HashMap<String, Semaphore>()

    /** Waits for one of [limit] lookup slots on [server]; cancellation throws. */
    suspend fun acquireLookupPermit(server: String, limit: Int) {
        val semaphore = synchronized(lock) { permits.getOrPut(server) { Semaphore(limit) } }
        semaphore.acquire()
    }

    fun releaseLookupPermit(server: String) {
        synchronized(lock) { permits[server] }?.release()
    }

    private class InFlight(val deferred: Deferred<Result<OpenCodeSession?>>, var waiters: Int)

    private val inFlight = HashMap<String, HashMap<String, InFlight>>()

    /**
     * Joins an in-flight lookup of the same session on the same server, or starts [operation]
     * (inheriting the caller's dependencies and retry scope). The last waiter to leave cancels it.
     */
    suspend fun lookupSession(
        server: String,
        sessionID: String,
        operation: suspend () -> Result<OpenCodeSession?>,
    ): Result<OpenCodeSession?> {
        val context = currentCoroutineContext().minusKey(Job)
        val flight = synchronized(lock) {
            val perServer = inFlight.getOrPut(server) { HashMap() }
            perServer[sessionID]?.also { it.waiters += 1 } ?: run {
                val deferred = scope.async(context) { operation() }
                val created = InFlight(deferred, 1)
                perServer[sessionID] = created
                deferred.invokeOnCompletion {
                    synchronized(lock) { if (inFlight[server]?.get(sessionID) === created) inFlight[server]?.remove(sessionID) }
                }
                created
            }
        }
        try {
            return flight.deferred.await()
        } catch (cancelled: CancellationException) {
            synchronized(lock) {
                flight.waiters -= 1
                if (flight.waiters == 0) {
                    flight.deferred.cancel()
                    if (inFlight[server]?.get(sessionID) === flight) inFlight[server]?.remove(sessionID)
                }
            }
            throw cancelled
        }
    }

    companion object {
        val shared = OpenCodeReadCache()

        /** Long enough for screens that open together to share one fetch; short enough for config changes. */
        private val MODEL_CATALOG_LIFETIME = 30.seconds
        private const val SESSION_PARENT_LIMIT = 4_096
        private val MISSING_SESSION_TTL = 5.seconds

        val ancestryError get() = OpenCodeClientError(localized(R.string.server_error_load_session_ancestry))
    }
}
