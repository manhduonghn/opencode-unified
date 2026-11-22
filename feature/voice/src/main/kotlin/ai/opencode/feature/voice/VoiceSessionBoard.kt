package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.server.model.OpenCodeSession
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.text.Normalizer
import kotlin.math.abs

/**
 * What the drawer knows about this server's sessions, published by Home
 * while a call is up so the delegator's session tools reuse it rather than
 * reading requests per session. Nothing here comes from a location-scoped
 * read the delegator made: pending requests are Home's bounded attention
 * refresh (`HomeFeature.attentionDirectories`).
 */
data class VoiceSessionBoard(
    val serverID: String,
    /** Home's root sessions. */
    val sessions: List<OpenCodeSession>,
    /** Running roots, subagents' runs included. */
    val busyIDs: Set<String>,
    /** Roots waiting on a permission or question (the drawer's orange). */
    val attentionIDs: Set<String>,
    /** The drawer's blue: unread and not muted. */
    val unreadIDs: Set<String>,
    /** Pins in drawer order. */
    val pinnedIDs: List<String>,
    /**
     * Directories Home's attention refresh reads. A running session outside
     * them may be waiting without Home knowing.
     */
    val attentionDirectories: Set<String>,
    /**
     * Home has a live event stream (or is briefly reconnecting after one), so
     * its indicators are current.
     */
    val isLive: Boolean,
    /** Home's latest attention read succeeded. Filled in by the client. */
    val attentionKnown: Boolean = false,
    /** The drawer and composer, for a Developer Mode screenshot's app state. */
    val home: VoiceAppState.Home? = null,
) {
    /** A yes/no answer the tools can also report as unknown. */
    enum class Flag {
        yes, no, unknown;

        val json: JsonElement
            get() = when (this) {
                yes -> JsonPrimitive(true)
                no -> JsonPrimitive(false)
                unknown -> JsonPrimitive("unknown")
            }

        companion object {
            fun of(value: Boolean): Flag = if (value) yes else no
        }
    }

    fun isRunning(sessionID: String): Boolean = sessionID in busyIDs

    /**
     * A session blocked on a request is still running, so an idle session has
     * none. A running one is known only when Home's latest read succeeded and
     * covered its directory; a failed read is never "no requests".
     */
    fun needsInput(session: OpenCodeSession, running: Boolean): Flag {
        if (!isLive) return if (running) Flag.unknown else Flag.no
        if (session.id in attentionIDs) return Flag.yes
        if (!running) return Flag.no
        if (!attentionKnown || session.directory !in attentionDirectories) return Flag.unknown
        return Flag.no
    }

    fun unread(session: OpenCodeSession): Flag {
        if (sessions.any { it.id == session.id }) {
            return if (isLive) Flag.of(session.id in unreadIDs) else Flag.unknown
        }
        // Outside the drawer's list only the server's own read state is known.
        val idle = session.idleMilliseconds ?: return Flag.unknown
        return Flag.of(idle > (session.viewedMilliseconds ?: -1))
    }

    companion object {
        /**
         * The board while a call is up; null otherwise, so Home pays nothing for it between calls.
         * Home calls this with its own state — `server.persistenceID`, its root sessions, its
         * status snapshot's busy, attention and unread-and-not-muted IDs, its `pinnedSessionKeys`
         * (`<persistenceID>|<sessionID>`), its `attentionDirectories`, and `status.showsActivity`.
         */
        fun make(
            hasVoiceCall: Boolean,
            serverID: String,
            sessions: List<OpenCodeSession>,
            busyIDs: Set<String>,
            attentionIDs: Set<String>,
            unreadIDs: Set<String>,
            pinnedSessionKeys: List<String>,
            attentionDirectories: Set<String>,
            isLive: Boolean,
            /** Home's app state for Developer Mode screenshots, else null. */
            home: VoiceAppState.Home? = null,
        ): VoiceSessionBoard? {
            if (!hasVoiceCall) return null
            val prefix = "$serverID|"
            return VoiceSessionBoard(
                serverID = serverID,
                sessions = sessions,
                busyIDs = busyIDs,
                attentionIDs = attentionIDs,
                unreadIDs = unreadIDs,
                pinnedIDs = pinnedSessionKeys.mapNotNull { key -> if (key.startsWith(prefix)) key.removePrefix(prefix) else null },
                attentionDirectories = attentionDirectories,
                isLive = isLive,
                home = home,
            )
        }
    }
}

