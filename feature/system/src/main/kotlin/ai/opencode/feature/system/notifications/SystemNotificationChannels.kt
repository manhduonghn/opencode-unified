package ai.opencode.feature.system.notifications

import ai.opencode.feature.system.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Android notification channels for Live Updates and notifications. Created on first use
 * (idempotent). One per kind, so each can be silenced on its own.
 */
object SystemNotificationChannels {
    /** A voice call's Live Update. */
    const val VOICE_CALL = "voice-call"
    /** A hands-free recording's Live Update. */
    const val DICTATION = "dictation"
    /** A run sent from this phone, while the app is away. */
    const val SESSION_RUN = "session-run"
    /** A session finished or needs input. */
    const val SESSIONS = "sessions"
    /** The share sheet's “sent” and “continue” notices. */
    const val SHARE = "share"

    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(VOICE_CALL, context.getString(R.string.system_channel_voice_calls), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
                NotificationChannel(DICTATION, context.getString(R.string.system_channel_recording), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
                NotificationChannel(SESSION_RUN, context.getString(R.string.system_channel_session_runs), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
                NotificationChannel(SESSIONS, context.getString(R.string.system_channel_session_updates), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(SHARE, context.getString(R.string.system_channel_shared_items), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setShowBadge(false)
                },
            ),
        )
    }
}
