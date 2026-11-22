package ai.opencode.feature.settings

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.settings.VoiceSettingsFeature.Action
import ai.opencode.feature.voice.CallVoice
import ai.opencode.feature.voice.RealtimePreflightClient
import ai.opencode.feature.voice.ServerVoiceProvider
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceKeyFingerprint
import ai.opencode.feature.voice.VoicePreviewClient
import ai.opencode.feature.voice.VoicePreviewError
import ai.opencode.feature.voice.VoiceProvider
import ai.opencode.feature.voice.VoiceServerChoice
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.OpenCodeServer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CallVoiceTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")

    @Test fun anUnsupportedChoiceFallsBackToTheDefault() {
        assertEquals("Cedar", CallVoicePicker.rowValue(CallVoice.cedar, engine = VoiceEngine.realtime))
        assertEquals("Quartz", CallVoicePicker.rowValue(CallVoice.quartz, engine = VoiceEngine.gptLive))
        assertEquals("Marin · Quartz unavailable", CallVoicePicker.rowValue(CallVoice.quartz, engine = VoiceEngine.realtime))
        assertEquals("Another provider's voice goes unmentioned", "Eve", CallVoicePicker.rowValue(CallVoice.marin, engine = VoiceEngine.grok))
        assertEquals("Marin", CallVoicePicker.rowValue(CallVoice.rex, engine = VoiceEngine.realtime))
    }

    @Test fun keyRowSwitchesWithProviderAndReflectsMissingSavedVerifiedAndServerKey() = runTest {
        val clock = TestClock()
        val shared = SettingsTestShared()
        shared.set(SharedKeys.voiceEngineMigratedToGPTLive, true)
        shared.set(SharedKeys.voiceModeEnabled, true)
        shared.set(VoiceSharedKeys.voiceEngine, VoiceEngine.gptLive)
        shared.set(SharedKeys.openAIAPIKey, null)
        shared.set(SharedKeys.xaiAPIKey, null)
        shared.set(SharedKeys.verifiedVoiceKeyFingerprint, null)
        // This phone's key rows; the Voice Provider row names the provider.
        shared.set(VoiceSharedKeys.voiceServerChoice, VoiceServerChoice.Phone)

        // Gemini Live, below, runs on the server calls start from.
        val store = TestStore(VoiceSettingsFeature.State(server = server), VoiceSettingsFeature(), this) {
            this[SharedStore] = shared.store
            this[Clock] = clock
            this[RealtimePreflightClient] = RealtimePreflightClient(verify = { _, _ -> })
        }

        store.withDependencies {
            assertEquals("GPT-Live", store.state.modelRowValue)
            assertEquals("OpenAI Key", store.state.keyRowTitle)
            assertEquals(VoiceKeyStatus.Missing, store.state.keyStatus)
            assertEquals("Missing", store.state.keyBadgeTitle)
        }

        // Tap the key row to edit OpenAI's key; before verification finishes it shows Saved.
        store.send(Action.KeyRowTapped) { it.copy(isEditingKey = true) }
        store.send(Action.ApiKeyEdited("sk-openai-123"))
        assertEquals("sk-openai-123", shared.get(SharedKeys.openAIAPIKey))
        store.withDependencies {
            assertEquals("OpenAI Key", store.state.keyRowTitle)
            assertEquals(VoiceKeyStatus.Saved, store.state.keyStatus)
            assertEquals("Saved", store.state.keyBadgeTitle)
        }

        // Switching to Grok Voice switches the key row to xAI Key, which is still Missing.
        store.send(Action.VoiceEngineChanged(VoiceEngine.grok))
        assertEquals(VoiceEngine.grok, shared.get(VoiceSharedKeys.voiceEngine))
        store.withDependencies {
            assertEquals("xAI · Grok Voice", store.state.modelRowValue)
            assertEquals("xAI Key", store.state.keyRowTitle)
            assertEquals(VoiceKeyStatus.Missing, store.state.keyStatus)
            assertEquals("Missing", store.state.keyBadgeTitle)
        }

        // Enter and verify an xAI key; it becomes Verified for Grok Voice.
        store.send(Action.ApiKeyEdited("xai-key-456"))
        assertEquals("xai-key-456", shared.get(SharedKeys.xaiAPIKey))
        store.withDependencies { assertEquals(VoiceKeyStatus.Saved, store.state.keyStatus) }
        val xaiFingerprint = VoiceKeyFingerprint.make(key = "xai-key-456", model = VoiceEngine.grok.modelID)
        clock.advance(800.milliseconds)
        store.receive(Action.KeyVerificationFinished("xai-key-456", VoiceEngine.grok.modelID, Result.success(xaiFingerprint)))
        assertEquals(xaiFingerprint, shared.get(SharedKeys.verifiedVoiceKeyFingerprint))
        store.withDependencies {
            assertEquals(VoiceKeyStatus.Verified, store.state.keyStatus)
            assertEquals("Verified", store.state.keyBadgeTitle)
        }

        // Switching back to GPT-Live shows OpenAI Key (Saved, then Verified after preflight).
        store.send(Action.VoiceEngineChanged(VoiceEngine.gptLive))
        assertEquals(VoiceEngine.gptLive, shared.get(VoiceSharedKeys.voiceEngine))
        store.withDependencies {
            assertEquals("OpenAI Key", store.state.keyRowTitle)
            assertEquals(VoiceKeyStatus.Saved, store.state.keyStatus)
        }
        val openAIFingerprint = VoiceKeyFingerprint.make(key = "sk-openai-123", model = VoiceEngine.gptLive.modelID)
        clock.advance(800.milliseconds)
        store.receive(Action.KeyVerificationFinished("sk-openai-123", VoiceEngine.gptLive.modelID, Result.success(openAIFingerprint)))
        assertEquals(openAIFingerprint, shared.get(SharedKeys.verifiedVoiceKeyFingerprint))
        store.withDependencies { assertEquals(VoiceKeyStatus.Verified, store.state.keyStatus) }

        // With the server holding the key, the row becomes "Key on Your Server".
        shared.set(VoiceSharedKeys.voiceServerChoice, VoiceServerChoice.Automatic)
        shared.set(VoiceSharedKeys.serverVoiceProvider, ServerVoiceProvider.geminiLive)
        store.send(Action.RouteChanged) { it.copy(isEditingKey = false) }
        store.withDependencies {
            assertEquals("Key on Your Server", store.state.keyRowTitle)
            assertEquals("Gemini Live", store.state.keyBadgeTitle)
        }
        store.send(Action.KeyRowTapped)
        store.finish()
    }

    @Test fun previewRequiresAKeyPlaysOneAtATimeAndStopsOnTapOrDisappear() = runTest {
        val clock = TestClock()
        val played = Locked(listOf<Triple<CallVoice, VoiceProvider, String>>())
        val stopCount = Locked(0)
        val shared = SettingsTestShared()
        shared.set(SharedKeys.voiceModeEnabled, true)
        shared.set(VoiceSharedKeys.voiceEngine, VoiceEngine.gptLive)
        shared.set(SharedKeys.openAIAPIKey, null)
        shared.set(SharedKeys.xaiAPIKey, null)
        shared.set(SharedKeys.verifiedVoiceKeyFingerprint, null)

        val store = TestStore(VoiceSettingsFeature.State(), VoiceSettingsFeature(), this) {
            this[SharedStore] = shared.store
            this[Clock] = clock
            this[RealtimePreflightClient] = RealtimePreflightClient(verify = { _, _ -> })
            this[VoicePreviewClient] = VoicePreviewClient(
                play = { voice, provider, apiKey ->
                    played.update { it + Triple(voice, provider, apiKey) }
                    if (voice == CallVoice.coral) throw VoicePreviewError.Failed
                    clock.sleep(10.seconds)
                },
                stop = { stopCount.update { it + 1 } },
            )
        }

        // No key: preview is disabled with a hint and tapping is a no-op.
        store.withDependencies {
            assertFalse(store.state.canPreviewVoices)
            assertEquals("Add your OpenAI key to preview voices", store.state.previewDisabledHint)
        }
        store.send(Action.PreviewVoiceTapped(CallVoice.marin))
        assertTrue(played.value.isEmpty())

        // Save an OpenAI key: previewing Marin starts playback.
        val previewKeyFingerprint = VoiceKeyFingerprint.make(key = "sk-preview", model = VoiceEngine.gptLive.modelID)
        store.send(Action.ApiKeyEdited("sk-preview"))
        clock.advance(800.milliseconds)
        store.receive(Action.KeyVerificationFinished("sk-preview", VoiceEngine.gptLive.modelID, Result.success(previewKeyFingerprint)))
        assertEquals(previewKeyFingerprint, shared.get(SharedKeys.verifiedVoiceKeyFingerprint))
        store.withDependencies {
            assertTrue(store.state.canPreviewVoices)
            assertNull(store.state.previewDisabledHint)
        }

        store.send(Action.PreviewVoiceTapped(CallVoice.marin)) { it.copy(previewingVoice = CallVoice.marin) }
        // Starting Cedar while Marin plays cancels Marin and plays only Cedar.
        store.send(Action.PreviewVoiceTapped(CallVoice.cedar)) { it.copy(previewingVoice = CallVoice.cedar) }
        testScheduler.runCurrent()
        assertEquals(listOf(CallVoice.marin, CallVoice.cedar), played.value.map { it.first })
        assertEquals(VoiceProvider.openAI, played.value.first().second)
        assertEquals("sk-preview", played.value.first().third)

        // Tapping Cedar again while it plays stops it immediately.
        store.send(Action.PreviewVoiceTapped(CallVoice.cedar)) { it.copy(previewingVoice = null) }
        testScheduler.runCurrent()
        assertEquals(1, stopCount.value)

        // Starting a preview and leaving the page stops playback.
        store.send(Action.PreviewVoiceTapped(CallVoice.ash)) { it.copy(previewingVoice = CallVoice.ash) }
        store.send(Action.VoicePickerDisappeared) { it.copy(previewingVoice = null) }
        testScheduler.runCurrent()
        assertEquals(2, stopCount.value)

        // A failed preview shows "Couldn’t play preview".
        store.send(Action.PreviewVoiceTapped(CallVoice.coral)) { it.copy(previewingVoice = CallVoice.coral) }
        store.receive(Action.VoicePreviewFailed(CallVoice.coral)) { it.copy(previewingVoice = null, previewError = "Couldn’t play preview") }
        store.finish()
    }
}
