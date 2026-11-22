package ai.opencode.app

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.withDependencies
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.pushingSubagent
import ai.opencode.feature.session.FolderBrowserFeature
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.ShellOutputFeature
import ai.opencode.feature.session.beginAnswering
import ai.opencode.feature.session.reconcileInvariants
import ai.opencode.feature.session.withPermissionRequests
import ai.opencode.feature.settings.SettingsFeature
import ai.opencode.feature.system.clienttools.ClientToolsFeature
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceOpenSessionFeature
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.clienttools.ClientTool
import ai.opencode.server.clienttools.ClientToolCall
import ai.opencode.server.clienttools.ClientToolResult
import ai.opencode.server.clienttools.ClientToolsClient
import ai.opencode.server.clienttools.JsonRpcID
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** The root's pending request here is a permission, not a question form. */
class VoiceOpenSessionReviewTests {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()
    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
    private val now = Instant.ofEpochSecond(1_800_000_000)
    private val root = OpenCodeSession(
        id = "ses_root", serverTitle = "Root", projectID = "p", directory = "/project", updatedAt = Instant.parse("0001-01-01T00:00:00Z"),
    )
    private val child = root.copy(id = "ses_child", parentID = root.id)

    /** End-to-end: a real app tool request, lookup, Home navigation, drawer arrival and MCP reply. The dock stays up because it is the destination. */
    @Test fun opensUnderSettingsAndAcknowledgesThePendingRequestDock() = runBlocking {
        val call = ClientToolCall(
            connection = UUID.randomUUID(), id = JsonRpcID.Number(1), tool = ClientTool.voiceOpenSession,
            sessionID = "ses_voice", targetSessionID = root.id, toolCallID = "tool_open",
        )
        val lookup = CompletableDeferred<Unit>()
        val replies = Locked<List<ClientToolResult>>(emptyList())
        var state = shared.run {
            val home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(root), activeSessionIDs = emptySet()))
            val voice = VoiceFeature.State(server, root).withPhase(VoiceFeature.State.Phase.Live).copy(usesSessionDelegator = true)
            val detail = SessionFeature.State.session(server, root)
                .copy(voice = voice, hasLoadedPendingRequests = true)
                .withPermissionRequests(listOf(OpenCodePermissionRequest(id = "per_root", sessionID = root.id, action = "shell", resources = listOf("ls"))))
                .beginAnswering()
                .reconcileInvariants()
                .copy(
                    activePicker = SessionFeature.State.ActivePicker.session,
                    folderBrowser = FolderBrowserFeature.State(server = server),
                    shellOutput = ShellOutputFeature.State(
                        server = server, directory = root.directory,
                        shell = OpenCodeShell(id = "sh_output", command = "ls", sessionID = root.id, isUserCommand = false, isListed = true, startedAt = now),
                    ),
                )
            val withBrowser = detail.copy(browser = detail.browser.copy(pageID = UUID.randomUUID()))
            AppFeature.State.initial().copy(
                home = home.copy(
                    sessionDetail = withBrowser,
                    settings = SettingsFeature.State.whatsNew(server),
                    pendingDeletion = root,
                    isDrawerOpen = true,
                ),
            )
        }
        state = state.copy(clientTools = state.clientTools.copy(target = state.clientToolsTarget))
        val navigationID = state.home?.sessionDetail?.navigationID
        assertTrue(state.home?.sessionDetail?.requestDock?.isAnswering == true)
        val effects = detachedScope(coroutineContext)
        val store = shared.appStore(state, effects) {
            this[Clock] = TestClock()
            this[DateClient] = DateClient.constant(now)
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(getSession = { _, _ ->
                lookup.await()
                root
            })
            this[ClientToolsClient] = ClientToolsClient(connect = { emptyFlow() }, reply = { _, result ->
                replies.update { it + result }
                true
            })
        }
        store.exhaustive = false
        store.send(AppFeature.Action.ClientTools(ClientToolsFeature.Action.Delegate(ClientToolsFeature.DelegateAction.ToolCalled(call))))
        lookup.complete(Unit)
        val opened = AppFeature.Action.Home(
            HomeFeature.Action.detail(SessionFeature.Action.Delegate(SessionFeature.Delegate.OpenSession(root, toolID = "tool_open"))),
        )
        store.receive(opened, timeout = kotlin.time.Duration.parse("5s"))
        store.skipReceivedActions()
        assertTrue(replies.value.isEmpty())
        store.send(AppFeature.Action.Home(HomeFeature.Action.SessionDrawerArrived(isOpen = false)))
        store.receive(AppFeature.Action.VoiceOpenSession(VoiceOpenSessionFeature.Action.Replied(call)))
        store.send(AppFeature.Action.VoiceOpenSession(VoiceOpenSessionFeature.Action.Fallback(toolID = "tool_open", targetID = root.id, server = server)))
        store.finish()
        store.skipReceivedActions()
        val detail = store.state.home?.sessionDetail
        assertEquals(true, detail?.requestDock?.isAnswering)
        assertEquals(VoiceFeature.State.Phase.Live, detail?.voice?.phase)
        assertEquals(navigationID, detail?.navigationID)
        assertNull(detail?.shellOutput)
        assertNull(detail?.activePicker)
        assertNull(detail?.folderBrowser)
        assertEquals(false, detail?.browser?.isOpen)
        assertEquals(false, store.state.home?.isSettingsShown)
        assertNull(store.state.home?.pendingDeletion)
        assertEquals(1, store.state.home?.drawerCloseRequests)
        assertEquals(listOf(ClientToolResult(listOf(ClientToolResult.Content.Text("{\"ok\":true,\"status\":\"opened\"}")))), replies.value)
        effects.skipInFlightEffects()
    }

    @Test fun aPushedSubagentsPendingDockAllowsAcknowledgement() {
        val app = shared.run {
            val home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(root, child), activeSessionIDs = emptySet()))
            val voice = VoiceFeature.State(server, root).withPhase(VoiceFeature.State.Phase.Live)
            val detail = SessionFeature.State.session(server, root).copy(voice = voice)
            val pushed = withDependencies({ this[DateClient] = DateClient.constant(now) }) { home.copy(sessionDetail = detail).pushingSubagent(child) }
            val top = pushed.subagentStack.last()
            val answering = top.copy(hasLoadedPendingRequests = true)
                .withPermissionRequests(listOf(OpenCodePermissionRequest(id = "per_child", sessionID = child.id, action = "shell", resources = listOf("ls"))))
                .beginAnswering()
                .reconcileInvariants()
            AppFeature.State.initial().copy(home = pushed.copy(subagentStack = pushed.subagentStack.dropLast(1) + answering))
        }
        assertEquals(true, app.home?.topDetail?.requestDock?.isAnswering)
        assertTrue(app.openSessionEnvironment.screen.visible)
        assertFalse(app.screenRequestRefusal == null)
    }
}
