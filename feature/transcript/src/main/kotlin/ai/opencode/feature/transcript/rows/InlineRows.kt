package ai.opencode.feature.transcript.rows

import ai.opencode.core.strings.localized
import ai.opencode.core.strings.localizedPlural
import ai.opencode.feature.transcript.R
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeQuestion
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.TranscriptNoticeKind
import ai.opencode.server.model.nonEmpty
import androidx.compose.runtime.Immutable

/**
 * What a Used N group shows: one row per tool in a band, with consecutive reads merged into one row
 * and notices kept where they arrived. Pure, so the merging and labels stay apart from the views.
 */
@Immutable
data class InlineToolRow(
    /** The first member's transcript item ID: it keys the row's disclosure. */
    val id: String,
    val memberIDs: List<String>,
    val kind: Kind,
) {
    sealed interface Kind {
        data class Tool(val call: OpenCodeToolCall) : Kind
        /** Consecutive settled reads: “Read .config, README.md, …”. */
        data class Reads(val calls: List<OpenCodeToolCall>) : Kind
        data class Notice(val text: String, val isError: Boolean) : Kind
    }

    val name: String
        get() = when (kind) {
            is Kind.Tool -> kind.call.displayName
            is Kind.Reads -> localized(R.string.transcript_tool_read)
            is Kind.Notice -> TranscriptNoticeKind.headingOfNotice(kind.text)
        }

    val detail: String?
        get() = when (kind) {
            is Kind.Tool -> detail(kind.call)
            is Kind.Reads -> kind.calls.mapNotNull(::detail).joinToString(", ").nonEmpty
            is Kind.Notice -> kind.text.split(" · ").drop(1).joinToString(" · ").nonEmpty
        }

    /** Only commands are mono. */
    val detailIsCommand: Boolean get() = (kind as? Kind.Tool)?.call?.isShell == true

    val isFailed: Boolean
        get() = when (kind) {
            is Kind.Tool -> kind.call.status == OpenCodeToolCall.Status.Error && kind.call.decline == null
            is Kind.Reads -> false
            is Kind.Notice -> kind.isError
        }

    val isRunning: Boolean
        get() = (kind as? Kind.Tool)?.call?.status.let { it == OpenCodeToolCall.Status.Running || it == OpenCodeToolCall.Status.Streaming }

    val isDenied: Boolean get() = (kind as? Kind.Tool)?.call?.decline != null

    companion object {
        /**
         * The group's rows in order. Reads merge only while they run back to back and settled
         * cleanly, so a failed or running read keeps its own row.
         */
        fun rows(items: List<OpenCodeTranscriptItem>): List<InlineToolRow> {
            val rows = mutableListOf<InlineToolRow>()
            for (item in items) {
                when (item) {
                    is OpenCodeTranscriptItem.Tool -> {
                        val call = item.call
                        val last = rows.lastOrNull()
                        if (isMergeableRead(call) && last != null) {
                            val kind = last.kind
                            if (kind is Kind.Reads) {
                                rows[rows.lastIndex] = last.copy(kind = Kind.Reads(kind.calls + call), memberIDs = last.memberIDs + item.id)
                                continue
                            }
                            if (kind is Kind.Tool && isMergeableRead(kind.call)) {
                                rows[rows.lastIndex] = last.copy(kind = Kind.Reads(listOf(kind.call, call)), memberIDs = last.memberIDs + item.id)
                                continue
                            }
                        }
                        rows += InlineToolRow(item.id, listOf(item.id), Kind.Tool(call))
                    }
                    is OpenCodeTranscriptItem.Notice ->
                        rows += InlineToolRow(item.id, listOf(item.id), Kind.Notice(item.text, item.isError))
                    else -> continue
                }
            }
            return rows
        }

        private fun isMergeableRead(call: OpenCodeToolCall): Boolean =
            call.name == "read" && call.status == OpenCodeToolCall.Status.Completed && call.decline == null

        /**
         * A row's one-line detail. Code Mode's `execute` names the tools its script called (or the
         * catalog search it ran), never its code; a skill names the skill.
         */
        fun detail(call: OpenCodeToolCall): String? {
            val input = call.input.obj
            fun string(key: String): String? = input?.get(key)?.string?.trim()?.nonEmpty
            return when (call.name) {
                "execute" -> {
                    val code = string("code") ?: return null
                    val tools = OpenCodeToolCall.calledTools(code)
                    if (tools.isNotEmpty()) return tools.joinToString(", ")
                    val lookup = catalogSearch(code) ?: return null
                    if (lookup.contains(" ")) localized(R.string.transcript_tool_search_quoted, lookup) else localized(R.string.transcript_tool_search, lookup)
                }
                "skill" -> string("id") ?: string("name") ?: call.metadata["name"]?.string?.nonEmpty
                else -> call.subtitle
            }
        }

        private val SEARCH_CALL = Regex("""\bsearch\s*\(\s*\{([^}]*)\}""")

        /** What a Code Mode `search({ namespace, query })` looked up: the namespace when it names one, else the query. */
        fun catalogSearch(code: String): String? {
            val arguments = SEARCH_CALL.find(code)?.groupValues?.get(1) ?: return null
            fun value(key: String): String? {
                val found = Regex("""\b$key\s*:\s*["'`]([^"'`]+)["'`]""").find(arguments) ?: return null
                return found.groupValues[1].trim().nonEmpty
            }
            return value("namespace") ?: value("query")
        }
    }
}

