package ai.opencode.devtools

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.diagnostics.DiagnosticsEvent
import ai.opencode.core.diagnostics.DiagnosticsRecorder
import ai.opencode.core.diagnostics.ProblemReportBuilder
import ai.opencode.core.diagnostics.ProblemReportContext
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.core.tuning.AnimationTimeScale
import ai.opencode.core.tuning.InternalBuild
import ai.opencode.core.tuning.TuningAccess
import ai.opencode.core.tuning.TuningSharedKeys
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import kotlinx.coroutines.flow.drop

/** Settings › Developer. Keep the public names stable: `:app` wires them. */
class DevToolsFeature : Reducer<DevToolsFeature.State, DevToolsFeature.Action> {
    enum class Route { Main, Troubleshooting, Logs, Network, Lab }

    data class Lab(
        val address: String = "10.0.2.2:4110",
        val status: Status = Status.Idle,
        val state: LabState? = null,
    ) {
        sealed interface Status {
            data object Idle : Status
            data object Loading : Status
            data object Connected : Status
            data class Failed(val message: String) : Status
        }
    }

    data class State(
        val route: Route = Route.Main,
        val developerMode: Boolean = false,
        val recordDiagnostics: Boolean = false,
        val includesTranscripts: Boolean = false,
        val demoCallAudio: Boolean = false,
        val tuningEnabled: Boolean = false,
        /** Debug build, or Developer Mode on ([TuningAccess]). */
        val toolsAvailable: Boolean = false,
        /** Debug or not installed by Play. */
        val isInternalBuild: Boolean = false,
        val timeScale: Double = 1.0,
        val perfHud: Boolean = false,
        val layoutBounds: Boolean = false,
        val events: List<DiagnosticsEvent> = emptyList(),
        val logLines: List<String> = emptyList(),
        val logQuery: String = "",
        val logCategory: String? = null,
        val networkFailuresOnly: Boolean = false,
        val lab: Lab = Lab(),
        val isExporting: Boolean = false,
        /** A transient confirmation or “Couldn’t …” error. */
        val message: String? = null,
    )

    sealed interface Action {
        /** The view appeared (TCA `.task`). */
        data object Task : Action
        data object Reload : Action
        data class DeveloperModeChanged(val on: Boolean) : Action
        data class RecordDiagnosticsChanged(val on: Boolean) : Action
        data class IncludeTranscriptsChanged(val on: Boolean) : Action
        data class DemoCallAudioChanged(val on: Boolean) : Action
        data class TuningEnabledChanged(val on: Boolean) : Action
        data object OpenTuningPanelTapped : Action
        data class TimeScaleChanged(val scale: Double) : Action
        data class PerfHudChanged(val on: Boolean) : Action
        data class LayoutBoundsChanged(val on: Boolean) : Action
        data class Navigate(val route: Route) : Action
        data object BackTapped : Action
        data class LogQueryChanged(val query: String) : Action
        data class LogCategoryChanged(val category: String?) : Action
        data object ShareLogTapped : Action
        data object CopyLogTapped : Action
        data object NetworkFailuresOnlyToggled : Action
        data class ShareTimelineTapped(val json: Boolean) : Action
        data object ClearTapped : Action
        data object ExportBundleTapped : Action
        data class BundleExported(val error: String?) : Action
        data class LabAddressChanged(val address: String) : Action
        data object LabConnectTapped : Action
        data class LabLoaded(val state: LabState?, val error: String?) : Action
        data class LabCheckpointSelected(val id: String) : Action
        data object PointAppAtLabTapped : Action
        data class MessageShown(val message: String?) : Action
    }

    private enum class CancelID { Observe, Lab, Message }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.Task -> {
            val recorder = dependency(DiagnosticsRecorder)
            Reduced(
                load(state).copy(lab = state.lab.copy(address = shared(DevToolsKeys.labAddress).value)),
                Effect.run<Action> { send ->
                    recorder.changes.drop(1).collect { send(Action.Reload) }
                }.cancellable(CancelID.Observe, cancelInFlight = true),
            )
        }
        Action.Reload -> Reduced(load(state))

