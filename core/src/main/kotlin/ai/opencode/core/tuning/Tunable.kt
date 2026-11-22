package ai.opencode.core.tuning

import ai.opencode.core.motion.MotionSpring
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.truncate
import kotlin.random.Random

/**
 * A value to dial in live, same keys and defaults. Keep each declaration on ONE line
 * (`Tunable("key", "label", default = …`) so `scripts/tune.py bake` can rewrite its default.
 */
class Tunable<T> @PublishedApi internal constructor(
    val key: String,
    val label: String,
    val defaultValue: T,
    val range: ClosedFloatingPointRange<Double>?,
    val step: Double?,
    @PublishedApi internal val type: TunableType<T>,
) {
    /** The live value (the default while tuning is off). */
    val value: T get() = TuningStore.shared.value(this)

    val descriptor: TunableDescriptor by lazy {
        TunableDescriptor(
            key = key,
            label = label,
            kind = type.kind,
            valueType = type.name,
            defaultValue = type.encode(defaultValue),
            range = range,
            step = step,
            choices = type.choices,
        )
    }

    fun preset(value: T): TunablePresetValue = TunablePresetValue(descriptor, type.encode(value))

    override fun toString(): String = "Tunable($key)"
}

fun Tunable(key: String, label: String, default: Double, range: ClosedFloatingPointRange<Double>? = null, step: Double? = null): Tunable<Double> =
    Tunable(key, label, default, range, step, TunableType.DoubleType)

fun Tunable(key: String, label: String, default: Float, range: ClosedFloatingPointRange<Double>? = null, step: Double? = null): Tunable<Float> =
    Tunable(key, label, default, range, step, TunableType.FloatType)

fun Tunable(key: String, label: String, default: Dp, range: ClosedFloatingPointRange<Double>? = null, step: Double? = null): Tunable<Dp> =
    Tunable(key, label, default, range, step, TunableType.DpType)

fun Tunable(key: String, label: String, default: Int, range: IntRange? = null, step: Int? = null): Tunable<Int> =
    Tunable(key, label, default, range?.let { it.first.toDouble()..it.last.toDouble() }, step?.toDouble(), TunableType.IntType)

fun Tunable(key: String, label: String, default: Boolean): Tunable<Boolean> =
    Tunable(key, label, default, null, null, TunableType.BooleanType)

/** A string enum as a tunable: `enum class EdgeStyle(override val rawValue: String) : TunableChoice { Soft("soft"), Hard("hard") }`. */
inline fun <reified E> Tunable(key: String, label: String, default: E): Tunable<E> where E : Enum<E>, E : TunableChoice =
    Tunable(key, label, default, null, null, TunableType.choice(enumValues<E>().toList(), E::class.java.simpleName))

/** A string enum a [Tunable] can hold. [rawValue] is the wire and storage form. */
interface TunableChoice {
    val rawValue: String
}

/** What a tunable type is on the wire. */
class TunableType<T> @PublishedApi internal constructor(
    val kind: TuneKind,
    /** The declared type ("Double", "Float", "Dp", "Int", "Boolean", or the enum's name). */
    val name: String,
    val choices: List<String>?,
    val encode: (T) -> TuneValue,
    val decode: (TuneValue) -> T?,
) {
    companion object {
        val DoubleType = TunableType<Double>(TuneKind.Number, "Double", null, { TuneValue.Number(it) }, { (it as? TuneValue.Number)?.value })
        val FloatType = TunableType<Float>(TuneKind.Number, "Float", null, { TuneValue.Number(it.toDouble()) }, { (it as? TuneValue.Number)?.value?.toFloat() })
        val DpType = TunableType<Dp>(TuneKind.Number, "Dp", null, { TuneValue.Number(it.value.toDouble()) }, { (it as? TuneValue.Number)?.value?.toFloat()?.dp })
        val IntType = TunableType<Int>(TuneKind.Number, "Int", null, { TuneValue.Number(it.toDouble()) }, { value ->
            (value as? TuneValue.Number)?.value?.takeIf { it.isFinite() }?.let { swiftRounded(it).toInt() }
        })
        val BooleanType = TunableType<Boolean>(TuneKind.Bool, "Boolean", null, { TuneValue.Bool(it) }, { (it as? TuneValue.Bool)?.value })

        @PublishedApi
        internal fun <E : TunableChoice> choice(cases: List<E>, name: String): TunableType<E> =
            TunableType(TuneKind.Choice, name, cases.map { it.rawValue }, { TuneValue.Choice(it.rawValue) }, { value ->
                (value as? TuneValue.Choice)?.let { choice -> cases.firstOrNull { it.rawValue == choice.value } }
            })
    }
}

