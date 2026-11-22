package ai.opencode.feature.settings

import ai.opencode.core.ui.OpenCodeContentUnavailable
import ai.opencode.core.ui.OpenCodeSearchField
import ai.opencode.core.R as CoreR
import ai.opencode.core.strings.localized
import ai.opencode.core.dependencies.Dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.core.store.observe
import ai.opencode.core.store.scope
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.ProviderIcon
import ai.opencode.core.ui.SFSymbol
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.ModelProviderGroup
import ai.opencode.server.model.ModelSearch
import ai.opencode.server.model.ModelVisibility
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelCatalog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import ai.opencode.core.ui.OpenCodeFonts
import androidx.compose.ui.unit.dp

/**
 * Settings › Models (Hide Unused Models), which of this server's models the Today and Sheet
 * pickers list, kept on this phone per server (`ModelVisibility.modelVisibilityByServer`).
 */
class ModelVisibilityFeature : Reducer<ModelVisibilityFeature.State, ModelVisibilityFeature.Action> {
    data class State(
        val server: OpenCodeServer,
        val models: List<OpenCodeModel> = emptyList(),
        val isLoading: Boolean = false,
        val loadError: String? = null,
    ) {
        val visibilityByServer: Map<String, ModelVisibility>
            get() = shared(ModelVisibility.modelVisibilityByServer).value

        val visibility: ModelVisibility get() = visibility(visibilityByServer)

        fun visibility(byServer: Map<String, ModelVisibility>): ModelVisibility =
            byServer[server.persistenceID] ?: ModelVisibility()

        /** Providers in the order the server lists them. */
        val providers: List<Provider>
            get() {
                val byProvider = LinkedHashMap<String, MutableList<OpenCodeModel>>()
                for (model in models) byProvider.getOrPut(model.providerID) { mutableListOf() }.add(model)
                return byProvider.map { (id, list) -> Provider(id, list) }
            }

        fun provider(id: String): Provider? = providers.firstOrNull { it.id == id }

        /** Choices naming models or providers the server no longer offers. Empty until a catalog has loaded. */
        fun staleEntries(visibility: ModelVisibility = this.visibility): ModelVisibility.StaleEntries =
            if (models.isEmpty()) ModelVisibility.StaleEntries() else visibility.staleEntries(models)

        /** “4 of 12”, or “Hidden” for a hidden provider. */
        fun summary(provider: Provider, visibility: ModelVisibility = this.visibility): String {
            if (provider.id in visibility.hiddenProviderIDs) return localized(R.string.settings_models_summary_hidden)
            val shown = visibility.visible(models, null).count { it.providerID == provider.id }
            return if (shown == provider.models.size) localized(R.string.settings_models_summary_all) else localized(R.string.settings_models_summary_count, shown, provider.models.size)
        }
    }

    data class Provider(val id: String, val models: List<OpenCodeModel>) {
        val title: String get() = ModelProviderGroup.title(id)
        val canonicalID: String? get() = models.firstOrNull()?.canonicalProviderID
    }

    sealed interface Action {
        data object Task : Action
        data class ModelsLoaded(val result: Result<OpenCodeModelCatalog>) : Action
        data class ModeChanged(val mode: ModelVisibility.Mode) : Action
        data class ProviderToggled(val providerID: String, val shown: Boolean) : Action
        data class ModelToggled(val model: OpenCodeModel, val on: Boolean) : Action
        data object ResetTapped : Action
        data object ForgetUnavailableTapped : Action
    }

    private val openCodeClient by Dependency(OpenCodeClient)
    private val haptics by Dependency(HapticsClient)

