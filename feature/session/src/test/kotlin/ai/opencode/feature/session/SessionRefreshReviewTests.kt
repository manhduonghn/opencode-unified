package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.store.TestStore
import ai.opencode.feature.composer.LocationClient
import ai.opencode.feature.composer.LocationClientError
import ai.opencode.feature.composer.SharedLocation
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.AuthoritativeRefresh
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.distantPast
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeContextReading
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.OpenCodeTranscriptPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.max

/** `SessionRefreshReviewTests` and the SessionFeature half of `SharedLocationTests`. */
class SessionRefreshReviewTests {
    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_review", serverTitle = "Review", projectID = "p", directory = "/project", updatedAt = distantPast)
    private val user = OpenCodeTranscriptItem.UserText(id = "msg_user", text = "Check")
    private val text = OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_reply-text-0", text = "Unpersisted")

    private fun Harness.state(session: OpenCodeSession = this@SessionRefreshReviewTests.session, cachedItems: List<OpenCodeTranscriptItem>, server: OpenCodeServer = this@SessionRefreshReviewTests.server): State =
        state { State.session(server, session, cachedItems = cachedItems) }

    private fun TestStore<State, Action>.client(update: OpenCodeClient.() -> OpenCodeClient) =
        updateDependencies { this[OpenCodeClient] = this[OpenCodeClient].update() }

    private fun response(requestID: Int, items: List<OpenCodeTranscriptItem>, scope: AuthoritativeRefresh.Scope, pageCursor: AuthoritativeRefresh.PageCursor? = null, session: OpenCodeSession = this.session) =
        Action.RefreshResponse(session.id, requestID, Result.success(AuthoritativeRefresh(observed = session, items = items, scope = scope, pageCursor = pageCursor)))

    private class Gate {
        private val channel = Channel<Unit>(Channel.UNLIMITED)
        suspend fun await() { channel.receiveCatching() }
        fun open() { channel.trySend(Unit); channel.close() }
    }

    @Test fun idleSnapshotDoesNotResurrectRacedParts() {
        for (targeted in listOf(false, true)) sessionTest { h ->
            val state = h.state(cachedItems = listOf(user, text)).copy(
                projectedMessageIDs = setOf("msg_reply"),
                liveText = mapOf("msg_reply-text-0" to "Unpersisted"),
                refresh = State.Refresh(
                    id = 1,
                    reason = RefreshReason.Settlement(messageID = "msg_reply"),
                    racedEvents = listOf(
                        OpenCodeLiveContentEvent.TextEnded(messageID = "msg_reply", ordinal = 0, text = "Unpersisted"),
                        OpenCodeLiveContentEvent.ToolInputStarted(messageID = "msg_reply", toolID = "call", name = "shell"),
                    ),
                ),
            )
            val store = h.store(state)
            store.exhaustive = false
            store.send(response(1, if (targeted) emptyList() else listOf(user), if (targeted) AuthoritativeRefresh.Scope.Message("msg_reply") else AuthoritativeRefresh.Scope.Page))
            assertEquals("targeted=$targeted", listOf<OpenCodeTranscriptItem>(user), store.state.items)
            assertTrue(store.state.liveText.isEmpty())
            assertTrue(store.state.projectedMessageIDs.isEmpty())
        }
    }

