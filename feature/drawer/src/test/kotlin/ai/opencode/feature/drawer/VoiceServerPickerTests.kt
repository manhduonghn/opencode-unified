package ai.opencode.feature.drawer

import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeatureTestSupport.LockIsolated
import ai.opencode.feature.drawer.HomeFeatureTestSupport.append
import ai.opencode.feature.settings.HomeSettingsFeature
import ai.opencode.feature.voice.ServerVoiceClient
import ai.opencode.feature.voice.ServerVoiceModelsAnswer
import ai.opencode.feature.voice.ServerVoiceSupport
import ai.opencode.feature.voice.VoiceServerChoice
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VoiceServerPickerTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "secret", name = "My Mac")
    private val studio = OpenCodeServer(url = "http://studio.local:4096", username = "opencode", password = "studio", name = "Studio")

    /**
     * Home saves Voice Server and asks the server it names whether it brokers
     * calls, keeping the answer under that server: the chosen one, the
     * connected one once the chosen one is gone, and none for This iPhone.
     */
    @Test
    fun homeSavesTheChoiceAndAsksTheServerItNames() = runTest {
        val sharedStore = SharedStore.inMemory()
        val asked = LockIsolated(listOf<String>())
        val store = TestStore(HomeSettingsFeature.State(server = server), HomeSettingsFeature(), this) {
            this[SharedStore] = sharedStore
            this[ServerVoiceClient] = ServerVoiceClient.testValue.let { inert ->
                ServerVoiceClient(
                    checkSupport = { requested ->
                        asked.append(requested.persistenceID)
                        ServerVoiceSupport.supported
                    },
                    models = inert.models,
                    createSession = inert.createSession,
                )
            }
        }
        // `save([server, studio])`: saved, `server` in use.
        store.withDependencies {
            shared(ServerSharedKeys.savedServers).set(listOf(server, studio))
            shared(ServerSharedKeys.savedServer).set(server)
            shared(VoiceSharedKeys.voiceServerChoice).set(VoiceServerChoice.Connected)
        }
        fun choice() = store.withDependencies { shared(VoiceSharedKeys.voiceServerChoice).value }
        fun support() = store.withDependencies { shared(VoiceSharedKeys.serverVoiceSupportByServer).value }

        store.send(HomeSettingsFeature.Action.VoiceServerChanged(VoiceServerChoice.of(studio)))
        assertEquals(VoiceServerChoice.of(studio), choice())
        store.send(HomeSettingsFeature.Action.CheckServerVoiceSupport)
        store.receive(HomeSettingsFeature.Action.ServerVoiceSupportLoaded(serverID = studio.persistenceID, support = ServerVoiceSupport.supported))
        assertEquals(mapOf(studio.persistenceID to ServerVoiceSupport.supported), support())

        store.send(HomeSettingsFeature.Action.VoiceServerChanged(VoiceServerChoice.Phone))
        assertEquals(VoiceServerChoice.Phone, choice())
        store.send(HomeSettingsFeature.Action.CheckServerVoiceSupport)

        store.send(HomeSettingsFeature.Action.VoiceServerChanged(VoiceServerChoice.Saved(serverID = "srv_gone")))
        store.send(HomeSettingsFeature.Action.CheckServerVoiceSupport)
        // A stale pick falls back to Automatic, which also asks the connected server's models.
        store.receive(HomeSettingsFeature.Action.VoiceModelsLoaded(serverID = server.persistenceID, asked = 0, answer = ServerVoiceModelsAnswer.NoAnswer))
        store.receive(HomeSettingsFeature.Action.ServerVoiceSupportLoaded(serverID = server.persistenceID, support = ServerVoiceSupport.supported))
        assertEquals(listOf(studio.persistenceID, server.persistenceID), asked.value)
        assertEquals(mapOf(studio.persistenceID to ServerVoiceSupport.supported, server.persistenceID to ServerVoiceSupport.supported), support())
        store.finish()
    }
}
