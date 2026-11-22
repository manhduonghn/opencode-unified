package ai.opencode.feature.voice

import ai.opencode.core.dependencies.*
import ai.opencode.core.log.AppLog
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

/** Call-owned notification/Live Activity command channel. A command carries its
 * call identity: old PendingIntents can never mute or end a newer call. The reducer
 * collects events while active and handles muteTapped/endTapped, not endPressed. */
class VoiceCallCommands {
    enum class Action { mute, end }
    data class Command(val callID: UUID, val action: Action)
    private val stream = MutableSharedFlow<Command>(extraBufferCapacity = 16)
    val events: SharedFlow<Command> = stream
    @Volatile private var active: UUID? = null
    private var pendingForegroundStarts = 0
    fun activate(id: UUID) { synchronized(this) { active = id } }
    fun isActive(id: UUID) = active == id
    fun deactivate(id: UUID) { synchronized(this) { if (active == id) active = null } }
    internal fun noteForegroundStartQueued() { synchronized(this) { pendingForegroundStarts++ } }
    internal fun noteForegroundStartFailed() { synchronized(this) { if (pendingForegroundStarts > 0) pendingForegroundStarts-- } }
    internal fun consumeForegroundStart(): Int = synchronized(this) {
        if (pendingForegroundStarts > 0) pendingForegroundStarts--
        pendingForegroundStarts
    }
    internal val hasPendingForegroundStart: Boolean get() = synchronized(this) { pendingForegroundStarts > 0 }
    fun send(id: UUID, action: Action): Boolean = active == id && stream.subscriptionCount.value > 0 && stream.tryEmit(Command(id, action))
    companion object Key : DependencyKey<VoiceCallCommands> {
        override val liveValue by lazy { VoiceCallCommands() }
        override val testValue get() = VoiceCallCommands()
    }
}

data class VoiceCallServiceState(val callID: UUID, val phase: Phase = Phase.connecting, val muted: Boolean = false, val connectedAt: Instant? = null) {
    enum class Phase { connecting, live, reconnecting, ended }
}

/** Start from a visible Activity after RECORD_AUDIO permission has been granted,
 * before starting audio; API 35 forbids background microphone FGS admission.
 * :app owns the POST_NOTIFICATIONS permission prompt. It must collect commands
 * before start, update on semantic changes only, and stop when the call ends. */
class VoiceCallServiceClient(
    val start: suspend (VoiceCallServiceState) -> Unit,
    val update: suspend (VoiceCallServiceState) -> Unit,
    val stop: suspend (UUID) -> Unit,
) {
    companion object Key : DependencyKey<VoiceCallServiceClient> {
        internal fun makeLive(
            context: android.content.Context,
            commands: VoiceCallCommands,
        ): VoiceCallServiceClient {
            fun hasMicrophonePermission(): Boolean {
                // Without the microphone permission the call fails on its own
                // (microphoneDenied); a microphone service would throw instead.
                if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    AppLog.voice.notice("voice_call_service_skipped reason=microphone_denied")
                    return false
                }
                return true
            }
            fun startForegroundNow(state: VoiceCallServiceState) {
                commands.activate(state.callID)
                commands.noteForegroundStartQueued()
                try {
                    context.startForegroundService(VoiceCallService.intent(context, state, foregroundStart = true))
                } catch (error: Exception) {
                    commands.noteForegroundStartFailed()
                    commands.deactivate(state.callID)
                    throw error
                }
            }
            return VoiceCallServiceClient(
                start = { state -> withContext(Dispatchers.Main.immediate) {
                    if (hasMicrophonePermission()) startForegroundNow(state)
                } },
                update = { state -> withContext(Dispatchers.Main.immediate) {
                    if (commands.isActive(state.callID)) {
                        try {
                            context.startService(VoiceCallService.intent(context, state))
                        } catch (error: Exception) {
                            commands.deactivate(state.callID)
                            AppLog.voice.error("Call notification failed to update: ${error.javaClass.simpleName}")
                        }
                    } else if (state.phase != VoiceCallServiceState.Phase.ended && hasMicrophonePermission()) {
                        // First call after install: RECORD_AUDIO was granted after start() skipped admission.
                        startForegroundNow(state)
                    }
                } },
                stop = { id -> withContext(Dispatchers.Main.immediate) {
                    if (commands.isActive(id)) {
                        commands.deactivate(id)
                        // Never call stopService while a startForegroundService delivery is still queued:
                        // ActivityManager throws ForegroundServiceDidNotStartInTimeException if the
                        // service comes down before calling startForeground; onStartCommand promotes
                        // then immediately stops itself once it sees the call is no longer active.
                        if (!commands.hasPendingForegroundStart) {
                            runCatching { context.stopService(Intent(context, VoiceCallService::class.java)) }
                        }
                    }
                } },
            )
        }
        override val liveValue by lazy { makeLive(dependency(AppContext), dependency(VoiceCallCommands)) }
        override val testValue = VoiceCallServiceClient({}, {}, {})
    }
}

