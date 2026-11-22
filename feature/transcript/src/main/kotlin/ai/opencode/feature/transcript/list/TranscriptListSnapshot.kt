package ai.opencode.feature.transcript.list

import ai.opencode.feature.transcript.NewMessagesPlacement
import ai.opencode.feature.transcript.RowProps
import ai.opencode.feature.transcript.TranscriptConfiguration
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.TranscriptRowIdentityPlan
import ai.opencode.feature.transcript.user.TranscriptSendFailure
import ai.opencode.feature.transcript.user.UserMessageRun
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeQueuedInput
import ai.opencode.server.model.OpenCodeTranscriptItem
import androidx.compose.runtime.Immutable

/** One list row's identity (iOS `TranscriptListViewController.ItemID`). [key] is the LazyColumn key. */
@Immutable
sealed interface TranscriptItemID {
    val key: String

    data object History : TranscriptItemID { override val key = "history" }
    data object Empty : TranscriptItemID { override val key = "empty" }
    data class Message(val stableID: String) : TranscriptItemID { override val key = "m:$stableID" }
    data object Working : TranscriptItemID { override val key = "working" }
    data object NewDivider : TranscriptItemID { override val key = "new" }

    /** Separate content types per row family, so a message row never reuses a status row's slot. */
    val contentType: String
        get() = when (this) {
            History -> "history"
            Empty -> "empty"
            is Message -> "message"
            Working -> "working"
            NewDivider -> "new"
        }
}

/** A message row's value (iOS `MessageRow`): a user message carries its failure and delivery note. */
@Immutable
sealed interface MessageRow {
    data class Entry(val entry: TranscriptEntry, val failure: TranscriptSendFailure? = null, val note: String? = null) : MessageRow
    data class Queued(val input: OpenCodeQueuedInput, val note: String? = null) : MessageRow

    /** Same family of row: a pending row promotes into a delivered one in place. */
    companion object {
        fun sameKind(old: MessageRow?, new: MessageRow?): Boolean =
            (old is Entry && new is Entry) || (old is Queued && new is Queued)
    }
}

