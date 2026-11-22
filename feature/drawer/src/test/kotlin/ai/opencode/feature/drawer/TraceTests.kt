package ai.opencode.feature.drawer

import ai.opencode.core.diagnostics.ProblemReportBuilder
import ai.opencode.core.diagnostics.ProblemReportContext
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.devtools.traces.ProblemReportDestination
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class TraceUploadsTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "https://box.ts.net:4096", username = "opencode", password = "pw")
    private val context = ProblemReportContext(sessionID = "ses_1", serverID = "srv_1", screen = "Session", project = "opencode-ios", connection = "Connected")

    @Test
    fun theReportGoesToItsSessionElseTheMostRecentOne() {
        val older = OpenCodeSession(id = "ses_old", serverTitle = "Old", projectID = "p", directory = "/w", updatedAt = Instant.ofEpochSecond(100))
        val newer = OpenCodeSession(id = "ses_new", serverTitle = "New", projectID = "p", directory = "/w", updatedAt = Instant.ofEpochSecond(200))
        val child = OpenCodeSession(id = "ses_child", serverTitle = "Child", projectID = "p", directory = "/w", updatedAt = Instant.ofEpochSecond(300), parentID = "ses_new")
        val state = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(older, child, newer), activeSessionIDs = emptySet()))
        assertEquals(ProblemReportDestination(server = server, sessionID = "ses_1", directory = null), state.problemReportDestination(context))
        assertEquals(
            ProblemReportDestination(server = server, sessionID = "ses_new", directory = "/w"),
            state.problemReportDestination(ProblemReportContext(screen = "Home")),
        )
        // A subagent's report never posts into the subagent.
        assertEquals(
            ProblemReportDestination(server = server, sessionID = "ses_new", directory = "/w"),
            state.problemReportDestination(ProblemReportContext(sessionID = "ses_child", screen = "Subagent session")),
        )
        val running = state.copy(activeSessionParents = mapOf("ses_worker" to "ses_old"))
        assertEquals(
            ProblemReportDestination(server = server, sessionID = "ses_new", directory = "/w"),
            running.problemReportDestination(ProblemReportContext(sessionID = "ses_worker", screen = "Subagent session")),
        )
        val empty = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet()))
        assertNull(empty.problemReportDestination(ProblemReportContext(screen = "Home")))
    }

    @Test
    fun noReportOutputCarriesTheServerAddressOrUsername() {
        val secret = OpenCodeServer(url = "https://secret-box.ts.net:4096", username = "kituser", password = "pw")
        val home = HomeFeature.State(secret, OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet()))
        val fromHome = home.problemReportContext
        assertEquals(ProblemReportBuilder.serverKey(secret.persistenceID), fromHome.serverID)
        val output = fromHome.toString()
        assertFalse(output, output.contains("://"))
        assertFalse(output.contains("kituser"))
        assertFalse(output.contains("secret-box"))
    }
}