/**
 * The collapsed Used N header: “Used 12 tools”, or “Used 12 tools · 1 failed” in red when a call
 * failed.
 */
@Immutable
data class InlineToolGroupHeader(
    val title: String,
    val failed: Int,
    /** The existing quiet cue: failed notices, or Denied. */
    val cue: String?,
) {
    val spoken: String
        get() {
            var spoken = title
            if (failed > 0) spoken = localized(R.string.transcript_spoken_pair, spoken, localized(R.string.transcript_tool_group_failed_count, failed))
            if (cue != null) spoken = localized(R.string.transcript_spoken_pair, spoken, cue)
            return spoken
        }

    companion object {
        operator fun invoke(items: List<OpenCodeTranscriptItem>): InlineToolGroupHeader {
            val (label, cue) = ToolGroupLabel.of(items)
            val failed = items.count { item ->
                val call = (item as? OpenCodeTranscriptItem.Tool)?.call ?: return@count false
                call.status == OpenCodeToolCall.Status.Error && call.decline == null
            }
            return InlineToolGroupHeader(label, failed, cue)
        }
    }
}

object ToolGroupLabel {
    /**
     * The collapsed row's label and quiet cue. With tools, “Used 12 tools”; a lone notice says
     * what it was (“Shell finished”), several of one kind are counted (“2 subagents finished”),
     * and any other notices are Updates.
     */
    fun of(tools: List<OpenCodeTranscriptItem>): Pair<String, String?> {
        val toolCount = tools.count { it is OpenCodeTranscriptItem.Tool }
        if (toolCount == 0) {
            val notices = tools.filterIsInstance<OpenCodeTranscriptItem.Notice>()
                .map { TranscriptNoticeKind.headingOfNotice(it.text) to it.kind }
            // The label already says what failed.
            if (notices.size == 1) return notices[0].first to null
            val kind = notices.firstOrNull()?.second
            if (kind != null && notices.all { it.second == kind }) return kind.count(notices.size) to null
            return localized(R.string.transcript_tool_group_updates) to failedNotices(tools)
        }
        val denied = tools.any { (it as? OpenCodeTranscriptItem.Tool)?.call?.decline == OpenCodeToolCall.Decline.Denied }
        // A denied call is an answer: a quiet cue, like a failed notice's.
        return localizedPlural(R.plurals.transcript_tool_group_used, toolCount, toolCount) to (failedNotices(tools) ?: if (denied) localized(R.string.transcript_tool_denied) else null)
    }

    /**
     * The collapsed row's quiet cue: what failed among the group's background shell and subagent
     * notices (“Shell failed”), which the TUI and desktop keep in view.
     */
    fun failedNotices(tools: List<OpenCodeTranscriptItem>): String? {
        val headings = mutableListOf<String>()
        for (item in tools) {
            if (item !is OpenCodeTranscriptItem.Notice || !item.isError) continue
            val heading = TranscriptNoticeKind.headingOfNotice(item.text)
            if (heading !in headings) headings += heading
        }
        return if (headings.isEmpty()) null else headings.joinToString(", ")
    }
}

/**
 * An answered question in the band: the draft or question as a muted quote, then every option in
 * the order offered (yours primary with a ✓, the rest tertiary) and anything typed. A quoted draft
 * at the end of a question becomes the quote, and a stray “?” after its closing quote is dropped.
 */
