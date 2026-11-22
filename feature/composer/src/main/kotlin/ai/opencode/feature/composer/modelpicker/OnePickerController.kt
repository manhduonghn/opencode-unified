package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionTunables
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.min

/**
 * The per-frame pose, observed only by the leaves that draw it. Set only when it changes
 * ([OnePickerSimulation.FramePose] is a data class, so snapshot state compares it).
 */
@Stable
class OnePickerMotion(pose: OnePickerSimulation.FramePose) {
    var pose: OnePickerSimulation.FramePose by mutableStateOf(pose)
        internal set
}

/**
 * Drives [OnePickerSimulation] one frame at a time and owns what One Picker remembers across open
 * and close. [OnePickerFrameDriver] steps [stepFrame] on Compose's frame clock until it answers
 * [FrameAction.Stop]; the surface reports the IME's target frame through
 * [handleKeyboardFrameChange].
 */
@Stable
class OnePickerController {
    enum class FrameAction { Continue, Stop }

    /** Stepped every frame, so unobserved: views read the coarse mirrors below, set only when they change. */
    val simulation = OnePickerSimulation()

    /** Unobserved; leaves read it through [motion]. */
    var pose: OnePickerSimulation.FramePose = IDLE_POSE
        private set(value) {
            field = value
            if (motion.pose != value) motion.pose = value
            syncObservedPhase()
        }
    val motion = OnePickerMotion(IDLE_POSE)

    var isShown by mutableStateOf(false)
        private set
    var isOpen by mutableStateOf(false)
        private set
    var detent by mutableStateOf(ModelListDetent.Medium)
        private set
    var isDragging by mutableStateOf(false)
        private set

    /** Recent model IDs captured when the picker opened so in-place level picks never reorder rows under the user's finger. */
    var recentsAtOpen: List<String> by mutableStateOf(emptyList())
        private set

    /** Sections frozen during fold-out so nothing shifts while closing. */
    var frozenSections: List<ModelPickerSection>? by mutableStateOf(null)

    /** The list's catalog, kept across openings ([OnePickerCatalogPrewarm]). */
    val catalogCache = OnePickerCatalogCache()

    /** Incremented on each open from closed so the list starts at the top. */
    var openGeneration by mutableStateOf(0)
        private set
    var thresholdHapticCount by mutableStateOf(0)
        private set

    /** Chip & composer frames in the picker's coordinate space. */
    var chipFrame: Rect = Rect.Zero
    var latestViewportInSpace: Rect = Rect(Offset.Zero, Size(402f, 874f))
    var openedFromComposer: Rect = Rect.Zero
    var restingBottomComposer: Rect = Rect.Zero

    var searchFocused by mutableStateOf(false)
    var restoresComposerFocus by mutableStateOf(false)

    /** The keyboard was handed back to the composer as the picker closed. */
    var handedKeyboardToComposer: Boolean = false
    var onDidSettleClosed: (() -> Unit)? = null

    /** The composer's live frame in the picker's space. */
    var composerRectSource: (() -> Rect?)? = null

    /** The docked keyboard's frame in the window. */
    var keyboardFrameSource: (() -> Rect?)? = null

    /** The window's offset in the picker's space and its height. */
    var windowFrameInSpace: Rect = Rect.Zero
    var windowHeight: Double? = null

    /** [OnePickerFrameDriver] steps frames while this is set. */
    var wantsFrames by mutableStateOf(false)
        private set

    private var awaitsKeyboardCloseSync = false
    var ignoresDragUntilRelease = false
        private set
    private var closeSyncWaitElapsed = 0.0
    private var launchGeneration = 0

    private fun syncObservedPhase() {
        if (isShown != simulation.isShown) isShown = simulation.isShown
        if (isOpen != simulation.isOpen) isOpen = simulation.isOpen
        if (detent != simulation.detent) detent = simulation.detent
        if (isDragging != simulation.isDragging) isDragging = simulation.isDragging
    }

    fun noteWindowSpace(frameInSpace: Rect, height: Double) {
        windowFrameInSpace = frameInSpace
        windowHeight = height
    }

    fun dockedKeyboardTopInSpace(): Double? {
        val screenHeight = windowHeight ?: latestViewportInSpace.height.toDouble()
        val frame = keyboardFrameSource?.invoke() ?: return null
        if (frame.height <= 120f || frame.top >= screenHeight - 60) return null
        return (frame.top + windowFrameInSpace.top).toDouble()
    }

