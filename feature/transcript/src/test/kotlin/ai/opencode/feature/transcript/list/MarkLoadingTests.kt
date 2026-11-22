package ai.opencode.feature.transcript.list

import ai.opencode.feature.transcript.TranscriptConfiguration
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.server.model.OpenCodeTranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Port of iOS `MarkLoadingTests.swift`. iOS advances a TCA `TestClock` only to read elapsed
 * seconds; [MarkLoadingTiming] takes times in seconds, so the elapsed times are passed directly.
 *
 * Dropped (UIKit scene and cell lifecycle; Android's `MarkLoadingView` takes `isHidden` from the
 * list, and its pause rule is pinned by pulseTimelineRunsOnlyWhileALoadShowsOnScreen):
 * - pulsePausesInBackgroundBehindAnotherScreenAndOffScreen (scene notifications, `viewDidDisappear`,
 *   `willDisplay`/`didEndDisplaying` cell callbacks)
 * - pulsePausesWhenItsRowLeavesTheSnapshotWhileDisplayed (diffable snapshot + cell callbacks)
 * - reusedCellNeverHostsALoadingRow (collection-view cell reuse)
 * - listDetachedWhileBackgroundedPulsesAgainWhenShown (window attach/detach)
 *
 * Dropped (deliberate Android divergence: the mark doesn't hand off to the content. It is the
 * list's Empty row, which the first content replaces like any row, so there is no hold, crossfade
 * or touch-blocking hand-off to test; see docs/parity/transcript.md):
 * - fastLoadNeverStartsAHandoff, coldLoadHoldsMinimumThenCrossfades,
 *   arrivalDuringFadeInFinishesEntranceWithoutBlinking
 * - cancellingHandoffRestoresInteractionAndRemovesPresentation,
 *   activeHandoffDoesNotCountTowardDisabledInvariant
 * - backgroundDuringLoadingHandoffRestoresInteraction,
 *   suspendedAnimationCannotKeepTranscriptDisabledPastDeadline,
 *   cancelledHandoffDeadlineCannotUnlockItsReplacement,
 *   activeHandoffOwnsTheListOnlyUntilItsRealTimeDeadline,
 *   p282HandoffThatNeverLandsIsReleasedOnARealTimeDeadline,
 *   handoffDeadlineRunsFromItsStartNotFromWhenTheNetFirstSawIt,
 *   handoffInsideItsDeadlineIsNotExpedited
 */
class MarkLoadingTests {
    @Test
    fun loadingGlyphPulsesFromFullAndHoldsStillUnderReduceMotion() {
        // The pulse starts at full when the entrance starts, so the fade in is not dimmed.
        assertEquals(1.0, LoadingGlyphPulse.opacity(0.0, reducedMotion = false), 0.0)
        assertEquals(1f, LoadingGlyphPulse.scale(0.0, reducedMotion = false))
        assertEquals(1.0, LoadingGlyphPulse.opacity(null, reducedMotion = false), 0.0)
        assertEquals(1.0, LoadingGlyphPulse.opacity(-0.3, reducedMotion = false), 0.0)
        val trough = 0.85
        assertEquals(0.45, LoadingGlyphPulse.opacity(trough, reducedMotion = false), 0.0001)
        assertEquals(0.94, LoadingGlyphPulse.scale(trough, reducedMotion = false).toDouble(), 0.0001)
        assertEquals(1.0, LoadingGlyphPulse.opacity(1.7, reducedMotion = false), 0.0001)
        var elapsed = 0.0
        while (elapsed <= 4.0 + 1e-9) {
            assertEquals(0.5, LoadingGlyphPulse.opacity(elapsed, reducedMotion = true), 0.0)
            assertEquals(1f, LoadingGlyphPulse.scale(elapsed, reducedMotion = true))
            elapsed += 0.4
        }
    }

    @Test
    fun pulseTimelineRunsOnlyWhileALoadShowsOnScreen() {
        assertFalse(markLoadingIsPaused(loadingSince = 1.0, isHidden = false, settled = true, reducedMotion = false))
        assertTrue(markLoadingIsPaused(loadingSince = 1.0, isHidden = true, settled = false, reducedMotion = false))
        assertTrue(markLoadingIsPaused(loadingSince = null, isHidden = false, settled = false, reducedMotion = false))
        assertTrue(markLoadingIsPaused(loadingSince = 1.0, isHidden = false, settled = true, reducedMotion = true))
    }

    @Test
    fun cachedSwitchNeverStartsIndicatorEvenDuringRefresh() {
        val items = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "cached-message", text = "Cached message"))
        val builder = TranscriptSnapshotBuilder()
        builder.build(
            TranscriptConfiguration(sessionID = "cached", entries = TranscriptEntry.build(items), isLoading = true),
            fitGeneration = null,
            dividerDecision = null,
        )
        assertNull(builder.emptyLoadingSince)
        // A second later, still nothing shows.
        assertEquals(0.0, MarkLoadingTiming(start = builder.emptyLoadingSince).opacity(1.0), 0.0)
    }
}