object InlineQuestion {
    @Immutable
    data class Line(val text: String, val kind: Kind) {
        enum class Kind {
            /** A form's question title, when there are several. */
            Header,
            /** The draft or question, muted. */
            Prompt,
            /** What you chose or typed: primary, with a ✓. */
            Chosen,
            /** An option you didn't pick: tertiary. */
            Unchosen,
            /** What you decided without choosing (Dismissed): primary. */
            Decision,
            /** What the agent reports, or your reason: secondary. */
            Note,
        }
    }

    private val QUOTED_DRAFT = Regex("""(?s)(.*?)[:\s]*["“]([^"”]{20,})["”]\s*\??""")

    fun quote(text: String): String {
        val trimmed = text.trim()
        // Long enough to be a draft, so “Keep "A" or "B"?” stays a question.
        QUOTED_DRAFT.matchEntire(trimmed)?.let { return it.groupValues[2].trim() }
        return trimmed
    }

    /** Each question's option labels, aligned with `OpenCodeToolCall.questions`. */
    fun options(call: OpenCodeToolCall): List<List<String>> {
        val values = (call.input.obj?.get("questions") as? OpenCodeJSONValue.Array)?.values ?: return emptyList()
        return values.mapNotNull { value ->
            val obj = value.obj ?: return@mapNotNull null
            obj["question"]?.string?.trim()?.nonEmpty ?: return@mapNotNull null
            val options = (obj["options"] as? OpenCodeJSONValue.Array)?.values ?: return@mapNotNull emptyList()
            options.mapNotNull { (it.obj?.get("label")?.string ?: it.string)?.nonEmpty }
        }
    }

    fun lines(
        questions: List<OpenCodeQuestion>,
        options: List<List<String>>,
        answers: List<List<String>>,
        dismissed: Boolean,
        reason: String?,
    ): List<Line> {
        val lines = mutableListOf<Line>()
        for ((index, question) in questions.withIndex()) {
            if (questions.size > 1 && question.header.isNotEmpty()) lines += Line(question.header, Line.Kind.Header)
            lines += Line(quote(question.question), Line.Kind.Prompt)
            val offered = options.getOrNull(index) ?: emptyList()
            val picked = if (dismissed || index >= answers.size) emptyList() else answers[index]
            for (option in offered) lines += Line(option, if (option in picked) Line.Kind.Chosen else Line.Kind.Unchosen)
            for (typed in picked) {
                if (typed in offered) continue
                lines += Line(if (offered.isEmpty()) typed else localized(R.string.transcript_quoted, typed), Line.Kind.Chosen)
            }
            if (!dismissed && picked.isEmpty()) lines += Line(localized(R.string.transcript_question_no_answer), Line.Kind.Note)
        }
        if (dismissed) {
            lines += Line(localized(R.string.transcript_question_dismissed), Line.Kind.Decision)
            if (reason != null) lines += Line(localized(R.string.transcript_quoted, reason), Line.Kind.Note)
        }
        return lines
    }
}

/** The settled question row's words: the header, then the answer. */
@Immutable
data class QuestionRowSummary(
    val questions: List<OpenCodeQuestion>,
    val answers: List<List<String>>,
    val decline: OpenCodeToolCall.Decline?,
    val reason: String?,
) {
    constructor(questions: List<OpenCodeQuestion>, call: OpenCodeToolCall) :
        this(questions, call.questionAnswers, call.decline, call.declineReason)

    /** The leading, quieter part and the answer that follows it. */
    val parts: Pair<String, String?>
        get() {
            val header = questions.firstOrNull()?.header?.nonEmpty ?: localized(R.string.transcript_question)
            if (questions.size > 1) {
                val count = questions.size
                if (decline == null) return localizedPlural(R.plurals.transcript_question_answered_count, count, count) to null
                return localizedPlural(R.plurals.transcript_question_count, count, count) to localized(R.string.transcript_question_dismissed)
            }
            if (decline != null) return header to localized(R.string.transcript_question_dismissed)
            return header to answer(0)
        }

    val spoken: String
        get() {
            val (lead, value) = parts
            return value?.let { localized(R.string.transcript_spoken_pair, lead, it) } ?: lead
        }

    fun answer(index: Int): String {
        if (decline != null) return reason ?: localized(R.string.transcript_question_dismissed)
        val values = answers.getOrNull(index) ?: emptyList()
        return if (values.isEmpty()) localized(R.string.transcript_question_no_answer) else values.joinToString(", ")
    }
}