    fun liveComposerPresentationRectInSpace(): Rect? = composerRectSource?.invoke()

    fun open(
        sourceRect: Rect,
        viewport: Rect,
        ceiling: Double,
        recentModelIDs: List<String>,
        hadKeyboard: Boolean,
        restoresFocusTo: Rect? = null,
        initialKeyboardTop: Double? = null,
        initialDetent: ModelListDetent,
        reduceMotion: Boolean,
        onLaunch: () -> Unit,
        onOpened: () -> Unit,
    ) {
        val keyboardComposer = restoresFocusTo
        launchGeneration += 1
        val wasShown = simulation.isShown
        handedKeyboardToComposer = false
        awaitsKeyboardCloseSync = false
        // A reopen mid-close drops the last close's rows and recents; its return composer
        // follows the keyboard now unless a focus restore carries over.
        recentsAtOpen = recentModelIDs
        frozenSections = null
        if (!wasShown || hadKeyboard || keyboardComposer != null || !restoresComposerFocus) {
            openedFromComposer = keyboardComposer ?: sourceRect
        }
        if (!wasShown) {
            openGeneration += 1
            if (!hadKeyboard && initialKeyboardTop == null) {
                restingBottomComposer = sourceRect
            } else if (restingBottomComposer == Rect.Zero) {
                restingBottomComposer = Rect(
                    Offset(sourceRect.left, viewport.bottom - 12f - sourceRect.height),
                    sourceRect.size,
                )
            }
        }
        restoresComposerFocus = hadKeyboard || keyboardComposer != null || (wasShown && restoresComposerFocus)

        val metrics = OnePickerSimulation.Metrics(
            viewport = viewport,
            sourceRect = sourceRect,
            sourceRadius = COMPOSER_CORNER_RADIUS,
            ceiling = ceiling,
            compactHeight = OnePickerTunables.COMPACT_HEIGHT,
            sideInset = OnePickerTunables.SIDE_INSET,
            bottomInset = OnePickerTunables.BOTTOM_INSET,
            keyboardGap = OnePickerTunables.KEYBOARD_GAP,
            topRadius = OnePickerTunables.TOP_RADIUS,
            bottomRadius = OnePickerTunables.BOTTOM_RADIUS,
            keyboardTop = initialKeyboardTop,
        )

        onLaunch()
        simulation.arm(metrics, initialDetent, reduceMotion)
        pose = simulation.drawnFrame()
        launchSimulation()
        onOpened()
    }

    private fun syncPoseWithoutAnimation() {
        pose = simulation.drawnFrame()
    }

    private fun launchSimulation() {
        simulation.launchOpen(
            openDuration = Motion.panelOpen.curve().duration,
            openBounce = Motion.panelOpen.curve().bounce,
            widthDuration = OnePickerTiming.widthDuration,
            bottomDuration = Motion.keyboard.curve().duration,
            contentDuration = Motion.panelOpen.curve().duration,
            reduceFadeDuration = MotionTunables.reducedDuration.value,
        )
        syncPoseWithoutAnimation()
        wantsFrames = true
    }

    fun close(
        destinationRect: Rect,
        preservingVelocity: Double? = null,
        keepsKeyboard: Boolean = false,
        matchingKeyboardSpring: Boolean = false,
        freezeSections: () -> List<ModelPickerSection>,
    ) {
        if (!simulation.isShown) return
        launchGeneration += 1
        if (frozenSections == null) frozenSections = freezeSections()
        searchFocused = false
        awaitsKeyboardCloseSync = matchingKeyboardSpring
        closeSyncWaitElapsed = 0.0
        val closeDuration = if (matchingKeyboardSpring) {
            Motion.keyboard.curve().duration
        } else {
            Motion.panelClose.curve().duration
        }
        val holdBottomForRisingKeys = matchingKeyboardSpring &&
            !keepsKeyboard &&
            destinationRect.bottom < pose.rect.bottom - 40
        simulation.startClose(
            destinationRect = destinationRect,
            preservingTopVelocity = preservingVelocity,
            keepsKeyboard = keepsKeyboard,
            holdBottomUntilKeyboardSync = holdBottomForRisingKeys,
            closeDuration = closeDuration,
            contentOutDuration = Motion.panelClose.curve().duration,
            reduceFadeDuration = MotionTunables.reducedDuration.value,
        )
        syncPoseWithoutAnimation()
        if (simulation.phase == OnePickerSimulation.Phase.Closed) {
            finishClosed()
        } else {
            wantsFrames = true
        }
    }

