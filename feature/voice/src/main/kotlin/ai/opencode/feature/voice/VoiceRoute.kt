package ai.opencode.feature.voice

import ai.opencode.core.strings.localized
import ai.opencode.server.OpenCodeServer

/** The only precedence table for a new call. Never contains a phone key. */
sealed interface VoiceRoute {
    data class Server(val server: ServerRoute) : VoiceRoute
    data class PhoneKey(override val engine: VoiceEngine) : VoiceRoute
    data class Unavailable(val reason: Reason) : VoiceRoute

    enum class OpenAI {
        gptLive, realtime;

        val engine: VoiceEngine get() = if (this == gptLive) VoiceEngine.gptLive else VoiceEngine.realtime
    }

    /** Named `ServerRoute`: Kotlin can't nest it beside the `Server` case. */
    sealed interface ServerRoute {
        data class OpenAI(val model: VoiceRoute.OpenAI) : ServerRoute
        data object GeminiLive : ServerRoute
    }

    sealed interface Reason {
        data class MissingPhoneKey(val engine: VoiceEngine) : Reason
        data object ServerUnsupported : Reason

        val message: String
            get() = when (this) {
                is MissingPhoneKey -> "Add your ${engine.provider.displayName} API key in Settings to use Voice."
                ServerUnsupported -> ServerVoiceError.unsupportedServerNotice
            }
    }

    val isAvailable: Boolean get() = this !is Unavailable

    val isServer: Boolean get() = this is Server

    val warmsPhoneConnection: Boolean get() = this == PhoneKey(VoiceEngine.gptLive)
    val usesBrokerDelegator: Boolean get() = this == serverOpenAI(OpenAI.gptLive)
    val sendsContextDelta: Boolean get() = engine == VoiceEngine.gptLive || this == serverGeminiLive

    /** Gemini is not an OpenAI engine, even though it shares the attached-session tools. */
    val engine: VoiceEngine?
        get() = when (this) {
            is PhoneKey -> engine
            is Server -> when (val route = server) {
                is ServerRoute.OpenAI -> route.model.engine
                ServerRoute.GeminiLive -> null
            }
            is Unavailable -> null
        }

    val logEngine: String get() = engine?.rawValue ?: if (this == serverGeminiLive) "gemini-live" else "unavailable"
    val provider: ServerVoiceProvider get() = if (this == serverGeminiLive) ServerVoiceProvider.geminiLive else ServerVoiceProvider.openAI
    val modelID: String get() = engine?.modelID ?: GeminiLiveSessionConfig.defaultModel

    val keySource: VoiceKeySource
        get() = when (this) {
            is Server -> when (server) {
                is ServerRoute.OpenAI -> VoiceKeySource.Server(providerName = "OpenAI")
                ServerRoute.GeminiLive -> VoiceKeySource.Server(providerName = "Gemini Live")
            }
            is Unavailable -> when (val reason = reason) {
                Reason.ServerUnsupported -> VoiceKeySource.Server(providerName = "Gemini Live")
                is Reason.MissingPhoneKey -> VoiceKeySource.Phone(reason.engine.provider)
            }
            is PhoneKey -> VoiceKeySource.Phone(engine.provider)
        }

    fun sessionConfig(
        voice: CallVoice,
        context: VoiceInstructions.Context,
        isAtEar: Boolean,
        server: OpenCodeServer,
        delegation: LiveDelegation = LiveDelegation.client,
        unboundSpeechUsesInstructions: Boolean = false,
    ): RealtimeSessionConfig = RealtimeSessionConfig(
        engine = engine,
        model = modelID,
        voice = voice,
        instructions = VoiceInstructions.build(context, route = this),
        tools = if (engine == VoiceEngine.gptLive) emptyList() else VoiceInstructions.tools,
        isAtEar = isAtEar,
        serverVoice = if (isServer) server else null,
        provider = provider,
        liveDelegation = if (usesBrokerDelegator) delegation else LiveDelegation.client,
        unboundSpeechUsesInstructions = unboundSpeechUsesInstructions,
    )

    companion object {
        fun serverOpenAI(model: OpenAI): VoiceRoute = Server(ServerRoute.OpenAI(model))

        val serverGeminiLive: VoiceRoute = Server(ServerRoute.GeminiLive)

        fun resolve(
            selectedEngine: VoiceEngine,
            serverVoiceEnabled: Boolean,
            serverVoiceProvider: ServerVoiceProvider,
            serverVoiceSupport: ServerVoiceSupport,
            hasOpenAIKey: Boolean,
            hasXAIKey: Boolean,
            fallsBackToPhoneKey: Boolean = true,
        ): VoiceRoute {
            // Server Voice never owns Grok, including when Gemini is selected on the server.
            if (selectedEngine == VoiceEngine.grok) {
                return if (hasXAIKey) PhoneKey(VoiceEngine.grok) else Unavailable(Reason.MissingPhoneKey(VoiceEngine.grok))
            }
            if (serverVoiceEnabled && serverVoiceProvider == ServerVoiceProvider.geminiLive) {
                return if (serverVoiceSupport == ServerVoiceSupport.unsupported) Unavailable(Reason.ServerUnsupported) else serverGeminiLive
            }
            if (serverVoiceEnabled && serverVoiceSupport != ServerVoiceSupport.unsupported) {
                return serverOpenAI(if (selectedEngine == VoiceEngine.gptLive) OpenAI.gptLive else OpenAI.realtime)
            }
            // A server the person picked is never swapped for the phone's key.
            if (serverVoiceEnabled && !fallsBackToPhoneKey) return Unavailable(Reason.ServerUnsupported)
            // Automatic falls back only after a confirmed unsupported route.
            return if (hasOpenAIKey) PhoneKey(selectedEngine) else Unavailable(Reason.MissingPhoneKey(selectedEngine))
        }
    }
}

