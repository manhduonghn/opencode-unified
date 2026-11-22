package ai.opencode.feature.voice

import ai.opencode.core.strings.localized
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * Settings › Voice › Delegator (the `voiceSessionDelegator` experiment): whether GPT-Live uses its
 * built-in Responses API delegator or steers a real OpenCode session on the connected server.
 */
@Serializable(with = VoiceDelegatorChoice.Serializer::class)
sealed interface VoiceDelegatorChoice {
    /** The default stateless `/v1/responses` delegator ([VoiceDelegator]). */
    data object BuiltIn : VoiceDelegatorChoice

    /** Finds or creates a dedicated `"Voice"` session on the connected server, cached by session ID per server. */
    data object AutomaticSession : VoiceDelegatorChoice

    /** Steers whichever session is currently open on the phone's screen. */
    data object ViewedSession : VoiceDelegatorChoice

    /** Steers a specific session picked in Settings, never changing its model. */
    data class PickedSession(override val picked: Picked) : VoiceDelegatorChoice

    @Serializable
    data class Picked(
        /** [voiceServerID], never a server URL or credential. */
        val serverID: String,
        val sessionID: String,
        val title: String,
        val projectName: String? = null,
    ) {
        /** The title the delegator and the voice agent read; it stays English. */
        val modelTitle: String get() = VoiceFeature.State.usableTitle(title) ?: "Session"

        val displayTitle: String
            get() = VoiceFeature.State.usableTitle(title)?.let { VoiceFeature.State.displayTitle(it) } ?: localized(R.string.voice_delegator_session_fallback)

        companion object {
            operator fun invoke(session: OpenCodeSession, server: OpenCodeServer): Picked {
                val folder = VoiceFeature.State.lastPathComponent(session.directory)
                return Picked(
                    serverID = voiceServerID(server),
                    sessionID = session.id,
                    title = VoiceFeature.State.usableTitle(session.title) ?: VoiceFeature.State.newSessionTitle,
                    projectName = if (folder.isEmpty() || folder == "/") null else folder,
                )
            }
        }
    }

    val rawValue: String
        get() = when (this) {
            BuiltIn -> ""
            AutomaticSession -> "automatic"
            ViewedSession -> "viewed"
            // Null fields left out.
            is PickedSession -> pickedJSON.encodeToString(Picked.serializer(), picked)
        }

    val usesOpenCodeSession: Boolean get() = this != BuiltIn

    val picked: Picked? get() = (this as? PickedSession)?.picked

    /** Whether a picked session belongs to `server` (true for non-picked modes). */
    fun belongs(server: OpenCodeServer?): Boolean {
        val picked = picked ?: return true
        if (server == null) return true
        return picked.serverID == voiceServerID(server)
    }

    companion object {
        private val pickedJSON = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun of(rawValue: String): VoiceDelegatorChoice = when (rawValue) {
            "", "built-in" -> BuiltIn
            "automatic" -> AutomaticSession
            "viewed" -> ViewedSession
            else -> runCatching { pickedJSON.decodeFromString(Picked.serializer(), rawValue) }.getOrNull()
                ?.takeIf { it.sessionID.isNotEmpty() && it.serverID.isNotEmpty() }
                ?.let { PickedSession(it) }
                ?: BuiltIn
        }
    }

    /** Stored as its raw value. */
    object Serializer : KSerializer<VoiceDelegatorChoice> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("VoiceDelegatorChoice", PrimitiveKind.STRING)
        override fun serialize(encoder: Encoder, value: VoiceDelegatorChoice) = encoder.encodeString(value.rawValue)
        override fun deserialize(decoder: Decoder): VoiceDelegatorChoice = of(decoder.decodeString())
    }
}

/** Whether OpenCode Session delegation is available for the active voice route. */
sealed interface VoiceDelegatorRouteAvailability {
    data object Available : VoiceDelegatorRouteAvailability

    /** Gemini Live delegates straight to the session on screen without a delegator. */
    data object GeminiLive : VoiceDelegatorRouteAvailability

    /** Realtime and Grok Voice delegate straight to the session on screen without a delegator. */
    data class UnsupportedEngine(val engine: VoiceEngine) : VoiceDelegatorRouteAvailability

    val isAvailable: Boolean get() = this == Available

    val explanation: String?
        get() = when (this) {
            Available -> null
            GeminiLive -> localized(R.string.voice_delegator_unavailable_gemini)
            is UnsupportedEngine -> localized(R.string.voice_delegator_unavailable_engine, engine.title)
        }

    companion object {
        fun resolve(route: VoiceRoute, selectedEngine: VoiceEngine, isGeminiProvider: Boolean = false): VoiceDelegatorRouteAvailability {
            if (selectedEngine != VoiceEngine.grok && (route == VoiceRoute.serverGeminiLive || isGeminiProvider)) return GeminiLive
            if (selectedEngine != VoiceEngine.gptLive) return UnsupportedEngine(selectedEngine)
            return Available
        }
    }
}
