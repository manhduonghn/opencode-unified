package ai.opencode.server

import ai.opencode.core.dependencies.withDependenciesSuspending
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.testing.WireFixture
import ai.opencode.server.testing.WireFixture.Reply
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class NewMessagesReceiptTests {
    @get:Rule val isolation = TestIsolationRule()

    /** A server whose event stream sends a `session.viewed` for `ses_target` at idle 2000, then ends. */
    private fun fixture(): WireFixture = WireFixture(scheme = "http") { request ->
        if (request.path.endsWith("/view")) Reply.status(204)
        else Reply.Json(200, "data: {\"type\":\"session.viewed\",\"data\":{\"sessionID\":\"ses_target\",\"idle\":2000}}\n\n")
    }

    /** One connection's events, as the client delivers them. */
    private suspend fun streamedEvents(server: OpenCodeServer, receipts: SelfViewReceipts): List<OpenCodeServerEvent> =
        withDependenciesSuspending({ this[SelfViewReceipts] = receipts }) {
            val events = mutableListOf<OpenCodeServerEvent>()
            try {
                OpenCodeClient.liveValue.events(server).collect { events += it }
            } catch (_: OpenCodeClientError) {
                // The stream ending is its expected terminal error.
            }
            events
        }

    /** A receipt still in flight when the stream connects echoes on it, and that echo is dropped. */
    @Test
    fun aReceiptInFlightAcrossAConnectStillSwallowsItsEcho() = runTest {
        val receipts = SelfViewReceipts()
        val wire = fixture()
        try {
            receipts.begin(wire.server.persistenceID, "ses_target", 2_000)
            assertEquals(emptyList<OpenCodeServerEvent>(), streamedEvents(wire.server, receipts))
            assertEquals(0, receipts.pendingCount)
        } finally {
            wire.close()
        }
    }

    @Test
    fun receiptsMatchOnceExpireAfterTheirGraceAndStayPerServer() {
        var clock = Duration.ZERO
        val receipts = SelfViewReceipts { clock }
        val answered = receipts.begin("a", "s", 1)
        receipts.answered(answered)
        assertFalse(receipts.consumeEcho("b", "s", 1))
        assertFalse(receipts.consumeEcho("a", "s", 2))
        assertTrue(receipts.consumeEcho("a", "s", 1))
        assertFalse(receipts.consumeEcho("a", "s", 1))

        receipts.answered(receipts.begin("a", "s", 1))
        clock += 600.milliseconds
        assertFalse(receipts.consumeEcho("a", "s", 1))

        receipts.failed(receipts.begin("a", "s", 1))
        assertFalse(receipts.consumeEcho("a", "s", 1))

        receipts.begin("a", "inFlight", 1)
        receipts.answered(receipts.begin("a", "answered", 1))
        receipts.answered(receipts.begin("b", "answered", 1))
        receipts.streamConnected("a")
        assertEquals(2, receipts.pendingCount)
        assertFalse(receipts.consumeEcho("a", "answered", 1))
        assertTrue(receipts.consumeEcho("a", "inFlight", 1))
        assertTrue(receipts.consumeEcho("b", "answered", 1))

        receipts.begin("a", "lost", 1)
        clock += 11.seconds
        assertEquals(0, receipts.pendingCount)
    }
}
