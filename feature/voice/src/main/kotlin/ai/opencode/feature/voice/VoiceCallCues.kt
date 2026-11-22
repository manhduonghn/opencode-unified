package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.shared
import ai.opencode.core.strings.localized
import ai.opencode.core.tuning.Tunable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * The call's sounds, ported from Wisp (`wisp-audio` cues). Re-render them from Wisp
 * with the iOS repo's `scripts/wisp-call-sounds/render.sh` and copy them here, rather than editing
 * them.
 */
@Serializable
@Suppress("EnumEntryName")
enum class VoiceCallCue(val rawValue: String) {
    /** A breathy rise as the call starts, over the connecting loop. */
    @SerialName("call_start") callStart("call_start"),
    /** The quiet bed while the call connects: exactly 1.5 s, zero at both ends, looped seamlessly. */
    @SerialName("connecting_loop") connectingLoop("connecting_loop"),
    /** The call connected (the loop has let go). */
    @SerialName("connect") connect("connect"),
    /** The call ended. */
    @SerialName("hang_up") hangUp("hang_up"),
    /** The agent looked at something: a session, the web. */
    @SerialName("tool_look") toolLook("tool_look"),
    /** The agent opened something. */
    @SerialName("tool_open") toolOpen("tool_open"),
    /** The agent messaged or steered another session. */
    @SerialName("tool_steer") toolSteer("tool_steer"),
    /** A shell, read or edit: whisper quiet. */
    @SerialName("tool_step") toolStep("tool_step"),
    ;

    companion object {
        /**
         * The cue for a tool the agent called, as Wisp picks it (`play_tool_cue`): by its name,
         * or for a script by what its code calls. Others play nothing.
         */
        fun tool(name: String, code: String? = null): VoiceCallCue? {
            val short = name.split('.', '/').lastOrNull { it.isNotEmpty() } ?: name
            val script = code.orEmpty()
            fun calls(names: List<String>) = names.any { script.contains(it) }
            if (short in lookTools || calls(listOf("wisp.look"))) return toolLook
            if (short in openTools || calls(listOf("wisp.mac", "wisp.computer", "tools.browser"))) return toolOpen
            if (short in steerTools || calls(listOf("wisp.sessions", "wisp.seer", "tools.seer", "tools.opencode", "tools.rooms"))) {
                return toolSteer
            }
            if (short in stepTools || listOf("shell", "read", "edit").any { script.contains(".$it(") || script.contains("[\"$it\"](") }) {
                return toolStep
            }
            return null
        }

        /** `capture_app_screenshot` plays the shutter as it captures ([VoiceShutterSound]), not Look. */
        private val lookTools = setOf("read_session", "search_sessions", "websearch", "webfetch")
        private val openTools = setOf("open_session", "ios_voice_open_session", "create_session", "new_session_draft")
        private val steerTools = setOf(
            "send_message", "steer_queued", "interrupt_session", "wait_for_session", "pin_session", "unpin_session",
            "ios_voice_pin_session", "ios_voice_watch_session", "subagent", "task",
        )
        private val stepTools = setOf("shell", "bash", "read", "edit", "write", "grep", "glob", "apply_patch")
    }
}

/** Wisp's sound packs (Settings › Voice › Call Sounds). Breath Ping is Wisp's default. */
@Serializable
@Suppress("EnumEntryName")
enum class VoiceCallSoundPack(val rawValue: String) {
    @SerialName("breath_ping") breathPing("breath_ping"),
    @SerialName("crystal_glass") crystalGlass("crystal_glass"),
    @SerialName("warm_analog") warmAnalog("warm_analog"),
    @SerialName("aurora_pulse") auroraPulse("aurora_pulse"),
    @SerialName("velvet_bell") velvetBell("velvet_bell"),
    @SerialName("silent") silent("silent"),
    ;

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            breathPing -> localized(R.string.voice_sound_pack_breath_ping)
            crystalGlass -> localized(R.string.voice_sound_pack_crystal_glass)
            warmAnalog -> localized(R.string.voice_sound_pack_warm_analog)
            auroraPulse -> localized(R.string.voice_sound_pack_aurora_pulse)
            velvetBell -> localized(R.string.voice_sound_pack_velvet_bell)
            silent -> localized(R.string.voice_sound_pack_silent)
        }

    /**
     * The pack's root note (Wisp's `PackSpec.root_hz`): connect strikes it, and the hang-up
     * settles an octave under it. Haptics take their sharpness from it.
     */
    val rootHz: Float
        get() = when (this) {
            breathPing, silent -> 392f
            crystalGlass -> 440f
            warmAnalog -> 349.23f
            auroraPulse, velvetBell -> 329.63f
        }

    companion object {
        val default: VoiceCallSoundPack = breathPing
    }
}

