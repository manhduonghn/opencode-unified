package ai.opencode.feature.voice

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import androidx.compose.ui.geometry.Rect

/**
 * How far the floating call has grown: the orb, a card above the composer, or a sheet the height of
 * the screen. One shape grows through all three on one vertical drag.
 */
enum class FloatingCallStage {
    orb, card, full;

    /** One stage down, or null at the orb. */
    val previous: FloatingCallStage? get() = entries.getOrNull(ordinal - 1)

    /**
     * Whether the call grown this far covers the composer (`call.stage.covers`): the sheet
     * always, the card when it stands over the composer's span (on a wide screen it can stand
     * beside it). Either frame unknown, it's taken to cover. Frames are in window dp.
     */
    fun covers(composer: Rect?, card: Rect?): Boolean = when (this) {
        orb -> false
        full -> true
        FloatingCallStage.card -> if (composer == null || card == null || composer.width <= 0 || card.width <= 0) {
            true
        } else {
            card.left < composer.right && card.right > composer.left
        }
    }
}

/**
 * Which stage the floating call settles on, and the haptics for getting there. The per-frame motion
 * between stages is the view's; this only hears where a tap, a drag or its release lands.
 */
class FloatingCallStageFeature : Reducer<FloatingCallStageFeature.State, FloatingCallStageFeature.Action> {
    data class State(
        val stage: FloatingCallStage = FloatingCallStage.orb,
        /** The stage a drag in progress would settle on if let go now. */
        val dragTarget: FloatingCallStage? = null,
        /** A call row's request (a screen or link) is up above the composer: the orb stays an orb, so nothing grows over it. */
        val isHeld: Boolean = false,
    )

    sealed interface Action {
        /** The orb was tapped: it grows into the card. */
        data object OrbTapped : Action

        /** The chevron: straight back to the orb. */
        data object MinimizeTapped : Action

        /** TalkBack's escape: one stage down. */
        data object StepBack : Action
        data object DragBegan : Action

        /** The finger crossed into the reach of another stage. */
        data class DragCrossed(val stage: FloatingCallStage) : Action

        /** Let go: where the release (and its speed) carries it. */
        data class DragEnded(val stage: FloatingCallStage) : Action

        /** The playground opened or closed it. */
        data class Requested(val stage: FloatingCallStage) : Action

        /**
         * A pending request docked in the composer: the card or sheet, which cover the
         * composer, shrink back to the orb so the request shows.
         */
        data object RequestDocked : Action

        /**
         * A keyboard rose in the call's window: the card and sheet, standing at the screen's
         * foot, shrink back to the orb, which rides above it, so Mute and End are never under it.
         */
        data object KeyboardRose : Action
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> {
        val haptics = dependency(HapticsClient)
        return when (action) {
            Action.OrbTapped -> {
                if (state.stage != FloatingCallStage.orb || state.dragTarget != null || state.isHeld) return Reduced(state)
                haptics.play(Haptic.tap)
                Reduced(state.copy(stage = FloatingCallStage.card))
            }
            Action.MinimizeTapped -> Reduced(state.copy(dragTarget = null, stage = FloatingCallStage.orb))
            Action.StepBack -> {
                val previous = state.stage.previous ?: return Reduced(state)
                Reduced(state.copy(dragTarget = null, stage = previous))
            }
            Action.DragBegan -> if (state.isHeld) Reduced(state) else Reduced(state.copy(dragTarget = state.stage))
            is Action.DragCrossed -> {
                val target = state.dragTarget
                if (target == null || target == action.stage) return Reduced(state)
                haptics.play(Haptic.threshold)
                Reduced(state.copy(dragTarget = action.stage))
            }
            is Action.DragEnded -> {
                val cleared = state.copy(dragTarget = null)
                if (action.stage == state.stage || (state.isHeld && action.stage != FloatingCallStage.orb)) return Reduced(cleared)
                haptics.play(Haptic.snap)
                Reduced(cleared.copy(stage = action.stage))
            }
            is Action.Requested -> {
                if (state.isHeld && action.stage != FloatingCallStage.orb) return Reduced(state)
                Reduced(state.copy(dragTarget = null, stage = action.stage))
            }
            Action.RequestDocked, Action.KeyboardRose -> {
                if (state.stage == FloatingCallStage.orb || state.dragTarget != null) return Reduced(state)
                Reduced(state.copy(stage = FloatingCallStage.orb))
            }
        }
    }
}
