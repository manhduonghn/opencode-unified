package ai.opencode.feature.session

import ai.opencode.feature.composer.modelpicker.filtered
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.server.model.ModelSearch
import ai.opencode.server.model.ModelVisibility
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeProject

// The model and project pickers' lists. `recentProjectLimit` stays in `State`'s companion
// (`SessionFeature.kt`).

val State.filteredModels: List<OpenCodeModel> get() = ModelSearch.rank(pickerModels, modelFilter, favoriteModelIDs.value)

/** This server's model visibility while Hide Unused Models is on; null lists the whole catalog. */
val State.modelVisibility: ModelVisibility?
    get() {
        if (!ExperimentalFeature.hideModels.isActive(hideModelsExperiment.value)) return null
        return modelVisibilityByServer.value[server.persistenceID] ?: ModelVisibility()
    }

/** The models the pickers list: the visible ones and the current model, or all of them after Show All. */
val State.pickerModels: List<OpenCodeModel>
    get() {
        val visibility = modelVisibility
        if (visibility == null || showsAllModels) return models
        return visibility.visible(models, currentModelInfo?.id)
    }

/** How many models Show All adds; zero hides the button. */
val State.hiddenModelCount: Int
    get() {
        val visibility = modelVisibility ?: return 0
        return models.size - visibility.visible(models, currentModelInfo?.id).size
    }

val State.isFilteringModels: Boolean get() = modelFilter.trim().isNotEmpty()

val State.favoriteModels: List<OpenCodeModel> get() = filteredModels.filter { it.id in favoriteModelIDs.value }

val State.unfavoriteModels: List<OpenCodeModel> get() = filteredModels.filter { it.id !in favoriteModelIDs.value }

val State.filteredProjects: List<OpenCodeProject> get() = uniqueProjects.filtered(projectFilter) { listOf(it.name, it.directory) }

/** Each folder once, most recent first: the server can list one folder under more than one project. */
private val State.uniqueProjects: List<OpenCodeProject>
    get() {
        val directories = HashSet<String>()
        return projects.filter { directories.add(it.directory) }
    }

/** The projects a draft lists above its composer: the five most recent, with the chosen one first when it isn't among them. */
val State.recentProjects: List<OpenCodeProject>
    get() {
        val recent = uniqueProjects.take(State.recentProjectLimit)
        val chosen = draftProject ?: return recent
        if (recent.any { it.id == chosen.id || it.directory == chosen.directory }) return recent
        return listOf(chosen) + recent.take(State.recentProjectLimit - 1)
    }

/** A draft's project can change until its first send leaves; a call started here creates the session in it straight away. */
val State.canChooseProject: Boolean get() = session == null && outboxDraftID == null && callSessionID == null && items.isEmpty()
