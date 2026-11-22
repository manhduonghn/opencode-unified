package ai.opencode.feature.voice

import ai.opencode.server.OpenCodeClientError
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The harness runs the coordinator on virtual time (`VoiceLiveTestSupport.kt`). */
class LiveCallCoordinatorTests {
    private fun obj(vararg pairs: Pair<String, RealtimeJSON>) = RealtimeJSONs.obj(*pairs)
    private fun text(value: String) = obj("text" to RealtimeJSONs.string(value))

    private suspend fun TestScope.connected(harness: LiveHarness, delegation: LiveDelegation = LiveDelegation.client): Pair<EventIterator, FakeLiveSocket> {
        val events = EventIterator(harness.start(delegation = delegation), backgroundScope)
        val socket = harness.nextOpened()
        socket.yieldEvent(LiveServerEvent.SessionStarted(id = "live_1", expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, events.next())
        return events to socket
    }

    /**
     * Server Voice: GPT-Live runs the delegator on its Responses backend. The
     * call hands each finished round to the phone, sends the tool results
     * back with `response.item.create` and continues with `response.create`,
     * and never appends with the delegation's ID (Responses rejects it).
     */
    @Test fun responsesDelegationHandsToolRoundsToThePhoneAndTakesTheirResults() = runTest {
        val harness = LiveHarness(this)
        val backend = VoiceDelegator.responsesDelegation(context = "Viewing session \"Upload\".")
        val events = EventIterator(harness.start(delegation = LiveDelegation.responses(backend)), backgroundScope)
        val socket = harness.nextOpened()
        assertEquals(LiveDelegation.responses(backend), socket.config.delegation)
        socket.yieldEvent(LiveServerEvent.SessionStarted(id = "live_1", expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, events.next())

        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Which projects do I have?", startMilliseconds = 500, endMilliseconds = 1_400))
        events.next()
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_1", target = "responses", offsetMilliseconds = 1_500))
        assertEquals(VoiceCallEvent.ServerDelegation(callID = "del_1"), events.next())

        val call = VoiceDelegator.ToolCall(callID = "call_1", name = "list_projects", arguments = "{}")
        socket.yieldEvent(LiveServerEvent.ResponseEvent("del_1", LiveResponseEvent.Created(responseID = "resp_1")))
        socket.yieldEvent(LiveServerEvent.ResponseEvent("del_1", LiveResponseEvent.FunctionCall(call)))
        socket.yieldEvent(LiveServerEvent.ResponseEvent("del_1", LiveResponseEvent.Finished(responseID = "resp_1", status = "completed", error = null)))
        val first = harness.coordinator.delegatorRound(delegationID = "del_1")
        assertEquals(Result.success(VoiceDelegator.Response(id = "resp_1", calls = listOf(call), text = "")), first)

        val accepted = harness.coordinator.submitDelegatorInput(
            delegationID = "del_1",
            input = listOf(VoiceDelegator.InputItem.ToolOutput(callID = "call_1", output = """[{"name":"opencode-ios"}]""")),
        )
        assertTrue(accepted)
        val backendCommands = socket.commands.filter { it is LiveCommand.ResponseItemCreate || it is LiveCommand.ResponseCreate }
        assertEquals(2, backendCommands.size)
        val firstCommand = backendCommands.first()
        if (firstCommand is LiveCommand.ResponseItemCreate) {
            assertEquals(VoiceDelegator.InputItem.ToolOutput(callID = "call_1", output = """[{"name":"opencode-ios"}]""").liveItem, firstCommand.item)
        } else {
            throw AssertionError("The tool result goes first")
        }
        if (backendCommands.last() !is LiveCommand.ResponseCreate) throw AssertionError("Then the backend continues")

        // An answer arriving before the phone asks for it waits for it.
        socket.yieldEvent(LiveServerEvent.ResponseEvent("del_1", LiveResponseEvent.Message("You have one project, opencode-ios.")))
        socket.yieldEvent(LiveServerEvent.ResponseEvent("del_1", LiveResponseEvent.Finished(responseID = "resp_2", status = "completed", error = null)))
        delay(50)
        val second = harness.coordinator.delegatorRound(delegationID = "del_1")
        assertEquals(Result.success(VoiceDelegator.Response(id = "resp_2", calls = emptyList(), text = "You have one project, opencode-ios.")), second)

        // GPT-Live already has the answer: completing it appends nothing.
        harness.coordinator.completeFunction(callID = "del_1", output = "")
        delay(50)
        assertFalse(socket.commands.any {
            when (it) {
                is LiveCommand.AppendCommentary -> it.delegationID != null
                is LiveCommand.AppendThinking -> it.delegationID != null
                is LiveCommand.AppendInstructions -> it.delegationID != null
                else -> false
            }
        })
        assertFalse(socket.commands.any { it is LiveCommand.AppendCommentary })

        // The screen moved: the backend's prompt follows, once.
        harness.coordinator.updateDelegatorInstructions("Viewing session \"Docs\".")
        harness.coordinator.updateDelegatorInstructions("Viewing session \"Docs\".")
        assertEquals(1, socket.commands.count { it is LiveCommand.UpdateDelegatorInstructions })

        // A round asked for after the call ends gives up instead of hanging.
        harness.coordinator.end()
        val late = harness.coordinator.delegatorRound(delegationID = "del_1")
        if (late.isSuccess) throw AssertionError("No rounds after the call ends")
    }

    /**
     * A Responses delegation waiting on its next round gives up when the call
     * ends, so its tool loop never hangs.
     */
    @Test fun responsesDelegationRoundFailsWhenTheCallEnds() = runTest {
        val harness = LiveHarness(this)
        val (events, socket) = connected(harness, LiveDelegation.responses(VoiceDelegator.responsesDelegation(context = "Draft.")))
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_2", target = "responses", offsetMilliseconds = 900))
        assertEquals(VoiceCallEvent.ServerDelegation(callID = "del_2"), events.next())
        val coordinator = harness.coordinator
        val waiting = async { coordinator.delegatorRound(delegationID = "del_2") }
        delay(50)
        coordinator.end()
        val round = waiting.await()
        assertEquals(OpenCodeClientError("The voice call ended."), round.exceptionOrNull())
    }

    /** A client-delegation call ignores Responses delegations it didn't ask for. */
    @Test fun clientDelegationIgnoresResponsesDelegations() = runTest {
        val harness = LiveHarness(this)
        val (events, socket) = connected(harness)
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_3", target = "responses", offsetMilliseconds = 900))
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_4", target = "client", offsetMilliseconds = 1_000))
        assertEquals(VoiceCallEvent.FunctionCall(callID = "del_4", name = "delegate", arguments = obj()), events.next())
        harness.coordinator.end()
    }

    /**
     * A result is stuck mid-send on the socket, so the slot is taken, while
     * a delegation's answer waits its turn after the user's request.
     */
    private suspend fun TestScope.answerWaitingForTheSlot(): Pair<VoiceClockedCall, Job> {
        val call = idleCall(resultMaxHold = 200.milliseconds, answerSpeakingHold = 1.seconds)
        call.socket.stallFirstCommentary = true
        // Stalls inside the send, so it isn't awaited.
        launch { call.coordinator.announce("The session “Upload” finished the work you started there. Its reply: All tests pass.", sessionID = "ses_upload") }
        call.waitFor { call.socket.commentaryEntered == 1 }
        askedForTheRules(call)
        val answering = launch { call.coordinator.completeFunction(callID = "del_rules", output = "[COMPLETE] $rulesAnswer") }
        call.until { call.coordinator.serialized { call.coordinator.sendSlotWaiters.size } == 1 }
        assertTrue(call.spoken().isEmpty())
        return call to answering
    }

    /** The user asked for the rules, a delegation took it, and their reply wait passed. */
    private suspend fun askedForTheRules(call: VoiceClockedCall) {
        call.userSays("What are the rules?", from = 500, to = 900)
        call.delegates("del_rules", at = 900)
        call.advance(500)
    }

    /**
     * The session is replaced while the answer waits its turn: it goes to the
     * new session without its delegation, as an earlier request's result.
     */
    @Test fun anAnswerWaitingForTheSlotGoesAsAnEarlierResultWhenTheSessionIsReplaced() = runTest {
        val (call, answering) = answerWaitingForTheSlot()
        val (harness, socket) = call.harness to call.socket
        socket.yieldEvent(LiveServerEvent.SessionClosed(reason = LiveCloseReason.Expired, usageSeconds = 10))
        call.retryDelayPasses()
        call.waitFor { harness.sockets.size == 2 }
        val next = harness.sockets[1]
        next.yieldEvent(LiveServerEvent.SessionStarted(id = "live_2", expiresAt = null))
        call.settle()
        socket.stallFirstCommentary = false
        call.join(answering)
        call.waitFor { next.commentary().any { it.second.contains("roly-poly") } }
        val sent = next.commentary().firstOrNull { it.second.contains("roly-poly") } ?: throw AssertionError("Expected the answer on the new session")
        assertNull(sent.first)
        assertTrue(sent.second.startsWith("Result of an earlier request"))
        assertFalse("Never the old session's delegation", (socket.commentary() + next.commentary()).any { it.first == "del_rules" })
        harness.coordinator.end()
    }

    /**
     * A cancelled waiter gives up at once, and ending the call turns away
     * everyone still waiting: nothing they carried is sent or queued, even
     * when the send holding the slot fails afterwards.
     */
    @Test fun waitersForTheSlotGiveUpWhenCancelledOrTheCallEnds() = runTest {
        val (call, answering) = answerWaitingForTheSlot()
        val (harness, socket) = call.harness to call.socket
        val instructions = launch { harness.coordinator.updateInstructions(full = "Full.", delta = "Context for session C.") }
        val cancelled = launch { harness.coordinator.updateInstructions(full = "Full.", delta = "Cancelled context.") }
        call.settle()
        cancelled.cancel()
        call.join(cancelled)
        harness.coordinator.end()
        call.join(answering)
        call.join(instructions)
        // The send that held the slot fails now the call is over.
        socket.failOnCommentaryIndex = 0
        socket.stallFirstCommentary = false
        call.settle()
        assertFalse(socket.commands.any {
            when (it) {
                is LiveCommand.AppendCommentary -> it.content.contains("roly-poly")
                is LiveCommand.AppendInstructions -> it.content.contains("context")
                else -> false
            }
        })
        assertEquals("Nothing queued after the call ended", 0, harness.pendingAppendCount())
    }

    /**
     * The call ends while an answer holds the slot, its first chunk stuck in
     * the send. Whether that send then fails or goes through, no further
     * chunk is sent and nothing is queued.
     */
    @Test fun anAnswerMidSendStopsWhenTheCallEnds() {
        for (lateSendFails in listOf(true, false)) anAnswerMidSendStopsWhenTheCallEnds(lateSendFails)
    }

    private fun anAnswerMidSendStopsWhenTheCallEnds(lateSendFails: Boolean) = runTest {
        val call = idleCall(resultMaxHold = 200.milliseconds, answerSpeakingHold = 1.seconds)
        val (harness, socket) = call.harness to call.socket
        askedForTheRules(call)
        val longAnswer = "$rulesAnswer ".repeat(12)
        assertTrue(LiveContentChunker.commentaryChunks("[COMPLETE] $longAnswer").size > 1)
        socket.stallFirstCommentary = true
        val answering = launch { harness.coordinator.completeFunction(callID = "del_rules", output = "[COMPLETE] $longAnswer") }
        call.waitFor { socket.commentaryEntered == 1 }
        harness.coordinator.end()
        if (lateSendFails) socket.failOnCommentaryIndex = 0
        socket.stallFirstCommentary = false
        call.join(answering)
        call.settle()
        assertEquals("Only the chunk already under way (lateSendFails: $lateSendFails)", if (lateSendFails) 0 else 1, socket.commentary().size)
        assertEquals("No further chunk was even tried", 1, socket.commentaryEntered)
        assertEquals("Nothing queued after the call ended", 0, harness.pendingAppendCount())
    }

    /**
     * A replacement session attaches while an answer's first chunk is stuck
     * in the old session's send. Whether that send then goes through or
     * fails, the rest goes to the new session at once, as an earlier
     * request's result: every part once, nothing left waiting.
     */
    @Test fun anAnswerCutOffByAReplacementFinishesOnTheNewSession() {
        for (lateSendFails in listOf(false, true)) anAnswerCutOffByAReplacementFinishesOnTheNewSession(lateSendFails)
    }

    private fun anAnswerCutOffByAReplacementFinishesOnTheNewSession(lateSendFails: Boolean) = runTest {
        val call = idleCall(resultMaxHold = 200.milliseconds, answerSpeakingHold = 1.seconds)
        val (harness, socket) = call.harness to call.socket
        askedForTheRules(call)
        val output = "[COMPLETE] " + "$rulesAnswer ".repeat(12)
        val chunks = LiveContentChunker.commentaryChunks(output)
        assertTrue(chunks.size > 1)
        socket.stallFirstCommentary = true
        val answering = launch { harness.coordinator.completeFunction(callID = "del_rules", output = output) }
        call.waitFor { socket.commentaryEntered == 1 }
        socket.yieldEvent(LiveServerEvent.SessionClosed(reason = LiveCloseReason.Expired, usageSeconds = 10))
        call.retryDelayPasses()
        call.waitFor { harness.sockets.size == 2 }
        val next = harness.sockets[1]
        next.yieldEvent(LiveServerEvent.SessionStarted(id = "live_2", expiresAt = null))
        call.settle()
        if (lateSendFails) socket.failOnCommentaryIndex = 0
        socket.stallFirstCommentary = false
        call.join(answering)
        val expected = chunks.joinToString("").filter { !it.isWhitespace() }
        val prefix = LiveCallCoordinator.earlierConnectionLead.trim(' ', '\t')
        val delivered = {
            (socket.commentary() + next.commentary()).joinToString("") { it.second.replace(prefix, "") }.filter { !it.isWhitespace() }
        }
        call.waitFor { delivered().length >= expected.length }
        call.settle()
        assertEquals("Every part once, in order (lateSendFails: $lateSendFails)", expected, delivered())
        assertEquals(if (lateSendFails) 0 else 1, socket.commentary().size)
        assertTrue(next.commentary().all { it.first == null })
        assertTrue(next.commentary().firstOrNull()?.second?.startsWith(prefix) == true)
        assertEquals("Nothing stranded", 0, harness.pendingAppendCount())
        harness.coordinator.end()
    }

    /**
     * An answer held behind the voice agent's speech is flushed, and its
     * first chunk fails after a replacement session attached: the whole
     * answer goes to the new session, once, in order.
     */
    @Test fun aHeldAnswerWhoseFirstChunkFailsGoesWholeToTheNewSession() = runTest {
        val call = idleCall(resultMaxHold = 300.milliseconds, answerSpeakingHold = 1.seconds)
        val (harness, socket) = call.harness to call.socket
        askedForTheRules(call)
        call.agentSpeaks(from = 1_000)
        call.advance(50)
        val output = "[COMPLETE] " + "$rulesAnswer ".repeat(12)
        val chunks = LiveContentChunker.commentaryChunks(output)
        assertTrue("Two chunks are enough to put the end before the beginning", chunks.size > 1)
        socket.stallFirstCommentary = true
        harness.coordinator.completeFunction(callID = "del_rules", output = output)
        assertEquals("Held behind the speech", 0, socket.commentaryEntered)
        call.playbackDrains()
        call.advance(150)
        call.waitFor { socket.commentaryEntered == 1 }
        socket.yieldEvent(LiveServerEvent.SessionClosed(reason = LiveCloseReason.Expired, usageSeconds = 10))
        call.retryDelayPasses()
        call.waitFor { harness.sockets.size == 2 }
        val next = harness.sockets[1]
        next.yieldEvent(LiveServerEvent.SessionStarted(id = "live_2", expiresAt = null))
        call.settle()
        socket.failOnCommentaryIndex = 0
        socket.stallFirstCommentary = false
        val expected = chunks.joinToString("").filter { !it.isWhitespace() }
        val delivered = { next.commentary().joinToString("") { it.second }.filter { !it.isWhitespace() } }
        call.waitFor { delivered().length >= expected.length }
        call.settle()
        assertTrue(socket.commentary().isEmpty())
        assertEquals("The beginning first, every part once", expected, delivered())
        assertTrue(next.commentary().all { it.first == null })
        assertEquals(0, harness.pendingAppendCount())
        harness.coordinator.end()
    }

    @Test fun answerDelayWaitsOnlyForSpeechAndCapsTheVoiceAgentOnceTheUserPresses() {
        val t0 = Duration.ZERO
        val ms = { value: Int -> t0 + value.milliseconds }
        var gate = VoiceCallTiming.live.quietGate
        gate = gate.noteUserSpoke(t0)
        // Everything waits out the user's 2 s quiet gap, past the reply window.
        assertEquals(1_500.milliseconds, gate.delay(ms(500)))
        // The answer is that reply: the user's quiet gap only.
        assertEquals(1_800.milliseconds, gate.answerDelay(ms(200), readyAt = t0, pressed = false, pressedHold = 2.seconds))
        assertNull(gate.answerDelay(ms(2_000), readyAt = t0, pressed = false, pressedHold = 2.seconds))

        // The voice agent talks from 1 s: held, unless the user asked again,
        // then for at most `pressedHold` of that talk (not of the wait before it).
        gate = gate.noteAssistantSpeaking(true, ms(1_000))
        assertEquals(900.milliseconds, gate.answerDelay(ms(5_000), readyAt = t0, pressed = false, pressedHold = 2.seconds))
        assertEquals(900.milliseconds, gate.answerDelay(ms(1_500), readyAt = t0, pressed = true, pressedHold = 2.seconds))
        gate = gate.noteAssistantSpeaking(true, ms(2_000))
        assertEquals("One run of speech, from 1 s", 500.milliseconds, gate.answerDelay(ms(2_500), readyAt = t0, pressed = true, pressedHold = 2.seconds))
        assertNull(gate.answerDelay(ms(3_000), readyAt = t0, pressed = true, pressedHold = 2.seconds))
        // The user's own words still hold it.
        gate = gate.noteUserSpoke(ms(3_000))
        assertEquals(1_900.milliseconds, gate.answerDelay(ms(3_100), readyAt = t0, pressed = true, pressedHold = 2.seconds))

        // A tag isn't the user asking again; GPT-Live rarely closes one.
        assertFalse(LiveCallCoordinator.hasWords("[gasp"))
        assertFalse(LiveCallCoordinator.hasWords("[laughs] "))
        assertTrue(LiveCallCoordinator.hasWords("[gasp Okay, that's weird"))
        assertTrue(LiveCallCoordinator.hasWords("] Okay, and what are the rules"))
    }

    @Test fun recognizesSpokenClaimsOfActing() {
        for (claim in listOf(
            "Opening.", "Checking.", "I'm checking right now.", "Let me look into that.", "Got it, switching you now.",
            "On it.", "I'll take a look.", "Sure, opening OPENCODE IOS.", "Ooh, let's take a look.",
            "Let's check the upload test.", "Digging into it.",
        )) {
            assertTrue(claim, VoiceActionClaim.isClaim(claim))
        }
        // Natural reactions the prompt now invites, and suggestions, aren't claims.
        for (reply in listOf(
            "Sure, which one do you want?", "Hey! What's up?", "Let me know if you need anything.", "I'll let you know.",
            "Looking good.", "Okay.", "Checking the upload test took a while, and it eventually passed with two flaky retries.",
            "Yeah.", "Yeah, good call.", "Yeah, I heard you.", "Ooh, that's a weird one.", "Let's start with the tests.",
            "Let's stop there for today.", "Nice, let's see if that works.", "Huh, that's annoying.",
        )) {
            assertFalse(reply, VoiceActionClaim.isClaim(reply))
        }
    }

    @Test fun theVoiceIsToldAClaimNeedsADelegationAndAnAnswerIsNoFragment() {
        val live = VoiceInstructions.buildLive(
            VoiceInstructions.Context(sessionTitle = "HOME", directoryName = "home", recentMessages = emptyList(), previousTail = emptyList(), previousEndedAt = null, managesSessions = true),
        )
        assertTrue(live.contains("Nothing happens unless you delegate. If you say you're doing something, delegate it in that same turn; if you won't delegate, ask or answer instead."))
        assertTrue(live.contains("the answer completes their earlier request, so delegate that request now"))
        assertTrue(live.contains("A short answer to your own question, or a short report about earlier work, is not a fragment"))
    }

    @Test fun accumulatesTranscriptDeltasFlushesOnBargeInAndForwardsDelegationUpdates() = runTest {
        val harness = LiveHarness(this)
        val (events, socket) = connected(harness)

        // User transcript deltas grow a single utterance until delegation.
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Can you", startMilliseconds = 100, endMilliseconds = 300))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Can you", final = true), events.next())
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = " check the upload test?", startMilliseconds = 300, endMilliseconds = 700))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Can you check the upload test?", final = true), events.next())

        // Client delegation carries only the delegation ID; task text comes from the accumulated transcript.
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "item_del_1", target = "client", offsetMilliseconds = 700))
        assertEquals(VoiceCallEvent.FunctionCall(callID = "item_del_1", name = "delegate", arguments = obj()), events.next())

        // Completion uses session.commentary.append with the delegation ID.
        harness.coordinator.completeFunction(callID = "item_del_1", output = "[COMPLETE] The upload test passed.")

        assertTrue(socket.commands.any { it is LiveCommand.AppendCommentary && it.delegationID == "item_del_1" && it.content == "The upload test passed." })

        // Overlapping full-duplex speech: user audio and transcript deltas stream
        // while the server keeps sending assistant audio and transcript deltas.
        // The client must not flush playback during overlap; it stops only when
        // the server signals it stopped or cut off.
        harness.audioLevels.updateUser(speechPCM)
        harness.capture(speechPCM)

        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Spinach, carrot", startMilliseconds = 900, endMilliseconds = 1_100))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "Spinach, carrot"), events.next())
        socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = speechPCM, startMilliseconds = 900, endMilliseconds = 1_000))
        waitUntil { harness.enqueues == 1 }

        // User speaks simultaneously while the assistant continues speaking.
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Start now even though I am talking", startMilliseconds = 1_000, endMilliseconds = 1_400))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-2", text = "Start now even though I am talking", final = true), events.next())
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = ", tomato, broccoli", startMilliseconds = 1_100, endMilliseconds = 1_500))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = ", tomato, broccoli"), events.next())
        socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = speechPCM, startMilliseconds = 1_100, endMilliseconds = 1_200))
        waitUntil { harness.enqueues == 2 }

        // The shared meters aren't checked here: other suites' harnesses reset
        // them in parallel, which made this test flaky. Not flushing is the guarantee.
        assertEquals("Never flush assistant playback just because the user is speaking", 0, harness.flushes)

        // Server signals a speech cut-off: now the utterance settles where the server stopped and playback flushes.
        socket.yieldEvent(LiveServerEvent.Error(LiveServerError(message = "Assistant output audio cut off.", code = "output_audio_cut_off", type = "moderation_error")))
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "Spinach, carrot, tomato, broccoli"), events.next())
        assertEquals(VoiceCallEvent.AssistantPlaybackFinished, events.next())
        assertEquals(1, harness.flushes)
        assertEquals(0f, harness.audioLevels.snapshot().assistant)

        // Mute and unmute send GPT-Live's input audio control commands.
        harness.coordinator.setMuted(true)
        harness.coordinator.setMuted(false)
        assertTrue(socket.commands.any { it is LiveCommand.Mute })
        assertTrue(socket.commands.any { it is LiveCommand.Unmute })

        harness.coordinator.end()
    }

    @Test fun rotationAndExpirySeedReplacementWithSessionInputAndDeliverOrphanCommentary() = runTest {
        val harness = LiveHarness(
            this,
            VoiceCallTiming(
                rotateAfter = 0.05,
                forceRotateAfter = 30.0,
                retryDelays = listOf(0.01, 0.01),
                liveDelegationSettle = Duration.ZERO,
                liveTranscriptSettle = 20.milliseconds,
                liveAcknowledgementWait = Duration.ZERO,
            ),
        )
        val events = EventIterator(harness.start(), backgroundScope)
        val first = harness.nextOpened()

        first.yieldEvent(LiveServerEvent.SessionStarted(id = "live_1", expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, events.next())
        first.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Why is the upload test flaky?", startMilliseconds = 100, endMilliseconds = 500))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Why is the upload test flaky?", final = true), events.next())
        first.yieldEvent(LiveServerEvent.DelegationCreated(id = "item_del_1", target = "client", offsetMilliseconds = 500))
        assertEquals(VoiceCallEvent.FunctionCall(callID = "item_del_1", name = "delegate", arguments = obj()), events.next())

        // Standby opens seeded with `session.input` containing the developer note and user turn.
        val second = harness.nextOpened()
        assertTrue(second.config.input.any { it.role == LiveInputMessage.Role.developer && it.text.contains("One request you delegated is still running") })
        assertTrue(second.config.input.contains(LiveInputMessage(LiveInputMessage.Role.user, "Why is the upload test flaky?")))

        second.yieldEvent(LiveServerEvent.SessionStarted(id = "live_2", expiresAt = null))
        delay(30)
        assertFalse("Voluntary standby rotation waits while a delegation is open", first.closed)

        first.yieldEvent(LiveServerEvent.SessionClosed(reason = LiveCloseReason.Expired, usageSeconds = 7_200))
        waitUntil { first.closed }
        assertEquals(listOf("voice_session_expired", "voice_standby_promoted"), harness.voiceEvents)

        // Result for `item_del_1` (created on `first`) arrives after the promotion: sent unbound (`delegation_id: null`).
        harness.coordinator.completeFunction(callID = "item_del_1", output = "[COMPLETE] It races the progress callback.")
        assertTrue(second.commands.any {
            it is LiveCommand.AppendCommentary && it.delegationID == null && it.content.contains("It races the progress callback.")
        })

        // With no delegations open, the next standby rotates voluntarily at a turn boundary.
        val third = harness.nextOpened()
        third.yieldEvent(LiveServerEvent.SessionStarted(id = "live_3", expiresAt = null))
        waitUntil { second.closed }
        assertEquals(listOf("voice_session_expired", "voice_standby_promoted", "voice_rotation_swapped"), harness.voiceEvents)

        harness.coordinator.end()
    }

    /**
     * After a network switch, "Um.. Could pick a gymnasium Thanks", said over the HOME result, came
     * back from the voice agent as "an unrelated note here about picking a gym". His words reach
     * the model only as his own: live audio, `user` turns in a seed, or a catch-up transcript that
     * says so; never inside a result or a note.
     */
    @Test fun replayedGymCallKeepsTheUsersWordsTheirOwnAcrossReconnects() = runTest {
        val harness = LiveHarness(this)
        val (events, first) = connected(harness)

        val request = "You cuts my wife, letting her know that I'm going to the gym and just, uh, make sure you use, I think we have a tool that lets you get notified if there's a response. You can tell me"
        first.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = request, startMilliseconds = 99_000, endMilliseconds = 108_000))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = request, final = true), events.next())
        first.yieldEvent(LiveServerEvent.DelegationCreated(id = "item_home", target = "client", offsetMilliseconds = 108_000))
        assertEquals(VoiceCallEvent.FunctionCall(callID = "item_home", name = "delegate", arguments = obj()), events.next())
        val acknowledgement = "Yeah, okay. I can have that ready for you, just a second."
        first.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = acknowledgement, startMilliseconds = 108_200, endMilliseconds = 114_000))
        events.skipUntil(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = acknowledgement))

        // t≈116.8 s: the phone moved to cellular.
        first.finish(NetworkConnectionLost())
        events.skipUntil(VoiceCallEvent.Reconnecting)
        val second = harness.nextOpened()
        assertTrue(second.config.input.contains(LiveInputMessage(LiveInputMessage.Role.user, request)))
        second.yieldEvent(LiveServerEvent.SessionStarted(id = "live_2", expiresAt = null))
        events.skipUntil(VoiceCallEvent.Reconnected)

        // t≈120.8 s: the delegator's answer, now a result from the retired session.
        val answer = "Started that in HOME, including checking for reply notifications. The text hasn’t been confirmed sent yet."
        harness.coordinator.completeFunction(callID = "item_home", output = "[COMPLETE] $answer")
        waitUntil { second.commentary().any { it.second.contains(answer) } }
        assertEquals(listOf(LiveCallCoordinator.earlierConnectionLead + answer), second.commentary().map { it.second })

        // t≈121–125 s: the user talks over the reply.
        val overlap = "Um... Could pick a gymnasium Thanks"
        second.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Yep, I've started that in HOME.", startMilliseconds = 500, endMilliseconds = 3_000))
        second.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = overlap, startMilliseconds = 2_000, endMilliseconds = 4_300))
        events.skipUntil(VoiceCallEvent.UserTranscript(itemID = "live-u-2", text = overlap, final = true))

        // Another switch: the next session is seeded with his words as his.
        second.finish(NetworkConnectionLost())
        events.skipUntil(VoiceCallEvent.Reconnecting)
        val third = harness.nextOpened()
        val seed = third.config.input
        assertTrue(seed.contains(LiveInputMessage(LiveInputMessage.Role.user, overlap)))
        assertFalse(seed.any { it.role != LiveInputMessage.Role.user && it.text.contains("gymnasium", ignoreCase = true) })
        val header = seed.firstOrNull() ?: throw AssertionError("Expected a seed header")
        assertEquals(LiveInputMessage.Role.developer, header.role)
        assertTrue("The real result, without its delivery lead", header.text.contains("- $answer"))
        assertFalse(header.text.contains("gym"))

        // Words said before the new session confirms reach it as a transcript of his words.
        val followUp = "Picking a gym, what do you mean"
        delay(50)
        third.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = followUp, startMilliseconds = 100, endMilliseconds = 1_000))
        events.skipUntil(VoiceCallEvent.UserTranscript(itemID = "live-u-3", text = followUp, final = true))
        third.yieldEvent(LiveServerEvent.SessionStarted(id = "live_3", expiresAt = null))
        waitUntil { third.appends().any { it.first == "thinking" } }
        val catchUp = third.appends().firstOrNull { it.first == "thinking" } ?: throw AssertionError("Expected a catch-up transcript")
        assertEquals(catchUpLead + "\nUser: $followUp", catchUp.second)
        assertTrue(harness.callLogMarkers.contains("Sent catch-up transcript to the voice agent on the new session (1 line)"))

        // Nothing the model was given puts his words in a result or a note.
        for (socket in listOf(first, second, third)) {
            for (append in socket.appends().filter { it.second.contains("gym", ignoreCase = true) }) {
                assertTrue("${append.first}: ${append.second}", append.first == "thinking" && append.second.startsWith(catchUpLead))
            }
            assertFalse(socket.commentary().any { it.second.contains("gym", ignoreCase = true) })
        }
        harness.coordinator.end()
    }

    /**
     * A switch cut "…it'll lock" off mid-sentence and the next session, seeded with it as its own
     * turn, finished it and riffed on his son's "Bye, Keanu" ("kids say stuff… I can say bye to
     * him"). After "I was talking to somebody else… Catch me up" and another switch, it repeated
     * those lines word for word: its own replies were all it knew of the call. A cut-off reply is
     * seeded as cut off, never as a turn to finish, and the seed lists the real results.
     */
    @Test fun replayedKeanuCallSeedsRealResultsAndNeverAReplyToFinish() = runTest {
        val timing = VoiceCallTiming(
            rotateAfter = 3_000.0,
            forceRotateAfter = 3_500.0,
            retryDelays = listOf(0.01, 0.01, 0.01),
            liveDelegationSettle = Duration.ZERO,
            liveTranscriptSettle = 1.seconds,
            liveAcknowledgementWait = Duration.ZERO,
        ).copy(liveResultMaxHold = 200.milliseconds)
        val harness = LiveHarness(this, timing)
        val (events, first) = connected(harness)

        // t≈195 s: talking to his son, not to the voice agent.
        val keanu = "[laugh Hey. No, no, right. Cool. Yeah, my son was saying your name left right after you passed. He's like, Goodbye, Keanu"
        first.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = keanu, startMilliseconds = 1_000, endMilliseconds = 8_000))
        first.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "I didn't actually hear it, but, I'm sure it was.", startMilliseconds = 9_000, endMilliseconds = 11_000))

        // t≈215.6 s: OPENCODE IOS replies to the feedback request.
        val reply = "Got it. There was no note about a gym. The voice agent made that up, and I've got an agent tracing exactly where it came from."
        val notice = VoiceHandoff.earlierRequest(
            "The session “OPENCODE IOS” finished the work you started there. Tell the user only what's new since you last spoke about it; don't repeat the request or anything you already said, and if nothing is new, say nothing. Its reply: $reply",
        )
        harness.coordinator.announce(notice, sessionID = "ses_ios", promptIDs = listOf("msg_ios"))
        waitUntil { first.commentary().any { it.second == notice } }
        assertEquals(listOf(notice), first.commentary().map { it.second })

        // t≈216–227 s: its reply, cut off by the next network switch.
        val cutOff = "Here's the result. There was never a gym note, and I made it up. The session's now tracing exactly where that came from, and it'll lock"
        first.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = cutOff, startMilliseconds = 20_000, endMilliseconds = 31_000))
        events.skipUntil(VoiceCallEvent.AssistantDelta(itemID = "live-a-2", delta = cutOff))
        first.finish(NetworkConnectionLost())
        events.skipUntil(VoiceCallEvent.Reconnecting)

        val second = harness.nextOpened()
        val firstSeed = second.config.input
        assertFalse("Seeded as its own turn, it gets finished", firstSeed.any { it.role == LiveInputMessage.Role.assistant && it.text.contains("it'll lock") })
        assertTrue(firstSeed.contains(LiveInputMessage(
            LiveInputMessage.Role.developer,
            "Your reply here was cut off; the user heard only part of: “$cutOff”. Don't finish it or say it again.",
        )))
        assertTrue(firstSeed.contains(LiveInputMessage(LiveInputMessage.Role.assistant, "I didn't actually hear it, but, I'm sure it was.")))
        val firstHeader = firstSeed.firstOrNull()?.text ?: throw AssertionError("Expected a seed header")
        assertTrue(firstHeader.startsWith(LiveCallCoordinator.seedHeader))
        assertTrue(firstHeader.contains("- “OPENCODE IOS” replied: $reply"))
        assertFalse("Results, not delivery directions", firstHeader.contains(VoiceHandoff.earlierRequestLead.trim(' ', '\t')))
        assertFalse(firstHeader.contains("Keanu"))

        second.yieldEvent(LiveServerEvent.SessionStarted(id = "live_2", expiresAt = null))
        events.skipUntil(VoiceCallEvent.Reconnected)
        // t≈229.6 s: what the old seed got: it finished the sentence and riffed.
        val riff = "that down. Anyway, yeah, kids say stuff and it just lands that way. I can say \"bye\" to him for real right now if he's around."
        second.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = riff, startMilliseconds = 500, endMilliseconds = 11_000))
        events.skipUntil(VoiceCallEvent.AssistantDone(itemID = "live-a-3", text = riff))
        // t≈275.5 s: his correction.
        val correction = "Okay, sorry. Um, I was talking to somebody else. That's what the... Keanu stuff was about, um. What were- I messed everything up. So what- what did you do? Catch me up"
        second.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = correction, startMilliseconds = 47_000, endMilliseconds = 54_500))
        events.skipUntil(VoiceCallEvent.UserTranscript(itemID = "live-u-2", text = correction, final = true))
        // t≈283.7 s: back to Wi-Fi, right after "Catch me up".
        second.finish(NetworkConnectionLost())
        events.skipUntil(VoiceCallEvent.Reconnecting)

        val third = harness.nextOpened()
        val secondSeed = third.config.input
        val header = secondSeed.firstOrNull()?.text ?: throw AssertionError("Expected a seed header")
        assertTrue(header.contains("never finish or repeat an earlier reply of yours, and when the user corrected you, the correction stands"))
        assertTrue(header.contains("They are the only work you can report; a recap lists these and nothing else:\n- “OPENCODE IOS” replied: $reply"))
        assertEquals("His correction is his own last word", LiveInputMessage(LiveInputMessage.Role.user, correction), secondSeed.last())
        assertEquals(listOf(LiveInputMessage.Role.developer), secondSeed.filter { it.text.contains("it'll lock") }.map { it.role })
        assertFalse(secondSeed.any { it.role == LiveInputMessage.Role.developer && it.text.contains("kids say stuff") })
        assertTrue("Nothing redelivered: the result went out once", second.appends().isEmpty() && third.appends().isEmpty())
        harness.coordinator.end()
    }

    @Test fun resultRecordKeepsResultsWithoutTheirDeliveryDirections() {
        var record = VoiceResultRecord()
        record = record.record(LiveCallCoordinator.earlierConnectionLead + "Started that in HOME.")
        record = record.record("Result of the request you acknowledged with “Opening.”: Opened OPENCODE IOS.")
        record = record.record(VoiceHandoff.earlierRequest("The session “Blog” finished the work you started there without a text reply. Tell the user briefly that “Blog” finished its steps.", topic = "Draft the launch post"))
        record = record.record("Sent that to the OPENCODE IOS session.")
        record = record.record("Sent that to the OPENCODE IOS session.")
        assertEquals(
            listOf(
                "Started that in HOME.",
                "Opened OPENCODE IOS.",
                "The session “Blog” finished the work you started there without a text reply.",
                "Sent that to the OPENCODE IOS session.",
            ),
            record.entries,
        )
        for (index in 0 until 10) record = record.record("Result $index. " + "x".repeat(400))
        assertEquals(VoiceResultRecord.maxEntries, record.entries.size)
        assertTrue(record.entries.all { it.length <= VoiceResultRecord.entryLimit })
        assertTrue(record.entries.last().startsWith("Result 9."))
    }

    /**
     * Every engine is told what it hears is the user's, unclear speech gets
     * "Sorry, I didn't catch that", background speech gets silence, a
     * correction stands, and a recap is only real results.
     */
    @Test fun everyEngineIsToldWhatItHearsIsTheUsersOwnAndNeverANote() {
        val context = VoiceInstructions.Context(sessionTitle = "HOME", directoryName = "home", recentMessages = emptyList(), previousTail = emptyList(), previousEndedAt = null, managesSessions = true)
        val section = VoiceInstructions.whatYouHearSection
        for (prompt in listOf(VoiceInstructions.buildLive(context), VoiceInstructions.build(context, engine = VoiceEngine.realtime), VoiceInstructions.build(context, engine = VoiceEngine.grok))) {
            assertTrue(prompt.contains(section))
        }
        assertTrue(section.contains("It is never a note, a message, or part of a result: never call it one."))
        assertTrue(section.contains("Never say you got a note, message, or result that isn't in one, and never guess what one means."))
        assertTrue(section.contains("say \"Sorry, I didn't catch that.\""))
        assertTrue(section.contains("get no reply at all: keep listening. No greeting back, no laugh"))
        assertTrue(section.contains("the correction replaces your earlier take"))
        assertTrue(section.contains("lists only the requests you delegated and their actual results"))
        assertTrue(section.contains("No emotional probing"))
        assertTrue(VoiceDelegator.instructions.contains("Never mention a note, message, or content that isn't in a tool result"))
        assertTrue(VoiceDelegator.instructions.contains("reply only \"Sorry, I didn't catch that.\""))
    }

    @Test fun replacementsThatFailBeforeConfirmingUseUpRetriesAndFirstSessionFailureFailsTheCall() = runTest {
        val unconfirmed = LiveHarness(this)
        val unconfirmedEvents = EventIterator(unconfirmed.start(), backgroundScope)
        val initial = unconfirmed.nextOpened()
        initial.finish(NetworkConnectionLost())
        val startError = runCatching { unconfirmedEvents.next() }.exceptionOrNull()
        assertTrue("Expected the network connection lost: $startError", startError is NetworkConnectionLost)
        delay(30)
        assertEquals(1, unconfirmed.sockets.size)
        assertEquals(listOf("voice_connect_failed"), unconfirmed.voiceEvents)

        val harness = LiveHarness(this)
        val (events, first) = connected(harness)
        first.finish(NetworkConnectionLost())
        assertEquals(VoiceCallEvent.Reconnecting, events.next())

        // Each replacement connects, then fails before `session.started`.
        repeat(3) {
            val socket = harness.nextOpened()
            socket.finish(NetworkConnectionLost())
        }
        val ended = runCatching { events.next() }.exceptionOrNull()
        assertTrue("Expected the call to drop: $ended", ended is RealtimeClientError.Dropped)
        assertEquals(4, harness.sockets.size)
        assertEquals(
            listOf("voice_socket_dropped", "voice_connect_failed", "voice_connect_failed", "voice_connect_failed", "voice_reconnect_exhausted"),
            harness.voiceEvents,
        )
    }

    @Test fun aResultFinishingWhileALostSocketClosesIsKeptForTheNextSession() = runTest {
        val harness = LiveHarness(this)
        val (events, first) = connected(harness)
        first.yieldEvent(LiveServerEvent.DelegationCreated(id = "item_del_1", target = "client", offsetMilliseconds = 100))
        assertEquals(VoiceCallEvent.FunctionCall(callID = "item_del_1", name = "delegate", arguments = obj()), events.next())
        first.holdsClose = true
        first.finish(NetworkConnectionLost())
        assertEquals(VoiceCallEvent.Reconnecting, events.next())

        waitUntil { first.isHeldClosing }
        harness.coordinator.completeFunction(callID = "item_del_1", output = "[COMPLETE] Fixed it.")

        val second = harness.nextOpened()
        second.yieldEvent(LiveServerEvent.SessionStarted(id = "live_2", expiresAt = null))
        assertEquals(VoiceCallEvent.Reconnected, events.next())
        assertTrue(waitUntil {
            second.commands.any { it is LiveCommand.AppendCommentary && it.delegationID == null && it.content.contains("Fixed it.") }
        })

        first.releaseClose()
        harness.coordinator.end()
    }

    @Test fun lateTranscriptDeltasAfterDelegationSettleUpdateSameUserCaptionAndAudioInterruptionSuppressesPlayback() = runTest {
        val harness = LiveHarness(
            this,
            VoiceCallTiming(
                rotateAfter = 3_000.0,
                forceRotateAfter = 3_500.0,
                retryDelays = listOf(0.01),
                liveDelegationSettle = 25.milliseconds,
                liveTranscriptSettle = 40.milliseconds,
                liveAcknowledgementWait = Duration.ZERO,
            ),
        )
        val (events, socket) = connected(harness)

        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Check why the upload test", startMilliseconds = 0, endMilliseconds = 800))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Check why the upload test", final = true), events.next())
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "item_del_1", target = "client", offsetMilliseconds = 800))

        // Wait for `liveDelegationSettle` to fire `emitDelegation`.
        assertEquals(VoiceCallEvent.FunctionCall(callID = "item_del_1", name = "delegate", arguments = obj()), events.next())

        // Near-contiguous trailing delta with a 10 ms gap (`start_ms == 810` after `end_ms == 800`,
        // `gap == 10 <= 200`) arrives after the settle window: it must update `live-u-1` in place
        // rather than splitting into `live-u-2`.
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = " is failing in OpenCodeTests.", startMilliseconds = 810, endMilliseconds = 1_200))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Check why the upload test is failing in OpenCodeTests.", final = true), events.next())

        // A follow-up spoken 400 ms later (`start_ms == 1600 > 1200 + 200`), even before
        // the assistant speaks, starts a new utterance `live-u-2`.
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Also check the retry test.", startMilliseconds = 1_600, endMilliseconds = 2_000))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-2", text = "Also check the retry test.", final = true), events.next())

        // A 1-byte (`pcm.count == 1`) audio buffer is ignored and never enqueues.
        socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = byteArrayOf(1), startMilliseconds = 2_000, endMilliseconds = 2_010))
        delay(10)
        assertEquals(0, harness.enqueues)

        // Brief buffer drain mid-sentence does not split one assistant reply into two bubbles.
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Checking", startMilliseconds = 2_100, endMilliseconds = 2_200))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "Checking"), events.next())
        socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = byteArrayOf(1, 2), startMilliseconds = 2_100, endMilliseconds = 2_200))
        waitUntil { harness.enqueues == 1 }
        harness.audioEvent(VoiceAudioEvent.playbackDrained)
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = " now.", startMilliseconds = 2_200, endMilliseconds = 2_300))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = " now."), events.next())

        // Audio interruption settles the live assistant utterance immediately so it isn't stuck open.
        val enqueuesBefore = harness.enqueues
        harness.audioEvent(VoiceAudioEvent.interrupted)
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "Checking now."), events.next())
        assertEquals(VoiceCallEvent.AudioInterrupted, events.next())
        socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = byteArrayOf(1, 2), startMilliseconds = 2_300, endMilliseconds = 2_400))
        delay(20)
        assertEquals(enqueuesBefore, harness.enqueues)
        harness.audioEvent(VoiceAudioEvent.resumed)
        assertEquals(VoiceCallEvent.AudioResumed, events.next())

        harness.coordinator.end()
    }

    private fun acknowledgementTiming(liveTranscriptSettle: Duration, liveAcknowledgementWait: Duration) = VoiceCallTiming(
        rotateAfter = 3_000.0,
        forceRotateAfter = 3_500.0,
        retryDelays = listOf(0.01),
        liveDelegationSettle = Duration.ZERO,
        liveTranscriptSettle = liveTranscriptSettle,
        liveAcknowledgementWait = liveAcknowledgementWait,
    )

    /** The test's virtual time is the clock, and reading the events never moves it. */
    private suspend fun TestScope.connectedOnTheClock(harness: LiveHarness): Pair<ClockedEvents, FakeLiveSocket> {
        val events = ClockedEvents(harness.start(), backgroundScope)
        val socket = harness.nextOpened()
        socket.yieldEvent(LiveServerEvent.SessionStarted(id = "live_1", expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, events.next())
        return events to socket
    }

    private fun TestScope.advance(milliseconds: Int) {
        testScheduler.advanceTimeBy(milliseconds.toLong())
        testScheduler.runCurrent()
    }

    private suspend fun awaitAcknowledgement(harness: LiveHarness, id: String) {
        val mark = TimeSource.Monotonic.markNow()
        while (harness.coordinator.serialized { harness.coordinator.acknowledgements[id] } == null) {
            if (mark.elapsedNow() > 5.seconds) throw AssertionError("$id never waited for its acknowledgement")
            yield()
        }
    }

    @Test fun delegationWaitsForTheSpokenAcknowledgementToLabelIt() = runTest {
        val harness = LiveHarness(this, acknowledgementTiming(5.seconds, 5.seconds))
        val (events, socket) = connectedOnTheClock(harness)

        // The agent is still finishing an earlier reply when the user's request is delegated.
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "The build is green", startMilliseconds = 0, endMilliseconds = 200))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "The build is green"), events.next())
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Check the upload test", startMilliseconds = 300, endMilliseconds = 900))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Check the upload test", final = true), events.next())
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_1", target = "client", offsetMilliseconds = 900))

        // That earlier reply finishing is not the acknowledgement: it started before the delegation.
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = ".", startMilliseconds = 200, endMilliseconds = 300))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "."), events.next())
        harness.audioEvent(VoiceAudioEvent.interrupted)
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "The build is green."), events.next())
        assertEquals(VoiceCallEvent.AudioInterrupted, events.next())
        harness.audioEvent(VoiceAudioEvent.resumed)
        assertEquals(VoiceCallEvent.AudioResumed, events.next())

        // The acknowledgement labels the delegation once its sentence ends.
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = " Checking the", startMilliseconds = 1_100, endMilliseconds = 1_300))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-2", delta = " Checking the"), events.next())
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = " upload test.", startMilliseconds = 1_300, endMilliseconds = 1_500))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-2", delta = " upload test."), events.next())
        assertEquals(VoiceCallEvent.FunctionCall(callID = "del_1", name = "delegate", arguments = text("Checking the upload test.")), events.next())
        harness.coordinator.end()
    }

    @Test fun delegationGoesWithoutALabelWhenTheAcknowledgementIsLate() = runTest {
        val harness = LiveHarness(this, acknowledgementTiming(5.seconds, 50.milliseconds))
        val (events, socket) = connectedOnTheClock(harness)

        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Check the upload test", startMilliseconds = 0, endMilliseconds = 600))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Check the upload test", final = true), events.next())
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_1", target = "client", offsetMilliseconds = 600))
        // Started, but its sentence doesn't end within the wait.
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Checking the", startMilliseconds = 800, endMilliseconds = 1_000))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "Checking the"), events.next())
        advance(49)
        assertNotNull("Still waiting for the sentence to end", harness.coordinator.serialized { harness.coordinator.acknowledgements["del_1"] })
        advance(1)
        assertEquals(VoiceCallEvent.FunctionCall(callID = "del_1", name = "delegate", arguments = obj()), events.next())
        // Finishing later labels nothing again.
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = " upload test.", startMilliseconds = 1_000, endMilliseconds = 1_200))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = " upload test."), events.next())
        harness.coordinator.end()
    }

    /**
     * A move to another session while a delegation waits for its
     * acknowledgement keeps it: the conversation is the call's, so the
     * request goes out labelled as usual and nobody hears it wasn't sent.
     */
    @Test fun movingSessionsMidWaitKeepsTheDelegation() = runTest {
        val harness = LiveHarness(this, acknowledgementTiming(5.seconds, 5.seconds))
        val (events, socket) = connectedOnTheClock(harness)

        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Check the upload test", startMilliseconds = 0, endMilliseconds = 600))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Check the upload test", final = true), events.next())
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_a", target = "client", offsetMilliseconds = 600))
        awaitAcknowledgement(harness, "del_a")

        harness.coordinator.updateInstructions(full = "Attached to B.", delta = "${VoiceInstructions.retargetPrefix} You are attached to B.")
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Checking the upload test.", startMilliseconds = 800, endMilliseconds = 1_000))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "Checking the upload test."), events.next())
        assertEquals(VoiceCallEvent.FunctionCall(callID = "del_a", name = "delegate", arguments = text("Checking the upload test.")), events.next())
        assertFalse(socket.commands.any { it is LiveCommand.AppendThinking && it.content.contains("wasn't sent") })
        harness.coordinator.end()
    }

    /**
     * Delegations go out in the order they were made, each before any words
     * that came after it: a waiting one goes unlabelled when the next
     * delegation settles or the user starts a new turn, so the call captures
     * only its own words.
     */
    @Test fun backToBackDelegationsKeepTheirOrderAndTheirOwnWords() = runTest {
        val harness = LiveHarness(this, acknowledgementTiming(5.seconds, 5.seconds))
        val (events, socket) = connectedOnTheClock(harness)

        // Two delegations in quick succession.
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Run the tests", startMilliseconds = 0, endMilliseconds = 500))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Run the tests", final = true), events.next())
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_1", target = "client", offsetMilliseconds = 500))
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_2", target = "client", offsetMilliseconds = 520))
        assertEquals(VoiceCallEvent.FunctionCall(callID = "del_1", name = "delegate", arguments = obj()), events.next())

        // A new turn while del_2 waits: del_2 goes before its words.
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "And the lint", startMilliseconds = 1_500, endMilliseconds = 2_000))
        assertEquals(VoiceCallEvent.FunctionCall(callID = "del_2", name = "delegate", arguments = obj()), events.next())
        // On a test clock nobody advances: neither waited out the acknowledgement.
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-2", text = "And the lint", final = true), events.next())

        // The next one still gets its acknowledgement.
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_3", target = "client", offsetMilliseconds = 2_000))
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Running the lint.", startMilliseconds = 2_200, endMilliseconds = 2_400))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "Running the lint."), events.next())
        assertEquals(VoiceCallEvent.FunctionCall(callID = "del_3", name = "delegate", arguments = text("Running the lint.")), events.next())
        harness.coordinator.end()
    }

    /**
     * Abandoning a delegation that's still waiting for its acknowledgement
     * drops it too.
     */
    @Test fun abandoningADelegationMidWaitDropsIt() = runTest {
        val harness = LiveHarness(this, acknowledgementTiming(5.seconds, 80.milliseconds))
        val (events, socket) = connectedOnTheClock(harness)

        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Check the upload test", startMilliseconds = 0, endMilliseconds = 600))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Check the upload test", final = true), events.next())
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_a", target = "client", offsetMilliseconds = 600))
        awaitAcknowledgement(harness, "del_a")
        harness.coordinator.closeDelegation(callID = "del_a", reason = VoiceDelegationCloseReason.superseded)
        advance(150)
        // Past its deadline: had it survived, it would come before this.
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Okay.", startMilliseconds = 2_000, endMilliseconds = 2_200))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "Okay."), events.next())
        harness.coordinator.end()
    }

    @Test fun finishStopsAudioBeforeSocketCloseAndMultiChunkFallbackAndStandbyGuardsWork() = runTest {
        val harness = LiveHarness(
            this,
            VoiceCallTiming(
                rotateAfter = 0.04,
                forceRotateAfter = 30.0,
                retryDelays = listOf(0.01),
                liveDelegationSettle = Duration.ZERO,
                liveTranscriptSettle = 20.milliseconds,
                liveAcknowledgementWait = Duration.ZERO,
            ),
        )
        val events = EventIterator(harness.start(), backgroundScope)
        val first = harness.nextOpened()

        first.yieldEvent(LiveServerEvent.SessionStarted(id = "live_1", expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, events.next())

        // Standby opens; before it confirms, `updateInstructions(full:delta:)` updates `config.instructions`
        // to the full prompt while sending only the concise delta, then `sessionClosed` discards the standby.
        val deadStandby = harness.nextOpened()
        harness.coordinator.updateInstructions(
            full = "Full replacement instructions for future session.start.",
            delta = "Session changed. Short delta.",
        )
        assertTrue(first.commands.any { it is LiveCommand.AppendInstructions && it.delegationID == null && it.content == "Session changed. Short delta." })
        deadStandby.yieldEvent(LiveServerEvent.SessionClosed(reason = LiveCloseReason.Expired, usageSeconds = 10))
        waitUntil { deadStandby.closed }
        assertTrue("A standby that closes before promotion is discarded immediately", deadStandby.closed)

        // Multi-chunk commentary where chunk 1 succeeds and chunk 2 throws:
        // only chunk 2 is queued in `undelivered` for the replacement session.
        first.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_multi", target = "client", offsetMilliseconds = 100))
        assertEquals(VoiceCallEvent.FunctionCall(callID = "del_multi", name = "delegate", arguments = obj()), events.next())
        first.failOnCommentaryIndex = 1
        val chunk1 = "First chunk words. ".repeat(72)
        val chunk2 = "Second chunk distinct conclusion."
        harness.coordinator.completeFunction(callID = "del_multi", output = "[COMPLETE] $chunk1\n\n$chunk2")
        val commentaryMarks = { harness.callLogMarkers.filter { it.startsWith("Sent commentary") } }
        assertEquals("Only the chunk the socket took is marked sent", 1, commentaryMarks().size)

        // Trigger reconnect and verify `second` gets the FULL instructions in `session.start`
        // and only chunk 2 (not chunk 1's tail) in `session.commentary.append`.
        first.holdsClose = true
        first.finish(NetworkConnectionLost())
        assertEquals(VoiceCallEvent.Reconnecting, events.next())
        val second = harness.nextOpened()
        assertEquals("Full replacement instructions for future session.start.", second.config.instructions)
        second.yieldEvent(LiveServerEvent.SessionStarted(id = "live_2", expiresAt = null))
        assertEquals(VoiceCallEvent.Reconnected, events.next())
        waitUntil { second.commands.any { it is LiveCommand.AppendCommentary && it.delegationID == null && it.content.contains(chunk2) } }
        val secondCommentary = second.spoken()
        assertEquals(1, secondCommentary.size)
        assertTrue(secondCommentary.first().contains(chunk2))
        assertFalse("Chunk 1 already succeeded and must not be re-sent", secondCommentary.first().contains("First chunk words."))
        val drainMark = "Sent 1 undelivered append to the voice agent on the new session"
        waitUntil { harness.callLogMarkers.contains(drainMark) }
        assertTrue(harness.callLogMarkers.contains(drainMark))
        first.releaseClose()

        // `end()` stops audio and finishes the event stream even while `second.close()` is held open.
        second.holdsClose = true
        val endTask = launch { harness.coordinator.end() }
        waitUntil { harness.audioStopped == 1 }
        assertEquals("Microphone and speaker stop before socket close completes", 1, harness.audioStopped)
        assertNull("Event stream finishes before socket close completes", events.next())
        second.releaseClose()
        endTask.join()
    }

    companion object {
        private const val catchUpLead = "Transcript of what was said while the call switched connections, for context. User lines are the user's own words spoken to you, not a note or a result:"

        const val rulesAnswer = "One player is the giant desk spider, and up to four others are roly-poly pillbugs. The pillbugs win by stealing four of the five golden crumbs and bringing them back to their matchbox nest."

        /** 240 samples of 8,000 (little-endian Int16). */
        val speechPCM: ByteArray = ByteArray(480) { if (it % 2 == 0) 0x40 else 0x1F }
    }
}
