package ai.opencode.feature.transcript

import androidx.compose.ui.unit.dp
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.rows.TranscriptTimeStamps
import ai.opencode.server.model.AgentFileReference
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeQueuedInput
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.model
import androidx.compose.runtime.Immutable

/**
 * Detects a row removed and restored within five seconds, independently of whether that row is on
 * screen. IDs only, no content.
 */
class TranscriptSnapshotTrace {
    private var sessionID: String? = null
    private var previous: Set<String> = emptySet()
    private var missing: MutableMap<String, Double> = mutableMapOf()

    fun record(sessionID: String?, ids: List<String>, now: Double): Pair<List<String>, List<String>> {
        if (this.sessionID != sessionID) {
            this.sessionID = sessionID
            previous = emptySet()
            missing = mutableMapOf()
        }
        missing = missing.filterValues { now - it <= 5 }.toMutableMap()
        val current = ids.toSet()
        val vanished = (previous - current).sorted()
        for (id in vanished) missing[id] = now
        val reappeared = current.filter { missing[it] != null }.sorted()
        for (id in reappeared) missing.remove(id)
        previous = current
        return vanished to reappeared
    }
}

/** A stable, list-sized unit of transcript presentation. */
@Immutable
sealed interface TranscriptEntry {
    data class Single(val item: OpenCodeTranscriptItem) : TranscriptEntry
    data class UserGroup(val groupID: String, val items: List<OpenCodeTranscriptItem>, val startsTurn: Boolean) : TranscriptEntry
    data class ToolGroup(val groupID: String, val tools: List<OpenCodeTranscriptItem>) : TranscriptEntry

    /**
     * After a finished reply: actions on the whole reply, such as Copy. Android carries the
     * assistant texts (items keep raw Markdown) and Copy parses and serializes them on tap
     * (`OpenCodeMarkdownBlock.markdown`).
     */
    data class ReplyEnd(val endID: String, val texts: List<String>) : TranscriptEntry

    val id: String
        get() = when (this) {
            is Single -> item.id
            is UserGroup -> "user-$groupID"
            is ToolGroup -> "group-$groupID"
            is ReplyEnd -> endID
        }

    /** IDs of all transcript items represented by this list entry. */
    val itemIDs: List<String>
        get() = when (this) {
            is Single -> listOf(item.id)
            is UserGroup -> items.map { it.id }
            is ToolGroup -> tools.map { it.id }
            is ReplyEnd -> emptyList()
        }

    private val toolCalls: List<OpenCodeToolCall>
        get() = when (this) {
            is Single -> listOfNotNull((item as? OpenCodeTranscriptItem.Tool)?.call)
            is ToolGroup -> tools.mapNotNull { (it as? OpenCodeTranscriptItem.Tool)?.call }
            else -> emptyList()
        }

    val toolImageAttachments: List<OpenCodeImageAttachment> get() = toolCalls.flatMap { it.imageAttachments }

    /** Playable results are file cards, not undecodable image mosaic tiles. */
    val toolVideoReferences: List<AgentFileReference>
        get() = toolCalls.flatMap { it.videos.orEmpty() }.mapNotNull { it.mediaViewerReference }
            .filter { it.kind == AgentFileReference.Kind.Video }.distinctBy { it.id }

    /** The members of a group whose members can move between rows. */
    val groupMembers: List<OpenCodeTranscriptItem>?
        get() = when (this) {
            is ToolGroup -> tools
            is UserGroup -> items
            else -> null
        }

    /** The prompt ID represented by a user group. */
    val userMessageID: String?
        get() = (this as? UserGroup)?.items?.firstOrNull()?.let(::promptID)

