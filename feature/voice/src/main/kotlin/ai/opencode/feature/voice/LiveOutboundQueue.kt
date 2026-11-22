package ai.opencode.feature.voice

import kotlin.time.Duration

/** Priority and channel-hold role for an outbound GPT-Live frame. */
sealed interface LiveOutboundRole {
    /** A delegation's own answer: ordered ahead of announcements and context,
     * waits only on active speech, and protects its speech once delivered. */
    data class Answer(val readyAt: Duration, val delegatedAt: Duration?) : LiveOutboundRole

    /** Spoken news nobody is waiting on in this turn (`announce` or unbound
     * non-answer commentary). */
    data class Announcement(
        val id: String?,
        val sessionID: String?,
        val promptIDs: List<String>,
        val enqueuedAt: Duration,
    ) : LiveOutboundRole

    /** Session context (`session.instructions.append`), coalesced on retarget. */
    data object Context : LiveOutboundRole

    /** Ephemeral progress line (`VoiceProgressLine`), never stored in the queue. */
    data object Progress : LiveOutboundRole
}

/**
 * Distinguishes inert context instructions from unbound results/notices that
 * must be spoken aloud when `unboundSpeechUsesInstructions` rewrites them onto
 * `session.instructions.append`.
 */
enum class LiveInstructionIntent { silentContext, spokenDirective }

data class LiveOutboundItem(
    val role: LiveOutboundRole,
    val kind: LiveAppendKind,
    val delegationID: String?,
    val content: String,
    val enqueuedAt: Duration? = null,
    /** Set on ephemeral progress lines so `VoiceResultRecord` ignores them. */
    val origin: String? = null,
    /** The socket `delegationID` belongs to; cleared if sent on another socket. */
    val socketID: Int? = null,
    /** Queued while disconnected/attaching or requeued from a failed send. */
    val fromEarlierSocket: Boolean = false,
    /** Prevents immediate retry on the same broken socket before it closes. */
    val afterSocketID: Int? = null,
) {
    data class Answer(val readyAt: Duration, val delegatedAt: Duration?)

    val instructionIntent: LiveInstructionIntent
        get() = if (kind == LiveAppendKind.commentary) LiveInstructionIntent.spokenDirective else LiveInstructionIntent.silentContext

    val answer: Answer?
        get() = (role as? LiveOutboundRole.Answer)?.let { Answer(it.readyAt, it.delegatedAt) }

    val isAnswer: Boolean get() = role is LiveOutboundRole.Answer
}

/**
 * Single outbound queue for GPT-Live appends across quiet holds, background
 * announcements, and socket reconnects. Answers sit ahead of announcements,
 * context, and wire closures while preserving chunk order within each role.
 */
class LiveOutboundQueue(items: List<LiveOutboundItem> = emptyList()) {
    private val _items = items.toMutableList()

    /** A separate queue holding the same items. */
    fun copy() = LiveOutboundQueue(_items)
    val items: List<LiveOutboundItem> get() = _items.toList()

    val isEmpty: Boolean get() = _items.isEmpty()
    val count: Int get() = _items.size

    val oldestAnnouncementAt: Duration?
        get() = _items.mapNotNull { (it.role as? LiveOutboundRole.Announcement)?.enqueuedAt }.minOrNull()

    /** Earliest timestamp among queued answers and announcements, so a freshly
     * completed answer never waits longer on chatter than an older result. */
    val oldestResultAt: Duration?
        get() = _items.mapNotNull { item ->
            when (val role = item.role) {
                is LiveOutboundRole.Answer -> role.readyAt
                is LiveOutboundRole.Announcement -> role.enqueuedAt
                LiveOutboundRole.Context, LiveOutboundRole.Progress -> null
            }
        }.minOrNull()

    fun hasAnswers(forSocket: Int?): Boolean = _items.any { it.afterSocketID != forSocket && it.isAnswer }

    fun hasEligibleItems(forSocket: Int?): Boolean = _items.any { it.afterSocketID != forSocket }

    fun enqueue(newItems: List<LiveOutboundItem>, now: Duration? = null) {
        for (newItem in newItems) {
            val item = if (newItem.enqueuedAt == null && now != null) newItem.copy(enqueuedAt = now) else newItem
            if (item.isAnswer) {
                val index = _items.indexOfFirst { !it.isAnswer }.takeIf { it >= 0 } ?: _items.size
                _items.add(index, item)
            } else {
                _items += item
            }
        }
    }

    fun requeueFront(rest: List<LiveOutboundItem>) {
        if (rest.isEmpty()) return
        if (rest[0].isAnswer) {
            _items.addAll(0, rest)
        } else {
            val index = _items.indexOfFirst { !it.isAnswer }.takeIf { it >= 0 } ?: _items.size
            _items.addAll(index, rest)
        }
    }

    /** Roles that determine when the flush timer next wakes on `socketID`.
     * When an eligible answer is queued, nothing lower-priority may pass it. */
    fun candidateRoles(forSocket: Int): List<LiveOutboundRole> {
        _items.firstOrNull { it.afterSocketID != forSocket && it.isAnswer }?.let { return listOf(it.role) }
        return _items.filter { it.afterSocketID != forSocket }.map { it.role }
    }

    /**
     * Pops the next ready batch for `socketID`: either one whole answer's
     * chunks (stopping so `answerProtection` takes effect before trailing
     * notices), or all ready non-answer items in FIFO order.
     */
    fun popReadyBatch(forSocket: Int, isReady: (LiveOutboundRole) -> Boolean): List<LiveOutboundItem> {
        val firstIndex = _items.indexOfFirst { it.afterSocketID != forSocket && it.isAnswer }
        if (firstIndex >= 0) {
            val target = _items[firstIndex].answer
            if (!isReady(_items[firstIndex].role)) return emptyList()
            val batch = mutableListOf<LiveOutboundItem>()
            val index = firstIndex
            while (index < _items.size) {
                val item = _items[index]
                if (item.afterSocketID != forSocket && item.answer == target) batch += _items.removeAt(index) else break
            }
            return batch
        }
        val batch = mutableListOf<LiveOutboundItem>()
        var index = 0
        while (index < _items.size) {
            val item = _items[index]
            if (item.afterSocketID != forSocket && isReady(item.role)) batch += _items.removeAt(index) else index++
        }
        return batch
    }

    fun cancelAnnouncements(answered: Set<String>): Int {
        val before = _items.size
        _items.removeAll { item ->
            val role = item.role as? LiveOutboundRole.Announcement ?: return@removeAll false
            role.promptIDs.isNotEmpty() && role.promptIDs.all { it in answered }
        }
        return before - _items.size
    }

    /** Deduped result summaries queued in `outbound` that have not yet been
     * delivered to a live socket, for seeding a replacement session. */
    val unreadResultEntries: List<String>
        get() {
            val seen = mutableSetOf<String>()
            val entries = mutableListOf<String>()
            for (item in _items) {
                if (item.kind != LiveAppendKind.commentary || item.origin != null) continue
                val entry = VoiceResultRecord.entry(item.content)
                if (entry.isNotEmpty() && seen.add(entry)) entries += entry
            }
            return entries
        }

    fun detachFromRetiredSocket(activeSocketID: Int?) {
        for (index in _items.indices) {
            val socketID = _items[index].socketID ?: continue
            if (socketID != activeSocketID) _items[index] = _items[index].copy(delegationID = null, fromEarlierSocket = true)
        }
    }

    fun dropContext(): Int {
        val before = _items.size
        _items.removeAll { it.role == LiveOutboundRole.Context }
        return before - _items.size
    }

    fun removeAll() = _items.clear()
}
