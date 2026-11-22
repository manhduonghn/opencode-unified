package ai.opencode.server.model

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.live.directoryListing
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NewSessionDraftTests {
    @get:Rule val isolation = TestIsolationRule()

    private val projects = (1..7).map { OpenCodeProject(id = "p$it", name = "project-$it", directory = "/srv/code/project-$it") }

    @Test
    fun aFolderThatIsAKnownProjectChoosesThatProject() {
        assertEquals(projects[2], OpenCodeProject.folder("/srv/code/project-3", projects))
        assertEquals("/", OpenCodeProject.folder("/", projects).name)
    }

    // MARK: Listings

    @Test
    fun listedEntriesResolveAgainstTheServersLocation() {
        // What V2 answered for `?path=/Users` from a server whose default location is /Users/kit.
        val listing = directoryListing(
            location = "/Users/kit", path = "/Users",
            entries = listOf("../Shared/" to "directory", "./" to "directory", "../.localized" to "file", "../.hidden/" to "directory"),
        )
        assertEquals("/Users", listing.directory)
        assertEquals("By name, hidden folders last, no files", listOf("/Users/kit", "/Users/Shared", "/Users/.hidden"), listing.folders)
        val home = directoryListing(location = "/Users/kit", path = null, entries = listOf("code/" to "directory", "Desktop/" to "directory"))
        assertEquals("/Users/kit", home.directory)
        assertEquals(listOf("/Users/kit/code", "/Users/kit/Desktop"), home.folders)
    }
}
