package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.WorkingRun
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class SessionFeatureTestsSending {
    @get:Rule val isolation = TestIsolationRule()

    private val testServer = OpenCodeServer(url = "http://localhost:9", username = "", password = "")

    private fun testSession(id: String, title: String) =
        OpenCodeSession(id = id, serverTitle = title, projectID = "p", directory = "/project", updatedAt = Instant.now())

    /**
     * Each session screen counts its runs from 1, so a token from the number alone collided: the
     * next session's Starting Up and finale read the last one's times and skipped.
     */
    @Test
    fun twoSessionsInARowEachGetStartingUpAndAFinale() = homeTest { h ->
        val first = testSession(id = "ses_a", title = "A")
        val second = testSession(id = "ses_b", title = "B")
        var home = h.state { HomeFeature.State(testServer, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(first, second), activeSessionIDs = emptySet())) }
        val tokens = mutableListOf<Set<String>>()

        suspend fun run(detail: SessionFeature.State, session: OpenCodeSession): SessionFeature.State {
            val user = OpenCodeTranscriptItem.UserText(id = "msg_user_${session.id}", text = "Check")
            val answered = listOf(user, OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_reply_${session.id}-text-0", text = ""))
            val store = h.sessionStore(detail.copy(items = listOf(user))) {
                this[UuidClient] = UuidClient.incrementing()
                this[Clock] = ImmediateClock()
                this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                    getSession = { _, _ -> session },
                    transcript = { _, _, _ -> answered },
                    message = { _, _, _ -> answered },
                    pendingInputs = { _, _ -> emptyList() },
                    familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                )
            }
            store.exhaustive = false
            store.send(SessionFeature.Action.ExternalRunStateChanged(isRunning = true))
            val start = store.state.workingRun.startID
            assertNotNull("${session.title}: Starting Up", start)
            store.send(SessionFeature.Action.TranscriptResponse(sessionID = session.id, result = Result.success(answered)))
            store.send(SessionFeature.Action.ExternalRunStateChanged(isRunning = false))
            val finale = store.state.workingRun.finale
            assertEquals("${session.title}: a finale", WorkingRun.Finale.Kind.done, finale?.kind)
            tokens += listOfNotNull(start, finale?.id).map { WorkingRun.token(navigationID = store.state.navigationID, id = it) }.toSet()
            return store.state
        }

        home = h.state { home.openingDetail(first, cachedItems = emptyList()) }
        home = home.copy(sessionDetail = run(home.sessionDetail!!, first))
        home = h.state { home.openingDetail(second, cachedItems = emptyList()) }
        home = home.copy(sessionDetail = run(home.sessionDetail!!, second))
        assertTrue("Each session's runs time themselves", tokens[0].intersect(tokens[1]).isEmpty())

        // A call keeps the detail's identity across the switch; the numbers carry on.
        home = h.state { home.copy(sessionDetail = home.sessionDetail!!.copy(voice = VoiceFeature.State(testServer, second))) }
        val kept = home.sessionDetail!!.navigationID
        home = h.state { home.openingDetail(first, cachedItems = emptyList()) }
        assertEquals(kept, home.sessionDetail?.navigationID)
        home = home.copy(sessionDetail = home.sessionDetail!!.copy(voice = null))
        run(home.sessionDetail!!, first)
        assertTrue("A detail that kept its identity still names new runs", tokens[2].intersect(tokens[1]).isEmpty())
        h.skipInFlightEffects()
    }
}
