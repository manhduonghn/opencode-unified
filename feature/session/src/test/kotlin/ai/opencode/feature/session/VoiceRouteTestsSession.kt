package ai.opencode.feature.session

import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.distantPast
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.voice.ServerVoiceProvider
import ai.opencode.feature.voice.ServerVoiceSupport
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceServerChoice
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each test's shared keys live in its own in-memory store, set. */
class VoiceRouteTestsSession {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "route", serverTitle = "Routing", projectID = "p", directory = "/project", updatedAt = distantPast)

    /** Voice on, GPT-Live, no keys. */
    private fun Harness.preserveSettings() = state {
        shared(SharedKeys.voiceModeEnabled).set(true)
        shared(VoiceSharedKeys.voiceEngine).set(VoiceEngine.gptLive)
        shared(SharedKeys.openAIAPIKey).set(null)
        shared(SharedKeys.xaiAPIKey).set(null)
        shared(VoiceSharedKeys.serverVoiceProvider).set(ServerVoiceProvider.openAI)
        shared(VoiceSharedKeys.serverVoiceSupportByServer).set(emptyMap())
    }

    private fun Harness.isVoiceAvailable(): Boolean = state { State.session(server = server, session = session).isVoiceAvailable }

    /**
     * A stored Gemini choice has no effect while This Phone takes calls:
     * GPT-Live needs the phone's key, and Settings, the session and Home agree.
     */
    @Test fun inactiveGeminiNeverDisablesThePhoneKeyAcrossEntryPoints() = sessionTest { h ->
        h.preserveSettings()
        h.state {
            shared(VoiceSharedKeys.voiceServerChoice).set(VoiceServerChoice.Phone)
            shared(VoiceSharedKeys.serverVoiceProvider).set(ServerVoiceProvider.geminiLive)
        }
        assertFalse(h.isVoiceAvailable())

        h.state { shared(SharedKeys.openAIAPIKey).set("sk-phone") }
        val detail = h.state { State.session(server = server, session = session) }
        assertTrue(h.state { detail.isVoiceAvailable })
        assertTrue(h.state { detail.voiceRoute.warmsPhoneConnection })
    }

    @Test fun grokSuppressesUnrelatedServerErrors() = sessionTest { h ->
        h.preserveSettings()
        h.state {
            shared(VoiceSharedKeys.serverVoiceSupportByServer).set(mapOf(server.persistenceID to ServerVoiceSupport.unsupported))
            shared(VoiceSharedKeys.voiceEngine).set(VoiceEngine.grok)
        }
        assertFalse(h.isVoiceAvailable())
        h.state { shared(SharedKeys.xaiAPIKey).set("xai-test") }
        assertTrue(h.isVoiceAvailable())
    }
}
