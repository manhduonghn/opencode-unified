package ai.opencode.feature.session

import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRelease
import ai.opencode.core.motion.MotionRubberBand
import ai.opencode.core.motion.SpringSolver
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Velocities in dp/s.

/**
 * Where the background-work surface is: one surface animates its bounds between the capsule's and
 * the panel's.
 */
enum class BackgroundWorkStage {
    /** The capsule's surface, at rest. */
    closed,

    /** The panel's surface morphing back into the capsule's. */
    capsule,
    panel,
}

/** Shipped defaults; there's no tuning UI. */
object BackgroundWorkTunables {
    /** Drag closes past (dp). */
    const val dragCloses: Float = 56f

    /** Throw closes at (dp/s). */
    const val throwCloses: Float = 550f

    /** Drag scale span (dp). */
    const val dragScaleSpan: Float = 200f

    /** Drag min scale. */
    const val dragMinScale: Float = 0.90f

    /** Upward rubber band (dp). */
    const val upwardRubberBand: Float = 28f

    /**
     * The capsule's glyph frame from its leading edge, and its count lowered so the digits' caps
     * sit centred.
     */
    const val glyphLeading: Float = 8.25f
    const val countNudge: Float = 1f / 3f

    /** The orb's and the shell glyph's disc sits a little high in its frame. */
    const val orbGlyphNudgeY: Float = 1f / 3f

    /** Between the orb and the shell disc when both run. */
    const val pairGap: Float = 4f
}

object BackgroundWorkMotion {
    /** The capsule's surface grows into the panel (`panelOpen`) and folds back (`panelClose`). */
    fun <T> morph(opening: Boolean, reduceMotion: Boolean): FiniteAnimationSpec<T> =
        Motion.panelOpen.animation(if (opening) MotionPhase.Enter else MotionPhase.Exit, reduceMotion)

    /** Reduce Motion's cross-fade, for the capsule's counts. */
    fun <T> fade(): FiniteAnimationSpec<T> = Motion.reduced.animation(reduceMotion = true)

    /**
     * The fold back into the capsule, carrying a downward throw's speed (relative to the distance
     * left, at most 4×) into `panelClose`, so it never bounces. Returns the spec and that initial
     * velocity, which Compose takes as the `Animatable`'s initial velocity.
     */
    fun closeAnimation(releaseVelocity: Float, remainingDistance: Float, reduceMotion: Boolean): Pair<FiniteAnimationSpec<Float>, Float> {
        val relative = MotionRelease.relativeVelocity(releaseVelocity.toDouble(), 0.0, max(remainingDistance, 40f).toDouble())
        val velocity = Motion.panelClose.initialVelocity(min(max(relative, 0.0), 4.0).toFloat(), reduceMotion)
        return Motion.panelClose.animation<Float>(reduceMotion = reduceMotion) to velocity
    }

    /**
     * The capsule following its counts: its surface, words and glyph on one spring (`layout`),
     * unbouncy so a word leaving is never cut by the surface coming back.
     */
    fun <T> resize(): FiniteAnimationSpec<T> = Motion.layout.animation()

    const val capsuleInset: Float = 5f
    /** The desktop dock's 12: the capsule is a 34 dp chip with 12 dp corners. */
    const val panelRadius: Float = 12f
}

/**
 * Pure state machine for dragging `BackgroundWorkPanel` down toward its capsule or rubber-banding
 * upward, including scroll-top takeover, threshold haptics, release decision, and mid-return regrab
 * continuity. [copy] gives an independent value.
 */
