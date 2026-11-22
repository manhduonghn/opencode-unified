package ai.opencode.feature.settings

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.Dependency
import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.experiments.key
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.core.strings.localized
import ai.opencode.feature.voice.CallVoice
import ai.opencode.feature.voice.RealtimePreflightClient
import ai.opencode.feature.voice.RealtimePreflightError
import ai.opencode.feature.voice.ServerVoiceClient
import ai.opencode.feature.voice.ServerVoiceModelsAnswer
import ai.opencode.feature.voice.ServerVoiceProvider
import ai.opencode.feature.voice.ServerVoiceSupport
import ai.opencode.feature.voice.VoiceCallSoundPack
import ai.opencode.feature.voice.VoiceDelegatorChoice
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceKeyFingerprint
import ai.opencode.feature.voice.VoiceKeySource
import ai.opencode.feature.voice.VoiceModelsCache
import ai.opencode.feature.voice.VoicePreviewClient
import ai.opencode.feature.voice.VoicePreviewError
import ai.opencode.feature.voice.VoiceProvider
import ai.opencode.feature.voice.VoiceRoute
import ai.opencode.feature.voice.VoiceServerChoice
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.feature.voice.voiceFailureDisplayText
import ai.opencode.server.CachedHomeSnapshot
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * The shared values are read live through [shared]; the reducer writes them only when they change.
 */
class VoiceSettingsFeature : Reducer<VoiceSettingsFeature.State, VoiceSettingsFeature.Action> {
    data class State(
        /** The server calls start from; the last one used when null. */
        val server: OpenCodeServer? = null,
        /** The connected server's sessions, for the Delegator page's pick (Home's, else its own load). */
        val sessions: List<OpenCodeSession> = emptyList(),
        val isEditingKey: Boolean = false,
        val revealsKey: Boolean = false,
        val isCheckingKey: Boolean = false,
        val keyVerificationError: String? = null,
        val previewingVoice: CallVoice? = null,
        val previewError: String? = null,
    ) {
        val serverVoiceSupportByServer: Map<String, ServerVoiceSupport> get() = shared(VoiceSharedKeys.serverVoiceSupportByServer).value
        val savedServer: OpenCodeServer? get() = shared(ServerSharedKeys.savedServer).value
        val savedServers: List<OpenCodeServer> get() = shared(ServerSharedKeys.savedServers).value
        val cachedHomeSnapshots: Map<String, CachedHomeSnapshot> get() = shared(ServerSharedKeys.cachedHomeSnapshots).value
        val voiceSessionDelegatorExperiment: Boolean get() = shared(ExperimentalFeature.voiceSessionDelegator.key).value
        val voiceDelegatorChoice: VoiceDelegatorChoice get() = shared(VoiceSharedKeys.voiceDelegatorChoice).value
        /** Each saved server's voice models, the connected one's too, by `persistenceID`, as it last answered. */
        val voiceModelsByServer: Map<String, ServerVoiceModelsAnswer> get() = shared(VoiceSharedKeys.voiceModelsByServer).value
        val callSoundPack: VoiceCallSoundPack get() = shared(VoiceSharedKeys.callSoundPack).value
        val serverVoiceProvider: ServerVoiceProvider get() = shared(VoiceSharedKeys.serverVoiceProvider).value
        val voiceServer: VoiceServerChoice get() = shared(VoiceSharedKeys.voiceServerChoice).value
        val voiceModeEnabled: Boolean get() = shared(SharedKeys.voiceModeEnabled).value
        val voiceEngine: VoiceEngine get() = shared(VoiceSharedKeys.voiceEngine).value
        val callVoice: CallVoice get() = shared(VoiceSharedKeys.callVoice).value
        val raiseToTalk: Boolean get() = shared(SharedKeys.raiseToTalk).value
        val openAIAPIKey: String? get() = shared(SharedKeys.openAIAPIKey).value
        val xaiAPIKey: String? get() = shared(SharedKeys.xaiAPIKey).value
        val verifiedKeyFingerprint: String? get() = shared(SharedKeys.verifiedVoiceKeyFingerprint).value

        val activeProvider: VoiceProvider get() = voiceEngine.provider
        /** The model alone while Voice Provider names who runs it. */
        val modelRowValue: String get() = if (showsProviderRow) voiceEngine.title else voiceEngine.providerModelTitle

        /**
         * The Voice Provider row stands in for the key row. Grok always uses this phone's xAI key,
         * so it keeps its key row.
         */
        val showsProviderRow: Boolean get() = voiceEngine != VoiceEngine.grok

        val serverVoiceSupport: ServerVoiceSupport get() = broker.support(serverVoiceSupportByServer)

        val route: VoiceRoute
            get() = broker.route(
                engine = voiceEngine,
                support = serverVoiceSupport,
                hasOpenAIKey = hasOpenAIKey,
                hasXAIKey = !xaiAPIKey.orEmpty().trim().isEmpty(),
            )

        val keyRowTitle: String get() = keySource.rowTitle

        val activeAPIKey: String get() = voiceEngine.apiKey(openAIAPIKey, xaiAPIKey).orEmpty().trim()

        val isActiveKeyVerified: Boolean
            get() = voiceEngine.hasVerifiedAPIKey(openAIAPIKey, xaiAPIKey, verifiedKeyFingerprint)

        val keyStatus: VoiceKeyStatus
            get() {
                when (val source = keySource) {
                    is VoiceKeySource.Server -> return VoiceKeyStatus.OnServer(source.providerName)
                    VoiceKeySource.OpenCode -> return VoiceKeyStatus.OnServer("OpenCode")
                    is VoiceKeySource.Phone -> Unit
                }
                if (activeAPIKey.isEmpty()) return VoiceKeyStatus.Missing
                if (isCheckingKey) return VoiceKeyStatus.Checking
                keyVerificationError?.let { return VoiceKeyStatus.Invalid(it) }
                if (isActiveKeyVerified) return VoiceKeyStatus.Verified
                return VoiceKeyStatus.Saved
            }

        val keyBadgeTitle: String get() = keyStatus.badgeTitle
        val canPreviewVoices: Boolean get() = activeAPIKey.isNotEmpty()
        val previewDisabledHint: String?
            get() = if (canPreviewVoices) null else localized(R.string.settings_voice_preview_disabled_hint, activeProvider.displayName)
    }

