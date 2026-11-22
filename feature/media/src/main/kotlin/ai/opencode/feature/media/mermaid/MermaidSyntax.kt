package ai.opencode.feature.media

import ai.opencode.core.strings.localized
import java.security.MessageDigest
import kotlin.math.roundToInt

enum class MermaidTheme(val rawValue: String) {
    Light("light"),
    Dark("dark"),
}

/**
 * What a rendered diagram is cached under: its source, its theme, and the width it was drawn for (0
 * for the diagram itself, before any drawing). [width] is in dp, rounded.
 */
data class MermaidCacheKey private constructor(
    val sourceHash: String,
    val theme: MermaidTheme,
    val width: Int,
) {
    constructor(source: String, theme: MermaidTheme, width: Float = 0f) :
        this(MermaidSyntax.hash(source), theme, width.roundToInt())

    val diagram: MermaidCacheKey get() = MermaidCacheKey(sourceHash, theme, 0)

    val string: String get() = "$sourceHash|${theme.rawValue}|$width"
}

object MermaidSyntax {
    /** Hashes by source; emptied when full. */
    private val hashes = HashMap<String, String>()

    /** SHA-256s worked out, for tests. */
    @Volatile
    var hashCount = 0
        private set

    /**
     * The first 16 bytes of the source's SHA-256, hex, worked out once per source: a card asks
     * for its keys on every recomposition.
     */
    fun hash(source: String): String {
        synchronized(hashes) {
            hashes[source]?.let { return it }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(32)
        for (index in 0 until 16) {
            val byte = digest[index].toInt() and 0xFF
            hex.append(HEX_DIGITS[byte shr 4])
            hex.append(HEX_DIGITS[byte and 0x0F])
        }
        val hash = hex.toString()
        synchronized(hashes) {
            if (hashes.size >= 128) hashes.clear()
            hashes[source] = hash
            hashCount += 1
        }
        return hash
    }

    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * The diagram's kind from its first statement, for TalkBack: “Flowchart”, “Sequence
     * diagram”. Null when the source doesn't name a known kind.
     */
    fun diagramType(source: String): String? {
        var inFrontMatter = false
        for ((index, line) in source.split("\n").withIndex()) {
            val trimmed = line.trim(' ', '\t')
            if (trimmed == "---") {
                if (index == 0 || inFrontMatter) {
                    inFrontMatter = !inFrontMatter
                    continue
                }
            }
            if (inFrontMatter || trimmed.isEmpty() || trimmed.startsWith("%%")) continue
            val keyword = trimmed.takeWhile { !it.isWhitespace() && it != ';' && it != ':' && it != '{' }
            val name = names[keyword.lowercase()] ?: return null
            return localized(name)
        }
        return null
    }

    private val names: Map<String, Int> = mapOf(
        "flowchart" to R.string.media_mermaid_type_flowchart, "flowchart-elk" to R.string.media_mermaid_type_flowchart, "graph" to R.string.media_mermaid_type_flowchart,
        "sequencediagram" to R.string.media_mermaid_type_sequence,
        "classdiagram" to R.string.media_mermaid_type_class, "classdiagram-v2" to R.string.media_mermaid_type_class,
        "statediagram" to R.string.media_mermaid_type_state, "statediagram-v2" to R.string.media_mermaid_type_state,
        "erdiagram" to R.string.media_mermaid_type_entity_relationship,
        "journey" to R.string.media_mermaid_type_user_journey,
        "gantt" to R.string.media_mermaid_type_gantt,
        "pie" to R.string.media_mermaid_type_pie,
        "quadrantchart" to R.string.media_mermaid_type_quadrant,
        "requirementdiagram" to R.string.media_mermaid_type_requirement,
        "gitgraph" to R.string.media_mermaid_type_git_graph,
        "c4context" to R.string.media_mermaid_type_c4, "c4container" to R.string.media_mermaid_type_c4, "c4component" to R.string.media_mermaid_type_c4,
        "c4dynamic" to R.string.media_mermaid_type_c4, "c4deployment" to R.string.media_mermaid_type_c4,
        "mindmap" to R.string.media_mermaid_type_mind_map,
        "timeline" to R.string.media_mermaid_type_timeline,
        "zenuml" to R.string.media_mermaid_type_sequence,
        "sankey" to R.string.media_mermaid_type_sankey, "sankey-beta" to R.string.media_mermaid_type_sankey,
        "xychart" to R.string.media_mermaid_type_xy_chart, "xychart-beta" to R.string.media_mermaid_type_xy_chart,
        "block" to R.string.media_mermaid_type_block, "block-beta" to R.string.media_mermaid_type_block,
        "packet" to R.string.media_mermaid_type_packet, "packet-beta" to R.string.media_mermaid_type_packet,
        "kanban" to R.string.media_mermaid_type_kanban,
        "architecture" to R.string.media_mermaid_type_architecture, "architecture-beta" to R.string.media_mermaid_type_architecture,
        "radar-beta" to R.string.media_mermaid_type_radar,
        "treemap-beta" to R.string.media_mermaid_type_treemap,
    )
}
