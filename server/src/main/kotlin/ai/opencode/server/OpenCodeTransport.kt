package ai.opencode.server

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.server.DiagnosticsInstrumentation.recordingDiagnostics
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The OkHttp client pools behind the live client. Read a pool where the call is made, never keep
 * one: [reset] swaps in fresh ones.
 */
class OpenCodeTransport private constructor(
    private val current: () -> Pools,
    /**
     * Swaps every pool for a fresh one, so the next call opens a new connection instead of
     * queueing behind sockets a sleep or a route change left wedged. Calls already running
     * finish on the old pool.
     */
    val reset: () -> Unit,
) {
    class Pools(
        val request: OkHttpClient,
        val measuredRequest: OkHttpClient,
        val longPoll: OkHttpClient,
        val eventStream: OkHttpClient,
        /**
         * Immutable `media:<hash>` blobs. Defaults to [request] for test transports that stub one
         * client.
         */
        val media: OkHttpClient = request,
    )

    /** Fixed pools that never reset. */
    constructor(
        request: OkHttpClient,
        measuredRequest: OkHttpClient,
        longPoll: OkHttpClient,
        eventStream: OkHttpClient,
        media: OkHttpClient = request,
    ) : this(Pools(request, measuredRequest, longPoll, eventStream, media))

    constructor(pools: Pools) : this(current = { pools }, reset = {})

    constructor(rotating: RotatingPools) : this(current = { rotating.current }, reset = { rotating.reset() })

    val request: OkHttpClient get() = current().request
    val measuredRequest: OkHttpClient get() = current().measuredRequest
    val longPoll: OkHttpClient get() = current().longPoll
    val eventStream: OkHttpClient get() = current().eventStream
    val media: OkHttpClient get() = current().media

    companion object Key : DependencyKey<OpenCodeTransport> {
        override val liveValue: OpenCodeTransport by lazy { OpenCodeTransport(RotatingPools { pools(TransportSecurity.interceptor) }) }

        /**
         * Fresh pools (their own dispatcher and connection pool), each request first passing
         * [guard].
         */
        internal fun pools(guard: okhttp3.Interceptor?): Pools {
            // OkHttp caps calls per host at 5 by default; the event stream and a `/wait` long poll
            // hold two, and family fan-out needs more.
            val dispatcher = Dispatcher().apply { maxRequestsPerHost = maxRequests }
            val base = OkHttpClient.Builder().dispatcher(dispatcher).apply {
                // Before connecting, and again for every redirect hop.
                if (guard != null) addInterceptor(guard).addNetworkInterceptor(guard)
            }.build()
            // Requests feed the diagnostics timeline (inert while recording is off); the event
            // stream records its own connect/disconnect marks instead.
            val recorded = base.newBuilder().recordingDiagnostics().build()
            return Pools(
                request = recorded,
                measuredRequest = recorded.newBuilder().build(),
                longPoll = recorded.newBuilder().build(),
                // Server heartbeats reset the idle interval, not a total stream deadline.
                eventStream = base.newBuilder()
                    .connectTimeout(90, TimeUnit.SECONDS)
                    .readTimeout(90, TimeUnit.SECONDS)
                    .writeTimeout(90, TimeUnit.SECONDS)
                    .build(),
                // OkHttp keeps no response cache unless one is configured; URLSession's
                // `timeoutIntervalForRequest` is an idle interval, as OkHttp's per-phase timeouts.
                media = recorded.newBuilder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .writeTimeout(20, TimeUnit.SECONDS)
                    .build(),
            )
        }

        /**
         * The same pools behind [TestNetworkBlock]: a test reaches only the hosts its fixtures
         * register and loopback (`MockWebServer`); anything else fails. Fixture hosts
         * (`wire-<uuid>.invalid`) are plain HTTP, so the cleartext guard stays off.
         */
        override val testValue: OpenCodeTransport by lazy {
            fun OkHttpClient.blocked() = newBuilder().addInterceptor(TestNetworkBlock.interceptor).build()
            val live = pools(guard = null)
            OpenCodeTransport(
                request = live.request.blocked(),
                measuredRequest = live.measuredRequest.blocked(),
                longPoll = live.longPoll.blocked(),
                eventStream = live.eventStream.blocked(),
                media = live.media.blocked(),
            )
        }
    }
}

/**
 * The live pools, swapped on [reset]. A retired pool lets its calls finish: a cancelled POST is
 * never retried or reconciled.
 */
class RotatingPools(private val make: () -> OpenCodeTransport.Pools) {
    private val lock = Any()
    private var pools: OpenCodeTransport.Pools = make()

    val current: OpenCodeTransport.Pools get() = synchronized(lock) { pools }

    fun reset() {
        val fresh = make()
        val retired = synchronized(lock) {
            val old = pools
            pools = fresh
            old
        }
        retirement.launch {
            delay(retireDelay)
            // Closes the retired pools' idle sockets; calls in flight finish. Retired clients stay
            // usable, since a call's WebRTC signalling client keeps one for its lifetime.
            val clients = listOf(retired.request, retired.measuredRequest, retired.longPoll, retired.eventStream, retired.media)
            for (client in clients) client.connectionPool.evictAll()
        }
    }

    companion object {
        /** Long enough for a call made from a pool read just before the swap to start on it. */
        val retireDelay: Duration = 2.seconds

        private val retirement = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
