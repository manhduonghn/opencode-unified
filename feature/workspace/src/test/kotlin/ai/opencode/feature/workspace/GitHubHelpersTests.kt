package ai.opencode.feature.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubHelpersTests {
    @Test
    fun normalizeCloneUrl_acceptsFullUrlAndAddsGitSuffix() {
        assertEquals(
            "https://github.com/octocat/hello.git",
            normalizeCloneUrl("https://github.com/octocat/hello/"),
        )
    }

    @Test
    fun normalizeCloneUrl_acceptsHostAndOwnerSlug() {
        assertEquals(
            "https://github.com/octocat/hello.git",
            normalizeCloneUrl("github.com/octocat/hello"),
        )
    }

    @Test
    fun normalizeCloneUrl_acceptsOwnerSlug() {
        assertEquals(
            "https://github.com/octocat/hello.git",
            normalizeCloneUrl(" octocat/hello "),
        )
    }

    @Test
    fun normalizeCloneUrl_keepsSshAndNonGithubUrls() {
        assertEquals(
            "git@github.com:octocat/hello.git",
            normalizeCloneUrl("git@github.com:octocat/hello.git"),
        )
        assertEquals(
            "https://gitlab.com/octocat/hello",
            normalizeCloneUrl("https://gitlab.com/octocat/hello"),
        )
    }

    @Test
    fun normalizeCloneUrl_rejectsBlankAndBareOwner() {
        assertEquals("", normalizeCloneUrl("  "))
        assertEquals("", normalizeCloneUrl("octocat"))
    }

    @Test
    fun repoNameFromUrl_stripsGitSuffix() {
        assertEquals("hello", repoNameFromUrl("https://github.com/octocat/hello.git"))
        assertEquals("repository", repoNameFromUrl("https://github.com/octocat/"))
    }

    @Test
    fun parseRepos_readsGhJsonWithNoise() {
        val json = """
            [{"nameWithOwner":"octocat/hello","description":"Demo repo","url":"https://github.com/octocat/hello"}]
        """.trimIndent()
        val repos = parseRepos("warning line\n$json\n")

        assertEquals(1, repos!!.size)
        assertEquals("octocat/hello", repos.first().nameWithOwner)
        assertEquals("Demo repo", repos.first().description)
        assertEquals("https://github.com/octocat/hello", repos.first().url)
    }

    @Test
    fun parseRepos_toleratesNullDescription() {
        val repos = parseRepos("""[{"nameWithOwner":"o/r","description":null,"url":"u"}]""")
        assertEquals("", repos!!.first().description)
    }

    @Test
    fun parseRepos_returnsNullWithoutJson() {
        assertNull(parseRepos("not logged in"))
    }

    @Test
    fun parseAuthStatus_prefersLoggedInLine() {
        val status = parseAuthStatus(
            "- github.com\n  Logged in to github.com as octocat\n  Token: ********\n",
        )
        assertEquals("Logged in to github.com as octocat", status)
    }

    @Test
    fun parseAccount_skipsJsonAndStackTraces() {
        val login = parseAccount("java.lang.Exception: boom\n{\n  \"login\": \"octocat\"\n}\noctocat\n")
        assertEquals("octocat", login)
    }

    @Test
    fun repoError_prefersFirstUsefulLine() {
        assertEquals(
            "authentication failed",
            repoError("java.lang.Exception: boom\nauthentication failed\nmore", "fallback"),
        )
        assertEquals("fallback", repoError("java.lang.Exception: boom\n", "fallback"))
    }

    @Test
    fun loginFailureCarriesNoLocalizedText() {
        val ex = GitHubLoginException(GitHubLoginFailure.Expired)
        assertEquals(GitHubLoginFailure.Expired, ex.failure)
        assertEquals(GitHubLoginFailure.Expired.name, ex.message)
    }
}
