package ai.opencode.feature.drawer

import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeConnectionFeature.State.ConnectionStatus
import ai.opencode.feature.drawer.list.DrawerListInputs
import ai.opencode.feature.drawer.list.DrawerListItem
import ai.opencode.feature.drawer.list.DrawerListModel
import ai.opencode.feature.drawer.list.DrawerListSection
import ai.opencode.feature.drawer.style.DrawerSectionHeaderKind
import ai.opencode.feature.session.ServerConnectionNotice
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ServerFlowTests {
    @get:Rule val isolation = TestIsolationRule()

    private val snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet())
    private val laptop = OpenCodeServer(url = "http://192.168.1.20:4096", username = "opencode", password = "a", name = "Laptop")
    private val studio = OpenCodeServer(url = "http://10.0.0.4:4096", username = "opencode", password = "b", name = "Studio")

    @Test
    fun theNoteIsSeenOnceWithTheDrawer() = runTest {
        val home = HomeFeature.State(laptop, snapshot).copy(
            isDrawerOpen = true,
            serverSwitchNote = ServerSwitchNote(server = studio, returnedTo = "Laptop", reason = ServerSwitchNote.Reason.Unreachable),
        )
        val store = TestStore(home, HomeFeature(), backgroundScope)
        store.send(HomeFeature.Action.SessionDrawerArrived(isOpen = false)) {
            it.copy(isDrawerOpen = false, serverSwitchNote = null)
        }
    }

    // Copy

    @Test
    fun theMenuShowsEachServersStatusAsAGlyphAndAWord() {
        fun status(
            server: OpenCodeServer,
            connection: ConnectionStatus = ConnectionStatus.connected,
            rejected: Boolean = false,
            switchingTo: OpenCodeServer? = null,
            reachability: Map<String, ServerReachability> = emptyMap(),
        ): ServerMenuStatus? = ServerMenuStatus.of(
            server, current = laptop, status = connection, credentialsRejected = rejected,
            switchingTo = switchingTo, reachability = reachability,
        )
        assertEquals(ServerMenuStatus.connected, status(laptop))
        assertEquals(ServerMenuStatus.connecting, status(laptop, ConnectionStatus.reconnecting))
        assertEquals(ServerMenuStatus.unreachable, status(laptop, ConnectionStatus.disconnected))
        assertEquals(ServerMenuStatus.signInFailed, status(laptop, ConnectionStatus.disconnected, rejected = true))
        assertNull("nothing known yet", status(studio))
        assertEquals(ServerMenuStatus.connecting, status(studio, switchingTo = studio))
        assertEquals(
            ServerMenuStatus.signInFailed,
            status(studio, reachability = mapOf(studio.persistenceID to ServerReachability.signInFailed)),
        )
        val all = listOf(ServerMenuStatus.connected, ServerMenuStatus.connecting, ServerMenuStatus.unreachable, ServerMenuStatus.signInFailed)
        assertEquals(listOf("Connected", "Connecting…", "Unreachable", "Sign-in failed"), all.map { it.title })
    }

    @Test
    fun thePillSaysWhatTheConnectionIsDoing() {
        fun notice(
            status: ConnectionStatus,
            rejected: Boolean = false,
            error: String? = null,
            switching: Boolean = false,
        ): ServerConnectionNotice? = ServerConnectionNotice(
            status = status, credentialsRejected = rejected, errorMessage = error, server = studio, isSwitching = switching,
        )
        assertNull(notice(ConnectionStatus.connected))
        assertNull("the drawer shows a switch", notice(ConnectionStatus.disconnected, switching = true))

        val reconnecting = notice(ConnectionStatus.reconnecting)
        assertEquals("Reconnecting to Studio…", reconnecting?.title)
        assertNull(reconnecting?.action)
        assertEquals(ServerConnectionNotice.Action.retry, reconnecting?.primary)
        var inGrace = HomeConnectionFeature.State(server = studio).copy(connectionStatus = ConnectionStatus.reconnecting)
        val early = ServerConnectionNotice(
            status = inGrace.displayedStatus, credentialsRejected = false, errorMessage = null, server = studio, isSwitching = false,
        )
        assertNull("a reconnect waits out its grace before the pill shows", early)
        inGrace = inGrace.copy(showsRecovery = true)
        assertEquals(ConnectionStatus.reconnecting, inGrace.displayedStatus)

        val failed = notice(ConnectionStatus.disconnected, error = "Tailscale is off")
        assertEquals("Couldn’t connect to Studio", failed?.title)
        assertEquals("Tailscale is off", failed?.detail)
        assertEquals("Try Again", failed?.action)
        assertEquals(ServerConnectionNotice.Action.retry, failed?.primary)
        assertNull("no title said twice", notice(ConnectionStatus.disconnected, error = "Couldn’t connect to the server")?.detail)

        val signIn = notice(ConnectionStatus.disconnected, rejected = true, error = "Unauthorized")
        assertEquals("Couldn’t sign in to Studio", signIn?.title)
        assertNull(signIn?.detail)
        assertEquals("Pair Again", signIn?.action)
        assertEquals(ServerConnectionNotice.Action.pairAgain, signIn?.primary)
    }

    // Drawer list

    @Test
    fun ghostRowsHoldTheListUntilTheServerAnswers() {
        var home = HomeFeature.State(laptop, snapshot).copy(
            switchingTo = studio,
            serverSwitchNote = ServerSwitchNote(server = studio, returnedTo = "Laptop", reason = ServerSwitchNote.Reason.Unreachable),
        )
        var inputs = DrawerListInputs(
            selectedSessionID = null, reducedMotion = false, increasedContrast = false,
        )
        fun build() = DrawerListModel.build(
            state = home, inputs = inputs, liftedID = null,
            now = Instant.ofEpochSecond(100), timestamp = { _, _ -> "" }, project = { _, _ -> null },
        )
        inputs = inputs.copy(serverSwap = ServerSwapTarget(current = laptop, switchingTo = studio))
        val connecting = build()
        assertEquals("the drawer shows the server picked from the start", studio.persistenceID, connecting.serverID)
        assertEquals(
            listOf(DrawerListItem.Header(DrawerSectionHeaderKind.Recents)) +
                (0 until DrawerListModel.ghostCount).map { DrawerListItem.Ghost(it) },
            connecting.sections[DrawerListSection.recents],
        )

        home = home.copy(switchingTo = null)
        inputs = inputs.copy(serverSwap = ServerSwapTarget(current = laptop, switchingTo = null))
        val back = build()
        assertEquals(laptop.persistenceID, back.serverID)
        assertEquals(DrawerListItem.ServerNote, back.sections[DrawerListSection.top]?.firstOrNull())

        // Off, the list is today's.
        inputs = inputs.copy(serverSwap = null)
        val off = build()
        assertNotEquals(true, off.sections[DrawerListSection.top]?.contains(DrawerListItem.ServerNote))
    }
}
