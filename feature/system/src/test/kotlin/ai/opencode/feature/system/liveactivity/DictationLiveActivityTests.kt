package ai.opencode.feature.system.liveactivity

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DictationLiveActivityTests {
    @Test fun theStopIntentSendsItsCommand() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        // The manifest's receiver, registered here (library unit tests don't merge manifests).
        context.registerReceiver(
            LiveActivityActionReceiver(),
            IntentFilter(LiveActivityActionReceiver.ACTION_STOP_DICTATION),
            Context.RECEIVER_NOT_EXPORTED,
        )
        val command = async(start = CoroutineStart.UNDISPATCHED) { LiveActivityCommands.dictation.stream().first() }
        repeat(5) { yield() }
        context.sendBroadcast(Intent(LiveActivityActionReceiver.ACTION_STOP_DICTATION).setPackage(context.packageName))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(DictationCommand.Stop, command.await())
    }
}
