package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.store.TestStore
import ai.opencode.feature.composer.ComposerFileAttachment
import ai.opencode.feature.composer.PendingPrompt
import ai.opencode.feature.composer.PromptOutbox
import ai.opencode.feature.session.FailedSendsSupport.finishLikeTCA
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.distantPast
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.voice.DotScopeSimulation
import ai.opencode.feature.voice.VoiceAdmissionRecorder
import ai.opencode.feature.voice.VoiceCallClient
import ai.opencode.feature.voice.VoiceCallEvent
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceHandoff
import ai.opencode.feature.voice.VoiceWorkAdmission
import ai.opencode.feature.voice.VoiceWorkClient
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

/**
 * A voice call hosted by its session, both driven by their real reducers. Every server read is
 * stubbed and delegated work resolves only on cue.
 */
class SessionVoiceTests {
    private val server = OpenCodeServer(url = "http://voice.invalid", username = "", password = "")
    private val root = OpenCodeSession(id = "ses_root", serverTitle = "Root", projectID = "p", directory = "/project", updatedAt = distantPast)
    private val child = OpenCodeSession(id = "ses_child", serverTitle = "Child", projectID = "p", directory = "/project", updatedAt = distantPast, parentID = "ses_root")

    /** Answers that resolve (or fail) only when the test says so, by key. */
    private class Cue<Value>(keys: List<String>) {
        private val channels: Map<String, Channel<Result<Value>>> = keys.associateWith { Channel(Channel.UNLIMITED) }

        suspend fun wait(key: String): Value {
            val channel = channels[key] ?: throw OpenCodeClientError("No answer for $key")
            return channel.receive().getOrThrow()
        }

        fun resolve(key: String, value: Value) {
            channels[key]?.trySend(Result.success(value))
        }

        fun fail(key: String, message: String) {
            channels[key]?.trySend(Result.failure(OpenCodeClientError(message)))
        }
    }

    private class VoiceHarness(vararg keys: String) {
        val admissions = Cue<String>(keys.toList())
        val replies = Cue<String?>(keys.map { "msg_$it" })
        val typedIdle = Cue<Unit>(listOf("typed"))
        val submissions = LockIsolated(mapOf<String, String?>())
        val completed = LockIsolated(mapOf<String, String>())
        val callEnds = LockIsolated(0)
    }

