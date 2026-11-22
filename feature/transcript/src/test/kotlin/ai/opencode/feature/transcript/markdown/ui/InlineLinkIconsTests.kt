package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.ui.AppTheme
import ai.opencode.core.ui.OpenCodeColors
import ai.opencode.feature.transcript.TranscriptLayoutTuning
import ai.opencode.feature.transcript.markdown.InlineMarkdownParser
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/*
 * A link's icon slot is a word joiner carrying inline content, so it is one more character in the
 * presented text and an insertion in [PresentedText.edits].
 */
// Robolectric: the Chip fold and the icon slot's host parse with `android.net.Uri`.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InlineLinkIconsTests {
    @get:Rule val isolation = TestIsolationRule()

    private val device = "https://vercel.com/oauth/device?user_code=SPTL-FFCD"
    private val style = MarkdownStyle(TranscriptLayoutTuning(), OpenCodeColors.resolve(AppTheme.System, isDark = false))

    private fun decorate(source: String, linkStyle: InlineLinkStyle, isGrowing: Boolean): PresentedText =
        MarkdownPresentation.present(
            InlineMarkdownParser.parse(source),
            PresentationOptions(style.bodyColor, style.bodySize, linkStyle = linkStyle, isGrowing = isGrowing),
            style,
            targets = emptyMap(),
            density = Density(1f),
            links = null,
            siteIcon = { null },
        )

    private fun shown(presented: PresentedText) = presented.text.text.replace("\u2060", "")

    /** The character after each icon slot. */
    private fun slots(presented: PresentedText): List<Char> {
        val text = presented.text
        return text.getStringAnnotations(0, text.length)
            .filter { it.item.startsWith("icon:") }
            .map { text.text[it.end] }
    }

    private fun links(presented: PresentedText): List<String> =
        presented.text.getLinkAnnotations(0, presented.text.length).mapNotNull { (it.item as? LinkAnnotation.Clickable)?.tag }.distinct()

    @Test fun todayAndTextWithoutWebLinksAreUntouched() {
        val today = decorate("Open $device now", InlineLinkStyle.Today, isGrowing = false)
        assertEquals("Open $device now", today.text.text)
        assertTrue(today.inlineContent.isEmpty() && today.edits.isEmpty())
        val noLinks = decorate("No links, just `code`.", InlineLinkStyle.Address, isGrowing = false)
        assertEquals("No links, just code.", noLinks.text.text)
        assertTrue(noLinks.inlineContent.isEmpty() && noLinks.edits.isEmpty())
    }

    @Test fun iconBeforeLinksKeepsEveryCharacter() {
        val source = "Open $device and [the docs](https://vercel.com/docs/cli/login)."
        val decorated = decorate(source, InlineLinkStyle.Icon, isGrowing = false)
        assertEquals(InlineMarkdownParser.parse(source).text, shown(decorated))
        // Only the slots are inserted; no character of the text changes.
        assertTrue(decorated.edits.all { it.removed == 0 && it.inserted == 1 })
        assertEquals("On each link's first character", listOf('h', 't'), slots(decorated))
        assertEquals(listOf(device, "https://vercel.com/docs/cli/login"), decorated.fetchableLinks)
        // Taps and Copy Link still use the link's own URL.
        assertEquals(listOf(device, "https://vercel.com/docs/cli/login"), links(decorated))
    }

    @Test fun aChipFoldsABareURLToItsDomainAndAShortPath() {
        val decorated = decorate("Open $device and check.", InlineLinkStyle.Chip, isGrowing = false)
        assertEquals("Open vercel.com/oauth/device… and check.", shown(decorated))
        // One unbreakable run: word joiners between its characters.
        val text = decorated.text
        val chip = text.getLinkAnnotations(0, text.length).first { (it.item as? LinkAnnotation.Clickable)?.tag == device }
        assertTrue(text.text.substring(chip.start, chip.end).contains("vercel.com".toList().joinToString("\u2060")))
        assertEquals(
            "example.com/search…",
            MarkdownPresentation.displayed("https://example.com/search?q=oauth&page=2", InlineLinkStyle.Chip)?.replace("\u2060", ""),
        )
        assertEquals(
            "example.com/a/b…",
            MarkdownPresentation.displayed("https://www.example.com/a/b/c/d", InlineLinkStyle.Chip)?.replace("\u2060", ""),
        )
    }

    @Test fun linksWithTheirOwnTextKeepIt() {
        for (linkStyle in listOf(InlineLinkStyle.Icon, InlineLinkStyle.Chip, InlineLinkStyle.Address)) {
            val decorated = decorate("[Vercel CLI docs](https://vercel.com/docs/cli/login)", linkStyle, isGrowing = false)
            assertEquals("Vercel CLI docs", shown(decorated))
        }
    }

    @Test fun aCleanAddressDropsOnlyHTTPS() {
        val decorated = decorate("Open $device or http://example.com/plain", InlineLinkStyle.Address, isGrowing = false)
        assertEquals("Open vercel.com/oauth/device?user_code=SPTL-FFCD or http://example.com/plain", shown(decorated))
        assertEquals("example.com", MarkdownPresentation.displayed("https://www.example.com/", InlineLinkStyle.Address))
    }

    /** The slot is a character here. */
    @Test fun streamedArrivalsMapPastAFoldedURL() {
        val decorated = decorate("Open $device and check.", InlineLinkStyle.Address, isGrowing = false)
        val slot = 1
        val start = "Open ".length
        val after = start + device.length
        assertEquals(0, decorated.edits.mapOffset(0))
        assertEquals(start + slot, decorated.edits.mapOffset(start))
        assertEquals(after - "https://".length + slot, decorated.edits.mapOffset(after))
        assertEquals(after - "https://".length + 4 + slot, decorated.edits.mapOffset(after + 4))
        assertEquals("Inside the URL it stays inside", start + 3 + slot, decorated.edits.mapOffset(start + 3))
    }

    @Test fun aURLStillArrivingKeepsItsTextAndAsksForNothing() {
        val decorated = decorate("Open https://vercel.c", InlineLinkStyle.Chip, isGrowing = true)
        assertEquals("Open https://vercel.c", shown(decorated))
        assertEquals(1, slots(decorated).size)
        assertTrue(decorated.fetchableLinks.isEmpty())
        val settled = decorate("Open https://vercel.c", InlineLinkStyle.Chip, isGrowing = false)
        assertEquals(1, settled.fetchableLinks.size)
    }

    @Test fun privateLinksGetANeutralSlotAndNoDomain() {
        val decorated = decorate("See http://devbox.tail1234.ts.net:3000/preview and https://example.com/?token=abc", InlineLinkStyle.Icon, isGrowing = false)
        assertEquals(2, slots(decorated).size)
        assertTrue(decorated.fetchableLinks.isEmpty())
    }
}