    /** The catalog read; Settings cancels it when the page leaves. */
    object LoadID

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.Task -> {
            val server = state.server
            val client = openCodeClient
            Reduced(
                state.copy(isLoading = state.models.isEmpty() || state.isLoading, loadError = null),
                Effect.run<Action> { send ->
                    val result = try {
                        Result.success(client.modelCatalog(server, null))
                    } catch (error: OpenCodeClientError) {
                        Result.failure(error)
                    }
                    send(Action.ModelsLoaded(result))
                }.cancellable(LoadID, cancelInFlight = true),
            )
        }
        is Action.ModelsLoaded -> action.result.fold(
            onSuccess = { catalog ->
                val models = if (catalog.models.isNotEmpty() || state.models.isEmpty()) catalog.models else state.models
                Reduced(state.copy(isLoading = false, models = models))
            },
            onFailure = { error ->
                Reduced(state.copy(isLoading = false, loadError = if (state.models.isEmpty()) error.message else state.loadError))
            },
        )
        is Action.ModeChanged -> update(state) { it.copy(mode = action.mode) }
        is Action.ProviderToggled -> update(state) { it.setProvider(action.providerID, action.shown) }
        is Action.ModelToggled -> update(state) { it.setModel(action.model, action.on) }
        Action.ForgetUnavailableTapped -> {
            val stale = state.staleEntries()
            if (stale.isEmpty) {
                Reduced(state)
            } else {
                haptics.play(Haptic.tap)
                update(state) { it.forget(stale) }
            }
        }
        Action.ResetTapped -> {
            val id = state.server.persistenceID
            val byServer = shared(ModelVisibility.modelVisibilityByServer)
            if (id in byServer.value) {
                haptics.play(Haptic.tap)
                byServer.update { it - id }
            }
            Reduced(state)
        }
    }

    private fun update(state: State, change: (ModelVisibility) -> ModelVisibility): Reduced<State, Action> {
        val current = state.visibility
        val next = change(current)
        if (next == current) return Reduced(state)
        val id = state.server.persistenceID
        shared(ModelVisibility.modelVisibilityByServer).update { map ->
            if (next == ModelVisibility()) map - id else map + (id to next)
        }
        return Reduced(state)
    }
}

private typealias ModelsStore = ai.opencode.core.store.StoreOf<ModelVisibilityFeature.State, ModelVisibilityFeature.Action>

@Composable
private fun rememberModelsStore(store: SettingsStore, server: OpenCodeServer?): ModelsStore = remember(store) {
    store.scope<SettingsFeature.State, SettingsFeature.Action, ModelVisibilityFeature.State, ModelVisibilityFeature.Action>(
        state = { it.modelVisibility ?: ModelVisibilityFeature.State(it.server ?: server ?: placeholderServer) },
        action = { SettingsFeature.Action.ModelVisibility(it) },
    )
}

private val placeholderServer = OpenCodeServer(url = "", username = "", password = "")

