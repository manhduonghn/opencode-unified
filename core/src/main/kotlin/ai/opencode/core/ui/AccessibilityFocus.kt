package ai.opencode.core.ui

import android.view.View
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.getOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

// Moving TalkBack's focus from Compose, which has no API for it (`FocusRequester` moves input
// focus): asks the view's node provider for `ACTION_ACCESSIBILITY_FOCUS`.

/**
 * One event that moves TalkBack's focus: make a new one per event. Spent by its first move, so
 * recomposing, relayout or restoring never moves focus again.
 */
@Stable
class AccessibilityFocusRequest {
    internal var isSpent = false
}

/**
 * Moves TalkBack's focus here for [request], once its semantics are published and this window has
 * focus. Spent even while touch exploration is off.
 */
fun Modifier.accessibilityFocusRequest(request: AccessibilityFocusRequest?): Modifier = this then AccessibilityFocusRequestElement(request)

/** A new [AccessibilityFocusRequest] each time [event] becomes a new non-null value. */
@Composable
fun rememberAccessibilityFocusRequest(event: Any?): AccessibilityFocusRequest? =
    remember(event) { event?.let { AccessibilityFocusRequest() } }

/**
 * False under a composition that only shows a picture of a screen: nothing in it moves TalkBack's
 * focus.
 */
val LocalAccessibilityFocusMoves = staticCompositionLocalOf { true }

/**
 * A handle on one element ([accessibilityFocusTarget]) that [focus] moves TalkBack's focus onto,
 * for callers that pick the moment themselves.
 */
@Stable
class AccessibilityFocusTarget {
    internal var node: AccessibilityFocusTargetNode? = null

    /** Moves TalkBack's focus onto the element now; false if it isn't composed or the move was refused. */
    fun focus(): Boolean = node?.let { it.isAttached && it.moveAccessibilityFocus() } == true

    /** Whether TalkBack's focus is on the element now; false while touch exploration is off. */
    val isFocused: Boolean get() = node?.let { it.isAttached && it.hasAccessibilityFocus() } == true
}

/** Makes this element the one [target]'s [AccessibilityFocusTarget.focus] moves TalkBack to. */
fun Modifier.accessibilityFocusTarget(target: AccessibilityFocusTarget): Modifier = this then AccessibilityFocusTargetElement(target)

private fun <N> N.moveAccessibilityFocus(): Boolean where N : Modifier.Node, N : CompositionLocalConsumerModifierNode {
    if (!currentValueOf(LocalAccessibilityFocusMoves)) return false
    val id = accessibilityNodeId() ?: return false
    return currentValueOf(LocalView).accessibilityNodeProvider?.performAction(id, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) == true
}

private fun <N> N.hasAccessibilityFocus(): Boolean where N : Modifier.Node, N : CompositionLocalConsumerModifierNode {
    val view = currentValueOf(LocalView)
    if (view.context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled != true) return false
    val id = accessibilityNodeId() ?: return false
    return view.accessibilityNodeProvider?.createAccessibilityNodeInfo(id)?.isAccessibilityFocused == true
}

/** The marked element's virtual view id in its view's accessibility node provider. */
private fun <N> N.accessibilityNodeId(): Int? where N : Modifier.Node, N : CompositionLocalConsumerModifierNode {
    val view = currentValueOf(LocalView)
    // The only place that reads a semantics tree. The accessibility node provider's virtual view
    // ids are semantics node ids, and nothing public maps a modifier node to its id
    // (`requireSemanticsInfo()`, `LayoutNode` and the view's `Owner` are internal), so the
    // tree comes from `RootForTest`, the public interface every Compose view implements.
    val root = (view as? RootForTest)?.semanticsOwner?.unmergedRootSemanticsNode ?: return null
    // Absent while something hides it (a cleared ancestor): then there's nothing to focus.
    return root.find { it.config.getOrNull(AccessibilityFocusMarker) === this }?.id
}

private fun SemanticsNode.find(match: (SemanticsNode) -> Boolean): SemanticsNode? =
    if (match(this)) this else children.firstNotNullOfOrNull { it.find(match) }

private val AccessibilityFocusMarker = SemanticsPropertyKey<Modifier.Node>("AccessibilityFocusMarker")

private data class AccessibilityFocusTargetElement(val target: AccessibilityFocusTarget) :
    ModifierNodeElement<AccessibilityFocusTargetNode>() {
    override fun create() = AccessibilityFocusTargetNode(target)
    override fun update(node: AccessibilityFocusTargetNode) = node.update(target)
}

internal class AccessibilityFocusTargetNode(private var target: AccessibilityFocusTarget) :
    Modifier.Node(), SemanticsModifierNode, CompositionLocalConsumerModifierNode {
    override fun SemanticsPropertyReceiver.applySemantics() {
        this[AccessibilityFocusMarker] = this@AccessibilityFocusTargetNode
    }

    override fun onAttach() {
        target.node = this
    }

    override fun onDetach() {
        if (target.node === this) target.node = null
    }

    fun update(next: AccessibilityFocusTarget) {
        if (next === target) return
        if (target.node === this) target.node = null
        target = next
        if (isAttached) target.node = this
    }
}

private data class AccessibilityFocusRequestElement(val request: AccessibilityFocusRequest?) : ModifierNodeElement<AccessibilityFocusRequestNode>() {
    override fun create() = AccessibilityFocusRequestNode(request)
    override fun update(node: AccessibilityFocusRequestNode) = node.update(request)
}

/** How long a refused move keeps trying (see [AccessibilityFocusRequestNode]). */
private const val RETRY_NANOS = 1_000_000_000L

private class AccessibilityFocusRequestNode(private var request: AccessibilityFocusRequest?) :
    Modifier.Node(), SemanticsModifierNode, CompositionLocalConsumerModifierNode {
    private var pending: Job? = null

    override fun SemanticsPropertyReceiver.applySemantics() {
        this[AccessibilityFocusMarker] = this@AccessibilityFocusRequestNode
    }

    override fun onAttach() = focus()

    fun update(next: AccessibilityFocusRequest?) {
        if (next === request) return
        request = next
        if (isAttached) focus()
    }

    private fun focus() {
        pending?.cancel()
        val event = request ?: return
        if (event.isSpent) return
        pending = coroutineScope.launch {
            // Its semantics are published with the frame that lays it out.
            withFrameNanos {}
            withFrameNanos {}
            val view = currentValueOf(LocalView)
            if (view.context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled != true) {
                event.isSpent = true
                return@launch
            }
            if (!view.hasWindowFocus()) {
                view.awaitWindowFocus()
                withFrameNanos {}
            }
            if (event.isSpent) return@launch
            event.isSpent = true
            // Not in the accessibility tree yet (still transparent as it fades in, as Catch Up's
            // chrome is for its first frames): the move is refused, so it tries again each frame
            // for a moment.
            val deadline = withFrameNanos { it } + RETRY_NANOS
            while (!moveAccessibilityFocus()) {
                if (withFrameNanos { it } > deadline) return@launch
            }
        }
    }

    private suspend fun View.awaitWindowFocus() {
        if (hasWindowFocus()) return
        suspendCancellableCoroutine { continuation ->
            val observer = viewTreeObserver
            val listener = object : ViewTreeObserver.OnWindowFocusChangeListener {
                override fun onWindowFocusChanged(hasFocus: Boolean) {
                    if (!hasFocus) return
                    if (observer.isAlive) observer.removeOnWindowFocusChangeListener(this)
                    continuation.resume(Unit)
                }
            }
            observer.addOnWindowFocusChangeListener(listener)
            continuation.invokeOnCancellation { if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener) }
        }
    }
}
