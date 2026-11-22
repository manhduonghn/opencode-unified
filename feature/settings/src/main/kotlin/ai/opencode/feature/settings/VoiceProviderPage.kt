package ai.opencode.feature.settings

import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.diagnostics.HidesFromScreenshots
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.voice.ServerVoiceProvider
import ai.opencode.feature.voice.VoiceProviderOption
import ai.opencode.feature.voice.VoiceProviderState
import ai.opencode.feature.voice.VoiceProviderTunables
import ai.opencode.feature.voice.VoiceProviderTileKind
import ai.opencode.feature.voice.VoiceProviderTile
import ai.opencode.feature.voice.VoiceProviderStatus
import ai.opencode.feature.voice.VoiceServerChoice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/*
 * Settings › Voice's Voice Provider row, its page, and Your OpenAI Account. Tiles and statuses are
 * voice's `VoiceProviderTile`/`VoiceProviderStatus`, sized by `VoiceProviderTunables`.
 */

/**
 * Settings › Voice's Voice Provider row: who runs calls, where, and whether they can now. It opens the provider's page.
 */
@Composable
internal fun VoiceProviderSettingsRow(state: VoiceSettingsFeature.State, onClick: () -> Unit) {
    val active = state.providerCatalog.active
    val providerTitle = stringResource(R.string.settings_voice_provider_title)
    SettingsRowFrame(
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = providerTitle
            stateDescription = listOfNotNull(active.name, active.state.label).joinToString(", ")
        },
        onClick = onClick,
    ) {
        Row(
            Modifier.weight(1f).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VoiceProviderTile(active, size = VoiceProviderTunables.rowTile.value)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(providerTitle, style = SettingsText.summary, color = OpenCodeTheme.colors.secondaryText)
                Text(active.name, style = OpenCodeTextStyles.headline, color = OpenCodeTheme.colors.text)
                VoiceProviderStatus(active.state)
            }
        }
    }
}

/**
 * The provider calls use: who it is and where it runs, whether calls may move on when it isn't
 * ready, and the other providers to switch to. [opensAccount] (a failed call's card) pushes Your
 * OpenAI Account on arrival.
 */
@Composable
internal fun VoiceProviderDetailPage(store: SettingsStore, opensAccount: Boolean = false) {
    val voice = rememberVoiceStore(store)
    val state = rememberVoiceState(voice)
    val settings = { action: HomeSettingsFeature.Action -> store.send(SettingsFeature.Action.Settings(action)) }
    val catalog = state.providerCatalog
    val active = catalog.active
    var showsUnavailable by rememberSaveable { mutableStateOf(false) }
    var openedAccount by rememberSaveable { mutableStateOf(false) }
    val openAccount = { store.send(SettingsFeature.Action.PagePushed(SettingsPage.VoiceAccount)) }
    LaunchedEffect(voice) { voice.send(VoiceSettingsFeature.Action.ProviderPageAppeared) }
    LaunchedEffect(opensAccount) {
        if (opensAccount && !openedAccount) {
            openedAccount = true
            openAccount()
        }
    }

    /** Stores [option] as the person's pick, or Automatic when null. */
    fun use(option: VoiceProviderOption?) {
        if (option == null) {
            if (state.serverVoiceProvider != ServerVoiceProvider.openAI) {
                settings(HomeSettingsFeature.Action.ServerVoiceProviderChanged(ServerVoiceProvider.openAI))
            }
            settings(HomeSettingsFeature.Action.VoiceServerChanged(VoiceServerChoice.Automatic))
            return
        }
        if (option.provider != state.serverVoiceProvider) {
            settings(HomeSettingsFeature.Action.ServerVoiceProviderChanged(option.provider))
        }
        settings(HomeSettingsFeature.Action.VoiceServerChanged(option.choice))
    }

    val automaticFooter = stringResource(R.string.settings_voice_provider_automatic_footer, active.placeName)
    val othersHeader = stringResource(R.string.settings_voice_provider_others_header)

    SettingsPageScaffold(title = stringResource(R.string.settings_voice_provider_title), store = store) {
        item(key = "header") {
            VoiceProviderHeader(
                option = active,
                title = active.title,
                subtitle = if (active.brand == null) null else stringResource(R.string.settings_voice_provider_place, active.placeName),
                state = active.state,
            )
        }
        if (active.place == VoiceProviderOption.Place.Phone) {
            settingsSection(key = "account") {
                SettingsRow(stringResource(R.string.settings_voice_api_key_title), symbol = null, value = state.keyBadgeTitle, onClick = openAccount)
            }
        }
        settingsSection(
            key = "automatic",
            footer = footerText(automaticFooter),
        ) {
            SettingsToggleRow(
                stringResource(R.string.settings_voice_provider_choose_automatically),
                checked = catalog.isAutomatic,
                onCheckedChange = { use(if (it) null else active) },
            )
        }
        settingsSection(key = "others", header = othersHeader) {
            var rows = 0
            catalog.others.forEach { other ->
                if (rows++ > 0) SettingsDivider()
                val useDescription = stringResource(R.string.settings_voice_provider_use_description, other.name)
                VoiceProviderListRow(other, showsStatus = false) {
                    FilledTonalButton(
                        onClick = { use(other) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = useDescription },
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        shape = OpenCodeShape.control,
                    ) { Text(stringResource(R.string.settings_voice_provider_use), style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold)) }
                }
            }
            if (active.place != VoiceProviderOption.Place.Phone) {
                if (rows++ > 0) SettingsDivider()
                VoiceProviderListRow(catalog.account, showsStatus = catalog.account.isReady, onClick = openAccount) {
                    if (!catalog.account.isReady) {
                        Text(stringResource(R.string.settings_voice_provider_set_up), style = SettingsText.title, color = OpenCodeTheme.colors.secondaryText)
                    }
                }
            }
            if (catalog.unavailable.isNotEmpty()) {
                if (rows++ > 0) SettingsDivider()
                SettingsRowFrame(onClick = { showsUnavailable = !showsUnavailable }) {
                    Text(
                        pluralStringResource(R.plurals.settings_voice_provider_not_available_count, catalog.unavailable.size, catalog.unavailable.size),
                        modifier = Modifier.weight(1f).padding(start = VoiceProviderTunables.listTile.value.dp + 12.dp),
                        style = SettingsText.title,
                        color = OpenCodeTheme.colors.secondaryText,
                    )
                    OpenCodeIcon(
                        SFSymbol.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.rotate(if (showsUnavailable) 90f else 0f),
                        size = 18.dp,
                        tint = OpenCodeTheme.colors.tertiaryText,
                    )
                }
                if (showsUnavailable) {
                    catalog.unavailable.forEach { other ->
                        SettingsDivider()
                        VoiceProviderListRow(other) {}
                    }
                }
            }
        }
    }
}

