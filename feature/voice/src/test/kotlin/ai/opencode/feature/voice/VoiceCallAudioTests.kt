package ai.opencode.feature.voice

import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.voice.VoiceCallAudioFeature.Action
import ai.opencode.feature.voice.VoiceCallAudioFeature.Reply
import ai.opencode.feature.voice.VoiceCallAudioFeature.State
import ai.opencode.feature.voice.VoiceFeatureTestSupport.LockIsolated
import ai.opencode.feature.voice.VoiceFeatureTestSupport.append
import ai.opencode.server.clienttools.ClientTool
import ai.opencode.server.clienttools.ClientToolCall
import ai.opencode.server.clienttools.ClientToolResult
import ai.opencode.server.clienttools.ClientToolsClient
import ai.opencode.server.clienttools.JsonRpcID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.URI
import java.util.UUID
import kotlin.math.abs

class VoiceCallAudioTests {
    @get:Rule val isolation = TestIsolationRule()

    private val connection = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val song = CallAudioTrack(id = UUID.fromString("00000000-0000-0000-0000-0000000000A1"), title = "Song", duration = 192.0)
    private val clip = CallAudioTrack(id = UUID.fromString("00000000-0000-0000-0000-0000000000A2"), title = "Clip", duration = 4.0)

    private fun play(id: Int, url: String? = "https://example.com/song.mp3", title: String? = "Song") = ClientToolCall(
        connection = connection, id = JsonRpcID.Number(id.toLong()), tool = ClientTool.voicePlayAudio,
        sessionID = "ses_agent", url = url, title = title,
    )

    private fun control(id: Int, action: String) = ClientToolCall(
        connection = connection, id = JsonRpcID.Number(id.toLong()), tool = ClientTool.voiceAudioControl, action = action,
    )

    /**
     * The player's side: what was asked of it, its events, and what `play` returns; and the
     * socket's, which takes one reply per call (`replies` holds those that went out) and none
     * once it's `gone`.
     */
    private class Player(results: List<CallAudioResult>) {
        val calls = LockIsolated(listOf<String>())
        val replies = LockIsolated(listOf<ClientToolResult>())
        val answered = LockIsolated(setOf<JsonRpcID>())
        val gone = LockIsolated(false)
        val haptics = LockIsolated(listOf<Haptic>())
        val results = LockIsolated(results)
        private val listener = LockIsolated<Channel<CallAudioEvent>?>(null)

        /** A fresh stream per listen, as the live player's. */
        fun events(): kotlinx.coroutines.flow.Flow<CallAudioEvent> {
            val channel = Channel<CallAudioEvent>(Channel.UNLIMITED)
            listener.setValue(channel)
            return channel.receiveAsFlow()
        }

        fun reply(call: ClientToolCall, result: ClientToolResult): Boolean {
            if (gone.value) return false
            val inserted = answered.withValue { (it + call.id) to (call.id !in it) }
            if (!inserted) return false
            replies.append(result)
            return true
        }

        fun client(): CallAudioClient = CallAudioClient(
            play = { url, title ->
                calls.append("play $url ${title ?: "-"}")
                results.withValue { it.drop(1) to it.first() }
            },
            pause = { calls.append("pause") },
            resume = { calls.append("resume") },
            stop = { calls.append("stop") },
            stopTrack = { id -> calls.append("stop ${id.toString().takeLast(2).uppercase()}") },
            events = { events() },
        )

        suspend fun yield(event: CallAudioEvent) {
            for (attempt in 0 until 1_000) {
                if (listener.value != null) break
                yield()
            }
            listener.value?.send(event)
        }
    }

    /** Holds a download until the test opens it, cancelled or not. */
    private class Gate {
        private val opened = CompletableDeferred<Unit>()
        suspend fun await() = withContext(NonCancellable) { opened.await() }
        fun open() {
            opened.complete(Unit)
        }
    }

    private suspend fun until(condition: () -> Boolean) {
        for (attempt in 0 until 10_000) {
            if (condition()) return
            yield()
        }
    }

    private fun makeStore(
        scope: CoroutineScope,
        player: Player,
        initialState: State = State(),
        play: (suspend (URI, String?) -> CallAudioResult)? = null,
    ): TestStore<State, Action> = TestStore(initialState, VoiceCallAudioFeature(), scope) {
        this[HapticsClient] = HapticsClient(play = { player.haptics.append(it) })
        this[ClientToolsClient] = ClientToolsClient(connect = { emptyFlow() }, reply = { call, result -> player.reply(call, result) })
        val client = player.client()
        this[CallAudioClient] = if (play == null) client else CallAudioClient(
            play = play, pause = client.pause, resume = client.resume, stop = client.stop,
            stopTrack = client.stopTrack, events = client.events,
        )
    }

