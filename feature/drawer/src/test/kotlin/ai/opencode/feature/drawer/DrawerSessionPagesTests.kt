package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeatureTestSupport.LockIsolated
import ai.opencode.feature.drawer.HomeFeatureTestSupport.append
import ai.opencode.feature.drawer.HomeFeatureTestSupport.date
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receive
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receiveBounded
import ai.opencode.feature.drawer.HomeFeatureTestSupport.snapshot
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.OpenCodeSessionPage
import ai.opencode.server.model.OpenCodeSessionPageRequest
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The drawer's older pages and its search over every session. */
class DrawerSessionPagesTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")

    private fun session(id: String, title: String? = null, updated: Number) = HomeFeatureTestSupport.session(
        id = id, title = title ?: id, directory = "/project", updatedAt = date(updated), idle = 1_000, viewed = 1_000,
    )

    private fun HomeFeatureTestSupport.Harness.state(sessions: List<OpenCodeSession>, next: String? = null): HomeFeature.State {
        val state = state { HomeFeature.State(server, snapshot(sessions)) }
        return state.copy(sessionPages = state.sessionPages.copy(older = state.sessionPages.older.copy(next = next)))
    }

    private fun client(block: OpenCodeClient.() -> OpenCodeClient): OpenCodeClient = OpenCodeClient.testValue.block()

    private fun searching(text: String): Action = Action.SearchChanged(text)

    private fun pages(action: SessionPagesAction): Action = Action.SessionPages(action)

    // Older pages

    @Test
    fun scrollingToTheEndReadsOlderPagesUntilTheListEnds() = homeTest { h ->
        val newest = session("new", updated = 30)
        val older = session("old", updated = 20)
        val oldest = session("oldest", updated = 10)
        val requests = LockIsolated<List<OpenCodeSessionPageRequest>>(emptyList())
        val store = h.store(h.state(listOf(newest), next = "c1")) {
            this[OpenCodeClient] = client {
                copy(sessionPage = { _, request, limit ->
                    requests.append(request)
                    assertEquals(HomeFeature.sessionPageSize, limit)
                    if (request == OpenCodeSessionPageRequest.After("c1")) {
                        OpenCodeSessionPage(listOf(older), next = "c2")
                    } else {
                        OpenCodeSessionPage(listOf(oldest), next = null)
                    }
                })
            }
        }
        assertEquals(DrawerListFooter.loading, store.state.drawerListFooter)

        store.send(pages(SessionPagesAction.LoadMoreRequested)) {
            it.copy(sessionPages = it.sessionPages.copy(older = it.sessionPages.older.copy(isLoading = true)))
        }
        store.receivePages(SessionPagesAction.OlderLoaded::class) {
            it.copy(
                sessionPages = it.sessionPages.copy(pagesRead = 1, older = SessionPageCursor(next = "c2"), olderPagesLoaded = 1),
                sessions = listOf(newest, older),
            )
        }
        store.send(pages(SessionPagesAction.LoadMoreRequested)) {
            it.copy(sessionPages = it.sessionPages.copy(older = it.sessionPages.older.copy(isLoading = true)))
        }
        store.receivePages(SessionPagesAction.OlderLoaded::class) {
            it.copy(
                sessionPages = it.sessionPages.copy(pagesRead = 2, older = SessionPageCursor(next = null), olderPagesLoaded = 2),
                sessions = listOf(newest, older, oldest),
            )
        }
        assertNull(store.state.drawerListFooter)
        assertEquals(listOf("new", "old", "oldest"), store.state.recentSessions.map { it.id })
        // The end of the list reads nothing more.
        store.send(pages(SessionPagesAction.LoadMoreRequested))
        assertEquals(listOf(OpenCodeSessionPageRequest.After("c1"), OpenCodeSessionPageRequest.After("c2")), requests.value)
    }

    @Test
    fun aPageBeingReadIsNotAskedForTwice() = homeTest { h ->
        val initial = h.state(listOf(session("new", updated = 30)), next = "c1")
        val store = h.store(initial.copy(sessionPages = initial.sessionPages.copy(older = initial.sessionPages.older.copy(isLoading = true))))
        store.send(pages(SessionPagesAction.LoadMoreRequested))
    }

    @Test
    fun searchingAListThatHoldsEverySessionStaysLocal() = homeTest { h ->
        val store = h.store(h.state(listOf(session("a", "Alpha", updated = 2))))
        store.send(searching("Alpha")) { it.copy(search = "Alpha") }
        assertEquals(listOf("a"), store.state.recentSessions.map { it.id })
        assertNull(store.state.drawerListFooter)
    }

    @Test
    fun aSessionAlreadyListedKeepsItsFresherCopy() = homeTest { h ->
        val listed = session("a", "Renamed", updated = 50)
        val stale = session("a", "Old title", updated = 5)
        val older = session("b", updated = 4)
        val store = h.store(h.state(listOf(listed), next = "c1")) {
            this[OpenCodeClient] = client { copy(sessionPage = { _, _, _ -> OpenCodeSessionPage(listOf(stale, older), next = null) }) }
        }
        store.send(pages(SessionPagesAction.LoadMoreRequested)) {
            it.copy(sessionPages = it.sessionPages.copy(older = it.sessionPages.older.copy(isLoading = true)))
        }
        store.receivePages(SessionPagesAction.OlderLoaded::class) {
            it.copy(
                sessionPages = it.sessionPages.copy(pagesRead = 1, older = SessionPageCursor(next = null), olderPagesLoaded = 1),
                sessions = listOf(listed, older),
            )
        }
    }

    @Test
    fun aFailedPageKeepsTheListAndWaitsForTryAgain() = homeTest { h ->
        val newest = session("new", updated = 30)
        val older = session("old", updated = 20)
        val fails = LockIsolated(true)
        val store = h.store(h.state(listOf(newest), next = "c1")) {
            this[OpenCodeClient] = client {
                copy(sessionPage = { _, _, _ ->
                    if (fails.value) throw OpenCodeClientError("Offline")
                    OpenCodeSessionPage(listOf(older), next = null)
                })
            }
            this[HapticsClient] = HapticsClient(play = {})
        }
        store.send(pages(SessionPagesAction.LoadMoreRequested)) {
            it.copy(sessionPages = it.sessionPages.copy(older = it.sessionPages.older.copy(isLoading = true)))
        }
        store.receivePages(SessionPagesAction.OlderLoaded::class) {
            it.copy(sessionPages = it.sessionPages.copy(pagesRead = 1, older = it.sessionPages.older.copy(isLoading = false, failed = true)))
        }
        assertEquals("a failure never empties the list", listOf(newest), store.state.sessions)
        assertEquals(DrawerListFooter.failed, store.state.drawerListFooter)
        // Scrolling back to the footer doesn't retry on its own.
        store.send(pages(SessionPagesAction.LoadMoreRequested))

        fails.setValue(false)
        store.send(pages(SessionPagesAction.RetryTapped)) {
            it.copy(sessionPages = it.sessionPages.copy(older = it.sessionPages.older.copy(failed = false, isLoading = true)))
        }
        store.receivePages(SessionPagesAction.OlderLoaded::class) {
            it.copy(
                sessionPages = it.sessionPages.copy(pagesRead = 2, older = SessionPageCursor(next = null), olderPagesLoaded = 1),
                sessions = listOf(newest, older),
            )
        }
    }

    @Test
    fun tryAgainPlaysATap() = homeTest { h ->
        val played = LockIsolated<List<Haptic>>(emptyList())
        val initial = h.state(listOf(session("new", updated = 30)), next = "c1")
        val failed = initial.copy(sessionPages = initial.sessionPages.copy(older = initial.sessionPages.older.copy(failed = true)))
        val store = h.store(failed) {
            this[OpenCodeClient] = client { copy(sessionPage = { _, _, _ -> OpenCodeSessionPage(emptyList(), next = null) }) }
            this[HapticsClient] = HapticsClient(play = { played.append(it) })
        }
        store.send(pages(SessionPagesAction.RetryTapped)) {
            it.copy(sessionPages = it.sessionPages.copy(older = it.sessionPages.older.copy(failed = false, isLoading = true)))
        }
        store.receivePages(SessionPagesAction.OlderLoaded::class) {
            it.copy(sessionPages = it.sessionPages.copy(pagesRead = 1, older = SessionPageCursor(next = null), olderPagesLoaded = 1))
        }
        assertEquals(listOf(Haptic.tap), played.value)
    }

    @Test
    fun olderPagesSurviveASnapshotAndOnlyTheFirstPageSetsTheCursor() = homeTest { h ->
        val newest = session("new", updated = 30)
        val older = session("old", updated = 20)
        val store = h.store(h.state(listOf(newest))) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = client {
                copy(
                    homeAttention = { _, _, _ -> emptySet() },
                    transcript = { _, _, _ -> emptyList() },
                    sessionPage = { _, _, _ -> OpenCodeSessionPage(listOf(older), next = "c2") },
                )
            }
        }
        store.exhaustive = false

        store.send(Action.SnapshotRefreshed(pageSnapshot(listOf(newest), nextCursor = "c1")))
        assertEquals("c1", store.state.sessionPages.older.next)
        store.send(pages(SessionPagesAction.LoadMoreRequested))
        store.receivePages(SessionPagesAction.OlderLoaded::class)
        assertEquals(listOf("new", "old"), store.state.sessions.map { it.id })

        store.send(Action.SnapshotRefreshed(pageSnapshot(listOf(newest), nextCursor = "c1")))
        assertEquals("an older page isn't dropped by the next snapshot", listOf("new", "old"), store.state.sessions.map { it.id })
        assertEquals("paging goes on from the last page read", "c2", store.state.sessionPages.older.next)
        h.skipInFlightEffects()
    }

    @Test
    fun olderPagesStartRead() = homeTest { h ->
        val stale = HomeFeatureTestSupport.session(id = "legacy", title = "Legacy", directory = "/project", updatedAt = date(5))
        val newest = session("new", updated = 30)
        val store = h.store(h.state(listOf(newest), next = "c1")) {
            this[OpenCodeClient] = client { copy(sessionPage = { _, _, _ -> OpenCodeSessionPage(listOf(stale), next = null) }) }
        }
        val key = store.state.sessionKey(stale.id)
        store.send(pages(SessionPagesAction.LoadMoreRequested)) {
            it.copy(sessionPages = it.sessionPages.copy(older = it.sessionPages.older.copy(isLoading = true)))
        }
        store.receivePages(SessionPagesAction.OlderLoaded::class) {
            it.copy(
                sessionPages = it.sessionPages.copy(pagesRead = 1, older = SessionPageCursor(next = null), olderPagesLoaded = 1),
                sessionReadDates = it.sessionReadDates + (key to stale.updatedAt),
                sessions = listOf(newest, stale),
            )
        }
        assertFalse(store.state.isUnread(stale))
    }

    // Search

    @Test
    fun searchAsksTheServerAfterAPauseAndCancelsWhatTypingReplaced() = homeTest { h ->
        val clock = TestClock()
        val listed = session("new", "Drawer polish", updated = 30)
        val found = session("old", "Drawer search", updated = 2)
        val queries = LockIsolated<List<OpenCodeSessionPageRequest>>(emptyList())
        val store = h.store(h.state(listOf(listed), next = "c1")) {
            this[Clock] = clock
            this[OpenCodeClient] = client {
                copy(sessionPage = { _, request, _ ->
                    queries.append(request)
                    OpenCodeSessionPage(listOf(found), next = "s2")
                })
            }
        }

        store.send(searching("Dra")) {
            it.copy(search = "Dra", sessionPages = it.sessionPages.copy(search = SessionSearch("Dra", page = SessionPageCursor(isLoading = true))))
        }
        assertEquals(DrawerListFooter.loading, store.state.drawerListFooter)
        clock.advance(100.milliseconds)
        store.send(searching("Drawer ")) {
            it.copy(search = "Drawer ", sessionPages = it.sessionPages.copy(search = SessionSearch("Drawer", page = SessionPageCursor(isLoading = true))))
        }
        // Local matches show at once, before the server answers.
        assertEquals(listOf("new"), store.state.recentSessions.map { it.id })
        clock.advance(HomeFeature.sessionSearchDebounce)
        store.receivePages(SessionPagesAction.SearchLoaded::class) {
            it.copy(
                sessionPages = it.sessionPages.copy(
                    pagesRead = 1,
                    search = SessionSearch("Drawer", results = listOf(found), page = SessionPageCursor(next = "s2")),
                ),
            )
        }
        assertEquals("the replaced query never reached the server", listOf(OpenCodeSessionPageRequest.Search("Drawer")), queries.value)
        assertEquals(listOf("new", "old"), store.state.recentSessions.map { it.id })
        assertEquals(DrawerListFooter.loading, store.state.drawerListFooter)

        store.send(searching("")) { it.copy(search = "", sessionPages = it.sessionPages.copy(search = null)) }
        assertEquals("results leave with the search", listOf("new"), store.state.recentSessions.map { it.id })
        assertEquals("the list's own paging is back", DrawerListFooter.loading, store.state.drawerListFooter)
    }

    /** A server that answers search with a subagent's session still only offers roots: the drawer opens a subagent from its parent. */
    @Test
    fun aServersSearchMatchesNeverListSubagents() = homeTest { h ->
        val root = session("root", "Drawer polish", updated = 30)
        val child = session("child", "Drawer search", updated = 20).copy(parentID = root.id)
        val home = h.state(listOf(root))
        val searched = home.copy(search = "Drawer", sessionPages = home.sessionPages.copy(search = SessionSearch("Drawer", results = listOf(child))))
        assertEquals(listOf("root"), searched.visibleSessions.map { it.id })
    }

    @Test
    fun searchResultsPageAndEnd() = homeTest { h ->
        val clock = TestClock()
        val first = session("a", "Alpha one", updated = 9)
        val second = session("b", "Alpha two", updated = 3)
        val store = h.store(h.state(emptyList(), next = "c1")) {
            this[Clock] = clock
            this[OpenCodeClient] = client {
                copy(sessionPage = { _, request, _ ->
                    if (request == OpenCodeSessionPageRequest.Search("Alpha")) {
                        OpenCodeSessionPage(listOf(first), next = "s2")
                    } else {
                        OpenCodeSessionPage(listOf(second, first), next = null)
                    }
                })
            }
        }
        store.send(searching("Alpha")) {
            it.copy(search = "Alpha", sessionPages = it.sessionPages.copy(search = SessionSearch("Alpha", page = SessionPageCursor(isLoading = true))))
        }
        clock.advance(HomeFeature.sessionSearchDebounce)
        store.receivePages(SessionPagesAction.SearchLoaded::class) {
            it.copy(
                sessionPages = it.sessionPages.copy(
                    pagesRead = 1,
                    search = SessionSearch("Alpha", results = listOf(first), page = SessionPageCursor(next = "s2")),
                ),
            )
        }
        store.send(pages(SessionPagesAction.LoadMoreRequested)) {
            val search = it.sessionPages.search!!
            it.copy(sessionPages = it.sessionPages.copy(search = search.copy(page = search.page.copy(isLoading = true))))
        }
        store.receivePages(SessionPagesAction.SearchLoaded::class) {
            it.copy(
                sessionPages = it.sessionPages.copy(
                    pagesRead = 2,
                    search = SessionSearch("Alpha", results = listOf(first, second), page = SessionPageCursor(next = null)),
                ),
            )
        }
        assertNull(store.state.drawerListFooter)
        assertEquals(listOf("a", "b"), store.state.recentSessions.map { it.id })
    }

    @Test
    fun aFailedSearchKeepsWhatItShowsAndOffersTryAgain() = homeTest { h ->
        val clock = TestClock()
        val found = session("a", "Alpha", updated = 9)
        val fails = LockIsolated(false)
        val store = h.store(h.state(emptyList(), next = "c1")) {
            this[Clock] = clock
            this[HapticsClient] = HapticsClient(play = {})
            this[OpenCodeClient] = client {
                copy(sessionPage = { _, _, _ ->
                    if (fails.value) throw OpenCodeClientError("Offline")
                    OpenCodeSessionPage(listOf(found), next = "s2")
                })
            }
        }
        store.send(searching("Alpha")) {
            it.copy(search = "Alpha", sessionPages = it.sessionPages.copy(search = SessionSearch("Alpha", page = SessionPageCursor(isLoading = true))))
        }
        clock.advance(HomeFeature.sessionSearchDebounce)
        store.receivePages(SessionPagesAction.SearchLoaded::class) {
            it.copy(
                sessionPages = it.sessionPages.copy(
                    pagesRead = 1,
                    search = SessionSearch("Alpha", results = listOf(found), page = SessionPageCursor(next = "s2")),
                ),
            )
        }
        fails.setValue(true)
        store.send(pages(SessionPagesAction.LoadMoreRequested)) {
            val search = it.sessionPages.search!!
            it.copy(sessionPages = it.sessionPages.copy(search = search.copy(page = search.page.copy(isLoading = true))))
        }
        store.receivePages(SessionPagesAction.SearchLoaded::class) {
            val search = it.sessionPages.search!!
            it.copy(sessionPages = it.sessionPages.copy(pagesRead = 2, search = search.copy(page = search.page.copy(isLoading = false, failed = true))))
        }
        assertEquals("a failed page leaves the results", listOf(found), store.state.recentSessions)
        assertEquals(DrawerListFooter.failed, store.state.drawerListFooter)

        fails.setValue(false)
        store.send(pages(SessionPagesAction.RetryTapped)) {
            val search = it.sessionPages.search!!
            it.copy(sessionPages = it.sessionPages.copy(search = search.copy(page = search.page.copy(failed = false, isLoading = true))))
        }
        store.receivePages(SessionPagesAction.SearchLoaded::class) {
            it.copy(
                sessionPages = it.sessionPages.copy(
                    pagesRead = 3,
                    search = SessionSearch("Alpha", results = listOf(found), page = SessionPageCursor(next = "s2")),
                ),
            )
        }
    }

    @Test
    fun aLateAnswerForAnEndedSearchIsDropped() = homeTest { h ->
        val found = session("a", "Alpha", updated = 9)
        val store = h.store(h.state(emptyList()))
        val late = SessionPagesAction.SearchLoaded("Alpha", appending = false, Result.success(OpenCodeSessionPage(listOf(found), next = null)))
        store.send(pages(late))
        assertNull(store.state.sessionPages.search)
    }

    @Test
    fun openingASearchResultBringsItIntoTheList() = homeTest { h ->
        val found = session("old", "Alpha", updated = 2)
        val initial = h.state(listOf(session("new", updated = 30)))
        val searched = initial.copy(
            search = "Alpha",
            sessionPages = initial.sessionPages.copy(search = SessionSearch("Alpha", results = listOf(found))),
        )
        val store = h.store(searched) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = client {
                copy(
                    transcript = { _, _, _ -> emptyList() },
                    viewSession = { _, _, _ -> },
                    getSession = { _, _ -> found },
                    sessionFamily = { _, root -> OpenCodeSessionFamily(listOf(root), emptySet()) },
                    runningShells = { _, _ -> emptyList() },
                    modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                    familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                    pendingInputs = { _, _ -> emptyList() },
                    homeAttention = { _, _, _ -> emptySet() },
                )
            }
        }
        store.exhaustive = false
        store.send(Action.SessionTapped(found.id))
        assertTrue(store.state.sessions.contains(found))
        store.receive(Action.SessionNavigationReady::class)
        assertEquals(found.id, store.state.sessionDetail?.session?.id)
        h.skipInFlightEffects()
    }

    @Test
    fun deletedAndArchivedResultsLeaveTheSearch() = homeTest { h ->
        val a = session("a", "Alpha", updated = 2)
        val b = session("b", "Alpha two", updated = 1)
        val initial = h.state(emptyList())
        val searched = initial.copy(
            search = "Alpha",
            sessionPages = initial.sessionPages.copy(search = SessionSearch("Alpha", results = listOf(a, b))),
        )
        val store = h.store(searched) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = client {
                copy(
                    homeSnapshot = { _ -> snapshot(emptyList()) },
                    homeAttention = { _, _, _ -> emptySet() },
                )
            }
        }
        store.exhaustive = false
        store.send(Action.ServerEvent(OpenCodeServerEvent.SessionDeleted(a.id)))
        store.send(Action.ServerEvent(OpenCodeServerEvent.SessionArchiveChanged(b.id, isArchived = true)))
        assertEquals(true, store.state.sessionPages.search?.results?.isEmpty())
        h.skipInFlightEffects()
    }

    private suspend fun <T : SessionPagesAction> TestStore<HomeFeature.State, Action>.receivePages(
        case: KClass<T>,
        update: ((HomeFeature.State) -> HomeFeature.State)? = null,
    ) = receiveBounded({ case.isInstance((it as? Action.SessionPages)?.action) }, "sessionPages.${case.simpleName}", update = update)

    private fun pageSnapshot(sessions: List<OpenCodeSession>, nextCursor: String?) =
        OpenCodeHomeSnapshot(projects = emptyList(), sessions = sessions, activeSessionIDs = emptySet(), nextCursor = nextCursor)
}
