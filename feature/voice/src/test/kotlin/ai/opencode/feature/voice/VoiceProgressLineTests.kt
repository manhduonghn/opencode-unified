package ai.opencode.feature.voice

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The spoken "still working" line while a delegation is out, on a test clock. I've got that.", then
 * about 8 s of tool work with nothing said.
 */
class VoiceProgressLineTests {
    private companion object {
        val timing = VoiceProgressLine.Timing(firstAfter = 5.seconds, quiet = 3.seconds, again = 20.seconds)
        const val subagent = "working on “Buy Blood domains”"
    }

    private data class Line(val delegationID: String?, val content: String)

    private fun lines(call: VoiceClockedCall): List<Line> = call.socket.commands.mapNotNull {
        (it as? LiveCommand.AppendCommentary)?.let { c -> Line(delegationID = c.delegationID, content = c.content) }
    }

    /** Progress lines sent, however they went on the wire. */
    private fun statusLines(call: VoiceClockedCall): List<String> = call.socket.commands.mapNotNull {
        val content = when (it) {
            is LiveCommand.AppendCommentary -> it.content
            is LiveCommand.AppendInstructions -> it.content
            else -> null
        }
        content?.takeIf { text -> text.startsWith("Status update, not the result") }
    }

    private suspend fun asked(call: VoiceClockedCall) {
        call.userSays("Buy both domains and make the site", from = 0, to = 400)
        call.delegates("del_1", at = 400)
    }

    private suspend fun progressLines(call: VoiceClockedCall) = call.coordinator.serialized { call.coordinator.progressLines.toMap() }

    @Test fun speaksOnceAfterTheThresholdThenOnceMoreMuchLater() = runTest {
        val call = idleCall(progress = timing)
        asked(call)
        call.coordinator.noteProgressActivity(
            callID = "del_1",
            activity = VoiceProgressLine.activity(caption = "Starting subagent · Buy Blood domains"),
        )
        call.advance(4_999)
        assertTrue(lines(call).isEmpty())
        call.advance(1)
        call.waitFor { lines(call).size == 1 }
        // Bound to the delegation, as the Live docs' "still working" update.
        assertEquals(listOf(Line(delegationID = "del_1", content = VoiceProgressLine.instruction(activity = subagent))), lines(call))
        call.advance(19_999)
        assertEquals("Once, then only after a much longer wait", 1, lines(call).size)
        call.advance(1)
        call.waitFor { lines(call).size == 2 }
        call.advance(120_000)
        assertEquals("Never a third", 2, lines(call).size)
        call.coordinator.end()
    }

    @Test fun neverWhileTheUserIsTalking() = runTest {
        val call = idleCall(progress = timing)
        asked(call)
        call.advance(4_000)
        call.userSays("and a logo", from = 4_000, to = 4_300)
        call.advance(2_000)
        assertTrue("Due at 5 s, but the user spoke at 4 s", lines(call).isEmpty())
        call.userSays("with a dark theme", from = 6_000, to = 6_300)
        call.advance(2_999)
        assertTrue("Still within the quiet after their words", lines(call).isEmpty())
        call.advance(1)
        call.waitFor { lines(call).size == 1 }
        assertEquals(listOf(Line(delegationID = "del_1", content = VoiceProgressLine.instruction(activity = null))), lines(call))
        call.coordinator.end()
    }

    /** The acknowledgement (or anything the voice agent says) restarts the quiet. */
    @Test fun waitsForQuietAfterTheVoiceAgentSpeaks() = runTest {
        val call = idleCall(progress = timing)
        asked(call)
        call.agentSpeaks("Listening, buying both now.", from = 500, to = 2_500)
        call.advance(2_500)
        call.playbackDrains()
        call.advance(2_999)
        assertTrue(lines(call).isEmpty())
        call.advance(1)
        call.waitFor { lines(call).size == 1 }
        call.coordinator.end()
    }

    @Test fun theResultLandingCancelsIt() = runTest {
        val call = idleCall(progress = timing)
        asked(call)
        call.advance(4_000)
        call.coordinator.completeFunction(callID = "del_1", output = "[COMPLETE] Bought both domains.")
        call.waitFor { call.spoken().isNotEmpty() }
        assertTrue(progressLines(call).isEmpty())
        call.advance(60_000)
        assertEquals(listOf("Bought both domains."), call.spoken())
        call.coordinator.end()
    }

    @Test fun droppedWorkCancelsIt() = runTest {
        val call = idleCall(progress = timing)
        asked(call)
        call.coordinator.closeDelegation(callID = "del_1", reason = VoiceDelegationCloseReason.superseded)
        call.advance(60_000)
        assertTrue(lines(call).isEmpty())
        assertTrue(call.coordinator.serialized { call.coordinator.progressTasks.isEmpty() })
        call.coordinator.end()
    }

