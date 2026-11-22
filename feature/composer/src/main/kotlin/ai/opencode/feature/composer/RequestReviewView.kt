package ai.opencode.feature.composer

import ai.opencode.core.R as CoreR
import ai.opencode.core.ui.OpenCodeErrorText
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeSheetHandle
import ai.opencode.core.ui.OpenCodeSwitch
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeSecondaryButton
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.appFormattingLocale
import ai.opencode.server.model.OpenCodeFormField
import ai.opencode.server.model.OpenCodeFormFieldType
import ai.opencode.server.model.OpenCodeFormOption
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeFormValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The fallback for a form too large for the dock (several fields, or an external step), as a
 * Material bottom sheet. Questions and permissions answer in the dock. The session presents it
 * while its `activePicker` is the requests sheet; [onClose] clears that.
 *
 * @param dismissMessage what the composer's row holds while answering (it goes with Dismiss, as with the panel's ×), else null.
 * @param onOpenURL opens an external step's link (through the session's URL-opening dependency).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestReviewSheet(
    dock: RequestDockFeature.State<*>,
    context: RequestDockContext,
    dismissMessage: String?,
    send: (RequestDockFeature.Action) -> Unit,
    onClose: () -> Unit,
    onOpenURL: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    // A new request opens at the medium detent.
    LaunchedEffect(dock.selectedRequestID) { if (sheetState.isVisible) sheetState.partialExpand() }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = OpenCodeTheme.colors.canvas,
        dragHandle = { OpenCodeSheetHandle() },
        shape = OpenCodeShape.sheetTop,
    ) {
        RequestReviewView(dock, context, dismissMessage, send, onClose, onOpenURL)
    }
}

