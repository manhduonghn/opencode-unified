package ai.opencode.devtools.traces

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.diagnostics.DiagnosticsRecorder
import ai.opencode.core.diagnostics.ProblemReport
import ai.opencode.core.diagnostics.ProblemReportBuilder
import ai.opencode.core.diagnostics.ProblemReportBundle
import ai.opencode.core.diagnostics.ProblemReportContext
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.core.tuning.DeveloperMode
import ai.opencode.core.tuning.InternalBuild
import ai.opencode.devtools.DevToolsClient
import ai.opencode.server.DeveloperModeUploads
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeFileReference
import ai.opencode.server.model.OpenCodeUploadEvent
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RetryPolicy
import ai.opencode.server.sendPromptConfirming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/** Developer Mode problem reports. Nothing leaves the phone unless the person presses Send. */
class TraceUploadsFeature : Reducer<TraceUploadsFeature.State, TraceUploadsFeature.Action> {
    data class State(
        /** True when the previous run ended unexpectedly in the foreground, so the next report sent includes the previous run's timeline. */
        val crashedLastRun: Boolean = false,
        val report: Report? = null,
        /** Whether the report reached its session, shown for a moment over the sheet, or over Home once the sheet is closed. */
        val toast: Toast? = null,
    ) {
        enum class Toast(val message: String) {
            SentToSession("Sent to session"),
            SessionFailed("Couldn’t send to session"),
        }

        data class Report(
            val id: UUID,
            val context: ProblemReportContext,
            /** The session the report is also sent into, on this Home's server. */
            val destination: ProblemReportDestination?,
            val note: String = "",
            val includesScreenshot: Boolean = false,
            val phase: Phase = Phase.Composing,
            val copiedReportID: Boolean = false,
            /** The summary reached the session, so a retry only sends the trace link. */
            val deliveredToSession: Boolean = false,
        ) {
            enum class Phase { Composing, Sending, Sent, Failed }

            val shortID: String get() = ProblemReport.shortID(id)

            val isEditable: Boolean get() = phase == Phase.Composing || phase == Phase.Failed
        }

        companion object {
            val summaryLine: String
                get() = "App logs from the last ${DiagnosticsRecorder.retentionMinutes} minutes, this session’s ID, app version and device model"
        }
    }

    sealed interface Action {
        data object Task : Action
        data class ReportProblemTapped(val context: ProblemReportContext, val destination: ProblemReportDestination?) : Action
        data class NoteChanged(val note: String) : Action
        data class IncludeScreenshotChanged(val isOn: Boolean) : Action
        data object SendTapped : Action
        data object TryAgainTapped : Action
        data object CopyReportIDTapped : Action
        data object CancelTapped : Action
        data object Dismissed : Action
        data class Uploaded(val id: UUID, val ok: Boolean) : Action
        data class SentToSession(val id: UUID, val ok: Boolean) : Action
        data class UploadsUnsupported(val serverID: String) : Action
        data object ToastExpired : Action
    }

    private enum class CancelID { Upload, Toast }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.Task -> {
            if (reportsCrashes && dependency(DiagnosticsRecorder).takeUnexpectedEnd()) {
                Reduced(state.copy(crashedLastRun = true))
            } else {
                Reduced(state)
            }
        }

        is Action.ReportProblemTapped -> {
            if (!dependency(TraceUploader).isEnabled()) {
                Reduced(state)
            } else {
                Reduced(state.copy(report = State.Report(id = dependency(UuidClient).next(), context = action.context, destination = action.destination)))
            }
        }

        is Action.NoteChanged -> {
            val report = state.report
            if (report == null || !report.isEditable) Reduced(state) else Reduced(state.copy(report = report.copy(note = action.note)))
        }

        is Action.IncludeScreenshotChanged -> {
            val report = state.report
            if (report == null || !report.isEditable) Reduced(state) else Reduced(state.copy(report = report.copy(includesScreenshot = action.isOn)))
        }

        Action.SendTapped, Action.TryAgainTapped -> sendReport(state)

        is Action.Uploaded -> {
            val report = state.report
            when {
                report == null || report.id != action.id || report.phase != State.Report.Phase.Sending -> Reduced(state)
                action.ok -> Reduced(state.copy(crashedLastRun = false, report = report.copy(phase = State.Report.Phase.Sent)))
                report.destination == null || report.deliveredToSession -> Reduced(state.copy(report = report.copy(phase = State.Report.Phase.Failed)))
                // The summary is still on its way to the session: Try Again waits for it, so a
                // retry can't race it.
                else -> Reduced(state)
            }
        }

