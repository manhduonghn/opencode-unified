package ai.opencode.core.diagnostics

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Problem reports and the voice screenshot tool count marked sheets and screens apart
 * ([ScreenshotPolicy]). The tool itself (`VoiceDelegatorWorkspace`/`VoiceAppState`) is voice's.
 */
class VoiceScreenshotToolTests {
    @get:Rule val isolation = TestIsolationRule()

    /** The voice tool sends to his own session and refuses only real credentials. */
    @Test fun problemReportsAndVoiceScreenshotsKeepSeparatePolicies() {
        val settings = CredentialSurface.Marks(sheets = 0, screens = 1)
        val serverSheet = CredentialSurface.Marks(sheets = 1, screens = 0)
        assertEquals("Settings stays out of problem reports", 1, ScreenshotPolicy.problemReport.markedSurfaces(settings))
        assertEquals("The voice tool can capture Settings' pages", 0, ScreenshotPolicy.voice.markedSurfaces(settings))
        assertEquals(1, ScreenshotPolicy.problemReport.markedSurfaces(serverSheet))
        assertEquals("Every screenshot skips the server and pairing sheets", 1, ScreenshotPolicy.voice.markedSurfaces(serverSheet))
        assertEquals(2, ScreenshotPolicy.problemReport.markedSurfaces(CredentialSurface.Marks(sheets = 1, screens = 1)))
    }
}
