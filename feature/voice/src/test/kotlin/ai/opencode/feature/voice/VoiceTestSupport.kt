package ai.opencode.feature.voice

import ai.opencode.server.OpenCodeClientError
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Shared voice test helpers. */

/** The JSON body of a voice handoff (the line after the first newline). */
fun handoffBody(text: String): Map<String, RealtimeJSON>? {
    val json = text.split("\n", limit = 2).lastOrNull() ?: return null
    return runCatching { Json.parseToJsonElement(json).jsonObject as JsonObject }.getOrNull()
}

/** Holds an async dependency open per key until the test resolves it. */
class Cue<Value>(keys: List<String>) {
    private val channels: Map<String, Channel<Result<Value>>> =
        keys.associateWith { Channel(Channel.UNLIMITED) }

    suspend fun wait(key: String): Value {
        val channel = channels[key] ?: throw OpenCodeClientError(message = "No answer for $key")
        return channel.receive().getOrThrow()
    }

    fun resolve(key: String, value: Value) {
        channels[key]?.trySend(Result.success(value))
    }

    fun fail(key: String, message: String) {
        channels[key]?.trySend(Result.failure(OpenCodeClientError(message = message)))
    }
}

/**
 * A fake audio session over [VoiceAudioSessionControlling]. Playback restarting is the external
 * control's `interrupted(false)` (see [FakeExternalAudioControl]). On Android playback restarting
 * is the external control's `interrupted(false)` (see [FakeExternalAudioControl]).
 */
class FakeVoiceAudioSession(outputPorts: List<Int>) : VoiceAudioSessionControlling {
    sealed interface Event {
        data class SetActive(val active: Boolean) : Event
        data object Deactivated : Event
        data class Route(val atEar: Boolean) : Event
        data object PlaybackStarted : Event
        /** The call's playback gain (recorded by [RecordingAudioControl]). */
        data class VolumeChanged(val volume: Float) : Event
    }

    private val lock = Any()
    private val recorded = mutableListOf<Event>()
    private var ports = outputPorts

    /** Runs as the session activates, on the start's thread. */
    @Volatile var onActivate: (() -> Unit)? = null

    val events: List<Event> get() = synchronized(lock) { recorded.toList() }
    fun clearEvents() = synchronized(lock) { recorded.clear() }
    fun record(event: Event) = synchronized(lock) { recorded += event; Unit }
    fun setOutputPorts(ports: List<Int>) = synchronized(lock) { this.ports = ports }

    override val mode: Int = android.media.AudioManager.MODE_IN_COMMUNICATION
    override val outputPorts: List<Int> get() = synchronized(lock) { ports }
    override val isInputAvailable: Boolean = true
    override val outputLatency: Double = 0.008
    override val ioBufferDuration: Double = 0.023
    /** Runs as the engine reads the route, while it builds the graph. */
    @Volatile var onRoute: (() -> Unit)? = null
    override val routeDescription: String get() {
        onRoute?.invoke()
        return "route in: MicrophoneBuiltIn · out: ${outputPorts.joinToString(", ")}"
    }
    override suspend fun requestRecordPermission(): Boolean = true
    override fun activate(demo: Boolean) {
        record(Event.SetActive(true))
        onActivate?.invoke()
    }
    override fun deactivate() = record(Event.Deactivated)
    override fun route(atEar: Boolean, demo: Boolean) = record(Event.Route(atEar))
    override fun observe(id: java.util.UUID, focus: (Int) -> Unit, route: () -> Unit) {}
    override fun unobserve(id: java.util.UUID) {}
}

/** The WebRTC-style external hardware owner, so engine tests run without `AudioRecord`/`AudioTrack`. */
class FakeExternalAudioControl(private val session: FakeVoiceAudioSession) : VoiceExternalAudioControl {
    override fun setMuted(muted: Boolean) {}
    override fun setPlaybackVolume(volume: Float) {}
    override fun flush() {}
    override fun interrupted(interrupted: Boolean) {
        if (!interrupted) session.record(FakeVoiceAudioSession.Event.PlaybackStarted)
    }
    override fun stop() {}
}

/** Polls [condition] until it holds or [timeoutMillis] passes. */
suspend fun pollUntil(timeoutMillis: Long = 3_000, condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    while (!condition()) {
        if (System.nanoTime() > deadline) return false
        kotlinx.coroutines.delay(5)
    }
    return true
}
