package ai.opencode.feature.voice

import ai.opencode.server.OpenCodeServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.util.UUID

// Google Gemini Live API wire protocol (`BidiGenerateContent` and
// `BidiGenerateContentConstrained`). Kept free of UI and reducer state so
// setup, input audio, server content, transcriptions, interruptions, and tool
// calls can be tested directly from fixtures.

data class GeminiLiveContentTurn(val role: String, val text: String) {
    val json: RealtimeJSON
        get() = RealtimeJSONs.obj(
            "role" to RealtimeJSONs.string(role),
            "parts" to RealtimeJSONs.array(listOf(RealtimeJSONs.obj("text" to RealtimeJSONs.string(text)))),
        )
}

data class GeminiLiveFunctionCall(val id: String, val name: String, val args: RealtimeJSON)

data class GeminiLiveFunctionResponse(
    val id: String,
    val name: String,
    val response: RealtimeJSON,
    /** `SILENT` closes a call without the model speaking about it. */
    val scheduling: String? = null,
) {
    val json: RealtimeJSON
        get() = RealtimeJSONs.obj(
            *listOfNotNull(
                "id" to RealtimeJSONs.string(id),
                "name" to RealtimeJSONs.string(name),
                "response" to response,
                scheduling?.let { "scheduling" to RealtimeJSONs.string(it) },
            ).toTypedArray(),
        )
}

data class GeminiLiveSessionConfig(
    val model: String = defaultModel,
    val voice: CallVoice = CallVoice.default,
    val instructions: String,
    val tools: List<RealtimeFunctionTool>,
    val isAtEar: Boolean = false,
    val serverVoice: OpenCodeServer? = null,
) {
    val modelResourceName: String get() = if (model.startsWith("models/")) model else "models/$model"

    val geminiVoiceName: String
        get() = when (voice) {
            CallVoice.marin, CallVoice.coral, CallVoice.shimmer, CallVoice.ballad, CallVoice.sage -> "Aoede"
            CallVoice.cedar, CallVoice.ash, CallVoice.echo, CallVoice.verse -> "Puck"
            CallVoice.alloy -> "Kore"
            else -> "Aoede"
        }

    /**
     * The `BidiGenerateContentSetup` object without the outer `"setup"` wrapper,
     * suitable for both the server's `bidiGenerateContentSetup` token constraint
     * and the WebSocket's initial `setup` frame.
     */
    val setupBody: RealtimeJSON
        get() {
            val body = setupFields
            body["model"] = RealtimeJSONs.string(modelResourceName)
            return RealtimeJSONs.obj(body)
        }

    /**
     * Session fields sent to `POST /api/experimental/voice/session` (the server
     * sets `model: "models/<model>"` when minting the ephemeral token).
     */
    val serverSessionPayload: RealtimeJSON get() = RealtimeJSONs.obj(setupFields)

    private val setupFields: LinkedHashMap<String, RealtimeJSON>
        get() {
            val fields = linkedMapOf(
                "generationConfig" to RealtimeJSONs.obj(
                    "responseModalities" to RealtimeJSONs.array(listOf(RealtimeJSONs.string("AUDIO"))),
                    "speechConfig" to RealtimeJSONs.obj(
                        "voiceConfig" to RealtimeJSONs.obj(
                            "prebuiltVoiceConfig" to RealtimeJSONs.obj(
                                "voiceName" to RealtimeJSONs.string(geminiVoiceName),
                            ),
                        ),
                    ),
                ),
                "systemInstruction" to RealtimeJSONs.obj(
                    "parts" to RealtimeJSONs.array(listOf(RealtimeJSONs.obj("text" to RealtimeJSONs.string(instructions)))),
                ),
                "inputAudioTranscription" to RealtimeJSONs.obj(),
                "outputAudioTranscription" to RealtimeJSONs.obj(),
            )
            if (tools.isNotEmpty()) {
                fields["tools"] = RealtimeJSONs.array(
                    listOf(RealtimeJSONs.obj("functionDeclarations" to RealtimeJSONs.array(tools.map(::toolDeclarationJSON)))),
                )
            }
            return fields
        }

    val setupMessage: RealtimeJSON get() = RealtimeJSONs.obj("setup" to setupBody)

    companion object {
        const val defaultModel = "gemini-3.8-live"
        const val defaultWebSocketURL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"

        fun from(config: RealtimeSessionConfig): GeminiLiveSessionConfig {
            val resolvedModel = if (config.model == RealtimeSessionConfig.defaultModel ||
                config.model == LiveSessionConfig.defaultModel ||
                config.model == RealtimeSessionConfig.grokModel
            ) {
                defaultModel
            } else {
                config.model
            }
            return GeminiLiveSessionConfig(
                model = resolvedModel,
                voice = config.voice,
                instructions = config.instructions,
                tools = config.tools,
                isAtEar = config.isAtEar,
                serverVoice = config.serverVoice,
            )
        }

        fun toolDeclarationJSON(tool: RealtimeFunctionTool): RealtimeJSON = RealtimeJSONs.obj(
            "name" to RealtimeJSONs.string(tool.name),
            "description" to RealtimeJSONs.string(tool.description),
            "parametersJsonSchema" to tool.parameters,
        )

        fun webSocketURL(baseURL: String, token: String): String? {
            val isSecureSocket = baseURL.startsWith("wss://")
            val isSocket = isSecureSocket || baseURL.startsWith("ws://")
            // OkHttp's HttpUrl parses http(s) only: parse a ws(s) address as http(s) and swap back.
            val parsable = when {
                isSecureSocket -> "https://" + baseURL.removePrefix("wss://")
                isSocket -> "http://" + baseURL.removePrefix("ws://")
                else -> baseURL
            }
            val url = parsable.toHttpUrlOrNull() ?: return null
            val paramName = if (baseURL.contains("BidiGenerateContentConstrained") || token.startsWith("auth_tokens/")) "access_token" else "key"
            val names = url.queryParameterNames
            val built = if (paramName !in names && "access_token" !in names && "key" !in names && token.isNotEmpty()) {
                url.newBuilder().addQueryParameter(paramName, token).build()
            } else {
                url
            }
            val text = built.toString()
            return when {
                isSecureSocket -> "wss://" + text.removePrefix("https://")
                isSocket -> "ws://" + text.removePrefix("http://")
                else -> text
            }
        }
    }
}

