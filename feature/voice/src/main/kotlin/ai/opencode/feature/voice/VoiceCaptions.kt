package ai.opencode.feature.voice

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Which words of each caption line have appeared, and when. New words queue and appear at
 * least [spacing] apart, so a burst of streamed text still flows in word by word. Immutable:
 * each change returns the next reveal. Times are seconds on the monotonic clock.
 */
data class CaptionReveal(
    val lines: List<Line> = emptyList(),
    val lastReveal: Double = Double.NEGATIVE_INFINITY,
    /** The oldest line still shown; lines before it have dropped off the top. */
    val firstLineID: String? = null,
) {
    data class Line(
        val id: String,
        val isUser: Boolean,
        val words: List<String>,
        /** Reveal times of the leading words; the rest are still queued. */
        val revealedAt: List<Double>,
        /** Words the pacer may reveal; a live line holds back its unfinished last word. */
        val revealable: Int,
    ) {
        val text: String get() = words.joinToString(" ")
    }

    /**
     * Matches lines by ID (or, when the live reply settles under a new ID, by position and
     * speaker). A line matched by ID keeps the reveal of every word it had shown, revised or not;
     * one inherited by position keeps only its unchanged leading words. [revealed] shows
     * everything at once, for opening onto a call in progress.
     */
    fun sync(targets: List<CaptionTarget>, revealed: Boolean = false): CaptionReveal {
        var firstLineID = firstLineID
        var shown = targets
        if (firstLineID != null) {
            val start = targets.indexOfFirst { it.id == firstLineID }
            if (start >= 0) shown = targets.subList(start, targets.size) else firstLineID = null
        }
        val ids = shown.map { it.id }.toSet()
        val next = shown.mapIndexed { index, target ->
            val words = target.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val hasUnfinishedWord = target.isLive && !(target.text.lastOrNull()?.isWhitespace() ?: true)
            val revealable = if (hasUnfinishedWord) max(0, words.size - 1) else words.size
            val sameLine = lines.firstOrNull { it.id == target.id }
            var previous = sameLine
            if (previous == null && index in lines.indices && lines[index].isUser == target.isUser && lines[index].id !in ids) {
                previous = lines[index]
            }
            val revealedAt = when {
                revealed -> List(revealable) { Double.NEGATIVE_INFINITY }
                // A line revised under its own ID (a correction, a new progress step) swaps its
                // shown words in place: un-revealing them would blink them out and type them again.
                sameLine != null -> sameLine.revealedAt.take(revealable)
                previous != null -> {
                    val unchanged = previous.words.zip(words).takeWhile { (a, b) -> a == b }.size
                    previous.revealedAt.take(min(unchanged, revealable))
                }
                else -> emptyList()
            }
            Line(id = target.id, isUser = target.isUser, words = words, revealedAt = revealedAt, revealable = revealable)
        }
        return copy(lines = next, firstLineID = firstLineID)
    }

    /**
     * Drops the oldest lines once more than [maxLines] show (any past [keptLines] when
     * [force]d), or `null` when there's nothing to drop. Call it only while nothing is
     * appearing, so the column's shrink is never read as growth to glide.
     */
    fun trim(force: Boolean = false): CaptionReveal? {
        if (lines.size <= (if (force) keptLines else maxLines)) return null
        val kept = lines.drop(lines.size - keptLines)
        return copy(lines = kept, firstLineID = kept.firstOrNull()?.id)
    }

    val hasQueued: Boolean get() = lines.any { it.revealedAt.size < it.revealable }

    /** Seconds until the next queued word may appear, or `null` when none is queued. */
    fun nextRevealDelay(now: Double): Double? = if (hasQueued) max(0.0, lastReveal + spacing - now) else null

    fun revealNext(now: Double): CaptionReveal {
        val index = lines.indexOfFirst { it.revealedAt.size < it.revealable }
        if (index < 0) return this
        val updated = lines.toMutableList()
        updated[index] = updated[index].copy(revealedAt = updated[index].revealedAt + now)
        return copy(lines = updated, lastReveal = now)
    }

    companion object {
        const val spacing = 0.045

        /**
         * Every line is laid out word by word on each update, so the column keeps only the
         * newest lines: past [maxLines] it drops back to [keptLines].
         */
        const val keptLines = 30
        const val maxLines = 50
    }
}

/** Each word fades in where it lies, out of a light blur. */
object CaptionWordRenderer {
    const val duration = 0.42

    /** Eased progress of a word revealed [at] by [now] (0 not yet, 1 whole). */
    fun eased(at: Double, now: Double): Float {
        val progress = (now - at) / duration
        if (progress >= 1) return 1f
        if (progress <= 0) return 0f
        return (1 - (1 - progress).pow(3)).toFloat()
    }

    /** The word's blur radius (dp) at [eased]; none under Reduce Motion. */
    fun blur(eased: Float, reduceMotion: Boolean): Float = if (reduceMotion) 0f else (1 - eased) * 3
}
