package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.strings.localized
import ai.opencode.feature.composer.R
import ai.opencode.server.model.ModelVisibility
import ai.opencode.server.model.ModelProviderGroup
import ai.opencode.server.model.ModelSearch
import ai.opencode.server.model.ModelVariant
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.model
import ai.opencode.server.model.savedLevel

/**
 * The model list's sections: Favorites, then one
 * section per provider, as the TUI and desktop app group them. A model several providers offer
 * is listed under each, so the row you tap is the provider. Searching drops Favorites and
 * matches names and providers.
 */
data class ModelPickerSection(val kind: Kind, val models: List<OpenCodeModel>) {
    sealed interface Kind {
        data object Favorites : Kind
        data object Recent : Kind
        data object AllModels : Kind
        data object SearchResults : Kind
        data class Provider(val id: String) : Kind
    }

    val id: String
        get() = when (kind) {
            Kind.Favorites -> "favorites"
            Kind.Recent -> "recent"
            Kind.AllModels -> "all"
            Kind.SearchResults -> "search"
            is Kind.Provider -> "provider-${kind.id}"
        }

    val title: String
        get() = when (kind) {
            Kind.Favorites -> localized(R.string.composer_model_section_favorites)
            Kind.Recent -> localized(R.string.composer_model_section_recent)
            Kind.AllModels -> localized(R.string.composer_model_section_all_models)
            Kind.SearchResults -> ""
            is Kind.Provider -> ModelProviderGroup.title(kind.id)
        }

    companion object {
        /**
         * [recents] (most recent first) adds a Recent section of up to [recentLimit] models
         * after Favorites, leaving out favorites (New Model Picker). Searching drops both.
         */
        fun sections(
            models: List<OpenCodeModel>,
            favorites: Set<String>,
            filter: String = "",
            recents: List<String> = emptyList(),
            recentLimit: Int = 0,
        ): List<ModelPickerSection> {
            val needle = filter.trim()
            // Searching, providers come in the order of their best match.
            val shown = ModelSearch.rank(models, needle, favorites)
            val sections = mutableListOf<ModelPickerSection>()
            if (needle.isEmpty()) {
                val favored = shown.filter { it.id in favorites }
                if (favored.isNotEmpty()) sections += ModelPickerSection(Kind.Favorites, favored)
                val byID = LinkedHashMap<String, OpenCodeModel>()
                for (model in models) byID.putIfAbsent(model.id, model)
                val recent = recents.asSequence()
                    .filter { it !in favorites }
                    .mapNotNull { byID[it] }
                    .take(maxOf(recentLimit, 0))
                    .toList()
                if (recent.isNotEmpty()) sections += ModelPickerSection(Kind.Recent, recent)
            }
            // Providers in the order the server lists them.
            val byProvider = LinkedHashMap<String, MutableList<OpenCodeModel>>()
            for (model in shown) byProvider.getOrPut(model.providerID) { mutableListOf() } += model
            byProvider.forEach { (id, list) -> sections += ModelPickerSection(Kind.Provider(id), list) }
            return sections
        }
    }
}

/**
 * The model picker's inputs, as `SessionFeature.State` holds them. `SessionFeature` sits above this
 * module, so the session area builds this value from its state and the pickers read it.
 */
