package ai.opencode.devtools.tuning

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.core.tuning.AnimationTimeScale
import ai.opencode.core.tuning.TuneValue
import ai.opencode.core.tuning.TuningContext
import ai.opencode.core.tuning.TuningSharedKeys
import ai.opencode.core.tuning.TuningStore
import kotlin.time.Duration.Companion.seconds

/**
 * The slow-motion choice: the speed now, and the slow speed the quick toggle returns to. In memory
 * only.
 */
data class SlowMotion(val scale: Double = 1.0, val lastSlowScale: Double = DEFAULT_SLOW_SCALE) {
    val isSlowed: Boolean get() = scale != 1.0

    /** A speed from the panel; a slow one becomes the toggle's. */
    fun select(requested: Double): SlowMotion {
        val next = AnimationTimeScale.normalizeScale(requested)
        return SlowMotion(next, if (next != 1.0) next else lastSlowScale)
    }

    /** `1×` to the last slow speed, or back to `1×`. */
    fun toggle(): SlowMotion = select(if (isSlowed) 1.0 else lastSlowScale)

    companion object {
        val scales: List<Double> = AnimationTimeScale.supportedScales
        const val DEFAULT_SLOW_SCALE = 0.25

        /** "0.25×". */
        fun label(scale: Double): String = if (scale == Math.rint(scale)) "${scale.toInt()}×" else "$scale×"
    }
}

/** The floating Tuning Panel's state and actions. The panel edits [TuningStore] live. */
class TuningFeature : Reducer<TuningFeature.State, TuningFeature.Action> {
    data class State(
        /** On screen at all: the panel or its pill. */
        val isPanelShown: Boolean = false,
        /** Every group, not just this screen's. */
        val showsAll: Boolean = false,
        /** Folded into the pill. Kept across launches. */
        val isCollapsed: Boolean = false,
        val didCopy: Boolean = false,
        val lastCopied: String? = null,
        /** Where the panel floats. Kept across launches. */
        val placement: TuningPlacement = TuningPlacement(),
        /** Groups folded to their header. Kept across launches. */
        val foldedGroups: Set<String> = emptySet(),
        /** The Developer screen, opened over the app from the panel's menu. */
        val showsDeveloperTools: Boolean = false,
        val slowMotion: SlowMotion = SlowMotion(),
    )

    sealed interface Action {
        data object Task : Action
        data object Stopped : Action
        /** The summon gesture (three-finger double tap / four-finger hold) or Settings' button. */
        data object Summoned : Action
        data object CloseTapped : Action
        data object CollapseTapped : Action
        data object ExpandTapped : Action
        data object ShowsAllToggled : Action
        data class GroupHidden(val group: String, val hidden: Boolean) : Action
        data object TurnOffTapped : Action
        data class ValueChanged(val key: String, val value: TuneValue?) : Action
        data class PresetSelected(val key: String, val index: Int) : Action
        data class RandomizeTapped(val group: String) : Action
        data class UndoRollTapped(val group: String) : Action
        data class ResetTapped(val group: String?) : Action
        data class CopyValuesTapped(val group: String?) : Action
        data class CopyKotlinTapped(val group: String?) : Action
        data object CopiedShown : Action
        /** The panel, pill or tab was thrown, or the tab pulled out: where it lands. */
        data class PlacementChanged(val placement: TuningPlacement) : Action
        data class GroupFolded(val group: String, val folded: Boolean) : Action
        data object DeveloperToolsTapped : Action
        data object DeveloperToolsClosed : Action
        /** The panel's Slow Motion control. */
        data class SlowMotionSelected(val scale: Double) : Action
        /** The quick toggle (`summonGesture`): `1×` ↔ the last slow speed. */
        data object SlowMotionToggled : Action
    }

    private enum class CancelID { Copied }

