package ai.opencode.feature.composer

import ai.opencode.core.dependencies.DependencyKey

/**
 * Messages whose send failed, kept for the app's lifetime until Retry, Edit, or Delete resolves
 * them, or the server turns out to have them. A screen that opens on their session (or on their
 * unsent draft, for a failed create) shows them again as failed bubbles, so a send is never
 * silently dropped.
 */
class UndeliveredPrompts {
    data class Entry(val pending: PendingPrompt, val reason: String)

    private val lock = Any()
    private val entries = HashMap<String, MutableList<Entry>>()
    private val resolvedIDs = HashSet<String>()
    private val seededKeys = HashSet<String>()
    private var persistence: ComposerPersistence? = null

    fun bind(persistence: ComposerPersistence) {
        synchronized(lock) {
            if (this.persistence?.identity != persistence.identity) seededKeys.clear()
            this.persistence = persistence
        }
    }

    /**
     * [sessionID] is null when the session itself couldn't be created. Keeping a message again
     * (a retry that failed, a draft's new session) moves it.
     */
    fun keep(pending: PendingPrompt, reason: String, serverKey: String, sessionID: String?) {
        val persistence = synchronized(lock) {
            resolvedIDs.remove(pending.messageID)
            remove(setOf(pending.messageID))
            entries.getOrPut(key(serverKey, sessionID)) { mutableListOf() } += Entry(pending, reason)
            persistence
        }
        persistence?.markUndelivered(pending, reason, serverKey, sessionID)
    }

    /** In the order they failed. */
    fun entries(serverKey: String, sessionID: String?): List<Entry> {
        val key = key(serverKey, sessionID)
        val persistence = synchronized(lock) { if (key in seededKeys) null else persistence }
        val persisted = persistence?.loadUndelivered(serverKey, sessionID).orEmpty()
        return synchronized(lock) {
            if (persistence != null && key !in seededKeys) {
                seededKeys += key
                if (persisted.isNotEmpty()) {
                    val existing = entries[key] ?: mutableListOf()
                    val knownIDs = existing.map { it.pending.messageID }.toSet() + resolvedIDs
                    val restored = persisted.filter { it.pending.messageID !in knownIDs }
                    existing.addAll(0, restored)
                    entries[key] = existing
                }
            }
            entries[key]?.toList().orEmpty()
        }
    }

    fun resolve(messageIDs: Set<String>) {
        if (messageIDs.isEmpty()) return
        val persistence = synchronized(lock) {
            resolvedIDs += messageIDs
            remove(messageIDs)
            persistence
        }
        persistence?.settlePrompts(messageIDs)
    }

    private fun remove(messageIDs: Set<String>) {
        if (entries.isEmpty()) return
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.value.removeAll { it.pending.messageID in messageIDs }
            if (entry.value.isEmpty()) iterator.remove()
        }
    }

    companion object Key : DependencyKey<UndeliveredPrompts> {
        override val liveValue: UndeliveredPrompts by lazy { UndeliveredPrompts() }
        override val testValue: UndeliveredPrompts get() = UndeliveredPrompts()

        private fun key(serverKey: String, sessionID: String?) = "$serverKey|${sessionID ?: "new-session"}"
    }
}