/** `<key>.duration` and `<key>.bounce`. Reads as the core [MotionSpring] (exact Compose spring). */
class TunableSpring(key: String, label: String, duration: Double, bounce: Double) {
    val duration: Tunable<Double> = Tunable("$key.duration", "$label duration", default = duration, range = 0.05..2.0, step = 0.01)
    val bounce: Tunable<Double> = Tunable("$key.bounce", "$label bounce", default = bounce, range = -0.5..0.9, step = 0.01)

    val spring: MotionSpring get() = MotionSpring(duration.value, bounce.value)

    fun <T> spec(visibilityThreshold: T? = null): SpringSpec<T> = spring.spec(visibilityThreshold)

    fun <T> animation(reduceMotion: Boolean, delaySeconds: Double = 0.0, visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring.animation(reduceMotion, delaySeconds, visibilityThreshold)
}

/** Turns a camelCase identifier segment into Title Case words: `"catchUp"` reads `"Catch Up"`. */
object TuningTitle {
    fun format(segment: String): String {
        val words = StringBuilder()
        for (character in segment) {
            if (character.isUpperCase() && words.isNotEmpty()) words.append(' ')
            words.append(character)
        }
        return words.toString().replaceFirstChar { it.uppercase(Locale.ROOT) }
    }
}

/**
 * A screen, for the tuning panel, which lists only its groups. Name one with
 * `TuningContextEffect(context)`.
 */
@Serializable
data class TuningContext(val id: String, val label: String? = null, val groups: List<String> = listOf(id)) {
    val displayTitle: String get() = label ?: TuningTitle.format(id)

    /** Its groups, then the global ones every screen lists ([globalGroups]). */
    val panelGroups: List<String>
        get() {
            val result = ArrayList(groups)
            for (group in globalGroups) {
                if (group !in groups) result += group
            }
            return result
        }

    companion object {
        /**
         * Groups every screen lists after its own. The motion roles move everything, so they're
         * tunable wherever you are.
         */
        val globalGroups = listOf("motion")

        val session = TuningContext("session", groups = listOf("session", "motion", "composer", "modelPicker", "projectPicker", "call", "transcript", "numbers", "launch", "mark"))
        val motion = TuningContext("motion")
        val drawer = TuningContext("drawer")
        val catchUp = TuningContext("catchUp")
        val settings = TuningContext("settings")
        val transcriptPlayground = TuningContext("transcriptPlayground", label = "Transcript", groups = listOf("transcript", "session", "composer", "launch"))
        val sessionListPlayground = TuningContext("sessionListPlayground", label = "Session List", groups = listOf("drawer", "catchUp", "launch"))
        val catchUpPlayground = TuningContext("catchUpPlayground", label = "Catch Up", groups = listOf("catchUp", "drawer"))
        val motionPlayground = TuningContext("motionPlayground", label = "Motion", groups = listOf("motion"))
        val composerPlayground = TuningContext("composerPlayground", label = "Composer", groups = listOf("launch", "composer", "modelPicker", "projectPicker", "drawer", "transcript", "call"))
    }
}

/** What a tunable holds on the wire and in storage: a JSON number, boolean or string. */
@Serializable(with = TuneValueSerializer::class)
sealed interface TuneValue {
    data class Number(val value: Double) : TuneValue
    data class Bool(val value: Boolean) : TuneValue
    data class Choice(val value: String) : TuneValue
}

object TuneValueSerializer : KSerializer<TuneValue> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: TuneValue) {
        val json = encoder as JsonEncoder
        json.encodeJsonElement(
            when (value) {
                is TuneValue.Number -> JsonPrimitive(value.value)
                is TuneValue.Bool -> JsonPrimitive(value.value)
                is TuneValue.Choice -> JsonPrimitive(value.value)
            },
        )
    }

    override fun deserialize(decoder: Decoder): TuneValue {
        val element = (decoder as JsonDecoder).decodeJsonElement() as? JsonPrimitive
            ?: throw kotlinx.serialization.SerializationException("TuneValue must be a primitive")
        if (!element.isString) {
            element.booleanOrNull?.let { return TuneValue.Bool(it) }
            element.doubleOrNull?.let { return TuneValue.Number(it) }
        }
        return TuneValue.Choice(element.content)
    }
}

@Serializable
enum class TuneKind {
    @SerialName("number") Number,
    @SerialName("bool") Bool,
    @SerialName("choice") Choice,
}

