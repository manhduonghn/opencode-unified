package ai.opencode.feature.composer

import ai.opencode.core.log.AppLog
import ai.opencode.core.ui.SFSymbol
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keeps a recording's microphone alive while the app is away. Android silences a background app's
 * microphone unless a microphone foreground service runs, so [LiveCapture] starts this one with
 * every recording (the app is on screen then: a press started it) and stops it with the recorder.
 *
 * Its notification is the recording's Live Update slot: it posts under the id and channel the
 * system area's `LiveUpdateNotifications.dictation` uses (`DICTATION_ID` 4102, channel
 * `dictation`), so when a hands-free recording raises its Live Update the service's notification
 * becomes it, and there is never a second one. Until then (a press held on screen) Android defers a
 * new foreground service's notification by ten seconds, so a short dictation shows nothing. The ids
 * are repeated here because `:feature:system` sits beside this module, not under it.
 */
class DictationRecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                // A microphone service may only start with RECORD_AUDIO granted; the recording
                // itself reports the denial.
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    AppLog.voice.notice("dictation_service_skipped reason=microphone_denied")
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    AppLog.voice.info("dictation_service_started")
                } catch (error: Exception) {
                    // Started too late (the app already left): the recording goes on until
                    // Android silences it, which ends it like an interruption.
                    AppLog.voice.notice("dictation_service_failed error=${error.javaClass.simpleName}")
                    stopSelf()
                }
            }
        }
        // Never resurrect a microphone after process death.
        return START_NOT_STICKY
    }

    /** The Live Update already showing when the service comes up, or a quiet “Recording”. */
    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.activeNotifications.firstOrNull { it.id == NOTIFICATION_ID }?.let { return it.notification }
        if (manager.getNotificationChannel(CHANNEL) == null) {
            // Same channel the system area creates (`SystemNotificationChannels.DICTATION`).
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.composer_dictation_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
            )
        }
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(SFSymbol.MicFill.drawable)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentTitle("OpenCode")
            .setContentText(getString(R.string.composer_dictation_notification_text))
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            builder.setContentIntent(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        return builder.build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        /** `LiveUpdateNotifications.DICTATION_ID` (`:feature:system`). */
        private const val NOTIFICATION_ID = 4102
        /** `SystemNotificationChannels.DICTATION` (`:feature:system`). */
        private const val CHANNEL = "dictation"
        private const val STOP = "ai.opencode.dictation.STOP"

        private val lock = Any()
        private var isRunning = false

        /** A recording's microphone went live. Call while the app is on screen. */
        fun start(context: Context) {
            synchronized(lock) {
                if (isRunning) return
                try {
                    context.startForegroundService(Intent(context, DictationRecordingService::class.java))
                    isRunning = true
                } catch (error: Exception) {
                    AppLog.voice.notice("dictation_service_failed error=${error.javaClass.simpleName}")
                }
            }
        }

        /** The recording's microphone stopped. In order after [start], so it always follows its `startForeground`. */
        fun stop(context: Context) {
            synchronized(lock) {
                if (!isRunning) return
                isRunning = false
                try {
                    context.startService(Intent(context, DictationRecordingService::class.java).setAction(STOP))
                } catch (_: Exception) {
                    // Not allowed to start from the background: the foreground service is gone already.
                    context.stopService(Intent(context, DictationRecordingService::class.java))
                }
            }
        }
    }
}