data class BackgroundWorkDrag(
    /** Current effective vertical offset in dp (`> 0` downward, `< 0` upward rubber-banded). */
    var offset: Float = 0f,
    /** Current vertical velocity in `offset`-space (dp/s). */
    var velocity: Float = 0f,
    /** True while a finger owns the panel drag. */
    var isDragging: Boolean = false,
    /** True while [SpringSolver] is returning a below-threshold drag to `0`. */
    var isReturning: Boolean = false,
    /** True once the current drag has crossed `dragCloses` downward. */
    var isPastThreshold: Boolean = false,
    /** Where the panel stood (`offset`) when the current drag engaged, so a regrab mid-return starts with zero jump. */
    var grabOffset: Float = 0f,
    /**
     * The gesture translation at the moment the panel drag engaged (after touch slop or after a
     * scrolled list reached its top), so the first dragged frame steps from [grabOffset] without jumping.
     */
    var translationOrigin: Float? = null,
    /** Previous gesture translation sample for scroll-top direction detection. */
    var lastTranslation: Float? = null,
) {
    sealed interface ReleaseDecision {
        /** Spring back to the open panel (`offset = 0`), carrying [velocity] (dp/s). */
        data class ReturnToPanel(val velocity: Float) : ReleaseDecision

        /** Morph from the current dragged frame into the capsule, carrying [velocity] (dp/s). */
        data class DismissToCapsule(val velocity: Float) : ReleaseDecision
    }

    /** Prepares to catch the panel at [inFlightOffset] mid-return on the next drag sample with zero position jump. */
    fun regrab(inFlightOffset: Float) {
        offset = inFlightOffset
        grabOffset = inFlightOffset
        translationOrigin = null
        lastTranslation = null
        isReturning = false
        isPastThreshold = inFlightOffset > BackgroundWorkTunables.dragCloses
    }

    /** Clears active drag or return flags when the panel starts closing, preserving `offset` so the close morph begins where the panel is. */
    fun stopForClose() {
        isDragging = false
        isReturning = false
        translationOrigin = null
        lastTranslation = null
        isPastThreshold = false
    }

    /** Updates the drag from a gesture sample. Returns `true` when the drag crosses the dismiss threshold downward and should play [Haptic.threshold]. */
    fun beginOrUpdate(translation: Float, velocity: Float, scrollOffset: Float = 0f, isScrollable: Boolean = false): Boolean {
        val previousTranslation = lastTranslation
        lastTranslation = translation

        if (translationOrigin == null) {
            if (isReturning) {
                grabOffset = offset
                isReturning = false
            } else if (!isDragging) {
                grabOffset = offset
            }
            if (isScrollable) {
                val deltaY = translation - (previousTranslation ?: 0f)
                // A scrolled list scrolls first and only hands off to the panel at its top.
                if (!(scrollOffset <= 0.5f && (deltaY > 0 || grabOffset > 0.5f))) return false
            }
            translationOrigin = translation
            isDragging = true
            isPastThreshold = grabOffset > BackgroundWorkTunables.dragCloses
        }

        val origin = translationOrigin ?: return false
        val limit = BackgroundWorkTunables.upwardRubberBand
        var raw = rawPull(grabOffset, limit) + (translation - origin)
        if (isScrollable && raw < 0) {
            // Pushing back up past the open position on a scrollable list hands upward movement
            // back to the list's scroll view without jumping.
            translationOrigin = translation
            grabOffset = 0f
            raw = 0f
            if (scrollOffset > 0.5f) {
                translationOrigin = null
                isDragging = false
            }
        }

        offset = rubberBandedOffset(raw, limit)
        this.velocity = velocity * rubberBandDerivative(raw, limit)

        val threshold = BackgroundWorkTunables.dragCloses
        if (offset > threshold && !isPastThreshold) {
            isPastThreshold = true
            return true
        } else if (offset <= threshold - 2 && isPastThreshold) {
            isPastThreshold = false
        }
        return false
    }

    /** Decides whether releasing the finger morphs back into the capsule or springs back to the open panel. Preserves `offset` on dismiss. */
    fun end(rawVelocity: Float): ReleaseDecision {
        val wasDragging = isDragging
        isDragging = false
        translationOrigin = null
        lastTranslation = null
        isPastThreshold = false

        if (!(wasDragging || abs(offset) > 0.01f)) {
            isReturning = false
            return ReleaseDecision.ReturnToPanel(0f)
        }

        val limit = BackgroundWorkTunables.upwardRubberBand
        val currentRaw = rawPull(offset, limit)
        val effectiveVelocity = rawVelocity * rubberBandDerivative(currentRaw, limit)
        velocity = effectiveVelocity

        val dismissedByDistance = offset > BackgroundWorkTunables.dragCloses && rawVelocity >= -120
        val dismissedByVelocity = offset > 12 && rawVelocity > BackgroundWorkTunables.throwCloses

        return if (dismissedByDistance || dismissedByVelocity) {
            isReturning = false
            ReleaseDecision.DismissToCapsule(max(rawVelocity, 0f))
        } else {
            isReturning = abs(offset) > 0.01f || abs(effectiveVelocity) > 1
            ReleaseDecision.ReturnToPanel(effectiveVelocity)
        }
    }

    /** Advances a below-threshold return by [dt] seconds on [solver]. Returns `true` once the panel has settled back at `offset == 0`. */
    fun stepReturn(solver: SpringSolver, dt: Double): Boolean {
        val step = solver.step(dt, snapWhenSettled = true)
        offset = step.value.toFloat()
        velocity = step.velocity.toFloat()
        if (step.isSettled) {
            offset = 0f
            velocity = 0f
            isReturning = false
            grabOffset = 0f
            return true
        }
        return false
    }

    /**
     * The panel's frame and content scale for the current `offset`. Dragging down translates the
     * panel and scales it down slightly toward the capsule's leading/bottom frame; under Reduce
     * Motion it translates 1:1 without scaling. Rects in dp.
     */
    fun placed(panel: Rect, pill: Rect, reduceMotion: Boolean): Pair<Rect, Float> {
        if (abs(offset) <= 0.001f) return panel to 1f
        if (offset <= 0 || reduceMotion) return panel.translate(0f, offset) to 1f
        val span = max(BackgroundWorkTunables.dragScaleSpan, 1f)
        val minScale = min(max(BackgroundWorkTunables.dragMinScale, 0.5f), 1f)
        val progress = offset / (offset + span)
        val scale = 1 - (1 - minScale) * progress
        val width = max(pill.width, panel.width * scale)
        val height = max(pill.height, panel.height * scale)
        return Rect(panel.left, panel.top + offset, panel.left + width, panel.top + offset + height) to scale
    }

    companion object {
        private const val UPWARD_SLOPE = 0.35

        /** Above the open position the panel rubber-bands ([MotionRubberBand], never more than [limit]); down it follows the finger 1:1. */
        private fun band(limit: Float): MotionRubberBand = MotionRubberBand(limit.toDouble(), UPWARD_SLOPE)

        private val downward = 0.0..Double.MAX_VALUE

        /** Maps raw finger pull (`> 0` downward, `< 0` upward) to vertical offset. */
        fun rubberBandedOffset(rawPull: Float, limit: Float = BackgroundWorkTunables.upwardRubberBand): Float =
            band(limit).band(rawPull.toDouble(), downward).toFloat()

        /** The exact inverse of [rubberBandedOffset], so a regrab while rubber-banded upward resumes from the drawn `offset`. */
        fun rawPull(offset: Float, limit: Float = BackgroundWorkTunables.upwardRubberBand): Float =
            band(limit).unband(offset.toDouble(), downward).toFloat()

        /** `d offset / d rawPull`, so an upward release hands the spring the speed the panel is drawn moving. */
        fun rubberBandDerivative(rawPull: Float, limit: Float = BackgroundWorkTunables.upwardRubberBand): Float =
            if (rawPull < 0) band(limit).slope(rawPull.toDouble()).toFloat() else 1f
    }
}

