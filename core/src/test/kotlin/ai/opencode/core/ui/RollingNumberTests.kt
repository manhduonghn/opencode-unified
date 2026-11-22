package ai.opencode.core.ui

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionTunables
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.tuning.TuneValue
import ai.opencode.core.tuning.TuningContext
import ai.opencode.core.tuning.TuningStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Android lays the reels out with a Compose `TextMeasurer`; these JVM tests use [FakeMetrics]
 * (fractional advances, in px) in its place. Pixel grid: Android works in px.
 *
 * Deleted.
 */
class RollingNumberTests {
    @get:Rule val isolation = TestIsolationRule()

    private val us = Locale.US

    @Test
    fun placesKeyFromTheRightAcrossCarriesAndDecreases() {
        val model = RollingNumberModel()
        val style = RollingNumberStyle()
        val metrics = FakeMetrics(20f, bold = true)
        val start = 0.0

        model.retarget("9", us, at = start, animated = false, style = style)
        assertFalse(model.isMoving)

        model.retarget("10", us, at = start, animated = true, style = style)
        assertTrue(model.isMoving)

        val mid = model.frame(start + 0.12, metrics, style)
        assertEquals(2, mid.slots.size)
        // Ones place rolls 9 -> 10 (0), tens place opens from 0 -> 1.
        val ones = mid.slots[1].content as? RollingNumberFrame.Content.Reel ?: fail("Expected ones slot to be a digit reel")
        ones as RollingNumberFrame.Content.Reel
        assertTrue(ones.position > 9.0 && ones.position < 10.0)

        // 99 -> 100 -> 1,000 keeps ones/tens/hundreds keyed from the right and adds group:3 comma.
        val parsed1000 = RollingNumberModel.parse("1,000", us)
        assertEquals(listOf(1000.0), parsed1000.magnitudes)
        assertEquals(
            listOf(
                RollingNumberModel.Key.Integer(run = 0, place = 3),
                RollingNumberModel.Key.Group(run = 0, place = 3),
                RollingNumberModel.Key.Integer(run = 0, place = 2),
                RollingNumberModel.Key.Integer(run = 0, place = 1),
                RollingNumberModel.Key.Integer(run = 0, place = 0),
            ),
            parsed1000.tokens.map { it.first },
        )

        // Outward `Motion.staggerStep` stagger from the right on 9 -> 1,000: ones, tens, hundreds, group:3, thousands one step apart.
        val step = style.stagger
        model.retarget("9", us, at = start, animated = false, style = style)
        model.retarget("1,000", us, at = start, animated = true, style = style)
        val delaysByKey = model.slots.associate { it.key to it.delay }
        assertEquals(0.0, delaysByKey[RollingNumberModel.Key.Integer(0, 0)]!!, 0.0)
        assertTrue(abs((delaysByKey[RollingNumberModel.Key.Integer(0, 1)] ?: -1.0) - step) < 0.0001)
        assertTrue(abs((delaysByKey[RollingNumberModel.Key.Integer(0, 2)] ?: -1.0) - 2 * step) < 0.0001)
        assertTrue(abs((delaysByKey[RollingNumberModel.Key.Group(0, 3)] ?: -1.0) - 3 * step) < 0.0001)
        assertTrue(abs((delaysByKey[RollingNumberModel.Key.Integer(0, 3)] ?: -1.0) - 4 * step) < 0.0001)

        // Existing multi-digit roll (123 -> 456) staggers reels from the right, one step apart.
        model.retarget("123", us, at = start, animated = false, style = style)
        model.retarget("456", us, at = start, animated = true, style = style)
        val early = model.frame(start + 0.015, metrics, style)
        val onesEarly = early.slots[2].content as? RollingNumberFrame.Content.Reel
        val hundredsEarly = early.slots[0].content as? RollingNumberFrame.Content.Reel
        if (onesEarly == null || hundredsEarly == null) fail("Expected ones and hundreds digit reels")
        assertTrue(onesEarly!!.position > 3.0)
        assertTrue(abs(hundredsEarly!!.position - 1.0) < 0.0001)

        // Decrease across carry (100 -> 95): hundreds place marks removing while tens rolls 10 -> 9 and ones rolls 10 -> 5.
        model.retarget("100", us, at = start, animated = false, style = style)
        model.retarget("95", us, at = start, animated = true, style = style)
        assertEquals(true, model.slots.firstOrNull { it.key == RollingNumberModel.Key.Integer(0, 2) }?.removing)
        assertEquals(-1.0, model.slots.first { it.key == RollingNumberModel.Key.Integer(0, 1) }.reel.target, 0.0)
        assertEquals(-5.0, model.slots.first { it.key == RollingNumberModel.Key.Integer(0, 0) }.reel.target, 0.0)
    }

