package ai.opencode.feature.composer

import ai.opencode.core.sharing.InstantSerializer
import ai.opencode.core.strings.localized
import ai.opencode.server.model.OpenCodeAgentMention
import ai.opencode.server.model.OpenCodeAutocompleteAgent
import ai.opencode.server.model.OpenCodeAutocompleteCommand
import ai.opencode.server.model.OpenCodeAutocompleteFile
import ai.opencode.server.model.OpenCodeAutocompleteMentions
import ai.opencode.server.model.OpenCodeAutocompleteSkill
import ai.opencode.server.model.OpenCodeFileMention
import ai.opencode.server.model.OpenCodeMention
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSkillMention
import ai.opencode.server.model.ProjectMark
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.io.File
import java.time.Instant
import java.util.UUID

// The wire types (skill, agent, command, file, mentions, `PromptAutocompleteMentions` →
// `PromptContext.mentions`) live in `:server` (`model/Composer.kt`). Kotlin `String` indices are
// UTF-16 code units.

/** `UUID` as its string. */
object UUIDSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

internal fun String.trimmedWhitespaceAndNewlines(): String = trim { it.isWhitespace() || Character.isSpaceChar(it) }

private fun isWhitespaceUTF16(unit: Char): Boolean = unit.isWhitespace() || Character.isSpaceChar(unit)

/**
 * A root session offered as an `@` mention (`GET /api/session?parentID=null&archived=false`).
 * Like desktop (`composer/model.ts`), the chip shows `@<title>` and the sent text carries
 * `@session:<id>` (`@opencode/util/session-mention`).
 */
@Serializable
data class OpenCodeAutocompleteSession(
    val id: String,
    val title: String,
    /** The project's name, or the session's folder name. */
    val project: String,
    @Serializable(with = InstantSerializer::class) val updatedAt: Instant,
) {
    /** The token the prompt's text carries in place of the chip. */
    val sentToken: String get() = "@session:$id"

    companion object {
        /** An empty title reads “New Session”. */
        fun of(id: String, title: String, project: String, updatedAt: Instant): OpenCodeAutocompleteSession {
            val trimmed = title.trimmedWhitespaceAndNewlines()
            return OpenCodeAutocompleteSession(id, trimmed.ifEmpty { localized(R.string.composer_autocomplete_new_session) }, project, updatedAt)
        }

        fun of(session: OpenCodeSession, projects: List<OpenCodeProject>): OpenCodeAutocompleteSession {
            val project = projects.firstOrNull { it.id == session.projectID }?.name
                ?: ProjectMark.folderName(session.directory)
            return of(session.id, session.title, project, session.updatedAt)
        }

        /** A complete generated session ID (`SessionLink.exactID`): `ses_` and 26 letters or digits. */
        private val sessionMention = Regex("""(?<![\w/\\:@-])(?:@session:)?(ses_[A-Za-z0-9]{26})(?![\w/\\-])""")

        /** Whether `id` is a complete generated session ID, the only kind desktop offers. */
        fun isMentionable(id: String): Boolean {
            if (!id.contains("ses_")) return false
            val match = sessionMention.find(id) ?: return false
            return match.range.first == 0 && match.range.last == id.length - 1 && match.groupValues[1] == id
        }

        /**
         * Desktop's `matchesSessionTitle` (`composer/session-match.ts`): each query word starts
         * consecutive title words, case-insensitively. A trailing space asks for one more title word.
         */
        fun matches(title: String, query: String): Boolean {
            val words = query.lowercase().split(" ")
            val titleWords = title.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return titleWords.indices.any { start ->
                words.withIndex().all { (index, word) ->
                    val position = start + index
                    position < titleWords.size && titleWords[position].startsWith(word)
                }
            }
        }

        /** The word the server narrows its search by (desktop's `firstWord`). */
        fun serverSearch(query: String): String = query.lowercase().split(" ").firstOrNull() ?: ""
    }
}

