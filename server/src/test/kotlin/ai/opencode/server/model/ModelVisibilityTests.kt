package ai.opencode.server.model

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ModelVisibilityTests {
    @get:Rule val isolation = TestIsolationRule()

    private val cirrus = OpenCodeModel(modelID = "cirrus-4", providerID = "opencode", name = "Cirrus 4")
    private val mini = OpenCodeModel(modelID = "cirrus-4-mini", providerID = "opencode", name = "Cirrus 4 Mini")
    private val router = OpenCodeModel(modelID = "vendor/cirrus-4", providerID = "openrouter", name = "Cirrus 4")
    private val basalt = OpenCodeModel(modelID = "basalt-pro", providerID = "halcyon", name = "Basalt Pro")
    private val catalog get() = listOf(cirrus, mini, router, basalt)

    @Test
    fun hideListsEverythingButTheHiddenModelsAndProviders() {
        val visibility = ModelVisibility()
            .setModel(mini, on = false)
            .setProvider("openrouter", shown = false)
        assertEquals(listOf(cirrus, basalt), visibility.visible(catalog, keepingCurrentID = null))
    }

    @Test
    fun onlyShowListsJustTheChosenModelsAndKeepsEachList() {
        var visibility = ModelVisibility().setModel(mini, on = false)
        visibility = visibility.copy(mode = ModelVisibility.Mode.onlyShow)
        assertEquals("Nothing chosen yet lists everything", catalog, visibility.visible(catalog, keepingCurrentID = null))
        visibility = visibility.setModel(basalt, on = true)
        assertEquals(listOf(basalt), visibility.visible(catalog, keepingCurrentID = null))
        visibility = visibility.copy(mode = ModelVisibility.Mode.hide)
        assertEquals("Hide's list survives the switch", listOf(cirrus, router, basalt), visibility.visible(catalog, keepingCurrentID = null))
    }

    @Test
    fun aHiddenProviderWinsOverOnlyShow() {
        val visibility = ModelVisibility(mode = ModelVisibility.Mode.onlyShow, shownModelIDs = setOf(basalt.id, cirrus.id))
            .setProvider("halcyon", shown = false)
        assertEquals(listOf(cirrus), visibility.visible(catalog, keepingCurrentID = null))
    }

    @Test
    fun onlyShowIgnoresChosenModelsTheCatalogNoLongerOffers() {
        val stale = ModelVisibility(mode = ModelVisibility.Mode.onlyShow, shownModelIDs = setOf("opencode/retired-model"))
        assertEquals("Only stale choices list everything", catalog, stale.visible(catalog, keepingCurrentID = null))
        assertTrue(stale.isVisible(mini, catalog))
        val mixed = ModelVisibility(mode = ModelVisibility.Mode.onlyShow, shownModelIDs = setOf("opencode/retired-model", basalt.id))
        assertEquals(listOf(basalt), mixed.visible(catalog, keepingCurrentID = null))
    }

    @Test
    fun staleEntriesNameWhatTheCatalogDroppedAndCanBeForgotten() {
        var visibility = ModelVisibility(
            mode = ModelVisibility.Mode.onlyShow,
            hiddenProviderIDs = setOf("gone-provider", "halcyon"),
            hiddenModelIDs = setOf("opencode/old-hidden", mini.id),
            shownModelIDs = setOf("opencode/old-shown", basalt.id),
        )
        val stale = visibility.staleEntries(catalog)
        assertEquals(setOf("gone-provider"), stale.providerIDs)
        assertEquals(setOf("opencode/old-hidden", "opencode/old-shown"), stale.modelIDs)
        assertEquals(listOf("gone-provider", "opencode/old-hidden", "opencode/old-shown"), stale.all)
        visibility = visibility.forget(stale)
        assertEquals(
            ModelVisibility(mode = ModelVisibility.Mode.onlyShow, hiddenProviderIDs = setOf("halcyon"), hiddenModelIDs = setOf(mini.id), shownModelIDs = setOf(basalt.id)),
            visibility,
        )
        assertTrue(visibility.staleEntries(catalog).isEmpty)
    }

    @Test
    fun theCurrentModelStaysListed() {
        val visibility = ModelVisibility(hiddenModelIDs = setOf(mini.id))
        assertEquals(catalog, visibility.visible(catalog, keepingCurrentID = mini.id))
    }
}
