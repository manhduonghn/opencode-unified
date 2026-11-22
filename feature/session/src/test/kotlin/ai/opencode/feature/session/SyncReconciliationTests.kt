package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.testServer
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Android state is immutable, so "no write" is the same list instance (`===`). */
class SyncReconciliationTests {
    @Test fun unchangedMessageAndNoticeDoNotWriteTranscript() = sessionTest { h ->
        val first = OpenCodeTranscriptItem.AssistantMarkdown(id = "m-text-0", text = "First")
        val last = OpenCodeTranscriptItem.AssistantMarkdown(id = "m-text-1", text = "Last")
        val notice = OpenCodeTranscriptItem.Notice(id = "notice", text = "Finished")
        val state = h.syncState(listOf(OpenCodeTranscriptItem.UserText(id = "u", text = "Hi"), first, notice, last))
        val original = state.items
        val next = h.state { SessionFeature().applyAuthoritativeMessage(listOf(first, last), "m", state) }
        assertEquals(original, next.items)
        // Equal REST settlement must not enter the observed items mutation accessor.
        assertSame(original, next.items)
    }

    @Test fun unchangedWindowRetainsStorageAndWritesNoTranscript() = sessionTest { h ->
        val items = (0 until 600).map { OpenCodeTranscriptItem.UserText(id = "m$it", text = "History") }
        val window = items.takeLast(30)
        val merged = SessionFeature.mergeTranscript(fetched = window, existing = items)
        assertSame("An identical window must reuse the loaded buffer, not rebuild history", items, merged)
        val state = h.syncState(items)
        val next = h.state { SessionFeature().acceptWindow(window, state = state) }
        assertSame(items, next.items)
    }

    @Test fun equalPromptStillSettlesEchoBookkeeping() = sessionTest { h ->
        val prompt = OpenCodeTranscriptItem.UserText(id = "prompt", text = "Sent")
        val state = h.syncState(listOf(prompt)).copy(optimisticPromptIDs = setOf("prompt"), echoedPromptIDs = setOf("prompt"))
        val original = state.items
        val next = h.state { SessionFeature().applyAuthoritativeMessage(listOf(prompt), "prompt", state) }
        assertSame(original, next.items)
        assertTrue(next.optimisticPromptIDs.isEmpty())
        assertTrue(next.echoedPromptIDs.isEmpty())
    }

    @Test fun changedMessageAndInterleavedRowsKeepReplacementSemantics() = sessionTest { h ->
        val a = OpenCodeTranscriptItem.AssistantMarkdown(id = "m-text-0", text = "Old")
        val b = OpenCodeTranscriptItem.AssistantMarkdown(id = "m-text-1", text = "Last")
        val other = OpenCodeTranscriptItem.UserText(id = "u", text = "Other")
        val fresh = OpenCodeTranscriptItem.AssistantMarkdown(id = "m-text-0", text = "New")
        val state = h.syncState(listOf(a, other, b))
        val next = h.state { SessionFeature().applyAuthoritativeMessage(listOf(fresh, b), "m", state) }
        assertEquals(listOf(fresh, b, other), next.items)
    }

    @Test fun historyMergeHasLinearIDWork() {
        for (count in listOf(40, 600, 6_000)) {
            val existing = (0 until count).toList()
            val page = (0 until 30).flatMap { listOf(-1 - it, count - 30 + it) }
            var reads = 0
            val merged = SessionFeature.mergeOlderPage(page, existing, before = null, reachedLoadedItems = false) {
                reads += 1
                it
            }
            assertEquals(
                "Index every loaded/page ID once; never scan loaded history per page row",
                existing.size + page.size,
                reads,
            )
            assertEquals(legacyPage(page, existing, anchor = null, reached = false), merged)
        }
    }

    @Test fun historyMergeMatchesReverseScanAcrossGapsAndReorderedAnchors() {
        // Exhaust small pages, including duplicate new IDs and anchors in either
        // order: both are easy to mishandle with insertion buckets alone.
        val existing = listOf(0, 2, 4)
        for (anchor in listOf<Int?>(null, 0, 2, 4, 99)) {
            for (reached in listOf(false, true)) {
                for (encoding in 0 until 1_296) {
                    var value = encoding
                    val page = ArrayList<Int>()
                    repeat(4) { page.add(value % 6); value /= 6 }
                    val actual = SessionFeature.mergeOlderPage(page, existing, before = anchor, reachedLoadedItems = reached) { it }
                    assertEquals(legacyPage(page, existing, anchor, reached), actual)
                }
            }
        }
    }