    @Test
    fun interruptionsPreservePositionAndVelocityAndSettleToZeroWork() {
        val model = RollingNumberModel()
        val style = RollingNumberStyle()
        val metrics = FakeMetrics(32f, bold = true)
        val t0 = 100.0

        model.retarget("8", us, at = t0, animated = false, style = style)
        model.retarget("12", us, at = t0, animated = true, style = style)

        val t1 = t0 + 0.10
        val beforeInterrupt = model.frame(t1, metrics, style)
        val posBefore = (beforeInterrupt.slots.last().content as? RollingNumberFrame.Content.Reel)?.position
            ?: return fail("Expected ones reel")
        val velBefore = beforeInterrupt.slots.last().velocity
        assertTrue("$posBefore", posBefore > 8.2 && posBefore < 12.0)
        assertTrue("$velBefore", velBefore > 4.0)
        assertTrue(beforeInterrupt.blurBands.isNotEmpty())

        // Retarget mid-roll to 14: position and upward velocity continue without jumping or resetting.
        model.retarget("14", us, at = t1, animated = true, style = style)
        val afterInterrupt = model.frame(t1 + 1.0 / 120.0, metrics, style)
        val posAfter = (afterInterrupt.slots.last().content as? RollingNumberFrame.Content.Reel)?.position
            ?: return fail("Expected ones reel after interruption")
        assertTrue(posAfter > posBefore)
        assertTrue(posAfter - posBefore < 0.8)

        // Settle completely and confirm isMoving is false and blurBands are empty (zero per-frame work).
        var settledFrame = afterInterrupt
        for (step in 1..400) {
            if (settledFrame.isSettled) break
            settledFrame = model.frame(t1 + step / 60.0, metrics, style)
        }
        assertTrue(settledFrame.isSettled)
        assertFalse(model.isMoving)
        assertTrue(settledFrame.blurBands.isEmpty())
    }

    @Test
    fun unwrapChoosesShortestPathWhenOverFiveFacesAway() {
        // 15 -> 8: ones digit 5 -> 8 is 3 faces up vs 7 faces down through 0; takes 5 -> 8.
        assertEquals(8.0, RollingNumberModel.unwrap(8.0, 5.0, up = false), 0.0)

        // 8 -> 12: ones digit 8 -> 2 is 4 faces up (8, 9, 10, 11, 12); honors upward trend.
        assertEquals(12.0, RollingNumberModel.unwrap(2.0, 8.0, up = true), 0.0)
    }

    @Test
    fun stationarySeparatorsCurrencyPercentAndMultiRunStrings() {
        val model = RollingNumberModel()
        val style = RollingNumberStyle()
        val metrics = FakeMetrics(16f)
        val start = 0.0

        // Currency: "$0.18" -> "$0.42" keeps '$', '0', and '.' stationary while '1' -> '4' and '8' -> '2' roll.
        model.retarget("$0.18", us, at = start, animated = false, style = style)
        model.retarget("$0.42", us, at = start, animated = true, style = style)
        val mid = model.frame(start + 0.10, metrics, style)
        assertEquals(5, mid.slots.size)
        // '$' (index 0) and '.' (index 2) have zero velocity and progress == 1.
        val prefix = mid.slots[0].content as? RollingNumberFrame.Content.Symbol ?: fail("Expected stationary currency prefix")
        prefix as RollingNumberFrame.Content.Symbol
        assertEquals("$", prefix.from)
        assertEquals("$", prefix.to)
        assertEquals(1.0, prefix.progress, 0.0)
        assertEquals(0.0, mid.slots[0].velocity, 0.0)
        val point = mid.slots[2].content as? RollingNumberFrame.Content.Symbol ?: fail("Expected stationary decimal point")
        point as RollingNumberFrame.Content.Symbol
        assertEquals(".", point.from)
        assertEquals(".", point.to)
        assertEquals(1.0, point.progress, 0.0)
        assertEquals(0.0, mid.slots[2].velocity, 0.0)

        // Request dock "1 of 3" -> "2 of 3" rolls only page 1 -> 2 while " of " and "3" stay at rest.
        model.retarget("1 of 3", us, at = start, animated = false, style = style)
        model.retarget("2 of 3", us, at = start, animated = true, style = style)
        val pageMid = model.frame(start + 0.10, metrics, style)
        assertTrue((pageMid.slots.firstOrNull()?.velocity ?: 0.0) > 0)
        assertEquals(0.0, pageMid.slots.last().velocity, 0.0)
    }

