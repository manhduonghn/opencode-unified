package ai.opencode.feature.session

import ai.opencode.core.strings.localized
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

object StarterPromptTunables {
    /** Header to prompts. */
    const val headerGap = 2f

    /** Prompts to projects. */
    const val listGap = 12f
}

/**
 * The first computer's ready session: questions any project can answer from its files alone, so the
 * first send shows the agent reading and replying.
 */
val SessionFeature.State.Companion.firstSessionStarters: List<String>
    get() = listOf(
        localized(R.string.session_starter_project_purpose),
        localized(R.string.session_starter_code_tour),
        localized(R.string.session_starter_recent_changes),
    )

/**
 * Once there's something to send, the starter prompts retire for good: erasing it doesn't bring
 * them back.
 */
val SessionFeature.State.composingRetiresStarterPrompts: Boolean
    get() = starterPrompts.isNotEmpty() && hasPromptContent

/**
 * Starter prompts above a draft's recent projects. A tap types the prompt into the composer for the
 * person to send; composing, they fade away.
 */
@Composable
fun StarterPromptList(prompts: List<String>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val iconWidth = with(LocalDensity.current) { (22 * fontScale).dp }
    Column(modifier.testTag("starter-prompts"), verticalArrangement = Arrangement.spacedBy(StarterPromptTunables.headerGap.dp)) {
        Text(
            stringResource(R.string.session_starter_prompts_title),
            style = OpenCodeTextStyles.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = OpenCodeTheme.colors.secondaryText,
            modifier = Modifier.padding(start = 17.dp).semantics { heading() },
        )
        for (prompt in prompts) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.session_starter_prompt_pick_label)) { onPick(prompt) }
                    // The bubble lines up with the project folders and the composer's +.
                    .padding(start = 17.dp, end = 16.dp)
                    .testTag("starter-prompt"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.width(iconWidth), contentAlignment = Alignment.Center) {
                    OpenCodeIcon(SFSymbol.TextBubble, contentDescription = null, size = 22.dp, tint = OpenCodeTheme.colors.secondaryText)
                }
                Text(prompt, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
