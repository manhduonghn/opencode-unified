package ai.opencode.feature.transcript

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Port of iOS `NewMessagesListTests.swift`.
 *
 * Dropped (hosted `TranscriptListViewController` harness: whether arrivals fit is judged from
 * `UICollectionView` self-sized cell heights versus its 44 pt estimates, window attach/detach and
 * `scrollViewDidScroll` callbacks; Android judges it from LazyColumn layouts, which
 * need the Compose UI runtime):
 * - aTallArrivalAtAnEstimatedHeightIsntJudgedToFit
 * - rowsThatOnlyFitAtTheirEstimateKeepTheDivider (on screen and off)
 * - aShortArrivalFitsAndShowsAtTheEndWithoutTheDivider
 * - withTheExperimentOffEvenATallArrivalEndsAtTheBottom
 * - aWatchedReadSlidesToTheDividerOnlyWhenItDoesntFit (tall and short)
 * - rowsShrinkingBelowTheirEstimateDontRestartTheFollowerOnLanding
 * - rowsShrinkingBelowTheirEstimateDontRestartTheFollowerDuringASlide
 * - shrinkingRowsThatFitEndTheSlideAtTheEnd
 * - oneToolCallUnderWorkingFitsWithoutTheDivider
 * - whatLandedAwayIsDecidedOnceTheReaderIsBack
 * - whatLandedAwayAndDoesntFitShowsTheDividerOnReturn
 * - aDividerThatFitDoesntComeBackWhileStreaming
 * - reachingTheEndPastTheDividerClearsIt
 * - aLongArrivalMeasuresOnlyTheRowsThatProveItDoesntFit (short and long)
 * - withTheExperimentOffAWatchedReadSlidesToTheEnd (tall and short)
 * - dividerWithShortContentBelowClearsWhenReaderScrollsToEnd
 * - aFadedDividerClosesWithoutMovingTheRowsUnderIt
 * - aClearedDividerOnScreenClosesWithTheEndHeld
 * - leavingMidCollapseLandsTheDividerClosed
 * - leavingMidTurnBackLandsTheDividerOpen
 * - scrollingDuringTheFadeKeepsTheDivider
 * - browsingFarUpKeepsTheDividerOnAShortReplyScreensBelow
 * - browsingFromPreviousMarkReevaluatesFitWhenMarkMovesToShortReplyAtEnd
 * - initialOffscreenSnapshotBeforeFirstLayoutStillDecidesFitWhenShown
 * - replyThatFitsWithoutDividerHeightDoesNotShowDividerOrStopShortOfBottom
 *
 * Dropped with the bottom-anchored list (deliberate Android divergence, see
 * docs/parity/transcript.md):
 * - whatsNewFitsWithTheDividerGoneAndALittleOverflow: the divider isn't drawn until the list has
 *   decided, so its height and the overflow allowance never enter it; what's new fits when its
 *   first row's top is in view at the end.
 * - theDividerIsOnTheLastScreenAtTheEndBelowTheHeader: a divider on screen at the end, at rest,
 *   is read; the list measures it below the header through its content padding.
 */
class NewMessagesListTests {
    /// The visible top is under the header: a divider still behind the header counts as read,
    /// one below it doesn't.
    @Test
    fun theDividerIsPassedOnceItsAboveTheHeadersEdge() {
        assertTrue(NewMessagesPlacement.isPassed(dividerMaxY = 130f, offsetY = 40f, topInset = 100f))
        assertFalse(NewMessagesPlacement.isPassed(dividerMaxY = 150f, offsetY = 40f, topInset = 100f))
    }
}
