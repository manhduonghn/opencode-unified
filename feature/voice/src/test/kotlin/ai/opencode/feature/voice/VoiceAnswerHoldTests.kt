package ai.opencode.feature.voice

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How a delegation's answer and the results around it wait for speech, on a test clock: a loaded
 * machine stretching a pause can't change what the call does.
 */
class VoiceAnswerHoldTests {
    private val answer = LiveCallCoordinatorTests.rulesAnswer
    private val result = "The session “Upload” finished the work you started there. Its reply: All tests pass."

    /** The user asked for the rules and a delegation took it. */
    private suspend fun asked(call: VoiceClockedCall) {
        call.userSays("What are the rules?", from = 500, to = 900)
        call.delegates("del_rules", at = 900)
    }

    private suspend fun answers(call: VoiceClockedCall, output: String? = null) {
        call.coordinator.completeFunction(callID = "del_rules", output = "[COMPLETE] ${output ?: answer}")
    }

    /**
     * A delegation's answer waits on the voice agent's chatter only as long
     * as any result does.
     */
    @Test fun continuousSpeechHoldsAnAnswerOnlyUpToTheLimit() = runTest {
        val call = idleCall(resultMaxHold = 600.milliseconds)
        asked(call)
        call.advance(400)
        call.agentSpeaks("Meanwhile, a long story", from = 1_000, to = 9_000)
        call.advance(100)
        answers(call)
        assertTrue(call.spoken().isEmpty())
        call.advance(599)
        assertTrue("Held while the voice agent talks", call.spoken().isEmpty())
        call.advance(1)
        call.waitFor { call.spoken().isNotEmpty() }
        assertEquals("Not held for the whole story", listOf(answer), call.spoken())
        call.coordinator.end()
    }

    @Test fun aLongAnswerTheUserAskedForIsSentWhole() = runTest {
        val call = idleCall()
        asked(call)
        call.advance(500)
        val walkthrough = (1..80).joinToString(" ") { "Step $it of the walkthrough explains one more part of the rules." }
        assertTrue(walkthrough.length > 5_000)
        val output = VoiceHandoff.completion(VoiceSessionDelegator.spokenReply(walkthrough))
        val chunks = LiveContentChunker.commentaryChunks(output)
        assertTrue(chunks.size > 2)
        call.coordinator.completeFunction(callID = "del_rules", output = output)
        call.waitFor { call.spoken().size == chunks.size }
        assertEquals("Every sentence, nothing cut", walkthrough, call.spoken().joinToString(" "))
        call.coordinator.end()
    }

    /**
     * While the voice agent speaks a delegation's answer, a result waits for
     * it to finish (up to the longer limit) rather than cutting it off.
     */
    @Test fun aResultNeverCutsOffTheAnswerBeingSpoken() = runTest {
        val call = idleCall(resultMaxHold = 400.milliseconds, answerSpeakingHold = 5.seconds)
        asked(call)
        call.advance(500)
        answers(call)
        call.waitFor { call.spoken().isNotEmpty() }
        assertEquals(listOf(answer), call.spoken())
        // The voice agent reads the answer out.
        call.agentSpeaks("One player is the giant desk spider", from = 2_000, to = 6_000)
        call.advance(100)
        call.coordinator.announce(result, sessionID = "ses_upload")
        call.advance(1_000)
        assertFalse("Past the usual limit, but the answer is still being spoken", result in call.spoken())
        call.playbackDrains()
        call.advance(149)
        assertFalse("The answer's pause", result in call.spoken())
        call.advance(1)
        call.waitFor { result in call.spoken() }
        assertEquals("Right after the answer", listOf(answer, result), call.spoken())
        call.coordinator.end()
    }

    /**
     * A result whose limit runs out while an answer is queued behind the
     * same chatter goes out after the answer, and still gives the voice agent
     * its moment to start speaking it.
     */
    @Test fun anExpiredResultFollowsAQueuedAnswerAndStillWaitsForIt() = runTest {
        val call = idleCall(resultMaxHold = 600.milliseconds, answerSpeakingHold = 5.seconds)
        asked(call)
        call.advance(400)
        // The voice agent chatters on and never stops.
        call.agentSpeaks("Meanwhile, a long story", from = 1_000, to = 9_000)
        call.advance(50)
        call.coordinator.announce(result, sessionID = "ses_upload")
        call.advance(100)
        answers(call)
        call.advance(499)
        assertTrue(call.spoken().isEmpty())
        // The result's limit: the answer held ahead of it goes first, alone.
        call.advance(1)
        call.waitFor { call.spoken().isNotEmpty() }
        assertEquals("The answer first, alone", listOf(answer), call.spoken())
        call.advance(399)
        assertEquals("It gives the answer its moment to start", listOf(answer), call.spoken())
        call.advance(1)
        call.waitFor { call.spoken().size == 2 }
        assertEquals(listOf(answer, result), call.spoken())
        call.coordinator.end()
    }

