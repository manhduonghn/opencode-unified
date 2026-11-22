package ai.opencode.server.model

import java.text.Normalizer

/**
 * Each query word, in any order, must match the model's or provider's name or ID. Whole words rank
 * best, then word starts, version parts, then anywhere; three letters or more may skip letters
 * ("snt" for Sonnet). Numbers match only where a number starts. Best match first, then favorites,
 * then the server's order.
 */
object ModelSearch {
    private val levelSynonyms: Map<String, String> = mapOf(
        "off" to "none",
        "none" to "none",
        "minimal" to "minimal",
        "min" to "minimal",
        "low" to "low",
        "medium" to "medium",
        "med" to "medium",
        "high" to "high",
        "xhigh" to "xhigh",
        "max" to "max",
        "maximum" to "max",
        "ultra" to "ultra",
        "default" to "default",
    )

    data class ParsedLevelQuery(val terms: List<String>, val level: String?)

    data class ResolvedPick(val model: OpenCodeModel, val variant: String?, val hasExplicitLevel: Boolean)

    /**
     * Splits out a reasoning level word when paired with model search terms
     * (`"argon high"` → `(["argon"], "high")`, `"sonnet extra high"` → `(["sonnet"], "xhigh")`).
     */
    fun parseLevelQuery(query: String): ParsedLevelQuery {
        val raw = words(normalize(query))
        if (raw.size <= 1) return ParsedLevelQuery(raw, null)
        val terms = mutableListOf<String>()
        var level: String? = null
        var index = 0
        while (index < raw.size) {
            val word = raw[index]
            val next = raw.getOrNull(index + 1)
            if (level == null && word == "extra" && next == "high" && raw.size > 2) {
                level = "xhigh"
                index += 2
                continue
            }
            val mapped = levelSynonyms[word]
            if (level == null && mapped != null &&
                raw.withIndex().any { it.index != index && levelSynonyms[it.value] == null }
            ) {
                level = mapped
                index += 1
                continue
            }
            terms += word
            index += 1
        }
        return ParsedLevelQuery(terms, level)
    }

    /** Resolves a parsed level word against the variants [model] actually offers; `"default"` resolves to null. */
    fun resolveVariant(requested: String, model: OpenCodeModel): String? {
        if (requested == "default") return null
        val sorted = ModelVariant.sorted(model.variants.filter { it != "default" })
        if (sorted.isEmpty()) return null
        sorted.firstOrNull { it.equals(requested, ignoreCase = true) }?.let { return it }
        return when (requested.lowercase()) {
            "max", "ultra", "xhigh" -> sorted.last()
            "none", "minimal", "low" -> sorted.first()
            "medium" -> sorted[sorted.size / 2]
            else -> sorted.last()
        }
    }

    /** Top match for Enter in the search field, plus any explicit level parsed from the query. */
    fun resolvePick(
        models: List<OpenCodeModel>,
        query: String,
        favorites: Set<String> = emptySet(),
        recents: List<String> = emptyList(),
    ): ResolvedPick? {
        val top = rank(models, query, favorites, recents, parseLevel = true).firstOrNull() ?: return null
        val requested = parseLevelQuery(query).level
        if (requested != null) return ResolvedPick(top, resolveVariant(requested, top), true)
        return ResolvedPick(top, null, false)
    }

    private data class Ranked(
        val model: OpenCodeModel,
        val score: Double,
        val favorite: Boolean,
        val recentRank: Int,
        val index: Int,
    )

