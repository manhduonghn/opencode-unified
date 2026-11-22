package ai.opencode.feature.settings

import ai.opencode.core.ui.OpenCodeErrorText
import ai.opencode.core.ui.OpenCodeTextField
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.diagnostics.HidesFromScreenshots
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.voice.VoiceProvider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ai.opencode.core.ui.OpenCodeFonts
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * The selected model's provider key, its reveal button and Verify, under Grok's xAI Key row, and on
 * Your OpenAI Account. Kept out of every screenshot.
 */
@Composable
internal fun VoiceKeyEditor(state: VoiceSettingsFeature.State, voice: VoiceStore, settings: (HomeSettingsFeature.Action) -> Unit) {
    val key = state.voiceEngine.apiKey(state.openAIAPIKey, state.xaiAPIKey).orEmpty()
    val provider = state.activeProvider
    val stacked = isAccessibilitySize()
    val revealDescription = if (state.revealsKey) stringResource(R.string.settings_voice_key_hide_description) else stringResource(R.string.settings_voice_key_show_description)
    // The key is a credential.
    HidesFromScreenshots()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val keyInput = @Composable { fieldModifier: Modifier ->
            OpenCodeTextField(
                value = key,
                onValueChange = { value ->
                    voice.send(VoiceSettingsFeature.Action.ApiKeyEdited(value))
                    val saved = value.trim().ifEmpty { null }
                    when (provider) {
                        VoiceProvider.openAI -> settings(HomeSettingsFeature.Action.OpenAIAPIKeyChanged(saved))
                        VoiceProvider.xAI -> settings(HomeSettingsFeature.Action.XaiAPIKeyChanged(saved))
                    }
                },
                placeholder = provider.keyPlaceholder,
                singleLine = true,
                visualTransformation = if (state.revealsKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                textStyle = if (state.revealsKey) OpenCodeTextStyles.subheadline.copy(fontFamily = OpenCodeFonts.monospace) else SettingsText.title,
                trailingIcon = {
                    Box(
                        Modifier
                            .clickable(role = Role.Button) { voice.send(VoiceSettingsFeature.Action.ToggleRevealKeyTapped) }
                            .openCodeTapTarget()
                            .semantics { contentDescription = revealDescription },
                        contentAlignment = Alignment.Center,
                    ) {
                        OpenCodeIcon(if (state.revealsKey) SFSymbol.EyeSlash else SFSymbol.Eye, contentDescription = null, tint = OpenCodeTheme.colors.secondaryText)
                    }
                },
                modifier = fieldModifier,
            )
        }
        val verifyButton = @Composable {
            FilledTonalButton(
                onClick = { voice.send(VoiceSettingsFeature.Action.VerifyKeyTapped) },
                enabled = state.activeAPIKey.isNotEmpty() && !state.isCheckingKey,
                shape = OpenCodeShape.control,
            ) { Text(stringResource(R.string.settings_voice_key_verify)) }
        }
        if (stacked) {
            // Verify goes under the field.
            keyInput(Modifier.fillMaxWidth())
            verifyButton()
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                keyInput(Modifier.weight(1f))
                verifyButton()
            }
        }
        state.keyVerificationError?.let { error ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OpenCodeIcon(SFSymbol.ExclamationmarkCircleFill, contentDescription = null, size = 16.dp, tint = OpenCodeTheme.colors.danger)
                SelectionContainer {
                    OpenCodeErrorText(error)
                }
            }
        }
    }
}
