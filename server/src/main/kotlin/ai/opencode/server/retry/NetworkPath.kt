package ai.opencode.server.retry

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.PowerManager
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.diagnostics.DiagnosticsRecorder
import ai.opencode.core.log.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phone's network path, from one shared default-network callback. A hint for *when* to retry,
 * never a verdict that a request will fail.
 */
class NetworkPathMonitor private constructor(context: Context) {
    data class Snapshot(
        val status: Status,
        /**
         * Every interface the path can use, in order of preference. The first carries traffic by
         * default; a VPN adds an `other` interface (its tunnel) beside it.
         */
        val interfaces: List<Interface>,
        /** Metered: cellular or a hotspot (`NWPath.isExpensive`). */
        val isExpensive: Boolean = false,
        /** Data Saver restricts this app (`NWPath.isConstrained`, Low Data Mode). */
        val isConstrained: Boolean = false,
    ) {
        enum class Status(val rawValue: String) {
            Satisfied("satisfied"),
            /**
             * No link yet, but sending would bring one up. Android's callback reports no such
             * state; kept so the two codebases match.
             */
            RequiresConnection("requiresConnection"),
            Unsatisfied("unsatisfied"),
        }

        /**
         * One interface the path can use: its kind ("wifi", "cellular", "wiredEthernet", "other"
         * for a tunnel) and system name ("wlan0", "tun0").
         */
        data class Interface(val type: String, val name: String)

        /** [primaryInterface] reads like "wifi wlan0". */
        constructor(
            isSatisfied: Boolean,
            primaryInterface: String?,
            isExpensive: Boolean = false,
            isConstrained: Boolean = false,
        ) : this(
            status = if (isSatisfied) Status.Satisfied else Status.Unsatisfied,
            interfaces = interfacesOf(primaryInterface),
            isExpensive = isExpensive,
            isConstrained = isConstrained,
        )

        val isSatisfied: Boolean get() = status == Status.Satisfied

        /** Only a path with nowhere to send is offline; [Status.RequiresConnection] is tried. */
        val isOffline: Boolean get() = status == Status.Unsatisfied

        /** The interface traffic would leave on ("wifi wlan0", "cellular rmnet0"). */
        val primaryInterface: String? get() = interfaces.firstOrNull()?.let { "${it.type} ${it.name}" }

        /**
         * What decides where a connection goes: the interface traffic leaves on by default, and
         * every tunnel (`other`) interface. A tunnel coming up, going down or restarting on a new
         * interface strands connections made through the old one.
         */
        val routeSignature: List<Interface>
            get() {
                val first = interfaces.firstOrNull() ?: return emptyList()
                val tunnels = interfaces.drop(1).filter { it.type == "other" }.sortedBy { it.name }
                return listOf(first) + tunnels
            }

        /** A change worth reconnecting for: the path came back, or traffic can now leave another way. */
        fun isReroute(previous: Snapshot?): Boolean {
            if (previous == null || isOffline) return false
            if (previous.isOffline) return true
            if (status == Status.Satisfied && previous.status != Status.Satisfied) return true
            return previous.routeSignature != routeSignature
        }

        /** For the log and diagnostics: kinds only, never names or addresses. */
        val logSummary: String
            get() {
                val kinds = routeSignature.map { it.type }
                val listed = if (kinds.isEmpty()) "none" else kinds.joinToString(",")
                return "status=${status.rawValue} interfaces=$listed tunnels=${routeSignature.drop(1).size} " +
                    "expensive=$isExpensive constrained=$isConstrained"
            }

        private companion object {
            fun interfacesOf(primaryInterface: String?): List<Interface> {
                val parts = primaryInterface?.split(" ", limit = 2) ?: return emptyList()
                if (parts.size != 2) return emptyList()
                return listOf(Interface(parts[0], parts[1]))
            }
        }
    }

    private val lock = Any()
    private var state: Snapshot? = null
    private val observers = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private var capabilities: NetworkCapabilities? = null
    private var link: LinkProperties? = null