    /** Subagent sessions this entry's tool rows open. */
    val subagentSessionIDs: List<String>
        get() = when (this) {
            is Single -> {
                val call = (item as? OpenCodeTranscriptItem.Tool)?.call
                if (call != null && (call.name == "subagent" || call.name == "task")) {
                    listOfNotNull(call.metadata["sessionID"]?.string)
                } else {
                    emptyList()
                }
            }
            is ToolGroup -> tools.mapNotNull { item ->
                val call = (item as? OpenCodeTranscriptItem.Tool)?.call ?: return@mapNotNull null
                if (call.name != "subagent" && call.name != "task") return@mapNotNull null
                call.metadata["sessionID"]?.string
            }
            else -> emptyList()
        }

    val layoutKind: String
        get() = when (this) {
            is UserGroup -> "user"
            is ToolGroup -> "tools"
            is ReplyEnd -> "reply-end"
            is Single -> when (item) {
                is OpenCodeTranscriptItem.AssistantMarkdown -> when {
                    item.text.contains("\n|") || item.text.startsWith("|") -> "table"
                    item.text.contains("```") -> "code"
                    else -> "markdown"
                }
                is OpenCodeTranscriptItem.Compaction -> "compaction"
                is OpenCodeTranscriptItem.ModelSwitch -> "model-switch"
                else -> "other"
            }
        }

    /** Rows meet at the sum of their halves, on the live row tunables. */
    val rowInsets: RowInsets
        get() {
            val flow = TranscriptTunables.flowHalf.dp
            val turn = TranscriptTunables.turnHalf.dp
            val section = TranscriptTunables.sectionHalf.dp
            return when (this) {
                is UserGroup -> RowInsets(if (startsTurn) turn else 0.dp, turn)
                is Single -> when (val item = item) {
                    is OpenCodeTranscriptItem.Compaction -> RowInsets(section, section)
                    // Set apart from the reply above, close over the message it applied to.
                    is OpenCodeTranscriptItem.ModelSwitch -> RowInsets(section, flow)
                    // A time sits close over the message it dates.
                    is OpenCodeTranscriptItem.Info if item.id.startsWith(TranscriptTimeStamps.PREFIX) ->
                        RowInsets(section, TranscriptMetrics.compact)
                    else -> RowInsets(flow, flow)
                }
                is ToolGroup -> RowInsets(flow, flow)
                // Belongs to the reply above it; the next row is a new turn.
                is ReplyEnd -> RowInsets(flow, turn)
            }
        }

