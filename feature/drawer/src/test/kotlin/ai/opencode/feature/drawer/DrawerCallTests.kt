package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.experiments.key
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The drawer's call button ([HomeFeature.Action.DrawerCallTapped]): a call on it calls the screen
 * behind the drawer through its own call button, else Start Voice Call's Last Viewed path, and it's
 * gone during a call.
 *
 * Voice set-up is written into the test's own shared store.
 */
class DrawerCallTests {
    @get:Rule val isolation = TestIsolationRule()

    private companion object {
        val server = OpenCodeServer(url = "https://saved.example", username = "kit", password = "test")
        val project = OpenCodeProject(id = "prj_app", name = "app", directory = "/work/app")
        val older = OpenCodeSession(id = "ses_older", serverTitle = "Older work", projectID = "prj_app", directory = "/work/app", updatedAt = Instant.ofEpochSecond(100))
        val newer = OpenCodeSession(id = "ses_newer", serverTitle = "Fix login", projectID = "prj_app", directory = "/work/app", updatedAt = Instant.ofEpochSecond(300))
    }

    private val context = HomeTestContext()

    private fun voiceSetUp(on: Boolean) = context.read {
        shared(SharedKeys.voiceModeEnabled).set(on)
        shared(SharedKeys.openAIAPIKey).set(if (on) "sk-test" else null)
    }