/** The request sheet's content. */
@Composable
fun RequestReviewView(
    dock: RequestDockFeature.State<*>,
    context: RequestDockContext,
    dismissMessage: String?,
    send: (RequestDockFeature.Action) -> Unit,
    onClose: () -> Unit,
    onOpenURL: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OpenCodeTheme.colors
    val id = dock.selectedRequestID
    val form = dock.formRequests.firstOrNull { it.id == id }
    val responding = id != null && id in dock.respondingRequestIDs
    val fields = form?.activeFields(dock.formAnswers[id ?: ""] ?: emptyMap()) ?: emptyList()
    val focusManager = LocalFocusManager.current
    // Numeric answers exactly as typed, keyed by form and field: showing the parsed value instead would reformat "1." to "1" mid-entry.
    val numberDrafts = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(id) { focusManager.clearFocus() }

    Column(modifier.fillMaxWidth().background(colors.canvas).imePadding()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            OpenCodeIconButton(
                SFSymbol.Xmark,
                contentDescription = stringResource(CoreR.string.common_close),
                onClick = onClose,
                style = OpenCodeIconButtonStyle.Standard,
                modifier = Modifier.align(Alignment.CenterStart).testTag("request-review-close"),
            )
            Text(
                form?.title ?: "",
                style = OpenCodeTextStyles.headline,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 56.dp),
            )
        }
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (form != null) {
                for (field in fields) {
                    FormField(field, form, dock, responding, numberDrafts, send, onOpenURL)
                }
                RequestDockAnswer.origin(form, context.currentSessionID, context.familySessions)?.let { origin ->
                    Text(stringResource(R.string.composer_review_requested_by, origin), style = OpenCodeTextStyles.footnote, color = colors.secondaryText)
                }
            }
        }
        if (id != null && form != null) {
            Column(
                Modifier.fillMaxWidth().background(colors.canvas).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                dock.requestErrors[id]?.let { error ->
                    val title = dock.replyFailureTitle(id, isForm = true)
                    RequestErrorLine(title ?: error, detail = if (title != null) error else null)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OpenCodeSecondaryButton(
                        text = stringResource(CoreR.string.common_dismiss),
                        enabled = !responding,
                        onClick = {
                            focusManager.clearFocus()
                            // What the composer's row holds goes with it, as with the panel's ×.
                            send(RequestDockFeature.Action.FormCancelTapped(id, message = dismissMessage))
                        },
                        modifier = Modifier.testTag("request-sheet-dismiss"),
                    )
                    Spacer(Modifier.weight(1f))
                    RequestMonochromeButton(
                        enabled = !responding,
                        onClick = {
                            focusManager.clearFocus()
                            send(RequestDockFeature.Action.FormSubmitTapped(id))
                        },
                        modifier = Modifier.semantics { contentDescription = "Send" }.testTag("request-send"),
                    ) {
                        if (responding) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(CoreR.string.common_send), style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FormField(
    field: OpenCodeFormField,
    form: OpenCodeFormRequest,
    dock: RequestDockFeature.State<*>,
    responding: Boolean,
    numberDrafts: MutableMap<String, String>,
    send: (RequestDockFeature.Action) -> Unit,
    onOpenURL: (String) -> Unit,
) {
    val colors = OpenCodeTheme.colors
    val answers = dock.formAnswers[form.id] ?: emptyMap()
    val options = RequestDockAnswer.options(field)
    val enabled = !responding
    fun set(value: OpenCodeFormValue?) = send(RequestDockFeature.Action.FormValueChanged(form.id, field.key, value))

    Column(Modifier.fillMaxWidth()) {
        val title = field.title
        if (!title.isNullOrEmpty()) {
            Text(title, style = OpenCodeTextStyles.headline, color = colors.text)
        } else if (field.description.isNullOrEmpty()) {
            Text(field.key, style = OpenCodeTextStyles.headline, color = colors.text)
        }
        field.description?.let {
            Text(it, style = OpenCodeTextStyles.body, color = colors.secondaryText, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        }
        when (field.type) {
            OpenCodeFormFieldType.Boolean -> OptionRows(options, field, form.id, answers[field.key], dock, enabled, ::set)
            OpenCodeFormFieldType.External -> {
                field.url?.let { url ->
                    OpenCodeSecondaryButton(text = stringResource(R.string.composer_review_open_step), enabled = enabled, onClick = { onOpenURL(url) })
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.composer_review_completed), style = OpenCodeTextStyles.body, color = colors.text, modifier = Modifier.weight(1f))
                    OpenCodeSwitch(
                        checked = answers[field.key] == OpenCodeFormValue.Boolean(true),
                        onCheckedChange = { set(OpenCodeFormValue.Boolean(it)) },
                        enabled = enabled,
                    )
                }
            }
            OpenCodeFormFieldType.Multiselect -> {
                Text(stringResource(R.string.composer_request_choose_multiple), style = OpenCodeTextStyles.footnote, color = colors.secondaryText)
                OptionRows(options, field, form.id, answers[field.key], dock, enabled, ::set)
                if (field.custom == true) {
                    AnswerField(
                        hasOptions = options.isNotEmpty(),
                        multiple = true,
                        text = RequestDockAnswer.customText(answers[field.key], options),
                        enabled = enabled,
                    ) { text ->
                        val values = RequestDockAnswer.knownValues(dock.formAnswers[form.id]?.get(field.key), field) +
                            (if (text.isEmpty()) emptyList() else listOf(text))
                        set(OpenCodeFormValue.Strings(values))
                    }
                }
            }
            OpenCodeFormFieldType.String, OpenCodeFormFieldType.Number, OpenCodeFormFieldType.Integer -> {
                OptionRows(options, field, form.id, answers[field.key], dock, enabled, ::set)
                if (options.isEmpty() || field.custom == true) {
                    val draftKey = "${form.id}/${field.key}"
                    val value = answers[field.key]
                    val text = when (value) {
                        is OpenCodeFormValue.Str -> RequestDockAnswer.customText(value, options)
                        is OpenCodeFormValue.Number -> numberDrafts[draftKey] ?: formatTypedNumber(value.value)
                        else -> numberDrafts[draftKey] ?: ""
                    }
                    AnswerField(
                        hasOptions = options.isNotEmpty(),
                        text = text,
                        enabled = enabled,
                        keyboardType = if (field.type == OpenCodeFormFieldType.String) KeyboardType.Text else KeyboardType.Decimal,
                    ) { typed ->
                        if (field.type == OpenCodeFormFieldType.String) {
                            set(OpenCodeFormValue.Str(typed))
                        } else {
                            numberDrafts[draftKey] = typed
                            set(OpenCodeFormValue.typedNumber(typed))
                        }
                    }
                }
            }
        }
        val value = answers[field.key]
        if (value != null) {
            field.validationError(value)?.let { OpenCodeErrorText(it) }
        }
    }
}

@Composable
private fun OptionRows(
    options: List<OpenCodeFormOption>,
    field: OpenCodeFormField,
    formID: String,
    current: OpenCodeFormValue?,
    dock: RequestDockFeature.State<*>,
    enabled: Boolean,
    set: (OpenCodeFormValue?) -> Unit,
) {
    val multiple = field.type == OpenCodeFormFieldType.Multiselect
    options.forEachIndexed { index, option ->
        if (index > 0) RequestOptionRowDivider()
        val selected = RequestDockAnswer.isSelected(option.value, current)
        // The dock's own rows: a monochrome mark on the title's baseline.
        RequestOptionRow(option.label, option.description, selected, multiple, enabled = enabled) {
            // Re-choosing a single choice changes nothing.
            if (multiple || !selected) dependency(HapticsClient).play(Haptic.selection)
            val latest = dock.formAnswers[formID]?.get(field.key)
            set(RequestDockAnswer.pickedValue(option.value, field, latest))
        }
    }
}

/** Your own answer, in the dock's words. */
@Composable
private fun AnswerField(
    hasOptions: Boolean,
    text: String,
    enabled: Boolean,
    multiple: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    val colors = OpenCodeTheme.colors
    val placeholder = if (hasOptions) (if (multiple) stringResource(R.string.composer_review_add_own_placeholder) else stringResource(R.string.composer_review_own_words_placeholder)) else stringResource(R.string.composer_review_answer_placeholder)
    val answerDescription = stringResource(R.string.composer_review_answer_description)
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = colors.border)
        TextField(
            value = text,
            onValueChange = onChange,
            enabled = enabled,
            textStyle = OpenCodeTextStyles.body.copy(color = colors.text),
            placeholder = { Text(placeholder, style = OpenCodeTextStyles.body, color = colors.tertiaryText) },
            minLines = 1,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { contentDescription = answerDescription },
        )
    }
}

/** A number answer as shown back in its field. */
private fun formatTypedNumber(value: Double): String =
    java.text.DecimalFormat("#,##0.######", java.text.DecimalFormatSymbols.getInstance(appFormattingLocale())).format(value)
