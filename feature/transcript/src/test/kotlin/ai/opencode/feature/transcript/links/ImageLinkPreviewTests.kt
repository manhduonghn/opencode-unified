package ai.opencode.feature.transcript.links

import ai.opencode.feature.media.ImageLinkPolicy
import ai.opencode.feature.transcript.TranscriptTunables
import ai.opencode.feature.transcript.markdown.MarkdownStream
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The `:feature:transcript` tests of iOS `OpenCodeTests/ImageLinkPreviewTests.swift`: the
 * `MarkdownStream` half of `completedLinkAndBlockIdentitySurviveEveryLaterDelta` and the layout
 * snapshot. `imageLinkBlocksParticipateInTheRowGrowthHold` is dropped with row growth (AGENTS.md,
 * Android-native transcript motion). The policy and loader
 * tests are `:feature:media`'s. Robolectric: bare-URL detection reads the framework's
 * `Patterns.WEB_URL`.

 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImageLinkPreviewTests {
    @Test
    fun completedLinkAndBlockIdentitySurviveEveryLaterDelta() {
        val stream = MarkdownStream(inlineCache = null)
        val source = "Here is [screenshot](https://example.com/shot.png).\n\nMore text follows."
        var first: Pair<Int, String>? = null
        for (end in source.indices) {
            val blocks = stream.update(source.substring(0, end + 1), streaming = true)
            val images = blocks.flatMap { block ->
                ImageLinkPolicy.links(block.linkTextBlock(), growing = block.id == blocks.lastOrNull()?.id).map { block.id to it }
            }
            val image = images.firstOrNull()
            if (image != null) {
                val seen = first
                if (seen != null) assertEquals(seen, image) else first = image
            } else {
                assertNull("A completed preview never disappears on a later delta", first)
            }
        }
        assertEquals("https://example.com/shot.png", first?.second)
    }

    @Test
    fun imageLinkSettingsArePartOfTheEquatableLayoutSnapshot() {
        val enabled = TranscriptTunables.layoutSnapshot()
        val taller = enabled.copy(linkMaxHeight = enabled.linkMaxHeight + 10)
        assertNotEquals(enabled, taller)
    }
}
