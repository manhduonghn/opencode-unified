package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.composer.ComposerPersistence
import ai.opencode.feature.composer.PersistedSessionState
import ai.opencode.feature.composer.modelpicker.ModelDisplayNames
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.session.SessionFeature
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Home's half: a relaunched session keeps its waiting model pick, unless the server no longer
 * offers it.
 */
class SessionStatePersistenceTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "https://state.example", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_state", serverTitle = "State", projectID = "proj", directory = "/tmp/state", updatedAt = Instant.EPOCH)
    private val pick = OpenCodeModelRef(id = "picked", providerID = "example")

    private fun <R> with(persistence: ComposerPersistence, block: () -> R): R =
        DependencyValues.withValues(DependencyValues.current.update { this[ComposerPersistence] = persistence }, block)

    @Test fun aRelaunchedSessionKeepsItsWaitingModelPick() = homeTest { h ->
        val persistence = ComposerPersistence.inMemory(debounce = 60.seconds)
        persistence.scheduleSessionState(server.persistenceID, session.id, PersistedSessionState.Update(pendingModel = pick, requests = null))
        val home = h.state { HomeFeature.State(server, OpenCodeHomeSnapshot(emptyList(), listOf(session), emptySet())) }
        val detail = h.state { home.makeDetail(session, cachedItems = emptyList()).second }
        val restored = h.state { with(persistence) { home.restoringPersistedPick(detail) } }
        assertEquals(pick, restored.pendingModel)
    }

    @Test fun aRestoredPickTheServerNoLongerOffersFallsBackToTheSessionsModel() = homeTest { h ->
        val sessionModel = OpenCodeModel(modelID = "session-model", providerID = "example", name = "Session")
        val modelled = session.copy(model = sessionModel.ref)
        val persistence = ComposerPersistence.inMemory(debounce = 60.seconds)
        persistence.scheduleSessionState(server.persistenceID, session.id, PersistedSessionState.Update(pendingModel = pick, requests = null))
        val home = h.state { HomeFeature.State(server, OpenCodeHomeSnapshot(emptyList(), listOf(modelled), emptySet())) }
        var detail = h.state { home.makeDetail(modelled, cachedItems = emptyList()).second }
        // Already listed without it: the pick isn't restored.
        detail = detail.copy(models = listOf(sessionModel))
        detail = h.state { with(persistence) { home.restoringPersistedPick(detail) } }
        assertNull(detail.pendingModel)

        // Restored before the list loaded, a refresh without it drops it.
        detail = detail.copy(models = emptyList(), isLoadingModels = false)
        detail = h.state { with(persistence) { home.restoringPersistedPick(detail) } }
        assertEquals(pick, detail.pendingModel)
        val store = h.sessionStore(detail) { this[ComposerPersistence] = persistence }
        store.send(SessionFeature.Action.ModelsResponse(Result.success(OpenCodeModelCatalog(listOf(sessionModel), sessionModel)))) {
            it.copy(models = listOf(sessionModel), pendingModel = null, currentModel = sessionModel.ref)
        }
        assertEquals(ModelDisplayNames(listOf(sessionModel)), store.state.modelDisplayNamesByServer.value[server.persistenceID])
        assertNull(persistence.loadSessionState(server.persistenceID, session.id))
    }
}