/** One row or chip item in Composer Autocomplete. */
@Serializable
data class AutocompleteItem(
    val id: String,
    val kind: Kind,
    val title: String,
    val label: String,
    val searchText: String,
    val payload: Payload,
) {
    @Serializable
    enum class Kind {
        @SerialName("skill") Skill,
        @SerialName("agent") Agent,
        @SerialName("file") File,
        @SerialName("session") Session,
        @SerialName("command") Command,
        @SerialName("empty") Empty;

        val priority: Double
            get() = when (this) {
                Skill -> 2.0
                Agent -> 1.0
                Session -> 0.0
                File -> 0.0
                Command -> 2.0
                Empty -> -1.0
            }
    }

    @Serializable
    sealed interface Payload {
        @Serializable @SerialName("skill") data class Skill(val skill: OpenCodeAutocompleteSkill) : Payload
        @Serializable @SerialName("agent") data class Agent(val agent: OpenCodeAutocompleteAgent) : Payload
        @Serializable @SerialName("file") data class File(val file: OpenCodeAutocompleteFile) : Payload
        @Serializable @SerialName("session") data class Session(val session: OpenCodeAutocompleteSession) : Payload
        @Serializable @SerialName("command") data class Command(val command: OpenCodeAutocompleteCommand) : Payload
        @Serializable @SerialName("empty") data class Empty(val query: String) : Payload
    }

    val isSelectable: Boolean get() = kind != Kind.Empty

    /** The text token placed in `draft` when this item is chosen as an inline chip. */
    val draftToken: String
        get() = when (kind) {
            Kind.Command -> if (title.startsWith("/")) title else "/$title"
            Kind.Skill, Kind.Agent, Kind.File -> if (title.startsWith("@")) title else "@$title"
            Kind.Session -> "@$title"
            Kind.Empty -> ""
        }

    val accessibilityLabel: String
        get() = when (kind) {
            Kind.Skill -> localized(R.string.composer_autocomplete_skill_description, title)
            Kind.Agent -> localized(R.string.composer_autocomplete_subagent_description, title)
            Kind.File -> localized(R.string.composer_autocomplete_file_description, title, label)
            Kind.Session -> localized(R.string.composer_autocomplete_session_description, title, label)
            Kind.Command -> {
                // A command without a description shows the word “Command” as its label; it isn't read twice.
                val description = (payload as? Payload.Command)?.command?.description?.trimmedWhitespaceAndNewlines()
                if (label.isEmpty() || description.isNullOrEmpty()) {
                    localized(R.string.composer_autocomplete_command_description, title)
                } else {
                    localized(R.string.composer_autocomplete_command_with_label_description, title, label)
                }
            }
            Kind.Empty -> title
        }

    companion object {
        fun skill(skill: OpenCodeAutocompleteSkill) = AutocompleteItem(
            id = "skill:${skill.id}", kind = Kind.Skill, title = skill.name, label = localized(R.string.composer_autocomplete_skill),
            searchText = "", payload = Payload.Skill(skill),
        )

        fun agent(agent: OpenCodeAutocompleteAgent): AutocompleteItem {
            val display = agent.id.ifEmpty { agent.name }
            return AutocompleteItem(
                id = "agent:$display", kind = Kind.Agent, title = display, label = localized(R.string.composer_autocomplete_subagent),
                searchText = agent.name, payload = Payload.Agent(agent),
            )
        }

        fun file(file: OpenCodeAutocompleteFile) = AutocompleteItem(
            id = "file:${file.relativePath}", kind = Kind.File, title = file.name, label = file.folder,
            searchText = file.relativePath, payload = Payload.File(file),
        )

        fun session(session: OpenCodeAutocompleteSession) = AutocompleteItem(
            id = "session:${session.id}", kind = Kind.Session, title = session.title, label = session.project,
            searchText = "", payload = Payload.Session(session),
        )

        fun command(command: OpenCodeAutocompleteCommand): AutocompleteItem {
            val title = if (command.name.startsWith("/")) command.name else "/${command.name}"
            return AutocompleteItem(
                id = "command:${command.name}", kind = Kind.Command, title = title,
                label = command.description?.trimmedWhitespaceAndNewlines()?.ifEmpty { null } ?: localized(R.string.composer_autocomplete_command),
                searchText = "", payload = Payload.Command(command),
            )
        }

        fun empty(query: String) = AutocompleteItem(
            id = "empty", kind = Kind.Empty, title = localized(R.string.composer_autocomplete_no_matches, query), label = "",
            searchText = "", payload = Payload.Empty(query),
        )
    }
}

/** An inline chip chosen from Composer Autocomplete. */
@Serializable
data class ComposerAutocompleteChip(
    @Serializable(with = UUIDSerializer::class) val id: UUID,
    val item: AutocompleteItem,
    val token: String,
) {
    constructor(item: AutocompleteItem, id: UUID = UUID.randomUUID()) : this(id, item, item.draftToken)

    /**
     * What the prompt's text carries in place of [token]: a session's stable
     * `@session:<id>`, as desktop's `serializePrompt` writes it; else the token.
     */
    val sentToken: String
        get() = (item.payload as? AutocompleteItem.Payload.Session)?.session?.sentToken ?: token
}

