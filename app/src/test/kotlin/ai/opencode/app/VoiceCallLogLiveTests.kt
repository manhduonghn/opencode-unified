package ai.opencode.app

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.makeDetail
import ai.opencode.feature.voice.VoiceCallLog
import ai.opencode.feature.voice.VoiceCallLogClient
import ai.opencode.feature.voice.VoiceCallLogRecorder
import ai.opencode.feature.voice.VoiceCallLogUploadClient
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.UUID

/** The `AppFeature` part: leaving Home mid-call saves the call's log. */
class VoiceCallLogLiveTests {
    @get:Rule val isolation = TestIsolationRule()
    @get:Rule val temporary = TemporaryFolder()

    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(
        id = "ses_1", serverTitle = "Live call", projectID = "p", directory = "/project",
        updatedAt = Instant.parse("0001-01-01T00:00:00Z"),
    )
    private val shared = AppTestShared()

    private fun liveFiles(directory: File): List<File> =
        File(directory, "InProgress").listFiles()?.filter { it.extension == "json" } ?: emptyList()

    @Test
    fun leavingHomeMidCallSavesTheCallLog() = runTest {
        val directory = File(temporary.root, "voice-live-${UUID.randomUUID()}")
        val recorder = VoiceCallLogRecorder(
            directory = directory, date = DateClient { Instant.now() }, upload = VoiceCallLogUploadClient(),
            watchesNetwork = false, isDeveloperMode = { false },
        )
        val callLogID = UUID.randomUUID()
        recorder.begin(callLogID, title = "Live call", engine = "gptLive")
        recorder.note(callLogID, VoiceCallLog.Note.Kind.marker, "connected")

        val app = shared.run {
            val home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session), activeSessionIDs = emptySet()))
            val (withDetail, detail) = home.makeDetail(session)
            val voice = VoiceFeature.State(server, session).copy(callLogID = callLogID)
            AppFeature.State.initial().copy(
                connection = AppFeature.State.Connection.Connected,
                home = withDetail.copy(sessionDetail = detail.copy(voice = voice)),
            )
        }
        val effects = detachedScope(coroutineContext)
        val store = shared.appStore(app, effects) { this[VoiceCallLogClient] = VoiceCallLogClient(recorder) }
        store.exhaustive = false

        store.send(AppFeature.Action.PairingCodeScanned("not a pairing code"))
        repeat(50) { yield() }
        store.skipReceivedActions()
        assertNull(store.state.home)
        recorder.waitForLiveWrites()

        assertFalse(recorder.hasOpenCalls)
        assertEquals(listOf(callLogID), recorder.list().map { it.id })
        assertTrue(liveFiles(directory).isEmpty())
        effects.skipInFlightEffects()
    }
}
