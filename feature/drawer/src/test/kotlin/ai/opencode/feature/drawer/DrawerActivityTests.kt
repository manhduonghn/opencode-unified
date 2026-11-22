package ai.opencode.feature.drawer

import ai.opencode.feature.transcript.SessionSeenWatermark
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.testing.eventually
import ai.opencode.feature.drawer.HomeFeature.State.Indicator
import ai.opencode.feature.session.DrawerBadge
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.withRequestsConnectionIsAvailable
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.OpenCodeShell
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The drawer's running indicators, matched to the TUI and the desktop app: a root shows Working
 * while it or any session in its family runs (subagents run their own sessions); shells don't
 * count. Plus `DrawerDotTests` (the drawer button's dot).
 *
 * Stores run their effects in `backgroundScope`.
 */
class DrawerActivityTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "https://drawer.example", username = "", password = "")

    private fun session(id: String, parent: String? = null, directory: String = "/app", idle: Long? = null) = OpenCodeSession(
        id = id, serverTitle = id, projectID = directory, directory = directory,
        updatedAt = Instant.ofEpochSecond(10), idleMilliseconds = idle, parentID = parent,
    )

    private fun snapshot(
        sessions: List<OpenCodeSession>,
        active: Set<String> = emptySet(),
        parents: Map<String, String>? = null,
    ) = OpenCodeHomeSnapshot(projects = emptyList(), sessions = sessions, activeSessionIDs = active, activeSessionParents = parents)

    private fun home(sessions: List<OpenCodeSession>, active: Set<String> = emptySet(), parents: Map<String, String>? = null) =
        HomeFeature.State(server, snapshot(sessions, active, parents))

    /** `state.sessionDetail = state.makeDetail(for:cachedItems:)`. */
    private fun HomeFeature.State.opening(session: OpenCodeSession, edit: (SessionFeature.State) -> SessionFeature.State = { it }): HomeFeature.State {
        val (s, detail) = makeDetail(session, cachedItems = emptyList())
        return s.copy(sessionDetail = edit(detail))
    }

    /** A refresh that waits for the test to answer it. */
    private class GatedSnapshots {
        val requests = AtomicInteger(0)
        private val answers = Channel<OpenCodeHomeSnapshot>(Channel.UNLIMITED)

        fun answer(snapshot: OpenCodeHomeSnapshot) {
            answers.trySend(snapshot)
        }

        suspend fun next(): OpenCodeHomeSnapshot {
            requests.incrementAndGet()
            return answers.receive()
        }
    }

    @Test
    fun switchingAwayKeepsTheOpenSessionsSubagentRunning() = runTest {
        val parent = session("ses_parent")
        val other = session("ses_other", directory = "/api")
        val child = session("ses_child", parent = parent.id)
        val state = home(listOf(parent, other), active = setOf(parent.id))
            .copy(transcriptCache = mapOf(parent.id to emptyList(), other.id to emptyList()))
            .opening(parent) { it.copy(familySessions = listOf(parent, child)) }
        val store = TestStore(state, HomeFeature(), backgroundScope) {
            this[Clock] = ImmediateClock()
        }
        store.exhaustive = false

        // The subagent starts while its parent is open and stays active across the switch.
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionStarted(child.id)))
        assertEquals(parent.id, store.state.activeSessionParents[child.id])
        assertEquals(Indicator.running, store.state.indicator(parent))

        store.send(HomeFeature.Action.SessionTapped(other.id))
        assertEquals(other.id, store.state.sessionDetail?.session?.id)
        assertEquals("The parent stays Working after a switch away", Indicator.running, store.state.indicator(parent))
        assertEquals(Indicator.none, store.state.indicator(other))
    }

    @Test
    fun runsOfSubagentsNoOpenFamilyHasListedYetReachEveryScreen() = runTest {
        val parent = session("ses_parent")
        val child = session("ses_child", parent = "ses_parent")
        val state = home(listOf(parent), active = setOf(parent.id))
            .opening(parent) { it.copy(familySessions = listOf(parent, child)) }
            .pushingSubagent(child)
        val pushed = state.subagentStack.last().navigationID
        val snapshots = GatedSnapshots()
        val store = TestStore(state, HomeFeature(), backgroundScope) {
            this[Clock] = ImmediateClock()
            // Unattributed, it asks for a list; that read isn't under test.
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(homeSnapshot = { snapshots.next() })
        }
        store.exhaustive = false

        // Created, then started before the family read that lists it replies.
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionStarted("ses_new")))
        store.skipReceivedActions()
        assertEquals(mapOf("ses_new" to true), store.state.sessionDetail?.familyRunChanges)
        assertEquals(mapOf("ses_new" to true), store.state.subagentStack.first { it.navigationID == pushed }.familyRunChanges)
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionEnded("ses_new")))
        store.skipReceivedActions()
        assertEquals(mapOf("ses_new" to false), store.state.sessionDetail?.familyRunChanges)
        assertEquals(mapOf("ses_new" to false), store.state.subagentStack.first { it.navigationID == pushed }.familyRunChanges)
    }

    /**
     * Its family read took its active status before the start, so the read's ancestry pass never
     * followed it: every screen whose family it joins by Home's links reads again. Listed and
     * unrelated starts don't.
     */
    @Test
    fun anUnlistedSubagentStartingRereadsEveryFamilyItJoins() = runTest {
        val parent = session("ses_parent")
        val child = session("ses_child", parent = "ses_parent")
        var state = home(listOf(parent), active = setOf(parent.id))
            .opening(parent) { it.copy(familyRootID = parent.id, familySessions = listOf(parent, child)) }
            .pushingSubagent(child)
        // From `session.created`, as Home records it.
        state = state.copy(activeSessionParents = state.activeSessionParents + ("ses_grand" to child.id))
        val reads = mutableListOf<String>()
        val store = TestStore(state, HomeFeature(), backgroundScope) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeSnapshot = { awaitCancellation() },
                sessionFamily = { _, root ->
                    reads += root.id
                    awaitCancellation()
                },
                transcript = { _, _, _ -> awaitCancellation() },
                getSession = { _, _ -> awaitCancellation() },
                runningShells = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionStarted("ses_grand")))
        // Android's TestStore reduces effect actions when asked.
        repeat(100) {
            if (reads.size < 2) {
                store.skipReceivedActions()
                yield()
            }
        }
        assertEquals("The open session and the pushed subagent read again", listOf(child.id, parent.id), reads.sorted())

        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionStarted(child.id)))
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionStarted("ses_elsewhere")))
        repeat(100) {
            store.skipReceivedActions()
            yield()
        }
        assertEquals(2, reads.size)
    }

    @Test
    fun aSubagentThatStartsMidRefreshKeepsItsRootRunningWhenTheSnapshotLands() = runTest {
        val root = session("ses_root")
        val child = session("ses_child", parent = "ses_root")
        val state = home(listOf(root), parents = mapOf("ses_earlier" to root.id)).copy(sessionDetail = null)
        val snapshots = GatedSnapshots()
        val store = TestStore(state, HomeFeature(), backgroundScope) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeSnapshot = { snapshots.next() },
                homeAttention = { _, _, _ -> emptySet() },
                transcript = { _, _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        // A new root asks for the list; its `/api/session/active` is read now.
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.SessionCreated("ses_new", parentID = null)))
        assertTrue(eventually { snapshots.requests.get() == 1 })
        // Then a subagent is created and starts. Its parent arrived with it, so nothing cancels the request in flight.
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.SessionCreated(child.id, parentID = root.id)))
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionStarted(child.id)))
        assertEquals(Indicator.running, store.state.indicator(root))
        assertEquals(1, snapshots.requests.get())

        // The snapshot read before the start: no subagent, no ancestry.
        snapshots.answer(snapshot(listOf(root)))
        store.receive({ it is HomeFeature.Action.SnapshotRefreshed }, "snapshotRefreshed") {
            assertEquals(setOf(child.id), it.activeSessionIDs)
            assertEquals(emptyMap<String, Boolean>(), it.runChangesDuringRefresh)
            it
        }
        assertEquals("Links merge, never replaced", mapOf(child.id to root.id, "ses_earlier" to root.id), store.state.activeSessionParents)
        assertEquals(Indicator.running, store.state.indicator(root))
    }

    @Test
    fun aSubagentWithUnknownAncestryIsLookedUpAgainAfterAStaleSnapshot() = runTest {
        val root = session("ses_root")
        val state = home(listOf(root)).copy(sessionDetail = null)
        val snapshots = GatedSnapshots()
        val store = TestStore(state, HomeFeature(), backgroundScope) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeSnapshot = { snapshots.next() },
                homeAttention = { _, _, _ -> emptySet() },
                transcript = { _, _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        // Started before this stream saw it created: its parent is unknown.
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionStarted("ses_child")))
        assertTrue(eventually { snapshots.requests.get() == 1 })
        snapshots.answer(snapshot(listOf(root)))
        store.receive({ it is HomeFeature.Action.SnapshotRefreshed }, "snapshotRefreshed")
        assertEquals(setOf("ses_child"), store.state.activeSessionIDs)
        assertTrue("The stale snapshot didn't look the subagent up", eventually { snapshots.requests.get() == 2 })
        snapshots.answer(snapshot(listOf(root), active = setOf("ses_child"), parents = mapOf("ses_child" to root.id)))
        store.receive({ it is HomeFeature.Action.SnapshotRefreshed }, "snapshotRefreshed")
        assertEquals(Indicator.running, store.state.indicator(root))
        eventually(timeout = 50.milliseconds) { false }
        assertEquals(2, snapshots.requests.get())
    }

    @Test
    fun sessionViewedUpdatesUnreadInPlaceWithoutRefreshing() = runTest {
        val viewed = session("ses_viewed", idle = 2_000)
        val state = home(listOf(viewed)).copy(sessionDetail = null, runChangesDuringRefresh = mapOf("ses_elsewhere" to true))
        assertEquals(Indicator.unread, state.indicator(viewed))
        // Exhaustive: no refresh effect, and the recorded run change survives.
        val store = TestStore(state, HomeFeature(), this)

        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.SessionViewChanged(viewed.id, idle = 2_000))) {
            it.copy(sessions = listOf(viewed.copy(viewedMilliseconds = 2_000))).settingSeenWatermark(SessionSeenWatermark(itemID = null, viewedMilliseconds = 2_000), viewed.id)
        }
        assertEquals(Indicator.none, store.state.indicator(store.state.sessions[0]))
        // An older watermark never makes it unread again.
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.SessionViewChanged(viewed.id, idle = 1_000)))
        store.finish()
    }

    @Test
    fun backgroundSubagentsKeepTheirRootRunningButShellsDont() = runTest {
        val root = session("ses_root")
        val shellOwner = session("ses_shell", directory = "/api")
        val state = home(listOf(root, shellOwner), active = setOf(root.id)).copy(sessionDetail = null)
        val store = TestStore(state, HomeFeature(), backgroundScope) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                // The root's own run ended; its subagent's session keeps running.
                homeSnapshot = { snapshot(listOf(root, shellOwner), active = setOf("ses_child"), parents = mapOf("ses_child" to root.id)) },
                homeAttention = { _, _, _ -> emptySet() },
                transcript = { _, _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionEnded(root.id)))
        store.receive({ it is HomeFeature.Action.SnapshotRefreshed }, "snapshotRefreshed")
        assertEquals("A background subagent keeps its root Working", Indicator.running, store.state.indicator(root))

        // A background shell is not a run, here or in the TUI.
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ShellStarted(OpenCodeShell(id = "sh_1", command = "bun dev", sessionID = shellOwner.id, isUserCommand = true))))
        assertEquals(Indicator.none, store.state.indicator(shellOwner))

        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionEnded("ses_child")))
        assertEquals(Indicator.none, store.state.indicator(root))
    }

    @Test
    fun aNewStepMarksASessionRunningLikeTheTUI() = runTest {
        val root = session("ses_root")
        val state = home(listOf(root)).copy(sessionDetail = null)
        val store = TestStore(state, HomeFeature(), backgroundScope)
        store.exhaustive = false

        // Its start came before this stream connected.
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ContentBatch(root.id, listOf(OpenCodeLiveContentEvent.StepStarted("msg_1")))))
        assertEquals(setOf(root.id), store.state.activeSessionIDs)
        assertEquals(Indicator.running, store.state.indicator(root))
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ContentBatch(root.id, listOf(OpenCodeLiveContentEvent.TextDelta("msg_1", 0, "Hi")))))
        assertEquals(mapOf(root.id to true), store.state.runChangesDuringRefresh)
    }

    @Test
    fun aFailedSnapshotRetriesWithoutBlankingALiveDrawer() = runTest {
        val root = session("ses_root")
        var state = home(listOf(root), active = setOf(root.id)).copy(sessionDetail = null)
        state = state.copy(connection = state.connection.copy(isEventStreamLive = true))
        val attempts = AtomicInteger(0)
        val clock = TestClock()
        val store = TestStore(state, HomeFeature(), backgroundScope) {
            this[Clock] = clock
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeSnapshot = {
                    attempts.incrementAndGet()
                    snapshot(listOf(root), active = setOf(root.id))
                },
                homeAttention = { _, _, _ -> emptySet() },
                transcript = { _, _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(HomeFeature.Action.SnapshotFailed) { it.copy(connection = it.connection.copy(snapshotFailures = 1)) }
        assertEquals(1, store.state.snapshotFailures)
        assertEquals(HomeConnectionFeature.State.ConnectionStatus.connected, store.state.connectionStatus)
        assertEquals(Indicator.running, store.state.indicator(root))
        store.send(HomeFeature.Action.SnapshotFailed)
        assertEquals(2, store.state.snapshotFailures)
        // The second failure backs off 2 s, then retries.
        clock.advance(1.seconds)
        assertEquals(0, attempts.get())
        clock.advance(1.seconds)
        store.receive(HomeFeature.Action.SnapshotRetryRequested)
        clock.advance(250.milliseconds)
        store.receive({ it is HomeFeature.Action.SnapshotRefreshed }, "snapshotRefreshed")
        assertEquals(0, store.state.snapshotFailures)
        assertEquals(1, attempts.get())
    }

    @Test
    fun multiHopKnownParentsAndListedSubagentsUpdateObservedBusySet() = runTest {
        val now = Instant.ofEpochSecond(100)
        val root = session("ses_root")
        val listedSub = session("ses_listed_sub", parent = root.id)
        val child = session("ses_child", parent = root.id)
        val grandchild = session("ses_grandchild", parent = child.id)
        // Multi-hop ancestry known via familySessions, not yet in activeSessionParents.
        val state = home(listOf(root, listedSub))
            .markingReadLocally(root)
            .markingReadLocally(listedSub)
            .copy(transcriptCache = mapOf(root.id to emptyList(), listedSub.id to emptyList()))
            .opening(root) { it.copy(familySessions = listOf(root, listedSub, child, grandchild)) }
        val store = TestStore(state, HomeFeature(), backgroundScope) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(now)
            this[HomeSnapshotDate] = DateClient.constant(now)
        }
        store.exhaustive = false

        // 1. A 2-hop grandchild starting walks the full knownParents chain, copies both hops into
        //    activeSessionParents, and updates the busy set for root.
        val busyBefore = store.state.busySessionIDs
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionStarted(grandchild.id)))
        assertEquals(mapOf(grandchild.id to child.id, child.id to root.id), store.state.activeSessionParents)
        assertEquals(setOf(grandchild.id), store.state.activeSessionIDs)
        assertEquals(true, store.state.runChangesDuringRefresh[grandchild.id])
        store.receive(HomeFeature.Action.SessionDetail(PresentationAction.Presented(SessionFeature.Action.FamilyRunStateChanged(grandchild.id, isRunning = true))))
        assertEquals(true, store.state.sessionDetail?.familyRunChanges?.get(grandchild.id))
        assertTrue(grandchild.id in store.state.sessionDetail!!.activeFamilySessionIDs)
        assertNotEquals(busyBefore, store.state.busySessionIDs)
        assertEquals(Indicator.running, store.state.indicator(root))
        assertEquals(Indicator.none, store.state.indicator(listedSub))

        // 2. Starting a subagent row that is itself listed updates the busy set even though its
        //    parent root is already busy.
        val busyBeforeListed = store.state.busySessionIDs
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionStarted(listedSub.id)))
        assertEquals(root.id, store.state.activeSessionParents[listedSub.id])
        assertTrue(listedSub.id in store.state.activeSessionIDs)
        assertEquals(true, store.state.runChangesDuringRefresh[listedSub.id])
        store.receive(HomeFeature.Action.SessionDetail(PresentationAction.Presented(SessionFeature.Action.FamilyRunStateChanged(listedSub.id, isRunning = true))))
        assertTrue(listedSub.id in store.state.sessionDetail!!.activeFamilySessionIDs)
        assertNotEquals(busyBeforeListed, store.state.busySessionIDs)
        assertEquals(Indicator.running, store.state.indicator(listedSub))
    }

    @Test
    fun familyResponseAndDeletionResyncObservedBusySetWhenChildWasAlreadyBusy() = runTest {
        val now = Instant.ofEpochSecond(100)
        val root = session("ses_root")
        val child = session("ses_child", parent = root.id)
        val grandchild = session("ses_grandchild", parent = child.id)
        val state = home(listOf(root), active = setOf(grandchild.id))
            .markingReadLocally(root)
            .copy(transcriptCache = mapOf(root.id to emptyList()))
            .opening(root) { it.withRequestsConnectionIsAvailable(false) }
        assertEquals(Indicator.none, state.indicator(root))
        val store = TestStore(state, HomeFeature(), backgroundScope) {
            this[Clock] = TestClock()
            this[DateClient] = DateClient.constant(now)
            this[HomeSnapshotDate] = DateClient.constant(now)
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        val busyBefore = store.state.busySessionIDs
        store.send(
            HomeFeature.Action.SessionDetail(
                PresentationAction.Presented(
                    SessionFeature.Action.FamilyResponse(Result.success(OpenCodeSessionFamily(listOf(root, child, grandchild), setOf(grandchild.id)))),
                ),
            ),
        )
        assertEquals(now, store.state.sessionDetail?.familyReadAt)
        assertEquals(listOf(root, child, grandchild), store.state.sessionDetail?.familySessions)
        assertEquals(setOf(grandchild.id), store.state.sessionDetail?.activeFamilySessionIDs)
        assertEquals(mapOf(grandchild.id to child.id, child.id to root.id), store.state.activeSessionParents)
        assertNotEquals(busyBefore, store.state.busySessionIDs)
        assertEquals(Indicator.running, store.state.indicator(root))

        val busyBeforeDelete = store.state.busySessionIDs
        store.send(HomeFeature.Action.SessionDetail(PresentationAction.Presented(SessionFeature.Action.FamilySessionDeleted(grandchild.id))))
        assertEquals(listOf(root, child), store.state.sessionDetail?.familySessions)
        assertEquals(emptySet<String>(), store.state.sessionDetail?.activeFamilySessionIDs)
        // The server isn't answering: no poll until Home hears from it.
        assertNull(store.state.sessionDetail?.pendingWorkPollSeconds)
        assertNull(store.state.activeSessionParents[grandchild.id])
        assertNotEquals(busyBeforeDelete, store.state.busySessionIDs)
        assertEquals(Indicator.none, store.state.indicator(root))
    }

    @Test
    fun archivingElsewhereDropsTheSessionFromRecentsButKeepsItOpen() = runTest {
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        fun listed(id: String, title: String, updated: Long) = OpenCodeSession(
            id = id, serverTitle = title, projectID = "p", directory = "/project", updatedAt = Instant.ofEpochSecond(updated),
        )
        val open = listed("ses_open", "Open", 10)
        val other = listed("ses_other", "Bench run", 20)
        val back = listed("ses_back", "Back", 30)
        val state = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(other, open), activeSessionIDs = emptySet()))
            .copy(sessionDetail = SessionFeature.State.session(server = server, session = open, cachedItems = emptyList()))
        // The server: `back` starts archived; snapshots list what isn't.
        val archived = java.util.concurrent.atomic.AtomicReference(setOf(back.id))
        val store = TestStore(state, HomeFeature(), backgroundScope) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(100))
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeAttention = { _, _, _ -> emptySet() },
                transcript = { _, _, _ -> emptyList() },
                sessionFamily = { _, root -> OpenCodeSessionFamily(listOf(root), emptySet()) },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
                runningShells = { _, _ -> emptyList() },
                viewSession = { _, _, _ -> },
                getSession = { _, _ -> open },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                homeSnapshot = { _ ->
                    val hidden = archived.get()
                    OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(back, other, open).filter { it.id !in hidden }, activeSessionIDs = emptySet())
                },
            )
        }
        store.exhaustive = false
        suspend fun change(id: String, archive: Boolean) {
            archived.updateAndGet { if (archive) it + id else it - id }
            store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.SessionArchiveChanged(sessionID = id, isArchived = archive)))
        }
        suspend fun receiveSnapshot() = store.receive({ it is HomeFeature.Action.SnapshotRefreshed }, "snapshotRefreshed")

        change(other.id, archive = true)
        assertEquals("Hidden at once, before the refresh", listOf(open.id), store.state.recentSessions.map { it.id })
        assertTrue(store.state.catchUpSessions.all { it.id != other.id })
        receiveSnapshot()
        assertEquals(listOf(open.id), store.state.recentSessions.map { it.id })

        change(open.id, archive = true)
        assertTrue(store.state.recentSessions.isEmpty())
        receiveSnapshot()
        assertTrue(store.state.recentSessions.isEmpty())
        assertEquals("The reader stays in a session archived from another client", open.id, store.state.sessionDetail?.session?.id)

        // One unarchived that this page never listed comes back with the refresh.
        change(back.id, archive = false)
        receiveSnapshot()
        assertEquals(listOf(back.id), store.state.recentSessions.map { it.id })
        assertEquals(open.id, store.state.sessionDetail?.session?.id)
        store.skipInFlightEffects()
    }

    /**
     * A subagent's session reaches the drawer's state (a created event, a family refresh) but
     * lists only under its parent, not in Recents.
     */
    @Test
    fun recentsListOnlyRootSessions() {
        val root = session("ses_root")
        val child = session("ses_child", parent = "ses_root")
        val state = home(listOf(child, root))
        assertEquals(listOf("ses_root"), state.recentSessions.map { it.id })
        assertEquals(listOf("ses_root"), state.visibleSessions.map { it.id })
    }
}

class DrawerDotTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "https://drawer-dot.example", username = "", password = "")

    private fun session(id: String, idle: Long? = null, viewed: Long? = null) = OpenCodeSession(
        id = id, serverTitle = id, projectID = "/app", directory = "/app",
        updatedAt = Instant.ofEpochSecond(10), idleMilliseconds = idle, viewedMilliseconds = viewed,
    )

    /** The dot appears once per run of unread or waiting sessions elsewhere, changes colour in place, and goes once they're all read. */
    @Test
    fun badgeAppearsOncePerRunAndChangesColourInPlace() {
        val open = session("ses_open", idle = 1_000, viewed = 1_000)
        val s1 = session("ses_1", idle = 1_000, viewed = 1_000)
        val s2 = session("ses_2", idle = 1_000, viewed = 1_000)
        val s3 = session("ses_3", idle = 1_000, viewed = 1_000)
        var state = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(open, s1, s2, s3), activeSessionIDs = emptySet()))
        val badges = mutableListOf(state.drawerBadge(excluding = open.id))
        fun record() {
            badges.add(state.drawerBadge(excluding = open.id))
        }
        fun editSession(index: Int, edit: (OpenCodeSession) -> OpenCodeSession) {
            state = state.copy(sessions = state.sessions.toMutableList().also { it[index] = edit(it[index]) })
        }

        editSession(1) { it.copy(idleMilliseconds = 2_000) }
        record()
        editSession(2) { it.copy(idleMilliseconds = 3_000) }
        record()
        state = state.copy(attentionSessionIDs = setOf(s2.id))
        record()
        state = state.copy(attentionSessionIDs = emptySet())
        record()
        for (i in 1..3) editSession(i) { it.copy(viewedMilliseconds = it.idleMilliseconds) }
        record()
        editSession(3) { it.copy(idleMilliseconds = 5_000) }
        record()

        assertEquals(
            listOf(null, DrawerBadge.Unread, DrawerBadge.Unread, DrawerBadge.Attention, DrawerBadge.Unread, null, DrawerBadge.Unread),
            badges,
        )
    }
}