/** An active `@` or `/` trigger in the composer draft. */
data class AutocompleteTrigger(
    val mode: Mode,
    val query: String,
    /**
     * Stable identity for this trigger position so Escape / outside tap dismisses only this
     * trigger instance until the user backspaces past it or starts another.
     */
    val key: String,
    /** UTF-16 offset of the `@` or `/` character in `draft`. */
    val utf16Start: Int,
    /** UTF-16 end offset of the trigger + query in `draft`. */
    val utf16End: Int,
) {
    enum class Mode(val rawValue: String) { Mention("@"), Command("/") }

    /** `NSRange` equivalent: start until end. */
    val range: IntRange get() = utf16Start until maxOf(utf16Start, utf16End)

    /** A chip and the UTF-16 range its token occupies in the draft (`start until end`). */
    data class ChipRange(val chip: ComposerAutocompleteChip, val start: Int, val end: Int) {
        val length: Int get() = end - start
    }

    companion object {
        internal fun isWhitespace(unit: Char): Boolean = isWhitespaceUTF16(unit)

        private val openMentionPattern = Regex("""(?:^|\s)@((?:[^\s@]+(?: [^\s@]+)* ?)?)\z""")

        /**
         * Detects an active `@` (at word start) or `/` (at message start) trigger at the end of
         * `draft` (or at `caretUTF16Offset` when provided), ignoring tokens that already belong
         * to chosen `chips`. The mention open at `openMentionKey` keeps single spaces in its query.
         */
        fun detect(
            draft: String,
            chips: List<ComposerAutocompleteChip> = emptyList(),
            caretUTF16Offset: Int? = null,
            openMentionKey: String? = null,
        ): AutocompleteTrigger? {
            val totalLength = draft.length
            if (totalLength <= 0) return null
            val limit = minOf(maxOf(0, caretUTF16Offset ?: totalLength), totalLength)
            if (limit <= 0) return null

            val chipRanges = resolvedChipRanges(draft, chips)
            // If the caret is inside or at the end of an already-committed chip token,
            // it is a chip, not an unchosen trigger.
            if (chipRanges.any { it.end == limit || (it.start < limit && limit <= it.end) }) return null

            val prefix = draft.substring(0, limit)

            // 1. `/` at the very start of the message (no chips before it, no whitespace, no second `/`).
            if (chipRanges.isEmpty() && prefix.startsWith("/")) {
                val body = prefix.substring(1)
                if (body.none { it.isWhitespace() || it == '/' }) {
                    return AutocompleteTrigger(Mode.Command, body, "slash:0", 0, limit)
                }
                return null
            }

            // 2. `@` at a word start with no whitespace or second `@` after it. An open mention
            // keeps single spaces so multi-word session titles stay searchable (desktop's `inputChanged`).
            if (openMentionKey != null) {
                val match = openMentionPattern.find(prefix)
                if (match != null) {
                    val queryGroup = match.groups[1]
                    val queryStart = queryGroup?.range?.first ?: (match.range.last + 1)
                    val atIndex = queryStart - 1
                    val candidateStart = atIndex
                    val candidateEnd = atIndex + maxOf(0, limit - atIndex)
                    if ("at:$atIndex" == openMentionKey &&
                        chipRanges.none { intersects(candidateStart, candidateEnd, it.start, it.end) }
                    ) {
                        return AutocompleteTrigger(Mode.Mention, queryGroup?.value ?: "", "at:$atIndex", atIndex, limit)
                    }
                }
            }

            var scan = prefix.length
            while (scan > 0) {
                val ch = prefix[scan - 1]
                if (isWhitespaceUTF16(ch)) return null
                if (ch == '@') {
                    val atIndex = scan - 1
                    val candidateEnd = atIndex + maxOf(0, limit - atIndex)
                    if (chipRanges.any { intersects(atIndex, candidateEnd, it.start, it.end) }) return null
                    val isWordStart = atIndex == 0 || isWhitespaceUTF16(prefix[atIndex - 1])
                    if (!isWordStart) return null
                    val query = prefix.substring(scan)
                    if (query.contains("@")) return null
                    return AutocompleteTrigger(Mode.Mention, query, "at:$atIndex", atIndex, limit)
                }
                scan -= 1
            }
            return null
        }

        /** `NSIntersectionRange(a, b).length > 0`. */
        private fun intersects(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Boolean =
            minOf(aEnd, bEnd) - maxOf(aStart, bStart) > 0

        private const val CLOSING_PUNCTUATION = ".,;:!?)]}"

        /** Locates each chip's token in `draft` in order, skipping overlapping matches. */
        fun resolvedChipRanges(draft: String, chips: List<ComposerAutocompleteChip>): List<ChipRange> {
            if (chips.isEmpty() || draft.isEmpty()) return emptyList()
            val result = mutableListOf<ChipRange>()
            var searchStart = 0
            for (chip in chips) {
                val token = chip.token
                if (token.isEmpty() || searchStart >= draft.length) continue
                var scan = searchStart
                while (scan <= draft.length - token.length) {
                    val found = draft.indexOf(token, scan)
                    if (found < 0) break
                    val validStart = found == 0 || isWhitespaceUTF16(draft[found - 1])
                    val end = found + token.length
                    val validEnd = end >= draft.length || isWhitespaceUTF16(draft[end]) || draft[end] in CLOSING_PUNCTUATION
                    if (validStart && validEnd) {
                        result += ChipRange(chip, found, end)
                        searchStart = end
                        break
                    }
                    scan = found + 1
                }
            }
            return result
        }
    }
}

/** Fuzzy matcher ported directly from the Round 2 web study (`model.js`). */
object AutocompleteFuzzy {
    private const val BOUNDARIES = "/.-_ "

    /**
     * Fuzzy score: prefix, word-boundary, camelCase, and consecutive-character bonuses.
     * Returns null when `query` is not a subsequence of `text`.
     */
    fun score(text: String, query: String): Double? {
        if (query.isEmpty()) return 1.0
        val tChars = text
        val tlChars = text.lowercase()
        val qlChars = query.lowercase()
        if (qlChars.isEmpty() || tlChars.isEmpty()) return null

        var qi = 0
        var run = 0
        var total = 0.0
        var last = -1
        var start = -1

        for (i in tlChars.indices) {
            if (qi >= qlChars.length) break
            if (tlChars[i] != qlChars[qi]) {
                run = 0
                continue
            }
            if (start < 0) start = i
            val prevChar: Char? = if (i > 0 && i - 1 < tChars.length) tChars[i - 1] else null
            val currChar = if (i < tChars.length) tChars[i] else tlChars[i]
            val boundary = i == 0 || (prevChar?.let { it in BOUNDARIES } ?: false)
            val camel = i > 0 && (prevChar?.isLowerCase() ?: false) && currChar.isUpperCase()
            val consecutive = last == i - 1
            run = if (consecutive) run + 1 else 0
            val gap = if (last >= 0) i - last - 1 else i
            total += 10 +
                (if (consecutive) (14 + run * 6).toDouble() else 0.0) +
                (if (boundary) 18.0 else if (camel) 14.0 else 0.0) -
                gap.toDouble() * 1.5
            last = i
            qi += 1
        }

        if (qi != qlChars.length) return null
        val prefixBonus = if (tlChars.startsWith(qlChars)) 24.0 else 0.0
        val startBonus = maxOf(0, 12 - maxOf(0, start)).toDouble()
        return total + prefixBonus + startBonus - tChars.length.toDouble() * 0.15
    }

    fun results(items: List<AutocompleteItem>, query: String): List<AutocompleteItem> {
        val trimmed = query.trimmedWhitespaceAndNewlines()
        if (trimmed.isEmpty()) return items
        data class Scored(val item: AutocompleteItem, val score: Double, val index: Int)
        val scored = mutableListOf<Scored>()
        for ((index, item) in items.withIndex()) {
            val bareTitle = if (item.title.startsWith("/") || item.title.startsWith("@")) item.title.drop(1) else item.title
            val s1 = score(bareTitle, trimmed) ?: Double.NEGATIVE_INFINITY
            val s2 = if (item.searchText.isEmpty()) Double.NEGATIVE_INFINITY
            else (score(item.searchText, trimmed) ?: Double.NEGATIVE_INFINITY) * 0.85
            val best = maxOf(s1, s2)
            if (!best.isFinite()) continue
            scored += Scored(item, best + item.kind.priority, index)
        }
        scored.sortWith { a, b ->
            if (kotlin.math.abs(a.score - b.score) > 1e-6) b.score.compareTo(a.score) else a.index.compareTo(b.index)
        }
        return scored.map { it.item }
    }

    /** Builds the ordered row list for `mode` and `query` matching `model.js` `rowsFor`/`displayRows`. */
    fun displayRows(
        mode: AutocompleteTrigger.Mode,
        query: String,
        skills: List<OpenCodeAutocompleteSkill>,
        agents: List<OpenCodeAutocompleteAgent>,
        files: List<OpenCodeAutocompleteFile>,
        commands: List<OpenCodeAutocompleteCommand>,
        sessions: List<OpenCodeAutocompleteSession> = emptyList(),
        limit: Int = 8,
    ): List<AutocompleteItem> {
        val rows = fixedRows(mode, query, skills, agents, files, commands, limit)
        if (mode != AutocompleteTrigger.Mode.Mention) return rows
        // Sessions come already title-matched (`OpenCodeAutocompleteSession.matches`).
        // Like desktop (`editor/interaction.ts`), only session titles hold spaces,
        // and sessions sit after skills and subagents, before files.
        val sessionRows = sessions.map(AutocompleteItem::session)
        if (query.any { it.isWhitespace() }) return sessionRows
        if (sessionRows.isEmpty()) return rows
        if (rows.firstOrNull()?.kind == AutocompleteItem.Kind.Empty) return sessionRows
        val insertion = rows.indexOfFirst { it.kind == AutocompleteItem.Kind.File }.let { if (it < 0) rows.size else it }
        return rows.toMutableList().apply { addAll(insertion, sessionRows) }
    }

    private fun fixedRows(
        mode: AutocompleteTrigger.Mode,
        query: String,
        skills: List<OpenCodeAutocompleteSkill>,
        agents: List<OpenCodeAutocompleteAgent>,
        files: List<OpenCodeAutocompleteFile>,
        commands: List<OpenCodeAutocompleteCommand>,
        limit: Int,
    ): List<AutocompleteItem> {
        val pool: List<AutocompleteItem> = when (mode) {
            AutocompleteTrigger.Mode.Command -> commands.map(AutocompleteItem::command)
            AutocompleteTrigger.Mode.Mention ->
                skills.map(AutocompleteItem::skill) +
                    agents.filter { !it.hidden && it.mode != "primary" }.map(AutocompleteItem::agent) +
                    files.map(AutocompleteItem::file)
        }

        val matched: List<AutocompleteItem> = if (query.isEmpty()) {
            pool.take(limit)
        } else {
            val filtered = results(pool, query)
            if (filtered.isEmpty()) {
                listOf(AutocompleteItem.empty(query))
            } else {
                val fileMatches = if (mode == AutocompleteTrigger.Mode.Mention) files.map(AutocompleteItem::file) else emptyList()
                val combined = filtered.take(limit).toMutableList()
                // A file `/api/fs/find` matched on the server stays even if its fuzzy score is low.
                if (mode == AutocompleteTrigger.Mode.Mention && fileMatches.isNotEmpty()) {
                    for (fileItem in fileMatches) {
                        if (combined.none { it.id == fileItem.id } && combined.size < limit) combined += fileItem
                    }
                }
                combined
            }
        }
        // In `bottom` order, index 0 is the best match and sits nearest the composer.
        return matched
    }
}

/** How chosen chips in `draft` map onto server requests at send time. */
sealed interface ComposerAutocompleteDraftResolution {
    data class CommandExecution(val name: String, val argumentsText: String, val mentions: OpenCodeAutocompleteMentions)

    data class PromptAugmentation(
        val mentions: OpenCodeAutocompleteMentions,
        /** The sent text with each session chip as `@session:<id>`; null when no session chip changed it. */
        val text: String? = null,
    )

    data class Command(val execution: CommandExecution) : ComposerAutocompleteDraftResolution
    data class Prompt(val augmentation: PromptAugmentation) : ComposerAutocompleteDraftResolution

    companion object {
        fun resolve(
            draft: String,
            sentPromptText: String? = null,
            chips: List<ComposerAutocompleteChip>,
        ): ComposerAutocompleteDraftResolution {
            val resolved = AutocompleteTrigger.resolvedChipRanges(draft, chips)
            if (resolved.isEmpty()) return Prompt(PromptAugmentation(OpenCodeAutocompleteMentions()))

            var leadingTrim = 0
            while (leadingTrim < draft.length && AutocompleteTrigger.isWhitespace(draft[leadingTrim])) leadingTrim += 1

            val first = resolved.first()
            val firstPayload = first.chip.item.payload
            if (first.start == leadingTrim && firstPayload is AutocompleteItem.Payload.Command) {
                val command = firstPayload.command
                val name = if (command.name.startsWith("/")) command.name.drop(1) else command.name
                var argStart = first.end
                while (argStart < draft.length && AutocompleteTrigger.isWhitespace(draft[argStart])) argStart += 1
                val argumentsText = draft.substring(argStart).trimmedWhitespaceAndNewlines()
                val built = buildMentions(resolved.drop(1), argumentsText, prefixDelta = -argStart)
                return Command(CommandExecution(name, built.second, built.first))
            }

            val trimmedDraft = draft.trimmedWhitespaceAndNewlines()
            val targetText = sentPromptText ?: trimmedDraft
            val locationPrefix = if (trimmedDraft.isNotEmpty() && targetText.endsWith(trimmedDraft)) {
                targetText.length - trimmedDraft.length
            } else {
                0
            }
            val prefixDelta = locationPrefix - leadingTrim
            val built = buildMentions(resolved, targetText, prefixDelta)
            return Prompt(PromptAugmentation(built.first, if (built.second == targetText) null else built.second))
        }

        /**
         * The draft text for a sent `text`: each session chip's `@session:<id>` back to its
         * `@<title>` token, so the chips find their places again.
         */
        fun draftText(sent: String, chips: List<ComposerAutocompleteChip>): String {
            var result = sent
            var searchStart = 0
            for (chip in chips) {
                if (chip.sentToken == chip.token) continue
                val found = result.indexOf(chip.sentToken, minOf(searchStart, result.length))
                if (found < 0) continue
                result = result.substring(0, found) + chip.token + result.substring(found + chip.sentToken.length)
                searchStart = found + chip.token.length
            }
            return result
        }

        /**
         * Mention spans in the sent text. Session chips become `@session:<id>` there, and the
         * spans after them shift by the length difference, as desktop's `serializePrompt` does.
         */
        private fun buildMentions(
            entries: List<AutocompleteTrigger.ChipRange>,
            targetText: String,
            prefixDelta: Int,
        ): Pair<OpenCodeAutocompleteMentions, String> {
            val sent = StringBuilder(targetText)
            var shift = 0
            val fileMentions = mutableListOf<OpenCodeFileMention>()
            val agentMentions = mutableListOf<OpenCodeAgentMention>()
            val skillMentions = mutableListOf<OpenCodeSkillMention>()

            for (entry in entries) {
                val start = entry.start + prefixDelta
                val end = entry.end + prefixDelta
                if (start < 0 || end <= start || end > targetText.length) continue
                val value = targetText.substring(start, end)
                if (value != entry.chip.token) continue
                val sentToken = entry.chip.sentToken
                if (sentToken != value) {
                    sent.replace(start + shift, end + shift, sentToken)
                    shift += sentToken.length - (end - start)
                    continue
                }
                val mention = OpenCodeMention(text = value, start = start + shift, end = end + shift)
                when (val payload = entry.chip.item.payload) {
                    is AutocompleteItem.Payload.Skill -> skillMentions += OpenCodeSkillMention(payload.skill.id, mention)
                    is AutocompleteItem.Payload.Agent ->
                        agentMentions += OpenCodeAgentMention(payload.agent.id.ifEmpty { payload.agent.name }, mention)
                    is AutocompleteItem.Payload.File -> {
                        val file = payload.file
                        fileMentions += OpenCodeFileMention(
                            uri = fileURI(file.absolutePath),
                            name = file.name,
                            description = file.relativePath,
                            mention = mention,
                        )
                    }
                    is AutocompleteItem.Payload.Session, is AutocompleteItem.Payload.Command, is AutocompleteItem.Payload.Empty -> Unit
                }
            }
            return OpenCodeAutocompleteMentions(fileMentions, agentMentions, skillMentions) to sent.toString()
        }

        private val windowsDrivePath = Regex("^[A-Za-z]:[\\\\/]")

        /** `file://` and the percent-encoded absolute path; a Windows drive path becomes `file:///C:/…`. */
        internal fun fileURI(path: String): String {
            val absolute = when {
                windowsDrivePath.containsMatchIn(path) -> "/" + path.replace('\\', '/')
                path.startsWith("/") -> path
                else -> File(path).absolutePath
            }
            val out = StringBuilder("file://")
            val allowed = "-._~!$&'()*+,;=:@/"
            for (byte in absolute.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xFF
                val ch = c.toChar()
                if (c < 0x80 && (ch.isLetterOrDigit() || ch in allowed)) out.append(ch)
                else out.append('%').append(String.format(java.util.Locale.US, "%02X", c))
            }
            return out.toString()
        }
    }
}