    @Test fun terminalExecutionQueuesAFullReadBehindAPartSettlement() {
        for (targeted in listOf(false, true)) sessionTest { h ->
            val tool = OpenCodeTranscriptItem.Tool(
                id = "msg_reply-tool-call",
                call = OpenCodeToolCall(
                    callID = "call", messageID = "msg_reply", name = "tool", status = OpenCodeToolCall.Status.Completed,
                    input = OpenCodeJSONValue.Object(emptyMap()), output = listOf("Done"), error = null, metadata = emptyMap(),
                ),
            )
            val ghost = OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_reply-text-1", text = "")
            val state = h.state(cachedItems = listOf(user, text)).copy(
                isExternallyActive = true,
                projectedMessageIDs = setOf("msg_reply"),
                refresh = State.Refresh(id = 1, reason = RefreshReason.Settlement(messageID = "msg_reply")),
                nextRefreshID = 1,
            )
            val finalRead = Gate()
            val store = h.store(state) {
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    getSession = { _, _ -> session },
                    transcript = { _, _, _ ->
                        finalRead.await()
                        listOf(user, text, tool)
                    },
                )
            }
            store.exhaustive = false
            store.send(
                Action.ContentBatch(
                    session.id,
                    listOf(
                        OpenCodeLiveContentEvent.TextEnded(messageID = "msg_reply", ordinal = 0, text = "Unpersisted"),
                        OpenCodeLiveContentEvent.ToolSucceeded(messageID = "msg_reply", toolID = "call", output = listOf("Done"), metadata = emptyMap()),
                    ),
                ),
            )
            store.receive(Action.RefreshRequested(RefreshReason.Settlement(messageID = "msg_reply")))
            store.send(Action.ExternalRunStateChanged(isRunning = false))
            assertEquals(RefreshReason.RunState, store.state.refresh?.followup)
            store.receive(Action.RefreshRequested(RefreshReason.RunState))
            store.send(Action.RefreshRequested(RefreshReason.Invalidation))
            assertEquals(RefreshReason.RunState, store.state.refresh?.followup)
            assertEquals(setOf("msg_reply"), store.state.projectedMessageIDs)
            val stale = OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_reply-text-0", text = "")
            store.send(response(1, if (targeted) listOf(stale, ghost) else listOf(user, stale, ghost), if (targeted) AuthoritativeRefresh.Scope.Message("msg_reply") else AuthoritativeRefresh.Scope.Page))
            assertTrue("R1 must replay the raced final text", store.state.items.contains(text))
            assertTrue("R1 must retain the completed tool absent from its snapshot", store.state.items.contains(tool))
            store.receive(Action.RefreshRequested(RefreshReason.RunState))
            finalRead.open()
            store.receive(Action.RefreshResponse::class)
            assertEquals("The terminal read retires only the unpersisted part", listOf(user, text, tool), store.state.items)
            assertTrue(store.state.projectedMessageIDs.isEmpty())
            store.finish()
        }
    }

    @Test fun serverIdleSettlesAnAdmittedSendUnlessALocalPromptStillWaits() {
        for (pending in listOf("none", "submitting", "queued", "echoed")) sessionTest { h ->
            val next = OpenCodeTranscriptItem.UserText(id = "msg_next", text = "Next")
            var state = h.state(cachedItems = listOf(user, text)).copy(
                isAwaitingReply = true,
                isExternallyActive = true,
                projectedMessageIDs = setOf("msg_reply"),
            )
            if (pending != "none") {
                state = state.copy(
                    promptStates = state.promptStates + ("msg_next" to State.PromptState(requestEpoch = 1, admission = if (pending == "submitting") State.PromptState.Admission.sent else State.PromptState.Admission.admitted)),
                )
                if (pending != "submitting") state = state.copy(queuedInputs = listOf(OpenCodePendingInput(id = "msg_next", text = "Next", delivery = OpenCodeDelivery.Queue)))
            }
            val store = h.store(state) {
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    getSession = { _, _ -> session },
                    transcript = { _, _, _ -> if (pending == "echoed") listOf(user, next) else listOf(user) },
                )
            }
            store.exhaustive = false
            store.send(Action.ExternalRunStateChanged(isRunning = false))
            assertEquals("pending=$pending", pending != "none", store.state.isAwaitingReply)
            assertEquals("pending=$pending", pending != "none", store.state.isRunning)
            store.receive(Action.RefreshResponse::class)
            assertEquals("pending=$pending", pending == "submitting" || pending == "queued", store.state.isAwaitingReply)
            assertEquals(
                "pending=$pending",
                if (pending == "echoed") listOf(user, next) else if (pending == "none") listOf(user) else listOf(user, text),
                store.state.items,
            )
            store.finish()
        }
    }

    @Test fun failedIdleWaitDoesNotCompleteTheSendOrEraseItsProjection() = sessionTest { h ->
        val idle = Gate()
        val reads = LockIsolated(0)
        val state = h.state(cachedItems = listOf(user)).copy(draft = "Check")
        val store = h.store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, _ -> },
                waitForIdle = { _, _ ->
                    idle.await()
                    throw OpenCodeClientError(message = "Network interrupted")
                },
                getSession = { _, _ -> session },
                transcript = { _, _, _ ->
                    reads.increment()
                    listOf(user)
                },
            )
        }
        store.exhaustive = false
        store.send(Action.SendTapped)
        store.receive(Action.PromptAdmitted::class)
        store.send(Action.ContentBatch(session.id, listOf(OpenCodeLiveContentEvent.TextDelta(messageID = "msg_reply", ordinal = 0, delta = "Unpersisted"))))
        idle.open()
        store.finish()
        store.skipReceivedActions()
        assertTrue(store.state.isAwaitingReply)
        assertEquals("A failed wait is not a terminal read", 0, reads.value)
        store.send(Action.RefreshRequested(RefreshReason.Reconnect))
        store.receive(Action.RefreshResponse::class)
        assertTrue(store.state.items.contains(text.copy(inProgress = true)))
        store.send(Action.ExternalRunStateChanged(isRunning = false))
        store.receive(Action.RefreshResponse::class)
        assertFalse(store.state.isRunning)
        assertFalse(store.state.items.any { it.id == text.id })
        store.finish()
    }

    @Test fun laterPromptEchoSettlesIdleAwaitingReply() {
        for (reason in listOf(RefreshReason.Invalidation, RefreshReason.Settlement(messageID = "msg_next"), RefreshReason.Reconnect, RefreshReason.Watchdog)) sessionTest { h ->
            val next = OpenCodeTranscriptItem.UserText(id = "msg_next", text = "Next")
            val snapshot = LockIsolated<List<OpenCodeTranscriptItem>>(listOf(user, text))
            val state = h.state(cachedItems = listOf(user, text)).let {
                it.copy(
                    isExternallyActive = true,
                    isAwaitingReply = true,
                    projectedMessageIDs = setOf("msg_reply"),
                    queuedInputs = listOf(OpenCodePendingInput(id = "msg_next", text = "Next", delivery = OpenCodeDelivery.Queue)),
                    promptStates = it.promptStates + ("msg_next" to State.PromptState(requestEpoch = 1, admission = State.PromptState.Admission.admitted)),
                )
            }
            val store = h.store(state) {
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    getSession = { _, _ -> session },
                    transcript = { _, _, _ -> snapshot.value },
                )
            }
            store.exhaustive = false
            store.send(Action.ExternalRunStateChanged(isRunning = false))
            store.receive(Action.RefreshResponse::class)
            assertTrue(store.state.isAwaitingReply)
            snapshot.setValue(listOf(user, next))
            store.send(Action.RefreshRequested(reason))
            store.receive(Action.RefreshResponse::class)
            assertTrue("reason=$reason", store.state.queuedInputs.isEmpty())
            assertFalse(store.state.isAwaitingReply)
            assertFalse(store.state.isRunning)
            assertTrue(store.state.projectedMessageIDs.isEmpty())
            assertEquals(listOf(user, next), store.state.items)
            store.finish()
        }
    }

    @Test fun explicitEchoOrCancellationReleasesTheLastIdlePrompt() {
        for (cancel in listOf(false, true)) sessionTest { h ->
            val next = OpenCodeTranscriptItem.UserText(id = "msg_next", text = "Next")
            val state = h.state(cachedItems = listOf(user, text)).let {
                it.copy(
                    isExternallyActive = true,
                    isAwaitingReply = true,
                    projectedMessageIDs = setOf("msg_reply"),
                    queuedInputs = listOf(OpenCodePendingInput(id = "msg_next", text = "Next", delivery = OpenCodeDelivery.Queue)),
                    promptStates = it.promptStates + ("msg_next" to State.PromptState(requestEpoch = 1, admission = State.PromptState.Admission.admitted)),
                )
            }
            val store = h.store(state) {
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    getSession = { _, _ -> session },
                    transcript = { _, _, _ -> listOf(user, text) },
                    cancelPendingInput = { _, _, id -> assertEquals("msg_next", id) },
                    pendingInputs = { _, _ -> emptyList() },
                )
            }
            store.exhaustive = false
            store.send(Action.ExternalRunStateChanged(isRunning = false))
            store.receive(Action.RefreshResponse::class)
            assertTrue(store.state.isAwaitingReply)
            if (cancel) {
                store.send(Action.PendingInputCancelTapped("msg_next"))
                store.receive(Action.PendingInputsResponse::class)
            } else {
                store.send(Action.PromptEchoResponse(session.id, messageID = "msg_next", requestEpoch = 1, items = listOf(next)))
            }
            assertTrue("cancel=$cancel", store.state.queuedInputs.isEmpty())
            assertFalse(store.state.isAwaitingReply)
            assertFalse(store.state.isRunning)
            assertTrue(store.state.projectedMessageIDs.isEmpty())
            store.finish()
        }
    }

    @Test fun duplicateIdleSignalsShareTheInFlightTerminalRead() {
        for (replyCompleted in listOf(false, true)) sessionTest { h ->
            val gate = Gate()
            val reads = LockIsolated(0)
            val state = h.state(cachedItems = listOf(user, text)).copy(
                isExternallyActive = true,
                isAwaitingReply = true,
                projectedMessageIDs = setOf("msg_reply"),
            )
            val store = h.store(state) {
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    getSession = { _, _ -> session },
                    transcript = { _, _, _ ->
                        val count = reads.increment()
                        if (count == 1) gate.await()
                        listOf(user)
                    },
                )
            }
            store.exhaustive = false
            store.send(Action.ExternalRunStateChanged(isRunning = false))
            store.receive(Action.RefreshRequested(RefreshReason.RunState))
            assertFalse(store.state.isRunning)
            store.send(if (replyCompleted) Action.ReplyCompleted(session.id) else Action.ExternalRunStateChanged(isRunning = false))
            store.skipReceivedActions()
            assertNull(store.state.refresh?.followup)
            gate.open()
            store.receive(Action.RefreshResponse::class)
            assertEquals(listOf<OpenCodeTranscriptItem>(user), store.state.items)
            assertTrue(store.state.projectedMessageIDs.isEmpty())
            store.finish()
            store.skipReceivedActions()
            assertEquals(1, reads.value)
            store.send(Action.ReplyCompleted(session.id))
            store.finish()
            store.skipReceivedActions()
            assertEquals("A wait resolving after terminal reconciliation must also be a no-op", 1, reads.value)
        }
    }

    @Test fun contentBeforeTheRunningEventSurvivesTheFirstRead() {
        for (reason in listOf(RefreshReason.Open, RefreshReason.Reconnect)) sessionTest { h ->
            val state = h.state(cachedItems = listOf(user)).copy(refresh = State.Refresh(id = 1, reason = reason), nextRefreshID = 1)
            val store = h.store(state) {
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    getSession = { _, _ -> session },
                    transcript = { _, _, _ -> listOf(user, text) },
                )
            }
            store.exhaustive = false
            store.send(Action.ContentBatch(session.id, listOf(OpenCodeLiveContentEvent.TextDelta(messageID = "msg_reply", ordinal = 0, delta = "Unpersisted"))))
            assertTrue(store.state.isExternallyActive)
            store.send(response(1, listOf(user), AuthoritativeRefresh.Scope.Page))
            assertEquals("reason=$reason", listOf(user, text.copy(inProgress = true)), store.state.items)
            store.send(Action.ExternalRunStateChanged(isRunning = false))
            store.receive(Action.RefreshResponse::class)
            assertEquals(listOf(user, text), store.state.items)
            assertTrue(store.state.projectedMessageIDs.isEmpty())
            store.finish()
        }
    }

    @Test fun racedEventsCannotRegressRESTTool() {
        val events = listOf(
            OpenCodeLiveContentEvent.ToolInputStarted(messageID = "msg_reply", toolID = "call", name = "shell"),
            OpenCodeLiveContentEvent.ToolInputDelta(messageID = "msg_reply", toolID = "call", delta = "{\"command\":"),
            OpenCodeLiveContentEvent.ToolInputEnded(messageID = "msg_reply", toolID = "call", text = "{}"),
            OpenCodeLiveContentEvent.ToolCalled(messageID = "msg_reply", toolID = "call", input = OpenCodeJSONValue.Object(mapOf("stale" to OpenCodeJSONValue.Bool(true)))),
            OpenCodeLiveContentEvent.ToolProgress(messageID = "msg_reply", toolID = "call", metadata = mapOf("known" to OpenCodeJSONValue.Bool(true))),
        )
        for (status in listOf(OpenCodeToolCall.Status.Running, OpenCodeToolCall.Status.Completed, OpenCodeToolCall.Status.Error)) {
            for (event in events) sessionTest { h ->
                val call = OpenCodeToolCall(
                    callID = "call", messageID = "msg_reply", name = "read", status = status,
                    input = OpenCodeJSONValue.Object(mapOf("path" to OpenCodeJSONValue.Str("preview.png"))), output = listOf("Image read successfully"),
                    error = if (status == OpenCodeToolCall.Status.Error) "Failed" else null, metadata = mapOf("known" to OpenCodeJSONValue.Bool(true)),
                    images = listOf(OpenCodeImageAttachment(id = "image", data = byteArrayOf(1, 2, 3))), startedAt = distantPast,
                )
                val tool = OpenCodeTranscriptItem.Tool(id = "msg_reply-tool-call", call = call)
                val state = h.state(cachedItems = listOf(tool)).copy(
                    isExternallyActive = true,
                    refresh = State.Refresh(id = 1, reason = RefreshReason.Settlement(messageID = "msg_reply"), racedEvents = listOf(event)),
                )
                val store = h.store(state)
                store.exhaustive = false
                store.send(response(1, listOf(tool), AuthoritativeRefresh.Scope.Message("msg_reply")))
                assertEquals("$status $event", listOf<OpenCodeTranscriptItem>(tool), store.state.items)
            }
        }
    }

    @Test fun userSendIsIdleBeforeItsFinalTranscriptIsAccepted() = sessionTest { h ->
        val idle = Gate()
        val sentID = LockIsolated("")
        val state = h.state(cachedItems = emptyList()).copy(hasRenderedFirstContentFrame = true, draft = "Check")
        val store = h.store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, id, _ -> sentID.setValue(id) },
                waitForIdle = { _, _ -> idle.await() },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> listOf(OpenCodeTranscriptItem.UserText(id = sentID.value, text = "Check")) },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false
        store.send(Action.SendTapped)
        store.receive(Action.PromptAdmitted::class)
        store.send(Action.ContentBatch(session.id, listOf(OpenCodeLiveContentEvent.TextDelta(messageID = "msg_reply", ordinal = 0, delta = "Unpersisted"))))
        idle.open()
        store.receive(Action.RefreshResponse::class)
        assertFalse(store.state.isRunning)
        assertEquals(listOf(sentID.value), store.state.items.map { it.id })
        assertTrue(store.state.projectedMessageIDs.isEmpty())
        store.finish()
    }

    @Test fun revertedMessageFromAnEarlierRunIsNotProtectedInTheNextRun() = sessionTest { h ->
        val state = h.state(cachedItems = listOf(user, text)).copy(isExternallyActive = true, projectedMessageIDs = setOf("msg_reply"))
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> listOf(user, text) },
            )
        }
        store.exhaustive = false
        store.send(Action.ExternalRunStateChanged(isRunning = false))
        store.receive(Action.RefreshResponse::class)
        assertTrue(store.state.projectedMessageIDs.isEmpty())
        // The old reply was reverted on the server just before another run starts.
        store.client { copy(transcript = { _, _, _ -> listOf(user) }) }
        store.send(Action.ExternalRunStateChanged(isRunning = true))
        store.receive(Action.RefreshResponse::class)
        assertEquals(listOf<OpenCodeTranscriptItem>(user), store.state.items)
    }

    @Test fun settlementWithNoProjectedRowReadsThePage() {
        for (event in listOf<OpenCodeLiveContentEvent>(
            OpenCodeLiveContentEvent.TextStarted(messageID = "msg_reply", ordinal = 0),
            OpenCodeLiveContentEvent.TextDelta(messageID = "msg_reply", ordinal = 0, delta = " \n"),
        )) sessionTest { h ->
            val state = h.state(cachedItems = listOf(user)).copy(isExternallyActive = true)
            val pageReads = LockIsolated(0)
            val messageReads = LockIsolated(0)
            val store = h.store(state) {
                this[Clock] = ImmediateClock()
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    getSession = { _, _ -> session },
                    transcript = { _, _, _ ->
                        pageReads.increment()
                        listOf(user, text)
                    },
                    message = { _, _, _ ->
                        messageReads.increment()
                        listOf(text)
                    },
                )
            }
            store.exhaustive = false
            store.send(Action.ContentBatch(session.id, listOf(event)))
            store.send(Action.RefreshRequested(RefreshReason.Settlement(messageID = "msg_reply")))
            store.receive(Action.RefreshResponse::class)
            assertEquals("$event", 1, pageReads.value)
            assertEquals(0, messageReads.value)
            assertEquals(listOf(user, text), store.state.items)
        }
    }

    @Test fun oldProjectedHistoryStaysBeforeTheWindow() = sessionTest { h ->
        val items = (0 until 200).map { OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_$it-text-0", text = "") }
        val next = OpenCodeTranscriptItem.UserText(id = "msg_next", text = "Next")
        val state = h.state(cachedItems = items).copy(
            isExternallyActive = true,
            projectedMessageIDs = (0 until 200).map { "msg_$it" }.toSet(),
            refresh = State.Refresh(id = 1, reason = RefreshReason.Invalidation),
        )
        val store = h.store(state)
        store.exhaustive = false
        store.send(response(1, listOf(items.last(), next), AuthoritativeRefresh.Scope.Page))
        assertEquals(items.first().id, store.state.items.first().id)
        assertEquals(next.id, store.state.items.last().id)
        assertEquals(201, store.state.items.size)
    }

    @Test fun mergeCostScalesWithHistoryRatherThanPastRunCount() {
        fun duration(count: Int): Double {
            val items = (0 until count).map { OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_$it-text-0", text = "") }
            val projected = (0 until count).map { "msg_$it" }.toSet()
            val fetched = listOf(items.last(), OpenCodeTranscriptItem.UserText(id = "msg_next", text = "Next"))
            return (0 until 3).map {
                val start = System.nanoTime()
                val merged = SessionFeature.mergeTranscript(fetched = fetched, existing = items, projectedMessageIDs = projected)
                assertEquals(count + 1, merged.size)
                (System.nanoTime() - start) / 1e9
            }.sorted()[1]
        }
        val small = duration(300)
        val large = duration(1200)
        println("merge scaling: 300=${small}s 1200=${large}s ratio=${large / max(small, 0.000001)}")
        if (System.getenv("CI") != "true") {
            assertTrue("Four times the history should stay comfortably below quadratic growth", large < max(0.02, small * 9))
        }
    }

    @Test fun retainedTailFollowsItsPredecessorsNewSiblingsBeforeANewSteer() {
        for (hadText in listOf(false, true)) sessionTest { h ->
            val tool = OpenCodeTranscriptItem.Tool(
                id = "msg_reply-tool-call",
                call = OpenCodeToolCall(
                    callID = "call", messageID = "msg_reply", name = "shell", status = OpenCodeToolCall.Status.Running,
                    input = OpenCodeJSONValue.Object(emptyMap()), output = emptyList(), error = null, metadata = emptyMap(),
                ),
            )
            val sibling = OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_reply-text-1", text = "")
            val steer = OpenCodeTranscriptItem.UserText(id = "msg_steer", text = "Also check")
            val state = h.state(cachedItems = if (hadText) listOf(user, text, tool) else listOf(user, tool)).copy(
                isExternallyActive = true,
                projectedMessageIDs = setOf("msg_reply"),
                queuedInputs = listOf(OpenCodePendingInput(id = "msg_steer", text = "Also check")),
                refresh = State.Refresh(id = 1, reason = RefreshReason.Invalidation),
            )
            val store = h.store(state)
            store.exhaustive = false
            store.send(response(1, if (hadText) listOf(user, text, sibling, steer) else listOf(user, text, steer), AuthoritativeRefresh.Scope.Page))
            assertEquals(
                "hadText=$hadText",
                if (hadText) listOf(user, text, sibling, tool, steer) else listOf(user, text, tool, steer),
                store.state.items,
            )
        }
    }

    // region SharedLocationTests (SessionFeature)

    private val spot = SharedLocation(
        latitude = 37.3349, longitude = -122.00902, accuracy = 12.4,
        place = "Apple Park", address = "1 Apple Park Way, Cupertino, CA 95014",
        capturedAt = Instant.ofEpochSecond(1_800_000_000),
    )

    @Test fun locationAttachesThenSendsAtTheTopOfThePrompt() = sessionTest { h ->
        val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
        val session = OpenCodeSession(id = "ses_loc", serverTitle = "Loc", projectID = "p", directory = "/tmp", updatedAt = distantPast)
        val state = h.state(session = session, cachedItems = emptyList(), server = server).copy(draft = "Where am I?")
        val sent = LockIsolated(listOf<String>())
        val spot = spot
        val store = h.store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[LocationClient] = LocationClient(current = { spot })
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, text, _, _, _, _ -> sent.append(text) },
                pendingInputs = { _, _ -> emptyList() },
                waitForIdle = { _, _ -> awaitCancellation() },
            )
        }
        store.exhaustive = false
        store.send(Action.locationTapped) { it.copy(composerAttachments = it.composerAttachments.copy(isLocating = true)) }
        store.receive({ (it as? Action.ComposerAttachments)?.action is ai.opencode.feature.composer.ComposerAttachmentsFeature.Action.LocationResponse }) {
            it.copy(composerAttachments = it.composerAttachments.copy(isLocating = false, pendingLocation = spot))
        }
        assertTrue(store.state.canSendPrompt)
        store.send(Action.SendTapped)
        repeat(200) { if (sent.value.isEmpty()) kotlinx.coroutines.delay(5) }
        h.skipInFlightEffects()
        assertNull(store.state.pendingLocation)
        assertEquals(listOf(spot.prompt("Where am I?")), sent.value)
    }

    @Test fun deniedLocationExplainsItself() = sessionTest { h ->
        val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
        val session = OpenCodeSession(id = "ses_denied", serverTitle = "Denied", projectID = "p", directory = "/tmp", updatedAt = distantPast)
        val store = h.store(h.state(session = session, cachedItems = emptyList(), server = server)) {
            this[LocationClient] = LocationClient(current = { throw LocationClientError.Denied })
        }
        store.exhaustive = false
        store.send(Action.locationTapped)
        store.receive({ (it as? Action.ComposerAttachments)?.action is ai.opencode.feature.composer.ComposerAttachmentsFeature.Action.LocationResponse })
        assertNull(store.state.pendingLocation)
        assertEquals(LocationClientError.Denied.localizedDescription, store.state.locationError)
    }

    @Test fun authoritativeRefreshStartsTranscriptBeforeGetSessionFinishes() = sessionTest { h ->
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val session = OpenCodeSession(id = "ses_parallel", serverTitle = "Parallel", projectID = "p", directory = "/project", updatedAt = distantPast)
        val sessionGate = Gate()
        val transcriptStarted = CompletableDeferred<Unit>()
        val items = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "m1", text = "Hello"))
        val store = h.store(h.state(session = session, cachedItems = emptyList(), server = server)) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ ->
                    sessionGate.await()
                    session
                },
                transcript = { _, _, _ ->
                    transcriptStarted.complete(Unit)
                    items
                },
                contextUsage = { _, _ -> OpenCodeContextReading.Empty },
            )
        }
        store.exhaustive = false
        store.send(Action.RefreshRequested(RefreshReason.Open))
        transcriptStarted.await()
        sessionGate.open()
        store.receive(Action.RefreshResponse::class) { it.copy(refresh = null, initialTranscriptIsFresh = true, items = items) }
        store.receive(Action.AcceptedTranscriptUpdated::class)
        store.finish()
    }

    @Test fun wireDecodedShortSessionMarksEarlierHistoryExhaustedImmediately() = sessionTest { h ->
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val session = OpenCodeSession(id = "ses_short", serverTitle = "Short", projectID = "p", directory = "/project", updatedAt = distantPast)
        val payload = """{"data":[{"id":"m1","type":"user","text":"Hello"}]}""".toByteArray()
        val historyCalls = LockIsolated(0)
        val store = h.store(h.state(session = session, cachedItems = emptyList(), server = server)) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> OpenCodeClient.decodeTranscript(payload) },
                historyPage = { _, _, _ ->
                    historyCalls.increment()
                    OpenCodeTranscriptPage(items = emptyList(), next = null)
                },
            )
        }
        store.exhaustive = false
        store.send(Action.RefreshRequested(RefreshReason.Open))
        store.receive(Action.RefreshResponse::class)
        store.receive(Action.AcceptedTranscriptUpdated::class)
        assertEquals(State.EarlierHistory.Exhausted, store.state.earlierHistory)
        assertFalse(store.state.hasEarlierItems)
        store.send(Action.ShowEarlierTapped)
        assertEquals(0, historyCalls.value)
        store.finish()
    }

    @Test fun wireDecodedLongSessionTransitionsDirectlyToPagingCursor() = sessionTest { h ->
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val session = OpenCodeSession(id = "ses_long_cursor", serverTitle = "Long", projectID = "p", directory = "/project", updatedAt = distantPast)
        val payload = """{"data":[{"id":"m2","type":"user","text":"Newest"},{"id":"m1","type":"user","text":"Older"}],"cursor":{"next":"cur_before_m1"}}""".toByteArray()
        val requestedCursors = LockIsolated(listOf<String?>())
        val oldest = OpenCodeTranscriptItem.UserText(id = "m0", text = "Oldest")
        val store = h.store(h.state(session = session, cachedItems = emptyList(), server = server)) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> OpenCodeClient.decodeTranscript(payload) },
                historyPage = { _, _, cursor ->
                    requestedCursors.append(cursor)
                    OpenCodeTranscriptPage(items = listOf(oldest), next = null)
                },
            )
        }
        store.exhaustive = false
        store.send(Action.RefreshRequested(RefreshReason.Open))
        store.receive(Action.RefreshResponse::class)
        store.receive(Action.AcceptedTranscriptUpdated::class)
        assertEquals(State.EarlierHistory.Paging(cursor = "cur_before_m1"), store.state.earlierHistory)
        assertEquals("m1", store.state.earlierHistoryInsertionID)
        assertTrue(store.state.hasEarlierItems)
        store.send(Action.ShowEarlierTapped)
        store.receive(Action.EarlierHistoryResponse::class)
        store.receive(Action.AcceptedTranscriptUpdated::class)
        assertEquals(listOf<String?>("cur_before_m1"), requestedCursors.value)
        assertEquals(listOf("m0", "m1", "m2"), store.state.items.map { it.id })
        assertEquals(State.EarlierHistory.Exhausted, store.state.earlierHistory)
        store.finish()
    }

    @Test fun inFlightInitialRefreshHoldsEarlierHistoryNetworkRequestBeforeFirstFrame() = sessionTest { h ->
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val session = OpenCodeSession(id = "ses_cached_race", serverTitle = "Cached Race", projectID = "p", directory = "/project", updatedAt = distantPast)
        val cached = (0 until 30).map { OpenCodeTranscriptItem.UserText(id = "m$it", text = "Message $it") }
        val state = h.state(session = session, cachedItems = cached, server = server).copy(refresh = State.Refresh(id = 1, reason = RefreshReason.Open))
        val historyCalls = LockIsolated(0)
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                historyPage = { _, _, _ ->
                    historyCalls.increment()
                    OpenCodeTranscriptPage(items = emptyList(), next = null)
                },
            )
        }
        store.exhaustive = false
        assertTrue(store.state.hasEarlierItems)
        store.send(Action.ShowEarlierTapped)
        assertEquals(60, store.state.visibleLimit)
        assertFalse(store.state.isLoadingEarlier)
        assertEquals(0, historyCalls.value)
        store.finish()
    }

    @Test fun selectedTranscriptCarriesPageCursorThroughActionWithoutStaticSideChannel() = sessionTest { h ->
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val session = OpenCodeSession(id = "ses_selected_cursor", serverTitle = "Selected", projectID = "p", directory = "/project", updatedAt = distantPast)
        // Decode a stale wire payload with identical item IDs and no cursor outside the reducer;
        // because pageCursor travels explicitly on selectedTranscriptResponse/transcriptResponse,
        // that stray decode cannot mark a later cursorless transcriptResponse exhausted.
        val stalePayload = """{"data":[{"id":"m1","type":"user","text":"Hello"}]}""".toByteArray()
        runCatching { OpenCodeClient.decodeTranscript(stalePayload) }

        val items = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "m1", text = "Hello"))
        val store = h.store(h.state(session = session, cachedItems = emptyList(), server = server))
        store.exhaustive = false

        store.send(Action.TranscriptResponse(session.id, Result.success(items)))
        assertEquals(State.EarlierHistory.UnknownEdge, store.state.earlierHistory)

        store.send(Action.TranscriptResponse(session.id, Result.success(items), pageCursor = AuthoritativeRefresh.PageCursor(next = "cur_explicit")))
        assertEquals(State.EarlierHistory.Paging(cursor = "cur_explicit"), store.state.earlierHistory)
        assertEquals("m1", store.state.earlierHistoryInsertionID)
    }

    @Test fun restPageOverlappingOnlyProjectedTailResetsEarlierHistoryToUnknownEdge() = sessionTest { h ->
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val session = OpenCodeSession(id = "ses_sse_gap", serverTitle = "SSE Gap", projectID = "p", directory = "/project", updatedAt = distantPast)
        val m1 = OpenCodeTranscriptItem.UserText(id = "m1", text = "First durable")
        val state = h.state(session = session, cachedItems = listOf(m1), server = server).copy(
            earlierHistory = State.EarlierHistory.Exhausted,
            initialTranscriptIsFresh = true,
            refresh = State.Refresh(id = 1, reason = RefreshReason.Invalidation),
        )
        val store = h.store(state)
        store.exhaustive = false

        store.send(
            Action.ContentBatch(
                session.id,
                listOf(
                    OpenCodeLiveContentEvent.TextDelta(messageID = "m2", ordinal = 0, delta = "Projected middle"),
                    OpenCodeLiveContentEvent.TextDelta(messageID = "m3", ordinal = 0, delta = "Projected tail"),
                ),
            ),
        )
        assertEquals(setOf("m2", "m3"), store.state.projectedMessageIDs)

        val m3Durable = OpenCodeTranscriptItem.AssistantMarkdown(id = "m3-text-0", text = "Projected tail")
        store.send(response(1, listOf(m3Durable), AuthoritativeRefresh.Scope.Page, pageCursor = AuthoritativeRefresh.PageCursor(next = "cur_m3"), session = session))
        assertEquals(State.EarlierHistory.UnknownEdge, store.state.earlierHistory)
        assertNull(store.state.earlierHistoryInsertionID)
        assertEquals(1, store.state.earlierHistoryGeneration)
    }

    /** Frames are the children's tops; widths are the column's. */
    @Test fun composerFoldLayoutMatchesMainWhenNoLocationIsMountedIncludingPreparingDictationRow() {
        // 1. An absent attachment row starts at zero so its first reservation can animate into
        // the layout. Existing attachments keep the row revealed.
        assertEquals(0f, ComposerFoldFrames.attachmentRevealProgress(locationMounted = false, locationPresented = false, hasNonLocationAttachments = false, reduceMotion = false))
        assertEquals(1f, ComposerFoldFrames.attachmentRevealProgress(locationMounted = false, locationPresented = false, hasNonLocationAttachments = true, reduceMotion = false))
        assertEquals(0f, ComposerFoldFrames.attachmentRevealProgress(locationMounted = true, locationPresented = false, hasNonLocationAttachments = false, reduceMotion = false))
        assertEquals(1f, ComposerFoldFrames.attachmentRevealProgress(locationMounted = true, locationPresented = true, hasNonLocationAttachments = false, reduceMotion = false))

        // 2. Composer + bottom "Preparing dictation…" (`composerFolds()`) row without location:
        // unfolded visible range is 0...y (92 total), never clipped at the last frame's bottom (64).
        val composer = 64f
        val preparingRow = 20f
        val unfoldedWithPreparing = ComposerFoldFrames.frames(
            sizes = listOf(composer, preparingRow),
            folds = listOf(false, true),
            firstStayingFoldTop = 22f,
            isFolded = false,
            attachmentRevealProgress = ComposerFoldFrames.attachmentRevealProgress(locationMounted = false, locationPresented = false, hasNonLocationAttachments = false, reduceMotion = false),
            spacing = 8f,
        )
        assertEquals(0f..92f, unfoldedWithPreparing.visible)
        assertEquals(listOf(0f, 72f), unfoldedWithPreparing.frames)

        // When folded for active dictation, it clips to the first staying top + foldTop ... its bottom (22...64).
        val foldedWithPreparing = ComposerFoldFrames.frames(
            sizes = listOf(composer, preparingRow),
            folds = listOf(false, true),
            firstStayingFoldTop = 22f,
            isFolded = true,
            attachmentRevealProgress = 1f,
            spacing = 8f,
        )
        assertEquals(22f..64f, foldedWithPreparing.visible)

        // Mid-fold the visible range passes between the two, so the height animates instead of
        // landing on the folded one.
        val halfFolded = ComposerFoldFrames.frames(
            sizes = listOf(composer, preparingRow),
            folds = listOf(false, true),
            firstStayingFoldTop = 22f,
            isFolded = false,
            foldProgress = 0.5f,
            attachmentRevealProgress = 1f,
            spacing = 8f,
        )
        assertEquals(11f..78f, halfFolded.visible)

        // 3. Even while a location card is unfolding at the top (progress < 1), the upper bound
        // stays at `y` so a bottom `composerFolds` subview is never clipped.
        val midLocationUnfold = ComposerFoldFrames.frames(
            sizes = listOf(136f, composer, preparingRow),
            folds = listOf(true, false, true),
            firstStayingFoldTop = 22f,
            isFolded = false,
            attachmentRevealProgress = 0.5f,
            spacing = 8f,
        )
        // Total y = 136 + 8 + 64 + 8 + 20 = 236; first staying top = 144, lower = 144 * 0.5 = 72.
        assertEquals(72f..236f, midLocationUnfold.visible)
    }

    // endregion
}