/** Client-to-server Gemini Live WebSocket messages. */
sealed interface GeminiLiveCommand {
    data class Setup(val config: GeminiLiveSessionConfig) : GeminiLiveCommand

    /** 16-bit PCM mono at 16 kHz (`audio/pcm;rate=16000`). */
    class RealtimeInputAudio(val pcm16k: ByteArray) : GeminiLiveCommand {
        override fun equals(other: Any?): Boolean = other is RealtimeInputAudio && other.pcm16k.contentEquals(pcm16k)
        override fun hashCode(): Int = pcm16k.contentHashCode()
        override fun toString(): String = "RealtimeInputAudio(${pcm16k.size} bytes)"
    }

    data class ClientContent(val turns: List<GeminiLiveContentTurn>, val turnComplete: Boolean) : GeminiLiveCommand
    data class ToolResponse(val responses: List<GeminiLiveFunctionResponse>) : GeminiLiveCommand

    val json: RealtimeJSON
        get() = when (this) {
            is Setup -> config.setupMessage
            is RealtimeInputAudio -> RealtimeJSONs.obj(
                "realtimeInput" to RealtimeJSONs.obj(
                    "audio" to RealtimeJSONs.obj(
                        "mimeType" to RealtimeJSONs.string("audio/pcm;rate=16000"),
                        "data" to RealtimeJSONs.string(pcm16k.base64Encoded()),
                    ),
                ),
            )
            is ClientContent -> RealtimeJSONs.obj(
                "clientContent" to RealtimeJSONs.obj(
                    "turns" to RealtimeJSONs.array(turns.map { it.json }),
                    "turnComplete" to RealtimeJSONs.bool(turnComplete),
                ),
            )
            is ToolResponse -> RealtimeJSONs.obj(
                "toolResponse" to RealtimeJSONs.obj(
                    "functionResponses" to RealtimeJSONs.array(responses.map { it.json }),
                ),
            )
        }