    private fun home(
        projects: List<OpenCodeProject> = listOf(project),
        sessions: List<OpenCodeSession> = listOf(older, newer),
        lastViewed: String? = null,
    ): HomeFeature.State {
        val state = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = projects, sessions = sessions, activeSessionIDs = emptySet()))
        return state.copy(
            lastViewedSessionIDs = if (lastViewed == null) emptyMap() else mapOf(server.persistenceID to lastViewed),
            isDrawerOpen = true,
        )
    }

    private fun HomeFeature.State.showing(session: OpenCodeSession) =
        copy(sessionDetail = SessionFeature.State.session(server, session, cachedItems = emptyList()))

    private fun store(
        scope: CoroutineScope,
        haptics: MutableList<Haptic> = Collections.synchronizedList(ArrayList()),
        state: () -> HomeFeature.State,
    ): TestStore<HomeFeature.State, HomeFeature.Action> {
        val store = context.store(scope, state) {
            this[HapticsClient] = HapticsClient(play = { haptics.add(it) })
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(1_000))
            this[Clock] = TestClock()
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                getSession = { _, id -> if (id == "ses_older") older else newer },
                transcript = hangingTranscript,
                createSession = { _, directory, id ->
                    OpenCodeSession(id = id ?: "", serverTitle = "", projectID = "prj_app", directory = directory, updatedAt = Instant.EPOCH)
                },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false
        return store
    }

    private val voiceTapped = HomeFeature.Action.detail(SessionFeature.Action.VoiceTapped)

    @Test
    fun itCallsTheSessionBehindTheDrawerAndLeavesTheDrawerOpen() = runTest {
        voiceSetUp(true)
        val haptics = Collections.synchronizedList(ArrayList<Haptic>())
        val store = store(backgroundScope, haptics) { home(lastViewed = "ses_older").showing(newer) }
        store.send(HomeFeature.Action.DrawerCallTapped)
        store.receive(voiceTapped)
        assertEquals("Not the last viewed session: the one behind the drawer", "ses_newer", store.state.sessionDetail?.session?.id)
        assertEquals("ses_newer", store.state.sessionDetail?.voice?.target?.sessionID)
        assertTrue(store.state.isDrawerOpen)
        assertTrue("No haptic of its own: the call's start plays it", haptics.isEmpty())
    }

    @Test
    fun aDraftBehindTheDrawerCallsInItsProject() = runTest {
        voiceSetUp(true)
        val store = store(backgroundScope) { home(lastViewed = "ses_older").openingDraft().copy(isDrawerOpen = true) }
        assertEquals(project, store.state.sessionDetail?.draftProject)
        store.send(HomeFeature.Action.DrawerCallTapped)
        store.receive(voiceTapped)
        assertEquals("/work/app", store.state.sessionDetail?.voice?.target?.directory)
        assertTrue(store.state.isDrawerOpen)
    }

    @Test
    fun aDraftWithNoProjectFallsBackToTheLastViewedSession() = runTest {
        voiceSetUp(true)
        val store = store(backgroundScope) { home(projects = emptyList(), lastViewed = "ses_older").openingDraft().copy(isDrawerOpen = true) }
        assertNull(store.state.sessionDetail?.draftProject)
        store.send(HomeFeature.Action.DrawerCallTapped)
        assertEquals("ses_older", store.state.sessionDetail?.session?.id)
        store.receive(voiceTapped)
        assertEquals("ses_older", store.state.sessionDetail?.voice?.target?.sessionID)
    }

    /** A draft with no project and no session to fall back on would open the project picker behind the drawer: the drawer offers no call. */
    @Test
    fun aDraftWithNoProjectAndNoSessionsOffersNoCall() = runTest {
        voiceSetUp(true)
        val store = store(backgroundScope) { home(projects = emptyList(), sessions = emptyList()).openingDraft().copy(isDrawerOpen = true) }
        assertNull(store.state.sessionDetail?.draftProject)
        assertFalse(context.read { store.state.drawerShowsCallButton })
        store.send(HomeFeature.Action.DrawerCallTapped)
        assertNull(store.state.sessionDetail?.voice)
        assertNull(store.state.sessionDetail?.activePicker)
    }

    /** A pushed subagent's own call button: it asks the root for the call, aimed at the subagent on top. */
    @Test
    fun aPushedSubagentCallsThroughItsOwnCallButton() = runTest {
        voiceSetUp(true)
        val child = OpenCodeSession(
            id = "ses_child", serverTitle = "Explore", projectID = "prj_app", directory = "/work/app",
            updatedAt = Instant.ofEpochSecond(400), parentID = "ses_newer",
        )
        val store = store(backgroundScope) { home().showing(newer).pushingSubagent(child) }
        val top = store.state.subagentStack.last().navigationID
        store.send(HomeFeature.Action.DrawerCallTapped)
        store.receive(HomeFeature.Action.SubagentStack(top, SessionFeature.Action.VoiceTapped))
        store.receive(HomeFeature.Action.SubagentStack(top, SessionFeature.Action.Delegate(SessionFeature.Delegate.CallRequested)))
        assertEquals("ses_child", store.state.sessionDetail?.voice?.target?.sessionID)
        assertTrue(store.state.isDrawerOpen)
    }

    @Test
    fun nothingBehindTheDrawerFallsBackToTheNewestSession() = runTest {
        voiceSetUp(true)
        val store = store(backgroundScope) { home().copy(sessionDetail = null) }
        store.send(HomeFeature.Action.DrawerCallTapped)
        assertEquals("ses_newer", store.state.sessionDetail?.session?.id)
        store.receive(voiceTapped)
        assertEquals("ses_newer", store.state.sessionDetail?.voice?.target?.sessionID)
    }

    @Test
    fun theButtonIsGoneDuringACallAndBackWhenItEnds() = runTest {
        voiceSetUp(true)
        var state = context.read { home().showing(newer) }
        assertTrue(context.read { state.drawerShowsCallButton })
        val voice = context.read { VoiceFeature.State(server, newer).withPhase(VoiceFeature.State.Phase.Live) }
        state = state.copy(sessionDetail = state.sessionDetail?.copy(voice = voice))
        assertFalse(context.read { state.drawerShowsCallButton })

        // A stale tap while the call is live does nothing, haptic included.
        val haptics = Collections.synchronizedList(ArrayList<Haptic>())
        val live = state
        val store = store(backgroundScope, haptics) { live }
        store.send(HomeFeature.Action.DrawerCallTapped)
        assertEquals(VoiceFeature.State.Phase.Live, store.state.sessionDetail?.voice?.phase)
        assertTrue(haptics.isEmpty())

        state = state.copy(sessionDetail = state.sessionDetail?.copy(voice = voice.withPhase(VoiceFeature.State.Phase.Ended)))
        assertTrue(context.read { state.drawerShowsCallButton })
    }

    @Test
    fun theButtonFollowsTheComposersOnlyShowingWithVoiceSetUp() = runTest {
        voiceSetUp(false)
        val state = context.read { home().showing(newer) }
        assertFalse(context.read { state.drawerShowsCallButton })
        voiceSetUp(true)
        assertTrue(context.read { state.drawerShowsCallButton })
    }

    @Test
    fun itDoesNothingWhileDisconnected() = runTest {
        voiceSetUp(true)
        val haptics = Collections.synchronizedList(ArrayList<Haptic>())
        val store = store(backgroundScope, haptics) {
            val state = home().showing(newer)
            state.copy(connection = state.connection.copy(connectionStatus = HomeConnectionFeature.State.ConnectionStatus.disconnected))
        }
        store.send(HomeFeature.Action.DrawerCallTapped)
        assertNull(store.state.sessionDetail?.voice)
        assertTrue(haptics.isEmpty())
    }
}
