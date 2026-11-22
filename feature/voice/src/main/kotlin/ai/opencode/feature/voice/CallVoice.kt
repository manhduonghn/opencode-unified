package ai.opencode.feature.voice

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.strings.localized
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeTransport
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RequestIdempotency
import ai.opencode.server.retry.TransportFailure
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * A built-in voice a call speaks with. GPT-Live offers every OpenAI case;
 * Realtime offers only the ten it shares; Grok Voice offers xAI's own. All
 * fix the voice when the call starts (Realtime once it has spoken), so a
 * change applies to the next call.
 *
 * Sources: openai-node `LiveAPI.BuiltInVoice` (`resources/live/live.ts`) and
 * `RealtimeAudioConfigOutput.voice` (`resources/realtime/realtime.ts`); OpenAI's
 * Managing GPT-Live sessions and Realtime conversations guides; xAI's Text to
 * Speech voice table (docs.x.ai, the same roster as Speech to Speech).
 */
@Suppress("EnumEntryName")
@Serializable
enum class CallVoice {
    alloy, ash, ballad, beacon, bossa, cedar, cinder, coral, delta, echo, gleam,
    marin, meridian, quartz, ripple, sage, shimmer, stone, tempo, verse, vesper, willow,
    altair, ara, atlas, aurora, carina, castor, celeste, cosmo, eve, helios, helix, iris, kepler,
    leo, liora, lumen, luna, lux, naksh, orion, perseus, rex, rigel, sal, sirius, ursa, zagan, zenith;

    val rawValue: String get() = name
    val id: CallVoice get() = this

    val title: String get() = name.replaceFirstChar(Char::uppercaseChar)

    /** How the regional voices speak; null for the rest. */
    val accent: String?
        get() = when (this) {
            quartz, ripple, rigel -> localized(R.string.voice_accent_australian_english)
            vesper, eve, leo -> localized(R.string.voice_accent_british_english)
            willow, stone -> localized(R.string.voice_accent_irish_english)
            gleam, meridian -> localized(R.string.voice_accent_north_american_english)
            bossa, tempo -> localized(R.string.voice_accent_brazilian_portuguese)
            beacon -> localized(R.string.voice_accent_filipino_english)
            delta, cinder -> localized(R.string.voice_accent_southern_us_english)
            naksh -> localized(R.string.voice_accent_indian_english)
            else -> null
        }

    val provider: VoiceProvider get() = if (this in grokVoices) VoiceProvider.xAI else VoiceProvider.openAI

    fun isSupported(engine: VoiceEngine): Boolean = when (engine) {
        VoiceEngine.gptLive -> provider == VoiceProvider.openAI
        VoiceEngine.realtime -> this in realtimeVoices
        VoiceEngine.grok -> provider == VoiceProvider.xAI
    }

    /**
     * The voice a call on `engine` uses for this saved choice: the choice
     * itself, or the engine's default when it doesn't offer it.
     */
    fun resolved(engine: VoiceEngine): CallVoice = if (isSupported(engine)) this else fallback(engine)

    /** Short fixed sample spoken when previewing a voice in Settings › Voice. */
    val previewPrompt: String get() = "Hi, I’m $title. Ready when you are."

    companion object {
        val default = marin

        /** xAI's default voice. */
        val grokDefault = eve

        val allCases: List<CallVoice> get() = entries

        fun supported(engine: VoiceEngine): List<CallVoice> = entries.filter { it.isSupported(engine) }

        fun fallback(engine: VoiceEngine): CallVoice = if (engine == VoiceEngine.grok) grokDefault else default

        fun ofRawValue(rawValue: String): CallVoice? = entries.firstOrNull { it.name == rawValue }

        private val realtimeVoices = setOf(alloy, ash, ballad, coral, echo, sage, shimmer, verse, marin, cedar)

        private val grokVoices = setOf(
            altair, ara, atlas, aurora, carina, castor, celeste, cosmo, eve, helios, helix, iris, kepler,
            leo, liora, lumen, luna, lux, naksh, orion, perseus, rex, rigel, sal, sirius, ursa, zagan, zenith,
        )
    }
}

