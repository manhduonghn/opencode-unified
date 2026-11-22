package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.testing.eventually
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeatureTestSupport.Harness
import ai.opencode.feature.drawer.HomeFeatureTestSupport.detail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.HomeFeatureTestSupport.session
import ai.opencode.feature.drawer.HomeFeatureTestSupport.snapshot
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.from
import ai.opencode.feature.session.missingFolderMoveTarget
import ai.opencode.feature.voice.VoiceCallClient
import ai.opencode.feature.voice.VoiceCallLogClient
import ai.opencode.feature.voice.VoiceCallLogRecorder
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceRoute
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

/**
 * A session whose folder is gone moves to its project's folder (Move to, or the server's
 * `session.moved`): every screen knows the project to offer, and every copy Home holds follows the
 * move.
 */
class HomeFeatureTestsMovedSessions {
    @get:Rule val isolation = TestIsolationRule()

    private val testServer = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
    private val repo = OpenCodeProject(id = "proj_1", name = "repo", directory = "/work/repo")

    private fun worktreeSession(id: String = "ses_1", parentID: String? = null): OpenCodeSession =
        session(id, title = "Fix login bug", projectID = "proj_1", directory = "/work/linked", parentID = parentID)

    private fun Harness.quietStore(
        state: HomeFeature.State,
        dependencies: DependencyValues.Builder.() -> Unit = {},
    ): TestStore<HomeFeature.State, Action> {
        val store = store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                transcript = { _, _, _ -> awaitCancellation() },
                getSession = { _, _ -> awaitCancellation() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
                sessionFamily = { _, root -> OpenCodeSessionFamily(listOf(root), emptySet()) },
                runningShells = { _, _ -> emptyList() },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                homeAttention = { _, _, _ -> emptySet() },
            )
            dependencies()
        }
        store.exhaustive = false
        return store
    }

    /**
     * Opened from the drawer or pushed as a subagent, a session in a removed worktree offers its
     * project's folder and follows later project updates.
     */
    @Test
    fun projectsReachEveryOpenScreen() = homeTest { h ->
        val session = worktreeSession()
        val child = worktreeSession(id = "ses_child", parentID = session.id)
        val store = h.quietStore(h.state { HomeFeature.State(testServer, snapshot(listOf(session), projects = listOf(repo))) })

        store.send(Action.SessionTapped(session.id))
        store.receive(Action.SessionNavigationReady(session.id))
        store.send(detail(SessionFeature.Action.Delegate(SessionFeature.Delegate.OpenSubagent(child))))
        assertEquals(repo, store.state.sessionDetail?.missingFolderMoveTarget)
        assertEquals(repo, store.state.subagentStack.lastOrNull()?.missingFolderMoveTarget)

        val other = OpenCodeProject(id = "proj_2", name = "other", directory = "/work/other")
        store.send(Action.ServerEvent(OpenCodeServerEvent.ProjectUpdated(other)))
        assertEquals(listOf(other, repo), store.state.sessionDetail?.projects)
        assertEquals(listOf(other, repo), store.state.subagentStack.lastOrNull()?.projects)

        store.send(Action.SnapshotRefreshed(snapshot(listOf(session), projects = listOf(other))))
        assertEquals(listOf(other), store.state.sessionDetail?.projects)
        assertEquals(listOf(other), store.state.subagentStack.lastOrNull()?.projects)
        assertNull(store.state.sessionDetail?.missingFolderMoveTarget)
        h.skipInFlightEffects()
    }

    /**
     * The server moved a session: Home's list, its saved screens, and the open screen all follow,
     * and the open screen reads its messages again.
     */
    @Test
    fun aMovedSessionFollowsEverywhereHomeHoldsIt() = homeTest { h ->
        val session = worktreeSession()
        val other = worktreeSession(id = "ses_2")
        val state = h.state {
            val home = HomeFeature.State(testServer, snapshot(listOf(session, other), projects = listOf(repo)))
            val saved = home.copy(
                sessionSnapshots = mapOf(other.id to requireNotNull(SessionFeature.SwitchSnapshot.from(SessionFeature.State.session(server = testServer, session = other, cachedItems = emptyList())))),
            )
            val (made, detail) = saved.makeDetail(session, cachedItems = emptyList())
            made.copy(sessionDetail = detail)
        }
        val store = h.quietStore(state)

        // Another session: only Home's copies change.
        store.send(Action.ServerEvent(OpenCodeServerEvent.SessionMoved(sessionID = other.id, directory = "/work/repo")))
        assertEquals(listOf("/work/linked", "/work/repo"), store.state.sessions.map { it.directory })
        assertEquals("/work/repo", store.state.sessionSnapshots[other.id]?.session?.directory)
        assertEquals(listOf("/work/repo"), store.state.sessionSnapshots[other.id]?.family?.map { it.directory })
        assertEquals("/work/linked", store.state.sessionDetail?.session?.directory)

        // The open session: its screen follows and reads again.
        store.send(Action.ServerEvent(OpenCodeServerEvent.SessionMoved(sessionID = session.id, directory = "/work/repo")))
        store.receive(detail(SessionFeature.Action.SessionMoved(sessionID = session.id, directory = "/work/repo")))
        store.receive(detail(SessionFeature.Action.MessageInvalidated(sessionID = session.id)))
        assertEquals(listOf("/work/repo", "/work/repo"), store.state.sessions.map { it.directory })
        assertEquals("/work/repo", store.state.sessionDetail?.session?.directory)
        assertEquals(listOf("/work/repo"), store.state.sessionDetail?.familySessions?.map { it.directory })
        assertNull(store.state.sessionDetail?.missingFolderMoveTarget)
        h.skipInFlightEffects()
    }

    /**
     * A call on a session that moves stays on it, now in its new folder: no "Moved to" note, no
     * retarget. Opening another session is a move.
     */
    @Test
    fun aCallStaysOnItsSessionWhenTheSessionMoves() = homeTest { h ->
        val directory = Files.createTempDirectory("voice-logs-").toFile()
        try {
            val logID = UUID.randomUUID()
            val recorder = DependencyValues.withValues(DependencyValues.testing().update { this[DateClient] = DateClient.constant(Instant.EPOCH) }) {
                VoiceCallLogRecorder(directory = directory)
            }
            recorder.begin(logID, title = "Fix login bug", engine = "test")
            val session = worktreeSession()
            val other = session("ses_2", title = "Docs", projectID = "proj_1", directory = "/work/repo")
            val state = h.state {
                val home = HomeFeature.State(testServer, snapshot(listOf(session, other), projects = listOf(repo)))
                    .let { it.copy(transcriptCache = it.transcriptCache + mapOf(session.id to emptyList(), other.id to emptyList())) }
                val (made, detail) = home.makeDetail(session, cachedItems = emptyList())
                val voice = VoiceFeature.State(testServer, session)
                    .withPhase(VoiceFeature.State.Phase.Live)
                    .copy(activeRoute = VoiceRoute.PhoneKey(VoiceEngine.realtime), callLogID = logID)
                made.copy(sessionDetail = detail.copy(voice = voice))
            }
            var retargets = 0
            val store = h.quietStore(state) {
                this[VoiceCallLogClient] = VoiceCallLogClient(recorder = recorder)
                this[DateClient] = DateClient.constant(Instant.EPOCH)
                this[VoiceCallClient] = VoiceCallClient(
                    start = { _, _ -> emptyFlow() },
                    completeFunction = { _, _ -> },
                    retarget = { retargets += 1 },
                    setMuted = { },
                    updateInstructions = { _, _ -> },
                    updateDelegatorInstructions = { },
                    end = { },
                )
            }
            fun moves(): List<String> = (recorder.snapshot(logID)?.notes ?: emptyList()).map { it.text }.filter { it.startsWith("Moved to") }

            // The server revived it in its project's folder.
            store.send(Action.ServerEvent(OpenCodeServerEvent.SessionMoved(sessionID = session.id, directory = "/work/repo")))
            store.receive(detail(SessionFeature.Action.SessionMoved(sessionID = session.id, directory = "/work/repo")))
            assertEquals(session.id, store.state.sessionDetail?.voice?.target?.sessionID)
            assertEquals("/work/repo", store.state.sessionDetail?.voice?.target?.directory)
            assertEquals("repo", store.state.sessionDetail?.voice?.directoryName)
            assertTrue(moves().isEmpty())

            // Another session is a move.
            store.send(Action.SessionTapped(other.id))
            store.receive({ it == detail(SessionFeature.Action.voicePresented(VoiceFeature.Action.TargetChanged)) }, "voice target changed")
            assertEquals(other.id, store.state.sessionDetail?.voice?.target?.sessionID)
            assertTrue(moves().size == 1 && moves().first().startsWith("Moved to “Docs”"))
            eventually { retargets > 0 }
            assertEquals(1, retargets)
            h.skipInFlightEffects()
        } finally {
            directory.deleteRecursively()
        }
    }
}
