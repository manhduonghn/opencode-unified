package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.dependencies.withDependenciesSuspending
import ai.opencode.core.log.AppLog
import ai.opencode.server.OpenCodeClientError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

val voiceLog: AppLog = AppLog.voice

/** Why a delegation closed without its result, sent as its function output's `status`. */
enum class VoiceDelegationCloseReason(val rawValue: String) {
    superseded("superseded"),
    coveredByInterimSpeech("coveredByInterimSpeech"),
    movedSession("movedSession"),
}

/**
 * At most one call at a time, like dictation. Starting a call ends any
 * previous one; the control methods address the current call.
 */
class VoiceCallClient(
    /** The call's events until it ends: the flow completes, or throws why it dropped. */
    val start: suspend (apiKey: String, config: RealtimeSessionConfig) -> Flow<VoiceCallEvent>,
    val completeFunction: suspend (callID: String, output: String) -> Unit,
    /**
     * What a delegation's work is doing right now, for its spoken "still
     * working" line (`VoiceProgressLine`); null when no named tool is running.
     */
    val noteProgressActivity: suspend (callID: String, activity: String?) -> Unit = { _, _ -> },
    /**
     * No spoken "still working" line for this delegation any more (its
     * delegator gave its own status).
     */
    val stopProgressLine: suspend (callID: String) -> Unit = { },
    /**
     * Speaks news that belongs to no open delegation (work a delegation
     * started has finished since, or an interim line): `session.commentary.append`
     * with no ID.
     */
    val announce: suspend (sessionID: String?, promptIDs: List<String>, text: String) -> Unit = { _, _, _ -> },
    /**
     * Drops un-flushed announcements whose every prompt the delegator already
     * answered.
     */
    val cancelPendingAnnouncements: suspend (promptIDs: List<String>) -> Unit = { },
    /**
     * Closes an abandoned or superseded delegation cleanly on the wire without
     * triggering duplicate speech.
     */
    val closeDelegation: suspend (callID: String, reason: VoiceDelegationCloseReason) -> Unit = { _, _ -> },
    /**
     * The call moved to another session. Only the instructions that follow
     * change; nothing in flight is dropped or cut off.
     */
    val retarget: suspend () -> Unit = {},
    val setMuted: suspend (Boolean) -> Unit,
    /** Raise to Talk: route the call to the earpiece while at the ear. */
    val setAtEar: suspend (Boolean) -> Unit = { },
    /**
     * Replaces the live model's instructions (`full`, used for `session.update`
     * and future `session.start`s) and, on GPT-Live, appends a concise
     * retarget `delta` (`session.instructions.append`) when provided.
     */
    val updateInstructions: suspend (full: String, delta: String?) -> Unit,
    val end: suspend () -> Unit,
    /**
     * Server Voice: the next round of a delegation GPT-Live runs on its
     * Responses backend (its tool calls, or its answer). A failure is an [OpenCodeClientError].
     */
    val delegatorRound: suspend (delegationID: String) -> Result<VoiceDelegator.Response> = {
        Result.failure(OpenCodeClientError("The voice call has no delegator."))
    },
    /** Server Voice: tool results for that backend, then a request to continue. */
    val submitDelegatorInput: suspend (delegationID: String, input: List<VoiceDelegator.InputItem>) -> Boolean = { _, _ -> false },
    /** Server Voice: the backend delegator's new instructions, as the screen moves. */
    val updateDelegatorInstructions: suspend (instructions: String) -> Unit = { },
    /**
     * GPT-Live: returns when the user next says words, so a delegator wait
     * can end and free the voice agent to answer them. Otherwise it returns
     * only once cancelled.
     */
    val nextUserWords: suspend () -> Unit = { untilCancelled() },
) {
    suspend fun announce(text: String) = announce.invoke(null, emptyList(), text)

    suspend fun announce(sessionID: String?, promptIDs: List<String> = emptyList(), text: String) =
        announce.invoke(sessionID, promptIDs, text)

    val delegatorRounds: VoiceDelegatorRounds
        get() = VoiceDelegatorRounds(next = delegatorRound, submit = submitDelegatorInput)

    companion object Key : DependencyKey<VoiceCallClient> {
        suspend fun untilCancelled(): Unit = kotlinx.coroutines.awaitCancellation()

        override val liveValue: VoiceCallClient by lazy {
            if (VoiceLabDemo.isEnabled) VoiceLabDemo.call else {
                val host = VoiceCallHost()
                VoiceCallClient(
                    start = { apiKey, config ->
                        withDependenciesSuspending({ this[VoiceAudioLabHooks] = VoiceLabDemo.audioHooks }) {
                            host.start(apiKey, config, dependency(RealtimeClient), dependency(LiveClient), dependency(GeminiLiveClient), dependency(VoiceAudioClient))
                        }
                    },
                    completeFunction = host::completeFunction,
                    noteProgressActivity = host::noteProgressActivity,
                    stopProgressLine = host::stopProgressLine,
                    announce = { sessionID, promptIDs, text -> host.announce(text, sessionID, promptIDs) },
                    cancelPendingAnnouncements = host::cancelPendingAnnouncements,
                    closeDelegation = host::closeDelegation,
                    retarget = host::retarget,
                    setMuted = host::setMuted,
                    setAtEar = host::setAtEar,
                    updateInstructions = host::updateInstructions,
                    end = host::end,
                    delegatorRound = host::delegatorRound,
                    submitDelegatorInput = host::submitDelegatorInput,
                    updateDelegatorInstructions = host::updateDelegatorInstructions,
                    nextUserWords = host::nextUserWords,
                )
            }
        }

        override val testValue: VoiceCallClient get() = inert()

        private fun inert() = VoiceCallClient(
            start = { _, _ -> emptyFlow() },
            completeFunction = { _, _ -> },
            closeDelegation = { _, _ -> },
            setMuted = { },
            updateInstructions = { _, _ -> },
            end = {},
        )
    }
}