sealed class VoicePreviewError : Exception("Couldn’t play preview") {
    data object MissingAPIKey : VoicePreviewError()

    data object Failed : VoicePreviewError()

    val errorDescription: String get() = localized(R.string.voice_preview_error)

    override fun fillInStackTrace(): Throwable = this
}

/**
 * Builds the TTS synthesis request for each voice provider:
 * - OpenAI: `POST https://api.openai.com/v1/audio/speech` (`gpt-4o-mini-tts`, MP3)
 * - xAI: `POST https://api.x.ai/v1/tts` (`voice_id`, `language: "en"`, MP3)
 */
object VoicePreviewRequest {
    const val openAISpeechURL = "https://api.openai.com/v1/audio/speech"
    const val xaiTTSURL = "https://api.x.ai/v1/tts"
    const val openAITTSModel = "gpt-4o-mini-tts"

    /** Applied by the client that sends it. */
    const val timeoutSeconds = 15L

    @Serializable
    @Suppress("PropertyName")
    data class OpenAIPayload(
        val model: String = openAITTSModel,
        val voice: String,
        val input: String,
        val response_format: String = "mp3",
    )

    @Serializable
    @Suppress("PropertyName")
    data class XAIPayload(
        val text: String,
        val voice_id: String,
        val language: String = "en",
    )

    private val encoder = kotlinx.serialization.json.Json { encodeDefaults = true }

    fun make(voice: CallVoice, provider: VoiceProvider, apiKey: String): Request {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) throw VoicePreviewError.MissingAPIKey
        val url: String
        val body: String
        when (provider) {
            VoiceProvider.openAI -> {
                url = openAISpeechURL
                body = encoder.encodeToString(OpenAIPayload.serializer(), OpenAIPayload(voice = voice.rawValue, input = voice.previewPrompt))
            }
            VoiceProvider.xAI -> {
                url = xaiTTSURL
                body = encoder.encodeToString(XAIPayload.serializer(), XAIPayload(text = voice.previewPrompt, voice_id = voice.rawValue))
            }
        }
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $trimmedKey")
            .header("Content-Type", "application/json")
            .post(body.toByteArray().toRequestBody(voiceJsonMediaType))
            .build()
    }
}

/** On-disk cache for synthesized voice preview clips, keyed by provider and voice. */
class VoicePreviewCache(val directory: File = defaultDirectory()) {
    fun fileURL(voice: CallVoice, provider: VoiceProvider): File = File(directory, "${provider.rawValue}-${voice.rawValue}.mp3")

    fun load(voice: CallVoice, provider: VoiceProvider): ByteArray? {
        val data = try { fileURL(voice, provider).readBytes() } catch (_: Exception) { return null }
        return data.takeIf { it.isNotEmpty() }
    }

    fun save(data: ByteArray, voice: CallVoice, provider: VoiceProvider) {
        if (data.isEmpty()) return
        val url = fileURL(voice, provider)
        try {
            directory.mkdirs()
            // Atomic: a temporary file renamed over the clip.
            val temporary = File(directory, url.name + ".tmp")
            temporary.writeBytes(data)
            if (!temporary.renameTo(url)) temporary.delete()
        } catch (_: Exception) {
        }
    }

    companion object {
        /** `Caches/OpenCode/voice-previews` (Android: `cacheDir/OpenCode/voice-previews`). */
        fun defaultDirectory(): File = File(File(dependency(AppContext).cacheDir, "OpenCode"), "voice-previews")
    }
}

/**
 * Plays a short fixed voice preview sample, caching the synthesized audio clip
 * per provider and voice on disk and ensuring at most one preview plays at a time.
 */