        is Action.DeveloperModeChanged -> {
            shared(SharedKeys.developerMode).set(action.on)
            AppLog.app.info { "developer_mode on=${action.on}" }
            // While Developer Mode is on, recording stays on too, so reports have a timeline.
            val recorder = dependency(DiagnosticsRecorder)
            if (action.on && !recorder.isEnabled) recorder.setEnabled(true)
            DevTools.accessChanged()
            if (action.on) dependency(SavedVoiceLogUploads).uploadAll()
            Reduced(load(state))
        }
        is Action.RecordDiagnosticsChanged -> {
            dependency(DiagnosticsRecorder).setEnabled(action.on)
            Reduced(load(state))
        }
        is Action.IncludeTranscriptsChanged -> {
            dependency(DiagnosticsRecorder).setIncludesTranscripts(action.on)
            Reduced(load(state))
        }
        is Action.DemoCallAudioChanged -> {
            shared(SharedKeys.voiceDemoMode).set(action.on)
            Reduced(state.copy(demoCallAudio = action.on))
        }
        is Action.TuningEnabledChanged -> {
            if (state.toolsAvailable) DevTools.setTuningEnabled(action.on)
            Reduced(load(state))
        }
        Action.OpenTuningPanelTapped -> {
            DevTools.showPanel()
            Reduced(load(state))
        }
        is Action.TimeScaleChanged -> {
            val applied = AnimationTimeScale.shared.setScale(action.scale)
            dependency(HapticsClient).play(Haptic.selection)
            Reduced(state.copy(timeScale = applied))
        }
        is Action.PerfHudChanged -> {
            shared(DevToolsKeys.perfHud).set(action.on)
            Reduced(state.copy(perfHud = action.on))
        }
        is Action.LayoutBoundsChanged -> {
            shared(DevToolsKeys.layoutBounds).set(action.on)
            Reduced(state.copy(layoutBounds = action.on))
        }
        is Action.Navigate -> Reduced(load(state.copy(route = action.route)))
        Action.BackTapped -> Reduced(state.copy(route = Route.Main))

        is Action.LogQueryChanged -> Reduced(state.copy(logQuery = action.query))
        is Action.LogCategoryChanged -> Reduced(state.copy(logCategory = action.category))
        Action.ShareLogTapped -> {
            val text = filteredLog(state).joinToString("\n")
            val client = dependency(DevToolsClient)
            Reduced(state, Effect.run { client.shareText("OpenCode app log", text) })
        }
        Action.CopyLogTapped -> {
            val text = filteredLog(state).joinToString("\n")
            val pasteboard = dependency(PasteboardClient)
            dependency(HapticsClient).play(Haptic.tap)
            Reduced(state, Effect.merge(Effect.run { pasteboard.setString(text) }, showMessage("Copied")))
        }
        Action.NetworkFailuresOnlyToggled -> Reduced(state.copy(networkFailuresOnly = !state.networkFailuresOnly))
        is Action.ShareTimelineTapped -> {
            val events = state.events
            val text = if (action.json) DiagnosticsRecorder.exportJSON(events) else DiagnosticsRecorder.exportText(events)
            val client = dependency(DevToolsClient)
            Reduced(state, Effect.run { client.shareText("OpenCode diagnostics", text) })
        }
        Action.ClearTapped -> {
            dependency(DiagnosticsRecorder).clear()
            Reduced(load(state))
        }
        Action.ExportBundleTapped -> {
            if (state.isExporting) {
                Reduced(state)
            } else {
                val client = dependency(DevToolsClient)
                val recorder = dependency(DiagnosticsRecorder)
                val now = dependency(DateClient).now()
                val id = dependency(UuidClient).next()
                val server = shared(ServerSharedKeys.savedServer).value
                val bundle = ProblemReportBuilder.make(
                    id = id,
                    context = ProblemReportContext(serverID = server?.persistenceID.orEmpty(), screen = "Settings › Developer"),
                    device = client.device(),
                    now = now,
                    events = recorder.snapshot(),
                    appLog = client.appLog(),
                    previousRun = recorder.previousRunEvents(),
                )
                Reduced(
                    state.copy(isExporting = true),
                    Effect.run(onError = { error, send -> send(Action.BundleExported(error.javaClass.simpleName)) }) { send ->
                        val file = client.writeBundle(bundle)
                        AppLog.app.info { "diagnostics_bundle id=${bundle.report.shortID} files=${bundle.files.size} bytes=${file.length()}" }
                        client.shareFile(file, "application/zip")
                        send(Action.BundleExported(null))
                    },
                )
            }
        }
        is Action.BundleExported -> {
            val failed = action.error != null
            if (failed) AppLog.app.error { "diagnostics_bundle failed=${action.error}" }
            Reduced(state.copy(isExporting = false), if (failed) showMessage("Couldn’t export diagnostics") else Effect.none())
        }

