package ai.opencode.feature.settings

import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.experiments.key
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sound.SoundEffectsClient
import ai.opencode.core.sound.soundEffectsSend
import ai.opencode.core.sound.soundEffectsVolume
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeServer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SoundEffectsTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test fun settingsPrepareTheSoundsAndSampleTheVolume() = runTest {
        val shared = SettingsTestShared()
        val log = Locked(listOf<String>())
        val haptics = HapticsRecorder()
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val store = TestStore(HomeSettingsFeature.State(server = server), HomeSettingsFeature(), this) {
            this[SharedStore] = shared.store
            this[HapticsClient] = haptics.client
            this[SoundEffectsClient] = SoundEffectsClient(
                play = { effect -> log.update { it + "sound.${effect.rawValue}" } },
                prepare = { log.update { it + "sound.prepare" } },
            )
        }

        store.send(HomeSettingsFeature.Action.ExperimentalFeatureChanged(ExperimentalFeature.soundEffects, true))
        assertEquals(listOf("sound.prepare"), log.value)
        assertTrue(shared.get(ExperimentalFeature.soundEffects.key))

        store.send(HomeSettingsFeature.Action.SoundEffectsVolumeChanged(0.4))
        assertEquals(listOf("sound.prepare", "sound.replyDone"), log.value)
        assertEquals(0.4, shared.get(SharedKeys.soundEffectsVolume), 0.0)
        store.send(HomeSettingsFeature.Action.SoundEffectsVolumeChanged(0.4))
        assertEquals("an unchanged level plays no sample", listOf("sound.prepare", "sound.replyDone"), log.value)
        store.send(HomeSettingsFeature.Action.SoundEffectsVolumeChanged(3.0))
        assertEquals(1.0, shared.get(SharedKeys.soundEffectsVolume), 0.0)

        store.send(HomeSettingsFeature.Action.SoundEffectsSendChanged(true))
        assertTrue(shared.get(SharedKeys.soundEffectsSend))

        store.send(HomeSettingsFeature.Action.ExperimentalFeatureChanged(ExperimentalFeature.soundEffects, false))
        assertEquals(listOf("sound.prepare", "sound.replyDone", "sound.replyDone"), log.value)
        assertEquals(emptyList<String>(), haptics.value)
        store.finish()
    }
}
