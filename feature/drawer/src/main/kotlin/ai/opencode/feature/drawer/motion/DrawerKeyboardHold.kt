package ai.opencode.feature.drawer.motion

import ai.opencode.core.ui.DrawerKeyboardSignals
import ai.opencode.core.ui.LocalDrawerKeyboardSignals
import android.graphics.Insets
import android.os.CancellationSignal
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimationControlListener
import android.view.WindowInsetsAnimationController
import android.view.accessibility.AccessibilityManager
import android.view.animation.LinearInterpolator
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The composer keeps its focus while the drawer moves. Apps can't move the IME's window sideways,
 * so [follow] drives its insets animation (`WindowInsetsController.controlWindowInsetsAnimation`),
 * sliding the keys down as the card opens and back up as it closes ([ImeFollower]). Resting open
 * they are down ([sendKeysAway]); where the insets can't be driven (TalkBack touch exploration, or
 * the request refused) the keys stay put and step down at rest. Closing onto the same session gives
 * the field back ([giveBack]); anything else lets it go ([letGo]). A card the keyboard is leaving,
 * or drawer search, sets [excludesKeyboard].
 *
 * Wiring: create with [rememberDrawerKeyboardHold], provide it as [LocalDrawerKeyboardSignals]
 * around the card, mark fields with `Modifier.drawerKeyboardField(key)`, report drawer fields
 * through [drawerFieldFocusChanged], and set [DrawerMotionState.keyboardHold].
 */
