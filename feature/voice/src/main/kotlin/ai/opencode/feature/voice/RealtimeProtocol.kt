package ai.opencode.feature.voice

import ai.opencode.server.OpenCodeServer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant
import java.util.Base64

// OpenAI Realtime wire protocol (GA shape, `session.type: "realtime"`), and
// xAI's Grok Voice, which speaks it with a flatter `session.update`. Kept free
// of audio and OpenCode knowledge so it can be tested from fixtures.

data class RealtimeFunctionTool(
    val name: String,
    val description: String,
    val parameters: RealtimeJSON,
)

/**
 * Minimal JSON value for tool schemas and function arguments: kotlinx's [JsonElement]. Build values
 * with [RealtimeJSON.string] / [RealtimeJSON.number] / [RealtimeJSON.obj] … so numbers encode.
 */
typealias RealtimeJSON = JsonElement

/** Builders. */
object RealtimeJSONs {
    fun string(value: String): RealtimeJSON = JsonPrimitive(value)

    /** A number written: integral values without a fraction. */
    fun number(value: Double): RealtimeJSON =
        if (value.isFinite() && value == Math.rint(value) && kotlin.math.abs(value) < 1e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)

    fun number(value: Int): RealtimeJSON = JsonPrimitive(value)
    fun bool(value: Boolean): RealtimeJSON = JsonPrimitive(value)
    val NULL: RealtimeJSON get() = JsonNull
    fun array(values: List<RealtimeJSON>): RealtimeJSON = JsonArray(values)
    fun obj(vararg pairs: Pair<String, RealtimeJSON>): RealtimeJSON = JsonObject(linkedMapOf(*pairs))
    fun obj(values: Map<String, RealtimeJSON>): RealtimeJSON = JsonObject(values)
}

/** The member of an object, else null. */
operator fun RealtimeJSON.get(key: String): RealtimeJSON? = (this as? JsonObject)?.get(key)

/** The value of a string, else null. */
val RealtimeJSON.stringValue: String?
    get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The JSON coder every voice protocol shares (`JSONDecoder` ignores unknown keys too). */
internal val VoiceWireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** Encodes a [RealtimeJSON] as UTF-8 bytes. */
internal fun RealtimeJSON.encodedBytes(): ByteArray = VoiceWireJson.encodeToString(JsonElement.serializer(), this).toByteArray()

/** Decodes standard base64, or null when it isn't. */
internal fun decodeBase64OrNull(encoded: String): ByteArray? = try {
    Base64.getDecoder().decode(encoded)
} catch (_: IllegalArgumentException) {
    null
}

internal fun ByteArray.base64Encoded(): String = Base64.getEncoder().encodeToString(this)

/** Seconds since 1970 as an [Instant]. */
internal fun instantOfEpochSeconds(seconds: Double): Instant = Instant.ofEpochMilli(Math.round(seconds * 1000))

