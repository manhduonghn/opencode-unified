package ai.opencode.app

import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.system.SessionDirectory
import ai.opencode.feature.system.liveactivity.SessionActivityAttributes
import ai.opencode.feature.system.liveactivity.SessionActivityAttributes.ContentState.Phase
import ai.opencode.feature.system.liveactivity.SessionLiveActivity
import ai.opencode.feature.system.liveactivity.SessionLiveActivityClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** The `AppFeature` part: AppFeature ends a followed run's activity when its Home goes. */
class SessionLiveActivityTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
    private val open = OpenCodeSession(
        id = "ses_open", serverTitle = "Fix upload", projectID = "p", directory = "/work", updatedAt = Instant.ofEpochSecond(2_000),
    )
    private val other = OpenCodeSession(
        id = "ses_other", serverTitle = "Other", projectID = "p", directory = "/work", updatedAt = Instant.ofEpochSecond(1_000),
    )
    private val t0 = Instant.ofEpochSecond(3_000)
    private val shared = AppTestShared()

    private val attributes: SessionActivityAttributes
        get() = SessionActivityAttributes(serverKey = SessionDirectory.serverKey(server.persistenceID), sessionID = open.id)

    private fun working() = SessionActivityAttributes.ContentState(title = "Fix upload", phase = Phase.working, startedAt = t0)

    /** Home with the open session's run followed and the screen elsewhere. */
    private fun following(sawServerRun: Boolean = false, active: Boolean = false): HomeFeature.State =
        HomeFeature.State(
            server,
            OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(open, other), activeSessionIDs = if (active) setOf(open.id) else emptySet()),
        ).copy(sessionActivity = SessionLiveActivity.Tracked(attributes = attributes, content = working(), sawServerRun = sawServerRun))

    private suspend fun finish(store: TestStore<AppFeature.State, AppFeature.Action>) {
        repeat(50) { yield() }
        store.skipReceivedActions()
    }

    // MARK: Leaving Home

    @Test
    fun leavingHomeEndsAFollowedRunsActivity() = runTest {
        val app = shared.run {
            AppFeature.State.initial().copy(connection = AppFeature.State.Connection.Connected, home = following(active = true))
        }
        val pushes = Locked<List<SessionActivityAttributes.ContentState?>>(emptyList())
        val effects = detachedScope(coroutineContext)
        val store = shared.appStore(app, effects) {
            this[ExperimentalFeatures] = ExperimentalFeatures.enabling(setOf(ExperimentalFeature.sessionLiveActivity))
            this[SessionLiveActivityClient] = SessionLiveActivityClient(update = { content, _ -> pushes.update { it + content } }, endAll = {})
        }
        store.exhaustive = false

        store.send(AppFeature.Action.PairingCodeScanned("not a pairing code"))
        finish(store)
        assertNull(store.state.home)
        assertEquals(listOf<SessionActivityAttributes.ContentState?>(null), pushes.value)
        effects.skipInFlightEffects()
    }
}
