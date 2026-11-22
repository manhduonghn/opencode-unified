package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.store.TestStore
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertEquals
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertFalse
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertNotNull
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertNull
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertTrue
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.expectOptimisticSend
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.failedSendStore
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.hang
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.sentID
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.date
import ai.opencode.feature.session.SessionTestSupport.assertRemembersModelNames
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.testServer
import ai.opencode.feature.session.SessionTestSupport.testSession
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.delay
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SessionFeatureTestsSending {
    private fun user(id: String, text: String) = OpenCodeTranscriptItem.UserText(id = id, text = text)
    private fun assistant(id: String, text: String) = OpenCodeTranscriptItem.AssistantMarkdown(id = id, text = text)

    @Test
    fun sendingWhileRunningQueuesOptimisticallyAndSurvivesStalePolls() = sessionTest { h ->
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "ses_1", serverTitle = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = Instant.EPOCH)
        val localID = "msg_00000000000100000000000000"
        val posts = LockIsolated<List<Pair<String, OpenCodeDelivery>>>(emptyList())
        val state = h.state { State.session(server = server, session = session, cachedItems = listOf(user("m1", "first"))) }
            .copy(isExternallyActive = true)
            .withDraft("second")

        val store = h.store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                // Hold the send open so the poll below is genuinely stale.
                sendPrompt = { _, _, _, _, _, messageID, delivery ->
                    posts.append(messageID to delivery)
                    delay(400.milliseconds)
                },
                waitForIdle = { _, _ -> },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> listOf(user("m1", "first")) },
                message = { _, _, _ -> throw OpenCodeClientError(message = "Not promoted") },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                pendingInputs = { _, _ -> listOf(OpenCodePendingInput(id = localID, text = "second")) },
            )
        }
        store.exhaustive = false

        store.send(Action.SendTapped) {
            it.copy(
                optimisticPromptIDs = setOf(localID),
                // Mid-run sends render as pending from the start, below Working, and
                // a steering one flies off the composer into its dim bubble.
                items = listOf(user("m1", "first")),
                queuedInputs = listOf(OpenCodePendingInput(id = localID, text = "second")),
                lastSentItemID = localID,
                sendGeneration = 1,
            )
        }
        // A poll that predates the server's admission must not blink the row out.
        store.send(Action.PendingInputsResponse(sessionID = "ses_1", inputs = emptyList()))
        assertEquals(listOf("second"), store.state.queuedInputs.map { it.text })
        store.receive(Action.PromptAdmitted::class, timeout = 2.seconds) {
            it.copy(promptStates = it.promptStates + (localID to it.promptStates.getValue(localID).copy(admission = State.PromptState.Admission.admitted)))
        }
        assertFalse(store.state.isSubmittingPrompt)
        // A read that started before admission can complete afterwards. Admission
        // alone does not retire the client-ID row.
        store.send(Action.PendingInputsResponse(sessionID = "ses_1", inputs = emptyList()))
        assertEquals(listOf(localID), store.state.queuedInputs.map { it.id })
        // The server's echo retires the optimistic row.
        store.send(Action.PendingInputsResponse(sessionID = "ses_1", inputs = listOf(OpenCodePendingInput(id = localID, text = "second")))) {
            it.copy(queuedInputs = listOf(OpenCodePendingInput(id = localID, text = "second")))
        }
        store.finish(timeout = 2.seconds)
        assertEquals(listOf(localID to OpenCodeDelivery.Steer), posts.value)
    }

    @Test
    fun queuedSendWithImagesKeepsImagesThroughEchoAndPromotion() = sessionTest { h ->
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "ses_1", serverTitle = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = Instant.EPOCH)
        val data = byteArrayOf(1, 2, 3)
        val state = h.state { State.session(server = server, session = session, cachedItems = emptyList(), isRunning = true) }
            .withDraft("Look")
            .withPendingAttachments(listOf(OpenCodeOutgoingAttachment(data = data, mime = "image/png")))
        val messageID = "msg_00000000000100000000000000"
        val store = h.store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(sendPrompt = { _, _, _, _, _, _, _ -> delay(10.seconds) })
        }
        store.exhaustive = false

        fun firstRowImages() = store.state.queuedInputRows.firstOrNull()?.images?.map { it.data.toList() }

        store.send(Action.SendTapped)
        assertEquals(listOf(messageID), store.state.queuedInputs.map { it.id })
        assertEquals(listOf(data.toList()), firstRowImages())

        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = listOf(OpenCodePendingInput(id = messageID, text = "Look"))))
        assertEquals(listOf(data.toList()), firstRowImages())

        val promoted = listOf(
            OpenCodeTranscriptItem.UserImages(id = "$messageID-images", images = listOf(OpenCodeImageAttachment(id = "server-image", data = data))),
            user(messageID, "Look"),
        )
        store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.success(promoted)))
        assertEquals(promoted, store.state.items)
        assertTrue(store.state.queuedInputs.isEmpty())
        assertTrue(store.state.queuedInputImages.isEmpty())

        // A pre-promotion inbox response landing late cannot resurrect the row.
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = listOf(OpenCodePendingInput(id = messageID, text = "Look"))))
        assertTrue(store.state.queuedInputs.isEmpty())

        store.send(Action.PromptAdmitted(sessionID = session.id, messageID = messageID, requestEpoch = 1))
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = emptyList()))
        assertTrue(store.state.queuedInputs.isEmpty())
        assertTrue(store.state.queuedInputImages.isEmpty())
        h.skipInFlightEffects()
    }

    @Test
    fun sendingMessageDeliversPromptAndLoadsReply() = sessionTest { h ->
        val replied = listOf(user("msg_00000000000100000000000000", "hello"), assistant("m2-0", "hi!"))
        val posts = LockIsolated<List<String>>(emptyList())

        val session = testSession()
        val store = h.store(h.makeState(session = session, draft = "hello")) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, sessionID, text, _, _, messageID, _ -> posts.append("$sessionID $text $messageID") },
                waitForIdle = { _, _ -> },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> replied },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }

        store.send(Action.SendTapped) { expectOptimisticSend(it) }
        assertTrue(store.state.isSubmittingPrompt)
        store.receive(Action.PromptAdmitted(sessionID = "ses_1", messageID = "msg_00000000000100000000000000", requestEpoch = 1)) {
            it.copy(promptStates = it.promptStates + ("msg_00000000000100000000000000" to State.PromptState(requestEpoch = 1, admission = State.PromptState.Admission.admitted)))
        }
        assertFalse(store.state.isSubmittingPrompt)
        assertEquals(listOf("ses_1 hello msg_00000000000100000000000000"), posts.value)
        store.receive(Action.ReplyCompleted(sessionID = "ses_1"))
        store.receive(Action.ExternalRunStateChanged(isRunning = false)) {
            it.copy(
                isAwaitingReply = false,
                runWorkGeneration = 2,
                // Nothing new yet: Working waits for the settlement read.
                workingRun = it.workingRun.copy(
                    counter = 2,
                    finale = WorkingRun.Finale(id = 2, kind = WorkingRun.Finale.Kind.pending, turn = "msg_00000000000100000000000000"),
                ),
            )
        }
        store.receive(Action.RefreshRequested(RefreshReason.RunState)) {
            it.copy(nextRefreshID = 1, refresh = State.Refresh(id = 1, reason = RefreshReason.RunState))
        }
        store.receive(Action.RefreshResponse::class) {
            it.copy(
                refresh = null,
                initialTranscriptIsFresh = true,
                optimisticPromptIDs = emptySet(),
                promptStates = emptyMap(),
                promotedPromptIDs = listOf("msg_00000000000100000000000000"),
                items = replied,
                // The read brought the reply: Done.
                workingRun = it.workingRun.copy(
                    counter = 3,
                    finale = WorkingRun.Finale(id = 3, kind = WorkingRun.Finale.Kind.done, turn = "msg_00000000000100000000000000"),
                ),
            )
        }
        store.receive(Action.AcceptedTranscriptUpdated(sessionID = "ses_1", items = replied))
    }

    @Test
    fun queueIsOneShotAndTheNextSendSteers() = sessionTest { h ->
        val state = h.makeState(session = testSession(id = "ses_root", title = "Root"))
            .copy(isExternallyActive = true)
            .withDraft("Do this afterwards")
        state.returnKeySends.set(true)
        val deliveries = LockIsolated<List<OpenCodeDelivery>>(emptyList())
        val store = h.store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, delivery -> deliveries.append(delivery) },
                transcript = { _, _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
                waitForIdle = { _, _ -> delay(60.seconds) },
                message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
            )
        }
        store.exhaustive = false
        store.send(Action.QueueTapped)
        assertEquals(OpenCodeDelivery.Queue, store.state.queuedInputs.lastOrNull()?.delivery)
        // Queued or steering, a send flies from the composer: into the pill, or its dim bubble.
        assertEquals(store.state.queuedInputs.lastOrNull()?.id, store.state.lastSentItemID)
        assertEquals(1, store.state.sendGeneration)
        store.receive(Action.PromptAdmitted::class)
        store.send(Action.Binding(BindingAction.Draft("Actually, use this approach\n")))
        store.send(Action.ReturnKeyTapped)
        store.receive(Action.SendTapped)
        assertEquals(OpenCodeDelivery.Steer, store.state.queuedInputs.lastOrNull()?.delivery)
        assertEquals(store.state.queuedInputs.lastOrNull()?.id, store.state.lastSentItemID)
        assertEquals(2, store.state.sendGeneration)
        store.receive(Action.PromptAdmitted::class)
        assertEquals(listOf(OpenCodeDelivery.Queue, OpenCodeDelivery.Steer), deliveries.value)
        h.skipInFlightEffects()
    }

    @Test
    fun acceptedPromptWithLostResponseReconcilesSameClientID() = sessionTest { h ->
        val session = testSession()
        val messageID = "msg_00000000000100000000000000"
        val echoed = listOf<OpenCodeTranscriptItem>(user(messageID, "hello"))
        val reads = LockIsolated<List<String>>(emptyList())
        val store = h.store(h.makeState(session = session, draft = "hello")) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, _ -> throw OpenCodeClientError(message = "The response was lost.") },
                message = { _, _, id ->
                    reads.append(id)
                    echoed
                },
                pendingInputs = { _, _ -> emptyList() },
                waitForIdle = { _, _ -> },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> echoed },
            )
        }
        store.exhaustive = false

        store.send(Action.SendTapped)
        store.receive(Action.PromptAdmitted(sessionID = session.id, messageID = messageID, requestEpoch = 1))
        store.receive(Action.ReplyCompleted(sessionID = session.id))
        store.receive(Action.RefreshResponse::class)
        assertTrue(store.state.draft.isEmpty())
        assertTrue(store.state.failedSends.isEmpty())
        assertTrue(reads.value.all { it == messageID })
    }

    @Test
    fun firstSendInDraftCreatesSessionThenDelivers() = sessionTest { h ->
        val project = OpenCodeProject(id = "proj_1", name = "demo", directory = "/tmp/demo")
        val created = testSession(id = "ses_new", title = "Untitled session", directory = "/tmp/demo", updatedAt = date(2_000))
        val replied = listOf(user("msg_00000000000100000000000000", "hello"), assistant("m2-0", "hi!"))
        val calls = LockIsolated<List<String>>(emptyList())

        val state = h.state { State.draft(server = testServer, draftProject = project, projects = listOf(project)) }.withDraft("hello")

        val store = h.store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                createSession = { _, directory, _ ->
                    calls.append("create $directory")
                    created
                },
                getSession = { _, _ -> created },
                pendingInputs = { _, _ -> emptyList() },
                sendPrompt = { _, sessionID, text, _, _, _, _ -> calls.append("prompt $sessionID $text") },
                waitForIdle = { _, _ -> },
                transcript = { _, _, _ -> replied },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
            )
        }
        store.exhaustive = false

        store.send(Action.SendTapped) {
            it.copy(
                optimisticPromptIDs = setOf("msg_00000000000100000000000000"),
                isAwaitingReply = true,
                items = listOf(user("msg_00000000000100000000000000", "hello")),
                lastSentItemID = "msg_00000000000100000000000000",
                sendGeneration = 1,
            ).withDraft("")
        }
        // Android: an unnamed session learns its first prompt (the title fallback, `SessionTitle`).
        store.receive(Action.SessionCreated::class) { it.copy(session = created.copy(firstPrompt = "hello")) }
        store.receive(Action.ReplyCompleted(sessionID = "ses_new"))
        store.receive(Action.ExternalRunStateChanged(isRunning = false)) { it.copy(isAwaitingReply = false) }
        store.receive(Action.RefreshResponse::class) { it.copy(items = replied) }
        assertEquals(listOf("create /tmp/demo", "prompt ses_new hello"), calls.value)
        h.skipInFlightEffects()
    }

    // MARK: Working line

    private fun Harness.workingRunStore(state: State, transcript: List<OpenCodeTranscriptItem> = emptyList()): TestStore<State, Action> {
        val session = state.session ?: testSession()
        val store = store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> transcript },
                message = { _, _, _ -> transcript },
                pendingInputs = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
            )
        }
        store.exhaustive = false
        return store
    }

    /**
     * The first frame after a run's end keeps Working until the finale: the reducer records both in
     * the action that flips the run.
     */
    @Test
    fun aRunsStartAndEndReachTheWorkingLineInTheSameAction() {
        for (fails in listOf(false, true)) sessionTest { h ->
            val session = testSession()
            val user = user("msg_user", "Check")
            val answered = mutableListOf<OpenCodeTranscriptItem>(user, assistant("msg_reply-text-0", "Checked"))
            if (fails) answered += OpenCodeTranscriptItem.Info(id = "msg_reply-error", text = "Run failed: ProviderError — overloaded")
            val store = h.workingRunStore(h.makeState(session = session, cachedItems = listOf(user)), transcript = answered)

            store.send(Action.ExternalRunStateChanged(isRunning = true))
            assertEquals(1, store.state.workingRun.startID, "Starting Up from the run's first frame")
            assertEquals(session.id, store.state.workingRun.key)
            store.send(Action.WorkingRunElapsed(1))
            assertNull(store.state.workingRun.startID)
            store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.success(answered.toList())))

            store.send(Action.ExternalRunStateChanged(isRunning = false))
            assertEquals(
                WorkingRun.Finale(id = 2, kind = if (fails) WorkingRun.Finale.Kind.failed else WorkingRun.Finale.Kind.done, turn = user.id),
                store.state.workingRun.finale,
                "The finale from the run's last frame",
            )
            // The run's own last events mark it running again for a moment: the flash plays on.
            store.send(
                Action.ContentBatch(
                    sessionID = session.id,
                    events = listOf(OpenCodeLiveContentEvent.TextEnded(messageID = "msg_reply", ordinal = 0, text = "Checked")),
                ),
            )
            assertTrue(store.state.isRunning)
            assertEquals(2, store.state.workingRun.finale?.id)
            store.send(Action.ExternalRunStateChanged(isRunning = false))
            assertEquals(2, store.state.workingRun.finale?.id)
            store.send(Action.WorkingRunElapsed(2))
            assertNull(store.state.workingRun.finale, "Played once; a remount doesn't replay it")
        }
    }

    @Test
    fun stoppingARunLeavesNoFinale() = sessionTest { h ->
        val session = testSession()
        val answered = listOf(user("msg_user", "Check"), assistant("msg_reply-text-0", ""))
        val store = h.workingRunStore(h.makeState(session = session, cachedItems = listOf(answered[0])), transcript = answered)
        store.updateDependencies {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                interrupt = { _, _ -> true },
                // The stream reports the end before any read of Stop's own.
                isSessionActive = { _, _ ->
                    delay(60.seconds)
                    true
                },
            )
        }
        store.send(Action.ExternalRunStateChanged(isRunning = true))
        store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.success(answered)))
        store.send(Action.InterruptTapped)
        store.send(Action.ExternalRunStateChanged(isRunning = false))
        assertNull(store.state.workingRun.finale)
    }

    /**
     * A queued message is delivered as the last run ends: the server starts
     * its run before the message is in the transcript, during the flash. That
     * is a new run, not the last one's trailing events.
     */
    @Test
    fun aQueuedMessageDeliveredDuringTheFinaleStartsANewWorkingLine() = sessionTest { h ->
        val session = testSession()
        val user = user("msg_user", "Check")
        val answered = listOf(user, assistant("msg_reply-text-0", ""))
        val store = h.workingRunStore(h.makeState(session = session, cachedItems = listOf(user)), transcript = answered)
        store.send(Action.ExternalRunStateChanged(isRunning = true))
        store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.success(answered)))
        store.send(Action.ExternalRunStateChanged(isRunning = false))
        assertEquals(WorkingRun.Finale.Kind.done, store.state.workingRun.finale?.kind)

        // `execution.started` for the queued message.
        store.send(Action.ExternalRunStateChanged(isRunning = true))
        assertNull(store.state.workingRun.finale, "The new run's Working")
        assertEquals(3, store.state.workingRun.startID)
        store.send(Action.ExternalRunStateChanged(isRunning = false))

        // Or its first step's events arrive first.
        store.send(Action.ContentBatch(sessionID = session.id, events = listOf(OpenCodeLiveContentEvent.StepStarted(messageID = "msg_next"))))
        assertTrue(store.state.isRunning)
        assertNull(store.state.workingRun.finale)
        assertNotNull(store.state.workingRun.startID)
    }

    /**
     * The run's own trailing events kept the flash, then its message landed:
     * it was a new run all along, so its Working starts then.
     */
    @Test
    fun aNewMessageDuringTheFlashStartsItsRun() = sessionTest { h ->
        val session = testSession()
        val user = user("msg_user", "Check")
        val answered = listOf(user, assistant("msg_reply-text-0", ""))
        val store = h.workingRunStore(h.makeState(session = session, cachedItems = listOf(user)), transcript = answered)
        store.send(Action.ExternalRunStateChanged(isRunning = true))
        store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.success(answered)))
        store.send(Action.ExternalRunStateChanged(isRunning = false))
        store.send(
            Action.ContentBatch(sessionID = session.id, events = listOf(OpenCodeLiveContentEvent.TextEnded(messageID = "msg_reply", ordinal = 0, text = ""))),
        )
        assertEquals(WorkingRun.Finale.Kind.done, store.state.workingRun.finale?.kind)
        store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.success(answered + user("msg_next", "Next"))))
        assertNull(store.state.workingRun.finale)
        assertNotNull(store.state.workingRun.startID)
    }

    /**
     * A provider error before any text arrives only with the settlement read
     * after the run ends: the line waits for it, then flashes Failed.
     */
    @Test
    fun aRunThatFailsBeforeStreamingFlashesFailedAfterTheRefresh() = sessionTest { h ->
        val session = testSession()
        val user = user("msg_user", "Check")
        val store = h.workingRunStore(h.makeState(session = session, cachedItems = listOf(user)), transcript = listOf(user))
        store.send(Action.ExternalRunStateChanged(isRunning = true))
        store.send(Action.ExternalRunStateChanged(isRunning = false))
        val pending = store.state.workingRun.finale
        assertEquals(WorkingRun.Finale.Kind.pending, pending?.kind, "Working holds for the settlement read")
        store.send(
            Action.TranscriptResponse(
                sessionID = session.id,
                result = Result.success(listOf(user, OpenCodeTranscriptItem.Info(id = "msg_reply-error", text = "Run failed: ProviderError — overloaded"))),
            ),
        )
        assertEquals(WorkingRun.Finale.Kind.failed, store.state.workingRun.finale?.kind)
        assertTrue(store.state.workingRun.finale?.id != pending?.id, "The flash is timed from the read")

        // Nothing came: the wait ends on its own, with no flash.
        val quiet = h.workingRunStore(h.makeState(session = session, cachedItems = listOf(user)), transcript = listOf(user))
        quiet.send(Action.ExternalRunStateChanged(isRunning = true))
        quiet.send(Action.ExternalRunStateChanged(isRunning = false))
        val waiting = quiet.state.workingRun.finale
        assertEquals(WorkingRun.Finale.Kind.pending, waiting?.kind)
        quiet.send(Action.WorkingRunElapsed(waiting?.id ?: 0))
        assertNull(quiet.state.workingRun.finale)
    }

    /**
     * The first send creates the session, which remounts the transcript: the
     * run keeps the start the line saw and the draft's orb.
     */
    @Test
    fun aDraftsFirstRunKeepsItsStartAndOrbWhenItsSessionArrives() = sessionTest { h ->
        val project = OpenCodeProject(id = "proj_1", name = "demo", directory = "/tmp/demo")
        val created = testSession(id = "ses_new", title = "Untitled session", directory = "/tmp/demo")
        val state = h.state { State.draft(server = testServer, draftProject = project, projects = listOf(project)) }.withDraft("hello")
        val store = h.workingRunStore(state)
        store.updateDependencies {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                createSession = { _, _, _ -> created },
                getSession = { _, _ -> created },
                sendPrompt = { _, _, _, _, _, _, _ -> },
                waitForIdle = { _, _ -> hang() },
            )
        }

        store.send(Action.SendTapped)
        assertEquals(1, store.state.workingRun.startID)
        val orb = store.state.workingOrbKey
        store.receive(Action.SessionCreated::class)
        assertEquals(created.id, store.state.session?.id)
        assertEquals(1, store.state.workingRun.startID, "The start survives the session arriving")
        assertEquals(orb, store.state.workingOrbKey, "The orb keeps its cache key")
        store.skipReceivedActions()
        h.skipInFlightEffects()
    }

    /**
     * A send that fails, or is cancelled before the server admits it, ran no
     * turn: no green Done, and no wait for a settlement read.
     */
    @Test
    fun aFailedOrCancelledSendLeavesNoFinale() = sessionTest { h ->
        val session = testSession()
        val earlier = listOf(user("msg_0", "Earlier"), assistant("msg_0r-text-0", ""))
        val failing = h.failedSendStore(h.makeState(session = session, cachedItems = earlier, draft = "hello"), attempts = LockIsolated(emptyList())) {
            throw OpenCodeClientError(message = "The server returned HTTP 500.", statusCode = 500)
        }
        failing.exhaustive = false
        failing.send(Action.SendTapped)
        assertEquals(1, failing.state.workingRun.startID)
        failing.receive(Action.PromptFailed::class)
        assertFalse(failing.state.isRunning)
        assertNull(failing.state.workingRun.finale)

        // Cancelled: its bubble leaves, and the last turn is the earlier, answered one.
        val sent = earlier + user("msg_1", "hello")
        var run = WorkingRun()
        val idle = WorkingRun.Signal(sessionID = session.id, isRunning = false, isInterrupting = false, items = earlier)
        val running = WorkingRun.Signal(sessionID = session.id, isRunning = true, isInterrupting = false, items = sent)
        run = run.update(idle, running, items = sent, failedSends = emptySet())
        run = run.update(running, idle, items = earlier, failedSends = emptySet())
        assertNull(run.finale)
    }

    /**
     * Like the V2 TUI and desktop app, picking a model (or its reasoning
     * level) only changes the composer; the next send applies it, just
     * before its prompt, and later sends have nothing to apply.
     */
    @Test
    fun pickingAModelWaitsForTheNextSend() = sessionTest { h ->
        val session = testSession(directory = "/tmp/demo", model = OpenCodeModelRef(id = "ling-3.0-tiny-free", providerID = "opencode"))
        val fast = OpenCodeModel(modelID = "fast-frank", providerID = "opencode", name = "Fast Frank", variants = listOf("high"))
        val high = fast.ref(variant = "high")
        val calls = LockIsolated<List<String>>(emptyList())

        val store = h.store(h.makeState(session = session, draft = "first")) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                modelCatalog = { _, directory ->
                    calls.append("catalog $directory")
                    OpenCodeModelCatalog(models = listOf(fast), defaultModel = null)
                },
                switchModel = { _, sessionID, ref -> calls.append("model $sessionID ${ref.id} ${ref.variant ?: "-"}") },
                sendPrompt = { _, sessionID, text, _, _, _, _ -> calls.append("prompt $sessionID $text") },
                pendingInputs = { _, _ -> emptyList() },
                waitForIdle = { _, _ -> hang() },
                message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
            )
        }

        // The persisted model remains identifiable while its friendly name loads.
        assertEquals("Ling 3.0 Tiny Free", store.state.currentModelName)

        store.send(Action.ModelButtonTapped) { it.copy(activePicker = State.ActivePicker.model, isLoadingModels = true) }
        store.receive(Action.ModelsResponse::class) { it.copy(isLoadingModels = false, models = listOf(fast)) }
        store.state.assertRemembersModelNames(listOf(fast))
        assertEquals(listOf("catalog /tmp/demo"), calls.value)
        calls.setValue(emptyList())
        store.send(Action.ModelPicked(fast)) { it.copy(currentModel = fast.ref, pendingModel = fast.ref, activePicker = null) }
        store.send(Action.VariantPicked("high")) { it.copy(currentModel = high, pendingModel = high) }
        // The composer shows the pick; the server hasn't heard of it.
        assertEquals("Fast Frank · High", store.state.currentModelLabel)
        assertTrue(calls.value.isEmpty())

        store.exhaustive = false
        store.send(Action.SendTapped)
        assertEquals(high, store.state.pendingModel, "It waits until the server admits the send")
        store.receive(Action.ModelApplied::class)
        assertNull(store.state.pendingModel)
        assertEquals(high, store.state.session?.model)
        store.receive(Action.PromptAdmitted::class)
        assertEquals(listOf("model ses_1 fast-frank high", "prompt ses_1 first"), calls.value)

        store.send(Action.Binding(BindingAction.Draft("second")))
        store.send(Action.SendTapped)
        store.receive(Action.PromptAdmitted::class)
        assertEquals(listOf("prompt ses_1 second"), calls.value.takeLast(1), "Nothing new to apply")
        assertEquals(high, store.state.currentModel)
        h.skipInFlightEffects()
    }

    /**
     * A pick waits until a send the server admits carries it: a failed send
     * leaves it waiting, and Retry takes the composer's model as it is then.
     */
    @Test
    fun aPickWaitsUntilASendCarryingItIsAdmitted() = sessionTest { h ->
        val fast = OpenCodeModel(modelID = "fast-frank", providerID = "opencode", name = "Fast Frank")
        val slow = OpenCodeModel(modelID = "slow-sam", providerID = "opencode", name = "Slow Sam")
        val switched = LockIsolated<List<String>>(emptyList())
        val attempts = LockIsolated<List<Pair<String, OpenCodeDelivery>>>(emptyList())
        val store = h.failedSendStore(h.makeState(session = testSession(), draft = "hello"), attempts = attempts) { count ->
            if (count == 1) throw OpenCodeClientError(message = "The server returned HTTP 400.", statusCode = 400)
        }
        store.updateDependencies {
            this[OpenCodeClient] = this[OpenCodeClient].copy(switchModel = { _, _, ref -> switched.append(ref.id) })
        }
        store.exhaustive = false

        store.send(Action.ModelPicked(fast))
        store.send(Action.SendTapped)
        store.receive(Action.PromptFailed::class)
        assertEquals(fast.ref, store.state.pendingModel, "Still waiting")

        // Picked again before Retry: the retry carries the new pick.
        store.send(Action.ModelPicked(slow))
        store.send(Action.FailedSendRetryTapped(sentID))
        store.receive(Action.ModelApplied::class)
        store.receive(Action.PromptAdmitted::class)
        assertEquals(listOf("fast-frank", "slow-sam"), switched.value)
        assertNull(store.state.pendingModel)
        assertEquals(slow.ref, store.state.currentModel)
        h.skipInFlightEffects()
    }
}
