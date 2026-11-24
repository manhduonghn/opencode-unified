package ai.opencode.feature.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubApiTests {
    @Test
    fun cloneInputIsNormalizedToAGitHubUrl() {
        val expected = "https://github.com/owner/repo.git"
        for (input in listOf(
            "owner/repo",
            "owner/repo/",
            "owner/repo.git",
            "owner/repo.git/",
            "github.com/owner/repo",
            "www.github.com/owner/repo",
            "https://github.com/owner/repo",
            "https://www.github.com/owner/repo",
            "http://github.com/owner/repo.git",
        )) {
            assertEquals(input, expected, normalizeCloneUrl(input))
        }
        assertEquals("git@github.com:owner/repo.git", normalizeCloneUrl("git@github.com:owner/repo.git"))
    }

    @Test
    fun cloneInputRejectsOtherHostsAndPaths() {
        for (input in listOf(
            "",
            "   ",
            "repo",
            "gitlab.com/owner/repo",
            "https://example.com/owner/repo",
            "ssh://git@github.com/owner/repo",
            "owner/repo/extra",
            "../owner/repo",
            "owner/../repo",
            "owner/repo?tab=readme",
            "owner/repo#section",
            "owner/re po",
        )) {
            assertNull(input, normalizeCloneUrl(input).takeIf { it.isNotEmpty() })
        }
    }

    @Test
    fun repositoryNameHandlesHttpsAndSshRemotes() {
        assertEquals("repo", repoNameFromUrl("https://github.com/owner/repo.git"))
        assertEquals("repo", repoNameFromUrl("git@github.com:owner/repo.git"))
        assertEquals("repository", repoNameFromUrl(""))
    }

    @Test
    fun authStatusPrefersTheLoggedInLine() {
        assertEquals(
            "Logged in to github.com",
            parseAuthStatus("some noise\nLogged in to github.com using your account\n"),
        )
        assertEquals("", parseAuthStatus(""))
    }

    @Test
    fun repositoryListIgnoresToolChatterAroundJson() {
        val text = """
            - Reading repos…
            [{"nameWithOwner":"owner/repo","description":"A repo","url":"https://github.com/owner/repo"}]
        """.trimIndent()
        assertEquals(
            listOf(Repo("owner/repo", "A repo", "https://github.com/owner/repo")),
            parseRepos(text),
        )
        assertNull(parseRepos("not logged in"))
    }
}
