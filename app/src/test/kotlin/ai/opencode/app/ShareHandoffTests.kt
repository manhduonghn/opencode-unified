package ai.opencode.app

import ai.opencode.core.sharing.shared
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.session.isDraft
import ai.opencode.feature.session.pendingAttachments
import ai.opencode.feature.system.share.ReceivedShare
import ai.opencode.feature.system.share.ShareHandoff
import ai.opencode.feature.system.share.ShareInboxClient
import ai.opencode.server.CachedHomeSnapshot
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** The `AppFeature` part. */
class ShareHandoffTests {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()

    private val server = OpenCodeServer(url = "http://localhost:4096", username = "kit", password = "secret")
    private val project = OpenCodeProject(id = "proj", name = "Work", directory = "/work")
    private val other = OpenCodeProject(id = "proj_other", name = "Other", directory = "/other")
    private val current = OpenCodeSession(id = "ses_current", serverTitle = "Current", projectID = "proj", directory = "/work", updatedAt = Instant.ofEpochSecond(30))
    private val target = OpenCodeSession(id = "ses_target", serverTitle = "Target", projectID = "proj_other", directory = "/other", updatedAt = Instant.ofEpochSecond(20))
    private val screenshot = ReceivedShare.Image(id = UUID(0, 1), data = byteArrayOf(0xFF.toByte(), 0xD8.toByte()), mime = "image/jpeg")

    private fun share(target: ShareHandoff.Target, text: String, images: List<ReceivedShare.Image> = emptyList(), at: Long, serverID: String = server.persistenceID) =
        ReceivedShare(
            handoff = ShareHandoff(
                id = UUID.randomUUID(), createdAt = Instant.ofEpochSecond(at), serverID = serverID,
                target = target, text = text,
                images = images.map { ShareHandoff.Image(id = it.id, fileName = "x.jpg", mime = it.mime) },
            ),
            images = images,
        )

    @Test
    fun appConsumesSharesOnActivationAndRoutesThemToTheOpenServer() = runTest {
        val snapshot = OpenCodeHomeSnapshot(projects = listOf(project, other), sessions = listOf(current, target), activeSessionIDs = emptySet())
        shared.run {
            shared(ServerSharedKeys.savedServer).set(server)
            shared(ServerSharedKeys.savedServers).set(listOf(server))
            shared(ServerSharedKeys.cachedHomeSnapshots).set(mapOf(server.persistenceID to CachedHomeSnapshot(serverID = server.persistenceID, snapshot = snapshot)))
        }
        val pending = share(ShareHandoff.Target.NewSession(projectID = project.id), text = "from Photos", images = listOf(screenshot), at = 100)
        val foreign = share(ShareHandoff.Target.NewSession(projectID = null), text = "elsewhere", at = 90, serverID = "forgotten-server")
        val shares = listOf(foreign, pending)
        val effects = detachedScope(coroutineContext)
        val store = shared.appStore(shared.appState(), effects) {
            this[ShareInboxClient] = ShareInboxClient.testValue.let { base ->
                ShareInboxClient(
                    consume = { shares },
                    publishTargets = base.publishTargets,
                    publishCredentials = base.publishCredentials,
                    removeTargets = base.removeTargets,
                )
            }
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                health = {},
                homeSnapshot = { snapshot },
                homeAttention = { _, _, _ -> emptySet() },
                events = { emptyFlow() },
            )
        }
        store.exhaustive = false

        store.send(AppFeature.Action.PendingSharesRequested)
        store.receiveCase<AppFeature.Action.SharesReceived>()
        store.receive({ (it as? AppFeature.Action.Home)?.action is HomeFeature.Action.SharesReceived }, "home.sharesReceived")
        assertNotNull(store.state.shareError)
        assertTrue(store.state.pendingShares.isEmpty())
        assertEquals(true, store.state.home?.sessionDetail?.isDraft)
        assertEquals("from Photos", store.state.home?.sessionDetail?.draft)
        assertEquals(listOf(screenshot.id.toString()), store.state.home?.sessionDetail?.pendingAttachments?.map { it.id })
        store.finish()
    }
}
