package ai.opencode.feature.media

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.toSize
import java.util.Collections
import java.util.WeakHashMap

/**
 * A scrolling list image previews live in (the transcript list; iOS `AgentImageViewport`): what
 * part of it the reader sees, and the rows to ease when an image's shape changes. The list
 * provides itself through [LocalAgentImageViewport] and registers with [AgentImageVisibility].
 */
interface AgentImageViewport {
    /**
     * The part of the list the reader sees (between the header and the composer), in window
     * coordinates; empty while nothing counts as on screen (the session's opening hasn't settled).
     */
    val imageViewportVisibleRect: Rect
    /** Still opening: nothing counts as on screen yet; ask again shortly. */
    val imageViewportIsSettling: Boolean
    /** Covered session roots stay composed: a hidden or covered list doesn't hold previews. */
    val imageViewportIsVisible: Boolean
    /** An image inside the row keyed [row] ([LocalAgentImageRow]) is about to change the row's height. */
    fun imageContentRevised(row: Any?)
}

/** The list the markers below belong to (iOS: the marker's ancestor viewport). */
val LocalAgentImageViewport = staticCompositionLocalOf<AgentImageViewport?> { null }

/** The list row the markers below are in, so [AgentImageViewport.imageContentRevised] can find it (iOS: the cell above the view). */
val LocalAgentImageRow = staticCompositionLocalOf<Any?> { null }

/**
 * Which image previews are on screen (iOS `AgentImageVisibility`). A reply's blocks are laid out
 * eagerly in one row, so being composed says nothing: each preview carries a marker
 * ([AgentImageMarker]), and after every scroll and layout (on the next turn of the main loop,
 * never inside one: previews change state the rows observe) its viewport says which markers lie
 * inside the part the reader sees (with [MARGIN] screens of margin, so a read starts just before
 * it scrolls in). Only those hold a read and show pixels.
 *
 * A plain main-thread object: markers report their window bounds from `onGloballyPositioned`.
 */
class AgentImageVisibility(private val previews: AgentImagePreviews) {
    private val viewports: MutableSet<AgentImageViewport> = Collections.newSetFromMap(WeakHashMap())
    private val markers = LinkedHashSet<AgentImageMarkerView>()
    private var scheduled = false
    private var settleScheduled = false
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /** Settle rechecks scheduled, for diagnostics. */
    var settleChecks = 0
        private set

    init {
        previews.onLayoutChange = { key -> revised(key) }
    }

    fun register(viewport: AgentImageViewport) {
        viewports.add(viewport)
        setNeedsUpdate()
    }

    /** The viewport left: its markers go off screen and its opening hold ends. */
    fun unregister(viewport: AgentImageViewport) {
        viewports.remove(viewport)
        previews.holdEntrances(viewport, false)
        update()
    }

    /** Every preview's marker. */
    val markerViews: List<AgentImageMarkerView> get() = markers.toList()

    fun add(marker: AgentImageMarkerView) {
        markers.add(marker)
        setNeedsUpdate()
    }

    fun remove(marker: AgentImageMarkerView) {
        markers.remove(marker)
    }

    /** Updates on the next turn of the main loop, once for many requests. */
    fun setNeedsUpdate() {
        if (scheduled) return
        scheduled = true
        handler.post {
            scheduled = false
            update()
        }
    }

    /** Marks every marker on or off screen now. */
    fun update() {
        // Only an opening viewport with mounted images can hold an entrance. Text-only, covered,
        // and settled viewports release even without a marker asking for the visible rect.
        val currentMarkers = markers.toList()
        var waiting = false
        for (viewport in viewports.toList()) {
            val hold = viewport.imageViewportIsVisible && viewport.imageViewportIsSettling &&
                currentMarkers.any { it.isAttached && it.viewport === viewport }
            previews.holdEntrances(viewport, hold)
            waiting = waiting || hold
        }
        previews.refreshEntranceHolds()
        // Every placement first; then what's in view comes on screen before the margin, so its
        // reads take the free slots first.
        val placed = currentMarkers.map { it to placement(it) }
        for ((marker, place) in placed) marker.isInView = place.second
        for ((marker, place) in placed) if (!place.first) marker.isOnScreen = false
        for ((marker, place) in placed) if (place.second) marker.isOnScreen = true
        for ((marker, place) in placed) if (place.first && !place.second) marker.isOnScreen = true
        // A viewport still opening, with previews in it, is asked again once it may have
        // settled; one without any (an empty draft) is left alone.
        if (waiting && !settleScheduled) {
            settleScheduled = true
            settleChecks += 1
            handler.postDelayed({
                settleScheduled = false
                setNeedsUpdate()
            }, 100)
        }
    }