    override fun reduce(state: State, action: Action): Reduced<State, Action> {
        val tuning = dependency(TuningStore)
        return when (action) {
            Action.Task -> Reduced(
                state.copy(
                    isCollapsed = shared(TuningPanelKeys.collapsed).value,
                    placement = TuningPlacement.fromText(shared(TuningPanelKeys.placement).value),
                    foldedGroups = shared(TuningPanelKeys.foldedGroups).value.split(',').filter { it.isNotEmpty() }.toSet(),
                ),
            )
            Action.Stopped -> Reduced(state.copy(isPanelShown = false))
            Action.Summoned -> {
                val shown = !state.isPanelShown
                if (shown) dependency(HapticsClient).play(Haptic.confirm)
                Reduced(state.copy(isPanelShown = shown))
            }
            Action.CloseTapped -> Reduced(state.copy(isPanelShown = false))
            Action.CollapseTapped -> {
                shared(TuningPanelKeys.collapsed).set(true)
                Reduced(state.copy(isCollapsed = true))
            }
            Action.ExpandTapped -> {
                shared(TuningPanelKeys.collapsed).set(false)
                Reduced(state.copy(isCollapsed = false))
            }
            Action.ShowsAllToggled -> Reduced(state.copy(showsAll = !state.showsAll))
            is Action.GroupHidden -> {
                tuning.setHidden(action.group, action.hidden)
                Reduced(state)
            }
            Action.TurnOffTapped -> {
                shared(TuningSharedKeys.tuningEnabled).set(false)
                tuning.setEnabled(false)
                AnimationTimeScale.shared.reset()
                Reduced(state.copy(isPanelShown = false, slowMotion = state.slowMotion.select(1.0)))
            }
            is Action.SlowMotionSelected -> {
                if (AnimationTimeScale.normalizeScale(action.scale) == state.slowMotion.scale) return Reduced(state)
                applySlowMotion(state.slowMotion.select(action.scale), state)
            }
            Action.SlowMotionToggled -> applySlowMotion(state.slowMotion.toggle(), state)
            is Action.ValueChanged -> {
                tuning.set(action.key, action.value)
                Reduced(state)
            }
            is Action.PresetSelected -> {
                tuning.applyPreset(action.key, action.index)
                Reduced(state)
            }
            is Action.RandomizeTapped -> {
                tuning.randomize(action.group)
                Reduced(state)
            }
            is Action.UndoRollTapped -> {
                tuning.undoRoll(action.group)
                Reduced(state)
            }
            is Action.ResetTapped -> {
                tuning.resetAll(action.group)
                Reduced(state)
            }
            is Action.CopyValuesTapped -> copySnippet(tuning.valuesSnippet(action.group), state)
            is Action.CopyKotlinTapped -> copySnippet(tuning.kotlinSnippet(action.group), state)
            Action.CopiedShown -> Reduced(state.copy(didCopy = false))
            is Action.PlacementChanged -> {
                if (action.placement == state.placement) return Reduced(state)
                shared(TuningPanelKeys.placement).set(action.placement.text)
                Reduced(state.copy(placement = action.placement))
            }
            Action.DeveloperToolsTapped -> Reduced(state.copy(showsDeveloperTools = true, isPanelShown = false))
            Action.DeveloperToolsClosed -> Reduced(state.copy(showsDeveloperTools = false))
            is Action.GroupFolded -> {
                val folded = if (action.folded) state.foldedGroups + action.group else state.foldedGroups - action.group
                shared(TuningPanelKeys.foldedGroups).set(folded.sorted().joinToString(","))
                Reduced(state.copy(foldedGroups = folded))
            }
        }
    }

    private fun copySnippet(snippet: String, state: State): Reduced<State, Action> {
        if (snippet.isEmpty()) return Reduced(state)
        dependency(HapticsClient).play(Haptic.tap)
        val pasteboard = dependency(PasteboardClient)
        val clock = dependency(Clock)
        return Reduced(
            state.copy(didCopy = true, lastCopied = snippet),
            Effect.run<Action> { send ->
                pasteboard.setString(snippet)
                clock.sleep(1.5.seconds)
                send(Action.CopiedShown)
            }.cancellable(CancelID.Copied, cancelInFlight = true),
        )
    }

    /** Slows the app's clock. */
    private fun applySlowMotion(next: SlowMotion, state: State): Reduced<State, Action> {
        dependency(HapticsClient).play(Haptic.selection)
        AnimationTimeScale.shared.setScale(next.scale)
        return Reduced(state.copy(slowMotion = next))
    }
}

/** Tuning panel keys (`tuning-panel-collapsed`, folded groups and `tuning-placement`). */
object TuningPanelKeys {
    val collapsed: SharedKey<Boolean> = SharedKey.appStorage("tuning-panel-collapsed", false)
    val foldedGroups: SharedKey<String> = SharedKey.appStorage("tuning-folded-groups", "")
    /** [TuningPlacement.text], JSON; anything unreadable is the default. */
    val placement: SharedKey<String> = SharedKey.appStorage("tuning-placement", "")
}

/**
 * This screen's groups (then the global ones) that have tunables and aren't settled; with All,
 * every unsettled group, this screen's first.
 */
object TuningPanelGroups {
    /** [TuningContext.globalGroups]. */
    val globalGroups: List<String> get() = TuningContext.globalGroups

    fun shown(registered: List<String>, context: TuningContext, showsAll: Boolean, hidden: Set<String>): List<String> {
        val available = registered.filter { it !in hidden }
        val here = context.panelGroups.filter { it in available }
        if (!showsAll) return here
        return here + available.filter { it !in here }
    }
}