@Stable
class DrawerKeyboardHold(
    private val scope: CoroutineScope,
    private val monitor: DrawerKeyboardMonitor? = null,
) : DrawerKeyboardSignals {
    /** Shows or hides the soft keyboard without moving focus. */
    interface Ime {
        fun show()
        fun hide()
    }

    var ime: Ime? = null

    /**
     * Drives the IME's insets with the card ([ImeFollower] on a device). Nothing it asked for may
     * outlive the hold: while the app controls the IME's insets the system ignores the IME's own
     * show requests, so a controller (or a request still waiting for one) left behind would keep
     * the keys from every field in the app.
     */
    interface Follower {
        /** Control was asked for and not yet given back. */
        val isActive: Boolean

        /** The system refused or took back the control before it was given back. */
        var onRefused: () -> Unit

        /** Shows [shownFraction] of the keys (0 down, 1 up), asking for control on the first call. */
        fun follow(shownFraction: Float)

        /** The card rests mid-hold: the keys end up [shown] or down once control arrives. */
        fun finish(shown: Boolean)

        /**
         * Gives control back now: a controller finishes with the keys [shown] or down, a request
         * still waiting is withdrawn. Returns whether a controller placed the keys.
         */
        fun release(shown: Boolean): Boolean

        /** Gives up control where the keys are. */
        fun cancel()
    }

    /** Drives the IME's insets with the card ([follow]); `null` keeps the keys still. */
    var follower: Follower? = null
        set(value) {
            field?.cancel()
            field = value
            value?.onRefused = ::followRefused
        }

    /** TalkBack's touch exploration finds the keys where the system put them, so they stay put. */
    var followsCard: Boolean = true

    /** Keys brought back while the card moves: the follower raises them with it, or [giveBack] shows them. */
    private var keysComingBack = false

    /** The field let go of its focus before the card came back. */
    var onLetGo: () -> Unit = {}

    /** Lays the card out without the keyboard (`true`) while it leaves, and with it again (`false`) once it's gone. */
    var excludeKeyboard: (Boolean) -> Unit = {}

    private class Field(val key: Any, val inDrawer: Boolean, val clearFocus: () -> Unit)

    /** The focused field. */
    private var focused: Field? = null

    /** The field that keeps its focus while the card is away. */
    private var heldField: Field? = null
        set(value) {
            field = value
            heldKey = value?.key
        }
    private var heldKey: Any? by mutableStateOf(null)

    /** The card is laying out without the keyboard; the transcript leaves it out of its inset too. */
    override var excludesKeyboard: Boolean by mutableStateOf(false)
        private set

    /** The keys stepped down under the open drawer. */
    var keysAway: Boolean by mutableStateOf(false)
        private set

    /** The keyboard leaving a card, until it's gone. */
    var isLeaving: Boolean by mutableStateOf(false)
        private set

    val isHolding: Boolean get() = heldField != null

    private var isDrawerSearching = false
    private var drawerOwnsKeyboard = false
    private var isExcludingKeyboard = false
    private var isKeyboardVisible = false
    private var searchRestoreGeneration = 0
    private var searchRestoreJob: Job? = null
    private var searchRestoreOnHide: (() -> Unit)? = null
    private var leaveGeneration = 0
    private var leaveJob: Job? = null
    private var leaveRestore: (() -> Unit)? = null

    override fun isHeld(key: Any): Boolean = heldKey == key

    /** Moves input focus to the container's focus target (set by `SessionDrawerContainer`). */
    var focusRelease: () -> Boolean = { false }

    override fun releaseFocus(): Boolean = focusRelease()

    override val holdsCardField: Boolean get() = heldField?.inDrawer == false

    override fun cardFieldFocusChanged(key: Any, focused: Boolean, clearFocus: () -> Unit) =
        fieldFocusChanged(Field(key, inDrawer = false, clearFocus), focused)

    /** A field in the drawer (its search) gained or lost focus. */
    fun drawerFieldFocusChanged(key: Any, focused: Boolean, clearFocus: () -> Unit) =
        fieldFocusChanged(Field(key, inDrawer = true, clearFocus), focused)

    private fun fieldFocusChanged(changed: Field, isFocused: Boolean) {
        if (isFocused) {
            focused = changed
            // Nothing is held: no control of the IME is ours any more, so this field's keys come up.
            if (!isHolding) follower?.takeIf { it.isActive }?.release(shown = true)
            checkActiveResponder(changed)
            return
        }
        if (focused?.key == changed.key) focused = null
        // Another field took the keyboard, or the held field left: the hold is over.
        if (heldField?.key == changed.key) letGo()
        if (changed.inDrawer && !isDrawerSearching) releaseDrawerKeyboardWhenHidden()
    }

    /** The IME started showing or finished hiding (`false`, `keyboardDidHide`). */
    fun imeVisibilityChanged(visible: Boolean) {
        if (visible) {
            isKeyboardVisible = true
            focused?.let(::checkActiveResponder)
            // The system brought the held field's keys back over the open drawer (back from the
            // background it restores the focused field's IME after ON_RESUME): down they go again.
            val held = heldField
            if (held != null && keysAway && focused?.key == held.key && follower?.isActive != true) ime?.hide()
        } else {
            isKeyboardVisible = false
            if (!isDrawerSearching && !isDrawerFieldFocused) {
                cancelSearchRestore()
                drawerOwnsKeyboard = false
                syncExclusion()
            }
            searchRestoreOnHide?.invoke()
        }
        leaveRestore?.invoke()
    }

    /**
     * Drawer search opened or closed: while it's open or its keyboard is still leaving, the card
     * and transcript ignore the keyboard.
     */
    fun setDrawerSearching(searching: Boolean) {
        isDrawerSearching = searching
        if (searching) {
            cancelSearchRestore()
            drawerOwnsKeyboard = true
            syncExclusion()
        } else {
            releaseDrawerKeyboardWhenHidden()
        }
    }

    /** Ends drawer-owned keyboard exclusion once the session card takes over. */
    fun endDrawerSearch() {
        cancelSearchRestore()
        isDrawerSearching = false
        drawerOwnsKeyboard = false
        syncExclusion()
    }

    /**
     * Keeps the focus of the card's focused field while the card moves. Returns whether it
     * holds; if not, the caller puts the keyboard away as before.
     */
    fun begin(): Boolean {
        if (isHolding) return true
        val current = focused ?: return false
        if (current.inDrawer) return false
        heldField = current
        monitor?.isHoldingKeyboard = true
        return true
    }

    private val canFollow: Boolean get() = followsCard && follower != null

    /**
     * The card is where it is: [progress] is how far the drawer is open. The keys slide down as it
     * opens and up as it closes.
     */
    fun follow(progress: Float) {
        val held = heldField ?: return
        val follower = follower ?: return
        if (!followsCard || keysAway || focused?.key != held.key) return
        if (progress <= 0f && !follower.isActive && !keysComingBack) return
        keysComingBack = false
        follower.follow(1f - progress.coerceIn(0f, 1f))
    }

    /** The drawer rests open: the keys step down out of its way, the field keeps its focus. */
    fun sendKeysAway() {
        val held = heldField ?: return
        if (keysAway || focused?.key != held.key) return
        keysAway = true
        keysComingBack = false
        // At rest there is nothing left to follow: control goes back with the keys down.
        val placed = follower?.takeIf { it.isActive }?.release(shown = false) == true
        if (!placed) ime?.hide()
    }

    /** The card moves again, or the drawer commits to closing: the keys come back up. */
    fun bringKeysBack() {
        val held = heldField ?: return
        if (!keysAway) return
        keysAway = false
        if (focused?.key != held.key) return
        // Following, they come up with the card's next frames; else at once.
        if (canFollow) keysComingBack = true else ime?.show()
    }

    /** Closed back onto the same session: the keys are (or are coming) back, and the field is the card's own again. */
    fun giveBack() {
        val held = heldField ?: return
        if (focused?.key != held.key) return letGo()
        bringKeysBack()
        val following = follower?.isActive == true
        val placed = follower?.takeIf { it.isActive }?.release(shown = true) == true
        if (!placed && (following || keysComingBack)) ime?.show()
        end()
    }

    /**
     * Back from the background: the system has the keys again and may bring the held field's up
     * over the open drawer, so keys the hold sent down go down again (and [imeVisibilityChanged]
     * catches the system restoring them after this). Only a hold whose keys rest under the card
     * acts: mid-move the card's next frame follows it anyway, and nothing held leaves the keys to
     * the system. The Android IME has no window of ours to re-frame, only its shown state.
     */
    fun didBecomeActive() {
        val held = heldField ?: return
        if (focused?.key != held.key || !keysAway) return
        ime?.hide()
    }

    /** The IME's insets couldn't be driven: the keys go where the hold wants them at once. */
    private fun followRefused() {
        val held = heldField ?: return
        if (focused?.key != held.key) return
        if (keysAway) ime?.hide() else if (keysComingBack) ime?.show()
    }

    /** A session or New Session was chosen in the drawer: the held card lets its keyboard go now. */
    fun cardWillChange() = letGo(leavingCard = true, now = true)

    /**
     * The card leaves the keyboard behind (another session, a new one, a field elsewhere). The
     * field lets go just after (clearing focus mid-composition would write the composer's focus
     * state mid-update); [now] clears it at once. A card the keyboard leaves ([leavingCard])
     * stops laying out around it at once.
     */
    fun letGo(leavingCard: Boolean = false, now: Boolean = false) {
        val held = heldField ?: return
        val resigning = focused?.key == held.key
        // Keys the card was carrying go down with the focus it gives up, and control of them
        // goes back to the system now, a request still waiting included (see [Follower]).
        follower?.release(shown = !resigning)
        end()
        if (resigning && leavingCard) leave()
        val resign = {
            if (focused?.key == held.key) {
                held.clearFocus()
                // A field that left without reporting it isn't the focused one any more.
                if (focused?.key == held.key) focused = null
            }
            onLetGo()
        }
        if (now) resign() else scope.launch { resign() }
    }

    /** The drawer is going away: whatever the hold did is undone, so nothing outlives it. */
    fun release() {
        leaveGeneration += 1
        leaveJob?.cancel()
        leaveRestore = null
        cancelSearchRestore()
        drawerOwnsKeyboard = false
        isLeaving = false
        isDrawerSearching = false
        excludesKeyboard = false
        if (isExcludingKeyboard) {
            isExcludingKeyboard = false
            excludeKeyboard(false)
        }
        follower?.release(shown = !keysAway)
        end()
    }

    private val isDrawerFieldFocused: Boolean get() = focused?.inDrawer == true

    private val shouldExcludeKeyboard: Boolean
        get() = isLeaving || drawerOwnsKeyboard || isDrawerSearching

    private fun syncExclusion() {
        val excluded = shouldExcludeKeyboard
        if (isExcludingKeyboard != excluded) {
            isExcludingKeyboard = excluded
            excludeKeyboard(excluded)
        }
        if (excludesKeyboard != excluded) excludesKeyboard = excluded
    }

    private fun checkActiveResponder(responder: Field) {
        cancelSearchRestore()
        if (responder.inDrawer) {
            drawerOwnsKeyboard = true
        } else {
            isDrawerSearching = false
            drawerOwnsKeyboard = false
        }
        syncExclusion()
    }

    private fun cancelSearchRestore() {
        searchRestoreGeneration += 1
        searchRestoreJob?.cancel()
        searchRestoreJob = null
        searchRestoreOnHide = null
    }

    private fun releaseDrawerKeyboardWhenHidden() {
        if (!drawerOwnsKeyboard) return syncExclusion()
        if (!isKeyboardVisible && !isDrawerFieldFocused) {
            cancelSearchRestore()
            drawerOwnsKeyboard = false
            syncExclusion()
            return
        }
        cancelSearchRestore()
        val generation = searchRestoreGeneration
        val restore = restore@{
            if (searchRestoreGeneration != generation) return@restore
            searchRestoreJob?.cancel()
            searchRestoreJob = null
            searchRestoreOnHide = null
            if (isDrawerSearching || isDrawerFieldFocused) return@restore
            drawerOwnsKeyboard = false
            syncExclusion()
        }
        searchRestoreOnHide = restore
        searchRestoreJob = scope.launch {
            delay(SEARCH_RESTORE_MILLIS)
            restore()
        }
    }

    /** The keyboard leaves the card: it lays out without it until the IME is gone (or shows again). */
    private fun leave() {
        isLeaving = true
        syncExclusion()
        leaveGeneration += 1
        val generation = leaveGeneration
        leaveJob?.cancel()
        val restore = restore@{
            if (leaveGeneration != generation) return@restore
            leaveRestore = null
            leaveJob?.cancel()
            leaveJob = null
            isLeaving = false
            syncExclusion()
        }
        leaveRestore = restore
        leaveJob = scope.launch {
            delay(LEAVE_RESTORE_MILLIS)
            restore()
        }
    }

    private fun end() {
        heldField = null
        keysAway = false
        keysComingBack = false
        monitor?.isHoldingKeyboard = false
    }

    private companion object {
        const val SEARCH_RESTORE_MILLIS = 600L
        const val LEAVE_RESTORE_MILLIS = 1_500L
    }
}

