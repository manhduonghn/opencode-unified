package ai.opencode.feature.settings

import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.voice.ServerVoiceClient
import ai.opencode.feature.voice.ServerVoiceModel
import ai.opencode.feature.voice.ServerVoiceModelsAnswer
import ai.opencode.feature.voice.ServerVoiceProvider
import ai.opencode.feature.voice.ServerVoiceSupport
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceKeyFingerprint
import ai.opencode.feature.voice.VoicePick
import ai.opencode.feature.voice.VoiceProviderOption
import ai.opencode.feature.voice.VoiceProviderState
import ai.opencode.feature.voice.VoiceReadiness
import ai.opencode.feature.voice.VoiceServerChoice
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VoiceProviderTestsSettings {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "secret", name = "My Mac")
    private val studio = OpenCodeServer(url = "http://studio.local:4096", username = "opencode", password = "studio", name = "Studio")

    private fun SettingsTestShared.save(servers: List<OpenCodeServer>, choice: VoiceServerChoice = VoiceServerChoice.Automatic) {
        set(ServerSharedKeys.savedServers, servers)
        set(ServerSharedKeys.savedServer, server)
        set(VoiceSharedKeys.voiceServerChoice, choice)
    }

    private fun SettingsTestShared.savePhoneKey() {
        set(SharedKeys.openAIAPIKey, "sk-phone")
        set(SharedKeys.verifiedVoiceKeyFingerprint, VoiceKeyFingerprint.make(key = "sk-phone", model = VoiceEngine.gptLive.modelID))
    }

    private fun SettingsTestShared.know(
        support: Map<String, ServerVoiceSupport> = emptyMap(),
        models: Map<String, ServerVoiceModelsAnswer> = emptyMap(),
    ) {
        set(VoiceSharedKeys.serverVoiceSupportByServer, support)
        set(VoiceSharedKeys.voiceModelsByServer, models)
    }

    /**
     * An earlier “connected server” pin becomes the pin of the server connected when it's read,
     * so it no longer follows a switch.
     */
    @Test fun aLegacyConnectedPinBecomesTheConnectedServersOwn() = runTest {
        val shared = SettingsTestShared()
        shared.save(listOf(server, studio), choice = VoiceServerChoice.Connected)
        val store = TestStore(HomeSettingsFeature.State(server = server), HomeSettingsFeature(), this) {
            this[SharedStore] = shared.store
            this[ServerVoiceClient] = ServerVoiceClient(
                checkSupport = { requested ->
                    assertEquals(server, requested)
                    ServerVoiceSupport.supported
                },
                models = { throw AssertionError("A pick doesn't ask the models list") },
                createSession = { _, _ -> throw AssertionError("Unused") },
            )
        }
        store.send(HomeSettingsFeature.Action.CheckServerVoiceSupport)
        store.receive(HomeSettingsFeature.Action.ServerVoiceSupportLoaded(server.persistenceID, ServerVoiceSupport.supported))
        val choice = shared.get(VoiceSharedKeys.voiceServerChoice)
        assertEquals(VoiceServerChoice.of(server), choice)
        val pick = VoicePick.resolve(
            choice = choice,
            provider = ServerVoiceProvider.openAI,
            connected = studio,
            saved = listOf(server, studio),
            readiness = VoiceReadiness(),
        )
        assertEquals(VoicePick(VoiceProviderOption.Place.Server(server), ServerVoiceProvider.openAI, isAutomatic = false), pick)
        assertEquals(VoiceServerChoice.Automatic, VoiceServerChoice.Automatic.pinningConnected(server))
        store.finish()
    }

    /**
     * The Voice Provider row: who runs calls and where, in Settings, with OpenCode named when its
     * Console connection hosts them; Your OpenAI Account once the phone takes them.
     */
    @Test fun theRowNamesTheProviderItsPlaceAndItsState() {
        val shared = SettingsTestShared()
        shared.save(listOf(server, studio))
        val settings = VoiceSettingsFeature.State(server = server)
        shared.read {
            assertTrue(settings.showsProviderRow)
            assertEquals("GPT-Live", settings.modelRowValue)
            assertEquals("OpenAI on My Mac", settings.providerCatalog.active.name)
            assertEquals(VoiceProviderState.unknown, settings.providerCatalog.active.state)
        }

        shared.know(models = mapOf(server.persistenceID to ServerVoiceModelsAnswer.Listed(listOf(ServerVoiceModel(model = "gpt-live-1", provider = "opencode", available = true)))))
        shared.read {
            assertEquals("OpenCode on My Mac", settings.providerCatalog.active.name)
            assertEquals(VoiceProviderState.ready, settings.providerCatalog.active.state)
        }

        shared.know(models = mapOf(server.persistenceID to ServerVoiceModelsAnswer.NoAnswer))
        shared.read {
            assertEquals("My Mac", settings.providerCatalog.active.name)
            assertEquals(VoiceProviderState.offline, settings.providerCatalog.active.state)
            assertEquals(VoiceProviderState.noKey, settings.providerCatalog.account.state)
        }

        shared.savePhoneKey()
        shared.read {
            assertEquals("Your OpenAI Account on This Phone", settings.providerCatalog.active.name)
            assertEquals(VoiceProviderState.ready, settings.providerCatalog.active.state)
            assertTrue(settings.providerCatalog.isAutomatic)
            assertEquals(listOf("My Mac"), settings.providerCatalog.unavailable.map { it.title })
        }

        // Grok runs only on this phone's xAI key: its key row, no provider row.
        shared.set(VoiceSharedKeys.voiceEngine, VoiceEngine.grok)
        shared.read {
            assertFalse(settings.showsProviderRow)
            assertEquals("xAI · ${VoiceEngine.grok.title}", settings.modelRowValue)
        }
    }
}
