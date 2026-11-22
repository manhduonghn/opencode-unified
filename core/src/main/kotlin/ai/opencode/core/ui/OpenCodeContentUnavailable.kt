package ai.opencode.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A screen or pane with nothing to show: it failed to load, it's empty, or a search found nothing.
 * A large glyph, a title, an optional description and an optional action, centred. It wraps its
 * content, so a caller that owns the whole pane passes `Modifier.fillMaxSize()`.
 */
@Composable
fun OpenCodeContentUnavailable(
    symbol: SFSymbol,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OpenCodeIcon(symbol, contentDescription = null, size = OpenCodeIconSize.hero, tint = OpenCodeTheme.colors.secondaryText)
        Spacer(Modifier.height(12.dp))
        Text(
            title,
            style = OpenCodeTextStyles.title3.copy(fontWeight = FontWeight.Bold),
            color = OpenCodeTheme.colors.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        if (description != null) {
            Spacer(Modifier.height(6.dp))
            Text(description, style = OpenCodeTextStyles.subheadline, color = OpenCodeTheme.colors.secondaryText, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            OpenCodePrimaryButton(text = action, onClick = onAction)
        }
    }
}