    sealed interface Action {
        data object Task : Action
        data class VoiceModeEnabledChanged(val on: Boolean) : Action
        data class VoiceEngineChanged(val engine: VoiceEngine) : Action
        data object RouteChanged : Action
        data object KeyRowTapped : Action
        data class ApiKeyEdited(val value: String) : Action
        data object ToggleRevealKeyTapped : Action
        data object VerifyKeyTapped : Action
        data class KeyVerificationFinished(val key: String, val model: String, val result: Result<String>) : Action
        data class CallVoiceSelected(val voice: CallVoice) : Action
        data class PreviewVoiceTapped(val voice: CallVoice) : Action
        data class VoicePreviewFinished(val voice: CallVoice) : Action
        data class VoicePreviewFailed(val voice: CallVoice) : Action
        data object VoicePickerDisappeared : Action
        data class RaiseToTalkChanged(val on: Boolean) : Action
        data class CallSoundPackSelected(val pack: VoiceCallSoundPack) : Action
        data object ProviderPageAppeared : Action
        data class VoiceModelsLoaded(val serverID: String, val asked: Int, val answer: ServerVoiceModelsAnswer) : Action
        data class VoiceDelegatorChoiceChanged(val choice: VoiceDelegatorChoice) : Action
        data object DelegatorPageAppeared : Action
        data class DelegatorSessionsLoaded(val sessions: List<OpenCodeSession>) : Action
    }

    internal sealed interface CancelID {
        data object VerifyKey : CancelID
        data object VoicePreview : CancelID
        data class VoiceModels(val serverID: String) : CancelID
        data object DelegatorSessions : CancelID
    }

