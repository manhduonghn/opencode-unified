package ai.opencode.feature.voice

import ai.opencode.core.testing.eventually
import ai.opencode.feature.voice.VoiceFeatureTestSupport.copy
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.hungUp
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.liveDelegatorState
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.preserveSettings
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.server
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.session
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.store
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeLiveContentEvent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class VoiceSessionDelegatorTestsProgress {
    /** Two delegations in two sessions: each hears only its own session's tools. */
    @Test fun toolsInTwoSessionsReachOnlyTheirOwnDelegation() = runBlocking {
        val shared = preserveSettings()
        val base = liveDelegatorState(shared)
        val capture = base.tail.capture().capture
        val state = base.copy(
            sessionDelegator = base.sessionDelegator.copy(
                activeSessionID = "ses_b",
                turns = listOf(
                    VoiceSessionDelegator.State.Turn(callID = "del_a", capture = capture, sessionID = "ses_a", title = "A", promptID = "msg_a", saidTurn = 0),
                    VoiceSessionDelegator.State.Turn(callID = "del_b", capture = capture, sessionID = "ses_b", title = "B", promptID = "msg_b", saidTurn = 0),
                ),
            ),
            delegatorWork = listOf("del_a", "del_b"),
        )

        val activities = Locked(listOf<List<String?>>())
        val effects = detachedScope(coroutineContext)
        val store = store(shared, state, effects, now = 2_000) {
            this[VoiceCallClient] = testVoiceCall(
                noteProgressActivity = { id, activity -> activities.update { it + listOf(listOf(id, activity)) } },
            )
        }

        store.send(
            VoiceFeature.Action.SessionDelegatorTools(
                sessionID = "ses_a",
                events = listOf(OpenCodeLiveContentEvent.ToolInputStarted(messageID = "m1", toolID = "t1", name = "shell")),
            ),
        ) {
            it.copy(
                sessionDelegator = it.sessionDelegator.copy(
                    toolNames = mapOf("t1" to "shell"),
                    runningTools = listOf(VoiceSessionDelegator.State.RunningTool(id = "t1", name = "shell", label = "Running shell", sessionID = "ses_a")),
                ),
            )
        }
        store.send(
            VoiceFeature.Action.SessionDelegatorTools(
                sessionID = "ses_b",
                events = listOf(OpenCodeLiveContentEvent.ToolInputStarted(messageID = "m2", toolID = "t2", name = "grep")),
            ),
        ) {
            it.copy(
                sessionDelegator = it.sessionDelegator.copy(
                    toolNames = it.sessionDelegator.toolNames + ("t2" to "grep"),
                    runningTools = it.sessionDelegator.runningTools +
                        VoiceSessionDelegator.State.RunningTool(id = "t2", name = "grep", label = "Searching files", sessionID = "ses_b"),
                ),
            )
        }
        store.finish()
        assertEquals(listOf(listOf("del_a", "running a command"), listOf("del_b", "searching the code")), activities.value)

        // A's tool ending leaves A with nothing named, not B's search.
        store.send(
            VoiceFeature.Action.SessionDelegatorTools(
                sessionID = "ses_a",
                events = listOf(OpenCodeLiveContentEvent.ToolSucceeded(messageID = "m1", toolID = "t1", output = listOf("ok"), metadata = emptyMap())),
            ),
        ) {
            it.copy(
                sessionDelegator = it.sessionDelegator.copy(
                    toolNames = it.sessionDelegator.toolNames - "t1",
                    runningTools = listOf(VoiceSessionDelegator.State.RunningTool(id = "t2", name = "grep", label = "Searching files", sessionID = "ses_b")),
                ),
            )
        }
        store.finish()
        assertEquals(listOf("del_a", null), activities.value.last())
        effects.skipInFlightEffects()
    }

    @Test fun runningToolsClearPerSessionAndASupersededTurnHearsNothing() {
        var state = VoiceSessionDelegator.State(
            runningTools = listOf(
                VoiceSessionDelegator.State.RunningTool(id = "t1", name = "shell", label = "Running shell", sessionID = "ses_a"),
                VoiceSessionDelegator.State.RunningTool(id = "t2", name = "grep", label = "Searching files", sessionID = "ses_b"),
            ),
            toolNames = mapOf("t1" to "shell", "t2" to "grep"),
        )
        assertEquals("Running shell", state.progressCaption("ses_a"))
        assertEquals("Searching files", state.progressCaption("ses_b"))
        state = state.clearRunningTools("ses_b")
        assertEquals(listOf("t1"), state.runningTools.map { it.id })
        assertEquals(mapOf("t1" to "shell"), state.toolNames)

        val capture = VoiceTranscriptTail().capture().capture
        state = state.copy(
            turns = listOf(
                VoiceSessionDelegator.State.Turn(callID = "del_old", capture = capture, sessionID = "ses_a", promptID = "msg_old", saidTurn = 0),
                VoiceSessionDelegator.State.Turn(callID = "del_new", capture = capture, sessionID = "ses_a", promptID = "msg_new", saidTurn = 0, superseded = true),
                VoiceSessionDelegator.State.Turn(callID = "del_admitting", capture = capture, sessionID = "ses_a", saidTurn = 0),
            ),
        )
        assertEquals("Superseded and unadmitted turns never speak", "del_old", state.progressCallID("ses_a"))
        assertNull(state.progressCallID("ses_b"))
    }

    /**
     * The built-in delegator's tools set the line's activity, and a tool
     * finishing after the call ended tells it nothing.
     */
    @Test fun builtInDelegatorToolsNoteActivityUntilTheCallEnds() = runBlocking {
        val shared = preserveSettings()
        val state = shared.read { VoiceFeature.State(server, session) }
            .withPhase(VoiceFeature.State.Phase.Live)
            .copy(usesDelegator = true)

        val activities = Locked(listOf<List<String?>>())
        val listing = Locked(false)
        val listed = Locked(false)
        val endedCalls = Locked(0)
        val effects = detachedScope(coroutineContext)
        val store = store(shared, state, effects, now = 5_000) {
            this[VoiceDelegatorClient] = VoiceDelegatorClient(isEnabled = true) { _, _, _ ->
                throw OpenCodeClientError("Unused on the call")
            }
            this[VoiceCallClient] = testVoiceCall(
                delegatorRound = {
                    Result.success(
                        VoiceDelegator.Response(
                            id = "resp_1",
                            calls = listOf(
                                VoiceDelegator.ToolCall(callID = "t1", name = "list_servers", arguments = "{}"),
                                VoiceDelegator.ToolCall(callID = "t2", name = "list_sessions", arguments = "{}"),
                            ),
                            text = "",
                        ),
                    )
                },
                noteProgressActivity = { id, activity -> activities.update { it + listOf(listOf(id, activity)) } },
                end = { endedCalls.update { it + 1 } },
            )
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeSnapshot = {
                    listing.set(true)
                    try {
                        VoiceCallClient.untilCancelled()
                    } finally {
                        listed.set(true)
                    }
                    throw OpenCodeClientError("Cancelled")
                },
            )
        }

        store.send(VoiceFeature.Action.CallEvent(VoiceCallEvent.ServerDelegation(callID = "d1"))) {
            val acted = it.copy(replyWatch = it.replyWatch.noteUserActed())
            val captured = acted.tail.capture()
            acted.copy(tail = captured.tail.accept(captured.capture), delegatorWork = listOf("d1"))
                .stepping(ReplyWatch.Event.DelegatorStarted(requestID = "d1"))
        }
        assertTrue(eventually(5.seconds) { listing.value })
        assertEquals(
            listOf(listOf("d1", "looking through your sessions"), listOf("d1", null), listOf("d1", "looking through your sessions")),
            activities.value,
        )

        store.send(VoiceFeature.Action.EndTapped) { it.hungUp() }
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
        assertTrue(eventually(5.seconds) { listed.value })
        repeat(100) { yield() }
        assertEquals("The tool that ended after the call told nothing", 3, activities.value.size)
        assertEquals(1, endedCalls.value)
        effects.skipInFlightEffects()
    }
}
