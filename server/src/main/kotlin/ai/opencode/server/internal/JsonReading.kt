package ai.opencode.server.internal

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The wire codec. Unknown keys ignored, absent optionals decode as null, nil optionals are omitted
 * on encode.
 */
internal val WireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** A payload that doesn't have the required shape. */
internal class WireDecodingException(message: String) : RuntimeException(message)

internal fun JsonElement.obj(): JsonObject = this as? JsonObject ?: throw WireDecodingException("expected an object")
internal fun JsonElement.arr(): JsonArray = this as? JsonArray ?: throw WireDecodingException("expected an array")

internal fun JsonElement.str(): String {
    val primitive = this as? JsonPrimitive
    if (primitive == null || !primitive.isString) throw WireDecodingException("expected a string")
    return primitive.content
}

internal fun JsonElement.dbl(): Double {
    val primitive = this as? JsonPrimitive
    if (primitive == null || primitive.isString || primitive is JsonNull) throw WireDecodingException("expected a number")
    return primitive.doubleOrNull ?: throw WireDecodingException("expected a number")
}

/** An integer; an exactly integral number such as `3.0` reads as 3, like Foundation's decoder. */
internal fun JsonElement.lng(): Long {
    val primitive = this as? JsonPrimitive
    if (primitive == null || primitive.isString || primitive is JsonNull) throw WireDecodingException("expected an integer")
    primitive.longOrNull?.let { return it }
    val double = primitive.doubleOrNull ?: throw WireDecodingException("expected an integer")
    if (double != Math.rint(double) || !double.isFinite()) throw WireDecodingException("expected an integer")
    return double.toLong()
}

internal fun JsonElement.int(): Int {
    val value = lng()
    if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) throw WireDecodingException("integer out of range")
    return value.toInt()
}

internal fun JsonElement.bool(): Boolean {
    val primitive = this as? JsonPrimitive
    if (primitive == null || primitive.isString) throw WireDecodingException("expected a boolean")
    return primitive.booleanOrNull ?: throw WireDecodingException("expected a boolean")
}

/** `decode(_:forKey:)`: present and non-null, else throws. */
internal inline fun <T> JsonObject.req(key: String, read: (JsonElement) -> T): T {
    val value = this[key]
    if (value == null || value is JsonNull) throw WireDecodingException("missing $key")
    return read(value)
}

/** `decodeIfPresent(_:forKey:)`: absent or null is null; a present value of the wrong type throws. */
internal inline fun <T> JsonObject.opt(key: String, read: (JsonElement) -> T): T? {
    val value = this[key]
    if (value == null || value is JsonNull) return null
    return read(value)
}

/** `try? decodeIfPresent(...)`: absent, null, or malformed are all null. */
internal inline fun <T> JsonObject.lenient(key: String, read: (JsonElement) -> T): T? =
    try { opt(key, read) } catch (_: RuntimeException) { null }

internal fun JsonObject.reqString(key: String) = req(key) { it.str() }
internal fun JsonObject.optString(key: String) = opt(key) { it.str() }

internal fun <T> JsonElement.list(read: (JsonElement) -> T): List<T> = arr().map(read)

internal fun <T> JsonElement.stringMap(read: (JsonElement) -> T): Map<String, T> = obj().mapValues { read(it.value) }

/** A serializer over a `JsonElement` walker (custom `init(from:)` ports). */
internal open class ElementSerializer<T>(
    private val decode: (JsonElement) -> T,
    private val encode: (T) -> JsonElement,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
    override fun deserialize(decoder: Decoder): T = decode((decoder as JsonDecoder).decodeJsonElement())
    override fun serialize(encoder: Encoder, value: T) = (encoder as JsonEncoder).encodeJsonElement(encode(value))
}