    private val playing: State get() = State(track = song)

    /** Effects run here, so a test's leftover listener is cancelled rather than waited on. */
    private fun effects(scope: CoroutineScope): CoroutineScope = CoroutineScope(scope.coroutineContext + Job())

    private fun started(track: CallAudioTrack) = CallAudioResult.Started(track)

    private fun text(value: String) = ClientToolResult(listOf(ClientToolResult.Content.Text(value)))

    @Test fun playStartsAndRepliesWithTheTrack() = runTest {
        val player = Player(listOf(started(song)))
        val scope = effects(this)
        val store = makeStore(scope, player)
        val call = play(1)

        store.send(Action.PlayRequested(call)) { it.copy(loading = call) }
        store.receive(Action.Started(call, started(song), replied = true)) { it.copy(loading = null, track = song) }
        store.send(Action.CallEnded) { it.copy(track = null) }
        store.finish()
        until { player.calls.value.size == 2 }

        assertEquals(listOf("play https://example.com/song.mp3 Song", "stop"), player.calls.value)
        // The Android app hosts its tools as `android`, so the agent sees `android_voice_*`.
        assertEquals(
            listOf(text("Playing “Song” on the call (3:12). It ducks while anyone speaks; use android_voice_audio_control to pause, resume or stop it.")),
            player.replies.value,
        )
        scope.cancel()
    }

    @Test fun duckAndUnduckFollowThePlayer() = runTest {
        val player = Player(listOf(started(song)))
        val scope = effects(this)
        val store = makeStore(scope, player)
        val call = play(1)

        store.send(Action.PlayRequested(call)) { it.copy(loading = call) }
        store.receive(Action.Started(call, started(song), replied = true)) { it.copy(loading = null, track = song) }
        player.yield(CallAudioEvent.Ducked(true))
        store.receive(Action.Player(CallAudioEvent.Ducked(true))) { it.copy(isDucked = true) }
        player.yield(CallAudioEvent.Ducked(false))
        store.receive(Action.Player(CallAudioEvent.Ducked(false))) { it.copy(isDucked = false) }
        store.send(Action.CallEnded) { it.copy(track = null) }
        store.finish()
        scope.cancel()
    }

    @Test fun pauseAndResumeFromTheRowAndFromTheAgent() = runTest {
        val player = Player(emptyList())
        val scope = effects(this)
        val store = makeStore(scope, player, initialState = playing)

        store.send(Action.PauseTapped) { it.copy(isPaused = true) }
        // Already paused: nothing to do, no haptic.
        store.send(Action.PauseTapped)
        store.send(Action.ResumeTapped) { it.copy(isPaused = false) }
        store.send(Action.ControlRequested(control(2, "pause"))) { it.copy(isPaused = true) }
        store.send(Action.ControlRequested(control(3, "resume"))) { it.copy(isPaused = false) }
        store.send(Action.ControlRequested(control(4, "rewind")))
        store.finish()
        until { player.replies.value.size == 3 }

        assertEquals(listOf(Haptic.tap, Haptic.tap), player.haptics.value)
        assertEquals(listOf("pause", "resume", "pause", "resume"), player.calls.value)
        assertEquals(listOf(text("Paused."), text("Resumed."), ClientToolResult.failure(Reply.badAction)), player.replies.value)
        scope.cancel()
    }

    @Test fun stopFromTheRowClearsIt() = runTest {
        val player = Player(emptyList())
        val scope = effects(this)
        val store = makeStore(scope, player, initialState = playing)

        store.send(Action.StopTapped) { it.copy(track = null) }
        // Nothing left to control.
        store.send(Action.ControlRequested(control(2, "resume")))
        store.finish()
        until { player.replies.value.isNotEmpty() }

        assertEquals(listOf(Haptic.cancel), player.haptics.value)
        assertEquals(listOf("stop"), player.calls.value)
        assertEquals(listOf(ClientToolResult.failure(Reply.nothingPlaying)), player.replies.value)
        scope.cancel()
    }

    @Test fun stopFromTheAgent() = runTest {
        val player = Player(emptyList())
        val scope = effects(this)
        val store = makeStore(scope, player, initialState = playing)

        store.send(Action.ControlRequested(control(2, "stop"))) { it.copy(track = null) }
        store.finish()
        until { player.replies.value.isNotEmpty() }

        assertTrue(player.haptics.value.isEmpty())
        assertEquals(listOf(text("Stopped.")), player.replies.value)
        scope.cancel()
    }