/**
 * Drives the IME's insets for [DrawerKeyboardHold.follow]: one controlled insets animation at a
 * time, its shown fraction set each frame, finished shown or hidden when the card rests. Asking for
 * control while the keys are down brings them up from hidden, so they rise with a closing card.
 */
class ImeFollower(private val view: View) : DrawerKeyboardHold.Follower {
    private var controller: WindowInsetsAnimationController? = null
    private var cancellation: CancellationSignal? = null
    private var requested = false
    private var fraction = 1f
    private var pendingFinish: Boolean? = null

    /** Where keys go if control arrives for a request already given back. */
    private var orphanShown = true

    /** The system refused or took back the control before it finished (another inset change, focus lost). */
    override var onRefused: () -> Unit = {}

    /** Control was asked for and not yet finished. */
    override val isActive: Boolean get() = requested

    /** Shows [shownFraction] of the keys (0 down, 1 up). Asks for control on the first call. */
    override fun follow(shownFraction: Float) {
        fraction = shownFraction
        pendingFinish = null
        controller?.let {
            apply(it)
            return
        }
        if (requested) return
        val insetsController = view.windowInsetsController ?: return onRefused()
        if (!view.hasWindowFocus()) return onRefused()
        requested = true
        val signal = CancellationSignal()
        cancellation = signal
        insetsController.controlWindowInsetsAnimation(WindowInsets.Type.ime(), -1L, LinearInterpolator(), signal, listener)
    }