    init {
        val manager = connectivity
        if (manager != null) {
            if (manager.activeNetwork == null) receive(Snapshot(Snapshot.Status.Unsatisfied, emptyList()))
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    synchronized(lock) { capabilities = networkCapabilities }
                    publish()
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    synchronized(lock) { link = linkProperties }
                    publish()
                }

                override fun onLost(network: Network) {
                    synchronized(lock) {
                        capabilities = null
                        link = null
                    }
                    receive(Snapshot(Snapshot.Status.Unsatisfied, emptyList()))
                }
            })
        }
    }

    private fun publish() {
        val (caps, properties) = synchronized(lock) { capabilities to link }
        caps ?: return
        val satisfied = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val physical = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wiredEthernet"
            else -> "other"
        }
        val name = properties?.interfaceName ?: ""
        // A VPN is the default network itself, carrying its underlying transport: the physical link
        // first (its name isn't reported), then the tunnel.
        val interfaces = when {
            !satisfied -> emptyList()
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ->
                listOf(Snapshot.Interface(physical, ""), Snapshot.Interface("other", name))
            else -> listOf(Snapshot.Interface(physical, name))
        }
        val constrained = connectivity?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        receive(
            Snapshot(
                status = if (satisfied) Snapshot.Status.Satisfied else Snapshot.Status.Unsatisfied,
                interfaces = interfaces,
                isExpensive = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                isConstrained = constrained,
            ),
        )
    }

    /** Null until the first path arrives. */
    val current: Snapshot? get() = synchronized(lock) { state }

    val isConstrained: Boolean get() = current?.isConstrained == true
    val isExpensive: Boolean get() = current?.isExpensive == true

    /** Non-essential large prefetches defer on Data Saver, or Battery Saver on a metered link. */
    val shouldDeferLargePrefetch: Boolean
        get() = shouldDeferLargePrefetch(current, power?.isPowerSaveMode == true)

    /** Every path from now on, starting with the current one when known. */
    fun updates(): Flow<Snapshot> = callbackFlow {
        val observer: (Snapshot) -> Unit = { trySend(it) }
        val current = synchronized(lock) {
            observers.add(observer)
            state
        }
        current?.let { trySend(it) }
        awaitClose { observers.remove(observer) }
    }.buffer(4, BufferOverflow.DROP_OLDEST)

    /** Returns once sending could work: a satisfied path, or one that a connection would bring up. */
    suspend fun waitUntilUsable() {
        updates().first { !it.isOffline }
    }

    private fun receive(snapshot: Snapshot) {
        val previous = synchronized(lock) {
            if (state == snapshot) return
            val previous = state
            state = snapshot
            previous
        }
        if (previous?.logSummary != snapshot.logSummary) {
            AppLog.network.info { "Path ${snapshot.logSummary}" }
            DiagnosticsRecorder.shared.recordLink("path_change", snapshot.logSummary)
        }
        for (observer in observers) observer(snapshot)
    }

    companion object {
        /** A burst of path callbacks (Wi-Fi, then a tunnel, then DNS settling) is one reroute. */
        val rerouteDebounce: Duration = 250.milliseconds

        /** The process-wide monitor; null where no application context is installed (JVM tests). */
        val shared: NetworkPathMonitor? by lazy {
            runCatching { NetworkPathMonitor(dependency(AppContext).applicationContext) }.getOrNull()
        }

        fun shouldDeferLargePrefetch(snapshot: Snapshot?, isLowPowerModeEnabled: Boolean = false): Boolean {
            snapshot ?: return false
            if (snapshot.isConstrained) return true
            return snapshot.isExpensive && isLowPowerModeEnabled
        }

        /**
         * One reroute per settled change in [updates]: each reroute restarts the [debounce] wait,
         * and a path that drops meanwhile cancels it.
         */
        fun reroutes(
            updates: Flow<Snapshot>,
            debounce: Duration = rerouteDebounce,
            clock: Clock = Clock.liveValue,
        ): Flow<Unit> = channelFlow {
            var pending: Job? = null
            var previous: Snapshot? = null
            updates.collect { snapshot ->
                if (snapshot.isReroute(previous)) {
                    pending?.cancel()
                    pending = launch {
                        clock.sleep(debounce)
                        send(Unit)
                    }
                } else if (snapshot.isOffline) {
                    pending?.cancel()
                    pending = null
                }
                previous = snapshot
            }
            pending?.cancel()
        }
    }
}

