package ai.opencode.feature.settings

import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.voice.ServerVoiceProvider
import ai.opencode.feature.voice.ServerVoiceSupport
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceKeySource
import ai.opencode.feature.voice.VoiceProvider
import ai.opencode.feature.voice.VoiceRoutePreferences
import ai.opencode.feature.voice.VoiceServerChoice
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.OpenCodeServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VoiceRouteTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")

    /** Each test's in-memory shared store starts from these values. */
    private fun preserveSettings(): SettingsTestShared {
        val shared = SettingsTestShared()
        shared.set(SharedKeys.voiceModeEnabled, true)
        shared.set(VoiceSharedKeys.voiceEngine, VoiceEngine.gptLive)
        shared.set(SharedKeys.openAIAPIKey, null)
        shared.set(SharedKeys.xaiAPIKey, null)
        shared.set(VoiceSharedKeys.serverVoiceProvider, ServerVoiceProvider.openAI)
        shared.set(VoiceSharedKeys.voiceServerChoice, VoiceServerChoice.Automatic)
        shared.set(VoiceSharedKeys.serverVoiceSupportByServer, emptyMap())
        return shared
    }

    @Test fun inactiveGeminiNeverDisablesThePhoneKeyAcrossEntryPoints() {
        val shared = preserveSettings()
        shared.set(VoiceSharedKeys.voiceServerChoice, VoiceServerChoice.Phone)
        shared.set(VoiceSharedKeys.serverVoiceProvider, ServerVoiceProvider.geminiLive)
        shared.read { assertFalse(VoiceRoutePreferences().isAvailable(server)) }

        shared.set(SharedKeys.openAIAPIKey, "sk-phone")
        shared.read {
            val settings = VoiceSettingsFeature.State(server = server)
            assertEquals(VoiceKeySource.Phone(VoiceProvider.openAI), settings.keySource)
            assertEquals("OpenAI Key", settings.keyRowTitle)
            assertTrue(VoiceRoutePreferences().isAvailable(server))
        }
    }

    @Test fun grokSuppressesUnrelatedServerErrors() {
        val shared = preserveSettings()
        shared.set(VoiceSharedKeys.serverVoiceSupportByServer, mapOf(server.persistenceID to ServerVoiceSupport.unsupported))
        val settings = VoiceSettingsFeature.State(server = server)
        shared.set(VoiceSharedKeys.voiceEngine, VoiceEngine.grok)
        shared.read {
            assertEquals(VoiceKeySource.Phone(VoiceProvider.xAI), settings.keySource)
            assertEquals("xAI Key", settings.keyRowTitle)
        }
    }
}
