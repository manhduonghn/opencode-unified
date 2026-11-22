package ai.opencode.feature.drawer.container

import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.feature.drawer.catchup.CatchUpDisposition
import ai.opencode.feature.drawer.catchup.CatchUpStackController
import ai.opencode.feature.drawer.catchup.CatchUpStage
import ai.opencode.feature.drawer.catchup.KeepsMotionDuration
import ai.opencode.feature.drawer.motion.DrawerKeyboardHold
import ai.opencode.feature.drawer.motion.DrawerMotionState
import ai.opencode.feature.drawer.motion.DrawerPanEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Port of the wide-window metrics `SessionDrawerContainer` uses: a window at least
 * [minimumWindowWidth] wide lists the sessions in a [width]-wide sidebar beside the session instead
 * of a drawer under it.
 */
object SidebarMetrics {
    val minimumWindowWidth: Dp = 700.dp
    val width: Dp = 320.dp
}

/**
 * Mutes the session card while the drawer is open. Its alpha is the drawer's progress, so it
 * follows the drag and the settle exactly. Light darkens the white card toward grey; a black scrim
 * would vanish on the black dark-mode card, so dark blends toward grey. Draw it at `alpha =
 * progress`.
 */
object DrawerScrim {
    fun color(isDark: Boolean, highContrast: Boolean): Color =
        if (isDark) {
            Color(0.24f, 0.24f, 0.24f, if (highContrast) 0.7f else 0.6f)
        } else {
            Color(0f, 0f, 0f, if (highContrast) 0.45f else 0.32f)
        }
}

/**
 * Whether closing the drawer gives the keyboard back to the composer that had it when it opened.
 */
object DrawerKeyboard {
    /** The session the drawer closes onto: one just chosen in it (maybe still loading behind the old card), else the one shown. */
    fun closingSession(chosen: String?, shown: String?): String? = chosen ?: shown

    /** Only on the same session, with nothing presented over it. A draft (`null`) keeps its own focus. */
    fun restoresFocus(openedOn: String?, closedOn: String?, sheetPresented: Boolean): Boolean {
        if (sheetPresented) return false
        if (closedOn == null) return true
        return openedOn == closedOn
    }
}

/** Which way Catch Up's stage is fading. */
enum class CatchUpModeMorph {
    /** The stage fades in over the drawer or the session. */
    EnterCatchUp,
    /** The stage fades out over the session, laid out full screen at once. */
    ExitIntoSession,
    /** Back or finishing onto the open drawer it was opened from: the stage fades out over it. */
    ReturnToDrawer,
}

/**
 * The plain object behind [SessionDrawerContainer]: owns the drawer's motion ([motion]), Catch Up's
 * stack ([catchUpStack]), the Catch Up mode morphs and the wide-window sidebar. Everything per
 * frame is snapshot state read in layer/draw blocks; Home hears only coarse callbacks.
 *
 * Create it with [rememberSessionDrawerController]. Units: dp unless a name says px.
 */
