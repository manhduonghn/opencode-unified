package ai.opencode.feature.transcript.rows

import ai.opencode.core.strings.localized
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.feature.transcript.R
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.server.model.ModelVariant
import ai.opencode.server.model.OpenCodeModelRef
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import android.content.res.AssetManager
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * What a model switch reads as, like the desktop app's divider: “Switched to Claude Opus 5.5 ·
 * High”, the model by its catalog name (its ID until the catalog has it, or for a model it lacks)
 * and the thinking level when it isn't the default. A switch back to the same model's default level
 * says Default, so it never reads as no change.
 */
@Immutable
data class ModelSwitchLabel(val title: String, val variant: String?, val thinkingOff: Boolean = false) {
    val accessibilityLabel: String
        get() {
            if (variant == null) return title
            if (thinkingOff) return localized(R.string.transcript_model_switch_thinking_off_description, title)
            return localized(R.string.transcript_model_switch_thinking_description, title, variant)
        }

    companion object {
        operator fun invoke(model: OpenCodeModelRef, previous: OpenCodeModelRef?, name: String?): ModelSwitchLabel {
            val level = level(model)
            val variant = when {
                level != null -> ModelVariant.label(level)
                previous != null && previous.sameModel(model) && level(previous) != null -> localized(R.string.transcript_model_switch_default)
                else -> null
            }
            return ModelSwitchLabel(localized(R.string.transcript_model_switch_title, name ?: model.id), variant, thinkingOff = level == "none")
        }

        private fun level(ref: OpenCodeModelRef): String? {
            val variant = ref.variant
            return if (variant.isNullOrEmpty() || variant == "default") null else variant
        }
    }
}

/**
 * Which providers have a mark of their own: the session's `assets/provider-icons/<id>.svg`, merged
 * into the app. Listed once per process.
 */
internal object ProviderMarks {
    @Volatile private var names: Set<String>? = null

    fun has(providerID: String, assets: AssetManager): Boolean {
        val known = names ?: (runCatching { assets.list("provider-icons")?.toSet() }.getOrNull().orEmpty())
            .map { it.removeSuffix(".svg") }.toSet().also { names = it }
        return providerID in known
    }
}

/**
 * A quiet centred rule where the session's model or thinking level changed, like compaction's, with
 * the provider's mark as on desktop. A long name truncates at its end, before the level, which
 * stays in view; accessibility sizes wrap.
 */
@Composable
fun ModelSwitchRule(label: ModelSwitchLabel, providerID: String, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    val iconSize = scaledDp(12f)
    val style = OpenCodeTextStyles.caption1.copy(color = colors.secondaryText)
    RuleRow(quaternaryLine(), modifier.clearAndSetSemantics { contentDescription = label.accessibilityLabel }, minLine = 16.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            // The provider's mark, or filled sparkles for one without a mark of its own.
            val mark = LocalTranscriptRowSlots.current.providerMark
            val assets = LocalContext.current.assets
            if (mark != null && ProviderMarks.has(providerID, assets)) {
                // A template image takes the row's secondary ink.
                CompositionLocalProvider(LocalContentColor provides colors.secondaryText) {
                    mark(providerID, Modifier.size(iconSize))
                }
            } else {
                OpenCodeIcon("sparkles", contentDescription = null, size = iconSize, tint = colors.secondaryText)
            }
            if (isAccessibilityTextSize()) {
                Text(listOfNotNull(label.title, label.variant).joinToString(" · "), style = style, textAlign = TextAlign.Center)
            } else {
                Row {
                    Text(label.title, Modifier.weight(1f, fill = false), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    label.variant?.let { Text(" · $it", style = style, maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}
