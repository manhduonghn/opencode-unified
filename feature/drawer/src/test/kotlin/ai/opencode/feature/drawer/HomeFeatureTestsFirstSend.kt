package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.store.TestStore
import ai.opencode.feature.composer.UndeliveredPrompts
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeatureTestSupport.Gate
import ai.opencode.feature.drawer.HomeFeatureTestSupport.Harness
import ai.opencode.feature.drawer.HomeFeatureTestSupport.detail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.distantPast
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.HomeFeatureTestSupport.settle
import ai.opencode.feature.drawer.HomeFeatureTestSupport.snapshot
import ai.opencode.feature.session.BindingAction
import ai.opencode.feature.session.SessionFeature
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ai.opencode.core.testing.TestIsolationRule

/**
 * A new session's first prompt is sent even when the screen changes while the session is still
 * being created.
 */
class FirstSendSurvivesNavigationTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun Harness.makeStore(
        projectID: String,
        createSession: suspend (OpenCodeServer, String, String?) -> OpenCodeSession,
        sendPrompt: suspend (OpenCodeServer, String, String) -> Unit = { _, _, _ -> },
    ): TestStore<HomeFeature.State, HomeFeature.Action> {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val project = OpenCodeProject(id = projectID, name = "experiments", directory = "/tmp/experiments")
        val store = store(state { HomeFeature.State(server, snapshot(emptyList(), projects = listOf(project))) }) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = ImmediateClock()
            this[UndeliveredPrompts] = UndeliveredPrompts()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                createSession = createSession,
                message = { _, _, _ -> throw OpenCodeClientError("Missing", statusCode = 404) },
                sendPrompt = { server, sessionID, text, _, _, _, _ -> sendPrompt(server, sessionID, text) },
                waitForIdle = { _, _ -> awaitCancellation() },
                getSession = { _, id -> OpenCodeSession(id = id, serverTitle = "", projectID = projectID, directory = "/tmp/experiments", updatedAt = distantPast) },
                transcript = { _, _, _ -> emptyList() },
                sessionFamily = { _, root -> OpenCodeSessionFamily(listOf(root), emptySet()) },
                runningShells = { _, _ -> emptyList() },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false
        return store
    }

    @Test
    fun firstPromptIsDeliveredEvenWhenTheReaderLeavesMidCreate() = homeTest { h ->
        val created = OpenCodeSession(id = "ses_new", serverTitle = "", projectID = "proj_x", directory = "/tmp/experiments", updatedAt = distantPast)
        val createStarted = Gate()
        val createGate = Gate()
        val delivered = Channel<Pair<String, String>>(Channel.UNLIMITED)
        val store = h.makeStore(
            projectID = "proj_x",
            createSession = { _, _, _ ->
                createStarted.yield()
                createStarted.finish()
                createGate.await()
                created
            },
            sendPrompt = { _, sessionID, text ->
                delivered.trySend(sessionID to text)
                delivered.close()
            },
        )

        store.send(Action.NewSessionTapped)
        store.send(detail(SessionFeature.Action.Binding(BindingAction.Draft("Why did the Slack bot fail?"))))
        store.send(detail(SessionFeature.Action.SendTapped))
        createStarted.await()
        // The reader opens something else before the create returns.
        store.send(Action.NewSessionTapped)
        createGate.yield()
        createGate.finish()

        val sent = delivered.receiveCatching().getOrNull()
        assertEquals("ses_new", sent?.first)
        assertEquals("Why did the Slack bot fail?", sent?.second)
        h.skipInFlightEffects()
    }

    @Test
    fun aSendThatFailsAfterItsScreenLeftWaitsInTheSessionListAsAnUnsentSession() = homeTest { h ->
        val createStarted = Gate()
        val createGate = Gate()
        val store = h.makeStore(
            projectID = "proj_y",
            createSession = { _, _, _ ->
                createStarted.yield()
                createStarted.finish()
                createGate.await()
                throw OpenCodeClientError("The server returned HTTP 400.", statusCode = 400)
            },
        )

        store.send(Action.NewSessionTapped)
        store.send(detail(SessionFeature.Action.Task))
        store.send(detail(SessionFeature.Action.Binding(BindingAction.Draft("Keep this draft"))))
        store.send(detail(SessionFeature.Action.SendTapped))
        createStarted.await()
        // Leaving to another screen tears the first one down mid-create.
        store.send(Action.NewSessionTapped)
        createGate.yield()
        createGate.finish()
        repeat(40) {
            if (store.state.unsentDrafts.firstOrNull()?.isFailed != true) {
                settle(1)
                store.send(Action.UnsentDraftsChanged)
            }
        }
        // The new session stays blank, and the failed one waits in the list.
        store.send(detail(SessionFeature.Action.Task))
        assertEquals(emptyList<String>(), store.state.sessionDetail?.failedSends?.map { it.pending.text })
        val unsent = store.state.unsentDrafts.single()
        assertEquals("Keep this draft", unsent.title)
        assertTrue(unsent.isFailed)

        store.send(Action.SessionTapped(unsent.sessionID))
        store.send(detail(SessionFeature.Action.Task))
        assertEquals(listOf("Keep this draft"), store.state.sessionDetail?.failedSends?.map { it.pending.text })
        assertTrue(store.state.sessionDetail?.items?.any { it is OpenCodeTranscriptItem.UserText && it.text == "Keep this draft" } == true)
        h.skipInFlightEffects()
    }
}