    fun retargetCloseDestination(
        destinationRect: Rect,
        keepsKeyboard: Boolean = false,
        matchingKeyboardSpring: Boolean = false,
    ) {
        if (simulation.phase != OnePickerSimulation.Phase.Closing) return
        awaitsKeyboardCloseSync = matchingKeyboardSpring
        val closeDuration = if (matchingKeyboardSpring) {
            Motion.keyboard.curve().duration
        } else {
            Motion.panelClose.curve().duration
        }
        simulation.retargetClose(destinationRect, keepsKeyboard = keepsKeyboard, closeDuration = closeDuration)
        syncPoseWithoutAnimation()
        wantsFrames = true
    }

    fun reset() {
        launchGeneration += 1
        wantsFrames = false
        searchFocused = false
        restoresComposerFocus = false
        handedKeyboardToComposer = false
        awaitsKeyboardCloseSync = false
        ignoresDragUntilRelease = false
        frozenSections = null
        simulation.reset()
        syncPoseWithoutAnimation()
    }

    fun toggleDetent() {
        if (!simulation.isOpen) return
        chooseDetent(if (simulation.detent == ModelListDetent.Large) ModelListDetent.Medium else ModelListDetent.Large)
    }

    fun chooseDetent(next: ModelListDetent) {
        if (!simulation.isOpen || next == simulation.detent) return
        simulation.setDetent(next, duration = Motion.layout.curve().duration, bounce = Motion.layout.curve().bounce)
        pose = simulation.drawnFrame()
        wantsFrames = true
    }

    /**
     * Settles a live drag at its detent from outside its gesture (the app leaving the
     * foreground). That gesture's later samples are ignored until it ends, so it can't resume
     * from the mid-spring top plus its whole translation.
     */
    fun settleInteraction() {
        if (!simulation.isDragging) return
        ignoresDragUntilRelease = true
        settleDragAtDetent()
    }

    /** Ends a drag whose gesture stopped without reporting its end (cancelled), at its detent, so frames come to rest. */
    fun cancelDrag() {
        ignoresDragUntilRelease = false
        if (!simulation.isDragging) return
        settleDragAtDetent()
    }

    private fun settleDragAtDetent() {
        simulation.setDetent(
            simulation.detent,
            duration = Motion.release.curve().duration,
            bounce = Motion.release.curve().bounce,
        )
        pose = simulation.drawnFrame()
        wantsFrames = true
    }

    fun beginDrag() {
        ignoresDragUntilRelease = false
        if (!simulation.isOpen) return
        simulation.beginDrag()
        pose = simulation.drawnFrame()
    }

    fun updateDrag(translationY: Double) {
        if (!simulation.isOpen || ignoresDragUntilRelease) return
        if (simulation.updateDrag(translationY, dragCloses = OnePickerTunables.DRAG_CLOSES)) {
            thresholdHapticCount += 1
        }
        pose = simulation.drawnFrame()
    }

    fun endDrag(
        translationY: Double,
        velocityY: Double,
        destinationRect: Rect,
        keepsKeyboard: Boolean = false,
        onDismissed: (velocityY: Double) -> Unit,
    ) {
        if (ignoresDragUntilRelease) {
            ignoresDragUntilRelease = false
            return
        }
        if (!simulation.isOpen) return
        val outcome = simulation.endDrag(
            dy = translationY,
            vy = velocityY,
            dragCloses = OnePickerTunables.DRAG_CLOSES,
            throwCloses = OnePickerTunables.THROW_CLOSES,
            // Let go, it settles at a rest on the release spring, carrying the finger's speed.
            detentDuration = Motion.release.curve().duration,
            detentBounce = Motion.release.curve().bounce,
            closeDuration = Motion.panelClose.curve().duration,
            contentOutDuration = Motion.panelClose.curve().duration,
            destinationRect = destinationRect,
            keepsKeyboard = keepsKeyboard,
        )
        pose = simulation.drawnFrame()
        if (outcome == OnePickerSimulation.DragOutcome.Dismiss) onDismissed(velocityY)
        wantsFrames = true
    }