    /**
     * The user talks past even the answer's limit while a result and an
     * answer wait. When they stop, the answer goes first and still gets its
     * moment to start: the result's own deadline, long gone, doesn't cut that.
     */
    @Test fun aLongHeldResultStillWaitsForAnAnswerSentAfterTheUserStops() = runTest {
        val call = idleCall(resultMaxHold = 300.milliseconds, answerSpeakingHold = 1.seconds)
        call.userSays("So", from = 1_000)
        call.advance(30)
        call.coordinator.announce(result, sessionID = "ses_upload")
        call.delegates("del_rules", at = 1_050)
        for (index in 1..20) {
            call.advance(80)
            call.userSays(" and", from = 1_000 + index * 80)
            if (index == 3) answers(call)
        }
        assertTrue("Held while the user talks, past every limit", call.spoken().isEmpty())
        call.advance(149)
        assertTrue("Still within the user's pause", call.spoken().isEmpty())
        call.advance(1)
        call.waitFor { call.spoken().isNotEmpty() }
        assertEquals(listOf(answer), call.spoken())
        call.advance(399)
        assertEquals("The answer gets its moment to start", listOf(answer), call.spoken())
        call.advance(1)
        call.waitFor { call.spoken().size == 2 }
        assertEquals(listOf(answer, result), call.spoken())
        call.coordinator.end()
    }

    /**
     * An answer sent while the voice agent is still talking: its audio
     * follows the cut-off speech without a break, and is protected as the
     * answer, past the usual limit, until it ends.
     */
    @Test fun theAnswersAudioIsProtectedWhenItFollowsSpeechWithoutABreak() = runTest {
        val call = idleCall(resultMaxHold = 400.milliseconds, answerSpeakingHold = 4.seconds)
        asked(call)
        call.advance(400)
        call.agentSpeaks(from = 1_000)
        call.advance(50)
        answers(call)
        // The chatter holds the answer up to its limit; it goes out mid-speech.
        call.advance(399)
        assertTrue(call.spoken().isEmpty())
        call.advance(1)
        call.waitFor { call.spoken().isNotEmpty() }
        // The answer's audio arrives with no drain in between.
        call.agentSpeaks(from = 1_600)
        call.advance(50)
        call.coordinator.announce(result, sessionID = "ses_upload")
        for (index in 1..6) {
            call.advance(150)
            call.agentSpeaks(from = 1_700 + index * 150)
        }
        assertFalse("Held past the start wait and the usual limit: it's the answer", result in call.spoken())
        call.playbackDrains()
        call.advance(149)
        assertFalse(result in call.spoken())
        call.advance(1)
        call.waitFor { result in call.spoken() }
        assertEquals(listOf(answer, result), call.spoken())
        call.coordinator.end()
    }

    /**
     * An answer's chunks go out whole and in order even when a send stalls:
     * a result whose limit runs out meanwhile waits its turn instead of
     * landing between them.
     */
    @Test fun aResultNeverLandsBetweenAnAnswersChunks() = runTest {
        val call = idleCall(resultMaxHold = 200.milliseconds, answerSpeakingHold = 1.seconds)
        asked(call)
        call.advance(500)
        val longAnswer = "$answer ".repeat(12)
        val chunks = LiveContentChunker.commentaryChunks("[COMPLETE] $longAnswer")
        assertTrue(chunks.size > 1)
        call.socket.stallFirstCommentary = true
        val answering = launch { answers(call, longAnswer) }
        call.waitFor { call.socket.commentaryEntered == 1 }
        call.coordinator.announce(result, sessionID = "ses_upload")
        // Past the result's limit and the answer's start wait, the first chunk still stuck.
        call.advance(700)
        assertTrue("Nothing overtakes the stalled chunk", call.spoken().isEmpty())
        call.socket.stallFirstCommentary = false
        call.join(answering)
        call.advance(400)
        call.waitFor { call.spoken().size == chunks.size + 1 }
        assertEquals("The answer whole, then the result", chunks + result, call.spoken())
        call.coordinator.end()
    }