        is Action.SentToSession -> {
            var report = state.report
            if (report != null && report.id == action.id) {
                if (action.ok) report = report.copy(deliveredToSession = true)
                if (report.phase == State.Report.Phase.Sending) report = report.copy(phase = State.Report.Phase.Failed)
            }
            dependency(HapticsClient).play(if (action.ok) Haptic.success else Haptic.error)
            val clock = dependency(Clock)
            Reduced(
                state.copy(report = report, toast = if (action.ok) State.Toast.SentToSession else State.Toast.SessionFailed),
                Effect.run<Action> { send ->
                    clock.sleep(TOAST_DURATION)
                    send(Action.ToastExpired)
                }.cancellable(CancelID.Toast, cancelInFlight = true),
            )
        }

        is Action.UploadsUnsupported -> {
            val unsupported = shared(SharedKeys.uploadUnsupportedServerIDs)
            if (action.serverID !in unsupported.value) unsupported.update { it + action.serverID }
            Reduced(state)
        }

        Action.ToastExpired -> Reduced(state.copy(toast = null))

        Action.CopyReportIDTapped -> {
            val report = state.report
            if (report == null || report.phase != State.Report.Phase.Sent) {
                Reduced(state)
            } else {
                dependency(HapticsClient).play(Haptic.tap)
                val pasteboard = dependency(PasteboardClient)
                val shortID = report.shortID
                Reduced(state.copy(report = report.copy(copiedReportID = true)), Effect.run { pasteboard.setString(shortID) })
            }
        }