data class RealtimeSessionConfig(
    val engine: VoiceEngine? = VoiceEngine.realtime,
    val model: String = defaultModel,
    val voice: CallVoice = CallVoice.default,
    val instructions: String,
    val tools: List<RealtimeFunctionTool>,
    val transcriptionModel: String = "gpt-live-transcribe",
    /** Without a language, short sounds transcribe as other scripts ("嗯"). */
    val transcriptionLanguages: List<String> = listOf("en"),
    /**
     * Semantic turn detection waits for a finished thought instead of a fixed
     * silence, so a pause mid-sentence doesn't end the user's turn.
     */
    val turnEagerness: String = "low",
    /** Raise to Talk: whether the call starts at the ear (receiver) rather than on the speaker. */
    val isAtEar: Boolean = false,
    /** The server brokering the session through `POST /api/experimental/voice/session`. */
    val serverVoice: OpenCodeServer? = null,
    /** The provider the broker runs, while a server brokers the session. */
    val provider: ServerVoiceProvider = ServerVoiceProvider.openAI,
    /**
     * GPT-Live: who runs its delegations (Server Voice runs the delegator on
     * the server's key through Responses delegation).
     */
    val liveDelegation: LiveDelegation = LiveDelegation.client,
    /**
     * OpenCode Session Delegator: sends unbound spoken updates (`delegationID == null`)
     * via `session.instructions.append` and runs the 900 ms action-claim backstop.
     */
    val unboundSpeechUsesInstructions: Boolean = false,
) {
    fun liveSessionConfig(input: List<LiveInputMessage> = emptyList()): LiveSessionConfig = LiveSessionConfig(
        model = if (model == defaultModel) LiveSessionConfig.defaultModel else model,
        voice = voice,
        instructions = instructions,
        input = input,
        isAtEar = isAtEar,
        serverVoice = serverVoice,
        delegation = liveDelegation,
        unboundSpeechUsesInstructions = unboundSpeechUsesInstructions,
    )

    /** The socket address: the model rides in the query on both services. */
    val webSocketURL: String?
        get() {
            val base = if (engine == VoiceEngine.grok) "wss://api.x.ai/v1/realtime" else "wss://api.openai.com/v1/realtime"
            // OkHttp's HttpUrl only parses http(s); build on https and swap the scheme back.
            val url = base.replaceFirst("wss://", "https://").toHttpUrlOrNull()
                ?.newBuilder()?.setQueryParameter("model", model)?.build() ?: return null
            return url.toString().replaceFirst("https://", "wss://")
        }

    /**
     * The `session.update` payload. Server-driven turns: the model responds
     * when the turn ends and cancels itself on barge-in. No server noise
     * reduction: the device's voice processing already cleans the input, and
     * stacking both clipped soft syllables.
     */
    val sessionUpdate: RealtimeJSON get() = sessionUpdate(includeAudioFormat = true)

    /** WebRTC negotiates the audio format in SDP, so its updates leave it out. */
    fun sessionUpdate(includeAudioFormat: Boolean): RealtimeJSON = RealtimeJSONs.obj(
        "type" to RealtimeJSONs.string("session.update"),
        "session" to sessionPayload(includeAudioFormat),
    )

    fun sessionPayload(includeAudioFormat: Boolean): RealtimeJSON {
        if (engine == VoiceEngine.grok) return grokSessionPayload
        val input = linkedMapOf(
            "transcription" to RealtimeJSONs.obj(
                "model" to RealtimeJSONs.string(transcriptionModel),
                "languages" to RealtimeJSONs.array(transcriptionLanguages.map(RealtimeJSONs::string)),
            ),
            "turn_detection" to RealtimeJSONs.obj(
                "type" to RealtimeJSONs.string("semantic_vad"),
                "eagerness" to RealtimeJSONs.string(turnEagerness),
                "create_response" to RealtimeJSONs.bool(true),
                "interrupt_response" to RealtimeJSONs.bool(true),
            ),
        )
        val output = linkedMapOf("voice" to RealtimeJSONs.string(voice.rawValue))
        if (includeAudioFormat) {
            input["format"] = RealtimeJSONs.obj("type" to RealtimeJSONs.string("audio/pcm"), "rate" to RealtimeJSONs.number(24_000))
            output["format"] = RealtimeJSONs.obj("type" to RealtimeJSONs.string("audio/pcm"), "rate" to RealtimeJSONs.number(24_000))
        }
        return RealtimeJSONs.obj(
            "type" to RealtimeJSONs.string("realtime"),
            "model" to RealtimeJSONs.string(model),
            "instructions" to RealtimeJSONs.string(instructions),
            "output_modalities" to RealtimeJSONs.array(listOf(RealtimeJSONs.string("audio"))),
            "tool_choice" to RealtimeJSONs.string("auto"),
            "tools" to RealtimeJSONs.array(tools.map(::toolJSON)),
            "audio" to RealtimeJSONs.obj("input" to RealtimeJSONs.obj(input), "output" to RealtimeJSONs.obj(output)),
        )
    }

    /**
     * Grok Voice's documented shape: voice and turn detection at the top and no
     * session type; it transcribes the user's turns without being asked. Its
     * turn detection is `server_vad` only; the model is in the socket's query.
     */
    val grokSessionPayload: RealtimeJSON
        get() {
            val format = RealtimeJSONs.obj("type" to RealtimeJSONs.string("audio/pcm"), "rate" to RealtimeJSONs.number(24_000))
            return RealtimeJSONs.obj(
                "voice" to RealtimeJSONs.string(voice.rawValue),
                "instructions" to RealtimeJSONs.string(instructions),
                "turn_detection" to RealtimeJSONs.obj("type" to RealtimeJSONs.string("server_vad")),
                "tools" to RealtimeJSONs.array(tools.map(::toolJSON)),
                "audio" to RealtimeJSONs.obj(
                    "input" to RealtimeJSONs.obj("format" to format),
                    "output" to RealtimeJSONs.obj("format" to format),
                ),
            )
        }

    companion object {
        const val defaultModel = "gpt-realtime-2.1"

        /** Pinned rather than `grok-voice-latest`, as xAI recommends for production. */
        const val grokModel = "grok-voice-3.0"

        private fun toolJSON(tool: RealtimeFunctionTool): RealtimeJSON = RealtimeJSONs.obj(
            "type" to RealtimeJSONs.string("function"),
            "name" to RealtimeJSONs.string(tool.name),
            "description" to RealtimeJSONs.string(tool.description),
            "parameters" to tool.parameters,
        )
    }
}

