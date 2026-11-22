package ai.opencode.feature.drawer

import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant
import java.util.Collections
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HapticsAuditTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
    private val session = OpenCodeSession(id = "ses_1", serverTitle = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = Instant.now())

    @Test
    fun homeHaptics() = runTest {
        val log = Collections.synchronizedList(mutableListOf<String>())
        val state = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session), activeSessionIDs = emptySet()))
        val store = TestStore(state, HomeFeature(), backgroundScope) {
            this[HapticsClient] = HapticsClient(play = { log += it.name })
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(deleteSession = { _, _ -> })
        }
        store.exhaustive = false
        store.send(HomeFeature.Action.TogglePin(session.id))
        store.send(HomeFeature.Action.TogglePin(session.id))
        assertEquals(listOf("selection", "selection"), log.toList())
        store.send(HomeFeature.Action.DrawerPinZoneCrossed(entered = true))
        assertEquals(listOf("selection", "selection", "threshold"), log.toList())
        store.send(HomeFeature.Action.DrawerPinSlotChanged)
        assertEquals(listOf("selection", "selection", "threshold", "selection"), log.toList())
        store.send(HomeFeature.Action.DrawerRowDropReleased(sessionID = session.id, pinnedIndex = 0))
        assertEquals(
            "Lifting the finger over a new pin slot plays snap",
            listOf("selection", "selection", "threshold", "selection", "snap"), log.toList(),
        )
        store.send(HomeFeature.Action.DrawerRowDropped(sessionID = session.id, pinnedIndex = 0))
        assertEquals("Landing completion mutates pins without playing a second snap", 5, log.size)
        store.send(HomeFeature.Action.DrawerRowDropReleased(sessionID = session.id, pinnedIndex = 0))
        assertEquals("Releasing in the same pinned slot plays nothing", 5, log.size)
        store.send(HomeFeature.Action.DrawerPinZoneCrossed(entered = false))
        store.send(HomeFeature.Action.DrawerRowDropReleased(sessionID = session.id, pinnedIndex = null))
        store.send(HomeFeature.Action.DrawerRowDropped(sessionID = session.id, pinnedIndex = null))
        assertEquals(listOf("selection", "selection", "threshold", "selection", "snap", "threshold", "snap"), log.toList())
        store.send(HomeFeature.Action.DeleteSessionTapped(session.id))
        assertEquals("Asking to delete plays nothing; the confirmation dialog is the feedback", 7, log.size)
        store.send(HomeFeature.Action.DeleteSessionConfirmed)
        assertEquals(
            listOf("selection", "selection", "threshold", "selection", "snap", "threshold", "snap", "confirm"),
            log.toList(),
        )
    }
}