    @Test fun contentBatchesKeepOneSleeperAndExactInactivityDeadline() = sessionTest { h ->
        val clock = TestClock()
        val sleeps = LockIsolated(0)
        val reads = LockIsolated(0)
        val initial = h.syncState(emptyList()).copy(
            hasRenderedFirstContentFrame = true,
            isExternallyActive = true,
            streamIsHealthy = true,
            streamSupportsProjection = true,
        )
        val session = initial.session!!
        val store = h.store(initial) {
            this[Clock] = CountingClock(clock, sleeps)
            this[SessionTimers] = SessionTimers(watchdog = true, runEndCheck = false, requestRetry = false)
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> reads.increment(); emptyList() },
            )
        }
        // This test counts task starts/deadlines; the complete refresh state
        // transition is covered exhaustively by SessionFeatureTests.
        store.exhaustive = false
        store.send(Action.ContentBatch(sessionID = session.id, events = emptyList()))
        repeat(60) {
            clock.advance(1.seconds)
            store.skipReceivedActions()
            repeat(10) { store.send(Action.ContentBatch(sessionID = session.id, events = emptyList())) }
        }
        assertEquals("601 batches must not recreate the watchdog", 1, store.state.watchdogGeneration)
        assertTrue("Only inactivity-deadline wakes may re-arm the sleeper", sleeps.value <= 4)
        assertEquals("Continuous content must retain the previous inactivity behavior", 0, reads.value)
        val interval = store.state.watchdogSchedule!!.interval
        assertEquals(20 + kotlin.math.abs(session.id.hashCode() % 5), interval.inWholeSeconds.toInt())
        clock.advance(interval - 1.milliseconds)
        store.skipReceivedActions()
        assertEquals(0, reads.value)
        clock.advance(1.milliseconds)
        store.receive(Action.RefreshResponse::class)
        assertEquals(1, reads.value)
        store.skipReceivedActions()
        h.skipInFlightEffects()
    }

    @Test fun staleWatchdogWakeCannotRefreshAnotherSession() = sessionTest { h ->
        val clock = TestClock()
        val initial = h.syncState(emptyList()).copy(watchdogGeneration = 2)
        val store = h.store(initial) {
            this[Clock] = clock
            this[SessionTimers] = SessionTimers(watchdog = true, runEndCheck = false, requestRetry = false)
        }
        store.send(Action.WatchdogElapsed(generation = 1))
        store.finish()
    }

    private fun legacyPage(page: List<Int>, existing: List<Int>, anchor: Int?, reached: Boolean): List<Int>? {
        val result = existing.toMutableList()
        val existingSet = existing.toSet()
        if (page.any { it in existingSet }) {
            var insertion: Int? = anchor?.let { result.indexOf(it).takeIf { index -> index >= 0 } }
            for (item in page.reversed()) {
                val known = result.indexOf(item)
                if (known >= 0) insertion = known
                else if (insertion != null) result.add(insertion, item)
            }
        } else if (reached || existing.isEmpty()) {
            result.addAll(anchor?.let { result.indexOf(it).takeIf { index -> index >= 0 } } ?: 0, page)
        } else {
            return null
        }
        return result
    }

    private fun Harness.syncState(items: List<OpenCodeTranscriptItem>): SessionFeature.State = state {
        SessionFeature.State.session(
            server = testServer,
            session = OpenCodeSession(
                id = "ses_sync",
                serverTitle = "Sync",
                projectID = "p",
                directory = "/project",
                updatedAt = SessionTestSupport.distantPast,
            ),
            cachedItems = items,
        )
    }

    /** Counts sleeps on [base]. */
    private class CountingClock(private val base: Clock, private val sleeps: LockIsolated<Int>) : Clock {
        override fun now(): Duration = base.now()
        override suspend fun sleep(duration: Duration) {
            sleeps.increment()
            base.sleep(duration)
        }
    }
}
