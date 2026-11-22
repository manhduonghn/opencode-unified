package ai.opencode.feature.settings

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.voice.ServerVoiceProvider
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceKeySource
import ai.opencode.feature.voice.VoiceProvider
import ai.opencode.feature.voice.VoiceSharedKeys
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VoiceRoutingBaselineTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test fun aServerNeverHidesGroksPhoneKey() {
        val shared = SettingsTestShared()
        val state = VoiceSettingsFeature.State()
        shared.set(VoiceSharedKeys.voiceEngine, VoiceEngine.grok)
        for (provider in ServerVoiceProvider.entries) {
            shared.set(VoiceSharedKeys.serverVoiceProvider, provider)
            shared.read {
                assertEquals(VoiceKeySource.Phone(VoiceProvider.xAI), state.keySource)
                assertEquals("xAI Key", state.keyRowTitle)
            }
        }
    }
}