    /** The card rests: the keys stay up ([shown]) or go down, and the system has them again. */
    override fun finish(shown: Boolean) {
        if (!requested) return
        val current = controller
        if (current == null) {
            pendingFinish = shown
            return
        }
        end(current, shown)
    }

    /**
     * Gives control back now. A request still waiting is withdrawn rather than left to finish
     * when (if ever) control arrives: while it waits, the system hands it the next show of the
     * IME's, whichever field asked for the keys.
     */
    override fun release(shown: Boolean): Boolean {
        val current = controller
        if (current != null) {
            end(current, shown)
            return true
        }
        if (requested) {
            orphanShown = shown
            cancellation?.cancel()
        }
        reset()
        return false
    }

    /** Gives up control where the keys are. */
    override fun cancel() {
        release(shown = fraction >= 0.5f)
    }

    private fun apply(controller: WindowInsetsAnimationController) {
        if (controller.isFinished || controller.isCancelled) return
        val hidden = controller.hiddenStateInsets.bottom
        val shown = controller.shownStateInsets.bottom
        val bottom = (hidden + (shown - hidden) * fraction).roundToInt()
        controller.setInsetsAndAlpha(Insets.of(0, 0, 0, bottom), 1f, fraction)
    }

    private fun end(controller: WindowInsetsAnimationController, shown: Boolean) {
        if (!controller.isFinished && !controller.isCancelled) {
            fraction = if (shown) 1f else 0f
            apply(controller)
            controller.finish(shown)
            resyncInsets()
        }
        reset()
    }

