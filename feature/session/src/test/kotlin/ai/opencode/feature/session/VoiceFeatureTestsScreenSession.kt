package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.PresentationAction
import ai.opencode.feature.composer.ComposerFileAttachment
import ai.opencode.feature.composer.PendingPrompt
import ai.opencode.feature.composer.PromptOutbox
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.date
import ai.opencode.feature.session.SessionTestSupport.distantPast
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.settle
import ai.opencode.feature.session.SessionTestSupport.withTestDependencies
import ai.opencode.feature.transcript.user.TranscriptUserMessage
import ai.opencode.feature.voice.VoiceAdmissionRecorder
import ai.opencode.feature.voice.VoiceCallClient
import ai.opencode.feature.voice.VoiceCallEvent
import ai.opencode.feature.voice.VoiceDelegator
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceHandoff
import ai.opencode.feature.voice.VoiceRoute
import ai.opencode.feature.voice.VoiceWorkAdmission
import ai.opencode.feature.voice.VoiceWorkClient
import ai.opencode.feature.voice.VoiceWorkTarget
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Voice records through `VoiceAdmissionRecorder`, which `:app` installs over the composer's outbox.
 * Tests that assert admissions install the same bridge ([recordInto]).
 */
class VoiceFeatureTestsScreenSession {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_voice", serverTitle = "Voice lab", projectID = "p", directory = "/Users/kit/project", updatedAt = distantPast)

    private fun DependencyValues.Builder.recordInto(outbox: PromptOutbox) {
        this[PromptOutbox] = outbox
        this[VoiceAdmissionRecorder] = VoiceAdmissionRecorder { prompt, sessionID, serverKey ->
            outbox.recordAdmitted(
                PendingPrompt(
                    messageID = prompt.messageID,
                    text = prompt.text,
                    attachments = emptyList(),
                    files = prompt.files.map { ComposerFileAttachment(uploaded = it) },
                    ownsRun = prompt.ownsRun,
                    delivery = prompt.delivery,
                    requestEpoch = prompt.requestEpoch,
                ),
                sessionID,
                serverKey,
            )
        }
    }

    private fun callClient(
        completeFunction: suspend (String, String) -> Unit = { _, _ -> },
        start: suspend (String, ai.opencode.feature.voice.RealtimeSessionConfig) -> kotlinx.coroutines.flow.Flow<VoiceCallEvent> = { _, _ -> kotlinx.coroutines.flow.emptyFlow() },
        delegatorRound: suspend (String) -> Result<VoiceDelegator.Response> = { Result.failure(OpenCodeClientError("The voice call has no delegator.")) },
        submitDelegatorInput: suspend (String, List<VoiceDelegator.InputItem>) -> Boolean = { _, _ -> false },
    ) = VoiceCallClient(
        start = start,
        completeFunction = completeFunction,
        closeDelegation = { _, _ -> },
        setMuted = { },
        updateInstructions = { _, _ -> },
        end = {},
        delegatorRound = delegatorRound,
        submitDelegatorInput = submitDelegatorInput,
    )

    private fun workClient(
        submit: suspend (VoiceWorkTarget, String) -> VoiceWorkAdmission = { _, _ -> VoiceWorkAdmission(promptID = "msg_test") },
        awaitReply: suspend (OpenCodeServer, String, String) -> String? = { _, _, _ -> null },
        recentMessages: suspend (OpenCodeServer, String, Int) -> List<String> = { _, _, _ -> emptyList() },
    ) = VoiceWorkClient(
        submit = submit,
        awaitReply = awaitReply,
        checkSessionActive = { _, _ -> false },
        recentMessages = recentMessages,
    )

    private fun delegate(callID: String, text: String) = Action.voicePresented(
        VoiceFeature.Action.CallEvent(
            VoiceCallEvent.FunctionCall(callID = callID, name = "delegate", arguments = JsonObject(mapOf("text" to JsonPrimitive(text)))),
        ),
    )

