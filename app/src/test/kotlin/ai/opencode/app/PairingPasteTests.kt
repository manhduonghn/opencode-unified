package ai.opencode.app

import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.testing.TestIsolationRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** The welcome's Paste. Ordinary mistakes stay beside Paste, never the error report card. */
class PairingPasteTests {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()

    @Test
    fun failedPasteSaysWhyBesidePasteWithoutTheErrorCard() = runTest {
        val clipboard = Locked<String?>(null)
        val effects = detachedScope(coroutineContext)
        val store = shared.appStore(shared.appState(), effects) {
            this[PasteboardClient] = PasteboardClient(setURL = {}, string = { clipboard.value })
        }
        store.send(AppFeature.Action.PastePairingCodeTapped) {
            it.copy(pasteError = "Your clipboard is empty", connection = AppFeature.State.Connection.Picking)
        }
        assertNull(store.state.errorMessage)
        clipboard.set("hello")
        store.send(AppFeature.Action.PastePairingCodeTapped) { it.copy(pasteError = "That isn’t a pairing link") }
        clipboard.set("http://127.0.0.1:4096/auth/connect/abc")
        store.send(AppFeature.Action.PastePairingCodeTapped) { it.copy(pasteError = loopbackPairingMessage) }
        assertNull(store.state.errorMessage)
        store.send(AppFeature.Action.ScanPairingCodeTapped) { it.copy(pasteError = null, isScanningPairingCode = true) }
        store.finish()
        effects.skipInFlightEffects()
    }

    /** The link as a terminal wraps it, or inside the command's whole output, pastes. */
    @Test
    fun copiedPairingLinkPastes() {
        val link = "https://abc.opentunnel.xyz/auth/connect/Ab3_x-9QAb3_x-9QAb3_x-"
        assertNull(pasteProblem(link.take(50) + "\n" + link.drop(50)))
        assertNull(pasteProblem("\n  Open a link to connect. Links work once and expire in 5 minutes.\n\n  $link\n\n"))
        assertEquals("That isn’t a pairing link", pasteProblem("see https://example.com/docs."))
    }
}