    private val clock by Dependency(Clock)
    private val realtimePreflight by Dependency(RealtimePreflightClient)
    private val voicePreview by Dependency(VoicePreviewClient)
    internal val serverVoice by Dependency(ServerVoiceClient)
    private val openCodeClient by Dependency(OpenCodeClient)

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.Task -> {
            val voiceServers = checkVoiceServers(listOfNotNull(state.connectedServer))
            if (!state.voiceModeEnabled || !state.keySource.isPhoneKey) {
                Reduced(state, voiceServers)
            } else {
                val verified = verifyCurrentKey(state, debounce = true)
                Reduced(verified.state, Effect.merge(voiceServers, verified.effect))
            }
        }
        is Action.VoiceModeEnabledChanged -> {
            shared(SharedKeys.voiceModeEnabled).set(action.on)
            if (!action.on) {
                val next = state.copy(isEditingKey = false, revealsKey = false, isCheckingKey = false, keyVerificationError = null)
                val stopped = stopPreview(next)
                Reduced(stopped.state, Effect.merge(Effect.cancel(CancelID.VerifyKey), stopped.effect))
            } else if (!state.keySource.isPhoneKey) {
                Reduced(state)
            } else {
                verifyCurrentKey(state, debounce = true)
            }
        }
        is Action.VoiceEngineChanged -> {
            shared(SharedKeys.voiceEngineMigratedToGPTLive).set(true)
            val previousProvider = state.activeProvider
            shared(VoiceSharedKeys.voiceEngine).set(action.engine)
            var next = state
            if (previousProvider != action.engine.provider) next = next.copy(revealsKey = false)
            next = next.copy(isCheckingKey = false, keyVerificationError = null, previewError = null)
            val stopped = stopPreview(next)
            if (!stopped.state.voiceModeEnabled || !stopped.state.keySource.isPhoneKey) {
                Reduced(stopped.state, Effect.merge(Effect.cancel(CancelID.VerifyKey), stopped.effect))
            } else {
                val verified = verifyCurrentKey(stopped.state, debounce = true)
                Reduced(verified.state, Effect.merge(stopped.effect, verified.effect))
            }
        }
        Action.RouteChanged ->
            if (!state.keySource.isPhoneKey) {
                Reduced(
                    state.copy(isEditingKey = false, revealsKey = false, isCheckingKey = false, keyVerificationError = null),
                    Effect.cancel(CancelID.VerifyKey),
                )
            } else {
                Reduced(state)
            }
        Action.KeyRowTapped ->
            if (!state.keySource.isPhoneKey) {
                Reduced(state)
            } else {
                val editing = !state.isEditingKey
                Reduced(state.copy(isEditingKey = editing, revealsKey = if (editing) state.revealsKey else false))
            }
        is Action.ApiKeyEdited -> apiKeyEdited(state, action.value)
        Action.ToggleRevealKeyTapped -> Reduced(state.copy(revealsKey = !state.revealsKey))
        Action.VerifyKeyTapped ->
            if (state.activeAPIKey.isEmpty() || state.isCheckingKey) {
                Reduced(state)
            } else {
                verifyCurrentKey(state, debounce = false)
            }
        is Action.KeyVerificationFinished ->
            if (state.activeAPIKey != action.key || state.voiceEngine.modelID != action.model) {
                Reduced(state)
            } else {
                action.result.fold(
                    onSuccess = { fingerprint ->
                        shared(SharedKeys.verifiedVoiceKeyFingerprint).set(fingerprint)
                        Reduced(state.copy(isCheckingKey = false, keyVerificationError = null))
                    },
                    onFailure = { error -> Reduced(state.copy(isCheckingKey = false, keyVerificationError = error.message?.let { voiceFailureDisplayText(it) })) },
                )
            }
        is Action.CallVoiceSelected -> {
            if (state.callVoice != action.voice) shared(VoiceSharedKeys.callVoice).set(action.voice)
            Reduced(state)
        }
        is Action.PreviewVoiceTapped -> previewVoice(state, action.voice)
        is Action.VoicePreviewFinished ->
            Reduced(if (state.previewingVoice == action.voice) state.copy(previewingVoice = null) else state)
        is Action.VoicePreviewFailed -> Reduced(
            state.copy(
                previewingVoice = if (state.previewingVoice == action.voice) null else state.previewingVoice,
                previewError = VoicePreviewError.Failed.errorDescription,
            ),
        )
        Action.VoicePickerDisappeared -> stopPreview(state.copy(previewError = null))
        is Action.RaiseToTalkChanged -> {
            if (state.raiseToTalk != action.on) shared(SharedKeys.raiseToTalk).set(action.on)
            Reduced(state)
        }
        is Action.CallSoundPackSelected -> {
            if (state.callSoundPack != action.pack) shared(VoiceSharedKeys.callSoundPack).set(action.pack)
            Reduced(state)
        }
        Action.ProviderPageAppeared -> Reduced(state, checkVoiceServers(state.otherSavedServers))
        is Action.VoiceModelsLoaded -> {
            VoiceModelsCache.record(action.answer, action.serverID, action.asked)
            Reduced(state)
        }
        is Action.VoiceDelegatorChoiceChanged -> {
            if (state.voiceDelegatorChoice != action.choice) shared(VoiceSharedKeys.voiceDelegatorChoice).set(action.choice)
            Reduced(state)
        }
        Action.DelegatorPageAppeared -> {
            val server = state.connectedServer
            if (server == null) {
                Reduced(state)
            } else {
                val client = openCodeClient
                Reduced(
                    state,
                    Effect.run<Action> { send ->
                        val snapshot = try {
                            client.homeSnapshot(server)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            return@run
                        }
                        send(Action.DelegatorSessionsLoaded(snapshot.sessions))
                    }.cancellable(CancelID.DelegatorSessions, cancelInFlight = true),
                )
            }
        }
        is Action.DelegatorSessionsLoaded -> Reduced(state.copy(sessions = action.sessions))
    }

    private fun apiKeyEdited(state: State, rawValue: String): Reduced<State, Action> {
        // The selected model's provider: Your OpenAI Account edits it while a server takes calls too.
        val trimmed = rawValue.trim()
        val saved = trimmed.ifEmpty { null }
        when (state.activeProvider) {
            VoiceProvider.openAI -> if (state.openAIAPIKey != saved) shared(SharedKeys.openAIAPIKey).set(saved)
            VoiceProvider.xAI -> if (state.xaiAPIKey != saved) shared(SharedKeys.xaiAPIKey).set(saved)
        }
        val next = state.copy(isCheckingKey = false, keyVerificationError = null, previewError = null)
        if (saved == null) {
            val stopped = stopPreview(next)
            return Reduced(stopped.state, Effect.merge(Effect.cancel(CancelID.VerifyKey), stopped.effect))
        }
        if (!next.voiceModeEnabled) return Reduced(next, Effect.cancel(CancelID.VerifyKey))
        return verifyCurrentKey(next, debounce = true)
    }

    private fun previewVoice(state: State, voice: CallVoice): Reduced<State, Action> {
        if (!state.canPreviewVoices) return Reduced(state)
        if (state.previewingVoice == voice) return stopPreview(state)
        val provider = state.activeProvider
        val apiKey = state.activeAPIKey
        val preview = voicePreview
        return Reduced(
            state.copy(previewingVoice = voice, previewError = null),
            Effect.run<Action> { send ->
                try {
                    preview.play(voice, provider, apiKey)
                    send(Action.VoicePreviewFinished(voice))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    send(Action.VoicePreviewFailed(voice))
                }
            }.cancellable(CancelID.VoicePreview, cancelInFlight = true),
        )
    }

    private fun stopPreview(state: State): Reduced<State, Action> {
        if (state.previewingVoice == null) return Reduced(state)
        val preview = voicePreview
        return Reduced(
            state.copy(previewingVoice = null),
            Effect.merge(Effect.cancel(CancelID.VoicePreview), Effect.run { preview.stop() }),
        )
    }

    private fun verifyCurrentKey(state: State, debounce: Boolean): Reduced<State, Action> {
        val key = state.activeAPIKey
        val model = state.voiceEngine.modelID
        if (key.isEmpty()) {
            return Reduced(state.copy(isCheckingKey = false, keyVerificationError = null), Effect.cancel(CancelID.VerifyKey))
        }
        val fingerprint = VoiceKeyFingerprint.make(key = key, model = model)
        if (debounce && state.verifiedKeyFingerprint == fingerprint) {
            return Reduced(state.copy(isCheckingKey = false, keyVerificationError = null), Effect.cancel(CancelID.VerifyKey))
        }
        val clock = clock
        val preflight = realtimePreflight
        return Reduced(
            state.copy(isCheckingKey = !debounce, keyVerificationError = null),
            Effect.run<Action> { send ->
                try {
                    if (debounce) clock.sleep(800.milliseconds)
                    preflight.verify(key, model)
                    send(Action.KeyVerificationFinished(key, model, Result.success(fingerprint)))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: RealtimePreflightError) {
                    send(Action.KeyVerificationFinished(key, model, Result.failure(error)))
                } catch (error: Exception) {
                    send(Action.KeyVerificationFinished(key, model, Result.failure(RealtimePreflightError.Service(error.message.orEmpty()))))
                }
            }.cancellable(CancelID.VerifyKey, cancelInFlight = true),
        )
    }
}