    private fun viewport(marker: AgentImageMarkerView): AgentImageViewport? =
        marker.viewport?.takeIf { it in viewports }

    /** On screen (within the margin), and in view (inside what's visible). */
    private fun placement(marker: AgentImageMarkerView): Pair<Boolean, Boolean> {
        if (!marker.isAttached) return false to false
        val viewport = viewport(marker) ?: return false to false
        if (!viewport.imageViewportIsVisible) return false to false
        val bounds = marker.bounds ?: return false to false
        // A held image's marker has no height: it's where its image will be.
        val frame = Rect(bounds.left, bounds.top - 0.5f, bounds.right, bounds.bottom + 0.5f)
        val visible = viewport.imageViewportVisibleRect
        return isOnScreen(frame, visible) to (!visible.isEmpty && frame.overlaps(visible))
    }

    /** An entry is about to change its layout: the rows showing it ease. */
    private fun revised(key: AgentImageKey) {
        for (marker in markers.toList()) {
            if (marker.sighting?.key == key) viewport(marker)?.imageContentRevised(marker.rowKey)
        }
    }

    /** The row holding [marker] is about to change height for an image. */
    fun revised(marker: AgentImageMarkerView) {
        viewport(marker)?.imageContentRevised(marker.rowKey)
    }

    /** The row keyed [row] in [viewport] is about to change height for an image ([AgentImageRowAnchor]). */
    fun revised(viewport: AgentImageViewport?, row: Any?) {
        viewport?.takeIf { it in viewports }?.imageContentRevised(row)
    }

    companion object Key : DependencyKey<AgentImageVisibility> {
        /** Screens beyond the visible part that still count as on screen. */
        const val MARGIN = 0.5f

        override val liveValue: AgentImageVisibility by lazy { AgentImageVisibility(dependency(AgentImagePreviews)) }
        override val testValue: AgentImageVisibility get() = AgentImageVisibility(AgentImagePreviews.testValue)

        /** Whether a marker at [frame] counts as on screen for [visible]. */
        fun isOnScreen(frame: Rect, visible: Rect): Boolean {
            if (visible.isEmpty) return false
            val margin = visible.height * MARGIN
            return frame.overlaps(Rect(visible.left, visible.top - margin, visible.right, visible.bottom + margin))
        }
    }
}

/** The block a gallery is in, keying its laid-out place (iOS `agentImageBlock`). */
val LocalAgentImageBlock = staticCompositionLocalOf<Int?> { null }

/**
 * The place in an image row of a layout that changes the row's height itself (`ToolImageMosaic`;
 * iOS `AgentImageRowAnchor`): [revised] marks the row before it does. Held in `remember`, never
 * observed; [Placement] records the row it's composed in.
 */
class AgentImageRowAnchor {
    private var viewport: AgentImageViewport? = null
    private var row: Any? = null
    private var visibility: AgentImageVisibility? = null

    fun revised() {
        visibility?.revised(viewport, row)
    }

    companion object {
        /** Records the viewport and row the anchor is composed in (iOS `Placement`). */
        @Composable
        fun Placement(anchor: AgentImageRowAnchor) {
            val viewport = LocalAgentImageViewport.current
            val row = LocalAgentImageRow.current
            val visibility = remember { dependency(AgentImageVisibility) }
            SideEffect {
                anchor.viewport = viewport
                anchor.row = row
                anchor.visibility = visibility
            }
        }
    }
}

/**
 * Holds a preview's read while it's on screen (iOS `AgentImageMarkerView`). Lives behind the
 * preview, invisible to touches and accessibility; its window bounds come from the composable.
 */