    fun encoded(): ByteArray = json.encodedBytes()
}

/** Server-to-client Gemini Live WebSocket events. */
sealed interface GeminiLiveServerEvent {
    data object SetupComplete : GeminiLiveServerEvent
    data object Interrupted : GeminiLiveServerEvent
    data class InputTranscription(val text: String, val finished: Boolean) : GeminiLiveServerEvent
    data class OutputTranscription(val text: String, val finished: Boolean) : GeminiLiveServerEvent

    /** 16-bit PCM mono at 24 kHz (`audio/pcm;rate=24000`). */
    class Audio(val pcm: ByteArray) : GeminiLiveServerEvent {
        override fun equals(other: Any?): Boolean = other is Audio && other.pcm.contentEquals(pcm)
        override fun hashCode(): Int = pcm.contentHashCode()
        override fun toString(): String = "Audio(${pcm.size} bytes)"
    }

    data class ModelText(val text: String) : GeminiLiveServerEvent
    data class FunctionCall(val call: GeminiLiveFunctionCall) : GeminiLiveServerEvent
    data class ToolCallCancellation(val ids: List<String>) : GeminiLiveServerEvent
    data object TurnComplete : GeminiLiveServerEvent
    data class GoAway(val timeLeft: String?) : GeminiLiveServerEvent
    data class Error(val message: String) : GeminiLiveServerEvent
    data object Unknown : GeminiLiveServerEvent

    @Serializable
    private data class ServerMessageEnvelope(
        val setupComplete: JsonObject? = null,
        val serverContent: ServerContent? = null,
        val toolCall: ToolCall? = null,
        val toolCallCancellation: ToolCallCancellationPayload? = null,
        val goAway: GoAwayPayload? = null,
        val error: WireError? = null,
    ) {
        @Serializable data class Transcription(val text: String? = null, val finished: Boolean? = null)
        @Serializable data class InlineData(val mimeType: String? = null, val data: String? = null)

        @Serializable
        data class WireFunctionCall(val id: String? = null, val name: String? = null, val args: JsonElement? = null) {
            val functionCall: GeminiLiveFunctionCall?
                get() {
                    if (name.isNullOrEmpty()) return null
                    return GeminiLiveFunctionCall(
                        id = id ?: "gemini-call-${UUID.randomUUID().toString().uppercase()}",
                        name = name,
                        args = args ?: RealtimeJSONs.obj(),
                    )
                }
        }

        @Serializable
        data class Part(
            val text: String? = null,
            val thought: Boolean? = null,
            val inlineData: InlineData? = null,
            val functionCall: WireFunctionCall? = null,
        )

        @Serializable data class ModelTurn(val role: String? = null, val parts: List<Part>? = null)

        @Serializable
        data class ServerContent(
            val modelTurn: ModelTurn? = null,
            val turnComplete: Boolean? = null,
            val generationComplete: Boolean? = null,
            val interrupted: Boolean? = null,
            val inputTranscription: Transcription? = null,
            val outputTranscription: Transcription? = null,
        )

        @Serializable data class ToolCall(val functionCalls: List<WireFunctionCall>? = null)
        @Serializable data class ToolCallCancellationPayload(val ids: List<String>? = null)
        @Serializable data class GoAwayPayload(val timeLeft: String? = null)
        @Serializable data class WireError(val code: Int? = null, val message: String? = null, val status: String? = null)
    }