/** Tuned on the phone (Settings → Developer → Tuning). */
object CallSoundTunables {
    /** Added to every cue's level, which matches Wisp's at 0 dB. */
    val level = Tunable("callSound.level", "Call sounds level (dB)", default = 0.0, range = -20.0..6.0, step = 0.5)
}

/** The bundled cues, decoded once each to 24 kHz mono Float32. */
object VoiceCallSoundBank {
    /** The pack chosen in Settings. */
    val pack: VoiceCallSoundPack get() = shared(VoiceSharedKeys.callSoundPack).value

    private val cache = ConcurrentHashMap<String, FloatArray>()
    private const val resourceDirectory = "/ai/opencode/feature/voice/sounds/"

    fun resourceName(cue: VoiceCallCue, pack: VoiceCallSoundPack): String = "call_${pack.rawValue}_${cue.rawValue}"

    /** The cue's samples at Wisp's level plus [CallSoundTunables.level]; none for Silent. */
    fun samples(cue: VoiceCallCue, pack: VoiceCallSoundPack = this.pack): FloatArray {
        val raw = rawSamples(cue, pack)
        val db = CallSoundTunables.level.value
        if (db == 0.0) return raw
        val gain = 10.0.pow(db / 20).toFloat()
        return FloatArray(raw.size) { (raw[it] * gain).coerceIn(-1f, 1f) }
    }

    /** The bundled samples exactly as Wisp renders them. */
    fun rawSamples(cue: VoiceCallCue, pack: VoiceCallSoundPack): FloatArray {
        if (pack == VoiceCallSoundPack.silent) return FloatArray(0)
        val name = resourceName(cue, pack)
        cache[name]?.let { return it }
        val decoded = decode(name) ?: FloatArray(0)
        if (decoded.isEmpty()) AppLog.voice.error("Couldn’t load call cue $name")
        cache[name] = decoded
        return decoded
    }

    /** The scanner's cues: connect and hang-up sound; its turnarounds don't (the connecting loop plays under them). */
    fun samples(cue: DotScopeSimulation.Cue): FloatArray = when (cue) {
        DotScopeSimulation.Cue.connected -> samples(VoiceCallCue.connect)
        DotScopeSimulation.Cue.ended -> samples(VoiceCallCue.hangUp)
        DotScopeSimulation.Cue.sweepRight, DotScopeSimulation.Cue.sweepLeft -> FloatArray(0)
    }

    private fun decode(name: String): FloatArray? = runCatching {
        VoiceCallSoundBank::class.java.getResourceAsStream("$resourceDirectory$name.caf")?.use { CAFIMA4.decode(it) }
    }.getOrNull()

    /** How long the hang-up tail plays before it's stopped: the whole cue plus a margin for output latency. */
    fun endTailWait(samples: FloatArray): Double = samples.size / PCM.sampleRate + 0.08

    fun wavData(raw: FloatArray): ByteArray {
        val dataSize = raw.size * 2
        val rate = PCM.sampleRate.toInt()
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVE".toByteArray()); put("fmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataSize)
            for (s in raw) putShort((s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
        }.array()
    }
}

