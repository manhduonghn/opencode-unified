package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.files.ComposerStagedFile
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.composer.ComposerFileAttachment
import ai.opencode.feature.composer.ComposerPersistence
import ai.opencode.feature.composer.ComposerPersistenceStorage
import ai.opencode.feature.composer.DraftState
import ai.opencode.feature.composer.PendingPrompt
import ai.opencode.feature.composer.PersistedComposerState
import ai.opencode.feature.composer.PromptOutbox
import ai.opencode.feature.composer.SharedComposerDraft
import ai.opencode.feature.composer.SharedDraftTarget
import ai.opencode.feature.composer.SharedLocation
import ai.opencode.feature.composer.UndeliveredPrompts
import ai.opencode.feature.drawer.HomeFeatureTestSupport.LockIsolated
import ai.opencode.feature.drawer.HomeFeatureTestSupport.detail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receiveBounded
import ai.opencode.feature.drawer.catchup.CatchUpFeature
import ai.opencode.feature.session.BindingAction
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.pendingFiles
import ai.opencode.feature.session.pendingLocation
import ai.opencode.feature.session.withDraft
import ai.opencode.feature.session.withPendingFiles
import ai.opencode.feature.session.withPendingLocation
import ai.opencode.feature.system.share.ReceivedShare
import ai.opencode.feature.system.share.ShareHandoff
import ai.opencode.server.CachedHomeSnapshot
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Family switches and Catch Up steps assert the composer and persistence rather than every field.
 */
class ComposerPersistenceTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "opencode", password = "pw")
    private val otherServer = OpenCodeServer(url = "http://localhost:10", username = "opencode", password = "pw")
    private val project = OpenCodeProject(id = "proj_1", name = "OpenCode", directory = "/Users/kit/code/opencode")
    private val session = OpenCodeSession(
        id = "ses_1",
        serverTitle = "Durable drafts",
        projectID = "proj_1",
        directory = "/Users/kit/code/opencode",
        updatedAt = Instant.ofEpochSecond(1_000),
    )
    private val secondID = "msg_00000000000200000000000000"

    /** One test's dependency world: the states built before the store share its persistence and shared store. */
    private class World(
        val persistence: ComposerPersistence,
        val outbox: PromptOutbox = PromptOutbox(),
        val undelivered: UndeliveredPrompts = UndeliveredPrompts(),
    ) {
        val sharedStore: SharedStore = SharedStore.inMemory()

        val pinned: DependencyValues.Builder.() -> Unit = {
            this[SharedStore] = sharedStore
            this[ComposerPersistence] = persistence
            this[PromptOutbox] = outbox
            this[UndeliveredPrompts] = undelivered
            this[DateClient] = DateClient.constant(Instant.EPOCH)
        }

        fun <R> state(block: () -> R): R = DependencyValues.withValues(DependencyValues.testing().update(pinned), block)

        fun store(scope: CoroutineScope, state: HomeFeature.State, dependencies: DependencyValues.Builder.() -> Unit = {}) =
            TestStore(state, HomeFeature(), scope) {
                pinned()
                dependencies()
            }
    }

    private fun setDraft(text: String): SessionFeature.Action = SessionFeature.Action.Binding(BindingAction.Draft(text))

    /** Reads that never answer: the screens' opening effects stay out of the way. */
    @Suppress("UNUSED_PARAMETER")
    private fun hangingClient(getSession: (String) -> OpenCodeSession): OpenCodeClient = OpenCodeClient.testValue.copy(
        getSession = { _, _ -> awaitCancellation() },
        transcript = { _, _, _ -> awaitCancellation() },
        sessionFamily = { _, _ -> awaitCancellation() },
        modelCatalog = { _, _ -> awaitCancellation() },
        familyPendingRequests = { _, _, _ -> awaitCancellation() },
        pendingInputs = { _, _ -> awaitCancellation() },
        runningShells = { _, _ -> awaitCancellation() },
        viewSession = { _, _, _ -> },
    )

    @Test
    fun sessionDeletionServerDeletionAndStalenessCleanUpPersistedState() = runTest {
        val tempDir = Files.createTempDirectory("ComposerPersistenceTests-").toFile()
        try {
            val currentTime = LockIsolated(Instant.ofEpochSecond(1_000_000))
            val persistence = ComposerPersistence.make(ComposerPersistenceStorage.live(tempDir), debounce = 60.seconds, now = { currentTime.value })

            fun attachment(id: String, data: String, name: String) =
                OpenCodeOutgoingAttachment(id = id, data = data.toByteArray(), mime = "image/png", name = name)
            val attachmentA = attachment("00000000-0000-0000-0000-000000000031", "a", "a.png")
            val attachmentB = attachment("00000000-0000-0000-0000-000000000032", "b", "b.png")
            val attachmentStale = attachment("00000000-0000-0000-0000-000000000033", "stale", "stale.png")

            persistence.scheduleDraft(server.persistenceID, session.id, null, DraftState(text = "Draft in ses_1", attachments = listOf(attachmentA)))
            persistence.scheduleDraft(otherServer.persistenceID, "ses_other", null, DraftState(text = "Draft on other server", attachments = listOf(attachmentB)))
            persistence.scheduleDraft(server.persistenceID, "ses_stale", null, DraftState(text = "Stale draft", attachments = listOf(attachmentStale)))
            persistence.flush()

            val blobA = File(tempDir, "${ComposerPersistenceStorage.ATTACHMENTS_DIRECTORY_NAME}/${attachmentA.id}")
            val blobB = File(tempDir, "${ComposerPersistenceStorage.ATTACHMENTS_DIRECTORY_NAME}/${attachmentB.id}")
            val blobStale = File(tempDir, "${ComposerPersistenceStorage.ATTACHMENTS_DIRECTORY_NAME}/${attachmentStale.id}")
            assertTrue(blobA.exists())
            assertTrue(blobB.exists())
            assertTrue(blobStale.exists())

            // Delete session `ses_1` via HomeFeature.serverEvent(.sessionDeleted).
            val world = World(persistence)
            val homeState = world.state {
                HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listOf(project), sessions = listOf(session), activeSessionIDs = emptySet()))
            }.copy(sessionDetail = null)

            val homeStore = world.store(this, homeState) {
                this[HomeSnapshotDate] = DateClient.constant(Instant.ofEpochSecond(1_000_000))
            }

            homeStore.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.SessionDeleted(sessionID = session.id))) {
                it.copy(sessions = emptyList())
            }
            assertEquals(
                CachedHomeSnapshot(
                    serverID = server.persistenceID,
                    snapshot = OpenCodeHomeSnapshot(projects = listOf(project), sessions = emptyList(), activeSessionIDs = emptySet()),
                    capturedAt = Instant.ofEpochSecond(1_000_000),
                ),
                homeStore.withDependencies { ai.opencode.core.sharing.shared(ServerSharedKeys.cachedHomeSnapshots).value[server.persistenceID] },
            )
            homeStore.finish()
            persistence.flush()

            assertNull(persistence.loadDraft(server.persistenceID, session.id))
            assertFalse("Deleted session's attachment blob is cleaned up", blobA.exists())
            assertEquals("Draft on other server", persistence.loadDraft(otherServer.persistenceID, "ses_other")?.draft?.text)

            // Advance past 30 days and reload from disk: stale entry is pruned and its attachment blob discarded.
            currentTime.setValue(Instant.ofEpochSecond(1_000_000 + PersistedComposerState.STALENESS_INTERVAL_SECONDS + 60))
            val relaunchedPersistence = ComposerPersistence.make(ComposerPersistenceStorage.live(tempDir), debounce = 60.seconds, now = { currentTime.value })
            assertNull(relaunchedPersistence.loadDraft(server.persistenceID, "ses_stale"))
            assertFalse("Stale draft's attachment blob is cleaned up", blobStale.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun incomingShareAndKeepDraftMergeIntoPersistedDraftAndDisarmSendLater() = runTest {
        val persistence = ComposerPersistence.inMemory(debounce = 60.seconds)
        val targetSession = OpenCodeSession(id = "ses_target", serverTitle = "Target", projectID = project.id, directory = project.directory, updatedAt = Instant.ofEpochSecond(500))
        persistence.scheduleDraft(server.persistenceID, targetSession.id, null, DraftState(text = "Persisted draft before launch"))

        val world = World(persistence)
        val homeState = world.state {
            HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listOf(project), sessions = listOf(session, targetSession), activeSessionIDs = emptySet()))
                .copy(transcriptCache = mapOf(session.id to emptyList(), targetSession.id to emptyList()))
        }.copy(snapshotLoaded = true)

        val store = world.store(backgroundScope, homeState) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(viewSession = { _, _, _ -> })
        }
        store.exhaustive = false

        // 1. A Send Later share arrives for `targetSession`, which has a persisted draft on disk.
        val sendLaterShare = ReceivedShare(
            handoff = ShareHandoff(
                id = UUID.fromString("00000000-0000-0000-0000-000000000090"),
                createdAt = Instant.ofEpochSecond(2_000),
                serverID = server.persistenceID,
                target = ShareHandoff.Target.Session(id = targetSession.id, projectID = project.id),
                text = "https://example.com/shared",
                delivery = ShareHandoff.Delivery.send,
                messageID = "msg_shared_send",
            ),
            images = emptyList(),
        )

        store.send(HomeFeature.Action.SharesReceived(listOf(sendLaterShare))) {
            it.copy(shareNavigationTarget = SharedDraftTarget.Session(targetSession.id))
        }
        store.receive(HomeFeature.Action.SessionTapped(targetSession.id))
        assertEquals(targetSession.id, store.state.sessionDetail?.session?.id)
        assertEquals("Persisted draft before launch\n\nhttps://example.com/shared", store.state.sessionDetail?.draft)
        assertEquals(1, store.state.sessionDetail?.composerFocusRequest)
        assertNull("Send Later is disarmed when a persisted draft already existed", store.state.sessionDetail?.sharedSend)
        assertNull(store.state.shareNavigationTarget)

        // 2. Seed a persisted draft on `session` and deliver `.keepDraft` dictation while `targetSession` is open.
        persistence.scheduleDraft(server.persistenceID, session.id, null, DraftState(text = "Earlier session notes"))

        store.send(detail(SessionFeature.Action.Delegate(SessionFeature.Delegate.KeepDraft(SharedDraftTarget.Session(session.id), SharedComposerDraft(text = "dictated addition")))))
        assertEquals("Earlier session notes\n\ndictated addition", persistence.loadDraft(server.persistenceID, session.id)?.draft?.text)

        store.send(HomeFeature.Action.SessionTapped(session.id))
        assertEquals(session.id, store.state.sessionDetail?.session?.id)
        assertEquals("Earlier session notes\n\ndictated addition", store.state.sessionDetail?.draft)
    }

    @Test
    fun parkDraftFamilyDraftsAndCatchUpPreserveAttachmentsAndNeverDuplicateOrResurrectDrafts() = runTest {
        val persistence = ComposerPersistence.inMemory(debounce = 60.seconds)
        val childSession = OpenCodeSession(id = "ses_child", serverTitle = "Subagent", projectID = project.id, directory = project.directory, updatedAt = Instant.ofEpochSecond(1_200), parentID = session.id)
        val unreadSession = OpenCodeSession(id = "ses_unread", serverTitle = "Unread", projectID = project.id, directory = project.directory, updatedAt = Instant.ofEpochSecond(1_800), idleMilliseconds = 5_000)

        val stagedFile = ComposerStagedFile(
            id = UUID.fromString("00000000-0000-0000-0000-000000000095"),
            name = "report.txt",
            mime = "text/plain",
            size = 42,
            url = File(System.getProperty("java.io.tmpdir"), "report.txt"),
        )
        val uploadedFile = ComposerFileAttachment(stagedFile, status = ComposerFileAttachment.Status.Uploaded(path = "/tmp/report.txt"))
        val location = SharedLocation(
            latitude = 37.7749,
            longitude = -122.4194,
            accuracy = 10.0,
            place = "Ferry Building",
            address = "San Francisco, CA",
            capturedAt = Instant.ofEpochSecond(1_500),
        )

        // 1. Subagent push -> type draft with file + location -> pop (`parkDraft`) -> push again:
        // preserves file & location and restores text once without duplicating.
        val world = World(persistence)
        var homeState = world.state {
            var state = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listOf(project), sessions = listOf(session, childSession, unreadSession), activeSessionIDs = emptySet()))
            state = state.copy(transcriptCache = mapOf(session.id to emptyList(), childSession.id to emptyList(), unreadSession.id to emptyList()))
            state = state.openingDetail(session, cachedItems = emptyList())
            state = state.updatingDetail { it.copy(familySessions = listOf(session, childSession)) }
            state = state.pushingSubagent(childSession)
            state = state.copy(subagentStack = listOf(state.subagentStack[0].withDraft("Subagent draft").withPendingFiles(listOf(uploadedFile)).withPendingLocation(location)))
            state = state.poppingSubagents(toDepth = 0)
            state.pushingSubagent(childSession)
        }

        assertEquals(1, homeState.subagentStack.size)
        assertEquals("Subagent draft restores once without duplication", "Subagent draft", homeState.subagentStack[0].draft)
        assertEquals("parkDraft preserves staged files", listOf(uploadedFile), homeState.subagentStack[0].pendingFiles)
        assertEquals("parkDraft preserves shared location", location, homeState.subagentStack[0].pendingLocation)

        // 2. Family switch in SessionFeature: sending a draft in childSession clears it in ComposerPersistence
        // so switching back to root and then back to childSession never resurrects the sent draft.
        val familyState = world.state {
            SessionFeature.State.session(server, session, cachedItems = emptyList(), initialTranscriptRequestIsExternal = true).copy(
                familySessions = listOf(session, childSession),
                familyRootID = session.id,
                familyTranscriptCache = mapOf(session.id to emptyList(), childSession.id to emptyList()),
            )
        }
        val familyStore = TestStore(familyState, SessionFeature(), backgroundScope) {
            world.pinned(this)
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = ImmediateClock()
            this[PromptOutbox] = PromptOutbox(scope = backgroundScope)
            this[UndeliveredPrompts] = UndeliveredPrompts()
            this[OpenCodeClient] = hangingClient { id -> if (id == childSession.id) childSession else session }.copy(
                sendPrompt = { _, _, _, _, _, _, _ -> },
                waitForIdle = { _, _ -> throw OpenCodeClientError(message = "Idle wait not under test") },
            )
        }
        familyStore.exhaustive = false

        fun assertComposer(sessionID: String, draft: String, files: List<ComposerFileAttachment>, location: SharedLocation?) {
            assertEquals(sessionID, familyStore.state.session?.id)
            assertEquals(draft, familyStore.state.draft)
            assertEquals(files, familyStore.state.pendingFiles)
            assertEquals(location, familyStore.state.pendingLocation)
        }

        familyStore.send(SessionFeature.Action.FamilyPicked(childSession.id))
        assertComposer(childSession.id, "Subagent draft", listOf(uploadedFile), location)

        familyStore.send(SessionFeature.Action.FamilyPicked(session.id))
        assertComposer(session.id, "", emptyList(), null)
        assertEquals("Subagent draft", persistence.loadDraft(server.persistenceID, childSession.id)?.draft?.text)

        familyStore.send(SessionFeature.Action.FamilyPicked(childSession.id))
        assertComposer(childSession.id, "Subagent draft", listOf(uploadedFile), location)

        familyStore.send(SessionFeature.Action.SendTapped)
        assertComposer(childSession.id, "", emptyList(), null)
        assertTrue(familyStore.state.isAwaitingReply)
        familyStore.receiveBounded({ it is SessionFeature.Action.PromptAdmitted && it.sessionID == childSession.id }, "promptAdmitted")
        assertNull("Sending clears the family draft from persistence", persistence.loadDraft(server.persistenceID, childSession.id))

        familyStore.send(SessionFeature.Action.FamilyPicked(session.id))
        assertEquals(session.id, familyStore.state.session?.id)

        familyStore.send(SessionFeature.Action.FamilyPicked(childSession.id))
        assertComposer(childSession.id, "", emptyList(), null)
        assertEquals("Sent draft never comes back from familyDrafts", "", familyStore.state.draft)

        // 3. Catch Up card with `readsOnView == false` does not restore persisted draft on `.task`,
        // and leaving Catch Up restores the return composer's text, files, and location once.
        persistence.scheduleDraft(server.persistenceID, unreadSession.id, null, DraftState(text = "Persisted unread draft"))
        homeState = world.state {
            homeState.poppingSubagents(toDepth = 0)
                .updatingDetail { it.withDraft("Return composer text").withPendingFiles(listOf(uploadedFile)).withPendingLocation(location) }
        }

        val catchUpStore = world.store(backgroundScope, homeState) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(viewSession = { _, _, _ -> })
        }
        catchUpStore.exhaustive = false

        catchUpStore.send(HomeFeature.Action.CatchUp(CatchUpFeature.Action.Tapped))
        assertEquals(session.id, catchUpStore.state.catchUp?.returnSessionID)
        assertEquals("Return composer text", catchUpStore.state.catchUp?.returnComposer?.text)
        assertEquals(listOf(uploadedFile), catchUpStore.state.catchUp?.returnComposer?.files)
        assertEquals(location, catchUpStore.state.catchUp?.returnComposer?.location)
        assertEquals(unreadSession.id, catchUpStore.state.sessionDetail?.session?.id)
        assertEquals(false, catchUpStore.state.sessionDetail?.readsOnView)
        assertEquals("Catch Up card starts with an empty composer", "", catchUpStore.state.sessionDetail?.draft)

        // The card's `task` doesn't load the persisted draft either.
        catchUpStore.send(detail(SessionFeature.Action.Task))
        assertEquals("restorePersistedComposer does not load persisted draft when readsOnView is false", "", catchUpStore.state.sessionDetail?.draft)

        catchUpStore.send(HomeFeature.Action.CatchUp(CatchUpFeature.Action.ExitTapped))
        assertNull(catchUpStore.state.catchUp)
        assertEquals(session.id, catchUpStore.state.sessionDetail?.session?.id)
        assertEquals("Return composer restores once without duplication", "Return composer text", catchUpStore.state.sessionDetail?.draft)
        assertEquals("Return composer keeps its files", listOf(uploadedFile), catchUpStore.state.sessionDetail?.pendingFiles)
        assertEquals("Return composer keeps its location", location, catchUpStore.state.sessionDetail?.pendingLocation)
    }

    @Test
    fun familySwitchDeduplicationSessionCreatedOutboxPromotionAndSubagentCatchUpParking() = runTest {
        val persistence = ComposerPersistence.inMemory(debounce = 60.seconds)
        val outbox = PromptOutbox(scope = backgroundScope)
        val undelivered = UndeliveredPrompts()
        outbox.bind(persistence)
        undelivered.bind(persistence)

        val childSession = OpenCodeSession(id = "ses_child", serverTitle = "Subagent", projectID = project.id, directory = project.directory, updatedAt = Instant.ofEpochSecond(1_100), parentID = session.id)
        val unreadSession = OpenCodeSession(id = "ses_unread", serverTitle = "Unread root", projectID = project.id, directory = project.directory, updatedAt = Instant.ofEpochSecond(1_200), idleMilliseconds = 2_000)
        val unreadChild = OpenCodeSession(id = "ses_unread_child", serverTitle = "Unread child", projectID = project.id, directory = project.directory, updatedAt = Instant.ofEpochSecond(1_250), idleMilliseconds = 2_500, parentID = unreadSession.id)

        // 1. Duplicate draft on .familyPicked: neither parkDraft nor setSharedDraft duplicates into the composer.
        val world = World(persistence, outbox, undelivered)
        val homeState = world.state {
            var state = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listOf(project), sessions = listOf(unreadSession, session, childSession), activeSessionIDs = emptySet()))
            state = state.copy(transcriptCache = mapOf(session.id to emptyList(), childSession.id to emptyList(), unreadSession.id to emptyList()))
            state = state.openingDetail(session, cachedItems = emptyList())
            state = state.updatingDetail { it.copy(familySessions = listOf(session, childSession), familyRootID = session.id) }
            state.pushingSubagent(childSession)
        }

        val familyDedupStore = world.store(backgroundScope, homeState) {
            this[OpenCodeClient] = hangingClient { id -> if (id == childSession.id) childSession else session }
        }
        familyDedupStore.exhaustive = false

        val pushedID = requireNotNull(familyDedupStore.state.subagentStack.lastOrNull()?.navigationID)
        familyDedupStore.send(HomeFeature.Action.SubagentStack(pushedID, setDraft("Parked subagent draft")))
        familyDedupStore.send(HomeFeature.Action.SubagentsPopped(depth = 0))
        val parkedSubagentShare = familyDedupStore.withDependencies { familyDedupStore.state.sharedDrafts[SharedDraftTarget.Session(childSession.id)]?.text }
        assertEquals("Parked subagent draft", parkedSubagentShare)

        familyDedupStore.send(detail(SessionFeature.Action.FamilyPicked(childSession.id)))
        assertEquals(
            "Picking a family session after parkDraft loads the draft once without appending waitingShares again",
            "Parked subagent draft",
            familyDedupStore.state.sessionDetail?.draft,
        )

        familyDedupStore.send(detail(setDraft("")))
        familyDedupStore.send(detail(SessionFeature.Action.FamilyPicked(session.id)))
        familyDedupStore.send(detail(SessionFeature.Action.Delegate(SessionFeature.Delegate.KeepDraft(SharedDraftTarget.Session(childSession.id), SharedComposerDraft(text = "Shared to child")))))
        familyDedupStore.send(detail(SessionFeature.Action.FamilyPicked(childSession.id)))
        assertEquals(
            "Picking a family session with a waiting share absorbs it once without pre-loading from current.drafts",
            "Shared to child",
            familyDedupStore.state.sessionDetail?.draft,
        )

        // 2. .sessionCreated promotes the draft outbox and clears outboxDraftID, and restorePersistedDraft migrates nil-session drafts.
        val createdSession = OpenCodeSession(id = "ses_created_from_draft", serverTitle = "Created session", projectID = project.id, directory = project.directory, updatedAt = Instant.ofEpochSecond(1_300))
        val draftHomeState = world.state {
            HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listOf(project), sessions = listOf(session), activeSessionIDs = emptySet())).openingDraft()
        }

        val sendAttempt = LockIsolated(0)
        val draftCreatedStore = world.store(backgroundScope, draftHomeState) {
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(2_000))
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                createSession = { _, _, _ -> createdSession },
                getSession = { _, _ -> createdSession },
                message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
                sendPrompt = { _, _, _, _, _, _, _ ->
                    val attempt = synchronized(sendAttempt) {
                        sendAttempt.update { it + 1 }
                        sendAttempt.value
                    }
                    if (attempt > 1) throw OpenCodeClientError(message = "Second prompt failed on aliased draft lane", statusCode = 400)
                },
                waitForIdle = { _, _ -> throw OpenCodeClientError(message = "Idle wait not under test") },
                transcript = { _, _, _ -> emptyList() },
                sessionFamily = { _, root -> OpenCodeSessionFamily(listOf(root), emptySet()) },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
                runningShells = { _, _ -> emptyList() },
                viewSession = { _, _, _ -> },
            )
        }
        draftCreatedStore.exhaustive = false

        draftCreatedStore.send(detail(setDraft("First prompt in new session")))
        draftCreatedStore.send(detail(SessionFeature.Action.SendTapped))
        draftCreatedStore.receiveBounded({ it.detailAction == SessionFeature.Action.SessionCreated(createdSession) }, "sessionCreated")
        assertEquals(createdSession.id, draftCreatedStore.state.sessionDetail?.session?.id)
        assertNull("sessionCreated clears outboxDraftID", draftCreatedStore.state.sessionDetail?.outboxDraftID)
        draftCreatedStore.receiveBounded({ it.detailAction is SessionFeature.Action.PromptAdmitted }, "promptAdmitted")

        // Now send a second prompt on `.session(createdSession.id)` (which aliases to the unpruned draft lane) and fail:
        draftCreatedStore.send(detail(setDraft("Second prompt in created session")))
        draftCreatedStore.send(detail(SessionFeature.Action.SendTapped))
        draftCreatedStore.receiveBounded({ it.detailAction is SessionFeature.Action.PromptFailed }, "promptFailed")
        assertTrue(
            "Later send or failure on the aliased session lane never writes draftID back into the nil-session outbox",
            persistence.loadOutbox(server.persistenceID, null).isEmpty(),
        )
        assertEquals(1, persistence.loadOutbox(server.persistenceID, createdSession.id).size)

        draftCreatedStore.send(HomeFeature.Action.NewSessionTapped)
        assertNull("Later New Session tap starts fresh instead of adopting the old session", draftCreatedStore.state.sessionDetail?.session)
        assertNull(draftCreatedStore.state.sessionDetail?.outboxDraftID)

        // Cold restore when createSession succeeded before termination and a follow-up draft was typed under nil sessionID:
        val coldPersistence = ComposerPersistence.inMemory(debounce = 60.seconds)
        val coldPending = PendingPrompt(
            messageID = secondID,
            text = "Created before crash",
            attachments = emptyList(),
            files = emptyList(),
            wasRunning = false,
            delivery = OpenCodeDelivery.Steer,
            requestEpoch = 1,
            model = null,
        )
        coldPersistence.recordOutbox(server.persistenceID, null, secondID, project.directory, secondID, createdSession, coldPending, false, "${server.persistenceID}|draft:$secondID")
        coldPersistence.scheduleDraft(server.persistenceID, null, project, DraftState(text = "Follow-up typed before create settled"))
        val coldWorld = World(coldPersistence)
        val coldRestoredHome = coldWorld.state {
            HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listOf(project), sessions = listOf(session), activeSessionIDs = emptySet()))
        }
        assertNull("A new draft doesn't adopt the session another draft created", coldRestoredHome.sessionDetail?.session)
        assertEquals("Follow-up typed before create settled", coldRestoredHome.sessionDetail?.draft)

        // 3. Catch Up entered from a subagent parks the subagent composer under the subagent ID without overwriting the root draft,
        // and promoteCatchUpDetail migrates catchUpFamilyDrafts.
        val catchUpPersistence = ComposerPersistence.inMemory(debounce = 60.seconds)
        val catchUpWorld = World(catchUpPersistence)
        val subagentCatchUpHome = catchUpWorld.state {
            var state = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listOf(project), sessions = listOf(unreadSession, session), activeSessionIDs = emptySet()))
            state = state.copy(transcriptCache = mapOf(session.id to emptyList(), childSession.id to emptyList(), unreadSession.id to emptyList(), unreadChild.id to emptyList()))
            state = state.openingDetail(session, cachedItems = emptyList())
            state.updatingDetail { it.copy(familySessions = listOf(session, childSession), familyRootID = session.id) }
        }

        val subagentCatchUpStore = catchUpWorld.store(backgroundScope, subagentCatchUpHome) {
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(2_000))
            this[OpenCodeClient] = hangingClient { id ->
                when (id) {
                    childSession.id -> childSession
                    unreadSession.id -> unreadSession
                    unreadChild.id -> unreadChild
                    else -> session
                }
            }
        }
        subagentCatchUpStore.exhaustive = false

        catchUpPersistence.scheduleDraft(server.persistenceID, unreadSession.id, null, DraftState(text = "Earlier unread root draft"))
        subagentCatchUpStore.send(detail(setDraft("Root draft stays intact")))
        subagentCatchUpStore.send(detail(SessionFeature.Action.FamilyPicked(childSession.id)))
        subagentCatchUpStore.send(detail(setDraft("Subagent draft before Catch Up")))
        assertEquals("Root draft stays intact", catchUpPersistence.loadDraft(server.persistenceID, session.id)?.draft?.text)

        subagentCatchUpStore.send(HomeFeature.Action.CatchUp(CatchUpFeature.Action.Tapped))
        assertEquals(session.id, subagentCatchUpStore.state.catchUp?.returnSessionID)
        assertEquals(childSession.id, subagentCatchUpStore.state.catchUp?.returnComposerSessionID)

        subagentCatchUpStore.send(detail(SessionFeature.Action.FamilyResponse(Result.success(OpenCodeSessionFamily(sessions = listOf(unreadSession, unreadChild), activeSessionIDs = emptySet())))))
        subagentCatchUpStore.send(detail(setDraft("Unread root card draft")))
        subagentCatchUpStore.send(detail(SessionFeature.Action.FamilyPicked(unreadChild.id)))
        subagentCatchUpStore.send(detail(setDraft("Unread child card draft")))

        subagentCatchUpStore.send(HomeFeature.Action.CatchUp(CatchUpFeature.Action.OpenTapped))
        assertEquals(
            "Leaving Catch Up entered from a subagent does not overwrite the root session's draft",
            "Root draft stays intact",
            catchUpPersistence.loadDraft(server.persistenceID, session.id)?.draft?.text,
        )
        assertEquals(
            "Leaving Catch Up parks the subagent's composer under the subagent's own session ID",
            "Subagent draft before Catch Up",
            catchUpPersistence.loadDraft(server.persistenceID, childSession.id)?.draft?.text,
        )
        assertEquals(
            "promoteCatchUpDetail merges drafts typed on other family members of the Catch Up card after any existing draft",
            "Earlier unread root draft\n\nUnread root card draft",
            catchUpPersistence.loadDraft(server.persistenceID, unreadSession.id)?.draft?.text,
        )
        assertEquals(unreadChild.id, subagentCatchUpStore.state.sessionDetail?.session?.id)
        assertEquals("Unread child card draft", subagentCatchUpStore.state.sessionDetail?.draft)
        subagentCatchUpStore.send(detail(SessionFeature.Action.FamilyPicked(unreadSession.id)))
        assertEquals("Earlier unread root draft\n\nUnread root card draft", subagentCatchUpStore.state.sessionDetail?.draft)
    }
}
