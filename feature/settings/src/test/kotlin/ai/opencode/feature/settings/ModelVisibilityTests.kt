package ai.opencode.feature.settings

import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.settings.ModelVisibilityFeature.Action
import ai.opencode.feature.settings.ModelVisibilityFeature.State
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.ModelVisibility
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelCatalog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The "Settings › Models" section (`ModelVisibilitySettingsFeature` → Android's
 * [ModelVisibilityFeature]).
 *
 * Android's state reads it live from the shared key, so each step checks the key after the send.
 */
class ModelVisibilityTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")

    private val cirrus = OpenCodeModel(modelID = "cirrus-4", providerID = "opencode", name = "Cirrus 4")
    private val mini = OpenCodeModel(modelID = "cirrus-4-mini", providerID = "opencode", name = "Cirrus 4 Mini")
    private val router = OpenCodeModel(modelID = "vendor/cirrus-4", providerID = "openrouter", name = "Cirrus 4")
    private val basalt = OpenCodeModel(modelID = "basalt-pro", providerID = "halcyon", name = "Basalt Pro")
    private val catalog get() = listOf(cirrus, mini, router, basalt)

    private val key = ModelVisibility.modelVisibilityByServer

    @Test fun settingsTogglesWriteThisServersChoice() = runTest {
        val shared = SettingsTestShared()
        val models = catalog
        val store = TestStore(State(server), ModelVisibilityFeature(), this) {
            this[SharedStore] = shared.store
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                modelCatalog = { _, directory ->
                    assertNull(directory)
                    OpenCodeModelCatalog(models = models, defaultModel = null)
                },
            )
            this[HapticsClient] = HapticsRecorder().client
        }
        val id = server.persistenceID

        store.send(Action.Task) { it.copy(isLoading = true) }
        store.receive(Action.ModelsLoaded(Result.success(OpenCodeModelCatalog(models, null)))) { it.copy(isLoading = false, models = catalog) }
        assertEquals(listOf("opencode", "openrouter", "halcyon"), store.state.providers.map { it.id })

        store.send(Action.ModelToggled(mini, on = false))
        assertEquals(mapOf(id to ModelVisibility(hiddenModelIDs = setOf(mini.id))), shared.get(key))
        store.withDependencies { assertEquals("1 of 2", store.state.summary(store.state.providers[0])) }

        store.send(Action.ProviderToggled("halcyon", shown = false))
        assertEquals(mapOf(id to ModelVisibility(hiddenProviderIDs = setOf("halcyon"), hiddenModelIDs = setOf(mini.id))), shared.get(key))
        store.withDependencies { assertEquals("Hidden", store.state.summary(store.state.providers[2])) }

        store.send(Action.ModeChanged(ModelVisibility.Mode.onlyShow))
        val onlyShow = ModelVisibility(mode = ModelVisibility.Mode.onlyShow, hiddenProviderIDs = setOf("halcyon"), hiddenModelIDs = setOf(mini.id))
        assertEquals(mapOf(id to onlyShow), shared.get(key))
        store.send(Action.ModelToggled(mini, on = false))
        assertEquals(mapOf(id to onlyShow), shared.get(key))

        store.send(Action.ResetTapped)
        assertEquals(emptyMap<String, ModelVisibility>(), shared.get(key))
        store.send(Action.ResetTapped)
        store.finish()
    }

    @Test fun settingsForgetsUnavailableModelsOnceTheCatalogLoads() = runTest {
        val shared = SettingsTestShared()
        val models = catalog
        val id = server.persistenceID
        val saved = ModelVisibility(
            mode = ModelVisibility.Mode.onlyShow,
            hiddenProviderIDs = setOf("gone-provider"),
            shownModelIDs = setOf("opencode/retired-model", basalt.id),
        )
        shared.set(key, mapOf(id to saved))
        val log = HapticsRecorder()
        val store = TestStore(State(server), ModelVisibilityFeature(), this) {
            this[SharedStore] = shared.store
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(modelCatalog = { _, _ -> OpenCodeModelCatalog(models = models, defaultModel = null) })
            this[HapticsClient] = log.client
        }

        store.withDependencies { assertTrue("Nothing is stale before a catalog loads", store.state.staleEntries().isEmpty) }
        store.send(Action.ForgetUnavailableTapped)
        store.send(Action.Task) { it.copy(isLoading = true) }
        store.receive(Action.ModelsLoaded(Result.success(OpenCodeModelCatalog(models, null)))) { it.copy(isLoading = false, models = models) }
        store.withDependencies { assertEquals(listOf("gone-provider", "opencode/retired-model"), store.state.staleEntries().all) }
        store.send(Action.ForgetUnavailableTapped)
        assertEquals(mapOf(id to ModelVisibility(mode = ModelVisibility.Mode.onlyShow, shownModelIDs = setOf(basalt.id))), shared.get(key))
        store.withDependencies { assertTrue(store.state.staleEntries().isEmpty) }
        store.finish()
        assertEquals(listOf("tap"), log.value)
    }

    @Test fun aFailedCatalogReadMarksNothingStale() = runTest {
        val shared = SettingsTestShared()
        val failure = OpenCodeClientError(message = "The server returned HTTP 502.", statusCode = 502)
        shared.set(key, mapOf(server.persistenceID to ModelVisibility(hiddenModelIDs = setOf(mini.id))))
        val store = TestStore(State(server), ModelVisibilityFeature(), this) {
            this[SharedStore] = shared.store
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(modelCatalog = { _, _ -> throw failure })
        }
        store.send(Action.Task) { it.copy(isLoading = true) }
        store.receive(Action.ModelsLoaded(Result.failure(failure))) { it.copy(isLoading = false, loadError = failure.message) }
        store.withDependencies { assertTrue(store.state.staleEntries().isEmpty) }
        store.finish()
    }

    @Test fun undoingEveryChangeForgetsTheServer() = runTest {
        val shared = SettingsTestShared()
        val store = TestStore(State(server), ModelVisibilityFeature(), this) { this[SharedStore] = shared.store }
        store.send(Action.ModelToggled(mini, on = false))
        assertEquals(mapOf(server.persistenceID to ModelVisibility(hiddenModelIDs = setOf(mini.id))), shared.get(key))
        store.send(Action.ModelToggled(mini, on = true))
        assertEquals(emptyMap<String, ModelVisibility>(), shared.get(key))
        store.finish()
    }
}