@Stable
class SessionDrawerController internal constructor(
    private val scope: CoroutineScope,
    /** The drawer's drag, settle and open state. */
    val motion: DrawerMotionState,
    /** Catch Up's card stack. */
    val catchUpStack: CatchUpStackController,
) {
    // region Inputs (set by the composable each pass)

    internal var reducedMotion = false
    internal var drawerWidth = 0f
    internal var keyboardHold: DrawerKeyboardHold? = null
    internal var isSheetPresented: () -> Boolean = { false }
    internal var isContentPushed: () -> Boolean = { false }
    internal var clearFocus: () -> Unit = {}

    var onArrived: (Boolean) -> Unit = {}
    var onCommitted: () -> Unit = {}
    var onTouchDown: () -> Unit = {}
    var onPresentationChanged: (Boolean) -> Unit = {}

    /**
     * The session the card shows, or is about to show once a session chosen in the drawer has
     * loaded (`null` for a draft). A session chosen in the open drawer, or a new one, opens with
     * the keyboard down: the hold lets go while the drawer still covers the card.
     */
    var contentSessionID: String? = null
        set(value) {
            val old = field
            field = value
            val hold = keyboardHold ?: return
            if (value == old || !hold.isHolding || value == sessionWhenSuspended) return
            hold.letGo(leavingCard = true)
        }

    // endregion

    // region Card picture

    /** Records the card's content each frame, for the picture that leaves with Catch Up's stage. */
    internal var cardLayer: GraphicsLayer? = null

    /**
     * Captures the card, then runs [then] (which changes what the card shows): Catch Up's stage
     * fades out with that picture as its front card.
     */
    fun captureCardThen(then: () -> Unit) {
        catchUpStack.settle()
        // Finishing Catch Up: the last card has already flown away (`liveAlpha == 0`).
        val liveHidden = catchUp != null && catchUpStack.liveAlpha <= 0.01f
        val layer = cardLayer
        if (layer == null || liveHidden || layer.size.width <= 0 || layer.size.height <= 0) {
            pendingCardSnapshot = null
            then()
            return
        }
        scope.launch {
            pendingCardSnapshot = runCatching { layer.toImageBitmap() }.getOrNull()
            then()
        }
    }

    /** The card's picture taken for the exit about to start. */
    internal var pendingCardSnapshot: ImageBitmap? = null
        private set

    // endregion

    // region Keyboard and touches

    /** The card's session when it last stopped taking touches (the drawer began to open). */
    private var sessionWhenSuspended: String? = null

    /** The card doesn't take touches: the drawer is moving or open. */
    private var isContentSuspended = false

    private fun suspendContent() {
        if (!isContentSuspended) sessionWhenSuspended = contentSessionID
        isContentSuspended = true
        val hold = keyboardHold
        if (hold == null || !hold.isHolding) clearFocus()
    }

    /**
     * Closing onto the same session gives the keyboard back: the held field's keys come back up.
     * After a switch to another session, or under Settings presented meanwhile, the keyboard stays
     * down until the composer is tapped (`DrawerKeyboard`).
     */
    private fun contentBecameInteractive() {
        isContentSuspended = false
        val restores = DrawerKeyboard.restoresFocus(
            openedOn = sessionWhenSuspended,
            closedOn = contentSessionID,
            sheetPresented = isSheetPresented(),
        )
        sessionWhenSuspended = contentSessionID
        val hold = keyboardHold
        if (restores) {
            hold?.giveBack()
        } else {
            hold?.letGo()
            if (hold?.isHolding != true) clearFocus()
        }
    }

    /** A subagent pushed over the session lets go of the session's keyboard. */
    fun pagesChanged(pushed: Boolean) {
        if (pushed) keyboardHold?.letGo()
    }

    private var lastPushed = false

    /** The pushed stack changed. */
    internal fun updatePushed(pushed: Boolean) {
        if (pushed == lastPushed) return
        lastPushed = pushed
        pagesChanged(pushed)
    }

    // endregion

    // region Motion callbacks

    /** Mode changes place the drawer without a haptic or an arrival. */
    private var quiet = false

    private inline fun quietly(block: () -> Unit) {
        val was = quiet
        quiet = true
        try {
            block()
        } finally {
            quiet = was
        }
    }

    init {
        motion.callbacks = DrawerMotionState.Callbacks(
            onArrived = { if (!quiet) onArrived(it) },
            onCommitted = { if (!quiet) onCommitted() },
            onTouchDown = { onTouchDown() },
            onPresentationChanged = { onPresentationChanged(it) },
            onSuspendContent = ::suspendContent,
            onContentInteractive = { contentBecameInteractive() },
        )
        motion.canHoldKeyboard = { catchUp == null && !isContentPushed() && sidebarShown == null }
    }

    /**
     * Home's `isPresented`: every programmatic open and close animates; Reduce Motion lands at
     * once.
     */
    fun setPresented(presented: Boolean, animated: Boolean = true) {
        if (catchUp != null) return
        if (presented == motion.isPresented || (sidebarShown != null && presented)) return
        // Catch Up's return to the drawer hands over where it was headed.
        finishModeTransition()
        motion.setPresented(presented, animated)
    }

    /** A drag began on the card: a morph still landing lands now. */
    internal fun panBegan() = finishModeTransition()

    /**
     * A drag that began before Catch Up opened or closed: its remaining events belong to neither
     * the stack nor the drawer.
     */
    private var isPanInFlight = false
    private var ignoresPanUntilItEnds = false

    /** Whether the drawer takes this pan event. */
    internal fun acceptsPan(event: DrawerPanEvent): Boolean {
        when (event) {
            is DrawerPanEvent.Began -> {
                ignoresPanUntilItEnds = false
                isPanInFlight = true
            }
            is DrawerPanEvent.Ended, is DrawerPanEvent.Cancelled -> {
                isPanInFlight = false
                if (ignoresPanUntilItEnds) {
                    ignoresPanUntilItEnds = false
                    return false
                }
            }
            is DrawerPanEvent.Changed -> Unit
        }
        return !ignoresPanUntilItEnds
    }

    private fun cancelPanInFlight() {
        if (!isPanInFlight) return
        isPanInFlight = false
        ignoresPanUntilItEnds = true
    }

    /** Closing by hand: a tap on the card, back, TalkBack's escape. */
    fun closeDrawer(): Boolean {
        if (!motion.isPresented && motion.offset <= 0f) return false
        finishModeTransition()
        return motion.close()
    }

    // endregion

    // region Sidebar

    /** See `SessionDrawerContainer`'s `sidebarShown`: `null` slides the phone's drawer under the card. */
    var sidebarShown: Boolean? = null
        private set
    private var sidebarInitialized = false

    /** The card's leading edge, in dp: the sidebar's width while it shows, else 0. */
    internal val sidebarLeading = Animatable(0f)
    private var sidebarJob: Job? = null

    /** The sidebar is composed (shown, or still sliding away). */
    var isSidebarComposed by mutableStateOf(false)
        private set

    /** The sidebar shows beside the card, except while catching up. */
    val isSidebarVisible: Boolean get() = sidebarShown == true && catchUp == null

    fun setSidebar(shown: Boolean?, animated: Boolean) {
        if (sidebarInitialized && shown == sidebarShown) return
        val wasSidebar = sidebarInitialized && sidebarShown != null
        sidebarInitialized = true
        sidebarShown = shown
        // A return to the drawer still landing hands over to the sidebar.
        finishModeTransition()
        motion.recedes = shown == null
        if (shown != null) {
            keyboardHold?.letGo()
            // The drawer becomes the sidebar: anything open or moving rests shut.
            val wasPresented = motion.isPresented
            quietly {
                if (wasPresented) motion.setPresented(false, animated = false) else if (motion.offset > 0f) motion.settle(0f, animated = false)
            }
            if (wasPresented) onPresentationChanged(false)
            layoutSidebar(animated = animated && wasSidebar)
        } else {
            sidebarJob?.cancel()
            sidebarJob = null
            scope.launch { sidebarLeading.snapTo(0f) }
            isSidebarComposed = false
        }
    }

    /**
     * Moves the card's leading edge to the sidebar's (or the screen's), on `MotionRole.Layout` for
     * the sidebar's toggle (opening; closing on its exit, `Collapse`). The card's own frame changes,
     * so the session narrows and widens with the sidebar instead of sliding.
     */
    internal fun layoutSidebar(animated: Boolean) {
        if (sidebarShown == null || catchUp != null) return
        val visible = isSidebarVisible
        if (visible) isSidebarComposed = true
        val target = if (visible) drawerWidth else 0f
        sidebarJob?.cancel()
        sidebarJob = scope.launch {
            if (animated) sidebarLeading.animateTo(target, MotionRole.Layout.animation(if (visible) MotionPhase.Enter else MotionPhase.Exit)) else sidebarLeading.snapTo(target)
            if (!isSidebarVisible) isSidebarComposed = false
        }
    }

    /** The sidebar's width changed. */
    internal fun sidebarWidthChanged() {
        if (sidebarShown != null) layoutSidebar(animated = false)
    }

    // endregion

    // region Catch Up mode

    /** The stage on screen; `null` outside Catch Up. */
    var catchUp: CatchUpStage? = null
        private set

    /** The last stage shown, kept mounted while the stage fades out. */
    internal var lastStage: CatchUpStage? = null

    /** The fade running, if any. Composition reads it to keep the stage and drawer mounted. */
    var morph: CatchUpModeMorph? by mutableStateOf(null)
        private set

    private var morphJob: Job? = null
    private var morphTarget = 1f
    private var morphCompletion: (() -> Unit)? = null

    /** Catch Up's stage (its chrome and stack) fades in and out with this. */
    internal val stageAlpha = mutableFloatStateOf(0f)

    /** The drawer stays open under the stage while it fades in. */
    internal var entersOverDrawer = false
        private set

    /** The card's picture in the stack's front slot while the stage fades out. */
    internal var leavingCard by mutableStateOf<ImageBitmap?>(null)
        private set

    /**
     * [returnsToDrawer]: the stack ends by going back to the open drawer it was opened from (Back,
     * or finishing the stack), rather than onto the session.
     */
    fun updateCatchUp(stage: CatchUpStage?, returnsToDrawer: Boolean, animated: Boolean) {
        val previous = catchUp
        if (stage == null && previous == null) return
        val togglesMode = (stage != null) != (previous != null)
        if (togglesMode) {
            // A drag under way belonged to the other mode: neither the stack nor the drawer takes the rest of it.
            cancelPanInFlight()
            // Set first, so a return to the drawer cut short by Catch Up opening again knows it never arrived.
            catchUp = stage
            // The fade running lands, but the stage keeps its opacity: the new fade starts from there.
            val alpha = stageAlpha.floatValue
            finishModeTransition()
            stageAlpha.floatValue = alpha
        }
        var fromDrawer = sidebarShown != null
        if (stage != null && previous == null) {
            keyboardHold?.letGo()
            if (motion.progress > 0f) fromDrawer = true
            val wasPresented = motion.isPresented
            if (wasPresented) onPresentationChanged(false)
            // Catch Up takes over from the drawer; that is not a close to feel.
            quietly {
                if (wasPresented) motion.setPresented(false, animated = false) else if (motion.offset > 0f) motion.settle(0f, animated = false)
            }
        }
        catchUp = stage
        if (stage != null) lastStage = stage
        // Once the last card has flown away (`liveAlpha == 0`), there is no card left to picture.
        val wasShowingCard = previous != null && previous.cardID != null && catchUpStack.liveAlpha > 0.01f
        // A card drag under way is nobody's now. (The stack keeps its stage until the fade unmounts
        // it, so its preview fades with the stage.)
        if (previous != null && stage == null) catchUpStack.dropDrag()
        // Catching up takes the whole screen; afterwards the sidebar returns.
        if (sidebarShown != null) {
            sidebarJob?.cancel()
            val target = if (isSidebarVisible) drawerWidth else 0f
            scope.launch { sidebarLeading.snapTo(target) }
        }
        when {
            previous == null && stage != null -> enter(animated, fromDrawer, stage.cardID != null)
            previous != null && stage == null && returnsToDrawer && sidebarShown == null ->
                leave(CatchUpModeMorph.ReturnToDrawer, animated, wasShowingCard)
            previous != null && stage == null -> leave(CatchUpModeMorph.ExitIntoSession, animated, wasShowingCard)
            else -> Unit
        }
    }

    /** The chrome's Keep Unread / Mark as Read: a fade still running lands first. */
    fun dismissCard(disposition: CatchUpDisposition) {
        finishModeTransition()
        catchUpStack.fling(disposition)
    }

    /** The chrome's Undo. */
    fun undoCard() {
        finishModeTransition()
        catchUpStack.undo()
    }

    private fun enter(animated: Boolean, fromDrawer: Boolean, showsCard: Boolean) {
        pendingCardSnapshot = null
        catchUpStack.liveAlpha = if (showsCard) 1f else 0f
        entersOverDrawer = fromDrawer
        runMorph(CatchUpModeMorph.EnterCatchUp, animated, target = 1f, phase = MotionPhase.Enter) {
            entersOverDrawer = false
            if (catchUp != null) catchUpStack.revealPreview(animated = animated)
        }
    }

    /**
     * The session (or the open drawer) is at rest at once, under the stage; the stage fades out
     * with the card's picture in its front slot. Touches and TalkBack take the new pose from the
     * first frame.
     */
    private fun leave(kind: CatchUpModeMorph, animated: Boolean, wasShowingCard: Boolean) {
        leavingCard = pendingCardSnapshot.takeIf { animated && wasShowingCard }
        pendingCardSnapshot = null
        catchUpStack.liveAlpha = 0f
        if (kind == CatchUpModeMorph.ReturnToDrawer) {
            quietly { motion.setPresented(true, animated = false) }
        }
        runMorph(kind, animated, target = 0f, phase = MotionPhase.Exit) {
            leavingCard = null
            catchUpStack.releasePreview()
            if (kind == CatchUpModeMorph.ReturnToDrawer) {
                // Cut short by Catch Up opening again or the drawer becoming a sidebar: the drawer
                // never arrived.
                if (catchUp == null && sidebarShown == null) onArrived(true)
            } else if (catchUp == null) {
                quietly { motion.settle(0f, animated = false) }
                layoutSidebar(animated = false)
            }
        }
    }

    private fun runMorph(
        kind: CatchUpModeMorph,
        animated: Boolean,
        target: Float,
        phase: MotionPhase,
        completion: () -> Unit,
    ) {
        morphJob?.cancel()
        morph = kind
        morphTarget = target
        morphCompletion = completion
        if (!animated) {
            finishModeTransition()
            return
        }
        val spec = MotionRole.Swap.animation<Float>(phase, reduceMotion = reducedMotion)
        morphJob = scope.launch(KeepsMotionDuration) {
            animate(stageAlpha.floatValue, target, animationSpec = spec) { value, _ ->
                stageAlpha.floatValue = value
            }
            morphJob = null
            landMorph()
        }
    }

    /**
     * Hands ownership from a fade to whatever comes next (a drag, a card's flight, Catch Up again,
     * the sidebar): the fade lands at its end now.
     */
    fun finishModeTransition() {
        if (morph == null) return
        morphJob?.cancel()
        morphJob = null
        stageAlpha.floatValue = morphTarget
        landMorph()
    }

    private fun landMorph() {
        val completion = morphCompletion
        morphCompletion = null
        morph = null
        completion?.invoke()
    }

    // endregion

    internal fun dispose() {
        finishModeTransition()
        sidebarJob?.cancel()
    }
}
