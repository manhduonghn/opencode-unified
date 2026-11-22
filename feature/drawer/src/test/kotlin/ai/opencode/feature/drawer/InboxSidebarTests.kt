package ai.opencode.feature.drawer

import ai.opencode.feature.transcript.SessionSeenWatermark
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeatureTestSupport.Gate
import ai.opencode.feature.drawer.HomeFeatureTestSupport.LockIsolated
import ai.opencode.feature.drawer.HomeFeatureTestSupport.append
import ai.opencode.feature.drawer.HomeFeatureTestSupport.date
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.HomeFeatureTestSupport.increment
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receiveBounded
import ai.opencode.feature.drawer.HomeFeatureTestSupport.snapshot
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class InboxSidebarTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")

    private fun session(id: String, updated: Long, unread: Boolean = false, archived: Long? = null) = OpenCodeSession(
        id = id,
        serverTitle = id,
        projectID = "p",
        directory = "/project",
        updatedAt = date(updated),
        idleMilliseconds = 2_000,
        viewedMilliseconds = if (unread) 1_000 else 2_000,
        archivedMilliseconds = archived,
    )

    /** They come from Markdown. */
    private fun reply(text: String) = OpenCodeTranscriptItem.AssistantMarkdown(id = "reply", text = "```\nx\n```\n\n$text")

    private fun state(): HomeFeature.State {
        val sessions = listOf(
            session("read_old", updated = 100),
            session("unread_new", updated = 400, unread = true),
            session("waiting", updated = 50),
            session("working", updated = 300),
            session("newest_read", updated = 500),
            session("archived", updated = 600, unread = true, archived = 1_789_440_124_000),
            session("unread_old", updated = 200, unread = true),
        )
        val state = HomeFeature.State(
            server,
            snapshot(
                sessions,
                active = setOf("working"),
                projects = listOf(OpenCodeProject(id = "p", name = "opencode", directory = "/project")),
            ),
        )
        val cache = sessions.associate { it.id to emptyList<OpenCodeTranscriptItem>() } +
            ("newest_read" to listOf(OpenCodeTranscriptItem.UserText(id = "ask", text = "Fix it"), reply("Fixed the   login\nflow.")))
        return state.copy(attentionSessionIDs = setOf("waiting"), transcriptCache = cache)
    }

    private fun key(id: String) = "${server.persistenceID}|$id"

    private val offline: OpenCodeClient.() -> OpenCodeClient = {
        copy(viewSession = { _, _, _ -> throw OpenCodeClientError("Offline") })
    }

    private suspend fun TestStore<HomeFeature.State, HomeFeature.Action>.receiveArchiveResponse(update: ((HomeFeature.State) -> HomeFeature.State)? = null) =
        receiveBounded(
            { ((it as? Action.InboxSidebar)?.action) is InboxSidebarFeature.Action.ArchiveResponse },
            "inboxSidebar.archiveResponse",
            update = update,
        )

    private fun HomeFeature.State.withSession(id: String, change: (OpenCodeSession) -> OpenCodeSession) =
        copy(sessions = sessions.map { if (it.id == id) change(it) else it })

    /** The session as a confirmed archive lists it first under Archived. */
    private fun HomeFeature.State.archivedEntry(id: String) = sessions.first { it.id == id }.copy(archivedMilliseconds = 1_000_000)

    @Test
    fun needsYourInputThenUnreadThenNewestAndArchivedLeftOut() = homeTest { h ->
        val rows = h.state { state() }.inboxSidebarRows()
        assertEquals(listOf("waiting", "unread_new", "unread_old", "newest_read", "working", "read_old"), rows.map { it.id })
        assertEquals(InboxSidebarRow.Line.NeedsInput, rows.first { it.id == "waiting" }.line)
        assertEquals(InboxSidebarRow.Line.Working, rows.first { it.id == "working" }.line)
        assertEquals(InboxSidebarRow.Line.Reply("Fixed the login flow."), rows.first { it.id == "newest_read" }.line)
        assertEquals(InboxSidebarRow.Line.None, rows.first { it.id == "read_old" }.line)
        assertTrue(rows.all { it.project == "opencode" })
        assertEquals(listOf("unread_new", "unread_old"), rows.filter { it.unread }.map { it.id })
    }

    @Test
    fun markAsReadMovesTheSessionAmongTheReadOnes() = homeTest { h ->
        val initial = h.state { state() }
        val store = h.store(initial) { this[OpenCodeClient] = OpenCodeClient.testValue.offline() }
        store.send(Action.MarkRead("unread_new")) {
            it.withSession("unread_new") { s -> s.copy(viewedMilliseconds = 2_000) }.copy(
                locallyViewedMilliseconds = it.locallyViewedMilliseconds + ("unread_new" to 2_000L),
                sessionReadDates = it.sessionReadDates + (key("unread_new") to date(400)),
            ).settingSeenWatermark(SessionSeenWatermark(itemID = null, viewedMilliseconds = 2_000), "unread_new")
        }
        assertEquals(
            listOf("waiting", "unread_old", "newest_read", "unread_new", "working", "read_old"),
            store.state.inboxSidebarRows().map { it.id },
        )
        store.finish()
    }

    @Test
    fun archiveHidesTheSessionAndPatchesTheServer() = homeTest { h ->
        val archived = LockIsolated<List<String>>(emptyList())
        val store = h.store(h.state { state() }) {
            this[DateClient] = DateClient.constant(date(1_000))
            this[OpenCodeClient] = OpenCodeClient.testValue.offline().copy(archiveSession = { _, id -> archived.append(id) })
        }
        assertTrue(store.state.drawerSessions.recents.map { it.id }.contains("newest_read"))
        store.send(Action.InboxSidebar(InboxSidebarFeature.Action.ArchiveTapped(sessionID = "newest_read", title = "newest_read"))) {
            it.copy(inboxSidebarStatus = it.inboxSidebarStatus.copy(archivedIDs = setOf("newest_read")))
        }
        assertFalse(store.state.inboxSidebarRows().map { it.id }.contains("newest_read"))
        // The regular list's rows leave at once too (Archive in selection mode).
        assertFalse(store.state.drawerSessions.recents.map { it.id }.contains("newest_read"))
        store.receiveArchiveResponse {
            it.withSession("newest_read") { s -> s.copy(archivedMilliseconds = 1_000_000) }.copy(
                inboxSidebarStatus = it.inboxSidebarStatus.copy(archivedIDs = emptySet(), archived = listOf(it.archivedEntry("newest_read"))),
                locallyViewedMilliseconds = it.locallyViewedMilliseconds + ("newest_read" to 2_000L),
                sessionReadDates = it.sessionReadDates + (key("newest_read") to date(500)),
            ).settingSeenWatermark(SessionSeenWatermark(itemID = "reply", viewedMilliseconds = 2_000), "newest_read")
        }
        assertEquals(listOf("newest_read"), archived.value)
        assertEquals(listOf("waiting", "unread_new", "unread_old", "working", "read_old"), store.state.inboxSidebarRows().map { it.id })
        store.finish()
    }

    @Test
    fun archivingAnUnreadSessionTakesItOutOfCatchUp() = homeTest { h ->
        val gate = Gate()
        val store = h.store(h.state { state() }) {
            this[DateClient] = DateClient.constant(date(1_000))
            this[OpenCodeClient] = OpenCodeClient.testValue.offline().copy(archiveSession = { _, _ -> gate.await() })
        }
        assertEquals(listOf("waiting", "unread_new", "unread_old"), store.state.catchUpSessions.map { it.id })
        store.send(Action.InboxSidebar(InboxSidebarFeature.Action.ArchiveTapped(sessionID = "unread_new", title = "unread_new"))) {
            it.copy(inboxSidebarStatus = it.inboxSidebarStatus.copy(archivedIDs = setOf("unread_new")))
        }
        // Gone from Catch Up while the request is in flight…
        assertEquals(listOf("waiting", "unread_old"), store.state.catchUpSessions.map { it.id })
        gate.yield()
        store.receiveArchiveResponse {
            it.withSession("unread_new") { s -> s.copy(archivedMilliseconds = 1_000_000, viewedMilliseconds = 2_000) }.copy(
                inboxSidebarStatus = it.inboxSidebarStatus.copy(archivedIDs = emptySet(), archived = listOf(it.archivedEntry("unread_new"))),
                locallyViewedMilliseconds = it.locallyViewedMilliseconds + ("unread_new" to 2_000L),
                sessionReadDates = it.sessionReadDates + (key("unread_new") to date(400)),
            ).settingSeenWatermark(SessionSeenWatermark(itemID = null, viewedMilliseconds = 2_000), "unread_new")
        }
        // …and after: read, so nothing counts it or reopens it.
        assertEquals(listOf("waiting", "unread_old"), store.state.catchUpSessions.map { it.id })
        assertFalse(store.state.isUnread(store.state.sessions.first { it.id == "unread_new" }))
        store.finish()
    }

    @Test
    fun theServersSnapshotDecidesOnceTheArchiveLands() = homeTest { h ->
        val store = h.store(h.state { state() }) {
            this[DateClient] = DateClient.constant(date(1_000))
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.offline().copy(
                archiveSession = { _, _ -> },
                homeAttention = { _, _, _ -> emptySet() },
                transcript = { _, _, _ -> emptyList() },
            )
        }
        store.exhaustive = false
        store.send(Action.InboxSidebar(InboxSidebarFeature.Action.ArchiveTapped(sessionID = "read_old", title = "read_old")))
        store.receiveArchiveResponse()
        assertTrue(store.state.inboxSidebarStatus.archivedIDs.isEmpty())
        assertFalse(store.state.inboxSidebarRows().map { it.id }.contains("read_old"))

        // The server reports it archived: still hidden, with nothing kept locally.
        val sessions = store.state.sessions.map { if (it.id == "read_old") it.copy(archivedMilliseconds = 1_789_440_124_000) else it }
        store.send(Action.SnapshotRefreshed(OpenCodeHomeSnapshot(projects = store.state.projects, sessions = sessions, activeSessionIDs = setOf("working"))))
        assertFalse(store.state.inboxSidebarRows().map { it.id }.contains("read_old"))

        // Restored on desktop: the next snapshot brings it back.
        val restored = sessions.map { if (it.id == "read_old") it.copy(archivedMilliseconds = null) else it }
        store.send(Action.SnapshotRefreshed(OpenCodeHomeSnapshot(projects = store.state.projects, sessions = restored, activeSessionIDs = setOf("working"))))
        assertTrue(store.state.inboxSidebarRows().map { it.id }.contains("read_old"))
        assertTrue(store.state.inboxSidebarStatus.archivedIDs.isEmpty())
        h.skipInFlightEffects()
    }

    @Test
    fun aSnapshotMidArchiveDoesNotBringItBack() = homeTest { h ->
        val gate = Gate()
        val store = h.store(h.state { state() }) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                archiveSession = { _, _ -> gate.await() },
                homeAttention = { _, _, _ -> emptySet() },
                transcript = { _, _, _ -> emptyList() },
            )
        }
        store.exhaustive = false
        store.send(Action.InboxSidebar(InboxSidebarFeature.Action.ArchiveTapped(sessionID = "read_old", title = "read_old")))
        store.send(Action.SnapshotRefreshed(OpenCodeHomeSnapshot(projects = store.state.projects, sessions = store.state.sessions, activeSessionIDs = setOf("working"))))
        assertFalse(store.state.inboxSidebarRows().map { it.id }.contains("read_old"))
        h.skipInFlightEffects()
    }

    @Test
    fun aFailedArchiveBringsTheSessionBackWithAnError() = homeTest { h ->
        val clock = TestClock()
        val store = h.store(h.state { state() }) {
            this[Clock] = clock
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(archiveSession = { _, _ -> throw OpenCodeClientError("Offline") })
        }
        store.send(Action.InboxSidebar(InboxSidebarFeature.Action.ArchiveTapped(sessionID = "working", title = "Working title"))) {
            it.copy(inboxSidebarStatus = it.inboxSidebarStatus.copy(archivedIDs = setOf("working")))
        }
        store.receiveArchiveResponse {
            it.copy(inboxSidebarStatus = it.inboxSidebarStatus.copy(archivedIDs = emptySet(), archiveError = DrawerFailure(DrawerFailure.Action.archive, "Working title", "Offline")))
        }
        assertTrue(store.state.inboxSidebarRows().any { it.id == "working" })
        clock.advance(5.seconds)
        store.receiveBounded(
            { ((it as? Action.InboxSidebar)?.action) == InboxSidebarFeature.Action.ArchiveErrorCleared },
            "inboxSidebar.archiveErrorCleared",
        ) { it.copy(inboxSidebarStatus = it.inboxSidebarStatus.copy(archiveError = null)) }
    }

    @Test
    fun archivesThatFailTogetherShareOneError() = homeTest { h ->
        val gate = Gate()
        val store = h.store(h.state { state() }) {
            this[Clock] = TestClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                archiveSession = { _, _ ->
                    gate.await()
                    throw OpenCodeClientError("Offline")
                },
            )
        }
        store.exhaustive = false
        store.send(Action.InboxSidebar(InboxSidebarFeature.Action.ArchiveTapped(sessionID = "working", title = "Working title")))
        store.send(Action.InboxSidebar(InboxSidebarFeature.Action.ArchiveTapped(sessionID = "read_old", title = "read_old")))
        gate.finish()
        store.receiveArchiveResponse()
        store.receiveArchiveResponse()
        assertEquals("Couldn’t archive 2 sessions", store.state.inboxSidebarStatus.archiveError?.title)
        store.skipInFlightEffects()
    }

    @Test
    fun aSecondSwipeWhileArchivingDoesNothing() = homeTest { h ->
        val clock = TestClock()
        val requests = LockIsolated(0)
        val store = h.store(h.state { state() }) {
            this[DateClient] = DateClient.constant(date(1_000))
            this[OpenCodeClient] = OpenCodeClient.testValue.offline().copy(
                archiveSession = { _, _ ->
                    requests.increment()
                    clock.sleep(1.seconds)
                },
            )
        }
        store.send(Action.InboxSidebar(InboxSidebarFeature.Action.ArchiveTapped(sessionID = "read_old", title = "read_old"))) {
            it.copy(inboxSidebarStatus = it.inboxSidebarStatus.copy(archivedIDs = setOf("read_old")))
        }
        store.send(Action.InboxSidebar(InboxSidebarFeature.Action.ArchiveTapped(sessionID = "read_old", title = "read_old")))
        clock.advance(1.seconds)
        store.receiveArchiveResponse {
            it.withSession("read_old") { s -> s.copy(archivedMilliseconds = 1_000_000) }.copy(
                inboxSidebarStatus = it.inboxSidebarStatus.copy(archivedIDs = emptySet(), archived = listOf(it.archivedEntry("read_old"))),
                locallyViewedMilliseconds = it.locallyViewedMilliseconds + ("read_old" to 2_000L),
                sessionReadDates = it.sessionReadDates + (key("read_old") to date(100)),
            ).settingSeenWatermark(SessionSeenWatermark(itemID = null, viewedMilliseconds = 2_000), "read_old")
        }
        assertEquals(1, requests.value)
        store.finish()
    }

    @Test
    fun theArchivedPageListsTheServersArchivedSessionsAndUnarchiveRestoresOne() = homeTest { h ->
        val clock = TestClock()
        val unarchived = LockIsolated<List<String>>(emptyList())
        val old = session("old_archive", updated = 50, archived = 1_789_440_000_000)
        // Restored on desktop since the list loaded: it's back in the session list, not under Archived.
        val restored = session("read_old", updated = 100, archived = 1_789_440_000_000)
        val store = h.store(h.state { state() }) {
            this[Clock] = clock
            this[OpenCodeClient] = OpenCodeClient.testValue.offline().copy(
                archivedSessions = { _, search, limit ->
                    assertEquals("", search)
                    assertEquals(50, limit)
                    listOf(old, restored)
                },
                unarchiveSession = { _, id -> unarchived.append(id) },
            )
        }
        store.send(Action.InboxSidebar(InboxSidebarFeature.Action.ArchivedListRequested))
        store.receiveBounded(
            { ((it as? Action.InboxSidebar)?.action) is InboxSidebarFeature.Action.ArchivedListResponse },
            "inboxSidebar.archivedListResponse",
        ) { it.copy(inboxSidebarStatus = it.inboxSidebarStatus.copy(archived = listOf(old, restored))) }
        assertEquals(listOf("old_archive"), store.state.inboxSidebarArchivedRows().map { it.id })

        store.send(Action.InboxSidebar(InboxSidebarFeature.Action.UnarchiveTapped(sessionID = "old_archive", title = "old_archive"))) {
            it.copy(inboxSidebarStatus = it.inboxSidebarStatus.copy(unarchivingIDs = setOf("old_archive")))
        }
        assertTrue(store.state.inboxSidebarArchivedRows().isEmpty())
        store.receiveBounded(
            { ((it as? Action.InboxSidebar)?.action) is InboxSidebarFeature.Action.UnarchiveResponse },
            "inboxSidebar.unarchiveResponse",
        ) { it.copy(inboxSidebarStatus = it.inboxSidebarStatus.copy(unarchivingIDs = emptySet(), archived = listOf(restored))) }
        assertEquals(listOf("old_archive"), unarchived.value)
        // The snapshot it refetches brings the session back to the list.
        h.skipInFlightEffects()
    }

    @Test
    fun searchingArchivedAsksTheServerAndListsItsMatches() = homeTest { h ->
        val clock = TestClock()
        val old = session("old_archive", updated = 50, archived = 1_789_440_000_000)
        val match = session("login_archive", updated = 40, archived = 1_789_440_000_000)
        val initial = h.state { state() }.let { it.copy(inboxSidebarStatus = it.inboxSidebarStatus.copy(archived = listOf(old))) }
        val store = h.store(initial) {
            this[Clock] = clock
            this[OpenCodeClient] = OpenCodeClient.testValue.offline().copy(
                archivedSessions = { _, search, _ ->
                    assertEquals("login", search)
                    listOf(match)
                },
            )
        }
        store.send(Action.InboxSidebar(InboxSidebarFeature.Action.ArchivedSearchChanged("login "))) {
            it.copy(inboxSidebarStatus = it.inboxSidebarStatus.copy(archivedSearch = "login "))
        }
        clock.advance(HomeFeature.sessionSearchDebounce)
        store.receiveBounded(
            { ((it as? Action.InboxSidebar)?.action) is InboxSidebarFeature.Action.ArchivedSearchResponse },
            "inboxSidebar.archivedSearchResponse",
        ) { it.copy(inboxSidebarStatus = it.inboxSidebarStatus.copy(archivedMatches = listOf(match))) }
        assertEquals(listOf("login_archive"), store.state.inboxSidebarArchivedRows().map { it.id })

        store.send(Action.InboxSidebar(InboxSidebarFeature.Action.ArchivedSearchChanged(""))) {
            it.copy(inboxSidebarStatus = it.inboxSidebarStatus.copy(archivedSearch = "", archivedMatches = null))
        }
        assertEquals(listOf("old_archive"), store.state.inboxSidebarArchivedRows().map { it.id })
    }

    @Test
    fun aFailedUnarchiveKeepsTheSessionArchivedWithAnError() = homeTest { h ->
        val clock = TestClock()
        val old = session("old_archive", updated = 50, archived = 1_789_440_000_000)
        val initial = h.state { state() }.let { it.copy(inboxSidebarStatus = it.inboxSidebarStatus.copy(archived = listOf(old))) }
        val store = h.store(initial) {
            this[Clock] = clock
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(unarchiveSession = { _, _ -> throw OpenCodeClientError("Offline") })
        }
        store.send(Action.InboxSidebar(InboxSidebarFeature.Action.UnarchiveTapped(sessionID = "old_archive", title = "Old title"))) {
            it.copy(inboxSidebarStatus = it.inboxSidebarStatus.copy(unarchivingIDs = setOf("old_archive")))
        }
        store.receiveBounded(
            { ((it as? Action.InboxSidebar)?.action) is InboxSidebarFeature.Action.UnarchiveResponse },
            "inboxSidebar.unarchiveResponse",
        ) { it.copy(inboxSidebarStatus = it.inboxSidebarStatus.copy(unarchivingIDs = emptySet(), archiveError = DrawerFailure(DrawerFailure.Action.unarchive, "Old title", "Offline"))) }
        assertEquals(listOf("old_archive"), store.state.inboxSidebarArchivedRows().map { it.id })
        clock.advance(5.seconds)
        store.receiveBounded(
            { ((it as? Action.InboxSidebar)?.action) == InboxSidebarFeature.Action.ArchiveErrorCleared },
            "inboxSidebar.archiveErrorCleared",
        ) { it.copy(inboxSidebarStatus = it.inboxSidebarStatus.copy(archiveError = null)) }
    }

    @Test
    fun ageIsShort() {
        val now = date(1_000_000)
        assertEquals("now", InboxSidebarRow.age(now.minusSeconds(20), now))
        assertEquals("5m", InboxSidebarRow.age(now.minusSeconds(300), now))
        assertEquals("3h", InboxSidebarRow.age(now.minusSeconds(3L * 3_600), now))
        assertEquals("2d", InboxSidebarRow.age(now.minusSeconds(2L * 86_400), now))
    }
}
