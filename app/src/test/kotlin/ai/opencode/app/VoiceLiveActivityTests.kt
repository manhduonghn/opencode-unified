package ai.opencode.app

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.makeDetail
import ai.opencode.feature.system.liveactivity.VoiceCallActivityAttributes
import ai.opencode.feature.system.liveactivity.VoiceLiveActivityClient
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** The `AppFeature` part: leaving Home mid-call ends its ongoing notification. */
class VoiceLiveActivityTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(
        id = "ses_voice", serverTitle = "Fix flaky upload test", projectID = "p", directory = "/Users/kit/project",
        updatedAt = Instant.parse("0001-01-01T00:00:00Z"),
    )
    private val t0: Instant = Instant.ofEpochSecond(1_000)
    private val shared = AppTestShared()

    private fun homeState(): HomeFeature.State =
        HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session), activeSessionIDs = emptySet()))

    private fun pushRecorder(pushes: Locked<List<VoiceCallActivityAttributes.ContentState?>>) =
        VoiceLiveActivityClient(update = { content, _ -> pushes.update { it + content } }, endAll = {}, commands = { emptyFlow() })

    private suspend fun finish(store: ai.opencode.core.store.TestStore<AppFeature.State, AppFeature.Action>) {
        repeat(50) { yield() }
        store.skipReceivedActions()
    }

    @Test
    fun leavingHomeMidCallEndsTheActivity() = runTest {
        val app = shared.run {
            val (home, detail) = homeState().makeDetail(session)
            val voice = VoiceFeature.State(server, session).withPhase(VoiceFeature.State.Phase.Live)
                .copy(callPresenceID = UUID.fromString("00000000-0000-0000-0000-000000000001"))
            AppFeature.State.initial().copy(
                connection = AppFeature.State.Connection.Connected,
                home = home.copy(
                    sessionDetail = detail.copy(voice = voice),
                    voiceCallActivity = VoiceCallActivityAttributes.ContentState(
                        phase = VoiceCallActivityAttributes.ContentState.Phase.Live, isMuted = false, startedAt = t0,
                    ),
                ),
            )
        }
        val pushes = Locked<List<VoiceCallActivityAttributes.ContentState?>>(emptyList())
        val effects = detachedScope(coroutineContext)
        val store = shared.appStore(app, effects) { this[VoiceLiveActivityClient] = pushRecorder(pushes) }
        store.exhaustive = false

        store.send(AppFeature.Action.PairingCodeScanned("not a pairing code"))
        finish(store)
        assertNull(store.state.home)
        assertEquals(listOf<VoiceCallActivityAttributes.ContentState?>(null), pushes.value)
        effects.skipInFlightEffects()
    }

    @Test
    fun leavingHomeWithoutACallLeavesActivitiesAlone() = runTest {
        val app = shared.run {
            AppFeature.State.initial().copy(connection = AppFeature.State.Connection.Connected, home = homeState())
        }
        val pushes = Locked<List<VoiceCallActivityAttributes.ContentState?>>(emptyList())
        val effects = detachedScope(coroutineContext)
        val store = shared.appStore(app, effects) { this[VoiceLiveActivityClient] = pushRecorder(pushes) }
        store.exhaustive = false

        store.send(AppFeature.Action.PairingCodeScanned("not a pairing code"))
        finish(store)
        assertNull(store.state.home)
        assertTrue(pushes.value.isEmpty())
        effects.skipInFlightEffects()
    }
}
