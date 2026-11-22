package ai.opencode.feature.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** A rendering probe with no JVM unit-test equivalent (dropped). */
class CaptionRevealTests {
    @Test fun queuedWordsAppearAtLeastSpacingApart() {
        var reveal = CaptionReveal()
        reveal = reveal.sync(listOf(CaptionTarget(id = "user-1", isUser = true, text = "Check the retry test.")))
        assertEquals(0.0, reveal.nextRevealDelay(now = 10.0))
        reveal = reveal.revealNext(10.0)
        assertTrue(reveal.nextRevealDelay(now = 10.02)!! > 0.024)
        reveal = reveal.revealNext(10.045)
        reveal = reveal.revealNext(10.09)
        reveal = reveal.revealNext(10.135)
        assertEquals(listOf(10.0, 10.045, 10.09, 10.135), reveal.lines[0].revealedAt)
        assertNull(reveal.nextRevealDelay(now = 11.0))
    }

    @Test fun openingOntoACallShowsEverythingAtOnce() {
        var reveal = CaptionReveal()
        reveal = reveal.sync(listOf(CaptionTarget(id = "user-1", isUser = true, text = "Hello there")), revealed = true)
        assertFalse(reveal.hasQueued)
    }

    @Test fun liveReplyHoldsBackItsUnfinishedWordAndKeepsRevealsWhenItSettles() {
        var reveal = CaptionReveal()
        reveal = reveal.sync(listOf(CaptionTarget(id = "assistant-a1", isUser = false, text = "Sure. The upl", isLive = true)))
        assertEquals(2, reveal.lines[0].revealable)
        reveal = reveal.revealNext(1.0)
        reveal = reveal.revealNext(2.0)
        assertFalse(reveal.hasQueued)
        reveal = reveal.sync(listOf(CaptionTarget(id = "assistant-a1", isUser = false, text = "Sure. The upload test.")))
        assertEquals(listOf(1.0, 2.0), reveal.lines[0].revealedAt)
        assertEquals(4, reveal.lines[0].revealable)
    }

    @Test fun aLongCallKeepsOnlyTheNewestLines() {
        fun targets(count: Int) = (0 until count).map { CaptionTarget(id = "line-$it", isUser = it % 2 == 0, text = "Line $it words") }
        var reveal = CaptionReveal()
        reveal = reveal.sync(targets(220), revealed = true)
        reveal = reveal.trim(force = true) ?: reveal
        assertEquals((190 until 220).map { "line-$it" }, reveal.lines.map { it.id })

        reveal = reveal.sync(targets(240))
        assertEquals(50, reveal.lines.size)
        val trimmedAtFifty = reveal.trim()
        assertNull(trimmedAtFifty)
        reveal = reveal.revealNext(1.0)
        assertEquals(listOf(1.0), reveal.lines[30].revealedAt)

        reveal = reveal.sync(targets(241))
        val trimmedPastFifty = reveal.trim()
        assertNotNull(trimmedPastFifty)
        reveal = trimmedPastFifty!!
        assertEquals((211 until 241).map { "line-$it" }, reveal.lines.map { it.id })
        assertEquals(listOf(1.0), reveal.lines[9].revealedAt)

        reveal = reveal.sync(targets(260))
        assertEquals("line-211", reveal.lines.firstOrNull()?.id)
        assertEquals(49, reveal.lines.size)
    }

    @Test fun captionsStartingOverShowFromTheirFirstLine() {
        var reveal = CaptionReveal()
        reveal = reveal.sync((0 until 60).map { CaptionTarget(id = "old-$it", isUser = false, text = "Old") }, revealed = true)
        reveal = reveal.trim(force = true) ?: reveal
        reveal = reveal.sync(listOf(CaptionTarget(id = "new-0", isUser = true, text = "Hello there")))
        assertEquals(listOf("new-0"), reveal.lines.map { it.id })
        assertNull(reveal.firstLineID)
    }

    @Test fun settlingUnderANewIDInheritsByPosition() {
        var reveal = CaptionReveal()
        reveal = reveal.sync(listOf(CaptionTarget(id = "assistant-live", isUser = false, text = "Sure thing ", isLive = true)))
        reveal = reveal.revealNext(1.0)
        reveal = reveal.revealNext(2.0)
        reveal = reveal.sync(listOf(CaptionTarget(id = "assistant-xyz", isUser = false, text = "Sure, thing")))
        assertEquals(emptyList<Double>(), reveal.lines[0].revealedAt)
        reveal = reveal.sync(listOf(CaptionTarget(id = "assistant-live", isUser = false, text = "Sure thing ", isLive = true)))
        reveal = reveal.revealNext(1.0)
        reveal = reveal.sync(listOf(CaptionTarget(id = "assistant-xyz", isUser = false, text = "Sure thing done")))
        assertEquals(listOf(1.0), reveal.lines[0].revealedAt)
    }

