package ai.opencode.core.sound

import ai.opencode.core.R
import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.diagnostics.LabMarks
import ai.opencode.core.log.AppLog
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Whether no call, dictation or media holds the audio session. The app installs the owner from
 * `:feature:voice`; until then the system audio mode is read.
 */
interface AudioSessionIdleState {
    fun <T> withIdleState(body: (isIdle: Boolean) -> T): T

    /** Stops a borrowed sound as a holder activates, before the holder touches the session. */
    fun onHolderActivating(stop: () -> Unit)

    companion object Key : DependencyKey<AudioSessionIdleState> {
        override val liveValue: AudioSessionIdleState by lazy {
            val audio = dependency(AppContext).getSystemService(AudioManager::class.java)
            ModeIdleState { audio.mode == AudioManager.MODE_NORMAL }
        }
        override val testValue: AudioSessionIdleState get() = ModeIdleState { true }
    }
}

private class ModeIdleState(private val isIdle: () -> Boolean) : AudioSessionIdleState {
    private val lock = Any()
    override fun <T> withIdleState(body: (isIdle: Boolean) -> T): T = synchronized(lock) { body(isIdle()) }
    override fun onHolderActivating(stop: () -> Unit) {}
}

/**
 * What ends playback, from Android's process lifecycle, audio devices (route) and audio mode (a
 * call interrupts).
 */
sealed interface SoundEffectsEvent {
    data object BecameActive : SoundEffectsEvent
    data object ResignedActive : SoundEffectsEvent
    data object EnteredBackground : SoundEffectsEvent

    /** Android: a call or communication mode. */
    data class Interruption(val began: Boolean) : SoundEffectsEvent

    data class RouteChange(val ownConfiguration: Boolean = false) : SoundEffectsEvent
}

/** The observers playback registers with. */
class SoundEffectsEventCenter {
    private val lock = Any()
    private val observers = mutableListOf<(SoundEffectsEvent) -> Unit>()

    fun observe(observer: (SoundEffectsEvent) -> Unit) {
        synchronized(lock) { observers.add(observer) }
    }

    fun post(event: SoundEffectsEvent) {
        synchronized(lock) { observers.toList() }.forEach { it(event) }
    }
}

/**
 * The session and players Sound Effects go through. Everything but [load] is called under the
 * audio session owner's lock. Faked in tests.
 */
interface SoundEffectsAudio {
    /** Reads the sounds into memory. Never touches the audio session. */
    fun load()

    /** Then active: only right before a permitted play. */
    fun activate()

    /** Inactive with `.notifyOthersOnDeactivation`, so other apps' audio resumes. */
    fun deactivate()

    /** Starts [effect]. [finished] runs once it plays out, never after [stop]. */
    fun start(effect: SoundEffect, volume: Float, finished: () -> Unit): Boolean
    fun stop()
}

/**
 * Plays admitted sounds on the idle session, stops at once when it's taken, the app leaves, the
 * route changes or an interruption begins, and gives it back.
 */
