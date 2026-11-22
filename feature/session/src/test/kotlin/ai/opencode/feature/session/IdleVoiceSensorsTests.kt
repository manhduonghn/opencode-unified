package ai.opencode.feature.session

import ai.opencode.core.sharing.SharedKeys
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.voice.LiveSocketWarmerClient
import ai.opencode.feature.voice.VoiceServerChoice
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Collections

class IdleVoiceSensorsTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_idle", serverTitle = "Idle", projectID = "p", directory = "/Users/kit/project", updatedAt = Instant.EPOCH)

    @Test fun aCoveredScreenStartsNoCallFromTheEar() = sessionTest { h ->
        h.sharedStore.reference(SharedKeys.voiceModeEnabled).set(true)
        h.sharedStore.reference(SharedKeys.openAIAPIKey).set("sk-test")
        val state = h.state { SessionFeature.State.session(server = server, session = session, cachedItems = emptyList()) }.copy(isCovered = true)
        val store = h.store(state)
        store.send(Action.RaisedToEar)
        store.finish()
        assertNull(store.state.voice)
    }

    @Test fun startingAPhoneKeyCallWarmsItsConnectionOnce() = sessionTest { h ->
        h.sharedStore.reference(VoiceSharedKeys.voiceServerChoice).set(VoiceServerChoice.Phone)
        h.sharedStore.reference(SharedKeys.voiceModeEnabled).set(true)
        h.sharedStore.reference(SharedKeys.openAIAPIKey).set(" sk-test ")
        val state = h.state { SessionFeature.State.draft(server, null, emptyList()) }
        assertTrue(h.state { state.voiceRoute.warmsPhoneConnection })
        val warms = Collections.synchronizedList(mutableListOf<String>())
        val store = h.store(state) {
            this[LiveSocketWarmerClient] = LiveSocketWarmerClient(warm = { warms += it }, cool = {})
        }
        store.exhaustive = false
        store.send(Action.CallButtonTapped)
        store.receive(Action.VoiceTapped)
        store.finish()
        assertEquals(listOf("sk-test"), warms.toList())
    }
}