/**
 * Who pays for a call, moved down to Voice because [VoiceRoute.keySource] returns it and Settings
 * sits above Voice. Settings' `VoiceKeyStatus` stays in Settings.
 */
sealed interface VoiceKeySource {
    data class Phone(val provider: VoiceProvider) : VoiceKeySource
    data class Server(val providerName: String) : VoiceKeySource

    /**
     * The server's OpenCode Console connection: OpenCode hosts the call and
     * bills the connection's workspace.
     */
    data object OpenCode : VoiceKeySource

    val rowTitle: String
        get() = when (this) {
            is Phone -> provider.keyRowTitle
            is Server, OpenCode -> localized(R.string.voice_key_source_server_row_title)
        }

    /** Settings › Voice's footer: whose key, or which workspace, pays for calls. */
    val footer: String
        get() = when (this) {
            is Phone -> localized(R.string.voice_key_source_phone_footer)
            is Server -> localized(R.string.voice_key_source_server_footer, providerName)
            OpenCode -> localized(R.string.voice_key_source_opencode_footer)
        }

    val isPhoneKey: Boolean get() = this is Phone
}

/**
 * Which server brokers a new call's Server Voice session (Settings › Voice ›
 * Voice Provider), for calls on `connected`. Settings, Home, the session and a
 * call's start all resolve it here ([VoicePick.resolve]), and [route] resolves
 * the call from it. Only the broker moves: a call's session, context and
 * tools stay on its own server.
 */
class VoiceBroker(
    choice: VoiceServerChoice,
    provider: ServerVoiceProvider = ServerVoiceProvider.openAI,
    connected: OpenCodeServer?,
    saved: List<OpenCodeServer>,
    readiness: VoiceReadiness = VoiceReadiness(),
) {
    /** A server brokers new calls: the pick, or Automatic's choice, is a server. */
    val isEnabled: Boolean

    /** The server that brokers, picked or chosen automatically. Null while nothing brokers. */
    val server: OpenCodeServer?

    /** Chosen automatically: a server without the voice routes falls back to the phone's key. A pick never does. */
    val isAutomatic: Boolean

    /** The provider the broker runs: the picked one, OpenAI automatically. */
    val provider: ServerVoiceProvider

    init {
        val pick = VoicePick.resolve(choice, provider, connected, saved, readiness)
        if (pick.place == VoiceProviderOption.Place.Phone) {
            isEnabled = false
            server = null
        } else {
            isEnabled = true
            server = pick.server ?: connected
        }
        isAutomatic = pick.isAutomatic
        this.provider = pick.provider
    }

    companion object {
        /** The broker and the readiness it was resolved with. */
        fun resolve(
            choice: VoiceServerChoice,
            provider: ServerVoiceProvider = ServerVoiceProvider.openAI,
            connected: OpenCodeServer?,
            saved: List<OpenCodeServer>,
            answers: Map<String, ServerVoiceModelsAnswer>,
            support: Map<String, ServerVoiceSupport>,
            hasOpenAIKey: Boolean,
            engine: VoiceEngine,
        ): Pair<VoiceBroker, VoiceReadiness> {
            val readiness = VoiceReadiness(answers = answers, support = support, hasPhoneKey = hasOpenAIKey, engine = engine)
            val broker = VoiceBroker(choice, provider, connected, saved, readiness)
            return broker to readiness
        }
    }

    /** What's known of whether the broker offers `POST /api/experimental/voice/session`. */
    fun support(supportByServer: Map<String, ServerVoiceSupport>): ServerVoiceSupport =
        server?.let { supportByServer[it.persistenceID] } ?: ServerVoiceSupport.unknown

    /** The route a new call on `engine` takes through this broker. */
    fun route(engine: VoiceEngine, support: ServerVoiceSupport, hasOpenAIKey: Boolean, hasXAIKey: Boolean): VoiceRoute =
        VoiceRoute.resolve(
            selectedEngine = engine,
            serverVoiceEnabled = isEnabled,
            serverVoiceProvider = provider,
            serverVoiceSupport = support,
            hasOpenAIKey = hasOpenAIKey,
            hasXAIKey = hasXAIKey,
            fallsBackToPhoneKey = !isEnabled || isAutomatic,
        )

    override fun equals(other: Any?): Boolean =
        other is VoiceBroker && other.isEnabled == isEnabled && other.server == server &&
            other.isAutomatic == isAutomatic && other.provider == provider
    override fun hashCode(): Int = listOf(isEnabled, server, isAutomatic, provider).hashCode()
    override fun toString(): String =
        "VoiceBroker(isEnabled=$isEnabled, server=${server?.persistenceID?.let { "set" }}, isAutomatic=$isAutomatic, provider=$provider)"
}
