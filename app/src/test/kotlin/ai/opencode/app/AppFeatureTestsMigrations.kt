package ai.opencode.app

import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.transcript.SessionSeenWatermark
import ai.opencode.feature.transcript.sessionSeenWatermarks
import ai.opencode.server.CachedHomeSnapshot
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** Each test gets its own in-memory `SharedStore`. */
class AppFeatureTestsMigrations {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()

    /** The voice orb is Wisp's alone: a saved orb style is dropped at launch. */
    @Test
    fun aSavedOrbStyleIsDropped() = runTest {
        val style = SharedKey.appStorage(RETIRED_ORB_STYLE_KEY, "")
        shared.run { shared(style).set("silkRibbons") }
        val store = shared.appStore(shared.appState(), detachedScope(coroutineContext))

        store.send(AppFeature.Action.Task)
        assertFalse(shared.store.contains(style))
        store.finish()
    }

    /**
     * The first launch of this build clears the stale seen watermarks before restoring the last
     * session; with the divider on they're current, and stay.
     */
    @Test
    fun retiringTheDividerClearsWatermarksSavedWithItOff() {
        for (dividerWasOn in listOf(false, true)) {
            val shared = AppTestShared()
            val divider = SharedKey.appStorage("experimental-new-messages-divider", false)
            val saved = OpenCodeServer(url = "https://divider.example", username = "kit", password = "secret")
            // Read up to the reply: the server's viewed time is its idle time.
            val session = OpenCodeSession(
                id = "ses_read", serverTitle = "Read", projectID = "p", directory = "/project",
                updatedAt = java.time.Instant.now(), idleMilliseconds = 2_000, viewedMilliseconds = 2_000,
            )
            val key = "${saved.persistenceID}|${session.id}"
            val stale = SessionSeenWatermark(itemID = "msg_old", viewedMilliseconds = 1_000, acknowledgedViewedMilliseconds = 2_000)

            val app = shared.run {
                shared(ServerSharedKeys.savedServer).set(saved)
                shared(ServerSharedKeys.cachedHomeSnapshots).set(
                    mapOf(
                        saved.persistenceID to CachedHomeSnapshot(
                            serverID = saved.persistenceID,
                            snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session), activeSessionIDs = emptySet()),
                        ),
                    ),
                )
                shared(SharedKeys.lastViewedSessionIDs).set(mapOf(saved.persistenceID to session.id))
                shared(SharedKeys.sessionSeenWatermarks).update { it.setting(stale, key) }
                // As an earlier build saved the switch (a write equal to a key's default saves nothing).
                shared(SharedKey.appStorage(divider.id, !dividerWasOn)).set(dividerWasOn)
                AppFeature.State.initial()
            }

            assertFalse("Retired once", shared.store.contains(divider))
            val detail = app.home?.sessionDetail
            assertEquals("divider $dividerWasOn", session.id, detail?.session?.id)
            assertEquals("divider $dividerWasOn", dividerWasOn, detail?.newMessagesUnread)
            assertEquals("divider $dividerWasOn", dividerWasOn, detail?.newMessagesBaseline == setOf("msg_old"))
        }
    }
}