enum class AppPhase { Active, Background }

/**
 * The app entering the background or becoming active again, from activity start/stop counts. Call
 * [install] from `Application.onCreate` so the first foregrounding is counted; otherwise it
 * installs itself on first use.
 */
object AppPhases {
    private val observers = CopyOnWriteArrayList<(AppPhase) -> Unit>()
    @Volatile private var installed = false
    private var started = 0

    fun install(application: Application) {
        synchronized(this) {
            if (installed) return
            installed = true
        }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                val becameActive = synchronized(this@AppPhases) { started++ == 0 }
                if (becameActive) observers.forEach { it(AppPhase.Active) }
            }

            override fun onActivityStopped(activity: Activity) {
                val wentBackground = synchronized(this@AppPhases) {
                    started = maxOf(0, started - 1)
                    started == 0
                }
                if (wentBackground) observers.forEach { it(AppPhase.Background) }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * Calls [observer] synchronously with every phase from now on, for as long as the process
     * lives.
     */
    fun observe(observer: (AppPhase) -> Unit) {
        installIfNeeded()
        observers.add(observer)
    }

    private fun installIfNeeded() {
        if (!installed) {
            runCatching { dependency(AppContext).applicationContext as? Application }.getOrNull()?.let(::install)
        }
    }

    fun phases(): Flow<AppPhase> = callbackFlow {
        installIfNeeded()
        val observer: (AppPhase) -> Unit = { trySend(it) }
        observers.add(observer)
        awaitClose { observers.remove(observer) }
    }
}

/** The network path for reducers: when to reconnect, and whether to wait. */
data class NetworkPathClient(
    /**
     * Yields each time the path comes back or moves to another interface (Wi-Fi and cellular, a
     * VPN re-pathing): a connection opened before may be stranded on the old route.
     */
    val reroutes: () -> Flow<Unit>,
    val isOffline: () -> Boolean,
    /** Returns once a network path is available (or at once if one is). */
    val available: suspend () -> Unit,
    /** Returns the next time the server with this `persistenceID` answers a request, or when cancelled. */
    val serverAnswered: suspend (server: String) -> Unit,
    /** Yields each time the app enters the background or becomes active again. */
    val appPhases: () -> Flow<AppPhase> = { emptyFlow() },
) {
    /** A retry environment over this path, with live jitter. */
    val retryEnvironment: RetryEnvironment
        get() = RetryEnvironment(random = ::liveRandom, isOffline = isOffline, networkAvailable = available)

    /**
     * Returns at the next reroute, or once a request reaches the server (`persistenceID`) again,
     * whichever comes first: whatever kept a connection away may be gone, so a loop waiting out a
     * backoff tries now.
     */
    suspend fun nextReconnectCue(server: String) {
        coroutineScope {
            val reroute = async { nextReroute() }
            val answer = async { serverAnswered(server) }
            select {
                reroute.onAwait {}
                answer.onAwait {}
            }
            coroutineContext.cancelChildren()
        }
    }

    /** Returns at the next reroute; never, if the path reports none. */
    private suspend fun nextReroute() {
        val rerouted = reroutes().firstOrNull()
        if (rerouted != null) return
        awaitCancellation()
    }

    companion object Key : DependencyKey<NetworkPathClient> {
        override val liveValue: NetworkPathClient by lazy {
            NetworkPathClient(
                reroutes = {
                    val monitor = NetworkPathMonitor.shared
                    if (monitor == null) emptyFlow() else NetworkPathMonitor.reroutes(monitor.updates())
                },
                isOffline = { NetworkPathMonitor.shared?.current?.isOffline == true },
                available = { NetworkPathMonitor.shared?.waitUntilUsable() },
                serverAnswered = { ServerContact.shared.next(it) },
                appPhases = { AppPhases.phases() },
            )
        }

        /** Always online; never reroutes, and never hears from a server. */
        override val testValue: NetworkPathClient = NetworkPathClient(
            reroutes = { emptyFlow() },
            isOffline = { false },
            available = {},
            serverAnswered = { awaitCancellation() },
        )
    }
}
