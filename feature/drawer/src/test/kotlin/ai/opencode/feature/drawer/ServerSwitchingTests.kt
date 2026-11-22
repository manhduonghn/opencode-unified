package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeConnectionFeature.State.ConnectionStatus
import ai.opencode.feature.drawer.switcher.DrawerTitle
import ai.opencode.feature.session.ServerFlowTunables
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class ServerSwitchingTests {
    @get:Rule val isolation = TestIsolationRule()

    private val snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet())

    private fun offlineClient() = OpenCodeClient.testValue.copy(
        homeSnapshot = { throw OpenCodeClientError(message = "Offline") },
    )

    @Test
    fun aReconnectedStreamKeepsANameGivenSinceItStarted() = runTest {
        val laptop = OpenCodeServer(url = "http://primary.test", username = "opencode", password = "a")
        val home = HomeFeature.State(laptop, snapshot).let {
            it.copy(connection = it.connection.copy(server = it.server.copy(name = "Work Mac")))
        }
        val store = TestStore(home, HomeFeature(), backgroundScope) {
            this[Clock] = TestClock()
            this[OpenCodeClient] = offlineClient()
        }
        store.exhaustive = false
        store.send(HomeFeature.Action.ServerConnected(laptop))
        assertEquals("Work Mac", store.state.server.name)
    }

    @Test
    fun theDrawerTitleDimsOnlyOnAConfirmedOutageAndItsMenuOffersTryAgain() {
        val one = DrawerTitle.Presentation(serverCount = 1, status = ConnectionStatus.connected)
        assertTrue(
            "One server, all well: just the name",
            !one.isDimmed && !one.hasMenu && one.accessibilityValue.isEmpty() && one.accessibilityHint == null,
        )
        val several = DrawerTitle.Presentation(serverCount = 3, status = ConnectionStatus.connected)
        assertTrue(!several.isDimmed && several.hasMenu && several.listsServers && !several.offersTryAgain)
        assertEquals("Show your servers", several.accessibilityHint)

        for (count in listOf(1, 3)) {
            val reconnecting = DrawerTitle.Presentation(serverCount = count, status = ConnectionStatus.reconnecting)
            assertTrue("A reconnect earns nothing", !reconnecting.isDimmed && !reconnecting.offersTryAgain)
            assertTrue(reconnecting.hasMenu == (count > 1) && reconnecting.accessibilityValue == "Reconnecting")

            val offline = DrawerTitle.Presentation(serverCount = count, status = ConnectionStatus.disconnected)
            assertTrue("An outage dims it; Try Again is in its menu", offline.isDimmed && offline.hasMenu && offline.offersTryAgain)
            assertEquals(count > 1, offline.listsServers)
            assertEquals("VoiceOver doesn't rely on the dimming", "Offline", offline.accessibilityValue)
            assertEquals(if (count > 1) "Show your servers" else "Offer to try again", offline.accessibilityHint)
        }

        val switching = DrawerTitle.Presentation(serverCount = 3, status = ConnectionStatus.disconnected, switchingTo = "Studio")
        assertTrue(
            "Connecting to another server isn't this one's outage",
            !switching.isDimmed && switching.accessibilityValue == "Connecting to Studio",
        )
    }

    @Test
    fun rejectedCredentialsOfferEditingInsteadOfTryAgain() {
        for (count in listOf(1, 3)) {
            val rejected = DrawerTitle.Presentation(serverCount = count, status = ConnectionStatus.disconnected, credentialsRejected = true)
            assertTrue(
                "Trying again can't fix a 401",
                rejected.isDimmed && rejected.hasMenu && rejected.offersEdit && !rejected.offersTryAgain,
            )
            assertEquals("Sign-in failed", rejected.accessibilityValue)
            assertEquals(if (count > 1) "Show your servers" else "Offer to edit the server", rejected.accessibilityHint)
        }
        val offline = DrawerTitle.Presentation(serverCount = 1, status = ConnectionStatus.disconnected)
        assertTrue(offline.offersTryAgain && !offline.offersEdit)
    }

    @Test
    fun aDroppedStreamLeavesTheTitleAloneUntilItFailsAgain() = runTest {
        val laptop = OpenCodeServer(url = "http://primary.test", username = "opencode", password = "a")
        val home = HomeFeature.State(laptop, snapshot).let {
            it.copy(connection = it.connection.copy(hasConnected = true))
        }
        val clock = TestClock()
        val store = TestStore(home, HomeFeature(), backgroundScope) {
            this[Clock] = clock
            this[OpenCodeClient] = offlineClient()
        }
        store.exhaustive = false
        fun title() = DrawerTitle.Presentation(serverCount = 1, status = store.state.connection.displayedStatus)

        store.send(HomeFeature.Action.ConnectionLost)
        assertEquals(ConnectionStatus.reconnecting, store.state.connectionStatus)
        assertTrue("A blip shows nothing", !title().isDimmed && !title().hasMenu && title().issue == null)
        store.send(HomeFeature.Action.ConnectionLost)
        assertEquals("A second failure inside the grace isn't an outage yet", ConnectionStatus.reconnecting, store.state.connectionStatus)
        clock.advance(ServerFlowTunables.grace.seconds)
        store.receive({ it is HomeFeature.Action.ReconnectingGraceElapsed }, "reconnectingGraceElapsed")
        assertTrue(title().issue == "Reconnecting" && !title().isDimmed)
        clock.advance(ServerFlowTunables.offlineGrace.seconds)
        store.receive({ it is HomeFeature.Action.OfflineGraceElapsed }, "offlineGraceElapsed")
        assertEquals(ConnectionStatus.disconnected, store.state.connectionStatus)
        assertTrue("Still failing after the grace is an outage", title().isDimmed && title().offersTryAgain)
        store.send(HomeFeature.Action.RetryConnectionTapped)
        assertEquals(ConnectionStatus.reconnecting, store.state.connectionStatus)
        assertEquals("Try Again shows its reconnect at once", "Reconnecting", title().issue)
        assertFalse("Try Again brightens it while it reconnects", title().isDimmed)
    }
}
