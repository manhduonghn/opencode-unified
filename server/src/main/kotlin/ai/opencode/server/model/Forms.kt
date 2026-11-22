package ai.opencode.server.model

import ai.opencode.core.strings.localized
import ai.opencode.core.strings.localizedPlural
import ai.opencode.core.ui.appFormattingLocale
import ai.opencode.server.R
import ai.opencode.server.internal.ElementSerializer
import ai.opencode.server.internal.arr
import ai.opencode.server.internal.str
import ai.opencode.server.internal.UrlParts
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.text.DecimalFormatSymbols
import java.time.OffsetDateTime
import java.util.Locale

/** A form answer. Decodes: Bool, then number, then string, else a string list. */
@Serializable(with = OpenCodeFormValueSerializer::class)
sealed interface OpenCodeFormValue {
    data class Str(val value: String) : OpenCodeFormValue
    data class Number(val value: Double) : OpenCodeFormValue
    data class Boolean(val value: kotlin.Boolean) : OpenCodeFormValue
    data class Strings(val values: List<String>) : OpenCodeFormValue

    companion object {
        /**
         * A number as typed in [locale]: grouping separators ignored, a trailing decimal
         * separator reads as the whole number. Blank or unreadable text is no answer.
         */
        fun typedNumber(text: String, locale: Locale = appFormattingLocale()): OpenCodeFormValue? {
            val symbols = DecimalFormatSymbols.getInstance(locale)
            var cleaned = text.trim()
            cleaned = cleaned.replace(symbols.groupingSeparator.toString(), "")
            if (symbols.decimalSeparator != '.') cleaned = cleaned.replace(symbols.decimalSeparator, '.')
            if (cleaned.isEmpty()) return null
            if (cleaned.endsWith(".")) cleaned = cleaned.dropLast(1)
            if (cleaned.isEmpty() || cleaned.last() in "dDfF") return null
            return cleaned.toDoubleOrNull()?.let(::Number)
        }
    }
}

internal object OpenCodeFormValueSerializer : ElementSerializer<OpenCodeFormValue>(
    decode = { element ->
        val primitive = element as? JsonPrimitive
        when {
            primitive != null && !primitive.isString && primitive.booleanOrNull != null -> OpenCodeFormValue.Boolean(primitive.booleanOrNull!!)
            primitive != null && !primitive.isString && primitive.doubleOrNull != null -> OpenCodeFormValue.Number(primitive.doubleOrNull!!)
            primitive != null && primitive.isString -> OpenCodeFormValue.Str(primitive.content)
            else -> OpenCodeFormValue.Strings(element.arr().map { it.str() })
        }
    },
    encode = { value ->
        when (value) {
            is OpenCodeFormValue.Str -> JsonPrimitive(value.value)
            is OpenCodeFormValue.Number -> JsonPrimitive(value.value)
            is OpenCodeFormValue.Boolean -> JsonPrimitive(value.value)
            is OpenCodeFormValue.Strings -> JsonArray(value.values.map(::JsonPrimitive))
        }
    },
)

@Serializable
data class OpenCodeFormOption(val value: String, val label: String, val description: String? = null) {
    val id: String get() = value
}

@Serializable
data class OpenCodeFormCondition(val key: String, val op: Operation, val value: OpenCodeFormValue) {
    @Serializable
    enum class Operation { @SerialName("eq") Eq, @SerialName("neq") Neq }
}

@Serializable
enum class OpenCodeFormFieldType {
    @SerialName("string") String,
    @SerialName("number") Number,
    @SerialName("integer") Integer,
    @SerialName("boolean") Boolean,
    @SerialName("multiselect") Multiselect,
    @SerialName("external") External,
}

