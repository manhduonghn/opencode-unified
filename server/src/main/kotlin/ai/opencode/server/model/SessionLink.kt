package ai.opencode.server.model

/**
 * A session ID in a reply's Markdown, as the desktop app links them
 * (`@opencode/util/session-mention`, `markdown-session-links.ts`): a complete generated ID
 * (`ses_` and 26 letters or digits), optionally written `@session:ses_…`, as a whole inline code
 * span or inside plain text. Never in a fenced block, a link, or a longer identifier or path
 * (`/` or `\`). Parsing marks it with a private `opencode-session://` link; the row shows the
 * session's title in its place and a tap opens the session.
 *
 * In `:server` because the link menu (`:feature:media`) reads it. The rich-text marking (`mark`,
 * `markRuns`, `marked(plain:)`, `ids(in:)`) stays with the transcript's renderer, as extensions on
 * this object.
 */
object SessionLink {
    const val SCHEME = "opencode-session"

    // ICU `\w` (NSRegularExpression), spelled out so it reads the same on the JVM and Android.
    private const val WORD = "\\p{L}\\p{Nl}\\p{M}\\p{Nd}\\p{Pc}\\u200C\\u200D"
    private val pattern = Regex("(?<![$WORD/\\\\:@-])(?:@session:)?(ses_[A-Za-z0-9]{26})(?![$WORD/\\\\-])")

    /** [range] covers the whole mention, `@session:` included. */
    data class Mention(val id: String, val range: IntRange)

    fun mentions(text: String): List<Mention> {
        if (!text.contains("ses_")) return emptyList()
        return pattern.findAll(text).map { Mention(it.groupValues[1], it.range) }.toList()
    }

    /** The ID when [text] is exactly one mention (an inline code span). */
    fun exactID(text: String): String? {
        val mention = mentions(text).firstOrNull() ?: return null
        return if (mention.range.first == 0 && mention.range.last == text.lastIndex) mention.id else null
    }

    /** The private link the rendered text carries. [title] rides along for the hold menu's preview. */
    fun url(id: String, title: String? = null): String = buildString {
        append(SCHEME).append("://open?id=").append(AgentFileReference.percentEncodeQueryValue(id))
        if (title != null) append("&title=").append(AgentFileReference.percentEncodeQueryValue(title))
    }

    fun id(url: String): String? {
        if (AgentFileReference.urlScheme(url) != SCHEME) return null
        return query(url, "id")?.let(::exactID)
    }

    fun title(url: String): String? {
        if (AgentFileReference.urlScheme(url) != SCHEME) return null
        return query(url, "title")
    }

    private fun query(url: String, name: String): String? =
        AgentFileReference.queryItems(url)?.firstOrNull { it.first == name }?.second

    /** What a link shows until its title is known, or when it never is: `ses_ea0c…KB2l`. */
    fun shortID(id: String): String = if (id.length <= 16) id else "${id.take(8)}…${id.takeLast(4)}"

    /**
     * Whether Markdown [source] could render an ID, which always shows `s_`. Only entities (`&`)
     * and escapes (`\`) render a character the source doesn't hold, so otherwise that `s` and `_`
     * are literal, with nothing shown between them: the source holds `s_`, or `s` then markup
     * Markdown drops (emphasis, code span, link or image syntax), which starts with one of
     * `joinsAfterS`.
     */
    fun mayRenderID(source: String): Boolean {
        var afterS = false
        for (c in source) {
            if (c == '&' || c == '\\' || (afterS && c in joinsAfterS)) return true
            afterS = c == 's'
        }
        return false
    }

    private val joinsAfterS = setOf('_', '*', '~', '`', '<', '[', ']', '!')
}