/**
 * Drives interactive dragging, the frame-clock [SpringSolver] return-to-open, threshold haptics,
 * and mid-return regrab. The view reads [offset] in its layout/draw phase and steps [stepReturn]
 * from a `motionFrameLoop` while [isReturning].
 */
class BackgroundWorkDragController(private val haptics: HapticsClient) {
    var drag: BackgroundWorkDrag = BackgroundWorkDrag()
        private set

    /** Observable mirror of `drag.offset` and `drag.isReturning` for Compose. */
    private val offsetState = androidx.compose.runtime.mutableFloatStateOf(0f)
    private val returningState = androidx.compose.runtime.mutableStateOf(false)
    val offset: Float get() = offsetState.floatValue
    val isReturning: Boolean get() = returningState.value

    /** Remove animations, set by the view. */
    var reduceMotion: Boolean = false

    private var returnSolver = SpringSolver(Motion.release, value = 0.0, target = 0.0, positionEpsilon = 0.15, velocityEpsilon = 2.0)

    private fun publish() {
        offsetState.floatValue = drag.offset
        returningState.value = drag.isReturning
    }

    /** Updates the drag for a gesture sample. */
    fun update(translation: Float, velocity: Float, scrollOffset: Float = 0f, isScrollable: Boolean = false) {
        if (drag.translationOrigin == null) {
            if (drag.isReturning) drag.regrab(drag.offset)
            haptics.prepare(Haptic.threshold)
        }
        if (drag.beginOrUpdate(translation, velocity, scrollOffset, isScrollable)) haptics.play(Haptic.threshold)
        publish()
    }

    /**
     * Finishes the current drag, either starting the return spring to `offset = 0` (the view's
     * frame loop then steps [stepReturn] while [isReturning]) or invoking [onDismiss] with
     * `drag.offset` held at its release position for the capsule morph.
     */
    fun end(velocity: Float, onDismiss: (Float) -> Unit = {}): BackgroundWorkDrag.ReleaseDecision {
        val decision = drag.end(velocity)
        when (decision) {
            is BackgroundWorkDrag.ReleaseDecision.ReturnToPanel -> {
                if (!drag.isReturning) {
                    drag.offset = 0f
                    drag.grabOffset = 0f
                } else {
                    // Back open on the release spring, carrying the finger's speed.
                    returnSolver = SpringSolver(
                        Motion.release,
                        value = drag.offset.toDouble(),
                        velocity = decision.velocity.toDouble(),
                        target = 0.0,
                        reduceMotion = reduceMotion,
                        positionEpsilon = 0.15,
                        velocityEpsilon = 2.0,
                    )
                }
                publish()
            }
            is BackgroundWorkDrag.ReleaseDecision.DismissToCapsule -> {
                publish()
                onDismiss(decision.velocity)
            }
        }
        return decision
    }

    /** Steps the return spring (from the view's frame loop). True once settled. */
    fun stepReturn(dt: Double): Boolean {
        if (!drag.isReturning) return true
        val settled = drag.stepReturn(returnSolver, dt)
        publish()
        return settled
    }

    /** Stops any in-flight return spring or active drag before the panel morphs back into the capsule, preserving `drag.offset` as the close morph's start. */
    fun stopReturn() {
        drag.stopForClose()
        publish()
    }

    fun reset() {
        drag = BackgroundWorkDrag()
        publish()
    }
}
