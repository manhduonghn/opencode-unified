package ai.opencode.feature.settings

import ai.opencode.core.ui.OpenCodeSearchField
import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.motion.MotionPresence
import ai.opencode.core.strings.localized
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.voice.ServerVoiceProvider
import ai.opencode.feature.voice.VoiceDelegatorChoice
import ai.opencode.feature.voice.VoiceDelegatorRouteAvailability
import ai.opencode.feature.voice.VoiceDelegatorWorkspace
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.model.OpenCodeSession
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/* Settings › Voice's Delegator row and page. */

val VoiceSettingsFeature.State.showsDelegatorRow: Boolean
    get() = ExperimentalFeature.voiceSessionDelegator.isActive(voiceSessionDelegatorExperiment)

val VoiceSettingsFeature.State.delegatorRouteAvailability: VoiceDelegatorRouteAvailability
    get() = VoiceDelegatorRouteAvailability.resolve(
        route = route,
        selectedEngine = voiceEngine,
        isGeminiProvider = broker.isEnabled && broker.provider == ServerVoiceProvider.geminiLive,
    )

/** Root sessions on the connected server for picking a delegator session. */
val VoiceSettingsFeature.State.delegatorSessions: List<OpenCodeSession>
    get() {
        val server = connectedServer
        val source = when {
            sessions.isNotEmpty() -> sessions
            server != null -> cachedHomeSnapshots[server.persistenceID]?.snapshot?.sessions.orEmpty()
            else -> emptyList()
        }
        val pickedID = voiceDelegatorChoice.picked?.sessionID
        return source
            .filter { it.parentID == null && (it.archivedMilliseconds == null || it.id == pickedID) }
            .sortedWith(compareByDescending<OpenCodeSession> { it.updatedAt }.thenByDescending { it.id })
    }

/** Cross-server mismatch when the saved picked session belongs to another server. */
val VoiceSettingsFeature.State.crossServerPickedSession: VoiceDelegatorChoice.Picked?
    get() {
        val picked = voiceDelegatorChoice.picked ?: return null
        val server = connectedServer ?: return null
        return if (picked.serverID != VoiceDelegatorWorkspace.serverID(server)) picked else null
    }

val VoiceSettingsFeature.State.delegatorRowValue: String
    get() {
        val choice = voiceDelegatorChoice
        if (!delegatorRouteAvailability.isAvailable) {
            return if (choice.usesOpenCodeSession) localized(R.string.settings_delegator_direct_session_unavailable) else localized(R.string.settings_delegator_direct)
        }
        return when (choice) {
            VoiceDelegatorChoice.BuiltIn -> localized(R.string.settings_delegator_built_in)
            VoiceDelegatorChoice.AutomaticSession -> localized(R.string.settings_delegator_automatic)
            VoiceDelegatorChoice.ViewedSession -> localized(R.string.settings_delegator_viewed_session)
            is VoiceDelegatorChoice.PickedSession -> {
                if (crossServerPickedSession != null) return localized(R.string.settings_delegator_another_server, choice.picked.displayTitle)
                delegatorSessions.firstOrNull { it.id == choice.picked.sessionID }
                    ?.let { VoiceFeature.State.usableTitle(it.title) }
                    ?: choice.picked.displayTitle
            }
        }
    }

/** Settings › Voice › Delegator row. */
@Composable
internal fun VoiceDelegatorSettingsRow(state: VoiceSettingsFeature.State, onClick: () -> Unit) {
    SettingsChoiceRow(stringResource(R.string.settings_delegator_title), value = state.delegatorRowValue, onClick = onClick)
}

/**
 * Settings › Voice › Delegator: chooses Built-in or an OpenCode Session target (Automatic
 * “Voice”, Viewed Session, or a picked session on this server).
 */
