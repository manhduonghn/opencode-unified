package ai.opencode.feature.composer.modelpicker

import ai.opencode.feature.composer.ComposerSwipeOwnership
import ai.opencode.server.model.ModelProviderGroup
import ai.opencode.server.model.ModelSearch
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.ambiguousShortNames
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Duration.Companion.milliseconds

/**
 * The model picker: the list's sections, the level scale the meter and
 * its panel step through, the meter's press, and picks that wait for the next send.
 *
 * Dropped: `searchableSheetsAvoidForcedNavigationBarDrawerOnMac`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelPickerTests {
    private fun model(modelID: String, providerID: String, name: String, variants: List<String> = emptyList()) =
        OpenCodeModel(modelID = modelID, providerID = providerID, name = name, variants = variants)

    private val cirrusZen = model("cirrus-4", "opencode", "Cirrus 4", listOf("low", "high"))
    private val mini = model("cirrus-4-mini", "opencode", "Cirrus 4 Mini")
    private val cirrusRouter = model("vendor/cirrus-4", "openrouter", "Cirrus 4", listOf("low", "medium", "high", "max"))
    private val basalt = model("basalt-pro", "halcyon", "Basalt Pro")

    // MARK: Sections

    @Test fun favoritesThenOneSectionPerProviderInTheServersOrder() {
        val sections = ModelPickerSection.sections(listOf(cirrusZen, cirrusRouter, mini, basalt), setOf(basalt.id, cirrusRouter.id))
        assertEquals(listOf("Favorites", "OpenCode Zen", "OpenRouter", "Halcyon"), sections.map { it.title })
        assertEquals(listOf(cirrusRouter, basalt), sections[0].models)
        assertEquals(listOf(cirrusZen, mini), sections[1].models)
        assertEquals("A favorite stays in its provider's section too", listOf(cirrusRouter), sections[2].models)
    }

    @Test fun aModelSeveralProvidersOfferIsListedUnderEach() {
        val sections = ModelPickerSection.sections(listOf(cirrusZen, cirrusRouter), emptySet())
        assertEquals(listOf("OpenCode Zen", "OpenRouter"), sections.map { it.title })
        assertEquals(listOf("Cirrus 4", "Cirrus 4"), sections.flatMap { it.models }.map { it.name })
    }

    @Test fun searchDropsFavoritesAndMatchesNamesAndProviders() {
        val models = listOf(cirrusZen, cirrusRouter, mini, basalt)
        val byName = ModelPickerSection.sections(models, setOf(basalt.id), filter = "basalt")
        assertEquals(listOf("Halcyon"), byName.map { it.title })
        val byProvider = ModelPickerSection.sections(models, emptySet(), filter = "OpenRouter")
        assertEquals(listOf(cirrusRouter), byProvider.flatMap { it.models })
        assertTrue(ModelPickerSection.sections(models, emptySet(), filter = "zzz").isEmpty())
    }

    // MARK: Search

    private val sonnet45 = model("claude-sonnet-4-5-20250929", "anthropic", "Claude Sonnet 4.5")
    private val sonnet4 = model("claude-sonnet-4-20250514", "anthropic", "Claude Sonnet 4")
    private val opus = model("claude-opus-4-1", "anthropic", "Claude Opus 4.1")
    private val zenSonnet = model("claude-sonnet-4-5", "opencode", "Claude Sonnet 4.5")
    private val zenMini = model("gpt-5-mini", "opencode", "GPT-5 Mini")
    private val kimi = model("kimi-k2", "opencode-go", "Kimi K2")
    private val gpt5 = model("gpt-5", "openai", "GPT-5")
    private val openAIMini = model("gpt-5-mini", "openai", "GPT-5 Mini")
    private val routerOpus = model("anthropic/claude-opus-4.1", "openrouter", "Claude Opus 4.1")
    private val gemini = model("gemini-2.5-pro", "google", "Gemini 2.5 Pro")
    private val catalog: List<OpenCodeModel>
        get() = listOf(sonnet45, sonnet4, opus, zenSonnet, zenMini, kimi, gpt5, openAIMini, routerOpus, gemini)

    private fun search(query: String, favorites: Set<String> = emptySet()): List<OpenCodeModel> =
        ModelSearch.rank(catalog, query, favorites)

    @Test fun aProvidersNameListsItsModels() {
        assertEquals("OpenCode Zen and OpenCode Go, in the server's order", listOf(zenSonnet, zenMini, kimi), search("opencode"))
        assertEquals(listOf(zenSonnet, zenMini), search("OpenCode Zen"))
        assertEquals("OpenRouter's Anthropic models by their IDs, last", listOf(sonnet45, sonnet4, opus, routerOpus), search("anthropic"))
    }

    @Test fun wordsMatchInAnyOrderAcrossNameAndProvider() {
        assertEquals("Not Sonnet 4, whose ID only has a date with 4 and 5 in it", listOf(sonnet45, zenSonnet), search("son 4.5"))
        assertEquals(listOf(sonnet45, zenSonnet), search("4.5 son"))
        assertEquals(listOf(zenMini, openAIMini), search("gpt mini"))
        assertEquals(listOf(opus, routerOpus), search("anthropic opus"))
        assertEquals(listOf(opus, routerOpus), search("opus anthropic"))
        assertEquals(listOf(zenSonnet), search("zen sonnet"))
        assertEquals(listOf(zenMini), search("opencode gpt"))
    }

    @Test fun betterMatchesComeFirstThenFavorites() {
        assertEquals("A whole word before Gemini, which only contains it", zenMini, search("mini").first())
        assertEquals(gemini, search("mini").last())
        assertEquals(listOf(openAIMini, zenMini), search("gpt mini", favorites = setOf(openAIMini.id)))
        assertEquals("Equal matches keep the server's order", listOf(zenMini, gpt5, openAIMini), search("gpt"))
        assertEquals("Three letters may skip letters inside a word of the name", listOf(sonnet45, sonnet4, zenSonnet), search("snt"))
        assertEquals("Run together like the desktop's search", listOf(zenMini, openAIMini), search("gpt5mini"))
        assertEquals("The 5 in 4.5, not one inside Sonnet 4's date", listOf(sonnet45, zenSonnet), search("sonnet 5"))
        assertTrue("A number matches only where one starts", search("sonnet 0929").isEmpty())
    }

    @Test fun emptyAndPunctuationQueriesKeepTheList() {
        assertEquals(catalog, search(""))
        assertEquals(catalog, search("  ·  "))
        assertEquals("claude sonnet 4.5", ModelSearch.normalize("Claude Sonnet 4.5"))
        assertEquals("claude sonnet 4.5", ModelSearch.normalize("claude-sonnet-4-5"))
        assertEquals("gemini 2 5 pro", ModelSearch.normalize("Gémini 2,5 – Pro"))
    }

    /** Off [ModelPickerState], which the session builds. */
    @Test fun bothPickersShareTheSearch() {
        val sections = ModelPickerSection.sections(catalog, setOf(opus.id), filter = "anthropic opus")
        assertEquals(listOf("Anthropic", "OpenRouter"), sections.map { it.title })
        val plain = ModelPickerState(models = catalog, modelFilter = "anthropic opus")
        assertEquals(listOf(opus, routerOpus), plain.filteredModels)
        assertEquals(listOf("Anthropic", "OpenRouter"), plain.modelSections.map { it.title })
    }

    // MARK: Level scale

    @Test fun levelsRunFromTheDefaultUpThroughTheSortedVariants() {
        val scale = ModelLevelScale(listOf("high", "default", "low", "max", "medium"))
        assertEquals(listOf(null, "low", "medium", "high", "max"), scale.levels)
        assertEquals(listOf("low", "medium", "high", "max"), scale.variants)
        assertTrue(scale.isAdjustable)
        assertFalse(ModelLevelScale(emptyList()).isAdjustable)
        assertEquals(0, scale.index("default"))
        assertEquals("A level the model no longer offers reads as its default", 0, scale.index("ultra"))
        assertEquals("max", scale.stepped(from = "high", by = 1))
        assertEquals("max", scale.stepped(from = "max", by = 1))
        assertNull(scale.stepped(from = null, by = -1))
        assertEquals("Default", ModelLevelScale.label(null))
        assertEquals("Extra High", ModelLevelScale.label("xhigh"))
    }

    @Test fun scrubbingStepsOneLevelPerThirtyPointsFromWhereTheHoldBegan() {
        val scale = ModelLevelScale(listOf("low", "medium", "high", "max"))
        assertEquals("Under half a step stays put", 3, scale.scrubIndex(from = 3, translation = 14.0))
        assertEquals(4, scale.scrubIndex(from = 3, translation = 16.0))
        assertEquals(1, scale.scrubIndex(from = 3, translation = -61.0))
        assertEquals(4, scale.scrubIndex(from = 3, translation = 400.0))
        assertEquals(0, scale.scrubIndex(from = 3, translation = -400.0))
    }

    // MARK: Swipe ownership

    /**
     * A drag over the composer that began on the level stays the level's to its last callback,
     * even after the level's own gesture has let go.
     */
    @Test fun aDragKeepsTheOwnerItStartedWith() {
        val ownership = ComposerSwipeOwnership()
        val start = Offset(40f, 10f)
        assertTrue(ownership.yields(start) { true })
        assertTrue("The level let go first: still its drag", ownership.yields(start) { false })
        ownership.ended()
        assertFalse("The next drag decides afresh", ownership.yields(start) { false })
        assertFalse(ownership.yields(Offset(41f, 10f)) { false })
        assertTrue("A new start decides afresh", ownership.yields(Offset(90f, 10f)) { true })
    }

    /**
     * The list's rests: medium about half the large reach (never under 300 pt), a throw settling
     * at the nearer rest or closing well below medium, and a pull past large rubber-banding.
     */
    @Test fun theListRestsLikeASheetsDetents() {
        val rests = ModelListDetent.Rests(large = 800.0)
        assertEquals(400.0, rests.medium, 0.0)
        assertEquals(300.0, ModelListDetent.Rests(large = 420.0).medium, 0.0)
        assertEquals("Never above large", 200.0, ModelListDetent.Rests(large = 200.0).medium, 0.0)
        assertEquals(ModelListDetent.Large, ModelListDetent.rest(projected = 700.0, rests = rests))
        assertEquals(ModelListDetent.Medium, ModelListDetent.rest(projected = 520.0, rests = rests))
        assertEquals(ModelListDetent.Medium, ModelListDetent.rest(projected = 230.0, rests = rests))
        assertNull("Thrown well below medium, it closes", ModelListDetent.rest(projected = 200.0, rests = rests))
        assertEquals(300.0, ModelListDetent.reach(resting = 400.0, drag = 100.0, full = 800.0), 0.0)
        assertEquals(0.0, ModelListDetent.reach(resting = 400.0, drag = 500.0, full = 800.0), 0.0)
        val pulled = ModelListDetent.reach(resting = 800.0, drag = -400.0, full = 800.0)
        assertTrue("Past large it gives a little, never more than 48 pt", pulled > 800 && pulled < 848)
    }

    /**
     * A drag that begins mid-spring starts from where the glass is drawn, not from the rest it's
     * heading for, and keeps that start; a drag the system cancels (no throw) settles at the
     * nearer rest, never in between.
     */
    @Test fun aDragCatchesTheGlassWhereItIsAndACancelledOneSettles() {
        val first = ModelListDetent.drag(null, drawnReach = 523.0, translation = 4.0)
        assertEquals("Caught mid-spring at 523, not at a rest", ModelListDetent.Drag(start = 523.0, translation = 4.0), first)
        assertEquals("No jump", 519.0, ModelListDetent.reach(resting = first.start, drag = first.translation, full = 800.0), 0.0)
        val later = ModelListDetent.drag(first, drawnReach = 610.0, translation = -30.0)
        assertEquals("Later moves keep the catch", 523.0, later.start, 0.0)
        val rests = ModelListDetent.Rests(large = 800.0)
        assertEquals("Let go (or cancelled) nearer medium", ModelListDetent.Medium, ModelListDetent.rest(projected = 523.0 - 0, rests = rests))
        assertEquals(ModelListDetent.Large, ModelListDetent.rest(projected = 700.0 - 0, rests = rests))
    }

    /**
     * A drag the system cancels settles from where the finger left the glass, not from where it
     * caught it: dragged from Half nearly to Full, it stays Full.
     */
    @Test fun aCancelledDragSettlesFromWhereTheFingerLeftIt() {
        val rests = ModelListDetent.Rests(large = 800.0)
        val nearlyFull = ModelListDetent.Drag(start = 400.0, translation = -360.0)
        assertEquals(ModelListDetent.Large, ModelListDetent.settle(nearlyFull, predictedTranslation = null, rests = rests))
        val barely = ModelListDetent.Drag(start = 400.0, translation = -60.0)
        assertEquals(ModelListDetent.Medium, ModelListDetent.settle(barely, predictedTranslation = null, rests = rests))
        val downFar = ModelListDetent.Drag(start = 400.0, translation = 260.0)
        assertNull("Left well below medium, it closes", ModelListDetent.settle(downFar, predictedTranslation = null, rests = rests))
        assertEquals(
            "A throw's own prediction wins when there is one",
            ModelListDetent.Medium,
            ModelListDetent.settle(nearlyFull, predictedTranslation = 0.0, rests = rests),
        )
    }

    /** TalkBack's grabber reads and adjusts the rest like a sheet's. */
    @Test fun theGrabberAdjustsTheRest() {
        assertEquals("Half", ModelListDetent.Medium.accessibilityValue)
        assertEquals("Full", ModelListDetent.Large.accessibilityValue)
        assertEquals(ModelListDetent.Large, ModelListDetent.Medium.adjusted(1))
        assertEquals(ModelListDetent.Medium, ModelListDetent.Large.adjusted(-1))
        assertEquals(ModelListDetent.Large, ModelListDetent.Large.adjusted(1))
    }

    /** One restore at a time: scheduling another or cancelling drops it. */
    @Test fun aFocusRestoreRunsOnceUnlessCancelled() = runTest {
        val restore = ComposerFocusRestore(this)
        val runs = mutableListOf<String>()
        restore.schedule(after = 30.milliseconds) { runs += "first" }
        restore.schedule(after = 30.milliseconds) { runs += "second" }
        advanceTimeBy(150.milliseconds)
        assertEquals(listOf("second"), runs)
        restore.schedule(after = 30.milliseconds) { runs += "third" }
        restore.cancel()
        advanceTimeBy(150.milliseconds)
        assertEquals(listOf("second"), runs)
    }

    /** The pixel bounds [LevelArc.draw] covers in a 20×20 box (a 1 px stroke), or null when nothing is drawn. */
    private fun drawnBounds(fill: Double): androidx.compose.ui.geometry.Rect? {
        val image = androidx.compose.ui.graphics.ImageBitmap(20, 20)
        androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
            androidx.compose.ui.unit.Density(1f),
            androidx.compose.ui.unit.LayoutDirection.Ltr,
            androidx.compose.ui.graphics.Canvas(image),
            androidx.compose.ui.geometry.Size(20f, 20f),
        ) { LevelArc.draw(this, fill = fill, span = 240.0, color = androidx.compose.ui.graphics.Color.Black, stroke = 1f) }
        val pixels = IntArray(400)
        image.readPixels(pixels)
        val lit = pixels.indices.filter { (pixels[it] ushr 24) != 0 }
        if (lit.isEmpty()) return null
        val xs = lit.map { it % 20 }
        val ys = lit.map { it / 20 }
        return androidx.compose.ui.geometry.Rect(xs.min().toFloat(), ys.min().toFloat(), xs.max() + 1f, ys.max() + 1f)
    }

    @Test fun theArcFillsToTheLevelAtAnyCount() {
        for (levels in 1..6) {
            val count = levels + 1
            assertTrue(LevelArc.pose(index = 1, count = count).fill > 0)
            assertEquals(LevelArc.Pose(1.0, 1.0), LevelArc.pose(index = levels, count = count))
            val fills = (1..levels).map { LevelArc.pose(index = it, count = count).fill }
            assertTrue("Each level fills further", fills == fills.sorted() && fills.toSet().size == levels)
            val standard = LevelArc.pose(index = 0, count = count)
            assertTrue("Default reads dimmed, about halfway", standard.lit < 1 && standard.fill > 0.5 - 0.001 && standard.fill <= 1)
        }
        assertEquals(LevelArc.Pose(0.625, 0.45), LevelArc.pose(index = 0, count = 5))
        assertEquals("From bottom left, over the top, to bottom right", 150.0, LevelArc.start(span = 240.0), 0.0)
        assertNull("Nothing drawn at fill 0", drawnBounds(fill = 0.0))
        val full = drawnBounds(fill = 1.0)!!
        assertTrue("Centred in its box: $full", abs((full.left + full.right) / 2f - 10f) < 0.6f && full.top > 1f && full.bottom < 19f)
        assertEquals(2.45, LevelScrubberTunables.arcStroke, 0.0)
        assertEquals(240.0, LevelScrubberTunables.arcSpan, 0.0)
    }

    // MARK: - Model names and levels

    private val opus55 = model("claude-opus-5-5", "anthropic", "Claude Opus 5.5", listOf("low", "medium", "high", "max"))
    private val sonnet55Anthropic = model("claude-sonnet-5-5", "anthropic", "Claude Sonnet 5.5", listOf("low", "medium", "high"))
    private val sonnet55Zen = model("claude-sonnet-5-5", "opencode", "Claude Sonnet 5.5", listOf("low", "medium", "high"))
    private val argon = model("gemini-4-argon", "console-google", "Gemini 4 Argon", listOf("low", "medium", "high"))
    private val astra = model("gpt-6-astra", "openai", "GPT-6 Astra", listOf("low", "medium", "high", "xhigh"))
    private val haiku = model("claude-haiku-4-5", "anthropic", "Claude Haiku 4.5")

    @Test fun shortNamesStripRedundantVendorWordsAndDisambiguateSharedShortNames() {
        assertEquals("Opus 5.5", opus55.shortName)
        assertEquals("Sonnet 5.5", sonnet55Anthropic.shortName)
        assertEquals("Single-word family before a version digit stays intact", "Gemini 4 Argon", argon.shortName)
        val googleArgon = model("gemini-4-argon", "console-google", "Google Gemini 4 Argon")
        assertEquals("Redundant provider word is stripped", "Gemini 4 Argon", googleArgon.shortName)
        assertEquals("Hyphenated GPT-6 stays whole", "GPT-6 Astra", astra.shortName)
        val zenModel = model("opencode-zen", "opencode", "OpenCode Zen")
        assertEquals("Zen", zenModel.shortName)
        val models = listOf(opus55, sonnet55Anthropic, sonnet55Zen, argon, astra)
        assertEquals("Only Sonnet 5.5 is offered by two providers", setOf("Sonnet 5.5"), models.ambiguousShortNames)
        assertEquals("Google", ModelProviderGroup.canonicalVendorTitle("console-google"))
        assertEquals("The old picker keeps main's titles", "Console Google", ModelProviderGroup.title("console-google"))
        assertEquals("Fireworks Ai", ModelProviderGroup.title("fireworks-ai"))
        assertEquals("Fireworks AI", ModelProviderGroup.canonicalVendorTitle("fireworks-ai"))
    }

    /**
     * Recent sits between Favorites and the providers, most recent first, without favorites, up to
     * its limit, and goes while searching, as Favorites does.
     */
    @Test fun theSheetsRecentSitsAfterFavoritesWithoutThemUpToItsLimit() {
        val models = listOf(opus55, sonnet55Anthropic, argon, astra, haiku)
        val recents = listOf(opus55.id, "gone/model", argon.id, astra.id, haiku.id, sonnet55Anthropic.id)
        val sections = ModelPickerSection.sections(models, setOf(opus55.id), recents = recents, recentLimit = 3)
        assertEquals(listOf("Favorites", "Recent", "Anthropic", "Console Google", "OpenAI"), sections.map { it.title })
        assertEquals(listOf(opus55), sections[0].models)
        assertEquals("Up to 3, most recent first, skipping favorites and models no longer listed", listOf(argon, astra, haiku), sections[1].models)
        assertEquals("Providers still list every model", listOf(opus55, sonnet55Anthropic, haiku), sections[2].models)

        val searching = ModelPickerSection.sections(models, setOf(opus55.id), filter = "argon", recents = recents, recentLimit = 3)
        assertTrue(
            "Searching drops Recent with Favorites",
            searching.none { it.kind == ModelPickerSection.Kind.Recent || it.kind == ModelPickerSection.Kind.Favorites },
        )
        assertNotEquals(ModelPickerSection.Kind.Recent, ModelPickerSection.sections(models, emptySet(), recents = recents, recentLimit = 0).firstOrNull()?.kind)
        assertNotEquals(ModelPickerSection.Kind.Recent, ModelPickerSection.sections(models, emptySet(), recents = emptyList(), recentLimit = 3).firstOrNull()?.kind)
        assertEquals(
            "Without recents (the custom picker's list) nothing changes",
            listOf("Anthropic", "Console Google", "OpenAI"),
            ModelPickerSection.sections(models, emptySet()).map { it.title },
        )
    }

    @Test fun searchParsesTrailingLevelAndReturnPicksBestMatchAtThatLevel() {
        val parsedHigh = ModelSearch.parseLevelQuery("argon high")
        assertTrue(parsedHigh.terms == listOf("argon") && parsedHigh.level == "high")
        val parsedMax = ModelSearch.parseLevelQuery("opus max")
        assertTrue(parsedMax.terms == listOf("opus") && parsedMax.level == "max")
        val parsedPlain = ModelSearch.parseLevelQuery("high")
        assertTrue("Single word stays a model query", parsedPlain.terms == listOf("high") && parsedPlain.level == null)
    }
}