data class ModelPickerState(
    val models: List<OpenCodeModel> = emptyList(),
    val currentModel: OpenCodeModelRef? = null,
    val favoriteModelIDs: Set<String> = emptySet(),
    val recentModelIDs: List<String> = emptyList(),
    /** `modelVariants` (model ID → remembered variant, `"default"` for the default). */
    val modelVariants: Map<String, String> = emptyMap(),
    val modelFilter: String = "",
    val isLoadingModels: Boolean = false,
    val modelsLoadError: String? = null,
    /** `modelVisibility`: this server's visibility while Hide Unused Models is on; null lists the whole catalog. */
    val modelVisibility: ModelVisibility? = null,
    val showsAllModels: Boolean = false,
    /** This server's remembered labels (`modelDisplayNamesByServer[server.persistenceID]`). */
    val modelDisplayNames: ModelDisplayNames = ModelDisplayNames(),
) {
    /** Resolve a label without borrowing another provider's capabilities. */
    val currentModelName: String
        get() {
            val ref = currentModel ?: return localized(R.string.composer_model_placeholder)
            val model = currentModelInfo
            if (model != null && ModelDisplayNames.usable(model.name, model.key, model.modelID)) return model.name
            return modelDisplayNames.name(ref)
                ?: ModelDisplayNames(models).unambiguousName(ref)
                ?: modelDisplayNames.unambiguousName(ref)
                ?: ModelDisplayNames.prettify(ref.id)
        }

    /** Only the live catalogue can supply availability and capabilities. */
    val currentModelInfo: OpenCodeModel? get() = currentModel?.let { models.model(it) }

    /** "Claude Haiku 5.5 · High": the model and its reasoning variant, if any. */
    val currentModelLabel: String
        get() {
            val variant = currentModel?.variant
            if (variant == null || variant == "default") return currentModelName
            return "$currentModelName · ${ModelVariant.label(variant)}"
        }

    /** The models the Today and Sheet pickers list: the visible ones and the current model, or all of them after Show All. */
    val pickerModels: List<OpenCodeModel>
        get() {
            val visibility = modelVisibility ?: return models
            if (showsAllModels) return models
            return visibility.visible(models, currentModelInfo?.id)
        }

    /**
     * How many models Show All adds; zero hides the button. While any are hidden the pickers keep
     * Show All instead of an empty state, so hiding every model never strands the picker.
     */
    val hiddenModelCount: Int
        get() {
            val visibility = modelVisibility ?: return 0
            return models.size - visibility.visible(models, currentModelInfo?.id).size
        }

    val isFilteringModels: Boolean get() = modelFilter.trim { it == ' ' || it == '\t' }.isNotEmpty()

    val filteredModels: List<OpenCodeModel> get() = ModelSearch.rank(pickerModels, modelFilter, favoriteModelIDs)

    val favoriteModels: List<OpenCodeModel> get() = filteredModels.filter { it.id in favoriteModelIDs }

    val unfavoriteModels: List<OpenCodeModel> get() = filteredModels.filter { it.id !in favoriteModelIDs }

    /** The model list as the picker shows it. */
    val modelSections: List<ModelPickerSection>
        get() = ModelPickerSection.sections(models, favoriteModelIDs, modelFilter)

    /** The current model's levels, when it has any to choose between. */
    val currentLevelScale: ModelLevelScale?
        get() {
            val model = currentModelInfo ?: return null
            return ModelLevelScale(model.variants).takeIf { it.isAdjustable }
        }

    /** The current level; null is the model's default. */
    val currentLevel: String? get() = currentModel?.variant?.takeIf { it != "default" }

    /** Compact name for the composer chip (`"Opus 5.5"` for `"Claude Opus 5.5"`). */
    val currentModelShortName: String
        get() {
            val ref = currentModel ?: return currentModelName
            return OpenCodeModel(
                modelID = ref.id, providerID = ref.providerID, name = currentModelName,
                canonicalProviderID = currentModelInfo?.canonicalProviderID,
            ).shortName
        }

    /** The level word shown on the chip (`"High"`, `"Default"`, or null when the current model has no adjustable variants). */
    val currentChipLevelLabel: String?
        get() {
            if (currentLevelScale == null) return null
            return ModelLevelScale.label(currentLevel)
        }

    /**
     * The level shown for [model] on its row in One Picker (and picked when tapping its name): a level parsed
     * from the search query when searching, else the active level for the current model, else
     * the remembered variant in [modelVariants], else null (`"Default"`).
     */
    fun rememberedLevel(model: OpenCodeModel): String? {
        val scale = ModelLevelScale(model.variants)
        if (!scale.isAdjustable) return null
        if (isFilteringModels) {
            val parsed = ModelSearch.parseLevelQuery(modelFilter).level
            if (parsed != null) return ModelSearch.resolveVariant(parsed, model)
        }
        if (currentModelInfo?.id == model.id) {
            return currentLevel?.takeIf { it in model.variants }
        }
        return model.savedLevel(modelVariants)
    }
}

/**
 * The picker actions the views send to `SessionFeature`. The pickers here call one lambda with
 * these; the session area maps each to its own action of the same name.
 */
sealed interface ModelPickerEvent {
    /** `.modelPicked`: keeps the current variant when the new model offers it; closes the picker. */
    data class ModelPicked(val model: OpenCodeModel) : ModelPickerEvent

    /** `.modelPickedWithLevel`: picks at [level] and closes the picker; [ticked] skips the selection haptic. */
    data class ModelPickedWithLevel(val model: OpenCodeModel, val level: String?, val ticked: Boolean = false) : ModelPickerEvent

    /** `.modelLevelPicked`: as [ModelPickedWithLevel] but the picker stays open. */
    data class ModelLevelPicked(val model: OpenCodeModel, val level: String?) : ModelPickerEvent

    /** `.modelRowLevelChanged`: remembers a row's level (and applies it when it's the current model). */
    data class ModelRowLevelChanged(val model: OpenCodeModel, val level: String?) : ModelPickerEvent

    /** `.variantPicked`: the current model at another level, picker stays open. */
    data class VariantPicked(val variant: String?) : ModelPickerEvent

    data class ToggleFavoriteModel(val model: OpenCodeModel) : ModelPickerEvent

    /** `$store.modelFilter` binding. */
    data class FilterChanged(val text: String) : ModelPickerEvent

    data object ModelSearchSubmitted : ModelPickerEvent
    data object ShowAllModelsToggled : ModelPickerEvent

    /** `.modelListOpened` (also Try Again). */
    data object ModelListOpened : ModelPickerEvent

    /** `.modelListClosed`: however the picker closed; clears its search. */
    data object ModelListClosed : ModelPickerEvent

    /** `store.activePicker = nil` (Done, Cancel, swiped away). */
    data object Dismiss : ModelPickerEvent
}

/** Case-insensitive multi-field filter used by the picker sheets. */
fun <T> List<T>.filtered(query: String, fields: (T) -> List<String>): List<T> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return this
    return filter { element -> fields(element).any { it.contains(trimmed, ignoreCase = true) } }
}
