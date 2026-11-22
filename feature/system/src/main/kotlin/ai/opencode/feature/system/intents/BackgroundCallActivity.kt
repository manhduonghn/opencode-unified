package ai.opencode.feature.system.intents

import android.app.Activity
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Start Voice Call in Background's door ([StartBackgroundVoiceCallIntent]): a see-through
 * activity that finishes at once, so nothing of the app shows, while the intent runs in the
 * process. Its shortcut and App Action name a session the way a widget does
 * ([SystemActionActivity.EXTRA_CONTROL_TARGET]; none is Last Viewed Session).
 */
class BackgroundCallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent.getStringExtra(SystemActionActivity.EXTRA_CONTROL_TARGET)
        val session = target?.let { SessionEntity(it, "", null) }
        val context = applicationContext
        scope.launch {
            StartBackgroundVoiceCallIntent.perform(session) { SystemActionActivity.openApp(context) }
        }
        finish()
    }

    private companion object {
        /** Outlives the activity, as an intent's `perform` outlives its invocation. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
