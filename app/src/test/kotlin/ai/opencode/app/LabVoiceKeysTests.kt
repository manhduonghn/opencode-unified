package ai.opencode.app

import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.experimental
import ai.opencode.core.sharing.shared
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.ServerSharedKeys
import android.app.Application
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The lab's launch extras seed the voice keys, and `NONE` clears one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LabVoiceKeysTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun seedsKeysAtLaunchAndNoneClears() {
        val shared = AppTestShared()
        shared.run {
            val openAI = shared(SharedKeys.openAIAPIKey)
            val xAI = shared(SharedKeys.xaiAPIKey)
            xAI.set("old")
            LabLaunch.apply(Intent().putExtra("lab-openai-key", "sk-lab").putExtra("lab-xai-key", "NONE"))
            assertEquals("sk-lab", openAI.value)
            assertNull(xAI.value)
            LabLaunch.apply(Intent())
            assertEquals("sk-lab", openAI.value)
        }
    }

    @Test
    fun savesTheLabServerAndSwitchesExperiments() {
        val shared = AppTestShared()
        shared.run {
            val inbox = shared(experimental(ExperimentalFeature.inboxSidebar))
            LabLaunch.apply(
                Intent().putExtra("lab-server-url", "http://10.0.2.2:4151").putExtra("lab-experiments-on", "inbox-sidebar,hide-models"),
            )
            assertEquals("http://10.0.2.2:4151", shared(ServerSharedKeys.savedServer).value?.url)
            assertEquals(1, shared(ServerSharedKeys.savedServers).value.size)
            assertEquals(true, inbox.value)
            assertEquals(true, shared(experimental(ExperimentalFeature.hideModels)).value)
            LabLaunch.apply(Intent().putExtra("lab-experiments-off", "inbox-sidebar"))
            assertEquals(false, inbox.value)
            assertEquals(true, shared(experimental(ExperimentalFeature.hideModels)).value)
        }
    }
}
