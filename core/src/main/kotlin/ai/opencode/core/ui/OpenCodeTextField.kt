package ai.opencode.core.ui

import ai.opencode.core.R
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The desktop's text input (`packages/ui` `forms/text-input`) at touch size: a 48 dp field with
 * 8 dp corners, the page's own fill (a step up in dark) and a hairline edge, a 1.5 dp accent
 * ring while focused, the danger colour when [isError], half opacity when disabled. The label
 * sits above the field rather than floating inside it, and the placeholder shows whenever the
 * field is empty.
 *
 * A drop-in for Material's `OutlinedTextField` built on [BasicTextField]: the label, placeholder
 * and supporting text are part of the field's decoration, so TalkBack reads them with it as it
 * does Material's, and IME, selection and autofill behave the same.
 */
@Composable
fun OpenCodeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
    supportingText: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    textStyle: TextStyle = OpenCodeTextFieldDefaults.textStyle,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val colors = OpenCodeTextFieldDefaults.colors()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fieldErrorSemantics(isError, supportingText, stringResource(R.string.core_text_field_invalid_input_description)),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle.copy(color = colors.text),
        cursorBrush = SolidColor(if (isError) colors.error else colors.cursor),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        interactionSource = source,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        decorationBox = { innerTextField ->
            FieldDecoration(
                innerTextField = innerTextField,
                isEmpty = value.isEmpty(),
                label = label,
                placeholder = placeholder,
                enabled = enabled,
                isError = isError,
                supportingText = supportingText,
                leadingIcon = leadingIcon,
                trailingIcon = trailingIcon,
                singleLine = singleLine,
                textStyle = textStyle,
                interactionSource = source,
            )
        },
    )
}

/** [OpenCodeTextField] for a [TextFieldValue] (caret and selection held by the caller). */
@Composable
fun OpenCodeTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
    supportingText: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    textStyle: TextStyle = OpenCodeTextFieldDefaults.textStyle,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val colors = OpenCodeTextFieldDefaults.colors()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fieldErrorSemantics(isError, supportingText, stringResource(R.string.core_text_field_invalid_input_description)),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle.copy(color = colors.text),
        cursorBrush = SolidColor(if (isError) colors.error else colors.cursor),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        interactionSource = source,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        decorationBox = { innerTextField ->
            FieldDecoration(
                innerTextField = innerTextField,
                isEmpty = value.text.isEmpty(),
                label = label,
                placeholder = placeholder,
                enabled = enabled,
                isError = isError,
                supportingText = supportingText,
                leadingIcon = leadingIcon,
                trailingIcon = trailingIcon,
                singleLine = singleLine,
                textStyle = textStyle,
                interactionSource = source,
            )
        },
    )
}

/**
 * A search field in the same container as [OpenCodeTextField]: a muted magnifier leading, the
 * placeholder as its prompt. The model pickers' custom search rows use [openCodeFieldContainer]
 * for the same look.
 */
@Composable
fun OpenCodeSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailingIcon: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    OpenCodeTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = placeholder,
        leadingIcon = {
            OpenCodeIcon(
                SFSymbol.Magnifyingglass,
                contentDescription = null,
                size = OpenCodeTextFieldDefaults.iconSize,
                tint = OpenCodeTextFieldDefaults.colors().icon,
            )
        },
        trailingIcon = trailingIcon,
        singleLine = true,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
    )
}

/**
 * The field container on its own, for a custom field row (a search capsule with its own clear
 * button): fill, hairline (or the focus / error ring), 8 dp corners, and the light-mode lift.
 */
@Composable
fun Modifier.openCodeFieldContainer(
    isFocused: Boolean,
    isError: Boolean = false,
    shape: Shape = OpenCodeTextFieldDefaults.shape,
): Modifier {
    val colors = OpenCodeTextFieldDefaults.colors()
    val borderColor by animateColorAsState(
        when {
            isError -> colors.error
            isFocused -> colors.focusedBorder
            else -> colors.border
        },
        tween(FieldMetrics.ANIMATION_MILLIS),
        label = "fieldBorder",
    )
    val borderWidth by animateDpAsState(
        if (isFocused) FieldMetrics.focusedBorderWidth else FieldMetrics.borderWidth,
        tween(FieldMetrics.ANIMATION_MILLIS),
        label = "fieldBorderWidth",
    )
    var lift: Modifier = Modifier
    if (colors.shadow != Color.Transparent && !isFocused && !isError) {
        lift = Modifier.shadow(FieldMetrics.elevation, shape, clip = false, ambientColor = colors.shadow, spotColor = colors.shadow)
    }
    return this
        .then(lift)
        .background(colors.container, shape)
        .border(borderWidth, borderColor, shape)
}

/** [OpenCodeTextField]'s geometry, type and colours. */
object OpenCodeTextFieldDefaults {
    val shape: Shape = OpenCodeShape.control
    val minHeight: Dp = 48.dp
    val horizontalPadding: Dp = 12.dp
    val iconSize: Dp = 18.dp