class VoicePreviewClient(
    /** Throws [VoicePreviewError] (or cancellation). */
    val play: suspend (voice: CallVoice, provider: VoiceProvider, apiKey: String) -> Unit,
    val stop: suspend () -> Unit,
) {
    companion object Key : DependencyKey<VoicePreviewClient> {
        override val liveValue: VoicePreviewClient by lazy { makeLive() }

        override val testValue: VoicePreviewClient = VoicePreviewClient(
            play = { _, _, _ -> },
            stop = {},
        )

        fun makeLive(
            cache: VoicePreviewCache = VoicePreviewCache(),
            send: suspend (Request) -> VoiceHttpResponse = { request ->
                dependency(OpenCodeTransport).request.withVoiceTimeout(VoicePreviewRequest.timeoutSeconds).voiceSend(request)
            },
            playAudio: (suspend (ByteArray) -> Unit)? = null,
        ): VoicePreviewClient {
            val defaultPlayAudio: suspend (ByteArray) -> Unit = { data -> VoicePreviewAudioPlayer.play(data) }
            val defaultStopAudio: suspend () -> Unit = { VoicePreviewAudioPlayer.stop() }
            val noOpStopAudio: suspend () -> Unit = {}
            val coordinator = VoicePreviewCoordinator(
                cache = cache,
                send = send,
                playAudio = playAudio ?: defaultPlayAudio,
                stopAudio = if (playAudio == null) defaultStopAudio else noOpStopAudio,
            )
            return VoicePreviewClient(
                play = { voice, provider, apiKey -> coordinator.play(voice, provider, apiKey) },
                stop = { coordinator.stop() },
            )
        }

        /** Fetches the TTS clip using `NetworkRetry.run(idempotency: .unsafe)`. */
        suspend fun synthesize(
            voice: CallVoice,
            provider: VoiceProvider,
            apiKey: String,
            send: suspend (Request) -> VoiceHttpResponse,
        ): ByteArray {
            val request = VoicePreviewRequest.make(voice, provider, apiKey)
            val label = if (provider == VoiceProvider.xAI) "POST https://api.x.ai/v1/tts" else "POST https://api.openai.com/v1/audio/speech"
            val failed = VoicePreviewError.Failed.errorDescription
            try {
                return NetworkRetry.run(idempotency = RequestIdempotency.Unsafe, label = label) { _ ->
                    val response = try {
                        send(request)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        throw OpenCodeClientError(failed, transport = TransportFailure.of(error))
                    }
                    if (response.statusCode !in 200..<300 || response.body.isEmpty()) {
                        throw OpenCodeClientError(
                            failed,
                            statusCode = response.statusCode,
                            retryAfter = OpenCodeClientError.retryAfter(response.header("Retry-After")),
                        )
                    }
                    response.body
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                throw VoicePreviewError.Failed
            }
        }
    }
}

class VoicePreviewCoordinator(
    private val cache: VoicePreviewCache,
    private val send: suspend (Request) -> VoiceHttpResponse,
    private val playAudio: suspend (ByteArray) -> Unit,
    private val stopAudio: suspend () -> Unit,
) {
    private val lock = Any()
    private var activeID: UUID? = null

    private fun isActive(id: UUID): Boolean = synchronized(lock) { activeID == id }

    suspend fun play(voice: CallVoice, provider: VoiceProvider, apiKey: String) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) throw VoicePreviewError.MissingAPIKey
        val id = UUID.randomUUID()
        synchronized(lock) { activeID = id }
        stopAudio()

        try {
            val clip: ByteArray
            val cached = cache.load(voice, provider)
            if (cached != null) {
                clip = cached
            } else {
                val fetched = fetchClip(voice, provider, trimmedKey)
                currentCoroutineContext().ensureActive()
                if (!isActive(id)) throw CancellationException()
                cache.save(fetched, voice, provider)
                clip = fetched
            }
            currentCoroutineContext().ensureActive()
            if (!isActive(id)) throw CancellationException()
            try {
                playAudio(clip)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (!currentCoroutineContext().isActive || !isActive(id)) throw CancellationException()
                throw VoicePreviewError.Failed
            }
            if (!isActive(id)) throw CancellationException()
            synchronized(lock) { activeID = null }
        } catch (error: CancellationException) {
            // A cancelled preview stops its own audio, never a newer one.
            if (!currentCoroutineContext().isActive) withContext(NonCancellable) { cancel(id) }
            throw error
        }
    }

    suspend fun stop() {
        synchronized(lock) { activeID = null }
        stopAudio()
    }

    private suspend fun cancel(id: UUID) {
        val wasActive = synchronized(lock) {
            if (activeID != id) return@synchronized false
            activeID = null
            true
        }
        if (wasActive) stopAudio()
    }

    // VoiceLabDemo is not ported.
    private suspend fun fetchClip(voice: CallVoice, provider: VoiceProvider, apiKey: String): ByteArray =
        VoicePreviewClient.synthesize(voice, provider, apiKey, send)
}

