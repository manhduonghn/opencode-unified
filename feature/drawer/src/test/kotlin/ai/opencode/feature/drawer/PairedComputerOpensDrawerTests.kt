package ai.opencode.feature.drawer

import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.catchup.CatchUpFeature
import ai.opencode.feature.session.*
import ai.opencode.feature.settings.SettingsFeature
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * A computer paired for the first time opens the drawer on its sessions so the person can pick one;
 * nothing is opened for them.
 */
class PairedComputerOpensDrawerTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")

    private fun session(id: String, updated: Long, parentID: String? = null) = OpenCodeSession(
        id = id, serverTitle = id, projectID = "web", directory = "/web",
        updatedAt = Instant.ofEpochSecond(updated), idleMilliseconds = 1_000, viewedMilliseconds = 1_000,
        parentID = parentID,
    )

    private fun home(sessions: List<OpenCodeSession>): HomeFeature.State {
        val projects = if (sessions.isEmpty()) emptyList() else listOf(OpenCodeProject(id = "web", name = "web", directory = "/web"))
        val state = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = projects, sessions = sessions, activeSessionIDs = emptySet()))
        return state.copy(connection = state.connection.copy(connectionStatus = HomeConnectionFeature.State.ConnectionStatus.connected))
    }

    private fun CoroutineScope.store(state: HomeFeature.State) = TestStore(state, HomeFeature(), this)

    @Test fun opensTheDrawerAndPicksNothing() = runTest {
        val store = store(home(listOf(session("old", 10), session("recent", 30))))
        store.send(HomeFeature.Action.PairedComputerOpened) { it.copy(drawerOpenRequest = it.detailGeneration) }
        assertTrue(store.state.opensDrawerForPairing)
        store.send(HomeFeature.Action.DrawerOpenRequestHandled) { it.copy(drawerOpenRequest = null) }
        assertTrue(store.state.sessionDetail?.isDraft == true)
        store.finish()
    }

    /** New Session before the view opens the drawer: the request is dropped, so the drawer never opens over the fresh draft. */
    @Test fun aNewSessionBeforeTheDrawerOpensDropsIt() = runTest {
        val store = store(home(listOf(session("recent", 30))))
        store.send(HomeFeature.Action.PairedComputerOpened) { it.copy(drawerOpenRequest = it.detailGeneration) }
        store.send(HomeFeature.Action.NewSessionTapped) {
            store.withDependencies { it.openingDraft(reason = "you tapped New Session") }.copy(drawerOpenRequest = null)
        }
        assertFalse(store.state.opensDrawerForPairing)
        store.finish()
    }

    /** Settings shown before the view opens the drawer drops the request too. */
    @Test fun settingsBeforeTheDrawerOpensDropsIt() = runTest {
        val store = store(home(listOf(session("recent", 30))))
        store.send(HomeFeature.Action.PairedComputerOpened) { it.copy(drawerOpenRequest = it.detailGeneration) }
        store.send(HomeFeature.Action.SettingsTapped) {
            store.withDependencies { it.showingSettings().syncingTranscriptCover() }.copy(drawerOpenRequest = null)
        }
        assertFalse(store.state.opensDrawerForPairing)
        store.finish()
    }

    @Test fun noSessionsKeepsTheDraft() = runTest {
        val store = store(home(emptyList()))
        store.send(HomeFeature.Action.PairedComputerOpened)
        store.finish()
    }

    @Test fun onlySubagentsKeepsTheDraft() = runTest {
        val store = store(home(listOf(session("child", 40, parentID = "gone"))))
        store.send(HomeFeature.Action.PairedComputerOpened)
        store.finish()
    }

    @Test fun aSessionAlreadyOnScreenStays() = runTest {
        val old = session("old", 10)
        val state = home(listOf(old, session("recent", 30))).let { it.openingDetail(old, cachedItems = emptyList()) }
        val store = store(state)
        store.send(HomeFeature.Action.PairedComputerOpened)
        store.finish()
    }

    /** Anything presented over Home or its draft, or any move off it, means the drawer no longer applies. */
    @Test fun anythingPresentedOrNavigatedMeansTheDrawerNoLongerApplies() {
        var base = home(listOf(session("recent", 30)))
        base = base.copy(drawerOpenRequest = base.detailGeneration)
        assertTrue(base.opensDrawerForPairing)
        val pushed = session("child", 40, parentID = "recent")
        fun HomeFeature.State.detail(change: (SessionFeature.State) -> SessionFeature.State) =
            copy(sessionDetail = sessionDetail?.let(change))
        val changes: List<Pair<String, (HomeFeature.State) -> HomeFeature.State>> = listOf(
            "navigated" to { it.copy(detailGeneration = it.detailGeneration + 1) },
            "Catch Up" to { it.withCatchUp(CatchUpFeature.Stack(queue = listOf("recent"), returnSessionID = null)) },
            "Settings" to { it.copy(settings = SettingsFeature.State(server = it.server)) },
            "What’s New" to { it.copy(settings = SettingsFeature.State.whatsNew(it.server)) },
            "delete confirmation" to { it.copy(pendingDeletion = it.sessions.first()) },
            "tap in flight" to {
                it.copy(selectedTranscriptFetch = HomeFeature.SelectedTranscriptFetch(sessionID = "recent", transition = it.detailGeneration))
            },
            "push pending" to { it.copy(pendingSubagentPush = pushed) },
            "model picker" to { it.detail { d -> d.copy(activePicker = SessionFeature.State.ActivePicker.model) } },
            "requests sheet" to { it.detail { d -> d.copy(activePicker = SessionFeature.State.ActivePicker.requests) } },
            "call" to { it.detail { d -> d.copy(voice = VoiceFeature.State(server = it.server, draftDirectory = "/web", model = null)) } },
            "browser" to { it.detail { d -> d.copy(browser = d.browser.copy(pageID = UUID.randomUUID())) } },
        )
        for ((name, change) in changes) {
            val state = change(base)
            assertFalse(name, state.opensDrawerForPairing)
        }
    }
}
