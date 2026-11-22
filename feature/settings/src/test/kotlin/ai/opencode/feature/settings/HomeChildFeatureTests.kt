package ai.opencode.feature.settings

import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.voice.ServerVoiceClient
import ai.opencode.feature.voice.ServerVoiceModel
import ai.opencode.feature.voice.ServerVoiceModelsAnswer
import ai.opencode.feature.voice.ServerVoiceSupport
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.OpenCodeServer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeChildFeatureTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "https://home.example", username = "", password = "")

    /** Automatic also asks the connected server's voice models, so a call knows before it starts whether the server or this phone takes it. */
    @Test fun settingsChecksSupportForItsScopedServer() = runTest {
        val shared = SettingsTestShared()
        val gptLive = ServerVoiceModel(model = "gpt-live-1", provider = "openai", available = true)
        val store = TestStore(HomeSettingsFeature.State(server = server), HomeSettingsFeature(), this) {
            this[SharedStore] = shared.store
            this[ServerVoiceClient] = ServerVoiceClient(
                checkSupport = { requested ->
                    assertEquals(server, requested)
                    ServerVoiceSupport.supported
                },
                models = { requested ->
                    assertEquals(server, requested)
                    listOf(gptLive)
                },
                createSession = { _, _ -> throw AssertionError("Unused") },
            )
        }
        store.send(HomeSettingsFeature.Action.CheckServerVoiceSupport)
        store.receive(HomeSettingsFeature.Action.VoiceModelsLoaded(server.persistenceID, 0, ServerVoiceModelsAnswer.Listed(listOf(gptLive))))
        store.receive(HomeSettingsFeature.Action.ServerVoiceSupportLoaded(server.persistenceID, ServerVoiceSupport.supported))
        assertEquals(ServerVoiceSupport.supported, shared.get(VoiceSharedKeys.serverVoiceSupportByServer)[server.persistenceID])
        assertEquals(ServerVoiceModelsAnswer.Listed(listOf(gptLive)), shared.get(VoiceSharedKeys.voiceModelsByServer)[server.persistenceID])
        store.finish()
    }
}
