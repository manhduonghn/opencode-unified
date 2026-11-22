package ai.opencode.feature.drawer.catchup

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.runtime.Stable
import androidx.compose.ui.MotionDurationScale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The session card as the top of a Catch Up stack. */
data class CatchUpStage(
    /** Sessions still to show; the first is the open card. */
    val queue: List<String>,
    /** How the most recently handled card left, for Undo to reverse. */
    val undoDirection: CatchUpDisposition? = null,
    val onDismiss: (String, CatchUpDisposition) -> Unit = { _, _ -> },
    val onUndo: () -> Unit = {},
    val onExit: () -> Unit = {},
    /** The stack emptied: Catch Up closes itself (`CatchUpFinish.kt`). */
    val onFinish: () -> Unit = {},
) {
    val cardID: String? get() = queue.firstOrNull()
    val nextCardID: String? get() = queue.getOrNull(1)
}

/** Lets the chrome's buttons play the same choreography as a swipe. */
class CatchUpStageProxy {
    var dismiss: (CatchUpDisposition) -> Unit = {}
    var undo: () -> Unit = {}

    fun dismissCard(disposition: CatchUpDisposition) = dismiss(disposition)
    fun undoCard() = undo()
}

/** Translation (dp), rotation (radians) and uniform scale about the centre. */
data class CatchUpPose(val tx: Float = 0f, val ty: Float = 0f, val rotation: Float = 0f, val scale: Float = 1f) {
    companion object {
        val Identity = CatchUpPose()
    }
}

/**
 * Owns the stack's motion: the drag, the fling that hands the next card forward, Undo's return,
 * and the preview card beneath. A plain object: per-frame values are snapshot state read in the
 * draw phase, never in a reducer or a recomposition.
 *
 * The live session card is the only interactive card, and it is the card that flies away. The
 * store hears a dismissal once the card is off screen; the live card then shows the next session
 * and fades in over the preview, which already rose into its place showing that same session.
 */
@Stable
class CatchUpStackController internal constructor() {
    // Environment, set by the composable.
    var reducedMotion = false
    internal var isDark = false
    internal var containerWidth = 1f
    internal var cardHeight = 1f
    internal var endEditing: () -> Unit = {}
    internal lateinit var scope: CoroutineScope

    private val haptics = dependency(HapticsClient)

    /** The live card's travel from rest (dp). */
    var dragOffset by mutableStateOf(Offset.Zero)
        private set
    var liveAlpha by mutableFloatStateOf(1f)
    /** 0 peeks out from behind the live card; 1 sits where the live card rests. */
    var previewProgress by mutableFloatStateOf(0f)
        private set
    var previewAlpha by mutableFloatStateOf(1f)
    /** The session the preview card shows, `null` for none. */
    var shownPreviewID by mutableStateOf<String?>(null)
        private set
    var isPreviewHidden by mutableStateOf(true)
        private set
    /** The live card's border tinted toward the swipe's side, or `null` for its normal border. */
    var dragBorderColor by mutableStateOf<Color?>(null)
        private set
    val readBadge = CatchUpBadgeState(CatchUpDisposition.markRead)
    val keepBadge = CatchUpBadgeState(CatchUpDisposition.keepUnread)
    var isContentEnabled by mutableStateOf(false)
        private set

    private var stage: CatchUpStage? = null
    private var isDragging = false
    private var isSettling = false
    private var isTransitioning = false
    private var previewFollowsCard = true
    private var dragStartOffset = Offset.Zero
    private var armedDisposition: CatchUpDisposition? = null
    private var lastMovement = 0L
    private var cardJob: Job? = null
    private var previewJob: Job? = null
    private var pendingDismiss: (() -> Unit)? = null
    private var finishJob: Job? = null

    val isPreviewLocked: Boolean get() = isDragging || isTransitioning

    internal val currentStage: CatchUpStage? get() = stage

    // State

    /** The card behind waits for the open card to settle, then rises into view ([revealPreview]). */
    fun begin(stage: CatchUpStage) {
        this.stage = stage
        cancelFinish()
        finishTransition()
        previewAlpha = 0f
    }

