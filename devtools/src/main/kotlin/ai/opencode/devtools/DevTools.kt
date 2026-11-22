package ai.opencode.devtools

import ai.opencode.core.diagnostics.DiagnosticsRecorder
import ai.opencode.core.diagnostics.LabMarks
import ai.opencode.core.diagnostics.PerfFrameSampler
import ai.opencode.core.diagnostics.PerfTimeline
import ai.opencode.core.log.AppLog
import ai.opencode.core.motion.MotionTunables
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Store
import ai.opencode.core.tuning.AnimationTimeScale
import ai.opencode.core.tuning.TuningAccess
import ai.opencode.core.tuning.TuningPerf
import ai.opencode.core.tuning.TuningSharedKeys
import ai.opencode.core.tuning.TuningStore
import ai.opencode.devtools.tuning.TuningFeature
import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import ai.opencode.devtools.traces.TraceUploadsFeature
import ai.opencode.devtools.traces.WindowSnapshot
import android.app.Application
import android.content.Intent
import java.net.URL

/**
 * The developer tools' process-wide entry points. `:app` calls [install], [handleLaunchIntent],
 * wraps its root in `DevToolsOverlay` and shows `DevToolsView` as Settings › Developer.
 */
object DevTools {
    /** The Tuning Panel's store, shared by the overlay and Settings. Main thread. */
    val tuningStore: Store<TuningFeature.State, TuningFeature.Action> by lazy { Store(TuningFeature.State(), TuningFeature()) }

    /** The Developer screen's store when the overlay opens it (Settings may keep its own). Main thread. */
    val developerStore: Store<DevToolsFeature.State, DevToolsFeature.Action> by lazy { Store(DevToolsFeature.State(), DevToolsFeature()) }

    /** At launch: applies the saved Tuning switch where tuning is available. */
    fun install() {
        // Made at launch: its journal sets the previous run aside before anything records.
        DiagnosticsRecorder.shared
        runCatching { dependency(AppContext).applicationContext as Application }.getOrNull()?.let(WindowSnapshot::install)
        applyTuning(shared(TuningSharedKeys.tuningEnabled).value && TuningAccess.isAvailable)
    }

    /**
     * Debug lab extras: `tuning-enabled`, `tuning-as-release`, `lab-time-scale`, `lab-marks-url`,
     * `diagnostics-enabled`, `lab-tuning-perf`, `tuning-panel`, `perf-signposts`, `perf-frames`
     * and `lab-crash-report`.
     */
    fun handleLaunchIntent(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra("tuning-as-release", false)) TuningAccess.actsAsReleaseOverride = true
        intent.getStringExtra("lab-marks-url")?.let { raw -> LabMarks.endpoint = runCatching { URL(raw) }.getOrNull() }
        if (intent.getBooleanExtra("diagnostics-enabled", false)) DiagnosticsRecorder.shared.setEnabled(true)
        if (intent.getBooleanExtra("lab-tuning-perf", false)) TuningPerf.enabled = true
        if (intent.getBooleanExtra("lab-crash-report", false)) TraceUploadsFeature.labCrashReport = true
        if (intent.getBooleanExtra("perf-signposts", false)) PerfTimeline.signpostsRequested = true
        if (intent.getBooleanExtra("perf-frames", false)) PerfFrameSampler.framesRequested = true
        AnimationTimeScale.requestedLabScale(intent.getStringExtra("lab-time-scale"))?.let {
            AnimationTimeScale.shared.setScale(it, bypassAccessCheck = true)
        }
        if (intent.getBooleanExtra("tuning-enabled", false) && TuningAccess.isAvailable) applyTuning(true)
        // Lab runs open the panel without the multi-finger gesture adb can't send.
        if (intent.getBooleanExtra("tuning-panel", false)) summon()
        AppLog.app.info { "devtools launch tuning=${TuningStore.shared.isEnabled} timeScale=${AnimationTimeScale.shared.scale} labMarks=${LabMarks.isEnabled}" }
    }

    /** The secret gesture: shows the panel, turning tuning on first if it was off, or hides it again. */
    fun summon() {
        if (!TuningAccess.isAvailable) return
        if (!TuningStore.shared.isEnabled) setTuningEnabled(true)
        tuningStore.send(TuningFeature.Action.Summoned)
    }

    /** The quick toggle: `1×` ↔ the last slow speed, turning tuning on first (for the speed badge) without showing the panel. */
    fun toggleSlowMotion() {
        if (!TuningAccess.isAvailable) return
        if (!TuningStore.shared.isEnabled) setTuningEnabled(true)
        tuningStore.send(TuningFeature.Action.SlowMotionToggled)
    }

    /** Settings' Open Tuning Panel: shows the panel (never toggles it away) over the app, closing the overlay's Developer screen. */
    fun showPanel() {
        if (!TuningAccess.isAvailable) return
        if (!TuningStore.shared.isEnabled) setTuningEnabled(true)
        tuningStore.send(TuningFeature.Action.DeveloperToolsClosed)
        if (!tuningStore.state.isPanelShown) tuningStore.send(TuningFeature.Action.Summoned)
    }

    /** Settings' Tuning switch. Call from a reducer: it writes the shared key. */
    fun setTuningEnabled(isOn: Boolean) {
        shared(TuningSharedKeys.tuningEnabled).set(isOn)
        applyTuning(isOn)
    }

    /** Developer Mode changed: outside debug builds, tuning and slow motion leave with it. */
    fun accessChanged() {
        if (!TuningAccess.isAvailable) {
            if (TuningStore.shared.isEnabled) setTuningEnabled(false)
            AnimationTimeScale.shared.reset()
        }
    }

    private fun applyTuning(isOn: Boolean) {
        TuningStore.shared.setEnabled(isOn)
        if (isOn) {
            MotionTunables.register()
        } else {
            runCatching { tuningStore.send(TuningFeature.Action.Stopped) }
        }
    }
}

/** Android developer tools' own switches. */
object DevToolsKeys {
    /** The perf HUD: frame rate, janky frames, worst frame and recompositions per second. */
    val perfHud: SharedKey<Boolean> = SharedKey.appStorage("devtools-perf-hud", false)
    /** Outlines every semantics node; touch targets under 48 dp are filled red. */
    val layoutBounds: SharedKey<Boolean> = SharedKey.appStorage("devtools-layout-bounds", false)
    /** The last fixture address the lab switcher used ("10.0.2.2:4110"). */
    val labAddress: SharedKey<String> = SharedKey.appStorage("devtools-lab-address", "10.0.2.2:4110")
}
