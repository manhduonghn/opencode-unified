package ai.opencode.server

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.diagnostics.DiagnosticsRecorder
import ai.opencode.core.log.AppLog
import ai.opencode.server.live.requestHealth
import ai.opencode.server.retry.AppPhase
import ai.opencode.server.retry.AppPhases
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RetryPolicy
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Which of a server's addresses to use right now. A saved server keeps the address order
// pairing or the person gave it; the address that answered last lives here, in memory, so a
// LAN address that worked at home never becomes the one tried first everywhere else.

/** The outcome of one address race, for the log and diagnostics: kinds, counts and timings only, never an address. */
data class ServerLinkRace(
    /** The winner's route ("Tailscale", "Local Network"), null when none answered. */
    val route: String?,
    val milliseconds: Int,
    val candidates: Int,
) {
    val logSummary: String
        get() {
            if (route == null) return "no address answered ($candidates tried) after $milliseconds ms"
            return "$route answered first in $milliseconds ms ($candidates tried)"
        }
}

/** Why the app starts its connections over. */
enum class ServerLinkReset(val rawValue: String) {
    /** The network path changed (`NetworkPathClient.reroutes`). */
    Reroute("reroute"),

    /** Back from the background; acted on only after [ServerLinkStore.longBackground]. */
    Foreground("foreground"),

    /** The person tapped Try Again. */
    Retry("retry"),

    /** Requests timed out twice in a row on a network that's up. */
    Timeouts("timeouts"),
}

/** Counts what strands a request in flight: fresh pools, or a server moving to another address. */
data class ServerLinkEpoch(
    /** Bumped by every pool reset. */
    val pools: Int = 0,
    /** Bumped by every reset and every change of a server's address. */
    val changes: Int = 0,
)

data class ServerLinkClient(
    /** `server` at the address that last answered, or as saved if none has. */
    val resolve: (server: OpenCodeServer) -> OpenCodeServer,
    /**
     * Where a request goes now: like [resolve], but first waits for a race in flight, or runs one
     * when several addresses are saved and none has answered yet. Falls back to [resolve] when the
     * race fails.
     */
    val route: suspend (server: OpenCodeServer) -> OpenCodeServer,
    /**
     * Races every address at once; the first to answer as this server wins and the rest are
     * cancelled. Callers for the same server share a race. Throws [OpenCodeClientError].
     */
    val probe: suspend (server: OpenCodeServer) -> OpenCodeServer,
    /** `server.url` answered (its event stream connected): use it from now on. */
    val adopt: (server: OpenCodeServer) -> Unit,
    /**
     * Forgets the address in use for `server`, so the next request races again: always when
     * `failingURL` is null (the route changed, the person asked), else only when that address is
     * the one in use and failed.
     */
    val invalidate: (server: OpenCodeServer, failingURL: String?) -> Unit = { _, _ -> },
    /**
     * A request sent at `sentAt` reached `failingURL` (it wasn't `NotSent`) but got no answer:
     * forgets the address like [invalidate], unless the event stream on it delivered a line since
     * the request went (the address is up; that socket wedged), once in a row.
     */
    val requestFailed: (server: OpenCodeServer, failingURL: String, sentAt: Duration) -> Unit = { _, _, _ -> },
    /** The event stream to `server.url` delivered a line. */
    val noteStreamActivity: (server: OpenCodeServer) -> Unit,
    /** The event stream to `server.url` ended. */
    val noteStreamEnded: (server: OpenCodeServer) -> Unit = {},
    /** How long since that stream last delivered anything; null if it never has. */
    val streamSilence: (server: String) -> Duration?,
    /**
     * Starts every connection over: fresh pools (`OpenCodeTransport.reset`), addresses raced
     * again, reads in flight interrupted to retry on them.
     */
    val resetConnections: (reason: ServerLinkReset) -> Unit = {},
    /** A request (not a long poll) timed out on a network that's up. */
    val noteTimeout: () -> Unit = {},
    /** A request was answered; by `server` (its `persistenceID`) unless it was an address probe. */
    val noteAnswer: (server: String?) -> Unit = {},
    val epoch: () -> ServerLinkEpoch = { ServerLinkEpoch() },
    /** Returns once `epoch().changes` differs from `since`, or when cancelled. */
    val changed: suspend (since: Int) -> Unit = { awaitCancellation() },
) {
    companion object Key : DependencyKey<ServerLinkClient> {
        fun live(store: ServerLinkStore): ServerLinkClient = ServerLinkClient(
            resolve = { store.resolve(it) },
            route = { store.route(it) },
            probe = { store.probe(it) },
            adopt = { store.adopt(it) },
            invalidate = { server, failingURL -> store.invalidate(server, failingURL) },
            requestFailed = { server, failingURL, sentAt -> store.requestFailed(server, failingURL, sentAt) },
            noteStreamActivity = { store.noteStreamActivity(it) },
            noteStreamEnded = { store.noteStreamEnded(it) },
            streamSilence = { store.streamSilence(it) },
            resetConnections = { store.reset(it) },
            noteTimeout = { store.noteTimeout() },
            noteAnswer = { store.noteAnswer(it) },
            epoch = { store.epoch },
            changed = { store.changed(it) },
        )

        override val liveValue: ServerLinkClient by lazy {
            val store = ServerLinkStore(
                health = { requestHealth(it) },
                resetPools = { OpenCodeTransport.liveValue.reset() },
            )
            store.followAppPhases()
            live(store)
        }

        /**
         * Requests go to the server as given and nothing is remembered; a probe still races the
         * test's `OpenCodeClient.health` over every address.
         */
        override val testValue: ServerLinkClient = ServerLinkClient(
            resolve = { it },
            route = { it },
            probe = { server ->
                val health = dependency(OpenCodeClient).health
                ServerLinkStore.race(server, health).result.getOrThrow()
            },
            adopt = {},
            noteStreamActivity = {},
            streamSilence = { null },
        )
    }
}

