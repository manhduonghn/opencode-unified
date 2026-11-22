package ai.opencode.server

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/**
 * IDs in the server's own shape (packages/schema/src/identifier.ts `create`): a prefix
 * (`msg_`, `ses_`), 12 lowercase hex characters of milliseconds × 0x1000 plus a counter
 * that restarts each millisecond, then 14 random base62 characters. Ascending IDs sort as
 * text in creation order; descending ones (sessions) invert the time so the newest sorts first.
 */
object ServerID {
    const val RANDOM_LENGTH = 14
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    /** The 12 hex characters for one tick. A counter past 0xfff carries into the time; the low 48 bits stay. */
    fun timePart(milliseconds: Long, counter: Long, descending: Boolean): String {
        val current = milliseconds * 0x1000 + counter
        val value = if (descending) current.inv() else current
        return (0 until 6).joinToString("") { "%02x".format((value ushr (40 - 8 * it)) and 0xff) }
    }

    /** One base62 character per byte, `byte % 62` as the server does. */
    fun randomPart(bytes: ByteArray): String = buildString {
        for (byte in bytes) append(ALPHABET[(byte.toInt() and 0xff) % 62])
    }

    fun milliseconds(epochMillis: Long): Long = maxOf(0L, epochMillis)

    /**
     * A client-made session ID (`ses_`, descending time, and the last 14 letters and digits of
     * [entropy]), so sessions created on this phone sort among the server's own and a retried
     * create finds the one it already made.
     */
    fun newSessionID(at: Instant = Instant.now(), entropy: String = UUID.randomUUID().toString().uppercase()): String {
        val time = timePart(milliseconds(at.toEpochMilli()), counter = 1, descending = true)
        val random = entropy.split("_").lastOrNull { it.isNotEmpty() } ?: entropy
        val tail = random.filter { it.isLetterOrDigit() }.takeLast(RANDOM_LENGTH)
        return "ses_" + time + tail.padEnd(RANDOM_LENGTH, '0')
    }

    /**
     * Mints IDs from a clock and random bytes, holding the server's last-millisecond
     * counter so IDs made in the same millisecond still ascend.
     */
    class Generator(private val now: () -> Long, private val randomBytes: (Int) -> ByteArray) {
        private val lock = Any()
        private var tickMilliseconds = 0L
        private var tickCounter = 0L

        fun next(prefix: String, descending: Boolean = false): String {
            val milliseconds = now()
            val counter = synchronized(lock) {
                if (tickMilliseconds != milliseconds) {
                    tickMilliseconds = milliseconds
                    tickCounter = 0
                }
                tickCounter += 1
                tickCounter
            }
            return prefix + timePart(milliseconds, counter, descending) + randomPart(randomBytes(RANDOM_LENGTH))
        }

        companion object {
            private val random = SecureRandom()
            val live = Generator(
                now = { milliseconds(System.currentTimeMillis()) },
                randomBytes = { count -> ByteArray(count).also(random::nextBytes) },
            )
        }
    }
}

/**
 * Mints server-schema IDs ([ServerID]) for what the app creates: message IDs (ascending)
 * and session IDs (descending). Local UI identities stay on `UuidClient`.
 */
class ServerIDClient(
    val ascending: (prefix: String) -> String,
    val descending: (prefix: String) -> String,
) {
    constructor(generator: ServerID.Generator) : this(
        ascending = { generator.next(it) },
        descending = { generator.next(it, descending = true) },
    )

    /** A new message ID, `msg_` and an ascending ID. */
    fun messageID(): String = ascending("msg_")

    /** A new session ID, `ses_` and a descending ID. */
    fun sessionID(): String = descending("ses_")

    companion object Key : DependencyKey<ServerIDClient> {
        override val liveValue: ServerIDClient = ServerIDClient(ServerID.Generator.live)

        /** A fresh incrementing generator per test container. */
        override val testValue: ServerIDClient get() = incrementing()

        /** A new message ID from the current dependency, for code outside a reducer. */
        fun currentMessageID(): String = dependency(ServerIDClient).messageID()

        /** IDs at millisecond 0 with zero randomness, counting up from 1: `msg_00000000000100000000000000`, … */
        fun incrementing(): ServerIDClient = ServerIDClient(ServerID.Generator(now = { 0 }, randomBytes = { ByteArray(it) }))
    }
}
