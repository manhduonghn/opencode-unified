package ai.opencode.feature.voice

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How long a result waits so it doesn't cut anyone off, on a test clock: a loaded machine
 * stretching a pause can't change what the call does.
 */
class VoiceResultHoldTests {
    private val result = "The session “Upload” finished the work you started there. Its reply: All tests pass."

    /**
     * Measured against gpt-live-1: any append stops it mid-sentence, so
     * appends wait for a quiet moment, and for a reply the user is owed.
     */
    @Test fun appendsWaitUntilNeitherSideIsSpeaking() = runTest {
        val call = clockedCall(
            VoiceCallTiming(
                rotateAfter = 3_000.0,
                forceRotateAfter = 3_500.0,
                retryDelays = listOf(0.01),
                liveDelegationSettle = Duration.ZERO,
                liveTranscriptSettle = 40.milliseconds,
                liveAcknowledgementWait = Duration.ZERO,
            ).copy(
                liveAppendQuiet = 250.milliseconds,
                liveAppendReplyWait = 600.milliseconds,
            ),
        )
        val iterator = call.events
        val socket = call.socket
        val appends = { socket.appendContents() }

        // The assistant is talking: context and news are held, in order.
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Once upon a time", startMilliseconds = 100, endMilliseconds = 400))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "Once upon a time"), iterator.next())
        call.coordinator.updateInstructions(full = "Full.", delta = "Context for session B.")
        call.coordinator.announce("Session B finished.")
        call.advance(249)
        assertTrue("Nothing may interrupt the assistant mid-sentence", appends().isEmpty())
        call.advance(1)
        call.waitFor { appends().firstOrNull() == "Context for session B." }
        // News waits a moment longer when it was held behind context.
        call.advance(200)
        call.waitFor { appends().size == 2 }
        assertEquals(listOf("Context for session B.", "Session B finished."), appends())

        // The user just spoke and the reply hasn't started: hold past the plain quiet.
        call.userSays("What changed?", from = 2_000, to = 2_400)
        call.coordinator.updateInstructions(full = "Full.", delta = "Context for session C.")
        call.advance(599)
        assertEquals("A reply is owed; context waits for it", 2, appends().size)
        call.advance(1)
        call.waitFor { appends().size == 3 }
        assertEquals("Context for session C.", appends().last())

        // Each delivery is a call-log marker with how long it was held; a held
        // notice has its own "Sent held notice" line instead.
        val marks = call.harness.callLogMarkers
        assertEquals(2, marks.size)
        assertTrue(marks.all { it.startsWith("Sent instructions to the voice agent") })
        assertTrue(marks.firstOrNull()?.startsWith("Sent instructions to the voice agent · held ") == true)
        val held = marks.firstOrNull()?.let { it.split("·")[1].split(" ").filter(String::isNotEmpty)[1].toDoubleOrNull() } ?: 0.0
        assertTrue("It waited out the assistant's sentence", held >= 0.2)
        assertTrue(marks.firstOrNull()?.contains("it last spoke") == true)

        // A held multi-chunk completion notice is suppressed whole (no orphan trailing chunks) by its prompt.
        call.userSays("Did Blog game finish?", from = 6_000, to = 6_400)
        val longSummary = "Verified another scoring rule in the test suite. ".repeat(40)
        val multiChunkNotice = "The session “Blog game” finished the work you started there. Its reply: $longSummary"
        assertTrue(LiveContentChunker.chunks(multiChunkNotice).size > 1)
        call.coordinator.announce(multiChunkNotice, sessionID = "ses_blog", promptIDs = listOf("msg_blog"))
        call.coordinator.cancelPendingAnnouncements(promptIDs = listOf("msg_blog"))
        call.advance(700)
        assertEquals("All chunks of the cancelled multi-chunk announcement must be removed", 3, appends().size)
        call.coordinator.end()
    }