sealed interface RealtimeCommand {
    data class SessionUpdate(val config: RealtimeSessionConfig) : RealtimeCommand

    /** PCM16 mono 24 kHz. */
    class AppendAudio(val data: ByteArray) : RealtimeCommand {
        override fun equals(other: Any?): Boolean = other is AppendAudio && other.data.contentEquals(data)
        override fun hashCode(): Int = data.contentHashCode()
        override fun toString(): String = "AppendAudio(${data.size} bytes)"
    }

    /** Ends the user's turn now (muting mid-sentence sends no more audio to detect it). */
    data object CommitAudio : RealtimeCommand
    data class FunctionOutput(val callID: String, val output: String) : RealtimeCommand
    data class Truncate(val itemID: String, val audioEndMilliseconds: Int) : RealtimeCommand
    data object ResponseCreate : RealtimeCommand
    data object ResponseCancel : RealtimeCommand

    /**
     * Context the model reads but nobody said: continuity after a reconnect,
     * or a result whose tool call belonged to an earlier session.
     */
    data class SystemMessage(val text: String) : RealtimeCommand

    val json: RealtimeJSON
        get() = when (this) {
            is SessionUpdate -> config.sessionUpdate
            is AppendAudio -> RealtimeJSONs.obj(
                "type" to RealtimeJSONs.string("input_audio_buffer.append"),
                "audio" to RealtimeJSONs.string(data.base64Encoded()),
            )
            CommitAudio -> RealtimeJSONs.obj("type" to RealtimeJSONs.string("input_audio_buffer.commit"))
            is FunctionOutput -> RealtimeJSONs.obj(
                "type" to RealtimeJSONs.string("conversation.item.create"),
                "item" to RealtimeJSONs.obj(
                    "type" to RealtimeJSONs.string("function_call_output"),
                    "call_id" to RealtimeJSONs.string(callID),
                    "output" to RealtimeJSONs.string(output),
                ),
            )
            is Truncate -> RealtimeJSONs.obj(
                "type" to RealtimeJSONs.string("conversation.item.truncate"),
                "item_id" to RealtimeJSONs.string(itemID),
                "content_index" to RealtimeJSONs.number(0),
                "audio_end_ms" to RealtimeJSONs.number(audioEndMilliseconds),
            )
            ResponseCreate -> RealtimeJSONs.obj("type" to RealtimeJSONs.string("response.create"))
            ResponseCancel -> RealtimeJSONs.obj("type" to RealtimeJSONs.string("response.cancel"))
            is SystemMessage -> RealtimeJSONs.obj(
                "type" to RealtimeJSONs.string("conversation.item.create"),
                "item" to RealtimeJSONs.obj(
                    "type" to RealtimeJSONs.string("message"),
                    "role" to RealtimeJSONs.string("system"),
                    "content" to RealtimeJSONs.array(
                        listOf(RealtimeJSONs.obj("type" to RealtimeJSONs.string("input_text"), "text" to RealtimeJSONs.string(text))),
                    ),
                ),
            )
        }

    fun encoded(): ByteArray = json.encodedBytes()
}

sealed interface RealtimeServerEvent {
    /** `expiresAt` is when the service will end this session (60 minutes after it opened). */
    data class SessionCreated(val expiresAt: Instant?) : RealtimeServerEvent
    data object SpeechStarted : RealtimeServerEvent
    data object SpeechStopped : RealtimeServerEvent
    data class InputTranscript(val itemID: String, val text: String, val final: Boolean) : RealtimeServerEvent
    class OutputAudio(val responseID: String?, val itemID: String?, val pcm: ByteArray) : RealtimeServerEvent {
        override fun equals(other: Any?): Boolean =
            other is OutputAudio && other.responseID == responseID && other.itemID == itemID && other.pcm.contentEquals(pcm)
        override fun hashCode(): Int = (responseID.hashCode() * 31 + itemID.hashCode()) * 31 + pcm.contentHashCode()
        override fun toString(): String = "OutputAudio(responseID=$responseID, itemID=$itemID, ${pcm.size} bytes)"
    }
    data class OutputTranscriptDelta(val itemID: String?, val delta: String) : RealtimeServerEvent
    data class OutputTranscriptDone(val itemID: String?, val text: String) : RealtimeServerEvent
    data class FunctionCall(val callID: String, val name: String, val arguments: RealtimeJSON) : RealtimeServerEvent
    data class ResponseCreated(val id: String?) : RealtimeServerEvent
    data class ResponseDone(val id: String?) : RealtimeServerEvent
    data class Error(val error: RealtimeServerError) : RealtimeServerEvent

