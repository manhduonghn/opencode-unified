package ai.opencode.feature.system.liveactivity

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.strings.localized
import ai.opencode.feature.system.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.time.Instant

/** How a recording reads on the lock screen and in the status bar. */
object DictationLiveActivity {
    typealias Content = DictationActivityAttributes.ContentState

    /**
     * The part of the screen's recording the Live Update shows; observing only this keeps every
     * transcript update from re-running the mapping.
     */
    data class Recording(val title: String, val serverID: String)

    /**
     * Only a hands-free recording (locked, or TalkBack's tap to dictate) once the mic is live:
     * a held mic goes out with its press and never needs reminding of. Not while finishing:
     * Stop and Send end it at once. Not during a call, which has its own; a call card left
     * after one ended doesn't count.
     *
     * `SessionFeature.State` lives above this module.
     */
    fun recording(
        hasDetail: Boolean,
        callIsTerminal: Boolean,
        isRecording: Boolean,
        holdIsLocked: Boolean,
        sessionTitle: String?,
        serverID: String,
    ): Recording? {
        if (!hasDetail || !callIsTerminal || !isRecording || !holdIsLocked) return null
        val title = sessionTitle?.takeIf { it.isNotEmpty() } ?: localized(R.string.system_recording_new_session)
        return Recording(title, serverID)
    }

    /** The timer counts from when the Live Update first had a recording to show, and carries across a session switch. */
    fun content(recording: Recording?, previous: Content?, now: Instant): Content? {
        recording ?: return null
        return Content(recording.title, previous?.startedAt ?: now)
    }
}

class DictationLiveActivityClient(
    /** Shows `content` in the recording's one Live Update, starting it if needed; null ends it at once. `attributes` are only read on a start. */
    val update: suspend (DictationActivityAttributes.ContentState?, DictationActivityAttributes?) -> Unit,
    /** Live Updates a previous launch left behind; no recording survives a relaunch. */
    val endAll: suspend () -> Unit,
    /** Button taps from the Live Update. */
    val commands: () -> Flow<DictationCommand>,
) {
    companion object Key : DependencyKey<DictationLiveActivityClient> {
        override val liveValue: DictationLiveActivityClient by lazy {
            DictationLiveActivityClient(
                update = { content, attributes -> LiveDictationActivity.shared.enqueue(LiveDictationActivity.Request.Update(content, attributes)) },
                endAll = { LiveDictationActivity.shared.enqueue(LiveDictationActivity.Request.EndAll) },
                commands = { LiveActivityCommands.dictation.stream() },
            )
        }

        override val testValue = DictationLiveActivityClient(update = { _, _ -> }, endAll = {}, commands = { emptyFlow() })
    }
}

/**
 * Owns the one recording Live Update. Requests run in order, so an end never overtakes the update
 * before it. Like the call's, it pushes only when what it shows changes; a heartbeat moves the
 * stale date so a recording still running never reads as stopped.
 */
class LiveDictationActivity internal constructor(private val system: LiveUpdateTracker, private val context: android.content.Context) {
    typealias Content = DictationActivityAttributes.ContentState

    sealed interface Request {
        data class Update(val content: Content?, val attributes: DictationActivityAttributes?) : Request
        data object EndAll : Request
        data object Refresh : Request
        data class Dismissed(val activityID: String) : Request
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runner = LiveActivityRunner(Clock.liveValue, scope)
    private var activityID: String? = null
    private var content: Content? = null
    /** Swiped away, or it couldn't start: not asked again for this recording. */
    private var isDeclined = false

    init {
        scope.launch {
            LiveActivityActionReceiver.dismissalEvents
                .filter { it.kind == LiveActivityActionReceiver.Kind.Dictation }
                .collect { enqueue(Request.Dismissed(it.activityID)) }
        }
    }

    suspend fun enqueue(request: Request) = runner.enqueue { apply(request) }

    private fun apply(request: Request) {
        when (request) {
            Request.EndAll -> endAll()
            is Request.Update -> {
                val content = request.content
                if (content == null) {
                    endAll()
                    return
                }
                this.content = content
                val id = activityID
                if (id != null) {
                    if (system.state(id)?.isShowing == true) {
                        system.update(id, LiveVoiceActivity.staleDate()) { activityID, timeout ->
                            LiveUpdateNotifications.dictation(context, activityID, content, timeout)
                        }
                        return
                    }
                    // Gone while it showed this recording: the user swiped it away.
                    activityID = null
                    isDeclined = true
                    runner.stopHeartbeat()
                }
                if (isDeclined || request.attributes == null) return
                try {
                    val newID = system.request(LiveVoiceActivity.staleDate()) { activityID, timeout ->
                        LiveUpdateNotifications.dictation(context, activityID, content, timeout)
                    }
                    activityID = newID
                    record("dictation_activity_started", "id=$newID")
                    runner.startHeartbeat { apply(Request.Refresh) }
                } catch (error: Exception) {
                    // Notifications off: the recording goes without one.
                    isDeclined = true
                    record("dictation_activity_failed", LiveVoiceActivity.describe(error))
                }
            }
            Request.Refresh -> {
                val content = content
                val id = activityID
                if (content == null || id == null || system.state(id)?.isShowing != true) {
                    runner.stopHeartbeat()
                    return
                }
                system.update(id, LiveVoiceActivity.staleDate()) { activityID, timeout ->
                    LiveUpdateNotifications.dictation(context, activityID, content, timeout)
                }
            }
            is Request.Dismissed -> system.dismissed(request.activityID)
        }
    }

    private fun endAll() {
        runner.stopHeartbeat()
        activityID?.let { record("dictation_activity_ended", "id=$it") }
        activityID = null
        content = null
        isDeclined = false
        system.endAll()
    }

    private fun record(name: String, detail: String) = AppLog.voice.info { "$name $detail" }

    companion object {
        internal val shared: LiveDictationActivity by lazy {
            val context = dependency(AppContext).applicationContext
            LiveDictationActivity(LiveUpdateSystem.dictation(context), context)
        }

        internal suspend fun endAllFromCommand() = shared.enqueue(Request.EndAll)
    }
}