    private fun reset() {
        controller = null
        cancellation = null
        requested = false
        pendingFinish = null
    }

    private val listener = object : WindowInsetsAnimationControlListener {
        override fun onReady(controller: WindowInsetsAnimationController, types: Int) {
            if (!requested) {
                // Given back before it came: the system has the keys at once.
                controller.finish(orphanShown)
                return
            }
            this@ImeFollower.controller = controller
            val finish = pendingFinish
            if (finish != null) end(controller, finish) else apply(controller)
        }

        override fun onFinished(controller: WindowInsetsAnimationController) {
            if (this@ImeFollower.controller === controller) reset()
            resyncInsets()
        }

        override fun onCancelled(controller: WindowInsetsAnimationController?) {
            val wasActive = requested
            if (controller == null || this@ImeFollower.controller === controller || this@ImeFollower.controller == null) reset()
            resyncInsets()
            if (wasActive) onRefused()
        }
    }

    /**
     * Compose moves `WindowInsets.imeAnimationSource` to the resting insets only when an animation
     * with a duration ends, or on a dispatch outside any animation. A controlled animation has no
     * duration, so after a hold this asks for a dispatch next frame, and a second a frame later for
     * a late end.
     */
    private fun resyncInsets() {
        view.requestApplyInsets()
        view.postOnAnimation { view.requestApplyInsets() }
    }
}

/**
 * A [DrawerKeyboardHold] wired to this window's IME: the soft keyboard controller shows and hides
 * the keys, and `WindowInsets.ime` reports when they start showing or finish hiding. Releases the
 * hold when it leaves composition.
 */
@Composable
fun rememberDrawerKeyboardHold(monitor: DrawerKeyboardMonitor? = null): DrawerKeyboardHold {
    val scope = rememberCoroutineScope()
    val hold = remember(scope, monitor) { DrawerKeyboardHold(scope, monitor) }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val context = LocalContext.current
    val follower = remember(view) { ImeFollower(view) }
    if (hold.follower !== follower) hold.follower = follower
    val accessibility = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    DisposableEffect(hold, accessibility) {
        hold.followsCard = accessibility?.isTouchExplorationEnabled != true
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { hold.followsCard = !it }
        accessibility?.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibility?.removeTouchExplorationStateChangeListener(listener) }
    }
    hold.ime = remember(keyboard) {
        keyboard?.let {
            object : DrawerKeyboardHold.Ime {
                override fun show() = it.show()
                override fun hide() = it.hide()
            }
        }
    }
    val density = LocalDensity.current
    val imeInsets = androidx.compose.foundation.layout.WindowInsets.ime
    LaunchedEffect(hold, imeInsets, density) {
        snapshotFlow { imeInsets.getBottom(density) > 0 }
            .distinctUntilChanged()
            .collect { visible ->
                hold.imeVisibilityChanged(visible)
                monitor?.isImeVisible = visible
            }
    }
    // Back from the background the keys go back where the hold had them.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(hold, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) hold.didBecomeActive() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(hold) { onDispose { hold.release() } }
    return hold
}