    @Test
    fun localesAndNonRollableScriptsAreHandledCleanly() {
        // German locale: '.' is grouping, ',' is decimal.
        val de = RollingNumberModel.parse("1.234,56 €", Locale.GERMANY)
        assertEquals(listOf(1234.56), de.magnitudes)
        assertTrue(de.tokens.any { it.first == RollingNumberModel.Key.Group(0, 3) && it.second == "." })
        assertTrue(de.tokens.any { it.first == RollingNumberModel.Key.Point(0) && it.second == "," })

        // French locale: narrow no-break space grouping, ',' decimal.
        val fr = RollingNumberModel.parse("12\u202F345,5", Locale.FRANCE)
        assertEquals(listOf(12345.5), fr.magnitudes)
        assertTrue(fr.tokens.any { it.first == RollingNumberModel.Key.Group(0, 3) && it.second == "\u202F" })

        // Multi-run strings and negative numbers.
        val multi = RollingNumberModel.parse("1 subagent · 12 shells", us)
        assertEquals(listOf(1.0, 12.0), multi.magnitudes)
        val negative = RollingNumberModel.parse("-15", us)
        assertEquals(listOf(-15.0), negative.magnitudes)

        assertTrue(RollingNumberModel.isRollable("64% of 400K"))
        assertFalse(RollingNumberModel.isRollable("١٢٣"))
        assertFalse(RollingNumberModel.isRollable("\u202E123"))
    }

    @Test
    fun reduceMotionAndInactiveSceneSettleImmediatelyAndVoiceOverReadsFinalValue() {
        val model = RollingNumberModel()
        val style = RollingNumberStyle()
        val metrics = FakeMetrics(15f, bold = true)
        val t0 = 50.0

        model.sync("9", us, animated = false, style = style, nowSeconds = 0.0)
        model.sync("10", us, animated = true, style = style, nowSeconds = 0.0)
        assertTrue(model.isMoving)
        assertEquals("10", model.shownText)

        // Pausing when the scene becomes inactive or Reduce Motion turns on settles immediately.
        model.sync("10", us, animated = false, style = style, nowSeconds = 0.0)
        assertFalse(model.isMoving)
        val settled = model.frame(t0 + 0.05, metrics, style)
        assertTrue(settled.isSettled)
        assertTrue(settled.blurBands.isEmpty())

        // Trailing alignment pins the trailing edge at targetWidth throughout carries.
        model.retarget("99%", us, at = t0, animated = false, style = style)
        model.retarget("100%", us, at = t0, animated = true, style = style)
        val targetW = RollingNumberModel.targetWidth("100%", metrics, us)
        val trailingMid = model.frame(t0 + 0.08, metrics, style, targetWidth = targetW, alignment = RollingNumberAlignment.End)
        val lastSlot = trailingMid.slots.last()
        assertTrue(abs((lastSlot.x + lastSlot.width) - targetW) < 0.01f)
    }

    // Measured width

    private val titles = listOf("1 queued", "2 queued", "10 queued", "1 subagent · 2 shells", "64% of 400K")

    /**
     * The width a rolling title lays out at is on the pixel grid and holds every glyph it draws.
     */
    @Test
    fun rollingTitleMeasuresWhatItDraws() {
        for (size in listOf(15f, 20f, 31f)) {
            for (bold in listOf(false, true)) {
                val metrics = FakeMetrics(size * 2.625f, bold = bold)
                for (title in titles) {
                    val laidOut = RollingNumberModel.targetWidth(title, metrics, us)
                    assertTrue("$title sits on the pixel grid", abs(laidOut - laidOut.roundToInt()) < 0.001f)

                    val model = RollingNumberModel()
                    model.retarget(title, us, at = 0.0, animated = false, style = RollingNumberStyle())
                    val drawn = model.frame(0.0, metrics, RollingNumberStyle(), targetWidth = laidOut)
                    val extent = drawn.slots.maxOfOrNull { it.x + it.width } ?: 0f
                    assertTrue("$title draws inside its frame", extent <= laidOut + 0.001f)
                    assertTrue("$title is at most a pixel wider than it draws", laidOut - extent < 1f)
                }
            }
        }
    }