/** What one apply draws: ordered row IDs and each message row's value, props and run. */
@Immutable
data class TranscriptListSnapshot(
    val items: List<TranscriptItemID>,
    val queuedItems: List<TranscriptItemID>,
    val rows: Map<String, MessageRow>,
    val props: Map<String, RowProps>,
    val runs: Map<String, UserMessageRun>,
) {
    companion object {
        val empty = TranscriptListSnapshot(emptyList(), emptyList(), emptyMap(), emptyMap(), emptyMap())

        /**
         * iOS `buildSnapshotItems(from:supersededQueuedIDs:)`: entries win over queued rows and
         * the first occurrence wins, so a duplicate stable ID can never reach LazyColumn (which
         * throws on duplicate keys).
         */
        fun build(
            configuration: TranscriptConfiguration,
            entryStableIDs: Map<String, String>,
            queuedStableIDs: Map<String, String>,
            supersededQueuedIDs: Set<String>,
            sessionLinkIDsByStableID: Map<String, Set<String>>,
            drawsNewDivider: (NewMessagesPlacement) -> Boolean,
        ): TranscriptListSnapshot {
            val entryRows = configuration.entries.map { entry ->
                val messageID = entry.userMessageID
                (entryStableIDs[entry.id] ?: entry.id) to MessageRow.Entry(
                    entry,
                    failure = messageID?.let { configuration.failedSends[it] },
                    note = messageID?.let { configuration.deliveryNotes[it] },
                )
            }
            val queuedRows = configuration.queuedInputs
                .filter {
                    (it.isCompaction || it.text.isNotEmpty() || it.images.isNotEmpty() || it.files.isNotEmpty()) &&
                        it.id !in supersededQueuedIDs
                }
                .map { input -> (queuedStableIDs[input.id] ?: input.id) to MessageRow.Queued(input, configuration.deliveryNotes[input.id]) }
            val rows = LinkedHashMap<String, MessageRow>()
            val props = HashMap<String, RowProps>()
            val growingStableID: String? = if (configuration.isRunning) {
                entryRows.lastOrNull { (_, row) ->
                    (row.entry as? TranscriptEntry.Single)?.item is OpenCodeTranscriptItem.AssistantMarkdown
                }?.first
            } else {
                null
            }

            fun admit(list: List<Pair<String, MessageRow>>): List<TranscriptItemID> = list.mapNotNull { (stableID, row) ->
                if (rows.containsKey(stableID)) return@mapNotNull null
                rows[stableID] = row
                when (row) {
                    is MessageRow.Entry -> {
                        var p = RowProps(isGrowing = stableID == growingStableID)
                        val entry = row.entry
                        if (!configuration.isRunning && configuration.sessionID != null && entry is TranscriptEntry.UserGroup &&
                            row.failure == null && row.note == null &&
                            ai.opencode.feature.transcript.user.TranscriptUserMessage.from(entry)?.isFromAgent == false
                        ) {
                            p = p.copy(canEditMessage = true)
                        }
                        val item = (entry as? TranscriptEntry.Single)?.item
                        if (item is OpenCodeTranscriptItem.RunError && configuration.actionableRunErrorID == item.id) {
                            p = p.copy(runErrorActions = configuration.actionableRunErrorActions)
                        }
                        if (configuration.subagents.isNotEmpty()) {
                            val ids = entry.subagentSessionIDs
                            if (ids.isNotEmpty()) p = p.copy(subagents = ids.mapNotNull { id -> configuration.subagents[id]?.let { id to it } }.toMap())
                        }
                        if (configuration.sessionLinks.isNotEmpty()) {
                            // Rows that reported IDs get only theirs; a row that hasn't reported yet sees every
                            // answer, since its first draw reports through the session, not the list.
                            val ids = sessionLinkIDsByStableID[stableID]
                            p = p.copy(sessionLinks = if (ids != null) configuration.sessionLinks.filterKeys { it in ids } else configuration.sessionLinks)
                        }
                        if (p != RowProps()) props[stableID] = p
                    }
                    is MessageRow.Queued -> {
                        val input = row.input
                        if (!configuration.isRunning && input.delivery == OpenCodeDelivery.Steer && !input.isCompaction && input.notice == null) {
                            props[stableID] = RowProps(canSendNow = true)
                        }
                    }
                }
                TranscriptItemID.Message(stableID)
            }

            val entryItems = admit(entryRows)
            val queuedItems = admit(queuedRows)

            val items = mutableListOf<TranscriptItemID>()
            // A draft's setup sits above the composer instead.
            val showsEmptyRow = configuration.entries.isEmpty() && configuration.queuedInputs.isEmpty() &&
                !configuration.isRunning && configuration.earlierHistoryError == null && !configuration.isDraft &&
                (configuration.isLoading || configuration.showsEmptyState)
            if (!showsEmptyRow &&
                (configuration.hasEarlierItems || configuration.isLoadingEarlier || configuration.earlierHistoryError != null)
            ) {
                items += TranscriptItemID.History
            }
            if (showsEmptyRow) items += TranscriptItemID.Empty
            items += entryItems
            // Above the first entry the reader hadn't seen.
            val mark = configuration.newMessages
            if (mark != null && drawsNewDivider(mark)) {
                val index = items.indexOf(TranscriptItemID.Message(entryStableIDs[mark.entryID] ?: mark.entryID))
                if (index >= 0) items.add(index, TranscriptItemID.NewDivider)
            }
            // Working closes the transcript; steering messages wait below it.
            if (configuration.isRunning) items += TranscriptItemID.Working
            items += queuedItems
            return TranscriptListSnapshot(items, queuedItems, rows, props, userRuns(items, rows))
        }

        /** Runs of adjacent user messages (iOS `userRuns(in:rows:)`). */
        private fun userRuns(items: List<TranscriptItemID>, rows: Map<String, MessageRow>): Map<String, UserMessageRun> {
            val members = items.map { item ->
                val stableID = (item as? TranscriptItemID.Message)?.stableID ?: return@map null
                rows[stableID]?.let(::runMember)
            }
            val runs = HashMap<String, UserMessageRun>()
            for ((item, run) in items.zip(UserMessageRun.plan(members))) {
                val stableID = (item as? TranscriptItemID.Message)?.stableID ?: continue
                if (run != null) runs[stableID] = run
            }
            return runs
        }

        /**
         * A plain user message, delivered or steering; a line under it (a failed send, a delivery
         * note, a call log caption) ends its run. Read off the row's parts, never by parsing text.
         */
        private fun runMember(row: MessageRow): UserMessageRun.Member? {
            fun carriesCallLog(names: List<String>) = names.any { it.startsWith("voice-call-") }
            fun shape(member: UserMessageRun.Member, text: String, hasAttachments: Boolean): UserMessageRun.Member {
                val location = ai.opencode.feature.transcript.user.SharedLocation.parse(text)
                val hasText = (location?.second ?: text).isNotEmpty()
                return member.copy(endsWithBubble = hasText, startsWithBubble = hasText && !hasAttachments && location == null)
            }
            return when (row) {
                is MessageRow.Entry -> {
                    val entry = row.entry as? TranscriptEntry.UserGroup ?: return null
                    val id = entry.userMessageID ?: return null
                    if (!entry.items.all { TranscriptEntry.promptID(it) == id }) return null
                    val files = entry.items.flatMap { (it as? OpenCodeTranscriptItem.UserFiles)?.files?.map { f -> f.name } ?: emptyList() }
                    val member = UserMessageRun.Member(pending = false, endsRun = row.failure != null || row.note != null || carriesCallLog(files))
                    val text = entry.items.firstNotNullOfOrNull { (it as? OpenCodeTranscriptItem.UserText)?.text } ?: ""
                    val hasAttachments = entry.items.any {
                        (it is OpenCodeTranscriptItem.UserImages && it.images.isNotEmpty()) ||
                            (it is OpenCodeTranscriptItem.UserFiles && it.files.isNotEmpty())
                    }
                    shape(member, text, hasAttachments)
                }
                is MessageRow.Queued -> {
                    val input = row.input
                    if (input.delivery != OpenCodeDelivery.Steer || input.isCompaction || input.notice != null) return null
                    val member = UserMessageRun.Member(pending = true, endsRun = row.note != null || carriesCallLog(input.files.map { it.name }))
                    shape(member, input.text, input.images.isNotEmpty() || input.files.isNotEmpty() || input.input.inlineFiles.isNotEmpty())
                }
            }
        }

        /**
         * Whether the list holds a row for the mark's divider (iOS `drawsNewMessagesDivider`):
         * unless it fit, or the reader arrived on it without one.
         */
        fun drawsNewMessagesDivider(mark: NewMessagesPlacement, fitting: Int?, decision: Pair<String, Boolean>?): Boolean {
            if (mark.generation == fitting) return false
            if (decision != null && decision.first == mark.entryID) return decision.second
            return true
        }
    }
}

