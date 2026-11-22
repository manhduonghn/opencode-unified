package ai.opencode.feature.system.liveactivity

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/** The Live Updates' buttons and a Live Update swiped away. */
class LiveActivityActionReceiver : BroadcastReceiver() {
    enum class Kind { VoiceCall, Dictation, SessionRun }

    data class Dismissal(val kind: Kind, val activityID: String)

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_END_CALL -> LiveActivityCommands.voiceCall.perform(VoiceCallCommand.End)
                    ACTION_TOGGLE_MUTE -> LiveActivityCommands.voiceCall.perform(VoiceCallCommand.ToggleMute)
                    ACTION_STOP_DICTATION -> LiveActivityCommands.dictation.perform(DictationCommand.Stop)
                    ACTION_DISMISSED -> {
                        val kind = intent.getStringExtra(EXTRA_KIND)?.let { name -> Kind.entries.firstOrNull { it.name == name } }
                        val id = intent.getStringExtra(EXTRA_ID)
                        if (kind != null && id != null) dismissals.emit(Dismissal(kind, id))
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_END_CALL = "ai.opencode.mobilecode.live.END_CALL"
        const val ACTION_TOGGLE_MUTE = "ai.opencode.mobilecode.live.TOGGLE_MUTE"
        const val ACTION_STOP_DICTATION = "ai.opencode.mobilecode.live.STOP_DICTATION"
        const val ACTION_DISMISSED = "ai.opencode.mobilecode.live.DISMISSED"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_ID = "id"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val mutableDismissals = MutableSharedFlow<Dismissal>(extraBufferCapacity = 8)
        private val dismissals get() = mutableDismissals

        /** Live Updates the user swiped away, as they happen. */
        val dismissalEvents: SharedFlow<Dismissal> get() = mutableDismissals

        fun command(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context, action.hashCode(),
                Intent(context, LiveActivityActionReceiver::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        fun dismissed(context: Context, kind: Kind, activityID: String): PendingIntent =
            PendingIntent.getBroadcast(
                context, kind.ordinal,
                Intent(context, LiveActivityActionReceiver::class.java).setAction(ACTION_DISMISSED)
                    .setData(Uri.parse("opencode-live://${kind.name}/$activityID"))
                    .putExtra(EXTRA_KIND, kind.name)
                    .putExtra(EXTRA_ID, activityID),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
