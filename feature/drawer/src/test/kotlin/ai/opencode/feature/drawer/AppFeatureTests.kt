package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.CachedHomeSnapshot
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/** The one test there on Home's state alone (`persistSnapshot`, `cachedHomeSnapshot`). */
class AppFeatureTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun encodedHomeCacheDoesNotPersistServerPassword() {
        val server = OpenCodeServer(url = "https://saved.example", username = "kit", password = "unique-keychain-secret")
        val session = OpenCodeSession(id = "ses_cached", serverTitle = "Cached work", projectID = "proj_cached", directory = "/work", updatedAt = Instant.ofEpochSecond(1_000))
        val cache = DependencyValues.withValues(DependencyValues.testing()) {
            val home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session), activeSessionIDs = emptySet()))
            home.persistingSnapshot(Instant.EPOCH)
            home.cachedHomeSnapshot
        }
        assertNotNull(cache)
        val encoded = Json.encodeToString(CachedHomeSnapshot.serializer(), cache!!)
        assertFalse(encoded.contains(server.password))
        assertFalse(encoded.contains("\"password\""))
        assertEquals(cache, Json.decodeFromString(CachedHomeSnapshot.serializer(), encoded))
        assertEquals(listOf(session), cache.snapshot.sessions)
    }
}