        Action.CancelTapped, Action.Dismissed -> {
            val report = state.report
            when {
                report == null -> Reduced(state)
                // Once uploaded, closing the sheet lets the session message finish.
                report.phase == State.Report.Phase.Sent -> Reduced(state.copy(report = null))
                else -> Reduced(state.copy(report = null), Effect.cancel(CancelID.Upload))
            }
        }
    }

    private fun sendReport(state: State): Reduced<State, Action> {
        val uploader = dependency(TraceUploader)
        val current = state.report
        if (!uploader.isEnabled() || current == null || !current.isEditable) return Reduced(state)
        val snapshot = current.copy(phase = State.Report.Phase.Sending, copiedReportID = false)
        dependency(HapticsClient).play(Haptic.confirm)
        val unsupported = shared(SharedKeys.uploadUnsupportedServerIDs).value
        val attaches = snapshot.destination?.let { it.server.persistenceID !in unsupported } ?: false
        val crashedLastRun = state.crashedLastRun
        val at = dependency(DateClient).now()
        val recorder = dependency(DiagnosticsRecorder)
        val capture = dependency(ProblemReportCapture)
        val devTools = dependency(DevToolsClient)
        val client = dependency(OpenCodeClient)
        return Reduced(
            state.copy(report = snapshot),
            Effect.run<Action> { send ->
                val screenshot = if (snapshot.includesScreenshot) capture.screenshot() else null
                // Off the main thread: the timeline and the previous run can be a few hundred KB.
                val bundle = withContext(Dispatchers.Default) {
                    ProblemReportBuilder.make(
                        id = snapshot.id,
                        note = snapshot.note,
                        crashedLastRun = crashedLastRun,
                        context = snapshot.context,
                        device = devTools.device(),
                        now = at,
                        events = recorder.snapshot(),
                        appLog = devTools.appLog(),
                        previousRun = recorder.previousRunEvents(),
                        screenshot = screenshot,
                    )
                }
                val ok = NetworkRetry.policy.withValue(NetworkRetry.currentPolicy(RETRY_POLICY)) { uploader.upload(bundle) }
                send(Action.Uploaded(snapshot.id, ok))
                val destination = snapshot.destination ?: return@run
                val shortID = ProblemReport.shortID(snapshot.id)
                var files: List<OpenCodeFileReference> = emptyList()
                val text: String
                if (snapshot.deliveredToSession) {
                    if (!ok) return@run
                    text = ProblemReportBuilder.sessionTraceFollowUp(bundle.report, uploader.traceURL(snapshot.id))
                } else {
                    val traceURL = if (ok) uploader.traceURL(snapshot.id) else null
                    // The full report goes as a file, through the composer's upload, so the agent
                    // reads it with its tools; inline when the server can't.
                    val attached = if (attaches) uploadAttachment(bundle, destination, client) else Attachment.Failed
                    if (attached is Attachment.Uploaded) {
                        files = listOf(attached.file)
                        text = ProblemReportBuilder.sessionMessageWithAttachment(bundle, traceURL)
                    } else {
                        if (attached == Attachment.Unsupported) send(Action.UploadsUnsupported(destination.server.persistenceID))
                        text = ProblemReportBuilder.sessionMessage(bundle, traceURL)
                    }
                }
                // Made from the report's ID, so the server admits each message once.
                val messageID = ProblemReportBuilder.sessionMessageID(snapshot.id, followUp = snapshot.deliveredToSession)
                val sent = NetworkRetry.policy.withValue(NetworkRetry.currentPolicy(RETRY_POLICY)) {
                    try {
                        Result.success(client.sendPromptConfirming(destination.server, destination.sessionID, text, OpenCodeDelivery.Queue, files = files, messageID = messageID))
                    } catch (error: OpenCodeClientError) {
                        Result.failure(error)
                    }
                }
                sent.fold(
                    onSuccess = { id -> AppLog.send.info { "Problem report $shortID sent to ${destination.sessionID} as $id: ${text.length} characters, ${files.size} files, uploaded $ok" } },
                    onFailure = { error -> AppLog.send.error { "Problem report $shortID couldn’t reach ${destination.sessionID}: ${(error as OpenCodeClientError).logSummary}" } },
                )
                send(Action.SentToSession(snapshot.id, sent.isSuccess))
            }.cancellable(CancelID.Upload, cancelInFlight = true),
        )
    }

    private sealed interface Attachment {
        data class Uploaded(val file: OpenCodeFileReference) : Attachment
        data object Unsupported : Attachment
        data object Failed : Attachment
    }

    /**
     * Writes the report to a temporary file and uploads it to the session's directory as the
     * composer uploads a file.
     */
    private suspend fun uploadAttachment(bundle: ProblemReportBundle, destination: ProblemReportDestination, client: OpenCodeClient): Attachment {
        val name = ProblemReportBuilder.attachmentName(bundle.report)
        val file = withContext(Dispatchers.IO) {
            runCatching {
                File(System.getProperty("java.io.tmpdir"), "${UUID.randomUUID().toString().uppercase()}-$name").apply { writeText(ProblemReportBuilder.attachment(bundle)) }
            }.getOrNull()
        } ?: return Attachment.Failed
        try {
            val finished = client.uploadFile(destination.server, destination.directory, file, name)
                .firstOrNull { it is OpenCodeUploadEvent.Finished && it.path.isNotEmpty() } as? OpenCodeUploadEvent.Finished
            return finished?.let { Attachment.Uploaded(OpenCodeFileReference(name = name, mime = "text/markdown", path = it.path)) } ?: Attachment.Failed
        } catch (error: OpenCodeClientError) {
            AppLog.send.error { "Problem report ${bundle.report.shortID} attachment ${if (error.isUnsupported) "unsupported" else "failed"}; sending inline" }
            return if (error.isUnsupported) Attachment.Unsupported else Attachment.Failed
        } finally {
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    companion object {
        val RETRY_POLICY: RetryPolicy = RetryPolicy.send
        val TOAST_DURATION = 2.5.seconds

        /** A debug lab asked for crash reports. */
        @Volatile var labCrashReport: Boolean = false

        /**
         * A debugger stop or an `adb install` over a running app looks like a crash, so debug
         * builds treat one as a crash only when a lab asks.
         */
        val reportsCrashes: Boolean get() = if (InternalBuild.isDebug) labCrashReport else true
    }
}

/** Where a report is also sent: its session, or Home's most recent one, on Home's server. */
data class ProblemReportDestination(
    val server: OpenCodeServer,
    val sessionID: String,
    /** The session's directory, where the attachment uploads (as the composer's do). */
    val directory: String? = null,
)

/** Uploads a trace under this phone's Developer Mode owner key. */
class TraceUploader(
    val isEnabled: () -> Boolean = { false },
    val upload: suspend (ProblemReportBundle) -> Boolean = { false },
    /** The uploaded trace's page (owner key included: never log it). */
    val traceURL: suspend (UUID) -> String = { id -> "${DeveloperModeUploads.BASE}/t/${id.toString().uppercase()}" },
) {
    companion object Key : DependencyKey<TraceUploader> {
        override val liveValue: TraceUploader = TraceUploader(
            isEnabled = { DeveloperMode.isEnabled },
            upload = { bundle -> DeveloperModeUploads.upload(bundle) },
            traceURL = { id -> DeveloperModeUploads.traceURL(id) },
        )
        override val testValue: TraceUploader = TraceUploader()
    }
}
