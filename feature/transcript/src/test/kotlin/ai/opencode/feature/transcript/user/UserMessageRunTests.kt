package ai.opencode.feature.transcript.user

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Runs of user messages: adjacent messages of one kind join, anything between them or a line under
 * one ends the run, and a bubble's trailing corners tighten where it meets another bubble;
 * attachments keep their own round shape.
 */
class UserMessageRunTests {
    private fun member(pending: Boolean, endsRun: Boolean = false, startsWithBubble: Boolean = true) =
        UserMessageRun.Member(pending = pending, endsRun = endsRun, startsWithBubble = startsWithBubble)

    @Test
    fun adjacentSteeringMessagesJoinAndWorkingEndsTheRun() {
        // Delivered, Working, then three steering messages.
        val runs = UserMessageRun.plan(listOf(member(false), null, member(true), member(true), member(true)))
        assertEquals(UserMessageRun.alone, runs[0])
        assertNull(runs[1])
        assertEquals(UserMessageRun(joinsAbove = false, joinsBelow = true, bubbleAbove = false, bubbleBelow = true), runs[2])
        assertEquals(UserMessageRun(joinsAbove = true, joinsBelow = true, bubbleAbove = true, bubbleBelow = true), runs[3])
        assertEquals(UserMessageRun(joinsAbove = true, joinsBelow = false, bubbleAbove = true, bubbleBelow = false), runs[4])
    }

    @Test
    fun deliveredAndPendingNeverJoin() {
        val runs = UserMessageRun.plan(listOf(member(false), member(true)))
        assertEquals(listOf(false, false), runs.map { it?.joinsAbove })
        assertEquals(listOf(false, false), runs.map { it?.joinsBelow })
    }

    @Test
    fun aLineUnderAMessageEndsItsRun() {
        val runs = UserMessageRun.plan(listOf(member(false, endsRun = true), member(false), member(false)))
        assertEquals(false, runs[0]?.joinsBelow)
        assertEquals(false, runs[1]?.joinsAbove)
        assertEquals(true, runs[1]?.joinsBelow)
        assertEquals(true, runs[2]?.joinsAbove)
    }

    @Test
    fun bubblesJoinOnlyWhereTheyMeetABubble() {
        // Text, then an image with text, then text.
        val runs = UserMessageRun.plan(listOf(member(true), member(true, startsWithBubble = false), member(true)))
        // The first bubble meets the second message's image: it keeps its corner.
        assertEquals(UserSurfaceCorners.round, runs[0]?.bubbleCorners(hasAttachments = false))
        // The second's bubble sits under its own image, and meets the third's bubble.
        assertEquals(UserSurfaceCorners(top = false, bottom = true), runs[1]?.bubbleCorners(hasAttachments = true))
        assertEquals(UserSurfaceCorners(top = true, bottom = false), runs[2]?.bubbleCorners(hasAttachments = false))
        assertEquals(UserSurfaceCorners.round, UserMessageRun.alone.bubbleCorners(hasAttachments = false))
    }
}
