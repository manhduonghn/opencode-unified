package ai.opencode.feature.composer.plusmenu

import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.composer.PlusMenuTunables
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

fun formatVideoDuration(seconds: Double): String {
    if (!seconds.isFinite()) return "0:00"
    val total = max(0, seconds.roundToInt())
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    if (hours > 0) return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
    return String.format(Locale.US, "%d:%02d", minutes, secs)
}

/** “▶ 0:50” in a video's corner on its composer thumbnail: a dark backdrop so it reads on light and dark frames; decorative. */
@Composable
fun VideoDurationBadge(duration: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(
                Color.Black.copy(alpha = PlusMenuTunables.VIDEO_BADGE_OPACITY.toFloat()),
                RoundedCornerShape(PlusMenuTunables.VIDEO_BADGE_RADIUS.dp),
            )
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OpenCodeIcon(SFSymbol.PlayFill, contentDescription = null, size = 8.dp, tint = Color.White)
        Text(
            duration,
            color = Color.White,
            style = OpenCodeTextStyles.caption2.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
        )
    }
}
