package ai.opencode.core.ui

import ai.opencode.core.motion.MotionTunables
import ai.opencode.core.tuning.AnimationTimeScale
import ai.opencode.core.tuning.swiftRounded
import java.text.BreakIterator
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Motion parameters for [RollingNumber]. */
data class RollingNumberStyle(
    /** Perceptual roll duration in seconds (`stiffness = (2π / d)²`). */
    val duration: Double = MotionTunables.layoutSpring.duration.defaultValue,
    /** 0 is critically damped; positive values (≤ 0.3) allow a subtle overshoot. */
    val bounce: Double = MotionTunables.layoutSpring.bounce.defaultValue,
    /** Smear fast-moving reels vertically. */
    val motionBlur: Boolean = true,
    /** Multiplier on the vertical smear at peak speed (24 rows/s). */
    val blurAmount: Double = 1.0,
    /** Top and bottom edge alpha fade as a fraction of line height. */
    val edgeFade: Float = 0.18f,
    /** Cascade delay in seconds between newly added digit places, keyed from the right. */
    val stagger: Double = MotionTunables.staggerStep.defaultValue,
    /** Keypad mode: typing or deleting a trailing digit (`12 → 120`) keeps leading digits in place. */
    val preservesTypedPrefix: Boolean = false,
) {
    companion object {
        /** The baked defaults. */
        val Native = RollingNumberStyle()
    }
}

/** Glyph metrics a [RollingNumberModel] lays out with, in px. */
interface RollingNumberMetrics {
    val lineHeight: Float
    val ascender: Float

    /** Advance width of one glyph (a code point as a string). */
    fun advance(glyph: String): Float

    /** Proportional digits interpolate width between adjacent wheel faces; monospaced stay constant. */
    fun advance(reel: Double): Float {
        val low = floor(reel).toInt()
        val t = (reel - low).toFloat()
        val a = advance(rollingDigitGlyph(low))
        val b = advance(rollingDigitGlyph(low + 1))
        return a + (b - a) * t
    }
}

/** One drawn frame. */
class RollingNumberFrame(
    val slots: List<Slot>,
    val width: Float,
    /** `(x0, x1, radius)` triples: horizontal bands smeared vertically. */
    val blurBands: FloatArray,
    val isSettled: Boolean,
) {
    sealed interface Content {
        /** A digit wheel at fractional face [position] (`3.5` is halfway from 3 to 4). */
        data class Reel(val position: Double) : Content

        /** A non-digit cross-fading from [from] to [to]. */
        data class Symbol(val from: String?, val to: String?, val progress: Double) : Content
    }

    data class Slot(
        val x: Float,
        val width: Float,
        val opacity: Double,
        /** Vertical rise as a fraction of line height. */
        val rise: Double,
        /** Arrival/exit blur radius (px). */
        val blur: Float,
        /** Vertical smear radius from reel speed (px), the band radius for this slot. */
        val smear: Float,
        val velocity: Double,
        val content: Content,
    )
}

/** The per-place spring model behind [RollingNumber]. */
class RollingNumberModel {
    /** Stable identity per token across one or more numeric runs in the string. */
    sealed class Key : Comparable<Key> {
        data class Prefix(val run: Int, val offset: Int) : Key()

        /** Integer digit place within [run]: 0 = ones, 1 = tens, 2 = hundreds. */
        data class Integer(val run: Int, val place: Int) : Key()

        /** Grouping separator immediately left of [place] (`,` before place 3 in `1,000`). */
        data class Group(val run: Int, val place: Int) : Key()
        data class Point(val run: Int) : Key()
        data class Fraction(val run: Int, val offset: Int) : Key()
        data class Suffix(val offset: Int) : Key()

        private val sortTuple: IntArray
            get() = when (this) {
                is Prefix -> intArrayOf(run, 0, offset, 0)
                is Integer -> intArrayOf(run, 1, -place, 1)
                is Group -> intArrayOf(run, 1, -place, 2)
                is Point -> intArrayOf(run, 2, 0, 0)
                is Fraction -> intArrayOf(run, 3, offset, 0)
                is Suffix -> intArrayOf(Int.MAX_VALUE, 0, offset, 0)
            }