    /** 2 → 3 → 10 → 3 queued: the frame is the final width at once and every rolling frame draws inside it, moving less than a few points per frame. */
    @Test
    fun countRollNeverClipsOrJumps() {
        val style = RollingNumberStyle()
        // A 15 pt subheadline at 3× is 45 px.
        val metrics = FakeMetrics(45f)
        val jump = 9f
        val model = RollingNumberModel()
        var t = 100.0
        model.retarget("2 queued", us, at = t, animated = false, style = style)
        for (title in listOf("3 queued", "10 queued", "3 queued", "2 queued")) {
            val laidOut = RollingNumberModel.targetWidth(title, metrics, us)
            model.retarget(title, us, at = t, animated = true, style = style)
            var previous: RollingNumberFrame? = null
            for (i in 0 until 120) {
                t += 1.0 / 120
                val frame = model.frame(t, metrics, style, targetWidth = laidOut)
                val canvas = max(frame.width, laidOut)
                for (slot in frame.slots) {
                    if (slot.opacity <= 0.01) continue
                    assertTrue("$title: a glyph leaves the canvas", slot.x >= -0.001f && slot.x + slot.width <= canvas + 0.001f)
                }
                previous?.let { prev ->
                    assertTrue("$title: width jumps ${prev.width} → ${frame.width}", abs(frame.width - prev.width) < jump)
                    for ((a, b) in prev.slots.zip(frame.slots)) {
                        if (a.opacity > 0.5 && b.opacity > 0.5) {
                            assertTrue("$title: a glyph jumps ${a.x} → ${b.x}", abs(a.x - b.x) < jump)
                        }
                    }
                }
                previous = frame
                if (frame.isSettled) break
            }
            assertEquals(true, previous?.isSettled)
            assertTrue("$title settles at its laid-out width", abs((previous?.width ?: 0f) - laidOut) < 1f)
        }
    }

    @Test
    fun tunablesDefaultToOption3AndRespondToLiveOverrides() {
        val off = TuningStore(null)
        assertEquals(1.0, RollingNumberTunables.blurAmount.defaultValue, 0.0)
        assertEquals(0.18f, RollingNumberTunables.edgeFade.defaultValue, 0f)

        // The roll runs on `Motion.layout`, its places `Motion.staggerStep` apart.
        val defaultStyle = RollingNumberTunables.style(off)
        assertEquals(MotionTunables.layoutSpring.duration.defaultValue, defaultStyle.duration, 0.0)
        assertEquals(MotionTunables.layoutSpring.bounce.defaultValue, defaultStyle.bounce, 0.0)
        assertEquals(MotionTunables.staggerStep.defaultValue, defaultStyle.stagger, 0.0)
        assertTrue(defaultStyle.motionBlur)
        assertEquals(1.0, defaultStyle.blurAmount, 0.0)
        assertEquals(0.18f, defaultStyle.edgeFade, 0f)

        val live = TuningStore(null)
        live.setEnabled(true)
        RollingNumberTunables.register(live)
        assertTrue(live.descriptors.map { it.group }.all { it == "numbers" })
        assertTrue(TuningContext.session.groups.contains("numbers"))

        Motion.register(live)
        live.set("motion.springs.layout.duration", TuneValue.Number(0.42))
        live.set("numbers.blur", TuneValue.Number(0.0))
        val tuned = RollingNumberTunables.style(live)
        assertEquals(0.42, tuned.duration, 0.0)
        assertFalse(tuned.motionBlur)
    }

    /**
     * Stand-in for the `TextMeasurer` metrics: tabular digits (one width), proportional
     * everything else, fractional px advances so rounding to the pixel grid matters.
     */
    private class FakeMetrics(private val size: Float, bold: Boolean = false) : RollingNumberMetrics {
        private val weight = if (bold) 1.06f else 1f
        override val lineHeight: Float = size * 1.19f
        override val ascender: Float = size * 0.95f

        override fun advance(glyph: String): Float = weight * size * when {
            glyph.length == 1 && glyph[0] in '0'..'9' -> 0.6093f
            glyph == " " || glyph == "\u202F" || glyph == "\u00A0" -> 0.2539f
            glyph == "," || glyph == "." -> 0.2617f
            glyph == "%" -> 0.8711f
            glyph == "·" -> 0.2754f
            glyph == "$" || glyph == "€" -> 0.6201f
            else -> 0.5537f
        }
    }
}