    /**
     * From the WebRTC transport, not the service: the path is losing or
     * delaying audio (true), or healthy again.
     */
    data class LinkQuality(val poor: Boolean) : RealtimeServerEvent
    data object Ignored : RealtimeServerEvent

    @Serializable
    private data class Envelope(
        val type: String,
        @SerialName("item_id") val itemID: String? = null,
        @SerialName("response_id") val responseID: String? = null,
        val transcript: String? = null,
        val delta: String? = null,
        @SerialName("call_id") val callID: String? = null,
        val name: String? = null,
        val arguments: String? = null,
        val response: ResponsePayload? = null,
        val error: ErrorPayload? = null,
        val session: SessionPayload? = null,
    ) {
        @Serializable data class SessionPayload(@SerialName("expires_at") val expiresAt: Double? = null)
        @Serializable data class ResponsePayload(val id: String? = null)
        @Serializable data class ErrorPayload(val message: String? = null, val code: String? = null, val type: String? = null)
    }

    companion object {
        /** Throws when the frame isn't JSON or has no `type`. */
        fun decode(data: ByteArray): RealtimeServerEvent = decode(data.decodeToString())

        fun decode(text: String): RealtimeServerEvent {
            val envelope = VoiceWireJson.decodeFromString(Envelope.serializer(), text)
            return when (envelope.type) {
                "session.created" -> SessionCreated(envelope.session?.expiresAt?.let(::instantOfEpochSeconds))
                "input_audio_buffer.speech_started" -> SpeechStarted
                "input_audio_buffer.speech_stopped" -> SpeechStopped
                "conversation.item.input_audio_transcription.completed" ->
                    InputTranscript(envelope.itemID ?: "", envelope.transcript ?: "", final = true)
                "conversation.item.input_audio_transcription.delta" ->
                    InputTranscript(envelope.itemID ?: "", envelope.delta ?: "", final = false)
                "response.output_audio.delta" -> {
                    val pcm = envelope.delta?.let(::decodeBase64OrNull) ?: return Ignored
                    OutputAudio(envelope.responseID, envelope.itemID, pcm)
                }
                "response.output_audio_transcript.delta" -> OutputTranscriptDelta(envelope.itemID, envelope.delta ?: "")
                "response.output_audio_transcript.done" -> OutputTranscriptDone(envelope.itemID, envelope.transcript ?: "")
                "response.function_call_arguments.done" -> {
                    val arguments = envelope.arguments?.let {
                        try { VoiceWireJson.parseToJsonElement(it) } catch (_: Exception) { null }
                    } ?: RealtimeJSONs.obj()
                    FunctionCall(envelope.callID ?: "", envelope.name ?: "", arguments)
                }
                "response.created" -> ResponseCreated(envelope.response?.id)
                "response.done" -> ResponseDone(envelope.response?.id)
                "error" -> Error(
                    RealtimeServerError(
                        message = envelope.error?.message ?: "The voice service reported an error.",
                        code = envelope.error?.code,
                        type = envelope.error?.type,
                    ),
                )
                else -> Ignored
            }
        }
    }
}

data class RealtimeServerError(
    val message: String,
    val code: String? = null,
    val type: String? = null,
) {
    enum class Disposition {
        /**
         * A race the call shrugs off (a truncate after a short item drained, a
         * cancel crossing `response.done`): log it, show nothing.
         */
        harmless,

        /** The session hit its time limit; the call has ended. */
        sessionExpired,

        /** The credentials or account can't continue. */
        fatal;

        companion object {
            fun classify(type: String?, code: String?, message: String?): Disposition {
                val classification = listOfNotNull(type, code, message).joinToString(" ") { it.lowercase() }
                if (code == "session_expired" || code == "expired" || classification.contains("maximum duration")) {
                    return sessionExpired
                }
                val fatalMarkers = listOf(
                    "authentication", "invalid_api_key", "api key", "permission", "insufficient_quota", "quota", "billing",
                    "model_not_found",
                )
                return if (fatalMarkers.any(classification::contains)) fatal else harmless
            }
        }
    }

    val disposition: Disposition get() = Disposition.classify(type, code, message)
}