class SoundEffectsPlayback(
    private val owner: AudioSessionIdleState,
    private val audio: SoundEffectsAudio,
    notifications: SoundEffectsEventCenter = SoundEffectsEventCenter(),
    /** Whether the app is active, from its events. Under the owner's lock. */
    private var appIsActive: Boolean,
    /** Runs session work off the caller's thread (synchronous in tests). */
    private val perform: (() -> Unit) -> Unit,
) : SoundEffectOutput {
    enum class StopReason(val rawValue: String) { holder("holder"), background("background"), interruption("interruption"), route("route") }

    /** The sound playing on a session this activated. Under the owner's lock. */
    private var playing: Int? = null
    private var nextID = 0

    init {
        owner.onHolderActivating { stop(StopReason.holder) }
        notifications.observe(::handle)
    }

    private fun handle(event: SoundEffectsEvent) {
        when (event) {
            SoundEffectsEvent.BecameActive -> owner.withIdleState { appIsActive = true }
            SoundEffectsEvent.ResignedActive -> owner.withIdleState { appIsActive = false }
            else -> stopReason(event)?.let(::stop)
        }
    }

    override fun prepare() {
        perform { audio.load() }
    }

    override fun play(effect: SoundEffect, volume: Float) {
        perform {
            audio.load()
            // The one eligibility check, under the lock the app's and holders' changes take: a
            // play queued before the app left or a call started finds it out here, never after
            // activating.
            val skipped: String? = owner.withIdleState { isIdle ->
                if (!appIsActive) return@withIdleState "inactive"
                if (!isIdle) return@withIdleState "session_held"
                if (playing != null) {
                    audio.stop()
                    playing = null
                }
                try {
                    audio.activate()
                } catch (error: Exception) {
                    return@withIdleState "activate_failed code=${error.javaClass.simpleName}"
                }
                nextID += 1
                val id = nextID
                val started = audio.start(effect, volume) { perform { finish(id) } }
                if (!started) {
                    deactivate()
                    return@withIdleState "start_failed"
                }
                playing = id
                null
            }
            if (skipped != null) AppLog.app.debug("sound_effect_skipped ${effect.rawValue} reason=$skipped")
        }
    }

    /** Whether a sound this started is still playing. */
    val isPlaying: Boolean get() = owner.withIdleState { playing != null }

    private fun finish(id: Int) {
        owner.withIdleState { isIdle ->
            if (playing != id) return@withIdleState
            playing = null
            // A holder that activated since owns the session now.
            if (isIdle) deactivate()
        }
    }

    /**
     * Stops the sound at once. Gives the session back unless a holder is taking it, or an
     * interruption already took it.
     */
    fun stop(reason: StopReason) {
        owner.withIdleState { isIdle ->
            if (reason == StopReason.background) appIsActive = false
            if (playing == null) return@withIdleState
            audio.stop()
            playing = null
            LabMarks.mark("sound_effect_stopped reason=${reason.rawValue}")
            if (isIdle && reason != StopReason.holder && reason != StopReason.interruption) deactivate()
        }
    }

    private fun deactivate() {
        try {
            audio.deactivate()
        } catch (error: Exception) {
            AppLog.app.error("Couldn’t release sound effects audio session (${error.javaClass.simpleName})")
        }
    }

    companion object {
        /**
         * What ends a sound early. Our own configuration change doesn't: activating for a sound
         * posts it.
         */
        fun stopReason(event: SoundEffectsEvent): StopReason? = when (event) {
            SoundEffectsEvent.EnteredBackground -> StopReason.background
            is SoundEffectsEvent.Interruption -> if (event.began) StopReason.interruption else null
            is SoundEffectsEvent.RouteChange -> if (event.ownConfiguration) null else StopReason.route
            else -> null
        }

        /**
         * The bundled sounds on the idle session, the installed owner, and the process's lifecycle,
         * devices and audio mode.
         */
        fun live(): SoundEffectOutput {
            val context = dependency(AppContext)
            val thread = HandlerThread("ai.opencode.sound-effects").apply { start() }
            val handler = Handler(thread.looper)
            val center = SoundEffectsEventCenter()
            val audioManager = context.getSystemService(AudioManager::class.java)
            val lifecycle = ProcessLifecycleOwner.get().lifecycle
            val playback = SoundEffectsPlayback(
                owner = dependency(AudioSessionIdleState),
                audio = BundledSoundEffectsAudio(context, audioManager),
                notifications = center,
                appIsActive = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            ) { handler.post(it) }
            Handler(context.mainLooper).post {
                lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onResume(owner: LifecycleOwner) = center.post(SoundEffectsEvent.BecameActive)
                    override fun onPause(owner: LifecycleOwner) = center.post(SoundEffectsEvent.ResignedActive)
                    override fun onStop(owner: LifecycleOwner) = center.post(SoundEffectsEvent.EnteredBackground)
                })
            }
            audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) =
                    center.post(SoundEffectsEvent.RouteChange())
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) =
                    center.post(SoundEffectsEvent.RouteChange())
            }, handler)
            audioManager.addOnModeChangedListener(handler::post) { mode ->
                center.post(SoundEffectsEvent.Interruption(began = mode != AudioManager.MODE_NORMAL))
            }
            return playback
        }
    }
}

/**
 * The bundled sounds, one prepared [MediaPlayer] each. Takes no audio focus and won't start unless
 * the ringer is normal. Completions come back on the playback thread.
 */
class BundledSoundEffectsAudio(
    private val context: Context,
    private val audioManager: AudioManager,
) : SoundEffectsAudio {
    private class Silenced : Exception()

    private val lock = Any()
    private val players = mutableMapOf<SoundEffect, MediaPlayer>()
    private var current: MediaPlayer? = null

    private val attributes = AudioAttributes.Builder()
        // AVAudioPlayer on .ambient follows media volume; the silent switch is separate.
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    override fun load() = synchronized(lock) {
        if (players.isNotEmpty()) return@synchronized
        for (effect in SoundEffect.entries) {
            val player = try {
                MediaPlayer.create(context, effect.resource, attributes, audioManager.generateAudioSessionId())
            } catch (error: Exception) {
                AppLog.app.error("Couldn’t load sound effect ${effect.rawValue} (${error.javaClass.simpleName})")
                continue
            }
            if (player == null) {
                AppLog.app.error("Missing sound effect ${effect.rawValue}")
                continue
            }
            players[effect] = player
        }
    }

    override fun activate() {
        if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) throw Silenced()
    }

    override fun deactivate() {}

    override fun start(effect: SoundEffect, volume: Float, finished: () -> Unit): Boolean = synchronized(lock) {
        val player = players[effect] ?: return@synchronized false
        try {
            player.setVolume(volume, volume)
            player.seekTo(0)
            player.setOnCompletionListener { done ->
                val owned = synchronized(lock) { (current === done).also { if (it) current = null } }
                if (owned) finished()
            }
            player.start()
        } catch (_: IllegalStateException) {
            return@synchronized false
        }
        current = player
        true
    }

    override fun stop() = synchronized(lock) {
        current?.let { player ->
            runCatching { player.pause(); player.seekTo(0) }
        }
        current = null
    }

    private val SoundEffect.resource: Int
        get() = when (this) {
            SoundEffect.replyDone -> R.raw.sound_effect_reply_done
            SoundEffect.needsInput -> R.raw.sound_effect_needs_input
            SoundEffect.catchUpDone -> R.raw.sound_effect_catch_up_done
            SoundEffect.error -> R.raw.sound_effect_error
            SoundEffect.send -> R.raw.sound_effect_send
        }
}
