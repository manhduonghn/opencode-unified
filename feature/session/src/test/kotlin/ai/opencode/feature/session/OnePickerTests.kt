package ai.opencode.feature.session

import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertEquals
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.HapticsRecorder
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.assertRemembersModelNames
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.testServer
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeSession
import org.junit.Test
import java.time.Instant

class OnePickerTests {
    private val opusZen = OpenCodeModel("claude-opus-5-5", "opencode", "Claude Opus 5.5", variants = listOf("low", "medium", "high", "max"))
    private val sonnetAnthropic = OpenCodeModel("claude-sonnet-5-5", "anthropic", "Claude Sonnet 5.5", variants = listOf("low", "medium", "high"))
    private val astra = OpenCodeModel("gpt-6-astra", "openai", "GPT-6 Astra", variants = listOf("minimal", "low", "medium", "high", "xhigh"))
    private val carbon = OpenCodeModel("gemini-4-carbon", "console-google", "Gemini 4 Carbon", variants = listOf("low", "high"))
    private val haiku = OpenCodeModel("claude-haiku-5-5", "opencode", "Claude Haiku 5.5")
    private val halcyonLumen = OpenCodeModel("lumen-r1", "halcyon", "Lumen R1", variants = listOf("low", "high"))

    private val catalog: List<OpenCodeModel>
        get() = listOf(opusZen, haiku, sonnetAnthropic, astra, carbon, halcyonLumen)

    private fun Harness.makeState(model: OpenCodeModelRef?): State {
        val session = OpenCodeSession(
            id = "ses_one_picker",
            serverTitle = "One Picker Test",
            projectID = "proj_1",
            directory = "/tmp/project",
            updatedAt = Instant.EPOCH,
            model = model,
        )
        return state { State.session(server = testServer, session = session) }.copy(models = catalog)
    }

    @Test
    fun pickingModelAndChangingInlineLevelWaitsForNextSendAndPlaysExactHaptics() = sessionTest { h ->
        val initial = h.makeState(model = opusZen.ref(variant = "high"))
        initial.favoriteModelIDs.set(emptySet())
        initial.modelVariants.set(emptyMap())
        initial.recentModelIDs.set(listOf(opusZen.id))

        val haptics = HapticsRecorder()
        val switchedOnServer = LockIsolated(0)
        val catalogSnapshot = catalog
        val store = h.store(initial) {
            this[HapticsClient] = haptics.client
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                switchModel = { _, _, _ -> switchedOnServer.increment() },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = catalogSnapshot, defaultModel = opusZen) },
            )
        }

        // 1. Entry point `ModelButtonTapped` sets `.model` and refreshes catalog.
        store.send(Action.ModelButtonTapped) { it.copy(activePicker = State.ActivePicker.model) }
        store.receive(Action.ModelsResponse::class)
        store.state.assertRemembersModelNames(catalogSnapshot)

        // 2. Favorite a model (swipe or hold): plays `.selection`.
        store.send(Action.ToggleFavoriteModel(astra))
        assertEquals(setOf(astra.id), store.state.favoriteModelIDs.value)
        assertEquals(listOf(Haptic.selection), haptics.log)

        // 3. Inline level pick on a row: updates level + remembers variant + keeps picker open, no server POST yet!
        store.send(Action.ModelLevelPicked(astra, "xhigh")) {
            it.copy(currentModel = astra.ref(variant = "xhigh"), pendingModel = astra.ref(variant = "xhigh"))
        }
        assertEquals(mapOf(astra.id to "xhigh"), store.state.modelVariants.value)
        assertEquals(listOf(astra.id, opusZen.id), store.state.recentModelIDs.value)
        assertEquals(listOf(Haptic.selection, Haptic.selection), haptics.log)
        assertEquals(0, switchedOnServer.value, "Picking never eagerly POSTs /model; it waits for the next send")

        // 4. Re-picking the exact same model & level is a quiet no-op (no duplicate haptic), closing activePicker.
        store.send(Action.ModelPickedWithLevel(astra, "xhigh")) { it.copy(activePicker = null) }
        assertEquals(listOf(Haptic.selection, Haptic.selection), haptics.log, "No-op pick plays no extra haptic")

        // 5. Closing clears any search text and `showsAllModels`.
        store.send(Action.Binding(BindingAction.ModelFilter("opus"))) { it.copy(modelFilter = "opus") }
        store.send(Action.ShowAllModelsToggled) { it.copy(showsAllModels = true) }
        store.send(Action.ModelListClosed) { it.copy(modelFilter = "", showsAllModels = false) }
        assertEquals(0, switchedOnServer.value)
    }
}
