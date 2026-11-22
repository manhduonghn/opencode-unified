package ai.opencode.feature.voice

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * After four quick switches the voice agent didn't answer him (“And now you're not responding to
 * me”). The switches' context went out together 1.5 s after he stopped talking, and any append cuts
 * GPT-Live's reply off. Now they become one append, sent after the reply.
 */
class VoiceContextHoldTests {
    @Test fun contextWaitsForTheReplyTheUserIsOwed() {
        var gate = ReplyWatch.QuietGate(quietWindow = 900.milliseconds, replyWait = 1_500.milliseconds, maxHold = 5.seconds)
        val start = 1_000.seconds
        gate = gate.noteUserSpoke(start)
        val later = start + 2.seconds
        assertNull("Past the reply wait, a result goes out", gate.delay(later))
        assertEquals("Context waits for the reply", 6.seconds, gate.contextDelay(later, replyHold = 8.seconds))
        gate = gate.noteAssistantSpeaking(true, start + 3.seconds)
        gate = gate.noteAssistantSpeaking(false, start + 4.seconds)
        assertNull("Replied: quiet again", gate.contextDelay(start + 4.seconds + 900.milliseconds, replyHold = 8.seconds))
        assertNull(gate.contextDelay(start + 20.seconds, replyHold = 8.seconds))
    }

    @Test fun quickSwitchesSendOnlyTheLatestContextAfterTheReply() = runTest {
        val timing = VoiceCallTiming(
            rotateAfter = 3_000.0,
            forceRotateAfter = 3_500.0,
            retryDelays = listOf(0.01),
            liveDelegationSettle = Duration.ZERO,
            liveTranscriptSettle = 40.milliseconds,
            liveAcknowledgementWait = Duration.ZERO,
        ).copy(
            liveAppendQuiet = 200.milliseconds,
            liveAppendReplyWait = 300.milliseconds,
            liveAppendMaxHold = 5.seconds,
            liveContextReplyHold = 4.seconds,
        )
        val call = clockedCall(timing)
        val socket = call.socket
        val context: () -> List<String> = {
            socket.commands.mapNotNull { (it as? LiveCommand.AppendInstructions)?.content }
        }

        call.userSays("Why does it say now talking in SEER?", from = 1_000, to = 2_000)
        for (title in listOf("SEER", "HOME", "SEER", "OPENCODE IOS")) {
            call.coordinator.retarget()
            call.coordinator.updateInstructions(full = "Full $title.", delta = "${VoiceInstructions.retargetPrefix} Attached to $title.")
        }
        assertEquals(3, call.harness.callLogMarkers.filter { it.startsWith("Dropped 1 held context update") }.size)
        call.advance(700)
        assertTrue("Past the reply wait, the reply is still owed: nothing cuts it off", context().isEmpty())

        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Fixing that now.", startMilliseconds = 2_800, endMilliseconds = 3_400))
        val replied = now
        call.until { call.coordinator.serialized { call.coordinator.quietGate.lastAssistantSpeechAt } == replied }
        call.advance(199)
        assertTrue("Never over the reply", context().isEmpty())
        call.advance(1)
        call.waitFor { context().isNotEmpty() }
        assertEquals("Sent a quiet window after the reply, not at the limit", listOf("${VoiceInstructions.retargetPrefix} Attached to OPENCODE IOS."), context())
        call.coordinator.end()
    }

    @Test fun announcementDoesNotForceFlushHeldContextDuringUserReplyHold() = runTest {
        val timing = VoiceCallTiming(
            rotateAfter = 3_000.0,
            forceRotateAfter = 3_500.0,
            retryDelays = listOf(0.01),
            liveDelegationSettle = Duration.ZERO,
            liveTranscriptSettle = 40.milliseconds,
            liveAcknowledgementWait = Duration.ZERO,
        ).copy(
            liveAppendQuiet = 200.milliseconds,
            liveAppendReplyWait = 300.milliseconds,
            liveContextReplyHold = 8.seconds,
        )
        val call = clockedCall(timing)
        val socket = call.socket
        val context: () -> List<String> = {
            socket.commands.mapNotNull { (it as? LiveCommand.AppendInstructions)?.content }
        }

        call.userSays("What's left on the checklist?", from = 1_000, to = 1_600)
        call.coordinator.retarget()
        call.coordinator.updateInstructions(full = "Full.", delta = "${VoiceInstructions.retargetPrefix} Attached to SEER.")
        call.advance(400)
        assertTrue(context().isEmpty())

        // An announcement arrives past `liveAppendReplyWait` (300 ms) while `.context` is still inside its 8 s reply hold.
        call.coordinator.announce("The session “Upload” finished: All green.", sessionID = "ses_upload")
        call.waitFor { call.spoken() == listOf("The session “Upload” finished: All green.") }
        assertTrue("An announcement must never force-flush held context ahead of liveContextReplyHold", context().isEmpty())

        // Context flushes only once its 8 s reply hold expires (7,600 ms remaining).
        call.advance(7_599)
        assertTrue(context().isEmpty())
        call.advance(1)
        call.waitFor { context() == listOf("${VoiceInstructions.retargetPrefix} Attached to SEER.") }
        call.coordinator.end()
    }
}