    @Test fun aRevisedLineSwapsItsShownWordsInPlace() {
        var reveal = CaptionReveal()
        reveal = reveal.sync(listOf(CaptionTarget(id = "delegator-progress", isUser = false, text = "Reading the tests")))
        reveal = reveal.revealNext(1.0)
        reveal = reveal.revealNext(2.0)
        reveal = reveal.revealNext(3.0)
        reveal = reveal.sync(listOf(CaptionTarget(id = "delegator-progress", isUser = false, text = "Running the retry tests")))
        assertEquals(listOf(1.0, 2.0, 3.0), reveal.lines[0].revealedAt)
        assertEquals(4, reveal.lines[0].revealable)
        reveal = reveal.sync(listOf(CaptionTarget(id = "delegator-progress", isUser = false, text = "Done")))
        assertEquals(listOf(1.0), reveal.lines[0].revealedAt)
    }
}

class VoiceAudioQualityTests {
    @Test fun starvedRunsAndThePrerollShowInTheWindow() {
        val quality = VoiceAudioQuality()
        quality.setPreroll(milliseconds = 200.0)
        quality.assistantArrived(milliseconds = 100.0, queuedBefore = 0.0)
        val steady = assertNotNullValue(quality.takeWindow())
        assertFalse(steady.problem)
        assertTrue(steady.text, steady.text.contains("pre-roll 200 ms"))
        assertTrue(steady.text, steady.text.contains("dropouts 0"))

        quality.assistantArrived(milliseconds = 100.0, queuedBefore = 0.0)
        quality.assistantStarved(silentMilliseconds = 42.0, prerollMilliseconds = 300.0)
        val starved = assertNotNullValue(quality.takeWindow())
        assertTrue(starved.problem)
        assertTrue(starved.text, starved.text.contains("dropouts 1 (42 ms silent)"))
        assertTrue(starved.text, starved.text.contains("pre-roll 300 ms"))
        assertTrue(quality.hasProblems)
    }

    @Test fun onlyAStarvedRunIsADropout() {
        val quality = VoiceAudioQuality()
        // A sentence, a pause the player drains through, and the next sentence
        // behind the pre-roll: the scheduler saw no starvation, so no dropout.
        val scheduler = PlaybackScheduler()
        for ((now, speech) in listOf(0L to true, 2_400L to false, 40_800L to true)) {
            val decision = scheduler.schedule(frames = 2_400, itemID = "a", hasSpeech = speech, now = now)
            quality.assistantArrived(milliseconds = 100.0, queuedBefore = 0.0)
            decision.underrunFrames?.let { gap ->
                quality.assistantStarved(silentMilliseconds = gap.toDouble() / 24, prerollMilliseconds = 200.0)
            }
        }
        val quiet = assertNotNullValue(quality.takeWindow())
        assertFalse(quiet.problem)
        assertTrue(quiet.text, quiet.text.contains("dropouts 0"))
        assertFalse(quality.hasProblems)

        // Speech runs dry mid-sentence: a dropout.
        quality.assistantArrived(milliseconds = 100.0, queuedBefore = 0.0)
        quality.assistantStarved(silentMilliseconds = 80.0, prerollMilliseconds = 300.0)
        // A barge-in flush is not.
        quality.assistantFlushed()
        quality.assistantArrived(milliseconds = 100.0, queuedBefore = 0.0)
        quality.captured(dropped = false)
        quality.captured(dropped = true)

        val window = assertNotNullValue(quality.takeWindow())
        assertTrue(window.problem)
        assertTrue(window.text, window.text.contains("dropouts 1 (80 ms silent)"))
        assertTrue(window.text, window.text.contains("voice in: 2 chunks"))
        assertTrue(window.text, window.text.contains("dropped 1"))
        // The window resets; an empty one reports nothing.
        assertNull(quality.takeWindow())
    }

    @Test fun aSteadyWindowIsNotFlagged() {
        val quality = VoiceAudioQuality()
        repeat(5) { quality.assistantArrived(milliseconds = 100.0, queuedBefore = 200.0) }
        quality.captured(dropped = false)
        val window = assertNotNullValue(quality.takeWindow())
        assertFalse(window.problem)
        assertTrue(window.text, window.text.contains("dropouts 0"))
    }

    private fun <T> assertNotNullValue(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