/** A tunable's metadata: what the panel and a remote page show. */
data class TunableDescriptor(
    val key: String,
    val label: String,
    val kind: TuneKind,
    val valueType: String,
    val defaultValue: TuneValue,
    val range: ClosedFloatingPointRange<Double>?,
    val step: Double?,
    val choices: List<String>?,
) {
    /** The key's first segment: "drawer.edge.blurRadius" is in "drawer". */
    val group: String get() = key.substringBefore('.')

    /** The key's second segment, the panel section ("edge"), or null for a two-part key. */
    val section: String? get() = key.split('.').takeIf { it.size > 2 }?.get(1)

    private val isInt: Boolean get() = valueType == "Int"

    /**
     * [value] made valid for this tunable: numbers clamped to the range and whole for whole types,
     * choices one of the cases. Null when the kind is wrong.
     */
    fun accepting(value: TuneValue): TuneValue? = when {
        kind == TuneKind.Number && value is TuneValue.Number -> {
            var number = value.value
            if (!number.isFinite()) {
                null
            } else {
                range?.let { number = min(max(number, it.start), it.endInclusive) }
                // A slider's 0.05 + 25 × 0.01 is 0.30000000000000004: keep it 0.3.
                number = if (isInt) swiftRounded(number) else swiftRounded(number * 1e9) / 1e9
                TuneValue.Number(number)
            }
        }
        kind == TuneKind.Bool && value is TuneValue.Bool -> value
        kind == TuneKind.Choice && value is TuneValue.Choice -> if (choices?.contains(value.value) == true) value else null
        else -> null
    }

    fun isDefault(value: TuneValue): Boolean = TuningPresets.same(value, defaultValue)

    /** The value as a Kotlin literal for this type (for Copy as Kotlin). */
    fun kotlinLiteral(value: TuneValue): String = when (value) {
        is TuneValue.Choice -> "$valueType.${choiceCase(value.value)}"
        is TuneValue.Number -> when (valueType) {
            "Int" -> valueText(value)
            "Float" -> decimalWithPoint(value.value) + "f"
            "Dp" -> decimalWithPoint(value.value) + ".dp"
            else -> decimalWithPoint(value.value)
        }
        is TuneValue.Bool -> valueText(value)
    }

    private fun choiceCase(raw: String): String = raw.replaceFirstChar { it.uppercase(Locale.ROOT) }

    /** The value as Copy Values and `tune.py` write it: numbers, `true`/`false`, or the choice's raw name. */
    fun valueText(value: TuneValue): String = when (value) {
        is TuneValue.Number -> if (isInt) swiftRounded(value.value).toLong().toString() else decimal(value.value)
        is TuneValue.Bool -> if (value.value) "true" else "false"
        is TuneValue.Choice -> value.value
    }

    /** The declared range, or a sensible span around the default when none was declared. */
    val effectiveRange: ClosedFloatingPointRange<Double>
        get() {
            range?.let { return it }
            val fallback = (defaultValue as? TuneValue.Number)?.value ?: return 0.0..1.0
            if (fallback == 0.0) return 0.0..1.0
            return min(0.0, fallback * 2)..max(1.0, fallback * 2)
        }

    private class Grid(val count: Int, val valueAt: (Int) -> Double)

    /** Discrete step grid (`0..count`) for integer or stepped tunables, or null when continuous. */
    private val steppedGrid: Grid?
        get() {
            val bounds = effectiveRange
            if (isInt) {
                val stepSize = max(1, swiftRounded(step ?: 1.0).toInt())
                val low = ceil(bounds.start).toInt()
                val high = floor(bounds.endInclusive).toInt()
                if (high < low) return Grid(-1) { bounds.start }
                return Grid(max(0, (high - low) / stepSize)) { (low + it * stepSize).toDouble() }
            }
            val step = step
            if (step != null && step > 0) {
                val count = max(0, floor((bounds.endInclusive - bounds.start) / step + 1e-9).toInt())
                return Grid(count) { min(bounds.start + it * step, bounds.endInclusive) }
            }
            return null
        }

    /** Whether this tunable has more than one valid value. */
    val hasMultipleValues: Boolean
        get() = when (kind) {
            TuneKind.Number -> {
                val bounds = effectiveRange
                bounds.endInclusive > bounds.start && (steppedGrid?.let { it.count > 0 } ?: true)
            }
            TuneKind.Bool -> true
            TuneKind.Choice -> (choices?.size ?: 0) > 1
        }

    /** A random valid value within the range, snapped to the step (or a random choice). */
    fun randomValue(random: Random = Random.Default): TuneValue = when (kind) {
        TuneKind.Number -> {
            val bounds = effectiveRange
            val grid = steppedGrid
            when {
                bounds.endInclusive <= bounds.start -> accepting(TuneValue.Number(bounds.start)) ?: defaultValue
                grid != null && grid.count < 0 -> accepting(TuneValue.Number(bounds.start)) ?: defaultValue
                grid != null -> accepting(TuneValue.Number(grid.valueAt(random.nextInt(0, grid.count + 1)))) ?: defaultValue
                else -> {
                    val raw = random.nextDouble(bounds.start, bounds.endInclusive)
                    accepting(TuneValue.Number(swiftRounded(raw * 100) / 100)) ?: defaultValue
                }
            }
        }
        TuneKind.Bool -> TuneValue.Bool(random.nextBoolean())
        TuneKind.Choice -> choices?.takeIf { it.isNotEmpty() }?.let { TuneValue.Choice(it.random(random)) } ?: defaultValue
    }

    /** A valid value different from [current] when possible, so a roll never lands where it started. */
    fun alternateValue(current: TuneValue, random: Random = Random.Default): TuneValue {
        if (!hasMultipleValues) return current
        return when {
            kind == TuneKind.Bool && current is TuneValue.Bool -> TuneValue.Bool(!current.value)
            kind == TuneKind.Choice && current is TuneValue.Choice ->
                choices.orEmpty().filter { it != current.value }.randomOrNull(random)?.let { TuneValue.Choice(it) } ?: current
            kind == TuneKind.Number -> {
                repeat(16) {
                    val candidate = randomValue(random)
                    if (!TuningPresets.same(candidate, current)) return candidate
                }
                val bounds = effectiveRange
                val grid = steppedGrid
                val first = grid?.valueAt(0) ?: bounds.start
                val last = grid?.valueAt(grid.count) ?: bounds.endInclusive
                val fallback = if (TuningPresets.same(current, TuneValue.Number(last))) first else last
                accepting(TuneValue.Number(fallback)) ?: current
            }
            else -> randomValue(random)
        }
    }

    companion object {
        /** Shortest form that reads back as the same number to 4 places: 28, 0.7, 0.3125. */
        fun decimal(number: Double): String {
            val rounded = swiftRounded(number * 10_000) / 10_000
            if (rounded == Math.rint(rounded) && abs(rounded) < 1e15) return rounded.toLong().toString()
            return String.format(Locale.ROOT, "%.4f", rounded).trimEnd('0')
        }

        /** [decimal], always with a decimal point (a Kotlin Double literal): 28.0, 0.7. */
        fun decimalWithPoint(number: Double): String = decimal(number).let { if (it.contains('.')) it else "$it.0" }
    }
}