    /**
     * The user starts talking while the answer waits its turn behind a result
     * stuck mid-send: once it has the slot it checks again and holds on
     * until they pause.
     */
    @Test fun anAnswerWaitingForTheSlotHoldsForTheUserWhoStartedTalking() = runTest {
        val call = idleCall(resultMaxHold = 200.milliseconds, answerSpeakingHold = 1.seconds)
        call.socket.stallFirstCommentary = true
        // Stalls inside the send, so it isn't awaited.
        launch { call.coordinator.announce(result, sessionID = "ses_upload") }
        call.waitFor { call.socket.commentaryEntered == 1 }
        asked(call)
        call.advance(500)
        val answering = launch { answers(call) }
        call.until { call.coordinator.serialized { call.coordinator.sendSlotWaiters.size } == 1 }
        assertTrue(call.spoken().isEmpty())
        call.userSays("Oh and", from = 3_000)
        call.advance(30)
        call.socket.stallFirstCommentary = false
        call.join(answering)
        for (index in 1..6) {
            call.advance(80)
            call.userSays(" also", from = 3_000 + index * 80)
            assertFalse("Never over the user (${index * 80} ms)", call.spoken().any { it.contains("roly-poly") })
        }
        call.advance(149)
        assertFalse("Still within the user's pause", call.spoken().any { it.contains("roly-poly") })
        call.advance(1)
        call.waitFor { call.spoken().any { it.contains("roly-poly") } }
        assertEquals(listOf(result, answer), call.spoken())
        call.coordinator.end()
    }

    /**
     * The delegator's answer cancels only results it covered: a notice that
     * also answers other requests still speaks after it.
     */
    @Test fun theDelegatorCancelsOnlyTheResultsItAnswered() = runTest {
        val call = idleCall()
        call.userSays("When does Karma Cafe close?", from = 1_000, to = 1_600)
        call.delegates("del_cafe", at = 1_600)
        call.advance(50)
        val briefing = "This one reply answers 2 earlier requests: … Its reply: Inbox: two X DMs need replies."
        val alone = "The session “HOME” finished the work you started there. Its reply: Karma Cafe closed at 4."
        call.coordinator.announce(briefing, sessionID = "ses_home", promptIDs = listOf("msg_inbox", "msg_cafe"))
        call.coordinator.announce(alone, sessionID = "ses_home", promptIDs = listOf("msg_cafe"))
        call.coordinator.cancelPendingAnnouncements(promptIDs = listOf("msg_cafe"))
        call.coordinator.completeFunction(callID = "del_cafe", output = "[COMPLETE] Karma Cafe closed at 4 today.")
        // The answer after the user's pause, the briefing once it has started.
        call.advance(100)
        call.waitFor { call.spoken().isNotEmpty() }
        call.advance(400)
        call.waitFor { briefing in call.spoken() }
        val order = call.spoken()
        assertFalse("The part the delegator said isn't said twice", alone in order)
        val answer = order.indexOfFirst { it.contains("Karma Cafe closed at 4 today.") }
        assertTrue(answer >= 0)
        assertTrue(answer < order.indexOf(briefing))
        call.coordinator.end()
    }

    /** A read covering every prompt in the session cancels a combined notice. */
    @Test fun aReadCoveringTheWholeRunCancelsItsCombinedNotice() = runTest {
        val call = idleCall()
        call.userSays("What did HOME say?", from = 1_000, to = 1_600)
        call.delegates("del_read", at = 1_600)
        call.advance(50)
        val combined = "This one reply answers 2 earlier requests: … Its reply: two DMs need replies."
        call.coordinator.announce(combined, sessionID = "ses_home", promptIDs = listOf("msg_inbox", "msg_dms"))
        call.coordinator.cancelPendingAnnouncements(promptIDs = listOf("msg_inbox", "msg_dms", "msg_narrow"))
        call.coordinator.completeFunction(callID = "del_read", output = "[COMPLETE] HOME says two DMs need replies.")
        call.advance(100)
        call.waitFor { call.spoken().any { it.contains("HOME says two DMs need replies.") } }
        call.advance(900)
        assertFalse("Said once, by the delegator", combined in call.spoken())
        call.coordinator.end()
    }

    /**
     * Results released with the delegator's answer never go out on top of it:
     * they give the voice agent a moment (`liveAnswerStartWait`) to start
     * speaking it, then wait while it does.
     */
    @Test fun aResultReleasedWithTheDelegatorsAnswerWaitsForItToBeSpoken() = runTest {
        val call = idleCall()
        call.userSays("When does Karma Cafe close?", from = 1_000, to = 1_600)
        call.delegates("del_cafe", at = 1_600)
        call.advance(900)
        val briefing = "This one reply answers 3 earlier requests: … Its reply: Inbox: two X DMs need replies."
        call.coordinator.completeFunction(callID = "del_cafe", output = "[COMPLETE] Karma Cafe closed at 4 today.")
        call.coordinator.announce(briefing, sessionID = "ses_home", promptIDs = listOf("msg_inbox"))
        call.waitFor { call.spoken().any { it.contains("Karma Cafe closed at 4 today.") } }
        call.advance(399)
        assertFalse("Not over the answer the voice agent is about to speak", briefing in call.spoken())
        call.advance(1)
        call.waitFor { briefing in call.spoken() }
        call.coordinator.end()
    }

