package ai.opencode.feature.session

import ai.opencode.feature.session.ComposerControls.DictationControls
import ai.opencode.feature.session.ComposerControls.MicPlacement
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerItemProviderLoaderTests {
    private data class Case(
        val isDictating: Boolean,
        val isFinishing: Boolean,
        val isRunning: Boolean,
        val canSend: Boolean,
        val expanded: Boolean,
        val expected: ComposerControls,
    )

    @Test
    fun composerOffersACallWhenVoiceIsSetUp() {
        // Voice set up: empty and idle shows the call with dictation beside it; text or a run replaces it.
        val cases = listOf(
            Case(false, false, false, false, false, ComposerControls(ComposerTrailingControl.Call, MicPlacement.trailing, null)),
            Case(false, false, false, false, true, ComposerControls(ComposerTrailingControl.Call, MicPlacement.leading, null)),
            Case(false, false, false, true, true, ComposerControls(ComposerTrailingControl.Send(enabled = true), MicPlacement.leading, null)),
            Case(false, false, true, false, false, ComposerControls(ComposerTrailingControl.Stop, MicPlacement.trailing, null)),
            Case(true, false, false, false, true, ComposerControls(ComposerTrailingControl.Mic, MicPlacement.none, DictationControls(isFinishing = false))),
        )
        for (case in cases) {
            val controls = ComposerControls.make(
                isDictating = case.isDictating,
                isFinishing = case.isFinishing,
                isRunning = case.isRunning,
                canSend = case.canSend,
                expanded = case.expanded,
                canCall = true,
            )
            assertEquals(case.toString(), case.expected, controls)
        }
    }

    @Test
    fun activeCallKeepsSendInsteadOfHiddenCall() {
        for (expanded in listOf(false, true)) {
            for (ready in listOf(false, true)) {
                val controls = ComposerControls.make(
                    isDictating = false, isFinishing = false, isRunning = false,
                    canSend = ready, expanded = expanded, canCall = true,
                    keepsSendVisible = true, isInCall = true,
                )
                assertEquals(ComposerTrailingControl.Send(enabled = ready), controls.trailing)
                assertEquals(if (expanded) MicPlacement.leading else MicPlacement.trailing, controls.secondaryMic)
            }
            val running = ComposerControls.make(
                isDictating = false, isFinishing = false, isRunning = true,
                canSend = false, expanded = expanded, canCall = true,
                keepsSendVisible = true, isInCall = true,
            )
            assertEquals(ComposerTrailingControl.Stop, running.trailing)
        }
    }

    @Test
    fun composerControlPresentation() {
        val cases = listOf(
            Case(false, false, false, false, false, ComposerControls(ComposerTrailingControl.Mic, MicPlacement.none, null)),
            Case(false, false, true, false, false, ComposerControls(ComposerTrailingControl.Stop, MicPlacement.trailing, null)),
            Case(false, false, false, true, true, ComposerControls(ComposerTrailingControl.Send(enabled = true), MicPlacement.leading, null)),
            Case(true, false, true, true, true, ComposerControls(ComposerTrailingControl.Send(enabled = true), MicPlacement.none, DictationControls(isFinishing = false))),
            Case(false, true, true, true, true, ComposerControls(ComposerTrailingControl.Send(enabled = true), MicPlacement.none, DictationControls(isFinishing = true))),
        )
        for (case in cases) {
            val controls = ComposerControls.make(
                isDictating = case.isDictating,
                isFinishing = case.isFinishing,
                isRunning = case.isRunning,
                canSend = case.canSend,
                expanded = case.expanded,
            )
            assertEquals(case.toString(), case.expected, controls)
        }
    }
}
