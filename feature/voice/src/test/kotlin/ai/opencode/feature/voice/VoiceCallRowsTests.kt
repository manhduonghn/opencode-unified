package ai.opencode.feature.voice

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.clienttools.ClientTool
import ai.opencode.server.clienttools.ClientToolCall
import ai.opencode.server.clienttools.JsonRpcID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * Its value tests. Android shows the rows on a Material card that animates its own size, so there's
 * no panel to test.
 */
class VoiceCallRowsTests {
    @get:Rule val isolation = TestIsolationRule()

    private val screen = ClientToolCall(
        connection = UUID.fromString("00000000-0000-0000-0000-000000000003"),
        id = JsonRpcID.Number(1), tool = ClientTool.voiceRequestScreen, sessionID = "ses_voice", reason = "To check the layout.",
    )

    @Test fun aLeavingRowStaysDrawnUntilDropped() {
        var shown = VoiceCallRows(screen = screen)
        shown = shown.take(VoiceCallRows())
        assertEquals(screen, shown.screen)
        shown = shown.drop(missingFrom = VoiceCallRows())
        assertTrue(shown.isEmpty)
    }

    @Test fun aScreenRequestTakesTheRowBeforeALink() {
        val link = ClientToolCall(connection = screen.connection, id = JsonRpcID.Number(2), tool = ClientTool.voiceOpenURL, sessionID = "ses_voice")
        assertEquals(screen, VoiceCallRows(screen = screen, link = link).request)
        var shown = VoiceCallRows(link = link)
        shown = shown.take(VoiceCallRows(screen = screen))
        assertEquals(VoiceCallRows(screen = screen), shown)
    }

    @Test fun theTrackStaysWhileARequestComesAndGoes() {
        val track = CallAudioTrack(id = UUID.fromString("00000000-0000-0000-0000-000000000004"), title = "Song", duration = null)
        var shown = VoiceCallRows(track = track)
        shown = shown.take(VoiceCallRows(screen = screen, track = track))
        assertEquals(VoiceCallRows(screen = screen, track = track), shown)
        shown = shown.drop(missingFrom = VoiceCallRows(track = track))
        assertEquals(VoiceCallRows(track = track), shown)
    }
}