/** Your OpenAI Account, on this phone: the one place its key appears. */
@Composable
internal fun VoiceProviderAccountPage(store: SettingsStore) {
    val voice = rememberVoiceStore(store)
    val state = rememberVoiceState(voice)
    val settings = { action: HomeSettingsFeature.Action -> store.send(SettingsFeature.Action.Settings(action)) }
    // Picked outright, not by Automatic.
    val isPicked = state.voiceServer == VoiceServerChoice.Phone
    // The key, revealed or about to be, out of every screenshot.
    HidesFromScreenshots()

    val keyFooter = stringResource(R.string.settings_voice_account_key_footer)

    SettingsPageScaffold(title = stringResource(R.string.settings_voice_api_key_title), store = store) {
        item(key = "header") {
            VoiceProviderHeader(
                option = null,
                title = stringResource(R.string.settings_voice_account_title),
                subtitle = stringResource(R.string.settings_voice_account_place),
                state = null,
            )
        }
        settingsSection(
            key = "key",
            footer = footerText(keyFooter),
        ) {
            VoiceKeyEditor(state, voice, settings)
        }
        if (state.hasOpenAIKey && !isPicked) {
            settingsSection(key = "use") {
                SettingsButtonRow(stringResource(R.string.settings_voice_account_use_for_calls), onClick = {
                    settings(HomeSettingsFeature.Action.VoiceServerChanged(VoiceServerChoice.Phone))
                })
            }
        }
    }
}

/** A provider page's header: its tile, name, where it runs and its state. [option] null is Your OpenAI Account. */
@Composable
private fun VoiceProviderHeader(option: VoiceProviderOption?, title: String, subtitle: String?, state: VoiceProviderState?) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (option != null) VoiceProviderTile(option, size = VoiceProviderTunables.headerTile.value) else VoiceProviderTile(VoiceProviderTileKind.openAIAccount, size = VoiceProviderTunables.headerTile.value)
        Text(
            title,
            modifier = Modifier.padding(top = 14.dp).semantics { heading() },
            style = OpenCodeTextStyles.title2.copy(fontWeight = FontWeight.SemiBold),
            color = OpenCodeTheme.colors.text,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                modifier = Modifier.padding(top = 2.dp),
                style = OpenCodeTextStyles.subheadline,
                color = OpenCodeTheme.colors.secondaryText,
            )
        }
        if (state != null && state != VoiceProviderState.unknown) {
            Box(Modifier.padding(top = 12.dp)) { VoiceProviderStatus(state, prominent = true) }
        }
    }
}

/**
 * A provider in a list: its tile, name and where it runs, then a trailing accessory. A server that
 * can't run calls shows its name and why.
 */
@Composable
private fun VoiceProviderListRow(
    option: VoiceProviderOption,
    showsStatus: Boolean = true,
    onClick: (() -> Unit)? = null,
    accessory: @Composable () -> Unit,
) {
    val isAvailable = option.brand != null
    SettingsRowFrame(onClick = onClick, role = if (onClick == null) null else androidx.compose.ui.semantics.Role.Button) {
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.alpha(if (isAvailable) 1f else VoiceProviderTunables.unavailableOpacity.value.toFloat())) {
                VoiceProviderTile(option, size = VoiceProviderTunables.listTile.value)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    option.title,
                    style = SettingsText.title,
                    color = if (isAvailable) OpenCodeTheme.colors.text else OpenCodeTheme.colors.secondaryText,
                )
                if (isAvailable) {
                    Text(option.subtitle, style = OpenCodeTextStyles.subheadline, color = OpenCodeTheme.colors.secondaryText)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        if (showsStatus) VoiceProviderStatus(option.state)
        accessory()
    }
}
