package ai.opencode.feature.settings.whatsnew

import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.ConnectionStatus
import ai.opencode.feature.settings.SettingsFeature
import ai.opencode.feature.voice.VoiceRoutePreferences
import ai.opencode.server.OpenCodeServer

/**
 * [SettingsFeature] pushes `settingsPage()` for an `open` action, and Voice's page for a call
 * before Voice is set up.
 */

/** The page's button: what it does, in Title Case. */
fun WhatsNewEntry.Destination.title(voiceAvailable: Boolean): String = when (this) {
    WhatsNewEntry.Destination.NewSession -> localized(R.string.settings_whatsnew_action_new_session)
    is WhatsNewEntry.Destination.Prompt -> localized(R.string.settings_whatsnew_action_try_it)
    WhatsNewEntry.Destination.ModelList -> localized(R.string.settings_whatsnew_action_choose_model)
    WhatsNewEntry.Destination.Call ->
        if (voiceAvailable) localized(R.string.settings_whatsnew_action_start_call) else localized(R.string.settings_whatsnew_action_set_up_voice)
    is WhatsNewEntry.Destination.Open ->
        if (page == WhatsNewEntry.SettingsDestination.Servers) {
            localized(R.string.settings_whatsnew_action_manage_servers)
        } else {
            localized(R.string.settings_whatsnew_action_open_page, page.title)
        }
}

/** What the button does where its title alone doesn't say. */
val WhatsNewEntry.Destination.hint: String?
    get() = if (this is WhatsNewEntry.Destination.Prompt) localized(R.string.settings_whatsnew_action_try_it_hint) else null

val WhatsNewEntry.SettingsDestination.title: String
    get() = when (this) {
        WhatsNewEntry.SettingsDestination.Themes -> localized(R.string.settings_themes_title)
        WhatsNewEntry.SettingsDestination.Sessions -> localized(R.string.settings_sessions_title)
        WhatsNewEntry.SettingsDestination.Voice -> localized(R.string.settings_voice_title)
        WhatsNewEntry.SettingsDestination.Notifications -> localized(R.string.settings_notifications_title)
        WhatsNewEntry.SettingsDestination.Servers -> localized(R.string.settings_whatsnew_page_servers)
        WhatsNewEntry.SettingsDestination.Experimental -> localized(R.string.settings_experimental_title)
        is WhatsNewEntry.SettingsDestination.Experiment -> ExperimentalFeature.fromRawValue(id)?.title ?: localized(R.string.settings_experimental_title)
        WhatsNewEntry.SettingsDestination.Developer -> localized(R.string.settings_developer_title)
    }

/**
 * Whether the action can go now: a new session needs the server, so offline the page stays.
 */
fun SettingsFeature.State.canTryWhatsNew(destination: WhatsNewEntry.Destination): Boolean = when (destination) {
    WhatsNewEntry.Destination.NewSession, is WhatsNewEntry.Destination.Prompt ->
        connectionStatus != ConnectionStatus.disconnected
    else -> true
}

/** Notifications are hidden, not disabled, where the build has none. */
fun SettingsFeature.State.offersWhatsNew(destination: WhatsNewEntry.Destination): Boolean =
    destination != WhatsNewEntry.Destination.notifications || notificationPermission != null

/** Whether a call can start now. False until Home hands Settings its server. */
fun whatsNewVoiceAvailable(server: OpenCodeServer?): Boolean =
    server != null && VoiceRoutePreferences().isAvailable(server)
