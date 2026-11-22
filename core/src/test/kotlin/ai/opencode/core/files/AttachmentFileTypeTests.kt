package ai.opencode.core.files

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentFileTypeTests {
    @Test fun mimeMirrorsDesktopNormalization() {
        assertEquals("application/pdf", AttachmentFileType.mime(fileName = "a.pdf", declared = "application/pdf", head = byteArrayOf(0x25, 0x50)))
        assertEquals("application/pdf", AttachmentFileType.mime(fileName = "a.pdf", declared = null, head = ByteArray(0)))
        assertEquals("image/png", AttachmentFileType.mime(fileName = "a.PNG", declared = "application/octet-stream", head = ByteArray(0)))
        assertEquals("text/plain", AttachmentFileType.mime(fileName = "a.json", declared = "application/json", head = ByteArray(0)))
        assertEquals("text/plain", AttachmentFileType.mime(fileName = "a.svg", declared = "image/svg+xml", head = "<svg/>".toByteArray()))
        assertEquals("video/quicktime", AttachmentFileType.mime(fileName = "a.mov", declared = "video/quicktime", head = byteArrayOf(0, 0, 0, 0x14)))
        assertEquals("text/plain", AttachmentFileType.mime(fileName = "Makefile", declared = null, head = "all:\n\techo".toByteArray()))
        assertEquals("application/octet-stream", AttachmentFileType.mime(fileName = "blob", declared = null, head = byteArrayOf(1, 2, 3, 0)))
    }

    @Test fun labelsMatchDesktopCards() {
        assertEquals("PDF", AttachmentFileType.label(name = "Report.pdf", mime = "application/pdf"))
        assertEquals("Swift", AttachmentFileType.label(name = "/tmp/x/App.swift", mime = "text/plain"))
        assertEquals("MOV", AttachmentFileType.label(name = "clip.mov", mime = "video/quicktime"))
        assertEquals("File", AttachmentFileType.label(name = ".gitignore", mime = "text/plain"))
        assertEquals("File", AttachmentFileType.label(name = "README", mime = "text/plain"))
    }

    @Test fun namesStayASinglePathComponent() {
        assertEquals("-..-etc-passwd", AttachmentFileType.sanitizedName("../../etc/passwd"))
        assertEquals("hidden", AttachmentFileType.sanitizedName("  .hidden "))
        assertEquals("file", AttachmentFileType.sanitizedName(""))
    }
}
