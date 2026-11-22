package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.store.PresentationAction
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeatureTestSupport.Harness
import ai.opencode.feature.drawer.HomeFeatureTestSupport.LockIsolated
import ai.opencode.feature.drawer.HomeFeatureTestSupport.client
import ai.opencode.feature.drawer.HomeFeatureTestSupport.date
import ai.opencode.feature.drawer.HomeFeatureTestSupport.detail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.distantFuture
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.HomeFeatureTestSupport.increment
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receive
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receiveBounded
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receiveDetail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.snapshot
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.activeSubagents
import ai.opencode.feature.session.answerDrafts
import ai.opencode.feature.session.backgroundShells
import ai.opencode.feature.session.completedRequestIDs
import ai.opencode.feature.session.contextFraction
import ai.opencode.feature.session.dockRequestID
import ai.opencode.feature.session.formAnswers
import ai.opencode.feature.session.formRequests
import ai.opencode.feature.session.from
import ai.opencode.feature.session.hasBackgroundWork
import ai.opencode.feature.session.permissionRequests
import ai.opencode.feature.session.questionPageIndices
import ai.opencode.feature.session.selectedRequestID
import ai.opencode.feature.session.withAnswerDrafts
import ai.opencode.feature.session.withCompletedRequestIDs
import ai.opencode.feature.session.withFormAnswers
import ai.opencode.feature.session.withFormRequests
import ai.opencode.feature.session.withPermissionRequests
import ai.opencode.feature.session.withQuestionPageIndices
import ai.opencode.feature.session.withSelectedRequestID
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.model.OpenCodeContextUsage
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeFormField
import ai.opencode.server.model.OpenCodeFormFieldType
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.OpenCodeShell
import ai.opencode.server.model.OpenCodeTranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ai.opencode.core.testing.TestIsolationRule

class SessionSwitchTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "https://switch.example", username = "", password = "")

    private fun session(id: String, parent: String? = null) =
        OpenCodeSession(id = id, serverTitle = id, projectID = "project", directory = "/project", updatedAt = date(10), parentID = parent)

    private fun Harness.homeState(listed: Boolean = true): HomeFeature.State = state {
        val root = session("root")
        val other = session("other")
        val child = session("child", parent = "root")
        var home = HomeFeature.State(server, snapshot(listOf(root, other), active = setOf(child.id)))
        home = home.copy(
            transcriptCache = mapOf(
                root.id to listOf(OpenCodeTranscriptItem.UserText(id = "message", text = "Inspect the build")),
                other.id to emptyList(),
            ),
        )
        home = home.openingDetail(root, cachedItems = home.transcriptCache[root.id])
        val model = OpenCodeModel(modelID = "example-model", providerID = "example", name = "Example", contextLimit = 100_000)
        home.updatingDetail {
            it.copy(
                familySessions = listOf(root, child),
                activeFamilySessionIDs = setOf(child.id),
                shells = listOf(OpenCodeShell(id = "shell", command = "bun run preview", sessionID = root.id, isUserCommand = true, isListed = listed)),
                models = listOf(model),
                contextUsage = OpenCodeContextUsage(tokens = 80_000, model = model.ref),
                queuedInputs = listOf(OpenCodePendingInput(id = "queued", text = "Check the result", delivery = OpenCodeDelivery.Queue)),
                hasLoadedPendingRequests = true,
            ).withPermissionRequests(
                listOf(OpenCodePermissionRequest(id = "permission", sessionID = root.id, action = "read", resources = listOf("/project/file"))),
            )
        }
    }

    private fun <R> Harness.update(home: HomeFeature.State, block: (HomeFeature.State) -> R): R = state { block(home) }

    private fun pendingClient(base: OpenCodeClient = OpenCodeClient.testValue) = base.copy(
        familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
        pendingInputs = { _, _ -> emptyList() },
    )

    @Test
    fun switchBackSeedsFirstStateAndAuthoritativeRefreshRemovesEndedWork() {
        for (listed in listOf(true, false)) homeTest { h ->
            val store = h.store(h.homeState(listed)) {
                this[DateClient] = DateClient.constant(date(100))
                this[OpenCodeClient] = pendingClient()
            }
            store.exhaustive = false
            val firstID = store.state.sessionDetail?.navigationID
            store.send(Action.SessionTapped("other"))
            assertEquals(false, store.state.sessionDetail?.hasBackgroundWork)
            store.send(Action.SessionTapped("root"))
            // No task, frame callback, or response has run for this new visit.
            assertNotEquals(firstID, store.state.sessionDetail?.navigationID)
            assertEquals(listOf("child"), store.state.sessionDetail?.activeSubagents?.map { it.id })
            assertEquals(listOf("shell"), store.state.sessionDetail?.backgroundShells?.map { it.id })
            assertEquals(0.8, store.state.sessionDetail?.contextFraction)
            assertEquals(listOf("queued"), store.state.sessionDetail?.queuedInputs?.map { it.id })
            assertEquals(listOf("permission"), store.state.sessionDetail?.permissionRequests?.map { it.id })
            assertEquals(false, store.state.sessionDetail?.hasRenderedFirstContentFrame)
            store.send(detail(SessionFeature.Action.ShellsResponse("root", Result.failure(OpenCodeClientError("offline")))))
            assertEquals(1, store.state.sessionDetail?.backgroundShells?.size)
            store.send(detail(SessionFeature.Action.ShellsResponse("root", Result.success(emptyList()))))
            assertEquals(true, store.state.sessionDetail?.backgroundShells?.isEmpty())
            store.send(detail(SessionFeature.Action.FamilyResponse(Result.success(OpenCodeSessionFamily(listOf(session("root")), emptySet())))))
            assertEquals(false, store.state.sessionDetail?.hasBackgroundWork)
            h.skipInFlightEffects()
        }
    }

    @Test
    fun offscreenShellExitAndQueueCancellationDoNotResurrectOnReturn() = homeTest { h ->
        val store = h.store(h.homeState())
        store.exhaustive = false
        store.send(Action.SessionTapped("other"))
        store.send(Action.ServerEvent(OpenCodeServerEvent.ShellEnded(shellID = "shell")))
        store.send(Action.ServerEvent(OpenCodeServerEvent.InputCancelled(sessionID = "root", inputID = "queued")))
        store.send(Action.SessionTapped("root"))
        assertEquals(true, store.state.sessionDetail?.backgroundShells?.isEmpty())
        assertEquals(true, store.state.sessionDetail?.queuedInputs?.isEmpty())
        h.skipInFlightEffects()
    }

    @Test
    fun offscreenHistoryRefreshKeepsTheUnfinishedReplyForReopening() = homeTest { h ->
        var home = h.homeState()
        home = home.copy(activeSessionIDs = home.activeSessionIDs + "root")
        val user = OpenCodeTranscriptItem.UserText(id = "message", text = "Inspect the build")
        val stale = OpenCodeTranscriptItem.AssistantMarkdown(id = "reply-text-0", text = "")
        val partial = OpenCodeTranscriptItem.AssistantMarkdown(id = "reply-text-1", text = "Checking", inProgress = true)
        home = home.updatingDetail { it.copy(items = listOf(user, stale, partial)) }
        home = h.update(home) { it.openingDetail(session("other"), cachedItems = emptyList()) }
        assertEquals(listOf(user, stale, partial), home.transcriptCache["root"])
        home = home.copy(refreshingTranscripts = home.refreshingTranscripts + "root")
        val store = h.store(home)
        store.exhaustive = false
        store.send(Action.CachedTranscriptRefreshed("root", listOf(user)))
        assertFalse("root" in store.state.refreshingTranscripts)
        store.send(Action.SessionTapped("root"))
        assertEquals(listOf(user, partial), store.state.sessionDetail?.items)
        h.skipInFlightEffects()
    }

    private fun form(id: String = "form", owner: String = "root") = OpenCodeFormRequest(
        id = id,
        sessionID = owner,
        title = "Questions",
        metadata = mapOf("kind" to OpenCodeJSONValue.Str("question")),
        fields = listOf(
            OpenCodeFormField(key = "first", type = OpenCodeFormFieldType.String, title = "First"),
            OpenCodeFormField(key = "second", type = OpenCodeFormFieldType.String, title = "Second"),
        ),
    )

    @Test
    fun restoredFormsAcceptEditsImmediatelyAndKeepPartialAnswers() {
        for (partial in listOf(true, false)) homeTest { h ->
            val request = form()
            val home = h.update(h.homeState()) { home ->
                home.updatingDetail { d ->
                    var next = d.withFormRequests(listOf(request))
                    if (partial) {
                        next = next.withFormAnswers(next.formAnswers + (request.id to mapOf("first" to OpenCodeFormValue.Str("In progress"))))
                            .withQuestionPageIndices(next.questionPageIndices + (request.id to 1))
                    }
                    next
                }
            }
            val store = h.store(home)
            store.exhaustive = false
            store.send(Action.SessionTapped("other"))
            store.send(Action.SessionTapped("root"))
            assertEquals(
                if (partial) mapOf("first" to OpenCodeFormValue.Str("In progress")) else request.defaultAnswers,
                store.state.sessionDetail?.formAnswers?.get(request.id),
            )
            assertEquals(if (partial) 1 else null, store.state.sessionDetail?.questionPageIndices?.get(request.id))
            store.send(detail(SessionFeature.Action.formValueChanged(request.id, "second", OpenCodeFormValue.Str("Edited before refresh"))))
            assertEquals(OpenCodeFormValue.Str("Edited before refresh"), store.state.sessionDetail?.formAnswers?.get(request.id)?.get("second"))
            store.send(Action.SessionTapped("other"))
            store.send(Action.SessionTapped("root"))
            assertEquals(OpenCodeFormValue.Str("Edited before refresh"), store.state.sessionDetail?.formAnswers?.get(request.id)?.get("second"))
            h.skipInFlightEffects()
        }
    }

    @Test
    fun switchingAwayKeepsTheBarsWordsForEachRequest() = homeTest { h ->
        val request = form()
        val home = h.update(h.homeState()) { home ->
            home.updatingDetail { d -> d.withFormRequests(listOf(request)).withAnswerDrafts(mapOf("${request.id}/second" to "Half typed")) }
        }
        val store = h.store(home)
        store.exhaustive = false
        store.send(Action.SessionTapped("other"))
        store.send(Action.SessionTapped("root"))
        assertEquals(mapOf("${request.id}/second" to "Half typed"), store.state.sessionDetail?.answerDrafts)
        h.skipInFlightEffects()
    }

    @Test
    fun attentionRefreshClearsSettledOffscreenRequestsButKeepsGlobalForms() {
        for (stillPending in listOf(true, false)) homeTest { h ->
            val home = h.update(h.homeState()) { home ->
                var s = home.updatingDetail { it.withFormRequests(listOf(form(), form("global-form", owner = "global"))) }
                // A cached child must use its family's root when checking attention.
                val child = s.sessionDetail!!.copy(session = session("child", parent = "root"))
                s = s.copy(
                    sessionSnapshots = s.sessionSnapshots + ("child" to SessionFeature.SwitchSnapshot.from(child)!!),
                    sessionSnapshotOrder = listOf("child"),
                )
                s
            }
            val store = h.store(home)
            store.exhaustive = false
            store.send(Action.SessionTapped("other"))
            store.send(Action.AttentionRefreshed(if (stillPending) setOf("root") else emptySet()))
            for (id in listOf("root", "child")) {
                assertEquals(if (stillPending) 1 else 0, store.state.sessionSnapshots[id]?.permissions?.size)
                assertEquals(
                    if (stillPending) listOf("form", "global-form") else listOf("global-form"),
                    store.state.sessionSnapshots[id]?.forms?.map { it.id },
                )
            }
            store.send(Action.SessionTapped("root"))
            assertEquals(if (stillPending) 1 else 0, store.state.sessionDetail?.permissionRequests?.size)
            assertEquals(
                if (stillPending) listOf("form", "global-form") else listOf("global-form"),
                store.state.sessionDetail?.formRequests?.map { it.id },
            )
            h.skipInFlightEffects()
        }
    }

    @Test
    fun leavingAChildUpdatesTheCachedRootsFamilyRequests() = homeTest { h ->
        val freshFamily = listOf(session("root"), session("child", parent = "root"), session("new-child", parent = "root"))
        val home = h.update(h.homeState()) { home ->
            home.updatingDetail { it.withFormRequests(listOf(form())) }
                .rememberingSession()
                .updatingDetail {
                    it.copy(
                        session = session("child", parent = "root"),
                        familyRootID = "root",
                        familySessions = freshFamily,
                        familyReadAt = date(200),
                    ).withCompletedRequestIDs(setOf("permission", "form"))
                }
        }
        val store = h.store(home)
        store.exhaustive = false
        // The authoritative family read settles requests while viewing the child.
        store.send(detail(SessionFeature.Action.PendingRequestsResponse("child", Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = emptyList())))))
        store.send(Action.SessionTapped("other"))
        store.send(Action.SessionTapped("root"))
        assertEquals(true, store.state.sessionDetail?.permissionRequests?.isEmpty())
        assertEquals(true, store.state.sessionDetail?.formRequests?.isEmpty())
        assertEquals(setOf("permission", "form"), store.state.sessionDetail?.completedRequestIDs)
        assertEquals(freshFamily, store.state.sessionDetail?.familySessions)
        assertEquals(date(200), store.state.sessionDetail?.familyReadAt)
        assertEquals(true, store.state.sessionDetail?.hasLoadedPendingRequests)
        h.skipInFlightEffects()
    }

    private fun familyClient(base: OpenCodeClient = OpenCodeClient.testValue) = pendingClient(base).copy(
        getSession = { _, id -> session(id, parent = "root") },
        transcript = { _, _, _ -> emptyList() },
        message = { _, _, _ -> throw OpenCodeClientError("Missing", statusCode = 404) },
        sessionFamily = { _, root -> OpenCodeSessionFamily(listOf(root), emptySet()) },
    )

    @Test
    fun returningToAChildDoesNotRestorePromotedQueuedInputs() {
        for (inTranscript in listOf(true, false)) homeTest { h ->
            val home = h.update(h.homeState()) { home ->
                var child = home.sessionDetail!!.copy(session = session("child", parent = "root"))
                child = child.copy(queuedInputs = child.queuedInputs + OpenCodePendingInput(id = "still-queued", text = "Later", delivery = OpenCodeDelivery.Queue))
                for (id in listOf("queued", "still-queued")) {
                    child = child.copy(
                        pendingPromptPositions = child.pendingPromptPositions +
                            (id to SessionFeature.State.PendingPromptPosition(precedingPromptIDs = emptySet(), precedingMessageIDs = emptySet(), requestEpoch = 1)),
                    )
                }
                home.copy(
                    sessionSnapshots = home.sessionSnapshots + ("child" to SessionFeature.SwitchSnapshot.from(child)!!),
                    sessionSnapshotOrder = listOf("child"),
                ).updatingDetail {
                    it.copy(
                        familyTranscriptCache = it.familyTranscriptCache +
                            ("child" to if (inTranscript) listOf(OpenCodeTranscriptItem.UserText(id = "queued", text = "Check the result")) else emptyList()),
                        promotedPromptIDs = if (inTranscript) emptyList() else listOf("queued"),
                    )
                }
            }
            val store = h.store(home) {
                this[DateClient] = DateClient.constant(date(100))
                this[OpenCodeClient] = familyClient().copy(runningShells = { _, _ -> emptyList() })
            }
            store.exhaustive = false
            store.send(detail(SessionFeature.Action.FamilyPicked("child")))
            assertEquals(listOf("still-queued"), store.state.sessionDetail?.queuedInputs?.map { it.id })
            assertEquals(setOf("still-queued"), store.state.sessionDetail?.pendingPromptPositions?.keys)
            h.skipInFlightEffects()
        }
    }

    @Test
    fun switchingBackKeepsThePickedModel() = homeTest { h ->
        val model = OpenCodeModel(modelID = "picked-model", providerID = "example", name = "Picked")
        val store = h.store(h.homeState())
        store.exhaustive = false
        store.send(detail(SessionFeature.Action.ModelPicked(model)))
        store.send(Action.SessionTapped("other"))
        store.send(Action.SessionTapped("root"))
        assertEquals(model.ref, store.state.sessionDetail?.currentModel)
        assertEquals("Still waiting for the next send", model.ref, store.state.sessionDetail?.pendingModel)
        h.skipInFlightEffects()
    }

    private fun Harness.withLiveCall(home: HomeFeature.State) = update(home) { s ->
        s.updatingDetail { it.copy(voice = VoiceFeature.State(server, session("root")).withPhase(VoiceFeature.State.Phase.Live)) }
    }

    private fun voiceDelegate(action: VoiceFeature.DelegateAction): Action =
        detail(SessionFeature.Action.Voice(PresentationAction.Presented(VoiceFeature.Action.Delegate(action))))

    /**
     * A pick heard for a session whose screen hasn't opened yet (the voice delegator's new
     * session, refused its model) is where that screen starts.
     */
    @Test
    fun aPickHeardBeforeItsSessionOpensIsWhereItsScreenStarts() = homeTest { h ->
        val model = OpenCodeModelRef(id = "picked-model", providerID = "example")
        val store = h.store(h.withLiveCall(h.homeState())) {
            this[DateClient] = DateClient.constant(date(100))
            this[Clock] = ImmediateClock()
        }
        store.exhaustive = false
        store.send(voiceDelegate(VoiceFeature.DelegateAction.PickWaiting(sessionID = "other", model = model)))
        assertNull("Not this screen's", store.state.sessionDetail?.pendingModel)
        store.send(Action.SessionTapped("other"))
        assertEquals("other", store.state.sessionDetail?.session?.id)
        assertEquals(model, store.state.sessionDetail?.currentModel)
        assertEquals(model, store.state.sessionDetail?.pendingModel)
        assertTrue(store.state.waitingPicks.isEmpty())
        assertEquals("The call follows with it", model, store.state.sessionDetail?.voice?.pendingModel)
        h.skipInFlightEffects()
    }

    /**
     * The voice delegator switching a session visited before (whose pick was waiting) replaces
     * the pick in its snapshot and any pick waiting for a screen that hasn't opened.
     */
    @Test
    fun aDelegatorSwitchReachesOffScreenSessions() = homeTest { h ->
        val picked = OpenCodeModel(modelID = "picked-model", providerID = "example", name = "Picked")
        val switched = OpenCodeModelRef(id = "switched-model", providerID = "example")
        val waiting = OpenCodeModelRef(id = "waiting-model", providerID = "example")
        val store = h.store(h.withLiveCall(h.homeState())) {
            this[DateClient] = DateClient.constant(date(100))
            this[Clock] = ImmediateClock()
        }
        store.exhaustive = false
        store.send(detail(SessionFeature.Action.ModelPicked(picked)))
        store.send(Action.SessionTapped("other"))
        assertEquals(picked.ref, store.state.sessionSnapshots["root"]?.pendingModel)
        store.send(voiceDelegate(VoiceFeature.DelegateAction.ModelSwitched(sessionID = "root", model = switched)))
        store.send(voiceDelegate(VoiceFeature.DelegateAction.PickWaiting(sessionID = "new", model = waiting)))
        store.send(voiceDelegate(VoiceFeature.DelegateAction.ModelSwitched(sessionID = "new", model = switched)))
        assertTrue(store.state.waitingPicks.isEmpty())
        store.send(Action.SessionTapped("root"))
        assertEquals(switched, store.state.sessionDetail?.currentModel)
        assertNull(store.state.sessionDetail?.pendingModel)
        h.skipInFlightEffects()
    }

    /** A model a send applied reaches the list's copy of its session. */
    @Test
    fun anAppliedModelReachesTheList() = homeTest { h ->
        val model = OpenCodeModelRef(id = "picked-model", providerID = "example")
        val store = h.store(h.homeState())
        store.exhaustive = false
        store.send(detail(SessionFeature.Action.ModelApplied(sessionID = "root", model = model)))
        assertEquals(model, store.state.sessions.first { it.id == "root" }.model)
    }

    @Test
    fun aPinnedRequestKeepsItsPinAcrossASwitch() = homeTest { h ->
        val home = h.update(h.homeState()) { s -> s.updatingDetail { it.withFormRequests(listOf(form())).withSelectedRequestID("permission") } }
        assertEquals("permission", home.sessionDetail?.dockRequestID)
        val store = h.store(home)
        store.exhaustive = false
        store.send(Action.SessionTapped("other"))
        assertEquals("permission", store.state.sessionSnapshots["root"]?.selectedRequest)
        store.send(Action.SessionTapped("root"))
        assertEquals("permission", store.state.sessionDetail?.selectedRequestID)
        assertEquals("permission", store.state.sessionDetail?.dockRequestID)
        h.skipInFlightEffects()
    }

    @Test
    fun aPinRestoresOnlyWhileItsRequestIsCached() = homeTest { h ->
        val home = h.update(h.homeState()) { s -> s.updatingDetail { it.withFormRequests(listOf(form())).withSelectedRequestID("permission") } }
        val store = h.store(home)
        store.exhaustive = false
        store.send(Action.SessionTapped("other"))
        // Off screen, the root stopped waiting: its cached requests and pin go.
        store.send(Action.AttentionRefreshed(emptySet()))
        assertNull(store.state.sessionSnapshots["root"]?.selectedRequest)
        store.send(Action.SessionTapped("root"))
        assertNull(store.state.sessionDetail?.selectedRequestID)
        h.skipInFlightEffects()
    }

    @Test
    fun aChildsPinSyncsToTheCachedRoot() = homeTest { h ->
        val home = h.update(h.homeState()) { s ->
            s.updatingDetail { it.withFormRequests(listOf(form())) }
                .rememberingSession()
                .updatingDetail { it.copy(session = session("child", parent = "root"), familyRootID = "root").withSelectedRequestID("permission") }
                .rememberingSession()
        }
        assertEquals("permission", home.sessionSnapshots["root"]?.selectedRequest)
    }

    @Test
    fun deletingASubagentClearsItsCachedRequestsAndPin() = homeTest { h ->
        val home = h.update(h.homeState()) { s ->
            s.updatingDetail {
                it.withPermissionRequests(it.permissionRequests + OpenCodePermissionRequest(id = "child-permission", sessionID = "child", action = "shell", resources = listOf("ls")))
                    .withFormRequests(listOf(form("child-form", owner = "child")))
                    .withFormAnswers(mapOf("child-form" to mapOf("first" to OpenCodeFormValue.Str("Draft"))))
                    .withSelectedRequestID("child-permission")
            }
        }
        val store = h.store(home)
        store.exhaustive = false
        // Cached while another session is open.
        store.send(Action.SessionTapped("other"))
        assertEquals("child-permission", store.state.sessionSnapshots["root"]?.selectedRequest)
        store.send(Action.ServerEvent(OpenCodeServerEvent.SessionDeleted(sessionID = "child")))
        val cached = requireNotNull(store.state.sessionSnapshots["root"])
        assertEquals(listOf("permission"), cached.permissions.map { it.id })
        assertTrue(cached.forms.isEmpty())
        assertTrue(cached.formAnswers.isEmpty())
        assertNull(cached.selectedRequest)
        store.send(Action.SessionTapped("root"))
        assertEquals("permission", store.state.sessionDetail?.dockRequestID)
        h.skipInFlightEffects()
    }

    @Test
    fun deletingAnUnlistedSubagentThatAsksReachesTheOpenSession() = homeTest { h ->
        val home = h.update(h.homeState()) { s ->
            // The child asked before the family read listed it.
            s.updatingDetail {
                it.copy(familySessions = listOf(session("root")))
                    .withPermissionRequests(it.permissionRequests + OpenCodePermissionRequest(id = "late-permission", sessionID = "late-child", action = "shell", resources = listOf("ls")))
                    .withSelectedRequestID("late-permission")
            }
        }
        val store = h.store(home) { this[OpenCodeClient] = pendingClient() }
        store.exhaustive = false
        store.send(Action.ServerEvent(OpenCodeServerEvent.SessionDeleted(sessionID = "late-child")))
        store.receiveDetail(SessionFeature.Action.FamilySessionDeleted::class)
        assertEquals(listOf("permission"), store.state.sessionDetail?.permissionRequests?.map { it.id })
        assertNull(store.state.sessionDetail?.selectedRequestID)
        h.skipInFlightEffects()
    }

    @Test
    fun deletedChildLeavesEveryCachedFamily() = homeTest { h ->
        val store = h.store(h.homeState())
        store.exhaustive = false
        store.send(Action.SessionTapped("other"))
        store.send(Action.ServerEvent(OpenCodeServerEvent.SessionDeleted(sessionID = "child")))
        store.send(Action.SessionTapped("root"))
        assertEquals(listOf("root"), store.state.sessionDetail?.familySessions?.map { it.id })
        assertEquals(true, store.state.sessionDetail?.activeSubagents?.isEmpty())
        h.skipInFlightEffects()
    }

    @Test
    fun openingASubagentAndReturningKeepsTheParentsChrome() = homeTest { h ->
        val home = h.update(h.homeState()) { s -> s.updatingDetail { it.copy(familyTranscriptCache = it.familyTranscriptCache + ("child" to emptyList())) } }
        val store = h.store(home) {
            this[DateClient] = DateClient.constant(date(100))
            this[OpenCodeClient] = familyClient().copy(
                runningShells = { _, _ -> throw OpenCodeClientError("Offline") },
                contextUsage = { _, _ -> throw OpenCodeClientError("Offline") },
            )
        }
        store.exhaustive = false
        store.send(detail(SessionFeature.Action.FamilyPicked("child")))
        assertEquals(1, store.state.sessionSnapshots["root"]?.shells?.size)
        store.send(detail(SessionFeature.Action.FamilyPicked("root")))
        assertEquals(1, store.state.sessionDetail?.backgroundShells?.size)
        assertEquals(0.8, store.state.sessionDetail?.contextFraction)
        h.skipInFlightEffects()
    }

    @Test
    fun catchUpAndItsPreviewUseTheSameCachedChrome() = homeTest { h ->
        val home = h.update(h.homeState()) { s ->
            var next = s.openingDetail(session("other"), cachedItems = emptyList())
            next = next.markingUnreadLocally(session("root"))
            next = next.copy(
                connection = next.connection.copy(
                    connectionStatus = HomeConnectionFeature.State.ConnectionStatus.reconnecting,
                    lastKnownActivityDeadline = distantFuture,
                ),
            )
            next.markingReadLocally(session("other")).copy(activeSessionIDs = emptySet())
        }
        // The preview card's state, built as `HomeView` builds it.
        val preview = h.state {
            val root = session("root")
            home.seedingSessionSnapshot(
                SessionFeature.State.session(home.server, root, cachedItems = home.transcriptCache[root.id], isRunning = root.id in home.activeSessionIDs),
            ).copy(readsOnView = false)
        }
        assertEquals(1, preview.backgroundShells.size)
        assertEquals(0.8, preview.contextFraction)
        val store = h.store(home)
        store.exhaustive = false
        store.send(Action.catchUpTapped)
        assertEquals("root", store.state.sessionDetail?.session?.id)
        assertEquals(1, store.state.sessionDetail?.backgroundShells?.size)
        assertEquals(0.8, store.state.sessionDetail?.contextFraction)
        assertEquals(false, store.state.sessionDetail?.readsOnView)
        h.skipInFlightEffects()
    }

    @Test
    fun cacheIsBoundedAndDoesNotCrossDirectoriesOrServers() = homeTest { h ->
        h.state {
            var home = h.homeState()
            for (index in 0 until 12) {
                val next = session("session-$index")
                home = home.copy(sessions = home.sessions + next).openingDetail(next, cachedItems = emptyList())
            }
            assertEquals(HomeFeature.transcriptMemoryLimit, home.sessionSnapshots.size)
            assertNull(home.sessionSnapshots["root"])
            val current = home.sessionDetail!!.session!!
            home = home.rememberingSession()
            val moved = current.copy(directory = "/another-project")
            val (_, detail) = home.makeDetail(moved, cachedItems = emptyList())
            assertNull(detail.familyReadAt)
            val fresh = HomeFeature.State(OpenCodeServer(url = "https://other.example", username = "", password = ""), snapshot(listOf(current)))
            assertTrue(fresh.sessionSnapshots.isEmpty())
        }
    }

    @Test
    fun recentFamilyAndModelCatalogAreReusedButShellsAlwaysRelist() {
        for (age in listOf(2.0, 6.0)) homeTest { h ->
            val detail = h.update(h.homeState()) { s ->
                val home = s.updatingDetail { it.copy(familyReadAt = date(100)) }
                    .openingDetail(session("other"), cachedItems = emptyList())
                home.makeDetail(session("root"), cachedItems = emptyList()).second.copy(initialTranscriptIsFresh = true)
            }
            val families = LockIsolated(0)
            val shells = LockIsolated(0)
            val store = h.sessionStore(detail) {
                this[DateClient] = DateClient.constant(date(100 + age))
                this[OpenCodeClient] = pendingClient().copy(
                    sessionFamily = { _, root ->
                        families.increment()
                        OpenCodeSessionFamily(listOf(root), emptySet())
                    },
                    runningShells = { _, directory ->
                        assertEquals("/project", directory)
                        shells.increment()
                        emptyList()
                    },
                    modelCatalog = { _, _ -> throw AssertionError("A warm visit must reuse the catalog") },
                )
            }
            store.exhaustive = false
            store.send(SessionFeature.Action.FirstContentFrameRendered)
            // Android's merged effects deliver the family read before the shells; the order isn't the point.
            val expected = if (age >= 5) setOf(SessionFeature.Action.ShellsResponse::class, SessionFeature.Action.FamilyResponse::class)
            else setOf(SessionFeature.Action.ShellsResponse::class)
            val seen = HashSet<Any>()
            while (seen.size < expected.size) {
                store.receiveBounded({ action ->
                    val match = expected.firstOrNull { it.isInstance(action) && it !in seen }
                    if (match != null) seen += match
                    match != null
                }, "$expected")
            }
            assertEquals(if (age < 5) 0 else 1, families.value)
            assertEquals(1, shells.value)
            h.skipInFlightEffects()
        }
    }

    @Test
    fun catchUpOrASidebarTakingOverTheOpenDrawerLetsNoticesShowAgain() = homeTest { h ->
        val home = h.update(h.homeState()) { s ->
            s.openingDetail(session("other"), cachedItems = emptyList())
                .markingUnreadLocally(session("root"))
                .markingReadLocally(session("other"))
                .copy(activeSessionIDs = emptySet(), isDrawerOpen = true)
        }
        val store = h.store(home)
        store.exhaustive = false
        store.send(Action.catchUpTapped)
        assertTrue(store.state.catchUp != null)
        assertFalse("Catch Up closes the drawer without it arriving shut", store.state.isDrawerOpen)
        h.skipInFlightEffects()

        val h2 = Harness(this)
        val sidebar = h2.store(h2.state { HomeFeature.State(server, snapshot(emptyList())) })
        sidebar.send(Action.SessionDrawerArrived(isOpen = true)) { it.copy(isDrawerOpen = true) }
        sidebar.send(Action.DrawerLabelAvailabilityChanged(false)) { it.copy(showsDrawerLabel = false, isDrawerOpen = false) }
        h2.skipInFlightEffects()
    }
}
