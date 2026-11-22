package ai.opencode.feature.media

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import java.util.concurrent.Executors

/**
 * File playback's OS operations, injectable without touching audio hardware. Media can't see
 * `:feature:voice`'s `AudioSessionOwner`, so it counts its own holders and holds audio focus from
 * the first to the last. A call or dictation already holding audio ([isShared]) is joined, not
 * interrupted.
 */
class MediaPlaybackAudio(
    /**
     * Starts playback audio for one holder. `shared` when a call or dictation already holds
     * audio; [onInterrupted] runs when another app takes focus (the player pauses, as
     * `AVAudioSession` interruptions pause `AVPlayer`). Throws when focus is refused.
     */
    val activate: (shared: Boolean, onInterrupted: () -> Unit) -> Holder,
    /** Releases one holder; the last one abandons focus. */
    val deactivate: (Holder) -> Unit,
    /** Whether a call or dictation already holds audio. */
    val isShared: () -> Boolean,
    /** Where activation and release run, in order. The live one is never the main thread. */
    val perform: (() -> Unit) -> Unit = { it() },
) {
    /** One viewer's claim on playback audio. */
    class Holder internal constructor(internal val onInterrupted: () -> Unit)

    companion object Key : DependencyKey<MediaPlaybackAudio> {
        override val liveValue: MediaPlaybackAudio by lazy { live(dependency(AppContext)) }

        /** Opens no audio hardware. */
        override val testValue: MediaPlaybackAudio
            get() = MediaPlaybackAudio(
                activate = { _, onInterrupted -> Holder(onInterrupted) },
                deactivate = {},
                isShared = { false },
            )

        private fun live(context: Context): MediaPlaybackAudio {
            val focus = PlaybackFocus(context.getSystemService(AudioManager::class.java))
            val queue = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ai.opencode.media-audio") }
            return MediaPlaybackAudio(
                activate = focus::activate,
                deactivate = focus::deactivate,
                isShared = focus::isShared,
                perform = { queue.execute(it) },
            )
        }
    }
}

/** The process-wide focus request behind the live [MediaPlaybackAudio]. */
private class PlaybackFocus(private val audioManager: AudioManager) {
    private val lock = Any()
    private val holders = LinkedHashSet<MediaPlaybackAudio.Holder>()
    private var request: AudioFocusRequest? = null

    fun isShared(): Boolean = audioManager.mode == AudioManager.MODE_IN_CALL || audioManager.mode == AudioManager.MODE_IN_COMMUNICATION

    fun activate(shared: Boolean, onInterrupted: () -> Unit): MediaPlaybackAudio.Holder {
        val holder = MediaPlaybackAudio.Holder(onInterrupted)
        synchronized(lock) {
            // Joins a call's or dictation's audio rather than taking focus from its microphone.
            if (!shared && request == null) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build(),
                    )
                    .setOnAudioFocusChangeListener(::focusChanged)
                    .build()
                val result = audioManager.requestAudioFocus(focusRequest)
                if (result == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
                    AppLog.app.notice { "Media playback: audio focus refused" }
                    throw IllegalStateException("Audio focus refused")
                }
                request = focusRequest
            }
            holders += holder
        }
        return holder
    }

    fun deactivate(holder: MediaPlaybackAudio.Holder) {
        synchronized(lock) {
            if (!holders.remove(holder) || holders.isNotEmpty()) return
            request?.let(audioManager::abandonAudioFocusRequest)
            request = null
        }
    }

    private fun focusChanged(change: Int) {
        if (change != AudioManager.AUDIOFOCUS_LOSS && change != AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) return
        val interrupted = synchronized(lock) { holders.toList() }
        interrupted.forEach { it.onInterrupted() }
    }
}