    /**
     * A line never joins a queue: due while a notice is held, it waits on its
     * own timer, and the result landing meanwhile means it never speaks.
     */
    @Test fun aLineWaitingOnTheChannelNeverSpeaksAfterTheResult() = runTest {
        val call = idleCall(progress = timing)
        asked(call)
        call.advance(4_900)
        call.coordinator.holdNoticeForTest("Opened “Upload fixes”.")
        call.advance(600)
        assertEquals("The line didn't queue behind the notice", 1, call.coordinator.serialized { call.coordinator.outbound.count })
        assertEquals(0, progressLines(call)["del_1"]?.spoken)
        call.coordinator.releaseHeldForTest()
        call.coordinator.completeFunction(callID = "del_1", output = "[COMPLETE] Bought both domains.")
        call.waitFor { call.spoken().isNotEmpty() }
        call.advance(60_000)
        assertEquals(listOf("Bought both domains."), call.spoken())
        call.coordinator.end()
    }

    /** Held up, it counts nothing and later says what's running then. */
    @Test fun aLineHeldUpIsRebuiltFreshWithoutCounting() = runTest {
        val call = idleCall(progress = timing)
        asked(call)
        call.coordinator.noteProgressActivity(callID = "del_1", activity = "reading files")
        call.advance(4_900)
        call.coordinator.holdNoticeForTest("Opened “Upload fixes”.")
        call.advance(1_100)
        assertTrue(statusLines(call).isEmpty())
        assertEquals(0, progressLines(call)["del_1"]?.spoken)
        call.coordinator.noteProgressActivity(callID = "del_1", activity = "running a command")
        call.coordinator.releaseHeldForTest()
        call.advance(250)
        call.waitFor { statusLines(call).size == 1 }
        assertEquals(listOf(VoiceProgressLine.instruction(activity = "running a command")), statusLines(call))
        assertEquals(1, progressLines(call)["del_1"]?.spoken)
        call.coordinator.end()
    }

    /** A notice or result just sent is speech about to start. */
    @Test fun aCommentarySentPushesTheLineBack() = runTest {
        val call = idleCall(progress = timing)
        asked(call)
        call.advance(4_000)
        call.coordinator.announce("Opened “Upload fixes”.")
        call.waitFor { call.spoken().size == 1 }
        call.advance(2_999)
        assertTrue("Due at 5 s, but a notice went out at 4 s", statusLines(call).isEmpty())
        call.advance(1)
        call.waitFor { statusLines(call).size == 1 }
        call.coordinator.end()
    }

    /** Two delegations' lines are never spoken back to back. */
    @Test fun twoDelegationsLinesKeepTheQuietBetweenThem() = runTest {
        val call = idleCall(progress = timing)
        asked(call)
        call.advance(1_000)
        call.userSays("and a logo", from = 1_000, to = 1_300)
        call.delegates("del_2", at = 1_300)
        call.advance(4_000)
        call.waitFor { statusLines(call).size == 1 }
        assertEquals("del_1", lines(call).lastOrNull()?.delegationID)
        call.advance(2_999)
        assertEquals("del_2 was due at 6 s, but del_1's line went at 5 s", 1, statusLines(call).size)
        call.advance(1)
        call.waitFor { statusLines(call).size == 2 }
        assertEquals("del_2", lines(call).lastOrNull()?.delegationID)
        call.coordinator.end()
    }

    /** Server Voice: the backend's final round is the answer, so no line follows. */
    @Test fun serverVoicesFinalRoundStopsTheLine() = runTest {
        val backend = VoiceDelegator.responsesDelegation(context = "Viewing session \"Upload\".")
        val call = idleCall(progress = timing, delegation = LiveDelegation.responses(backend))
        call.userSays("Which projects do I have?", from = 0, to = 400)
        call.socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_1", target = "responses", offsetMilliseconds = 400))
        call.until { progressLines(call)["del_1"] != null }
        call.advance(4_000)
        call.socket.yieldEvent(LiveServerEvent.ResponseEvent("del_1", LiveResponseEvent.Created(responseID = "resp_1")))
        call.socket.yieldEvent(LiveServerEvent.ResponseEvent("del_1", LiveResponseEvent.Message("You have one project.")))
        call.socket.yieldEvent(
            LiveServerEvent.ResponseEvent("del_1", LiveResponseEvent.Finished(responseID = "resp_1", status = "completed", error = null)),
        )
        val answer = call.coordinator.delegatorRound(delegationID = "del_1")
        assertEquals(Result.success(VoiceDelegator.Response(id = "resp_1", calls = emptyList(), text = "You have one project.")), answer)
        assertNull(progressLines(call)["del_1"])
        call.advance(60_000)
        assertTrue(statusLines(call).isEmpty())
        call.coordinator.end()
    }