    @Test fun aTrackThatFinishesClearsTheRow() = runTest {
        val player = Player(listOf(started(clip)))
        val scope = effects(this)
        val store = makeStore(scope, player)
        val call = play(1, url = "https://example.com/clip.wav", title = null)

        store.send(Action.PlayRequested(call)) { it.copy(loading = call) }
        store.receive(Action.Started(call, started(clip), replied = true)) { it.copy(loading = null, track = clip) }
        // A replaced track's end is ignored.
        player.yield(CallAudioEvent.Finished(song.id))
        store.receive(Action.Player(CallAudioEvent.Finished(song.id)))
        player.yield(CallAudioEvent.Finished(clip.id))
        store.receive(Action.Player(CallAudioEvent.Finished(clip.id))) { it.copy(track = null) }
        store.finish()

        assertEquals(listOf("play https://example.com/clip.wav -"), player.calls.value)
        scope.cancel()
    }

    @Test fun callEndStopsItAndRefusesADownloadInFlightOnce() = runTest {
        val player = Player(emptyList())
        val gate = Gate()
        val clip = clip
        val scope = effects(this)
        // The download finishes after the call ended: it stops what it started.
        val store = makeStore(scope, player, initialState = playing.copy(isPaused = true)) { _, _ ->
            gate.await()
            CallAudioResult.Started(clip)
        }
        val call = play(1)

        store.send(Action.PlayRequested(call)) { it.copy(loading = call) }
        store.send(Action.CallEnded) { it.copy(loading = null, track = null, isPaused = false) }
        until { player.replies.value.isNotEmpty() }
        gate.open()
        until { player.calls.value.size == 2 }
        store.finish()

        assertEquals(listOf("stop", "stop A2"), player.calls.value)
        // The cancelled load's own refusal found it answered.
        assertEquals(listOf(ClientToolResult.failure(Reply.callEnded)), player.replies.value)
        scope.cancel()
    }

    @Test fun aCancelledLoadThatNobodyAnsweredStillReplies() = runTest {
        val player = Player(emptyList())
        val gate = Gate()
        val scope = effects(this)
        val store = makeStore(scope, player) { _, _ ->
            gate.await()
            CallAudioResult.Failed(CallAudioFailure.Download("cancelled"))
        }
        val call = play(1)

        // Torn down with no reply of its own, as when its feature goes away.
        store.send(Action.PlayRequested(call)) { it.copy(loading = call) }
        scope.cancel()
        gate.open()
        until { player.replies.value.isNotEmpty() }

        assertEquals(listOf(ClientToolResult.failure(Reply.cancelled)), player.replies.value)
    }

    @Test fun aPlayWhoseSocketWentFirstStopsItsTrack() = runTest {
        val player = Player(listOf(started(clip)))
        player.gone.setValue(true)
        val scope = effects(this)
        val store = makeStore(scope, player, initialState = playing)
        val call = play(2, url = "https://example.com/clip.wav", title = "Clip")

        store.send(Action.PlayRequested(call)) { it.copy(loading = call) }
        // The socket refused it as it closed; the clip had replaced the song.
        store.receive(Action.Started(call, started(clip), replied = false)) { it.copy(loading = null, track = null) }
        store.finish()

        assertEquals(listOf("play https://example.com/clip.wav Clip", "stop A2"), player.calls.value)
        assertTrue(player.replies.value.isEmpty())
        scope.cancel()
    }

    @Test fun aBadURLIsRefusedWithoutDownloading() = runTest {
        val player = Player(emptyList())
        val scope = effects(this)
        val store = makeStore(scope, player)

        store.send(Action.PlayRequested(play(1, url = "http://example.com/song.mp3")))
        store.send(Action.PlayRequested(play(2, url = "not a url")))
        store.send(Action.PlayRequested(play(3, url = null)))
        store.finish()
        until { player.replies.value.size == 3 }

        assertTrue(player.calls.value.isEmpty())
        assertEquals(List(3) { ClientToolResult.failure(Reply.badURL) }, player.replies.value)
        scope.cancel()
    }

    @Test fun aFailedDownloadKeepsWhatWasPlaying() = runTest {
        val failed = CallAudioResult.Failed(CallAudioFailure.Download("HTTP 404"))
        val player = Player(listOf(failed))
        val scope = effects(this)
        val store = makeStore(scope, player, initialState = playing)
        val call = play(2, url = "https://example.com/missing.mp3")

        store.send(Action.PlayRequested(call)) { it.copy(loading = call) }
        store.receive(Action.Started(call, failed, replied = true)) { it.copy(loading = null) }
        assertEquals(song, store.state.track)
        store.send(Action.CallEnded) { it.copy(track = null) }
        store.finish()

        assertEquals(listOf(ClientToolResult.failure(CallAudioFailure.Download("HTTP 404").message)), player.replies.value)
        scope.cancel()
    }

