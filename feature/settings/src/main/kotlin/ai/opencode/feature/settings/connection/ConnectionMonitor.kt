package ai.opencode.feature.settings.connection

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.shared
import ai.opencode.feature.settings.ConnectionStatus
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeTransport
import ai.opencode.server.ServerLinkClient
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RetryPolicy
import ai.opencode.server.retry.ServerContact
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.DurationUnit

/**
 * Health checks for the connection card: a light `GET /api/info` every two seconds (1.5 while
 * reconnecting, never more than one out at a time), only while the card is on screen and the app
 * active, timed from the phone. Also finds the server's machine name by a reverse lookup of its IP,
 * since V2 servers report only addresses.
 *
 * A single-shot probe on the transport's request pool, not an `OpenCodeClient` call: each beat is
 * one measured round trip and a missed one is a dropped beat, so nothing retries
 * ([RetryPolicy.once]).
 */
class ConnectionProbeClient(
    val check: suspend (OpenCodeServer) -> Answer,
    /** The host name a reverse lookup of an address finds, if any. */
    val reverseName: suspend (String) -> String?,
) {
    data class Answer(val milliseconds: Int, val version: String?)

    companion object Key : DependencyKey<ConnectionProbeClient> {
        override val liveValue: ConnectionProbeClient by lazy {
            ConnectionProbeClient(
                check = { server -> liveCheck(dependency(ServerLinkClient).resolve(server)) },
                reverseName = { address -> withContext(Dispatchers.IO) { reverseLookup(address) } },
            )
        }

        override val testValue: ConnectionProbeClient = ConnectionProbeClient(
            check = { throw IOException("The Internet connection appears to be offline.") },
            reverseName = { null },
        )

        /** An idle timeout, not a total deadline. */
        private const val TIMEOUT_SECONDS = 4L

        private val json = Json { ignoreUnknownKeys = true }

        fun request(server: OpenCodeServer): Request {
            val url = server.endpoint("/api/info") ?: throw IOException("bad URL")
            val builder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
            return server.authorize(builder).build()
        }

        suspend fun liveCheck(
            server: OpenCodeServer,
            contact: ServerContact = ServerContact.shared,
            client: OkHttpClient = dependency(OpenCodeTransport).request,
        ): Answer {
            val request = request(server)
            val probe = client.newBuilder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            val start = System.nanoTime()
            val (status, finalUrl, body) = NetworkRetry.policy.withValue(RetryPolicy.once) {
                probe.newCall(request).await()
            }
            val elapsedMillis = (System.nanoTime() - start) / 1_000_000.0
            if (status !in 200 until 300) throw IOException("bad server response")
            contact.heard(server.persistenceID, finalUrl)
            val version = runCatching {
                json.parseToJsonElement(body).jsonObject["version"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            return Answer(max(1, elapsedMillis.roundToInt()), version)
        }

        private data class Result(val status: Int, val url: okhttp3.HttpUrl, val body: String)

        private suspend fun Call.await(): Result = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { Result(it.code, it.request.url, it.body.string()) }
                    } catch (e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                        return
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }

        /** Null when no name is found (Android answers the IP itself). */
        internal fun reverseLookup(address: String): String? = try {
            val name = InetAddress.getByName(address).canonicalHostName
            name.takeIf { it != address }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Drives [ConnectionHealth] for the card on screen: feeds it Home's link, runs the checks, and
 * applies each status change at its moment on the trace. View-local and short-lived; only the last
 * averaged round trip per server and the machine names found outlive Settings. Its readable values
 * are Compose state. Main thread only.
 */
class ConnectionMonitor(server: OpenCodeServer, link: ConnectionHealth.Link) {
    private val probe = dependency(ConnectionProbeClient)
    private val clock = dependency(Clock)

    var health: ConnectionHealth
        private set

    /** What the card shows now (Compose state; mirrors `health.display`). */
    var display: ConnectionHealth.Display by mutableStateOf(ConnectionHealth.Display(ConnectionHealth.Status.connected))
        private set

    /** The server's version, from its last answer. */
    var version: String? by mutableStateOf(null)
        private set

    /** The machine's name, from a reverse lookup of its IP. */
    var resolvedName: String? by mutableStateOf(null)
        private set

    var server: OpenCodeServer by mutableStateOf(server)
        private set

    /**
     * The server a switch landed on, checked from its arrival; the card names it on its first
     * beat, when the status leaves Switching.
     */
    private var arrivedServer: OpenCodeServer? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wake: Job? = null
    private val checks = HashMap<Int, Job>()
    /** Views running checks: the card, and the server page pushed from it. */
    private var runners = 0
    private val isRunning: Boolean get() = runners > 0

    init {
        val now = now()
        health = ConnectionHealth(link, now, lastMilliseconds[server.persistenceID])
        resolvedName = savedName(server)
        publish()
    }

    /** Seconds on the monotonic clock the trace is drawn against. */
    fun now(): Double = clock.now().toDouble(DurationUnit.SECONDS)

    val name: String get() = ServerCardName.name(server, resolvedName)

    val route: ai.opencode.server.ConnectionRoute get() = ai.opencode.server.ConnectionRoute.of(server.url)

    private fun publish() {
        if (display != health.display) display = health.display
    }

    /** Home's connection or server changed. */
    fun update(server: OpenCodeServer, link: ConnectionHealth.Link) {
        val now = now()
        val current = arrivedServer ?: this.server
        if (server.persistenceID != current.persistenceID) {
            cancelChecks()
            if (health.display.status == ConnectionHealth.Status.switching) {
                // A switch landed: the same trace carries on with the new server's
                // checks, and the card names it on their first beat.
                arrivedServer = server
                health.setLink(link, now)
            } else {
                arrivedServer = null
                this.server = server
                resolvedName = savedName(server)
                health = ConnectionHealth(link, now, lastMilliseconds[server.persistenceID])
                if (isRunning) resolveName()
            }
        } else {
            if (arrivedServer != null) arrivedServer = server else this.server = server
            // The old server's checks say nothing about the one being switched to.
            if (link is ConnectionHealth.Link.Switching) cancelChecks()
            health.setLink(link, now)
        }
        publish()
        tick()
    }

    /** Runs checks until cancelled (the card left the screen or the app went inactive). */
    suspend fun run() {
        runners += 1
        if (runners == 1) {
            resolveName()
            tick()
        }
        try {
            awaitCancellation()
        } finally {
            runners -= 1
            if (runners == 0) {
                wake?.cancel()
                wake = null
                cancelChecks()
            }
        }
    }

    /** Retry from the card while Offline. */
    fun retry(onRetry: () -> Unit) {
        onRetry()
    }

    private fun cancelChecks() {
        checks.values.forEach { it.cancel() }
        checks.clear()
    }

    private fun tick() {
        if (!isRunning) return
        val now = now()
        health.advance(now)
        val arrived = arrivedServer
        if (arrived != null && health.display.status != ConnectionHealth.Status.switching) {
            arrivedServer = null
            server = arrived
            resolvedName = savedName(arrived)
            resolveName()
        }
        publish()
        val due = health.nextCheck
        if (due != null && due <= now + 0.005) startCheck(now)
        schedule(now)
    }

    private fun schedule(now: Double) {
        wake?.cancel()
        val next = health.nextWake
        if (next == null) {
            wake = null
            return
        }
        val delaySeconds = max(0.01, next - now)
        wake = scope.launch {
            delay((delaySeconds * 1000).toLong().coerceAtLeast(1))
            tick()
        }
    }

    private fun startCheck(now: Double) {
        val id = health.beginCheck(now)
        publish()
        val server = arrivedServer ?: server
        // One request at a time: while the last is still out (up to its 4 s timeout), this beat
        // sends nothing and drops at its deadline. Stacked requests into a dead route can stall a
        // VPN tunnel.
        if (checks.isNotEmpty()) return
        val probe = probe
        checks[id] = scope.launch {
            try {
                val answer = probe.check(server)
                ensureActive()
                answered(id, answer, server.persistenceID)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ensureActive()
                failed(id)
            }
        }
    }

    private fun answered(id: Int, answer: ConnectionProbeClient.Answer, persistenceID: String) {
        checks.remove(id)
        health.answer(id, answer.milliseconds, now())
        answer.version?.let { version = it }
        lastMilliseconds[persistenceID] = health.averageMilliseconds ?: answer.milliseconds
        tick()
    }

    private fun failed(id: Int) {
        checks.remove(id)
        health.fail(id, now())
        tick()
    }

    // Machine name

    private fun resolveName() {
        val address = ServerCardName.lookupAddress(server) ?: return
        val probe = probe
        val persistenceID = server.persistenceID
        scope.launch {
            val answer = probe.reverseName(address) ?: return@launch
            val name = ServerCardName.machineName(answer) ?: return@launch
            if (server.persistenceID != persistenceID) return@launch
            val names = shared(namesKey)
            if (names.value[address] != name) names.update { it + (address to name) }
            resolvedName = name
        }
    }

    companion object {
        /** The last averaged round trip per server, so reopening Settings shows a number at once. */
        private val lastMilliseconds = ConcurrentHashMap<String, Int>()

        /** Machine names found, by IP. */
        private val namesKey = SharedKey.appStorage<Map<String, String>>("connection-card-machine-names", emptyMap())

        private fun savedName(server: OpenCodeServer): String? =
            ServerCardName.lookupAddress(server)?.let { shared(namesKey).value[it] }?.let(ServerCardName::machineName)

        /**
         * The card's link from Home's: a switch in progress wins, and a refused password is
         * offline until the server is edited.
         */
        fun link(
            status: ConnectionStatus,
            switchingTo: OpenCodeServer? = null,
            credentialsRejected: Boolean = false,
        ): ConnectionHealth.Link {
            if (switchingTo != null) return ConnectionHealth.Link.Switching(displayName(switchingTo))
            if (credentialsRejected) return ConnectionHealth.Link.Rejected
            return when (status) {
                ConnectionStatus.connected -> ConnectionHealth.Link.Connected
                ConnectionStatus.reconnecting -> ConnectionHealth.Link.Reconnecting
                ConnectionStatus.disconnected -> ConnectionHealth.Link.Offline
            }
        }

        private fun displayName(server: OpenCodeServer): String = ServerCardName.name(server, savedName(server))
    }
}