/** Settings › Models. */
@Composable
fun ModelVisibilitySettingsView(store: SettingsStore) {
    val models = rememberModelsStore(store, null)
    val state by models.observe { it }
    val byServer by remember { shared(ModelVisibility.modelVisibilityByServer).flow }.collectAsState()
    val visibility = state.visibility(byServer)
    LaunchedEffect(models) { models.send(ModelVisibilityFeature.Action.Task) }
    val hideTitle = stringResource(R.string.settings_models_mode_hide)
    val onlyShowTitle = stringResource(R.string.settings_models_mode_only_show)
    val providersHeader = stringResource(R.string.settings_models_providers_header)
    val staleHeader = stringResource(R.string.settings_models_stale_header)

    Box(Modifier.fillMaxSize()) {
        SettingsPageScaffold(title = stringResource(R.string.settings_models_title), store = store) {
            item(key = "mode") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SettingsSegmentedControl(
                        options = listOf(ModelVisibility.Mode.hide, ModelVisibility.Mode.onlyShow),
                        selected = visibility.mode,
                        title = { if (it == ModelVisibility.Mode.hide) hideTitle else onlyShowTitle },
                        onSelect = { models.send(ModelVisibilityFeature.Action.ModeChanged(it)) },
                        modifier = Modifier.testTag("model-visibility-mode"),
                    )
                    SettingsFooterText(
                        if (visibility.mode == ModelVisibility.Mode.hide) {
                            stringResource(R.string.settings_models_mode_hide_footer)
                        } else {
                            stringResource(R.string.settings_models_mode_only_show_footer)
                        },
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    )
                }
            }
            val providers = state.providers
            if (providers.isNotEmpty()) {
                settingsSection(key = "providers", header = providersHeader) {
                    providers.forEachIndexed { index, provider ->
                        if (index > 0) SettingsDivider(inset = true)
                        SettingsRowFrame(Modifier.testTag("model-visibility-provider-${provider.id}"), onClick = {
                            store.send(SettingsFeature.Action.PagePushed(SettingsPage.ModelProvider(provider.id)))
                        }) {
                            ProviderIcon(provider.id, Modifier.size(20.dp), provider.canonicalID, tint = OpenCodeTheme.colors.secondaryText)
                            Text(provider.title, style = SettingsText.title, color = OpenCodeTheme.colors.text, modifier = Modifier.weight(1f))
                            Text(state.summary(provider, visibility), style = SettingsText.summary, color = OpenCodeTheme.colors.secondaryText)
                        }
                    }
                }
            }
            val stale = state.staleEntries(visibility)
            if (!stale.isEmpty) {
                settingsSection(
                    key = "stale",
                    header = staleHeader,
                ) {
                    stale.all.forEach { id ->
                        SettingsRowFrame {
                            Text(id, style = OpenCodeTextStyles.subheadline.copy(fontFamily = OpenCodeFonts.monospace), color = OpenCodeTheme.colors.secondaryText)
                        }
                        SettingsDivider()
                    }
                    SettingsButtonRow(
                        stringResource(R.string.settings_models_forget_unavailable),
                        onClick = { models.send(ModelVisibilityFeature.Action.ForgetUnavailableTapped) },
                        modifier = Modifier.testTag("model-visibility-forget-unavailable"),
                    )
                }
            }
            if (state.server.persistenceID in byServer) {
                settingsSection(key = "reset") {
                    SettingsButtonRow(
                        stringResource(R.string.settings_models_show_every_model),
                        onClick = { models.send(ModelVisibilityFeature.Action.ResetTapped) },
                        modifier = Modifier.testTag("model-visibility-reset"),
                    )
                }
            }
        }
        if (state.isLoading && state.models.isEmpty()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else if (state.loadError != null && state.models.isEmpty()) {
            OpenCodeContentUnavailable(
                symbol = SFSymbol.ExclamationmarkTriangle,
                title = stringResource(R.string.settings_models_load_failed_title),
                modifier = Modifier.align(Alignment.Center),
                description = state.loadError,
                action = stringResource(CoreR.string.common_try_again),
                onAction = { models.send(ModelVisibilityFeature.Action.Task) },
            )
        }
    }
}

/**
 * One provider's page: hide the whole provider, or switch its models. Search ranks with
 * `ModelSearch`, as both model pickers do.
 */
@Composable
fun ModelVisibilityProviderView(store: SettingsStore, providerID: String) {
    val models = rememberModelsStore(store, null)
    val state by models.observe { it }
    val byServer by remember { shared(ModelVisibility.modelVisibilityByServer).flow }.collectAsState()
    val visibility = state.visibility(byServer)
    val provider = state.provider(providerID)
    val providerTitle = provider?.title ?: ModelProviderGroup.title(providerID)
    val providerShown = providerID !in visibility.hiddenProviderIDs
    var filter by rememberSaveable { mutableStateOf("") }
    val list = provider?.let { ModelSearch.rank(it.models, filter) }.orEmpty()

    SettingsPageScaffold(title = providerTitle, store = store) {
        item(key = "search") {
            OpenCodeSearchField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = stringResource(R.string.settings_models_search_placeholder),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (filter.isEmpty()) {
            settingsSection(key = "provider") {
                SettingsToggleRow(
                    stringResource(R.string.settings_models_show_provider, providerTitle),
                    checked = providerShown,
                    onCheckedChange = { models.send(ModelVisibilityFeature.Action.ProviderToggled(providerID, it)) },
                    modifier = Modifier.testTag("model-visibility-provider-toggle"),
                )
            }
        }
        if (list.isNotEmpty()) {
            settingsSection(key = "models") {
                list.forEachIndexed { index, model ->
                    if (index > 0) SettingsDivider()
                    SettingsToggleRow(
                        model.name,
                        checked = visibility.isModelOn(model),
                        onCheckedChange = { models.send(ModelVisibilityFeature.Action.ModelToggled(model, it)) },
                        modifier = Modifier.testTag("model-visibility-model-${model.id}"),
                        enabled = providerShown,
                    )
                }
            }
        }
    }
}