    /**
     * A result that lands while the user is talking (or just said "okay,
     * thanks") waits only for the reply that's about to start, then is spoken.
     */
    @Test fun aResultLandingMidSpeechIsSpokenAtTheNextQuietMoment() = runTest {
        val call = idleCall()
        val result = "The session “Upload” finished the work you started there. Its reply: All 11 upload tests pass."
        call.userSays("Okay, thanks", from = 1_000, to = 1_400)
        call.advance(30)
        call.coordinator.announce(result, sessionID = "ses_upload")
        call.advance(269)
        assertTrue("Never over the user or right after them", call.spoken().isEmpty())
        call.advance(1)
        call.waitFor { result in call.spoken() }
        assertEquals("Out once the reply is late (300 ms), with no long quiet first", listOf(result), call.spoken())
        assertFalse(
            "Spoken, not turned into background",
            call.socket.commands.any { it is LiveCommand.AppendThinking && it.content.contains("All 11 upload tests pass") },
        )
        call.coordinator.end()
    }

    /**
     * Watched-session announcements wait behind an active foreground delegation
     * up to the 15 s silence cap, while `ios_voice_say` (`sessionID == null`)
     * still speaks at the next quiet moment.
     */
    @Test fun watchedSessionAnnouncementWaitsForActiveForegroundDelegationUpToSilenceCap() = runTest {
        val call = idleCall(answerSpeakingHold = 1.seconds)
        val result = "The session “Upload” finished the work you started there. Its reply: Done — fixed the retry loop."
        call.userSays("Wait, check the docs instead", from = 1_000, to = 1_600)
        call.delegates("del_docs", at = 1_600)
        call.advance(50)
        call.coordinator.announce(result, sessionID = "ses_upload")
        call.coordinator.announce("Say briefly that you're checking docs.")
        call.advance(250)
        call.waitFor { call.spoken() == listOf("Say briefly that you're checking docs.") }
        call.advance(5_000)
        assertFalse("Watched-session notice stays held behind the active foreground delegation", result in call.spoken())

        call.coordinator.completeFunction(callID = "del_docs", output = "[COMPLETE] The docs are up to date.")
        call.waitFor { "The docs are up to date." in call.spoken() }
        assertEquals(
            "Foreground answer speaks ahead of the background notice",
            listOf("Say briefly that you're checking docs.", "The docs are up to date."),
            call.spoken(),
        )
        call.advance(1_050)
        call.waitFor { result in call.spoken() }
        assertEquals(result, call.spoken().last())

        // A silent multi-minute delegation still releases urgent background news at the 15 s silence cap.
        call.userSays("Run the full build", from = 8_000, to = 8_400)
        call.delegates("del_long", at = 8_400)
        val secondNotice = "The session “Deploy” finished: Canary is live."
        call.coordinator.announce(secondNotice, sessionID = "ses_deploy")
        call.advance(14_999)
        assertFalse(secondNotice in call.spoken())
        call.advance(1)
        call.waitFor { secondNotice in call.spoken() }
        call.coordinator.end()
    }

    /**
     * The voice agent talking on and on holds a result only so long, then it
     * goes out even though that cuts the speech off.
     */
    @Test fun continuousSpeechHoldsAResultOnlyUpToTheLimit() = runTest {
        val call = idleCall(resultMaxHold = 600.milliseconds)
        call.agentSpeaks("And another thing about the weather", from = 1_000, to = 9_000)
        call.advance(100)
        call.coordinator.announce(result, sessionID = "ses_upload")
        call.advance(300)
        assertTrue("Held while the voice agent is mid-sentence", call.spoken().isEmpty())
        call.advance(299)
        assertTrue(call.spoken().isEmpty())
        call.advance(1)
        call.waitFor { result in call.spoken() }
        assertEquals("Out at the limit, still speaking", listOf(result), call.spoken())
        call.coordinator.end()
    }

