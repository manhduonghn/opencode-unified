package ai.opencode.feature.voice

import ai.opencode.core.dependencies.*
import ai.opencode.server.OpenCodeClientError
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Internal host test seam, with no UI or release entry point. */
internal interface VoiceCallTestingCoordinator {
    suspend fun checkIsFinished(): Boolean
    suspend fun setAtEar(atEar: Boolean)
    suspend fun end()
}

/** The single owner of the call's coordinator. A newer start/end invalidates
 * every older start, including one still tearing down its predecessor. */
class VoiceCallHost {
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private var current: VoiceCallTestingCoordinator? = null
    private val coordinator get() = current as? VoiceSessionCoordinator<*, *, *>
    private var startingAttemptID: UUID? = null
    private var nextAtEarSequence = 0
    private var latestAppliedAtEarSequence = 0
    private data class PendingAtEar(val attemptID: UUID, val sequence: Int, val value: Boolean)
    private var pendingAtEar: PendingAtEar? = null

    internal suspend fun installForTesting(coordinator: VoiceCallTestingCoordinator) = withContext(dispatcher) { current = coordinator }

    suspend fun start(apiKey: String, config: RealtimeSessionConfig, realtime: RealtimeClient, live: LiveClient,
        gemini: GeminiLiveClient = GeminiLiveClient.testValue, audio: VoiceAudioClient): Flow<VoiceCallEvent> = withContext(dispatcher) {
        val attemptID = dependency(UuidClient).next()
        startingAttemptID = attemptID; pendingAtEar = null
        endCurrent()
        if (startingAttemptID != attemptID) throw CancellationException()
        val initial = pendingAtEar?.takeIf { it.attemptID == attemptID }?.let { config.copy(isAtEar = it.value) } ?: config
        pendingAtEar = null; startingAttemptID = null
        if (initial.provider == ServerVoiceProvider.geminiLive) {
            val coordinator = GeminiCallCoordinator()
            launch(coordinator) { coordinator.start(apiKey, GeminiLiveSessionConfig.from(initial), gemini, audio) }
        } else when (initial.engine) {
            VoiceEngine.gptLive -> {
                var timing = VoiceCallTiming.live
                if (initial.unboundSpeechUsesInstructions) {
                    timing = timing.copy(liveUnbackedClaimWait = VoiceCallTiming.sessionDelegatorUnbackedClaimWait)
                }
                val coordinator = LiveCallCoordinator(timing = timing)
                launch(coordinator) { coordinator.start(apiKey, initial.liveSessionConfig(), live, audio) }
            }
            VoiceEngine.realtime, VoiceEngine.grok -> {
                val coordinator = VoiceCallCoordinator()
                launch(coordinator) { coordinator.start(apiKey, initial, realtime, audio) }
            }
            null -> throw ServerVoiceError.InvalidResponse
        }
    }
    private suspend fun launch(coordinator: VoiceSessionCoordinator<*, *, *>, start: suspend () -> Flow<VoiceCallEvent>): Flow<VoiceCallEvent> {
        current = coordinator
        try { return start() }
        catch (error: Throwable) {
            if (current === coordinator) current = null
            pendingAtEar = null
            withContext(NonCancellable) { coordinator.end() }
            throw error
        }
    }
    suspend fun completeFunction(callID: String, output: String) = withContext(dispatcher) { coordinator?.completeFunction(callID, output); Unit }
    suspend fun noteProgressActivity(callID: String, activity: String?) = withContext(dispatcher) { (current as? LiveCallCoordinator)?.noteProgressActivity(callID, activity); Unit }
    suspend fun stopProgressLine(callID: String) = withContext(dispatcher) { (current as? LiveCallCoordinator)?.stopProgressLine(callID); Unit }
    suspend fun announce(text: String, sessionID: String? = null, promptIDs: List<String> = emptyList()) = withContext(dispatcher) {
        when (val coordinator = current) {
            is LiveCallCoordinator -> coordinator.announce(text, sessionID, promptIDs)
            is GeminiCallCoordinator -> coordinator.announce(text)
            else -> Unit
        }
    }
    suspend fun cancelPendingAnnouncements(promptIDs: List<String>) = withContext(dispatcher) { (current as? LiveCallCoordinator)?.cancelPendingAnnouncements(promptIDs); Unit }
    suspend fun closeDelegation(callID: String, reason: VoiceDelegationCloseReason) = withContext(dispatcher) { coordinator?.closeDelegation(callID, reason); Unit }
    suspend fun retarget() = withContext(dispatcher) { coordinator?.retarget(); Unit }
    suspend fun setMuted(muted: Boolean) = withContext(dispatcher) { coordinator?.setMuted(muted); Unit }
    suspend fun setAtEar(atEar: Boolean) = withContext(dispatcher) {
        val sequence = ++nextAtEarSequence
        val entryAttemptID = startingAttemptID
        val entryActive = current
        if (entryActive != null) {
            if (entryActive.checkIsFinished()) { if (current === entryActive) current = null }
            else {
                if (sequence <= latestAppliedAtEarSequence || current !== entryActive) return@withContext
                latestAppliedAtEarSequence = sequence
                entryActive.setAtEar(atEar)
                return@withContext
            }
        }
        if (entryAttemptID != null && startingAttemptID == entryAttemptID && sequence > (pendingAtEar?.sequence ?: 0)) {
            pendingAtEar = PendingAtEar(entryAttemptID, sequence, atEar)
        }
    }
    suspend fun updateInstructions(full: String, delta: String?) = withContext(dispatcher) { coordinator?.updateInstructions(full, delta); Unit }
    suspend fun delegatorRound(id: String): Result<VoiceDelegator.Response> = withContext(dispatcher) {
        (current as? LiveCallCoordinator)?.delegatorRound(id) ?: Result.failure(OpenCodeClientError("The voice call ended."))
    }
    suspend fun submitDelegatorInput(id: String, input: List<VoiceDelegator.InputItem>): Boolean = withContext(dispatcher) {
        (current as? LiveCallCoordinator)?.submitDelegatorInput(id, input) ?: false
    }
    suspend fun nextUserWords() {
        val coordinator = withContext(dispatcher) { current as? LiveCallCoordinator }
        if (coordinator != null) coordinator.nextUserWords() else VoiceCallClient.untilCancelled()
    }
    suspend fun updateDelegatorInstructions(instructions: String) = withContext(dispatcher) { (current as? LiveCallCoordinator)?.updateDelegatorInstructions(instructions); Unit }
    suspend fun end() = withContext(dispatcher) { startingAttemptID = null; pendingAtEar = null; endCurrent() }
    private suspend fun endCurrent() {
        val active = current ?: return
        active.end()
        if (current === active) current = null
    }
}
