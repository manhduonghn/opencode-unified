package ai.opencode.server

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

// Client-made IDs share the server's time-sortable shape
// (packages/schema/src/identifier.ts), so desktop's text order puts an
// Android message where it was sent.

class ServerIDTests {
    @get:Rule val isolation = TestIsolationRule()

    private class Clock(milliseconds: Long) {
        val milliseconds = AtomicLong(milliseconds)
    }

    private fun generator(clock: Clock) = ServerID.Generator(now = { clock.milliseconds.get() }, randomBytes = { Random.nextBytes(it) })

    @Test
    fun matchesTheServersTimeVectors() {
        val clock = Clock(T)
        val generator = generator(clock)
        assertEquals("msg_3185c5000001", generator.next("msg_").take(16))
        assertEquals("msg_3185c5000002", generator.next("msg_").take(16))
        clock.milliseconds.set(T + 1)
        assertEquals("ses_ce7a3affeffe", generator.next("ses_", descending = true).take(16))
    }

    @Test
    fun overflowingTheCounterCarriesIntoTheTimeLikeTheServer() {
        val generator = generator(Clock(T + 2))
        val ids = (0 until 4096).map { generator.next("msg_") }
        assertEquals("msg_3185c5002ffe", ids[4093].take(16))
        assertEquals("msg_3185c5002fff", ids[4094].take(16))
        assertEquals("msg_3185c5003000", ids[4095].take(16))
        assertEquals(ids.sorted(), ids)
    }

    @Test
    fun idsMadeInSequenceSortAsText() {
        val clock = Clock(T)
        val generator = generator(clock)
        val ids = mutableListOf<String>()
        for (millisecond in 0 until 5) {
            clock.milliseconds.set(T + millisecond)
            repeat(500) { ids += generator.next("msg_") }
        }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals("Same-millisecond and cross-millisecond IDs ascend as plain strings", ids.sorted(), ids)

        clock.milliseconds.set(T + 10)
        val sessions = (0 until 3).map { generator.next("ses_", descending = true) }
        clock.milliseconds.set(T + 11)
        val newer = generator.next("ses_", descending = true)
        assertEquals("Sessions descend", sessions.sortedDescending(), sessions)
        assertTrue("A newer session sorts first", newer < sessions.last())
    }

    @Test
    fun liveIDsHaveTheServersShapeAndSortAfterOlderServerIDs() {
        val client = ServerIDClient.liveValue
        val messages = (0 until 200).map { client.messageID() }
        val session = client.sessionID()
        for (id in messages) assertTrue(id, matches(id, "msg_") && id.length == 30)
        assertTrue(matches(session, "ses_") && session.length == 30)
        for (id in SERVER_MESSAGE_IDS) assertTrue(matches(id, "msg_"))
        for (id in SERVER_SESSION_IDS) assertTrue(matches(id, "ses_"))
        assertEquals(messages.sorted(), messages)
        for (id in SERVER_MESSAGE_IDS) assertTrue("Made now, after every older server message", id < messages[0])
        for (id in SERVER_SESSION_IDS) assertTrue("Made now, so it sorts before older sessions", session < id)
    }

    @Test
    fun randomCharactersFollowTheServersAlphabet() {
        assertEquals("09AZaz07", ServerID.randomPart(byteArrayOf(0, 9, 10, 35, 36, 61, 62, 255.toByte())))
    }

    @Test
    fun incrementingIsDeterministic() {
        val client = ServerIDClient.incrementing()
        assertEquals("msg_00000000000100000000000000", client.messageID())
        assertEquals("msg_00000000000200000000000000", client.messageID())
        assertEquals("ses_fffffffffffc00000000000000", client.sessionID())
    }

    private companion object {
        /** 1_800_000_000_000 ms; vectors from identifier.ts `create(_, timestamp)`. */
        const val T = 1_800_000_000_000L

        /** Real IDs minted by a V2 server. */
        val SERVER_MESSAGE_IDS = listOf(
            "msg_15cb96740001QQuLgSGsIe7ZHF",
            "msg_15cb9756c0013ejzKF7iwmJ5Uu",
            "msg_15cb958ee001VUzU2UovIT3oNL",
            "msg_11a3e095b001b2ncKsYKEYDBv2",
        )
        val SERVER_SESSION_IDS = listOf("ses_ea349f5a5ffetR02vVNOiuDVWu", "ses_ecbf5e0b9ffeIrOHT5OMg2FT62")

        fun matches(id: String, prefix: String): Boolean =
            Regex("(msg|ses)_[0-9a-f]{12}[0-9A-Za-z]{14}").matches(id) && id.startsWith(prefix)
    }
}
