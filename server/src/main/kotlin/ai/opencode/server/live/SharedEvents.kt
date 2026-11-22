package ai.opencode.server.live

import ai.opencode.core.log.AppLog
import ai.opencode.core.strings.localized
import ai.opencode.server.EventBatcher
import ai.opencode.server.EventStreamOverflow
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.R
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Whether several windows (multi-instance, desktop windowing) share one upstream event stream and
 * coalesce Home reads. Off by default, as on iPhone: each subscription is its own stream. Set once
 * at startup.
 */
object MultipleWindows {
    @Volatile var isSupported: Boolean = false
}

/** Yields [event], or ends [this] with [EventStreamOverflow] when its buffer is full. */
internal fun ProducerScope<OpenCodeServerEvent>.yieldRecoverably(event: OpenCodeServerEvent): Boolean {
    val result = trySend(event)
    if (result.isSuccess) return true
    if (result.isClosed) return false
    AppLog.stream.notice { "Event buffer overflow: dropped=1" }
    close(EventStreamOverflow())
    return false
}

/**
 * One upstream stream per server shared by every window. A window that joins a live stream
 * hears `.connected` at once, so its Home reads a fresh snapshot as after a reconnect. When
 * the upstream ends every subscriber ends with the same error.
 */
internal class SharedEventStreams(private val source: (OpenCodeServer) -> Flow<OpenCodeServerEvent>) {
    private class Channel(val server: OpenCodeServer) {
        val subscribers = LinkedHashMap<UUID, ProducerScope<OpenCodeServerEvent>>()
        var upstream: Job? = null
        var isConnected = false
    }

    private val lock = Any()
    private val channels = mutableListOf<Channel>()

    /** Open upstream streams, for tests and diagnostics. */
    val upstreamCount: Int get() = synchronized(lock) { channels.count { it.upstream != null } }

    fun subscribe(server: OpenCodeServer): Flow<OpenCodeServerEvent> = callbackFlow {
        val id = UUID.randomUUID()
        // Inherits the first subscriber's context (dependencies, stream operation ID).
        val inherited = coroutineContext.minusKey(Job)
        synchronized(lock) {
            val channel = channels.firstOrNull { it.server == server } ?: Channel(server).also(channels::add)
            channel.subscribers[id] = this
            if (channel.isConnected) yieldRecoverably(OpenCodeServerEvent.Connected)
            if (channel.upstream == null) {
                channel.upstream = CoroutineScope(inherited + SupervisorJob()).launch { pump(channel, source(server)) }
            }
        }
        awaitClose { leave(server, id) }
    }.buffer(EventBatcher.BUFFER_CAPACITY)

    private suspend fun pump(channel: Channel, upstream: Flow<OpenCodeServerEvent>) {
        var failure: Throwable? = null
        try {
            upstream.collect { event ->
                val subscribers = synchronized(lock) {
                    if (event == OpenCodeServerEvent.Connected) channel.isConnected = true
                    channel.subscribers.values.toList()
                }
                for (subscriber in subscribers) subscriber.yieldRecoverably(event)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failure = error
        }
        val subscribers = synchronized(lock) {
            channels.remove(channel)
            val ended = channel.subscribers.values.toList()
            channel.subscribers.clear()
            ended
        }
        val error = failure ?: OpenCodeClientError(localized(R.string.server_error_event_stream_ended))
        for (subscriber in subscribers) subscriber.close(error)
    }

    private fun leave(server: OpenCodeServer, id: UUID) {
        val cancelled = synchronized(lock) {
            val channel = channels.firstOrNull { it.server == server } ?: return
            if (channel.subscribers.remove(id) == null || channel.subscribers.isNotEmpty()) return
            channels.remove(channel)
            channel.upstream
        }
        cancelled?.cancel()
    }
}

/**
 * A read that asks for what another window asked for within [joinWindow] joins that request
 * instead of sending its own, so the list is read once however many windows are open.
 */
internal class SharedReads<V>(private val joinWindow: Duration = 150.milliseconds) {
    private class Flight<V>(val started: Duration, val deferred: Deferred<Result<V>>)

    private val lock = Any()
    private val flights = HashMap<String, Flight<V>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var requests = 0

    /** Requests sent, for tests and diagnostics. */
    val sent: Int get() = synchronized(lock) { requests }

    suspend fun run(key: String, read: suspend () -> V): V {
        val now = System.nanoTime().nanoseconds
        val inherited = currentCoroutineContext().minusKey(Job)
        val flight = synchronized(lock) {
            flights[key]?.takeIf { now - it.started < joinWindow } ?: Flight(
                now,
                // Inherits the first caller's context (dependencies, retry policy), as its own request would.
                scope.async(inherited) { try { Result.success(read()) } catch (error: OpenCodeClientError) { Result.failure(error) } },
            ).also {
                flights[key] = it
                requests += 1
            }
        }
        val result = flight.deferred.await()
        synchronized(lock) { if (flights[key] === flight) flights.remove(key) }
        return result.getOrThrow()
    }
}

private val sharedEventStreams = SharedEventStreams(::subscribeEvents)
private val homeSnapshotReads = SharedReads<OpenCodeHomeSnapshot>()
private val homeAttentionReads = SharedReads<Set<String>>()

/** With windows sharing, one stream per address; otherwise each subscription is its own stream. */
internal fun subscribeEventsSharingWindows(server: OpenCodeServer): Flow<OpenCodeServerEvent> =
    if (MultipleWindows.isSupported) sharedEventStreams.subscribe(server) else subscribeEvents(server)

internal suspend fun requestHomeSnapshotSharingWindows(server: OpenCodeServer): OpenCodeHomeSnapshot {
    if (!MultipleWindows.isSupported) return requestHomeSnapshot(server)
    return homeSnapshotReads.run(server.persistenceID) { requestHomeSnapshot(server) }
}

internal suspend fun requestHomeAttentionSharingWindows(
    server: OpenCodeServer,
    roots: List<OpenCodeSession>,
    directories: List<String>,
): Set<String> {
    if (!MultipleWindows.isSupported) return requestHomeAttention(server, roots, directories)
    val key = "${server.persistenceID}|${directories.joinToString("\n")}|${roots.map { it.id }.hashCode()}"
    return homeAttentionReads.run(key) { requestHomeAttention(server, roots, directories) }
}
