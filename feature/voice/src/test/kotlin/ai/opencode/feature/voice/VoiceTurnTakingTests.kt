package ai.opencode.feature.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTurnTakingTests {
    /** The Kotlin value returns copies, so the test holds the var. */
    private class Turns {
        var value = VoiceTurnTaking()
        private fun <R> take(step: (VoiceTurnTaking) -> Pair<VoiceTurnTaking, R>): R =
            step(value).let { value = it.first; it.second }
        fun replyRequested() = take { it.replyRequested() }
        fun userStartedSpeaking() = take { it.userStartedSpeaking() }
        fun userStoppedSpeaking() = take { it.userStoppedSpeaking() }
        fun serverReplyTimedOut() = take { it.serverReplyTimedOut() }
        fun responseCreated() = take { it.responseCreated() }
        fun responseDone() = take { it.responseDone() }
        fun playbackDrained() = take { it.playbackDrained() }
        fun requestRejected() { value = value.requestRejected() }
        fun audioEnqueued(itemID: String?) { value = value.audioEnqueued(itemID) }
        val replyOwed get() = value.replyOwed
        val responseActive get() = value.responseActive
        val isPlaying get() = value.isPlaying
    }

    @Test fun aToolResultWaitsWhileTheUserIsTalking() {
        val turns = Turns()
        turns.userStartedSpeaking()
        val first = turns.replyRequested()
        val second = turns.replyRequested()
        assertFalse("Never start a reply mid-utterance", first)
        assertFalse("A second result joins the same owed reply", second)
        // Server VAD answers the user's turn; that reply covers the owed results.
        val stopped = turns.userStoppedSpeaking()
        val cancel = turns.responseCreated()
        val timedOut = turns.serverReplyTimedOut()
        val afterDone = turns.responseDone()
        assertTrue("No second reply once the server answered", stopped && !cancel && !timedOut && !afterDone)
    }

    @Test fun theOwedReplyGoesOutIfTheServerDoesNotAnswerTheTurn() {
        val turns = Turns()
        turns.userStartedSpeaking()
        turns.replyRequested()
        turns.userStoppedSpeaking()
        val timedOut = turns.serverReplyTimedOut()
        val again = turns.serverReplyTimedOut()
        assertTrue(timedOut && !again)
    }

    @Test fun aResultBetweenTheTurnEndingAndTheServerReplyIsSpokenAfterIt() {
        val turns = Turns()
        turns.userStartedSpeaking()
        turns.userStoppedSpeaking()
        // The server's reply was committed before this result: it can't cover it.
        val now = turns.replyRequested()
        val cancel = turns.responseCreated()
        val afterDone = turns.responseDone()
        assertFalse("Don't race the server's reply already on its way", now)
        assertFalse(cancel)
        assertTrue("…then speak about the result", afterDone)
    }

    @Test fun backToBackResultsSendOneRequest() {
        val turns = Turns()
        val first = turns.replyRequested()
        val second = turns.replyRequested()
        assertTrue("The first request holds the floor until the server confirms", first && !second)
        val cancel = turns.responseCreated()
        val afterDone = turns.responseDone()
        assertFalse(cancel)
        assertTrue("The second result gets its own reply afterwards", afterDone)
    }

    @Test fun aRequestedReplyCreatedAfterTheUserStartsTalkingIsCancelled() {
        val turns = Turns()
        turns.replyRequested()
        val bargeIn = turns.userStartedSpeaking()
        assertEquals("Nothing exists to cancel yet", VoiceTurnTaking.BargeIn(truncateItemID = null, cancelsResponse = false), bargeIn)
        val cancel = turns.responseCreated()
        assertTrue("Server VAD won't interrupt it: cancel it ourselves", cancel)
        assertTrue("The result stays owed", turns.replyOwed)
        turns.responseDone()
        turns.userStoppedSpeaking()
        val timedOut = turns.serverReplyTimedOut()
        assertTrue(timedOut)
    }

    @Test fun aRejectedRequestStaysOwed() {
        val turns = Turns()
        turns.replyRequested()
        turns.requestRejected()
        assertTrue(turns.replyOwed && !turns.responseActive)
    }

    @Test fun aResultArrivingMidReplyWaitsForThePlaybackToFinish() {
        val turns = Turns()
        turns.responseCreated()
        turns.audioEnqueued(itemID = "item_1")
        val now = turns.replyRequested()
        // Generation ends long before the speaker does: still not our turn.
        val afterDone = turns.responseDone()
        val afterDrain = turns.playbackDrained()
        assertTrue(!now && !afterDone)
        assertTrue("Queued, not spoken over the first reply", afterDrain)
    }

    @Test fun bargeInTruncatesTheItemStillPlayingAfterItsResponseFinished() {
        val turns = Turns()
        turns.responseCreated()
        turns.audioEnqueued(itemID = "item_1")
        turns.responseDone()
        val bargeIn = turns.userStartedSpeaking()
        assertEquals(VoiceTurnTaking.BargeIn(truncateItemID = "item_1", cancelsResponse = false), bargeIn)
        assertFalse(turns.isPlaying)
    }

    @Test fun bargeInCancelsAResponseThatHasNotSpokenYet() {
        val turns = Turns()
        turns.responseCreated()
        val bargeIn = turns.userStartedSpeaking()
        assertEquals(VoiceTurnTaking.BargeIn(truncateItemID = null, cancelsResponse = true), bargeIn)
    }

    @Test fun mutingMidSentenceEndsTheTurn() {
        val turns = Turns()
        turns.userStartedSpeaking()
        turns.replyRequested()
        // The coordinator treats a mute mid-sentence as the end of the turn.
        val stopped = turns.userStoppedSpeaking()
        val timedOut = turns.serverReplyTimedOut()
        assertTrue(stopped && timedOut)
    }

    @Test fun aFreeFloorRepliesAtOnce() {
        val turns = Turns()
        val now = turns.replyRequested()
        assertTrue(now && !turns.replyOwed)
    }
}
