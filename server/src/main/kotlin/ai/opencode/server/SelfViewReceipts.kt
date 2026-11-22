package ai.opencode.server

import ai.opencode.core.dependencies.DependencyKey
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Read receipts in flight or just sent, so the stream can drop their `session.viewed` echo. Echoes
 * carry no request ID, so a receipt matches by server, session and idle, and goes at once when it
 * can no longer echo.
 */
class SelfViewReceipts(private val now: () -> Duration = { System.nanoTime().nanoseconds }) {
    private data class Receipt(
        val id: UUID,
        val serverID: String,
        val sessionID: String,
        val idle: Long,
        val deadline: Duration,
        val answered: Boolean = false,
    )

    private val lock = Any()
    private val receipts = mutableListOf<Receipt>()

    /** Receipts still waiting for their echo, for tests and diagnostics. */
    val pendingCount: Int
        get() {
            val now = now()
            return synchronized(lock) { receipts.count { it.deadline > now } }
        }

    /** A receipt about to be sent; its echo may beat the POST's response. */
    fun begin(serverID: String, sessionID: String, idle: Long): UUID {
        val now = now()
        val id = UUID.randomUUID()
        synchronized(lock) {
            receipts.removeAll { it.deadline <= now }
            receipts += Receipt(id, serverID, sessionID, idle, deadline = now + 10.seconds)
        }
        return id
    }

    /** The server answered: its echo, published before the answer, gets a moment more to arrive. */
    fun answered(id: UUID) {
        val now = now()
        val grace = now + 500.milliseconds
        synchronized(lock) {
            receipts.removeAll { it.deadline <= now }
            val index = receipts.indexOfFirst { it.id == id }
            if (index < 0) return
            val receipt = receipts[index]
            receipts[index] = receipt.copy(answered = true, deadline = minOf(receipt.deadline, grace))
        }
    }

    /** The POST failed: nothing will echo. */
    fun failed(id: UUID) {
        synchronized(lock) { receipts.removeAll { it.id == id } }
    }

    /**
     * A new event stream to [serverID] is open: answered receipts' echoes went out before it, on
     * the stream that dropped. Receipts still in flight may echo on this one.
     */
    fun streamConnected(serverID: String) {
        val now = now()
        synchronized(lock) { receipts.removeAll { it.deadline <= now || it.serverID == serverID && it.answered } }
    }

    /** Whether a `session.viewed` is this client's own echo; it's consumed. */
    fun consumeEcho(serverID: String, sessionID: String, idle: Long): Boolean {
        val now = now()
        return synchronized(lock) {
            receipts.removeAll { it.deadline <= now }
            val index = receipts.indexOfFirst { it.serverID == serverID && it.sessionID == sessionID && it.idle == idle }
            if (index < 0) return@synchronized false
            receipts.removeAt(index)
            true
        }
    }

    companion object Key : DependencyKey<SelfViewReceipts> {
        private val shared = SelfViewReceipts()
        override val liveValue: SelfViewReceipts get() = shared
        override val testValue: SelfViewReceipts get() = SelfViewReceipts()
    }
}
