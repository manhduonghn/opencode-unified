package ai.opencode.feature.system.liveactivity

import ai.opencode.core.strings.localized
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.system.R
import ai.opencode.feature.system.liveactivity.SessionActivityAttributes.ContentState.Phase
import ai.opencode.feature.system.notifications.SystemNotificationChannels
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import java.time.Instant
import kotlin.time.Duration

/**
 * A run a send from this phone started, while the app is away: the session, what the agent is on
 * and for how long, then Done with the reply's opening line or Needs your input. Tapping it opens
 * the session, where a pending request docks. No message text while it works.
 *
 * Drawn as a promoted ongoing notification (a Live Update) in the status bar chip, the shade and on
 * the lock screen. The preview is `.privacySensitive()`: here the lock screen shows a public
 * version with the title and status only.
 */
object SessionRunLiveActivity {
    const val NOTIFICATION_ID = 4103

    fun notification(
        context: Context,
        activityID: String,
        attributes: SessionActivityAttributes,
        content: SessionActivityAttributes.ContentState,
        timeout: Duration,
        ended: Boolean = false,
    ): Notification {
        SystemNotificationChannels.ensure(context)
        val run = SessionRunDisplay(content)
        fun builder(showsPreview: Boolean): NotificationCompat.Builder {
            val builder = NotificationCompat.Builder(context, SystemNotificationChannels.SESSION_RUN)
                .setSmallIcon(run.symbol.drawable)
                .setColor(run.tint)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setOngoing(!ended)
                .setRequestPromotedOngoing(!ended)
                .setContentTitle(run.title)
                .setContentText(run.status)
                .setShortCriticalText(if (run.isCounting) null else run.shortStatus)
                .setWhen(run.startedAt.toEpochMilli())
                .setShowWhen(run.isCounting)
                .setUsesChronometer(run.isCounting)
                .setContentIntent(open(context, attributes))
                .setDeleteIntent(LiveActivityActionReceiver.dismissed(context, LiveActivityActionReceiver.Kind.SessionRun, activityID))
                .setTimeoutAfter(timeout.inWholeMilliseconds)
            val preview = run.preview
            if (showsPreview && preview != null) {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText("${run.status}\n$preview"))
            } else if (run.phase == Phase.working) {
                builder.setStyle(NotificationCompat.ProgressStyle().setProgressIndeterminate(true).setStyledByProgress(false))
            }
            return builder
        }
        return builder(showsPreview = true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(builder(showsPreview = false).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build())
            .build()
    }

    /** The session's `opencode://session` link, routed like a tapped notification. */
    private fun open(context: Context, attributes: SessionActivityAttributes): PendingIntent? {
        val url = attributes.url ?: return null
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, NOTIFICATION_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/**
 * What the row shows. An activity the app stopped refreshing while it worked reads as its last
 * known state, without a timer that would claim it's still going (Android removes it at its
 * timeout, so [isStale] is for parity and tests).
 */
data class SessionRunDisplay(
    val title: String,
    val phase: Phase,
    val step: String?,
    val preview: String?,
    val startedAt: Instant,
    val isStale: Boolean,
) {
    constructor(content: SessionActivityAttributes.ContentState, isStale: Boolean = false) : this(
        content.title,
        content.phase,
        content.step,
        content.preview,
        content.startedAt,
        isStale,
    )

    val isCounting: Boolean get() = phase == Phase.working && !isStale

    val status: String
        get() = when (phase) {
            Phase.working -> {
                val working = if (isStale) localized(R.string.system_session_last_seen_working) else localized(R.string.system_session_working)
                step?.let { localized(R.string.system_session_status_with_step, working, it) } ?: working
            }
            Phase.needsInput -> localized(R.string.system_session_needs_input)
            Phase.done -> localized(R.string.system_session_done)
            Phase.failed -> localized(R.string.system_session_failed)
        }

    val shortStatus: String
        get() = when (phase) {
            Phase.working -> localized(R.string.system_session_working)
            Phase.needsInput -> localized(R.string.system_session_input)
            Phase.done -> localized(R.string.system_session_done)
            Phase.failed -> localized(R.string.system_session_failed)
        }

    val symbol: SFSymbol
        get() = when (phase) {
            Phase.working -> SFSymbol.EllipsisCircleFill
            Phase.needsInput -> SFSymbol.ExclamationmarkBubbleFill
            Phase.done -> SFSymbol.CheckmarkCircleFill
            Phase.failed -> SFSymbol.XmarkCircleFill
        }

    /**
     * System colours (blue, orange, green, red; secondary when stale), as notification accent
     * colours.
     */
    val tint: Int
        get() = when (phase) {
            Phase.working -> if (isStale) SECONDARY else BLUE
            Phase.needsInput -> ORANGE
            Phase.done -> GREEN
            Phase.failed -> RED
        }

    private companion object {
        val BLUE = 0xFF0088FF.toInt()
        val ORANGE = 0xFFFF8D28.toInt()
        val GREEN = 0xFF34C759.toInt()
        val RED = 0xFFFF4245.toInt()
        val SECONDARY = 0xFF8E8E93.toInt()
    }
}