/**
 * Decodes the bundled cues: a Core Audio Format file holding Apple IMA4 (34-byte packets of 64
 * frames, mono), trimmed to the packet table's valid frames, as AVAudioFile reads it into Float32
 * (each Int16 sample / 32 768). Android has no CAF or IMA4 decoder.
 */
internal object CAFIMA4 {
    private const val bytesPerPacket = 34
    private const val framesPerPacket = 64
    private val indexTable = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)
    private val stepTable = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97,
        107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428,
        4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350,
        22385, 24623, 27086, 29794, 32767,
    )

    fun decode(stream: InputStream): FloatArray? {
        val input = DataInputStream(stream.buffered())
        val magic = ByteArray(4).also { input.readFully(it) }
        if (String(magic, Charsets.US_ASCII) != "caff") return null
        input.readInt() // version and flags
        var data: ByteArray? = null
        var validFrames: Long? = null
        var primingFrames = 0
        var sampleRate = 0.0
        var format = ""
        var channels = 0
        while (data == null) {
            val type = ByteArray(4)
            try { input.readFully(type) } catch (_: EOFException) { break }
            val size = input.readLong()
            when (String(type, Charsets.US_ASCII)) {
                "desc" -> {
                    sampleRate = input.readDouble()
                    format = String(ByteArray(4).also { input.readFully(it) }, Charsets.US_ASCII)
                    input.readInt() // format flags
                    if (input.readInt() != bytesPerPacket || input.readInt() != framesPerPacket) return null
                    channels = input.readInt()
                    input.readInt() // bits per channel
                    input.skipFully(size - 32)
                }
                "pakt" -> {
                    input.readLong() // packets
                    validFrames = input.readLong()
                    primingFrames = input.readInt()
                    input.readInt() // remainder frames
                    input.skipFully(size - 24)
                }
                "data" -> {
                    input.readInt() // edit count
                    data = if (size < 0) input.readBytes() else ByteArray((size - 4).toInt()).also { input.readFully(it) }
                }
                else -> input.skipFully(size)
            }
        }
        val bytes = data ?: return null
        if (format != "ima4" || channels != 1 || sampleRate != PCM.sampleRate) return null
        val packets = bytes.size / bytesPerPacket
        val decoded = FloatArray(packets * framesPerPacket)
        var out = 0
        // Core Audio's decoder carries its state across packets: a header only resets it when its
        // step index differs or its 9-bit predictor is more than 127 off (as ffmpeg's
        // `adpcm_ima_qt` does). Resetting on every header leaves the quantised predictor's
        // offset in quiet tails, so cues wouldn't end at rest.
        var predictor = 0
        var index = -1
        for (packet in 0 until packets) {
            val base = packet * bytesPerPacket
            val header = ((bytes[base].toInt() and 0xFF) shl 8) or (bytes[base + 1].toInt() and 0xFF)
            val headerPredictor = (header and 0xFF80).toShort().toInt()
            val headerIndex = (header and 0x7F).coerceAtMost(88)
            if (headerIndex != index || abs(headerPredictor - predictor) > 0x7F) {
                predictor = headerPredictor
                index = headerIndex
            }
            for (offset in 2 until bytesPerPacket) {
                val byte = bytes[base + offset].toInt() and 0xFF
                for (nibble in intArrayOf(byte and 0x0F, byte ushr 4)) {
                    val step = stepTable[index]
                    var diff = step shr 3
                    if (nibble and 4 != 0) diff += step
                    if (nibble and 2 != 0) diff += step shr 1
                    if (nibble and 1 != 0) diff += step shr 2
                    predictor = (if (nibble and 8 != 0) predictor - diff else predictor + diff).coerceIn(-32_768, 32_767)
                    index = (index + indexTable[nibble]).coerceIn(0, 88)
                    decoded[out++] = predictor / 32_768f
                }
            }
        }
        val start = primingFrames.coerceIn(0, decoded.size)
        val end = validFrames?.let { (start + it).toInt().coerceAtMost(decoded.size) } ?: decoded.size
        return decoded.copyOfRange(start, end)
    }

    private fun DataInputStream.skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) { read().takeIf { it >= 0 } ?: throw EOFException(); left -= 1 } else left -= skipped
        }
    }
}

