package ai.opencode.feature.session

import ai.opencode.feature.composer.modelpicker.ModelDisplayNames
import ai.opencode.feature.composer.modelpicker.OnePickerChip
import ai.opencode.feature.composer.modelpicker.OnePickerProviderMark
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertEquals
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertNull
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.assertRemembersModelNames
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.testServer
import ai.opencode.feature.session.SessionTestSupport.testSession
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeModelRef
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ModelDisplayNameTests {
    private val server = testServer
    private val model = OpenCodeModel(
        modelID = "claude-opus-4-1", providerID = "opencode", name = "Claude Opus 4.1",
        variants = listOf("medium", "high"),
    )

    private suspend fun <T> Channel<T>.next(): T = receiveCatching().getOrNull() ?: throw OpenCodeClientError("Closed")

    @Test
    fun coldStartNamesTheModelBeforeTheCatalogArrives() = sessionTest { h ->
        val session = testSession(title = "Working", model = model.ref("medium"))
        val initial = h.state { State.session(server = server, session = session, isRunning = true) }
        val response = Channel<OpenCodeModelCatalog>(Channel.UNLIMITED)
        val store = h.store(initial) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(modelCatalog = { _, _ -> response.next() })
        }
        store.send(Action.ModelListOpened) { it.copy(isLoadingModels = true) }
        assertEquals("Claude Opus 4.1 · Medium", store.state.currentModelLabel)
        assertEquals("Opus 4.1", store.state.currentModelShortName)
        val named = model.copy(name = "Claude Opus Custom")
        response.send(OpenCodeModelCatalog(models = listOf(named), defaultModel = null))
        response.close()
        store.receive(Action.ModelsResponse::class) { it.copy(isLoadingModels = false, models = listOf(named)) }
        store.state.assertRemembersModelNames(listOf(named))
        assertEquals("Claude Opus Custom · Medium", store.state.currentModelLabel)
        assertEquals("Opus Custom", store.state.currentModelShortName)
        store.finish()
    }

    @Test
    fun successfulLoadPersistsNamesForANewSessionWithNoCatalog() = sessionTest { h ->
        val named = model.copy(name = "Claude Opus Custom")
        val initial = h.state { State.draft(server = server, draftProject = null, projects = emptyList()) }
        val store = h.store(initial)
        store.send(Action.ModelsResponse(Result.success(OpenCodeModelCatalog(models = listOf(named), defaultModel = null)))) {
            it.copy(models = listOf(named))
        }
        store.state.assertRemembersModelNames(listOf(named))
        // A serialization round trip proves the label cache has no in-memory-only
        // fields; the next screen reads the same file-backed shared key at init.
        val serializer = MapSerializer(String.serializer(), ModelDisplayNames.serializer())
        val encoded = Json.encodeToString(serializer, store.state.modelDisplayNamesByServer.value)
        val decoded = Json.decodeFromString(serializer, encoded)
        assertEquals(named.name, decoded[server.persistenceID]?.name(model.ref))
        val next = h.state { State.draft(server = server, draftProject = null, projects = emptyList()).copy(currentModel = model.ref) }
        assertEquals(true, next.models.isEmpty())
        assertEquals(named.name, next.currentModelName)
        assertEquals("Opus Custom", next.currentModelShortName)
        assertNull(next.currentModelInfo, "Remembering a name must not claim availability or capabilities")
    }

    @Test
    fun failedReadRetriesOnReconnectAndKeepsRememberedName() {
        for (transcriptIsRefreshing in listOf(false, true)) failedReadRetriesOnReconnectAndKeepsRememberedName(transcriptIsRefreshing)
    }

    private fun failedReadRetriesOnReconnectAndKeepsRememberedName(transcriptIsRefreshing: Boolean) = sessionTest { h ->
        val named = model.copy(name = "Claude Opus Custom")
        var initial = h.state { State.draft(server = server, draftProject = null, projects = emptyList()).copy(currentModel = model.ref) }
        if (transcriptIsRefreshing) initial = initial.copy(refresh = State.Refresh(id = 1, reason = RefreshReason.Resume))
        h.state { initial.modelDisplayNamesByServer.update { it + (server.persistenceID to ModelDisplayNames(listOf(named))) } }
        val attempts = LockIsolated(0)
        val failure = OpenCodeClientError("Couldn’t load models")
        val retry = Channel<OpenCodeModelCatalog>(Channel.UNLIMITED)
        val store = h.store(initial) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                modelCatalog = { _, _ ->
                    if (attempts.increment() == 1) throw failure
                    retry.next()
                },
            )
        }
        store.send(Action.ModelListOpened) { it.copy(isLoadingModels = true) }
        store.receive(Action.ModelsResponse::class) { it.copy(isLoadingModels = false, modelsLoadError = failure.message) }
        assertEquals("Opus Custom", store.state.currentModelShortName)
        store.send(Action.EventStreamConnected(server)) {
            it.copy(streamIsHealthy = true, isLoadingModels = true, modelsLoadError = null)
        }
        if (!transcriptIsRefreshing) store.receive(Action.RefreshRequested(RefreshReason.Reconnect))
        retry.send(OpenCodeModelCatalog(models = listOf(model), defaultModel = null))
        retry.close()
        store.receive(Action.ModelsResponse::class) { it.copy(isLoadingModels = false, models = listOf(model)) }
        store.state.assertRemembersModelNames(listOf(model))
        assertEquals(2, attempts.value)
        assertEquals("Opus 4.1", store.state.currentModelShortName)
        store.finish()
    }

    @Test
    fun reconnectDoesNotDropALoadedNameOrFetchAgain() = sessionTest { h ->
        val initial = h.state { State.draft(server = server, draftProject = null, projects = emptyList()).copy(currentModel = model.ref) }
            .copy(models = listOf(model))
        val store = h.store(initial)
        store.send(Action.EventStreamConnected(server)) { it.copy(streamIsHealthy = true) }
        store.receive(Action.RefreshRequested(RefreshReason.Reconnect))
        assertEquals("Opus 4.1", store.state.currentModelShortName)
        store.finish()
    }

    @Test
    fun absentProviderBorrowsOnlyAnUnambiguousLabel() = sessionTest { h ->
        val named = model.copy(name = "Claude Opus Custom")
        var initial = h.state { State.draft(server = server, draftProject = null, projects = emptyList()) }
            .copy(currentModel = OpenCodeModelRef(id = model.key, providerID = "console-anthropic"), models = listOf(named))
        h.state {
            assertEquals("Claude Opus Custom", initial.currentModelName)
            assertNull(initial.currentModelInfo)
        }
        val other = named.copy(providerID = "anthropic", name = "Different Name")
        initial = initial.copy(models = initial.models + other)
        h.state { assertEquals("Claude Opus 4.1", initial.currentModelName) }
    }

    @Test
    fun catalogKeysAndServersKeepTheirOwnNames() = sessionTest { h ->
        val fast = OpenCodeModel(modelID = model.modelID, providerID = model.providerID, name = "Opus Fast", catalogID = "fast")
        var initial = h.state { State.draft(server = server, draftProject = null, projects = emptyList()).copy(currentModel = fast.ref) }
        h.state { initial.modelDisplayNamesByServer.update { it + (server.persistenceID to ModelDisplayNames(listOf(model, fast))) } }
        assertEquals("Opus Fast", initial.currentModelName)
        initial = initial.copy(currentModel = model.ref)
        assertEquals(model.name, initial.currentModelName)
        initial = initial.copy(server = OpenCodeServer(url = "http://localhost:10", username = "", password = ""), currentModel = fast.ref)
        assertEquals("Fast", initial.currentModelName)
    }

    @Test
    fun emptyAndRawCatalogNamesCannotEraseAKnownLabel() = sessionTest { h ->
        val initial = h.state { State.draft(server = server, draftProject = null, projects = emptyList()).copy(currentModel = model.ref) }
        val named = model.copy(name = "Claude Opus Custom")
        h.state { initial.modelDisplayNamesByServer.update { it + (server.persistenceID to ModelDisplayNames(listOf(named))) } }
        val store = h.store(initial)
        store.send(Action.ModelsResponse(Result.success(OpenCodeModelCatalog(models = emptyList(), defaultModel = null))))
        val raw = model.copy(name = model.modelID)
        store.send(Action.ModelsResponse(Result.success(OpenCodeModelCatalog(models = listOf(raw), defaultModel = null)))) {
            it.copy(models = listOf(raw))
        }
        assertEquals("Opus Custom", store.state.currentModelShortName)
    }

    @Test
    fun siblingModesNeverNameTheBaseModel() {
        for (mode in listOf("fast", "ultrafast")) siblingModesNeverNameTheBaseModel(mode)
    }

    private fun siblingModesNeverNameTheBaseModel(mode: String) = sessionTest { h ->
        var base = model.copy(name = "Claude Opus Custom")
        val sibling = OpenCodeModel(
            modelID = base.modelID, providerID = "console-anthropic", name = "Opus ${mode.replaceFirstChar { it.uppercase() }}",
            catalogID = "${base.key}-$mode",
        )
        val ref = OpenCodeModelRef(id = base.key, providerID = sibling.providerID)
        val remembered = ModelDisplayNames(listOf(sibling))
        assertNull(remembered.name(ref))
        assertNull(remembered.unambiguousName(ref))
        val all = ModelDisplayNames(listOf(sibling, base))
        assertEquals(base.name, all.unambiguousName(ref))
        assertEquals(sibling.name, all.name(sibling.ref))

        var state = h.state { State.draft(server = server, draftProject = null, projects = emptyList()).copy(currentModel = ref) }
        h.state { state.modelDisplayNamesByServer.update { it + (server.persistenceID to remembered) } }
        state = state.copy(models = listOf(sibling, base))
        assertNull(state.currentModelInfo, "A sibling's API ID is not its catalogue key")
        assertEquals(base.name, state.currentModelName, "A cached Fast label cannot beat the live base key")
        base = base.copy(providerID = sibling.providerID)
        state = state.copy(models = listOf(sibling, base))
        assertEquals(base, state.currentModelInfo)
        assertEquals(base.name, state.currentModelName)
    }

    @Test
    fun chipIdentitySurvivesCatalogArrivalWithTheSameNameAndIcon() {
        for (provider in listOf("opencode", "console-anthropic")) chipIdentitySurvivesCatalogArrivalWithTheSameNameAndIcon(provider)
    }

    private fun chipIdentitySurvivesCatalogArrivalWithTheSameNameAndIcon(provider: String) = sessionTest { h ->
        var named = model.copy(providerID = provider)
        var state = h.state { State.draft(server = server, draftProject = null, projects = emptyList()).copy(currentModel = named.ref) }
        h.state { state.modelDisplayNamesByServer.update { it + (server.persistenceID to ModelDisplayNames(listOf(named))) } }
        val before = OnePickerChip.ModelKey(state.modelPicker)
        assertEquals("$provider/${named.key}", before.id)
        named = named.copy(canonicalProviderID = "anthropic")
        state = state.copy(models = listOf(named))
        val after = OnePickerChip.ModelKey(state.modelPicker)
        assertEquals(before, after)
        assertEquals(OnePickerProviderMark.resolvedAssetName(providerID = provider, canonical = "anthropic"), after.icon)
        state = state.copy(currentModel = state.currentModel?.copy(variant = "high"))
        assertEquals(after, OnePickerChip.ModelKey(state.modelPicker), "Changing the level does not roll the name")
        named = named.copy(name = "Claude Opus Renamed")
        state = state.copy(models = listOf(named))
        assertNotEquals("An actual label change still rolls", after, OnePickerChip.ModelKey(state.modelPicker))
    }

    @Test
    fun reorderedCatalogDoesNotRewriteRememberedNames() = sessionTest { h ->
        val other = OpenCodeModel(modelID = "other", providerID = "example", name = "Other Model")
        val initial = h.state { State.draft(server = server, draftProject = null, projects = emptyList()).copy(currentModel = model.ref) }
            .copy(models = listOf(model, other))
        h.state { initial.modelDisplayNamesByServer.update { it + (server.persistenceID to ModelDisplayNames(listOf(model, other))) } }
        val store = h.store(initial)
        val original = store.state.modelDisplayNamesByServer.value
        store.send(Action.ModelsResponse(Result.success(OpenCodeModelCatalog(models = listOf(other, model), defaultModel = null)))) {
            // No shared mutation: only the live picker order changes.
            it.copy(models = listOf(other, model))
        }
        assertEquals(original, store.state.modelDisplayNamesByServer.value)
        store.send(Action.ModelsResponse(Result.success(OpenCodeModelCatalog(models = listOf(other), defaultModel = null)))) {
            it.copy(models = listOf(other))
        }
        assertEquals(original, store.state.modelDisplayNamesByServer.value, "A directory's subset keeps absent names in place too")
        store.finish()
    }

    @Test
    fun failureAfterSSEConnectedRetriesOnceOnContactOrForeground() {
        for (foreground in listOf(false, true)) failureAfterSSEConnectedRetriesOnceOnContactOrForeground(foreground)
    }

    private fun failureAfterSSEConnectedRetriesOnceOnContactOrForeground(foreground: Boolean) = sessionTest { h ->
        val initial = h.state { State.draft(server = server, draftProject = null, projects = emptyList()).copy(currentModel = model.ref) }
            .withRequestsConnectionIsAvailable(true)
            .copy(hasRenderedFirstContentFrame = true)
        val responses = Channel<Result<OpenCodeModelCatalog>>(Channel.UNLIMITED)
        val attempts = LockIsolated(0)
        val failure = OpenCodeClientError("Couldn’t load models")
        val store = h.store(initial) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                modelCatalog = { _, _ ->
                    attempts.increment()
                    responses.next().getOrThrow()
                },
            )
        }
        store.send(Action.ModelListOpened) { it.copy(isLoadingModels = true) }
        store.send(Action.EventStreamConnected(server)) { it.copy(streamIsHealthy = true) }
        store.receive(Action.RefreshRequested(RefreshReason.Reconnect))
        responses.send(Result.failure(failure))
        store.receive(Action.ModelsResponse::class) { it.copy(isLoadingModels = false, modelsLoadError = failure.message) }
        val wake: Action = if (foreground) Action.VisibilityChanged(true) else Action.RequestsConnectionChanged(true)
        store.send(wake) {
            val shown = if (foreground) it.copy(isVisible = true) else it
            shown.copy(isLoadingModels = true, modelsLoadError = null)
        }
        if (foreground) store.receive(Action.RefreshRequested(RefreshReason.Resume))
        store.send(wake)
        store.send(Action.RequestsConnectionChanged(true))
        responses.send(Result.success(OpenCodeModelCatalog(models = listOf(model), defaultModel = null)))
        responses.close()
        store.receive(Action.ModelsResponse::class) { it.copy(isLoadingModels = false, models = listOf(model)) }
        store.state.assertRemembersModelNames(listOf(model))
        assertEquals(2, attempts.value)
        store.finish()
    }
}
