package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.store.TestStore
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDelegationFeatureTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_voice", serverTitle = "Voice lab", projectID = "p", directory = "/Users/kit/project", updatedAt = Instant.EPOCH)
    private val emptyArgs = RealtimeJSONs.obj()

    private fun callEvent(event: VoiceCallEvent) = VoiceFeature.Action.CallEvent(event)

    private fun TestScope.liveStore(
        shared: VoiceTestShared,
        dependencies: DependencyValues.Builder.() -> Unit,
    ): TestStore<VoiceFeature.State, VoiceFeature.Action> {
        val store = voiceTestStore(shared, { VoiceFeature.State(server, session) }, dependencies = dependencies)
        store.exhaustive = false
        shared.set(SharedKeys.openAIAPIKey, "sk-test")
        shared.set(VoiceSharedKeys.voiceEngine, VoiceEngine.gptLive)
        return store
    }

    private suspend fun TestStore<VoiceFeature.State, VoiceFeature.Action>.connect(events: CallEvents) {
        send(VoiceFeature.Action.Start)
        events.yieldEvent(VoiceCallEvent.Connected)
        receive(::isCallEvent) { it.withPhase(VoiceFeature.State.Phase.Live) }
    }

    @Test fun clientDelegationBuildsPromptFromAccumulatedUserTranscriptsWithoutBlockingConversation() = runTest {
        val events = CallEvents()
        val releaseReply = Gate<String>()
        val submitted = mutableListOf<String>()
        val completed = mutableListOf<Pair<String, String>>()
        val shared = VoiceTestShared()
        val store = liveStore(shared) {
            this[VoiceCallClient] = testVoiceCall(
                start = { _, _ -> events.flow },
                completeFunction = { callID, output -> completed += callID to output },
            )
            this[VoiceWorkClient] = testVoiceWork(
                submit = { _, text ->
                    submitted += text
                    VoiceWorkAdmission(promptID = "msg_live_1")
                },
                awaitReply = { _, _, _ -> releaseReply.first() },
            )
        }
        store.connect(events)

        // User speaks across two utterances before GPT-Live delegates.
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Can you check why the upload test is failing?", final = true))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "Which target?"))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "live-u-2", text = "In OpenCodeTests.", final = true))
        store.receive(::isCallEvent)

        // GPT-Live client delegation has empty tool arguments.
        events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "item_del_1", name = "delegate", arguments = emptyArgs))
        store.receive(::isCallEvent)
        store.receive(::isWorkAdmitted)

        // Verify the bubble instruction has the user's own combined words and app context.
        val prompt = submitted.first()
        val parsed = VoiceHandoff.parse(prompt) ?: throw AssertionError("Expected a voice handoff")
        assertEquals("Can you check why the upload test is failing? In OpenCodeTests.", parsed.instruction)
        assertEquals(
            listOf(
                "user: Can you check why the upload test is failing?",
                "assistant: Which target?",
                "user: In OpenCodeTests.",
            ),
            parsed.transcriptDelta,
        )
        assertTrue(parsed.context?.contains("Session \"Voice lab\" in project \"project\".") == true)

        // Conversation continues non-blocking while the OpenCode session is still working!
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "live-u-3", text = "While that runs, how are we doing?", final = true))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.AssistantDelta(itemID = "live-a-2", delta = "Still running the test suite."))
        store.receive(::isCallEvent)
        assertTrue(store.state.isWorking)
        assertEquals("While that runs, how are we doing?", store.state.captions.last().text)

        // Now the OpenCode session finishes and feeds the result back for item_del_1.
        releaseReply.yieldValue("The upload test passed after freeing the port.")
        releaseReply.finish()
        store.receive(::isWorkReplied) { it.copy(pendingWork = emptyList()) }
        assertEquals("item_del_1", completed.firstOrNull()?.first)
        assertEquals("[COMPLETE] The upload test passed after freeing the port.", completed.firstOrNull()?.second)

        events.finish()
        store.receive(::isCallEnded) { it.withPhase(VoiceFeature.State.Phase.Ended) }
        store.receive(::isDelegateEnded)
        store.finish()
    }

    @Test fun capabilityDiscoveryScenariosDelegateExternalDataUnknownIntegrationsUnavailableSourcesAndPendingFollowUps() = runTest {
        val events = CallEvents()
        val pendingReplies = mutableMapOf<String, Gate<String>>()
        val submitted = mutableListOf<Pair<String, String>>()
        val completed = mutableMapOf<String, String>()
        var nextPromptNumber = 0
        val shared = VoiceTestShared()
        val store = liveStore(shared) {
            this[VoiceCallClient] = testVoiceCall(
                start = { _, _ -> events.flow },
                completeFunction = { callID, output -> completed[callID] = output },
            )
            this[VoiceWorkClient] = testVoiceWork(
                submit = { _, text ->
                    nextPromptNumber += 1
                    val promptID = "msg_cap_$nextPromptNumber"
                    val instruction = VoiceHandoff.parse(text)?.instruction ?: ""
                    submitted += promptID to instruction
                    VoiceWorkAdmission(promptID = promptID)
                },
                awaitReply = { _, _, promptID -> pendingReplies.getOrPut(promptID) { Gate() }.first() },
            )
        }
        store.connect(events)

        val scenarios = listOf(
            // 1. Messages (fictional)
            Triple("del_messages", "My friend Jordan texted me, what did they say?", "Jordan said dinner is moved to 7:30 PM at Harbor Noodle."),
            // 2. Mail (fictional)
            Triple("del_mail", "Check if the receipt from Northwind Books arrived in my email.", "Northwind Books sent receipt #NW-4021 for \$34.00."),
            // 3. Purchase history (fictional)
            Triple("del_purchases", "Look up when I ordered the desk lamp in my purchase history.", "Ordered on September 14 for \$68.00; delivered September 17."),
            // 4. Unknown integration (fictional)
            Triple("del_unknown", "Check the deploy status in AcmeDeploy.", "No AcmeDeploy CLI or credentials are configured in this workspace."),
            // 5. Unavailable source (fictional narrow blocker)
            Triple("del_unavailable", "Read the notes from the external backup drive.", "The backup volume /Volumes/FieldArchive is not mounted."),
        )

        for ((index, scenario) in scenarios.withIndex()) {
            val (callID, userUtterance, sessionReply) = scenario
            val promptID = "msg_cap_${index + 1}"
            events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "u_$callID", text = userUtterance, final = true))
            store.receive(::isCallEvent)
            events.yieldEvent(VoiceCallEvent.FunctionCall(callID = callID, name = "delegate", arguments = emptyArgs))
            store.receive(::isCallEvent)
            store.receive(::isWorkAdmitted)

            assertEquals(userUtterance, submitted.lastOrNull()?.second)
            pendingReplies.getOrPut(promptID) { Gate() }.apply { yieldValue(sessionReply); finish() }
            store.receive(::isWorkReplied)
            assertEquals("[COMPLETE] $sessionReply", completed[callID])
        }

        // 6. Pending delegation: a follow-up while the first request is still running delegates immediately to steer it.
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "u_pending_1", text = "Search all test logs for timeouts.", final = true))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_pending_1", name = "delegate", arguments = emptyArgs))
        store.receive(::isCallEvent)
        store.receive(::isWorkAdmitted)
        assertEquals(1, store.state.pendingWork.size)

        // Follow-up arrives before del_pending_1 finishes: still delegates!
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "u_pending_2", text = "Actually only check the last two runs, how is it going?", final = true))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_pending_2", name = "delegate", arguments = emptyArgs))
        store.receive(::isCallEvent)
        store.receive(::isWorkAdmitted)
        assertEquals(2, store.state.pendingWork.size)
        assertEquals("Actually only check the last two runs, how is it going?", submitted.lastOrNull()?.second)

        pendingReplies.getOrPut("msg_cap_6") { Gate() }.apply { yieldValue("Checked the last two runs: zero timeouts."); finish() }
        pendingReplies.getOrPut("msg_cap_7") { Gate() }.apply { yieldValue("Wrapped up with the last two runs."); finish() }
        store.receive(::isWorkReplied)
        store.receive(::isWorkReplied) { it.copy(pendingWork = emptyList()) }

        events.finish()
        store.receive(::isCallEnded) { it.withPhase(VoiceFeature.State.Phase.Ended) }
        store.receive(::isDelegateEnded)
        store.finish()
    }

    /**
     * D2: the voice agent owns follow-ups and corrections. Words that land
     * after a delegation never steer the session on their own; the next
     * delegation carries everything said since the previous one.
     */
    @Test fun lateWordsWaitForTheNextDelegationWhichCarriesEverythingSinceThePreviousOne() = runTest {
        val events = CallEvents()
        val firstSubmitGate = Gate<Unit>()
        val enteredFirstSubmit = Gate<Unit>()
        val pendingReplies = mutableMapOf<String, Gate<String>>()
        val submitted = mutableListOf<String>()
        val shared = VoiceTestShared()
        val store = liveStore(shared) {
            this[VoiceCallClient] = testVoiceCall(
                start = { _, _ -> events.flow },
            )
            this[VoiceWorkClient] = testVoiceWork(
                submit = { _, text ->
                    submitted += text
                    val count = submitted.size
                    if (count == 1) {
                        enteredFirstSubmit.yieldValue(Unit)
                        firstSubmitGate.first()
                    }
                    VoiceWorkAdmission(promptID = "msg_$count")
                },
                awaitReply = { _, _, promptID -> pendingReplies.getOrPut(promptID) { Gate() }.first() },
            )
        }
        store.connect(events)

        // 1. A delegation goes out with what was said so far, labelled by the acknowledgement.
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Check why the upload test", final = true))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_1", name = "delegate", arguments = RealtimeJSONs.obj("text" to RealtimeJSONs.string("Checking the upload test."))))
        store.receive(::isCallEvent)
        enteredFirstSubmit.first()

        // 2. The utterance keeps going while the first prompt is in flight, and after it lands:
        // the caption updates in place, and nothing else is sent.
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Check why the upload test is failing", final = true))
        store.receive(::isCallEvent)
        firstSubmitGate.yieldValue(Unit)
        firstSubmitGate.finish()
        store.receive(::isWorkAdmitted)
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Check why the upload test is failing on staging.", final = true))
        store.receive(::isCallEvent)
        assertEquals(listOf("user-live-u-1"), store.state.captions.map { it.id })
        assertEquals("Check why the upload test is failing on staging.", store.state.captions.first().text)
        delay(30)
        assertEquals("Late words never send a second steer prompt", 1, submitted.size)
        assertEquals("msg_1", store.state.pendingWork.firstOrNull()?.promptID)

        // 3. A correction: the voice agent delegates again, and that delegation
        // carries everything said since the first one.
        events.yieldEvent(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "Checking the upload test."))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "live-u-2", text = "Actually only the last run.", final = true))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_2", name = "delegate", arguments = RealtimeJSONs.obj("text" to RealtimeJSONs.string("Checking the last staging run."))))
        store.receive(::isCallEvent)
        store.receive(::isWorkAdmitted)
        assertEquals(2, submitted.size)
        val first = VoiceHandoff.parse(submitted[0]) ?: throw AssertionError("Expected a voice handoff")
        assertEquals("Check why the upload test", first.instruction)
        assertEquals("Checking the upload test.", first.framing)
        val correction = VoiceHandoff.parse(submitted[1]) ?: throw AssertionError("Expected a voice handoff")
        assertEquals("is failing on staging. Actually only the last run.", correction.instruction)
        assertEquals("Checking the last staging run.", correction.framing)
        assertEquals(
            listOf(
                "user (updated utterance): Check why the upload test is failing on staging.",
                "assistant: Checking the upload test.",
                "user: Actually only the last run.",
            ),
            correction.transcriptDelta,
        )
        assertEquals(listOf("msg_1", "msg_2"), store.state.pendingWork.map { it.promptID })

        pendingReplies.getOrPut("msg_1") { Gate() }.apply { yieldValue("Superseded by the correction."); finish() }
        pendingReplies.getOrPut("msg_2") { Gate() }.apply { yieldValue("The last staging run failed on a timeout."); finish() }
        store.receive(::isWorkReplied)
        store.receive(::isWorkReplied) { it.copy(pendingWork = emptyList()) }

        events.finish()
        store.receive(::isCallEnded) { it.withPhase(VoiceFeature.State.Phase.Ended) }
        store.receive(::isDelegateEnded)
        store.finish()
    }

    /**
     * D4a: the long header goes once per call per session, and again after
     * the session compacts or after a long gap; otherwise the short marker.
     */
    @Test fun theLongHeaderGoesOncePerCallPerSessionAndAfterCompactionOrALongGap() = runTest {
        val submitted = mutableListOf<String>()
        var clock = Instant.ofEpochSecond(1_000)
        // Each call gets its own event stream.
        val calls = mutableListOf<CallEvents>()
        val shared = VoiceTestShared()
        val store = liveStore(shared) {
            this[DateClient] = DateClient { clock }
            this[VoiceCallClient] = testVoiceCall(start = { _, _ ->
                val call = CallEvents()
                calls += call
                call.flow
            })
            this[VoiceWorkClient] = testVoiceWork(
                submit = { _, text ->
                    submitted += text
                    VoiceWorkAdmission(promptID = "msg_${submitted.size}")
                },
                awaitReply = { _, _, _ -> "Done." },
            )
        }

        fun call() = calls.last()
        var count = 0
        suspend fun delegate(after: Double = 5.0): String {
            clock = clock.plusMillis((after * 1_000).toLong())
            count += 1
            call().yieldEvent(VoiceCallEvent.UserTranscript(itemID = "u_$count", text = "Request $count.", final = true))
            store.receive(::isCallEvent)
            call().yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_$count", name = "delegate", arguments = emptyArgs))
            store.receive(::isCallEvent)
            store.receive(::isWorkAdmitted)
            store.receive(::isWorkReplied)
            val prompt = submitted[count - 1]
            assertEquals("Both forms parse", "Request $count.", VoiceHandoff.parse(prompt)?.instruction)
            return prompt
        }
        fun isFull(prompt: String) = prompt.startsWith(VoiceHandoff.header + "\n")
        fun isContinued(prompt: String) = prompt.startsWith(VoiceHandoff.continuedHeader + "\n")

        store.send(VoiceFeature.Action.Start)
        waitUntil { calls.isNotEmpty() }
        call().yieldEvent(VoiceCallEvent.Connected)
        store.receive(::isCallEvent) { it.withPhase(VoiceFeature.State.Phase.Live) }

        // First into the session: full. Repeats: short.
        assertTrue(isFull(delegate()))
        assertTrue(isContinued(delegate()))
        assertTrue("Within the lapse", isContinued(delegate(after = 29.0 * 60)))

        // The session compacted: the summary dropped the explanation.
        store.send(VoiceFeature.Action.CompactionObserved(VoiceFeature.State.CompactionMark(sessionID = "ses_voice", compactionID = "cmp_1")))
        assertTrue(isFull(delegate()))
        assertTrue(isContinued(delegate()))
        // A compaction seen in another session doesn't count here.
        store.send(VoiceFeature.Action.CompactionObserved(VoiceFeature.State.CompactionMark(sessionID = "ses_elsewhere", compactionID = "cmp_x")))
        assertTrue(isContinued(delegate()))

        // A long gap without a delegation.
        assertTrue(isFull(delegate(after = VoiceHandoff.headerLapse + 1)))
        assertTrue(isContinued(delegate()))

        // A new call explains itself again.
        store.send(VoiceFeature.Action.EndTapped)
        store.receive(::isDelegateEnded)
        store.send(VoiceFeature.Action.Start)
        waitUntil { calls.size >= 2 }
        call().yieldEvent(VoiceCallEvent.Connected)
        store.receive(::isCallEvent)
        assertTrue(isFull(delegate()))

        call().finish()
        store.skipReceivedActions()
        store.finish()
    }

    /**
     * A request on its way when the call moves still goes to its session,
     * header and all, so coming back doesn't explain the call again.
     */
    @Test fun movingAwayBeforeAHeaderIsAdmittedKeepsItsRequest() {
        val shared = VoiceTestShared()
        val values = DependencyValues.testing().update {
            this[SharedStore] = shared.store
            this[DateClient] = DateClient.constant(Instant.EPOCH)
        }
        DependencyValues.withValues(values) {
            val other = OpenCodeSession(id = "ses_other", serverTitle = "Other", projectID = "p", directory = "/Users/kit/project", updatedAt = Instant.EPOCH)
            val tail = VoiceTranscriptTail()
            val capture = tail.capture().capture
            var voice = VoiceFeature.State(server, session)
            voice = voice.copy(handoffs = voice.handoffs + ("ses_voice" to VoiceFeature.State.Handoff(lastDelegationAt = Instant.EPOCH, compactionID = null)))
            voice = voice.copy(pendingWork = listOf(VoiceFeature.State.PendingWork(callID = "del_1", capture = capture, explainsHeader = true, target = voice.target, move = voice.moves)))
            voice = voice.retarget(other)
            assertEquals(listOf("del_1"), voice.pendingWork.map { it.callID })
            assertEquals("ses_voice", voice.pendingWork.firstOrNull()?.target?.sessionID)
            assertNotNull(voice.handoffs["ses_voice"])
            assertTrue(voice.abandonedCallIDs.isEmpty())
            assertFalse("Its run is the other session's, not this one's", voice.hasAdmittedWork)
        }
    }

    @Test fun aFailedFirstDelegationLeavesTheHeaderForTheNext() = runTest {
        val events = CallEvents()
        val submitted = mutableListOf<String>()
        val shared = VoiceTestShared()
        val store = voiceTestStore(shared, { VoiceFeature.State(server, session) }) {
            this[VoiceCallClient] = testVoiceCall(start = { _, _ -> events.flow })
            this[VoiceWorkClient] = testVoiceWork(
                submit = { _, text ->
                    submitted += text
                    val count = submitted.size
                    if (count == 1) throw OpenCodeClientError("HTTP 503")
                    VoiceWorkAdmission(promptID = "msg_$count")
                },
                awaitReply = { _, _, _ -> "Done." },
            )
        }
        store.exhaustive = false
        shared.set(SharedKeys.openAIAPIKey, "sk-test")
        store.send(VoiceFeature.Action.Start)
        events.yieldEvent(VoiceCallEvent.Connected)
        store.receive(::isCallEvent)
        for (index in 1..2) {
            events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "u_$index", text = "Request $index.", final = true))
            store.receive(::isCallEvent)
            events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_$index", name = "delegate", arguments = emptyArgs))
            store.receive(::isCallEvent)
            if (index == 1) store.receive(::isWorkFailed) else store.receive(::isWorkAdmitted)
        }
        assertTrue(submitted.all { it.startsWith(VoiceHandoff.header + "\n") })
        events.finish()
        store.skipReceivedActions()
        store.finish()
    }

    @Test fun aVagueAcknowledgementLabelsTheVoiceMessageWithTheUsersWords() = runTest {
        val events = CallEvents()
        val submitted = mutableListOf<String>()
        val shared = VoiceTestShared()
        val store = liveStore(shared) {
            this[VoiceCallClient] = testVoiceCall(start = { _, _ -> events.flow })
            this[VoiceWorkClient] = testVoiceWork(
                submit = { _, text ->
                    submitted += text
                    VoiceWorkAdmission(promptID = "msg_${submitted.size}")
                },
                awaitReply = { _, _, _ -> "Done." },
            )
        }
        store.connect(events)

        // Vague, then missing: both show the user's words.
        for ((index, arguments) in listOf(RealtimeJSONs.obj("text" to RealtimeJSONs.string("Sure, I'll take a look.")), emptyArgs).withIndex()) {
            events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "live-u-$index", text = "Run the parser tests $index.", final = true))
            store.receive(::isCallEvent)
            events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_$index", name = "delegate", arguments = arguments))
            store.receive(::isCallEvent)
            store.receive(::isWorkAdmitted)
            store.receive(::isWorkReplied)
            val delegation = VoiceHandoff.parse(submitted[index]) ?: throw AssertionError("Expected a voice handoff")
            assertEquals("Run the parser tests $index.", delegation.instruction)
            assertNull(delegation.framing)
            assertEquals("Run the parser tests $index.", delegation.displayText)
        }

        events.finish()
        store.receive(::isCallEnded)
        store.skipReceivedActions()
        store.finish()
    }

    /**
     * The call drops delegations still waiting in the coordinator as soon as
     * it moves, not after re-reading the new session's messages.
     */
    @Test fun movingTheCallDropsWaitingDelegationsBeforeReadingTheNewSession() = runTest {
        val order = mutableListOf<String>()
        val gate = Gate<Unit>()
        val shared = VoiceTestShared()
        val store = voiceTestStore(shared, {
            VoiceFeature.State(server, session).withPhase(VoiceFeature.State.Phase.Live)
        }) {
            this[VoiceCallClient] = testVoiceCall(
                retarget = { order += "retarget" },
                updateInstructions = { _, _ -> order += "instructions" },
            )
            this[VoiceWorkClient] = testVoiceWork(recentMessages = { _, _, _ ->
                order += "read"
                gate.first()
                emptyList()
            })
        }
        store.exhaustive = false
        store.send(VoiceFeature.Action.TargetChanged)
        waitUntil { order.contains("read") }
        assertEquals("retarget", order.first())
        gate.yieldValue(Unit)
        gate.finish()
        waitUntil { order.contains("instructions") }
        assertEquals(listOf("retarget", "read", "instructions"), order)
        store.skipReceivedActions()
    }

    @Test fun clarificationStatusCheckRepeatedReadAndAmbiguousWriteHandledDistinctly() = runTest {
        val events = CallEvents()
        val pendingReplies = mutableMapOf<String, Gate<String>>()
        val submitted = mutableListOf<Pair<String, String>>()
        val completed = mutableMapOf<String, String>()
        var nextPromptNumber = 0
        val shared = VoiceTestShared()
        val store = liveStore(shared) {
            this[VoiceCallClient] = testVoiceCall(
                start = { _, _ -> events.flow },
                completeFunction = { callID, output -> completed[callID] = output },
            )
            this[VoiceWorkClient] = testVoiceWork(
                submit = { _, text ->
                    nextPromptNumber += 1
                    val promptID = "msg_flow_$nextPromptNumber"
                    val instruction = VoiceHandoff.parse(text)?.instruction ?: ""
                    submitted += promptID to instruction
                    VoiceWorkAdmission(promptID = promptID)
                },
                awaitReply = { _, _, promptID -> pendingReplies.getOrPut(promptID) { Gate() }.first() },
            )
        }
        fun reply(promptID: String, text: String) = pendingReplies.getOrPut(promptID) { Gate() }.apply { yieldValue(text); finish() }
        store.connect(events)

        // 1. Initial task starts running.
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "u_1", text = "Run the parser tests.", final = true))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_1", name = "delegate", arguments = emptyArgs))
        store.receive(::isCallEvent)
        store.receive(::isWorkAdmitted)

        // 2. Clarification while running steers the existing task immediately.
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "u_2", text = "Only run the markdown table cases.", final = true))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_clarify", name = "delegate", arguments = emptyArgs))
        store.receive(::isCallEvent)
        store.receive(::isWorkAdmitted)
        assertEquals("Only run the markdown table cases.", submitted.lastOrNull()?.second)

        // 3. Explicit poke/status request while running asks the session for its current state.
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "u_3", text = "How is that test run going?", final = true))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_status", name = "delegate", arguments = emptyArgs))
        store.receive(::isCallEvent)
        store.receive(::isWorkAdmitted)
        assertEquals("How is that test run going?", submitted.lastOrNull()?.second)

        reply("msg_flow_3", "12 of 14 markdown table test cases have passed; 2 are still running.")
        store.receive(::isWorkReplied)
        assertEquals("[COMPLETE] 12 of 14 markdown table test cases have passed; 2 are still running.", completed["del_status"])

        reply("msg_flow_1", "Initial run superseded by filter.")
        reply("msg_flow_2", "All 14 markdown table test cases passed.")
        store.receive(::isWorkReplied)
        store.receive(::isWorkReplied) { it.copy(pendingWork = emptyList()) }

        // 4. Repeated read-only request is delegated again without refusal.
        for (attempt in 1..2) {
            events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "u_read_$attempt", text = "Check the latest CI status.", final = true))
            store.receive(::isCallEvent)
            events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_read_$attempt", name = "delegate", arguments = emptyArgs))
            store.receive(::isCallEvent)
            store.receive(::isWorkAdmitted)
            assertEquals("Check the latest CI status.", submitted.lastOrNull()?.second)
            reply("msg_flow_${3 + attempt}", "CI build #812 is green.")
            store.receive(::isWorkReplied)
        }

        // 5. Ambiguous external write: status check delegates first, then retries only after confirming it didn't happen.
        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "u_write_check", text = "Check whether the invoice email to Riverstone Studio was sent.", final = true))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_write_check", name = "delegate", arguments = emptyArgs))
        store.receive(::isCallEvent)
        store.receive(::isWorkAdmitted)
        reply("msg_flow_6", "No invoice email was sent to Riverstone Studio; the draft is still unsent.")
        store.receive(::isWorkReplied)
        assertTrue(completed["del_write_check"]?.contains("still unsent") == true)

        events.yieldEvent(VoiceCallEvent.UserTranscript(itemID = "u_write_retry", text = "Send the invoice email to Riverstone Studio now.", final = true))
        store.receive(::isCallEvent)
        events.yieldEvent(VoiceCallEvent.FunctionCall(callID = "del_write_retry", name = "delegate", arguments = emptyArgs))
        store.receive(::isCallEvent)
        store.receive(::isWorkAdmitted)
        reply("msg_flow_7", "Sent invoice #RS-104 to Riverstone Studio.")
        store.receive(::isWorkReplied)
        assertEquals("[COMPLETE] Sent invoice #RS-104 to Riverstone Studio.", completed["del_write_retry"])

        events.finish()
        store.receive(::isCallEnded) { it.withPhase(VoiceFeature.State.Phase.Ended) }
        store.receive(::isDelegateEnded)
        store.finish()
    }
}
