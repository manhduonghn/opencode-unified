package ai.opencode.app

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeConnectionFeature
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.HomeSnapshotDate
import ai.opencode.feature.drawer.drawerBadge
import ai.opencode.feature.drawer.indicator
import ai.opencode.feature.session.DrawerBadge
import ai.opencode.server.CachedHomeSnapshot
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * The `AppFeature` part: a relaunch restores the cached Home's running state only while it is
 * fresh.
 */
class DrawerActivityTests {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()

    private fun session(id: String, directory: String = "/app") = OpenCodeSession(
        id = id, serverTitle = id, projectID = directory, directory = directory, updatedAt = Instant.ofEpochSecond(10),
    )

    @Test
    fun aRelaunchShowsCachedRunningStateOnlyWhileItIsFresh() {
        for (age in listOf(10L, 300L)) {
            val now = Instant.ofEpochSecond(1_800_000_000)
            val saved = OpenCodeServer(url = "https://relaunch.example", username = "kit", password = "secret")
            val root = session("ses_root")
            val other = session("ses_other", directory = "/api")
            val cache = CachedHomeSnapshot(
                serverID = saved.persistenceID,
                snapshot = OpenCodeHomeSnapshot(
                    projects = emptyList(), sessions = listOf(root, other), activeSessionIDs = setOf("ses_child"),
                    activeSessionParents = mapOf("ses_child" to root.id),
                ),
                capturedAt = now.minusSeconds(age),
            )
            val values = shared.values.update { this[HomeSnapshotDate] = DateClient.constant(now) }
            val app = DependencyValues.withValues(values) {
                shared(ServerSharedKeys.savedServer).set(saved)
                shared(ServerSharedKeys.cachedHomeSnapshots).set(mapOf(saved.persistenceID to cache))
                AppFeature.State.initial()
            }
            val home = app.home
            assertNotNull(home)
            assertEquals(HomeConnectionFeature.State.ConnectionStatus.reconnecting, home!!.connectionStatus)
            val fresh = age < HomeFeature.lastKnownActivityLifetime.inWholeSeconds
            assertEquals("age $age", if (fresh) HomeFeature.State.Indicator.running else HomeFeature.State.Indicator.none, home.indicator(root))
            assertNotEquals("age $age", HomeFeature.State.Indicator.running, home.indicator(other))
        }
    }
}

class DrawerDotTests {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()

    private fun session(id: String, idle: Long? = null, viewed: Long? = null) = OpenCodeSession(
        id = id, serverTitle = id, projectID = "/app", directory = "/app",
        updatedAt = Instant.ofEpochSecond(10), idleMilliseconds = idle, viewedMilliseconds = viewed,
    )

    @Test
    fun restoredSnapshotShowsTheDotAtOnce() {
        val now = Instant.ofEpochSecond(1_800_000_000)
        val saved = OpenCodeServer(url = "https://restore-dot.example", username = "kit", password = "secret")
        val open = session("ses_open", idle = 1_000, viewed = 1_000)
        val otherUnread = session("ses_other", idle = 2_000, viewed = 1_000)
        val cache = CachedHomeSnapshot(
            serverID = saved.persistenceID,
            snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(open, otherUnread), activeSessionIDs = emptySet()),
            capturedAt = now.minusSeconds(5),
        )
        val values = shared.values.update { this[HomeSnapshotDate] = DateClient.constant(now) }
        val app = DependencyValues.withValues(values) {
            shared(ServerSharedKeys.savedServer).set(saved)
            shared(ServerSharedKeys.cachedHomeSnapshots).set(mapOf(saved.persistenceID to cache))
            shared(SharedKeys.lastViewedSessionIDs).set(mapOf(saved.persistenceID to open.id))
            AppFeature.State.initial()
        }
        val home = app.home
        assertNotNull(home)
        val restoredBadge = DependencyValues.withValues(values) { home!!.drawerBadge(excluding = open.id) }
        assertEquals(DrawerBadge.Unread, restoredBadge)
    }
}
