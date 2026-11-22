package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeatureTestSupport.Gate
import ai.opencode.feature.drawer.HomeFeatureTestSupport.Harness
import ai.opencode.feature.drawer.HomeFeatureTestSupport.client
import ai.opencode.feature.drawer.HomeFeatureTestSupport.date
import ai.opencode.feature.drawer.HomeFeatureTestSupport.detail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.HomeFeatureTestSupport.snapshot
import ai.opencode.feature.drawer.VoiceHomeTestSupport.receiveVoice
import ai.opencode.feature.drawer.catchup.CatchUpFeature
import ai.opencode.feature.session.FolderBrowserFeature
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Non-exhaustive like the neighbouring `SubagentStackTests`. */
class VoiceOpenSessionHomeTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "https://voice-open.example", username = "", password = "")
    private val agentSwitched = "the voice agent switched"

    private fun session(id: String, parent: String? = null, directory: String = "/project") =
        OpenCodeSession(id = id, serverTitle = id, projectID = "project", directory = directory, updatedAt = date(10), parentID = parent)

    private val root get() = session("ses_root")
    private val child get() = session("ses_child", parent = "ses_root")
    private val target get() = session("ses_target", directory = "/elsewhere")

    private fun Harness.voiceStore(state: HomeFeature.State, transcript: Gate? = null): TestStore<HomeFeature.State, Action> =
        store(state) {
            this[DateClient] = DateClient.constant(date(100))
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = client().copy(
                getSession = { _, id -> if (id == target.id) target else session(id) },
                transcript = { _, _, _ ->
                    transcript?.await()
                    emptyList()
                },
                sessionFamily = { _, family -> OpenCodeSessionFamily(sessions = listOf(family), activeSessionIDs = emptySet()) },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
                runningShells = { _, _ -> emptyList() },
            )
        }.also { it.exhaustive = false }

    /** A live session-delegator call on the root. */
    private fun Harness.callOnRoot(listed: List<OpenCodeSession>): HomeFeature.State = state {
        var home = HomeFeature.State(server, snapshot(listed))
        home = home.copy(transcriptCache = mapOf(root.id to emptyList()))
        home = home.openingDetail(root, cachedItems = emptyList())
        val voice = VoiceFeature.State(server, root).withPhase(VoiceFeature.State.Phase.Live).copy(usesSessionDelegator = true)
        home.updatingDetail { it.copy(voice = voice) }
    }

    private fun openSession(session: OpenCodeSession) = detail(SessionFeature.Action.Delegate(SessionFeature.Delegate.OpenSession(session)))

    private enum class Screen { drawer, catchUp, subagent, call }

    private fun voiceOpenOutsideRecentsClosesTheDrawerAndKeepsTheCall(screen: Screen) = homeTest { h ->
        val gate = Gate()
        var state = h.callOnRoot(listOf(root, child))
        when (screen) {
            Screen.drawer -> state = state.copy(isDrawerOpen = true)
            Screen.catchUp -> state = h.state { state.withCatchUp(CatchUpFeature.Stack(queue = listOf(root.id), returnSessionID = root.id)) }
            Screen.subagent -> state = h.state { state.pushingSubagent(child) }
            Screen.call -> Unit
        }
        val navigationID = state.sessionDetail?.navigationID
        val drawerCloses = state.drawerCloseRequests
        val store = h.voiceStore(state, gate)

        // The target isn't listed: the delegate inserts it, then Home's normal switch opens it.
        store.send(openSession(target))
        assertEquals(target.id, store.state.sessions.first().id)
        assertEquals(drawerCloses + 1, store.state.drawerCloseRequests)
        store.receive(Action.SessionTapped(target.id))
        store.receive(Action.SessionNavigationReady(target.id))
        store.receiveVoice(VoiceFeature.Action.TargetChanged::class)

        assertEquals(target.id, store.state.topDetail?.session?.id)
        assertEquals("the call keeps its navigation identity", navigationID, store.state.sessionDetail?.navigationID)
        assertEquals(VoiceFeature.State.Phase.Live, store.state.sessionDetail?.voice?.phase)
        assertEquals(target.id, store.state.sessionDetail?.voice?.target?.sessionID)
        assertNull(store.state.catchUp)
        assertTrue(store.state.subagentStack.isEmpty())
        if (screen == Screen.drawer) {
            assertTrue(store.state.isDrawerOpen)
            store.send(Action.SessionDrawerArrived(isOpen = false))
            assertFalse(store.state.isDrawerOpen)
        }
        gate.finish()
        h.skipInFlightEffects()
    }

    @Test fun voiceOpenOutsideRecentsClosesTheDrawerAndKeepsTheCallFromTheDrawer() = voiceOpenOutsideRecentsClosesTheDrawerAndKeepsTheCall(Screen.drawer)

    @Test fun voiceOpenOutsideRecentsClosesTheDrawerAndKeepsTheCallFromCatchUp() = voiceOpenOutsideRecentsClosesTheDrawerAndKeepsTheCall(Screen.catchUp)

    @Test fun voiceOpenOutsideRecentsClosesTheDrawerAndKeepsTheCallFromASubagent() = voiceOpenOutsideRecentsClosesTheDrawerAndKeepsTheCall(Screen.subagent)

    @Test fun voiceOpenOutsideRecentsClosesTheDrawerAndKeepsTheCallFromTheCall() = voiceOpenOutsideRecentsClosesTheDrawerAndKeepsTheCall(Screen.call)

    private enum class Destination { rootFromSubagent, newSubagent, alreadyPushedSubagent }

    private fun subagentMovesKeepTheAgentReasonAndDeduplicate(destination: Destination) = homeTest { h ->
        val goal = if (destination == Destination.rootFromSubagent) root else child
        var state = h.callOnRoot(listOf(root, child))
        if (destination != Destination.newSubagent) state = h.state { state.pushingSubagent(child) }
        val navigationID = state.sessionDetail?.navigationID
        // Both mounted destinations carry a sheet or a page before reopening.
        state = state.updatingDetail { it.copy(activePicker = SessionFeature.State.ActivePicker.session, browser = it.browser.copy(pageID = UUID.randomUUID())) }
        val pushed = state.subagentStack.lastOrNull()
        if (pushed != null) {
            val covered = pushed.copy(
                activePicker = SessionFeature.State.ActivePicker.model,
                folderBrowser = FolderBrowserFeature.State(server = server),
                browser = pushed.browser.copy(pageID = UUID.randomUUID()),
            )
            state = state.copy(subagentStack = state.subagentStack.dropLast(1) + covered)
        }
        val store = h.voiceStore(state)

        store.send(openSession(goal))
        assertEquals(agentSwitched, store.state.nextMoveReason)
        assertEquals(1, store.state.drawerCloseRequests)
        store.receive(Action.SessionTapped(goal.id))
        if (destination != Destination.alreadyPushedSubagent) {
            store.receiveVoice(VoiceFeature.Action.TargetChanged::class)
            assertEquals(agentSwitched, store.state.sessionDetail?.voice?.moveReason)
        }
        assertEquals(goal.id, store.state.topDetail?.session?.id)
        assertNull(store.state.topDetail?.activePicker)
        assertNull(store.state.topDetail?.folderBrowser)
        assertEquals(false, store.state.topDetail?.browser?.isOpen)
        assertNull("A parent sheet still covers its pushed child", store.state.sessionDetail?.activePicker)
        assertEquals(navigationID, store.state.sessionDetail?.navigationID)
        assertNull(store.state.nextMoveReason)
        assertEquals(1, store.state.drawerCloseRequests)
        h.skipInFlightEffects()
    }

    @Test fun subagentMovesKeepTheAgentReasonAndDeduplicateRootFromSubagent() = subagentMovesKeepTheAgentReasonAndDeduplicate(Destination.rootFromSubagent)

    @Test fun subagentMovesKeepTheAgentReasonAndDeduplicateNewSubagent() = subagentMovesKeepTheAgentReasonAndDeduplicate(Destination.newSubagent)

    @Test fun subagentMovesKeepTheAgentReasonAndDeduplicateAlreadyPushedSubagent() = subagentMovesKeepTheAgentReasonAndDeduplicate(Destination.alreadyPushedSubagent)
}
