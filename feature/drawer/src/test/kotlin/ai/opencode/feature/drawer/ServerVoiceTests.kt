package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.settings.HomeSettingsFeature
import ai.opencode.feature.voice.ServerVoiceClient
import ai.opencode.feature.voice.ServerVoiceSupport
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.model.OpenCodeHomeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ServerVoiceTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "secret")

    /**
     * 2. In HomeFeature, transport errors in checkServerVoiceSupport leave the
     * Cache `.unknown`, and event-stream `.connected` clears any cached entry.
     */
    @Test
    fun serverVoiceReprobesOnceWhenUnsupportedAndClearsCacheOnReconnect() = homeTest { h ->
        val homeStore = h.store(h.state { HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet())) }) {
            this[Clock] = ImmediateClock()
            this[ServerVoiceClient] = ServerVoiceClient.testValue.let { inert ->
                ServerVoiceClient(checkSupport = { ServerVoiceSupport.unknown }, models = inert.models, createSession = inert.createSession)
            }
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeSnapshot = { OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet()) },
                homeAttention = { _, _, _ -> emptySet() },
            )
        }
        homeStore.exhaustive = false
        fun supportByServer() = homeStore.withDependencies { ai.opencode.core.sharing.shared(VoiceSharedKeys.serverVoiceSupportByServer).value }

        homeStore.send(HomeFeature.Action.Settings(HomeSettingsFeature.Action.CheckServerVoiceSupport))
        homeStore.receive(HomeFeature.Action.Settings(HomeSettingsFeature.Action.ServerVoiceSupportLoaded(serverID = server.persistenceID, support = ServerVoiceSupport.unknown)))
        assertNull(supportByServer()[server.persistenceID])

        homeStore.send(HomeFeature.Action.Settings(HomeSettingsFeature.Action.ServerVoiceSupportLoaded(serverID = server.persistenceID, support = ServerVoiceSupport.unsupported)))
        assertEquals(ServerVoiceSupport.unsupported, supportByServer()[server.persistenceID])

        homeStore.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.Connected))
        assertNull(supportByServer()[server.persistenceID])
        h.skipInFlightEffects()
    }
}
