package ai.opencode.feature.settings

import ai.opencode.core.experiments.key
import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.experiments.ExperimentalGroup
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.AllExperiments
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.observe
import ai.opencode.devtools.DevTools
import ai.opencode.devtools.DevToolsView
import ai.opencode.devtools.playgrounds.MotionPlaygroundView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay

/**
 * Settings › Experimental, All Experiments in its own section, then the experimental switches in
 * open groups (Composer, Calls, Drawer, Transcript); a group with one row has no header. Sound
 * Effects' volume and send rows sit under the drawer's switches while it's on. Opened for one
 * experiment (What’s New), it scrolls to that row and briefly highlights it (1.2 s, then a 0.6 s
 * ease-out).
 */
@Composable
fun ExperimentalSettingsView(store: SettingsStore, focus: ExperimentalFeature?) {
    // Every switch, so rows redraw when a value changes.
    val enabled = ExperimentalFeature.allCases.associateWith { feature ->
        remember(feature) { shared(feature.key).flow }.collectAsState().value
    }
    val all by remember { shared(AllExperiments.key).flow }.collectAsState()
    val send = { action: HomeSettingsFeature.Action -> store.send(SettingsFeature.Action.Settings(action)) }
    val listState = rememberLazyListState()
    var highlighted by remember { mutableStateOf<ExperimentalFeature?>(null) }

    // Scroll to the focused row (All Experiments, then each group's section, are one list item
    // each, in order), centred, and tint it.
    LaunchedEffect(focus) {
        val target = focus ?: return@LaunchedEffect
        val group = target.group ?: return@LaunchedEffect
        val index = 1 + ExperimentalGroup.entries.indexOf(group)
        // A frame for the list to lay out its rows first.
        withFrameNanos {}
        listState.scrollToItem(index)
        val info = listState.layoutInfo
        info.visibleItemsInfo.firstOrNull { it.index == index }?.let { item ->
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
            listState.scrollBy((item.offset + item.size / 2 - viewportCenter).toFloat())
        }
        highlighted = target
        delay(1200)
        highlighted = null
    }

    @Composable
    fun ColumnScope.toggles(group: ExperimentalGroup, leadingDivider: Boolean) {
        group.features.forEachIndexed { index, feature ->
            if (leadingDivider || index > 0) SettingsDivider(inset = true)
            ExperimentalFeatureToggle(
                feature = feature,
                enabled = enabled.getValue(feature),
                highlighted = highlighted == feature,
                onChange = { send(HomeSettingsFeature.Action.ExperimentalFeatureChanged(feature, it)) },
            )
        }
    }

    /** A lone row needs no title over it. */
    fun header(group: ExperimentalGroup): String? = if (group.features.size > 1) group.title else null

    val allFooter = stringResource(R.string.settings_experimental_all_footer)

    SettingsPageScaffold(title = stringResource(R.string.settings_experimental_title), store = store, listState = listState) {
        settingsSection(
            key = "experimental-all",
            footer = footerText(
                allFooter,
            ),
        ) {
            SettingsToggleRow(
                title = stringResource(R.string.settings_experimental_all),
                checked = all,
                onCheckedChange = { send(HomeSettingsFeature.Action.AllExperimentsChanged(it)) },
                symbol = "flask.fill",
                tint = SettingsTint.Gray,
            )
        }
        settingsSection(key = ExperimentalGroup.composer, header = header(ExperimentalGroup.composer)) {
            toggles(ExperimentalGroup.composer, leadingDivider = false)
        }
        settingsSection(key = ExperimentalGroup.calls, header = header(ExperimentalGroup.calls)) {
            toggles(ExperimentalGroup.calls, leadingDivider = false)
        }
        settingsSection(key = ExperimentalGroup.drawer, header = header(ExperimentalGroup.drawer)) {
            toggles(ExperimentalGroup.drawer, leadingDivider = false)
            if (ExperimentalFeature.soundEffects.isActive(enabled.getValue(ExperimentalFeature.soundEffects))) {
                SettingsDivider(inset = true)
                SoundEffectsSettingsRows(send)
            }
        }
        settingsSection(key = ExperimentalGroup.transcript, header = header(ExperimentalGroup.transcript)) {
            toggles(ExperimentalGroup.transcript, leadingDivider = false)
        }
    }
}

/** One experiment's switch: its tinted symbol and title. */
@Composable
fun ExperimentalFeatureToggle(
    feature: ExperimentalFeature,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    highlighted: Boolean = false,
) {
    val reduceMotion = rememberReduceMotion()
    val highlight by animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
        highlightSpec(highlighted, reduceMotion),
        label = "experiment-highlight",
    )
    SettingsToggleRow(
        title = feature.title,
        checked = enabled,
        onCheckedChange = onChange,
        symbol = feature.symbol,
        tint = feature.group?.tint ?: SettingsTint.Indigo,
        modifier = Modifier.background(highlight),
    )
}

