package ai.opencode.feature.system.share

import ai.opencode.feature.system.R
import ai.opencode.feature.system.intents.SystemActionActivity
import ai.opencode.feature.system.notifications.SystemNotificationChannels
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.UUID

/**
 * The share sheet's local notifications. Tapping "sent" opens the session it went to; "continue"
 * opens the app, which finds the share waiting. They show only when the person has allowed
 * OpenCode's notifications. Blocking; call off the main thread.
 */
object ShareNotifier {
    fun isAllowed(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** "Sent": tapping it opens the session it went to. */
    fun sent(context: Context, title: String, body: String, serverKey: String, sessionID: String) {
        val tap = SystemActionActivity.shareIntent(context, serverKey, sessionID)
        post(context, title, body, tap, group = ShareConversation.identifier(serverKey, sessionID))
    }

    /**
     * Continue in OpenCode when the app couldn't be opened from the sheet: tapping it opens the
     * app, which finds the share waiting.
     */
    fun continueInApp(context: Context, title: String): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return false
        return post(context, title, context.getString(R.string.system_share_continue_notification_body), launch, group = null)
    }

    @SuppressLint("MissingPermission") // Checked: areNotificationsEnabled covers POST_NOTIFICATIONS.
    private fun post(context: Context, title: String, body: String, tap: Intent, group: String?): Boolean {
        if (!isAllowed(context)) return false
        SystemNotificationChannels.ensure(context)
        val id = UUID.randomUUID().hashCode()
        val pending = PendingIntent.getActivity(
            context, id, tap.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, SystemNotificationChannels.SHARE)
            .setSmallIcon(R.drawable.ic_opencode_mark)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .apply { if (group != null) setGroup(group) }
            .build()
        return runCatching { NotificationManagerCompat.from(context).notify(id, notification) }.isSuccess
    }
}