/** Builds each [TranscriptListSnapshot], keeping row keys stable across queued → sent and server re-keys. */
internal class TranscriptSnapshotBuilder {
    private var entryStableIDs: Map<String, String> = emptyMap()
    private var queuedStableIDs: Map<String, String> = emptyMap()
    private var previousEntries: List<TranscriptEntry> = emptyList()
    private var previousQueued: List<OpenCodeQueuedInput> = emptyList()
    private var lastConfiguration: TranscriptConfiguration? = null
    private var lastFitGeneration: Int? = null
    private var lastDividerDecision: Pair<String, Boolean>? = null

    var snapshot: TranscriptListSnapshot = TranscriptListSnapshot.empty
        private set

    /** Null for a cached transcript, whose refresh never shows the loading mark. */
    var emptyLoadingSince: Double? = null
        private set

    /** [dividerDecision] is the New mark's entry and whether its divider shows. */
    fun build(
        configuration: TranscriptConfiguration,
        fitGeneration: Int?,
        dividerDecision: Pair<String, Boolean>?,
    ): TranscriptListSnapshot {
        if (configuration == lastConfiguration && fitGeneration == lastFitGeneration && dividerDecision == lastDividerDecision) {
            return snapshot
        }
        if (configuration.isLoading && emptyLoadingSince == null && configuration.entries.isEmpty()) {
            emptyLoadingSince = transcriptNow()
        }
        if (!configuration.isLoading && configuration.entries.isNotEmpty()) emptyLoadingSince = null
        if (configuration != lastConfiguration) {
            val plan = TranscriptRowIdentityPlan.make(
                previousEntries = previousEntries,
                previousQueued = previousQueued,
                previousEntryStableIDs = entryStableIDs,
                previousQueuedStableIDs = queuedStableIDs,
                entries = configuration.entries,
                queued = configuration.queuedInputs,
            )
            entryStableIDs = plan.entryStableIDs
            queuedStableIDs = plan.queuedStableIDs
            supersededQueuedIDs = plan.supersededQueuedIDs
            previousEntries = configuration.entries
            previousQueued = configuration.queuedInputs
        }
        lastConfiguration = configuration
        lastFitGeneration = fitGeneration
        lastDividerDecision = dividerDecision
        snapshot = TranscriptListSnapshot.build(
            configuration = configuration,
            entryStableIDs = entryStableIDs,
            queuedStableIDs = queuedStableIDs,
            supersededQueuedIDs = supersededQueuedIDs,
            sessionLinkIDsByStableID = emptyMap(),
            drawsNewDivider = { mark -> TranscriptListSnapshot.drawsNewMessagesDivider(mark, fitGeneration, dividerDecision) },
        )
        return snapshot
    }

    private var supersededQueuedIDs: Set<String> = emptySet()

    fun messageItemKey(entryID: String): String = TranscriptItemID.Message(entryStableIDs[entryID] ?: entryID).key

    fun isGrowingEntry(entryID: String): Boolean = snapshot.props[entryStableIDs[entryID] ?: entryID]?.isGrowing == true
}