    companion object {
        /** Image rows use the decoder's deterministic `<prompt-id>-images` suffix. */
        fun promptID(item: OpenCodeTranscriptItem): String? = when (item) {
            is OpenCodeTranscriptItem.UserText -> item.id
            is OpenCodeTranscriptItem.UserImages -> item.id.removeSuffix("-images")
            is OpenCodeTranscriptItem.UserFiles -> item.id.removeSuffix("-files")
            else -> null
        }

        /**
         * A session created without a model gets its first one as a switch from nothing ahead
         * of anything else: that's no switch. Read from all the loaded items.
         */
        fun sessionStartModelIDs(items: List<OpenCodeTranscriptItem>): Set<String> {
            val ids = mutableSetOf<String>()
            for (item in items) {
                if (item !is OpenCodeTranscriptItem.ModelSwitch) return ids
                if (item.previous == null) ids += item.id
            }
            return ids
        }

        private fun isPendingQuestion(item: OpenCodeTranscriptItem): Boolean {
            val call = (item as? OpenCodeTranscriptItem.Tool)?.call ?: return false
            return call.name == "question" &&
                (call.questions != null || call.status == OpenCodeToolCall.Status.Running || call.status == OpenCodeToolCall.Status.Streaming)
        }

        private fun isRunningOrStreaming(call: OpenCodeToolCall): Boolean =
            call.status == OpenCodeToolCall.Status.Running || call.status == OpenCodeToolCall.Status.Streaming

        private sealed interface Run {
            data object Tools : Run
            data class User(val promptID: String) : Run
        }

        /**
         * Groups raw transcript items into the entries rendered by the list. Each finished reply
         * ends with a [ReplyEnd]; the last one only once the session stops running. Model
         * switches take their names from [models] (session-start switches are left out).
         */
        fun build(
            items: List<OpenCodeTranscriptItem>,
            isRunning: Boolean = false,
            models: List<OpenCodeModel> = emptyList(),
            sessionStartModels: Set<String>? = null,
        ): List<TranscriptEntry> {
            val startModels = sessionStartModels ?: sessionStartModelIDs(items)
            val entries = mutableListOf<TranscriptEntry>()
            val run = mutableListOf<OpenCodeTranscriptItem>()
            var kind: Run? = null

            fun flush() {
                when (kind) {
                    Run.Tools -> entries += ToolGroup(run[0].id, run.toList())
                    is Run.User -> {
                        val startsTurn = entries.lastOrNull() !is UserGroup
                        entries += UserGroup(run[0].id, run.toList(), startsTurn)
                    }
                    null -> Unit
                }
                run.clear()
                kind = null
            }

            fun append(item: OpenCodeTranscriptItem, next: Run) {
                if (kind != next) flush()
                kind = next
                run += item
            }

            val reply = mutableListOf<String>()
            var replyID: String? = null
            // The turn already shows how its run ended: the run's idle message repeats its last step's error.
            var turnHasRunError = false
            fun endReply() {
                val id = replyID
                if (id != null && reply.isNotEmpty()) {
                    flush()
                    entries += ReplyEnd("reply-end-$id", reply.toList())
                }
                reply.clear()
                replyID = null
            }

            for ((index, item) in items.withIndex()) {
                when (item) {
                    is OpenCodeTranscriptItem.Tool if isPendingQuestion(item) -> {
                        // A pending question is the dock's, even before its input has streamed
                        // in; it joins the transcript answered.
                        if (isRunningOrStreaming(item.call)) continue
                        flush()
                        entries += Single(item)
                    }
                    is OpenCodeTranscriptItem.Tool, is OpenCodeTranscriptItem.Notice -> append(item, Run.Tools)
                    is OpenCodeTranscriptItem.UserText, is OpenCodeTranscriptItem.UserImages, is OpenCodeTranscriptItem.UserFiles -> {
                        endReply()
                        turnHasRunError = false
                        append(item, Run.User(promptID(item) ?: item.id))
                    }
                    is OpenCodeTranscriptItem.RunError -> {
                        if (item.error.beforeReply && turnHasRunError) continue
                        turnHasRunError = true
                        flush()
                        entries += Single(item)
                    }
                    is OpenCodeTranscriptItem.AssistantMarkdown -> {
                        flush()
                        entries += Single(item)
                        // Text that parses to none adds nothing.
                        if (item.text.isNotBlank()) reply += item.text
                        replyID = item.id
                    }
                    is OpenCodeTranscriptItem.Compaction, is OpenCodeTranscriptItem.Info, is OpenCodeTranscriptItem.Undone -> {
                        flush()
                        entries += Single(item)
                    }
                    is OpenCodeTranscriptItem.ModelSwitch -> {
                        if (item.id in startModels) continue
                        // Recorded just before the send that applied it, which ends the reply,
                        // unless the turn goes on.
                        if (switchEndsReply(index, items, isRunning)) endReply()
                        flush()
                        entries += Single(item.copy(name = models.model(item.model)?.name))
                    }
                    is OpenCodeTranscriptItem.SendFailure, is OpenCodeTranscriptItem.RunRetry -> continue
                }
            }
            flush()
            if (!isRunning) endReply()
            return entries
        }

        /**
         * Where a window of [items] starting at [index] may start without splitting the entry
         * [build] makes there: back to the first row of a tool group or of a send's rows.
         */
        fun entryStart(index: Int, items: List<OpenCodeTranscriptItem>): Int {
            fun kind(item: OpenCodeTranscriptItem): String = when {
                item is OpenCodeTranscriptItem.Tool && isPendingQuestion(item) ->
                    if (isRunningOrStreaming(item.call)) "passes" else "breaks"
                item is OpenCodeTranscriptItem.Tool || item is OpenCodeTranscriptItem.Notice -> "run:tools"
                item is OpenCodeTranscriptItem.UserText || item is OpenCodeTranscriptItem.UserImages ||
                    item is OpenCodeTranscriptItem.UserFiles -> "run:user:${promptID(item) ?: item.id}"
                item is OpenCodeTranscriptItem.SendFailure || item is OpenCodeTranscriptItem.RunRetry -> "passes"
                else -> "breaks"
            }
            if (index <= 0 || index >= items.size) return index
            var first = index
            while (first < items.size && kind(items[first]) == "passes") first++
            if (first >= items.size || !kind(items[first]).startsWith("run:")) return index
            val run = kind(items[first])
            var start = index
            var previous = index - 1
            while (previous >= 0) {
                when (kind(items[previous])) {
                    "passes" -> Unit
                    run -> start = previous
                    else -> return start
                }
                previous--
            }
            return start
        }

        private fun switchEndsReply(index: Int, items: List<OpenCodeTranscriptItem>, isRunning: Boolean): Boolean {
            for (item in items.subList(index + 1, items.size)) {
                when (item) {
                    is OpenCodeTranscriptItem.UserText, is OpenCodeTranscriptItem.UserImages, is OpenCodeTranscriptItem.UserFiles -> return true
                    is OpenCodeTranscriptItem.AssistantMarkdown -> return false
                    else -> continue
                }
            }
            return !isRunning
        }

        /**
         * The entries at the end of the transcript: the last one, and when that is a finished
         * reply's actions, the reply before it, whose height still moves the end.
         */
        fun tail(entries: List<TranscriptEntry>): List<TranscriptEntry> {
            val last = entries.lastOrNull() ?: return emptyList()
            if (last !is ReplyEnd) return listOf(last)
            val content = entries.lastOrNull { it !is ReplyEnd } ?: return listOf(last)
            return listOf(last, content)
        }

        /** Finds the list entry that contains a raw transcript item ID. */
        fun entryID(containing: String, entries: List<TranscriptEntry>): String? =
            entries.firstOrNull { containing in it.itemIDs }?.id
    }
}