    /**
     * Once the answer has been spoken, later speech isn't taken for it: a
     * result waits on that speech only the usual limit.
     */
    @Test fun speechAfterTheAnswerHoldsAResultOnlyTheUsualLimit() = runTest {
        val call = idleCall(resultMaxHold = 500.milliseconds, answerSpeakingHold = 5.seconds)
        val answer = LiveCallCoordinatorTests.rulesAnswer
        call.userSays("What are the rules?", from = 500, to = 900)
        call.delegates("del_rules", at = 900)
        call.advance(500)
        call.coordinator.completeFunction(callID = "del_rules", output = "[COMPLETE] $answer")
        call.waitFor { call.spoken().isNotEmpty() }
        assertEquals(listOf(answer), call.spoken())
        // The answer is spoken and ends.
        call.agentSpeaks(from = 2_000)
        call.advance(100)
        call.playbackDrains()
        call.advance(400)
        // Then the voice agent talks about something else, on and on.
        call.agentSpeaks(from = 4_000)
        call.advance(50)
        call.coordinator.announce(result, sessionID = "ses_upload")
        call.advance(499)
        assertFalse("Held while it talks", result in call.spoken())
        call.advance(1)
        call.waitFor { result in call.spoken() }
        assertEquals("The usual limit, not the answer's", listOf(answer, result), call.spoken())
        call.coordinator.end()
    }

    /**
     * The limit only ever cuts the voice agent off: while the user is still
     * talking, a result waits, however long it has been held.
     */
    @Test fun theUserSpeakingHoldsAResultPastTheLimit() = runTest {
        val call = idleCall(resultMaxHold = 300.milliseconds)
        call.userSays("So", from = 1_000)
        call.advance(30)
        call.coordinator.announce(result, sessionID = "ses_upload")
        for (index in 1..10) {
            call.advance(80)
            assertFalse("Held while the user talks (${index * 80} ms)", result in call.spoken())
            call.userSays(" and", from = 1_000 + index * 80)
        }
        call.advance(149)
        assertFalse("Still within the user's pause", result in call.spoken())
        call.advance(1)
        call.waitFor { result in call.spoken() }
        assertTrue("Out once they pause", result in call.spoken())
        call.coordinator.end()
    }

    /**
     * The live call's turn-taking, on a test clock: no caps, a 2 s quiet gap
     * after the user, the microphone read, talking over stops the voice agent.
     */
    private suspend fun TestScope.turnTaking(): VoiceClockedCall = clockedCall(
        VoiceCallTiming(
            rotateAfter = 3_000.0,
            forceRotateAfter = 3_500.0,
            retryDelays = listOf(0.01),
            liveDelegationSettle = Duration.ZERO,
            liveTranscriptSettle = 40.milliseconds,
            liveAcknowledgementWait = Duration.ZERO,
        ).copy(
            liveAppendQuiet = 900.milliseconds,
            liveAppendReplyWait = 1_500.milliseconds,
            liveAppendMaxHold = Duration.ZERO,
            liveResultMaxHold = Duration.ZERO,
            liveUserQuietGap = 2.seconds,
            liveMicVoiceLevel = 0.35f,
            liveBargeIn = 600.milliseconds,
        ),
    )

    /**
     * A result arrived 2.5 s into a long dictation, was held 450 ms past each transcript fragment
     * and at most 5 s, went out 1.5 s into a pause, and the voice agent talked over him for 9 s.
     * Now it waits until he has been quiet for 2 s, however long he talks, through pauses the
     * transcript has no words for.
     */
    @Test fun aResultDuringDictationWaitsUntilTheUserHasFinished() = runTest {
        val call = turnTaking()
        // Fragments every 1.2 s, with a 2.6 s gap the microphone still hears
        // him talk through; 20 s of dictation, four times the old cap.
        val fragments = (0..8_400 step 1_200).toSet() + (11_000..20_000 step 1_200).toSet()
        var lastHeard = 0
        for (at in 0..20_000 step 200) {
            if (at > 0) call.advance(200)
            if (at in fragments) {
                call.userSays(" and then", from = at)
                lastHeard = at
            }
            if (at in 8_400..11_000) {
                call.micHearsUser()
                lastHeard = at
            }
            if (at == 2_600) call.coordinator.announce(result, sessionID = "ses_upload")
            assertFalse("Held while the user dictates ($at ms)", result in call.spoken())
        }
        assertEquals(19_400, lastHeard)
        call.advance(1_399)
        assertFalse("Held through the 2 s quiet gap", result in call.spoken())
        call.advance(1)
        call.waitFor { result in call.spoken() }
        call.coordinator.end()
    }

