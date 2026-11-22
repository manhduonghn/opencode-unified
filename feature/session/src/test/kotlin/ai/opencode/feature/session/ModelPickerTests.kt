package ai.opencode.feature.session

import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.TestStore
import ai.opencode.feature.composer.modelpicker.ModelPickerSection
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.Gate
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertEquals
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertNull
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertTrue
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.HapticsRecorder
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.assertRemembersModelNames
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.testServer
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.ModelSearch
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import org.junit.Test
import java.time.Instant

class ModelPickerTests {
    private val cirrusZen = OpenCodeModel(modelID = "cirrus-4", providerID = "opencode", name = "Cirrus 4", variants = listOf("low", "high"))
    private val mini = OpenCodeModel(modelID = "cirrus-4-mini", providerID = "opencode", name = "Cirrus 4 Mini")
    private val cirrusRouter =
        OpenCodeModel(modelID = "vendor/cirrus-4", providerID = "openrouter", name = "Cirrus 4", variants = listOf("low", "medium", "high", "max"))
    private val basalt = OpenCodeModel(modelID = "basalt-pro", providerID = "halcyon", name = "Basalt Pro")

    // MARK: Search

    private val sonnet45 = OpenCodeModel(modelID = "claude-sonnet-4-5-20250929", providerID = "anthropic", name = "Claude Sonnet 4.5")
    private val sonnet4 = OpenCodeModel(modelID = "claude-sonnet-4-20250514", providerID = "anthropic", name = "Claude Sonnet 4")
    private val opus = OpenCodeModel(modelID = "claude-opus-4-1", providerID = "anthropic", name = "Claude Opus 4.1")
    private val zenSonnet = OpenCodeModel(modelID = "claude-sonnet-4-5", providerID = "opencode", name = "Claude Sonnet 4.5")
    private val zenMini = OpenCodeModel(modelID = "gpt-5-mini", providerID = "opencode", name = "GPT-5 Mini")
    private val kimi = OpenCodeModel(modelID = "kimi-k2", providerID = "opencode-go", name = "Kimi K2")
    private val gpt5 = OpenCodeModel(modelID = "gpt-5", providerID = "openai", name = "GPT-5")
    private val openAIMini = OpenCodeModel(modelID = "gpt-5-mini", providerID = "openai", name = "GPT-5 Mini")
    private val routerOpus = OpenCodeModel(modelID = "anthropic/claude-opus-4.1", providerID = "openrouter", name = "Claude Opus 4.1")
    private val gemini = OpenCodeModel(modelID = "gemini-2.5-pro", providerID = "google", name = "Gemini 2.5 Pro")
    private val catalog: List<OpenCodeModel>
        get() = listOf(sonnet45, sonnet4, opus, zenSonnet, zenMini, kimi, gpt5, openAIMini, routerOpus, gemini)

    @Test
    fun bothPickersShareTheSearch() = sessionTest { h ->
        val sections = ModelPickerSection.sections(models = catalog, favorites = setOf(opus.id), filter = "anthropic opus")
        assertEquals(listOf("Anthropic", "OpenRouter"), sections.map { it.title })
        val plain = h.state(model = null).copy(models = catalog, modelFilter = "anthropic opus")
        assertEquals(listOf(opus, routerOpus), plain.filteredModels)
        assertEquals(listOf("Anthropic", "OpenRouter"), plain.modelPicker.modelSections.map { it.title })
    }

    // MARK: Reducer

    private fun Harness.state(model: OpenCodeModelRef?): State {
        val session = OpenCodeSession(id = "ses_1", serverTitle = "Chat", projectID = "p", directory = "/project", updatedAt = Instant.EPOCH, model = model)
        return state { State.session(server = testServer, session = session) }.copy(models = listOf(cirrusZen, cirrusRouter, mini, basalt))
    }

    private fun Harness.catalogStore(state: State, catalog: OpenCodeModelCatalog): TestStore<State, Action> = store(state) {
        this[OpenCodeClient] = this[OpenCodeClient].copy(modelCatalog = { _, _ -> catalog })
    }

