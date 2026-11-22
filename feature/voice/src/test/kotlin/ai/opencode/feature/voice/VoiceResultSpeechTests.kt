package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.live.assistantReply
import ai.opencode.server.live.decodeTranscript
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal fun speechVoiceCall(
    start: suspend (String, RealtimeSessionConfig) -> Flow<VoiceCallEvent> = { _, _ -> kotlinx.coroutines.flow.emptyFlow() },
    completeFunction: suspend (String, String) -> Unit = { _, _ -> },
    announce: suspend (String?, List<String>, String) -> Unit = { _, _, _ -> },
    cancelPendingAnnouncements: suspend (List<String>) -> Unit = {},
    updateInstructions: suspend (String, String?) -> Unit = { _, _ -> },
    end: suspend () -> Unit = {},
    delegatorRound: (suspend (String) -> Result<VoiceDelegator.Response>)? = null,
    submitDelegatorInput: (suspend (String, List<VoiceDelegator.InputItem>) -> Boolean)? = null,
    updateDelegatorInstructions: suspend (String) -> Unit = {},
): VoiceCallClient = VoiceCallClient(
    start = start,
    completeFunction = completeFunction,
    announce = announce,
    cancelPendingAnnouncements = cancelPendingAnnouncements,
    setMuted = {},
    updateInstructions = updateInstructions,
    end = end,
    delegatorRound = delegatorRound ?: { Result.failure(OpenCodeClientError("The voice call has no delegator.")) },
    submitDelegatorInput = submitDelegatorInput ?: { _, _ -> false },
    updateDelegatorInstructions = updateDelegatorInstructions,
)

private val epoch: DateClient = DateClient.constant(Instant.EPOCH)
private val distantPast: Instant = Instant.parse("0001-01-01T00:00:00Z")
private val emptyArguments: RealtimeJSON = JsonObject(emptyMap())
private fun isReplyWatch(action: VoiceFeature.Action) = action is VoiceFeature.Action.ReplyWatch

/**
 * Results are always spoken (the call holds them until the conversation is idle), and say which
 * request they answer once the conversation has gone on.
 */
class VoiceLateResultTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val prompt = ReplyWatch.Prompt(promptID = "msg_ios", sessionID = "ses_ios", title = "OpenCode iOS")

    private fun resolved(after: Int, outcome: (Watch) -> List<ReplyWatch.Effect>): List<ReplyWatch.Effect> {
        val watch = Watch()
        watch.step(ReplyWatch.Event.Sent(listOf(prompt)))
        repeat(after) { watch.noteUserActed() }
        return outcome(watch).filter { it !is ReplyWatch.Effect.Log }
    }

    private fun reply(text: String): String = ReplyWatch.Outcome.Replied(text).notice("OpenCode iOS")

    /** "Done — fixed X" is an answer, not a mere confirmation. */
    @Test fun aResultOnTimeIsSpokenAsItIs() {
        for (text in listOf("Done — fixed the drawer animation.", "Okay, all 11 tests passed.", "Sent the iMessage to Olive: on my way.")) {
            val effects = resolved(after = 0) { it.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_ios", reply = text)) }
            assertEquals(
                text,
                listOf(
                    ReplyWatch.Effect.CancelWatch(promptID = "msg_ios"),
                    ReplyWatch.Effect.Announce(sessionID = "ses_ios", promptIDs = listOf("msg_ios"), text = reply(text)),
                    ReplyWatch.Effect.FindBackgroundWork(sessionID = "ses_ios", promptID = "msg_ios", title = "OpenCode iOS", turn = 0, reply = text),
                ),
                effects,
            )
        }
        assertTrue(reply("x").contains("if nothing is new, say nothing"))
    }

    /**
     * "Is it done yet?", a status read, then the result; "okay, thanks"; or a
     * quick other question: each is spoken, naming what it was about.
     */
    @Test fun aResultAfterTheConversationWentOnIsSpokenWithItsTopic() {
        for (acts in listOf(1, 2, 3)) {
            val text = "Renamed the acknowledgement rule and updated its tests."
            val effects = resolved(after = acts) { it.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_ios", reply = text)) }
            assertTrue(effects.contains(ReplyWatch.Effect.Announce(sessionID = "ses_ios", promptIDs = listOf("msg_ios"), text = VoiceHandoff.earlierRequest(reply(text)))))
        }
        assertEquals(VoiceHandoff.earlierRequest("x"), VoiceHandoff.earlierRequest(VoiceHandoff.earlierRequest("x")))
    }

    @Test fun aLateResultNamesTheRequestItAnswers() {
        val topic = "Rename Seer to Opticon and redo its icon"
        val watch = Watch()
        watch.step(ReplyWatch.Event.Sent(listOf(ReplyWatch.Prompt(promptID = "msg_rename", sessionID = "ses_ios", title = "OpenCode iOS", topic = topic))))
        watch.noteUserActed()
        val text = "Renamed it and drew the new icon."
        val effects = watch.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_rename", reply = text))
        val notice = ReplyWatch.Outcome.Replied(text).notice("OpenCode iOS", answering = listOf(topic), alreadyTold = null)
        val spoken = VoiceHandoff.earlierRequest(notice, topic = topic)
        assertTrue(spoken.startsWith(VoiceHandoff.earlierRequestLead + VoiceHandoff.earlierTopicLead + topic + "”. "))
        assertTrue(effects.contains(ReplyWatch.Effect.Announce(sessionID = "ses_ios", promptIDs = listOf("msg_rename"), text = spoken)))
        // Untitled prompts don't name a made-up topic.
        assertFalse(
            resolved(after = 1) { it.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_ios", reply = text)) }.any {
                it is ReplyWatch.Effect.Announce && it.text.contains(VoiceHandoff.earlierTopicLead)
            },
        )
    }

    @Test fun failuresSpeak() {
        val notice = ReplyWatch.Outcome.Failed("The build broke").notice("OpenCode iOS")
        assertTrue(
            resolved(after = 0) { it.step(ReplyWatch.Event.WaitFailed(promptID = "msg_ios", error = ReplyWatch.WaitError.Failed("The build broke"))) }
                .contains(ReplyWatch.Effect.Announce(sessionID = "ses_ios", promptIDs = listOf("msg_ios"), text = notice)),
        )
        assertTrue(
            resolved(after = 1) { it.step(ReplyWatch.Event.WaitFailed(promptID = "msg_ios", error = ReplyWatch.WaitError.Failed("The build broke"))) }
                .contains(ReplyWatch.Effect.Announce(sessionID = "ses_ios", promptIDs = listOf("msg_ios"), text = VoiceHandoff.earlierRequest(notice))),
        )
    }

    /**
     * In the call: "Is it done yet?", the status read, then the result lands
     * and is announced, not silenced.
     */
    @Test fun theCallAnnouncesAResultAfterAStatusCheck() = runTest {
        val shared = SpeechTestShared()
        val voice = shared.run {
            VoiceFeature.State(server, OpenCodeSession(id = "ses_ios", serverTitle = "OpenCode iOS", projectID = "p", directory = "/Users/kit/ios", updatedAt = distantPast))
                .withPhase(VoiceFeature.State.Phase.Live)
                .stepping(ReplyWatch.Event.Sent(listOf(prompt)))
        }
        val announced = Locked(listOf<String>())
        val store = TestStore(voice, VoiceFeature(), this) {
            this[SharedStore] = shared.store
            this[Clock] = ImmediateClock()
            this[DateClient] = epoch
            this[VoiceCallClient] = speechVoiceCall(
                announce = { _, _, text -> announced.update { it + text } },
                cancelPendingAnnouncements = {},
            )
        }
        store.exhaustive = false
        store.send(VoiceFeature.Action.CallEvent(VoiceCallEvent.UserTranscript(itemID = "u9", text = "Is it done yet?", final = true)))
        store.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = "msg_ios", reply = "Renamed the acknowledgement rule.")))
        store.finish()
        assertEquals(listOf(VoiceHandoff.earlierRequest(reply("Renamed the acknowledgement rule."))), announced.value)
    }
}

class VoiceSuppressionTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val home = "ses_home"
    private val steered: List<ReplyWatch.Prompt>
        get() = listOf(
            ReplyWatch.Prompt(promptID = "msg_inbox", sessionID = home, title = "HOME", topic = "Check email, messages and X DMs and give a briefing"),
            ReplyWatch.Prompt(promptID = "msg_dms", sessionID = home, title = "HOME", topic = "Investigate how to read X DMs"),
            ReplyWatch.Prompt(promptID = "msg_narrow", sessionID = home, title = "HOME", topic = "Only notifications and important DMs"),
        )
    private val combined = "Karma Cafe closed at 4. Inbox: Olive asked about dinner; two X DMs need replies."

    private data class Announced(val promptIDs: List<String>, val text: String)

    private fun announcements(effects: List<ReplyWatch.Effect>): List<Announced> =
        effects.mapNotNull { (it as? ReplyWatch.Effect.Announce)?.let { a -> Announced(a.promptIDs, a.text) } }

    /**
     * The run as the server returns it (`/message`, newest first): four
     * voice delegations steered in one after another, tool steps between
     * them, and the run's one text reply at the end.
     */
    private val runJSON: ByteArray
        get() {
            fun handoff(instruction: String): String = VoiceHandoff.format(instruction = instruction, transcriptDelta = emptyList())
            fun user(id: String, text: String) = buildJsonObject { put("id", id); put("type", "user"); put("text", text) }
            fun assistant(id: String, type: String, text: String, partID: String? = null) = buildJsonObject {
                put("id", id); put("type", "assistant")
                put("content", buildJsonArray { add(buildJsonObject { put("type", type); partID?.let { p -> put("id", p) }; put("text", text) }) })
            }
            val chronological = listOf(
                user("msg_inbox", handoff("Check email, messages and X DMs and give a briefing")),
                assistant("msg_a1", "reasoning", "Reading mail"),
                user("msg_dms", handoff("Investigate how to read X DMs")),
                assistant("msg_a2", "reasoning", "Checking tools"),
                user("msg_narrow", handoff("Only notifications and important DMs")),
                user("msg_cafe", handoff("When does Karma Cafe close?")),
                assistant("msg_a3", "reasoning", "Looking up hours"),
                assistant("msg_a4", "text", combined, partID = "msg_a4-text-0"),
            )
            return JsonObject(mapOf("data" to JsonArray(chronological.reversed()))).toString().toByteArray()
        }

    /**
     * What a watched prompt's read finds, as `watchPrompt` reads it: its own
     * reply, else the run's.
     */
    private fun read(promptID: String): String? =
        assistantReply(runJSON, afterMessageID = promptID) ?: assistantReply(runJSON, afterMessageID = promptID, acrossSteers = true)

    @Test fun eachSteeredPromptReadsTheRunsReply() {
        // One delegation's own read stops at the next one steered in before any text.
        for (id in listOf("msg_inbox", "msg_dms", "msg_narrow")) {
            assertNull(id, assistantReply(runJSON, afterMessageID = id))
            assertEquals(id, combined, assistantReply(runJSON, afterMessageID = id, acrossSteers = true))
        }
        assertEquals(combined, assistantReply(runJSON, afterMessageID = "msg_cafe"))
        // The delegator's own read of the transcript goes past the steers too.
        val items = decodeTranscript(runJSON)
        assertEquals(combined, VoiceWorkClient.replyAfterPrompt(items, afterPromptID = "msg_narrow"))
        assertEquals(combined, VoiceWorkClient.replyAfterPrompt(items, afterPromptID = "msg_inbox"))
    }

    /**
     * The real order from the log: the delegator answers the cafe part, then
     * the three watched prompts' reads land, in any order.
     */
    @Test fun promptsTheDelegatorDidNotAnswerAreSpokenAsOneNotice() {
        val watch = Watch()
        watch.step(ReplyWatch.Event.Sent(steered))
        watch.step(ReplyWatch.Event.DelegatorStarted(requestID = "d1"))
        // The read is the session's whole reply; what counts is what's spoken.
        assertTrue(watch.step(ReplyWatch.Event.DelegatorAnswered(sessionID = home, reply = combined, promptIDs = listOf("msg_cafe"), requestID = "d1")).isEmpty())
        assertEquals(listOf("msg_inbox", "msg_dms", "msg_narrow"), watch.watched.map { it.promptID })

        val effects = mutableListOf<ReplyWatch.Effect>()
        for (id in listOf("msg_dms", "msg_narrow", "msg_inbox")) {
            effects += watch.step(ReplyWatch.Event.WaitSucceeded(promptID = id, reply = read(id)))
        }
        assertTrue("Held for the delegator's own answer", announcements(effects).isEmpty())
        val finished = watch.step(ReplyWatch.Event.DelegatorFinished(requestID = "d1", spoken = "Karma Cafe closed at 4 today."))
        assertTrue(finished.contains(ReplyWatch.Effect.CancelWireAnnouncements(promptIDs = listOf("msg_cafe"))))
        val spoken = announcements(finished)
        assertEquals(1, spoken.size)
        assertEquals(listOf("msg_inbox", "msg_dms", "msg_narrow"), spoken.firstOrNull()?.promptIDs)
        val text = spoken.firstOrNull()?.text ?: ""
        assertTrue(text.contains("This one reply answers 3 earlier requests: “Check email, messages and X DMs and give a briefing”, “Investigate how to read X DMs”, “Only notifications and important DMs”."))
        assertTrue(text.contains("You already told the user part of it: “Karma Cafe closed at 4 today.”. Say only what they haven't heard yet."))
        assertFalse("Never the parts that weren't spoken", text.contains("part of it: “$combined”"))
        assertTrue(text.contains(combined))
        assertFalse(text.contains("finished without a text reply"))
        assertTrue(watch.watched.isEmpty())
        assertEquals(List(3) { ReplyWatch.Outcome.Replied(combined) }, watch.completed.map { it.outcome })
        assertTrue("Nothing of that run is left to tell", watch.delegatorReplies.isEmpty())
    }

    /** The other order: the reads land first, then the delegator answers. */
    @Test fun aRunResolvedBeforeTheDelegatorAnswersStillSaysWhatItTold() {
        val watch = Watch()
        watch.step(ReplyWatch.Event.Sent(steered))
        watch.step(ReplyWatch.Event.DelegatorStarted(requestID = "d1"))
        val effects = mutableListOf<ReplyWatch.Effect>()
        for (id in listOf("msg_narrow", "msg_inbox", "msg_dms")) {
            effects += watch.step(ReplyWatch.Event.WaitSucceeded(promptID = id, reply = read(id)))
        }
        assertTrue(announcements(effects).isEmpty())
        watch.step(ReplyWatch.Event.DelegatorAnswered(sessionID = home, reply = combined, promptIDs = listOf("msg_cafe"), requestID = "d1"))
        val spoken = announcements(watch.step(ReplyWatch.Event.DelegatorFinished(requestID = "d1", spoken = "Karma Cafe closed at 4 today.")))
        assertEquals(1, spoken.size)
        assertEquals(true, spoken.firstOrNull()?.text?.contains("You already told the user part of it: “Karma Cafe closed at 4 today.”"))
        assertTrue(watch.delegatorReplies.isEmpty())
    }

    /** What the delegator said is only for results of the same run. */
    @Test fun whatTheDelegatorSaidNeverReachesALaterUnrelatedResult() {
        val watch = Watch()
        // Nothing else of the run is left: nothing is kept.
        watch.step(ReplyWatch.Event.DelegatorStarted(requestID = "d1"))
        watch.step(ReplyWatch.Event.DelegatorAnswered(sessionID = home, reply = combined, promptIDs = listOf("msg_cafe"), requestID = "d1"))
        watch.step(ReplyWatch.Event.DelegatorFinished(requestID = "d1", spoken = "Karma Cafe closed at 4 today."))
        assertTrue(watch.delegatorReplies.isEmpty())
        watch.step(ReplyWatch.Event.Sent(listOf(steered[1])))
        val later = announcements(watch.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_dms", reply = "Use the browser.")))
        assertEquals(listOf(ReplyWatch.Outcome.Replied("Use the browser.").notice("HOME")), later.map { it.text })

        // Kept for the run's other result, then gone once that's told.
        val run = Watch()
        run.step(ReplyWatch.Event.Sent(listOf(steered[0])))
        run.step(ReplyWatch.Event.DelegatorStarted(requestID = "d2"))
        run.step(ReplyWatch.Event.DelegatorAnswered(sessionID = home, reply = combined, promptIDs = listOf("msg_cafe"), requestID = "d2"))
        run.step(ReplyWatch.Event.DelegatorFinished(requestID = "d2", spoken = "Karma Cafe closed at 4 today."))
        assertEquals(setOf("msg_inbox"), run.delegatorReplies[home]?.promptIDs)
        assertEquals("Karma Cafe closed at 4 today.", run.delegatorReplies[home]?.reply)
        val first = announcements(run.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_inbox", reply = combined)))
        assertEquals(true, first.firstOrNull()?.text?.contains("You already told the user"))
        assertTrue(run.delegatorReplies.isEmpty())
        run.step(ReplyWatch.Event.Sent(listOf(steered[2])))
        val next = announcements(run.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_narrow", reply = "Done.")))
        assertEquals(false, next.firstOrNull()?.text?.contains("You already told the user"))
    }

    /**
     * A read of the idle session covers every prompt the call tracks there,
     * resolved ones whose results may still be held included.
     */
    @Test fun aReadCancelsHeldResultsForEveryPromptInTheSession() = runBlocking {
        val shared = SpeechTestShared()
        val session = OpenCodeSession(id = home, serverTitle = "HOME", projectID = "p", directory = "/Users/kit/home", updatedAt = distantPast)
        val voice = shared.run {
            shared(SharedKeys.openAIAPIKey).set("sk-test")
            VoiceFeature.State(server, session)
                .withPhase(VoiceFeature.State.Phase.Live)
                .copy(usesDelegator = true)
                .stepping(ReplyWatch.Event.Sent(listOf(steered[0])))
                .stepping(ReplyWatch.Event.WaitSucceeded(promptID = "msg_inbox", reply = "Inbox: two DMs."))
                .stepping(ReplyWatch.Event.Sent(listOf(steered[1], steered[2])))
                .recording(VoiceTranscriptTail.Role.user, "What did HOME say?", id = "u1")
        }
        val cancelled = Locked(listOf<List<String>>())
        val effects = detachedScope(coroutineContext)
        val store = TestStore(voice, VoiceFeature(), effects) {
            this[SharedStore] = shared.store
            this[Clock] = ImmediateClock()
            this[DateClient] = epoch
            this[VoiceDelegatorClient] = VoiceDelegatorClient(isEnabled = true) { _, input, _ ->
                if (input.firstOrNull() is VoiceDelegator.InputItem.ToolOutput) {
                    VoiceDelegator.Response(id = "r2", calls = emptyList(), text = "HOME says two DMs need replies.")
                } else {
                    VoiceDelegator.Response(id = "r1", calls = listOf(VoiceDelegator.ToolCall(callID = "t_read", name = "read_session", arguments = "{}")), text = "")
                }
            }
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                isSessionActive = { _, _ -> false },
                transcript = { _, _, _ -> decodeTranscript("""{"data":[]}""".toByteArray()) },
            )
            this[VoiceCallClient] = speechVoiceCall(cancelPendingAnnouncements = { ids -> cancelled.update { it + listOf(ids) } })
        }
        store.exhaustive = false
        store.send(VoiceFeature.Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = emptyArguments)))
        store.receive(::isDelegatorFinished, timeout = 5.seconds)
        assertEquals(listOf(listOf("msg_inbox", "msg_dms", "msg_narrow")), cancelled.value)
        assertTrue(store.state.replyWatch.watched.isEmpty())
        store.finish()
        effects.skipInFlightEffects()
    }

    @Test fun aFailedDelegatorAnswerTellsNothing() {
        val watch = Watch()
        watch.step(ReplyWatch.Event.Sent(listOf(steered[0])))
        watch.step(ReplyWatch.Event.DelegatorStarted(requestID = "d1"))
        watch.step(ReplyWatch.Event.DelegatorAnswered(sessionID = home, reply = combined, promptIDs = listOf("msg_inbox"), requestID = "d1"))
        assertTrue("Held for the answer", announcements(watch.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_inbox", reply = combined))).isEmpty())
        val finished = watch.step(ReplyWatch.Event.DelegatorFinished(requestID = "d1", spoken = null))
        assertFalse(finished.any { it is ReplyWatch.Effect.CancelWireAnnouncements })
        assertEquals(listOf(ReplyWatch.Outcome.Replied(combined).notice("HOME")), announcements(finished).map { it.text })
        assertTrue(watch.delegatorReplies.isEmpty())
    }

    // MARK: Through the call

    private val homeSession: OpenCodeSession
        get() = OpenCodeSession(id = home, serverTitle = "HOME", projectID = "p", directory = "/Users/kit/home", updatedAt = distantPast)

    private fun delegatorCall(shared: SpeechTestShared): VoiceFeature.State = shared.run {
        shared(SharedKeys.openAIAPIKey).set("sk-test")
        VoiceFeature.State(server, homeSession)
            .withPhase(VoiceFeature.State.Phase.Live)
            .copy(usesDelegator = true)
            .recording(VoiceTranscriptTail.Role.user, "When does Karma Cafe close?", id = "u1")
    }

    /**
     * The run from the log, end to end: three watched requests steered into
     * HOME, then a request the delegator sends and waits on itself. Their
     * reads land while it works; the notice that follows its answer says what
     * it actually said, not the session's whole reply it read.
     */
    @Test fun theDelegatorsSpokenAnswerIsWhatCountsAsTold() {
        runCafeRequest(finalRoundFails = false) { announced ->
            assertEquals(1, announced.size)
            val text = announced.firstOrNull()?.text ?: ""
            assertTrue(text.contains("You already told the user part of it: “Karma Cafe closed at 4 today.”"))
            assertFalse(text.contains("part of it: “$combined"))
            assertTrue("The inbox part is still to be said", text.contains(combined))
            // The cafe request's read landed too, before the answer: it's the delegator's, not the notice's.
            assertEquals(listOf("msg_inbox", "msg_dms", "msg_narrow"), announced.firstOrNull()?.promptIDs)
            assertTrue(text.contains("This one reply answers 3 earlier requests"))
            assertFalse(text.contains("“When does Karma Cafe close?”"))
        }
    }

    /**
     * If the delegator's last round fails, nothing it read counts as told:
     * every result, the one it waited on included, is still spoken.
     */
    @Test fun aFailedFinalRoundLeavesEveryResultToBeSpoken() {
        runCafeRequest(finalRoundFails = true) { announced ->
            assertEquals(1, announced.size)
            assertEquals(true, announced.firstOrNull()?.promptIDs?.contains("msg_cafe"))
            assertEquals(false, announced.firstOrNull()?.text?.contains("You already told the user"))
        }
    }

    private fun runCafeRequest(finalRoundFails: Boolean, check: (List<Announced>) -> Unit) = runBlocking {
        val shared = SpeechTestShared()
        val released = CompletableDeferred<Unit>()
        // The answer waits until all four reads came back.
        val reads = Locked(0)
        val readsLanded = CompletableDeferred<Unit>()
        fun readLanded() { if (reads.withValue { (it + 1) to (it + 1) } == 4) readsLanded.complete(Unit) }
        val round = Locked(0)
        val announced = Locked(listOf<Announced>())
        val json = runJSON
        val transcript = decodeTranscript(json)
        val effects = detachedScope(coroutineContext)
        val store = TestStore(delegatorCall(shared), VoiceFeature(), effects) {
            this[SharedStore] = shared.store
            // A test clock: held results wait for the answer, not a timer.
            this[Clock] = TestClock()
            this[DateClient] = epoch
            this[VoiceDelegatorClient] = VoiceDelegatorClient(isEnabled = true) { _, _, _ ->
                when (round.withValue { (it + 1) to (it + 1) }) {
                    1 -> VoiceDelegator.Response(id = "r1", calls = listOf(VoiceDelegator.ToolCall(callID = "t1", name = "send_message", arguments = """{"text":"When does Karma Cafe close?"}""")), text = "")
                    2 -> VoiceDelegator.Response(id = "r2", calls = listOf(VoiceDelegator.ToolCall(callID = "t2", name = "wait_for_session", arguments = "{}")), text = "")
                    else -> {
                        // The watched reads land while the delegator writes its answer.
                        released.complete(Unit)
                        readsLanded.await()
                        repeat(20) { kotlinx.coroutines.yield() }
                        if (finalRoundFails) throw OpenCodeClientError("The delegator's reply wasn't readable.")
                        VoiceDelegator.Response(id = "r3", calls = emptyList(), text = "Karma Cafe closed at 4 today.")
                    }
                }
            }
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                sendDeliveredPrompt = { _, _, _, _ -> "msg_cafe" },
                // Every steer reads its target's requests first: none here.
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                waitForIdle = { _, _ -> },
                transcript = { _, _, _ -> transcript },
            )
            this[VoiceWorkClient] = VoiceWorkClient.testValue.let { work ->
                VoiceWorkClient(
                    submit = work.submit,
                    awaitReply = { _, _, promptID ->
                        released.await()
                        assistantReply(json, afterMessageID = promptID).also { if (it != null) readLanded() }
                    },
                    fetchRunReply = { _, _, promptID -> assistantReply(json, afterMessageID = promptID, acrossSteers = true).also { readLanded() } },
                    checkSessionActive = work.checkSessionActive,
                    recentMessages = work.recentMessages,
                )
            }
            this[VoiceCallClient] = speechVoiceCall(
                announce = { _, ids, text -> announced.update { it + Announced(ids, text) } },
            )
        }
        store.exhaustive = false
        store.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.Sent(steered)))
        store.send(VoiceFeature.Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = emptyArguments)))
        store.receive(::isDelegatorFinished, timeout = 10.seconds)
        store.eventuallyReducing(timeout = 5.seconds) { announced.value.isNotEmpty() }
        check(announced.value)
        effects.skipInFlightEffects()
    }

    /**
     * A read of the session reports its newest reply, not the reply to an
     * older prompt the call tracked, when a newer one (typed here) followed.
     */
    @Test fun aReadReportsTheSessionsNewestReply() = runBlocking {
        val shared = SpeechTestShared()
        val voice = delegatorCall(shared)
            .stepping(ReplyWatch.Event.Sent(listOf(ReplyWatch.Prompt(promptID = "msg_turn1", sessionID = home, title = "HOME"))))
            .stepping(ReplyWatch.Event.WaitSucceeded(promptID = "msg_turn1", reply = "Turn one reply."))
        val outputs = Locked(listOf<String>())
        val effects = detachedScope(coroutineContext)
        val store = TestStore(voice, VoiceFeature(), effects) {
            this[SharedStore] = shared.store
            this[Clock] = ImmediateClock()
            this[DateClient] = epoch
            this[VoiceDelegatorClient] = VoiceDelegatorClient(isEnabled = true) { _, input, _ ->
                val first = input.firstOrNull()
                if (first is VoiceDelegator.InputItem.ToolOutput) {
                    outputs.update { it + first.output }
                    VoiceDelegator.Response(id = "r2", calls = emptyList(), text = "It says turn two.")
                } else {
                    VoiceDelegator.Response(id = "r1", calls = listOf(VoiceDelegator.ToolCall(callID = "t1", name = "wait_for_session", arguments = "{}")), text = "")
                }
            }
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                waitForIdle = { _, _ -> },
                transcript = { _, _, _ ->
                    listOf(
                        userText("msg_turn1", "First request"),
                        assistantMarkdown("a1-text-0", "Turn one reply."),
                        userText("msg_typed", "Typed follow-up"),
                        assistantMarkdown("a2-text-0", "Turn two reply."),
                    )
                },
            )
            this[VoiceCallClient] = speechVoiceCall()
        }
        store.exhaustive = false
        store.send(VoiceFeature.Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = emptyArguments)))
        store.receive(::isDelegatorFinished, timeout = 5.seconds)
        assertEquals(true, outputs.value.firstOrNull()?.contains("Turn two reply."))
        assertEquals(false, outputs.value.firstOrNull()?.contains("Turn one reply."))
        store.finish()
        effects.skipInFlightEffects()
    }

    /**
     * A wait reports the session's newest reply, and covers only the tracked
     * prompts that reply answers: an earlier prompt with a reply of its own
     * still speaks.
     */
    @Test fun aWaitCoversOnlyThePromptsItsReplyAnswers() = runBlocking {
        val shared = SpeechTestShared()
        val voice = delegatorCall(shared).stepping(
            ReplyWatch.Event.Sent(
                listOf(
                    ReplyWatch.Prompt(promptID = "msg_old", sessionID = home, title = "HOME"),
                    ReplyWatch.Prompt(promptID = "msg_new", sessionID = home, title = "HOME"),
                ),
            ),
        )
        val outputs = Locked(listOf<String>())
        val cancelled = Locked(listOf<List<String>>())
        val effects = detachedScope(coroutineContext)
        val store = TestStore(voice, VoiceFeature(), effects) {
            this[SharedStore] = shared.store
            this[Clock] = ImmediateClock()
            this[DateClient] = epoch
            this[VoiceDelegatorClient] = VoiceDelegatorClient(isEnabled = true) { _, input, _ ->
                val first = input.firstOrNull()
                if (first is VoiceDelegator.InputItem.ToolOutput) {
                    outputs.update { it + first.output }
                    VoiceDelegator.Response(id = "r2", calls = emptyList(), text = "It says the new thing.")
                } else {
                    VoiceDelegator.Response(id = "r1", calls = listOf(VoiceDelegator.ToolCall(callID = "t1", name = "wait_for_session", arguments = "{}")), text = "")
                }
            }
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                waitForIdle = { _, _ -> },
                transcript = { _, _, _ ->
                    listOf(
                        userText("msg_old", "Old request"),
                        assistantMarkdown("a1-text-0", "Old reply."),
                        userText("msg_new", "New request"),
                        assistantMarkdown("a2-text-0", "New reply."),
                    )
                },
            )
            this[VoiceCallClient] = speechVoiceCall(cancelPendingAnnouncements = { ids -> cancelled.update { it + listOf(ids) } })
        }
        store.exhaustive = false
        store.send(VoiceFeature.Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = emptyArguments)))
        store.receive(::isDelegatorFinished, timeout = 5.seconds)
        assertEquals(true, outputs.value.firstOrNull()?.contains("New reply."))
        assertEquals(false, outputs.value.firstOrNull()?.contains("Old reply."))
        assertEquals(listOf(listOf("msg_new")), cancelled.value)
        assertEquals(listOf("msg_old"), store.state.replyWatch.watched.map { it.promptID })
        effects.skipInFlightEffects()
    }

    @Test fun newestReplyCoversPromptsSteeredInBeforeItsTextAndSentAfter() {
        val items = decodeTranscript(runJSON)
        val all = listOf("msg_inbox", "msg_dms", "msg_narrow", "msg_cafe")
        val newest = VoiceWorkClient.newestReply(items, tracked = all)
        assertEquals(combined, newest.reply)
        assertEquals(all.toSet(), newest.promptIDs)
        val none = VoiceWorkClient.newestReply(listOf(userText("msg_x", "Hi")), tracked = listOf("msg_x"))
        assertTrue(none.reply == null && none.promptIDs == setOf("msg_x"))
        // The newest prompt only ran tools: no reply, and the one before keeps its own.
        val toolOnly = VoiceWorkClient.newestReply(
            listOf(
                userText("msg_a", "First"),
                assistantMarkdown("a1-text-0", "First reply."),
                userText("msg_b", "Second"),
            ),
            tracked = listOf("msg_a", "msg_b"),
        )
        assertTrue(toolOnly.reply == null && toolOnly.promptIDs == setOf("msg_b"))
    }

    /**
     * An answer covering part of a held notice's run takes its prompts out
     * of the notice, which names only the rest.
     */
    @Test fun aHeldNoticeLosesThePromptsTheDelegatorAnswered() {
        val notice = ReplyWatch.HeldNotice(
            promptID = "msg_cafe", promptIDs = listOf("msg_inbox", "msg_dms", "msg_cafe"), sessionID = home, title = "HOME",
            outcome = ReplyWatch.Outcome.Replied(combined), topics = listOf("Briefing", "DMs", "Cafe"), turn = 0,
        )
        val rest = notice.removing(setOf("msg_cafe"))
        assertEquals(listOf("msg_inbox", "msg_dms"), rest.promptIDs)
        assertEquals(listOf("Briefing", "DMs"), rest.topics)
        assertEquals("msg_dms", rest.promptID)
        assertEquals("Fully covered notices are dropped elsewhere", notice, notice.removing(setOf("msg_inbox", "msg_dms", "msg_cafe")))
        assertEquals(notice, notice.removing(setOf("msg_other")))
    }

    /**
     * A run resolves only once every prompt in it has its own read: the last
     * one landing first doesn't drop the others' text.
     */
    @Test fun aRunWaitsForEveryPromptsRead() = runTest {
        val shared = SpeechTestShared()
        val released = mapOf("msg_inbox" to CompletableDeferred<Unit>(), "msg_dms" to CompletableDeferred(), "msg_narrow" to CompletableDeferred())
        val announced = Locked(listOf<String>())
        val replies: Map<String, String?> = mapOf("msg_inbox" to "Inbox: Olive asked about dinner.", "msg_dms" to null, "msg_narrow" to combined)
        val effects = detachedScope(coroutineContext)
        val store = TestStore(delegatorCall(shared), VoiceFeature(), effects) {
            this[SharedStore] = shared.store
            this[Clock] = ImmediateClock()
            this[DateClient] = epoch
            this[VoiceWorkClient] = VoiceWorkClient.testValue.let { work ->
                VoiceWorkClient(
                    submit = work.submit,
                    awaitReply = { _, _, promptID -> released.getValue(promptID).await(); replies[promptID] },
                    checkSessionActive = work.checkSessionActive,
                    recentMessages = work.recentMessages,
                )
            }
            this[VoiceCallClient] = speechVoiceCall(announce = { _, _, text -> announced.update { it + text } })
        }
        store.exhaustive = false
        store.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.Sent(steered)))
        released.getValue("msg_narrow").complete(Unit)
        store.receive(::isReplyWatch, timeout = 5.seconds)
        delay(100)
        assertTrue("The others' reads haven't landed", announced.value.isEmpty())
        assertEquals(3, store.state.replyWatch.watched.size)
        released.getValue("msg_inbox").complete(Unit)
        released.getValue("msg_dms").complete(Unit)
        store.eventuallyReducing(timeout = 5.seconds) { announced.value.isNotEmpty() }
        assertEquals(1, announced.value.size)
        assertEquals(true, announced.value.firstOrNull()?.contains("Inbox: Olive asked about dinner."))
        assertEquals(true, announced.value.firstOrNull()?.contains(combined))
        store.skipReceivedActions()
        assertTrue(store.state.replyWatch.watched.isEmpty())
        effects.skipInFlightEffects()
    }

    /**
     * A prompt the delegator sends is tracked from the moment it's sent:
     * an overlapping request sees it, and a later round failing doesn't lose
     * its result.
     */
    @Test fun aSentPromptIsTrackedFromTheMomentItsSent() = runBlocking {
        val shared = SpeechTestShared()
        val failNow = CompletableDeferred<Unit>()
        val round = Locked(0)
        val announced = Locked(listOf<Announced>())
        val effects = detachedScope(coroutineContext)
        val store = TestStore(delegatorCall(shared), VoiceFeature(), effects) {
            this[SharedStore] = shared.store
            this[Clock] = ImmediateClock()
            this[DateClient] = epoch
            this[VoiceDelegatorClient] = VoiceDelegatorClient(isEnabled = true) { _, _, _ ->
                if (round.withValue { (it + 1) to (it + 1) } == 1) {
                    VoiceDelegator.Response(id = "r1", calls = listOf(VoiceDelegator.ToolCall(callID = "t1", name = "send_message", arguments = """{"text":"Rename the drawer button"}""")), text = "")
                } else {
                    failNow.await()
                    throw OpenCodeClientError("The delegator's reply wasn't readable.")
                }
            }
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                sendDeliveredPrompt = { _, _, _, _ -> "msg_rename" },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
            )
            this[VoiceWorkClient] = VoiceWorkClient.testValue.let { work ->
                VoiceWorkClient(
                    submit = work.submit,
                    awaitReply = { _, _, _ ->
                        failNow.await()
                        // Lands after the failed round.
                        repeat(20) { kotlinx.coroutines.yield() }
                        "Renamed the drawer button."
                    },
                    checkSessionActive = work.checkSessionActive,
                    recentMessages = work.recentMessages,
                )
            }
            this[VoiceCallClient] = speechVoiceCall(announce = { _, ids, text -> announced.update { it + Announced(ids, text) } })
        }
        store.exhaustive = false
        store.send(VoiceFeature.Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = emptyArguments)))
        store.receive(::isReplyWatch, timeout = 5.seconds)
        // Still running, and already watched.
        assertEquals(listOf("d1"), store.state.delegatorWork)
        assertEquals(listOf("msg_rename"), store.state.replyWatch.watched.map { it.promptID })
        failNow.complete(Unit)
        store.receive(::isDelegatorFinished, timeout = 5.seconds)
        store.eventuallyReducing(timeout = 5.seconds) { announced.value.isNotEmpty() }
        assertEquals(listOf(listOf("msg_rename")), announced.value.map { it.promptIDs })
        assertEquals(true, announced.value.firstOrNull()?.text?.contains("Renamed the drawer button."))
        effects.skipInFlightEffects()
    }

    @Test fun thePromptTheDelegatorAnsweredIsStillSuppressed() {
        val watch = Watch()
        watch.step(ReplyWatch.Event.Sent(listOf(steered[0], steered[1])))
        watch.step(ReplyWatch.Event.DelegatorStarted(requestID = "d1"))
        watch.step(ReplyWatch.Event.DelegatorAnswered(sessionID = home, reply = "Here's your briefing.", promptIDs = listOf("msg_inbox"), requestID = "d1"))
        val effects = watch.step(ReplyWatch.Event.DelegatorFinished(requestID = "d1", spoken = "Here's your briefing."))
        assertTrue(effects.contains(ReplyWatch.Effect.Log(ReplyWatch.LogEntry.NoticeSuppressed(title = "HOME"))))
        assertTrue(effects.contains(ReplyWatch.Effect.CancelWireAnnouncements(promptIDs = listOf("msg_inbox"))))
        assertEquals(listOf("msg_dms"), watch.watched.map { it.promptID })
        assertTrue(watch.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_inbox", reply = "Here's your briefing.")).isEmpty())
        // A lone result with nothing already told keeps the plain notice.
        val plain = Watch()
        plain.step(ReplyWatch.Event.Sent(listOf(steered[1])))
        val notice = announcements(plain.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_dms", reply = "Use the browser.")))
        assertEquals(listOf(ReplyWatch.Outcome.Replied("Use the browser.").notice("HOME")), notice.map { it.text })
    }

    @Test fun readsReturnTheWholeReplyOrSayHowMuchIsLeft() {
        val short = (1..40).joinToString("\n") { "- Item $it: Olive at 4:23 asked about dinner plans" }
        assertTrue(short.length > 400)
        assertEquals(short, VoiceWorkClient.bounded(short))
        val long = (1..200).joinToString("\n") { "- Item $it: Olive at 4:23 asked about dinner plans" }
        val cut = VoiceWorkClient.bounded(long)
        assertTrue(cut.length < long.length && cut.length <= VoiceWorkClient.replyReadLimit + 60)
        val marker = cut.indexOf("… (truncated, ")
        assertTrue(marker >= 0)
        val kept = cut.substring(0, marker)
        assertTrue(long.startsWith(kept))
        assertEquals("Cut at a line break, never mid-item", '\n', long[kept.length])
        assertTrue(cut.endsWith("more characters)"))
        assertTrue(cut.contains("(truncated, ${long.length - kept.length} more characters)"))

        val items = listOf(
            userText("msg_inbox", "Brief me"),
            assistantMarkdown("a1-text-0", short),
        )
        val reply = VoiceWorkClient.replyAfterPrompt(items, afterPromptID = "msg_inbox") ?: ""
        assertTrue(reply.length > 1_000 && !reply.contains("…"))
        val messages = VoiceWorkClient.formatRecentMessages(items, fullLatestReply = true)
        assertEquals(true, messages.lastOrNull()?.contains("Item 40"))
        assertEquals("Other uses stay short", true, VoiceWorkClient.formatRecentMessages(items).lastOrNull()?.endsWith("…"))
    }

    /**
     * The delegator's read reports only the prompt it read after, and the
     * whole reply.
     */
    @Test fun readSessionReportsItsOwnPromptAndTheWholeReply() = runBlocking {
        val session = OpenCodeSession(id = home, serverTitle = "HOME", projectID = "p", directory = "/Users/kit/home", updatedAt = distantPast)
        val short = (1..40).joinToString("\n") { "- Item $it: Olive at 4:23 asked about dinner plans" }
        val client = OpenCodeClient.testValue.copy(
            getSession = { _, _ -> session },
            isSessionActive = { _, _ -> false },
            transcript = { _, _, _ -> listOf(userText("msg_narrow", "Only notifications"), assistantMarkdown("a1-text-0", short)) },
        )
        val answered = Locked(listOf<Pair<String?, List<String>>>())
        val workspace = VoiceDelegatorWorkspace(
            client = client,
            start = VoiceDelegatorWorkspace.Start(
                server = server, viewing = session, draftDirectory = "/Users/kit/home", draftModel = null, transcriptDelta = emptyList(),
                explained = emptySet(), watchedPromptIDs = mapOf(home to listOf("msg_narrow")),
            ),
            effects = VoiceDelegatorWorkspace.Effects(
                open = {}, explained = {}, answered = { _, reply, ids -> answered.update { it + (reply to ids) } }, note = {},
            ),
        )
        val output = workspace.run(VoiceDelegator.ToolCall(callID = "c", name = "read_session", arguments = "{}"))
        assertTrue(output.contains("Item 40"))
        assertEquals(1, answered.value.size)
        assertEquals(listOf("msg_narrow"), answered.value.firstOrNull()?.second)
        assertEquals(true, answered.value.firstOrNull()?.first?.contains("Item 40"))
    }

    // MARK: The delegator read a design cut at 400 characters ("| The 1…") and said the reply it
    // could see was cut off, so it wouldn't invent the rules.

    private val gameEngine = OpenCodeSession(id = "ses_game", serverTitle = "GAME ENGINE", projectID = "p", directory = "/Users/kit/game", updatedAt = distantPast)

    private fun gameWorkspace(client: OpenCodeClient): VoiceDelegatorWorkspace = VoiceDelegatorWorkspace(
        client = client,
        start = VoiceDelegatorWorkspace.Start(server = server, viewing = gameEngine, draftDirectory = gameEngine.directory, draftModel = null, transcriptDelta = emptyList(), explained = emptySet()),
        effects = VoiceDelegatorWorkspace.Effects(open = {}, explained = {}, note = {}),
    )

    private fun messages(output: String): List<String> =
        (jsonObject(output)["messages"] as JsonArray).map { it.jsonPrimitive.content }

    @Test fun readSessionGivesTheWholeReplyAndNamesWhereTheRestOfEveryOtherMessageIs() = runBlocking {
        assertTrue(designReply.length > 400 && designReply.indexOf("cocooning every critter") > 400)
        val earlier = "An earlier idea: a stealth mode where the spider hunts by vibration alone. ".repeat(12)
        val client = OpenCodeClient.testValue.copy(
            getSession = { _, _ -> gameEngine },
            isSessionActive = { _, _ -> false },
            transcript = { _, _, _ ->
                listOf(
                    userText("msg_first", "Pitch me a spider game"),
                    assistantMarkdown("msg_pitch-text-0", earlier),
                    userText("msg_more", "Make the spider game more interesting"),
                    assistantMarkdown("msg_design-text-0", designReply),
                )
            },
        )
        val messages = messages(gameWorkspace(client).run(VoiceDelegator.ToolCall(callID = "c", name = "read_session", arguments = """{"session_id":"ses_game"}""")))
        val latest = messages.last()
        assertTrue(latest.contains("The spider wins by cocooning every critter before the heist is done."))
        assertTrue(latest.contains("uncurled it sees the spider's web lines"))
        assertFalse("The newest reply whole, not cut at 400", latest.contains("truncated"))
        val pitch = messages.first { it.contains("stealth mode") }
        assertTrue(pitch.contains("… [truncated: "))
        assertTrue(pitch.endsWith(" more characters; call read_session with message_id \"msg_pitch-text-0\" to read all]"))
        for (message in messages.filter { it.contains("…") }) {
            assertTrue("Never a silent cut: ${message.take(60)}", message.contains("[truncated: "))
        }
    }

    /** A reply split by tool calls comes whole, every text part of it. */
    @Test fun readSessionGivesEveryTextPartOfTheNewestReply() = runBlocking {
        val rules = "Rule: the spider spins one web per round. ".repeat(20)
        val client = OpenCodeClient.testValue.copy(
            getSession = { _, _ -> gameEngine },
            isSessionActive = { _, _ -> false },
            transcript = { _, _, _ ->
                listOf(
                    userText("msg_rules", "Recap the rules, then plan the architecture"),
                    assistantMarkdown("msg_reply-text-0", rules),
                    OpenCodeTranscriptItem.Tool(id = "msg_reply-tool-1", call = toolCall("read")),
                    assistantMarkdown("msg_reply-text-1", "Below is the architecture plan around Durable Objects."),
                )
            },
        )
        val messages = messages(gameWorkspace(client).run(VoiceDelegator.ToolCall(callID = "c", name = "read_session", arguments = "{}")))
        assertTrue(messages.any { it.contains(rules.trim(' ')) && !it.contains("truncated") })
        assertEquals("assistant: Below is the architecture plan around Durable Objects.", messages.lastOrNull())
    }

    @Test fun readSessionByMessageIDReadsOneMessageWholeAndPagesALongOne() = runBlocking {
        val long = (1..300).joinToString("\n") { "- Line $it: the spider's web holds a crumb for three seconds." }
        assertTrue(long.length > VoiceWorkClient.messageReadLimit)
        val asked = Locked(listOf<String>())
        val client = OpenCodeClient.testValue.copy(
            getSession = { _, _ -> gameEngine },
            message = { _, _, messageID ->
                asked.update { it + messageID }
                listOf(
                    assistantMarkdown("msg_plan-text-0", "Rules first."),
                    assistantMarkdown("msg_plan-text-1", long),
                )
            },
        )
        val workspace = gameWorkspace(client)
        suspend fun read(arguments: String): JsonObject =
            jsonObject(workspace.run(VoiceDelegator.ToolCall(callID = "c", name = "read_session", arguments = arguments)))

        val first = read("""{"message_id":"msg_plan-text-1"}""")
        assertEquals("Reads the one message from the server, not the transcript", listOf("msg_plan"), asked.value)
        assertEquals("assistant", first["role"]?.jsonPrimitive?.content)
        assertEquals(long.trim().length, first["total_characters"]?.jsonPrimitive?.content?.toInt())
        val page = first["text"]!!.jsonPrimitive.content
        assertTrue(page.startsWith("- Line 1:"))
        val marker = page.indexOf("… [truncated: ")
        assertTrue(marker >= 0)
        val kept = marker
        assertTrue(page.endsWith("call read_session with message_id \"msg_plan-text-1\" and offset $kept to read on]"))

        val rest = read("""{"message_id":"msg_plan-text-1","offset":$kept}""")["text"]!!.jsonPrimitive.content
        val markdown = long.trim()
        assertEquals("The two pages are the whole message", markdown, page.substring(0, marker) + rest)
        assertTrue(rest.contains("- Line 300: the spider's web holds a crumb for three seconds."))
        assertFalse(rest.contains("truncated"))

        // The bare message ID reads all of its text parts.
        val whole = read("""{"message_id":"msg_plan"}""")["text"]!!.jsonPrimitive.content
        assertTrue(whole.startsWith("Rules first.\n\n- Line 1:"))

        val missing = workspace.run(VoiceDelegator.ToolCall(callID = "c", name = "read_session", arguments = """{"message_id":"msg_gone-text-0"}"""))
        assertTrue(missing.contains("\"error\""))
    }

    /** A wait returns the whole reply, not only the line after its last tool. */
    @Test fun waitForSessionReturnsEveryTextPartOfTheReply() = runBlocking {
        val rules = "The spider wins by cocooning every critter before the heist is done."
        val client = OpenCodeClient.testValue.copy(
            getSession = { _, _ -> gameEngine },
            waitForIdle = { _, _ -> },
            transcript = { _, _, _ ->
                listOf(
                    userText("msg_rules", "Recap the rules into rules.md"),
                    assistantMarkdown("msg_reply-text-0", rules),
                    OpenCodeTranscriptItem.Tool(id = "msg_reply-tool-1", call = toolCall("write")),
                    assistantMarkdown("msg_reply-text-1", "Saved to rules.md."),
                )
            },
        )
        val output = gameWorkspace(client).run(VoiceDelegator.ToolCall(callID = "c", name = "wait_for_session", arguments = "{}"))
        assertEquals("$rules\n\nSaved to rules.md.", jsonObject(output)["latest_reply"]?.jsonPrimitive?.content)

        // A prompt after the last reply has none yet: not the older one.
        val waiting = client.copy(
            transcript = { _, _, _ ->
                listOf(
                    userText("msg_rules", "Recap the rules"),
                    assistantMarkdown("msg_reply-text-0", rules),
                    userText("msg_more", "Now plan the backend"),
                )
            },
        )
        val none = gameWorkspace(waiting).run(VoiceDelegator.ToolCall(callID = "c", name = "wait_for_session", arguments = "{}"))
        assertTrue(none.contains(""""latest_reply":"""""))
    }

    @Test fun waitForSessionEndsWhenTheUserSpeaks() = runTest {
        val sleeping = OpenCodeClient.testValue.copy(
            getSession = { _, _ -> gameEngine },
            waitForIdle = { _, _ -> delay(600.seconds) },
            // A read after the yield would hang: the answer can't wait on it.
            transcript = { _, _, _ -> delay(600.seconds); emptyList() },
        )
        val notes = mutableListOf<String>()
        val waits = mutableListOf<Boolean>()
        val workspace = VoiceDelegatorWorkspace(
            client = sleeping,
            start = VoiceDelegatorWorkspace.Start(server = server, viewing = gameEngine, draftDirectory = gameEngine.directory, draftModel = null, transcriptDelta = emptyList(), explained = emptySet()),
            effects = VoiceDelegatorWorkspace.Effects(
                open = {},
                explained = {},
                waiting = { _, isWaiting -> waits += isWaiting },
                userSpoke = { delay(50) },
                note = { notes += it },
            ),
        )
        // The wait and the read never finish on their own: only the user's words
        // can have ended it.
        val output = workspace.run(VoiceDelegator.ToolCall(callID = "c", name = "wait_for_session", arguments = "{}"))
        val json = jsonObject(output)
        assertEquals(false, json["finished"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
        assertEquals(VoiceDelegatorWorkspace.userSpokeNote, json["user_spoke"]?.jsonPrimitive?.content)
        assertEquals(listOf(true, false), waits)
        assertTrue(notes.contains("Stopped waiting on “${gameEngine.title}”: the user spoke."))

        // Nor can a failing read turn it into an error and lose user_spoke.
        val failing = sleeping.copy(transcript = { _, _, _ -> throw OpenCodeClientError("Offline") })
        val yielded = VoiceDelegatorWorkspace(
            client = failing,
            start = VoiceDelegatorWorkspace.Start(server = server, viewing = gameEngine, draftDirectory = gameEngine.directory, draftModel = null, transcriptDelta = emptyList(), explained = emptySet()),
            effects = VoiceDelegatorWorkspace.Effects(open = {}, explained = {}, userSpoke = {}, note = {}),
        ).run(VoiceDelegator.ToolCall(callID = "c", name = "wait_for_session", arguments = "{}"))
        assertTrue(yielded.contains(""""user_spoke"""") && !yielded.contains("error"))

        // A wait that finishes first says nothing of the user.
        val idle = sleeping.copy(
            transcript = { _, _, _ -> listOf(userText("msg_inbox", "Check my inbox")) },
            waitForIdle = { _, _ -> },
        )
        val finished = gameWorkspace(idle).run(VoiceDelegator.ToolCall(callID = "c", name = "wait_for_session", arguments = "{}"))
        assertTrue(finished.contains(""""finished":true"""))
        assertFalse(finished.contains("user_spoke"))
    }

    /** A small cut keeps most of its budget: it snaps back to a break only near its end. */
    @Test fun aShortCutKeepsMostOfItsBudget() {
        val part = "### Rules\n" + "The critters curl into balls to roll fast but blind. ".repeat(10)
        val cut = VoiceWorkClient.bounded(part, limit = 400, readMore = "msg_a-text-0")
        val marker = cut.indexOf("… [truncated: ")
        assertTrue(marker >= 0)
        assertTrue(marker >= 300)
    }

    /** The request's tracked-work line is an excerpt and says where the rest is. */
    @Test fun trackedWorkSaysItsReplyIsAnExcerpt() {
        val watch = ReplyWatch(
            completed = listOf(ReplyWatch.Completed(promptID = "msg_rules", sessionID = "ses_game", title = "GAME ENGINE", outcome = ReplyWatch.Outcome.Replied(designReply))),
            callPhase = ReplyWatch.CallPhase.live,
        )
        val summary = watch.statusSummary(viewing = null, draftDirectory = "/Users/kit/game")
        assertTrue(summary.contains("finished — replied: Here's the game design"))
        assertTrue(summary.contains("… (excerpt; read_session of that session has the whole reply)"))
    }

    @Test fun theVoiceAndTheDelegatorAreToldTheNewestResultWinsAndNothingIsCutOff() {
        val live = VoiceInstructions.buildLive(
            VoiceInstructions.Context(sessionTitle = "HOME", directoryName = "home", recentMessages = emptyList(), previousTail = emptyList(), previousEndedAt = null, managesSessions = true),
        )
        assertTrue(live.contains("without predicting the session's steps or what will be found"))
        assertTrue(live.contains("While a request is out, never answer it from earlier results"))
        assertTrue(live.contains("The newest result about something replaces everything earlier about it"))
        assertTrue(VoiceDelegator.instructions.contains("never ask it to restate or recap it"))
        assertTrue(VoiceDelegator.instructions.contains("Never tell the user a reply is cut off, truncated, or unavailable."))
        assertTrue(VoiceDelegator.instructions.contains("wait_for_session before you answer"))
    }

    // MARK: Results speak promptly while the delegator works

    private val upload: ReplyWatch.Prompt get() = ReplyWatch.Prompt(promptID = "msg_up", sessionID = "ses_upload", title = "Upload")

    /**
     * A result from a session the delegator hasn't touched stays deferred while
     * a foreground request is running (up to `claimedResultLimit`).
     */
    @Test fun anUnclaimedResultWaitsForRunningDelegatorsUpToTheLimit() {
        val watch = Watch()
        watch.step(ReplyWatch.Event.Sent(listOf(upload)))
        watch.step(ReplyWatch.Event.DelegatorStarted(requestID = "d1"))
        val landed = watch.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_up", reply = "All tests pass."))
        assertTrue(announcements(landed).isEmpty())
        assertTrue(landed.contains(ReplyWatch.Effect.ExpireDeferral(key = "msg_up", after = ReplyWatch.claimedResultLimit)))
        val spoken = announcements(watch.step(ReplyWatch.Event.DeferralExpired(key = "msg_up")))
        assertEquals(listOf(ReplyWatch.Outcome.Replied("All tests pass.").notice("Upload")), spoken.map { it.text })
        assertEquals("Still working", setOf("d1"), watch.delegatorsInFlight)
        assertTrue("Said once", announcements(watch.step(ReplyWatch.Event.DelegatorFinished(requestID = "d1", spoken = "Done."))).isEmpty())
    }

    /**
     * A result the delegator is waiting on waits for its answer, which says
     * it once; but only up to the limit, never for the whole request.
     */
    @Test fun aClaimedResultWaitsForTheAnswerOnlyUpToTheLimit() {
        val watch = Watch()
        watch.step(ReplyWatch.Event.Sent(listOf(steered[0])))
        watch.step(ReplyWatch.Event.DelegatorStarted(requestID = "d1"))
        watch.step(ReplyWatch.Event.DelegatorWaiting(sessionID = home, requestID = "d1", isWaiting = true))
        val landed = watch.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_inbox", reply = combined))
        assertTrue(announcements(landed).isEmpty())
        assertTrue(landed.contains(ReplyWatch.Effect.ExpireDeferral(key = "msg_inbox", after = ReplyWatch.claimedResultLimit)))
        // The request keeps going (another wait, more rounds): the limit speaks it.
        val spoken = announcements(watch.step(ReplyWatch.Event.DeferralExpired(key = "msg_inbox")))
        assertEquals(listOf(listOf("msg_inbox")), spoken.map { it.promptIDs })
        assertTrue(watch.deferredNotices.isEmpty())

        // Answered in time: the answer says it, the result is suppressed.
        val answered = Watch()
        answered.step(ReplyWatch.Event.Sent(listOf(steered[0])))
        answered.step(ReplyWatch.Event.DelegatorStarted(requestID = "d1"))
        answered.step(ReplyWatch.Event.DelegatorWaiting(sessionID = home, requestID = "d1", isWaiting = true))
        answered.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_inbox", reply = combined))
        answered.step(ReplyWatch.Event.DelegatorAnswered(sessionID = home, reply = combined, promptIDs = listOf("msg_inbox"), requestID = "d1"))
        assertTrue("Still claimed by the read", answered.step(ReplyWatch.Event.DelegatorWaiting(sessionID = home, requestID = "d1", isWaiting = false)).isEmpty())
        val finished = answered.step(ReplyWatch.Event.DelegatorFinished(requestID = "d1", spoken = "Two DMs need replies."))
        assertTrue(announcements(finished).isEmpty())
        assertTrue(finished.contains(ReplyWatch.Effect.CancelDeferralExpiry(key = "msg_inbox")))
        assertTrue("A late timer does nothing", answered.step(ReplyWatch.Event.DeferralExpired(key = "msg_inbox")).isEmpty())
    }

    /**
     * A wait that ends without reading the reply (it timed out) lets the
     * result speak at once.
     */
    @Test fun aWaitEndingWithoutAReadReleasesTheResult() {
        val watch = Watch()
        watch.step(ReplyWatch.Event.Sent(listOf(steered[0])))
        watch.step(ReplyWatch.Event.DelegatorStarted(requestID = "d1"))
        watch.step(ReplyWatch.Event.DelegatorWaiting(sessionID = home, requestID = "d1", isWaiting = true))
        watch.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_inbox", reply = combined))
        val ended = watch.step(ReplyWatch.Event.DelegatorWaiting(sessionID = home, requestID = "d1", isWaiting = false))
        assertEquals(listOf(listOf("msg_inbox")), announcements(ended).map { it.promptIDs })
        assertTrue(ended.contains(ReplyWatch.Effect.CancelDeferralExpiry(key = "msg_inbox")))
    }

    /**
     * A result whose session the delegator reads while deferred waits for its
     * answer without restarting its arrival-stamped 20 s timer, and isn't said twice.
     */
    @Test fun aResultClaimedWhileDeferredKeepsItsArrivalDeadlineAndWaitsForTheAnswer() {
        val watch = Watch()
        watch.step(ReplyWatch.Event.Sent(listOf(steered[0])))
        watch.step(ReplyWatch.Event.DelegatorStarted(requestID = "d1"))
        watch.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_inbox", reply = combined))
        val read = watch.step(ReplyWatch.Event.DelegatorAnswered(sessionID = home, reply = combined, promptIDs = listOf("msg_inbox"), requestID = "d1"))
        assertTrue("Claiming an already-deferred notice keeps its arrival-stamped 20 s timer", read.isEmpty())
        val finished = watch.step(ReplyWatch.Event.DelegatorFinished(requestID = "d1", spoken = "Two DMs need replies."))
        assertTrue(announcements(finished).isEmpty())
        assertTrue(finished.contains(ReplyWatch.Effect.CancelWireAnnouncements(promptIDs = listOf("msg_inbox"))))
    }

    /**
     * Through the call: with the delegator busy, an unclaimed result from
     * elsewhere stays held while the foreground request runs and speaks when it finishes.
     */
    @Test fun anUnclaimedResultStaysHeldPastGraceWhileTheDelegatorIsBusy() = runTest {
        val shared = SpeechTestShared()
        val voice = delegatorCall(shared)
            .stepping(ReplyWatch.Event.DelegatorStarted(requestID = "d1"))
            .copy(delegatorWork = listOf("d1"))
        val clock = TestClock()
        val announced = Locked(listOf<String>())
        val effects = detachedScope(coroutineContext)
        val store = TestStore(voice, VoiceFeature(), effects) {
            this[SharedStore] = shared.store
            this[Clock] = clock
            this[DateClient] = epoch
            this[VoiceWorkClient] = VoiceWorkClient.testValue.let { work ->
                VoiceWorkClient(submit = work.submit, awaitReply = { _, _, _ -> "All tests pass." }, checkSessionActive = work.checkSessionActive, recentMessages = work.recentMessages)
            }
            this[VoiceCallClient] = speechVoiceCall(announce = { _, _, text -> announced.update { it + text } })
        }
        store.exhaustive = false
        store.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.Sent(listOf(upload))))
        store.receive(::isReplyWatch, timeout = 5.seconds)
        clock.advance(3.seconds)
        store.skipReceivedActions()
        assertTrue(announced.value.isEmpty())
        store.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.DelegatorFinished(requestID = "d1", spoken = "Done.")))
        store.eventuallyReducing(timeout = 5.seconds) { announced.value.isNotEmpty() }
        assertEquals(listOf(ReplyWatch.Outcome.Replied("All tests pass.").notice("Upload")), announced.value)
        effects.skipInFlightEffects()
    }

    /**
     * A read asks whether the session is idle before reading its transcript:
     * a run that ends in between still has its reply in what's read, so a
     * stale snapshot never marks a result as told.
     */
    @Test fun aReadChecksTheSessionIsIdleBeforeReadingIt() = runBlocking {
        val shared = SpeechTestShared()
        val calls = Locked(listOf<String>())
        val effects = detachedScope(coroutineContext)
        val store = TestStore(delegatorCall(shared), VoiceFeature(), effects) {
            this[SharedStore] = shared.store
            this[Clock] = ImmediateClock()
            this[DateClient] = epoch
            this[VoiceDelegatorClient] = VoiceDelegatorClient(isEnabled = true) { _, input, _ ->
                if (input.firstOrNull() is VoiceDelegator.InputItem.ToolOutput) {
                    VoiceDelegator.Response(id = "r2", calls = emptyList(), text = "It's idle.")
                } else {
                    VoiceDelegator.Response(id = "r1", calls = listOf(VoiceDelegator.ToolCall(callID = "t_read", name = "read_session", arguments = "{}")), text = "")
                }
            }
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                isSessionActive = { _, _ -> calls.update { it + "status" }; false },
                transcript = { _, _, _ -> calls.update { it + "transcript" }; emptyList() },
            )
            this[VoiceCallClient] = speechVoiceCall()
        }
        store.exhaustive = false
        store.send(VoiceFeature.Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = emptyArguments)))
        store.receive(::isDelegatorFinished, timeout = 5.seconds)
        assertEquals(listOf("status", "transcript"), calls.value.take(2))
        effects.skipInFlightEffects()
    }

    /**
     * Another request finishing doesn't cut a result's grace short: only the
     * request that held a result releases it, or the grace runs out.
     */
    @Test fun anUnrelatedRequestFinishingKeepsAnotherResultsGrace() {
        val watch = Watch()
        watch.step(ReplyWatch.Event.Sent(listOf(upload)))
        watch.step(ReplyWatch.Event.DelegatorStarted(requestID = "d1"))
        watch.step(ReplyWatch.Event.DelegatorStarted(requestID = "d2"))
        watch.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_up", reply = "All tests pass."))
        val unrelated = watch.step(ReplyWatch.Event.DelegatorFinished(requestID = "d2", spoken = "Opened HOME."))
        assertTrue("d1 may still read it", announcements(unrelated).isEmpty())
        assertEquals(listOf("msg_up"), watch.deferredNotices.map { it.promptID })
        assertEquals("Its own grace speaks it", 1, announcements(watch.step(ReplyWatch.Event.DeferralExpired(key = "msg_up"))).size)

        // With nothing left running, nothing can claim it: it speaks now.
        val last = Watch()
        last.step(ReplyWatch.Event.Sent(listOf(upload)))
        last.step(ReplyWatch.Event.DelegatorStarted(requestID = "d1"))
        last.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_up", reply = "All tests pass."))
        val finished = last.step(ReplyWatch.Event.DelegatorFinished(requestID = "d1", spoken = "Opened HOME."))
        assertEquals(1, announcements(finished).size)
        assertTrue(finished.contains(ReplyWatch.Effect.CancelDeferralExpiry(key = "msg_up")))
    }

    /**
     * A request finishing releases only what it held: a result another
     * running request also read keeps waiting for that one.
     */
    @Test fun aRequestReleasesOnlyWhatItHeld() {
        val watch = Watch()
        watch.step(ReplyWatch.Event.Sent(listOf(steered[0], upload)))
        watch.step(ReplyWatch.Event.DelegatorStarted(requestID = "d1"))
        watch.step(ReplyWatch.Event.DelegatorStarted(requestID = "d2"))
        watch.step(ReplyWatch.Event.DelegatorWaiting(sessionID = home, requestID = "d1", isWaiting = true))
        watch.step(ReplyWatch.Event.DelegatorWaiting(sessionID = home, requestID = "d2", isWaiting = true))
        watch.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_inbox", reply = combined))
        watch.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_up", reply = "All tests pass."))
        // d1's wait times out and it finishes without reading HOME: d2 still waits on it.
        watch.step(ReplyWatch.Event.DelegatorWaiting(sessionID = home, requestID = "d1", isWaiting = false))
        val first = watch.step(ReplyWatch.Event.DelegatorFinished(requestID = "d1", spoken = "Still working."))
        assertTrue(announcements(first).isEmpty())
        assertEquals("Upload keeps its grace too", setOf("msg_inbox", "msg_up"), watch.deferredNotices.map { it.promptID }.toSet())
        watch.step(ReplyWatch.Event.DelegatorWaiting(sessionID = home, requestID = "d2", isWaiting = false))
        assertEquals("HOME's result went once nobody held it", listOf("msg_up"), watch.deferredNotices.map { it.promptID })
    }

    @Test fun claimingADeferredResultDoesNotRestartItsTwentySecondDeadline() = runTest {
        val watch = Watch()
        watch.step(ReplyWatch.Event.Sent(listOf(steered[0])))
        watch.step(ReplyWatch.Event.DelegatorStarted(requestID = "d1"))
        watch.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_inbox", reply = combined))
        val waiting = watch.step(ReplyWatch.Event.DelegatorWaiting(sessionID = home, requestID = "d1", isWaiting = true))
        assertTrue("No replacement timer is scheduled when claiming", waiting.isEmpty())
        assertEquals(setOf("d1"), watch.deferredNotices.firstOrNull()?.heldBy)
        watch.step(ReplyWatch.Event.DelegatorAnswered(sessionID = home, reply = combined, promptIDs = listOf("msg_inbox"), requestID = "d1"))
        watch.step(ReplyWatch.Event.DelegatorWaiting(sessionID = home, requestID = "d1", isWaiting = false))
        val finished = watch.step(ReplyWatch.Event.DelegatorFinished(requestID = "d1", spoken = "Two DMs need replies."))
        assertTrue("The answer said it", announcements(finished).isEmpty())

        // Clocked reducer proof: arrived at t=0, claimed at t=15 s, expires at t=20 s (5 s later).
        val shared = SpeechTestShared()
        val voice = delegatorCall(shared)
            .stepping(ReplyWatch.Event.DelegatorStarted(requestID = "d1"))
            .copy(delegatorWork = listOf("d1"))
        val clock = TestClock()
        val announced = Locked(listOf<String>())
        val effects = detachedScope(coroutineContext)
        val store = TestStore(voice, VoiceFeature(), effects) {
            this[SharedStore] = shared.store
            this[Clock] = clock
            this[DateClient] = epoch
            this[VoiceWorkClient] = VoiceWorkClient.testValue.let { work ->
                VoiceWorkClient(submit = work.submit, awaitReply = { _, _, _ -> "All tests pass." }, checkSessionActive = work.checkSessionActive, recentMessages = work.recentMessages)
            }
            this[VoiceCallClient] = speechVoiceCall(announce = { _, _, text -> announced.update { it + text } })
        }
        store.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.Sent(listOf(upload)))) {
            it.stepping(ReplyWatch.Event.Sent(listOf(upload)))
        }
        store.receive(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = "msg_up", reply = "All tests pass.")), timeout = 5.seconds) {
            it.stepping(ReplyWatch.Event.WaitSucceeded(promptID = "msg_up", reply = "All tests pass."))
        }
        clock.advance(15.seconds)
        assertTrue(announced.value.isEmpty())
        store.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.DelegatorWaiting(sessionID = "ses_upload", requestID = "d1", isWaiting = true))) {
            it.stepping(ReplyWatch.Event.DelegatorWaiting(sessionID = "ses_upload", requestID = "d1", isWaiting = true))
        }
        clock.advance(5.seconds)
        store.receive(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.DeferralExpired(key = "msg_up")), timeout = 5.seconds) {
            it.stepping(ReplyWatch.Event.DeferralExpired(key = "msg_up"))
        }
        store.finish()
        assertEquals(listOf(ReplyWatch.Outcome.Replied("All tests pass.").notice("Upload")), announced.value)
    }

    /** Ending the call cancels every held result's timer. */
    @Test fun endingTheCallCancelsHeldResultsTimers() {
        val watch = Watch()
        watch.step(ReplyWatch.Event.Sent(listOf(steered[0], upload)))
        watch.step(ReplyWatch.Event.DelegatorStarted(requestID = "d1"))
        watch.step(ReplyWatch.Event.DelegatorWaiting(sessionID = home, requestID = "d1", isWaiting = true))
        watch.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_inbox", reply = combined))
        watch.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_up", reply = "All tests pass."))
        val ended = watch.step(ReplyWatch.Event.CallPhaseChanged(ReplyWatch.CallPhase.ended))
        assertTrue(ended.contains(ReplyWatch.Effect.CancelDeferralExpiry(key = "msg_inbox")))
        assertTrue(ended.contains(ReplyWatch.Effect.CancelDeferralExpiry(key = "msg_up")))
        assertTrue(watch.deferredNotices.isEmpty())
    }

    /**
     * A running request's read only: one from a request that isn't running
     * claims nothing.
     */
    @Test fun aReadFromARequestThatIsntRunningCountsForNothing() {
        val watch = Watch()
        watch.step(ReplyWatch.Event.Sent(listOf(steered[0])))
        watch.step(ReplyWatch.Event.DelegatorStarted(requestID = "d1"))
        watch.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_inbox", reply = combined))
        assertTrue(watch.step(ReplyWatch.Event.DelegatorAnswered(sessionID = home, reply = combined, promptIDs = listOf("msg_inbox"), requestID = "d_gone")).isEmpty())
        assertNull(watch.pendingAnswers["d_gone"])
        assertEquals(true, watch.deferredNotices.firstOrNull()?.heldBy?.isEmpty())
    }

    /**
     * The call ends while the delegator's answer is still being handed over:
     * the reply watch's timers and watches were already settled, so nothing
     * is left running once the store finishes, with no clock advanced.
     */
    @Test fun aCallEndingMidAnswerLeavesNothingRunning() = runBlocking {
        val shared = SpeechTestShared()
        val clock = TestClock()
        val answerNow = CompletableDeferred<Unit>()
        val completing = CompletableDeferred<Unit>()
        val releaseComplete = CompletableDeferred<Unit>()
        val announced = Locked(listOf<String>())
        val effects = detachedScope(coroutineContext)
        val store = TestStore(delegatorCall(shared), VoiceFeature(), effects) {
            this[SharedStore] = shared.store
            this[Clock] = clock
            this[DateClient] = epoch
            this[VoiceDelegatorClient] = VoiceDelegatorClient(isEnabled = true) { _, _, _ ->
                answerNow.await()
                VoiceDelegator.Response(id = "r1", calls = emptyList(), text = "Opened HOME.")
            }
            this[VoiceWorkClient] = VoiceWorkClient.testValue.let { work ->
                VoiceWorkClient(submit = work.submit, awaitReply = { _, _, _ -> "All tests pass." }, checkSessionActive = work.checkSessionActive, recentMessages = work.recentMessages)
            }
            this[VoiceCallClient] = speechVoiceCall(
                completeFunction = { _, _ -> completing.complete(Unit); releaseComplete.await() },
                announce = { _, _, text -> announced.update { it + text } },
            )
        }
        store.exhaustive = false
        store.send(VoiceFeature.Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = emptyArguments)))
        store.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.Sent(listOf(upload))))
        store.receive(::isReplyWatch, timeout = 5.seconds)
        assertEquals("Its grace timer is running", listOf("msg_up"), store.state.replyWatch.deferredNotices.map { it.promptID })
        answerNow.complete(Unit)
        store.receive(::isDelegatorFinished, timeout = 5.seconds)
        completing.await()
        store.send(VoiceFeature.Action.CallDropped)
        releaseComplete.complete(Unit)
        store.skipReceivedActions()
        // Every effect must end on its own: a leaked timer would keep the store busy.
        store.exhaustive = true
        store.finish(timeout = 3.seconds)
        assertTrue(announced.value.isEmpty())
        effects.skipInFlightEffects()
    }

    /**
     * A delegator answer still on its way when the call drops never reaches
     * a call rejoined on the same host: neither the results it releases nor
     * their timers, and a late finish from the old call is ignored.
     */
    @Test fun aDroppedCallsAnswerNeverReachesTheRejoinedCall() = runBlocking {
        val shared = SpeechTestShared()
        val clock = TestClock()
        val answerNow = CompletableDeferred<Unit>()
        val completing = CompletableDeferred<Unit>()
        val releaseComplete = CompletableDeferred<Unit>()
        val completed = Locked(listOf<String>())
        val announced = Locked(listOf<String>())
        val events = Channel<VoiceCallEvent>(Channel.UNLIMITED)
        val effects = detachedScope(coroutineContext)
        val store = TestStore(delegatorCall(shared), VoiceFeature(), effects) {
            this[SharedStore] = shared.store
            this[Clock] = clock
            this[DateClient] = epoch
            // Android: the rejoined call's ongoing-notification ID.
            this[UuidClient] = UuidClient.incrementing()
            this[VoiceDelegatorClient] = VoiceDelegatorClient(isEnabled = true) { _, _, _ ->
                answerNow.await()
                VoiceDelegator.Response(id = "r1", calls = emptyList(), text = "Opened HOME.")
            }
            this[VoiceWorkClient] = VoiceWorkClient.testValue.let { work ->
                VoiceWorkClient(submit = work.submit, awaitReply = { _, _, _ -> "All tests pass." }, checkSessionActive = work.checkSessionActive, recentMessages = work.recentMessages)
            }
            this[VoiceCallClient] = speechVoiceCall(
                start = { _, _ -> events.consumeAsFlow() },
                completeFunction = { callID, _ ->
                    completing.complete(Unit)
                    releaseComplete.await()
                    completed.update { it + callID }
                },
                announce = { _, _, text -> announced.update { it + text } },
                end = {},
            )
        }
        store.exhaustive = false
        store.send(VoiceFeature.Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = emptyArguments)))
        store.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.Sent(listOf(upload))))
        store.receive(::isReplyWatch, timeout = 5.seconds)
        assertEquals("In its grace while d1 works", listOf("msg_up"), store.state.replyWatch.deferredNotices.map { it.promptID })
        answerNow.complete(Unit)
        store.receive(::isDelegatorFinished, timeout = 5.seconds)
        completing.await()
        // The call drops while the answer is still being handed over, and is rejoined.
        store.send(VoiceFeature.Action.CallDropped)
        store.send(VoiceFeature.Action.RejoinTapped)
        events.trySend(VoiceCallEvent.Connected)
        releaseComplete.complete(Unit)
        delay(200)
        store.skipReceivedActions()
        clock.advance(30.seconds)
        delay(100)
        store.skipReceivedActions()
        assertTrue("The old call's result never reaches the new one", announced.value.isEmpty())
        // A late finish from the old call is ignored.
        store.send(VoiceFeature.Action.DelegatorFinished(callID = "d_old", result = Result.success(VoiceDelegator.Outcome(reply = "Stale.", responseID = null, unanswered = emptyList()))))
        delay(100)
        store.skipReceivedActions()
        assertFalse(completed.value.contains("d_old"))
        effects.skipInFlightEffects()
    }

    private fun toolCall(name: String) = OpenCodeToolCall(
        callID = "t1", messageID = "msg_reply", name = name, status = OpenCodeToolCall.Status.Completed,
        input = OpenCodeJSONValue.Null, output = emptyList(), error = null, metadata = emptyMap(),
    )

    private companion object {
        /** The design reply from the call, with the rules past the 400th character. */
        val designReply = """
            Here's the game design we're building for **Spider vs. Critters: Crumb Heist** (`--scene desk --mode crumb-heist`), pulling from what works in **Dead by Daylight**, **Monaco** (cop-and-robber heist stealth), **Pac-Man Vs.** (asymmetric vision/senses), and **Super Monkey Ball / Goron Roll** (tactile curl-and-roll movement).

            ### Why 1vN Predator-vs-Prey Usually Breaks—and How We Make It Fair

            The 1v4 problem is that a strong hunter snowballs and a weak one is a pushover, so every rule below keeps both sides in the match until the last crumb.

            ### Rules

            - One player is the giant desk spider; up to four players are roly-poly pillbug critters.
            - The critters win by stealing four of the five golden crumbs back to their matchbox nest.
            - The spider wins by cocooning every critter before the heist is done.
            - A cocooned critter can be cut free by a teammate within 20 seconds.
            - Curling into a ball makes a critter fast but blind; uncurled it sees the spider's web lines.
        """.trimIndent()
    }
}