    companion object {
        fun decode(data: ByteArray): GeminiLiveServerEvent = decodeAll(data).firstOrNull() ?: Unknown

        /**
         * A single `BidiGenerateContentServerMessage` frame can carry multiple
         * semantic events (for example, the final audio chunk alongside
         * `turnComplete: true`). Returns them in causal order. Throws when the
         * frame isn't JSON.
         */
        fun decodeAll(data: ByteArray): List<GeminiLiveServerEvent> {
            val envelope = VoiceWireJson.decodeFromString(ServerMessageEnvelope.serializer(), data.decodeToString())
            val events = mutableListOf<GeminiLiveServerEvent>()

            if (envelope.setupComplete != null) events += SetupComplete

            envelope.error?.let { error ->
                val message = error.message?.trim()
                events += Error(if (!message.isNullOrEmpty()) message else "The Gemini Live session failed.")
            }

            envelope.serverContent?.let { content ->
                if (content.interrupted == true) events += Interrupted
                content.inputTranscription?.let { input ->
                    if (!input.text.isNullOrEmpty()) events += InputTranscription(input.text, input.finished ?: false)
                }
                content.modelTurn?.parts?.forEach { part ->
                    val inline = part.inlineData
                    val base64 = inline?.data
                    val mime = inline?.mimeType
                    if (!base64.isNullOrEmpty() && mime != null && mime.startsWith("audio/pcm")) {
                        val pcm = decodeBase64OrNull(base64)
                        if (pcm != null && pcm.isNotEmpty()) events += Audio(pcm)
                    }
                    if (!part.text.isNullOrEmpty() && part.thought != true) events += ModelText(part.text)
                    part.functionCall?.functionCall?.let { events += FunctionCall(it) }
                }
                content.outputTranscription?.let { output ->
                    if (!output.text.isNullOrEmpty()) events += OutputTranscription(output.text, output.finished ?: false)
                }
                if (content.turnComplete == true || content.generationComplete == true) events += TurnComplete
            }

            envelope.toolCall?.functionCalls?.mapNotNull { it.functionCall }?.forEach { events += FunctionCall(it) }

            val ids = envelope.toolCallCancellation?.ids
            if (!ids.isNullOrEmpty()) events += ToolCallCancellation(ids)

            envelope.goAway?.let { events += GoAway(it.timeLeft) }

            if (events.isEmpty()) events += Unknown
            return events
        }
    }
}

/**
 * Downsamples 24 kHz 16-bit mono PCM from `VoiceAudioClient` to 16 kHz 16-bit
 * mono PCM for Gemini Live's `realtimeInput.audio` (an exact 3:2 ratio).
 *
 * A plain object on the audio path: it keeps the leftover samples between chunks.
 */
class GeminiPCMResampler {
    private var remainder: ShortArray = ShortArray(0)

    fun downsample24kTo16k(pcm24k: ByteArray): ByteArray {
        val sampleCount = pcm24k.size / 2
        if (sampleCount <= 0) return ByteArray(0)

        val samples = ShortArray(remainder.size + sampleCount)
        remainder.copyInto(samples)
        for (i in 0 until sampleCount) {
            val low = pcm24k[i * 2].toInt() and 0xFF
            val high = pcm24k[i * 2 + 1].toInt()
            samples[remainder.size + i] = ((high shl 8) or low).toShort()
        }

        val triplets = samples.size / 3
        val consumed = triplets * 3
        remainder = samples.copyOfRange(consumed, samples.size)
        if (triplets <= 0) return ByteArray(0)

        val output = ByteArrayOutputStream(triplets * 4)
        for (t in 0 until triplets) {
            val idx = t * 3
            val s0 = samples[idx].toInt()
            val s1 = samples[idx + 1].toInt()
            val s2 = samples[idx + 2].toInt()
            val out0 = clamp((2 * s0 + s1) / 3)
            val out1 = clamp((s1 + 2 * s2) / 3)
            output.write(out0 and 0xFF)
            output.write((out0 shr 8) and 0xFF)
            output.write(out1 and 0xFF)
            output.write((out1 shr 8) and 0xFF)
        }
        return output.toByteArray()
    }

    private fun clamp(value: Int): Int = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
}