    /** Opening the list from the composer refreshes the catalog, but no sheet. */
    @Test
    fun openingTheListRefreshesTheCatalogWithoutASheet() = sessionTest { h ->
        val initial = h.state(model = cirrusZen.ref(variant = "high")).copy(modelFilter = "old search")
        val store = h.catalogStore(initial, OpenCodeModelCatalog(models = listOf(cirrusZen, basalt), defaultModel = null))
        store.send(Action.ModelListOpened)
        store.receive(Action.ModelsResponse::class) { it.copy(models = listOf(cirrusZen, basalt)) }
        store.state.assertRemembersModelNames(listOf(cirrusZen, basalt))
        assertNull(store.state.activePicker)
    }

    /**
     * The catalog refresh arrives after the list has grown, maybe after a
     * query was typed: it never touches the search. Folding the list away
     * clears it, so the next opening starts empty.
     */
    @Test
    fun openingKeepsATypedQueryAndClosingClearsIt() = sessionTest { h ->
        // "bas" typed into Search Models before the deferred refresh is sent.
        val typed = h.state(model = cirrusZen.ref(variant = "high")).copy(modelFilter = "bas")
        val store = h.catalogStore(typed, OpenCodeModelCatalog(models = listOf(cirrusZen, basalt), defaultModel = null))
        store.send(Action.ModelListOpened)
        store.receive(Action.ModelsResponse::class) { it.copy(models = listOf(cirrusZen, basalt)) }
        store.state.assertRemembersModelNames(listOf(cirrusZen, basalt))
        assertEquals("bas", store.state.modelFilter, "A query typed before the refresh lands is kept")
        store.send(Action.ModelListClosed) { it.copy(modelFilter = "") }
    }

    /**
     * A pick from a provider's section, and a level from the meter, only change
     * the composer: both wait for the next send (no `switchModel` here).
     */
    @Test
    fun picksAndLevelsWaitForTheNextSend() = sessionTest { h ->
        val store = h.store(h.state(model = cirrusZen.ref(variant = "high")))
        assertEquals(listOf(null, "low", "high"), store.state.currentLevelScale?.levels)
        assertEquals("high", store.state.currentLevel)

        // Another provider's Cirrus 4 offers High too, so it keeps it.
        store.send(Action.ModelPicked(cirrusRouter)) {
            it.copy(currentModel = cirrusRouter.ref(variant = "high"), pendingModel = cirrusRouter.ref(variant = "high"))
        }
        assertEquals(5, store.state.currentLevelScale?.count)
        store.send(Action.VariantPicked("max")) {
            it.copy(currentModel = cirrusRouter.ref(variant = "max"), pendingModel = cirrusRouter.ref(variant = "max"))
        }
        // A model with no levels has no meter.
        store.send(Action.ModelPicked(mini)) { it.copy(currentModel = mini.ref, pendingModel = mini.ref) }
        assertNull(store.state.currentLevelScale)
    }

    @Test
    fun holdingARowFavoritesThatProvidersModel() = sessionTest { h ->
        val store = h.store(h.state(model = null))
        store.send(Action.ToggleFavoriteModel(cirrusRouter))
        assertEquals(setOf(cirrusRouter.id), store.state.favoriteModelIDs.value)
        assertEquals("Favorites", store.state.modelPicker.modelSections.firstOrNull()?.title)
        assertEquals(listOf(cirrusRouter), store.state.modelPicker.modelSections.firstOrNull()?.models)
        store.send(Action.ToggleFavoriteModel(cirrusRouter))
        assertEquals(emptySet(), store.state.favoriteModelIDs.value)
    }

