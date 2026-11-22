package ai.opencode.feature.session

import ai.opencode.feature.composer.modelpicker.ModelPickerSection
import ai.opencode.feature.composer.modelpicker.OnePickerCatalog
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertEquals
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertNull
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertTrue
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.ModelVisibility
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeSession
import org.junit.Test
import java.time.Instant

class ModelVisibilityTests {
    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
    private val other = OpenCodeServer(url = "http://localhost:10", username = "", password = "")

    private val cirrus = OpenCodeModel(modelID = "cirrus-4", providerID = "opencode", name = "Cirrus 4")
    private val mini = OpenCodeModel(modelID = "cirrus-4-mini", providerID = "opencode", name = "Cirrus 4 Mini")
    private val router = OpenCodeModel(modelID = "vendor/cirrus-4", providerID = "openrouter", name = "Cirrus 4")
    private val basalt = OpenCodeModel(modelID = "basalt-pro", providerID = "halcyon", name = "Basalt Pro")
    private val catalog: List<OpenCodeModel> get() = listOf(cirrus, mini, router, basalt)

    // MARK: Pickers

    private fun Harness.sessionState(visibility: ModelVisibility?, experiment: Boolean = true): State {
        val session = OpenCodeSession(id = "ses_1", serverTitle = "Chat", projectID = "p", directory = "/project", updatedAt = Instant.EPOCH, model = cirrus.ref)
        val state = state { State.session(server = server, session = session) }.copy(models = catalog)
        state.hideModelsExperiment.set(experiment)
        if (visibility != null) state.modelVisibilityByServer.set(mapOf(server.persistenceID to visibility))
        return state
    }

    /** The sections One Picker lists, from the models the picker may show. */
    private fun onePickerSections(state: State): List<ModelPickerSection> {
        val picker = state.modelPicker
        return OnePickerCatalog.sections(
            models = picker.pickerModels,
            favorites = picker.favoriteModelIDs,
            recents = emptyList(),
            currentModelID = null,
            filter = picker.modelFilter,
            recentLimit = 3,
            showsAllModels = true,
        )
    }

    @Test
    fun experimentOffListsTheWholeCatalog() = sessionTest { h ->
        val state = h.sessionState(ModelVisibility(hiddenModelIDs = setOf(mini.id, basalt.id)), experiment = false)
        assertEquals(catalog, state.pickerModels)
        assertEquals(catalog, state.filteredModels)
        assertEquals(0, state.hiddenModelCount)
        assertEquals(catalog, onePickerSections(state).flatMap { it.models })
    }

    @Test
    fun bothPickersListOnlyVisibleModelsUntilShowAll() = sessionTest { h ->
        val state = h.sessionState(ModelVisibility(hiddenProviderIDs = setOf("openrouter"), hiddenModelIDs = setOf(basalt.id)))
        val models = catalog
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(modelCatalog = { _, _ -> OpenCodeModelCatalog(models = models, defaultModel = null) })
        }
        assertEquals(listOf(cirrus, mini), store.state.filteredModels, "Today")
        assertEquals(listOf(cirrus, mini), onePickerSections(store.state).flatMap { it.models }, "One Picker")
        assertEquals(2, store.state.hiddenModelCount)

        store.send(Action.ShowAllModelsToggled) { it.copy(showsAllModels = true) }
        assertEquals(catalog, store.state.filteredModels)
        assertEquals(2, store.state.hiddenModelCount, "Show Fewer keeps the count")

        store.send(Action.ModelListClosed) { it.copy(showsAllModels = false) }
        assertEquals(listOf(cirrus, mini), store.state.filteredModels)
    }

    /**
     * Every model hidden and no current model: the list is empty but Show
     * All still counts them, so the pickers show it rather than No Models.
     */
    @Test
    fun hidingEverythingStillOffersShowAll() = sessionTest { h ->
        var state = h.sessionState(ModelVisibility(hiddenProviderIDs = setOf("opencode", "openrouter", "halcyon"))).copy(currentModel = null)
        assertNull(state.currentModelInfo)
        assertTrue(state.filteredModels.isEmpty())
        assertTrue(onePickerSections(state).isEmpty())
        assertEquals(catalog.size, state.hiddenModelCount, "The empty state gives way to Show All")
        state = state.copy(showsAllModels = true)
        assertEquals(catalog, state.filteredModels)
    }

    @Test
    fun staleOnlyShowChoicesNeverEmptyThePickers() = sessionTest { h ->
        val state = h.sessionState(ModelVisibility(mode = ModelVisibility.Mode.onlyShow, shownModelIDs = setOf("opencode/retired-model")))
            .copy(currentModel = null)
        assertEquals(catalog, state.filteredModels)
        assertEquals(0, state.hiddenModelCount)
    }

    @Test
    fun eachServerKeepsItsOwnChoice() = sessionTest { h ->
        val state = h.sessionState(null)
        state.modelVisibilityByServer.set(mapOf(other.persistenceID to ModelVisibility(hiddenModelIDs = setOf(mini.id))))
        assertEquals(catalog, state.pickerModels)
    }
}