        is Action.LabAddressChanged -> Reduced(state.copy(lab = state.lab.copy(address = action.address)))
        Action.LabConnectTapped -> {
            val address = state.lab.address.trim()
            shared(DevToolsKeys.labAddress).set(address)
            val client = dependency(DevToolsClient)
            Reduced(
                state.copy(lab = state.lab.copy(address = address, status = Lab.Status.Loading)),
                Effect.run<Action>(onError = { error, send -> send(Action.LabLoaded(null, error.message ?: error.javaClass.simpleName)) }) { send ->
                    send(Action.LabLoaded(client.labState(address), null))
                }.cancellable(CancelID.Lab, cancelInFlight = true),
            )
        }
        is Action.LabLoaded -> Reduced(
            state.copy(
                lab = state.lab.copy(
                    status = if (action.state != null) Lab.Status.Connected else Lab.Status.Failed(action.error ?: "unknown"),
                    state = action.state ?: state.lab.state.takeIf { action.error == null },
                ),
            ),
        )
        is Action.LabCheckpointSelected -> {
            val address = state.lab.address
            val client = dependency(DevToolsClient)
            dependency(HapticsClient).play(Haptic.selection)
            Reduced(
                state.copy(lab = state.lab.copy(status = Lab.Status.Loading)),
                Effect.run<Action>(onError = { error, send -> send(Action.LabLoaded(null, error.message ?: error.javaClass.simpleName)) }) { send ->
                    send(Action.LabLoaded(client.labSelect(address, action.id), null))
                }.cancellable(CancelID.Lab, cancelInFlight = true),
            )
        }
        Action.PointAppAtLabTapped -> {
            val address = state.lab.address.trim().trimEnd('/')
            val url = if (address.startsWith("http://") || address.startsWith("https://")) address else "http://$address"
            // Session-lab takes an empty password; the user name is the app's default.
            val server = OpenCodeServer(url = url, username = "opencode", password = "", name = "Session Lab")
            shared(ServerSharedKeys.savedServers).update { servers -> listOf(server) + servers.filterNot { server.supersedes(it) } }
            shared(ServerSharedKeys.savedServer).set(server)
            AppLog.app.info { "devtools_lab_server saved; relaunching" }
            val client = dependency(DevToolsClient)
            val store = dependency(SharedStore)
            // Saved values persist in the background: flush before the process goes away.
            Reduced(state, Effect.run { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.flush() }; client.restart() })
        }
        is Action.MessageShown -> Reduced(state.copy(message = action.message))
    }

    private fun showMessage(message: String): Effect<Action> = Effect.concatenate(
        Effect.send(Action.MessageShown(message)),
        Effect.run<Action> { send ->
            kotlinx.coroutines.delay(2_000)
            send(Action.MessageShown(null))
        }.cancellable(CancelID.Message, cancelInFlight = true),
    )

    private fun load(state: State): State {
        val recorder = dependency(DiagnosticsRecorder)
        val client = dependency(DevToolsClient)
        val events = if (state.route == Route.Troubleshooting || state.route == Route.Network) recorder.snapshot() else state.events
        return state.copy(
            developerMode = shared(SharedKeys.developerMode).value,
            recordDiagnostics = recorder.isEnabled,
            includesTranscripts = recorder.includesTranscripts,
            demoCallAudio = shared(SharedKeys.voiceDemoMode).value,
            tuningEnabled = shared(TuningSharedKeys.tuningEnabled).value && TuningAccess.isAvailable,
            toolsAvailable = TuningAccess.isAvailable,
            isInternalBuild = InternalBuild.isEnabled,
            timeScale = AnimationTimeScale.shared.scale,
            perfHud = shared(DevToolsKeys.perfHud).value,
            layoutBounds = shared(DevToolsKeys.layoutBounds).value,
            events = events,
            logLines = if (state.route == Route.Logs) client.appLog() else state.logLines,
        )
    }

    companion object {
        /** `AppLog` categories as lines name them (`<time> <level> <category>: …`). */
        val logCategories = listOf("send", "network", "stream", "voice", "app", "session")

        fun filteredLog(state: State): List<String> {
            val query = state.logQuery.trim()
            return state.logLines.filter { line ->
                (state.logCategory == null || line.contains(" ${state.logCategory}: ")) && (query.isEmpty() || line.contains(query, ignoreCase = true))
            }
        }
    }
}
