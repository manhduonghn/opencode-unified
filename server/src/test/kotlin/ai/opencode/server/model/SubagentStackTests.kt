package ai.opencode.server.model

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class SubagentStackTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun session(id: String, parent: String? = null) = OpenCodeSession(
        id = id, serverTitle = id, projectID = "project", directory = "/project",
        updatedAt = Instant.ofEpochSecond(10), parentID = parent,
    )

    private val root get() = session("root")
    private val child get() = session("child", parent = "root")
    private val grandchild get() = session("grandchild", parent = "child")

    @Test
    fun descendantsFollowTheFamilyTree() {
        val family = listOf(root, child, grandchild, session("sibling", parent = "root"))
        assertEquals(listOf("grandchild"), SessionActivity.descendants("child", family).map { it.id })
        assertEquals(listOf("child", "grandchild", "sibling"), SessionActivity.descendants("root", family).map { it.id })
        assertTrue(SessionActivity.descendants("grandchild", family).isEmpty())
    }
}