/** The live memory behind [ServerLinkClient], keyed by `persistenceID`. */
class ServerLinkStore(
    private val health: suspend (OpenCodeServer) -> Unit,
    private val resetPools: () -> Unit = {},
    private val now: () -> Duration = { Clock.liveValue.now() },
) {
    /** A race: who it authenticated as, and the winning address it settles on. */
    private class Race(val id: UUID, val credentials: Credentials, val result: Deferred<Result<String>>)

    /** What a race authenticated with; a caller with other credentials gets a race of its own. */
    private data class Credentials(val password: String, val headers: List<OpenCodeServer.Header>)

    private val lock = Any()
    private val active = HashMap<String, String>()
    private val races = HashMap<String, Race>()
    private val activity = HashMap<String, Duration>()

    /** The address each server's live event stream is on. */
    private val streams = HashMap<String, String>()

    /** Requests failed in a row on an address kept for its live stream. */
    private val failures = HashMap<String, Int>()
    private val epochs = MutableStateFlow(ServerLinkEpoch())
    private var timeouts = 0

    /** A request was answered on the pools in use: a timeout now points at them, not at a server that's away. */
    private var answeredSinceReset = false
    private var lastReset: Duration? = null
    private var backgroundedAt: Duration? = null
    private var lastBackground: Duration? = null

    /**
     * Times the app's trips to the background, for [ServerLinkReset.Foreground] resets. Observed
     * synchronously, where they're reported: Home acts on the app becoming active, and an
     * asynchronous relay could land after it asks.
     */
    fun followAppPhases() {
        AppPhases.observe { note(it) }
    }

    fun note(phase: AppPhase) {
        val now = now()
        synchronized(lock) {
            when (phase) {
                AppPhase.Background -> backgroundedAt = now
                AppPhase.Active -> {
                    lastBackground = backgroundedAt?.let { now - it }
                    backgroundedAt = null
                }
            }
        }
    }

    val epoch: ServerLinkEpoch get() = epochs.value

    suspend fun changed(since: Int) {
        epochs.first { it.changes != since }
    }

    fun reset(reason: ServerLinkReset) {
        val now = now()
        synchronized(lock) {
            if (reason == ServerLinkReset.Foreground) {
                // Still marked away when Home's resume beats the app's own activation.
                val away = backgroundedAt?.let { now - it } ?: lastBackground
                backgroundedAt = null
                lastBackground = null
                if (away == null || away < longBackground) return
            }
            val last = lastReset
            if (last != null && now - last < resetSpacing) return
            lastReset = now
            timeouts = 0
            answeredSinceReset = false
            // Whichever address answered before may be on the network just left.
            active.clear()
            failures.clear()
            val current = epochs.value
            epochs.value = current.copy(pools = current.pools + 1, changes = current.changes + 1)
        }
        resetPools()
        AppLog.network.info { "Connections reset: ${reason.rawValue}" }
        DiagnosticsRecorder.shared.recordLink("pool_reset", reason.rawValue)
    }

    /**
     * Two timeouts in a row reset the pools, but only once these pools have answered something:
     * timeouts from a host that's simply away would otherwise throw fresh pools away every two
     * requests.
     */
    fun noteTimeout() {
        val resets = synchronized(lock) {
            timeouts += 1
            timeouts >= 2 && answeredSinceReset
        }
        if (resets) reset(ServerLinkReset.Timeouts)
    }

    fun noteAnswer(server: String? = null) {
        synchronized(lock) {
            timeouts = 0
            answeredSinceReset = true
            if (server != null) failures.remove(server)
        }
    }

    /** A server's address changed: reads waiting on the old one go again. Call under [lock]. */
    private fun moved() {
        val current = epochs.value
        epochs.value = current.copy(changes = current.changes + 1)
    }

    fun resolve(server: OpenCodeServer): OpenCodeServer {
        val address = synchronized(lock) { active[server.persistenceID] } ?: return server
        if (address == server.url || address !in server.candidateURLs) return server
        return server.at(address)
    }

    suspend fun route(server: OpenCodeServer): OpenCodeServer {
        if (server.candidateURLs.size <= 1) return server
        val key = server.persistenceID
        val known: Boolean
        val racing: Boolean
        synchronized(lock) {
            known = active[key] != null
            racing = races[key] != null
        }
        if (!racing && known) return resolve(server)
        return try {
            probe(server)
        } catch (_: OpenCodeClientError) {
            resolve(server)
        }
    }

    suspend fun probe(server: OpenCodeServer): OpenCodeServer {
        val key = server.persistenceID
        val credentials = Credentials(server.password, server.headers)
        // The race outlives the caller that started it, as other callers share it; it keeps the
        // caller's dependencies and retry scope.
        val scope = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
        val result = synchronized(lock) {
            val running = races[key]
            if (running != null && running.credentials == credentials) {
                running.result
            } else {
                val id = UUID.randomUUID()
                val task = scope.async {
                    val (outcome, _) = race(server, health)
                    val winner = outcome.map { it.url }
                    settle(key, id, winner)
                    winner
                }
                races[key] = Race(id, credentials, task)
                task
            }
        }
        val winner = result.await().getOrThrow()
        return server.at(winner)
    }

    fun adopt(server: OpenCodeServer) {
        synchronized(lock) {
            val previous = active[server.persistenceID]
            active[server.persistenceID] = server.url
            failures.remove(server.persistenceID)
            if (previous != null && previous != server.url) moved()
        }
    }

    fun invalidate(server: OpenCodeServer, failingURL: String?) {
        synchronized(lock) {
            val key = server.persistenceID
            val address = active[key] ?: return
            if (failingURL != null && failingURL != address) return
            failures.remove(key)
            active.remove(key)
        }
    }

    fun requestFailed(server: OpenCodeServer, failingURL: String, sentAt: Duration) {
        val kept = synchronized(lock) {
            val key = server.persistenceID
            // The stream on this address heard from it after this request went: the next attempt
            // goes straight back rather than racing first. Twice in a row, or with no line since,
            // it's forgotten as usual.
            val heard = activity[key]
            if (active[key] != failingURL || streams[key] != failingURL || heard == null || heard <= sentAt) {
                false
            } else {
                val count = (failures[key] ?: 0) + 1
                if (count >= failuresBeforeForgetting) {
                    false
                } else {
                    failures[key] = count
                    true
                }
            }
        }
        if (!kept) invalidate(server, failingURL)
    }

    fun noteStreamActivity(server: OpenCodeServer) {
        val now = now()
        synchronized(lock) {
            activity[server.persistenceID] = now
            streams[server.persistenceID] = server.url
        }
    }

    fun noteStreamEnded(server: OpenCodeServer) {
        synchronized(lock) {
            if (streams[server.persistenceID] != server.url) return
            streams.remove(server.persistenceID)
            failures.remove(server.persistenceID)
        }
    }

    fun streamSilence(server: String): Duration? {
        val now = now()
        val last = synchronized(lock) { activity[server] } ?: return null
        return now - last
    }

    private fun settle(key: String, id: UUID, result: Result<String>) {
        synchronized(lock) {
            // A race superseded by one with other credentials says nothing now.
            if (races[key]?.id != id) return
            races.remove(key)
            val previous = active[key]
            failures.remove(key)
            val winner = result.getOrNull()
            if (winner != null) {
                active[key] = winner
            } else {
                // Nothing answered: the address that did before is no better than the others, so
                // the next request races again instead of using it.
                active.remove(key)
            }
            if (previous != active[key]) moved()
        }
    }

    /** A race's result and its summary for the log. */
    data class RaceOutcome(val result: Result<OpenCodeServer>, val summary: ServerLinkRace)

    companion object {
        /**
         * Each address gets this long to answer `/api/info`. A reachable server answers in well
         * under a second, even through a relay; a blackholed address shouldn't hold the others up.
         */
        val probeTimeout: Duration = 3.5.seconds

        /** A return from the background this long resets the pools: the system may have torn the sockets down. */
        val longBackground: Duration = 20.seconds

        /** Resets closer together than this are one (every window reroutes). */
        val resetSpacing: Duration = 1.seconds

        /** Failures in a row on an address with a live stream before it's forgotten anyway. */
        const val failuresBeforeForgetting = 2

        /** How long [OpenCodeServer.firstAttemptURLs] have to answer before the other addresses are tried too. */
        val preferredHeadStart: Duration = 1.5.seconds

        /**
         * Every address at once, except that those outside [OpenCodeServer.firstAttemptURLs] wait
         * until those have all failed or had [preferredHeadStart]. The first that answers as this
         * server wins and the rest are cancelled, so no slower attempt keeps a socket open. With no
         * winner, a refused sign-in (401) beats an unreachable address, since retrying can't fix it.
         */
        suspend fun race(server: OpenCodeServer, health: suspend (OpenCodeServer) -> Unit): RaceOutcome {
            val candidates = server.candidateURLs
            val first = server.firstAttemptURLs
            val started = Clock.liveValue.now()
            val result: Result<OpenCodeServer> = coroutineScope {
                val outcomes = Channel<Result<OpenCodeServer>>(Channel.UNLIMITED)
                val firstAllFailed = CompletableDeferred<Unit>()
                for (url in candidates) {
                    val candidate = server.at(url)
                    launch {
                        if (url !in first) withTimeoutOrNull(preferredHeadStart) { firstAllFailed.await() }
                        val outcome = try {
                            NetworkRetry.policy.withValue(RetryPolicy.once) { health(candidate) }
                            Result.success(candidate)
                        } catch (error: OpenCodeClientError) {
                            Result.failure(error)
                        }
                        outcomes.send(outcome)
                    }
                }
                val failures = mutableListOf<OpenCodeClientError>()
                repeat(candidates.size) {
                    val outcome = outcomes.receive()
                    if (outcome.isSuccess) {
                        coroutineContext.cancelChildren()
                        return@coroutineScope outcome
                    }
                    failures += outcome.exceptionOrNull() as OpenCodeClientError
                    // The other addresses wait, so the first failures are all the first addresses'.
                    if (failures.size == first.size) firstAllFailed.complete(Unit)
                }
                val details = mutableListOf<ConnectionFailureDetail>()
                for (failure in failures) details += failure.connectionFailures
                val failure = failures.firstOrNull { it.statusCode == 401 }
                    ?: failures.lastOrNull()
                    ?: OpenCodeClientError(OpenCodeClientError.UNREACHABLE_MESSAGE)
                Result.failure(failure.copy(connectionFailures = details))
            }
            val milliseconds = (Clock.liveValue.now() - started).inWholeMicroseconds.toDouble() / 1000.0
            val winner = result.getOrNull()
            val route = if (winner != null) ConnectionRoute.of(winner.url).rawValue else null
            val summary = ServerLinkRace(route, milliseconds.roundToInt(), candidates.size)
            if (currentCoroutineContext().isActive) {
                AppLog.network.info { "Address race: ${summary.logSummary}" }
                DiagnosticsRecorder.shared.recordLink("link_race", summary.logSummary)
            }
            return RaceOutcome(result, summary)
        }
    }
}
