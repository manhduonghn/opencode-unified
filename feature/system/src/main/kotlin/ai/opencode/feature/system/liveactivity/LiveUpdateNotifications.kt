package ai.opencode.feature.system.liveactivity

import ai.opencode.core.strings.localized
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.system.R
import ai.opencode.feature.system.notifications.SystemNotificationChannels
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How a call and a hands-free recording read away from the app. Android 16 draws a promoted ongoing
 * notification (a Live Update) in the status bar chip, the shade and on the lock screen. Same
 * states, copy and buttons; Material visuals.
 */
object LiveUpdateNotifications {
    const val VOICE_CALL_ID = 4101
    const val DICTATION_ID = 4102

    /**
     * One state of the call, after staleness (an activity the app stopped refreshing reads as
     * ended; Android removes it at its timeout instead).
     */
    data class CallDisplay(val state: VoiceCallActivityAttributes.ContentState.Phase, val isMuted: Boolean, val startedAt: Instant?) {
        constructor(content: VoiceCallActivityAttributes.ContentState, isStale: Boolean = false) : this(
            if (isStale) VoiceCallActivityAttributes.ContentState.Phase.Ended else content.phase,
            content.isMuted,
            content.startedAt,
        )

        /** The system counts the timer from the first connect. */
        val timerStart: Instant? get() = if (state == VoiceCallActivityAttributes.ContentState.Phase.Live) startedAt else null

        val status: String
            get() = when (state) {
                VoiceCallActivityAttributes.ContentState.Phase.Connecting -> localized(R.string.system_call_connecting)
                VoiceCallActivityAttributes.ContentState.Phase.Reconnecting -> localized(R.string.system_call_reconnecting)
                VoiceCallActivityAttributes.ContentState.Phase.Live -> ""
                VoiceCallActivityAttributes.ContentState.Phase.Ended -> localized(R.string.system_call_ended)
            }
    }

    data class RecordingDisplay(val title: String, val startedAt: Instant, val isRecording: Boolean) {
        constructor(content: DictationActivityAttributes.ContentState, isStale: Boolean = false) :
            this(content.title, content.startedAt, !isStale)
    }

    /**
     * The call's Live Update: the mark, the timer or where the call is, then Mute and End. Past it
     * the notification goes, so a call the app stopped refreshing never reads as live.
     */
    fun voiceCall(context: Context, activityID: String, content: VoiceCallActivityAttributes.ContentState, timeout: Duration): Notification {
        SystemNotificationChannels.ensure(context)
        val call = CallDisplay(content)
        val ended = call.state == VoiceCallActivityAttributes.ContentState.Phase.Ended
        val builder = NotificationCompat.Builder(context, SystemNotificationChannels.VOICE_CALL)
            .setSmallIcon(R.drawable.ic_opencode_mark)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(call.timerStart != null)
            .setOngoing(!ended)
            .setRequestPromotedOngoing(!ended)
            .setContentTitle(if (call.timerStart != null) "OpenCode" else call.status)
            .setContentIntent(openApp(context))
            .setDeleteIntent(LiveActivityActionReceiver.dismissed(context, LiveActivityActionReceiver.Kind.VoiceCall, activityID))
            .setTimeoutAfter(timeout.inWholeMilliseconds)
        call.timerStart?.let { start ->
            builder.setWhen(start.toEpochMilli()).setUsesChronometer(true)
        }
        if (!ended && call.timerStart == null) builder.setShortCriticalText(call.status)
        if (call.isMuted && !ended) builder.setContentText(context.getString(R.string.system_call_muted))
        if (!ended) {
            val progress = NotificationCompat.ProgressStyle()
                .setStyledByProgress(false)
                .setProgressStartIcon(IconCompat.createWithResource(context, R.drawable.ic_opencode_mark))
            if (call.timerStart == null) {
                progress.setProgressIndeterminate(true)
            } else {
                progress.addProgressSegment(NotificationCompat.ProgressStyle.Segment(1)).setProgress(1)
            }
            builder.setStyle(progress)
            builder.addAction(
                NotificationCompat.Action.Builder(
                    IconCompat.createWithResource(context, if (call.isMuted) SFSymbol.MicSlashFill.drawable else SFSymbol.MicFill.drawable),
                    if (call.isMuted) context.getString(R.string.system_call_unmute) else context.getString(R.string.system_call_mute),
                    LiveActivityActionReceiver.command(context, LiveActivityActionReceiver.ACTION_TOGGLE_MUTE),
                ).build(),
            )
            builder.addAction(
                NotificationCompat.Action.Builder(
                    IconCompat.createWithResource(context, SFSymbol.PhoneDownFill.drawable),
                    context.getString(R.string.system_call_end),
                    LiveActivityActionReceiver.command(context, LiveActivityActionReceiver.ACTION_END_CALL),
                ).build(),
            )
        }
        return builder.build()
    }

    /**
     * A hands-free recording while the app is away: a still mic, the session the words go to,
     * how long it has been recording, and Stop, which puts the words in that session's draft.
     * No transcript: the lock screen is for anyone to see.
     */
    fun dictation(context: Context, activityID: String, content: DictationActivityAttributes.ContentState, timeout: Duration): Notification {
        SystemNotificationChannels.ensure(context)
        val recording = RecordingDisplay(content)
        return NotificationCompat.Builder(context, SystemNotificationChannels.DICTATION)
            .setSmallIcon(SFSymbol.MicFill.drawable)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setRequestPromotedOngoing(true)
            .setContentTitle(recording.title)
            .setContentText(if (recording.isRecording) context.getString(R.string.system_recording_active) else context.getString(R.string.system_recording_stopped))
            .setWhen(recording.startedAt.toEpochMilli())
            .setShowWhen(true)
            .setUsesChronometer(recording.isRecording)
            .setContentIntent(openApp(context))
            .setDeleteIntent(LiveActivityActionReceiver.dismissed(context, LiveActivityActionReceiver.Kind.Dictation, activityID))
            .setTimeoutAfter(timeout.inWholeMilliseconds)
            .setStyle(NotificationCompat.ProgressStyle().setProgressIndeterminate(true).setStyledByProgress(false))
            .addAction(
                NotificationCompat.Action.Builder(
                    IconCompat.createWithResource(context, SFSymbol.StopFill.drawable),
                    context.getString(R.string.system_recording_stop),
                    LiveActivityActionReceiver.command(context, LiveActivityActionReceiver.ACTION_STOP_DICTATION),
                ).build(),
            )
            .build()
    }

    /** Tapping a Live Update opens the app. */
    private fun openApp(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Call red (#FF4245), for the End button where Material lets an app colour it. */
    const val RED: Int = 0xFFFF4245.toInt()

    /** How long an ended call's “Call ended” lingers. */
    val endedLinger: Duration = 3.seconds
}
