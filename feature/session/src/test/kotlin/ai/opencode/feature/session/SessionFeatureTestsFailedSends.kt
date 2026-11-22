package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.store.TestStore
import ai.opencode.feature.composer.RequestDockFeature
import ai.opencode.feature.composer.PendingPrompt
import ai.opencode.feature.composer.UndeliveredPrompts
import ai.opencode.feature.session.FailedSendsSupport.expectOptimisticSend
import ai.opencode.feature.session.FailedSendsSupport.sentID
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.testServer
import ai.opencode.feature.session.SessionTestSupport.testSession
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.TranscriptRowIdentityPlan
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.ServerIDClient
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.retry.TransportFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parameterized tests loop over their arguments. */
class SessionFeatureTestsFailedSends {
    /**
     * A send store whose POSTs follow `attempt` (by its 1-based number) and
     * whose message reads find the prompt only once `serverHasIt` is set.
     */
    private fun Harness.failedSendStore(
        state: State,
        kept: UndeliveredPrompts = UndeliveredPrompts(),
        attempts: LockIsolated<List<Pair<String, OpenCodeDelivery>>>,
        serverHasIt: LockIsolated<Boolean> = LockIsolated(false),
        moveSession: suspend (sessionID: String, directory: String) -> Unit = { _, _ -> },
        attempt: (Int) -> Unit,
    ): TestStore<State, Action> = store(state) {
        this[UuidClient] = UuidClient.incrementing()
        this[ServerIDClient] = ServerIDClient.incrementing()
        this[Clock] = ImmediateClock()
        this[UndeliveredPrompts] = kept
        this[OpenCodeClient] = this[OpenCodeClient].copy(
            sendPrompt = { _, _, _, _, _, messageID, delivery ->
                val count = attempts.withValue { (it + (messageID to delivery)).let { next -> next to next.size } }
                attempt(count)
            },
            message = { _, _, messageID ->
                if (!serverHasIt.value) throw OpenCodeClientError("Missing", statusCode = 404)
                listOf(OpenCodeTranscriptItem.UserText(id = messageID, text = "hello"))
            },
            pendingInputs = { _, _ -> emptyList() },
            getSession = { _, _ -> awaitCancellation() },
            transcript = { _, _, _ -> awaitCancellation() },
            runningShells = { _, _ -> emptyList() },
            familyPendingRequests = { _, _, _ -> throw OpenCodeClientError("Offline") },
            modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
            moveSession = { _, sessionID, directory -> moveSession(sessionID, directory) },
            // The reply's wait isn't part of these tests.
            waitForIdle = { _, _ -> awaitCancellation() },
        )
    }

    private val http400 = OpenCodeClientError("The server returned HTTP 400.", statusCode = 400)

    private fun UndeliveredPrompts.ids(sessionID: String) = entries(serverKey = testServer.persistenceID, sessionID = sessionID).map { it.pending.messageID }

