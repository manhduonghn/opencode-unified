package ai.opencode.server.model

import ai.opencode.core.R as CoreR
import ai.opencode.core.sharing.InstantSerializer
import ai.opencode.core.strings.localized
import ai.opencode.core.strings.localizedPlural
import ai.opencode.server.R
import androidx.annotation.StringRes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.math.ceil

/**
 * What a background shell's or subagent's notice reports (“Shell finished”,
 * “Explore failed”); several of one kind are counted (“2 subagents finished”).
 */
@Serializable
data class TranscriptNoticeKind(val source: Source, val outcome: Outcome) {
    @Serializable
    enum class Source {
        @SerialName("shell") Shell,
        @SerialName("subagent") Subagent;

        /** “Shell”, “Subagent”. */
        val title: String
            get() = when (this) {
                Shell -> localized(R.string.server_notice_source_shell)
                Subagent -> localized(R.string.server_notice_source_subagent)
            }
    }

    @Serializable
    enum class Outcome(val rawValue: String) {
        @SerialName("finished") Finished("finished"),
        @SerialName("failed") Failed("failed"),
        @SerialName("cancelled") Cancelled("cancelled");
    }

    /** The notice's own status line, naming a subagent by its agent: “Explore finished”, else “Subagent finished”. */
    fun heading(agent: String? = null): String {
        val actor = if (source == Source.Subagent) AgentDisplayName.of(agent) ?: source.title else source.title
        // A cancelled one was interrupted.
        return when (outcome) {
            Outcome.Finished -> localized(R.string.server_notice_heading_finished, actor)
            Outcome.Failed -> localized(R.string.server_notice_heading_failed, actor)
            Outcome.Cancelled -> localized(R.string.server_notice_heading_interrupted, actor)
        }
    }

    /** “1 shell failed”, “2 subagents finished”. */
    fun count(count: Int): String {
        val id = when (source) {
            Source.Shell -> when (outcome) {
                Outcome.Finished -> R.plurals.server_notice_count_shell_finished
                Outcome.Failed -> R.plurals.server_notice_count_shell_failed
                Outcome.Cancelled -> R.plurals.server_notice_count_shell_interrupted
            }
            Source.Subagent -> when (outcome) {
                Outcome.Finished -> R.plurals.server_notice_count_subagent_finished
                Outcome.Failed -> R.plurals.server_notice_count_subagent_failed
                Outcome.Cancelled -> R.plurals.server_notice_count_subagent_interrupted
            }
        }
        return localizedPlural(id, count, count)
    }

    companion object {
        /** A notice reads “<heading> · <description>”. */
        fun headingOfNotice(text: String): String = text.split(" · ").first()
    }
}

object AgentDisplayName {
    /** An agent's ID as people read it: “explore” → “Explore”, “code-reviewer” → “Code-Reviewer”. */
    fun of(id: String?): String? {
        val trimmed = id?.trimmedWhitespace()?.ifEmpty { null } ?: return null
        return titleCased(trimmed)
    }

    fun titleCased(text: String): String {
        val result = StringBuilder()
        var atWordStart = true
        var index = 0
        while (index < text.length) {
            val end = text.offsetByCodePoints(index, 1)
            val piece = text.substring(index, end)
            val codePoint = piece.codePointAt(0)
            val isWord = Character.isLetter(codePoint) || Character.isDigit(codePoint) || piece == "_"
            result.append(if (atWordStart && isWord) piece.uppercase() else piece)
            atWordStart = !isWord
            index = end
        }
        return result.toString()
    }
}

/**
 * A run that ended early: an assistant message's `error`, or an `idle` message's when
 * the run failed before any reply (V2 `Session.StructuredError`).
 */
