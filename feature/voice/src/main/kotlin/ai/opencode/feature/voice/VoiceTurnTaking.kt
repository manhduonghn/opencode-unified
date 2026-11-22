package ai.opencode.feature.voice

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Who may speak when. The user always preempts the agent, and the agent never
 * starts a reply while the user is mid-utterance or an earlier reply is still
 * playing. Pure bookkeeping, fed by `VoiceCallCoordinator`; the methods that
 * return `Boolean` answer whether to send `response.create` now.
 *
 * Server VAD fires `speech_started` once per utterance and only interrupts a
 * response that exists at that moment, so a reply created after the user
 * started talking would play straight over them. Tool results therefore wait
 * for the floor, and several waiting results become one reply.
 *
 * Immutable: each mutating method returns the updated state, paired with its result where it has
 * one (`first` is the new state).
 */
data class VoiceTurnTaking(
    val userSpeaking: Boolean = false,
    /** A response is generating, or one we asked for hasn't been confirmed yet. */
    val responseActive: Boolean = false,
    /** We sent `response.create` and are waiting for `response.created`. */
    val requestInFlight: Boolean = false,
    /** The assistant item whose audio is still playing; it outlives `response.done`. */
    val playingItemID: String? = null,
    val isPlaying: Boolean = false,
    /** A tool result is waiting for the floor; one reply is owed. */
    val replyOwed: Boolean = false,
    /** The user's turn just ended; server VAD's reply is on its way. */
    val awaitingServerReply: Boolean = false,
    /** That reply was committed after the owed result, so it covers it. */
    private val serverReplyCoversOwed: Boolean = false,
    /**
     * A confirmed session can carry a reply. Between sessions owed replies
     * wait, so nothing is sent to a socket that can't answer.
     */
    val hasSession: Boolean = true,
    /**
     * Another app or call holds the audio: nothing can be heard, so replies
     * wait for it to come back. Survives a move to a new session.
     */
    val audioInterrupted: Boolean = false,
) {
    val floorIsFree: Boolean
        get() = !userSpeaking && !responseActive && !isPlaying && !awaitingServerReply && !audioInterrupted

    /** What a barge-in must undo. */
    data class BargeIn(
        /** Truncate this item to what was heard (it may have finished generating long ago). */
        val truncateItemID: String?,
        /** Cancel the response still being generated. */
        val cancelsResponse: Boolean,
    )

    /** A tool result was delivered and the model should speak about it. */
    fun replyRequested(): Pair<VoiceTurnTaking, Boolean> =
        // Committed before this result, the reply on its way can't see it.
        copy(replyOwed = true, serverReplyCoversOwed = false).releaseOwedReply()

    fun userStartedSpeaking(): Pair<VoiceTurnTaking, BargeIn> {
        val bargeIn = BargeIn(
            truncateItemID = if (isPlaying) playingItemID else null,
            cancelsResponse = responseActive && !requestInFlight,
        )
        return copy(
            userSpeaking = true,
            awaitingServerReply = false,
            serverReplyCoversOwed = false,
            isPlaying = false,
            playingItemID = null,
        ) to bargeIn
    }

    /**
     * The user's turn ended (or they muted mid-sentence). Returns true: wait
     * `serverReplyGrace`, then call `serverReplyTimedOut()`.
     */
    fun userStoppedSpeaking(): Pair<VoiceTurnTaking, Boolean> {
        if (!userSpeaking) return this to false
        return copy(userSpeaking = false, awaitingServerReply = true, serverReplyCoversOwed = replyOwed) to true
    }

    /** Server VAD didn't answer the user's turn: the floor is ours again. */
    fun serverReplyTimedOut(): Pair<VoiceTurnTaking, Boolean> {
        if (!awaitingServerReply) return this to false
        return copy(awaitingServerReply = false).releaseOwedReply()
    }

    /**
     * Returns true when the response must be cancelled: it began after the user
     * started talking, so server VAD won't interrupt it.
     */
    fun responseCreated(): Pair<VoiceTurnTaking, Boolean> {
        val wasRequested = requestInFlight
        var next = copy(requestInFlight = false, responseActive = true)
        if (next.userSpeaking) {
            // Ours crossed the user's first word: it goes, the result stays owed.
            if (wasRequested) next = next.copy(replyOwed = true)
            return next to true
        }
        if (next.awaitingServerReply) {
            next = next.copy(awaitingServerReply = false)
            if (next.serverReplyCoversOwed) next = next.copy(replyOwed = false)
        }
        return next.copy(serverReplyCoversOwed = false) to false
    }

    /** The server refused our `response.create` (one was already active). */
    fun requestRejected(): VoiceTurnTaking {
        if (!requestInFlight) return this
        return copy(requestInFlight = false, responseActive = false, replyOwed = true)
    }

    fun responseDone(): Pair<VoiceTurnTaking, Boolean> =
        copy(responseActive = false, requestInFlight = false).releaseOwedReply()

    fun audioEnqueued(itemID: String?): VoiceTurnTaking =
        copy(isPlaying = true, playingItemID = itemID ?: playingItemID)

    fun playbackDrained(): Pair<VoiceTurnTaking, Boolean> =
        copy(isPlaying = false, playingItemID = null).releaseOwedReply()

    /** The system took the audio: playback stopped without draining. */
    fun audioWasInterrupted(): VoiceTurnTaking = copy(isPlaying = false, playingItemID = null, audioInterrupted = true)

    /** The audio came back: reply if one is owed and the floor is free. */
    fun audioResumed(): Pair<VoiceTurnTaking, Boolean> = copy(audioInterrupted = false).releaseOwedReply()

    /**
     * The call moved to a new Realtime session: nothing generating or pending
     * on the old one exists there, but local playback and owed replies carry
     * over. A reply cut off mid-generation is owed again.
     */
    fun sessionChanged(): VoiceTurnTaking = copy(
        replyOwed = replyOwed || responseActive || requestInFlight,
        responseActive = false,
        requestInFlight = false,
        userSpeaking = false,
        awaitingServerReply = false,
        serverReplyCoversOwed = false,
        hasSession = false,
    )

    /** The new session confirmed: reply if one is owed and the floor is free. */
    fun sessionConfirmed(): Pair<VoiceTurnTaking, Boolean> = copy(hasSession = true).releaseOwedReply()

    /** A reply is owed regardless of the floor (the user's turn went unanswered). */
    fun owe(): VoiceTurnTaking = copy(replyOwed = true)

    private fun releaseOwedReply(): Pair<VoiceTurnTaking, Boolean> {
        if (!replyOwed || !hasSession || !floorIsFree) return this to false
        // Hold the floor until the server confirms, so a second result can't race it.
        return copy(replyOwed = false, responseActive = true, requestInFlight = true) to true
    }

    companion object {
        /**
         * How long to wait for server VAD to answer the user's turn before asking
         * for an owed reply ourselves.
         */
        val serverReplyGrace: Duration = 1_500.milliseconds
    }
}
