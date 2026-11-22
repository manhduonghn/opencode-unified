package ai.opencode.feature.voice

import ai.opencode.core.sharing.FileDirectory
import ai.opencode.core.sharing.SharedKey

/**
 * The voice-typed shared keys, declared here because their value types belong to Voice.
 * [VoiceEngine] (`LiveProtocol.kt`), [ServerVoiceProvider], [ServerVoiceSupport] and
 * [VoiceServerChoice] (`ServerVoiceClient.kt`), [CallVoice] (`CallVoice.kt`),
 * [VoiceTranscriptRecord] (`VoiceTranscript.kt`). Bool and key-chain voice keys
 * (`voiceModeEnabled`, `voiceEngineMigratedToGPTLive`, `openAIAPIKey`, `xaiAPIKey`, …) live in
 * `:core`'s `SharedKeys`.
 */
object VoiceSharedKeys {
    /** Voice engine for new calls. Defaults to GPT-Live, including for users upgrading from builds that only had Realtime. */
    val voiceEngine = SharedKey.appStorage("voice-engine", VoiceEngine.gptLive)

    /** The provider a server brokers calls on (Voice Provider). Defaults to OpenAI. */
    val serverVoiceProvider = SharedKey.appStorage("server-voice-provider", ServerVoiceProvider.openAI)

    /** Whether each connected server (by `persistenceID`) offers `POST /api/experimental/voice/session`. */
    val serverVoiceSupportByServer =
        SharedKey.inMemory<Map<String, ServerVoiceSupport>>("server-voice-support", emptyMap())

    /** The voice new calls speak with. A call on an engine that doesn't offer it uses the default instead (`CallVoice.resolved(engine)`). */
    val callVoice = SharedKey.appStorage("voice-call-voice", CallVoice.default)

    /** Settings › Voice › Call Sounds. */
    val callSoundPack = SharedKey.appStorage("call-sound-pack", VoiceCallSoundPack.default)

    /** Settings › Voice › Voice Provider. Automatic by default. */
    val voiceServerChoice = SharedKey.appStorage<VoiceServerChoice>("voice-server", VoiceServerChoice.Automatic)

    /** Settings › Voice › Delegator. Built-in by default. */
    val voiceDelegatorChoice = SharedKey.appStorage<VoiceDelegatorChoice>("voice-delegator-choice", VoiceDelegatorChoice.BuiltIn)

    /** Cached Automatic `"Voice"` delegator session ID per server (`srv_…` → `ses_…`). */
    val voiceDelegatorSessionIDs = SharedKey.fileStorage<Map<String, String>>(
        FileDirectory.Documents,
        "voice-delegator-sessions.json",
        emptyMap(),
    )

    /**
     * Each saved server's last voice models answer, by `persistenceID`. Settings › Voice asks;
     * calls read it to choose automatically.
     */
    val voiceModelsByServer =
        SharedKey.inMemory<Map<String, ServerVoiceModelsAnswer>>("voice-models-by-server", emptyMap())

    /**
     * How many times each server (by `persistenceID`) reconnected: an answer asked before the
     * latest says nothing of the server now.
     */
    val voiceModelsEpochs = SharedKey.inMemory<Map<String, Int>>("voice-models-epochs", emptyMap())

    /** Phone-local voice transcripts keyed by `serverID:sessionID`. */
    val voiceTranscripts = SharedKey.fileStorage<Map<String, VoiceTranscriptRecord>>(
        FileDirectory.Documents,
        "voice-transcripts.json",
        emptyMap(),
    )
}
