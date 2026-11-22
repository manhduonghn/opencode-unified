package ai.opencode.feature.settings

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.voice.ServerVoiceClient
import ai.opencode.feature.voice.ServerVoiceModel
import ai.opencode.feature.voice.ServerVoiceModelsAnswer
import ai.opencode.feature.voice.ServerVoiceSupport
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceKeyFingerprint
import ai.opencode.feature.voice.VoiceKeySource
import ai.opencode.feature.voice.VoiceProvider
import ai.opencode.feature.voice.VoiceProviderState
import ai.opencode.feature.voice.VoiceRoute
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
import kotlin.time.Duration.Companion.seconds

class VoiceServerPickerTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "secret", name = "My Mac")
    private val studio = OpenCodeServer(url = "http://studio.local:4096", username = "opencode", password = "studio", name = "Studio")
    private val grokBox = OpenCodeServer(url = "http://grok.local:4096", username = "opencode", password = "grok", name = "Grok Box")
    private val oldBox = OpenCodeServer(url = "http://old.local:4096", username = "opencode", password = "old", name = "Old Box")
    private val gptLive = ServerVoiceModel(model = "gpt-live-1", provider = "openai", available = true)
    private val grok = ServerVoiceModel(model = "grok-voice-1", provider = "xai", available = true)
    private val geminiLive = ServerVoiceModel(model = "gemini-3.8-live", provider = "google", available = true)

    /** `servers` saved and `server` the one in use, with `choice` in Voice Provider. */
    private fun SettingsTestShared.save(servers: List<OpenCodeServer>, choice: VoiceServerChoice = VoiceServerChoice.Automatic) {
        set(ServerSharedKeys.savedServers, servers)
        set(ServerSharedKeys.savedServer, server)
        set(VoiceSharedKeys.voiceServerChoice, choice)
    }

    private fun SettingsTestShared.setSupport(support: Map<String, ServerVoiceSupport>) =
        set(VoiceSharedKeys.serverVoiceSupportByServer, support)

    private fun SettingsTestShared.savePhoneKey() {
        set(SharedKeys.openAIAPIKey, "sk-phone")
        set(SharedKeys.verifiedVoiceKeyFingerprint, VoiceKeyFingerprint.make(key = "sk-phone", model = VoiceEngine.gptLive.modelID))
    }

    private fun serverVoice(models: suspend (OpenCodeServer) -> List<ServerVoiceModel>?) = ServerVoiceClient(
        checkSupport = { throw AssertionError("Unused") },
        models = models,
        createSession = { _, _ -> throw AssertionError("Unused") },
    )

    /**
     * Settings › Voice asks only the connected server; opening Voice Provider asks the other saved
     * servers, and it lists the ones ready to take calls: not a Grok-only server (No key) or one
     * without the list (Update OpenCode). A server that doesn't answer next time reads as offline.
     */
    @Test fun settingsAsksTheConnectedServerAndTheProviderPageAsksTheOthers() = runTest {
        val shared = SettingsTestShared()
        shared.save(listOf(server, studio, grokBox, oldBox))
        val clock = TestClock()
        val round = Locked(1)
        val asked = Locked(listOf<String>())
        val order = listOf(server, studio, grokBox, oldBox).map { it.persistenceID }
        val store = TestStore(VoiceSettingsFeature.State(server = server), VoiceSettingsFeature(), this) {
            this[SharedStore] = shared.store
            this[Clock] = clock
            this[ServerVoiceClient] = serverVoice { requested ->
                asked.update { it + requested.persistenceID }
                // Each answers a second after the one before it, so answers arrive in order.
                clock.sleep(((order.indexOf(requested.persistenceID).takeIf { it >= 0 } ?: 0) + 1).seconds)
                when (requested.persistenceID) {
                    server.persistenceID -> listOf(gptLive)
                    studio.persistenceID -> if (round.value == 1) listOf(gptLive) else null
                    grokBox.persistenceID -> if (round.value == 1) listOf(grok) else listOf(grok, geminiLive)
                    else -> emptyList()
                }
            }
        }
        store.withDependencies {
            assertTrue(store.state.providerCatalog.others.isEmpty())
            assertTrue("Servers not heard from aren't listed", store.state.providerCatalog.unavailable.isEmpty())
        }

        store.send(VoiceSettingsFeature.Action.Task)
        clock.advance(1.seconds)
        store.receive(VoiceSettingsFeature.Action.VoiceModelsLoaded(server.persistenceID, 0, ServerVoiceModelsAnswer.Listed(listOf(gptLive))))
        clock.advance(10.seconds)
        assertEquals("Settings › Voice leaves the other saved servers alone", listOf(server.persistenceID), asked.value)
        store.withDependencies { assertTrue(store.state.providerCatalog.unavailable.isEmpty()) }

        store.send(VoiceSettingsFeature.Action.ProviderPageAppeared)
        clock.advance(2.seconds)
        store.receive(VoiceSettingsFeature.Action.VoiceModelsLoaded(studio.persistenceID, 0, ServerVoiceModelsAnswer.Listed(listOf(gptLive))))
        clock.advance(1.seconds)
        store.receive(VoiceSettingsFeature.Action.VoiceModelsLoaded(grokBox.persistenceID, 0, ServerVoiceModelsAnswer.Listed(listOf(grok))))
        clock.advance(1.seconds)
        store.receive(VoiceSettingsFeature.Action.VoiceModelsLoaded(oldBox.persistenceID, 0, ServerVoiceModelsAnswer.Listed(emptyList())))
        assertEquals(order.sorted(), asked.value.sorted())
        assertEquals(
            mapOf(
                server.persistenceID to ServerVoiceModelsAnswer.Listed(listOf(gptLive)),
                studio.persistenceID to ServerVoiceModelsAnswer.Listed(listOf(gptLive)),
                grokBox.persistenceID to ServerVoiceModelsAnswer.Listed(listOf(grok)),
                oldBox.persistenceID to ServerVoiceModelsAnswer.Listed(emptyList()),
            ),
            shared.get(VoiceSharedKeys.voiceModelsByServer),
        )
        store.withDependencies {
            val catalog = store.state.providerCatalog
            assertEquals("OpenAI on My Mac", catalog.active.name)
            assertEquals(VoiceProviderState.ready, catalog.active.state)
            assertTrue(catalog.isAutomatic)
            assertEquals(listOf("OpenAI on Studio"), catalog.others.map { it.name })
            assertEquals(listOf("Grok Box", "Old Box"), catalog.unavailable.map { it.title })
            assertEquals(listOf(VoiceProviderState.noKey, VoiceProviderState.needsUpdate), catalog.unavailable.map { it.state })
        }

        round.set(2)
        store.send(VoiceSettingsFeature.Action.Task)
        clock.advance(1.seconds)
        store.receive(VoiceSettingsFeature.Action.VoiceModelsLoaded(server.persistenceID, 0, ServerVoiceModelsAnswer.Listed(listOf(gptLive))))
        store.send(VoiceSettingsFeature.Action.ProviderPageAppeared)
        clock.advance(2.seconds)
        store.receive(VoiceSettingsFeature.Action.VoiceModelsLoaded(studio.persistenceID, 0, ServerVoiceModelsAnswer.NoAnswer))
        assertEquals(ServerVoiceModelsAnswer.NoAnswer, shared.get(VoiceSharedKeys.voiceModelsByServer)[studio.persistenceID])
        clock.advance(1.seconds)
        store.receive(VoiceSettingsFeature.Action.VoiceModelsLoaded(grokBox.persistenceID, 0, ServerVoiceModelsAnswer.Listed(listOf(grok, geminiLive))))
        clock.advance(1.seconds)
        store.receive(VoiceSettingsFeature.Action.VoiceModelsLoaded(oldBox.persistenceID, 0, ServerVoiceModelsAnswer.Listed(emptyList())))
        assertEquals(8, asked.value.size)
        store.withDependencies {
            val catalog = store.state.providerCatalog
            assertEquals(listOf("Gemini Live on Grok Box"), catalog.others.map { it.name })
            assertEquals(listOf(VoiceServerChoice.of(grokBox)), catalog.others.map { it.choice })
            assertEquals(listOf("Studio", "Old Box"), catalog.unavailable.map { it.title })
            assertEquals(listOf(VoiceProviderState.offline, VoiceProviderState.needsUpdate), catalog.unavailable.map { it.state })
        }
        store.finish()
    }

    /** A picked server brokers before its check answers. A pick that's no longer saved chooses automatically; This Phone brokers nothing. */
    @Test fun aPickBrokersAndAStaleOneChoosesAutomatically() {
        val shared = SettingsTestShared()
        shared.save(listOf(server, studio), choice = VoiceServerChoice.of(studio))
        val settings = VoiceSettingsFeature.State(server = server)
        shared.read {
            assertTrue(settings.broker.isEnabled && settings.broker.server == studio && !settings.broker.isAutomatic)
            assertEquals("Studio", settings.providerCatalog.active.placeName)
            assertFalse(settings.providerCatalog.isAutomatic)

            val onStudio = VoiceSettingsFeature.State(server = studio)
            assertEquals(studio, onStudio.broker.server)
        }

        shared.set(ServerSharedKeys.savedServers, listOf(server))
        shared.read {
            assertEquals(server, settings.broker.server)
            assertTrue(settings.broker.isAutomatic)
            assertTrue(settings.providerCatalog.isAutomatic)
        }

        shared.set(VoiceSharedKeys.voiceServerChoice, VoiceServerChoice.Phone)
        shared.read {
            assertTrue(!settings.broker.isEnabled && settings.broker.server == null)
            assertEquals("Your OpenAI Account on This Phone", settings.providerCatalog.active.name)
            assertEquals(VoiceProviderState.noKey, settings.providerCatalog.active.state)
        }
    }

    /**
     * A broker that lists the call's model as OpenCode's starts it through its OpenCode Console
     * connection, so the key row names OpenCode and the footer the workspace it bills. Any other
     * answer keeps the provider's key.
     */
    @Test fun theKeyRowNamesOpenCodeWhenTheBrokerHostsTheCallThroughIt() {
        val shared = SettingsTestShared()
        shared.save(listOf(server, studio))
        val hosted = ServerVoiceModel(model = "gpt-live-1", provider = "opencode", available = true)
        val settings = VoiceSettingsFeature.State(server = server)
        shared.set(
            VoiceSharedKeys.voiceModelsByServer,
            mapOf(server.persistenceID to ServerVoiceModelsAnswer.Listed(listOf(hosted)), studio.persistenceID to ServerVoiceModelsAnswer.Listed(listOf(gptLive))),
        )
        shared.read {
            assertEquals(VoiceKeySource.OpenCode, settings.keySource)
            assertEquals("Key on Your Server", settings.keyRowTitle)
            assertEquals("OpenCode", settings.keyBadgeTitle)
            assertEquals("Calls are billed to your OpenCode Console workspace.", settings.keySource.footer)

        }
        shared.set(
            VoiceSharedKeys.voiceModelsByServer,
            shared.get(VoiceSharedKeys.voiceModelsByServer) +
                (server.persistenceID to ServerVoiceModelsAnswer.Listed(listOf(ServerVoiceModel(model = "gpt-live-1", provider = "opencode", available = false)))),
        )
        shared.read { assertEquals("A listing without the connection", VoiceKeySource.Server(providerName = "OpenAI"), settings.keySource) }
        shared.set(
            VoiceSharedKeys.voiceModelsByServer,
            shared.get(VoiceSharedKeys.voiceModelsByServer) + (server.persistenceID to ServerVoiceModelsAnswer.Listed(listOf(hosted))),
        )

        shared.set(VoiceSharedKeys.voiceEngine, VoiceEngine.realtime)
        shared.read {
            assertEquals("OpenCode hosts only GPT-Live there", VoiceKeySource.Server(providerName = "OpenAI"), settings.keySource)
            assertEquals("Calls use your server’s OpenAI key.", settings.keySource.footer)
        }
        shared.set(VoiceSharedKeys.voiceEngine, VoiceEngine.gptLive)

        shared.set(VoiceSharedKeys.voiceServerChoice, VoiceServerChoice.of(studio))
        shared.read {
            assertEquals("Studio brokers with its own OpenAI key", VoiceKeySource.Server(providerName = "OpenAI"), settings.keySource)
        }

        shared.set(VoiceSharedKeys.voiceServerChoice, VoiceServerChoice.Phone)
        shared.read { assertEquals(VoiceKeySource.Phone(VoiceProvider.openAI), settings.keySource) }
    }

    /**
     * Home saves Voice Server and asks the server it names whether it brokers calls, keeping the
     * answer under that server: the chosen one, the connected one once the chosen one is gone, and
     * none for This Phone.
     */
    @Test fun homeSavesTheChoiceAndAsksTheServerItNames() = runTest {
        val shared = SettingsTestShared()
        shared.save(listOf(server, studio))
        val asked = Locked(listOf<String>())
        val store = TestStore(HomeSettingsFeature.State(server = server), HomeSettingsFeature(), this) {
            this[SharedStore] = shared.store
            this[ServerVoiceClient] = ServerVoiceClient(
                checkSupport = { requested ->
                    asked.update { it + requested.persistenceID }
                    ServerVoiceSupport.supported
                },
                models = { null },
                createSession = { _, _ -> throw AssertionError("Unused") },
            )
        }

        store.send(HomeSettingsFeature.Action.VoiceServerChanged(VoiceServerChoice.of(studio)))
        assertEquals(VoiceServerChoice.of(studio), shared.get(VoiceSharedKeys.voiceServerChoice))
        store.send(HomeSettingsFeature.Action.CheckServerVoiceSupport)
        store.receive(HomeSettingsFeature.Action.ServerVoiceSupportLoaded(studio.persistenceID, ServerVoiceSupport.supported))
        assertEquals(mapOf(studio.persistenceID to ServerVoiceSupport.supported), shared.get(VoiceSharedKeys.serverVoiceSupportByServer))

        store.send(HomeSettingsFeature.Action.VoiceServerChanged(VoiceServerChoice.Phone))
        assertEquals(VoiceServerChoice.Phone, shared.get(VoiceSharedKeys.voiceServerChoice))
        store.send(HomeSettingsFeature.Action.CheckServerVoiceSupport)

        // A pick no longer saved chooses automatically, which reads the connected server's
        // models; one that doesn't answer reads as offline.
        store.send(HomeSettingsFeature.Action.VoiceServerChanged(VoiceServerChoice.Saved(serverID = "srv_gone")))
        store.send(HomeSettingsFeature.Action.CheckServerVoiceSupport)
        store.receive(HomeSettingsFeature.Action.VoiceModelsLoaded(server.persistenceID, 0, ServerVoiceModelsAnswer.NoAnswer))
        store.receive(HomeSettingsFeature.Action.ServerVoiceSupportLoaded(server.persistenceID, ServerVoiceSupport.supported))
        assertEquals(listOf(studio.persistenceID, server.persistenceID), asked.value)
        assertEquals(
            mapOf(studio.persistenceID to ServerVoiceSupport.supported, server.persistenceID to ServerVoiceSupport.supported),
            shared.get(VoiceSharedKeys.serverVoiceSupportByServer),
        )
        store.finish()
    }

    /**
     * Never the connected server's support. A picked server without the voice routes fails rather
     * than move to the phone's key.
     */
    @Test fun everyEntryPointRoutesByTheChosenServersSupport() {
        val shared = SettingsTestShared()
        shared.save(listOf(server, studio), choice = VoiceServerChoice.of(studio))
        shared.savePhoneKey()
        shared.set(SharedKeys.voiceModeEnabled, true)
        val settings = VoiceSettingsFeature.State(server = server)

        shared.setSupport(mapOf(server.persistenceID to ServerVoiceSupport.supported, studio.persistenceID to ServerVoiceSupport.unsupported))
        shared.read {
            assertEquals(VoiceRoute.Unavailable(VoiceRoute.Reason.ServerUnsupported), settings.route)
            assertEquals(VoiceProviderState.needsUpdate, settings.providerCatalog.active.state)
        }

        shared.setSupport(mapOf(server.persistenceID to ServerVoiceSupport.unsupported, studio.persistenceID to ServerVoiceSupport.supported))
        shared.read {
            assertEquals(VoiceRoute.serverOpenAI(VoiceRoute.OpenAI.gptLive), settings.route)
            assertEquals(VoiceKeySource.Server(providerName = "OpenAI"), settings.keySource)
        }
    }

    @Test fun thisIPhoneUsesThePhonesOwnKey() {
        val shared = SettingsTestShared()
        shared.save(listOf(server, studio), choice = VoiceServerChoice.Phone)
        shared.setSupport(mapOf(server.persistenceID to ServerVoiceSupport.supported, studio.persistenceID to ServerVoiceSupport.supported))
        shared.set(SharedKeys.voiceModeEnabled, true)
        shared.read {
            assertEquals(
                VoiceRoute.Unavailable(VoiceRoute.Reason.MissingPhoneKey(VoiceEngine.gptLive)),
                VoiceSettingsFeature.State(server = server).route,
            )
        }

        shared.savePhoneKey()
        shared.read {
            val settings = VoiceSettingsFeature.State(server = server)
            assertEquals(VoiceRoute.PhoneKey(VoiceEngine.gptLive), settings.route)
            assertEquals(VoiceKeySource.Phone(VoiceProvider.openAI), settings.keySource)
        }
    }
}
