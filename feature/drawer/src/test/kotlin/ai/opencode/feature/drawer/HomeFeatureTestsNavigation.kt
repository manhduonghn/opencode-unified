package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.store.PresentationAction
import ai.opencode.feature.composer.DictationClient
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.feature.composer.SharedDraftTarget
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeatureTestSupport.Gate
import ai.opencode.feature.drawer.HomeFeatureTestSupport.LockIsolated
import ai.opencode.feature.drawer.HomeFeatureTestSupport.append
import ai.opencode.feature.drawer.HomeFeatureTestSupport.date
import ai.opencode.feature.drawer.HomeFeatureTestSupport.detail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.HomeFeatureTestSupport.increment
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receive
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receiveBounded
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receiveDetail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.settle
import ai.opencode.feature.drawer.HomeFeatureTestSupport.snapshot
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.dictationHold
import ai.opencode.feature.session.isDictating
import ai.opencode.feature.session.isDraft
import ai.opencode.feature.session.withDraft
import ai.opencode.feature.session.withRequestFailureAwaitingReconnect
import ai.opencode.feature.session.withRequestsConnectionIsAvailable
import ai.opencode.server.CachedHomeSnapshot
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ai.opencode.core.testing.TestIsolationRule
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Opening sessions and drafts, the recording that follows the screen, server events and the
 * connection.
 */
class HomeFeatureTestsNavigation {
    @get:Rule val isolation = TestIsolationRule()

    private val testServer = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")

    private fun testSession(
        id: String = "ses_1",
        title: String = "Fix login bug",
        projectID: String = "proj_1",
        directory: String = "/tmp/demo",
        updatedAt: Instant = date(1_000),
    ) = OpenCodeSession(id = id, serverTitle = title, projectID = projectID, directory = directory, updatedAt = updatedAt)

    private fun HomeFeature.State.key(id: String) = "${server.persistenceID}|$id"

