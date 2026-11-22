package ai.opencode.feature.voice

/**
 * Bounded 40-turn ring buffer and seed-offset bookkeeping shared by both
 * voice coordinators when seeding a replacement session.
 *
 * Immutable: the mutating methods return the updated history.
 */
data class VoiceTurnHistory(
    val turns: List<Turn> = emptyList(),
    private val seededTurns: Int = 0,
    /** Turns trimmed off the front of `turns`, to keep marks taken across an await. */
    private val droppedTurns: Int = 0,
) {
    data class Turn(
        val id: String? = null,
        val role: VoiceTranscriptTail.Role,
        val text: String,
        /**
         * A reply the connection (or an interruption) stopped mid-way. Seeded
         * as it is, GPT-Live finishes it on the next session.
         */
        val cutOff: Boolean = false,
    ) {
        val line: String
            get() = "${if (role == VoiceTranscriptTail.Role.user) "User" else "You"}: ${text.take(600)}" +
                if (cutOff) " [cut off; don't finish or repeat it]" else ""
    }

    /** [catchUp]'s result. */
    data class CatchUp(val mark: Int, val lines: Int, val text: String)

    val mark: Int get() = turns.size + droppedTurns

    fun noteSeeded(at: Int): VoiceTurnHistory = copy(seededTurns = maxOf(0, at - droppedTurns))

    fun catchUp(seeded: Boolean): CatchUp? {
        if (!seeded || turns.size <= seededTurns) return null
        val lines = turns.drop(seededTurns).map { it.line }
        return CatchUp(mark, lines.size, catchUpLead + "\n" + lines.joinToString("\n"))
    }

    /** One system message: the call so far and any work still running. */
    fun seedText(runningRequests: Int = 0): String {
        var text = "This voice call continues from an earlier connection; you keep its context. Carry on naturally and don't mention reconnecting. Don't repeat anything you already said."
        val recent = turns.takeLast(10).map { it.line }
        if (recent.isNotEmpty()) {
            text += "\n\nThe call so far, oldest first:\n" + recent.joinToString("\n")
        }
        if (runningRequests > 0) {
            val subject = if (runningRequests == 1) "One request you delegated is" else "$runningRequests requests you delegated are"
            text += "\n\n$subject still running. Results arrive as system messages starting \"Result of an earlier request\"; deliver each once, then."
        }
        return text
    }

    fun append(role: VoiceTranscriptTail.Role, text: String): VoiceTurnHistory {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return this
        return copy(turns = turns + Turn(role = role, text = trimmed)).trimmed()
    }

    fun upsert(id: String, role: VoiceTranscriptTail.Role, text: String, cutOff: Boolean = false): VoiceTurnHistory {
        val index = turns.indexOfFirst { it.id == id }
        if (index >= 0) {
            val updated = turns.toMutableList()
            updated[index] = updated[index].copy(text = text, cutOff = cutOff)
            return copy(turns = updated)
        }
        return copy(turns = turns + Turn(id = id, role = role, text = text, cutOff = cutOff)).trimmed()
    }

    private fun trimmed(): VoiceTurnHistory {
        if (turns.size <= 40) return this
        val drop = turns.size - 40
        return VoiceTurnHistory(
            turns = turns.drop(drop),
            seededTurns = maxOf(0, seededTurns - drop),
            droppedTurns = droppedTurns + drop,
        )
    }

    companion object {
        const val catchUpLead = "Transcript of what was said while the call switched connections, for context. User lines are the user's own words spoken to you, not a note or a result:"
    }
}

/**
 * The results the voice agent was actually given this call, as short lines.
 * A replacement session only knows what it is seeded with; without these a
 * recap is built from the voice agent's own earlier replies, embellishments
 * included.
 */
data class VoiceResultRecord(val entries: List<String> = emptyList()) {
    fun record(content: String): VoiceResultRecord {
        val entry = entry(content)
        if (entry.isEmpty() || entries.lastOrNull() == entry) return this
        return VoiceResultRecord((entries + entry).takeLast(maxEntries))
    }

    companion object {
        const val maxEntries = 6
        const val entryLimit = 300

        /** A result without the delivery directions wrapped around it. */
        fun entry(content: String): String {
            val horizontal = { char: Char -> char == ' ' || char == '\t' || (char.isWhitespace() && char != '\n' && char != '\r') }
            var text = content.trim()
            for (rawLead in listOf(VoiceHandoff.earlierRequestLead, LiveCallCoordinator.earlierConnectionLead, LiveCallCoordinator.verifyLead)) {
                val lead = rawLead.trim(horizontal)
                if (text.startsWith(lead)) text = text.removePrefix(lead).trim(horizontal)
            }
            if (text.startsWith(VoiceHandoff.earlierTopicLead)) {
                val end = text.indexOf("”. ")
                if (end >= 0) text = text.substring(end + "”. ".length)
            }
            if (text.startsWith(LiveCallCoordinator.claimLeadPrefix)) {
                val end = text.indexOf("”: ")
                if (end >= 0) text = text.substring(end + "”: ".length)
            }
            val reply = text.indexOf("Its reply: ")
            if (reply >= 0) {
                val open = text.indexOf("“")
                val title = if (open >= 0) {
                    val close = text.indexOf("”", open + 1)
                    if (close >= 0) text.substring(open + 1, close) else null
                } else {
                    null
                }
                val body = text.substring(reply + "Its reply: ".length)
                text = title?.let { "“$it” replied: $body" } ?: body
            } else {
                val directions = text.indexOf(" Tell the user")
                if (directions >= 0) text = text.substring(0, directions)
            }
            val collapsed = text.splitWhere(Char::isWhitespace).joinToString(" ")
            return if (collapsed.length > entryLimit) collapsed.take(entryLimit - 1) + "…" else collapsed
        }
    }
}
