package ai.opencode.app

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.system.NotificationDestination
import ai.opencode.feature.system.SystemAction
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class LaunchBloomTests {
    @get:Rule val isolation = TestIsolationRule()

    private val clock = TestClock()
    private val values = DependencyValues.testing().update { this[Clock] = clock }

    private fun <R> withClock(block: () -> R): R = DependencyValues.withValues(values, block)

    /** A fresh cover over a cold launch. */
    private fun arm() = withClock {
        LaunchBloomController.hasCompletedColdLaunch = false
        LaunchBloomController.startedAt = null
        LaunchBloomController.start()
    }

    @Before fun reset() = arm()

    @After fun complete() = LaunchBloomController.completeColdLaunch()

    @Test
    fun readyContentNeverMountsACoverOrReplaysOnForeground() {
        assertFalse(withClock { LaunchBloomController.keepsCover(isContentReady = true) })
        assertFalse(LaunchBloomController.isRunning)
        assertTrue(LaunchBloomController.hasCompletedColdLaunch)
        assertFalse(withClock { LaunchBloomController.keepsCover(isContentReady = false) })
        assertFalse(LaunchBloomController.isRunning)
    }

    @Test
    fun coverIsTheBlankLaunchBackgroundAndReadinessCutsStraightToContent() {
        assertTrue(withClock { LaunchBloomController.keepsCover(isContentReady = false) })
        assertTrue(LaunchBloomController.isRunning)
        // However long the wait, the cover never changes on its own.
        assertTrue(withClock { LaunchBloomController.keepsCover(isContentReady = false) })
        assertTrue(LaunchBloomController.isRunning)
        assertFalse(withClock { LaunchBloomController.keepsCover(isContentReady = true) })
        assertFalse(LaunchBloomController.isRunning)
    }

    @Test
    fun coverGivesUpAfterItsTimeoutWhenContentNeverArrives() = runTest {
        // A saved server that's unreachable never sends a snapshot, so content never becomes
        // ready; the app's own loading or offline state must show instead.
        assertTrue(LaunchBloomController.isRunning)
        assertEquals(1_500L, LaunchBloomController.TIMEOUT_MILLIS)
        clock.advance((LaunchBloomController.TIMEOUT_MILLIS - 100).milliseconds)
        assertTrue(withClock { LaunchBloomController.keepsCover(isContentReady = false) })
        assertTrue(LaunchBloomController.isRunning)
        clock.advance(200.milliseconds)
        assertFalse(withClock { LaunchBloomController.keepsCover(isContentReady = false) })
        assertFalse(LaunchBloomController.isRunning)
    }

    @Test
    fun deepLinksNotificationsSharesAndPairingCancelLaunch() = runTest {
        val shared = AppTestShared()
        val store = shared.appStore(shared.appState(), detachedScope(coroutineContext)) {
            this[UuidClient] = UuidClient.incrementing()
        }
        store.exhaustive = false

        assertTrue(LaunchBloomController.isRunning)
        store.send(AppFeature.Action.PairingURLOpened("opencode://pair?url=https%3A%2F%2Fbox.example.com&code=ocp_test"))
        assertFalse(LaunchBloomController.isRunning)
        store.state.pendingPairing?.let { store.send(AppFeature.Action.PairingCancelled(it.id)) }
        arm()
        assertTrue(LaunchBloomController.isRunning)
        store.send(AppFeature.Action.SystemActionReceived(SystemAction(destination = SystemAction.Destination.Draft(projectID = null))))
        assertFalse(LaunchBloomController.isRunning)
        arm()
        store.send(AppFeature.Action.NotificationOpened(NotificationDestination(serverKey = "https://box.example.com", sessionID = "ses_1")))
        assertFalse(LaunchBloomController.isRunning)
        arm()
        store.send(AppFeature.Action.SharesReceived(emptyList()))
        assertFalse(LaunchBloomController.isRunning)
    }
}