/**
 * When a call moves to a new voice session. Sessions end at their duration
 * limit, so a long call prepares a seeded replacement ahead of time and
 * swaps at a quiet moment; a dropped socket is replaced the same way.
 */
data class VoiceCallTiming(
    /** Open the replacement this long (seconds) after a session started. */
    val rotateAfter: Double = 55.0 * 60,
    /** Past this (seconds), swap even mid-turn rather than meet the hard limit. */
    val forceRotateAfter: Double = 58.0 * 60,
    /** Pauses (seconds) before each reconnect attempt after an unexpected close. */
    val retryDelays: List<Double> = listOf(0.5, 2.5, 6.0),
    /**
     * Brief window after `session.delegation.created` for the final input
     * transcript fragment to land before assembling the delegated prompt.
     */
    val liveDelegationSettle: Duration = 180.milliseconds,
    /**
     * Idle pause after the last `session.output_transcript.delta` before
     * settling an assistant caption when no audio buffers remain queued.
     */
    val liveTranscriptSettle: Duration = 450.milliseconds,
    /**
     * How long a settled delegation waits for GPT-Live's spoken acknowledgement
     * ("Checking the upload test.") to finish, to show it in the session as the
     * voice agent's framing. Measured against the live service
     * (`scripts/live-delegation-timing.ts`): the acknowledgement starts 0.1–0.6 s
     * after `session.delegation.created` and finishes 0.5–1.5 s after it, so a
     * 1 s wait past the settle catches about half; the rest go out with the
     * user's words as their label rather than holding the request longer.
     */
    val liveAcknowledgementWait: Duration = 1.seconds,
    /**
     * How long after the voice agent says it's acting ("Opening.") on a
     * request no delegation took the call waits for GPT-Live's delegation
     * before making it itself (`VoiceActionClaim`). The delegation comes
     * before its acknowledgement, so this only covers a late event. Zero: off
     * (the coordinator tests' default).
     */
    val liveUnbackedClaimWait: Duration = Duration.ZERO,
    /**
     * The spoken "still working" line while a delegation is out
     * (`VoiceProgressLine`). Zero `firstAfter`: off, the coordinator tests'
     * default.
     */
    val liveProgress: VoiceProgressLine.Timing = VoiceProgressLine.Timing(),
    /**
     * Realtime transcribes a turn after it ends, sometimes after the reply that
     * delegates it: how long a `delegate` call waits for the user's words.
     */
    val realtimeTranscriptWait: Duration = 1.seconds,
    /**
     * GPT-Live stops speaking the instant it receives any append
     * (instructions, thinking, or commentary), measured against gpt-live-1,
     * so appends wait until neither side has spoken for this long. Zero sends
     * at once (the coordinator tests' default).
     */
    val liveAppendQuiet: Duration = Duration.ZERO,
    /**
     * After the user speaks, a reply is coming even while it's quiet: appends
     * wait until it starts, or until this long passes without one.
     */
    val liveAppendReplyWait: Duration = Duration.ZERO,
    /**
     * Session context (instructions) has nothing to say, and cutting off the
     * reply the user is owed costs the turn: held alone, it waits for that
     * reply to start, up to this long after the user's last words.
     */
    val liveContextReplyHold: Duration = Duration.ZERO,
    /**
     * Maximum time an append may be held while assistant audio is not playing,
     * so continuous user fragments or background noise cannot hold a completion
     * notice forever.
     */
    val liveAppendMaxHold: Duration = Duration.ZERO,
    /**
     * Once the user has spoken since a delegation began, how long its answer
     * may wait behind the voice agent talking without it before it goes out
     * anyway (and stops that speech).
     */
    val liveAnswerPressedHold: Duration = Duration.ZERO,
    /**
     * A request's own last words can land just after its delegation; words
     * this long after it are the user asking again.
     */
    val liveAnswerPressedAfter: Duration = 1.seconds,
    /**
     * A result (an announcement, or a delegation's answer) waits for speech
     * in progress at most this long from when it was ready, then goes out
     * even if that cuts the speech off. Zero: no limit.
     */
    val liveResultMaxHold: Duration = Duration.ZERO,
    /**
     * The longer limit while the voice agent is speaking a delegation's
     * answer (or is about to), so a result never cuts the answer off.
     */
    val liveAnswerSpeakingHold: Duration = Duration.ZERO,
    /**
     * After a delegation's answer goes out, how long results wait at most for
     * the voice agent to start speaking it. An append before it starts can
     * replace it.
     */
    val liveAnswerStartWait: Duration = Duration.ZERO,
    /**
     * How long the user must have been quiet (no words transcribed and no
     * voice on the microphone) before anything held for them is sent. Zero:
     * a brief pause after their last transcript fragment (`liveAppendQuiet`,
     * at most 450 ms), the coordinator tests' default.
     */
    val liveUserQuietGap: Duration = Duration.ZERO,
    /**
     * Microphone level (`VoiceAudioLevels` scale, 0–1) at which a capture
     * chunk counts as the user's voice. Zero: the microphone isn't read.
     */
    val liveMicVoiceLevel: Float = 0f,
    /**
     * The microphone counts as the user talking only while their words were
     * transcribed this recently, so a noise with no words never holds updates.
     */
    val liveMicVoiceWordWindow: Duration = 6.seconds,
    /**
     * The user talking over the voice agent this long (their voice on the
     * microphone, with words transcribed over its speech) stops its audio.
     * Zero: off, full duplex.
     */
    val liveBargeIn: Duration = Duration.ZERO,
) {
    /** [rotationDeadlines]'s result. */
    data class RotationDeadlines(val prepareAt: Instant, val forceAt: Instant)

    fun rotationDeadlines(openedAt: Instant, expiresAt: Instant?, defaultLimit: Double): RotationDeadlines {
        val limit = expiresAt?.let { java.time.Duration.between(openedAt, it).toNanos() / 1e9 } ?: defaultLimit
        // Five minutes' warning to find a quiet moment, two to force it; a short
        // session (the lab's) keeps those margins in proportion.
        return RotationDeadlines(
            prepareAt = openedAt.plusSeconds(minOf(rotateAfter, limit - minOf(5.0 * 60, limit * 0.25))),
            forceAt = openedAt.plusSeconds(minOf(forceRotateAfter, limit - minOf(2.0 * 60, limit * 0.1))),
        )
    }

    companion object {
        val live: VoiceCallTiming
            get() {
                var timing = VoiceCallTiming()
                // The lab's stand-in server can expire sessions in seconds (debug builds only).
                VoiceLabEnvironment.string("OPENCODE_LAB_VOICE_ROTATE_AFTER")?.toDoubleOrNull()?.let { seconds ->
                    timing = VoiceCallTiming(rotateAfter = seconds, forceRotateAfter = seconds * 1.5, retryDelays = listOf(0.5, 1.5, 3.0))
                }
                return timing.copy(
                    liveAppendQuiet = 900.milliseconds,
                    liveAppendReplyWait = 1_500.milliseconds,
                    liveContextReplyHold = 8.seconds,
                    // No caps: an update waits for a real quiet moment however long that takes.
                    liveAppendMaxHold = Duration.ZERO,
                    liveUserQuietGap = Math.round(VoiceTurnTunables.userQuietGap.value * 1_000).milliseconds,
                    liveMicVoiceLevel = VoiceTurnTunables.micVoiceLevel.value.toFloat(),
                    liveBargeIn = Math.round(VoiceTurnTunables.bargeIn.value * 1_000).milliseconds,
                    liveAnswerPressedHold = 2_500.milliseconds,
                    liveResultMaxHold = Duration.ZERO,
                    liveAnswerSpeakingHold = 15.seconds,
                    liveAnswerStartWait = 1_200.milliseconds,
                    liveUnbackedClaimWait = 1_500.milliseconds,
                    liveProgress = VoiceProgressLine.Timing(
                        firstAfter = Math.round(VoiceProgressTunables.firstAfter.value * 1_000).milliseconds,
                        quiet = Math.round(VoiceProgressTunables.quiet.value * 1_000).milliseconds,
                        again = Math.round(VoiceProgressTunables.again.value * 1_000).milliseconds,
                    ),
                )
            }

        val sessionDelegatorUnbackedClaimWait: Duration = 900.milliseconds

        private fun Instant.plusSeconds(seconds: Double): Instant = plusNanos(Math.round(seconds * 1e9))
    }
}
