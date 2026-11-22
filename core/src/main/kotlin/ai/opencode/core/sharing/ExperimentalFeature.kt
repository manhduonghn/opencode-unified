package ai.opencode.core.sharing

import ai.opencode.core.R
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.experiments.ExperimentalGroup
import ai.opencode.core.strings.localized
import androidx.annotation.StringRes

/**
 * Features still being tried out on phones, listed in Settings › Experimental, off by default, kept
 * on this phone. Lives in `:core` because Composer, Transcript, Voice, Drawer and Settings all read
 * it; Settings owns the list UI.
 *
 * The entries are the whole list, in Settings order; each one carries its title, group and symbol.
 *
 * Code reads it with `dependency(ExperimentalFeatures)(feature)` (reducers) or
 * `shared(experimental(feature))` (views).
 */
@Suppress("EnumEntryName")
enum class ExperimentalFeature(
    /** The switch's key (`experimental-<rawValue>`) and lab name. */
    val rawValue: String,
    @StringRes private val titleRes: Int,
    /**
     * The Settings › Experimental section it sits in; null for a switch that lives on another
     * Settings page (Welcome Preview, in Developer › Playgrounds).
     */
    val group: ExperimentalGroup?,
    /** SF Symbol name, drawn through `OpenCodeIcons`. */
    val symbol: String,
    /**
     * Removed, but still read by modules that haven't dropped it yet. Not in [allCases], never
     * listed, never on.
     */
    val isRetired: Boolean = false,
    /**
     * All Experiments switches it. False for experiments that ask for a permission or share data,
     * which stay a choice of their own, and for switches outside the pane.
     */
    joinsAll: Boolean = true,
) {
    // Composer
    hideModels("hide-models", R.string.core_experiment_hide_models_title, ExperimentalGroup.composer, "eye.slash"),

    // Calls
    backgroundCallIntent(
        "background-call-intent", R.string.core_experiment_background_call_intent_title, ExperimentalGroup.calls, "button.programmable",
        // Starts the microphone with the app in the background.
        joinsAll = false,
    ),
    voiceSessionDelegator("voice-session-delegator", R.string.core_experiment_voice_session_delegator_title, ExperimentalGroup.calls, "bubble.left.and.bubble.right"),
    shareScreenWithAgents(
        "share-screen-with-agents", R.string.core_experiment_share_screen_with_agents_title, ExperimentalGroup.calls, "rectangle.on.rectangle",
        // Sends a picture of the app to the agent.
        joinsAll = false,
    ),

    // Drawer
    inboxSidebar("inbox-sidebar", R.string.core_experiment_inbox_sidebar_title, ExperimentalGroup.drawer, "tray"),
    sessionLiveActivity(
        "session-live-activity", R.string.core_experiment_session_live_activity_title, ExperimentalGroup.drawer, "platter.filled.top.iphone",
    ),
    soundEffects("sound-effects", R.string.core_experiment_sound_effects_title, ExperimentalGroup.drawer, "speaker.wave.2.fill"),
    serverAddressLinks("server-address-links", R.string.core_experiment_server_address_links_title, ExperimentalGroup.drawer, "link"),

    // Transcript
    inAppBrowser("in-app-browser", R.string.core_experiment_in_app_browser_title, ExperimentalGroup.transcript, "safari"),

    // Settings › Developer › Playgrounds, beside Show Welcome
    welcomePreview("welcome-preview", R.string.core_experiment_welcome_preview_title, null, "hand.wave"),

    // Removed; kept only until the modules that still read them port the removal.

    /** Never on. */
    @Deprecated("Removed on iOS (cd6be7c6)")
    sinceYouLeft("since-you-left", R.string.core_experiment_since_you_left_title, ExperimentalGroup.transcript, "clock.arrow.circlepath", isRetired = true),

    /** Never on; its switch is cleared at launch. */
    @Deprecated("Removed on iOS (c75d4a1e)")
    sessionSwitcher("session-switcher", R.string.core_experiment_session_switcher_title, ExperimentalGroup.drawer, "square.stack", isRetired = true),

    /** Never on; its switch is cleared at launch. */
    @Deprecated("Removed on iOS (c75d4a1e)")
    serverTitleSwitch("server-title-switch", R.string.core_experiment_server_title_switch_title, ExperimentalGroup.drawer, "server.rack", isRetired = true),
    ;

    val id: String get() = rawValue

    val title: String get() = localized(titleRes)

    /** All Experiments switches it: it didn't opt out and sits in the pane. */
    val joinsAll: Boolean = joinsAll && group != null && !isRetired

    /** Retired: always off. */
    private val isOff: Boolean get() = isRetired

    /** Whether this experiment is on in a view, from its stored switch (`shared(experimental(this))`). */
    fun isActive(stored: Boolean): Boolean = if (isOff) false else stored

    companion object {
        /** Every experiment, in Settings order (declaration order). Retired ones aside. */
        val allCases: List<ExperimentalFeature> by lazy {
            entries.filter { !it.isRetired }
        }

        /** The registered experiment with this key, or null. */
        fun fromRawValue(rawValue: String): ExperimentalFeature? = allCases.firstOrNull { it.rawValue == rawValue }

        /**
         * Switches of removed experiments (Session Switcher, Title Is the Switch, Floating Call,
         * Call Tray, the Model Picker menu and the switches before it) and of ones now always on
         * (Composer Autocomplete, Server Flow, New Plus Menu, Project Icons, Image Link Previews,
         * Living Orb, New Messages Divider, Open Links from Calls, Audio in Calls, Server Details,
         * Server Voice, One Picker, Agent Selection), with the call-style migration's markers. Nothing reads them;
         * they're cleared at launch.
         */
        val retiredKeys: List<String> = buildList {
            add("experimental-session-switcher")
            add("experimental-server-title-switch")
            add("experimental-composer-autocomplete")
            add("experimental-server-flow")
            add("experimental-plus-menu-sheet")
            add("experimental-project-icons")
            add("experimental-floating-call")
            add("experimental-call-tray")
            add("call-style-migrated-living-orb")
            add("call-style-migrated-orb-default")
            add("experimental-model-picker")
            add("experimental-new-model-picker")
            add("experimental-custom-model-picker")
            val alwaysOn = listOf(
                "image-link-previews", "living-orb", "new-messages-divider",
                "open-links-from-calls", "audio-in-calls", "server-details", "server-voice",
                "one-picker", "agent-selection",
            )
            for (name in alwaysOn) {
                add("experimental-$name")
                add("experimental-all-skip-$name")
                add("experimental-all-saved-$name")
            }
        }

        fun removeRetiredSwitches(store: SharedStore) {
            for (key in retiredKeys) store.remove(SharedKey.appStorage(key, false))
        }
    }
}

/** One experimental feature's switch: `experimental-<rawValue>`, off by default. */
fun experimental(feature: ExperimentalFeature): SharedKey<Boolean> =
    SharedKey.appStorage("experimental-${feature.rawValue}", false)

/** The experimental switches as reducers read them, overridable in tests. */
class ExperimentalFeatures(val isEnabled: (ExperimentalFeature) -> Boolean) {
    operator fun invoke(feature: ExperimentalFeature): Boolean = isEnabled(feature)

    companion object Key : DependencyKey<ExperimentalFeatures> {
        override val liveValue = ExperimentalFeatures { feature -> feature.isActive(shared(experimental(feature)).value) }
        override val testValue = ExperimentalFeatures { false }

        /** Every experiment on, or only [features]. */
        fun enabling(features: Set<ExperimentalFeature> = ExperimentalFeature.allCases.toSet()) =
            ExperimentalFeatures { it in features }
    }
}
