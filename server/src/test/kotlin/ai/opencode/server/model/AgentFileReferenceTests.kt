package ai.opencode.server.model

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Recognizing files the agent mentions, mirroring desktop's local-link rules (session-ui
 * markdown-image.ts, markdown-inline-code-kind.ts). Link URLs are strings on Android.
 */
class AgentFileReferenceTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun target(location: String, relativePath: String, absolutePath: String) =
        AgentFileReference.Target(location, relativePath, absolutePath)

    @Test
    fun inlineCodePathsNeedARecognizableFileName() {
        assertEquals("OpenCode/SessionView.swift", AgentFileReference.fromInlineCode("OpenCode/SessionView.swift")?.path)
        assertEquals("SessionView.swift", AgentFileReference.fromInlineCode("SessionView.swift")?.path)
        assertEquals(AgentFileReference.Kind.Video, AgentFileReference.fromInlineCode("/tmp/demo.mp4")?.kind)
        assertEquals(AgentFileReference.Kind.Pdf, AgentFileReference.fromInlineCode("~/Downloads/spec.pdf")?.kind)
        assertEquals(AgentFileReference.Kind.Text, AgentFileReference.fromInlineCode("Makefile")?.kind)
        assertNotNull(AgentFileReference.fromInlineCode("types/index.d.ts"))

        // Code, routes, flags, URLs, and prose stay plain inline code.
        for (text in listOf(
            "store.send", "/api/fs/read", "--port", "https://example.com/a.pdf", "and/or", "a b.swift",
            "foo()", "Config.lock", "v1.2", "e.g.", "docs/", "@scope/pkg", "\$HOME/x.txt", "~",
        )) {
            assertNull(text, AgentFileReference.fromInlineCode(text))
        }
    }

    @Test
    fun lineSuffixesAreSplitOff() {
        assertEquals(
            AgentFileReference("OpenCode/SessionView.swift", 42),
            AgentFileReference.fromInlineCode("OpenCode/SessionView.swift:42"),
        )
        assertEquals(AgentFileReference("main.go", 10), AgentFileReference.fromInlineCode("main.go:10:4"))
        assertEquals(AgentFileReference("src/app.ts", 7), AgentFileReference.fromLink("src/app.ts#L7"))
        // A dot is legal in a URL scheme.
        assertEquals(AgentFileReference("SessionView.swift", 42), AgentFileReference.fromLink("SessionView.swift:42"))
        assertEquals(AgentFileReference("README.md", 7), AgentFileReference.fromLink("README.md?plain=1#L7"))
    }

    @Test
    fun localLinksAreFilesAndWebLinksAreNot() {
        assertEquals("docs/spec.pdf", AgentFileReference.fromLink("docs/spec.pdf")?.path)
        assertEquals("/tmp/demo clip.mov", AgentFileReference.fromLink("/tmp/demo%20clip.mov")?.path)
        assertEquals("/Users/kit/a.mp4", AgentFileReference.fromLink("file:///Users/kit/a.mp4")?.path)
        assertNull(AgentFileReference.fromLink("file://other-host/a.mp4"))
        assertNull(AgentFileReference.fromLink("https://example.com/a.mp4"))
        assertNull(AgentFileReference.fromLink("mailto:kit@example.com"))
        assertNull(AgentFileReference.fromLink("#section"))
        assertEquals("README.md", AgentFileReference.fromLink("README.md?plain=1")?.path)
    }

    @Test
    fun kindsChooseTheViewer() {
        assertEquals(AgentFileReference.Kind.Video, AgentFileReference("a.mov").kind)
        assertEquals(AgentFileReference.Kind.Audio, AgentFileReference("a.m4a").kind)
        assertEquals(AgentFileReference.Kind.Image, AgentFileReference("a.png").kind)
        assertEquals(AgentFileReference.Kind.Pdf, AgentFileReference("a.pdf").kind)
        assertEquals(AgentFileReference.Kind.Text, AgentFileReference("a.swift").kind)
        assertEquals(AgentFileReference.Kind.Document, AgentFileReference("a.zip").kind)
        assertEquals("Video · MP4", AgentFileReference("a.mp4").typeLabel)
        assertFalse(AgentFileReference("a.swift").kind.showsCard)
        assertTrue(AgentFileReference("a.pdf").kind.showsCard)
    }

    @Test
    fun targetsReadInsideTheSessionOrFromTheFilesOwnFolder() {
        val directory = "/Users/kit/code/app"
        assertEquals(
            target(directory, "OpenCode/A.swift", "/Users/kit/code/app/OpenCode/A.swift"),
            AgentFileReference("OpenCode/A.swift").target(directory),
        )
        assertEquals("docs/spec.pdf", AgentFileReference("/Users/kit/code/app/docs/./spec.pdf").target(directory)?.relativePath)
        // Outside the session: the file's folder becomes the location.
        assertEquals(target("/tmp", "demo.mp4", "/tmp/demo.mp4"), AgentFileReference("/tmp/demo.mp4").target(directory))
        assertEquals(
            target("/Users/kit/code/other", "x.md", "/Users/kit/code/other/x.md"),
            AgentFileReference("../other/x.md").target(directory),
        )
        assertEquals("/Users/kit/Downloads/a.pdf", AgentFileReference("~/Downloads/a.pdf").target(directory)?.absolutePath)
        assertNull("No home to expand", AgentFileReference("~/a.pdf").target("/srv/app"))
        assertNull(AgentFileReference("a.pdf").target(null))
        // A prefix that isn't a folder boundary is outside.
        assertEquals("/Users/kit/code/app2", AgentFileReference("/Users/kit/code/app2/a.md").target(directory)?.location)
    }

    /**
     * A Windows server reports `C:\…` directories: files inside the session read from its location
     * as the server spelled it.
     */
    @Test
    fun windowsServersResolveDriveAndBackslashPaths() {
        val directory = """C:\Users\kit\Desktop\bbb"""
        assertEquals(
            target(directory, "Dashboard.html", "C:/Users/kit/Desktop/bbb/Dashboard.html"),
            AgentFileReference("Dashboard.html").target(directory),
        )
        assertEquals("src/app.ts", AgentFileReference("""src\app.ts""").target(directory)?.relativePath)
        assertEquals(
            target(directory, "README.md", "C:/users/kit/desktop/bbb/README.md"),
            AgentFileReference("""c:\users\kit\desktop\bbb\docs\..\README.md""").target(directory),
        )
        assertEquals(directory, AgentFileReference("C:/Users/kit/Desktop/bbb/a.pdf").target(directory)?.location)
        // Outside the session: the file's folder, in the server's own spelling.
        assertEquals(
            target("""D:\media""", "clip.mp4", "D:/media/clip.mp4"),
            AgentFileReference("""D:\media\clip.mp4""").target(directory),
        )
        assertEquals("""C:\""", AgentFileReference("""C:\a.log""").target(directory)?.location)
        assertEquals("C:/temp/a.log", AgentFileReference("""\temp\a.log""").target(directory)?.absolutePath)
        assertEquals("C:/Users/kit/Downloads/a.pdf", AgentFileReference("~/Downloads/a.pdf").target(directory)?.absolutePath)
        assertEquals("""C:\Users\kit\Desktop\bbb2""", AgentFileReference("../bbb2/x.md").target(directory)?.location)
        assertEquals("Dashboard.html", AgentFileReference("""C:\Users\kit\Desktop\bbb\Dashboard.html""").name)
        assertNull(AgentFileReference("""C:\""").target(directory))
        // Links and inline code name Windows files too.
        assertEquals("C:/Users/kit/a.pdf", AgentFileReference.fromLink("C:/Users/kit/a.pdf")?.path)
        assertEquals("C:/Users/kit/a.pdf", AgentFileReference.fromLink("""C:\Users\kit\a.pdf""")?.path)
        assertEquals("C:/Users/kit/a.pdf", AgentFileReference.fromLink("file:///C:/Users/kit/a.pdf")?.path)
        assertEquals("Dashboard.html", AgentFileReference.fromInlineCode("""C:\Users\kit\Desktop\bbb\Dashboard.html""")?.name)
    }

    @Test
    fun linkURLsRoundTrip() {
        val file = AgentFileReference("/tmp/a clip?.mp4", 3)
        assertEquals(file, AgentFileReference.fromLinkURL(file.linkURL))
        assertNull(AgentFileReference.fromLinkURL("https://example.com"))
    }

    @Test
    fun readRequestScopesToTheLocation() {
        val server = OpenCodeServer(url = "https://box.ts.net:4096", username = "opencode", password = "pw")
        val target = AgentFileReference.Target(location = "/tmp/x y", relativePath = "my clip.mp4", absolutePath = "/tmp/x y/my clip.mp4")
        val request = requireNotNull(server.fileReadRequest(target.location, target.relativePath))
        assertEquals(
            "https://box.ts.net:4096/api/fs/read/my%20clip.mp4?location%5Bdirectory%5D=/tmp/x%20y",
            request.url.toString(),
        )
        assertEquals(server.basicAuthorization, request.header("Authorization"))
    }
}

class AgentFileMarkdownTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun toolRowsOfferViewableFiles() {
        fun call(name: String, input: Map<String, OpenCodeJSONValue>) = OpenCodeToolCall(
            callID = "c", name = name, status = OpenCodeToolCall.Status.Completed,
            input = OpenCodeJSONValue.Object(input), output = emptyList(), metadata = emptyMap(),
        )
        assertEquals("/repo/a.swift", call("write", mapOf("path" to OpenCodeJSONValue.Str("/repo/a.swift"))).fileReference?.path)
        assertEquals(AgentFileReference.Kind.Pdf, call("read", mapOf("filePath" to OpenCodeJSONValue.Str("docs/spec.pdf"))).fileReference?.kind)
        assertNull(call("list", mapOf("path" to OpenCodeJSONValue.Str("/repo/src"))).fileReference)
        assertNull(call("glob", mapOf("path" to OpenCodeJSONValue.Str("src/*.ts"))).fileReference)
        assertNull(call("shell", mapOf("path" to OpenCodeJSONValue.Str("a.swift"), "command" to OpenCodeJSONValue.Str("ls"))).fileReference)
    }
}
