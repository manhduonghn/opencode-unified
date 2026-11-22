package ai.opencode.server.model

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MediaFileOriginalTests {
    @get:Rule val isolation = TestIsolationRule()
    private val hash = "a".repeat(64)
    private val file = OpenCodeTranscriptFile(id = "chip", name = "Résumé 2026.pdf", mime = "application/pdf", mediaHash = hash)

    @Test fun hashOriginalIsNeverAWorkspacePath() {
        val reference = requireNotNull(file.mediaViewerReference)
        assertEquals(file.name, reference.name)
        assertEquals("media:$hash", reference.path)
        assertEquals(AgentFileReference.Kind.Pdf, reference.kind)
        assertNull(reference.target("/workspace"))
    }

    @Test fun missingOriginalAndPlainTextStayInert() {
        assertNull(file.copy(mediaHash = null).mediaViewerReference)
        assertNull(file.copy(mediaHash = "../invalid").mediaViewerReference)
        assertNull(file.copy(isUnavailable = true).mediaViewerReference)
        assertNull(file.copy(mime = "text/plain; charset=utf-8").mediaViewerReference)
        assertNull(file.copy(mime = "Image/PNG").mediaViewerReference)
    }

    @Test fun extensionlessMediaUsesMIMEAndSafeExportName() {
        val clip = file.copy(name = "../Folder/Clip", mime = "Video/MP4")
        val video = requireNotNull(clip.mediaViewerReference)
        assertEquals("video/mp4", video.mime)
        assertEquals(AgentFileReference.Kind.Video, video.kind)
        assertTrue(video.kind.isVisual)
        assertFalse(video.name.contains('/'))
        val audio = requireNotNull(clip.copy(mime = "audio/mpeg").mediaViewerReference)
        assertEquals(AgentFileReference.Kind.Audio, audio.kind)
        assertFalse(audio.kind.isVisual)
    }

    @Test fun sizeUsesBothSourcesAndRejectsUnknownValues() {
        val large = 200_000_001L
        val original = requireNotNull(file.copy(byteCount = large).mediaViewerReference?.media)
        assertEquals(large, original.downloadSize(null))
        assertEquals(large, original.downloadSize(1))
        val unknown = original.copy(byteCount = -1)
        assertNull(unknown.downloadSize(null))
        assertEquals(large, unknown.downloadSize(large))
    }
}