class VoiceSessionBoardClient(
    /** Home's latest board, or null when no call is up. */
    val publish: (board: VoiceSessionBoard?) -> Unit,
    /** Whether Home's latest attention read for a server succeeded. */
    val attentionRead: (serverID: String, succeeded: Boolean) -> Unit,
    val current: (serverID: String) -> VoiceSessionBoard?,
) {
    companion object Key : DependencyKey<VoiceSessionBoardClient> {
        /** One process-wide store; tests make their own. */
        fun inMemory(): VoiceSessionBoardClient {
            val lock = Any()
            var board: VoiceSessionBoard? = null
            val attentionKnown = mutableMapOf<String, Boolean>()
            return VoiceSessionBoardClient(
                publish = { next -> synchronized(lock) { board = next } },
                attentionRead = { serverID, succeeded -> synchronized(lock) { attentionKnown[serverID] = succeeded } },
                current = { serverID ->
                    synchronized(lock) {
                        val stored = board
                        if (stored == null || stored.serverID != serverID) {
                            null
                        } else {
                            stored.copy(attentionKnown = attentionKnown[serverID] ?: false)
                        }
                    }
                },
            )
        }

        override val liveValue: VoiceSessionBoardClient by lazy { inMemory() }
        override val testValue: VoiceSessionBoardClient = VoiceSessionBoardClient(publish = {}, attentionRead = { _, _ -> }, current = { null })
    }
}

/**
 * Forgiving title matching for spoken queries: any word order, filler words
 * dropped, word prefixes, run-together words ("earpiece" for "ear piece"),
 * and one misheard letter in longer words.
 */
class VoiceSessionSearch(query: String) {
    val phrase: String = normalize(query)
    val terms: List<String>

    init {
        val words = phrase.split(" ").filter { it.isNotEmpty() }
        val meaningful = words.filter { it !in filler }
        terms = meaningful.ifEmpty { words }
    }

    /**
     * The most specific word, for a second server search when the phrase as a
     * whole matches no title.
     */
    val longestTerm: String? get() = terms.maxByOrNull { it.length }

    /** Null when the session doesn't match; higher is better. */
    fun score(session: OpenCodeSession): Int? {
        if (terms.isEmpty()) return null
        val title = normalize(session.title)
        if (title == phrase) return 100
        if (title.contains(phrase)) return 80
        val words = (title + " " + normalize(File(session.directory).name)).split(" ").filter { it.isNotEmpty() }
        val joined = words.joinToString("")
        val matched = terms.count { term ->
            words.any { it.startsWith(term) || (term.length >= 5 && withinOneEdit(it, term)) } ||
                (term.length >= 4 && joined.contains(term))
        }
        if (matched == terms.size) return 50 + matched * 5
        if (matched > 0 && matched * 2 >= terms.size) return 20 + matched * 5
        return null
    }

    companion object {
        private val filler = setOf(
            "a", "an", "the", "my", "our", "that", "this", "one", "ones", "session", "sessions",
            "about", "for", "on", "in", "of", "to", "and", "with", "chat", "conversation", "called", "named",
        )

        private val combiningMarks = Regex("\\p{Mn}+")

        fun normalize(text: String): String {
            val folded = combiningMarks.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "").lowercase()
            return folded.map { if (it.isLetter() || it.isDigit()) it else ' ' }.joinToString("")
                .split(" ").filter { it.isNotEmpty() }.joinToString(" ")
        }

        fun withinOneEdit(a: String, b: String): Boolean {
            if (abs(a.length - b.length) > 1) return false
            var i = 0
            var j = 0
            var edits = 0
            while (i < a.length && j < b.length) {
                if (a[i] == b[j]) {
                    i += 1
                    j += 1
                    continue
                }
                edits += 1
                if (edits > 1) return false
                if (a.length > b.length) {
                    i += 1
                } else if (a.length < b.length) {
                    j += 1
                } else {
                    i += 1
                    j += 1
                }
            }
            return edits + (a.length - i) + (b.length - j) <= 1
        }
    }
}
