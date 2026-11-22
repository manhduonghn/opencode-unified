package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.motion.SpringSolver
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.model.OpenCodeModel
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Catalog, provider-mark and 120 Hz motion-audit tests for One Picker. */
class OnePickerTests {
    @get:Rule val isolation = TestIsolationRule()

    private val opusZen = OpenCodeModel("claude-opus-5-5", "opencode", "Claude Opus 5.5", variants = listOf("low", "medium", "high", "max"))
    private val sonnetAnthropic = OpenCodeModel("claude-sonnet-5-5", "anthropic", "Claude Sonnet 5.5", variants = listOf("low", "medium", "high"))
    private val astra = OpenCodeModel("gpt-6-astra", "openai", "GPT-6 Astra", variants = listOf("minimal", "low", "medium", "high", "xhigh"))
    private val carbon = OpenCodeModel("gemini-4-carbon", "console-google", "Gemini 4 Carbon", variants = listOf("low", "high"))
    private val haiku = OpenCodeModel("claude-haiku-5-5", "opencode", "Claude Haiku 5.5")
    private val halcyonLumen = OpenCodeModel("lumen-r1", "halcyon", "Lumen R1", variants = listOf("low", "high"))

    private val catalog: List<OpenCodeModel>
        get() = listOf(opusZen, haiku, sonnetAnthropic, astra, carbon, halcyonLumen)

    @Test
    fun idleSectionsPlaceFavoritesThenFrozenRecentsWithCurrentModelThenProviders() {
        val sections = OnePickerCatalog.sections(
            models = catalog,
            favorites = setOf(sonnetAnthropic.id),
            recents = listOf(sonnetAnthropic.id, astra.id, carbon.id),
            currentModelID = opusZen.id,
            filter = "",
            recentLimit = 3,
            showsAllModels = true,
        )
        assertEquals(
            listOf("Favorites", "Recent", "OpenCode Zen", "Anthropic", "OpenAI", "Console Google", "Halcyon"),
            sections.map { it.title },
        )
        assertEquals(listOf(sonnetAnthropic), sections[0].models)
        assertEquals(
            "Current model stays visible at the top of Recent when not in Favorites, excluding favorited models",
            listOf(opusZen, astra, carbon),
            sections[1].models,
        )
        assertEquals(listOf(opusZen, haiku), sections[2].models)
    }

    @Test
    fun curatedModeHidesProviderSectionsOnlyWhenFavoritesAndRecentHaveEnoughPicks() {
        val curated = OnePickerCatalog.sections(
            models = catalog,
            favorites = setOf(opusZen.id),
            recents = listOf(astra.id),
            currentModelID = opusZen.id,
            filter = "",
            recentLimit = 3,
            showsAllModels = false,
        )
        assertEquals(listOf("Favorites", "Recent"), curated.map { it.title })

        val unconfigured = OnePickerCatalog.sections(
            models = catalog,
            favorites = emptySet(),
            recents = emptyList(),
            currentModelID = opusZen.id,
            filter = "",
            recentLimit = 3,
            showsAllModels = false,
        )
        assertTrue("Falls back to full provider catalog when fewer than 2 curated picks exist", unconfigured.size > 2)
    }

    @Test
    fun searchDropsSectionsMatchesWordsInAnyOrderAndParsesLevelWords() {
        val results = OnePickerCatalog.sections(
            models = catalog,
            favorites = setOf(opusZen.id),
            recents = listOf(astra.id),
            currentModelID = opusZen.id,
            filter = "astra extra high",
            recentLimit = 3,
            showsAllModels = false,
        )
        assertEquals(1, results.size)
        assertEquals(ModelPickerSection.Kind.SearchResults, results[0].kind)
        assertEquals(astra, results[0].models.first())

        val parsedAstra = OnePickerCatalog.parsedSearchLevel("astra extra high", astra)
        assertTrue(parsedAstra.matched && parsedAstra.level == "xhigh")
        val parsedHaiku = OnePickerCatalog.parsedSearchLevel("haiku high", haiku)
        assertTrue("Non-adjustable models never invent a level", !parsedHaiku.matched && parsedHaiku.level == null)
    }

    // Provider marks & filled fallback. The Compose mark draws the same squircle directly.