    private inline fun <reified T : VoiceFeature.Action> voice(): (Action) -> Boolean = { action ->
        ((action as? Action.Voice)?.action as? PresentationAction.Presented)?.action is T
    }

    private inline fun <reified T : VoiceFeature.DelegateAction> voiceDelegate(): (Action) -> Boolean = { action ->
        val presented = ((action as? Action.Voice)?.action as? PresentationAction.Presented)?.action
        presented is VoiceFeature.Action.Delegate && presented.action is T
    }

    private fun live(voice: VoiceFeature.State) = voice.withPhase(VoiceFeature.State.Phase.Live)

    /** An empty `transcript` read that never returns. */
    private suspend fun neverItems(): List<OpenCodeTranscriptItem> = awaitCancellation()

    private fun DependencyValues.Builder.draftSessionClient(created: OpenCodeSession) {
        this[OpenCodeClient] = this[OpenCodeClient].copy(
            getSession = { _, _ -> created },
            transcript = { _, _, _ -> neverItems() },
            sessionFamily = { _, root -> OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet()) },
            runningShells = { _, _ -> emptyList() },
            modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
            familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
            pendingInputs = { _, _ -> emptyList() },
        )
    }

    /**
     * Realtime, Grok and Gemini use direct submission, without a delegator.
     * Its accepted prompt must reach the mounted screen without an SSE echo.
     */
    @Test fun directDelegationAppearsWithoutAnEventStreamEcho() = sessionTest { h ->
        val outbox = PromptOutbox()
        val posted = LockIsolated<String?>(null)
        val earlier = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "msg_before", text = "Earlier"))
        val state = h.state {
            State.session(server = server, session = session, cachedItems = earlier).copy(
                voice = live(VoiceFeature.State(server, session)).copy(activeRoute = VoiceRoute.PhoneKey(VoiceEngine.realtime), usesDelegator = false),
                isVisible = true,
            )
        }
        val store = h.store(state) {
            recordInto(outbox)
            this[DateClient] = DateClient.constant(date(4_000))
            this[VoiceWorkClient] = workClient(
                submit = { target, text ->
                    assertEquals(session.id, target.sessionID)
                    posted.setValue(text)
                    VoiceWorkAdmission(promptID = "msg_direct_admitted")
                },
            )
            this[VoiceCallClient] = callClient()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> session },
                pendingInputs = { _, _ -> emptyList() },
                message = { _, _, _ -> throw OpenCodeClientError("Offline") },
                transcript = { _, _, _ -> earlier },
            )
        }
        // Test the admission prefix; ongoing reply polling belongs to the call.
        store.exhaustive = false
        store.send(delegate("direct", "Check the session sync."))
        store.receive(voice<VoiceFeature.Action.WorkAdmitted>(), "voice.presented.workAdmitted")
        store.skipReceivedActions()
        val text = posted.value ?: throw AssertionError("Nothing was posted")
        assertEquals(1, outbox.contents(serverKey = server.persistenceID, sessionID = session.id).admitted.size)
        assertEquals(listOf("msg_direct_admitted"), store.state.queuedInputs.map { it.id })
        assertEquals("Check the session sync.", store.state.queuedInputRows.firstOrNull()?.let(TranscriptUserMessage::from)?.voiceDelegation?.displayText)
        store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.success(earlier)))
        assertEquals(listOf("msg_direct_admitted"), store.state.queuedInputs.map { it.id })
        store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.success(earlier + OpenCodeTranscriptItem.UserText(id = "msg_direct_admitted", text = text))))
        store.send(Action.InputEnqueued(OpenCodePendingInput(id = "msg_direct_admitted", text = text)))
        assertTrue(store.state.queuedInputs.isEmpty())
        assertEquals(1, store.state.items.count { SessionFeature.messageID(it) == "msg_direct_admitted" })
        h.skipInFlightEffects()
    }

    /**
     * The POST succeeds while SSE is delayed and REST still has the old page.
     * Admission must reach the mounted transcript, not only the next visit.
     */
    @Test fun serverDelegationAppearsWithoutAnEventStreamEcho() = sessionTest { h ->
        val outbox = PromptOutbox()
        val posted = LockIsolated<String?>(null)
        val rounds = LockIsolated(0)
        val earlier = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "msg_before", text = "Earlier"))
        val state = h.state {
            State.session(server = server, session = session, cachedItems = earlier).copy(
                voice = live(VoiceFeature.State(server, session)).copy(usesDelegator = true),
                isVisible = true,
            )
        }
        val store = h.store(state) {
            recordInto(outbox)
            // The clock is unimplemented; the call's reply-watch timers wait on it until skipped.
            this[Clock] = ai.opencode.core.dependencies.TestClock()
            this[DateClient] = DateClient.constant(date(4_000))
            this[VoiceCallClient] = callClient(
                delegatorRound = {
                    val round = rounds.withValue { (it + 1) to (it + 1) }
                    Result.success(
                        VoiceDelegator.Response(
                            id = "response_$round",
                            calls = if (round == 1) listOf(VoiceDelegator.ToolCall(callID = "send", name = "send_message", arguments = """{"text":"Check the session sync."}""")) else emptyList(),
                            text = if (round == 1) "" else "Sent.",
                        ),
                    )
                },
                submitDelegatorInput = { _, _ -> true },
            )
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> session },
                moveToBackground = { _, _ -> },
                sendDeliveredPrompt = { _, id, text, delivery ->
                    assertTrue(id == session.id && delivery == OpenCodeDelivery.Steer)
                    posted.setValue(text)
                    "msg_voice_admitted"
                },
                pendingInputs = { _, _ -> emptyList() },
                message = { _, _, _ -> throw OpenCodeClientError("Offline") },
                transcript = { _, _, _ -> earlier },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
            )
            this[VoiceWorkClient] = VoiceWorkClient.liveValue.let { live ->
                VoiceWorkClient(
                    submit = live.submit,
                    awaitReply = { _, _, _ -> null },
                    checkSessionActive = { _, _ -> false },
                    recentMessages = { _, _, _ -> emptyList() },
                )
            }
        }
        // Exercise the complete delegator/tool bridge; assert transcript ownership
        // below rather than the unrelated reply-watch bookkeeping actions.
        store.exhaustive = false
        store.send(Action.voicePresented(VoiceFeature.Action.CallEvent(VoiceCallEvent.ServerDelegation(callID = "delegation"))))
        store.receive(voice<VoiceFeature.Action.DelegatorFinished>(), "voice.presented.delegatorFinished", timeout = 5.seconds)
        store.skipReceivedActions()
        val text = posted.value ?: throw AssertionError("Nothing was posted")
        assertEquals(1, outbox.contents(serverKey = server.persistenceID, sessionID = session.id).admitted.size)
        assertEquals(listOf("msg_voice_admitted"), store.state.queuedInputs.map { it.id })
        assertEquals("Check the session sync.", store.state.queuedInputRows.firstOrNull()?.let(TranscriptUserMessage::from)?.voiceDelegation?.displayText)

        // A stale page cannot erase the admitted row. Its eventual REST echo
        // promotes it once; a late SSE admission must not duplicate it.
        store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.success(earlier)))
        assertEquals(listOf("msg_voice_admitted"), store.state.queuedInputs.map { it.id })
        store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.success(earlier + OpenCodeTranscriptItem.UserText(id = "msg_voice_admitted", text = text))))
        store.send(Action.InputEnqueued(OpenCodePendingInput(id = "msg_voice_admitted", text = text)))
        assertTrue(store.state.queuedInputs.isEmpty())
        assertEquals(1, store.state.items.count { SessionFeature.messageID(it) == "msg_voice_admitted" })
        // Only admission and reconciliation are under test; stop the call's
        // ongoing reply-watch timers without synthesizing a hang-up.
        h.skipInFlightEffects()
    }

    /**
     * The call carries the screen's pick and hands it back once applied;
     * the screen's model button keeps it.
     */
    @Test fun theCallCarriesTheScreensPickUntilItIsApplied() = sessionTest { h ->
        val session = OpenCodeSession(id = "ses_1", serverTitle = "Fix", projectID = "p", directory = "/project", updatedAt = distantPast)
        val fast = OpenCodeModel(modelID = "fast", providerID = "fixture", name = "Fast")
        val state = h.state { State.session(server = server, session = session, cachedItems = emptyList()).copy(voice = live(VoiceFeature.State(server, session))) }
        val store = h.store(state)
        store.exhaustive = false

        store.send(Action.ModelPicked(fast))
        assertEquals(fast.ref, store.state.voice?.pendingModel)
        store.send(Action.voicePresented(VoiceFeature.Action.PendingModelApplied(sessionID = "ses_1", model = fast.ref)))
        store.receive(voiceDelegate<VoiceFeature.DelegateAction.PendingModelApplied>(), "voice.presented.delegate.pendingModelApplied")
        assertNull(store.state.pendingModel)
        assertNull(store.state.voice?.pendingModel)
        assertEquals(fast.ref, store.state.currentModel)
    }

    @Test fun aCallOnADraftCreatesAnEmptySessionAndMovesOntoIt() = sessionTest { h ->
        val project = OpenCodeProject(id = "p", name = "project", directory = "/project")
        val creates = LockIsolated(listOf<Pair<String, String?>>())
        val created = CompletableDeferred<Unit>()
        val store = h.store(h.state { State.draft(server = server, draftProject = project, projects = listOf(project)) }) {
            this[DateClient] = DateClient.constant(date(1_000))
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                createSession = { _, directory, id ->
                    creates.append(directory to id)
                    created.await()
                    OpenCodeSession(id = id ?: "", serverTitle = "", projectID = "p", directory = directory, updatedAt = distantPast)
                },
                getSession = { _, id -> OpenCodeSession(id = id, serverTitle = "", projectID = "p", directory = "/project", updatedAt = distantPast) },
                transcript = { _, _, _ -> emptyList() },
                sessionFamily = { _, root -> OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet()) },
                runningShells = { _, _ -> emptyList() },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false
        store.send(Action.VoiceTapped)
        // The call starts at once, on the draft, while its session is made.
        assertEquals(VoiceWorkTarget(server = server, sessionID = null, directory = "/project"), store.state.voice?.target)
        assertEquals("New session", store.state.voice?.sessionTitle)
        assertEquals(true, store.state.callSessionID?.startsWith("ses_"))
        assertFalse("The call's session is being made there", store.state.canChooseProject)
        repeat(200) { if (creates.value.isEmpty()) settle(1) }
        assertEquals(listOf("/project"), creates.value.map { it.first })
        assertEquals("A client-minted ID, so a retried create can't make a second", store.state.callSessionID, creates.value.firstOrNull()?.second)

        // The model hears it's a new session, with no history to read yet.
        val instructions = LockIsolated<String?>(null)
        val events = Channel<VoiceCallEvent>(Channel.UNLIMITED)
        store.updateDependencies {
            this[VoiceCallClient] = callClient(start = { _, config -> instructions.setValue(config.instructions); events.receiveAsFlow() })
            this[VoiceWorkClient] = workClient(recentMessages = { _, _, _ -> emptyList() })
        }
        store.withDependencies { shared(SharedKeys.openAIAPIKey) }.set("sk-test")
        store.send(Action.voicePresented(VoiceFeature.Action.Start))
        repeat(200) { if (instructions.value == null) settle(1) }
        assertEquals(true, instructions.value?.contains("has not started yet"))

        val sessionID = store.state.callSessionID
        created.complete(Unit)
        store.receive(Action.CallSessionResponse::class)
        store.receive(Action.SessionCreated::class)
        store.receive(voice<VoiceFeature.Action.DraftSessionCreated>(), "voice.presented.draftSessionCreated")
        assertEquals(sessionID, store.state.session?.id)
        assertNull(store.state.callSessionID)
        assertEquals("The call moved onto it", sessionID, store.state.voice?.target?.sessionID)
        assertEquals("Not a move", sessionID, store.state.voice?.adoptedDraftSessionID)
        assertNull(store.state.voice?.lastMove)
        h.skipInFlightEffects()

        // Without a project there is nowhere to create it: pick one first, as for a first send.
        val unplaced = h.store(h.state { State.draft(server = server, draftProject = null, projects = listOf(project)) })
        unplaced.exhaustive = false
        unplaced.send(Action.VoiceTapped)
        assertNull(unplaced.state.voice)
        assertEquals(State.ActivePicker.project, unplaced.state.activePicker)
    }

    /**
     * A request the call makes before the screen's session lands creates
     * that same session, by its minted ID.
     */
    @Test fun aDraftCallsRequestCreatesTheScreensSession() = sessionTest { h ->
        val state = h.state {
            State.draft(server = server, draftProject = OpenCodeProject(id = "p", name = "project", directory = "/project"), projects = emptyList())
                .copy(voice = live(VoiceFeature.State(server, draftDirectory = "/project", model = null)).copy(draftSessionID = "ses_minted"))
        }
        val targets = LockIsolated(listOf<VoiceWorkTarget>())
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(date(0))
            this[VoiceWorkClient] = workClient(
                submit = { target, _ ->
                    targets.append(target)
                    awaitCancellation()
                },
            )
            this[VoiceCallClient] = callClient()
        }
        store.exhaustive = false
        store.send(delegate("c1", "Fix the upload test"))
        repeat(200) { if (targets.value.isEmpty()) settle(1) }
        assertEquals(listOf<String?>("ses_minted"), targets.value.map { it.newSessionID })
        h.skipInFlightEffects()
    }

    /**
     * When the session can't be created, the call stays on the draft (its
     * first request creates the session) and the project can change again.
     */
    @Test fun aCallWhoseSessionCantBeCreatedStaysOnTheDraft() = sessionTest { h ->
        val project = OpenCodeProject(id = "p", name = "project", directory = "/project")
        val store = h.store(h.state { State.draft(server = server, draftProject = project, projects = listOf(project)) }) {
            this[DateClient] = DateClient.constant(date(1_000))
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(createSession = { _, _, _ -> throw OpenCodeClientError("Offline") })
        }
        store.exhaustive = false
        store.send(Action.VoiceTapped)
        store.receive(Action.CallSessionResponse::class)
        assertNull(store.state.session)
        assertNull(store.state.callSessionID)
        assertTrue(store.state.canChooseProject)
        assertEquals(true, store.state.voice?.isOnDraft)
        h.skipInFlightEffects()
    }

    @Test fun firstRequestOnADraftCreatesTheSessionAndTheScreenAdoptsIt() = sessionTest { h ->
        val created = OpenCodeSession(id = "ses_new", serverTitle = "Upload test", projectID = "p", directory = "/project", updatedAt = distantPast)
        val state = h.state {
            State.draft(server = server, draftProject = OpenCodeProject(id = "p", name = "project", directory = "/project"), projects = emptyList())
                .copy(voice = live(VoiceFeature.State(server, draftDirectory = "/project", model = OpenCodeModelRef(id = "fixture-model", providerID = "fixture"))))
        }
        val targets = LockIsolated(listOf<VoiceWorkTarget>())
        val completed = LockIsolated(listOf<String>())
        val release = CompletableDeferred<Unit>()
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(date(0))
            this[VoiceWorkClient] = workClient(
                submit = { target, _ ->
                    targets.append(target)
                    release.await()
                    VoiceWorkAdmission(promptID = "msg_1", createdSession = created)
                },
                awaitReply = { _, sessionID, _ ->
                    assertEquals("ses_new", sessionID)
                    "On it."
                },
            )
            this[VoiceCallClient] = callClient(completeFunction = { _, output -> completed.append(output) })
            draftSessionClient(created)
        }
        store.exhaustive = false

        store.send(delegate("c1", "Fix the upload test"))
        // A second request while the first is still creating the session would make another.
        store.send(delegate("c2", "And the retry test"))
        repeat(50) { if (completed.value.isEmpty()) settle(1) }
        assertEquals(listOf(VoiceHandoff.failure("The new session is still starting. Say that again in a moment.")), completed.value)

        release.complete(Unit)
        store.receive(voice<VoiceFeature.Action.SessionStarted>(), "voice.presented.sessionStarted")
        store.receive(Action.SessionCreated::class)
        assertEquals("ses_new", store.state.session?.id)
        assertEquals("ses_new", store.state.voice?.target?.sessionID)
        assertEquals(listOf<String?>(null), targets.value.map { it.sessionID })
        assertEquals("/project", targets.value.firstOrNull()?.directory)
        assertEquals("fixture-model", targets.value.firstOrNull()?.model?.id)
        assertNull("Only the submission carries the model", store.state.voice?.target?.model)
        store.skipReceivedActions()
        h.skipInFlightEffects()
    }

    /**
     * The draft's model goes to the session the call's first request
     * creates once: applied, it doesn't wait for another send.
     */
    @Test fun aDraftCallsModelIsAppliedOnceToTheSessionItCreates() = sessionTest { h ->
        val created = OpenCodeSession(id = "ses_new", serverTitle = "Upload test", projectID = "p", directory = "/project", updatedAt = distantPast)
        val model = OpenCodeModelRef(id = "fixture-model", providerID = "fixture")
        val project = OpenCodeProject(id = "p", name = "project", directory = "/project")
        val state = h.state {
            State.draft(server = server, draftProject = project, projects = listOf(project)).copy(currentModel = model)
                .copy(voice = live(VoiceFeature.State(server, draftDirectory = "/project", model = model)))
        }
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(date(0))
            this[VoiceWorkClient] = workClient(
                submit = { _, _ -> VoiceWorkAdmission(promptID = "msg_1", createdSession = created) },
                awaitReply = { _, _, _ -> "On it." },
            )
            this[VoiceCallClient] = callClient()
            draftSessionClient(created)
        }
        store.exhaustive = false
        store.send(delegate("c1", "Fix the upload test"))
        store.receive(Action.SessionCreated::class)
        assertEquals(model, store.state.pendingModel)
        store.receive(voiceDelegate<VoiceFeature.DelegateAction.PendingModelApplied>(), "voice.presented.delegate.pendingModelApplied")
        assertNull("The next send doesn't apply it again", store.state.pendingModel)
        assertNull(store.state.voice?.pendingModel)
        store.skipReceivedActions()
        h.skipInFlightEffects()
    }

    /**
     * A refused model sends nothing: the call hears why, and the pick waits on the session it
     * created.
     */
    @Test fun aDraftCallsRefusedModelSendsNothing() = sessionTest { h ->
        val created = OpenCodeSession(id = "ses_new", serverTitle = "Upload test", projectID = "p", directory = "/project", updatedAt = distantPast)
        val model = OpenCodeModelRef(id = "fixture-model", providerID = "fixture")
        val calls = LockIsolated(listOf<String>())
        val admission = withTestDependencies({
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                createSession = { _, _, _ -> calls.append("create"); created },
                switchModel = { _, _, _ -> throw OpenCodeClientError("Unknown model", statusCode = 400) },
                sendDeliveredPrompt = { _, _, _, _ -> calls.append("prompt"); "msg_1" },
            )
        }) {
            VoiceWorkClient.liveValue.submit(VoiceWorkTarget(server = server, sessionID = null, directory = "/project", model = model), "Fix it")
        }
        assertEquals(listOf("create"), calls.value)
        assertEquals("ses_new", admission.createdSession?.id)
        assertNull(admission.promptID)
        assertEquals("Unknown model", admission.modelError?.message)

        val project = OpenCodeProject(id = "p", name = "project", directory = "/project")
        val state = h.state {
            State.draft(server = server, draftProject = project, projects = listOf(project)).copy(currentModel = model)
                .copy(voice = live(VoiceFeature.State(server, draftDirectory = "/project", model = model)))
        }
        val completed = LockIsolated(listOf<String>())
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(date(0))
            this[VoiceWorkClient] = workClient(submit = { _, _ -> admission })
            this[VoiceCallClient] = callClient(completeFunction = { _, output -> completed.append(output) })
            draftSessionClient(created)
        }
        store.exhaustive = false
        store.send(delegate("c1", "Fix the upload test"))
        store.receive(Action.SessionCreated::class)
        store.receive(voice<VoiceFeature.Action.WorkFailed>(), "voice.presented.workFailed")
        assertEquals("ses_new", store.state.session?.id)
        assertEquals("It waits for the next send", model, store.state.pendingModel)
        store.skipReceivedActions()
        h.skipInFlightEffects()
    }

    /**
     * The delegator's new session couldn't take the draft's model: its screen
     * shows the pick, waiting for the next send.
     */
    @Test fun aKeptPickWaitsOnTheSessionsScreen() = sessionTest { h ->
        val session = OpenCodeSession(id = "ses_new", serverTitle = "New", projectID = "p", directory = "/project", updatedAt = distantPast)
        val model = OpenCodeModelRef(id = "fixture-model", providerID = "fixture")
        val state = h.state { State.session(server = server, session = session, cachedItems = emptyList()).copy(voice = live(VoiceFeature.State(server, session))) }
        val store = h.store(state)
        store.exhaustive = false
        store.send(Action.voicePresented(VoiceFeature.Action.DelegatorKeptPick(sessionID = "ses_new", model = model)))
        store.receive(voiceDelegate<VoiceFeature.DelegateAction.PickWaiting>(), "voice.presented.delegate.pickWaiting")
        assertEquals(model, store.state.currentModel)
        assertEquals(model, store.state.pendingModel)
        assertEquals(model, store.state.voice?.pendingModel)
    }

    @Test fun aDraftsSessionIsNotAdoptedOnceItsRequestWasLetGo() = sessionTest { h ->
        val created = OpenCodeSession(id = "ses_new", serverTitle = "Upload test", projectID = "p", directory = "/project", updatedAt = distantPast)
        val project = OpenCodeProject(id = "p", name = "project", directory = "/project")
        val other = OpenCodeProject(id = "o", name = "other", directory = "/other")
        val state = h.state {
            State.draft(server = server, draftProject = project, projects = listOf(project, other))
                .copy(voice = live(VoiceFeature.State(server, draftDirectory = "/project", model = null)))
        }
        val release = CompletableDeferred<Unit>()
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(date(0))
            this[VoiceWorkClient] = workClient(
                submit = { _, _ ->
                    release.await()
                    VoiceWorkAdmission(promptID = "msg_1", createdSession = created)
                },
            )
            this[VoiceCallClient] = callClient()
            draftSessionClient(created)
        }
        store.exhaustive = false

        store.send(delegate("c1", "Fix the upload test"))
        // The draft moves to another project while its session is being created there.
        store.send(Action.ProjectPicked(other))
        release.complete(Unit)
        store.receive(voice<VoiceFeature.Action.SessionStarted>(), "voice.presented.sessionStarted")
        assertNull("The screen stays on the draft", store.state.session)
        assertEquals(true, store.state.voice?.isOnDraft)
        assertEquals("/other", store.state.voice?.target?.directory)
        store.skipReceivedActions()
        h.skipInFlightEffects()
    }
}