    @Test fun aFailedSendKeepsItsBubbleAndTheComposerStaysEmpty() = sessionTest { h ->
        val session = testSession()
        val kept = UndeliveredPrompts()
        val attempts = LockIsolated(listOf<Pair<String, OpenCodeDelivery>>())
        val store = h.failedSendStore(h.makeState(session = session, draft = "hello"), kept = kept, attempts = attempts) { throw http400 }

        store.send(Action.SendTapped) { it.expectOptimisticSend() }
        store.receive(Action.PromptFailed::class) {
            it.copy(
                promptStates = emptyMap(),
                isAwaitingReply = false,
                failedSends = listOf(
                    FailedSend(
                        pending = PendingPrompt(messageID = sentID, text = "hello", attachments = emptyList(), requestEpoch = 1),
                        reason = "The server returned HTTP 400.",
                    ),
                ),
            )
        }
        // Home's cache hears the confirmed transcript, without the failed message.
        store.receive(Action.AcceptedTranscriptUpdated(sessionID = session.id, items = emptyList()))
        // The words stay in their bubble, not back in the composer.
        assertEquals(listOf(OpenCodeTranscriptItem.UserText(id = sentID, text = "hello")), store.state.items)
        assertTrue(store.state.draft.isEmpty())
        assertEquals(listOf(sentID), kept.ids(session.id))

        // It reached the server after all: the transcript's copy settles it.
        store.exhaustive = false
        store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.success(listOf(OpenCodeTranscriptItem.UserText(id = sentID, text = "hello")))))
        assertTrue(store.state.failedSends.isEmpty())
        assertEquals(listOf(sentID), store.state.items.map { it.id })
        assertTrue(kept.ids(session.id).isEmpty())
    }

    @Test fun retrySendsTheSameMessageAgain() = sessionTest { h ->
        val session = testSession()
        val attempts = LockIsolated(listOf<Pair<String, OpenCodeDelivery>>())
        val store = h.failedSendStore(h.makeState(session = session, draft = "hello"), attempts = attempts) { count ->
            if (count == 1) throw http400
        }
        store.exhaustive = false

        store.send(Action.SendTapped)
        store.receive(Action.PromptFailed::class)
        store.send(Action.FailedSendRetryTapped(sentID)) {
            it.copy(
                failedSends = emptyList(),
                isAwaitingReply = true,
                promptStates = it.promptStates + (sentID to State.PromptState(requestEpoch = 2)),
            )
        }
        store.receive(Action.PromptAdmitted(sessionID = session.id, messageID = sentID, requestEpoch = 2))
        assertEquals("Retry reuses the message ID", listOf(sentID, sentID), attempts.value.map { it.first })
        assertEquals("The bubble stays in place", listOf(OpenCodeTranscriptItem.UserText(id = sentID, text = "hello")), store.state.items)
        assertEquals(sentID, store.state.lastSentItemID)
        assertEquals("A retry doesn't fly from the composer", 1, store.state.sendGeneration)
        h.skipInFlightEffects()
    }

    @Test fun retryAfterATimeoutTheServerReceivedDoesNotSendTwice() = sessionTest { h ->
        val session = testSession()
        val attempts = LockIsolated(listOf<Pair<String, OpenCodeDelivery>>())
        val serverHasIt = LockIsolated(false)
        val store = h.failedSendStore(h.makeState(session = session, draft = "hello"), attempts = attempts, serverHasIt = serverHasIt) {
            throw OpenCodeClientError.unreachable(TransportFailure.Interrupted)
        }
        store.exhaustive = false

        store.send(Action.SendTapped)
        // Every lookup missed it, so it shows as failed…
        store.receive(Action.PromptFailed::class)
        assertEquals(listOf(sentID), store.state.failedSends.map { it.id })
        // It was tried again on its own, for its whole window, before failing for good.
        val tried = attempts.value.size
        assertTrue("tried $tried", tried > 5)
        // …but the server admitted it late. Retry finds it instead of posting.
        serverHasIt.setValue(true)
        store.send(Action.FailedSendRetryTapped(sentID))
        store.receive(Action.PromptAdmitted(sessionID = session.id, messageID = sentID, requestEpoch = 2))
        assertEquals("No further POST", tried, attempts.value.size)
        assertTrue(store.state.failedSends.isEmpty())
        assertEquals(listOf(OpenCodeTranscriptItem.UserText(id = sentID, text = "hello")), store.state.items)
        h.skipInFlightEffects()
    }

    private val folderGone = OpenCodeClientError(OpenCodeClientError.FOLDER_GONE_MESSAGE, statusCode = 404)

    /** A session in a worktree that was removed, whose project lives on. */
    private fun Harness.folderGoneState(): State =
        makeState(session = testSession(directory = "/work/linked"), draft = "hello")
            .copy(projects = listOf(OpenCodeProject(id = "p", name = "repo", directory = "/work/repo")))

    private suspend fun expectFolderGoneFailure(store: TestStore<State, Action>, sessionID: String) {
        store.send(Action.SendTapped) { it.expectOptimisticSend() }
        store.receive(Action.PromptFailed::class) {
            it.copy(
                promptStates = emptyMap(),
                isAwaitingReply = false,
                failedSends = listOf(
                    FailedSend(
                        pending = PendingPrompt(messageID = sentID, text = "hello", attachments = emptyList(), requestEpoch = 1),
                        reason = OpenCodeClientError.FOLDER_GONE_MESSAGE,
                    ),
                ),
            )
        }
        store.receive(Action.AcceptedTranscriptUpdated(sessionID = sessionID, items = emptyList()))
    }

    private fun State.movedTo(directory: String): State = copy(
        session = session?.copy(directory = directory),
        familySessions = familySessions.mapIndexed { index, it -> if (index == 0) it.copy(directory = directory) else it },
    )

    @Test fun aSessionWhoseFolderIsGoneMovesToItsProjectAndSendsAgain() = sessionTest { h ->
        val attempts = LockIsolated(listOf<Pair<String, OpenCodeDelivery>>())
        val moves = LockIsolated(listOf<String>())
        val store = h.failedSendStore(
            h.folderGoneState(),
            attempts = attempts,
            moveSession = { sessionID, directory -> moves.append("$sessionID → $directory") },
        ) { count ->
            if (count == 1) throw folderGone
        }

        expectFolderGoneFailure(store, sessionID = "ses_1")
        assertEquals(true, store.state.failedSends.firstOrNull()?.isFolderGone)
        assertEquals("/work/repo", store.state.missingFolderMoveTarget?.directory)

        store.send(Action.FailedSendMoveTapped(sentID)) { it.copy(movingFailedSendID = sentID) }
        // The send goes again under its own ID once the session has moved.
        store.exhaustive = false
        store.receive(Action.MissingFolderMoved(sessionID = "ses_1", messageID = sentID, directory = "/work/repo")) {
            // Requests, models, and shells are read again in the new folder.
            it.movedTo("/work/repo")
                .withRequestLoadAttempt(RequestDockFeature.State.LoadAttempt.Initial)
                .copy(
                    movingFailedSendID = null,
                    pendingWorkPollSeconds = 2,
                    isLoadingModels = true,
                    failedSends = emptyList(),
                    isAwaitingReply = true,
                    promptStates = it.promptStates + (sentID to State.PromptState(requestEpoch = 2)),
                )
        }
        store.receive(Action.PromptAdmitted(sessionID = "ses_1", messageID = sentID, requestEpoch = 2))
        assertEquals(listOf("ses_1 → /work/repo"), moves.value)
        assertEquals(listOf(sentID, sentID), attempts.value.map { it.first })
        assertNull("Already in its project's folder", store.state.missingFolderMoveTarget)
        h.skipInFlightEffects()
    }

    @Test fun aSessionWithNowhereToMoveOffersOnlyRetry() = sessionTest { h ->
        // Its project's folder is the one that's gone.
        val state = h.folderGoneState().copy(projects = listOf(OpenCodeProject(id = "p", name = "linked", directory = "/work/linked")))
        val store = h.failedSendStore(state, attempts = LockIsolated(emptyList())) { throw folderGone }

        expectFolderGoneFailure(store, sessionID = "ses_1")
        assertNull(store.state.missingFolderMoveTarget)
        store.send(Action.FailedSendMoveTapped(sentID))
    }

    /** A move on its way holds Move to and Retry, and a failed move keeps offering Move to once settled. */
    @Test fun aMoveOnItsWayHoldsAnotherMoveAndRetry() = sessionTest { h ->
        val attempts = LockIsolated(listOf<Pair<String, OpenCodeDelivery>>())
        val moves = LockIsolated(0)
        val gate = CompletableDeferred<Unit>()
        val store = h.failedSendStore(
            h.folderGoneState(),
            attempts = attempts,
            moveSession = { _, _ ->
                val count = moves.withValue { (it + 1).let { next -> next to next } }
                if (count == 1) gate.await()
                throw OpenCodeClientError("Directory does not exist: /work/repo", statusCode = 400)
            },
        ) { throw folderGone }

        expectFolderGoneFailure(store, sessionID = "ses_1")
        store.send(Action.FailedSendMoveTapped(sentID)) { it.copy(movingFailedSendID = sentID) }
        store.send(Action.FailedSendMoveTapped(sentID))
        store.send(Action.FailedSendRetryTapped(sentID))
        gate.complete(Unit)
        store.receive(Action.MissingFolderMoveFailed::class) {
            it.copy(
                movingFailedSendID = null,
                failedSends = it.failedSends.map { failed -> if (failed.id == sentID) failed.copy(reason = "Couldn’t move session") else failed },
            )
        }
        assertEquals(1, moves.value)
        assertEquals("Retry waited for the move", 1, attempts.value.size)
        assertEquals(true, store.state.failedSends.firstOrNull { it.id == sentID }?.isFolderGone)
        assertEquals("/work/linked", store.state.session?.directory)

        // Settled, it can move again.
        store.send(Action.FailedSendMoveTapped(sentID)) { it.copy(movingFailedSendID = sentID) }
        store.receive(Action.MissingFolderMoveFailed::class) { it.copy(movingFailedSendID = null) }
        assertEquals(2, moves.value)
    }

    /** Deleted while its move is on its way: the session still moves, and nothing is sent. */
    @Test fun deletingTheMovingSendLetsTheMoveFinishWithoutSending() = sessionTest { h ->
        val attempts = LockIsolated(listOf<Pair<String, OpenCodeDelivery>>())
        val gate = CompletableDeferred<Unit>()
        val store = h.failedSendStore(h.folderGoneState(), attempts = attempts, moveSession = { _, _ -> gate.await() }) {
            throw folderGone
        }

        expectFolderGoneFailure(store, sessionID = "ses_1")
        store.send(Action.FailedSendMoveTapped(sentID)) { it.copy(movingFailedSendID = sentID) }
        store.send(Action.FailedSendDeleteTapped(sentID)) {
            it.copy(movingFailedSendID = null, failedSends = emptyList(), items = emptyList(), optimisticPromptIDs = emptySet())
        }
        store.receive(Action.AcceptedTranscriptUpdated(sessionID = "ses_1", items = emptyList()))
        store.exhaustive = false
        gate.complete(Unit)
        store.receive(Action.MissingFolderMoved(sessionID = "ses_1", messageID = sentID, directory = "/work/repo")) {
            it.movedTo("/work/repo")
                .withRequestLoadAttempt(RequestDockFeature.State.LoadAttempt.Initial)
                .copy(pendingWorkPollSeconds = 2, isLoadingModels = true)
        }
        assertEquals("Nothing sent", 1, attempts.value.size)
        h.skipInFlightEffects()
    }

    /**
     * Moved by the server (or another screen's Move to): what was read in the old folder is read
     * again in the new one, which clears a failed request read.
     */
    @Test fun aMovedSessionRereadsRequestsModelsAndShellsInItsNewFolder() = sessionTest { h ->
        val reads = LockIsolated(listOf<String>())
        val requestsGate = CompletableDeferred<Unit>()
        val modelsGate = CompletableDeferred<Unit>()
        val shellsGate = CompletableDeferred<Unit>()
        val state = h.makeState(session = testSession(directory = "/work/linked"))
            .copy(streamIsHealthy = true)
            .withRequestLoadError("Couldn’t load requests")
            .withRequestFailureAwaitingReconnect(true)
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                familyPendingRequests = { _, _, directory ->
                    reads.append("requests $directory")
                    requestsGate.await()
                    OpenCodePendingRequests(permissions = emptyList())
                },
                pendingInputs = { _, _ -> emptyList() },
                modelCatalog = { _, directory ->
                    reads.append("models ${directory ?: "none"}")
                    modelsGate.await()
                    OpenCodeModelCatalog(models = emptyList(), defaultModel = null)
                },
                runningShells = { _, directory ->
                    reads.append("shells $directory")
                    shellsGate.await()
                    emptyList()
                },
            )
        }

        store.send(Action.SessionMoved(sessionID = "ses_1", directory = "/work/repo")) {
            it.movedTo("/work/repo")
                .withRequestLoadAttempt(RequestDockFeature.State.LoadAttempt.Initial)
                .withRequestLoadError(null)
                .copy(pendingWorkPollSeconds = 60, isLoadingModels = true)
        }
        store.receive(Action.PendingInputsRequested(sessionID = "ses_1"))
        store.receive(Action.PendingInputsResponse(sessionID = "ses_1", inputs = emptyList()))
        requestsGate.complete(Unit)
        store.receive(Action.PendingRequestsResponse(sessionID = "ses_1", result = Result.success(OpenCodePendingRequests(permissions = emptyList())))) {
            it.withRequestLoadAttempt(null).withRequestFailureAwaitingReconnect(false).withRequestArrivalsAreLive(true)
        }
        modelsGate.complete(Unit)
        store.receive(Action.ModelsResponse(Result.success(OpenCodeModelCatalog(models = emptyList(), defaultModel = null)))) {
            it.copy(isLoadingModels = false)
        }
        shellsGate.complete(Unit)
        store.receive(Action.ShellsResponse(sessionID = "ses_1", result = Result.success(emptyList())))
        assertEquals(listOf("models /work/repo", "requests /work/repo", "shells /work/repo"), reads.value.sorted())

        // Already there: the server's event after this phone's move changes nothing.
        store.send(Action.SessionMoved(sessionID = "ses_1", directory = "/work/repo"))
        h.skipInFlightEffects()
    }

    /**
     * A read on its way when the session moved, or a message read carrying the copy it started
     * with, never takes the session back to the old folder.
     */
    @Test fun aReadThatPredatesTheMoveKeepsTheNewFolder() = sessionTest { h ->
        val state = h.makeState(session = testSession(directory = "/work/repo"))
            .copy(refresh = State.Refresh(id = 1, reason = RefreshReason.Invalidation, directoryChanged = true))
        val store = h.store(state)
        store.exhaustive = false

        store.send(
            Action.RefreshResponse(
                sessionID = "ses_1",
                requestID = 1,
                result = Result.success(
                    SessionFeature.AuthoritativeRefresh(
                        observed = testSession(directory = "/work/linked"),
                        items = emptyList(),
                        scope = SessionFeature.AuthoritativeRefresh.Scope.Page,
                        observedIsRead = true,
                    ),
                ),
            ),
        )
        assertEquals("/work/repo", store.state.session?.directory)
        assertEquals(listOf("/work/repo"), store.state.familySessions.map { it.directory })
        h.skipInFlightEffects()
    }

    @Test fun editPutsTheWordsAndAttachmentsBackInTheComposer() = sessionTest { h ->
        val session = testSession()
        val kept = UndeliveredPrompts()
        val image = OpenCodeOutgoingAttachment(data = byteArrayOf(0x89.toByte(), 0x50), mime = "image/png")
        val state = h.makeState(session = session, draft = "hello").withPendingAttachments(listOf(image))
        val store = h.failedSendStore(state, kept = kept, attempts = LockIsolated(emptyList())) { throw http400 }
        store.exhaustive = false

        store.send(Action.SendTapped)
        store.receive(Action.PromptFailed::class)
        assertEquals("The image stays with its message", listOf("$sentID-images", sentID), store.state.items.map { it.id })
        assertTrue(store.state.pendingAttachments.isEmpty())

        store.send(Action.Binding(BindingAction.Draft("and then")))
        store.send(Action.FailedSendEditTapped(sentID)) {
            it.copy(failedSends = emptyList(), items = emptyList(), optimisticPromptIDs = emptySet())
                .withDraft("hello\n\nand then")
                .withPendingAttachments(listOf(image))
        }
        assertTrue(kept.ids(session.id).isEmpty())
    }

    @Test fun deleteRemovesTheFailedMessage() = sessionTest { h ->
        val session = testSession()
        val kept = UndeliveredPrompts()
        val store = h.failedSendStore(h.makeState(session = session, draft = "hello"), kept = kept, attempts = LockIsolated(emptyList())) { throw http400 }
        store.exhaustive = false

        store.send(Action.SendTapped)
        store.receive(Action.PromptFailed::class)
        store.send(Action.FailedSendDeleteTapped(sentID)) {
            it.copy(failedSends = emptyList(), items = emptyList(), optimisticPromptIDs = emptySet())
        }
        assertTrue(store.state.draft.isEmpty())
        assertTrue(kept.ids(session.id).isEmpty())
    }

    @Test fun retryKeepsTheDeliveryMode() {
        for (delivery in listOf(OpenCodeDelivery.Steer, OpenCodeDelivery.Queue)) retryKeepsTheDeliveryMode(delivery)
    }

    private fun retryKeepsTheDeliveryMode(delivery: OpenCodeDelivery) = sessionTest { h ->
        val attempts = LockIsolated(listOf<Pair<String, OpenCodeDelivery>>())
        val store = h.failedSendStore(h.makeState(session = testSession(), isRunning = true, draft = "Do this next"), attempts = attempts) { count ->
            if (count == 1) throw http400
        }
        store.exhaustive = false

        store.send(if (delivery == OpenCodeDelivery.Queue) Action.QueueTapped else Action.SendTapped)
        assertEquals("$delivery", listOf(delivery), store.state.queuedInputs.map { it.delivery })
        store.receive(Action.PromptFailed::class)
        // The pending row becomes a failed bubble in the transcript.
        assertTrue(store.state.queuedInputs.isEmpty())
        assertEquals(listOf(OpenCodeTranscriptItem.UserText(id = sentID, text = "Do this next")), store.state.items)
        assertEquals(delivery, store.state.failedSends.withID(sentID)?.pending?.delivery)

        // Still mid-run, it goes back to a pending row with its mode.
        store.send(Action.FailedSendRetryTapped(sentID))
        assertTrue(store.state.items.isEmpty())
        assertEquals(listOf(delivery), store.state.queuedInputs.map { it.delivery })
        store.receive(Action.PromptAdmitted::class)
        assertEquals(listOf(delivery, delivery), attempts.value.map { it.second })
        assertEquals(listOf(sentID, sentID), attempts.value.map { it.first })
        h.skipInFlightEffects()
    }

    @Test fun theCacheNeverHoldsASendStillInFlight() = sessionTest { h ->
        val session = testSession()
        val earlier = OpenCodeTranscriptItem.UserText(id = "msg_earlier", text = "Earlier")
        val gate = CompletableDeferred<Unit>()
        val store = h.store(h.makeState(session = session, cachedItems = listOf(earlier), draft = "hello")) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = ImmediateClock()
            this[UndeliveredPrompts] = UndeliveredPrompts()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, _ ->
                    gate.await()
                    throw http400
                },
                message = { _, _, _ -> throw OpenCodeClientError("Missing", statusCode = 404) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.SendTapped)
        assertEquals(listOf(earlier.id, sentID), store.state.items.map { it.id })
        // A read lands while the POST is still out: the cache gets only what the server confirmed.
        store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.success(listOf(earlier))))
        store.receive(Action.AcceptedTranscriptUpdated(sessionID = session.id, items = listOf(earlier)))
        gate.complete(Unit)
        store.receive(Action.PromptFailed::class)
        store.receive(Action.AcceptedTranscriptUpdated(sessionID = session.id, items = listOf(earlier)))
        assertEquals(listOf(earlier.id, sentID), store.state.items.map { it.id })
    }

    @Test fun restoringAFailedSendTheServerHasResolvesIt() = sessionTest { h ->
        val session = testSession()
        val kept = UndeliveredPrompts()
        kept.keep(
            PendingPrompt(messageID = sentID, text = "hello", attachments = emptyList(), requestEpoch = 1),
            reason = "The server returned HTTP 400.", serverKey = testServer.persistenceID, sessionID = session.id,
        )
        // The transcript, from the server, already has it when the screen starts.
        val store = h.failedSendStore(
            h.makeState(session = session, cachedItems = listOf(OpenCodeTranscriptItem.UserText(id = sentID, text = "hello"))),
            kept = kept, attempts = LockIsolated(emptyList()),
        ) {}
        store.exhaustive = false
        store.send(Action.Task)
        assertTrue(store.state.failedSends.isEmpty())
        assertEquals(listOf(OpenCodeTranscriptItem.UserText(id = sentID, text = "hello")), store.state.items)
        assertTrue(kept.ids(session.id).isEmpty())
        h.skipInFlightEffects()
    }

    @Test fun aFailedSendTheInboxHasKeepsItsBubbleAndImages() = sessionTest { h ->
        val session = testSession()
        val kept = UndeliveredPrompts()
        val state = h.makeState(session = session, draft = "hello")
            .withPendingAttachments(listOf(OpenCodeOutgoingAttachment(data = byteArrayOf(0x89.toByte(), 0x50), mime = "image/png")))
        val store = h.failedSendStore(state, kept = kept, attempts = LockIsolated(emptyList())) {
            throw OpenCodeClientError("Couldn’t reach the OpenCode server.")
        }
        store.exhaustive = false
        store.send(Action.SendTapped)
        store.receive(Action.PromptFailed::class)
        val rows = store.state.items

        // The server had it after all and is holding it for the next step.
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = listOf(OpenCodePendingInput(id = sentID, text = "hello", delivery = OpenCodeDelivery.Steer))))
        assertTrue(store.state.failedSends.isEmpty())
        assertEquals("Its bubble, image included, stays until the echo", rows, store.state.items)
        assertTrue("Never a second, pending copy", store.state.queuedInputs.isEmpty())
        assertTrue(kept.ids(session.id).isEmpty())
    }

    @Test fun aRetryTheInboxListsAsARunStartsIsShownOnce() = sessionTest { h ->
        val session = testSession()
        val attempts = LockIsolated(listOf<Pair<String, OpenCodeDelivery>>())
        val store = h.failedSendStore(h.makeState(session = session, draft = "hello"), attempts = attempts) { count ->
            if (count == 1) throw http400
        }
        store.exhaustive = false
        store.send(Action.SendTapped)
        store.receive(Action.PromptFailed::class)
        store.send(Action.FailedSendRetryTapped(sentID))
        store.receive(Action.PromptAdmitted::class)
        // Another client started a run, so the server holds the retry in its inbox.
        store.send(Action.ExternalRunStateChanged(isRunning = true))
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = listOf(OpenCodePendingInput(id = sentID, text = "hello", delivery = OpenCodeDelivery.Steer))))
        assertEquals(1, store.state.items.count { TranscriptEntry.promptID(it) == sentID })
        assertTrue(store.state.queuedInputs.isEmpty())
        h.skipInFlightEffects()
    }

    /**
     * The server admits an idle send to its inbox before its run's first step
     * delivers it. The bubble is already in the transcript, so an inbox read
     * landing first (or late, after the echo) never adds a steering row too.
     */
    @Test fun anIdleSendTheInboxListsIsShownOnce() {
        for (listedAfterEcho in listOf(false, true)) anIdleSendTheInboxListsIsShownOnce(listedAfterEcho)
    }

    private fun anIdleSendTheInboxListsIsShownOnce(listedAfterEcho: Boolean) = sessionTest { h ->
        val session = testSession()
        val store = h.failedSendStore(h.makeState(session = session, draft = "hello"), attempts = LockIsolated(emptyList())) {}
        store.exhaustive = false
        store.send(Action.SendTapped)
        store.receive(Action.PromptAdmitted::class)
        store.send(Action.ExternalRunStateChanged(isRunning = true))
        val listing = listOf(OpenCodePendingInput(id = sentID, text = "hello", delivery = OpenCodeDelivery.Steer))
        if (listedAfterEcho) {
            store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.success(listOf(OpenCodeTranscriptItem.UserText(id = sentID, text = "hello")))))
        }
        store.send(Action.PendingInputsResponse(sessionID = session.id, inputs = listing))
        assertTrue("listedAfterEcho=$listedAfterEcho", store.state.queuedInputs.isEmpty())
        assertEquals(1, store.state.items.count { TranscriptEntry.promptID(it) == sentID })
        val entries = TranscriptEntry.build(store.state.visibleItems, isRunning = true)
        val plan = TranscriptRowIdentityPlan.make(
            previousEntries = entries, previousQueued = emptyList(),
            previousEntryStableIDs = entries.associate { it.id to it.id }, previousQueuedStableIDs = emptyMap(),
            entries = entries, queued = store.state.queuedInputRows,
        )
        assertTrue(plan.supersededQueuedIDs.isEmpty())
        h.skipInFlightEffects()
    }

    @Test fun aFailedSendComesBackWhenTheSessionReopens() = sessionTest { h ->
        val session = testSession()
        val kept = UndeliveredPrompts()
        val first = h.failedSendStore(h.makeState(session = session, draft = "hello"), kept = kept, attempts = LockIsolated(emptyList())) { throw http400 }
        first.exhaustive = false
        first.send(Action.SendTapped)
        first.receive(Action.PromptFailed::class)

        // The reader left and opened the session again: a new screen.
        val reopened = h.failedSendStore(
            h.makeState(session = session, cachedItems = listOf(OpenCodeTranscriptItem.UserText(id = "msg_earlier", text = "Earlier"))),
            kept = kept, attempts = LockIsolated(emptyList()),
        ) {}
        reopened.exhaustive = false
        reopened.send(Action.Task)
        assertEquals(listOf(sentID), reopened.state.failedSends.map { it.id })
        assertEquals(listOf("msg_earlier", sentID), reopened.state.items.map { it.id })
        // Caches never hold the failed bubble as if it were sent.
        assertEquals(listOf("msg_earlier"), reopened.state.cacheableItems.map { it.id })
        reopened.send(Action.Task)
        assertEquals("Restoring twice adds nothing", listOf("msg_earlier", sentID), reopened.state.items.map { it.id })
        h.skipInFlightEffects()
    }

    @Test fun interruptingAnActiveSessionWaitsForServerState() = sessionTest { h ->
        val session = testSession()
        val store = h.store(h.makeState(session = session, isRunning = true)) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                interrupt = { _, sessionID ->
                    assertEquals("ses_1", sessionID)
                    true
                },
                isSessionActive = { _, _ -> true },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
            )
            this[Clock] = TestClock()
        }
        store.exhaustive = false

        store.send(Action.InterruptTapped) { it.copy(isInterrupting = true) }
        store.receive(Action.InterruptAcknowledged(sessionID = "ses_1", generation = 1, interrupted = true))
        store.send(Action.InterruptTapped)
        assertTrue(store.state.isExternallyActive)
        assertTrue(store.state.isInterrupting)
        store.send(Action.ExternalRunStateChanged(isRunning = false)) { it.copy(isExternallyActive = false, isInterrupting = false) }
        store.receive(Action.RefreshRequested(RefreshReason.RunState))
        store.receive(Action.RefreshResponse::class)
        store.skipReceivedActions()
    }

    /**
     * Stop's request goes again on a dropped connection on the shared request
     * policy (five attempts), reading the session before each retry, then
     * gives up: Stop comes back with “Couldn’t stop session”.
     */
    @Test fun interruptFailureKeepsSessionActiveAndShowsFeedback() = sessionTest { h ->
        val attempts = LockIsolated(0)
        val store = h.store(h.makeState(isRunning = true)) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                interrupt = { _, _ ->
                    attempts.increment()
                    throw OpenCodeClientError.unreachable(TransportFailure.Interrupted)
                },
                isSessionActive = { _, _ -> true },
            )
        }

        store.send(Action.InterruptTapped) {
            it.copy(isInterrupting = true, runWorkGeneration = 1, stopAttempt = State.StopAttempt(steering = emptySet()))
        }
        store.receive(Action.InterruptFailed(sessionID = "ses_1", generation = 1)) {
            it.copy(
                isInterrupting = false,
                stopAttempt = null,
                items = listOf(OpenCodeTranscriptItem.Info(id = "info-00000000-0000-0000-0000-000000000000", text = "Couldn’t stop session")),
            )
        }
        assertEquals("The first try and four more", 5, attempts.value)
        assertTrue(store.state.isExternallyActive)
        assertTrue("Stop is back for another try", store.state.isRunning)
    }
}