    fun syncViewport(viewport: Rect, ceiling: Double, liveComposer: Rect) {
        if (!searchFocused && liveComposer.width > 0f && liveComposer.bottom > viewport.bottom - 140f) {
            restingBottomComposer = liveComposer
        }
        if (!simulation.isShown) return
        val updated = simulation.metrics.copy(
            viewport = viewport,
            ceiling = ceiling,
            compactHeight = OnePickerTunables.COMPACT_HEIGHT,
            sideInset = OnePickerTunables.SIDE_INSET,
            bottomInset = OnePickerTunables.BOTTOM_INSET,
            keyboardGap = OnePickerTunables.KEYBOARD_GAP,
            topRadius = OnePickerTunables.TOP_RADIUS,
            bottomRadius = OnePickerTunables.BOTTOM_RADIUS,
        )
        if (updated == simulation.metrics) return
        simulation.metrics = updated
        if (simulation.isOpen && !simulation.isDragging) {
            simulation.setDetent(simulation.detent, duration = Motion.layout.curve().duration, bounce = Motion.layout.curve().bounce)
            wantsFrames = true
        }
        syncObservedPhase()
    }

    // Frame step & keyboard tracking

    fun stepFrame(dt: Double): FrameAction {
        val sheetActive = simulation.phase == OnePickerSimulation.Phase.Open ||
            simulation.phase == OnePickerSimulation.Phase.Closing
        if (!sheetActive) return FrameAction.Stop
        if (simulation.phase == OnePickerSimulation.Phase.Closing && awaitsKeyboardCloseSync) {
            closeSyncWaitElapsed += dt
            if (closeSyncWaitElapsed >= 0.075) {
                awaitsKeyboardCloseSync = false
                closeSyncWaitElapsed = 0.0
                val fallbackDest = when {
                    dockedKeyboardTopInSpace() != null -> simulation.metrics.sourceRect
                    restingBottomComposer.width > 0f -> restingBottomComposer
                    else -> simulation.metrics.sourceRect
                }
                simulation.retargetClose(
                    fallbackDest,
                    keepsKeyboard = false,
                    closeDuration = Motion.keyboard.curve().duration,
                )
            }
        }
        if (!searchFocused) {
            liveComposerPresentationRectInSpace()?.let { liveComp ->
                val liveBottom = liveComp.bottom.toDouble()
                if (simulation.phase == OnePickerSimulation.Phase.Open && simulation.metrics.keyboardTop == null && liveBottom > simulation.bottom.value) {
                    simulation.bottom.value = min(simulation.metrics.floorY, liveBottom)
                } else if (simulation.phase == OnePickerSimulation.Phase.Closing && simulation.metrics.keyboardTop == null) {
                    simulation.pinClosingToComposer(liveComp, countsRest = !awaitsKeyboardCloseSync)
                    if (awaitsKeyboardCloseSync && liveBottom < simulation.metrics.floorY - 8) {
                        awaitsKeyboardCloseSync = false
                        closeSyncWaitElapsed = 0.0
                        simulation.bottom.target = simulation.metrics.sourceRect.bottom.toDouble()
                    }
                    if (abs(liveBottom - simulation.bottom.target) <= 1.0) simulation.bottom.velocity = 0.0
                }
            }
        }
        val sheetSettled = simulation.step(dt)
        if (!searchFocused) {
            liveComposerPresentationRectInSpace()?.let { liveComp ->
                val liveBottom = liveComp.bottom.toDouble()
                if (simulation.phase == OnePickerSimulation.Phase.Open && simulation.metrics.keyboardTop == null && liveBottom > simulation.bottom.value) {
                    simulation.bottom.value = min(simulation.metrics.floorY, liveBottom)
                } else if (simulation.phase == OnePickerSimulation.Phase.Closing && simulation.metrics.keyboardTop == null) {
                    simulation.bottom.value = liveBottom
                }
            }
        }
        pose = simulation.drawnFrame()
        if (sheetSettled && simulation.phase == OnePickerSimulation.Phase.Closed) finishClosed()
        return if (sheetSettled) FrameAction.Stop else FrameAction.Continue
    }

    internal fun stopFrames() {
        wantsFrames = false
    }

    private fun finishClosed() {
        awaitsKeyboardCloseSync = false
        handedKeyboardToComposer = false
        frozenSections = null
        onDidSettleClosed?.invoke()
    }

    fun dismissDockedKeyboardTracking(rideDuration: Double = Motion.keyboard.curve().duration) {
        searchFocused = false
        if (!simulation.isOpen) return
        simulation.updateKeyboardTop(null, rideDuration = rideDuration)
        pose = simulation.drawnFrame()
        wantsFrames = true
    }