    @Test
    fun tappingSessionOpensDetailAndLoadsTranscript() = homeTest { h ->
        val server = testServer
        val session = testSession()
        val items = listOf(
            OpenCodeTranscriptItem.UserText(id = "m1", text = "hello"),
            OpenCodeTranscriptItem.AssistantMarkdown(id = "m2-0", text = "hi"),
        )
        val gate = Gate()
        val requests = LockIsolated(0)
        val store = h.store(h.state { HomeFeature.State(server, snapshot(listOf(session))) }) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                transcript = { _, _, _ ->
                    requests.increment()
                    gate.await()
                    items
                },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
                getSession = { _, _ -> session },
                sessionFamily = { _, root -> OpenCodeSessionFamily(listOf(root), emptySet()) },
                runningShells = { _, _ -> emptyList() },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                homeAttention = { _, _, _ -> emptySet() },
            )
        }
        store.exhaustive = false

        store.send(Action.SessionTapped("ses_1"))
        store.receive(Action.SessionNavigationReady("ses_1")) {
            it.copy(
                sessionDetail = h.state {
                    SessionFeature.State.session(server, session, initialTranscriptRequestIsExternal = true, navigationID = 2)
                },
                detailGeneration = 2,
            )
        }
        store.send(detail(SessionFeature.Action.Task))
        assertEquals(1, requests.value)
        gate.yield()
        gate.finish()
        store.receive(Action.SelectedTranscriptResponse::class) {
            it.copy(transcriptCache = it.transcriptCache + ("ses_1" to items))
        }
        store.receiveDetail(SessionFeature.Action.TranscriptResponse::class) {
            it.copy(
                sessionDetail = it.sessionDetail?.copy(
                    initialTranscriptRequestIsExternal = false,
                    initialTranscriptIsFresh = true,
                    isLoading = false,
                    items = items,
                ),
            )
        }
        assertEquals(items, store.state.sessionDetail?.items)
        assertEquals("Session task must join the tap-time load instead of issuing a duplicate", 1, requests.value)
        h.skipInFlightEffects()
    }

    @Test
    fun draftOpenedBeforeTheFirstSnapshotLearnsItsProjects() = homeTest { h ->
        val recent = OpenCodeProject(id = "proj_recent", name = "recent", directory = "/tmp/recent")
        val other = OpenCodeProject(id = "proj_other", name = "other", directory = "/tmp/other")
        val session = OpenCodeSession(id = "ses_1", serverTitle = "Latest work", projectID = "proj_recent", directory = "/tmp/recent", updatedAt = date(1_000))
        // Cold launch with nothing cached: Home starts empty and opens a draft.
        val state = h.state {
            val base = HomeFeature.State(testServer, snapshot(emptyList()))
            base.copy(connection = base.connection.copy(connectionStatus = HomeConnectionFeature.State.ConnectionStatus.reconnecting))
        }
        assertEquals(true, state.sessionDetail?.isDraft)
        assertEquals(true, state.sessionDetail?.projects?.isEmpty())
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeAttention = { _, _, _ -> emptySet() },
                transcript = { _, _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.SnapshotRefreshed(snapshot(listOf(session), projects = listOf(other, recent))))
        assertEquals(true, store.state.sessionDetail?.isDraft)
        assertEquals(listOf(recent, other), store.state.sessionDetail?.projects)
        assertEquals(recent, store.state.sessionDetail?.draftProject)
        h.skipInFlightEffects()
    }

    private data class Sent(val sessionID: String, val text: String)

    private fun dictationUpdated(transcript: String): (Action) -> Boolean = { action ->
        val presented = (action as? Action.SessionDetail)?.action as? PresentationAction.Presented
        val updated = (presented?.action as? SessionFeature.Action.Dictation)?.action as? DictationFeature.Action.Updated
        updated?.transcript == transcript
    }

    /**
     * A recording follows the screen. Switching sessions mid-hold keeps it running, locked (so
     * the finger lifting later sends nothing), with the same words; Send sends it into the session
     * now on screen. What was typed beside it stays in the first session's composer.
     */
    @Test
    fun switchingSessionsCarriesTheRecordingAndSendsItThere() = homeTest { h ->
        val first = OpenCodeSession(id = "ses_first", serverTitle = "First", projectID = "p", directory = "/work", updatedAt = date(2_000))
        val second = OpenCodeSession(id = "ses_second", serverTitle = "Second", projectID = "p", directory = "/work", updatedAt = date(1_000))
        val state = h.state {
            HomeFeature.State(testServer, snapshot(listOf(first, second)))
                .copy(transcriptCache = mapOf(first.id to emptyList(), second.id to emptyList()))
                .openingDetail(first, cachedItems = emptyList())
                .updatingDetail { it.withDraft("Typed in first") }
        }
        val feed = Channel<String>(Channel.UNLIMITED)
        val sent = LockIsolated<List<Sent>>(emptyList())
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(date(3_000))
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeAttention = { _, _, _ -> emptySet() },
                message = { _, _, _ -> throw OpenCodeClientError("Missing", statusCode = 404) },
                sendPrompt = { _, sessionID, text, _, _, _, _ -> sent.append(Sent(sessionID, text)) },
                waitForIdle = { _, _ -> },
                getSession = { _, id -> if (id == first.id) first else second },
                sessionFamily = { _, root -> OpenCodeSessionFamily(listOf(root), emptySet()) },
                runningShells = { _, _ -> emptyList() },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                familyPendingRequests = { _, _, _ -> throw OpenCodeClientError("Offline") },
                viewSession = { _, _, _ -> },
                pendingInputs = { _, _ -> emptyList() },
                transcript = { _, _, _ -> emptyList() },
            )
            this[DictationClient] = DictationClient(
                start = { feed },
                stop = {
                    feed.trySend("first words and more, sent")
                    feed.close()
                },
            )
        }
        store.exhaustive = false

        store.send(detail(SessionFeature.Action.micPressBegan))
        store.receive(detail(SessionFeature.Action.dictationReady))
        feed.trySend("first words")
        store.receiveBounded(dictationUpdated("first words"), "dictationUpdated(first words)")
        assertEquals("Typed in first first words", store.state.sessionDetail?.dictationHold?.text)
        val navigationID = store.state.sessionDetail?.navigationID

        // Still holding: the drawer, Catch Up, or the voice agent moves the screen.
        store.send(Action.SessionTapped(second.id))
        assertEquals(second, store.state.sessionDetail?.session)
        assertEquals("the recording's effect lives on", navigationID, store.state.sessionDetail?.navigationID)
        assertEquals(true, store.state.sessionDetail?.isDictating)
        assertEquals(true, store.state.sessionDetail?.dictationHold?.isLocked)
        assertEquals("first words", store.state.sessionDetail?.dictationHold?.text)
        assertEquals("Typed in first", store.withDependencies { store.state.sharedDrafts[SharedDraftTarget.Session(first.id)]?.text })

        feed.trySend("first words and more")
        store.receiveBounded(dictationUpdated("first words and more"), "dictationUpdated(first words and more)")
        assertEquals("first words and more", store.state.sessionDetail?.dictationHold?.text)
        store.send(detail(SessionFeature.Action.micPressReleased))
        assertTrue("the finger lifting after the switch sends nothing", sent.value.isEmpty())

        store.send(detail(SessionFeature.Action.dictationSendTapped))
        // The send runs on the effects' own threads, so wait real time for it (up to 5 s): a
        // fixed number of yields ran out on a slow CI runner before the prompt went out.
        val deadline = System.nanoTime() + 5_000_000_000L
        while (sent.value.isEmpty() && System.nanoTime() < deadline) {
            // This TestStore reduces what effects send when asked.
            store.skipReceivedActions()
            settle(1)
            withContext(Dispatchers.IO) { Thread.sleep(5) }
        }
        assertEquals(listOf(Sent(second.id, "first words and more, sent")), sent.value)

        // The first session's typed text is waiting in its composer.
        store.send(Action.SessionTapped(first.id))
        assertEquals("Typed in first", store.state.sessionDetail?.draft)
        h.skipInFlightEffects()
    }

    @Test
    fun newSessionOpensDraftWithMostRecentProject() = homeTest { h ->
        val server = testServer
        val recent = OpenCodeProject(id = "proj_recent", name = "recent", directory = "/tmp/recent")
        val other = OpenCodeProject(id = "proj_other", name = "other", directory = "/tmp/other")
        val session = OpenCodeSession(id = "ses_1", serverTitle = "Latest work", projectID = "proj_recent", directory = "/tmp/recent", updatedAt = date(1_000))
        val created = OpenCodeSession(id = "ses_new", serverTitle = "Untitled session", projectID = "proj_recent", directory = "/tmp/recent", updatedAt = date(2_000))
        val store = h.store(h.state { HomeFeature.State(server, snapshot(listOf(session), projects = listOf(other, recent))) }) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
                sessionFamily = { _, root -> OpenCodeSessionFamily(listOf(root), emptySet()) },
                runningShells = { _, _ -> emptyList() },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
            )
        }
        store.exhaustive = false

        assertEquals(listOf(recent, other), store.state.projectsByRecency)

        store.send(Action.NewSessionTapped) {
            it.copy(
                sessionDetail = h.state { SessionFeature.State.draft(server, draftProject = recent, projects = listOf(recent, other), navigationID = 2) },
                detailGeneration = 2,
            )
        }
        store.send(detail(SessionFeature.Action.SessionCreated(created))) {
            it.copy(
                sessions = listOf(created, session),
                sessionDetail = it.sessionDetail?.copy(session = created),
                lastViewedSessionIDs = it.lastViewedSessionIDs + (server.persistenceID to created.id),
            )
        }
        assertEquals(listOf(created, session), store.state.sessions)
        assertEquals(created, store.state.sessionDetail?.session)
        assertEquals(created.id, store.state.lastViewedSessionIDs[server.persistenceID])
        assertEquals(
            CachedHomeSnapshot(
                serverID = server.persistenceID,
                snapshot = snapshot(listOf(created, session), projects = listOf(other, recent)),
                capturedAt = date(0),
            ),
            h.state { store.state.cachedHomeSnapshot },
        )
    }

    @Test
    fun reopeningSessionStartsFromCachedTranscript() = homeTest { h ->
        val server = testServer
        val session = testSession()
        val items = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "m1", text = "hello"))
        val state = h.state { HomeFeature.State(server, snapshot(listOf(session))).copy(transcriptCache = mapOf("ses_1" to items)) }
        val store = h.store(state)

        store.send(Action.SessionTapped("ses_1")) {
            it.copy(
                sessionReadDates = it.sessionReadDates + (it.key("ses_1") to session.updatedAt),
                lastViewedSessionIDs = it.lastViewedSessionIDs + (server.persistenceID to session.id),
                detailGeneration = 2,
                sessionDetail = h.state { SessionFeature.State.session(server, session, cachedItems = items, navigationID = 2) }
                    .copy(newMessagesBaseline = setOf("m1")),
            )
        }
        assertEquals(false, store.state.sessionDetail?.isLoading)
        assertEquals(items, store.state.sessionDetail?.items)
    }

    @Test
    fun serverEventsDriveActivityTitlesAndDeletes() = homeTest { h ->
        val server = testServer
        val session = testSession()
        val refreshed = snapshot(listOf(session))
        val capturedAt = date(0)
        // Already cached, so the snapshot schedules no prefetch (covered elsewhere).
        val state = h.state {
            HomeFeature.State(server, snapshot(listOf(session))).copy(transcriptCache = mapOf(session.id to listOf(OpenCodeTranscriptItem.UserText(id = "m1", text = "cached"))))
        }
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeSnapshot = { _ -> refreshed },
                homeAttention = { _, _, _ -> emptySet() },
            )
        }
        fun cached() = h.state { store.state.cachedHomeSnapshot }

        // A run's start and end are persisted, so a quick relaunch shows them.
        store.send(Action.ServerEvent(OpenCodeServerEvent.ExecutionStarted(sessionID = "ses_1"))) {
            it.copy(activeSessionIDs = setOf("ses_1"), runChangesDuringRefresh = mapOf("ses_1" to true))
        }
        assertEquals(CachedHomeSnapshot(server.persistenceID, snapshot(listOf(session), active = setOf("ses_1")), capturedAt), cached())
        val renamed = session.copy(serverTitle = "Login fixed")
        val renamedCache = CachedHomeSnapshot(server.persistenceID, snapshot(listOf(renamed), active = setOf(session.id)), capturedAt)
        store.send(Action.ServerEvent(OpenCodeServerEvent.SessionRenamed(sessionID = "ses_1", title = "Login fixed"))) {
            it.copy(sessions = listOf(renamed))
        }
        assertEquals(renamedCache, cached())
        store.send(Action.ServerEvent(OpenCodeServerEvent.ExecutionEnded(sessionID = "ses_1"))) {
            // Re-applied over the snapshot the end requests.
            it.copy(activeSessionIDs = emptySet(), runChangesDuringRefresh = mapOf("ses_1" to false))
        }
        assertEquals(CachedHomeSnapshot(server.persistenceID, snapshot(listOf(renamed)), capturedAt), cached())
        store.receive(Action.SnapshotRefreshed::class) {
            it.copy(
                noticeReadiness = HomeFeature.State.NoticeReadiness.listed,
                sessions = refreshed.sessions,
                activeSessionIDs = refreshed.activeSessionIDs,
                runChangesDuringRefresh = emptyMap(),
                sessionReadDates = it.sessionReadDates + (it.key(session.id) to session.updatedAt),
                initializedInboxServerIDs = setOf(server.persistenceID),
            )
        }
        assertEquals(CachedHomeSnapshot(server.persistenceID, refreshed, capturedAt), cached())
        // A snapshot never rewrites the saved server: only pairing, adding, editing or switching does.
        assertNull(h.shared(ServerSharedKeys.savedServer))
        assertTrue(h.shared(ServerSharedKeys.savedServers).isEmpty())
        store.receive(Action.AttentionRefreshed(emptySet())) { it.copy(noticeReadiness = HomeFeature.State.NoticeReadiness.settled) }
        val deletedCache = CachedHomeSnapshot(server.persistenceID, snapshot(emptyList()), capturedAt)
        store.send(Action.ServerEvent(OpenCodeServerEvent.SessionDeleted(sessionID = "ses_1"))) {
            it.copy(
                sessions = emptyList(),
                activeSessionIDs = emptySet(),
                transcriptCache = emptyMap(),
                sessionReadDates = it.sessionReadDates - it.key(session.id),
            )
        }
        assertEquals(deletedCache, cached())
    }

    @Test
    fun alternateAddressPropagatesIntoSelectedSession() = homeTest { h ->
        val primary = OpenCodeServer(url = "http://primary.test", username = "opencode", password = "pw", alternateURLs = listOf("http://alternate.test"))
        val alternate = primary.at("http://alternate.test")
        val session = OpenCodeSession(id = "ses_1", serverTitle = "Selected", projectID = "p", directory = "/project", updatedAt = Instant.now())
        val state = h.state {
            HomeFeature.State(primary, snapshot(listOf(session))).copy(sessionDetail = SessionFeature.State.session(primary, session, cachedItems = emptyList()))
        }
        val store = h.store(state) {
            this[Clock] = TestClock()
            this[DateClient] = DateClient.constant(date(100))
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
                sessionFamily = { _, root -> OpenCodeSessionFamily(listOf(root), emptySet()) },
                runningShells = { _, _ -> emptyList() },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.ServerConnected(alternate))
        store.receive(detail(SessionFeature.Action.EventStreamConnected(alternate)))
        assertEquals(alternate, store.state.server)
        assertEquals(alternate, store.state.sessionDetail?.server)
        h.skipInFlightEffects()
    }

    @Test
    fun unauthorizedEventStreamPausesWithCredentialError() = homeTest { h ->
        val server = OpenCodeServer(url = "http://server.test", username = "opencode", password = "wrong")
        val state = h.state {
            val base = HomeFeature.State(server, snapshot(emptyList()))
            base.copy(connection = base.connection.copy(connectionStatus = HomeConnectionFeature.State.ConnectionStatus.reconnecting))
        }
        val store = h.store(state) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeSnapshot = { _ -> snapshot(emptyList()) },
                homeAttention = { _, _, _ -> emptySet() },
            )
        }
        store.exhaustive = false
        val error = OpenCodeClientError("The server rejected these credentials.", statusCode = 401)

        store.send(Action.EventStreamFailed(error)) {
            it.copy(
                connection = it.connection.copy(
                    connectionStatus = HomeConnectionFeature.State.ConnectionStatus.disconnected,
                    connectionErrorMessage = error.message,
                    credentialsRejected = true,
                ),
            )
        }
        assertEquals(HomeConnectionFeature.State.ConnectionStatus.disconnected, store.state.connectionStatus)
        assertEquals(error.message, store.state.connectionErrorMessage)
        store.send(Action.RetryConnectionTapped)
        h.skipInFlightEffects()
    }

    @Test
    fun offlineHomeOpensCachedSessionsAndOnlyAnOutageBlocksNewSessions() {
        for (isReconnecting in listOf(false, true)) homeTest { h ->
            val server = OpenCodeServer(url = "https://saved.example", username = "kit", password = "secret")
            val session = OpenCodeSession(id = "ses_cached", serverTitle = "Cached work", projectID = "proj_cached", directory = "/work", updatedAt = date(1_000))
            val items = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "m_cached", text = "Cached message"))
            val state = h.state {
                val base = HomeFeature.State(server, snapshot(listOf(session), active = setOf(session.id)))
                base.copy(
                    connection = base.connection.copy(
                        connectionStatus = if (isReconnecting) HomeConnectionFeature.State.ConnectionStatus.reconnecting
                        else HomeConnectionFeature.State.ConnectionStatus.disconnected,
                    ),
                    transcriptCache = mapOf(session.id to items),
                )
            }
            val store = h.store(state)

            store.send(Action.SessionTapped(session.id)) {
                it.copy(
                    detailGeneration = 2,
                    sessionDetail = h.state { SessionFeature.State.session(server, session, cachedItems = items, isRunning = true, navigationID = 2) }
                        .copy(newMessagesBaseline = setOf("m_cached")).withRequestsConnectionIsAvailable(false),
                    sessionReadDates = it.sessionReadDates + (it.key(session.id) to session.updatedAt),
                    lastViewedSessionIDs = it.lastViewedSessionIDs + (server.persistenceID to session.id),
                )
            }
            assertEquals(items, store.state.sessionDetail?.items)
            assertEquals(false, store.state.sessionDetail?.isLoading)
            // A retrying connection still allows a draft (first send reports any failure); a
            // confirmed outage keeps the cached detail in place.
            if (isReconnecting) {
                store.send(Action.NewSessionTapped) {
                    h.state { it.rememberingSession() }.copy(
                        detailGeneration = 3,
                        sessionDetail = h.state { SessionFeature.State.draft(server, draftProject = null, projects = emptyList(), navigationID = 3) }
                            .withRequestsConnectionIsAvailable(false),
                    )
                }
            } else {
                store.send(Action.NewSessionTapped)
            }
            assertEquals(isReconnecting, store.state.sessionDetail?.isDraft)
            assertEquals(listOf(session), store.state.sessions)
            assertEquals("A cached launch has no live stream to trust", HomeFeature.State.Indicator.none, store.state.indicator(session))
            store.finish()
        }
    }

    @Test
    fun attentionEventsReconcileFromAuthoritativePendingRequests() = homeTest { h ->
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "ses_1", serverTitle = "Work", projectID = "proj_1", directory = "/tmp/demo", updatedAt = Instant.now())
        val state = h.state {
            HomeFeature.State(server, snapshot(listOf(session), projects = listOf(OpenCodeProject(id = "proj_1", name = "demo", directory = "/tmp/demo"))))
        }
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(homeAttention = { _, _, _ -> setOf(session.id) })
        }

        store.send(Action.ServerEvent(OpenCodeServerEvent.AttentionChanged(askedSessionID = session.id))) {
            it.copy(attentionSessionIDs = setOf(session.id))
        }
        store.receive(Action.AttentionRefreshed(setOf(session.id)))
        // Unknown (child) owners and settlements wait for the authoritative refetch.
        store.send(Action.ServerEvent(OpenCodeServerEvent.AttentionChanged(askedSessionID = "ses_child_unknown")))
        store.receive(Action.AttentionRefreshed(setOf(session.id)))
        store.send(Action.ServerEvent(OpenCodeServerEvent.AttentionChanged(askedSessionID = null)))
        store.receive(Action.AttentionRefreshed(setOf(session.id)))
    }

    @Test
    fun requestSettledElsewhereRefreshesTheOpenSessionWithoutWaitingForItsPoll() = homeTest { h ->
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "ses_1", serverTitle = "Work", projectID = "proj_1", directory = "/tmp/demo", updatedAt = Instant.now())
        val state = h.state {
            HomeFeature.State(server, snapshot(listOf(session))).copy(sessionDetail = SessionFeature.State.session(server, session, cachedItems = emptyList()))
        }
        val fetches = LockIsolated(0)
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeAttention = { _, _, _ -> emptySet() },
                familyPendingRequests = { _, _, _ ->
                    fetches.increment()
                    OpenCodePendingRequests(permissions = emptyList())
                },
            )
        }
        store.exhaustive = false

        store.send(Action.ServerEvent(OpenCodeServerEvent.AttentionChanged(askedSessionID = null)))
        store.receive(detail(SessionFeature.Action.RequestsChanged))
        store.receiveDetail(SessionFeature.Action.PendingRequestsResponse::class)
        assertEquals(1, fetches.value)
        h.skipInFlightEffects()
    }
}