    /** The card behind comes out from under the open card; under Reduce Motion it fades in where it rests. */
    fun revealPreview(animated: Boolean) {
        previewJob?.cancel()
        previewJob = null
        if (!animated) {
            previewAlpha = 1f
            previewProgress = 0f
            return
        }
        val fromAlpha = if (reducedMotion) 0f else previewAlpha
        previewJob = scope.launch(KeepsMotionDuration) {
            animate(0f, 1f, animationSpec = MotionRole.Layout.animation(reduceMotion = reducedMotion)) { p, _ ->
                previewAlpha = fromAlpha + (1f - fromAlpha) * p
                previewProgress = if (reducedMotion) 0f else 1f - p
            }
            previewJob = null
        }
    }

    fun end() {
        cancelFinish()
        stopJobs()
        runPendingDismiss()
        isDragging = false
        isSettling = false
        isTransitioning = false
        previewFollowsCard = true
        dragStartOffset = Offset.Zero
        moveCardQuietly(Offset.Zero)
        stage = null
        releasePreview()
    }

    /** Emptied without a swipe (another device read the last card): Catch Up closes itself `closeDelay` later. */
    private fun scheduleFinish(finish: () -> Unit) {
        if (finishJob != null) return
        finishJob = scope.launch {
            delay(Motion.millis(CatchUpFinishTunables.closeDelay).toLong())
            finishJob = null
            finish()
        }
    }

    private fun cancelFinish() {
        finishJob?.cancel()
        finishJob = null
    }

    fun releasePreview() {
        isPreviewHidden = true
        previewAlpha = 1f
        shownPreviewID = null
    }

    fun update(stage: CatchUpStage) {
        val previous = this.stage
        this.stage = stage
        if (stage.cardID == null) scheduleFinish(stage.onFinish) else cancelFinish()
        // A running swipe or Undo settles against the latest stage when it lands.
        if (isPreviewLocked) return
        syncPreview()
        if (stage.cardID == previous?.cardID) return
        if (stage.cardID == null) {
            isContentEnabled = false
            fadeLive(to = 0f)
        } else if (previous?.cardID == null) {
            isContentEnabled = true
            fadeLive(to = 1f)
        }
        // Otherwise another device moved the card (deleted or reordered): it changes in place.
    }

    /** Keeps the latest callbacks without treating it as a change (closures are new each composition). */
    internal fun refreshCallbacks(stage: CatchUpStage) {
        this.stage = stage
    }

    private fun syncPreview() {
        val next = stage?.nextCardID
        isPreviewHidden = next == null
        shownPreviewID = next
    }

    // Geometry

    private val dimAlpha: Float get() = if (isDark) 0.62f else 0.24f

    val previewDim: Float get() = dimAlpha * (1f - previewProgress)

    /** Border alpha factor of the preview card (0.42 at rest, 1 in front). */
    val previewBorderFactor: Float get() = 0.42f + 0.58f * previewProgress

    private val threshold: Float get() = max(containerWidth * CatchUpTunables.commitFraction, 1f)

    private val hapticDistance: Float get() = max(threshold * CatchUpTunables.hapticThreshold, 1f)

    /** The back card scales and brightens across the full card travel, not just to the commit threshold. */
    private val revealDistance: Float get() = max(containerWidth * CatchUpTunables.revealFraction, 1f)

    /** Smaller and lower, so a band of it shows beneath the open card. */
    fun previewPose(): CatchUpPose {
        if (reducedMotion) return CatchUpPose.Identity
        val progress = previewProgress.coerceIn(0f, 1f)
        val baseScale = CatchUpTunables.backScale
        val scale = baseScale + (1 - baseScale) * progress
        val height = max(cardHeight, 1f)
        val lift = (height * CatchUpTunables.backLiftRatio + CatchUpTunables.backOffset) * (1 - progress)
        val angle = CatchUpTunables.backRotation * (1 - progress)
        return CatchUpPose(tx = 0f, ty = lift, rotation = angle, scale = scale)
    }

