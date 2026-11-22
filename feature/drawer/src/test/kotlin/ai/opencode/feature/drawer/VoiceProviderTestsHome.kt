package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.settings.HomeSettingsFeature
import ai.opencode.feature.voice.ServerVoiceClient
import ai.opencode.feature.voice.ServerVoiceModel
import ai.opencode.feature.voice.ServerVoiceModelsAnswer
import ai.opencode.feature.voice.ServerVoiceProvider
import ai.opencode.feature.voice.ServerVoiceSupport
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceKeyFingerprint
import ai.opencode.feature.voice.VoicePick
import ai.opencode.feature.voice.VoiceProviderOption
import ai.opencode.feature.voice.VoiceReadiness
import ai.opencode.feature.voice.VoiceServerChoice
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeHomeSnapshot
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** `aReconnectForgetsTheServersModelsAndAnyAnswerStillComing`, which drives [HomeFeature]. */
class VoiceProviderTestsHome {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "secret", name = "My Mac")
    private val keyless = ServerVoiceModel(model = "gpt-live-1", provider = "openai", available = false)
    private val empty = OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet())

    /**
     * A keyless answer cached before the server was set up doesn't outlive its reconnect:
     * Automatic goes back to the server instead of the phone's key, and an answer asked before
     * the reconnect doesn't land.
     */
    @Test fun aReconnectForgetsTheServersModelsAndAnyAnswerStillComing() = homeTest { h ->
        h.setShared(ServerSharedKeys.savedServers, listOf(server))
        h.setShared(ServerSharedKeys.savedServer, server)
        h.setShared(VoiceSharedKeys.voiceServerChoice, VoiceServerChoice.Automatic)
        h.setShared(SharedKeys.openAIAPIKey, "sk-phone")
        h.setShared(SharedKeys.verifiedVoiceKeyFingerprint, VoiceKeyFingerprint.make(key = "sk-phone", model = VoiceEngine.gptLive.modelID))
        h.setShared(VoiceSharedKeys.serverVoiceSupportByServer, mapOf(server.persistenceID to ServerVoiceSupport.supported))
        h.setShared(VoiceSharedKeys.voiceModelsByServer, mapOf<String, ServerVoiceModelsAnswer>(server.persistenceID to ServerVoiceModelsAnswer.Listed(listOf(keyless))))
        fun models() = h.shared(VoiceSharedKeys.voiceModelsByServer)
        fun pick() = VoicePick.resolve(
            choice = VoiceServerChoice.Automatic,
            provider = ServerVoiceProvider.openAI,
            connected = server,
            saved = listOf(server),
            readiness = VoiceReadiness(answers = models(), support = mapOf(server.persistenceID to ServerVoiceSupport.supported), hasPhoneKey = true),
        )
        assertEquals(VoiceProviderOption.Place.Phone, pick().place)

        val gate = CompletableDeferred<Unit>()
        val store = h.store(h.state { HomeFeature.State(server, empty) }) {
            this[Clock] = ImmediateClock()
            this[ServerVoiceClient] = ServerVoiceClient(
                checkSupport = { ServerVoiceSupport.supported },
                models = {
                    gate.await()
                    listOf(keyless)
                },
                createSession = { _, _ -> throw AssertionError("Unused") },
            )
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeSnapshot = { empty },
                homeAttention = { _, _, _ -> emptySet() },
            )
        }
        store.exhaustive = false

        // Asked before the reconnect…
        store.send(HomeFeature.Action.Settings(HomeSettingsFeature.Action.CheckServerVoiceSupport))
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.Connected))
        assertNull(models()[server.persistenceID])
        assertEquals(VoicePick(VoiceProviderOption.Place.Server(server), ServerVoiceProvider.openAI, isAutomatic = true), pick())

        // …answered after it: the server may have changed since, so it's dropped.
        gate.complete(Unit)
        store.receive(HomeFeature.Action.Settings(HomeSettingsFeature.Action.VoiceModelsLoaded(server.persistenceID, 0, ServerVoiceModelsAnswer.Listed(listOf(keyless)))))
        assertNull(models()[server.persistenceID])
        assertEquals(VoiceProviderOption.Place.Server(server), pick().place)
        h.skipInFlightEffects()
    }
}
