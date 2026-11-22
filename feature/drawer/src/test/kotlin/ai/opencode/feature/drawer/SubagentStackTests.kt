package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.composer.PendingPrompt
import ai.opencode.feature.composer.SharedComposerDraft
import ai.opencode.feature.composer.SharedDraftTarget
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeatureTestSupport.Gate
import ai.opencode.feature.drawer.HomeFeatureTestSupport.Harness
import ai.opencode.feature.drawer.HomeFeatureTestSupport.LockIsolated
import ai.opencode.feature.drawer.HomeFeatureTestSupport.client
import ai.opencode.feature.drawer.HomeFeatureTestSupport.date
import ai.opencode.feature.drawer.HomeFeatureTestSupport.detail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receiveBounded
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receiveDetail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.snapshot
import ai.opencode.feature.drawer.catchup.CatchUpFeature
import ai.opencode.feature.media.BrowserFeature
import ai.opencode.feature.session.BindingAction
import ai.opencode.feature.session.FolderBrowserFeature
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.SessionSheetFeature
import ai.opencode.feature.session.ShellOutputFeature
import ai.opencode.feature.session.beginAnswering
import ai.opencode.feature.session.isAnsweringRequest
import ai.opencode.feature.session.parkedComposer
import ai.opencode.feature.session.permissionRequests
import ai.opencode.feature.session.reconcileInvariants
import ai.opencode.feature.session.withDraft
import ai.opencode.feature.session.withPermissionRequests
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.model.OpenCodeCompaction
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.OpenCodeShell
import ai.opencode.server.model.OpenCodeTranscriptItem
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Opening a subagent pushes it over its parent; going back pops it. */
class SubagentStackTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "https://stack.example", username = "", password = "")

    private fun session(id: String, parent: String? = null) =
        OpenCodeSession(id = id, serverTitle = id, projectID = "project", directory = "/project", updatedAt = date(10), parentID = parent)

    private val root get() = session("root")
    private val child get() = session("child", parent = "root")
    private val grandchild get() = session("grandchild", parent = "child")

    private fun userText(id: String, text: String) = OpenCodeTranscriptItem.UserText(id = id, text = text)
    private fun completedCompaction(id: String) =
        OpenCodeTranscriptItem.Compaction(id = id, info = OpenCodeCompaction(status = OpenCodeCompaction.Status.Completed))

    private fun Harness.home(listingChild: Boolean = false): HomeFeature.State = state {
        val listed = listOf(root, session("other")) + if (listingChild) listOf(child) else emptyList()
        var home = HomeFeature.State(server, snapshot(listed))
        home = home.copy(transcriptCache = mapOf(root.id to listOf(userText("root-message", "Inspect the build")), "other" to emptyList()))
        home = home.openingDetail(root, cachedItems = home.transcriptCache[root.id])
        home.updatingDetail {
            it.copy(
                familySessions = listOf(root, child, grandchild),
                familyReadAt = date(50),
                familyTranscriptCache = it.familyTranscriptCache + (child.id to listOf(userText("child-message", "Explore the repo"))),
            )
                .withDraft("A draft for the parent")
                .copy(hasLoadedPendingRequests = true)
                .withPermissionRequests(
                    listOf(
                        OpenCodePermissionRequest(id = "root-permission", sessionID = root.id, action = "read", resources = listOf("/project/a")),
                        OpenCodePermissionRequest(id = "grandchild-permission", sessionID = grandchild.id, action = "shell", resources = listOf("ls")),
                    ),
                )
        }
    }

    private fun stackClient(base: OpenCodeClient = client()) = base.copy(
        getSession = { _, id -> session(id) },
        transcript = { _, _, _ -> emptyList() },
        sessionFamily = { _, root -> OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet()) },
        modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
        familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
        pendingInputs = { _, _ -> emptyList() },
        runningShells = { _, _ -> emptyList() },
    )

    private fun Harness.stackStore(state: HomeFeature.State): TestStore<HomeFeature.State, Action> =
        store(state) {
            this[DateClient] = DateClient.constant(date(100))
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = stackClient()
        }.also { it.exhaustive = false }

    /** Taps a subagent's card on the screen on top and lets Home push it. */
    private suspend fun open(id: String, store: TestStore<HomeFeature.State, Action>) {
        val top = store.state.subagentStack.lastOrNull()?.navigationID
        if (top != null) {
            store.send(Action.SubagentStack(top, SessionFeature.Action.SubagentTapped(id)))
            store.receiveBounded({ it is Action.SubagentStack && it.action is SessionFeature.Action.Delegate }, "subagentStack.delegate")
        } else {
            store.send(detail(SessionFeature.Action.SubagentTapped(id)))
            store.receiveDetail(SessionFeature.Action.Delegate::class)
        }
    }

    private fun Action.isVoice(case: kotlin.reflect.KClass<out VoiceFeature.Action>) = voiceAction?.let { case.isInstance(it) } == true

    private suspend fun TestStore<HomeFeature.State, Action>.receiveVoice(case: kotlin.reflect.KClass<out VoiceFeature.Action>) =
        receiveBounded({ it.isVoice(case) }, "sessionDetail.voice.${case.simpleName}")

    private fun HomeFeature.State.sharedDraftText(h: Harness, sessionID: String): String? =
        h.state { sharedDrafts[SharedDraftTarget(sessionID)]?.text }

    @Test
    fun openingASubagentPushesItOverTheUntouchedParent() = homeTest { h ->
        val store = h.stackStore(h.home())
        val parentID = store.state.sessionDetail!!.navigationID
        store.send(detail(SessionFeature.Action.SubagentTapped("child")))
        store.receiveBounded(
            { (it.detailAction as? SessionFeature.Action.Delegate)?.delegate is SessionFeature.Delegate.OpenSubagent },
            "sessionDetail.delegate.openSubagent",
        )

        assertEquals(1, store.state.subagentStack.size)
        val pushed = store.state.subagentStack.last()
        assertEquals("child", pushed.session?.id)
        assertNotEquals("a screen of its own", parentID, pushed.navigationID)
        assertEquals("its first frame comes from what the parent cached", listOf("child-message"), pushed.items.map { it.id })
        assertEquals("its own subagents, known already", listOf("child", "grandchild"), pushed.familySessions.map { it.id })
        assertEquals("its family's requests only", listOf("grandchild-permission"), pushed.permissionRequests.map { it.id })
        assertTrue(pushed.callsFromRoot)
        // The parent is exactly as it was, live underneath.
        assertEquals(parentID, store.state.sessionDetail?.navigationID)
        assertEquals("root", store.state.sessionDetail?.session?.id)
        assertEquals("A draft for the parent", store.state.sessionDetail?.draft)
        assertEquals(listOf("root-message"), store.state.sessionDetail?.items?.map { it.id })
        assertEquals("child", store.state.topDetail?.session?.id)
        // The drawer and its notices still follow the root.
        assertEquals(setOf("root"), store.state.openSessionIDs)
        h.skipInFlightEffects()
    }

    /**
     * A request in the child's family docks from the push; going back parks the message waiting
     * beside it, never the bar's answer.
     */
    @Test
    fun goingBackWhileAnsweringParksTheMessageNotTheAnswer() = homeTest { h ->
        val state = h.home()
        h.state { state.sharedDrafts[SharedDraftTarget("child")] = SharedComposerDraft(text = "Half a reply") }
        val store = h.stackStore(state)
        open("child", store)
        val childID = store.state.subagentStack.last().navigationID
        assertEquals("the grandchild's request docks at once", true, store.state.subagentStack.lastOrNull()?.isAnsweringRequest)
        assertEquals("Half a reply", store.state.subagentStack.lastOrNull()?.parkedComposer?.text)
        store.send(Action.SubagentStack(childID, SessionFeature.Action.Binding(BindingAction.Draft("Only in the workspace"))))
        store.send(Action.SubagentsPopped(0))
        assertEquals("Half a reply", store.state.sharedDraftText(h, "child"))
        h.skipInFlightEffects()
    }

    @Test
    fun goingBackPopsInstantlyAndKeepsTheChildsWorkForNextTime() = homeTest { h ->
        // No request in the child's family, so its composer is a message.
        val state = h.home().updatingDetail { detail -> detail.withPermissionRequests(detail.permissionRequests.filterNot { it.sessionID == "grandchild" }) }
        val store = h.stackStore(state)
        open("child", store)
        val childID = store.state.subagentStack.last().navigationID
        store.send(Action.SubagentStack(childID, SessionFeature.Action.Binding(BindingAction.Draft("Half a reply"))))
        store.send(Action.SubagentsPopped(0))

        assertTrue(store.state.subagentStack.isEmpty())
        assertEquals("A draft for the parent", store.state.sessionDetail?.draft)
        assertEquals("nothing reloads", listOf("root-message"), store.state.sessionDetail?.items?.map { it.id })
        assertEquals("Half a reply", store.state.sharedDraftText(h, "child"))
        // Pushed again: the parked draft comes back.
        open("child", store)
        assertEquals("Half a reply", store.state.subagentStack.lastOrNull()?.draft)
        h.skipInFlightEffects()
    }

    @Test
    fun nestedSubagentsPushFurtherAndPopOneAtATime() = homeTest { h ->
        val store = h.stackStore(h.home())
        open("child", store)
        val childID = store.state.subagentStack.last().navigationID
        open("grandchild", store)
        assertEquals(listOf("child", "grandchild"), store.state.subagentStack.map { it.session?.id })
        assertEquals(listOf("grandchild-permission"), store.state.subagentStack.lastOrNull()?.permissionRequests?.map { it.id })
        // Only the top screen announces a request's arrival with a haptic.
        assertEquals(true, store.state.sessionDetail?.isCovered)
        assertEquals(listOf(true, false), store.state.subagentStack.map { it.isCovered })

        store.send(Action.SubagentsPopped(1))
        assertEquals(listOf("child"), store.state.subagentStack.map { it.session?.id })
        assertEquals("the parent subagent is the same live screen", childID, store.state.subagentStack.lastOrNull()?.navigationID)
        assertEquals(listOf(false), store.state.subagentStack.map { it.isCovered })
        store.send(Action.SubagentsPopped(0))
        assertTrue(store.state.subagentStack.isEmpty())
        assertEquals(false, store.state.sessionDetail?.isCovered)
        h.skipInFlightEffects()
    }

    @Test
    fun switchingSessionsFromTheDrawerResetsTheStack() = homeTest { h ->
        val store = h.stackStore(h.home())
        open("child", store)
        store.send(Action.SessionTapped("other"))
        assertTrue(store.state.subagentStack.isEmpty())
        assertEquals("other", store.state.sessionDetail?.session?.id)

        // The row for the session on screen goes back to it, keeping it.
        store.send(Action.SessionTapped("root"))
        open("child", store)
        val rootID = store.state.sessionDetail?.navigationID
        store.send(Action.SessionTapped("root"))
        assertTrue(store.state.subagentStack.isEmpty())
        assertEquals(rootID, store.state.sessionDetail?.navigationID)
        h.skipInFlightEffects()
    }

    @Test
    fun aLinkToASubagentLandsWithItsParentUnderneath() = homeTest { h ->
        val state = h.home(listingChild = true).let { home -> h.state { home.openingDetail(session("other"), cachedItems = emptyList()) } }
        val store = h.stackStore(state)
        store.send(Action.SessionTapped("child"))
        store.receiveBounded({ it is Action.SessionTapped }, "sessionTapped")
        assertEquals("root", store.state.sessionDetail?.session?.id)
        assertEquals(listOf("child"), store.state.subagentStack.map { it.session?.id })
        assertEquals("root", store.state.lastViewedSessionIDs[server.persistenceID])
        h.skipInFlightEffects()
    }

    @Test
    fun rootOwnedCallAdmissionReachesOnlyTheMatchingSubagent() = homeTest { h ->
        val child = this@SubagentStackTests.child
        val state = h.home().let { home ->
            h.state {
                home.updatingDetail { it.copy(voice = VoiceFeature.State(server, child).withPhase(VoiceFeature.State.Phase.Live)) }
                    .copy(
                        subagentStack = listOf(
                            SessionFeature.State.session(server, child, cachedItems = emptyList(), navigationID = 1).copy(callsFromRoot = true),
                            SessionFeature.State.session(server, grandchild, cachedItems = emptyList(), navigationID = 2).copy(callsFromRoot = true),
                        ),
                    )
            }
        }
        val childID = state.subagentStack.first().navigationID
        h.promptOutbox.recordAdmitted(
            PendingPrompt(messageID = "msg_child_voice", text = "Check the build", attachments = emptyList(), requestEpoch = 0),
            sessionID = child.id,
            serverKey = server.persistenceID,
        )
        val store = h.store(state) {
            this[DateClient] = DateClient.constant(date(100))
            this[OpenCodeClient] = client().copy(
                pendingInputs = { _, id ->
                    assertEquals(child.id, id)
                    emptyList()
                },
                message = { _, _, _ -> throw OpenCodeClientError(message = "Offline") },
                transcript = { _, id, _ ->
                    assertEquals(child.id, id)
                    emptyList()
                },
                getSession = { _, id ->
                    assertEquals(child.id, id)
                    child
                },
                sessionFamily = { _, root -> OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet()) },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                runningShells = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false
        store.send(
            detail(SessionFeature.Action.voicePresented(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.PromptAdmitted(sessionID = child.id)))),
        )
        store.receiveBounded(
            { it is Action.SubagentStack && it.id == childID && it.action is SessionFeature.Action.VoicePromptAdmitted },
            "subagentStack[$childID].voicePromptAdmitted",
        )
        store.finish()
        assertEquals(listOf("msg_child_voice"), store.state.subagentStack.firstOrNull { it.navigationID == childID }?.queuedInputs?.map { it.id })
        assertEquals(true, store.state.subagentStack.lastOrNull()?.queuedInputs?.isEmpty())
        assertEquals(true, store.state.sessionDetail?.queuedInputs?.isEmpty())
        assertEquals(listOf("root-message"), store.state.sessionDetail?.items?.map { it.id })
    }

    @Test
    fun aLiveCallFollowsPushesAndPops() = homeTest { h ->
        val rootModel = OpenCodeModelRef(id = "claude-opus-4", providerID = "anthropic", variant = "high")
        val childModel = OpenCodeModelRef(id = "gpt-5.4", providerID = "openai", variant = "medium")
        val rootURL = "https://docs.example.com/root"
        val childURL = "https://docs.example.com/child"
        val state = h.home().let { home ->
            h.state {
                home.updatingDetail { detail ->
                    val withPage = detail.copy(
                        currentModel = rootModel,
                        pendingModel = rootModel,
                        items = listOf(userText("root-message", "Inspect the build"), completedCompaction("cmp-root")),
                        browser = detail.browser.copy(pageID = UUID(0, 0), url = rootURL),
                        familyPendingModels = detail.familyPendingModels + (child.id to childModel),
                        familyTranscriptCache = detail.familyTranscriptCache +
                            (child.id to listOf(userText("child-message", "Explore the repo"), completedCompaction("cmp-child"))),
                    )
                    val voice = VoiceFeature.State(server, root).withPhase(VoiceFeature.State.Phase.Live).copy(viewing = withPage.browser.viewing)
                    withPage.copy(voice = voice).reconcileInvariants()
                }
            }
        }
        assertEquals(rootModel, state.sessionDetail?.voice?.pendingModel)
        assertEquals(true, state.sessionDetail?.voice?.targetNeedsInput)
        assertEquals("cmp-root", state.sessionDetail?.voice?.compactions?.get(root.id))
        assertEquals(rootURL, state.sessionDetail?.voice?.viewing?.url)

        val store = h.stackStore(state)

        open("child", store)
        assertEquals("the root's call retargets to the screen on top", "child", store.state.sessionDetail?.voice?.target?.sessionID)
        assertNull("the root keeps owning it", store.state.subagentStack.lastOrNull()?.voice)
        assertEquals("the pushed subagent's waiting model pick projects onto the call", childModel, store.state.sessionDetail?.voice?.pendingModel)
        assertEquals(
            "the grandchild's pending permission keeps targetNeedsInput true on the pushed subagent",
            true,
            store.state.sessionDetail?.voice?.targetNeedsInput,
        )
        assertEquals("the pushed subagent's finished compaction seeds into the call", "cmp-child", store.state.sessionDetail?.voice?.compactions?.get(child.id))
        assertNull("the parent's browser page does not stay over the pushed subagent", store.state.sessionDetail?.voice?.viewing)
        store.receiveVoice(VoiceFeature.Action.TargetChanged::class)

        val childID = store.state.subagentStack.last().navigationID
        store.send(Action.SubagentStack(childID, SessionFeature.Action.Browser(BrowserFeature.Action.Open(childURL))))
        store.receiveVoice(VoiceFeature.Action.ViewingChanged::class)
        assertEquals(
            "browser changes on the top subagent forward .viewingChanged to the root-owned call",
            childURL,
            store.state.sessionDetail?.voice?.viewing?.url,
        )

        store.send(Action.SubagentsPopped(0))
        assertEquals("root", store.state.sessionDetail?.voice?.target?.sessionID)
        assertEquals("popping back to the root restores its waiting model pick", rootModel, store.state.sessionDetail?.voice?.pendingModel)
        assertEquals("popping back to the root restores its pending request flag", true, store.state.sessionDetail?.voice?.targetNeedsInput)
        assertEquals("cmp-root", store.state.sessionDetail?.voice?.compactions?.get(root.id))
        assertEquals("popping back to the root restores its active browser page", rootURL, store.state.sessionDetail?.voice?.viewing?.url)
        store.receiveVoice(VoiceFeature.Action.TargetChanged::class)
        h.skipInFlightEffects()
    }

    @Test
    fun rePushingASubagentAfterApplyingOrChangingItsModelDoesNotRestoreStaleFamilyPendingModel() = homeTest { h ->
        val staleModel = OpenCodeModel(modelID = "gpt-5.4", providerID = "openai", name = "GPT-5.4")
        val newerModel = OpenCodeModel(modelID = "claude-opus-4", providerID = "anthropic", name = "Claude Opus 4")
        val state = h.home(listingChild = true).let { home ->
            h.state {
                home.updatingDetail {
                    it.copy(
                        familyPendingModels = it.familyPendingModels + (child.id to staleModel.ref),
                        voice = VoiceFeature.State(server, root).withPhase(VoiceFeature.State.Phase.Live),
                    )
                }
            }
        }

        val store = h.stackStore(state)
        open("child", store)
        store.receiveVoice(VoiceFeature.Action.TargetChanged::class)
        val firstChildID = store.state.subagentStack.last().navigationID
        assertEquals(staleModel.ref, store.state.subagentStack.lastOrNull()?.pendingModel)
        assertEquals(staleModel.ref, store.state.sessionDetail?.voice?.pendingModel)

        // Changing the model on the pushed subagent and applying it forwards to noteModel and
        // sessionDetail, updating sessions and voice.pendingModel.
        store.send(Action.SubagentStack(firstChildID, SessionFeature.Action.ModelPicked(newerModel)))
        assertEquals(newerModel.ref, store.state.sessionDetail?.voice?.pendingModel)
        store.send(Action.SubagentStack(firstChildID, SessionFeature.Action.ModelApplied(sessionID = "child", model = newerModel.ref)))
        assertNull(store.state.subagentStack.lastOrNull()?.pendingModel)
        assertEquals(newerModel.ref, store.state.subagentStack.lastOrNull()?.currentModel)
        assertNull(store.state.sessionDetail?.voice?.pendingModel)
        assertEquals(newerModel.ref, store.state.sessions.firstOrNull { it.id == "child" }?.model)
        assertEquals(staleModel.ref, store.state.sessionDetail?.familyPendingModels?.get(child.id))

        // Popping and re-pushing the subagent uses its snapshot (currentModel == newerModel.ref,
        // pendingModel == null) instead of restoring parent.familyPendingModels[child.id].
        store.send(Action.SubagentsPopped(0))
        store.receiveVoice(VoiceFeature.Action.TargetChanged::class)
        open("child", store)
        store.receiveVoice(VoiceFeature.Action.TargetChanged::class)
        assertEquals(newerModel.ref, store.state.subagentStack.lastOrNull()?.currentModel)
        assertNull(store.state.subagentStack.lastOrNull()?.pendingModel)
        assertNull(store.state.sessionDetail?.voice?.pendingModel)
        h.skipInFlightEffects()
    }

    @Test
    fun startingACallFromAPushedSubagentSyncsTheTopScreensVoiceInvariants() = homeTest { h ->
        val childModel = OpenCodeModelRef(id = "gpt-5.4", providerID = "openai", variant = "high")
        val childURL = "https://docs.example.com/subagent"
        h.setShared(SharedKeys.voiceModeEnabled, true)
        h.setShared(SharedKeys.openAIAPIKey, "sk-test")
        val state = h.home().let { home ->
            h.state {
                home.copy(waitingPicks = home.waitingPicks + (child.id to childModel)).updatingDetail {
                    it.copy(
                        familyTranscriptCache = it.familyTranscriptCache +
                            (child.id to listOf(userText("child-message", "Explore the repo"), completedCompaction("cmp-child"))),
                    ).beginAnswering()
                }
            }
        }
        assertEquals(true, state.sessionDetail?.isAnsweringRequest)

        val store = h.stackStore(state)

        open("child", store)
        val childID = store.state.subagentStack.last().navigationID
        store.send(Action.SubagentStack(childID, SessionFeature.Action.Browser(BrowserFeature.Action.Open(childURL))))

        store.send(Action.SubagentStack(childID, SessionFeature.Action.VoiceTapped))
        store.receiveBounded({ it is Action.SubagentStack }, "subagentStack")

        val voice = store.state.sessionDetail?.voice
        assertNotNull(voice)
        assertEquals("child", voice!!.target.sessionID)
        assertEquals("starting a call from a pushed subagent carries its waiting model pick", childModel, voice.pendingModel)
        assertEquals("starting a call from a pushed subagent reflects its pending requests", true, voice.targetNeedsInput)
        assertEquals("starting a call from a pushed subagent seeds its finished compaction", "cmp-child", voice.compactions[child.id])
        assertEquals("starting a call from a pushed subagent carries its open browser page", childURL, voice.viewing?.url)
        h.skipInFlightEffects()
    }

    @Test
    fun switchingToARestoredSessionDuringACallSyncsPendingRequestsAndEndsAnswering() = homeTest { h ->
        val otherModel = OpenCodeModelRef(id = "claude-sonnet-4", providerID = "anthropic", variant = "high")
        val state = h.home().let { home ->
            h.state {
                home.sharedDrafts[SharedDraftTarget("other")] = SharedComposerDraft(text = "Parked message on other")
                var s = home.copy(
                    transcriptCache = home.transcriptCache +
                        ("other" to listOf(userText("other-msg", "Deploy"), completedCompaction("cmp-other"))),
                )
                val (made, otherDetail) = s.makeDetail(session("other"), cachedItems = s.transcriptCache["other"])
                s = made.remembering(
                    otherDetail.copy(currentModel = otherModel, pendingModel = otherModel, hasLoadedPendingRequests = true)
                        .withPermissionRequests(
                            listOf(OpenCodePermissionRequest(id = "other-perm", sessionID = "other", action = "shell", resources = listOf("deploy.sh"))),
                        ),
                )
                s.updatingDetail { it.copy(voice = VoiceFeature.State(server, root).withPhase(VoiceFeature.State.Phase.Live)) }
            }
        }

        val store = h.stackStore(state)
        store.send(Action.SessionTapped("other"))

        val switched = store.state.sessionDetail!!
        assertEquals("other", switched.session?.id)
        assertTrue("a floating call leaves the composer, so the request docks", switched.isAnsweringRequest)
        assertEquals("other", switched.voice?.target?.sessionID)
        assertEquals(otherModel, switched.voice?.pendingModel)
        assertEquals("restored pending requests on the target session sync to voice.targetNeedsInput", true, switched.voice?.targetNeedsInput)
        assertEquals("cmp-other", switched.voice?.compactions?.get("other"))

        // Deferred switch (`selectedTranscriptResponse` when `transcriptCache` misses) runs the
        // same `makeDetail` → `finishDetailTransition` → `reconcileInvariants` path.
        store.send(Action.SessionTapped("root"))
        val deferredState = store.state.copy(transcriptCache = store.state.transcriptCache - "other")
        val deferredStore = h.store(deferredState) {
            this[DateClient] = DateClient.constant(date(100))
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = stackClient().copy(
                transcript = { _, sessionID, _ ->
                    if (sessionID == "other") listOf(userText("other-msg", "Deploy"), completedCompaction("cmp-other-2")) else emptyList()
                },
            )
        }
        deferredStore.exhaustive = false
        deferredStore.send(Action.SessionTapped("other"))
        deferredStore.receiveBounded({ it is Action.SessionNavigationReady }, "sessionNavigationReady")
        deferredStore.receiveBounded({ it is Action.SelectedTranscriptResponse }, "selectedTranscriptResponse")
        deferredStore.receiveDetail(SessionFeature.Action.TranscriptResponse::class)
        deferredStore.receiveVoice(VoiceFeature.Action.CompactionObserved::class)
        val deferredSwitched = deferredStore.state.sessionDetail!!
        assertEquals("other", deferredSwitched.session?.id)
        assertTrue(deferredSwitched.isAnsweringRequest)
        assertEquals("other", deferredSwitched.voice?.target?.sessionID)
        assertEquals(otherModel, deferredSwitched.voice?.pendingModel)
        assertEquals(true, deferredSwitched.voice?.targetNeedsInput)
        assertEquals("cmp-other-2", deferredSwitched.voice?.compactions?.get("other"))
        h.skipInFlightEffects()
    }

    @Test
    fun aPushedSubagentThatGoesAwayReturnsToItsParent() = homeTest { h ->
        val store = h.stackStore(h.home())
        open("child", store)
        store.send(Action.ServerEvent(OpenCodeServerEvent.SessionDeleted(sessionID = "child")))
        assertTrue(store.state.subagentStack.isEmpty())
        assertEquals("root", store.state.sessionDetail?.session?.id)
        assertNull("popping a deleted subagent never saves it again", store.state.sessionSnapshots["child"])
        h.skipInFlightEffects()
    }

    /** Opening a subagent already pushed (a link, the voice agent) goes back to it; the root is never rebuilt as that subagent. */
    @Test
    fun openingAPushedSubagentAgainGoesBackToIt() = homeTest { h ->
        val store = h.stackStore(h.home(listingChild = true))
        val rootID = store.state.sessionDetail?.navigationID
        open("child", store)
        open("grandchild", store)
        assertEquals(listOf("child", "grandchild"), store.state.subagentStack.map { it.session?.id })
        store.send(Action.SessionTapped("child"))
        assertEquals(listOf("child"), store.state.subagentStack.map { it.session?.id })
        assertEquals("root", store.state.sessionDetail?.session?.id)
        assertEquals(rootID, store.state.sessionDetail?.navigationID)
        store.send(Action.SessionTapped("child"))
        assertEquals("the top again: nothing changes", listOf("child"), store.state.subagentStack.map { it.session?.id })
        assertEquals("root", store.state.sessionDetail?.session?.id)
        h.skipInFlightEffects()
    }

    @Test
    fun aCoveredParentKeepsReceivingItsLiveEvents() = homeTest { h ->
        val store = h.stackStore(h.home())
        open("child", store)
        store.send(Action.ServerEvent(OpenCodeServerEvent.InputQueueChanged(sessionID = "root", inputID = null)))
        store.receiveDetail(SessionFeature.Action.InputQueueChanged::class)
        store.send(Action.ServerEvent(OpenCodeServerEvent.InputQueueChanged(sessionID = "child", inputID = null)))
        store.receiveBounded({ it is Action.SubagentStack }, "subagentStack")
        h.skipInFlightEffects()
    }

    @Test
    fun catchUpCardsStillSwapTheirFamilyInPlace() = homeTest { h ->
        val state = h.home().withCatchUp(CatchUpFeature.Stack(queue = listOf("root"), returnSessionID = null))
        val store = h.stackStore(state)
        store.send(detail(SessionFeature.Action.SubagentTapped("child")))
        store.receiveDetail(SessionFeature.Action.FamilyPicked::class)
        assertTrue(store.state.subagentStack.isEmpty())
        assertEquals("child", store.state.sessionDetail?.session?.id)
        h.skipInFlightEffects()
    }

    /**
     * A popped subagent's view stays in `SessionPageStack` for its pop animation and fires
     * callbacks after its state has already left `subagentStack`; late element actions must be
     * ignored without trapping in `forEach`, while in-flight subagent effects still cancel on pop.
     */
    @Test
    fun lateElementActionsAfterPopOrSessionSwitchDoNotTrapForEach() = homeTest { h ->
        val childSession = child
        val transcriptStarted = Gate()
        val transcriptCancelled = LockIsolated(false)
        val store = h.store(h.home()) {
            this[DateClient] = DateClient.constant(date(100))
            this[OpenCodeClient] = client().copy(
                getSession = { _, _ -> childSession },
                sessionFamily = { _, root -> OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet()) },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                transcript = { _, sessionID, _ ->
                    if (sessionID == "child") {
                        transcriptStarted.yield()
                        suspendCancellableCoroutine<Unit> { continuation ->
                            continuation.invokeOnCancellation { transcriptCancelled.setValue(true) }
                        }
                    }
                    emptyList()
                },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
                runningShells = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        open("child", store)
        val childID = store.state.subagentStack.last().navigationID
        store.send(Action.SubagentStack(childID, SessionFeature.Action.Task))
        store.send(Action.SubagentStack(childID, SessionFeature.Action.FirstContentFrameRendered))
        // The frame's `.refreshRequested(.open)` waits for a drain.
        repeat(5) { store.skipReceivedActions() }
        transcriptStarted.await()

        store.send(Action.SubagentsPopped(0))
        assertTrue(store.state.subagentStack.isEmpty())
        assertTrue("popping the subagent cancels its in-flight effects", transcriptCancelled.value)
        assertEquals(setOf("child-message"), store.state.sessionSnapshots["child"]?.seenItemIDs)

        // Late actions from the departing subagent view or queued forwards:
        store.send(Action.SubagentStack(childID, SessionFeature.Action.VisibilityChanged(false)))
        store.send(Action.SubagentStack(childID, SessionFeature.Action.FirstContentFrameRendered))
        store.send(Action.SubagentStack(childID, SessionFeature.Action.WorkingRunElapsed(1)))
        assertTrue(store.state.subagentStack.isEmpty())

        // Same when a drawer session switch clears the subagent stack:
        open("child", store)
        assertEquals(setOf("child-message"), store.state.subagentStack.lastOrNull()?.newMessagesBaseline)
        val secondChildID = store.state.subagentStack.last().navigationID
        store.send(Action.SessionTapped("other"))
        assertTrue(store.state.subagentStack.isEmpty())
        store.send(Action.SubagentStack(secondChildID, SessionFeature.Action.VisibilityChanged(false)))
        assertTrue(store.state.subagentStack.isEmpty())
        h.skipInFlightEffects()
    }

    /**
     * Family navigation and sheet/call dismissals clear child state while views or debounced
     * tasks finish tearing down; late child actions must not trap `ifLet`.
     */
    @Test
    fun lateChildActionsAfterFamilySwitchOrDismissalDoNotTrapIfLet() = homeTest { h ->
        val clock = TestClock()
        val state = h.home().let { home ->
            h.state {
                home.updatingDetail {
                    it.copy(
                        sessionSheet = SessionSheetFeature.State(server = server, sessionID = "root"),
                        shellOutput = ShellOutputFeature.State(
                            server = server,
                            directory = "/project",
                            shell = OpenCodeShell(id = "sh_1", command = "ls", sessionID = "root"),
                        ),
                        folderBrowser = FolderBrowserFeature.State(server = server),
                        voice = VoiceFeature.State(server, root).withPhase(VoiceFeature.State.Phase.Live),
                    )
                }
            }
        }

        val store = h.store(state) {
            this[Clock] = clock
            this[UuidClient] = UuidClient.incrementing()
            this[DateClient] = DateClient.constant(date(100))
            this[OpenCodeClient] = stackClient()
        }
        store.exhaustive = false

        // Start the 600ms debounced browser-viewing notification for the call, then switch family
        // member (which clears sessionSheet and shellOutput) and end the call before 600ms elapses.
        store.send(detail(SessionFeature.Action.Browser(BrowserFeature.Action.Open("https://example.com"))))
        store.send(detail(SessionFeature.Action.FamilyPicked("child")))
        assertNull(store.state.sessionDetail?.sessionSheet)
        assertNull(store.state.sessionDetail?.shellOutput)

        // Clear voice and folderBrowser, then advance past the 600ms debounce and send late child
        // and dismiss actions for each cleared child.
        store.send(detail(SessionFeature.Action.ProjectPickerDismissed))
        assertNull(store.state.sessionDetail?.folderBrowser)
        store.send(detail(SessionFeature.Action.Voice(PresentationAction.Dismiss)))
        assertNull(store.state.sessionDetail?.voice)
        clock.advance(700.milliseconds)

        store.send(detail(SessionFeature.Action.SessionSheet(SessionSheetFeature.Action.FailureDismissed)))
        store.send(detail(SessionFeature.Action.ShellOutput(PresentationAction.Presented(ShellOutputFeature.Action.Task))))
        store.send(detail(SessionFeature.Action.ShellOutput(PresentationAction.Dismiss)))
        store.send(detail(SessionFeature.Action.FolderBrowser(PresentationAction.Presented(FolderBrowserFeature.Action.RoutesChanged(emptyList())))))
        store.send(detail(SessionFeature.Action.FolderBrowser(PresentationAction.Dismiss)))
        store.send(detail(SessionFeature.Action.Voice(PresentationAction.Dismiss)))
        assertFalse(store.state.sessionDetail?.voice != null)
        h.skipInFlightEffects()
    }
}