    @Test
    fun providerMarksUseRealAssetsAndFilledFallbackForUnknownProviders() {
        assertEquals("provider-anthropic", OnePickerProviderMark.resolvedAssetName("anthropic", null))
        assertEquals("provider-openai", OnePickerProviderMark.resolvedAssetName("openai", null))
        assertEquals("provider-opencode", OnePickerProviderMark.resolvedAssetName("opencode", null))
        assertEquals("provider-google", OnePickerProviderMark.resolvedAssetName("console-google", null))
        assertEquals("provider-ollama-cloud", OnePickerProviderMark.resolvedAssetName("ollama", null))
        // Unknown/fictional providers return null so the filled monogram renders instead of stroked sparkles.
        assertNull(OnePickerProviderMark.resolvedAssetName("halcyon", null))
        assertNull(OnePickerProviderMark.resolvedAssetName("meridian", null))
        assertEquals("H", OnePickerProviderMark.fallbackInitial("halcyon"))
        assertEquals("M", OnePickerProviderMark.fallbackInitial("meridian"))
    }

    // 120 Hz motion audit.

    @Test
    fun morphTrajectoryAt120HzHasZeroJumpsWidthLeadsAndStationaryBottomNeverJiggles() {
        val viewport = rect(0, 0, 402, 874)
        val composerBottom = rect(20, 786, 362, 56)
        val metrics = OnePickerSimulation.Metrics(
            viewport = viewport,
            sourceRect = composerBottom,
            sourceRadius = 28.0,
            ceiling = 104.0,
            compactHeight = 460.0,
            sideInset = 8.0,
            bottomInset = 8.0,
        )
        val sim = OnePickerSimulation(metrics)
        sim.arm(metrics, ModelListDetent.Medium, reduceMotion = false)
        sim.launchOpen(openDuration = 0.35, openBounce = 0.08, widthDuration = 0.25, bottomDuration = 0.34, contentDuration = 0.28)
        val dt = 1.0 / 120.0
        val openFrames = mutableListOf(sim.drawnFrame().rect)
        var widthReached90At: Int? = null
        var topReached90At: Int? = null
        val targetTop = metrics.compactTopY
        for (frame in 1..120) {
            val settled = sim.step(dt)
            val pose = sim.drawnFrame()
            openFrames += pose.rect
            if (widthReached90At == null && sim.sideProgress.value >= 0.90) widthReached90At = frame
            val topProgress = (composerBottom.top - pose.rect.top) / (composerBottom.top - targetTop)
            if (topReached90At == null && topProgress >= 0.90) topReached90At = frame
            if (settled) break
        }
        assertTrue("Open spring settles cleanly within 1 second", sim.isAllSettled)
        assertTrue("Width leads height during expansion", (widthReached90At ?: 999) < (topReached90At ?: 0))
        val openSteps = openFrames.zipWithNext { a, b -> abs(b.top - a.top) }
        val maxOpenTopStep = openSteps.maxOrNull() ?: 0f
        val maxOpenJerk = openSteps.zipWithNext { a, b -> abs(b - a) }.maxOrNull() ?: 0f
        assertTrue("Within MotionAudit 24 pt/frame 120 Hz velocity ceiling (max step: $maxOpenTopStep pt)", maxOpenTopStep < 24)
        assertTrue("Within MotionAudit 10 pt/frame² 120 Hz jerk ceiling (max Δstep: $maxOpenJerk pt)", maxOpenJerk < 10)
        val settledCompact = openFrames.last()
        assertTrue(abs(settledCompact.top - metrics.compactTopY) < 0.05)
        assertTrue(abs(settledCompact.bottom - metrics.floorY) < 0.05)

        // Expand Medium -> Large: the bottom edge must stay mathematically locked (0.0 pt jitter).
        sim.setDetent(ModelListDetent.Large, duration = 0.34, bounce = 0.04)
        val anchoredBottom = sim.drawnFrame().rect.bottom
        for (i in 1..120) {
            val done = sim.step(dt)
            val rect = sim.drawnFrame().rect
            assertEquals("Two-edge 1/64 pt snap locks stationary bottom edge with 0.0 pt jitter", anchoredBottom, rect.bottom)
            if (done) break
        }
        assertTrue(abs(sim.drawnFrame().rect.top - metrics.ceiling) < 0.05)
    }

