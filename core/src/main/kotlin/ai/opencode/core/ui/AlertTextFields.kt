package ai.opencode.core.ui

import ai.opencode.core.R
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * Text fields in alerts (Rename Session, Rename Server, Paste the Code) show a clear button.
 * Compose has no such rule, so each alert's field passes [clearButton] as its `trailingIcon`.
 */
object AlertTextFields {
    /** The field's trailing clear (×) button, shown while the field holds text. */
    fun clearButton(value: String, onClear: () -> Unit): (@Composable () -> Unit)? {
        if (value.isEmpty()) {
            return null
        }
        return {
            OpenCodeIconButton(
                SFSymbol.XmarkCircleFill,
                contentDescription = stringResource(R.string.core_alert_text_field_clear_description),
                onClick = onClear,
                style = OpenCodeIconButtonStyle.Standard,
                iconSize = OpenCodeIconSize.small,
            )
        }
    }
}