/**
 * Assigns persistent row IDs while queued input is echoed by the server and then promoted into
 * transcript history. The list owns the resulting maps; this pure planner also preserves regrouped
 * member overlap.
 */
data class TranscriptRowIdentityPlan(
    val entryStableIDs: Map<String, String>,
    val queuedStableIDs: Map<String, String>,
    val promotedEntryIDs: Set<String>,
    val supersededQueuedIDs: Set<String>,
) {
    companion object {
        /** A group member that is the row's own work: notices move between groups as history settles. */
        private val OpenCodeTranscriptItem.isGroupedTool: Boolean get() = this !is OpenCodeTranscriptItem.Notice

        private fun sameMemberIDs(lhs: List<OpenCodeTranscriptItem>?, rhs: List<OpenCodeTranscriptItem>?): Boolean {
            if (lhs == null && rhs == null) return true
            if (lhs == null || rhs == null || lhs.size != rhs.size) return false
            for (i in lhs.indices) if (lhs[i].id != rhs[i].id) return false
            return true
        }

        private data class QueuedSlot(val input: OpenCodeQueuedInput, val stableID: String)

        private data class Candidate(val sharesTools: Boolean, val oldIndex: Int, val tools: Int, val newIndex: Int)

        fun make(
            previousEntries: List<TranscriptEntry>,
            previousQueued: List<OpenCodeQueuedInput>,
            previousEntryStableIDs: Map<String, String>,
            previousQueuedStableIDs: Map<String, String>,
            entries: List<TranscriptEntry>,
            queued: List<OpenCodeQueuedInput>,
        ): TranscriptRowIdentityPlan {
            val availableQueued = previousQueued.map { QueuedSlot(it, previousQueuedStableIDs[it.id] ?: it.id) }.toMutableList()
            val entryStableIDs = mutableMapOf<String, String>()
            val promotedEntryIDs = mutableSetOf<String>()
            // Two rows never share an ID: a taken one is never handed out again.
            val takenStableIDs = mutableSetOf<String>()
            fun take(stableID: String, reserved: Set<String> = emptySet()): String {
                var candidate = stableID
                var suffix = 2
                while (candidate in takenStableIDs || candidate in reserved) {
                    candidate = "$stableID#$suffix"
                    suffix++
                }
                takenStableIDs += candidate
                return candidate
            }

            // Fast path for streamed deltas: same entries, same members, no queue.
            if (entries.size == previousEntries.size && queued.isEmpty() && previousQueued.isEmpty() &&
                previousEntryStableIDs.size == entries.size
            ) {
                var identicalOrder = true
                for (index in entries.indices) {
                    val entry = entries[index]
                    val previous = previousEntries[index]
                    if (entry.id != previous.id || previousEntryStableIDs[entry.id] == null ||
                        !sameMemberIDs(previous.groupMembers, entry.groupMembers)
                    ) {
                        identicalOrder = false
                        break
                    }
                }
                if (identicalOrder) return TranscriptRowIdentityPlan(previousEntryStableIDs, emptyMap(), emptySet(), emptySet())
            }

            val previousGroups = mutableMapOf<String, Pair<Int, List<OpenCodeTranscriptItem>>>()
            for ((index, entry) in previousEntries.withIndex()) {
                entry.groupMembers?.let { previousGroups[entry.id] = index to it }
            }
            val unchanged = entries.all { entry ->
                if (previousEntryStableIDs[entry.id] == null) return@all false
                val members = entry.groupMembers ?: return@all true
                sameMemberIDs(previousGroups[entry.id]?.second, members)
            }
            if (unchanged) {
                for (entry in entries) entryStableIDs[entry.id] = take(previousEntryStableIDs.getValue(entry.id))
            } else {
                // Which old group each member was in.
                val previousOwner = mutableMapOf<String, Int>()
                for ((_, group) in previousGroups) for (member in group.second) previousOwner[member.id] = group.first
                val matchedPreviousIndexes = mutableSetOf<Int>()

                // Exact names first, while the group still holds that row's tools and none of
                // its tools came from an earlier row.
                for (entry in entries) {
                    val stableID = previousEntryStableIDs[entry.id] ?: continue
                    val members = entry.groupMembers
                    val exact = previousGroups[entry.id]
                    if (members != null && exact != null) {
                        val tools = members.filter { it.isGroupedTool }.map { it.id }
                        val exactTools = exact.second.filter { it.isGroupedTool }.map { it.id }.toSet()
                        val keepsItsTools = exactTools.isEmpty() || tools.any { it in exactTools }
                        val joinsEarlierRow = tools.any { (previousOwner[it] ?: Int.MAX_VALUE) < exact.first }
                        if (!keepsItsTools || joinsEarlierRow) continue
                        matchedPreviousIndexes += exact.first
                    }
                    entryStableIDs[entry.id] = take(stableID)
                }

                // Then shared members: the earliest old row sharing tools keeps its identity,
                // then rows sharing only notices; each old row once.
                val candidates = mutableListOf<Candidate>()
                for ((newIndex, entry) in entries.withIndex()) {
                    if (entryStableIDs[entry.id] != null) continue
                    val members = entry.groupMembers ?: continue
                    val shared = linkedMapOf<Int, Int>()
                    for (member in members) {
                        val oldIndex = previousOwner[member.id] ?: continue
                        if (oldIndex in matchedPreviousIndexes) continue
                        shared[oldIndex] = (shared[oldIndex] ?: 0) + if (member.isGroupedTool) 1 else 0
                    }
                    for ((oldIndex, tools) in shared) candidates += Candidate(tools > 0, oldIndex, tools, newIndex)
                }
                candidates.sortWith { lhs, rhs ->
                    when {
                        lhs.sharesTools != rhs.sharesTools -> if (lhs.sharesTools) -1 else 1
                        lhs.oldIndex != rhs.oldIndex -> lhs.oldIndex.compareTo(rhs.oldIndex)
                        lhs.tools != rhs.tools -> rhs.tools.compareTo(lhs.tools)
                        else -> lhs.newIndex.compareTo(rhs.newIndex)
                    }
                }
                for (candidate in candidates) {
                    val entry = entries[candidate.newIndex]
                    val old = previousEntries[candidate.oldIndex]
                    if (entryStableIDs[entry.id] != null || candidate.oldIndex in matchedPreviousIndexes) continue
                    val stableID = previousEntryStableIDs[old.id] ?: continue
                    if (stableID in takenStableIDs) continue
                    entryStableIDs[entry.id] = take(stableID)
                    matchedPreviousIndexes += candidate.oldIndex
                }

                val previousEntryIDs = previousEntries.map { it.id }.toSet()
                for (entry in entries) {
                    if (entryStableIDs[entry.id] != null) continue
                    val messageID = entry.userMessageID
                    val index = if (entry.id !in previousEntryIDs && messageID != null) {
                        availableQueued.indexOfFirst { !it.input.isCompaction && it.input.id == messageID }
                    } else {
                        -1
                    }
                    if (index >= 0) {
                        entryStableIDs[entry.id] = take(availableQueued.removeAt(index).stableID)
                        promotedEntryIDs += entry.id
                    } else {
                        // A pending input keeps its own identity for its row.
                        entryStableIDs[entry.id] = take(entry.id, availableQueued.map { it.stableID }.toSet())
                    }
                }
            }

            val queuedStableIDs = mutableMapOf<String, String>()
            val supersededQueuedIDs = mutableSetOf<String>()
            val previousQueuedStableIDSet = previousQueuedStableIDs.values.toSet()
            for (input in queued) {
                // A transcript row already shows this input: the pending row never shows as well.
                val echo = if (input.isCompaction) null else entries.firstOrNull { it.userMessageID == input.id }
                val echoStableID: String? = echo?.let { e ->
                    val entryStableID = entryStableIDs[e.id] ?: return@let null
                    if (e.id in promotedEntryIDs || entryStableID in previousQueuedStableIDSet) entryStableID else null
                }
                // A transcript row that left as this input arrived (a failed message retried
                // mid-run) hands it its identity, so the row reconfigures.
                val demotedStableID: String? = run {
                    if (echoStableID != null || input.isCompaction || previousQueuedStableIDs.containsKey(input.id)) return@run null
                    val previous = previousEntries.firstOrNull { it.userMessageID == input.id } ?: return@run null
                    if (entries.any { it.id == previous.id }) return@run null
                    val stableID = previousEntryStableIDs[previous.id] ?: return@run null
                    if (entryStableIDs.values.contains(stableID)) null else stableID
                }
                val index = availableQueued.indexOfFirst { it.input.id == input.id }
                val stableID = if (index >= 0) availableQueued.removeAt(index).stableID else echoStableID ?: demotedStableID ?: input.id
                if (echo != null) {
                    // Hidden behind its echo, which shows under the same identity.
                    queuedStableIDs[input.id] = stableID
                    supersededQueuedIDs += input.id
                } else {
                    queuedStableIDs[input.id] = take(stableID)
                }
            }

            return TranscriptRowIdentityPlan(entryStableIDs, queuedStableIDs, promotedEntryIDs, supersededQueuedIDs)
        }
    }
}

val OpenCodeQueuedInput.rowInsets: RowInsets
    get() = if (isCompaction) {
        RowInsets(TranscriptTunables.sectionHalf.dp, TranscriptTunables.sectionHalf.dp)
    } else {
        RowInsets(TranscriptTunables.turnHalf.dp, TranscriptTunables.turnHalf.dp)
    }
