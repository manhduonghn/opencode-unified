package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.feature.composer.DraftState
import ai.opencode.feature.composer.PendingPrompt
import ai.opencode.feature.composer.RequestDockFeature
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.date
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.settle
import ai.opencode.feature.session.SessionTestSupport.testServer
import ai.opencode.feature.session.SessionTestSupport.testSession
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodePermissionReply
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SessionFeatureTestsSubagents {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun user(id: String, text: String) = OpenCodeTranscriptItem.UserText(id = id, text = text)

    /** Lets running effects reach their next clock sleep. */
    private suspend fun settleEffects() {
        settle(20)
        delay(30)
    }

    private suspend fun eventually(condition: () -> Boolean): Boolean {
        repeat(100) {
            if (condition()) return true
            delay(10)
        }
        return condition()
    }

    /**
     * A queued message stays parked after Stop: once the server says nothing
     * runs, it no longer holds Working up.
     */
    @Test fun aParkedQueuedMessageNeverHoldsWorking() = sessionTest { h ->
        val clock = TestClock()
        val session = testSession()
        val queued = OpenCodePendingInput(id = "msg_queued", text = "Then deploy", delivery = OpenCodeDelivery.Queue)
        val state = h.makeState(session = session, isRunning = true).copy(
            isAwaitingReply = true,
            queuedInputs = listOf(queued),
            promptStates = mapOf("msg_queued" to State.PromptState(requestEpoch = 1, admission = State.PromptState.Admission.admitted)),
        )
        val store = h.store(state) {
            this[Clock] = clock
            this[SessionTimers] = SessionTimers.testValue.copy(runEndCheck = true)
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                isSessionActive = { _, _ -> false },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
                pendingInputs = { _, _ -> listOf(queued) },
            )
        }
        store.exhaustive = false

        store.send(Action.ExternalRunStateChanged(isRunning = false))
        assertTrue("The next run may still take it", store.state.isRunning)
        settleEffects()
        clock.advance(1500.milliseconds)
        store.receive(Action.RunStateRead(sessionID = session.id, generation = 1, isActive = false)) { it.copy(isAwaitingReply = false) }
        assertFalse("Not stuck on Working", store.state.isRunning)
        store.skipReceivedActions()
        assertTrue("Still parked in the pill", eventually { store.state.queuedInputs.map { it.id } == listOf("msg_queued") })
        h.skipInFlightEffects()
    }

    /** The next run starts before the read: nothing changes. */
    @Test fun aRunEndCheckIsCancelledByTheNextRun() = sessionTest { h ->
        val clock = TestClock()
        val reads = LockIsolated(0)
        val session = testSession()
        val state = h.makeState(session = session, isRunning = true).copy(
            isAwaitingReply = true,
            queuedInputs = listOf(OpenCodePendingInput(id = "msg_steer", text = "Use staging", delivery = OpenCodeDelivery.Steer)),
            promptStates = mapOf("msg_steer" to State.PromptState(requestEpoch = 1, admission = State.PromptState.Admission.admitted)),
        )
        val store = h.store(state) {
            this[Clock] = clock
            this[SessionTimers] = SessionTimers.testValue.copy(runEndCheck = true)
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                isSessionActive = { _, _ -> reads.increment(); false },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.ExternalRunStateChanged(isRunning = false))
        settleEffects()
        store.send(Action.ExternalRunStateChanged(isRunning = true))
        settleEffects()
        clock.advance(3.seconds)
        settleEffects()
        assertEquals(0, reads.value)
        assertTrue(store.state.isRunning)
        h.skipInFlightEffects()
    }

    /**
     * A steering message left behind by a run that ended (an older server,
     * a provider failure) offers Send Now: it goes by way of the queue, as the
     * server refuses steering a steering message, and wakes the session.
     */
    @Test fun sendNowOnAStrandedSteerWakesTheSession() = sessionTest { h ->
        val changes = LockIsolated(listOf<OpenCodeDelivery>())
        val session = testSession()
        val state = h.makeState(session = session).copy(queuedInputs = listOf(OpenCodePendingInput(id = "msg_steer", text = "Use staging", delivery = OpenCodeDelivery.Steer)))
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                setPendingInputDelivery = { _, sessionID, id, delivery ->
                    assertTrue(sessionID == session.id && id == "msg_steer")
                    changes.append(delivery)
                },
                pendingInputs = { _, _ -> emptyList() },
                isSessionActive = { _, _ -> true },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        assertFalse(store.state.isRunning)
        store.send(Action.PendingInputSendNowTapped("msg_steer")) { it.copy(isAwaitingReply = true) }
        assertTrue("Working at once", store.state.isRunning)
        store.receive(Action.RunStateRead(sessionID = session.id, generation = 1, isActive = true))
        assertEquals(listOf(OpenCodeDelivery.Queue, OpenCodeDelivery.Steer), changes.value)
        h.skipInFlightEffects()
    }

    @Test fun sendNowOnAStrandedSteerThatFailsSaysSo() = sessionTest { h ->
        val session = testSession()
        val state = h.makeState(session = session).copy(queuedInputs = listOf(OpenCodePendingInput(id = "msg_steer", text = "Use staging", delivery = OpenCodeDelivery.Steer)))
        val store = h.store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                setPendingInputDelivery = { _, _, _, _ -> throw OpenCodeClientError("Offline") },
                pendingInputs = { _, _ -> listOf(OpenCodePendingInput(id = "msg_steer", text = "Use staging", delivery = OpenCodeDelivery.Steer)) },
            )
        }
        store.exhaustive = false

        store.send(Action.PendingInputSendNowTapped("msg_steer"))
        store.receive(Action.StrandedSteerSendFailed::class) { it.copy(isAwaitingReply = false) }
        assertEquals(
            OpenCodeTranscriptItem.Info(id = "info-00000000-0000-0000-0000-000000000000", text = "Couldn’t send the pending message now: Offline"),
            store.state.items.lastOrNull(),
        )
        assertFalse(store.state.isRunning)
        h.skipInFlightEffects()
    }

    @Test fun subagentPermissionRepliesToOwner() = sessionTest { h ->
        val permission = json.decodeFromString(
            OpenCodePermissionRequest.serializer(),
            """
            {"id":"per_1","sessionID":"ses_child","action":"shell","resources":["git status"],
             "source":{"type":"tool","messageID":"msg_1","id":"call_1"}}
            """.trimIndent(),
        )
        val root = testSession(id = "ses_root", title = "Root", directory = "/tmp/demo")
        val child = testSession(id = "ses_child", title = "Child", directory = "/tmp/demo", parentID = root.id)
        val state = h.makeState(session = root, hasRenderedFirstContentFrame = false).copy(familySessions = listOf(root, child))
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                replyPermission = { _, sessionID, requestID, reply, message ->
                    assertEquals("ses_child", sessionID)
                    assertEquals("per_1", requestID)
                    assertEquals(OpenCodePermissionReply.Once, reply)
                    assertNull(message)
                },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
            )
        }

        store.send(Action.PendingRequestsResponse(sessionID = root.id, result = Result.success(OpenCodePendingRequests(permissions = listOf(permission), forms = emptyList())))) {
            // The subagent's request docks; nothing opens on its own.
            it.withPermissionRequests(listOf(permission))
                // The composer becomes the answer, its message parked.
                .withParkedComposer(DraftState())
                .withAnswerShownTarget(permission.id)
                .withRequestArrivalsAreLive(true)
        }
        assertEquals(permission.id, store.state.dockRequestID)
        store.send(Action.permissionReplyTapped(permission.id, OpenCodePermissionReply.Once)) { it.withRespondingRequestIDs(setOf(permission.id)) }
        store.receive(Action.requestResponseFinished(sessionID = "ses_child", requestID = permission.id, error = null)) {
            it.withRespondingRequestIDs(emptySet())
                .withCompletedRequestIDs(setOf(permission.id))
                .withPermissionRequests(emptyList())
                .withParkedComposer(null)
                .withAnswerShownTarget(null)
        }
        store.receive(Action.PendingRequestsResponse(sessionID = root.id, result = Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()))))
    }

    @Test fun formsInitializeDefaultsAndSubmitActiveAnswers() = sessionTest { h ->
        val form = json.decodeFromString(
            OpenCodeFormRequest.serializer(),
            """
            {"id":"frm_1","sessionID":"ses_1","title":"Deploy","fields":[
              {"key":"confirm","type":"boolean","title":"Confirm","required":true,"default":true},
              {"key":"note","type":"string","title":"Note","required":true,"when":[{"key":"confirm","op":"eq","value":true}]}
            ]}
            """.trimIndent(),
        )
        val session = testSession(directory = "/tmp/demo")
        val store = h.store(h.makeState(session = session, hasRenderedFirstContentFrame = false)) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                replyForm = { _, sessionID, formID, answer ->
                    assertEquals("ses_1", sessionID)
                    assertEquals("frm_1", formID)
                    assertEquals(mapOf("confirm" to OpenCodeFormValue.Boolean(true), "note" to OpenCodeFormValue.Str("Ship it")), answer)
                },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
            )
        }

        store.send(Action.PendingRequestsResponse(sessionID = "ses_1", result = Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = listOf(form))))) {
            it.withFormRequests(listOf(form))
                .withFormAnswers(it.formAnswers + (form.id to mapOf("confirm" to OpenCodeFormValue.Boolean(true))))
                .withParkedComposer(DraftState())
                .withAnswerShownTarget(form.id)
                .withRequestArrivalsAreLive(true)
        }
        store.send(Action.formSubmitTapped(form.id)) {
            it.withRequestErrors(it.requestErrors + (form.id to "Required"))
                .withQuestionPageIndices(it.questionPageIndices + (form.id to 1))
        }
        store.send(Action.formValueChanged(formID = form.id, key = "note", value = OpenCodeFormValue.Str("Ship it"))) {
            it.withFormAnswers(it.formAnswers + (form.id to (it.formAnswers[form.id].orEmpty() + ("note" to OpenCodeFormValue.Str("Ship it")))))
                .withRequestErrors(it.requestErrors - form.id)
        }
        store.send(Action.formSubmitTapped(form.id)) { it.withRespondingRequestIDs(setOf(form.id)) }
        store.receive(Action.requestResponseFinished(sessionID = "ses_1", requestID = "frm_1", error = null)) {
            it.withRespondingRequestIDs(emptySet())
                .withFormRequests(emptyList())
                .withFormAnswers(emptyMap())
                .withCompletedRequestIDs(setOf(form.id))
                .withQuestionPageIndices(emptyMap())
                .withParkedComposer(null)
                .withAnswerShownTarget(null)
        }
        store.receive(Action.PendingRequestsResponse(sessionID = "ses_1", result = Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()))))
    }

    @Test fun childOwnedRequestResponseIsAppliedBeforeFamilyDiscoveryFinishes() = sessionTest { h ->
        val form = json.decodeFromString(
            OpenCodeFormRequest.serializer(),
            """
            {"id":"frm_child","sessionID":"child","title":"Question","fields":[
              {"key":"answer","type":"string","required":true}
            ]}
            """.trimIndent(),
        )
        val root = testSession(id = "root", title = "Root")
        val state = h.makeState(session = root, hasRenderedFirstContentFrame = false)
            .withFormRequests(listOf(form))
            .let { it.withFormAnswers(it.formAnswers + (form.id to mapOf("answer" to OpenCodeFormValue.Str("Ready")))) }
            .withSelectedRequestID(form.id)
            .copy(activePicker = State.ActivePicker.requests)
        val reply = CompletableDeferred<Unit>()
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                replyForm = { _, _, _, _ ->
                    reply.await()
                    throw OpenCodeClientError("HTTP 503")
                },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
            )
        }
        store.exhaustive = false

        store.send(Action.formSubmitTapped(form.id)) { it.withRespondingRequestIDs(setOf(form.id)) }
        store.send(Action.PendingRequestsResponse(sessionID = root.id, result = Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()))))
        assertEquals(listOf(form), store.state.formRequests)
        assertEquals(mapOf("answer" to OpenCodeFormValue.Str("Ready")), store.state.formAnswers[form.id])
        reply.complete(Unit)
        store.receive(Action.requestResponseFinished(sessionID = "child", requestID = form.id, error = OpenCodeClientError("HTTP 503"))) {
            it.withRespondingRequestIDs(emptySet())
                .withRequestErrors(it.requestErrors + (form.id to "HTTP 503"))
                .updateRequestDock { dock -> dock.copy(requestFailures = mapOf(form.id to RequestDockFeature.State.RequestFailure.Reply)) }
        }
        store.send(Action.PendingRequestsResponse(sessionID = root.id, result = Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()))))
        assertEquals(listOf(form), store.state.formRequests)
        assertEquals(mapOf("answer" to OpenCodeFormValue.Str("Ready")), store.state.formAnswers[form.id])
        assertEquals(State.ActivePicker.requests, store.state.activePicker)
    }

    @Test fun sessionFamilyTracksSubagentsAndSwitchesCachedTranscripts() = sessionTest { h ->
        val root = testSession(id = "ses_root", title = "Root", directory = "/tmp/demo")
        val child = testSession(id = "ses_child", title = "Inspect API", directory = "/tmp/demo", parentID = root.id, agent = "explore")
        val state = h.makeState(session = root, cachedItems = listOf(user("root", "Root"))).copy(
            familyTranscriptCache = mapOf(child.id to listOf(user("child", "Child"))),
            queuedInputs = listOf(OpenCodePendingInput(id = "msg_queued", text = "Look")),
            queuedInputImages = mapOf("msg_queued" to listOf(OpenCodeImageAttachment(id = "img", data = byteArrayOf(1)))),
        ).withDraft("Unsent root work")
        val store = h.store(state) {
            this[DateClient] = DateClient.constant(date(100))
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, id -> if (id == child.id) child else root },
                transcript = { _, _, _ -> emptyList() },
                runningShells = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                // Inbox reads never land, so only the switch itself can clear queued rows.
                pendingInputs = { _, _ -> throw OpenCodeClientError("offline") },
            )
        }
        store.exhaustive = false

        store.send(Action.FamilyResponse(Result.success(OpenCodeSessionFamily(sessions = listOf(root, child), activeSessionIDs = setOf(child.id))))) {
            it.copy(familySessions = listOf(root, child), activeFamilySessionIDs = setOf(child.id))
        }
        store.send(Action.FamilyResponse(Result.success(OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = setOf(child.id)))))
        assertEquals(listOf(root, child), store.state.familySessions)
        store.send(Action.FamilyPicked(child.id))
        assertEquals(child, store.state.session)
        assertEquals(listOf<OpenCodeTranscriptItem>(user("child", "Child")), store.state.items)
        assertTrue(store.state.isExternallyActive)
        assertTrue(store.state.draft.isEmpty())
        assertTrue(store.state.queuedInputs.isEmpty())
        assertTrue(store.state.queuedInputImages.isEmpty())
        store.send(Action.TranscriptResponse(sessionID = root.id, result = Result.success(listOf(user("late", "Late root")))))
        assertEquals(listOf<OpenCodeTranscriptItem>(user("child", "Child")), store.state.items)
        store.send(
            Action.PromptFailed(
                sessionID = root.id,
                error = OpenCodeClientError("Late failure"),
                pending = PendingPrompt(messageID = "msg_root", text = "Root prompt", attachments = emptyList(), requestEpoch = 1),
            ),
        )
        assertTrue(store.state.draft.isEmpty())
        store.send(Action.FamilyPicked(root.id))
        assertEquals(listOf<OpenCodeTranscriptItem>(user("root", "Root")), store.state.items)
        assertEquals("Unsent root work", store.state.draft)
        h.skipInFlightEffects()
    }

    @Test fun familySwitchNeverEvictsTheIncomingTranscript() {
        val root = testSession(id = "ses_root", title = "Root")
        val children = (0 until 8).map { testSession(id = "ses_child$it", title = "Child $it", parentID = root.id) }
        // Caching the outgoing root overfills the cache, so every switch evicts
        // one child. Whichever child that is, it must not be the one opened.
        for (target in children) sessionTest { h ->
            val state = h.makeState(session = root, cachedItems = listOf(user("root", "Root"))).copy(
                familySessions = listOf(root) + children,
                familyTranscriptCache = children.associate { it.id to listOf<OpenCodeTranscriptItem>(user(it.id, it.title)) },
            )
            val store = h.store(state) {
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    getSession = { _, id -> (listOf(root) + children).firstOrNull { it.id == id } ?: root },
                    transcript = { _, _, _ -> emptyList() },
                    runningShells = { _, _ -> emptyList() },
                    familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                    pendingInputs = { _, _ -> emptyList() },
                )
            }
            store.exhaustive = false

            store.send(Action.FamilyPicked(target.id))
            assertEquals(target.id, listOf<OpenCodeTranscriptItem>(user(target.id, target.title)), store.state.items)
            h.skipInFlightEffects()
        }
    }

    @Test fun draftWithoutProjectOpensPickerWithoutLosingText() = sessionTest { h ->
        val state = h.state { State.draft(server = testServer, draftProject = null, projects = emptyList()) }.withDraft("Keep this")
        val store = h.store(state)

        store.send(Action.SendTapped) {
            it.copy(activePicker = State.ActivePicker.project, folderBrowser = FolderBrowserFeature.State(server = testServer))
        }
        assertEquals("Keep this", store.state.draft)
        assertTrue(store.state.items.isEmpty())
    }

    @Test fun lateInboxCannotDuplicateAnAuthoritativeUserBubble() = sessionTest { h ->
        val state = h.makeState(
            session = testSession(directory = "/tmp/demo", updatedAt = Instant.ofEpochSecond(1_000)),
            isRunning = true,
            hasRenderedFirstContentFrame = false,
        ).copy(items = listOf(user("msg_9", "steer me")))

        val store = h.store(state)
        store.exhaustive = false

        // The transcript already contains the authoritative prompt. A late inbox
        // snapshot cannot turn it back into queued work below newer replies.
        store.send(Action.PendingInputsResponse(sessionID = "ses_1", inputs = listOf(OpenCodePendingInput(id = "msg_9", text = "steer me"))))
        assertEquals(listOf<OpenCodeTranscriptItem>(user("msg_9", "steer me")), store.state.visibleItems)
        assertTrue(store.state.queuedInputs.isEmpty())

        // A later empty poll leaves the same single delivered bubble.
        store.send(Action.PendingInputsResponse(sessionID = "ses_1", inputs = emptyList()))
        assertEquals(listOf<OpenCodeTranscriptItem>(user("msg_9", "steer me")), store.state.visibleItems)
        assertTrue(store.state.queuedInputs.isEmpty())
    }

    @Test fun steeringShowsQueuedStateAsSoonAsServerAdmitsIt() = sessionTest { h ->
        val session = testSession(directory = "/tmp/demo")
        val state = h.makeState(session = session, isRunning = true, draft = "Steer")
        val messageID = FailedSendsSupport.sentID
        val store = h.store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, _ -> },
                waitForIdle = { _, _ -> },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                pendingInputs = { _, _ -> listOf(OpenCodePendingInput(id = messageID, text = "Steer")) },
            )
        }
        store.exhaustive = false

        store.send(Action.SendTapped)
        store.receive(Action.PendingInputsResponse(sessionID = "ses_1", inputs = listOf(OpenCodePendingInput(id = messageID, text = "Steer")))) {
            it.copy(queuedInputs = listOf(OpenCodePendingInput(id = messageID, text = "Steer")))
        }
        assertTrue(store.state.visibleItems.isEmpty())
    }
}
