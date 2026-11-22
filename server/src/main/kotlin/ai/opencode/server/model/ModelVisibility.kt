package ai.opencode.server.model

import ai.opencode.core.sharing.FileDirectory
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.strings.localized
import ai.opencode.server.R
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * In `:server` because both `:feature:composer` (the pickers) and `:feature:settings` (Settings ›
 * Models) read it.
 *
 * Which models one server's pickers list (Hide Unused Models). Kept on this phone, per
 * server. Hidden providers hide all their models; otherwise **Hide** lists every model but
 * [hiddenModelIDs], and **Only Show** lists just [shownModelIDs]. Each list is kept when
 * switching, so trying the other choice loses nothing.
 */
@Serializable
data class ModelVisibility(
    val mode: Mode = Mode.hide,
    val hiddenProviderIDs: Set<String> = emptySet(),
    val hiddenModelIDs: Set<String> = emptySet(),
    val shownModelIDs: Set<String> = emptySet(),
) {
    @Serializable
    enum class Mode {
        @SerialName("hide") hide,
        @SerialName("onlyShow") onlyShow;

        val id: String get() = name

        val title: String
            get() = when (this) {
                hide -> localized(R.string.server_model_visibility_hide)
                onlyShow -> localized(R.string.server_model_visibility_only_show)
            }
    }

    /**
     * Only Show lists just the chosen models [catalog] still offers; with none of them left
     * (nothing chosen yet, or only models the server has since dropped) it lists everything, so
     * stale choices never empty the picker.
     */
    fun isVisible(model: OpenCodeModel, catalog: List<OpenCodeModel>): Boolean =
        isVisible(model, liveShownIDs(catalog))

    private fun isVisible(model: OpenCodeModel, liveShownIDs: Set<String>): Boolean {
        if (model.providerID in hiddenProviderIDs) return false
        return when (mode) {
            Mode.hide -> model.id !in hiddenModelIDs
            Mode.onlyShow -> liveShownIDs.isEmpty() || model.id in liveShownIDs
        }
    }

    private fun liveShownIDs(catalog: List<OpenCodeModel>): Set<String> =
        shownModelIDs.intersect(catalog.map { it.id }.toSet())

    /** Model and provider IDs this choice names that [catalog] no longer offers. Settings lists them so they can be forgotten. */
    fun staleEntries(catalog: List<OpenCodeModel>): StaleEntries {
        val modelIDs = catalog.map { it.id }.toSet()
        val providerIDs = catalog.map { it.providerID }.toSet()
        return StaleEntries(
            providerIDs = hiddenProviderIDs - providerIDs,
            modelIDs = (hiddenModelIDs + shownModelIDs) - modelIDs,
        )
    }

    fun forget(stale: StaleEntries): ModelVisibility = copy(
        hiddenProviderIDs = hiddenProviderIDs - stale.providerIDs,
        hiddenModelIDs = hiddenModelIDs - stale.modelIDs,
        shownModelIDs = shownModelIDs - stale.modelIDs,
    )

    data class StaleEntries(
        val providerIDs: Set<String> = emptySet(),
        val modelIDs: Set<String> = emptySet(),
    ) {
        val isEmpty: Boolean get() = providerIDs.isEmpty() && modelIDs.isEmpty()
        /** Sorted for display: providers first, then models. */
        val all: List<String> get() = providerIDs.sorted() + modelIDs.sorted()
    }

    /** Whether the model's own switch is on, whatever its provider's. */
    fun isModelOn(model: OpenCodeModel): Boolean = when (mode) {
        Mode.hide -> model.id !in hiddenModelIDs
        Mode.onlyShow -> model.id in shownModelIDs
    }

    fun setModel(model: OpenCodeModel, on: Boolean): ModelVisibility = when (mode) {
        Mode.hide -> copy(hiddenModelIDs = if (on) hiddenModelIDs - model.id else hiddenModelIDs + model.id)
        Mode.onlyShow -> copy(shownModelIDs = if (on) shownModelIDs + model.id else shownModelIDs - model.id)
    }

    fun setProvider(providerID: String, shown: Boolean): ModelVisibility =
        copy(hiddenProviderIDs = if (shown) hiddenProviderIDs - providerID else hiddenProviderIDs + providerID)

    /** [models] as the picker lists them, keeping the current model so its checkmark never vanishes. */
    fun visible(models: List<OpenCodeModel>, keepingCurrentID: String?): List<OpenCodeModel> {
        val live = liveShownIDs(models)
        return models.filter { isVisible(it, live) || it.id == keepingCurrentID }
    }

    companion object {
        /** Each saved server's model visibility, keyed by its persistence ID. */
        val modelVisibilityByServer = SharedKey.fileStorage<Map<String, ModelVisibility>>(
            FileDirectory.Documents,
            "model-visibility.json",
            emptyMap(),
        )
    }
}