    fun livePose(): CatchUpPose {
        val offset = dragOffset
        if (reducedMotion) return CatchUpPose(tx = offset.x)
        val rotation = -offset.x / max(containerWidth, 1f) * CatchUpTunables.dragRotation
        return CatchUpPose(tx = offset.x, ty = offset.y * CatchUpTunables.rubberBand, rotation = rotation)
    }

    private fun setFeedback(offset: Float, animatedPop: Boolean) {
        val targetDistance = hapticDistance
        val progress = min(abs(offset) / targetDistance, 1f)
        val popScope = if (::scope.isInitialized) scope else null
        readBadge.update(if (offset > 0) offset else 0f, targetDistance, reducedMotion, animatedPop, popScope)
        keepBadge.update(if (offset < 0) -offset else 0f, targetDistance, reducedMotion, animatedPop, popScope)
        dragBorderColor = if (progress > 0.01f) {
            val tone = if (offset >= 0) CatchUpMetrics.readColor else CatchUpMetrics.keepColor
            tone.copy(alpha = 0.45f + 0.50f * progress)
        } else {
            null
        }
    }

    private fun dispositionPastHapticThreshold(offsetX: Float): CatchUpDisposition? {
        if (abs(offsetX) < hapticDistance) return null
        return if (offsetX > 0) CatchUpDisposition.markRead else CatchUpDisposition.keepUnread
    }

    /** Moves the live card, with the preview, the swipe overlays and the threshold haptic following it. */
    private fun moveCard(offset: Offset, isSettling: Boolean) {
        dragOffset = offset
        if (previewFollowsCard) previewProgress = min(abs(offset.x) / revealDistance, 1f)
        setFeedback(offset.x, animatedPop = !isSettling)
        if (isSettling) return
        val armed = dispositionPastHapticThreshold(offset.x)
        if (armed != armedDisposition) {
            armedDisposition = armed
            if (armed != null) haptics.play(Haptic.threshold)
        }
    }

    /** Moves the live card alone: no overlay, no haptic. */
    private fun moveCardQuietly(offset: Offset) {
        dragOffset = offset
        setFeedback(0f, animatedPop = false)
    }

    // Drag

    internal val canBeginDrag: Boolean get() = stage?.cardID != null || isSettling

    /** The pan began with [pan] already travelled. Returns whether the stack took it. */
    fun panBegan(pan: Offset): Boolean {
        var startOffset = Offset.Zero
        if (isSettling) {
            startOffset = dragOffset
            stopJobs()
            isSettling = false
            isTransitioning = false
        } else {
            finishTransition()
        }
        if (stage?.cardID == null) return false
        isDragging = true
        dragStartOffset = startOffset
        haptics.prepare(Haptic.threshold)
        armedDisposition = dispositionPastHapticThreshold(startOffset.x)
        endEditing()
        isContentEnabled = false
        lastMovement = System.nanoTime()
        moveCard(dragStartOffset + pan, isSettling = false)
        return true
    }

    fun panChanged(pan: Offset) {
        if (!isDragging) return
        lastMovement = System.nanoTime()
        moveCard(dragStartOffset + pan, isSettling = false)
    }

    fun panEnded(pan: Offset, velocity: Offset) {
        if (!isDragging) return
        isDragging = false
        val total = dragStartOffset + pan
        dragStartOffset = Offset.Zero
        // The last movement's speed lingers after the finger comes to rest; a held finger is not a flick.
        val rested = (System.nanoTime() - lastMovement) / 1e9 > 0.08
        val releaseVelocity = if (rested) Offset.Zero else velocity
        val disposition = CatchUpSwipeMath.decision(total, releaseVelocity, max(containerWidth, 1f))
        if (disposition != null && stage?.cardID != null) {
            fling(disposition, releaseVelocity)
        } else {
            settleBack(releaseVelocity)
        }
    }

    fun panCancelled() {
        if (!isDragging) return
        isDragging = false
        dragStartOffset = Offset.Zero
        settleBack(Offset.Zero)
    }