    @Test fun aSecondPlayReplacesTheFirst() = runTest {
        val player = Player(listOf(started(song), started(clip)))
        val scope = effects(this)
        val store = makeStore(scope, player)
        val first = play(1)
        val second = play(2, url = "https://example.com/clip.wav", title = "Clip")

        store.send(Action.PlayRequested(first)) { it.copy(loading = first) }
        store.receive(Action.Started(first, started(song), replied = true)) { it.copy(loading = null, track = song) }
        store.send(Action.PauseTapped) { it.copy(isPaused = true) }
        store.send(Action.PlayRequested(second)) { it.copy(loading = second) }
        store.receive(Action.Started(second, started(clip), replied = true)) { it.copy(loading = null, track = clip, isPaused = false) }
        store.send(Action.CallEnded) { it.copy(track = null) }
        store.finish()

        assertEquals(2, player.replies.value.size)
        scope.cancel()
    }

    @Test fun aSecondPlayWhileTheFirstDownloadsRefusesTheFirstOnce() = runTest {
        val player = Player(emptyList())
        val gate = Gate()
        val song = song
        val clip = clip
        val scope = effects(this)
        val store = makeStore(scope, player) { url, _ ->
            if (!url.path.endsWith("/slow.mp3")) {
                CallAudioResult.Started(clip)
            } else {
                gate.await()
                CallAudioResult.Started(song)
            }
        }
        val first = play(1, url = "https://example.com/slow.mp3")
        val second = play(2, url = "https://example.com/clip.wav", title = "Clip")

        store.send(Action.PlayRequested(first)) { it.copy(loading = first) }
        store.send(Action.PlayRequested(second)) { it.copy(loading = second) }
        store.receive(Action.Started(second, started(clip), replied = true)) { it.copy(loading = null, track = clip) }
        // The first download lands late: it stops only its own track, never the clip.
        until { player.replies.value.size == 2 }
        gate.open()
        until { player.calls.value == listOf("stop A1") }
        store.send(Action.CallEnded) { it.copy(track = null) }
        store.finish()
        until { player.calls.value.size == 2 }

        assertEquals(listOf("stop A1", "stop"), player.calls.value)
        assertEquals(setOf<JsonRpcID>(JsonRpcID.Number(1), JsonRpcID.Number(2)), player.answered.value)
        assertTrue(player.replies.value.contains(ClientToolResult.failure(Reply.replaced)))
        assertTrue(player.replies.value.contains(text(Reply.playing(clip))))
        assertEquals(2, player.replies.value.size)
        scope.cancel()
    }

    // region Ducking curve

    @Test fun duckingRampsDownOnSpeechHoldsThenComesBack() {
        val settings = CallAudioDucker.Settings(duckLevel = 0.2, attack = 0.15, release = 0.8, hold = 0.6, userLevel = 0.35f, agentLevel = 0.2f)
        val ducker = CallAudioDucker()
        val dt = 0.025
        var time = 0.0
        fun run(seconds: Double, user: Float = 0f, assistant: Float = 0f): Double {
            var gain = ducker.gain
            val steps = Math.round(seconds / dt).toInt()
            for (step in 0 until steps) {
                time += dt
                gain = ducker.step(time, dt, user, assistant, settings)
            }
            return gain
        }

        assertEquals(1.0, run(1.0), 0.0)
        assertFalse(ducker.isDucked)
        // The user speaks: 95% of the way down within the attack, never a jump.
        val first = run(dt, user = 0.5f)
        assertTrue(ducker.isDucked)
        assertTrue(first < 1 && first > 0.5)
        assertTrue(abs(run(0.125, user = 0.5f) - 0.2) < 0.06)
        // Quiet again: it holds, then comes back over the release.
        assertTrue(abs(run(0.5) - 0.2) < 0.01)
        assertTrue(ducker.isDucked)
        run(0.15)
        assertFalse(ducker.isDucked)
        assertTrue(run(0.8) > 0.95)
        // The voice agent speaking ducks it too.
        run(0.3, assistant = 0.4f)
        assertTrue(ducker.isDucked)
        assertTrue(ducker.gain < 0.25)
    }

    @Test fun leakWindowSummarisesTheMicWhileMusicPlays() {
        val window = CallAudioLeakWindow()
        assertEquals(null, window.summary)
        for (level in listOf(0.05f, 0.1f, 0.2f, 0.6f)) window.add(user = level, assistant = 0f, ducked = level > 0.35f, agentLevel = 0.2f)
        window.add(user = 0.9f, assistant = 0.5f, ducked = true, agentLevel = 0.2f)
        assertEquals("Music: ducked 40% of window · mic while agent quiet p50 0.20 p90 0.60", window.summary)
    }

    // endregion
}
