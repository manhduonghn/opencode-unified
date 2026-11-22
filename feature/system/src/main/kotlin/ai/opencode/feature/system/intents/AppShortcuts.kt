package ai.opencode.feature.system.intents

import ai.opencode.feature.system.R
import ai.opencode.feature.system.SessionDirectory
import ai.opencode.core.strings.localized
import ai.opencode.core.ui.SFSymbol
import ai.opencode.server.model.OpenCodeDelivery
import androidx.annotation.StringRes

enum class DeliveryOption(@StringRes private val titleRes: Int, val symbol: SFSymbol, val delivery: OpenCodeDelivery) {
    Steer(R.string.system_delivery_steer, SFSymbol.ArrowTurnUpRight, OpenCodeDelivery.Steer),
    Queue(R.string.system_delivery_queue, SFSymbol.TextBadgePlus, OpenCodeDelivery.Queue),
    ;

    val title: String get() = localized(titleRes)

    companion object {
        val TYPE_DISPLAY_NAME: String get() = localized(R.string.system_delivery_type_name)
    }
}

/** Sends a typed or dictated message to a session without opening the app. */
object AskOpenCodeIntent {
    val TITLE: String get() = localized(R.string.system_ask_title)
    val DESCRIPTION: String get() = localized(R.string.system_ask_description)
    val MESSAGE_TITLE: String get() = localized(R.string.system_ask_message_title)
    val REQUEST_VALUE_DIALOG: String get() = localized(R.string.system_ask_message_placeholder)
    val SESSION_TITLE: String get() = localized(R.string.system_ask_session_title)
    val defaultDelivery = DeliveryOption.Steer
}

/**
 * Android has no phrase-based shortcut provider; each maps to a door in `res/xml/shortcuts.xml`.
 *
 * - Ask OpenCode → [AskOpenCodeActivity] (App Action `actions.intent.CREATE_MESSAGE`).
 * - Start Voice Call, Background Call, New Session, Open Session → App Action
 *   `actions.intent.OPEN_APP_FEATURE` into [SystemActionActivity], whose feature name
 *   [feature] reads back; and the Start Call / Open Session / New Session tiles and widgets.
 *
 * The phrases are kept as the App Action features' names.
 */
object OpenCodeShortcuts {
    data class AppShortcut(val shortTitle: String, val symbol: SFSymbol, val phrases: List<String>)

    val appShortcuts = listOf(
        AppShortcut("Ask OpenCode", SFSymbol.TextBubble, listOf("Ask OpenCode", "Send a message to OpenCode", "Tell OpenCode")),
        AppShortcut(
            "Start Voice Call", SFSymbol.Waveform,
            listOf("Start a voice call in OpenCode", "Call OpenCode", "Talk to OpenCode", "Call OpenCode about <session>"),
        ),
        AppShortcut("Background Call", SFSymbol.ButtonProgrammable, listOf("Start a background call in OpenCode")),
        AppShortcut(
            "New Session", SFSymbol.SquareAndPencil,
            listOf("New OpenCode session", "Start a new session in OpenCode", "New OpenCode session in <project>"),
        ),
        AppShortcut("Open Session", SFSymbol.BubbleLeft, listOf("Open <session> in OpenCode", "Show <session> in OpenCode")),
    )

    sealed interface Feature {
        data object StartVoiceCall : Feature
        data object BackgroundCall : Feature
        data class NewSession(val project: ProjectEntity?) : Feature
        data class OpenSession(val session: SessionEntity) : Feature
    }

    /**
     * Reads an App Action's spoken feature: a call, a background call, a new session
     * (optionally “in <project>”), or a session's name (“Open <session>”), resolved from the
     * directory as Siri resolves an entity.
     */
    fun feature(spoken: String, directory: SessionDirectory): Feature? {
        val text = spoken.trim().lowercase()
        if (text.isEmpty()) return null
        if ("background" in text && "call" in text) return Feature.BackgroundCall
        val aboutSession = Regex("^(?:call|talk to)\\b.*\\babout (.+)$").find(text)?.groupValues?.get(1)
        if (aboutSession != null) {
            directory.sessionEntities(aboutSession).firstOrNull()?.let { return Feature.OpenSession(it) }
        }
        if (text == "call" || text.contains("voice call") || text.startsWith("call") || text.startsWith("talk")) {
            return Feature.StartVoiceCall
        }
        if (text.contains("new session") || text == "new") {
            val project = Regex("\\bin (.+)$").find(text)?.groupValues?.get(1)
                ?.let { directory.projectEntities(it).firstOrNull() }
            return Feature.NewSession(project)
        }
        val name = text.removePrefix("open ").removePrefix("show ").trim()
        return directory.sessionEntities(name).firstOrNull()?.let(Feature::OpenSession)
    }
}