@Serializable
data class OpenCodeFormField(
    val key: String,
    val type: OpenCodeFormFieldType,
    val title: String? = null,
    val description: String? = null,
    val required: Boolean? = null,
    val `when`: List<OpenCodeFormCondition>? = null,
    val format: String? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val pattern: String? = null,
    val options: List<OpenCodeFormOption>? = null,
    val custom: Boolean? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val minItems: Int? = null,
    val maxItems: Int? = null,
    val url: String? = null,
    @SerialName("default") val defaultValue: OpenCodeFormValue? = null,
) {
    val id: String get() = key

    fun isVisible(answers: Map<String, OpenCodeFormValue>): Boolean = (`when` ?: emptyList()).all { condition ->
        val answer = answers[condition.key] ?: return@all false
        val expected = condition.value
        val matches = if (answer is OpenCodeFormValue.Strings && expected is OpenCodeFormValue.Str) {
            expected.value in answer.values
        } else answer == expected
        if (condition.op == OpenCodeFormCondition.Operation.Neq) !matches else matches
    }

    fun validationError(value: OpenCodeFormValue?): kotlin.String? {
        if (type == OpenCodeFormFieldType.External && value != OpenCodeFormValue.Boolean(true)) return localized(R.string.server_form_complete_step)
        if (required == true && value == null) return localized(R.string.server_form_required)
        value ?: return null
        when {
            type == OpenCodeFormFieldType.String && value is OpenCodeFormValue.Str -> {
                val text = value.value
                val length = text.codePointCount(0, text.length)
                if (required == true && text.trimmedWhitespace().isEmpty()) return localized(R.string.server_form_required)
                minLength?.let { if (length < it) return localizedPlural(R.plurals.server_form_min_length, it, it) }
                maxLength?.let { if (length > it) return localizedPlural(R.plurals.server_form_max_length, it, it) }
                pattern?.let { if (runCatching { Regex(it).containsMatchIn(text) }.getOrDefault(false).not()) return localized(R.string.server_form_invalid_format) }
                options?.let { opts -> if (custom != true && opts.none { it.value == text }) return localized(R.string.server_form_choose_option) }
                if (format == "email" && !text.contains("@")) return localized(R.string.server_form_invalid_email)
                if (format == "uri" && UrlParts.parse(text)?.scheme == null) return localized(R.string.server_form_invalid_url)
                if (format == "date" && !ISO_DATE.matches(text)) return localized(R.string.server_form_invalid_date)
                if (format == "date-time" && runCatching { OffsetDateTime.parse(text) }.isFailure) return localized(R.string.server_form_invalid_date_time)
            }
            (type == OpenCodeFormFieldType.Number || type == OpenCodeFormFieldType.Integer) && value is OpenCodeFormValue.Number -> {
                val number = value.value
                minimum?.let { if (number < it) return localized(R.string.server_form_minimum, formatGroupedNumber(it)) }
                maximum?.let { if (number > it) return localized(R.string.server_form_maximum, formatGroupedNumber(it)) }
                if (type == OpenCodeFormFieldType.Integer && Math.rint(number) != number) return localized(R.string.server_form_whole_number)
            }
            type == OpenCodeFormFieldType.Multiselect && value is OpenCodeFormValue.Strings -> {
                if (required == true && value.values.isEmpty()) return localized(R.string.server_form_required)
                minItems?.let { if (value.values.size < it) return localizedPlural(R.plurals.server_form_min_items, it, it) }
                maxItems?.let { if (value.values.size > it) return localizedPlural(R.plurals.server_form_max_items, it, it) }
            }
            (type == OpenCodeFormFieldType.Boolean || type == OpenCodeFormFieldType.External) && value is OpenCodeFormValue.Boolean -> Unit
            else -> return localized(R.string.server_form_invalid_value)
        }
        return null
    }

    private companion object {
        val ISO_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    }
}

@Serializable
data class OpenCodeFormRequest(
    val id: String,
    val sessionID: String,
    val title: String,
    val fields: List<OpenCodeFormField>,
    val metadata: Map<String, OpenCodeJSONValue>? = null,
) {
    val isQuestion: Boolean get() = metadata?.get("kind")?.string == "question"
    val isGlobal: Boolean get() = sessionID == "global"
    val mcpServer: String? get() = metadata?.get("server")?.string?.trimmedWhitespace()?.nonEmpty

    val defaultAnswers: Map<String, OpenCodeFormValue>
        get() = fields.mapNotNull { field ->
            field.defaultValue?.let { field.key to it }
                ?: if (field.type == OpenCodeFormFieldType.External) field.key to OpenCodeFormValue.Boolean(false) else null
        }.toMap()

    fun activeFields(answers: Map<String, OpenCodeFormValue>): List<OpenCodeFormField> {
        val active = mutableListOf<OpenCodeFormField>()
        val activeAnswers = answers.toMutableMap()
        for (field in fields) {
            if (field.isVisible(activeAnswers)) active += field else activeAnswers.remove(field.key)
        }
        return active
    }

    data class ValidationError(val message: String, /** The failing field's position among the active fields. */ val fieldIndex: Int)

    fun validatedAnswer(answers: Map<String, OpenCodeFormValue>): Result<Map<String, OpenCodeFormValue>> {
        val active = activeFields(answers)
        for ((index, field) in active.withIndex()) {
            field.validationError(answers[field.key])?.let {
                return Result.failure(ValidationFailure(ValidationError(it, index)))
            }
        }
        return Result.success(active.mapNotNull { field -> answers[field.key]?.let { field.key to it } }.toMap())
    }

    class ValidationFailure(val error: ValidationError) : Exception(error.message)
}
