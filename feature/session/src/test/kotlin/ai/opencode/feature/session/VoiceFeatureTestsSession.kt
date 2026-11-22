package ai.opencode.feature.session

import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionTestSupport.distantPast
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.voice.VoiceServerChoice
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared keys live in the test's own in-memory store. */
class VoiceFeatureTestsSession {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_voice", serverTitle = "Voice lab", projectID = "p", directory = "/Users/kit/project", updatedAt = distantPast)

    /**
     * Voice needs its switch, and on This Phone the phone's key; chosen
     * automatically, the connected server can take the call without one.
     */
    @Test fun voiceAvailabilityRequiresBothAdvancedToggleAndAPIKey() = sessionTest { h ->
        val state = h.state { State.session(server = server, session = session, cachedItems = emptyList()) }
        state.voiceServer.set(VoiceServerChoice.Phone)
        state.voiceModeEnabled.set(false)
        state.openAIAPIKey.set("sk-test")
        assertFalse(h.state { state.isVoiceAvailable })

        state.voiceModeEnabled.set(true)
        state.openAIAPIKey.set(null)
        assertFalse(h.state { state.isVoiceAvailable })

        state.openAIAPIKey.set("sk-test")
        assertTrue(h.state { state.isVoiceAvailable })

        state.openAIAPIKey.set(null)
        state.voiceServer.set(VoiceServerChoice.Automatic)
        assertTrue("The connected server, not known to be unable, takes the call", h.state { state.isVoiceAvailable })
    }
}
