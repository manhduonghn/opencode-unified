package ai.opencode.feature.settings.whatsnew

import ai.opencode.core.sharing.FileDirectory
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer

/**
 * What’s New: a drawer row while some feature is unseen, the page it pushes, and Settings’ switch
 * for the row. Smaller changes never bring the row back. What’s new is seen once the page you read
 * it on closes.
 *
 * The page lives on Settings' stack ([ai.opencode.feature.settings.SettingsFeature] with `root =
 * whatsNew`). The seen IDs and the row switch are shared keys ([seenIDs], [isButtonEnabled]).
 */
class WhatsNewFeature : Reducer<WhatsNewFeature.State, WhatsNewFeature.Action> {
    data class State(
        val entries: List<WhatsNewEntry> = WhatsNewEntry.all,
        /** The drawer’s row opened the page, over the drawer like Settings. */
        val isPresented: Boolean = false,
        /** The list appeared since the page last closed; closing marks it seen. */
        val wasViewed: Boolean = false,
    ) {
        val seenIDs: Set<String> get() = shared(seenIDsKey).value
        val isButtonEnabled: Boolean get() = shared(buttonEnabledKey).value

        val features: List<WhatsNewEntry>
            get() = entries.filter { it.kind == WhatsNewEntry.Kind.feature && it.audience == WhatsNewEntry.Audience.everyone }
        val developerFeatures: List<WhatsNewEntry>
            get() = entries.filter { it.kind == WhatsNewEntry.Kind.feature && it.audience == WhatsNewEntry.Audience.developer }
        val smallerChanges: List<WhatsNewEntry> get() = entries.filter { it.kind == WhatsNewEntry.Kind.smaller }
        val unseenFeatures: List<WhatsNewEntry> get() = unseenFeatures(seenIDs)
        val showsButton: Boolean get() = isButtonEnabled && unseenFeatures.isNotEmpty()

        fun unseenFeatures(seen: Set<String>): List<WhatsNewEntry> = features.filter { it.id !in seen }

        /** A feature that came since the page was last read. */
        fun isNew(entry: WhatsNewEntry, seen: Set<String> = seenIDs): Boolean =
            entry.kind == WhatsNewEntry.Kind.feature && entry.id !in seen

        fun markAllSeen() {
            val ids = entries.map { it.id }.toSet()
            val seen = shared(seenIDsKey)
            if (seen.value.containsAll(ids)) return
            seen.update { it + ids }
        }
    }

    sealed interface Action {
        data object ButtonTapped : Action
        data object HideTapped : Action
        data class ButtonEnabledChanged(val enabled: Boolean) : Action
        /** The list appeared, opened from the drawer or in Settings. */
        data object Viewed : Action
        /** The page it was read on closed (Settings' overlay left the screen). */
        data object Closed : Action
        /**
         * The action on an entry’s page, by the entry’s ID. A Settings page pushes over that page
         * (Settings' reducer); Home takes the rest to a session.
         */
        data class TryTapped(val destination: WhatsNewEntry.Destination, val entryID: String) : Action
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.ButtonTapped -> Reduced(state.copy(isPresented = true))
        Action.HideTapped -> {
            shared(buttonEnabledKey).set(false)
            Reduced(state)
        }
        is Action.ButtonEnabledChanged -> {
            shared(buttonEnabledKey).set(action.enabled)
            Reduced(state)
        }
        Action.Viewed -> Reduced(state.copy(wasViewed = true))
        Action.Closed -> {
            if (state.wasViewed) state.markAllSeen()
            Reduced(state.copy(isPresented = false, wasViewed = false))
        }
        is Action.TryTapped -> Reduced(state)
    }

    companion object {
        /** What’s New entries already shown, by ID. */
        val seenIDsKey = SharedKey.fileStorage<Set<String>>(FileDirectory.Documents, "whats-new-seen.json", emptySet())

        /** Settings → What’s New → Show in Drawer. Hiding the row turns it off. */
        val buttonEnabledKey = SharedKey.appStorage("whats-new-button-enabled", true)
    }
}