    @Test
    fun midFlightReversalAndSearchKeyboardRidePreserveVelocityWithZeroJumps() {
        val viewport = rect(0, 0, 402, 874)
        val composerKeyboardUp = rect(20, 472, 362, 64)
        val metrics = OnePickerSimulation.Metrics(
            viewport = viewport,
            sourceRect = composerKeyboardUp,
            sourceRadius = 28.0,
            ceiling = 104.0,
            compactHeight = 460.0,
        )
        val sim = OnePickerSimulation(metrics)
        sim.arm(metrics, ModelListDetent.Medium, reduceMotion = false)
        sim.launchOpen()
        val dt = 1.0 / 120.0
        var previousRect = sim.drawnFrame().rect

        // Step 10 frames (~83 ms) into open, then reverse to close mid-flight.
        repeat(10) {
            sim.step(dt)
            val next = sim.drawnFrame().rect
            assertTrue(abs(next.top - previousRect.top) < 20)
            previousRect = next
        }
        sim.startClose(composerKeyboardUp)
        assertEquals("Reversing open -> close mid-flight has 0.0 pt position snap", previousRect, sim.drawnFrame().rect)

        // Step 8 frames (~67 ms) into close, then re-open mid-fold.
        repeat(8) {
            sim.step(dt)
            val next = sim.drawnFrame().rect
            assertTrue(abs(next.top - previousRect.top) < 24)
            previousRect = next
        }
        val velocityBeforeReopen = sim.top.velocity
        sim.arm(metrics, ModelListDetent.Medium, reduceMotion = false)
        sim.launchOpen()
        assertEquals("Re-opening mid-fold preserves live velocity", velocityBeforeReopen, sim.top.velocity, 0.0)
        assertEquals("Re-opening mid-fold has 0.0 pt position snap", previousRect, sim.drawnFrame().rect)

        // Settle open, then raise the bottom-search keyboard (keyboardTop = 548).
        for (i in 0 until 120) if (sim.step(dt)) break
        previousRect = sim.drawnFrame().rect
        sim.updateKeyboardTop(548.0, rideDuration = 0.34)
        assertEquals("Focusing bottom search never teleports on Frame 0", previousRect, sim.drawnFrame().rect)
        var maxBottomStep = 0f
        for (i in 0 until 120) {
            val done = sim.step(dt)
            val rect = sim.drawnFrame().rect
            maxBottomStep = max(maxBottomStep, abs(rect.bottom - previousRect.bottom))
            previousRect = rect
            if (done) break
        }
        assertTrue("Bottom search rides keyboard smoothly without a jump (max step: $maxBottomStep pt)", maxBottomStep > 2 && maxBottomStep < 24)
        assertTrue(abs(previousRect.bottom - (548 - metrics.keyboardGap)) < 0.05)
        assertTrue(abs(previousRect.top - metrics.ceiling) < 0.05)
    }

    @Test
    fun interactiveDragCarriesReleaseVelocityAndReduceMotionEliminatesTravel() {
        val viewport = rect(0, 0, 402, 874)
        val source = rect(20, 786, 362, 56)
        val metrics = OnePickerSimulation.Metrics(viewport = viewport, sourceRect = source, ceiling = 104.0, compactHeight = 460.0)
        val sim = OnePickerSimulation(metrics)
        sim.arm(metrics, ModelListDetent.Medium, reduceMotion = false)
        sim.launchOpen()
        for (i in 0 until 120) if (sim.step(1.0 / 120.0)) break

        sim.beginDrag()
        val crossedEarly = sim.updateDrag(40.0, dragCloses = 88.0)
        val crossedThreshold = sim.updateDrag(92.0, dragCloses = 88.0)
        val crossedRepeat = sim.updateDrag(110.0, dragCloses = 88.0)
        assertFalse(crossedEarly)
        assertTrue("Crossing dragCloses fires threshold once", crossedThreshold)
        assertFalse("Does not re-fire while staying past threshold", crossedRepeat)

        val outcome = sim.endDrag(110.0, 940.0, dragCloses = 88.0, throwCloses = 680.0)
        assertEquals(OnePickerSimulation.DragOutcome.Dismiss, outcome)
        assertEquals(OnePickerSimulation.Phase.Closing, sim.phase)
        assertEquals("Release velocity transfers 1:1 into the closing spring", 940.0, sim.top.velocity, 0.0)
        for (i in 0 until 120) if (sim.step(1.0 / 120.0)) break
        assertEquals(OnePickerSimulation.Phase.Closed, sim.phase)

        // Reduce Motion: zero spatial travel across open and close, smooth fade only.
        val reduced = OnePickerSimulation(metrics)
        reduced.arm(metrics, ModelListDetent.Medium, reduceMotion = true)
        reduced.launchOpen()
        val rects = mutableSetOf<Rect>()
        for (i in 0 until 60) {
            val done = reduced.step(1.0 / 120.0)
            rects += reduced.drawnFrame().rect
            if (done) break
        }
        assertEquals("Under Reduce Motion, the picker fades in place with zero spatial travel", 1, rects.size)
        assertEquals(1.0, reduced.drawnFrame().glassOpacity, 0.0)
    }

