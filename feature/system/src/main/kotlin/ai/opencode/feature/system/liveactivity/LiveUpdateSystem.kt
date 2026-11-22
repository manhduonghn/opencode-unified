package ai.opencode.feature.system.liveactivity

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.log.AppLog
import ai.opencode.feature.system.notifications.SystemNotificationChannels
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * One notification ID per kind of Live Update, a fresh activity ID per start, and the stale date as
 * the notification's timeout. The state is tracked here rather than read back from the shade, whose
 * list lags a post.
 */
internal class LiveUpdateTracker(
    private val context: Context,
    private val notificationID: Int,
    private val channel: String,
) {
    class Entry(var staleAt: Instant, var state: LiveVoiceActivity.State)

    private val lock = Any()
    private val entries = HashMap<String, Entry>()
    private val manager = NotificationManagerCompat.from(context)

    /** Notifications allowed for the app and this channel. */
    fun areEnabled(): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        SystemNotificationChannels.ensure(context)
        val importance = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(channel)?.importance
        return importance != NotificationManager.IMPORTANCE_NONE
    }

    fun state(id: String): LiveVoiceActivity.State? = synchronized(lock) {
        val entry = entries[id] ?: return null
        if (entry.state == LiveVoiceActivity.State.Active && Instant.now().isAfter(entry.staleAt)) LiveVoiceActivity.State.Stale else entry.state
    }

    /** Posts a new one; throws [LiveUpdateSystem.RequestError] when notifications are off. */
    @SuppressLint("MissingPermission")
    fun request(staleDate: Instant, build: (String, Duration) -> Notification): String {
        if (!manager.areNotificationsEnabled()) throw LiveUpdateSystem.RequestError("NotificationsDisabled")
        if (!areEnabled()) throw LiveUpdateSystem.RequestError("ChannelBlocked")
        val id = UUID.randomUUID().toString().lowercase().take(8)
        synchronized(lock) {
            // One at a time: an older one is replaced.
            entries.values.forEach { if (it.state.isShowing) it.state = LiveVoiceActivity.State.Ended }
            entries[id] = Entry(staleDate, LiveVoiceActivity.State.Active)
        }
        try {
            manager.notify(notificationID, build(id, timeout(staleDate)))
        } catch (error: SecurityException) {
            synchronized(lock) { entries.remove(id) }
            throw LiveUpdateSystem.RequestError("PermissionDenied")
        }
        return id
    }

    @SuppressLint("MissingPermission")
    fun update(id: String, staleDate: Instant, build: (String, Duration) -> Notification) {
        synchronized(lock) {
            val entry = entries[id] ?: return
            if (!entry.state.isShowing) return
            entry.staleAt = staleDate
            entry.state = LiveVoiceActivity.State.Active
        }
        runCatching { manager.notify(notificationID, build(id, timeout(staleDate))) }
            .onFailure { AppLog.voice.error { "Live Update not updated: ${it::class.java.simpleName}" } }
    }

    fun end(id: String) {
        val current = synchronized(lock) {
            val entry = entries.remove(id)
            entry != null && entry.state.isShowing && entries.values.none { it.state.isShowing }
        }
        if (current) manager.cancel(notificationID)
    }

    fun endAll() {
        synchronized(lock) { entries.clear() }
        manager.cancel(notificationID)
    }

    fun dismissed(id: String) = synchronized(lock) {
        entries[id]?.state = LiveVoiceActivity.State.Dismissed
    }

    private fun timeout(staleDate: Instant): Duration =
        maxOf(1L, staleDate.toEpochMilli() - Instant.now().toEpochMilli()).milliseconds
}

internal object LiveUpdateSystem {
    class RequestError(val reason: String) : Exception(reason)

    fun voice(context: Context): LiveVoiceActivity.System {
        val app = context.applicationContext
        val tracker = LiveUpdateTracker(app, LiveUpdateNotifications.VOICE_CALL_ID, SystemNotificationChannels.VOICE_CALL)
        return LiveVoiceActivity.System(
            areActivitiesEnabled = tracker::areEnabled,
            state = tracker::state,
            request = { _, content, staleDate ->
                tracker.request(staleDate) { id, timeout -> LiveUpdateNotifications.voiceCall(app, id, content, timeout) }
            },
            update = { id, content, staleDate ->
                tracker.update(id, staleDate) { activityID, timeout -> LiveUpdateNotifications.voiceCall(app, activityID, content, timeout) }
            },
            end = { id, _ -> tracker.end(id) },
            endAll = { tracker.endAll() },
            clock = Clock.liveValue,
            enablementUpdates = { enablementUpdates(app, tracker) },
            foregroundEntries = { foregroundEntries() },
            stateUpdates = { id ->
                LiveActivityActionReceiver.dismissalEvents
                    .filter { it.kind == LiveActivityActionReceiver.Kind.VoiceCall && it.activityID == id }
                    .map {
                        tracker.dismissed(id)
                        LiveVoiceActivity.State.Dismissed
                    }
            },
            record = { name, detail -> AppLog.voice.info { "$name $detail" } },
            report = { LiveActivityStatus.current.set(it) },
        )
    }

    fun dictation(context: Context): LiveUpdateTracker =
        LiveUpdateTracker(context.applicationContext, LiveUpdateNotifications.DICTATION_ID, SystemNotificationChannels.DICTATION)

    fun sessionRun(context: Context): LiveUpdateTracker =
        LiveUpdateTracker(context.applicationContext, SessionRunLiveActivity.NOTIFICATION_ID, SystemNotificationChannels.SESSION_RUN)

    /** The app's or a channel's notifications switched in Settings. */
    private fun enablementUpdates(context: Context, tracker: LiveUpdateTracker): Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(tracker.areEnabled())
            }
        }
        val filter = IntentFilter().apply {
            addAction(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED)
            addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }

    /** The app coming to the foreground. */
    private fun foregroundEntries(): Flow<Unit> = callbackFlow {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                trySend(Unit)
            }
        }
        lifecycle.addObserver(observer)
        awaitClose { lifecycle.removeObserver(observer) }
    }.flowOn(Dispatchers.Main.immediate)
}
