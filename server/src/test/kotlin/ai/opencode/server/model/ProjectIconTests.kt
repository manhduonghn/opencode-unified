package ai.opencode.server.model

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.live.decodeStreamFrame
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ProjectIconTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun derivesDisplayNameAndDisambiguatedInitials() {
        // Explicit name wins over repository and canonical folder.
        assertEquals("Board", ProjectMark.displayName(name = "Board", repository = "team-board", canonical = "/src/rewrite"))
        // Repository name from git remote wins when no custom name is set.
        assertEquals("team-board", ProjectMark.displayName(name = null, repository = "team-board", canonical = "/src/rewrite"))
        assertEquals(
            "hex",
            ProjectMark.displayName(name = null, repository = "git@github.com:anomalyco/hex.git", canonical = "/Users/kit/code/open-source/voice-control"),
        )
        assertEquals(
            "team-board",
            ProjectMark.displayName(
                name = null,
                repository = "https://github.com/kitlangton/team-board.git",
                canonical = "/Users/kit/code/worktrees/team-board/rewrite",
            ),
        )
        assertEquals("tool", ProjectMark.displayName(name = null, repository = "ssh://git@gitlab.com/group/sub/tool.git/", canonical = "/src/checkout"))
        // Known gaps (`file:` remotes, `host:path` without `user@`, or no remote) fall back to the folder basename.
        assertEquals("rewrite", ProjectMark.displayName(name = null, repository = "file:///tmp/repo.git", canonical = "/src/rewrite/"))
        assertEquals("rewrite", ProjectMark.displayName(name = null, repository = "github.com:acme/team-board.git", canonical = "/src/rewrite/"))
        assertEquals("rewrite", ProjectMark.displayName(name = null, repository = null, canonical = "/src/rewrite/"))
        assertEquals("scrawl", ProjectMark.displayName(name = null, repository = null, canonical = "/Users/kit/code/open-source/scrawl"))

        // Org prefixes (`opencode-`, `anomaly-`) strip only when followed by a disambiguating suffix.
        assertEquals("O", ProjectMark.initial("opencode"))
        assertEquals("O", ProjectMark.initial("OpenCode"))
        assertEquals("C", ProjectMark.initial("opencode-console"))
        assertEquals("C", ProjectMark.initial("OpenCode Console"))
        assertEquals("I", ProjectMark.initial("opencode-ios"))
        assertEquals("I", ProjectMark.initial("opencode-ios-sf-picks"))
        assertEquals("S", ProjectMark.initial("opencode-slack"))
        assertEquals("A", ProjectMark.initial("anomaly"))
        assertEquals("R", ProjectMark.initial("anomaly-ramp"))
        assertEquals("H", ProjectMark.initial("hex"))
        assertEquals("T", ProjectMark.initial("team-board"))
        assertEquals("K", ProjectMark.initial("knobs"))
        assertEquals("?", ProjectMark.initial(""))
    }

    @Test
    fun decodesProjectIconSchemaAndFollowsDesktopPrecedence() {
        // Current V2 schema, as served by /openapi.json and desktop beta.
        val discoveredJSON = """
            {
              "url": "data:image/svg+xml;base64,PHN2Zy8+"
            }
        """.trimIndent()
        val discovered = Json.decodeFromString(OpenCodeProjectIconSerializer, discoveredJSON)
        assertEquals("data:image/svg+xml;base64,PHN2Zy8+", discovered.url)
        assertEquals(discovered.url, discovered.resolvedSource)

        // Round-trip through serialization (as used by cachedHomeSnapshots).
        val encoded = Json.encodeToString(OpenCodeProjectIconSerializer, discovered)
        val roundTripped = Json.decodeFromString(OpenCodeProjectIconSerializer, encoded)
        assertEquals(discovered, roundTripped)
        assertEquals(discovered.url, roundTripped.resolvedSource)

        // Explicit color choice suppresses automatic URL and OpenCode icons.
        val colored = OpenCodeProjectIcon(url = "https://example.com/icon.png", color = "blue")
        assertNull(colored.resolvedSource)
        assertNull(ProjectMark.avatarSource(projectID = "012780c4098d08caa4ea8c479ed0a4690489f38d", icon = colored))

        // User override wins over color, the built-in OpenCode icon, and URL.
        val overridden = OpenCodeProjectIcon(url = "https://example.com/icon.png", override = "data:image/png;base64,iVBORw0KGgo=", color = "blue")
        assertEquals("data:image/png;base64,iVBORw0KGgo=", overridden.resolvedSource)
        assertEquals(
            "data:image/png;base64,iVBORw0KGgo=",
            ProjectMark.avatarSource(projectID = "012780c4098d08caa4ea8c479ed0a4690489f38d", icon = overridden),
        )

        // Built-in OpenCode project IDs win over URL when not overridden or colored.
        for (openCodeID in listOf(
            "012780c4098d08caa4ea8c479ed0a4690489f38d",
            "476fbfe6befa9e63ddd7f9ae71655999a0104256",
            "4b0ea68d7af9a6031a7ffda7ad66e0cb83315750",
        )) {
            assertEquals("https://opencode.ai/favicon.svg", ProjectMark.avatarSource(projectID = openCodeID, icon = null))
            assertEquals("https://opencode.ai/favicon.svg", ProjectMark.avatarSource(projectID = openCodeID, icon = discovered))
        }

        // The superseded discovery proposal must not override the shipped URL or hit a nonexistent route.
        val obsolete = """{"url":"data:current","discovered":{"url":"/api/project/abc/icon?v=1"}}"""
        val current = Json.decodeFromString(OpenCodeProjectIconSerializer, obsolete)
        assertEquals("data:current", ProjectMark.avatarSource(projectID = "abc", icon = current))
    }

    @Test
    fun decodesProjectUpdatedStreamFrame() {
        val frame =
            """{"type":"project.updated","data":{"id":"prj_hex","canonical":"/Users/kit/code/open-source/voice-control","vcs":"git","repository":"hex","icon":{"url":"data:image/svg+xml;base64,PHN2Zy8+"},"time":{"created":1,"updated":2,"active":3},"sandboxes":[]}}"""
        val event = decodeStreamFrame(frame)
        assertEquals(
            OpenCodeServerEvent.ProjectUpdated(
                OpenCodeProject(
                    id = "prj_hex",
                    name = "hex",
                    directory = "/Users/kit/code/open-source/voice-control",
                    repository = "hex",
                    icon = OpenCodeProjectIcon(url = "data:image/svg+xml;base64,PHN2Zy8+"),
                ),
            ),
            event,
        )
    }
}