    private fun settleBack(velocity: Offset) {
        armedDisposition = null
        val startOffset = dragOffset
        if (hypot(startOffset.x, startOffset.y) < 0.5f) {
            finishSettle()
            return
        }
        isTransitioning = true
        isSettling = true
        // Only the release speed toward the centre carries over, so the card never steps outward first.
        var initialVelocity = Offset.Zero
        if (!reducedMotion) {
            val inwardX = if (velocity.x * startOffset.x < 0) velocity.x else 0f
            val inwardY = if (velocity.y * startOffset.y < 0) velocity.y else 0f
            initialVelocity = Offset(inwardX, inwardY)
        }
        cardJob = scope.launch(KeepsMotionDuration) {
            animate(
                typeConverter = Offset.VectorConverter,
                initialValue = startOffset,
                targetValue = Offset.Zero,
                initialVelocity = initialVelocity,
                animationSpec = MotionRole.Release.animation(reduceMotion = reducedMotion, visibilityThreshold = Offset(0.5f, 0.5f)),
            ) { offset, _ -> moveCard(offset, isSettling = true) }
            cardJob = null
            finishSettle()
        }
    }

    /**
     * Catch Up ended under the finger: the rest of the drag, or the snap-back it started, moves
     * nothing and can't dismiss a card. The card stays where it was while the stage fades.
     */
    internal fun dropDrag() {
        if (!isDragging && !isSettling) return
        if (isSettling) {
            stopJobs()
            isSettling = false
            isTransitioning = false
        }
        isDragging = false
        dragStartOffset = Offset.Zero
        armedDisposition = null
    }

    private fun finishSettle() {
        isSettling = false
        isTransitioning = false
        moveCardQuietly(Offset.Zero)
        val hasCard = stage?.cardID != null
        liveAlpha = if (hasCard) 1f else 0f
        isContentEnabled = hasCard
        syncPreview()
    }

    // Dismiss

    /** Flings the open card away along the finger's path and brings the next one forward. Buttons share the flight. */
    fun fling(disposition: CatchUpDisposition, velocity: Offset = Offset.Zero) {
        if (isSettling) {
            stopJobs()
            isSettling = false
            isTransitioning = false
        } else if (isTransitioning) {
            finishTransition()
        } else {
            stopJobs()
        }
        val stage = stage ?: return
        val cardID = stage.cardID ?: return
        isDragging = false
        // A swipe that crossed the threshold already ticked; a fast short flick or a button tap
        // commits with one now, at the moment of commit.
        if (armedDisposition != disposition) haptics.play(Haptic.threshold)
        armedDisposition = null
        endEditing()
        isContentEnabled = false
        isTransitioning = true
        previewFollowsCard = true
        shownPreviewID = stage.nextCardID
        isPreviewHidden = stage.nextCardID == null
        previewAlpha = 1f
        pendingDismiss = {
            this.stage = this.stage?.let { it.copy(queue = it.queue.drop(1)) }
            stage.onDismiss(cardID, disposition)
            // The last card takes Catch Up with it.
            if (stage.nextCardID == null) stage.onFinish()
        }

        val direction = if (disposition == CatchUpDisposition.markRead) 1f else -1f
        val width = max(containerWidth, 1f)
        val startOffset = dragOffset
        val startPreview = previewProgress
        val target = Offset(direction * width * 1.85f, startOffset.y)
        val towardVelocity = if (velocity.x * direction > 0) velocity.x else 0f
        cardJob = scope.launch(KeepsMotionDuration) {
            if (reducedMotion) {
                setFeedback(direction * hapticDistance, animatedPop = false)
                animate(0f, 1f, animationSpec = MotionRole.Flight.animation(reduceMotion = true)) { p, _ ->
                    liveAlpha = 1f - p
                    previewProgress = startPreview + (1f - startPreview) * p
                }
            } else {
                // The flight stops at the bound, where the tilted card has fully left the screen.
                val edge = width * 1.3f
                val flight = Animatable(startOffset, Offset.VectorConverter)
                flight.updateBounds(
                    lowerBound = Offset(-edge, -Float.MAX_VALUE),
                    upperBound = Offset(edge, Float.MAX_VALUE),
                )
                flight.animateTo(
                    targetValue = target,
                    animationSpec = MotionRole.Flight.animation(),
                    initialVelocity = Offset(towardVelocity, 0f),
                ) { moveCard(value, isSettling = true) }
            }
            land()
        }
    }