    /**
     * No cap releases a result while the user talks, and the voice agent's
     * own speech holds it until that has finished too.
     */
    @Test fun noCapReleasesAResult() = runTest {
        val call = turnTaking()
        call.agentSpeaks("Let me check on that.", from = 0)
        call.coordinator.announce(result, sessionID = "ses_upload")
        for (frame in 1..80) {
            call.advance(100)
            call.agentSpeaks(from = frame * 100)
        }
        assertFalse("Held 8 s while the voice agent speaks", result in call.spoken())
        call.playbackDrains()
        call.advance(500)
        call.userSays("Wait, also", from = 9_000)
        for (index in 1..20) {
            call.advance(1_000)
            assertFalse("Held while the user talks ($index s)", result in call.spoken())
            call.userSays(" and", from = 9_000 + index * 1_000)
        }
        call.advance(1_999)
        assertFalse(result in call.spoken())
        call.advance(1)
        call.waitFor { result in call.spoken() }
        call.coordinator.end()
    }

    /**
     * The user talking over the voice agent, words and voice, stops its
     * audio; the rest of what it says is dropped until its stream goes quiet.
     */
    @Test fun talkingOverTheVoiceAgentStopsIt() = runTest {
        val call = turnTaking()
        val coordinator = call.coordinator
        call.agentSpeaks("The session Upload finished", from = 0)
        for (at in 100..400 step 100) {
            call.advance(100)
            call.agentSpeaks(from = at)
            if (at >= 200) call.micHearsUser()
        }
        call.userSays("Stop, wait", from = 300)
        assertEquals("Not yet: 200 ms of the user's voice", 0, call.harness.flushes)
        for (at in 500..800 step 100) {
            call.advance(100)
            if (coordinator.serialized { coordinator.droppingAssistantSpeech }) break
            call.agentSpeaks(from = at)
            call.micHearsUser()
        }
        call.until { coordinator.serialized { coordinator.droppingAssistantSpeech } }
        assertEquals(1, call.harness.flushes)
        assertTrue("Stopped the voice agent: the user talked over it" in call.harness.callLogMarkers)
        assertFalse(coordinator.serialized { coordinator.quietGate.assistantSpeaking })

        // It talks on: none of that plays.
        val enqueued = call.harness.enqueues
        call.socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = " and all tests pass", startMilliseconds = 900, endMilliseconds = 1_100))
        call.socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = LiveCallCoordinatorTests.speechPCM, startMilliseconds = 900, endMilliseconds = 1_000))
        call.advance(100)
        call.userSays(" I meant", from = 900)
        assertEquals(enqueued, call.harness.enqueues)
        assertFalse(coordinator.serialized { coordinator.quietGate.assistantSpeaking })

        // Half a second of quiet in its stream, and it's heard again.
        val silence = ByteArray(4_800)
        repeat(5) { call.socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = silence, startMilliseconds = null, endMilliseconds = null)) }
        call.until { !coordinator.serialized { coordinator.droppingAssistantSpeech } }
        call.advance(100)
        call.agentSpeaks("Sure.", from = 2_000)
        assertEquals(enqueued + 1, call.harness.enqueues)
        call.coordinator.end()
    }

    /** Without words, a noise on the microphone holds nothing. */
    @Test fun aNoiseWithoutWordsHoldsNothing() = runTest {
        val call = turnTaking()
        call.micHearsUser()
        call.coordinator.announce(result, sessionID = "ses_upload")
        call.waitFor { result in call.spoken() }
        call.coordinator.end()
    }
}
