package ai.opencode.feature.voice

import java.time.Duration
import java.time.Instant

/**
 * Notices when the call's audio can't get out: a microphone chunk's send that takes over a second
 * means the connection is backing up, and the capture stream starts dropping speech. Poor until
 * sends have been quick again for a few seconds, so it doesn't flicker.
 *
 * Immutable: the mutating methods return the updated watch, paired with the change of state when
 * they report one.
 */
data class VoiceUplinkWatch(
    val isPoor: Boolean = false,
    private val inFlightSince: Instant? = null,
    private val lastSlowAt: Instant? = null,
) {
    fun sendStarted(at: Instant): VoiceUplinkWatch = copy(inFlightSince = at)

    /** Returns a change of state, if the send settles it. */
    fun sendFinished(at: Instant): Pair<VoiceUplinkWatch, Boolean?> {
        val started = inFlightSince ?: return this to null
        return copy(inFlightSince = null).evaluateElapsed(seconds(started, at), at)
    }

    /**
     * Checked on a timer: a send stuck in flight makes it poor before it
     * returns; quiet, quick sends let it recover.
     */
    fun check(at: Instant): Pair<VoiceUplinkWatch, Boolean?> {
        if (inFlightSince != null) return evaluateElapsed(seconds(inFlightSince, at), at)
        if (isPoor && lastSlowAt != null && seconds(lastSlowAt, at) >= recoverAfter) {
            return copy(isPoor = false) to false
        }
        return this to null
    }

    private fun evaluateElapsed(elapsed: Double, now: Instant): Pair<VoiceUplinkWatch, Boolean?> {
        var next = this
        if (elapsed >= healthyUnder) next = next.copy(lastSlowAt = now)
        if (elapsed >= poorAfter && !next.isPoor) return next.copy(isPoor = true) to true
        return next to null
    }

    companion object {
        /** A send this slow (seconds), finished or still going, makes the connection poor. */
        const val poorAfter: Double = 1.0

        /** Sends quicker than this (seconds) count as healthy again. */
        const val healthyUnder: Double = 0.4

        /** How long (seconds) sends must stay healthy before it recovers. */
        const val recoverAfter: Double = 3.0

        private fun seconds(from: Instant, to: Instant): Double = Duration.between(from, to).toNanos() / 1e9
    }
}