/** One process-wide player. */
private object VoicePreviewAudioPlayer {
    private var player: MediaPlayer? = null
    private var continuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null

    suspend fun play(data: ByteArray) = withContext(Dispatchers.Main) {
        stopNow()
        if (VoiceLabEnvironment.string("OPENCODE_LAB_VOICE_MUTE_OUTPUT") == "1") {
            delay(1_400)
            return@withContext
        }
        val audioPlayer = MediaPlayer()
        try {
            audioPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            audioPlayer.setDataSource(ByteArrayMediaSource(data))
            withContext(Dispatchers.IO) { audioPlayer.prepare() }
        } catch (_: Exception) {
            audioPlayer.release()
            throw VoicePreviewError.Failed
        }
        player = audioPlayer
        suspendCancellableCoroutine { cont ->
            continuation = cont
            audioPlayer.setOnCompletionListener { finished(it, successfully = true) }
            audioPlayer.setOnErrorListener { mp, _, _ ->
                finished(mp, successfully = false)
                true
            }
            cont.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { stopNow() } }
            try {
                audioPlayer.start()
            } catch (_: Exception) {
                finished(audioPlayer, successfully = false)
            }
        }
    }

    suspend fun stop() = withContext(Dispatchers.Main) { stopNow() }

    private fun stopNow() {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            it.setOnCompletionListener(null)
            it.setOnErrorListener(null)
            it.release()
        }
        player = null
        continuation?.let {
            continuation = null
            if (it.isActive) it.resumeWithException(CancellationException())
        }
    }

    private fun finished(mediaPlayer: MediaPlayer, successfully: Boolean) {
        if (player !== mediaPlayer) return
        player = null
        mediaPlayer.release()
        continuation?.let {
            continuation = null
            if (!it.isActive) return
            if (successfully) it.resume(Unit) else it.resumeWithException(VoicePreviewError.Failed)
        }
    }

    private class ByteArrayMediaSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val count = min(size.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, count)
            return count
        }

        override fun getSize(): Long = data.size.toLong()
        override fun close() {}
    }

    /** Generates a short syllable-shaped PCM16 WAV clip for lab demos. */
    @Suppress("unused")
    fun makeDemoWAV(duration: Double, frequency: Double): ByteArray {
        val sampleRate = PCM.sampleRate
        val frameCount = maxOf(1, (duration * sampleRate).toInt())
        val samples = FloatArray(frameCount)
        for (i in 0 until frameCount) {
            val t = i / sampleRate
            val envelope = sin(PI * min(1.0, t / 0.08)) * sin(PI * min(1.0, (duration - t) / 0.12))
            val syllable = 0.35 + 0.65 * abs(sin(t * PI * 4.2))
            samples[i] = (sin(2 * PI * frequency * t) * envelope * syllable * 0.25).toFloat()
        }
        return VoiceCallSoundBank.wavData(samples)
    }
}
/** Only read in lab mode. */
val VoiceLabDemo.previewDemoKey: String get() = "lab-voice-preview-key"
