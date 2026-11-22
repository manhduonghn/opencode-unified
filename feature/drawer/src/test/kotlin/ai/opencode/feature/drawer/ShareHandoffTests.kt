package ai.opencode.feature.drawer

import kotlin.time.Duration.Companion.seconds
import ai.opencode.core.files.ComposerStagedFile
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.composer.ComposerFileAttachment
import ai.opencode.feature.session.isDraft
import ai.opencode.feature.session.pendingAttachments
import ai.opencode.feature.session.pendingFiles
import ai.opencode.feature.session.sendAfterUploads
import ai.opencode.feature.session.unstartedUploadIDs
import ai.opencode.feature.session.withDraft
import ai.opencode.feature.session.withPendingAttachments
import ai.opencode.feature.composer.SharedDraftTarget
import ai.opencode.feature.system.share.ReceivedShare
import ai.opencode.feature.system.share.ShareHandoff
import ai.opencode.feature.system.share.ShareTargets
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeUploadEvent
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ShareInboxFeatureTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:4096", username = "kit", password = "secret")
    private val project = OpenCodeProject(id = "proj", name = "Work", directory = "/work")
    private val other = OpenCodeProject(id = "proj_other", name = "Other", directory = "/other")
    private val current = OpenCodeSession(
        id = "ses_current", serverTitle = "Current", projectID = "proj", directory = "/work", updatedAt = Instant.ofEpochSecond(30),
    )
    private val target = OpenCodeSession(
        id = "ses_target", serverTitle = "Target", projectID = "proj_other", directory = "/other", updatedAt = Instant.ofEpochSecond(20),
    )
    private val screenshot = ReceivedShare.Image(id = UUID(0, 1), data = byteArrayOf(0xFF.toByte(), 0xD8.toByte()), mime = "image/jpeg")

    private val context = HomeTestContext()

    private fun share(
        target: ShareHandoff.Target,
        text: String,
        images: List<ReceivedShare.Image> = emptyList(),
        at: Long,
    ) = ReceivedShare(
        handoff = ShareHandoff(
            id = UUID.randomUUID(), createdAt = Instant.ofEpochSecond(at), serverID = server.persistenceID,
            target = target, text = text,
            images = images.map { ShareHandoff.Image(id = it.id, fileName = "x.jpg", mime = it.mime) },
        ),
        images = images,
    )

    private fun home(): HomeFeature.State {
        var state = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listOf(project, other), sessions = listOf(current, target), activeSessionIDs = emptySet()))
        state = state.copy(transcriptCache = mapOf(current.id to emptyList(), target.id to emptyList()))
        state = state.openingDetail(current, cachedItems = emptyList())
        return state.copy(sessionDetail = state.sessionDetail!!.withDraft("Typed already"))
    }

    private fun makeStore(scope: CoroutineScope, state: () -> HomeFeature.State = ::home): TestStore<HomeFeature.State, HomeFeature.Action> {
        val store = context.store(scope, state) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                viewSession = { _, _, _ -> },
                uploadFile = { _, _, file, _ -> flowOf(OpenCodeUploadEvent.Finished(path = "/tmp/${file.name}")) },
            )
        }
        store.exhaustive = false
        return store
    }

    private val sessionTapped = { action: HomeFeature.Action -> action is HomeFeature.Action.SessionTapped }

    @Test
    fun shareOpensItsSessionWithTheItemsInTheComposer() = runTest {
        val store = makeStore(this)
        store.send(
            HomeFeature.Action.SharesReceived(
                listOf(share(ShareHandoff.Target.Session(id = target.id, projectID = other.id), text = "What’s wrong here?", images = listOf(screenshot), at = 100)),
            ),
        )
        store.receive(sessionTapped, "sessionTapped")
        assertEquals(target.id, store.state.sessionDetail?.session?.id)
        assertEquals("What’s wrong here?", store.state.sessionDetail?.draft)
        assertEquals(
            listOf(OpenCodeOutgoingAttachment(id = screenshot.id.toString(), data = screenshot.data, mime = "image/jpeg")),
            store.state.sessionDetail?.pendingAttachments,
        )
        assertTrue(store.withDependencies { store.state.sharedDrafts.isEmpty })
        store.finish()
    }

    @Test
    fun shareForTheOpenSessionKeepsWhatWasTyped() = runTest {
        val store = makeStore(this)
        val navigationID = store.state.sessionDetail?.navigationID
        store.send(
            HomeFeature.Action.SharesReceived(
                listOf(share(ShareHandoff.Target.Session(id = current.id, projectID = project.id), text = "https://example.com", at = 100)),
            ),
        )
        store.receive(sessionTapped, "sessionTapped")
        assertEquals(navigationID, store.state.sessionDetail?.navigationID)
        assertEquals("Typed already\n\nhttps://example.com", store.state.sessionDetail?.draft)
        store.finish()
    }

    @Test
    fun multipleSharesOpenTheNewestAndHoldTheRestForTheirSessions() = runTest {
        val store = makeStore(this)
        store.send(
            HomeFeature.Action.SharesReceived(
                listOf(
                    share(ShareHandoff.Target.Session(id = target.id, projectID = other.id), text = "first", images = listOf(screenshot), at = 100),
                    share(ShareHandoff.Target.NewSession(projectID = other.id), text = "second", at = 110),
                    share(ShareHandoff.Target.NewSession(projectID = other.id), text = "third", at = 120),
                ),
            ),
        )
        assertEquals(true, store.state.sessionDetail?.isDraft)
        assertEquals(other, store.state.sessionDetail?.draftProject)
        assertEquals("second\n\nthird", store.state.sessionDetail?.draft)
        assertEquals(listOf<SharedDraftTarget>(SharedDraftTarget.Session(target.id)), store.withDependencies { store.state.sharedDrafts.keys.toList() })

        store.send(HomeFeature.Action.SessionTapped(target.id))
        assertEquals("first", store.state.sessionDetail?.draft)
        assertEquals(listOf(screenshot.id.toString()), store.state.sessionDetail?.pendingAttachments?.map { it.id })
        assertTrue(store.withDependencies { store.state.sharedDrafts.isEmpty })
        store.finish()
    }

    @Test
    fun shareForADeletedSessionStartsANewSessionInItsProject() = runTest {
        val store = makeStore(this)
        store.send(
            HomeFeature.Action.SharesReceived(
                listOf(share(ShareHandoff.Target.Session(id = "ses_gone", projectID = other.id), text = "still useful", at = 100)),
            ),
        )
        assertEquals(true, store.state.sessionDetail?.isDraft)
        assertEquals(other, store.state.sessionDetail?.draftProject)
        assertEquals("still useful", store.state.sessionDetail?.draft)
        store.finish()
    }

    @Test
    fun publishedTargetsFollowTheDrawerWithoutSecrets() {
        var state = context.read { home() }
        state = state.copy(pinnedSessionKeys = listOf(state.sessionKey(target.id)), search = "no match")
        val targets = state.shareTargets
        assertEquals(server.persistenceID, targets.serverID)
        assertEquals("localhost:4096", targets.serverName)
        assertEquals(current.id, targets.lastViewedSessionID)
        assertEquals(listOf(target.id), targets.pinned.map { it.id })
        assertEquals("Other", targets.pinned.firstOrNull()?.projectName)
        assertEquals(listOf(current.id), targets.recents.map { it.id })
        assertEquals(ShareTargets.Project(id = project.id, name = "Work", directory = "/work"), targets.newSessionProject)
        assertEquals(listOf(project.id, other.id), targets.projects?.map { it.id })
        assertEquals("/other", targets.pinned.firstOrNull()?.directory)
        val encoded = Json.encodeToString(ShareTargets.serializer(), targets)
        assertFalse(encoded.contains(server.password))
    }

    @Test
    fun shareWithJSONFilePreservesExistingDraftAndAttachmentsWithoutAutoSending() = runTest {
        val existingAttachment = OpenCodeOutgoingAttachment(
            id = UUID(0, 9).toString(), data = byteArrayOf(0x89.toByte(), 0x50), mime = "image/png", name = "existing.png",
        )
        val jsonFile = ComposerStagedFile(
            id = UUID(0, 2),
            name = "opencode-diagnostics.txt.json",
            mime = "text/plain",
            size = 27,
            url = File("/nonexistent/ComposerFiles/${UUID(0, 2).toString().uppercase()}/opencode-diagnostics.txt.json"),
        )
        val continued = ReceivedShare(
            handoff = ShareHandoff(
                id = UUID.randomUUID(),
                createdAt = Instant.ofEpochSecond(100),
                serverID = server.persistenceID,
                target = ShareHandoff.Target.Session(id = current.id, projectID = project.id),
                text = "Check this trace",
                files = listOf(ShareHandoff.FileEntry(id = jsonFile.id, fileName = "file-0", name = jsonFile.name, mime = jsonFile.mime)),
                delivery = ShareHandoff.Delivery.openInComposer,
            ),
            images = emptyList(),
            files = listOf(jsonFile),
        )
        val store = makeStore(this) {
            val initial = home()
            initial.copy(sessionDetail = initial.sessionDetail!!.withPendingAttachments(listOf(existingAttachment)))
        }
        store.send(HomeFeature.Action.SharesReceived(listOf(continued)))
        store.receive(sessionTapped, "sessionTapped")
        val detail = store.state.sessionDetail
        assertEquals(current.id, detail?.session?.id)
        assertEquals("Typed already\n\nCheck this trace", detail?.draft)
        assertEquals(listOf(existingAttachment), detail?.pendingAttachments)
        assertEquals(listOf(ComposerFileAttachment(jsonFile)), detail?.pendingFiles)
        assertEquals(listOf(jsonFile.id), detail?.unstartedUploadIDs)
        assertEquals(1, detail?.composerFocusRequest)
        assertNull(detail?.sharedSend)
        assertNull(detail?.sendAfterUploads)
        // Composer reads the shared file on `Dispatchers.IO` (real time): wait for it in real time,
        // not on the test's virtual clock, so a loaded box can't outlast `finish`'s timeout.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { store.finish(timeout = 10.seconds) }
    }
}
