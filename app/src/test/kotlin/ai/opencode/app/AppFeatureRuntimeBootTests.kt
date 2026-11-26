package ai.opencode.app

import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The chat may ask the local runtime to start; the runtime stays the only daemon owner. */
class AppFeatureRuntimeBootTests {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()

    private fun TestScope.store(boot: RuntimeBootClient): TestStore<AppFeature.State, AppFeature.Action> {
        shared.run {
            shared(ServerSharedKeys.savedServer).set(null)
            shared(ServerSharedKeys.savedServers).set(emptyList())
        }
        return shared.appStore(shared.appState(), detachedScope(coroutineContext)) {
            this[OpenCodeClient] = OpenCodeClient.testValue
            this[RuntimeBootClient] = boot
        }
    }

    @Test
    fun noSavedServerAsksRuntimeToStart() = runTest {
        val asked = Locked(false)
        lateinit var boot: RuntimeBootClient
        boot = RuntimeBootClient(canStart = true) {
            asked.set(true)
            boot.complete(Result.failure(IllegalStateException("runtime unavailable")))
        }
        val store = store(boot)
        store.exhaustive = false

        store.send(AppFeature.Action.Task)
        eventually { asked.value }

        assertTrue(asked.value)
        store.skipInFlightEffects()
    }

    @Test
    fun pairedServerIsRememberedAndConnected() = runTest {
        val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "token")
        lateinit var boot: RuntimeBootClient
        boot = RuntimeBootClient(canStart = true) { boot.complete(Result.success(server)) }
        val store = store(boot)
        store.exhaustive = false

        store.send(AppFeature.Action.RuntimeStartRequested)
        eventually { shared.run { shared(ServerSharedKeys.savedServer).value } == server }

        assertEquals(server, shared.run { shared(ServerSharedKeys.savedServer).value })
        store.skipInFlightEffects()
    }

    @Test
    fun failedRuntimeStartSurfacesTheError() = runTest {
        lateinit var boot: RuntimeBootClient
        boot = RuntimeBootClient(canStart = true) { boot.complete(Result.failure(IllegalStateException("cannot start"))) }
        val store = store(boot)
        store.exhaustive = false

        store.send(AppFeature.Action.RuntimeStartRequested)
        eventually { store.state.connection is AppFeature.State.Connection.Failed }

        val failed = store.state.connection as? AppFeature.State.Connection.Failed
        assertNotNull(failed)
        assertEquals("cannot start", failed?.message)
        store.skipInFlightEffects()
    }
}