    /**
     * The keyboard is moving to [endScreenFrame] (null or off screen: leaving). Only retargets the
     * springs; the next frame draws. Rides `Motion.keyboard`'s curve.
     */
    fun handleKeyboardFrameChange(endScreenFrame: Rect?) {
        val ride = Motion.keyboard.curve().springParameters
        val omega = ride.omega
        val zeta = ride.zeta
        if (simulation.phase == OnePickerSimulation.Phase.Closing && awaitsKeyboardCloseSync) {
            awaitsKeyboardCloseSync = false
            closeSyncWaitElapsed = 0.0
            simulation.retargetClose(
                simulation.metrics.sourceRect,
                keepsKeyboard = simulation.metrics.keyboardTop != null,
                closeDuration = Motion.keyboard.curve().duration,
                omega = omega,
                zeta = zeta,
            )
            wantsFrames = true
            return
        }
        val screenHeight = windowHeight ?: simulation.metrics.viewport.height.toDouble()
        if (simulation.phase == OnePickerSimulation.Phase.Closing && !searchFocused && simulation.metrics.keyboardTop == null) {
            retargetCloseForKeyboard(endScreenFrame, screenHeight, omega, zeta)
            return
        }
        if (!simulation.isOpen) return
        val end = endScreenFrame
        if (end == null || end.height <= 80f || end.top >= screenHeight - 60 || !searchFocused) {
            simulation.updateKeyboardTop(null, Motion.keyboard.curve().duration, omega, zeta)
            wantsFrames = true
            return
        }
        val topInSpace = (end.top + windowFrameInSpace.top).toDouble()
        simulation.updateKeyboardTop(topInSpace, Motion.keyboard.curve().duration, omega, zeta)
        wantsFrames = true
    }

    /**
     * The keyboard came or went mid-close (a second tap outside, focus back as a sheet leaves):
     * the fold rides its curve onto where the composer is headed.
     */
    private fun retargetCloseForKeyboard(endScreenFrame: Rect?, screenHeight: Double, omega: Double, zeta: Double) {
        val src = simulation.metrics.sourceRect
        val end = endScreenFrame
        val destination = if (end != null && end.height > 80f && end.top < screenHeight - 60) {
            val keyboardUp = openedFromComposer
            if (keyboardUp.width > 0f &&
                (restingBottomComposer.width <= 0f || keyboardUp.bottom < restingBottomComposer.bottom - 40f)
            ) {
                keyboardUp
            } else {
                val maxY = end.top + windowFrameInSpace.top - simulation.metrics.keyboardGap.toFloat()
                Rect(Offset(src.left, maxY - src.height), src.size)
            }
        } else {
            if (restingBottomComposer.width > 0f) restingBottomComposer else src
        }
        if (abs(destination.top - src.top) <= 1f && abs(destination.bottom - src.bottom) <= 1f) return
        simulation.retargetClose(
            destination,
            keepsKeyboard = false,
            closeDuration = Motion.keyboard.curve().duration,
            omega = omega,
            zeta = zeta,
        )
        wantsFrames = true
    }

    companion object {
        /** `ComposerTunables.cornerRadius`: the desktop dock's 12 on Android. */
        const val COMPOSER_CORNER_RADIUS = 12.0

        private val IDLE_POSE = OnePickerSimulation.FramePose(
            rect = Rect.Zero,
            targetRect = Rect.Zero,
            layoutSize = Size.Zero,
            topRadius = COMPOSER_CORNER_RADIUS,
            bottomRadius = COMPOSER_CORNER_RADIUS,
            contentReveal = 0.0,
            searchBarReveal = 0.0,
            glassOpacity = 0.0,
            composerOpacity = 1.0,
            backdropStrength = 0.0,
        )
    }
}

/** Steps it on the frame clock while it wants frames, stopping when a step settles. */
@Composable
fun OnePickerFrameDriver(controller: OnePickerController) {
    val running = controller.wantsFrames
    LaunchedEffect(controller, running) {
        if (!running) return@LaunchedEffect
        var last = -1L
        while (controller.wantsFrames) {
            val action = withFrameNanos { now ->
                val dt = if (last < 0) 1.0 / 120.0 else min((now - last) / 1_000_000_000.0, 1.0 / 30.0)
                last = now
                controller.stepFrame(dt)
            }
            if (action == OnePickerController.FrameAction.Stop) controller.stopFrames()
        }
    }
}
