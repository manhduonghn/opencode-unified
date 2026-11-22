package ai.opencode.server

import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeLiveContentEvent.TextDelta
import ai.opencode.server.OpenCodeLiveContentEvent.TextEnded
import ai.opencode.server.OpenCodeLiveContentEvent.TextStarted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

class EventBatcherTests {
    @get:Rule val isolation = TestIsolationRule()

    private class Stream(scope: CoroutineScope, remaining: Int = EventBatcher.BUFFER_CAPACITY) {
        val focused = mutableSetOf<String>()
        val output = mutableListOf<OpenCodeLiveContentEvent>()
        val prepared = mutableListOf<Pair<String, Boolean>>()
        val overflows = mutableListOf<EventStreamOverflow>()
        val batcher = EventBatcher(
            scope, clock = TestClock(), isFocused = focused::contains,
            hooks = EventBatcherHooks(prepareMarkdown = { _, text, streaming -> prepared.add(text to streaming) }),
            emit = { batch ->
                output += (batch as OpenCodeServerEvent.ContentBatch).events
                EventEmission.Enqueued(remaining)
            },
            onOverflow = { overflows.add(it) },
        )

        fun batch(session: String, vararg events: OpenCodeLiveContentEvent) {
            batcher.receive(OpenCodeServerEvent.ContentBatch(session, events.toList()))
            batcher.flush()
        }
    }

    @Test fun oldestFocusedPartKeepsStreamingWith32OtherUnfinishedParts() = runTest {
        val s = Stream(backgroundScope)
        s.focused += "open"
        s.batch("open", TextStarted("m", 0), TextDelta("m", 0, "A"))
        for (ordinal in 32 downTo 0) s.batch("hidden", TextStarted("h", ordinal), TextDelta("h", ordinal, "H"))
        s.output.clear()
        s.batch("open", TextDelta("m", 0, "B"))
        s.batch("open", TextDelta("m", 0, "C"))
        // Revisiting the oldest discarded ID must not evict the next live accumulator.
        s.batch("hidden", TextDelta("h", 32, "1"), TextDelta("h", 31, "2"), TextEnded("h", 32, "H1"))
        assertEquals(
            listOf(TextDelta("m", 0, "B", "AB"), TextDelta("m", 0, "C", "ABC"), TextDelta("h", 31, "2", "H2"), TextEnded("h", 32, "H1")),
            s.output,
        )
        assertEquals(listOf("A" to true, "AB" to true, "ABC" to true), s.prepared)
    }

    @Test fun oversizedOffscreenPartWaitsForItsEndAfterOpening() = runTest {
        for (remaining in listOf(1, EventBatcher.BUFFER_CAPACITY)) {
            val s = Stream(backgroundScope, remaining)
            val limit = "é".repeat(256 * 1024) // 512 KiB in UTF-8.
            val end = TextEnded("m", 0, "A${limit}tail")
            s.batch("s", TextStarted("m", 0), TextDelta("m", 0, "A"))
            s.batch("s", TextDelta("m", 0, limit))
            s.focused += "s"
            s.batch("s", TextDelta("m", 0, "tail"), end)
            assertEquals(listOf(TextStarted("m", 0), TextDelta("m", 0, "A", "A"), end), s.output)
            assertEquals(listOf(end.text to false), s.prepared)
        }
    }

    @Test fun openingAfterAnOffscreenFlushShowsThePrefixFromTheQueuedBatch() = runTest {
        val s = Stream(backgroundScope)
        s.batch("s", TextStarted("m", 0), TextDelta("m", 0, "First"))
        s.batch("s", TextDelta("m", 0, " second"))
        // Home consumed the first batch before the detail existed; the queued one carries the prefix.
        assertEquals(TextDelta("m", 0, " second", "First second"), s.output.last())
    }

    @Test fun tooManyUnfinishedPartsIncludingDiscardedPrefixesEndsTheStream() = runTest {
        val s = Stream(backgroundScope)
        repeat(1024) { s.batch("hidden", TextStarted("m", it)) }
        assertEquals(1024, s.output.size)
        // Ending a discarded part frees one slot for a focused part.
        s.focused += "open"
        s.batch("hidden", TextEnded("m", 0, "done"))
        s.batch("open", TextStarted("m", 1024))
        assertEquals(1026, s.output.size)
        s.batch("open", TextStarted("m", 1025))
        s.batch("open", TextEnded("m", 1025, "after overflow"))
        assertEquals(1, s.overflows.size)
        assertEquals(1026, s.output.size)
    }

    @Test
    fun batcherOverflowFinishesAndCancelsFurtherEmission() = runTest {
        for (path in listOf("boundary", "sessionFlush", "allFlush")) {
            // A one-event buffer whose overflow ends the stream, as the live subscription's does.
            val channel = Channel<OpenCodeServerEvent>(1)
            val batcher = EventBatcher(
                scope = backgroundScope,
                emit = { event ->
                    val result = channel.trySend(event)
                    when {
                        result.isSuccess -> EventEmission.Enqueued(remaining = 0)
                        result.isClosed -> EventEmission.Terminated
                        else -> {
                            channel.close(EventStreamOverflow())
                            EventEmission.Dropped
                        }
                    }
                },
                clock = TestClock(),
            )
            batcher.receive(OpenCodeServerEvent.Connected)
            if (path == "boundary") {
                batcher.receive(OpenCodeServerEvent.ExecutionEnded("s"))
            } else {
                batcher.receive(OpenCodeServerEvent.ContentBatch("s", listOf(OpenCodeLiveContentEvent.TextDelta("m", 0, "part"))))
                if (path == "sessionFlush") {
                    batcher.receive(OpenCodeServerEvent.ExecutionEnded("s"))
                } else {
                    batcher.flush()
                }
            }
            batcher.receive(OpenCodeServerEvent.ExecutionStarted("must-not-arrive"))
            try {
                for (event in channel) {
                    assertNotEquals(path, OpenCodeServerEvent.ExecutionStarted("must-not-arrive"), event)
                }
                fail("$path: the direct stream must report overflow")
            } catch (_: EventStreamOverflow) {
                // Expected.
            }
        }
    }
}
