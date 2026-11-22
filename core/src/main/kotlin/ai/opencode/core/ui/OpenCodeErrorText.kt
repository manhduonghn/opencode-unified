package ai.opencode.core.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign

/** An error written next to what failed. TalkBack reads it when it appears. */
@Composable
fun OpenCodeErrorText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = OpenCodeTextStyles.footnote,
    color: Color = OpenCodeTheme.colors.danger,
    textAlign: TextAlign? = null,
) {
    Text(text, modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = style, color = color, textAlign = textAlign)
}
