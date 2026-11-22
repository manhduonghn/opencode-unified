package ai.opencode.feature.drawer.icons

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.HomeSnapshotDate
import ai.opencode.server.CachedHomeSnapshot
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeProjectIcon
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * Other modules:
 *
 * Rendering and loader tests that decode images (`rasterizesSVG…`, `composerWrapperRenders…`,
 * `embeddedImagesStayLocalAndBounded`, `serverDataURLsCacheLocally…`, `loaderCachesOnDisk…`)
 * are in `ProjectIconRenderTests.kt`: they need real `ImageDecoder`/`Bitmap`/`Canvas` pixels
 * (Robolectric, native graphics).
 */
class ProjectIconTests {
    @get:Rule val isolation = TestIsolationRule()
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun homeFeatureAppliesProjectUpdatedEvent() = runTest {
        val server = OpenCodeServer(url = "https://box.example.com:4096", username = "opencode", password = "secret")
        val initialProject = OpenCodeProject(id = "prj_1", name = "voice-control", directory = "/code/voice-control")
        val session = OpenCodeSession(
            id = "ses_1",
            serverTitle = "Fix audio route",
            projectID = "prj_1",
            directory = "/code/voice-control",
            updatedAt = Instant.ofEpochSecond(1_700_000_000),
        )
        val state = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listOf(initialProject), sessions = listOf(session), activeSessionIDs = emptySet()))
        val store = TestStore(state, HomeFeature(), this) {
            this[HomeSnapshotDate] = DateClient.constant(Instant.EPOCH)
        }

        val updatedProject = OpenCodeProject(
            id = "prj_1",
            name = "hex",
            directory = "/code/voice-control",
            repository = "hex",
            icon = OpenCodeProjectIcon(url = "data:image/svg+xml;base64,PHN2Zy8+"),
        )
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ProjectUpdated(updatedProject))) {
            it.copy(
                projects = listOf(updatedProject),
                sessionDetail = it.sessionDetail?.copy(
                    projects = it.sessionDetail!!.projects.map { project -> if (project.id == updatedProject.id) updatedProject else project },
                    draftProject = if (it.sessionDetail!!.draftProject?.id == updatedProject.id) updatedProject else it.sessionDetail!!.draftProject,
                ),
            )
        }
        assertEquals(listOf(updatedProject), store.state.sessionDetail?.projects)
        assertEquals(updatedProject, store.state.sessionDetail?.draftProject)
        val cached = store.withDependencies { shared(ServerSharedKeys.cachedHomeSnapshots).value[server.persistenceID] }
        assertEquals(
            CachedHomeSnapshot(
                serverID = server.persistenceID,
                snapshot = OpenCodeHomeSnapshot(projects = listOf(updatedProject), sessions = listOf(session), activeSessionIDs = emptySet()),
                capturedAt = Instant.EPOCH,
            ),
            cached,
        )
        store.finish()
    }

    private companion object {
        val cachedSVG = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(
            """<svg viewBox="0 0 64 64"><rect width="64" height="64" fill="#111111"/><rect x="16" y="16" width="32" height="32" fill="white"/></svg>""".toByteArray(),
        )
    }
}