class AgentImageMarkerView(
    private val previews: AgentImagePreviews,
    private val visibility: AgentImageVisibility,
) {
    /** Other inline media reuse the transcript's actual viewport test. */
    var onVisibilityChange: ((Boolean) -> Unit)? = null
    var sighting: AgentImageSighting? = null
        private set
    private var request: AgentImageRequest? = null
    private var shown: AgentImageSighting? = null
    /** The list it's in, and its row there. */
    var viewport: AgentImageViewport? = null
        internal set
    var rowKey: Any? = null
        internal set
    /** Composed and placed (iOS `window != nil`). */
    var isAttached = false
        private set
    /** Its bounds in window coordinates, unclipped by the list. */
    var bounds: Rect? = null
        private set

    var isOnScreen = false
        set(value) {
            if (value == field) return
            field = value
            sync()
            onVisibilityChange?.invoke(value)
        }

    /** Inside what the reader sees, not just the margin: its read goes first. */
    var isInView = false
        set(value) {
            if (value == field) return
            field = value
            syncPriority()
        }

    /** The key this marker counts as in view for. */
    private var countedInView: AgentImageKey? = null

    fun contentRevised() = visibility.revised(this)

    fun configure(sighting: AgentImageSighting, request: AgentImageRequest) {
        this.request = request
        if (sighting == this.sighting) return
        this.sighting = sighting
        sync()
        visibility.setNeedsUpdate()
    }

    /** Shows the current sighting while on screen, and nothing otherwise. */
    private fun sync() {
        val wanted = if (isOnScreen && isAttached) sighting else null
        if (wanted == shown) return
        shown?.let(previews::hide)
        shown = wanted
        // Its priority before its read is asked for, so the queue sees it.
        syncPriority()
        val request = request
        if (wanted != null && request != null) previews.show(wanted, request)
    }

    /** Counts this marker in view for its shown key, both ways. */
    private fun syncPriority() {
        val wanted = if (isInView) shown?.key else null
        if (wanted == countedInView) return
        countedInView?.let(previews::leaveView)
        countedInView = wanted
        wanted?.let(previews::enterView)
    }

    internal fun attach() {
        isAttached = true
        visibility.add(this)
    }

    internal fun positioned(coordinates: LayoutCoordinates) {
        if (!coordinates.isAttached) return
        val origin = coordinates.positionInWindow()
        val next = Rect(origin, coordinates.size.toSize())
        if (next == bounds) return
        bounds = next
        visibility.setNeedsUpdate()
    }

    /** Leaves the screen for good (the composable left the composition). */
    fun dismantle() {
        isOnScreen = false
        isAttached = false
        sighting = null
        sync()
        visibility.remove(this)
    }
}

/**
 * A preview's marker (iOS `AgentImageMarker`): sized by [modifier] (usually `matchParentSize()`
 * behind the preview), hidden from accessibility, reading [sighting] while it's on screen.
 */
@Composable
fun AgentImageMarker(
    sighting: AgentImageSighting,
    request: AgentImageRequest,
    modifier: Modifier = Modifier,
    onVisibilityChange: ((Boolean) -> Unit)? = null,
) {
    val viewport = LocalAgentImageViewport.current
    val row = LocalAgentImageRow.current
    val marker = remember { AgentImageMarkerView(dependency(AgentImagePreviews), dependency(AgentImageVisibility)) }
    DisposableEffect(marker) {
        marker.attach()
        onDispose { marker.dismantle() }
    }
    SideEffect {
        marker.viewport = viewport
        marker.rowKey = row
        marker.onVisibilityChange = onVisibilityChange
        marker.configure(sighting, request)
    }
    Spacer(modifier.onGloballyPositioned(marker::positioned).clearAndSetSemantics {})
}

/**
 * A marker that only reports whether it's on screen, for other inline media that reuse the
 * transcript's viewport test (iOS `ImageLinkVisibilityMarker`: an `AgentImageMarkerView` without a
 * sighting). [changed] gets the visibility and the marker, whose [AgentImageMarkerView.contentRevised]
 * tells the list its row is about to change height.
 */
@Composable
fun AgentImageVisibilityMarker(modifier: Modifier = Modifier, changed: (Boolean, AgentImageMarkerView) -> Unit) {
    val viewport = LocalAgentImageViewport.current
    val row = LocalAgentImageRow.current
    val marker = remember { AgentImageMarkerView(dependency(AgentImagePreviews), dependency(AgentImageVisibility)) }
    val current by androidx.compose.runtime.rememberUpdatedState(changed)
    DisposableEffect(marker) {
        marker.attach()
        onDispose {
            marker.onVisibilityChange = null
            marker.dismantle()
        }
    }
    SideEffect {
        marker.viewport = viewport
        marker.rowKey = row
        if (marker.onVisibilityChange == null) marker.onVisibilityChange = { visible -> current(visible, marker) }
    }
    Spacer(modifier.onGloballyPositioned(marker::positioned).clearAndSetSemantics {})
}
