package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.sharing.FileDirectory
import ai.opencode.core.sharing.SharedKey
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelRef
import kotlinx.serialization.Serializable

/**
 * Labels only: remembered names never make an unavailable model selectable or supply stale
 * capabilities. Provider/catalog identity remains authoritative.
 */
@Serializable
data class ModelDisplayNames(val entries: List<Entry> = emptyList()) {
    @Serializable
    data class Entry(val providerID: String, val key: String, val modelID: String, val name: String)

    /** This value with [models]' usable names first. */
    fun remember(models: List<OpenCodeModel>): ModelDisplayNames {
        val incoming = models.mapNotNull { model ->
            if (!usable(model.name, model.key, model.modelID)) null
            else Entry(model.providerID, model.key, model.modelID, model.name)
        }
        val ids = incoming.map { "${it.providerID}/${it.key}" }.toSet()
        // Catalogue order varies by directory. Identical labels need neither an
        // LRU reshuffle nor a shared-file write.
        val existing = entries.toSet()
        if (entries.size <= LIMIT && incoming.all { it in existing }) return this
        return ModelDisplayNames((incoming + entries.filter { "${it.providerID}/${it.key}" !in ids }).take(LIMIT))
    }

    fun name(ref: OpenCodeModelRef): String? =
        entries.firstOrNull { it.providerID == ref.providerID && it.key == ref.id }?.name

    /**
     * A provider may disappear while history still names it. Borrow only a name all matching
     * entries agree on; never borrow their routing or capabilities.
     */
    fun unambiguousName(ref: OpenCodeModelRef): String? {
        // Fast/Ultrafast entries share an API modelID with the base model but
        // have distinct catalogue keys. They cannot name a base-model ref.
        val names = entries.filter { it.key == ref.id }.map { it.name }.toSet()
        return if (names.size == 1) names.first() else null
    }

    companion object {
        private const val LIMIT = 1024

        operator fun invoke(models: List<OpenCodeModel>): ModelDisplayNames = ModelDisplayNames().remember(models)

        fun usable(name: String, key: String, modelID: String): Boolean =
            name.isNotBlank() && name != key && name != modelID

        // A digit, "-", then a digit that ends a numeric component: "4-1" → "4.1", but not the
        // "20250514" in a dated snapshot.
        private val versionDash = Regex("""(?<=\d)-(?=\d(?:\D|$))""")

        /**
         * Last resort for a ref never seen in a catalogue. Keep numeric version components
         * together, but don't turn a dated snapshot into a version.
         */
        fun prettify(id: String): String {
            val leaf = id.split('/').lastOrNull { it.isNotEmpty() } ?: id
            val words = leaf.replace(versionDash, ".").split('-', '_').filter { it.isNotEmpty() }
            return words.joinToString(" ") { word ->
                when (word.lowercase()) {
                    "gpt" -> "GPT"
                    "openai" -> "OpenAI"
                    else -> word.take(1).uppercase() + word.drop(1)
                }
            }
        }

        val modelDisplayNamesByServer = SharedKey.fileStorage<Map<String, ModelDisplayNames>>(
            FileDirectory.Documents,
            "model-display-names.json",
            emptyMap(),
        )
    }
}
