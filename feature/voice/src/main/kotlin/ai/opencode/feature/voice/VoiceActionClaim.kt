package ai.opencode.feature.voice

/**
 * A short reply in which the voice agent says it's acting now ("Opening.",
 * "Checking.", "Let me look into that."), which only a delegation makes
 * true.
 */
object VoiceActionClaim {
    /** Acknowledgements are a few words; anything longer is an answer. */
    const val maxWords = 10

    private val gerunds = listOf(
        "opening", "checking", "switching", "sending", "pulling up", "pulling that up", "pulling it up",
        "starting", "creating", "searching", "asking", "moving", "pinning", "unpinning", "stopping",
        "looking into", "looking at", "looking for", "looking now", "looking it up", "taking a look",
        "having a look", "on it", "doing it now", "doing that now", "working on it", "digging into", "digging in",
    )
    private val verbs = listOf(
        "open", "check", "switch", "send", "pull", "start", "create", "search", "ask", "move", "pin",
        "unpin", "stop", "look", "take a look", "have a look", "find", "get on", "dig",
    )
    private val leads = listOf("i'll ", "i will ", "let me ", "i'm going to ", "i am going to ", "gonna ", "going to ")

    /**
     * "Let's" also suggests ("Let's start with the tests", "Let's stop there"),
     * so only the verbs that say the voice agent is looking now.
     */
    private val lets = listOf("let's ")
    private val letsVerbs = listOf("check", "look", "take a look", "have a look", "dig", "find", "pull", "open", "search")
    private val progressive = listOf("i'm ", "i am ")

    fun isClaim(text: String): Boolean {
        val lowered = text.lowercase().replace("’", "'")
        val words = lowered.splitWhere(Char::isWhitespace)
        if (words.isEmpty() || words.size > maxWords) return false
        val clauses = lowered
            .splitWhere { it in ".,!?;:—–…" }
            .map { it.trim { char -> char == ' ' || char == '\t' } + " " }
        return clauses.any { clause ->
            if (startsWithGerund(clause)) return@any true
            val afterProgressive = rest(clause, progressive)
            if (afterProgressive != null && startsWithGerund(afterProgressive)) return@any true
            rest(clause, lets)?.let { rest -> return@any letsVerbs.any { rest.startsWith("$it ") } }
            rest(clause, leads)?.let { rest -> return@any verbs.any { rest.startsWith("$it ") } }
            false
        }
    }

    private fun startsWithGerund(clause: String): Boolean = gerunds.any { clause.startsWith("$it ") }

    private fun rest(clause: String, prefixes: List<String>): String? =
        prefixes.firstOrNull(clause::startsWith)?.let { clause.substring(it.length) }

    private val pastLeads = listOf("i ", "i've ", "i have ", "we ", "we've ", "we have ")
    private val clauseOpeners = listOf("so ", "and ", "yeah ", "yep ", "yes ", "ok ", "okay ", "alright ", "no ", "nope ")
    private val doneClauses = listOf(
        "it's done ", "it is done ", "that's done ", "that is done ", "all done ", "that went through ", "it went through ",
    )
    private val outcomeVerbs = setOf("took", "put", "sent", "added", "removed", "moved", "deleted", "booked", "ordered", "texted", "emailed")
    private val negations = setOf("not", "never", "nothing", "nobody", "neither", "yet")

    /**
     * A reply that states an outcome ("Done, it's sent.", "I've added it."),
     * which only a result makes true. Questions and negated clauses don't count.
     */
    fun isOutcomeClaim(text: String): Boolean {
        val normalized = text.replace("’", "'").replace("?", "?\n")
        for (sentence in normalized.splitWhere { it in ".!…\n" }) {
            if (sentence.endsWith("?")) continue
            for (raw in sentence.splitWhere { it in ",;:—–" }) {
                var clause = raw.trim().lowercase()
                if (clause.isEmpty()) continue
                clause += " "
                val tokens = clause.splitWhere { !it.isLetter() && it != '\'' }
                if (tokens.any { it.endsWith("n't") || it in negations }) continue
                while (true) {
                    val opener = clauseOpeners.firstOrNull(clause::startsWith) ?: break
                    clause = clause.substring(opener.length)
                }
                if (doneClauses.any(clause::startsWith)) return true
                var rest = rest(clause, pastLeads) ?: continue
                rest = rest(rest, listOf("just ", "already ")) ?: rest
                val verb = rest.splitWhere(Char::isWhitespace).firstOrNull()
                if (verb != null && verb in outcomeVerbs) return true
            }
        }
        return false
    }

    /** The silent check sent to the delegator for an outcome claim with no result behind it. */
    fun verifyPrompt(question: String?, claim: String): String {
        val trimmed = question?.trim().orEmpty()
        val asked = if (trimmed.isEmpty()) "" else "User asked: \"$trimmed\"\n"
        return "[Outcome check]\n${asked}Voice told the user: \"$claim\"\nCheck whether that action is actually complete. " +
            "If Voice's statement is already accurate and nothing else needs saying, reply with ONLY the word VERIFIED. " +
            "Otherwise reply in 1–2 plain spoken sentences correcting Voice."
    }

    /** Whether the delegator's reply to `verifyPrompt` corrects the voice agent (anything but VERIFIED). */
    fun correctsVoice(reply: String): Boolean {
        var text = reply.trim()
        if (text.startsWith("[COMPLETE]")) text = text.removePrefix("[COMPLETE]").trim()
        return text.isNotEmpty() && text != "VERIFIED"
    }
}