    private fun Harness.makeStore(state: State, harness: VoiceHarness): TestStore<State, Action> {
        val root = root
        val child = child
        val store = store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            val outbox = PromptOutbox()
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
            this[VoiceWorkClient] = VoiceWorkClient(
                submit = { target, text ->
                    val key = instruction(text)
                    harness.submissions.update { it + (key to target.sessionID) }
                    VoiceWorkAdmission(promptID = harness.admissions.wait(key))
                },
                awaitReply = { _, _, promptID -> harness.replies.wait(promptID) },
                checkSessionActive = { _, _ -> false },
                recentMessages = { _, _, _ -> emptyList() },
            )
            this[VoiceCallClient] = VoiceCallClient(
                start = { _, _ -> emptyFlow() },
                completeFunction = { callID, output -> harness.completed.update { it + (callID to output) } },
                closeDelegation = { _, _ -> },
                setMuted = { },
                updateInstructions = { _, _ -> },
                end = { harness.callEnds.increment() },
            )
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, id -> if (id == child.id) child else root },
                transcript = { _, _, _ -> emptyList() },
                sessionFamily = { _, _ -> never() },
                runningShells = { _, _ -> never() },
                modelCatalog = { _, _ -> never() },
                message = { _, _, _ -> throw OpenCodeClientError("Missing", statusCode = 404) },
                sendPrompt = { _, _, _, _, _, _, _ -> },
                waitForIdle = { _, _ -> harness.typedIdle.wait("typed") },
                interrupt = { _, _ -> throw AssertionError("Voice must never interrupt the session's run") },
                // Request and inbox polls are not under test; hold them silently open.
                familyPendingRequests = { _, _, _ -> never() },
                pendingInputs = { _, _ -> never() },
            )
        }
        store.exhaustive = false
        return store
    }

    private fun Harness.hosting(session: OpenCodeSession): State = state {
        State.session(server = server, session = session, cachedItems = emptyList())
            .copy(voice = VoiceFeature.State(server, session).withPhase(VoiceFeature.State.Phase.Live))
    }

    private suspend fun delegate(key: String, store: TestStore<State, Action>) {
        store.send(
            Action.voicePresented(
                VoiceFeature.Action.CallEvent(
                    VoiceCallEvent.FunctionCall(callID = "call_$key", name = "delegate", arguments = JsonObject(mapOf("text" to JsonPrimitive(key)))),
                ),
            ),
        )
    }

    private suspend fun admit(key: String, store: TestStore<State, Action>, harness: VoiceHarness) {
        delegate(key, store)
        harness.admissions.resolve(key, "msg_$key")
        store.receive(voice<VoiceFeature.Action.WorkAdmitted>(), "voice.presented.workAdmitted")
        store.receive(Action.RefreshResponse::class)
    }

    private inline fun <reified T : VoiceFeature.Action> voice(): (Action) -> Boolean = { action ->
        val presented = ((action as? Action.Voice)?.action as? ai.opencode.core.store.PresentationAction.Presented)?.action
        presented is T
    }

    private val voiceEnded: (Action) -> Boolean = { action ->
        val presented = ((action as? Action.Voice)?.action as? ai.opencode.core.store.PresentationAction.Presented)?.action
        presented is VoiceFeature.Action.Delegate && presented.action == VoiceFeature.DelegateAction.Ended
    }

    private fun State.withVoice(transform: (VoiceFeature.State) -> VoiceFeature.State): State = copy(voice = voice?.let(transform))

    /**
     * Host reactions to a voice action are delivered synchronously with it,
     * so once they are applied, a missing refresh or mirror is truly absent.
     */
    private suspend fun applyReactions(store: TestStore<State, Action>) = store.skipReceivedActions()

    @Test fun admittedVoiceWorkRunsTheSessionUntilItsReply() = sessionTest { h ->
        val harness = VoiceHarness("alpha")
        val store = h.makeStore(h.hosting(root), harness)

        delegate("alpha", store)
        assertEquals(1, store.state.voice?.pendingWork?.size)
        assertFalse("A delegation the server has not admitted is not a run", store.state.isRunning)

        harness.admissions.resolve("alpha", "msg_alpha")
        store.receive(voice<VoiceFeature.Action.WorkAdmitted>(), "workAdmitted") {
            it.withVoice { voice -> voice.copy(pendingWork = voice.pendingWork.mapIndexed { i, work -> if (i == 0) work.copy(promptID = "msg_alpha") else work }) }
        }
        assertTrue(store.state.isRunning)
        assertFalse("Voice work must not borrow the typed send's flag", store.state.isAwaitingReply)
        store.receive(Action.RefreshRequested(RefreshReason.RunState))
        store.receive(Action.RefreshResponse::class)

        harness.replies.resolve("msg_alpha", "Fixed the test.")
        store.receive(voice<VoiceFeature.Action.WorkReplied>(), "workReplied") { it.withVoice { voice -> voice.copy(pendingWork = emptyList()) } }
        assertFalse(store.state.isRunning)
        store.receive(Action.RefreshRequested(RefreshReason.RunState))
        store.receive(Action.RefreshResponse::class)
        assertEquals(mapOf("call_alpha" to VoiceHandoff.completion("Fixed the test.")), harness.completed.value)
        assertEquals(mapOf<String, String?>("alpha" to root.id), harness.submissions.value)
        store.finishLikeTCA()
    }

    @Test fun failedVoiceWorkReleasesOnlyTheRunItAdmitted() = sessionTest { h ->
        val harness = VoiceHarness("alpha", "beta")
        val store = h.makeStore(h.hosting(root), harness)

        delegate("alpha", store)
        harness.admissions.fail("alpha", "HTTP 503")
        store.receive(voice<VoiceFeature.Action.WorkFailed>(), "workFailed") { it.withVoice { voice -> voice.copy(pendingWork = emptyList()) } }
        applyReactions(store)
        assertFalse(store.state.isRunning)
        assertEquals("Nothing was admitted, so nothing needs re-reading", 0, store.state.nextRefreshID)
        assertEquals(VoiceHandoff.failure("HTTP 503"), harness.completed.value["call_alpha"])

        admit("beta", store, harness)
        assertTrue(store.state.isRunning)

        harness.replies.fail("msg_beta", "Run failed: the tool crashed")
        store.receive(voice<VoiceFeature.Action.WorkFailed>(), "workFailed") { it.withVoice { voice -> voice.copy(pendingWork = emptyList()) } }
        assertFalse(store.state.isRunning)
        store.receive(Action.RefreshRequested(RefreshReason.RunState))
        store.receive(Action.RefreshResponse::class)
        assertEquals(VoiceHandoff.failure("Run failed: the tool crashed"), harness.completed.value["call_beta"])
        assertEquals(VoiceFeature.State.Phase.Live, store.state.voice?.phase)
        store.finishLikeTCA()
    }

    @Test fun voiceRepliesKeepTheirOwnershipAndServerIdleSettlesTheTypedSend() = sessionTest { h ->
        val harness = VoiceHarness("alpha", "beta")
        val state = h.hosting(root).withDraft("Also update the docs")
        val store = h.makeStore(state, harness)

        // A typed send owns the run first.
        store.send(Action.SendTapped) { it.copy(isAwaitingReply = true) }
        store.receive(Action.PromptAdmitted::class)

        store.send(Action.ExternalRunStateChanged(isRunning = true))
        store.receive(Action.RefreshResponse::class)

        admit("alpha", store, harness)
        admit("beta", store, harness)
        assertEquals(listOf("msg_alpha", "msg_beta"), store.state.voice?.admittedPromptIDs)
        assertEquals(listOf("msg_alpha", "msg_beta"), store.state.queuedInputs.map { it.id })
        assertFalse("Visible voice admissions must not take ownership of the typed send", store.state.hasPendingLocalPrompt)

        harness.replies.resolve("msg_alpha", "Alpha done.")
        store.receive(voice<VoiceFeature.Action.WorkReplied>(), "workReplied")
        assertEquals(listOf("msg_beta"), store.state.voice?.admittedPromptIDs)
        assertTrue(store.state.isRunning)
        store.receive(Action.RefreshResponse::class)

        harness.replies.resolve("msg_beta", "Beta done.")
        store.receive(voice<VoiceFeature.Action.WorkReplied>(), "workReplied")
        assertEquals(emptyList<String>(), store.state.voice?.admittedPromptIDs)
        assertTrue("Voice finishing must not settle the typed send", store.state.isAwaitingReply)
        assertTrue("Voice finishing must not settle the server's run", store.state.isExternallyActive)
        assertTrue(store.state.isRunning)
        store.receive(Action.RefreshResponse::class)
        applyReactions(store)

        store.send(Action.ExternalRunStateChanged(isRunning = false))
        assertFalse("Server idle settles an admitted typed send even while its wait request is outstanding", store.state.isRunning)
        assertEquals(
            "Settling the run must not discard rows still waiting for their transcript echoes",
            listOf("msg_alpha", "msg_beta"), store.state.queuedInputs.map { it.id },
        )
        store.receive(Action.RefreshResponse::class)

        // TCA has already reduced the synchronous sends of the actions received so far (here the
        // read's follow-up); Android's TestStore reduces them on the next receive.
        applyReactions(store)
        val refreshes = store.state.nextRefreshID
        harness.typedIdle.resolve("typed", Unit)
        store.receive(Action.ReplyCompleted::class)
        assertEquals(refreshes, store.state.nextRefreshID)
        assertFalse(store.state.isRunning)
        assertEquals(listOf("call_alpha", "call_beta"), harness.completed.value.keys.sorted())
        store.finishLikeTCA()
    }

    @Test fun hangingUpAbandonsVoiceWorkButNotTheServersRun() = sessionTest { h ->
        val harness = VoiceHarness("alpha")
        val store = h.makeStore(h.hosting(root), harness)

        admit("alpha", store, harness)
        store.send(Action.ExternalRunStateChanged(isRunning = true))
        store.receive(Action.RefreshResponse::class)

        store.send(Action.VoiceTapped)
        store.receive(voice<VoiceFeature.Action.EndTapped>(), "endTapped") {
            it.withVoice { voice -> voice.withPhase(VoiceFeature.State.Phase.Ended).copy(pendingWork = emptyList()) }
        }
        store.receive(voiceEnded, "delegate.ended")
        assertTrue("The server still owns the run the call handed off", store.state.isRunning)
        assertFalse(store.state.isAwaitingReply)
        store.receive(Action.RefreshRequested(RefreshReason.RunState))
        store.receive(Action.RefreshResponse::class)
        // The bar stays while the scope sweeps out, then the composer returns.
        store.receive(Action.VoiceExitFinished) { it.copy(voice = null) }

        // The abandoned wait was cancelled, so a late answer is never spoken.
        harness.replies.resolve("msg_alpha", "Too late.")
        store.send(Action.ExternalRunStateChanged(isRunning = false)) { it.copy(isExternallyActive = false) }
        assertFalse(store.state.isRunning)
        store.finishLikeTCA()
        assertTrue(harness.completed.value.isEmpty())
        assertEquals(1, harness.callEnds.value)
    }

    @Test fun anEndedCallSweepsOutBeforeTheComposerReturns() = sessionTest { h ->
        val clock = TestClock()
        val state = h.hosting(root)
        val store = h.store(state) {
            this[Clock] = clock
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            this[VoiceCallClient] = VoiceCallClient(
                start = { _, _ -> emptyFlow() },
                completeFunction = { _, _ -> },
                closeDelegation = { _, _ -> },
                setMuted = { },
                updateInstructions = { _, _ -> },
                end = {},
            )
        }
        store.exhaustive = false

        store.send(Action.voicePresented(VoiceFeature.Action.EndTapped))
        store.receive(voiceEnded, "delegate.ended")
        assertEquals("The bar stays while the scope sweeps out", VoiceFeature.State.Phase.Ended, store.state.voice?.phase)

        // Taps during the sweep change nothing and don't restart it.
        store.send(Action.voicePresented(VoiceFeature.Action.EndTapped))

        clock.advance(((DotScopeSimulation.exitDuration - 0.1) * 1000).toLong().milliseconds)
        assertNotNull(store.state.voice)
        clock.advance(100.milliseconds)
        store.receive(Action.VoiceExitFinished)
        assertNull(store.state.voice)
        store.finishLikeTCA()
    }

    @Test fun failedCallKeepsItsBarButReleasesTheRun() = sessionTest { h ->
        val harness = VoiceHarness("alpha")
        val store = h.makeStore(h.hosting(root), harness)

        admit("alpha", store, harness)
        assertTrue(store.state.isRunning)

        store.send(Action.voicePresented(VoiceFeature.Action.CallEnded(errorMessage = "socket dropped"))) {
            it.withVoice { voice -> voice.withPhase(VoiceFeature.State.Phase.Failed("socket dropped")).copy(pendingWork = emptyList()) }
        }
        assertNotNull("A failed call stays up until dismissed", store.state.voice)
        assertFalse(store.state.isRunning)
        store.receive(Action.RefreshRequested(RefreshReason.RunState))
        store.receive(Action.RefreshResponse::class)

        store.send(Action.voicePresented(VoiceFeature.Action.EndTapped))
        // A failure has no bar to sweep out: it goes at once.
        store.receive(voiceEnded, "delegate.ended") { it.copy(voice = null) }
        store.finishLikeTCA()
        assertTrue(harness.completed.value.isEmpty())
        assertEquals("A failed call has no live connection left to end", 0, harness.callEnds.value)
    }

    @Test fun voicePinnedToRootNeverLeaksIntoASelectedChild() = sessionTest { h ->
        val harness = VoiceHarness("alpha", "beta")
        val state = h.hosting(root).copy(
            familySessions = listOf(root, child),
            activeFamilySessionIDs = setOf(child.id),
            familyTranscriptCache = mapOf(child.id to listOf(OpenCodeTranscriptItem.UserText(id = "child", text = "Child"))),
        )
        val store = h.makeStore(state, harness)

        admit("alpha", store, harness)
        assertTrue(store.state.isRunning)

        store.send(Action.FamilyPicked(child.id)) { it.copy(session = child, isExternallyActive = true) }
        store.receive(Action.RefreshResponse::class)
        assertEquals(root.id, store.state.voice?.target?.sessionID)

        // The child going idle says nothing about the call's root.
        store.send(Action.FamilyRunStateChanged(sessionID = child.id, isRunning = false))
        store.receive(Action.ExternalRunStateChanged::class) { it.copy(isExternallyActive = false) }
        assertFalse(store.state.isRunning)
        store.receive(Action.RefreshResponse::class)
        applyReactions(store)

        val refreshes = store.state.nextRefreshID
        delegate("beta", store)
        harness.admissions.resolve("beta", "msg_beta")
        store.receive(voice<VoiceFeature.Action.WorkAdmitted>(), "workAdmitted")
        applyReactions(store)
        assertEquals(root.id, harness.submissions.value["beta"])
        assertFalse("The root's voice work is not the child's run", store.state.isRunning)

        harness.replies.resolve("msg_alpha", "Alpha done.")
        store.receive(voice<VoiceFeature.Action.WorkReplied>(), "workReplied")
        applyReactions(store)
        assertFalse(store.state.isRunning)
        assertEquals("Root voice work must not refresh the selected child", refreshes, store.state.nextRefreshID)

        store.send(Action.FamilyPicked(root.id)) { it.copy(session = root) }
        assertTrue("Back on the root, its outstanding voice work runs it", store.state.isRunning)
        store.receive(Action.RefreshResponse::class)

        harness.replies.resolve("msg_beta", "Beta done.")
        store.receive(voice<VoiceFeature.Action.WorkReplied>(), "workReplied")
        assertFalse(store.state.isRunning)
        store.receive(Action.RefreshRequested(RefreshReason.RunState))
        store.receive(Action.RefreshResponse::class)
        h.skipInFlightEffects()
    }

    private companion object {
        private val instructionPattern = Regex("\"instruction\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

        /** The delegation's own words. */
        fun instruction(text: String): String =
            VoiceHandoff.parse(text)?.instruction ?: instructionPattern.find(text)?.groupValues?.get(1) ?: ""

        suspend fun <T> never(): T = awaitCancellation()
    }
}