    /**
     * The default plain picker sheet: favorites sort ahead of the rest, the
     * composer chip names the model and its thinking variant ("Cirrus 4 · High"),
     * choosing a variant from the Thinking menu keeps the sheet open, and
     * choosing a model closes it.
     */
    @Test
    fun plainPickerSheetSortsFavoritesAndKeepsSheetOpenWhenChangingThinking() = sessionTest { h ->
        val initial = h.state(model = cirrusZen.ref(variant = "high")).copy(modelFilter = "stale")
        initial.favoriteModelIDs.set(setOf(basalt.id))
        val store = h.catalogStore(initial, OpenCodeModelCatalog(models = listOf(cirrusZen, cirrusRouter, mini, basalt), defaultModel = null))
        assertEquals("Cirrus 4 · High", store.state.currentModelLabel)
        store.send(Action.ModelButtonTapped) { it.copy(activePicker = State.ActivePicker.model, modelFilter = "") }
        store.receive(Action.ModelsResponse::class)
        store.state.assertRemembersModelNames(listOf(cirrusZen, cirrusRouter, mini, basalt))
        assertEquals(
            listOf(basalt.id, cirrusZen.id, cirrusRouter.id, mini.id),
            (store.state.favoriteModels + store.state.unfavoriteModels).map { it.id },
        )
        store.send(Action.VariantPicked("low")) {
            it.copy(currentModel = cirrusZen.ref(variant = "low"), pendingModel = cirrusZen.ref(variant = "low"))
        }
        assertEquals(State.ActivePicker.model, store.state.activePicker, "Changing Thinking keeps the Model sheet open")
        assertEquals("Cirrus 4 · Low", store.state.currentModelLabel)
        store.send(Action.ModelPicked(cirrusRouter)) {
            it.copy(currentModel = cirrusRouter.ref(variant = "low"), pendingModel = cirrusRouter.ref(variant = "low"), activePicker = null)
        }
    }

