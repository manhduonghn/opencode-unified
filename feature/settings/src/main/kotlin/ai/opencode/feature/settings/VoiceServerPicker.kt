package ai.opencode.feature.settings

import ai.opencode.core.store.Effect
import ai.opencode.core.store.cancellable
import ai.opencode.feature.voice.ServerVoiceModels
import ai.opencode.feature.voice.ServerVoiceModelsAnswer
import ai.opencode.feature.voice.VoiceBroker
import ai.opencode.feature.voice.VoiceKeySource
import ai.opencode.feature.voice.VoiceModelsCache
import ai.opencode.feature.voice.VoiceProviderCatalog
import ai.opencode.feature.voice.VoiceReadiness
import ai.opencode.feature.voice.VoiceRoute
import ai.opencode.server.OpenCodeServer

// Settings › Voice › Voice Provider, which provider calls use. Automatic by default (the connected
// server, this phone's key, then another saved server that's ready), or one picked. The rows
// themselves are on the Voice Provider page (`VoiceProviderPage.kt`).

/** The server calls start from: Home's, else the last one used. */
val VoiceSettingsFeature.State.connectedServer: OpenCodeServer? get() = server ?: savedServer

val VoiceSettingsFeature.State.hasOpenAIKey: Boolean get() = openAIAPIKey.orEmpty().trim().isNotEmpty()

val VoiceSettingsFeature.State.readiness: VoiceReadiness
    get() = VoiceReadiness(
        answers = voiceModelsByServer,
        support = serverVoiceSupportByServer,
        hasPhoneKey = hasOpenAIKey,
        engine = voiceEngine,
    )

val VoiceSettingsFeature.State.broker: VoiceBroker
    get() = VoiceBroker(
        choice = voiceServer,
        provider = serverVoiceProvider,
        connected = connectedServer,
        saved = savedServers + listOfNotNull(savedServer),
        readiness = readiness,
    )

/** Every saved server but the connected one, each once, the last used first. */
val VoiceSettingsFeature.State.otherSavedServers: List<OpenCodeServer>
    get() {
        val seen = listOfNotNull(connectedServer?.persistenceID).toMutableSet()
        return (savedServers + listOfNotNull(savedServer)).filter { seen.add(it.persistenceID) }
    }

/** The Voice Provider row and its page. */
val VoiceSettingsFeature.State.providerCatalog: VoiceProviderCatalog
    get() = VoiceProviderCatalog(
        choice = voiceServer,
        provider = serverVoiceProvider,
        connected = connectedServer,
        others = otherSavedServers,
        readiness = readiness,
    )

/**
 * Where calls' credentials live. A broker that lists the call's model as OpenCode's starts it
 * through its OpenCode Console connection.
 */
val VoiceSettingsFeature.State.keySource: VoiceKeySource
    get() {
        val route = route
        val brokerServer = broker.server
        if (route is VoiceRoute.Server && brokerServer != null &&
            ServerVoiceModels.hostedByOpenCode(route.server, voiceModelsByServer[brokerServer.persistenceID]?.models.orEmpty())
        ) {
            return VoiceKeySource.OpenCode
        }
        return route.keySource
    }

/**
 * Asks saved servers which voice models they hold keys for, so Voice Provider can say which are
 * ready and Automatic can move on to them. A server that doesn't answer reads as offline; one
 * without the list (a 404) as needing an update. Settings › Voice asks the connected server; the
 * others only when Voice Provider's page, which lists them, opens: a saved server may be asleep,
 * and asking one that won't answer is traffic into a dead route.
 */
internal fun VoiceSettingsFeature.checkVoiceServers(
    servers: List<OpenCodeServer>,
): Effect<VoiceSettingsFeature.Action> {
    val client = serverVoice
    return Effect.merge(servers.map { server ->
        val epoch = VoiceModelsCache.epoch(server.persistenceID)
        Effect.run<VoiceSettingsFeature.Action> { send ->
            val models = client.models(server)
            send(
                VoiceSettingsFeature.Action.VoiceModelsLoaded(
                    server.persistenceID,
                    epoch,
                    models?.let { ServerVoiceModelsAnswer.Listed(it) } ?: ServerVoiceModelsAnswer.NoAnswer,
                ),
            )
        }.cancellable(VoiceSettingsFeature.CancelID.VoiceModels(server.persistenceID), cancelInFlight = true)
    })
}
