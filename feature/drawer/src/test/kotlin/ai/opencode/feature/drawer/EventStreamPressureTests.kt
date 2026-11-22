package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.EventBatcher
import ai.opencode.server.EventEmission
import ai.opencode.server.EventStreamFocusClient
import ai.opencode.server.EventStreamOverflow
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import java.time.Instant
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A slow main thread under heavy delta load across sessions. The stream sheds what's off screen
 * instead of overflowing, and an overflow after a quiet stretch reconnects at the shortest wait.
 */
class EventStreamPressureTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "https://home.example", username = "", password = "")

    /**
     * An eight-slot buffer nothing drains until the store subscribes, reporting the slots left on
     * every send.
     */
    private class EightSlots {
        val channel = Channel<OpenCodeServerEvent>(8)
        private var queued = 0

        fun emit(event: OpenCodeServerEvent): EventEmission {
            val result = channel.trySend(event)
            if (result.isClosed) return EventEmission.Terminated
            if (result.isFailure) {
                channel.close(EventStreamOverflow())
                return EventEmission.Dropped
            }
            queued += 1
            return EventEmission.Enqueued(remaining = 8 - queued)
        }
    }

    private fun content(sessionID: String, vararg events: OpenCodeLiveContentEvent) =
        OpenCodeServerEvent.ContentBatch(sessionID, events.toList())

    @Test fun pressureShedsOffScreenDeltasAndKeepsTheStreamConnected() = runTest {
        // Nothing drains the stream until the store subscribes, as when the main thread is busy.
        // Eight slots; without shedding this sequence needs 14.
        val slots = EightSlots()
        val batcher = EventBatcher(
            scope = backgroundScope,
            emit = slots::emit,
            clock = TestClock(),
            capacity = 8,
            isFocused = { it == "ses_open" },
        )
        fun batch(sessionID: String, vararg events: OpenCodeLiveContentEvent) {
            batcher.receive(content(sessionID, *events))
            batcher.flush()
        }
        batcher.receive(OpenCodeServerEvent.Connected)
        batch("ses_open", OpenCodeLiveContentEvent.TextDelta("msg_open", 0, "Hel"))
        batch("ses_other", OpenCodeLiveContentEvent.TextDelta("msg_other", 0, "a"))
        batcher.receive(OpenCodeServerEvent.ExecutionStarted("ses_other"))
        // Half the buffer is waiting: the other session's deltas go.
        batch("ses_other", OpenCodeLiveContentEvent.TextDelta("msg_other", 0, "b"))
        batch("ses_open", OpenCodeLiveContentEvent.TextDelta("msg_open", 0, "lo"))
        for (delta in listOf("c", "d", "e", "f", "g", "h")) {
            batch(
                "ses_other",
                OpenCodeLiveContentEvent.TextDelta("msg_other", 0, delta),
                OpenCodeLiveContentEvent.ReasoningDelta("msg_other", 1),
                OpenCodeLiveContentEvent.ToolInputDelta("msg_other", "call_1", delta),
            )
        }
        batch(
            "ses_other",
            OpenCodeLiveContentEvent.TextDelta("msg_other", 0, "i"),
            OpenCodeLiveContentEvent.TextEnded("msg_other", 0, "abcdefghi"),
        )
        batcher.receive(OpenCodeServerEvent.ExecutionEnded("ses_other"))

        val state = HomeConnectionFeature.State(server = server, isStreaming = true)
        val store = TestStore(state, HomeConnectionFeature(), this) {
            this[Clock] = TestClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(events = { slots.channel.receiveAsFlow() })
        }
        store.send(HomeConnectionFeature.Action.NetworkRerouted)
        store.receive(HomeConnectionFeature.Action.ServerConnected(server)) {
            it.copy(hasConnected = true, isEventStreamLive = true)
        }
        store.receive(HomeConnectionFeature.Action.ServerEvent(content("ses_open", OpenCodeLiveContentEvent.TextDelta("msg_open", 0, "Hel"))))
        store.receive(HomeConnectionFeature.Action.ServerEvent(content("ses_other", OpenCodeLiveContentEvent.TextDelta("msg_other", 0, "a"))))
        store.receive(HomeConnectionFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionStarted("ses_other")))
        store.receive(HomeConnectionFeature.Action.ServerEvent(content("ses_open", OpenCodeLiveContentEvent.TextDelta("msg_open", 0, "lo"))))
        store.receive(HomeConnectionFeature.Action.ServerEvent(content("ses_other", OpenCodeLiveContentEvent.TextEnded("msg_other", 0, "abcdefghi"))))
        store.receive(HomeConnectionFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionEnded("ses_other")))
        // Still connected: no overflow, no `ConnectionLost`.
        assertTrue(store.state.isEventStreamLive)
        store.skipInFlightEffects()
    }

    @Test fun withoutPressureNothingIsShed() = runTest {
        val slots = EightSlots()
        val batcher = EventBatcher(
            scope = backgroundScope,
            emit = slots::emit,
            clock = TestClock(),
            capacity = 8,
            isFocused = { false },
        )
        val delta = content("ses_other", OpenCodeLiveContentEvent.TextDelta("m", 0, "x"))
        batcher.receive(delta)
        batcher.flush()
        slots.channel.close()
        val events = ArrayList<OpenCodeServerEvent>()
        for (event in slots.channel) {
            events += event
        }
        assertEquals(listOf<OpenCodeServerEvent>(delta), events)
    }

    @Test fun focusIsTheUnionOfEveryWindowsSessions() {
        val focus = EventStreamFocusClient.inMemory()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        focus.publish(first, setOf("ses_a", "ses_sub"))
        focus.publish(second, setOf("ses_b"))
        assertTrue(focus.contains("ses_a") && focus.contains("ses_sub") && focus.contains("ses_b"))
        focus.publish(first, emptySet())
        assertFalse(focus.contains("ses_a"))
        assertTrue(focus.contains("ses_b"))
    }

    /**
     * An overflow after the connection kept up for a quiet stretch is a new burst: it reconnects
     * at the first, shortest wait, not the 15 s the backoff had climbed toward.
     */
    @Test fun anOverflowAfterAQuietStretchRestartsTheBackoff() = runTest {
        val clock = TestClock()
        val now = Instant.ofEpochSecond(100)
        val streams = Collections.synchronizedList(mutableListOf<Channel<OpenCodeServerEvent>>())
        val state = HomeConnectionFeature.State(server = server, isStreaming = true)
        val store = TestStore(state, HomeConnectionFeature(), this) {
            this[Clock] = clock
            this[HomeSnapshotDate] = DateClient.constant(now)
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                events = {
                    val channel = Channel<OpenCodeServerEvent>(Channel.UNLIMITED)
                    channel.trySend(OpenCodeServerEvent.Connected)
                    streams += channel
                    channel.receiveAsFlow()
                },
            )
        }
        fun overflow(index: Int) {
            streams[index].close(EventStreamOverflow())
        }

        store.send(HomeConnectionFeature.Action.NetworkRerouted)
        store.receive(HomeConnectionFeature.Action.ServerConnected(server)) {
            it.copy(hasConnected = true, isEventStreamLive = true)
        }
        overflow(0)
        store.receive(HomeConnectionFeature.Action.ConnectionLost) {
            it.copy(
                connectionStatus = HomeConnectionFeature.State.ConnectionStatus.reconnecting,
                isEventStreamLive = false,
                lastKnownActivityDeadline = now.plusMillis(HomeFeature.lastKnownActivityLifetime.inWholeMilliseconds),
                isRecovering = true,
                recoveryFailures = 1,
            )
        }
        clock.advance(1.seconds)
        store.receive(HomeConnectionFeature.Action.ServerConnected(server)) {
            it.copy(isEventStreamLive = true, isRecovering = false, recoveryFailures = 0)
        }
        // Straight away again: the backoff climbs (up to 2 s).
        overflow(1)
        store.receive(HomeConnectionFeature.Action.ConnectionLost) {
            it.copy(isEventStreamLive = false, isRecovering = true, recoveryFailures = 1)
        }
        clock.advance(2.seconds)
        store.receive(HomeConnectionFeature.Action.ServerConnected(server)) {
            it.copy(isEventStreamLive = true, isRecovering = false, recoveryFailures = 0)
        }
        // The 2 s advance also reaches the quiet grace. This TestStore reduces an effect's action
        // when it's received, so the grace has already fired; the reconnect settled recovery, so it
        // changes nothing.
        store.receive(HomeConnectionFeature.Action.ReconnectingGraceElapsed)
        // Kept up for the quiet stretch, then fell behind: a climbing backoff would wait at least
        // 2 s; a restarted one at most 1 s.
        clock.advance(HomeConnectionFeature.overflowQuietStretch)
        overflow(2)
        store.receive(HomeConnectionFeature.Action.ConnectionLost) {
            it.copy(isEventStreamLive = false, isRecovering = true, recoveryFailures = 1)
        }
        clock.advance(1.seconds)
        store.receive(HomeConnectionFeature.Action.ServerConnected(server)) {
            it.copy(isEventStreamLive = true, isRecovering = false, recoveryFailures = 0)
        }
        assertEquals(4, streams.size)
        store.skipInFlightEffects()
    }
}