        override fun compareTo(other: Key): Int {
            val a = sortTuple
            val b = other.sortTuple
            for (i in a.indices) if (a[i] != b[i]) return a[i].compareTo(b[i])
            return 0
        }
    }

    /** Semi-implicit Euler spring stepped at 240 Hz. */
    class Spring(var value: Double, var target: Double, var velocity: Double = 0.0) {
        val isSettled: Boolean get() = abs(value - target) < 0.0005 && abs(velocity) < 0.005

        fun step(dt: Double, stiffness: Double, damping: Double) {
            val acceleration = -stiffness * (value - target) - damping * velocity
            velocity += acceleration * dt
            value += velocity * dt
        }
    }

    class Slot(var key: Key, val isDigit: Boolean) {
        var reel = Spring(0.0, 0.0)
        var from: String? = null
        var to: String? = null
        var fade = Spring(1.0, 1.0)
        var presence = Spring(1.0, 1.0)
        var delay = 0.0
        var removing = false
    }

    var slots: List<Slot> = emptyList()
        private set
    private var lastStep: Double? = null
    private var stiffness = 0.0
    private var damping = 0.0
    var isMoving = false
        private set
    var shownText: String? = null
        private set

    /**
     * Brings the model to [text], rolling when [animated], else snapping. [nowSeconds] is on the
     * frame clock passed to [frame].
     */
    fun sync(text: String, locale: Locale, animated: Boolean, style: RollingNumberStyle, nowSeconds: Double) {
        if (text == shownText && !(!animated && isMoving)) return
        retarget(text, locale, nowSeconds, animated, style)
    }

    fun retarget(text: String, locale: Locale, at: Double, animated: Boolean, style: RollingNumberStyle) {
        val d = max(style.duration, 0.05)
        stiffness = (2 * PI / d).pow(2)
        damping = 4 * PI * (1 - style.bounce.coerceIn(0.0, 0.9)) / d

        val parsed = parse(text, locale)
        val previous = shownText
        try {
            if (!animated || previous == null) {
                slots = parsed.tokens.map { (key, glyph) ->
                    Slot(key, glyph.isAsciiDigit).apply {
                        glyph.asciiDigit?.let { reel = Spring(it.toDouble(), it.toDouble()) }
                        from = glyph
                        to = glyph
                    }
                }
                isMoving = false
                lastStep = null
                return
            }
            if (previous == text) return

            // Optional keypad-append preservation (`12 → 120` typing digits onto the right).
            if (style.preservesTypedPrefix && isWhole(previous) && isWhole(text) &&
                previous.length != text.length && (text.startsWith(previous) || previous.startsWith(text))
            ) {
                val shift = text.length - previous.length
                for (slot in slots) {
                    val key = slot.key
                    if (key is Key.Integer) slot.key = Key.Integer(key.run, key.place + shift)
                }
            }

            val previousParsed = parse(previous, locale)
            val existing = HashMap<Key, Slot>()
            for (slot in slots) existing[slot.key] = slot
            val next = ArrayList<Slot>()
            val arrivals = HashSet<Key>()

            for ((key, glyph) in parsed.tokens) {
                val runIndex = runIndex(key)
                val prevMag = runIndex?.let { previousParsed.magnitudes.getOrNull(it) } ?: 0.0
                val nextMag = runIndex?.let { parsed.magnitudes.getOrNull(it) } ?: 0.0
                val goingUp = nextMag >= prevMag
                val slot = existing[key]
                if (slot != null && slot.isDigit == glyph.isAsciiDigit) {
                    slot.removing = false
                    slot.presence.target = 1.0
                    val digit = glyph.asciiDigit
                    if (digit != null) {
                        slot.reel.target = unwrap(digit.toDouble(), slot.reel.value, goingUp)
                    } else if (slot.to != glyph) {
                        slot.from = if (slot.fade.value > 0.5) slot.to else slot.from
                        slot.to = glyph
                        slot.fade = Spring(0.0, 1.0)
                    }
                    next += slot
                } else {
                    next += Slot(key, glyph.isAsciiDigit).apply {
                        glyph.asciiDigit?.let { reel = Spring(it.toDouble(), it.toDouble()) }
                        to = glyph
                        fade = Spring(0.0, 1.0)
                        presence = Spring(0.0, 1.0)
                    }
                    arrivals += key
                }
            }

            val nextKeys = next.mapTo(HashSet()) { it.key }
            for (slot in slots) {
                if (slot.key in nextKeys) continue
                slot.removing = true
                slot.presence.target = 0.0
                next += slot
            }
            next.sortBy { it.key }

            // Right-to-left stagger across changing and new places within each run. Digits
            // already in flight keep delay 0 so they never pause.
            val rankByRun = HashMap<Int, Int>()
            for (i in next.indices.reversed()) {
                val slot = next[i]
                slot.delay = 0.0
                if (slot.removing) continue
                val shouldStagger = slot.key in arrivals ||
                    (slot.isDigit && abs(slot.reel.target - slot.reel.value) > 0.001 && abs(slot.reel.velocity) < 0.01)
                if (!shouldStagger) continue
                val run = runIndex(slot.key) ?: -1
                val rank = rankByRun[run] ?: 0
                rankByRun[run] = rank + 1
                slot.delay = min(style.duration * 0.30, rank * style.stagger)
            }
            slots = next
            isMoving = true
            lastStep = at
        } finally {
            shownText = text
        }
    }

