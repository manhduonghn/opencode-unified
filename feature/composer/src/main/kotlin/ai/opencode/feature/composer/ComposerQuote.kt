package ai.opencode.feature.composer

import ai.opencode.server.model.OpenCodeQuote
import kotlinx.serialization.Serializable

@Serializable
data class ComposerQuote(val id: String, val text: String, val comment: String? = null) {
    val quote: OpenCodeQuote get() = OpenCodeQuote(text, comment)

    companion object {
        fun cleaned(text: String): String? {
            val trimmed = text.trim('\n', '\r', ' ', '\t')
            return trimmed.ifEmpty { null }
        }
    }
}
