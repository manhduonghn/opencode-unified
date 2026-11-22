package ai.opencode.feature.transcript.user

import ai.opencode.server.OpenCodeServer
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * `toolMosaicTakesALoadedShapeRevisesItsRowAndKeepsItThroughAPurge` hosts the mosaic in a window;
 * its shape logic is `TranscriptLayoutTests`' `ToolImageMosaic.aspects`/`change`.
 */
class MediaReferenceTests {
    private val hashA = "a1".repeat(32)

    @Test
    fun loadKeyChangesWhenTheImageGoes() {
        val server = OpenCodeServer(url = "https://example.invalid", username = "user", password = "secret")
        val shown = ToolImageMosaicTile.loadKey(server, hashA, attempt = 0, hasImage = true)
        val purged = ToolImageMosaicTile.loadKey(server, hashA, attempt = 0, hasImage = false)
        val retried = ToolImageMosaicTile.loadKey(server, hashA, attempt = 1, hasImage = false)
        assertNotEquals("a purge under a warm tile starts its load again", shown, purged)
        assertNotEquals(purged, retried)
    }
}