    @Test
    fun keyboardDropOnOpenAndReturnOnCloseMoveInLockstepWithZeroGap() {
        val viewport = rect(0, 0, 402, 874)
        val keyboardStartY = 538.0
        val keyboardEndY = 874.0
        val composerAboveKeys = rect(20, 472, 362, 58) // bottom = 530 (8 above 538)
        val metrics = OnePickerSimulation.Metrics(
            viewport = viewport,
            sourceRect = composerAboveKeys,
            sourceRadius = 28.0,
            ceiling = 104.0,
            compactHeight = 460.0,
            sideInset = 8.0,
            bottomInset = 8.0, // floorY = 866 (8 above 874)
            keyboardGap = 6.0,
            keyboardTop = null,
        )
        val kbDuration = 0.267
        val kbOmega = SpringSolver.parameters(kbDuration, 0.0).omega
        val sim = OnePickerSimulation(metrics)
        sim.arm(metrics, ModelListDetent.Medium, reduceMotion = false)
        assertEquals(1.0, sim.drawnFrame().composerOpacity, 0.0)
        assertEquals("Single glass surface owns the capsule from arm through settle", 1.0, sim.drawnFrame().glassOpacity, 0.0)
        sim.launchOpen(openDuration = 0.35, openBounce = 0.06, widthDuration = 0.25, bottomDuration = kbDuration, contentDuration = 0.24)
        val dt = 1.0 / 120.0
        var prev = sim.drawnFrame()
        assertEquals("Open starts at exact composer rect above keyboard", composerAboveKeys, prev.rect)
        assertEquals("Composer controls dissolve smoothly as glass grows; never a 1-frame empty box", 1.0, prev.composerOpacity, 0.0)
        assertEquals(1.0, prev.glassOpacity, 0.0)
        for (stepIdx in 1..120) {
            val done = sim.step(dt)
            val next = sim.drawnFrame()
            val t = stepIdx * dt
            val kbProgress = 1 - (1 + kbOmega * t) * exp(-kbOmega * t)
            val simulatedKeyboardTop = keyboardStartY + (keyboardEndY - keyboardStartY) * kbProgress
            val gapToKeys = simulatedKeyboardTop - next.rect.bottom
            assertTrue("Bottom edge tracks retreating keyboard within 1.5 pt at frame $stepIdx (gap=$gapToKeys)", abs(gapToKeys - 8) < 1.5)
            assertTrue("Smooth 120 Hz open top trajectory", abs(next.rect.top - prev.rect.top) < 24)
            assertEquals(1.0, next.glassOpacity, 0.0)
            assertTrue("Glass is never visible without content on open", max(next.composerOpacity, next.contentReveal) >= 0.35)
            assertEquals("Composer controls and Search Models never overlap", 0.0, min(next.composerOpacity, next.searchBarReveal), 0.0)
            prev = next
            if (done) break
        }
        assertTrue(sim.isAllSettled)
        assertTrue(abs(prev.rect.bottom - metrics.floorY) < 0.05)

        // Close with the message box's keyboard returning (874 -> 538): the bottom rises in lockstep, the exact reverse of open.
        val openLayoutSize = prev.layoutSize
        sim.startClose(composerAboveKeys, keepsKeyboard = false, closeDuration = kbDuration, contentOutDuration = 0.16)
        for (stepIdx in 1..120) {
            val done = sim.step(dt)
            val next = sim.drawnFrame()
            val t = stepIdx * dt
            val kbProgress = 1 - (1 + kbOmega * t) * exp(-kbOmega * t)
            val risingKeyboardTop = keyboardEndY + (keyboardStartY - keyboardEndY) * kbProgress
            val gapToKeys = risingKeyboardTop - next.rect.bottom
            if (sim.phase != OnePickerSimulation.Phase.Closed) {
                assertTrue("Bottom edge rides returning keyboard within 1.5 pt at frame $stepIdx (gap=$gapToKeys)", abs(gapToKeys - 8) < 1.5)
                assertEquals("List layout size never resizes during fold", openLayoutSize, next.layoutSize)
                assertEquals(1.0, next.glassOpacity, 0.0)
                assertTrue("Glass is never visible without content on close", max(next.composerOpacity, next.contentReveal) >= 0.35)
                assertEquals("Composer controls and Search Models never overlap", 0.0, min(next.composerOpacity, next.searchBarReveal), 0.0)
            }
            prev = next
            if (done) break
        }
        assertEquals(OnePickerSimulation.Phase.Closed, sim.phase)
        assertEquals(1.0, sim.drawnFrame().composerOpacity, 0.0)
        assertEquals(0.0, sim.drawnFrame().glassOpacity, 0.0)
    }

    private fun rect(x: Int, y: Int, width: Int, height: Int): Rect =
        Rect(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat())
}
