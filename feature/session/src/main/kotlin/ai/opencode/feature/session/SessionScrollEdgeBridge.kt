package ai.opencode.feature.session

import ai.opencode.feature.transcript.list.TranscriptBorrowedDrag
import android.os.Process
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Port of iOS `SessionScrollEdgeBridge` (`Transcript/TranscriptListView.swift`), which the
 * transcript port leaves to the session screen: what the transcript list and the session chrome
 * share without re-rendering the session. The composer's height (the list's bottom inset), how
 * far content has scrolled under the header (the backdrop's reveal), a jump-to-latest request,
 * and when this screen first drew each Working-line event (so a remount never replays Starting
 * Up or a finale). Plain object, not a reducer: values change per frame.
 */
@Stable
class SessionScrollEdgeBridge {
    /** The composer area's height (dp), the list's bottom content inset. */
    var composerHeight by mutableFloatStateOf(0f)

    /**
     * What the transcript last said about Jump to latest (the composer host shows it). Saved with
     * the composer's height, so a recreated screen's pill is there from its first frame.
     * Only within the same process: after process death the list starts hidden while its row
     * loads and says itself when to show the pill.
     */
    var reportedShowsJumpButton = false

    /** Content scrolled under the header (dp), capped at the reveal distance and rounded. */
    var scrolledUnderHeader by mutableFloatStateOf(0f)
        private set

    /** Bumped to scroll the list to its latest row. */
    var jumpRequest by mutableIntStateOf(0)

    /** iOS `onBorrowedDrag`: drags on the controls over the list (Jump to latest, queued, capsule) scroll it. */
    val borrowedDrag = TranscriptBorrowedDrag()

    private val workingRunStamps = HashMap<String, Double>()
    private val workingRunOrder = ArrayDeque<String>()

    /** When this screen first drew the event [token] (seconds, `AgentActivityClock`); keeps 64. */
    fun workingRunTime(token: String, now: Double): Double {
        workingRunStamps[token]?.let { return it }
        workingRunStamps[token] = now
        workingRunOrder.addLast(token)
        while (workingRunOrder.size > 64) workingRunStamps.remove(workingRunOrder.removeFirst())
        return now
    }

    /** Whether the transcript has reported where it sits yet; until then the header's bar doesn't animate. */
    var hasPlacedTranscript by mutableStateOf(false)
        private set

    fun scrolled(underHeader: Float, revealDistance: Float) {
        if (!hasPlacedTranscript) hasPlacedTranscript = true
        val capped = min(max(underHeader, 0f), max(revealDistance, MINIMUM_TRACKED_REVEAL)).roundToInt().toFloat()
        if (capped != scrolledUnderHeader) scrolledUnderHeader = capped
    }

    companion object {
        /** Tracked even when nothing reveals with the scroll, so a band that takes over draws at once. */
        const val MINIMUM_TRACKED_REVEAL = 24f

        /**
         * For the screen's `rememberSaveable`: the composer's last height outlives the activity
         * being recreated, so a restored transcript's first frame already has its bottom inset and
         * lays the reader's row out where it was, instead of a frame at a shorter scroll range.
         * Jump to latest's last state goes with it within the same process.
         */
        val Saver: Saver<SessionScrollEdgeBridge, Any> = listSaver(
            save = { listOf(it.composerHeight, it.reportedShowsJumpButton, process) },
            restore = {
                SessionScrollEdgeBridge().apply {
                    composerHeight = it[0] as Float
                    reportedShowsJumpButton = it[1] as Boolean && it.getOrNull(2) == process
                }
            },
        )

        /** This process (pid and start time), so a saved value can tell a recreation from a restore after process death. */
        private val process: String get() = "${Process.myPid()}:${Process.getStartUptimeMillis()}"
    }
}