    /** Steps the springs to [atSeconds] and lays the slots out. */
    fun frame(
        atSeconds: Double,
        metrics: RollingNumberMetrics,
        style: RollingNumberStyle,
        targetWidth: Float? = null,
        alignment: RollingNumberAlignment = RollingNumberAlignment.Start,
    ): RollingNumberFrame {
        step(atSeconds)
        var x = 0f
        val out = ArrayList<RollingNumberFrame.Slot>(slots.size)
        val bands = ArrayList<Float>()
        var settled = true

        for (slot in slots) {
            val presence = slot.presence.value.coerceIn(0.0, 1.0)
            if (!(slot.delay <= 0 && slot.reel.isSettled && slot.fade.isSettled && slot.presence.isSettled)) settled = false
            val full: Float
            val content: RollingNumberFrame.Content
            if (slot.isDigit) {
                full = metrics.advance(slot.reel.value)
                content = RollingNumberFrame.Content.Reel(slot.reel.value)
            } else {
                val a = slot.from?.let(metrics::advance) ?: 0f
                val b = slot.to?.let(metrics::advance) ?: 0f
                val t = slot.fade.value.coerceIn(0.0, 1.0)
                full = a + (b - a) * t.toFloat()
                content = RollingNumberFrame.Content.Symbol(slot.from, slot.to, t)
            }
            val width = full * presence.toFloat()
            // Fresh grouping separators hold slightly longer so a comma doesn't appear before its digit rises.
            val hold = if (slot.key is Key.Group) 0.20 else 0.14
            val shown = if (slot.removing) max(0.0, presence - 0.45) / 0.55 else max(0.0, presence - hold) / (1 - hold)
            val rise = when {
                slot.removing -> (1 - presence) * 0.28
                slot.isDigit -> (1 - shown) * 0.24
                else -> 0.0
            }
            val blur = if (style.motionBlur) ((1 - shown) * metrics.lineHeight * 0.09).toFloat() else 0f
            val speed = if (slot.isDigit) abs(slot.reel.velocity) else 0.0
            var smear = 0f
            if (style.motionBlur && slot.isDigit) {
                // Smear envelope from 4 to 24 rows/s (`@kitlangton/rolling-number`'s blurEnvelope).
                val amount = ((speed - 4) / 20).coerceIn(0.0, 1.0)
                if (amount > 0.02) {
                    smear = (amount * metrics.lineHeight * 0.12 * style.blurAmount).toFloat()
                    bands += x
                    bands += x + width
                    bands += smear
                }
            }
            out += RollingNumberFrame.Slot(x, width, shown, rise, blur, smear, speed, content)
            x += width
        }

        val canvasWidth = max(max(x, targetWidth ?: x), 1f)
        val xOffset = when (alignment) {
            RollingNumberAlignment.End -> canvasWidth - x
            RollingNumberAlignment.Center -> (canvasWidth - x) / 2
            RollingNumberAlignment.Start -> 0f
        }
        val shifted = if (abs(xOffset) > 0.001f) out.map { it.copy(x = it.x + xOffset) } else out
        if (abs(xOffset) > 0.001f) {
            var i = 0
            while (i < bands.size) {
                bands[i] += xOffset
                bands[i + 1] += xOffset
                i += 3
            }
        }

        if (settled) {
            slots = slots.filterNot { it.removing }
            for (slot in slots) {
                val digit = rollingWrappedDigit(swiftRounded(slot.reel.target).toInt()).toDouble()
                slot.reel = Spring(digit, digit)
                slot.from = slot.to
            }
            isMoving = false
        }
        return RollingNumberFrame(shifted, x, bands.toFloatArray(), settled)
    }