open class VoiceCallService : Service() {
    private var state: VoiceCallServiceState? = null
    private val commands get() = dependency(VoiceCallCommands)
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.voice_notification_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.voice_notification_channel_description); setSound(null, null); enableVibration(false); setShowBadge(false)
            },
        )
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val foregroundStart = intent?.getBooleanExtra(FOREGROUND_START, false) == true
        val remainingStarts = if (foregroundStart) commands.consumeForegroundStart() else if (commands.hasPendingForegroundStart) 1 else 0
        if (state?.let { !commands.isActive(it.callID) } == true) state = null
        val id = intent?.getStringExtra("callID")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (id == null) {
            if (foregroundStart) satisfyAndStopIfIdle(null, remainingStarts, startId)
            else if (state == null && remainingStarts == 0) stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent.action) {
            STOP -> if (state == null || state?.callID == id) {
                state = null
                if (remainingStarts == 0) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
            }
            MUTE, END -> {
                if (state?.callID != id || state?.phase == VoiceCallServiceState.Phase.ended) {
                    if (state == null && remainingStarts == 0) stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (!commands.send(id, if (intent.action == MUTE) VoiceCallCommands.Action.mute else VoiceCallCommands.Action.end)) {
                    commands.deactivate(id); state = null
                    if (remainingStarts == 0) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
                }
            }
            else -> {
                val phase = VoiceCallServiceState.Phase.entries.firstOrNull { it.name == intent.getStringExtra("phase") } ?: VoiceCallServiceState.Phase.connecting
                val connected = intent.getLongExtra("connectedAt", -1).takeIf { it >= 0 }?.let(Instant::ofEpochMilli)
                val updated = VoiceCallServiceState(id, phase, intent.getBooleanExtra("muted", false), connected)
                if (!commands.isActive(id)) {
                    if (foregroundStart) satisfyAndStopIfIdle(updated, remainingStarts, startId)
                    else if (state == null && remainingStarts == 0) stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (foregroundStart || updated != state) {
                    if (!promoteForeground(updated)) {
                        commands.send(id, VoiceCallCommands.Action.end)
                        commands.deactivate(id)
                        if (state == null && remainingStarts == 0) stopSelf(startId)
                        return START_NOT_STICKY
                    }
                    state = updated
                }
            }
        }
        return START_NOT_STICKY // Never resurrect a microphone after process death.
    }
    protected open fun startMicrophoneForeground(notification: Notification) {
        startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }
    private fun promoteForeground(target: VoiceCallServiceState): Boolean {
        // A microphone service may only start with RECORD_AUDIO granted (API 34+ throws
        // SecurityException otherwise, or if WIU eligibility was lost before onStartCommand).
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            AppLog.voice.notice("voice_call_service_skipped reason=microphone_denied")
            return false
        }
        return try {
            startMicrophoneForeground(notification(target))
            true
        } catch (error: Exception) {
            AppLog.voice.error("Call notification failed to start: ${error.javaClass.simpleName}")
            false
        }
    }
    private fun satisfyAndStopIfIdle(fallback: VoiceCallServiceState?, remainingStarts: Int, startId: Int) {
        val current = state
        val promoted = promoteForeground(current ?: fallback ?: VoiceCallServiceState(UUID(0L, 0L), VoiceCallServiceState.Phase.ended))
        if (current == null && remainingStarts == 0) {
            if (promoted) stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        state?.let { commands.send(it.callID, VoiceCallCommands.Action.end); commands.deactivate(it.callID) }
        state = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)
    }
    private fun notification(state: VoiceCallServiceState): Notification {
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_voice_call_notification)
            .setContentTitle("OpenCode").setCategory(Notification.CATEGORY_CALL).setOngoing(state.phase != VoiceCallServiceState.Phase.ended)
            .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC)
        val text = when (state.phase) {
            VoiceCallServiceState.Phase.connecting -> getString(R.string.voice_notification_connecting)
            VoiceCallServiceState.Phase.reconnecting -> getString(R.string.voice_notification_reconnecting)
            VoiceCallServiceState.Phase.ended -> getString(R.string.voice_notification_call_ended)
            VoiceCallServiceState.Phase.live -> null
        }
        if (text != null) builder.setContentText(text).setShowWhen(false)
        else state.connectedAt?.let { builder.setWhen(it.toEpochMilli()).setShowWhen(true).setUsesChronometer(true) }
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setContentIntent(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        if (state.phase != VoiceCallServiceState.Phase.ended) {
            builder.addAction(Notification.Action.Builder(null, if (state.muted) getString(R.string.voice_unmute) else getString(R.string.voice_mute), command(state.callID, MUTE)).build())
            builder.addAction(Notification.Action.Builder(null, getString(R.string.voice_notification_end), command(state.callID, END)).build())
        }
        return builder.build()
    }
    private fun command(id: UUID, action: String): PendingIntent = PendingIntent.getService(this, action.hashCode(),
        Intent(this, VoiceCallService::class.java).setAction(action).setData("opencode-call:$id".toUri())
            .putExtra("callID", id.toString()), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    override fun onTaskRemoved(rootIntent: Intent?) {
        state?.let { commands.send(it.callID, VoiceCallCommands.Action.end); commands.deactivate(it.callID) }
        state = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        state?.let { commands.send(it.callID, VoiceCallCommands.Action.end); commands.deactivate(it.callID) }
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "opencode-voice-call"
        private const val NOTIFICATION = 7301
        internal const val FOREGROUND_START = "foregroundStart"
        internal const val STOP = "ai.opencode.voice.STOP"
        private const val MUTE = "ai.opencode.voice.MUTE"
        private const val END = "ai.opencode.voice.END"
        internal fun intent(
            context: android.content.Context,
            state: VoiceCallServiceState,
            foregroundStart: Boolean = false,
        ) = Intent(context, VoiceCallService::class.java)
            .putExtra("callID", state.callID.toString()).putExtra("phase", state.phase.name).putExtra("muted", state.muted)
            .putExtra("connectedAt", state.connectedAt?.toEpochMilli() ?: -1)
            .putExtra(FOREGROUND_START, foregroundStart)
    }
}