    /**
     * Opening the model picker while the initial model catalog fetch is still
     * in flight cancels the hung request and starts a fresh fetch instead of
     * dropping the retry behind `isLoadingModels`.
     */
    @Test
    fun openingPickerWhileLoadingRestartsModelCatalogFetchAndCancelsHungLoad() = sessionTest { h ->
        val attempts = LockIsolated(0)
        val firstCancelled = LockIsolated(false)
        val firstStarted = Gate()

        val initial = h.state(model = null).copy(models = emptyList())
        val store = h.store(initial) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                modelCatalog = { _, _ ->
                    val attempt = attempts.increment()
                    if (attempt == 1) {
                        firstStarted.open()
                        try {
                            awaitCancellation()
                        } catch (cancellation: CancellationException) {
                            firstCancelled.setValue(true)
                            throw cancellation
                        }
                    }
                    OpenCodeModelCatalog(models = listOf(cirrusZen, basalt), defaultModel = cirrusZen)
                },
            )
        }

        // Initial load begins when the picker opens the first time.
        store.send(Action.ModelListOpened) { it.copy(isLoadingModels = true) }
        firstStarted.await()
        assertEquals(1, attempts.value)

        // Reopening the picker while `isLoadingModels` is still true restarts the
        // request cleanly rather than returning `.none`.
        store.send(Action.ModelButtonTapped) { it.copy(activePicker = State.ActivePicker.model) }
        store.receive(Action.ModelsResponse::class) {
            it.copy(isLoadingModels = false, models = listOf(cirrusZen, basalt), currentModel = cirrusZen.ref)
        }
        store.state.assertRemembersModelNames(listOf(cirrusZen, basalt))
        assertEquals(2, attempts.value)
        assertTrue(firstCancelled.value)
    }

    /**
     * Models that plugins add after the first read show up without reopening
     * the session: each change rereads the catalog, a burst leaves one read
     * standing, and its answer replaces the partial list.
     */
    @Test
    fun aCatalogChangeRereadsModelsAndReplacesAPartialList() = sessionTest { h ->
        val attempts = LockIsolated(0)
        val firstCancelled = LockIsolated(false)
        val firstStarted = Gate()

        val initial = h.state(model = cirrusZen.ref).copy(models = listOf(cirrusZen))
        val store = h.store(initial) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                modelCatalog = { _, _ ->
                    if (attempts.increment() == 1) {
                        firstStarted.open()
                        try {
                            awaitCancellation()
                        } catch (cancellation: CancellationException) {
                            firstCancelled.setValue(true)
                            throw cancellation
                        }
                    }
                    OpenCodeModelCatalog(models = listOf(cirrusZen, basalt), defaultModel = cirrusZen)
                },
            )
        }

        store.send(Action.ModelsChanged)
        firstStarted.await()
        store.send(Action.ModelsChanged)
        store.receive(Action.ModelsResponse::class) { it.copy(models = listOf(cirrusZen, basalt)) }
        store.state.assertRemembersModelNames(listOf(cirrusZen, basalt))
        assertEquals(2, attempts.value)
        assertTrue(firstCancelled.value)
    }

    /**
     * A failed model catalog fetch records `modelsLoadError` rather than
     * leaving an indistinguishable empty state, and retrying clears the error.
     */
    @Test
    fun failedModelCatalogRecordsErrorAndRetriesOnReopen() = sessionTest { h ->
        val shouldFail = LockIsolated(true)
        val timeoutError = OpenCodeClientError(message = "The request timed out.")
        val initial = h.state(model = null).copy(models = emptyList())
        val store = h.store(initial) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                modelCatalog = { _, _ ->
                    if (shouldFail.value) throw timeoutError
                    OpenCodeModelCatalog(models = listOf(cirrusZen), defaultModel = cirrusZen)
                },
            )
        }

        store.send(Action.ModelListOpened) { it.copy(isLoadingModels = true) }
        store.receive(Action.ModelsResponse::class) { it.copy(isLoadingModels = false, modelsLoadError = timeoutError.message) }

        shouldFail.setValue(false)
        store.send(Action.ModelListOpened) { it.copy(isLoadingModels = true, modelsLoadError = null) }
        store.receive(Action.ModelsResponse::class) {
            it.copy(isLoadingModels = false, models = listOf(cirrusZen), currentModel = cirrusZen.ref)
        }
        store.state.assertRemembersModelNames(listOf(cirrusZen))
    }

    // MARK: - Fused + Stack Model Picker

    private val opus55 = OpenCodeModel(modelID = "claude-opus-5-5", providerID = "anthropic", name = "Claude Opus 5.5", variants = listOf("low", "medium", "high", "max"))
    private val sonnet55Anthropic =
        OpenCodeModel(modelID = "claude-sonnet-5-5", providerID = "anthropic", name = "Claude Sonnet 5.5", variants = listOf("low", "medium", "high"))
    private val argon = OpenCodeModel(modelID = "gemini-4-argon", providerID = "console-google", name = "Gemini 4 Argon", variants = listOf("low", "medium", "high"))
    private val astra = OpenCodeModel(modelID = "gpt-6-astra", providerID = "openai", name = "GPT-6 Astra", variants = listOf("low", "medium", "high", "xhigh"))
    private val haiku = OpenCodeModel(modelID = "claude-haiku-4-5", providerID = "anthropic", name = "Claude Haiku 4.5")

    private fun Harness.hapticStore(state: State, haptics: HapticsRecorder) = store(state) { this[HapticsClient] = haptics.client }

    /**
     * The sheet keeps Recent as it opened: a pick made in it is recorded at
     * once but only shows in Recent the next time it opens.
     */
    @Test
    fun searchParsesTrailingLevelAndReturnPicksBestMatchAtThatLevel() = sessionTest { h ->
        val models = listOf(opus55, sonnet55Anthropic, argon, astra, haiku)
        val parsedHigh = ModelSearch.parseLevelQuery("argon high")
        assertTrue(parsedHigh.terms == listOf("argon") && parsedHigh.level == "high")
        val parsedMax = ModelSearch.parseLevelQuery("opus max")
        assertTrue(parsedMax.terms == listOf("opus") && parsedMax.level == "max")
        val parsedPlain = ModelSearch.parseLevelQuery("high")
        assertTrue(parsedPlain.terms == listOf("high") && parsedPlain.level == null, "Single word stays a model query")

        val initial = h.state(model = opus55.ref(variant = "medium")).copy(models = models, modelFilter = "argon high")
        initial.modelVariants.set(mapOf(argon.id to "low"))

        val store = h.store(initial)
        val resolved = checkNotNull(
            ModelSearch.resolvePick(
                store.state.models,
                query = store.state.modelFilter,
                favorites = store.state.favoriteModelIDs.value,
                recents = store.state.recentModelIDs.value,
            ),
        )
        val level = if (resolved.hasExplicitLevel) resolved.variant else store.state.rememberedLevel(resolved.model)
        store.send(Action.ModelPickedWithLevel(resolved.model, level)) {
            it.copy(currentModel = argon.ref(variant = "high"), pendingModel = argon.ref(variant = "high"))
        }
        assertEquals(listOf(argon.id), store.state.recentModelIDs.value)
        assertEquals("high", store.state.modelVariants.value[argon.id])
        assertEquals("Gemini 4 Argon", store.state.modelPicker.currentModelShortName)
        assertEquals("High", store.state.modelPicker.currentChipLevelLabel)
    }

    @Test
    fun theSheetsLevelMenuPicksAndStaysOpenAndARowTapCloses() = sessionTest { h ->
        val initial = h.state(model = opus55.ref(variant = "high")).copy(models = listOf(opus55, argon), activePicker = State.ActivePicker.model)
        initial.modelVariants.set(emptyMap())
        initial.recentModelIDs.set(emptyList())
        val haptics = HapticsRecorder()
        val store = h.hapticStore(initial, haptics)
        store.send(Action.ModelLevelPicked(argon, "high")) {
            it.copy(currentModel = argon.ref(variant = "high"), pendingModel = argon.ref(variant = "high"))
        }
        assertEquals("high", store.state.modelVariants.value[argon.id])
        assertEquals(listOf(argon.id), store.state.recentModelIDs.value)
        assertEquals(listOf(Haptic.selection), haptics.log)
        store.send(Action.ModelLevelPicked(argon, "high"))
        assertEquals(listOf(Haptic.selection), haptics.log, "Re-choosing the shown level is quiet")
        store.send(Action.ModelPickedWithLevel(opus55, "high")) {
            it.copy(currentModel = opus55.ref(variant = "high"), pendingModel = opus55.ref(variant = "high"), activePicker = null)
        }
        assertEquals("high", store.state.modelVariants.value[opus55.id])
        assertEquals(listOf(opus55.id, argon.id), store.state.recentModelIDs.value)
        assertEquals(listOf(Haptic.selection, Haptic.selection), haptics.log)
    }

    @Test
    fun aPickFromALevelDragThatTickedIsQuietOnRelease() = sessionTest { h ->
        val initial = h.state(model = opus55.ref(variant = "high")).copy(models = listOf(opus55, argon))
        initial.modelVariants.set(emptyMap())
        initial.recentModelIDs.set(emptyList())
        val haptics = HapticsRecorder()
        val store = h.hapticStore(initial, haptics)
        // The drag ticked each stop it crossed; letting go adds no selection.
        store.send(Action.ModelPickedWithLevel(argon, "high", ticked = true)) {
            it.copy(currentModel = argon.ref(variant = "high"), pendingModel = argon.ref(variant = "high"))
        }
        assertEquals("high", store.state.modelVariants.value[argon.id])
        assertEquals(listOf(argon.id), store.state.recentModelIDs.value)
        assertTrue(haptics.log.isEmpty())
    }

    @Test
    fun aRowLevelIsASelectionOnlyWhenTheLevelChanges() = sessionTest { h ->
        val initial = h.state(model = opus55.ref(variant = "high")).copy(models = listOf(opus55, argon))
        initial.modelVariants.set(mapOf(opus55.id to "low"))
        val haptics = HapticsRecorder()
        val store = h.hapticStore(initial, haptics)
        // The current model's shown level is already High: only its memory catches up.
        store.send(Action.ModelRowLevelChanged(opus55, "high")) { it.copy(pendingModel = opus55.ref(variant = "high")) }
        assertEquals("high", store.state.modelVariants.value[opus55.id])
        store.send(Action.ModelRowLevelChanged(opus55, "high"))
        assertTrue(haptics.log.isEmpty())
        store.send(Action.ModelRowLevelChanged(argon, "low"))
        assertEquals("low", store.state.modelVariants.value[argon.id])
        assertEquals(listOf(Haptic.selection), haptics.log)
    }
}
