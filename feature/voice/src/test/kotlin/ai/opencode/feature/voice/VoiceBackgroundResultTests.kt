package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.withDependenciesSuspending
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.store.TestStore
import ai.opencode.feature.voice.VoiceFeatureTestSupport.copy
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeRunMessage
import ai.opencode.server.model.OpenCodeRunMessagesPage
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.testing.WireDecoders
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import java.time.Instant

/**
 * Work a watched run starts in the background is watched too, and its result is spoken once, at the
 * next opening.
 */
class VoiceBackgroundResultTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val home = "ses_home"
    private val child = "ses_eye"
    private val started = "I've started the eye tracking research in the background."
    private val report = "macOS has no eye tracking; a webcam tracker is the realistic option."

    private val homeSession: OpenCodeSession
        get() = OpenCodeSession(id = home, serverTitle = "HOME", projectID = "p", directory = "/Users/kit/home", updatedAt = distantPast)

    private data class Announcement(val promptIDs: List<String>, val text: String)

    private fun liveVoice(shared: SpeechTestShared): VoiceFeature.State = shared.run {
        VoiceFeature.State(server, homeSession)
            .withPhase(VoiceFeature.State.Phase.Live)
            .copy(usesDelegator = true)
            .let { it.copy(replyWatch = it.replyWatch.copy(callPhase = ReplyWatch.CallPhase.live)) }
    }

    @Test fun backgroundWorkIsSpokenOnceWhenItLands() = runBlocking<Unit> {
        val work = VoiceBackgroundWork(sessionID = child, parentSessionID = home, description = "Eye tracking research")
        val eye = ReplyWatch.Prompt(promptID = "msg_eye", sessionID = home, title = "HOME", topic = "Research eye tracking for the Mac")
        val others = listOf(
            ReplyWatch.Prompt(promptID = "msg_merge", sessionID = "ses_ios", title = "OpenCode iOS", topic = "Fix the merge conflicts"),
            ReplyWatch.Prompt(promptID = "msg_prune", sessionID = "ses_dots", title = "Dotfiles", topic = "Apply the prune"),
            ReplyWatch.Prompt(promptID = "msg_board", sessionID = "ses_board", title = "Board", topic = "Move OC-12 to Done"),
        )
        val replies = mapOf(
            "msg_eye" to started,
            "msg_merge" to "Resolved both merge conflicts.",
            "msg_prune" to "Removed the empty skill folders.",
            "msg_board" to "OC-12 is Done.",
        )
        val background = ReplyWatch.Prompt(work, title = "HOME", saidTurn = 0)
        val finished = Channel<Unit>(Channel.UNLIMITED)
        val announced = Locked(listOf<Announcement>())
        val idled = Locked(listOf<String>())
        val clock = TestClock()

        val shared = SpeechTestShared()
        val voice = liveVoice(shared)
        val effects = detachedScope(coroutineContext)
        val store = TestStore(voice, VoiceFeature(), effects) {
            this[SharedStore] = shared.store
            this[Clock] = clock
            this[DateClient] = epoch
            this[VoiceWorkClient] = VoiceWorkClient.testValue.copy(
                awaitReply = { _, _, promptID -> replies[promptID] },
                backgroundWork = { _, sessionID, promptID, _ ->
                    if (sessionID == home && promptID == "msg_eye") listOf(work) else emptyList()
                },
                awaitIdle = { _, sessionID ->
                    idled.update { it + sessionID }
                    finished.receiveCatching()
                },
                backgroundReply = { _, sessionID -> if (sessionID == child) report else null },
            )
            this[VoiceCallClient] = speechVoiceCall(
                announce = { _, promptIDs, text -> announced.update { it + Announcement(promptIDs, text) } },
            )
        }

        // HOME replies that it started the work: that reply is spoken, and the
        // work it started in the background is watched in its own session.
        store.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.Sent(listOf(eye)))) { it.stepping(ReplyWatch.Event.Sent(listOf(eye))) }
        store.receive(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = "msg_eye", reply = started))) {
            it.stepping(ReplyWatch.Event.WaitSucceeded(promptID = "msg_eye", reply = started))
        }
        store.receive(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.Sent(listOf(background)))) { it.stepping(ReplyWatch.Event.Sent(listOf(background))) }
        assertTrue(store.state.replyWatch.isWatching(sessionID = child))
        assertFalse(store.state.replyWatch.isWatching(sessionID = home))

        // The conversation moves on: three unrelated delegations finish.
        for ((turn, prompt) in others.withIndex()) {
            val topic = prompt.topic ?: ""
            store.send(VoiceFeature.Action.CallEvent(VoiceCallEvent.UserTranscript(itemID = "u$turn", text = topic, final = true))) {
                val recorded = it.recording(VoiceTranscriptTail.Role.user, topic, id = "u$turn")
                recorded.copy(
                    replyWatch = recorded.replyWatch.noteUserActed(),
                    captions = recorded.captions + VoiceFeature.State.Caption(id = "user-u$turn", role = VoiceTranscriptTail.Role.user, text = topic),
                )
            }
            store.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.Sent(listOf(prompt)))) { it.stepping(ReplyWatch.Event.Sent(listOf(prompt))) }
            val reply = replies[prompt.promptID]
            store.receive(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = prompt.promptID, reply = reply))) {
                it.stepping(ReplyWatch.Event.WaitSucceeded(promptID = prompt.promptID, reply = reply))
            }
        }

        // The research lands while the voice is mid-request: it waits for that
        // request's answer, then speaks.
        store.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.DelegatorStarted(requestID = "r_status"))) {
            it.stepping(ReplyWatch.Event.DelegatorStarted(requestID = "r_status"))
        }
        finished.trySend(Unit)
        store.receive(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = background.promptID, reply = report))) {
            it.stepping(ReplyWatch.Event.WaitSucceeded(promptID = background.promptID, reply = report))
        }
        assertEquals(listOf(background.promptID), store.state.replyWatch.deferredNotices.map { it.promptID })
        assertFalse("Held while the request runs", announced.value.any { it.promptIDs == listOf(background.promptID) })
        store.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.DelegatorFinished(requestID = "r_status", spoken = "OC-12 is Done."))) {
            it.stepping(ReplyWatch.Event.DelegatorFinished(requestID = "r_status", spoken = "OC-12 is Done."))
        }
        store.finish()
        effects.skipInFlightEffects()

        assertTrue(idled.value.toString(), idled.value.all { it == child })
        val spoken = announced.value.filter { it.promptIDs == listOf(background.promptID) }
        assertEquals(1, spoken.size)
        val notice = ReplyWatch.Outcome.Replied(report)
            .notice("HOME", answering = listOf("Eye tracking research"), alreadyTold = null, background = "Eye tracking research")
        assertEquals(VoiceHandoff.earlierRequest(notice, topic = "Eye tracking research"), spoken.firstOrNull()?.text)
        assertTrue(spoken.firstOrNull()?.text?.contains(report) == true)
        assertEquals("The started reply, three results, the research", 5, announced.value.size)
        assertTrue(store.state.replyWatch.watched.isEmpty())
        val summary = store.state.replyWatch.statusSummary(viewing = homeSession, draftDirectory = "/Users/kit/home")
        assertTrue(summary, summary.contains("Background work “Eye tracking research” that \"HOME\" started (subagent session id $child): finished"))
        finished.close()
    }

    private fun user(id: String): OpenCodeRunMessage =
        OpenCodeRunMessage(id = id, type = "user", text = "Research eye tracking")

    private fun assistant(id: String, text: String = "", launching: String? = null): OpenCodeRunMessage {
        val launches = launching?.let { listOf(OpenCodeRunMessage.Launch(sessionID = it, description = "Eye tracking research")) } ?: emptyList()
        return OpenCodeRunMessage(id = id, type = "assistant", text = text, launches = launches)
    }

    private fun completion(id: String, of: String): OpenCodeRunMessage =
        OpenCodeRunMessage(id = id, type = "synthetic", completedChildID = of)

    /** Tool-only steps: a long run. */
    private fun steps(prefix: String, count: Int): List<OpenCodeRunMessage> =
        (0 until count).map { assistant("$prefix$it") }

    /**
     * Only background subagents the watched run started are followed: not
     * one an earlier prompt started, nor one the next prompt did.
     */
    @Test fun backgroundLaunchesAreTheRunsOwn() {
        val messages = listOf(
            user("msg_old"), assistant("a0", launching = "ses_prune"), assistant("a0t", "Started the prune."),
            user("msg_eye"), assistant("a1", launching = child), assistant("a1t", started),
            user("msg_next"), assistant("a2", launching = "ses_later"),
        )
        val work = VoiceBackgroundWork(sessionID = child, parentSessionID = home, description = "Eye tracking research")
        assertEquals(listOf(work), VoiceWorkClient.pendingBackgroundWork(messages, home, afterPromptID = "msg_eye", reply = started))
        assertTrue(VoiceWorkClient.pendingBackgroundWork(messages, home, afterPromptID = "msg_missing", reply = started).isEmpty())
    }

    /**
     * Work counts as covered only when its completion landed
     * before text of the reply the call read. One that finished after that
     * reply, even in the moment before the search ran, is still news; so is
     * one whose completion woke a later run of the session nobody read.
     */
    @Test fun onlyACompletionBeforeTheReadReplyCoversTheWork() {
        val work = VoiceBackgroundWork(sessionID = child, parentSessionID = home, description = "Eye tracking research")
        val finishedAfter = listOf(user("msg_eye"), assistant("a1", launching = child), assistant("a1t", started), completion("s1", of = child))
        assertEquals(listOf(work), VoiceWorkClient.pendingBackgroundWork(finishedAfter, home, afterPromptID = "msg_eye", reply = started))
        val wokeALaterRun = finishedAfter + assistant("a2t", "The research is in: no eye tracking on macOS.")
        assertEquals(listOf(work), VoiceWorkClient.pendingBackgroundWork(wokeALaterRun, home, afterPromptID = "msg_eye", reply = started))
        val finishedWithin = listOf(user("msg_eye"), assistant("a1", launching = child), completion("s1", of = child), assistant("a1t", report))
        assertTrue(VoiceWorkClient.pendingBackgroundWork(finishedWithin, home, afterPromptID = "msg_eye", reply = report).isEmpty())
        assertEquals("No reply read covers nothing", listOf(work), VoiceWorkClient.pendingBackgroundWork(finishedWithin, home, afterPromptID = "msg_eye", reply = null))
    }

    /**
     * The run's prompt is past the first page: discovery pages back to it,
     * and stops after `backgroundSearchPages` when it's gone.
     */
    @Test fun discoveryPagesBackToTheRunsPrompt() = runBlocking<Unit> {
        val reads = Locked(listOf<String?>())
        val pages: Map<String?, OpenCodeRunMessagesPage> = mapOf(
            null to OpenCodeRunMessagesPage(messages = steps("n", 39) + assistant("a1t", started), next = "older"),
            "older" to OpenCodeRunMessagesPage(messages = listOf(user("msg_eye"), assistant("a1", launching = child)) + steps("o", 38), next = "oldest"),
        )
        val found = withDependenciesSuspending({
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                runMessages = { _, _, cursor ->
                    reads.update { it + cursor }
                    pages[cursor] ?: OpenCodeRunMessagesPage(messages = steps(cursor ?: "x", 40), next = "${cursor ?: ""}+")
                },
            )
        }) {
            VoiceWorkClient.live(VoiceWorkCoordinator()).backgroundWork(server, home, "msg_eye", started)
        }
        assertEquals(listOf(child), found.map { it.sessionID })
        assertEquals(listOf(null, "older"), reads.value)

        reads.set(emptyList())
        val gone = withDependenciesSuspending({
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                runMessages = { _, _, cursor ->
                    reads.update { it + cursor }
                    OpenCodeRunMessagesPage(messages = steps(cursor ?: "x", 40), next = "${cursor ?: ""}+")
                },
            )
        }) {
            VoiceWorkClient.live(VoiceWorkCoordinator()).backgroundWork(server, home, "msg_gone", started)
        }
        assertTrue(gone.isEmpty())
        assertEquals(VoiceWorkClient.backgroundSearchPages, reads.value.size)
    }

    /**
     * The race end to end, on a run longer than a page: the research finished
     * after HOME's reply was read and before the search. It's watched, read
     * at once (its session is already idle) and spoken once.
     */
    @Test fun workThatFinishedAfterTheReplyWasReadIsSpokenOnce() {
        val pages: Map<String?, OpenCodeRunMessagesPage> = mapOf(
            null to OpenCodeRunMessagesPage(messages = steps("n", 38) + assistant("a1t", started) + completion("s1", of = child), next = "older"),
            "older" to OpenCodeRunMessagesPage(messages = listOf(user("msg_eye"), assistant("a1", launching = child)) + steps("o", 38), next = null),
        )
        val announced = runEyeRequest(reply = started, pages = pages, watches = true)
        assertEquals(listOf(listOf("msg_eye"), listOf("background-$child")), announced.map { it.promptIDs })
        assertTrue(announced.lastOrNull()?.text?.contains(report) == true)
    }

    /** Work whose completion HOME's read reply came after was told in it. */
    @Test fun workTheReadReplyCoveredIsNotSpokenAgain() {
        val pages: Map<String?, OpenCodeRunMessagesPage> = mapOf(
            null to OpenCodeRunMessagesPage(messages = listOf(user("msg_eye"), assistant("a1", launching = child), completion("s1", of = child), assistant("a1t", report)), next = null),
        )
        val announced = runEyeRequest(reply = report, pages = pages, watches = false)
        assertEquals(listOf(listOf("msg_eye")), announced.map { it.promptIDs })
    }

    private fun runEyeRequest(reply: String, pages: Map<String?, OpenCodeRunMessagesPage>, watches: Boolean): List<Announcement> = runBlocking {
        val eye = ReplyWatch.Prompt(promptID = "msg_eye", sessionID = home, title = "HOME", topic = "Research eye tracking for the Mac")
        val background = ReplyWatch.Prompt(
            VoiceBackgroundWork(sessionID = child, parentSessionID = home, description = "Eye tracking research"),
            title = "HOME",
            saidTurn = 0,
        )
        val announced = Locked(listOf<Announcement>())
        val idled = Locked(listOf<String>())
        val shared = SpeechTestShared()
        val voice = liveVoice(shared)
        val effects = detachedScope(coroutineContext)
        val store = TestStore(voice, VoiceFeature(), effects) {
            this[SharedStore] = shared.store
            this[Clock] = ImmediateClock()
            this[DateClient] = epoch
            this[VoiceWorkClient] = VoiceWorkClient.testValue.copy(
                awaitReply = { _, _, _ -> reply },
                backgroundWork = VoiceWorkClient.live(VoiceWorkCoordinator()).backgroundWork,
                awaitIdle = { _, sessionID -> idled.update { it + sessionID } },
                backgroundReply = { _, _ -> report },
            )
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                runMessages = { _, _, cursor -> pages[cursor] ?: OpenCodeRunMessagesPage(messages = emptyList(), next = null) },
            )
            this[VoiceCallClient] = speechVoiceCall(
                announce = { _, promptIDs, text -> announced.update { it + Announcement(promptIDs, text) } },
            )
        }
        store.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.Sent(listOf(eye)))) { it.stepping(ReplyWatch.Event.Sent(listOf(eye))) }
        store.receive(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = "msg_eye", reply = reply))) {
            it.stepping(ReplyWatch.Event.WaitSucceeded(promptID = "msg_eye", reply = reply))
        }
        if (watches) {
            store.receive(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.Sent(listOf(background)))) { it.stepping(ReplyWatch.Event.Sent(listOf(background))) }
            store.receive(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = background.promptID, reply = report))) {
                it.stepping(ReplyWatch.Event.WaitSucceeded(promptID = background.promptID, reply = report))
            }
        }
        store.finish()
        effects.skipInFlightEffects()
        assertTrue(store.state.replyWatch.watched.isEmpty())
        assertTrue(idled.value.all { it == child })
        announced.value
    }

    /** Voice follows reported launches across server versions and reads the parent's own text. */
    @Test fun runMessagesDecodeLaunchesAndCompletions() {
        val json = """
        {"data":[
          {"id":"s1","type":"synthetic","text":"<subagent …>","description":"Eye tracking research","metadata":{"source":"subagent","childID":"ses_eye","agent":"General","state":"completed"}},
          {"id":"a1","type":"assistant","content":[
            {"type":"reasoning","text":"Delegate it."},
            {"type":"tool","id":"t1","name":"subagent","state":{"status":"completed","input":{"description":"Eye tracking research","prompt":"…"},"metadata":{"sessionID":"ses_eye","status":"running"},"content":[{"type":"text","text":"The subagent will report back."}]}},
            {"type":"tool","id":"t2","name":"task","state":{"status":"completed","input":{"background":true},"metadata":{"sessionID":"ses_legacy"}}},
            {"type":"tool","id":"t3","name":"subagent","state":{"status":"completed","input":{"notify":false},"metadata":{"sessionID":"ses_silent","status":"running"}}},
            {"type":"tool","id":"t4","name":"subagent","state":{"status":"completed","input":{"description":"Inline"},"metadata":{"sessionID":"ses_inline"}}},
            {"type":"tool","id":"t5","name":"subagent","state":{"status":"completed","input":{},"metadata":{"sessionID":"ses_done","status":"completed"}}},
            {"type":"text","text":"I've started it."}
          ]},
          {"id":"msg_eye","type":"user","text":"Research eye tracking"}
        ],"cursor":{"next":"older"}}
        """.trimIndent().toByteArray()
        val page = WireDecoders.decodeRunMessages(json)
        assertEquals("older", page.next)
        assertEquals(
            listOf(
                OpenCodeRunMessage(id = "msg_eye", type = "user", text = "Research eye tracking"),
                OpenCodeRunMessage(id = "a1", type = "assistant", text = "I've started it.", launches = listOf(
                    OpenCodeRunMessage.Launch(sessionID = "ses_eye", description = "Eye tracking research"),
                    OpenCodeRunMessage.Launch(sessionID = "ses_legacy", description = null),
                )),
                OpenCodeRunMessage(id = "s1", type = "synthetic", completedChildID = "ses_eye"),
            ),
            page.messages,
        )
    }

    @Test fun aReadOfTheParentSessionHoldsTheResult() {
        val work = VoiceBackgroundWork(sessionID = child, parentSessionID = home, description = "Eye tracking research")
        val watch = Watch()
        watch.step(ReplyWatch.Event.Sent(listOf(ReplyWatch.Prompt(work, title = "HOME", saidTurn = 0))))
        watch.step(ReplyWatch.Event.DelegatorStarted(requestID = "r1"))
        watch.step(ReplyWatch.Event.DelegatorWaiting(sessionID = home, requestID = "r1", isWaiting = true))
        val effects = watch.step(ReplyWatch.Event.WaitSucceeded(promptID = "background-$child", reply = report))
        assertEquals(setOf("r1"), watch.deferredNotices.firstOrNull()?.heldBy)
        assertTrue(effects.contains(ReplyWatch.Effect.ExpireDeferral(key = "background-$child", after = ReplyWatch.claimedResultLimit)))
        assertFalse("Background work's own result follows nothing", effects.any { it is ReplyWatch.Effect.FindBackgroundWork })
    }

    private fun sessionDelegatorVoice(shared: SpeechTestShared, userActed: Boolean): VoiceFeature.State = shared.run {
        VoiceFeature.State(server, homeSession)
            .withPhase(VoiceFeature.State.Phase.Live)
            .copy(usesSessionDelegator = true)
            .let { it.copy(replyWatch = it.replyWatch.copy(callPhase = ReplyWatch.CallPhase.live)) }
            .let { if (userActed) it.copy(replyWatch = it.replyWatch.noteUserActed()) else it }
    }

    @Test fun sessionDelegatorRelaysParentSubagentCorrectionWithoutRawChildOrDoubleDelivery() = runBlocking<Unit> {
        val voiceSessionID = "ses_voice"
        val inboxChild = "ses_inbox"
        val rawChildStale = "The top priority is checking in online for Avianca B8OE4C before tonight's 11 PM flight."
        val correctedRelay = "Inbox isn't at zero, because eight handled emails need your OK. **The check-in is already done.**"
        val work = VoiceBackgroundWork(sessionID = inboxChild, parentSessionID = voiceSessionID, description = "Inbox zero pass")
        val background = ReplyWatch.Prompt(work, title = "Voice", saidTurn = 0)

        val rawBackgroundReads = Locked(0)
        val announced = Locked(listOf<Announcement>())

        val parentUnsolicitedMessages = listOf(
            user("msg_1"),
            assistant("a1", "The inbox-zero pass is running now.", launching = inboxChild),
            OpenCodeRunMessage(id = "idle_1", type = "idle"),
            completion("syn_inbox", of = inboxChild),
            assistant("a2", correctedRelay),
            OpenCodeRunMessage(id = "idle_2", type = "idle"),
        )

        fun relayStore(shared: SpeechTestShared, state: VoiceFeature.State, effects: kotlinx.coroutines.CoroutineScope, messages: List<OpenCodeRunMessage>, expectedSession: String?) =
            TestStore(state, VoiceFeature(), effects) {
                this[SharedStore] = shared.store
                this[Clock] = ImmediateClock()
                this[DateClient] = epoch
                this[VoiceWorkClient] = VoiceWorkClient.testValue.copy(
                    awaitIdle = { _, _ -> },
                    backgroundReply = { _, _ ->
                        rawBackgroundReads.update { it + 1 }
                        rawChildStale
                    },
                    relayedBackgroundReply = VoiceWorkClient.live(VoiceWorkCoordinator()).relayedBackgroundReply,
                )
                this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                    waitForIdle = { _, _ -> },
                    runMessages = { _, sessionID, _ ->
                        if (expectedSession != null) assertEquals(expectedSession, sessionID)
                        OpenCodeRunMessagesPage(messages = messages, next = null)
                    },
                )
                this[VoiceCallClient] = speechVoiceCall(
                    announce = { _, promptIDs, text -> announced.update { it + Announcement(promptIDs, text) } },
                )
            }

        val shared = SpeechTestShared()
        val effects = detachedScope(coroutineContext)
        val store = relayStore(shared, sessionDelegatorVoice(shared, userActed = true), effects, parentUnsolicitedMessages, voiceSessionID)

        val cleanedRelay = "Inbox isn't at zero, because eight handled emails need your OK. The check-in is already done."
        store.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.Sent(listOf(background)))) { it.stepping(ReplyWatch.Event.Sent(listOf(background))) }
        store.receive(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = background.promptID, reply = cleanedRelay)), timeout = 5.seconds) {
            it.stepping(ReplyWatch.Event.WaitSucceeded(promptID = background.promptID, reply = cleanedRelay))
        }
        store.finish(timeout = 5.seconds)
        effects.skipInFlightEffects()

        assertEquals("Session delegator never fetches the child subagent's raw text", 0, rawBackgroundReads.value)
        assertEquals(1, announced.value.size)
        assertTrue(announced.value.first().text.contains("The check-in is already done."))
        assertFalse(announced.value.first().text.contains("Avianca B8OE4C"))

        // Now test the double-delivery overlap (`L92 -> L94 -> L96`): `<subagent>`
        // lands in the same Voice-session run as a `[Voice delta]` user delegation,
        // whose own `completeFunction` speaks the combined reply. ReplyWatch must
        // supersede the background watch with zero `announce` calls.
        announced.update { emptyList() }
        val parentCoveredMessages = listOf(
            OpenCodeRunMessage(id = "idle_prev", type = "idle"),
            completion("syn_inbox", of = inboxChild),
            assistant("a_empty", ""),
            user("msg_2"),
            assistant("a_combined", "Carbon's enum plan is in and sent to Blood."),
            OpenCodeRunMessage(id = "idle_end", type = "idle"),
        )
        val coveredShared = SpeechTestShared()
        val coveredEffects = detachedScope(coroutineContext)
        val coveredStore = relayStore(coveredShared, sessionDelegatorVoice(coveredShared, userActed = false), coveredEffects, parentCoveredMessages, null)

        coveredStore.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.Sent(listOf(background)))) { it.stepping(ReplyWatch.Event.Sent(listOf(background))) }
        coveredStore.receive(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.Superseded(promptID = background.promptID)), timeout = 5.seconds) {
            it.stepping(ReplyWatch.Event.Superseded(promptID = background.promptID))
        }
        coveredStore.finish(timeout = 5.seconds)
        coveredEffects.skipInFlightEffects()

        assertEquals(0, rawBackgroundReads.value)
        assertTrue("A `<subagent>` run covered by a user delegation turn must not announce twice", announced.value.isEmpty())

        // Multi-synthetic run (`L163 -> L164 -> L165 -> L168`) and combined reply.
        val multiMessages = listOf(
            OpenCodeRunMessage(id = "idle_0", type = "idle"),
            completion("s_seer", of = "ses_seer"),
            assistant("a_seer", "The Seer audit found 3,000 lines; I'll park the duplicate."),
            completion("s_part", of = "ses_part"),
            assistant("a_tool", ""),
            assistant("a_part", "I've started Carbon on that review now."),
            OpenCodeRunMessage(id = "idle_1", type = "idle"),
        )
        assertEquals(
            "The Seer audit found 3,000 lines; I'll park the duplicate.",
            VoiceWorkClient.relayedSubagentReply(multiMessages, childSessionID = "ses_seer"),
        )
        assertEquals("I've started Carbon on that review now.", VoiceWorkClient.relayedSubagentReply(multiMessages, childSessionID = "ses_part"))

        val combinedMessages = listOf(
            OpenCodeRunMessage(id = "idle_0", type = "idle"),
            completion("s_1", of = "ses_1"),
            completion("s_2", of = "ses_2"),
            assistant("a_both", "Both audits finished."),
            OpenCodeRunMessage(id = "idle_1", type = "idle"),
        )
        assertNull(VoiceWorkClient.relayedSubagentReply(combinedMessages, childSessionID = "ses_1"))
        assertEquals("Both audits finished.", VoiceWorkClient.relayedSubagentReply(combinedMessages, childSessionID = "ses_2"))
    }

    /**
     * If `<subagent>` arrives after the initial 150/400 ms poll
     * window, or the parent Voice run after `<subagent>` has not yet settled to
     * `"idle"`, ReplyWatch must keep retrying rather than dropping via `.superseded`.
     */
    @Test fun sessionDelegatorBackgroundWatchRetriesUntilSyntheticArrivesAndParentSettles() = runBlocking<Unit> {
        val voiceSessionID = "ses_voice"
        val inboxChild = "ses_inbox"
        val relayed = "The inbox check finished cleanly."
        val work = VoiceBackgroundWork(sessionID = inboxChild, parentSessionID = voiceSessionID, description = "Inbox check")
        val background = ReplyWatch.Prompt(work, title = "Voice", saidTurn = 0)

        val beforeSynthetic = listOf(
            user("msg_1"),
            assistant("a1", "Running inbox check.", launching = inboxChild),
            OpenCodeRunMessage(id = "idle_1", type = "idle"),
        )
        val unsettledAfterSynthetic = beforeSynthetic + listOf(
            completion("syn_inbox", of = inboxChild),
            assistant("a_mid", "Looking at the result…"),
        )
        val settledRelay = beforeSynthetic + listOf(
            completion("syn_inbox", of = inboxChild),
            assistant("a2", relayed),
            OpenCodeRunMessage(id = "idle_2", type = "idle"),
        )

        val pageReads = Locked(0)
        val announced = Locked(listOf<String>())
        val shared = SpeechTestShared()
        val effects = detachedScope(coroutineContext)
        val store = TestStore(sessionDelegatorVoice(shared, userActed = true), VoiceFeature(), effects) {
            this[SharedStore] = shared.store
            this[Clock] = ImmediateClock()
            this[DateClient] = epoch
            this[VoiceWorkClient] = VoiceWorkClient.testValue.copy(
                awaitIdle = { _, _ -> },
                checkSessionActive = { _, _ -> false },
                relayedBackgroundReply = VoiceWorkClient.live(VoiceWorkCoordinator()).relayedBackgroundReply,
            )
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                waitForIdle = { _, _ -> },
                runMessages = { _, _, _ ->
                    val n = pageReads.withValue { (it + 1) to (it + 1) }
                    // First 3 reads (initial + 150ms + 400ms): no `<subagent>` yet.
                    // Next 3 reads (`fetchSettledReply` pass): `<subagent>` arrived, no `"idle"` yet.
                    // Afterwards: `"idle"` settled with final relay.
                    when {
                        n <= 3 -> OpenCodeRunMessagesPage(messages = beforeSynthetic, next = null)
                        n <= 6 -> OpenCodeRunMessagesPage(messages = unsettledAfterSynthetic, next = null)
                        else -> OpenCodeRunMessagesPage(messages = settledRelay, next = null)
                    }
                },
            )
            this[VoiceCallClient] = speechVoiceCall(announce = { _, _, text -> announced.update { it + text } })
        }

        val retryError = ReplyWatch.WaitError.Retryable("Waiting for Voice session to relay background work.")
        store.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.Sent(listOf(background)))) { it.stepping(ReplyWatch.Event.Sent(listOf(background))) }
        store.receive(
            VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitFailed(promptID = background.promptID, error = retryError, sessionStillActive = false)),
            timeout = 5.seconds,
        ) {
            it.stepping(ReplyWatch.Event.WaitFailed(promptID = background.promptID, error = retryError, sessionStillActive = false))
        }
        store.receive(
            VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitFailed(promptID = background.promptID, error = retryError, sessionStillActive = null)),
            timeout = 5.seconds,
        ) {
            it.stepping(ReplyWatch.Event.WaitFailed(promptID = background.promptID, error = retryError, sessionStillActive = null))
        }
        store.receive(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = background.promptID, reply = relayed)), timeout = 5.seconds) {
            it.stepping(ReplyWatch.Event.WaitSucceeded(promptID = background.promptID, reply = relayed))
        }
        store.finish(timeout = 5.seconds)
        effects.skipInFlightEffects()

        assertEquals(1, announced.value.size)
        assertTrue(announced.value.first().contains(relayed))
    }
}

/** A wait gives way to the user's words, not to a tag or a cancelled wait. */
class VoiceWaitYieldTests {
    @Test fun theUsersNextWordsEndADelegatorWait() = runTest {
        val harness = LiveHarness(this)
        val events = EventIterator(harness.start(), backgroundScope)
        val socket = harness.nextOpened()
        socket.yieldEvent(LiveServerEvent.SessionStarted(id = "live_1", expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, events.next())

        var heard = false
        val wait = launch {
            harness.coordinator.nextUserWords()
            heard = true
        }
        delay(50)
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "[gasp", startMilliseconds = 1_000, endMilliseconds = 1_100))
        delay(100)
        assertFalse("A tag isn't the user talking", heard)
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Hello?", startMilliseconds = 1_200, endMilliseconds = 1_500))
        wait.join()
        assertTrue(heard)

        val cancelled = launch { harness.coordinator.nextUserWords() }
        delay(50)
        cancelled.cancel()
        cancelled.join()
        assertTrue(harness.coordinator.userWordWaiters.isEmpty())
        harness.coordinator.end()
    }
}

private val epoch: DateClient = DateClient.constant(Instant.EPOCH)
private val distantPast: Instant = Instant.parse("0001-01-01T00:00:00Z")
