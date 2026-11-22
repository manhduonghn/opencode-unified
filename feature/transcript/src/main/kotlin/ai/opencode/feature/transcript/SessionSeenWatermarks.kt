package ai.opencode.feature.transcript

import ai.opencode.core.sharing.FileDirectory
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.feature.transcript.rows.TranscriptTimeStamps
import kotlinx.serialization.Serializable

// `:core` can't see it, so the type and its key live here (noted in docs/parity/core.md).

/**
 * How far the reader had seen a session as they last left it: the last seen item, the read time the
 * unseen rows count from, and the server read it had already acknowledged while rows stayed unseen.
 */
@Serializable
data class SessionSeenWatermark(
    val itemID: String? = null,
    val viewedMilliseconds: Long? = null,
    val acknowledgedViewedMilliseconds: Long? = null,
) {
    /** A read elsewhere since this watermark was kept makes it stale. */
    fun isOlderThanServerViewed(serverViewed: Long): Boolean {
        if (serverViewed <= 0) return false
        val knownServerViewed = maxOf(viewedMilliseconds ?: 0, acknowledgedViewedMilliseconds ?: 0)
        if (knownServerViewed >= serverViewed) return false
        if (itemID != null) {
            TranscriptTimeStamps.isBefore(serverViewed, itemID)?.let { return it }
        }
        if (knownServerViewed > 0) return serverViewed > knownServerViewed
        return false
    }
}

/** Watermarks by `serverID|sessionID`, the most recent [maximumEntries] kept. */
@Serializable
data class SessionSeenWatermarks(
    val entries: Map<String, SessionSeenWatermark> = emptyMap(),
    val order: List<String> = emptyList(),
) {
    operator fun get(key: String): SessionSeenWatermark? = entries[key]

    /** Stores [watermark] as the most recent, evicting the oldest past [maximumEntries]. */
    fun setting(watermark: SessionSeenWatermark, key: String): SessionSeenWatermarks {
        val entries = entries.toMutableMap()
        entries[key] = watermark
        val order = (order - key + key).toMutableList()
        while (order.size > maximumEntries) entries.remove(order.removeAt(0))
        if (entries.size > order.size) {
            val retained = order.toHashSet()
            entries.keys.retainAll(retained)
        }
        return SessionSeenWatermarks(entries, order)
    }

    fun removing(key: String): SessionSeenWatermarks =
        SessionSeenWatermarks(entries - key, order - key)

    companion object {
        const val maximumEntries = 64
    }
}

private val sessionSeenWatermarksKey = SharedKey.fileStorage(
    FileDirectory.Documents,
    "session-seen-watermarks.json",
    SessionSeenWatermarks(),
)

/**
 * Compact read watermark per session (`serverID|sessionID` → last seen item ID or timestamp),
 * capped to recent sessions with LRU eviction so a cold launch into a session with new messages
 * since it was last viewed still marks what arrived meanwhile.
 */
val SharedKeys.sessionSeenWatermarks: SharedKey<SessionSeenWatermarks> get() = sessionSeenWatermarksKey