/** One tunable's value in a [TuningPreset]: `DrawerTunables.rowHeight.preset(40.0)`. */
data class TunablePresetValue(val descriptor: TunableDescriptor, val value: TuneValue) {
    val key: String get() = descriptor.key
}

/** A named variant: the values it sets. A key it doesn't name is at its default while applied. */
data class TuningPreset(val name: String, val values: List<TunablePresetValue>) {
    constructor(name: String, vararg values: TunablePresetValue) : this(name, values.toList())
}

/**
 * Variants of a set of tunables, picked by number. Applying one sets every key any of them names,
 * so switching leaves nothing behind.
 */
data class TuningPresets(
    /** Its first segment is the panel group it shows in. */
    val key: String,
    val label: String,
    val presets: List<TuningPreset>,
    /** Extra tunables every preset keeps at their default (and applying a preset resets). */
    val resetting: List<TunableDescriptor> = emptyList(),
) {
    val group: String get() = key.substringBefore('.')

    /** Every tunable a preset names (or resets): in the order the fullest preset names them, then any the others add. */
    val descriptors: List<TunableDescriptor> by lazy {
        val result = mutableListOf<TunableDescriptor>()
        val fullestFirst = presets.withIndex().sortedWith(compareByDescending<IndexedValue<TuningPreset>> { it.value.values.size }.thenBy { it.index })
        for (value in fullestFirst.flatMap { it.value.values }) {
            if (result.none { it.key == value.key }) result += value.descriptor
        }
        for (descriptor in resetting) if (result.none { it.key == descriptor.key }) result += descriptor
        result
    }

    val keys: List<String> get() = descriptors.map { it.key }

    /** What applying [index] sets each key to: null is its default. */
    fun changes(applying: Int): Map<String, TuneValue?> {
        if (applying !in presets.indices) return emptyMap()
        val named = presets[applying].values.associate { it.key to it.value }
        return keys.associateWith { named[it] }
    }

    /** The preset these overrides match, if any (absent is its default). */
    fun matching(overrides: Map<String, TuneValue>): Int? = presets.indices.firstOrNull { index ->
        val changes = changes(index)
        descriptors.all { descriptor ->
            val current = overrides[descriptor.key] ?: descriptor.defaultValue
            val wanted = changes[descriptor.key] ?: descriptor.defaultValue
            same(current, wanted)
        }
    }

    companion object {
        fun same(a: TuneValue, b: TuneValue): Boolean =
            if (a is TuneValue.Number && b is TuneValue.Number) abs(a.value - b.value) < 1e-9 else a == b
    }
}

/** To nearest, halves away from zero. */
internal fun swiftRounded(value: Double): Double {
    if (!value.isFinite()) return value
    // Exact: `value - whole` never rounds, unlike `floor(value + 0.5)` (0.49999999999999994 → 1).
    val whole = truncate(value)
    return if (abs(value - whole) >= 0.5) whole + sign(value) else whole
}