    private fun step(atSeconds: Double) {
        val last = lastStep
        if (!isMoving || last == null) {
            lastStep = atSeconds
            return
        }
        var elapsed = AnimationTimeScale.shared.scaledDelta((atSeconds - last).coerceIn(0.0, 1.0 / 20.0))
        lastStep = atSeconds
        val dt = 1.0 / 240.0
        while (elapsed > 0) {
            val h = min(dt, elapsed)
            elapsed -= h
            for (slot in slots) {
                if (slot.delay > 0) {
                    slot.delay = max(0.0, slot.delay - h)
                } else {
                    slot.reel.step(h, stiffness, damping)
                    slot.fade.step(h, stiffness * 1.6, 2 * sqrt(stiffness * 1.6))
                    slot.presence.step(h, stiffness, 2 * sqrt(stiffness))
                }
            }
        }
    }

    class Parsed(val tokens: List<Pair<Key, String>>, val magnitudes: List<Double>)

    companion object {
        /** Width of [text] at rest, rounded up to the pixel grid to avoid truncation. */
        fun targetWidth(text: String, metrics: RollingNumberMetrics, locale: Locale): Float {
            val width = parse(text, locale).tokens.sumOf { metrics.advance(it.second).toDouble() }
            return ceil(width - 0.001).toFloat()
        }

        /** Rolling supports ASCII digits 0–9 without right-to-left text or bidi controls. */
        fun isRollable(text: String): Boolean {
            if (text.isEmpty()) return false
            var index = 0
            while (index < text.length) {
                val cp = text.codePointAt(index)
                index += Character.charCount(cp)
                if (cp in 0x0590..0x08FF || cp in 0x200E..0x200F || cp in 0x202A..0x202E ||
                    cp in 0x2066..0x2069 || cp in 0xFB1D..0xFEFF
                ) return false
                // Non-ASCII decimal digits (e.g. Eastern Arabic) stay static localized text.
                if (Character.isDigit(cp) && cp !in 0x30..0x39) return false
            }
            // A digit carrying a combining mark (`1⃣`) is not a digit.
            return text.characterGlyphs().any { it.isAsciiDigit }
        }

        /**
         * Unwraps [digit] (0..9) relative to [value]: rolls in the number's direction of change
         * when at most 5 faces away, otherwise the shorter way so `15 → 8` never passes 0.
         */
        fun unwrap(digit: Double, value: Double, up: Boolean): Double {
            val rounded = swiftRounded(value)
            if (abs(rounded - value) < 0.0001 && rollingWrappedDigit(rounded.toInt()) == digit.toInt()) return rounded
            val base = floor(value / 10) * 10
            var above = base + digit
            while (above < value - 0.0001) above += 10
            if (above - value >= 10) above -= 10
            val below = above - 10
            val preferred = if (up) above else below
            val other = if (up) below else above
            return if (abs(preferred - value) <= 5) preferred
            else if (abs(other - value) < abs(preferred - value)) other else preferred
        }

        private fun isWhole(text: String) = text.isNotEmpty() && text.all { it in '0'..'9' }

        private fun runIndex(key: Key): Int? = when (key) {
            is Key.Integer -> key.run
            is Key.Group -> key.run
            is Key.Point -> key.run
            is Key.Fraction -> key.run
            is Key.Prefix, is Key.Suffix -> null
        }

        /**
         * Parses one or more numeric runs (`"1,234.56"`, `"64%"`, `"1 subagent · 2 shells"`),
         * keying integer places from the right of each run and separators by the place they precede.
         */
        fun parse(text: String, locale: Locale): Parsed {
            val chars = text.characterGlyphs()
            val symbols = DecimalFormatSymbols.getInstance(locale)
            val decimalChar = symbols.decimalSeparator.toString()
            val groupChar = symbols.groupingSeparator.toString()

            fun isGrouping(i: Int): Boolean {
                val c = chars[i]
                if (c == decimalChar) return false
                if (!(c == groupChar || c == "," || c == "\u00A0" || c == "\u202F" || c == "'" || c == "\u2019")) return false
                if (i <= 0 || i + 1 >= chars.size) return false
                return chars[i - 1].isAsciiDigit && chars[i + 1].isAsciiDigit
            }

            fun isDecimal(i: Int): Boolean {
                val c = chars[i]
                if (!(c == decimalChar || (c == "." && groupChar != "."))) return false
                if (i <= 0 || i + 1 >= chars.size) return false
                return chars[i - 1].isAsciiDigit && chars[i + 1].isAsciiDigit
            }

            val result = ArrayList<Pair<Key, String>>()
            val magnitudes = ArrayList<Double>()
            var index = 0
            var run = 0
            while (index < chars.size) {
                if (!chars[index].isAsciiDigit) {
                    val start = index
                    while (index < chars.size && !chars[index].isAsciiDigit) index++
                    val hasMoreDigits = index < chars.size
                    for (offset in 0 until index - start) {
                        val c = chars[start + offset]
                        result += (if (hasMoreDigits) Key.Prefix(run, offset) else Key.Suffix(offset)) to c
                    }
                    continue
                }
                val runStart = index
                var decimalIndex: Int? = null
                while (index < chars.size) {
                    when {
                        chars[index].isAsciiDigit -> index++
                        decimalIndex == null && isGrouping(index) -> index++
                        decimalIndex == null && isDecimal(index) -> {
                            decimalIndex = index
                            index++
                        }
                        else -> break
                    }
                }
                val runEnd = index
                val intEnd = decimalIndex ?: runEnd
                var remainingDigits = (runStart until intEnd).count { chars[it].isAsciiDigit }
                val clean = StringBuilder()
                for (i in runStart until intEnd) {
                    val c = chars[i]
                    if (c.isAsciiDigit) {
                        remainingDigits--
                        result += Key.Integer(run, remainingDigits) to c
                        clean.append(c)
                    } else {
                        result += Key.Group(run, remainingDigits) to c
                    }
                }
                if (decimalIndex != null) {
                    result += Key.Point(run) to chars[decimalIndex]
                    clean.append('.')
                    for (offset in 0 until runEnd - decimalIndex - 1) {
                        val c = chars[decimalIndex + 1 + offset]
                        result += Key.Fraction(run, offset) to c
                        clean.append(c)
                    }
                }
                var magnitude = clean.toString().toDoubleOrNull() ?: 0.0
                if (runStart > 0 && (chars[runStart - 1] == "-" || chars[runStart - 1] == "\u2212")) magnitude = -magnitude
                magnitudes += magnitude
                run++
            }
            return Parsed(result, magnitudes)
        }
    }
}

/** Horizontal placement of a [RollingNumber] inside its resting width. */
enum class RollingNumberAlignment { Start, Center, End }

/** Extended grapheme clusters (`\r\n`, a digit with a combining mark, an emoji sequence). */
private fun String.characterGlyphs(): List<String> {
    val out = ArrayList<String>(length)
    val breaks = BreakIterator.getCharacterInstance(Locale.ROOT)
    breaks.setText(this)
    var start = breaks.first()
    var end = breaks.next()
    while (end != BreakIterator.DONE) {
        out += substring(start, end)
        start = end
        end = breaks.next()
    }
    return out
}

private val String.isAsciiDigit: Boolean get() = length == 1 && this[0] in '0'..'9'
private val String.asciiDigit: Int? get() = if (isAsciiDigit) this[0] - '0' else null

internal fun rollingWrappedDigit(face: Int): Int = ((face % 10) + 10) % 10

internal fun rollingDigitGlyph(face: Int): String = ('0' + rollingWrappedDigit(face)).toString()
