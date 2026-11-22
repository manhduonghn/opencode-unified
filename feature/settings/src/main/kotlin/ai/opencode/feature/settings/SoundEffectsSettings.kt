package ai.opencode.feature.settings

import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.sound.soundEffectsSend
import ai.opencode.core.sound.soundEffectsVolume
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.appFormattingLocale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * Beneath the Sound Effects switch while it's on, how loud the sounds play (0–100 % in tenths, each
 * new level plays a sample) and whether a send plays one.
 */
@Composable
fun SoundEffectsSettingsRows(send: (HomeSettingsFeature.Action) -> Unit) {
    val volume by remember { shared(SharedKeys.soundEffectsVolume).flow }.collectAsState()
    val sendSound by remember { shared(SharedKeys.soundEffectsSend).flow }.collectAsState()
    val percent = remember(volume) {
        NumberFormat.getPercentInstance(appFormattingLocale()).apply { maximumFractionDigits = 0 }.format(volume)
    }
    val colors = OpenCodeTheme.colors
    val volumeDescription = stringResource(R.string.settings_sound_effects_volume_description)
    Column(Modifier.fillMaxWidth()) {
        SettingsValueRow(stringResource(R.string.settings_sound_effects_volume), percent)
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OpenCodeIcon(SFSymbol.SpeakerFill, contentDescription = null, tint = colors.secondaryText)
            Slider(
                value = volume.toFloat(),
                onValueChange = { send(HomeSettingsFeature.Action.SoundEffectsVolumeChanged((it * 10).roundToInt() / 10.0)) },
                valueRange = 0f..1f,
                steps = 9,
                colors = SliderDefaults.colors(
                    thumbColor = colors.selection,
                    activeTrackColor = colors.selection,
                    activeTickColor = colors.onSelection.copy(alpha = 0.6f),
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    inactiveTickColor = colors.secondaryText.copy(alpha = 0.6f),
                ),
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = volumeDescription
                        stateDescription = percent
                    },
            )
            OpenCodeIcon(SFSymbol.SpeakerWave3Fill, contentDescription = null, tint = colors.secondaryText)
        }
    }
    SettingsDivider(inset = false)
    SettingsToggleRow(
        title = stringResource(R.string.settings_sound_effects_send_sound),
        checked = sendSound,
        onCheckedChange = { send(HomeSettingsFeature.Action.SoundEffectsSendChanged(it)) },
    )
}
