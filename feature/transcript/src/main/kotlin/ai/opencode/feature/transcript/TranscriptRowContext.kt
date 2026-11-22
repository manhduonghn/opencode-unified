package ai.opencode.feature.transcript

import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeRunError
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.staticCompositionLocalOf

/** Per-row values the list hands each row beside its entry. Equal props skip recomposition. */
@Immutable
data class RowProps(
    val canSendNow: Boolean = false,
    val canEditMessage: Boolean = false,
    /** The running reply's last text row: streamed text there holds its lines. */
    val isGrowing: Boolean = false,
    val runErrorActions: List<OpenCodeRunError.Action> = emptyList(),
    val subagents: Map<String, TranscriptSubagent> = emptyMap(),
    /** The targets of the session IDs this row reported showing. */
    val sessionLinks: Map<String, SessionLinkTarget> = emptyMap(),
)

/**
 * View-local row state (tool, compaction, Show More, link-list disclosure) owned by the list, never
 * by a row: a row leaving the screen and coming back (LazyColumn disposes it) keeps what the reader
 * opened. Snapshot-backed sets, so only rows reading an ID recompose when it toggles.
 */
@Stable
class TranscriptDisclosureState {
    val toolGroups = mutableStateSetOf<String>()
    val tools = mutableStateSetOf<String>()
    val compactions = mutableStateSetOf<String>()
    /** Long user messages opened past their fold (`UserBubbleFold`), by message ID. */
    val userMessages = mutableStateSetOf<String>()
    /** Reply-end link lists opened past their first few, by the reply-end row's identity. */
    val linkLists = mutableStateSetOf<String>()

    /**
     * Set by the list: runs a toggle in a row as one motion. Without it the change lands at once.
     * Arguments: row ID, opening, cross-fades, the toggle itself.
     */
    var animateToggle: ((entryID: String, opening: Boolean, crossFades: Boolean, toggle: () -> Unit) -> Unit)? = null

    enum class Kind { ToolGroups, Tools, Compactions, UserMessages, LinkLists }

    fun set(kind: Kind): MutableSet<String> = when (kind) {
        Kind.ToolGroups -> toolGroups
        Kind.Tools -> tools
        Kind.Compactions -> compactions
        Kind.UserMessages -> userMessages
        Kind.LinkLists -> linkLists
    }

    fun isOpen(kind: Kind, id: String): Boolean = id in set(kind)

    /** Closes everything. */
    fun clear() = Kind.entries.forEach { set(it).clear() }

    /** Opens or closes [id] in the row for [entryID]. */
    fun toggle(kind: Kind, id: String, entryID: String, crossFades: Boolean = false) {
        val target = set(kind)
        val opening = id !in target
        val toggle = { if (!target.remove(id)) target.add(id) }
        animateToggle?.invoke(entryID, opening, crossFades, toggle) ?: toggle()
    }

    companion object {
        /**
         * For the list's `rememberSaveable`: what the reader opened outlives the activity being
         * recreated (a dark mode switch, font size, process death). The list is keyed by session,
         * so each session keeps its own.
         */
        val Saver: Saver<TranscriptDisclosureState, Any> = listSaver<TranscriptDisclosureState, ArrayList<String>>(
            save = { state -> Kind.entries.map { ArrayList(state.set(it)) } },
            restore = { saved ->
                TranscriptDisclosureState().apply { Kind.entries.forEachIndexed { index, kind -> set(kind).addAll(saved[index]) } }
            },
        )
    }
}

/** What every transcript row reads from its surroundings. */
@Stable
class TranscriptRowContext(
    val server: OpenCodeServer?,
    val sessionID: String?,
    val directory: String?,
    val disclosure: TranscriptDisclosureState,
    val send: (TranscriptAction) -> Unit,
    /** Image previews inside the transcript; other Markdown surfaces show cards. */
    val imagePreviews: Boolean = true,
)

val LocalTranscriptRowContext = staticCompositionLocalOf {
    TranscriptRowContext(server = null, sessionID = null, directory = null, disclosure = TranscriptDisclosureState(), send = {})
}
