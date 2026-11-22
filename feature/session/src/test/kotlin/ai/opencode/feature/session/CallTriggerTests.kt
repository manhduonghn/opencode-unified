package ai.opencode.feature.session

import ai.opencode.core.log.CallTrigger
import ai.opencode.core.log.SystemActionOrigin
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.system.SystemAction
import ai.opencode.feature.system.intents.SessionEntity
import ai.opencode.feature.system.intents.SystemQuickAction
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What asked for each call is named in the log, and a resting grip can't start one (docs/voice.md,
 * The call composer).
 */
class CallTriggerTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_grip", serverTitle = "Grip", projectID = "p", directory = "/Users/kit/project", updatedAt = SessionTestSupport.distantPast)

    private fun Harness.voiceReady(): SessionFeature.State {
        sharedStore.reference(SharedKeys.voiceModeEnabled).set(true)
        sharedStore.reference(SharedKeys.openAIAPIKey).set("sk-test")
        return state { SessionFeature.State.session(server = server, session = session, cachedItems = emptyList()) }
    }

    @Test fun theCallButtonStartsACallThroughVoiceTapped() = sessionTest { h ->
        val store = h.store(h.voiceReady())
        store.exhaustive = false

        store.send(Action.CallButtonTapped)
        store.receive(Action.VoiceTapped)
        assertNotNull(store.state.voice)
        h.skipInFlightEffects()
    }

    @Test fun eachTriggerHasItsOwnLogLine() {
        assertEquals("Call trigger: call button", CallTrigger.CallButton.logLine)
        assertEquals("Call trigger: Stop held", CallTrigger.StopHeld.logLine)
        assertEquals("Call trigger: Raise to Talk", CallTrigger.RaiseToTalk.logLine)
        assertEquals("Call trigger: drawer call button", CallTrigger.Drawer.logLine)
        val origins = SystemActionOrigin.entries
        val names = mutableSetOf<String>()
        for (origin in origins) {
            names += CallTrigger.System(origin).logLine
        }
        assertEquals("Every system origin reads differently", origins.size, names.size)
        // Android's Start Voice Call is the app shortcut and Assistant.
        assertTrue(CallTrigger.System(SystemActionOrigin.shortcut).logLine.contains("Assistant"))
    }

    @Test fun systemSurfacesNameThemselves() {
        assertEquals(SystemActionOrigin.shortcut, SessionEntity.lastViewed(null).action(startsCall = true, origin = SystemActionOrigin.shortcut)?.origin)
        assertEquals(
            SystemActionOrigin.backgroundShortcut,
            SessionEntity.newSession(null).action(startsCall = true, origin = SystemActionOrigin.backgroundShortcut)?.origin,
        )
        val quick = SystemQuickAction.action(SystemQuickAction.Kind.StartCall.rawValue, null)
        assertEquals(SystemActionOrigin.quickAction, quick?.origin)
        // Links never start calls, and stay unattributed.
        assertEquals(SystemActionOrigin.other, SystemAction.fromUrl("opencode://last-session")?.origin)
    }

    @Test fun onlyAQuickReleaseIsATap() {
        val down = 1_000_000L
        assertTrue(ComposerCallPress.isTap(began = down, released = down + 100))
        assertTrue(ComposerCallPress.isTap(began = down, released = down + 340))
        // Past the window: Stop neither stops nor calls, the call button doesn't call.
        assertFalse(ComposerCallPress.isTap(began = down, released = down + 350))
        assertFalse(ComposerCallPress.isTap(began = down, released = down + 700))
        assertFalse(ComposerCallPress.isTap(began = down, released = down + 1_500))
        // No touch-down seen (assistive activation): a tap.
        assertTrue(ComposerCallPress.isTap(began = null, released = down))
    }

    @Test fun aDriftingThumbIsTimedFromTouchDown() {
        // Timing is the release minus the touch-down, nothing else: how far the finger moved
        // never resets it.
        val down = 1_000_000L
        var held = 400L
        while (held <= 2_000L) {
            assertFalse(ComposerCallPress.isTap(began = down, released = down + held))
            held += 200L
        }
    }

    @Test fun holdsLongEnoughToStartACallAreDeliberate() {
        assertTrue(ComposerCallPress.tapWindow.inWholeMilliseconds <= 350)
        assertTrue(ComposerCallPress.stopHold.inWholeMilliseconds >= 1_000)
    }

    @Test fun restartsNameThemselves() {
        assertEquals("Call trigger: rejoin", CallTrigger.Rejoin.logLine)
        assertEquals("Call trigger: try again", CallTrigger.TryAgain.logLine)
    }
}