@Composable
internal fun VoiceDelegatorSettingsPage(store: SettingsStore) {
    val voice = rememberVoiceStore(store)
    val state = rememberVoiceState(voice)
    var searchQuery by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(voice) { voice.send(VoiceSettingsFeature.Action.DelegatorPageAppeared) }

    val choice = state.voiceDelegatorChoice
    val availability = state.delegatorRouteAvailability
    val isAvailable = availability.isAvailable
    val otherServerPick = state.crossServerPickedSession
    val query = searchQuery.trim()
    val filteredSessions = if (query.isEmpty()) {
        state.delegatorSessions
    } else {
        state.delegatorSessions.filter { session ->
            session.title.contains(query, ignoreCase = true) ||
                lastPathComponent(session.directory).contains(query, ignoreCase = true) ||
                session.id.contains(query, ignoreCase = true)
        }
    }

    fun select(next: VoiceDelegatorChoice) {
        voice.send(VoiceSettingsFeature.Action.VoiceDelegatorChoiceChanged(next))
        store.send(SettingsFeature.Action.Settings(HomeSettingsFeature.Action.VoiceDelegatorChoiceChanged(next)))
    }
    val modeFooter = stringResource(R.string.settings_delegator_mode_footer)
    val targetHeader = stringResource(R.string.settings_delegator_target_header)
    val targetFooter = stringResource(R.string.settings_delegator_target_footer)
    val sessionsHeader = stringResource(R.string.settings_delegator_sessions_header)
    val sessionsFooter = stringResource(R.string.settings_delegator_sessions_footer)

    SettingsPageScaffold(title = stringResource(R.string.settings_delegator_title), store = store) {
        val explanation = availability.explanation
        if (explanation != null) {
            settingsSection(key = "unavailable") {
                DelegatorNote(explanation, SFSymbol.InfoCircle, OpenCodeTheme.colors.secondaryText)
            }
        } else if (otherServerPick != null) {
            settingsSection(key = "other-server") {
                DelegatorNote(
                    stringResource(R.string.settings_delegator_other_server_note, otherServerPick.displayTitle),
                    SFSymbol.ExclamationmarkTriangle,
                    OpenCodeTheme.colors.warning,
                )
            }
        }
        settingsSection(
            key = "mode",
            footer = footerText(modeFooter),
        ) {
            ModeRow(
                title = stringResource(R.string.settings_delegator_built_in),
                isSelected = !choice.usesOpenCodeSession || !isAvailable,
            ) { select(VoiceDelegatorChoice.BuiltIn) }
            SettingsDivider()
            ModeRow(
                title = stringResource(R.string.settings_delegator_opencode_session),
                isSelected = choice.usesOpenCodeSession && isAvailable,
                isEnabled = isAvailable,
            ) { if (!choice.usesOpenCodeSession) select(VoiceDelegatorChoice.AutomaticSession) }
        }
        if (choice.usesOpenCodeSession && isAvailable) {
            settingsSection(
                key = "target",
                header = targetHeader,
                footer = footerText(targetFooter),
            ) {
                ModeRow(
                    title = stringResource(R.string.settings_delegator_automatic),
                    isSelected = choice == VoiceDelegatorChoice.AutomaticSession,
                ) { select(VoiceDelegatorChoice.AutomaticSession) }
                SettingsDivider()
                ModeRow(
                    title = stringResource(R.string.settings_delegator_viewed_session),
                    isSelected = choice == VoiceDelegatorChoice.ViewedSession,
                ) { select(VoiceDelegatorChoice.ViewedSession) }
                if (otherServerPick != null) {
                    SettingsDivider()
                    ModeRow(title = otherServerPick.displayTitle, isSelected = true) {}
                }
            }
            val server = state.connectedServer
            if (server != null) {
                item(key = "search") {
                    OpenCodeSearchField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = stringResource(R.string.settings_delegator_search_placeholder),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                settingsSection(
                    key = "sessions",
                    header = sessionsHeader,
                    footer = footerText(sessionsFooter),
                ) {
                    if (filteredSessions.isEmpty()) {
                        SettingsRowFrame {
                            Text(
                                if (searchQuery.isEmpty()) stringResource(R.string.settings_delegator_no_sessions) else stringResource(R.string.settings_delegator_no_matching_sessions),
                                style = SettingsText.title,
                                color = OpenCodeTheme.colors.secondaryText,
                            )
                        }
                    } else {
                        val serverID = VoiceDelegatorWorkspace.serverID(server)
                        filteredSessions.forEachIndexed { index, session ->
                            if (index > 0) SettingsDivider()
                            val folder = lastPathComponent(session.directory)
                            val picked = choice.picked
                            ModeRow(
                                title = VoiceFeature.State.usableTitle(session.title) ?: stringResource(R.string.settings_delegator_new_session),
                                subtitle = if (folder.isEmpty() || folder == "/") null else folder,
                                isSelected = picked?.sessionID == session.id && picked.serverID == serverID,
                            ) { select(VoiceDelegatorChoice.PickedSession(VoiceDelegatorChoice.Picked(session, server))) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DelegatorNote(text: String, symbol: SFSymbol, color: androidx.compose.ui.graphics.Color) {
    SettingsRowFrame {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            OpenCodeIcon(symbol, contentDescription = null, size = 18.dp, tint = color)
            Text(text, style = OpenCodeTextStyles.subheadline, color = color)
        }
    }
}

@Composable
private fun ModeRow(
    title: String,
    subtitle: String? = null,
    isSelected: Boolean,
    isEnabled: Boolean = true,
    onClick: () -> Unit,
) {
    SettingsRowFrame(
        Modifier.semantics(mergeDescendants = true) { selected = isSelected },
        onClick = onClick,
        enabled = isEnabled,
        role = Role.Button,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = SettingsText.title,
                color = if (isEnabled) OpenCodeTheme.colors.text else OpenCodeTheme.colors.secondaryText,
            )
            if (subtitle != null) {
                Text(subtitle, style = SettingsText.summary, color = OpenCodeTheme.colors.secondaryText)
            }
        }
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(18.dp)) {
            MotionPresence(isOpen = isSelected) {
                OpenCodeIcon(SFSymbol.Checkmark, contentDescription = null, size = 18.dp, tint = OpenCodeTheme.colors.selection)
            }
        }
    }
}

private fun lastPathComponent(path: String): String {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty()) return if (path.isEmpty()) "" else "/"
    return trimmed.substringAfterLast('/')
}
