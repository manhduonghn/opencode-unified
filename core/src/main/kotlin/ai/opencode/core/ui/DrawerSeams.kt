package ai.opencode.core.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Seams between the session drawer and the screens on its card, which sit below it in the module
 * graph. The drawer provides these locals; both default to `null`.
 */
interface DrawerKeyboardSignals {
    /**
     * The card lays out without the keyboard: the session leaves `WindowInsets.ime` out while true.
     */
    val excludesKeyboard: Boolean

    /**
     * Whether the field named [key] is held while the drawer moves or rests open, so input can't
     * reach it.
     */
    fun isHeld(key: Any): Boolean

    /** A field on the card is held while the drawer moves or rests open (its keys are the drawer's). */
    val holdsCardField: Boolean get() = false

    /**
     * A text field on the card gained or lost focus. [clearFocus] lets the drawer take the focus
     * away when the card changes.
     */
    fun cardFieldFocusChanged(key: Any, focused: Boolean, clearFocus: () -> Unit)

    /**
     * Where the text field named [key] is laid out (`null` once it leaves): the switcher's strip
     * never takes a touch that starts on it.
     */
    fun cardFieldPlaced(key: Any, coordinates: LayoutCoordinates?) {}

    /**
     * Moves input focus off the card to a target of the drawer's, keeping the window's focus:
     * `clearFocus` would bounce it back and TalkBack would follow. False if it couldn't move.
     */
    fun releaseFocus(): Boolean = false
}

/** The drawer's keyboard hold, provided by the drawer container around the card. */
val LocalDrawerKeyboardSignals = staticCompositionLocalOf<DrawerKeyboardSignals?> { null }

/**
 * Reports this text field's focus to the drawer's keyboard hold (see [DrawerKeyboardSignals]).
 * Put it on the composer's field; [key] identifies it for [DrawerKeyboardSignals.isHeld].
 */
fun Modifier.drawerKeyboardField(key: Any): Modifier = composed {
    val signals = LocalDrawerKeyboardSignals.current
    val focusManager = LocalFocusManager.current
    if (signals == null) {
        Modifier
    } else {
        DisposableEffect(signals, key) {
            onDispose {
                signals.cardFieldFocusChanged(key, focused = false, clearFocus = {})
                signals.cardFieldPlaced(key, null)
            }
        }
        Modifier
            .onFocusChanged { state ->
                signals.cardFieldFocusChanged(key, state.isFocused) { focusManager.clearFocus(force = true) }
            }
            .onGloballyPositioned { signals.cardFieldPlaced(key, it) }
    }
}

/**
 * Where the session drawer's pan never starts. Regions are layout nodes, so hit tests follow the
 * card.
 */
interface DrawerPanExclusion {
    /** Records ([coordinates] non-null) or clears the excluded region named [id]. */
    fun updateExclusion(id: Any, coordinates: LayoutCoordinates?)

    /**
     * Records ([coordinates] non-null) or clears the horizontal scroller named [id]; the drawer and
     * Catch Up yield it drags it can still scroll.
     */
    fun updateHorizontalScroller(
        id: Any,
        coordinates: LayoutCoordinates?,
        canScrollBackward: () -> Boolean,
        canScrollForward: () -> Boolean,
    ) {}

    /**
     * Records ([coordinates] non-null) or clears the text selection named [id]; [handleClaims]
     * takes px in [coordinates].
     */
    fun updateSelectionHandles(id: Any, coordinates: LayoutCoordinates?, handleClaims: (Offset) -> Boolean) {}
}

/** The drawer's pan regions, provided by the drawer container around the card. */
val LocalDrawerPanExclusion = staticCompositionLocalOf<DrawerPanExclusion?> { null }

/**
 * This element scrolls sideways with [state]: the drawer's pan and Catch Up's swipe leave it the
 * drags it can still scroll.
 */
fun Modifier.drawerPanHorizontalScroller(state: ScrollState): Modifier =
    drawerPanHorizontalScroller({ state.canScrollBackward }, { state.canScrollForward })

/** As [drawerPanHorizontalScroller] for any scroller (a `LazyRow`'s state, say). */
fun Modifier.drawerPanHorizontalScroller(canScrollBackward: () -> Boolean, canScrollForward: () -> Boolean): Modifier = composed {
    val exclusion = LocalDrawerPanExclusion.current
    if (exclusion == null) {
        Modifier
    } else {
        val id = remember { Any() }
        val backward = rememberUpdatedState(canScrollBackward)
        val forward = rememberUpdatedState(canScrollForward)
        DisposableEffect(exclusion) {
            onDispose { exclusion.updateHorizontalScroller(id, null, { false }, { false }) }
        }
        Modifier.onGloballyPositioned { exclusion.updateHorizontalScroller(id, it, { backward.value() }, { forward.value() }) }
    }
}

/**
 * This element draws selection handles: a touch [handleClaims] (px here) drags the handle and never
 * opens the drawer.
 */
fun Modifier.drawerPanSelectionHandles(handleClaims: (Offset) -> Boolean): Modifier = composed {
    val exclusion = LocalDrawerPanExclusion.current
    if (exclusion == null) {
        Modifier
    } else {
        val id = remember { Any() }
        val claims = rememberUpdatedState(handleClaims)
        DisposableEffect(exclusion) {
            onDispose { exclusion.updateSelectionHandles(id, null) { false } }
        }
        Modifier.onGloballyPositioned { exclusion.updateSelectionHandles(id, it) { point -> claims.value(point) } }
    }
}

/** The drawer's pan never starts on this element while [isEnabled]. */
fun Modifier.excludedFromDrawerPan(isEnabled: Boolean = true): Modifier = composed {
    val exclusion = LocalDrawerPanExclusion.current
    if (exclusion == null) {
        Modifier
    } else {
        val id = remember { Any() }
        DisposableEffect(exclusion, isEnabled) {
            if (!isEnabled) exclusion.updateExclusion(id, null)
            onDispose { exclusion.updateExclusion(id, null) }
        }
        Modifier.onGloballyPositioned { if (isEnabled) exclusion.updateExclusion(id, it) }
    }
}