    val textStyle: TextStyle
        @Composable @ReadOnlyComposable get() = OpenCodeTextStyles.callout

    val labelStyle: TextStyle
        @Composable @ReadOnlyComposable get() = OpenCodeTextStyles.footnote.copy(fontWeight = FontWeight.Medium)

    @Composable
    @ReadOnlyComposable
    fun colors(): OpenCodeTextFieldColors {
        val colors = OpenCodeTheme.colors
        val scheme = MaterialTheme.colorScheme
        // Light: the page's own white with the desktop's soft lift. Dark: a step up from the sheet.
        val container = if (colors.isDark) {
            lerp(scheme.surfaceContainerLow, scheme.surfaceContainerHigh, 0.5f)
        } else {
            scheme.surfaceContainerLowest
        }
        val border = if (colors.strongBorders) colors.tertiaryText else scheme.outline
        val shadow = if (colors.isDark) Color.Transparent else Color.Black.copy(alpha = 0.25f)
        return OpenCodeTextFieldColors(
            container = container,
            border = border,
            focusedBorder = colors.focusRing,
            error = scheme.error,
            text = colors.text,
            label = colors.secondaryText,
            placeholder = colors.tertiaryText,
            icon = colors.tertiaryText,
            cursor = scheme.primary,
            shadow = shadow,
        )
    }
}

/** [OpenCodeTextField]'s resolved colours for the current theme and appearance. */
class OpenCodeTextFieldColors(
    val container: Color,
    val border: Color,
    val focusedBorder: Color,
    val error: Color,
    val text: Color,
    val label: Color,
    val placeholder: Color,
    val icon: Color,
    val cursor: Color,
    /** The light-mode lift under an idle field; transparent in dark. */
    val shadow: Color,
)

@Composable
private fun FieldDecoration(
    innerTextField: @Composable () -> Unit,
    isEmpty: Boolean,
    label: String?,
    placeholder: String?,
    enabled: Boolean,
    isError: Boolean,
    supportingText: String?,
    leadingIcon: (@Composable () -> Unit)?,
    trailingIcon: (@Composable () -> Unit)?,
    singleLine: Boolean,
    textStyle: TextStyle,
    interactionSource: MutableInteractionSource,
) {
    val colors = OpenCodeTextFieldDefaults.colors()
    val focused by interactionSource.collectIsFocusedAsState()
    Column(
        Modifier.alpha(if (enabled) 1f else FieldMetrics.DISABLED_ALPHA),
        verticalArrangement = Arrangement.spacedBy(FieldMetrics.labelSpacing),
    ) {
        if (label != null) {
            Text(label, style = OpenCodeTextFieldDefaults.labelStyle, color = colors.label)
        }
        val verticalPadding = if (singleLine) 0.dp else FieldMetrics.multilineVerticalPadding
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = OpenCodeTextFieldDefaults.minHeight)
                .openCodeFieldContainer(isFocused = focused && enabled, isError = isError),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
        ) {
            if (leadingIcon != null) {
                Box(
                    Modifier
                        .heightIn(min = OpenCodeTextFieldDefaults.minHeight)
                        .padding(start = OpenCodeTextFieldDefaults.horizontalPadding, end = FieldMetrics.iconSpacing),
                    contentAlignment = Alignment.Center,
                ) { leadingIcon() }
            }
            val start = if (leadingIcon != null) 0.dp else OpenCodeTextFieldDefaults.horizontalPadding
            val end = if (trailingIcon != null) 0.dp else OpenCodeTextFieldDefaults.horizontalPadding
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = OpenCodeTextFieldDefaults.minHeight)
                    .padding(start = start, end = end, top = verticalPadding, bottom = verticalPadding),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (isEmpty && placeholder != null) {
                    Text(
                        placeholder,
                        style = textStyle,
                        color = colors.placeholder,
                        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                    )
                }
                innerTextField()
            }
            if (trailingIcon != null) {
                Box(
                    Modifier.heightIn(min = OpenCodeTextFieldDefaults.minHeight),
                    contentAlignment = Alignment.Center,
                ) { trailingIcon() }
            }
        }
        if (supportingText != null) {
            Text(
                supportingText,
                style = OpenCodeTextStyles.footnote,
                color = if (isError) colors.error else colors.label,
            )
        }
    }
}

/** Material's error semantics: TalkBack announces the error with the field. */
private fun Modifier.fieldErrorSemantics(isError: Boolean, supportingText: String?, defaultError: String): Modifier {
    if (!isError) return this
    return semantics { error(supportingText ?: defaultError) }
}

private object FieldMetrics {
    val borderWidth: Dp = 1.dp
    val focusedBorderWidth: Dp = 1.5.dp
    val elevation: Dp = 1.dp
    val labelSpacing: Dp = 6.dp
    val iconSpacing: Dp = 8.dp
    val multilineVerticalPadding: Dp = 13.dp
    const val ANIMATION_MILLIS = 120
    const val DISABLED_ALPHA = 0.5f
}
