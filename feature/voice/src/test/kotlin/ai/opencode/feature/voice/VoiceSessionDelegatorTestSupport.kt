package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.experimental
import ai.opencode.core.store.TestStore
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerIDClient
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.CoroutineScope
import java.time.Instant

internal object VoiceSessionDelegatorTestSupport {
    val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    val otherServer = OpenCodeServer(url = "http://other.example.ts.net:4096", username = "", password = "")
    val session = OpenCodeSession(
        id = "ses_view",
        serverTitle = "Session View",
        projectID = "proj_1",
        directory = "/Users/alex/project",
        updatedAt = Instant.ofEpochSecond(100),
    )

    /** Ascending IDs are messages, descending ones sessions. */
    fun mockServerID(session: () -> String, message: () -> String) = ServerIDClient(
        ascending = { message() },
        descending = { session() },
    )

    /**
     * Each test's in-memory shared store starts from these values, so nothing needs restoring
     * afterwards.
     */
    fun preserveSettings(): VoiceTestShared {
        val shared = VoiceTestShared()
        shared.set(SharedKeys.voiceModeEnabled, true)
        shared.set(VoiceSharedKeys.voiceEngine, VoiceEngine.gptLive)
        shared.set(SharedKeys.openAIAPIKey, null)
        shared.set(SharedKeys.xaiAPIKey, null)
        shared.set(VoiceSharedKeys.serverVoiceProvider, ServerVoiceProvider.openAI)
        shared.set(VoiceSharedKeys.voiceServerChoice, VoiceServerChoice.Automatic)
        shared.set(VoiceSharedKeys.serverVoiceSupportByServer, emptyMap())
        shared.set(VoiceSharedKeys.voiceModelsByServer, emptyMap())
        shared.set(experimental(ExperimentalFeature.voiceSessionDelegator), false)
        shared.set(ServerSharedKeys.savedServer, null)
        shared.set(ServerSharedKeys.savedServers, emptyList())
        shared.set(SharedKeys.verifiedVoiceKeyFingerprint, null)
        shared.set(VoiceSharedKeys.voiceDelegatorChoice, VoiceDelegatorChoice.BuiltIn)
        shared.set(VoiceSharedKeys.voiceDelegatorSessionIDs, emptyMap())
        shared.set(VoiceSharedKeys.voiceTranscripts, emptyMap())
        shared.set(SharedKeys.pinnedSessionKeys, emptyList())
        return shared
    }

    fun liveDelegatorState(shared: VoiceTestShared, server: OpenCodeServer = this.server, session: OpenCodeSession = this.session): VoiceFeature.State =
        shared.read { VoiceFeature.State(server, session) }
            .withPhase(VoiceFeature.State.Phase.Live)
            .copy(usesSessionDelegator = true)

    fun store(
        shared: VoiceTestShared,
        state: VoiceFeature.State,
        effects: CoroutineScope,
        now: Long,
        dependencies: DependencyValues.Builder.() -> Unit = {},
    ): TestStore<VoiceFeature.State, VoiceFeature.Action> = TestStore(state, VoiceFeature(), effects) {
        this[SharedStore] = shared.store
        this[Clock] = ImmediateClock()
        this[DateClient] = DateClient.constant(Instant.ofEpochSecond(now))
        dependencies()
    }

    fun VoiceFeature.State.startingTurn(
        callID: String,
        sessionID: String?,
        admitting: Boolean,
        saidTurn: Int,
        provisionalSessionID: String? = null,
        activeSessionID: String? = sessionDelegator.activeSessionID,
    ): VoiceFeature.State {
        val acted = copy(replyWatch = replyWatch.noteUserActed())
        val captured = acted.tail.capture()
        return acted.copy(
            tail = captured.tail,
            sessionDelegator = acted.sessionDelegator.copy(
                activeSessionID = activeSessionID,
                provisionalSessionIDs = acted.sessionDelegator.provisionalSessionIDs + listOfNotNull(provisionalSessionID),
                turns = acted.sessionDelegator.turns + VoiceSessionDelegator.State.Turn(
                    callID = callID,
                    capture = captured.capture,
                    sessionID = sessionID,
                    provisionalSessionID = provisionalSessionID,
                    admitting = admitting,
                    saidTurn = saidTurn,
                ),
            ),
            delegatorWork = acted.delegatorWork + callID,
        ).stepping(ReplyWatch.Event.DelegatorStarted(requestID = callID))
    }

    fun VoiceFeature.State.hearing(itemID: String, text: String): VoiceFeature.State =
        copy(replyWatch = replyWatch.noteUserActed())
            .recording(VoiceTranscriptTail.Role.user, text, itemID)
            .copy(captions = listOf(VoiceFeature.State.Caption("user-$itemID", VoiceTranscriptTail.Role.user, text)))

    /** The first turn's admission as the reducer applies it. */
    fun VoiceFeature.State.admittingFirstTurn(
        sessionID: String,
        title: String,
        promptID: String,
        saidTurn: Int,
        contextLine: String?,
        now: Instant,
    ): VoiceFeature.State {
        val turn = sessionDelegator.turns[0]
        val delegator = sessionDelegator.copy(
            lastContextBySession = if (contextLine == null) {
                sessionDelegator.lastContextBySession
            } else {
                sessionDelegator.lastContextBySession + (sessionID to contextLine)
            },
            turns = listOf(turn.copy(admitting = false, title = title, promptID = promptID)) + sessionDelegator.turns.drop(1),
        )
        return copy(
            tail = tail.accept(turn.capture),
            sessionDelegator = delegator,
            handoffs = if (contextLine == null) handoffs else handoffs + (sessionID to VoiceFeature.State.Handoff(lastDelegationAt = now, compactionID = null)),
        )
            .stepping(ReplyWatch.Event.DelegatorWaiting(sessionID = sessionID, requestID = turn.callID, isWaiting = true))
            .stepping(ReplyWatch.Event.Sent(listOf(ReplyWatch.Prompt(promptID = promptID, sessionID = sessionID, title = title, saidTurn = saidTurn))))
    }

    fun VoiceFeature.State.settlingFirstTurn(sessionID: String, promptID: String, reply: String): VoiceFeature.State {
        val callID = sessionDelegator.turns[0].callID
        val remaining = sessionDelegator.turns.drop(1)
        return stepping(ReplyWatch.Event.WaitSucceeded(promptID = promptID, reply = reply))
            .copy(
                sessionDelegator = sessionDelegator.copy(
                    turns = remaining,
                    activeSessionID = if (remaining.isEmpty()) null else sessionDelegator.activeSessionID,
                ),
                delegatorWork = delegatorWork.filter { it != callID },
            )
            .stepping(ReplyWatch.Event.DelegatorAnswered(sessionID = sessionID, reply = reply, promptIDs = listOf(promptID), requestID = callID))
            .stepping(ReplyWatch.Event.DelegatorFinished(requestID = callID, spoken = reply))
    }

    /**
     * `.callPhaseChanged(.ended)`, no delegator work, unadmitted captures released into the tail,
     * transcript persisted (a shared write here) and `.ended`.
     */
    fun VoiceFeature.State.hungUp(): VoiceFeature.State {
        val ended = stepping(ReplyWatch.Event.CallPhaseChanged(ReplyWatch.CallPhase.ended))
        val (delegator, captures) = ended.sessionDelegator.resetForCallEnd()
        return ended.copy(
            delegatorWork = emptyList(),
            sessionDelegator = delegator,
            tail = captures.fold(ended.tail) { tail, capture -> tail.release(capture) },
        ).withPhase(VoiceFeature.State.Phase.Ended)
    }
}