/**
 * Wisp's spacing for tool cues: none within 450 ms of the last, and a step (shell, read, edit)
 * none within 1.2 s, so a burst of tools is one sound.
 */
data class VoiceToolCueThrottle(var last: Double? = null) {
    fun admit(cue: VoiceCallCue, at: Double): Boolean {
        last?.let {
            val since = at - it
            if (since < gap || (cue == VoiceCallCue.toolStep && since < stepGap)) return false
        }
        last = at
        return true
    }

    companion object {
        const val gap = 0.45
        const val stepGap = 1.2
    }
}

/** Plays a tool cue on the live call when the agent calls a tool. */
class VoiceCallCueClient(
    val toolCalled: (name: String, code: String?) -> Unit = { _, _ -> },
    /**
     * The app captured its own screen: whether a call owns the audio, in which case the shutter
     * played (or stayed quiet) on the call's own engine.
     */
    val shutter: () -> Boolean = { false },
) {
    companion object Key : DependencyKey<VoiceCallCueClient> {
        override val liveValue: VoiceCallCueClient by lazy {
            VoiceCallCueClient(
                toolCalled = { name, code -> VoiceCallSounds.shared.toolCalled(name, code) },
                shutter = { VoiceCallSounds.shared.shutter() },
            )
        }
        override val testValue: VoiceCallCueClient get() = VoiceCallCueClient()
    }
}

/**
 * The call's screenshot shutter: two soft clicks, the blades opening and closing, at a tool cue's
 * level. Synthesized rather than bundled (Wisp has no shutter); it plays on the call's engine so
 * echo cancellation keeps it out of the microphone.
 */
object VoiceShutterSound {
    const val peak: Float = 0.05f
    const val length = 0.14

    /** Each click's start (s) and relative strength. */
    val clicks: List<Pair<Double, Float>> = listOf(0.004 to 1f, 0.068 to 0.7f)

    val raw: FloatArray by lazy { render() }

    /** The shutter at [CallSoundTunables.level]; none for Silent. */
    fun samples(pack: VoiceCallSoundPack = VoiceCallSoundBank.pack): FloatArray {
        if (pack == VoiceCallSoundPack.silent) return FloatArray(0)
        val db = CallSoundTunables.level.value
        if (db == 0.0) return raw
        val gain = 10.0.pow(db / 20).toFloat()
        return FloatArray(raw.size) { (raw[it] * gain).coerceIn(-1f, 1f) }
    }

    private fun render(): FloatArray {
        val rate = PCM.sampleRate
        val out = FloatArray((length * rate).toInt())
        // Int arithmetic wraps the same way.
        var seed = 0x5EED_1108
        fun noise(): Float {
            seed = seed * 1_664_525 + 1_013_904_223
            return (seed ushr 8).toFloat() / (1 shl 23).toFloat() - 1
        }
        for ((at, gain) in clicks) {
            val start = (at * rate).toInt()
            var previousIn = 0f
            var previousOut = 0f
            for (n in 0 until minOf((0.03 * rate).toInt(), out.size - start)) {
                val t = n / rate
                // A 0.4 ms rise so it doesn't pop, then a 4 ms decay.
                val envelope = ((1 - exp(-t / 0.0004)) * exp(-t / 0.004)).toFloat()
                val x = noise()
                // One-pole high-pass: the click's air, without a thump.
                val high = x - previousIn + 0.92f * previousOut
                previousIn = x
                previousOut = high
                val body = (sin(2 * PI * 1_900 * t) * exp(-t / 0.006)).toFloat()
                out[start + n] += gain * envelope * (0.7f * high + 0.5f * body)
            }
        }
        val loudest = out.maxOfOrNull { abs(it) } ?: 1f
        return FloatArray(out.size) { out[it] / loudest * peak }
    }
}
