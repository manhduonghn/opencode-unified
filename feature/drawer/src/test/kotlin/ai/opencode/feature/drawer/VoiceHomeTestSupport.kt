package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.store.TestStore
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.voice.RealtimeJSON
import ai.opencode.feature.voice.RealtimeSessionConfig
import ai.opencode.feature.voice.VoiceBackgroundWork
import ai.opencode.feature.voice.VoiceCallClient
import ai.opencode.feature.voice.VoiceDelegationCloseReason
import ai.opencode.feature.voice.VoiceCallEvent
import ai.opencode.feature.voice.VoiceDelegator
import ai.opencode.feature.voice.VoiceDelegatorClient
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceTranscriptTail
import ai.opencode.feature.voice.VoiceWorkAdmission
import ai.opencode.feature.voice.VoiceWorkClient
import ai.opencode.feature.voice.VoiceWorkTarget
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal object VoiceHomeTestSupport {
    /**
     * `runBlocking`, not `runTest`: the delegator's tools run on real dispatchers, so receive
     * timeouts must be real time. Effects left running are cancelled at the end.
     */
    fun voiceHomeTest(block: suspend CoroutineScope.(HomeFeatureTestSupport.Harness) -> Unit) = runBlocking {
        val harness = HomeFeatureTestSupport.Harness(this)
        try {
            block(harness)
        } finally {
            harness.skipInFlightEffects()
        }
    }

    /** Bounded. */
    suspend fun eventually(limit: Int = 2_000, condition: () -> Boolean) {
        var tries = 0
        while (!condition() && tries < limit) {
            yield()
            if (tries > 200) Thread.sleep(1)
            tries++
        }
    }

    fun VoiceFeature.State.live(): VoiceFeature.State = withPhase(VoiceFeature.State.Phase.Live)

    fun VoiceFeature.State.recording(role: VoiceTranscriptTail.Role, text: String, id: String? = null): VoiceFeature.State =
        copy(tail = tail.record(role, text, id).tail)

    fun HomeFeature.State.withCall(voice: VoiceFeature.State?): HomeFeature.State =
        copy(sessionDetail = sessionDetail!!.copy(voice = voice))

    fun arguments(text: String? = null): RealtimeJSON =
        JsonObject(if (text == null) emptyMap() else mapOf("text" to JsonPrimitive(text)))

    /** `.sessionDetail(.presented(.voice(.presented(action))))`. */
    fun voice(action: VoiceFeature.Action): HomeFeature.Action =
        HomeFeature.Action.detail(SessionFeature.Action.voicePresented(action))

    /** `.sessionDetail(.presented(.voice(.presented(.callEvent(event)))))`. */
    fun voiceEvent(event: VoiceCallEvent): HomeFeature.Action = voice(VoiceFeature.Action.CallEvent(event))

    suspend fun <T : VoiceFeature.Action> TestStore<HomeFeature.State, HomeFeature.Action>.receiveVoice(
        case: KClass<T>,
        timeout: Duration = 5.seconds,
    ) = with(HomeFeatureTestSupport) {
        receiveBounded({ action -> action.voiceAction?.let(case::isInstance) == true }, "voice.${case.simpleName}", timeout)
    }

    fun delegator(respond: suspend (String, List<VoiceDelegator.InputItem>, String?) -> VoiceDelegator.Response) =
        VoiceDelegatorClient(isEnabled = true, respond = respond)

    fun response(id: String, calls: List<VoiceDelegator.ToolCall> = emptyList(), text: String = "") =
        VoiceDelegator.Response(id = id, calls = calls, text = text)

    fun call(callID: String, name: String, arguments: String) = VoiceDelegator.ToolCall(callID = callID, name = name, arguments = arguments)

    /** A copy of the call client with some closures replaced. */
    fun VoiceCallClient.copy(
        start: suspend (String, RealtimeSessionConfig) -> Flow<VoiceCallEvent> = this.start,
        completeFunction: suspend (String, String) -> Unit = this.completeFunction,
        noteProgressActivity: suspend (String, String?) -> Unit = this.noteProgressActivity,
        stopProgressLine: suspend (String) -> Unit = this.stopProgressLine,
        announce: suspend (String?, List<String>, String) -> Unit = this.announce,
        cancelPendingAnnouncements: suspend (List<String>) -> Unit = this.cancelPendingAnnouncements,
        closeDelegation: suspend (String, VoiceDelegationCloseReason) -> Unit = this.closeDelegation,
        retarget: suspend () -> Unit = this.retarget,
        setMuted: suspend (Boolean) -> Unit = this.setMuted,
        setAtEar: suspend (Boolean) -> Unit = this.setAtEar,
        updateInstructions: suspend (String, String?) -> Unit = this.updateInstructions,
        end: suspend () -> Unit = this.end,
        delegatorRound: suspend (String) -> Result<VoiceDelegator.Response> = this.delegatorRound,
        submitDelegatorInput: suspend (String, List<VoiceDelegator.InputItem>) -> Boolean = this.submitDelegatorInput,
        updateDelegatorInstructions: suspend (String) -> Unit = this.updateDelegatorInstructions,
    ) = VoiceCallClient(
        start, completeFunction, noteProgressActivity, stopProgressLine, announce, cancelPendingAnnouncements, closeDelegation,
        retarget, setMuted, setAtEar, updateInstructions, end, delegatorRound, submitDelegatorInput, updateDelegatorInstructions,
    )

    /** A copy of the work client with some closures replaced. */
    fun VoiceWorkClient.copy(
        submit: suspend (VoiceWorkTarget, String) -> VoiceWorkAdmission = this.submit,
        awaitReply: suspend (OpenCodeServer, String, String) -> String? = this.awaitReply,
        awaitIdle: suspend (OpenCodeServer, String) -> Unit = this.awaitIdle,
        fetchReply: suspend (OpenCodeServer, String, String) -> String? = this.fetchReply,
        fetchRunReply: suspend (OpenCodeServer, String, String) -> String? = this.fetchRunReply,
        backgroundWork: suspend (OpenCodeServer, String, String, String?) -> List<VoiceBackgroundWork> = this.backgroundWork,
        backgroundReply: suspend (OpenCodeServer, String) -> String? = this.backgroundReply,
        checkSessionActive: suspend (OpenCodeServer, String) -> Boolean? = this.checkSessionActive,
        activeSessionIDs: suspend (OpenCodeServer) -> Set<String> = this.activeSessionIDs,
        noteSessionSettled: suspend (OpenCodeServer, String) -> Unit = this.noteSessionSettled,
        noteActiveSessions: suspend (OpenCodeServer, Set<String>) -> Unit = this.noteActiveSessions,
        recentMessages: suspend (OpenCodeServer, String, Int) -> List<String> = this.recentMessages,
    ) = VoiceWorkClient(
        submit = submit,
        awaitReply = awaitReply,
        awaitIdle = awaitIdle,
        fetchReply = fetchReply,
        fetchRunReply = fetchRunReply,
        backgroundWork = backgroundWork,
        backgroundReply = backgroundReply,
        checkSessionActive = checkSessionActive,
        activeSessionIDs = activeSessionIDs,
        noteSessionSettled = noteSessionSettled,
        noteActiveSessions = noteActiveSessions,
        recentMessages = recentMessages,
    )

    fun DependencyValues.Builder.voiceCall(update: VoiceCallClient.() -> VoiceCallClient) {
        this[VoiceCallClient] = this[VoiceCallClient].update()
    }

    fun DependencyValues.Builder.voiceWork(update: VoiceWorkClient.() -> VoiceWorkClient) {
        this[VoiceWorkClient] = this[VoiceWorkClient].update()
    }

    /**
     * The reads a session screen makes when it opens, answering [getSession] for the session ID.
     */
    fun OpenCodeClient.openingScreens(getSession: (String) -> OpenCodeSession): OpenCodeClient = copy(
        getSession = { _, id -> getSession(id) },
        transcript = { _, _, _ -> emptyList() },
        sessionFamily = { _, root -> OpenCodeSessionFamily(listOf(root), emptySet()) },
        runningShells = { _, _ -> emptyList() },
        modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
        familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
        pendingInputs = { _, _ -> emptyList() },
    )
}
