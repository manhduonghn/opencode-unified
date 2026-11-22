package ai.opencode.feature.voice

import ai.opencode.core.capture.ScreenCaptureShutterSound
import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * The shutter outside a call: the call's shutter clicks ([VoiceShutterSound]) louder, written to a
 * WAV once and played as a UI sound (`USAGE_ASSISTANCE_SONIFICATION`), quiet while the ringer is
 * silenced or vibrating.
 */
class ShutterSystemSound(private val context: Context) {
    private val lock = Any()
    private var pool: SoundPool? = null
    private var soundID: Int? = null
    private var loaded = false
    private var playsOnLoad = false

    fun play() {
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio != null && audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        synchronized(lock) {
            val pool = pool ?: register() ?: return
            val id = soundID ?: return
            if (loaded) pool.play(id, 1f, 1f, 1, 0, 1f) else playsOnLoad = true
        }
    }

    private fun register(): SoundPool? {
        val samples = VoiceShutterSound.raw.map { it * (peak / VoiceShutterSound.peak) }.toFloatArray()
        val file = File(context.cacheDir, "opencode-shutter.wav")
        try {
            val tmp = File(context.cacheDir, "opencode-shutter.wav.tmp")
            tmp.writeBytes(wav(samples, PCM.sampleRate))
            if (!tmp.renameTo(file)) throw IOException("rename")
        } catch (error: Exception) {
            AppLog.app.error("Couldn’t write the shutter sound: ${error::class.simpleName}")
            return null
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attributes).build()
        pool.setOnLoadCompleteListener { loadedPool, id, status ->
            synchronized(lock) {
                if (status != 0) {
                    AppLog.app.error("Couldn’t register the shutter sound: status=$status")
                    loadedPool.release(); this.pool = null; soundID = null; playsOnLoad = false
                    return@synchronized
                }
                loaded = true
                if (playsOnLoad) { playsOnLoad = false; loadedPool.play(id, 1f, 1f, 1, 0, 1f) }
            }
        }
        val id = pool.load(file.path, 1)
        if (id == 0) {
            AppLog.app.error("Couldn’t register the shutter sound: status=0")
            pool.release()
            return null
        }
        this.pool = pool
        soundID = id
        return pool
    }

    companion object {
        /** Louder than the call's cue (0.05), which sits under speech; this plays alone at the system volume. */
        const val peak: Float = 0.4f

        /**
         * Installed at the root as `:core`'s [ScreenCaptureShutterSound]: during a call the call's
         * own engine plays it ([VoiceCallCueClient.shutter]); while dictation or media holds the
         * audio session it stays quiet; else the app's own shutter.
         */
        fun live(): ScreenCaptureShutterSound {
            val sound by lazy { ShutterSystemSound(dependency(AppContext)) }
            return ScreenCaptureShutterSound {
                if (dependency(VoiceCallCueClient).shutter()) return@ScreenCaptureShutterSound
                if (!dependency(AudioSessionOwner).withIdleState { it }) return@ScreenCaptureShutterSound
                sound.play()
            }
        }

        /** Mono 16-bit PCM WAV. */
        fun wav(samples: FloatArray, sampleRate: Double): ByteArray {
            val rate = sampleRate.toInt()
            val dataBytes = samples.size * 2
            val out = ByteArrayOutputStream(44 + dataBytes)
            fun u32(value: Int) { for (i in 0 until 4) out.write((value ushr (8 * i)) and 0xFF) }
            fun u16(value: Int) { out.write(value and 0xFF); out.write((value ushr 8) and 0xFF) }
            out.write("RIFF".toByteArray(Charsets.US_ASCII)); u32(36 + dataBytes)
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII)); u32(16); u16(1); u16(1)
            u32(rate); u32(rate * 2); u16(2); u16(16)
            out.write("data".toByteArray(Charsets.US_ASCII)); u32(dataBytes)
            for (sample in samples) u16((sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt())
            return out.toByteArray()
        }
    }
}