@Serializable
data class OpenCodeRunError(
    val type: String,
    val message: String,
    val status: Int? = null,
    /** Recorded on the run's `idle` message: nothing replied before it failed. */
    val beforeReply: Boolean = false,
) {
    enum class Action(@StringRes private val titleRes: Int) {
        /** Sends “Continue”, so the agent picks up where the run stopped. */
        TryAgain(CoreR.string.common_try_again),
        /** Undoes the last message and puts its words back in the composer. */
        Edit(CoreR.string.common_edit),
        Compact(R.string.server_run_error_action_compact),
        SwitchModel(R.string.server_run_error_action_switch_model);

        val title: String get() = localized(titleRes)
    }

    /** A stop the reader asked for (Stop, or a denied request) is no failure. */
    val isStop: Boolean get() = type == "aborted" || type == "permission.rejected"

    /** The line the transcript shows. */
    val line: String
        get() = when (type) {
            "aborted" -> localized(R.string.server_run_error_aborted)
            "permission.rejected" -> localized(R.string.server_run_error_permission_rejected)
            "provider.content-filter" -> localized(R.string.server_run_error_content_filter)
            "provider.rate-limit" -> localized(R.string.server_run_error_rate_limit)
            "provider.quota" -> localized(R.string.server_run_error_quota)
            "provider.auth" -> localized(R.string.server_run_error_auth)
            "provider.invalid-request" ->
                if (isContextOverflow) localized(R.string.server_run_error_context_overflow) else localized(R.string.server_run_error_invalid_request)
            "provider.internal" -> if (isOverloaded) localized(R.string.server_run_error_overloaded) else localized(R.string.server_run_error_internal)
            "provider.transport" -> localized(R.string.server_run_error_transport)
            "provider.timeout" -> localized(R.string.server_run_error_timeout)
            "provider.invalid-output" -> localized(R.string.server_run_error_invalid_output)
            "provider.no-route", "provider.unknown" -> localized(R.string.server_run_error_unavailable)
            "provider.unsupported-operation" -> localized(R.string.server_run_error_unsupported_operation)
            "tool.execution" -> localized(R.string.server_run_error_tool_execution)
            else -> localized(R.string.server_run_error_other)
        }

    /** The one action the line offers; the hold menu has the rest. */
    val action: Action?
        get() = when (type) {
            "aborted", "permission.rejected" -> null
            "provider.content-filter" -> Action.Edit
            "provider.invalid-request" -> if (isContextOverflow) Action.Compact else Action.Edit
            "provider.quota", "provider.auth", "provider.no-route", "provider.unknown", "provider.unsupported-operation" -> Action.SwitchModel
            else -> Action.TryAgain
        }

    /** Every action that could help, the line's own first. */
    val actions: List<Action>
        get() {
            if (isStop) return emptyList()
            val all = listOfNotNull(action).toMutableList()
            for (other in listOf(Action.TryAgain, Action.Edit, Action.SwitchModel)) if (other !in all) all += other
            if (isContextOverflow && Action.Compact !in all) all += Action.Compact
            return all
        }

    /** The raw type and the provider's message, for Details and Copy. */
    val details: String
        get() {
            val code = status?.let { "$type · $it" } ?: type
            return if (message.isEmpty()) code else "$code\n$message"
        }

    /** V2 classifies an overflowing prompt as an invalid request; its message carries the provider's wording. */
    val isContextOverflow: Boolean
        get() {
            val text = message.lowercase()
            return listOf(
                "prompt is too long", "context length", "context_length_exceeded", "context window", "maximum context",
                "too many tokens", "input is too long", "exceeds the context",
            ).any { text.contains(it) }
        }

    private val isOverloaded: Boolean get() = status == 529 || message.lowercase().contains("overloaded")
}

/** The server's automatic retry after a failed step (`Assistant.retry`). */
@Serializable
data class OpenCodeRunRetry(
    val attempt: Int,
    @Serializable(with = InstantSerializer::class) val at: Instant,
    val error: OpenCodeRunError,
) {
    /** “Rate limit reached · retrying in 4 s”, then “… · retrying”. */
    fun title(now: Instant): String {
        val seconds = ceil((at.toEpochMilli() - now.toEpochMilli()) / 1000.0).toInt()
        return if (seconds > 0) localized(R.string.server_run_retry_in_seconds, error.line, seconds) else localized(R.string.server_run_retry_now, error.line)
    }
}