    /** [parseLevel] (One Picker only) also scores the query without a level word, favouring models that offer that level. */
    fun rank(
        models: List<OpenCodeModel>,
        query: String,
        favorites: Set<String> = emptySet(),
        recents: List<String> = emptyList(),
        parseLevel: Boolean = false,
    ): List<OpenCodeModel> {
        val allTerms = words(normalize(query))
        if (allTerms.isEmpty()) return models
        val parsed = if (parseLevel) parseLevelQuery(query) else ParsedLevelQuery(allTerms, null)
        val recentOrder = HashMap<String, Int>()
        recents.forEachIndexed { idx, id -> recentOrder.putIfAbsent(id, idx) }
        return models.mapIndexedNotNull { index, model ->
            val fullScore = score(model, allTerms)
            val requested = parsed.level
            val strippedScore: Double? = if (requested != null && parsed.terms.isNotEmpty()) {
                score(model, parsed.terms)?.let { base ->
                    val bonus = when {
                        requested == "default" -> 0.01
                        model.variants.any { it.equals(requested, ignoreCase = true) } -> 0.02
                        model.variants.isNotEmpty() -> 0.01
                        else -> 0.0
                    }
                    base + bonus
                }
            } else null
            val best = listOfNotNull(fullScore, strippedScore).maxOrNull() ?: return@mapIndexedNotNull null
            Ranked(model, best, model.id in favorites, recentOrder[model.id] ?: Int.MAX_VALUE, index)
        }.sortedWith { a, b ->
            when {
                a.score != b.score -> b.score.compareTo(a.score)
                a.favorite != b.favorite -> if (a.favorite) -1 else 1
                a.recentRank != b.recentRank -> a.recentRank.compareTo(b.recentRank)
                else -> a.index.compareTo(b.index)
            }
        }.map { it.model }
    }

    /** How well every term matches, from 0 to 1; null when any term doesn't. */
    fun score(model: OpenCodeModel, terms: List<String>): Double? {
        data class Field(val text: String, val weight: Double, val fuzzy: Boolean)
        val fields = listOf(
            Field(normalize(model.name), 1.0, true),
            Field(normalize(ModelProviderGroup.title(model.providerID)), 0.95, false),
            Field(normalize(model.providerID), 0.95, false),
            Field(normalize(model.key), 0.8, false),
            Field(normalize(model.modelID), 0.8, false),
            Field(normalize(model.canonicalProviderID ?: ""), 0.6, false),
        )
        var total = 0.0
        for (term in terms) {
            val best = fields.maxOf { match(term, it.text, it.fuzzy) * it.weight }
            if (best <= 0) return null
            total += best
        }
        return total / terms.size
    }

    /**
     * Lowercased words without accents or punctuation, keeping a version's dot: "Claude Sonnet
     * 4.5" and "claude-sonnet-4-5" both read "claude sonnet 4.5".
     */
    fun normalize(text: String): String {
        // NFKD + dropping combining marks does the same.
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD)
        val characters = decomposed.filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }.lowercase()
        val out = StringBuilder()
        for ((index, character) in characters.withIndex()) {
            if (character.isLetter() || character.isNumberLike()) {
                out.append(character)
            } else if (character in ".-_" && index > 0 && index + 1 < characters.length &&
                characters[index - 1].isNumberLike() && characters[index + 1].isNumberLike()
            ) {
                out.append('.')
            } else if (out.isNotEmpty() && out.last() != ' ') {
                out.append(' ')
            }
        }
        return if (out.lastOrNull() == ' ') out.substring(0, out.length - 1) else out.toString()
    }

    fun match(term: String, text: String, fuzzy: Boolean): Double {
        if (text.isEmpty()) return 0.0
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.any { it == term }) return 1.0
        if (words.any { it.startsWith(term) }) return 0.85
        for (index in 1 until text.length) {
            if (startsPart(text, index) && text.startsWith(term, index)) return 0.7
        }
        if (term.firstOrNull()?.isNumberLike() == true) return 0.0
        if (text.contains(term)) return 0.5
        if (text.replace(" ", "").contains(term)) return 0.4
        if (fuzzy && term.length >= 3 && words.any { it.firstOrNull() == term.first() && isSubsequence(term, it) }) return 0.25
        return 0.0
    }

    /** A version part or a run of digits inside a word: "5" in "4.5", "2" in "v2". */
    private fun startsPart(characters: String, index: Int): Boolean {
        val previous = characters[index - 1]
        val current = characters[index]
        if (previous == '.') return true
        return (previous.isLetter() && current.isNumberLike()) || (previous.isNumberLike() && current.isLetter())
    }

    private fun isSubsequence(term: String, word: String): Boolean {
        var remaining = 0
        for (character in word) {
            if (remaining < term.length && character == term[remaining]) {
                remaining += 1
                if (remaining == term.length) return true
            }
        }
        return remaining == term.length
    }

    private fun words(text: String): List<String> = text.split(' ').filter { it.isNotEmpty() }

    /** Digits and other numeric characters. */
    private fun Char.isNumberLike(): Boolean = isDigit() || Character.getType(this).let {
        it == Character.LETTER_NUMBER.toInt() || it == Character.OTHER_NUMBER.toInt()
    }
}