/** Tinted at once, then fades out on the dim role. */
private fun highlightSpec(highlighted: Boolean, reduceMotion: Boolean): AnimationSpec<Color> =
    if (highlighted) snap() else MotionRole.Dim.animation(MotionPhase.Exit, reduceMotion)

/**
 * Settings' row into Experimental, counting experiments turned on (“2 On”), or “All On” while All
 * Experiments is. The count is a badge.
 */
@Composable
fun ExperimentalSettingsRow(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val all by remember { shared(AllExperiments.key).flow }.collectAsState()
    val features = ExperimentalGroup.entries.flatMap { it.features }
    val enabled = features.map { feature -> remember(feature) { shared(feature.key).flow }.collectAsState().value }
    var on = 0
    for ((index, feature) in features.withIndex()) {
        if (enabled[index]) on += 1
    }
    val badge = if (all) stringResource(R.string.settings_experimental_all_on) else if (on == 0) null else pluralStringResource(R.plurals.settings_experimental_on_count, on, on)
    SettingsRow(stringResource(R.string.settings_experimental_title), "flask.fill", SettingsTint.Gray, modifier = modifier, badge = badge, onClick = onClick)
}

val ExperimentalGroup.tint: SettingsTint
    get() = when (this) {
        ExperimentalGroup.composer -> SettingsTint.Blue
        ExperimentalGroup.calls -> SettingsTint.Green
        ExperimentalGroup.transcript -> SettingsTint.Orange
        ExperimentalGroup.drawer -> SettingsTint.Purple
    }

/**
 * Settings › Developer: `:devtools`' Developer screen (Developer Mode, Record Diagnostics, Demo
 * Call Audio, Troubleshooting, Tuning, Android's tools), with Settings' Playgrounds under Tuning,
 * and the server's Server Details row last while there is a server. Its sub-pages go back inside
 * it; its main page's back pops Settings' stack. The overlay shares the store.
 */
@Composable
fun DeveloperSettingsView(store: SettingsStore) {
    val hasServer by store.observe { it.server != null }
    DevToolsView(
        DevTools.developerStore,
        Modifier.fillMaxSize(),
        onClose = { store.send(SettingsFeature.Action.BackTapped) },
        onOpenPlaygrounds = { store.send(SettingsFeature.Action.PagePushed(SettingsPage.Playgrounds)) },
        onOpenServerDetails = if (hasServer) ({ store.send(SettingsFeature.Action.PagePushed(SettingsPage.ServerDetails)) }) else null,
    )
}

/**
 * Onboarding ports here. Welcome Preview's switch and its Show Welcome row live here, out of
 * Experimental.
 */
@Composable
fun PlaygroundsSettingsView(store: SettingsStore) {
    val welcomePreview by remember { shared(ExperimentalFeature.welcomePreview.key).flow }.collectAsState()
    val send = { action: HomeSettingsFeature.Action -> store.send(SettingsFeature.Action.Settings(action)) }
    SettingsPageScaffold(title = "Playgrounds", store = store) {
        settingsSection(key = "playgrounds", footer = footerText("Try visual changes with built-in examples and no server.")) {
            SettingsRow("Onboarding", "dot.radiowaves.left.and.right", SettingsTint.Indigo, showsChevron = false) {
                send(HomeSettingsFeature.Action.OnboardingPlaygroundOpened)
            }
            SettingsDivider(inset = true)
            SettingsRow("Motion", "sparkles", SettingsTint.Indigo, showsChevron = false) {
                store.send(SettingsFeature.Action.PagePushed(SettingsPage.MotionPlayground))
            }
        }
        settingsSection(key = "welcome-preview", footer = footerText("The first-run welcome, shown before any server is paired.")) {
            ExperimentalFeatureToggle(
                feature = ExperimentalFeature.welcomePreview,
                enabled = welcomePreview,
                onChange = { send(HomeSettingsFeature.Action.ExperimentalFeatureChanged(ExperimentalFeature.welcomePreview, it)) },
            )
            if (ExperimentalFeature.welcomePreview.isActive(welcomePreview)) {
                SettingsDivider(inset = true)
                SettingsRow("Show Welcome", "hand.wave", SettingsTint.Indigo, showsChevron = false) {
                    send(HomeSettingsFeature.Action.WelcomeOpened)
                }
            }
        }
    }
}

/** Settings › Playgrounds › Motion. */
@Composable
fun MotionPlaygroundSettingsView(store: SettingsStore) {
    SettingsPageScaffold(title = "Motion", store = store) {
        item(key = "motion-playground") { MotionPlaygroundView() }
    }
}