    /** The preview now sits where the open card belongs: the store moves on, and the live card fades in over it. */
    private suspend fun land() {
        moveCardQuietly(Offset.Zero)
        liveAlpha = 0f
        runPendingDismiss()
        if (stage?.cardID == null) {
            cardJob = null
            isTransitioning = false
            isPreviewHidden = true
            return
        }
        isContentEnabled = true
        previewFollowsCard = false
        animate(0f, 1f, animationSpec = MotionRole.Swap.animation(reduceMotion = reducedMotion)) { alpha, _ -> liveAlpha = alpha }
        cardJob = null
        isTransitioning = false
        previewFollowsCard = true
        // Behind the open card again, now showing the card after it.
        syncPreview()
        revealPreview(animated = true)
    }

    private fun runPendingDismiss() {
        val dismiss = pendingDismiss ?: return
        pendingDismiss = null
        dismiss()
    }

    // Undo

    /** Returns the last handled card from the side it left, while the open card goes back behind it. */
    fun undo() {
        finishTransition()
        val stage = stage ?: return
        val direction = stage.undoDirection
        if (direction == null) {
            stage.onUndo()
            return
        }
        val current = stage.cardID
        isTransitioning = true
        previewFollowsCard = false
        if (current != null) {
            // The card now behind the returning one is the current card.
            shownPreviewID = current
            isPreviewHidden = false
            previewProgress = 1f
        }
        stage.onUndo()
        isContentEnabled = true
        val sign = if (direction == CatchUpDisposition.markRead) 1f else -1f
        val startOffset = Offset(sign * max(containerWidth, 1f) * 1.85f, 0f)
        if (reducedMotion) {
            liveAlpha = 0f
        } else {
            moveCardQuietly(startOffset)
        }
        cardJob = scope.launch(KeepsMotionDuration) {
            // The card the person asked back arrives on `MotionRole.PanelOpen`; under Reduce Motion it fades in.
            animate(0f, 1f, animationSpec = MotionRole.PanelOpen.animation(reduceMotion = reducedMotion)) { p, _ ->
                if (reducedMotion) liveAlpha = p else moveCardQuietly(startOffset * (1f - p))
                if (current != null) previewProgress = 1f - p
            }
            cardJob = null
            isTransitioning = false
            previewFollowsCard = true
            moveCardQuietly(Offset.Zero)
            syncPreview()
        }
    }

    // Arrivals

    private fun fadeLive(to: Float) {
        val from = liveAlpha
        val phase = if (to > from) MotionPhase.Enter else MotionPhase.Exit
        previewJob?.cancel()
        previewJob = scope.launch(KeepsMotionDuration) {
            animate(from, to, animationSpec = MotionRole.Swap.animation(phase, reducedMotion)) { alpha, _ -> liveAlpha = alpha }
            previewJob = null
        }
    }

    // Helpers

    private fun stopJobs() {
        cardJob?.cancel()
        cardJob = null
        previewJob?.cancel()
        previewJob = null
    }

    /** Jumps whatever is running to its end so a new gesture starts from rest. */
    private fun finishTransition() {
        stopJobs()
        if (pendingDismiss != null) liveAlpha = 0f
        runPendingDismiss()
        isSettling = false
        isTransitioning = false
        previewFollowsCard = true
        moveCardQuietly(Offset.Zero)
        val hasCard = stage?.cardID != null
        liveAlpha = if (hasCard) 1f else 0f
        isContentEnabled = hasCard
        syncPreview()
        previewProgress = 0f
        previewAlpha = 1f
    }

    /** Lands a running swipe at once, so the store hears its dismissal before Catch Up closes. */
    fun settle() {
        if (pendingDismiss != null) finishTransition()
    }

    internal fun dispose() {
        stopJobs()
        cancelFinish()
    }
}

/**
 * Remove Animations sets the system animator scale to 0, which would snap these to their end;
 * Motion's specs already swap in the short Reduce Motion fade.
 */
internal val KeepsMotionDuration = object : MotionDurationScale {
    override val scaleFactor = 1f
}