    /**
     * The user asks for the rules, then "Wait, don't you have them?" while the delegator reads
     * them: its answer is ready 1.4 s after they stop, held for the voice agent's reply.
     */
    private suspend fun kotlinx.coroutines.test.TestScope.pressedCall(pressedHold: Duration): VoiceClockedCall = clockedCall(
        VoiceCallTiming(
            rotateAfter = 3_000.0,
            forceRotateAfter = 3_500.0,
            retryDelays = listOf(0.01),
            liveDelegationSettle = Duration.ZERO,
            liveTranscriptSettle = 40.milliseconds,
            liveAcknowledgementWait = Duration.ZERO,
        ).copy(
            liveAppendQuiet = 150.milliseconds,
            liveAppendReplyWait = 1_500.milliseconds,
            liveAnswerPressedHold = pressedHold,
            liveAnswerPressedAfter = 300.milliseconds,
        ),
    )

    @Test fun theAnswerTheUserIsWaitingOnGoesOutBeforeTheVoiceAgentFillsTheGap() = runTest {
        val call = pressedCall(pressedHold = 600.milliseconds)
        call.userSays("Okay, and what are the rules to the game", from = 170_200, to = 171_260)
        call.delegates("del_rules", at = 171_260)
        call.agentSpeaks("Let me check.", from = 172_540, to = 173_000)
        call.advance(100)
        call.playbackDrains()
        call.advance(500)
        call.userSays("Wait, wait. Don't you have them? Didn't it tell you them already", from = 176_100, to = 178_740)
        call.advance(250)
        // The delegator's fresh answer lands before the voice agent's reply starts.
        answers(call)
        call.waitFor { call.spoken().isNotEmpty() }
        assertEquals("Not held for the 1.5 s reply window: it is the reply", listOf(answer), call.spoken())
        assertTrue(call.socket.commands.any { it is LiveCommand.AppendCommentary && it.delegationID == "del_rules" })
        call.coordinator.end()
    }

    @Test fun anAnswerTheUserPressedForStopsTheVoiceAgentTalkingWithoutIt() = runTest {
        val call = pressedCall(pressedHold = 600.milliseconds)
        call.userSays("Okay, and what are the rules to the game", from = 170_200, to = 171_260)
        call.delegates("del_rules", at = 171_260)
        call.advance(500)
        call.userSays("Don't you have them?", from = 176_100, to = 178_740)
        call.advance(200)
        // The voice agent answers from an older result while the delegator works.
        call.agentSpeaks("I don't have them, what I got back was cut off", from = 180_160, to = 183_000)
        call.advance(50)
        answers(call)
        assertTrue("Briefly held: never cut a sentence off at once", call.spoken().isEmpty())
        call.advance(599)
        assertTrue(call.spoken().isEmpty())
        call.advance(1)
        call.waitFor { call.spoken().isNotEmpty() }
        assertEquals("Out while the voice agent still talks without it, not after it", listOf(answer), call.spoken())
        call.coordinator.end()
    }

    /**
     * Without the user asking again, the voice agent's speech holds the
     * answer as before: an answer never cuts off a sentence it didn't need to.
     */
    @Test fun anAnswerNobodyPressedForWaitsForTheVoiceAgentToFinish() = runTest {
        val call = pressedCall(pressedHold = 300.milliseconds)
        call.userSays("What are the rules?", from = 170_200, to = 171_260)
        call.delegates("del_rules", at = 171_260)
        call.advance(400)
        // A gasp the transcript tags is not the user asking again.
        call.userSays("[gasp", from = 171_700, to = 171_800)
        call.agentSpeaks("Let me look into that, and meanwhile here's a long aside", from = 172_000, to = 175_000)
        call.advance(100)
        answers(call)
        call.advance(700)
        assertTrue("Held while the voice agent speaks", call.spoken().isEmpty())
        call.playbackDrains()
        call.advance(149)
        assertTrue("And the pause after it", call.spoken().isEmpty())
        call.advance(1)
        call.waitFor { call.spoken().isNotEmpty() }
        assertEquals(listOf(answer), call.spoken())
        call.coordinator.end()
    }
}
