package ai.opencode.server.model

import ai.opencode.core.ui.appFormattingLocale
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.math.BigDecimal
import java.text.BreakIterator
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols

/** Any JSON value, decoded leniently. */
@Serializable(with = OpenCodeJSONValueSerializer::class)
sealed interface OpenCodeJSONValue {
    data object Null : OpenCodeJSONValue
    data class Bool(val value: Boolean) : OpenCodeJSONValue
    data class Number(val value: Double) : OpenCodeJSONValue
    data class Str(val value: String) : OpenCodeJSONValue
    data class Array(val values: List<OpenCodeJSONValue>) : OpenCodeJSONValue
    data class Object(val values: Map<String, OpenCodeJSONValue>) : OpenCodeJSONValue

    val obj: Map<String, OpenCodeJSONValue>? get() = (this as? Object)?.values
    val string: String? get() = (this as? Str)?.value

    /** Display text: scalars as text, structures as pretty JSON with sorted keys. */
    val displayText: String
        get() = when (this) {
            Null -> "null"
            is Bool -> value.toString()
            is Number -> formatGroupedNumber(value)
            is Str -> value
            is Array, is Object -> toJsonText(this, pretty = true, indent = "")
        }

    /** One-line JSON for structured tool arguments (sorted keys, unescaped slashes). */
    val compactText: String get() = toJsonText(this, pretty = false, indent = "")

    fun needsBounding(stringLimit: Int, collectionLimit: Int): Boolean = when (this) {
        Null, is Bool, is Number -> false
        is Str -> utf8Length(value) > stringLimit && value.characterPrefixEnd(stringLimit) != null
        is Array -> values.size > collectionLimit || values.any { it.needsBounding(stringLimit, collectionLimit) }
        is Object -> values.size > collectionLimit || values.values.any { it.needsBounding(stringLimit, collectionLimit) }
    }

    fun bounded(stringLimit: Int = 20_000, collectionLimit: Int = 100): OpenCodeJSONValue {
        if (!needsBounding(stringLimit, collectionLimit)) return this
        fun bound(value: OpenCodeJSONValue) = value.bounded(stringLimit, collectionLimit)
        return when (this) {
            is Str -> Str(value.bounded(stringLimit))
            is Array -> Array(values.take(collectionLimit).map(::bound))
            is Object ->
                if (values.size <= collectionLimit) Object(values.mapValues { bound(it.value) })
                // Keep a deterministic subset: the first keys in sorted order.
                else Object(values.entries.sortedBy { it.key }.take(collectionLimit).associate { it.key to bound(it.value) })
            else -> this
        }
    }

    companion object {
        fun of(element: JsonElement): OpenCodeJSONValue = when (element) {
            JsonNull -> Null
            is JsonPrimitive -> when {
                element.isString -> Str(element.content)
                element.booleanOrNull != null -> Bool(element.booleanOrNull!!)
                element.doubleOrNull != null -> Number(element.doubleOrNull!!)
                else -> Str(element.content)
            }
            is JsonArray -> Array(element.map(::of))
            is JsonObject -> Object(element.mapValues { of(it.value) })
        }

        fun toElement(value: OpenCodeJSONValue): JsonElement = when (value) {
            Null -> JsonNull
            is Bool -> JsonPrimitive(value.value)
            is Number -> JsonPrimitive(value.value)
            is Str -> JsonPrimitive(value.value)
            is Array -> JsonArray(value.values.map(::toElement))
            is Object -> JsonObject(value.values.mapValues { toElement(it.value) })
        }
    }
}

object OpenCodeJSONValueSerializer : KSerializer<OpenCodeJSONValue> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
    override fun deserialize(decoder: Decoder): OpenCodeJSONValue =
        OpenCodeJSONValue.of((decoder as JsonDecoder).decodeJsonElement())
    override fun serialize(encoder: Encoder, value: OpenCodeJSONValue) =
        (encoder as JsonEncoder).encodeJsonElement(OpenCodeJSONValue.toElement(value))
}

private fun toJsonText(value: OpenCodeJSONValue, pretty: Boolean, indent: String): String = when (value) {
    OpenCodeJSONValue.Null -> "null"
    is OpenCodeJSONValue.Bool -> value.value.toString()
    is OpenCodeJSONValue.Number -> jsonNumber(value.value)
    is OpenCodeJSONValue.Str -> jsonString(value.value)
    is OpenCodeJSONValue.Array ->
        if (value.values.isEmpty()) "[]"
        else if (!pretty) value.values.joinToString(",", "[", "]") { toJsonText(it, false, "") }
        else {
            val inner = "$indent  "
            value.values.joinToString(",\n", "[\n", "\n$indent]") { inner + toJsonText(it, true, inner) }
        }
    is OpenCodeJSONValue.Object -> {
        val entries = value.values.entries.sortedBy { it.key }
        if (entries.isEmpty()) "{}"
        else if (!pretty) entries.joinToString(",", "{", "}") { jsonString(it.key) + ":" + toJsonText(it.value, false, "") }
        else {
            val inner = "$indent  "
            entries.joinToString(",\n", "{\n", "\n$indent}") { inner + jsonString(it.key) + " : " + toJsonText(it.value, true, inner) }
        }
    }
}

private fun jsonNumber(value: Double): String =
    if (value.isFinite() && value == Math.rint(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString()
    else BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

private fun jsonString(text: String): String = buildString {
    append('"')
    for (c in text) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        '\b' -> append("\\b")
        '\u000C' -> append("\\f")
        else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
    }
    append('"')
}

/**
 * English in the user's region (see [appFormattingLocale]), grouped, and at most six fraction
 * digits, rounded half to even (`0.26575467567788` → "0.265755").
 */
internal fun formatGroupedNumber(value: Double): String =
    DecimalFormat("#,##0.######", DecimalFormatSymbols(appFormattingLocale())).format(value)

/** As [formatGroupedNumber], ungrouped. */
internal fun formatPlainNumber(value: Double): String =
    DecimalFormat("0.######", DecimalFormatSymbols(appFormattingLocale())).format(value)

internal fun utf8Length(text: String): Int {
    var count = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        count += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> { i++; 4 }
            else -> 3
        }
        i++
    }
    return count
}

val String.nonEmpty: String? get() = ifEmpty { null }

/** Up to the first line break, else the whole string. */
val String.firstLine: String
    get() {
        // LF, VT, FF, CR, NEL, LS, PS.
        val parts = split('\n', '\u000B', '\u000C', '\r', '\u2028', '\u2029', '\u0085').filter { it.isNotEmpty() }
        return parts.firstOrNull() ?: this
    }

/** At most [limit] characters, then a truncation marker. */
fun String.bounded(limit: Int): String {
    // UTF-8 length is never below the character count: a cheap first check.
    if (utf8Length(this) <= limit) return this
    val end = characterPrefixEnd(limit) ?: return this
    return substring(0, end) + "\n… output truncated"
}

/**
 * Where the first [limit] characters end, or null when the text has no more than [limit] of them.
 */
internal fun String.characterPrefixEnd(limit: Int): Int? {
    if (limit < 0 || codePointCount(0, length) <= limit) return null
    val characters = BreakIterator.getCharacterInstance()
    characters.setText(this)
    characters.first()
    val end = if (limit == 0) 0 else characters.next(limit)
    return if (end == BreakIterator.DONE || end >= length) null else end
}

internal fun String.trimmedWhitespace(): String = trim { it.isWhitespace() || Character.isSpaceChar(it) }