    @Test fun endingAServerDelegationStopsTheLine() = runTest {
        val backend = VoiceDelegator.responsesDelegation(context = "Viewing session \"Upload\".")
        val call = idleCall(progress = timing, delegation = LiveDelegation.responses(backend))
        call.userSays("Which projects do I have?", from = 0, to = 400)
        call.socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_1", target = "responses", offsetMilliseconds = 400))
        call.until { progressLines(call)["del_1"] != null }
        call.advance(4_000)
        call.coordinator.serialized { call.coordinator.endServerDelegation("del_1", "The call ended the delegation.") }
        assertTrue(progressLines(call).isEmpty())
        call.advance(60_000)
        assertTrue(statusLines(call).isEmpty())
        call.coordinator.end()
    }

    @Test fun offWithoutTiming() = runTest {
        val call = idleCall()
        asked(call)
        call.advance(60_000)
        assertTrue(lines(call).isEmpty())
        call.coordinator.end()
    }

    @Test fun dueWaitsForTheThresholdAndForQuiet() {
        val start = 1_000.seconds
        var tracker = VoiceProgressLine.Tracker(delegatedAt = start)
        assertEquals(start + 5.seconds, VoiceProgressLine.due(tracker, lastSpeechAt = null, timing = timing))
        assertEquals(start + 7.seconds, VoiceProgressLine.due(tracker, lastSpeechAt = start + 4.seconds, timing = timing))
        assertNull("Off", VoiceProgressLine.due(tracker, lastSpeechAt = null, timing = VoiceProgressLine.Timing()))
        tracker = tracker.copy(spoken = 1, lastLineAt = start + 6.seconds)
        assertEquals(start + 26.seconds, VoiceProgressLine.due(tracker, lastSpeechAt = start + 7.seconds, timing = timing))
        tracker = tracker.copy(spoken = 2)
        assertNull(VoiceProgressLine.due(tracker, lastSpeechAt = null, timing = timing))
    }

    @Test fun activitySaysWhatIsRunning() {
        assertEquals(subagent, VoiceProgressLine.activity(caption = "Starting subagent · Buy Blood domains"))
        assertNull(VoiceProgressLine.activity(caption = "Starting subagent"))
        assertEquals("running a command", VoiceProgressLine.activity(caption = "Running shell"))
        assertEquals("reading files", VoiceProgressLine.activity(caption = "Reading VoiceFeature.swift"))
        assertEquals("searching the code", VoiceProgressLine.activity(caption = "Searching files"))
        assertEquals("reading a web page", VoiceProgressLine.activity(caption = "Reading web page"))
        // Only listed captions are spoken: no tool name is ever read aloud.
        assertNull(VoiceProgressLine.activity(caption = "Working"))
        assertNull(VoiceProgressLine.activity(caption = "Ending call"))
        assertNull(VoiceProgressLine.activity(caption = "Running deploy prod"))
        assertNull(VoiceProgressLine.activity(caption = "Running script"))
        // Every caption `toolCaption` can give is either listed or unspoken.
        for (name in listOf("ios_voice_end_call", "mcp__acme__deploy_prod", "some-internal-tool", "skill")) {
            val caption = VoiceSessionDelegator.toolCaption(name = name, input = null)
            assertNull(name, caption?.let { VoiceProgressLine.activity(caption = it) })
        }
        assertEquals("messaging a session", VoiceProgressLine.activityForDelegatorTool("send_message"))
        assertNull(VoiceProgressLine.activityForDelegatorTool("end_call"))
        val line = VoiceProgressLine.instruction(activity = "running a command")
        assertTrue(line.contains("not the result") && line.contains("Never say it's done"))
    }
}

/** A notice held as if it would cut someone off, with no flush scheduled. */
internal suspend fun LiveCallCoordinator.holdNoticeForTest(content: String) = serialized {
    val now = clock.now()
    outbound.enqueue(
        listOf(
            PendingAppend(
                role = LiveOutboundRole.Announcement(id = null, sessionID = null, promptIDs = emptyList(), enqueuedAt = now),
                kind = LiveAppendKind.commentary,
                delegationID = null,
                content = content,
                enqueuedAt = now,
            ),
        ),
        now,
    )
}

internal suspend fun LiveCallCoordinator.releaseHeldForTest() = serialized { outbound.removeAll() }
