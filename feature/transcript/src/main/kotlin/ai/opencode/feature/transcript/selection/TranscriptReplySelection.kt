package ai.opencode.feature.transcript.selection

import ai.opencode.feature.transcript.R
import ai.opencode.feature.transcript.markdown.MarkdownQuote
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuComponent
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuData
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

/**
 * One selection across every paragraph, list item, table cell, formula and code block of one reply,
 * owned by the transcript list (a reply spans several list rows, so no single text view can hold
 * it).
 *
 * The leaves register as [TranscriptSelectionAnchorNode]s and draw their own highlight; this
 * holds the selection as (leaf, offset) ends, so it survives the reply's document being rebuilt
 * as its text streams, hit-tests touches, moves the ends while a handle or a hold drags, and
 * places the handles, the magnifier and the menu. Android-native visuals: Material handles and
 * highlight from [LocalTextSelectionColors], the platform magnifier, and the platform floating
 * text toolbar (Copy, Quote, Select All) above the selection.
 */
@Stable
class TranscriptReplySelection {
    /** What the list supplies. */
    interface Host {
        /** The visible band of the list, in its own space: below the header, above the composer and keyboard (px). */
        val visibleTop: Float
        val visibleBottom: Float

        /** True while the list coasts or glides: a touch then only stops it. */
        val isCoasting: Boolean

        /** The selection document of the reply holding [itemID], or null when it isn't a reply. */
        fun document(itemID: String): TranscriptSelectionDocument?

        /** A drag moved: autoscroll near the edges from now until it ends. */
        fun dragMoved()

        /** A selection began or ended; [byReader] when the reader tapped it away. */
        fun activeChanged(active: Boolean, byReader: Boolean)

        /** Reads the list's layout, so a draw that calls it redraws as the list scrolls or relayouts. */
        fun observeLayout()
    }

    data class End(val key: TranscriptSelectionLeafKey, val local: Int)

    private class Ends(val start: End, val end: End)

    /** A selection saved when the activity was recreated, put back once its reply is in the list. */
    private class Pending(val ends: Ends, val menu: Boolean)

    private sealed interface Drag {
        class Sweep(val base: TextRange) : Drag
        class Handle(val fixed: End, val grab: Offset) : Drag
    }

    class Hit internal constructor(
        internal val document: TranscriptSelectionDocument,
        val offset: Int,
        val isLink: Boolean,
        /** Code or a formula: a hold on it while it's selected lifts its block to drag. */
        val liftsWhenSelected: Boolean,
    )

    class HandleHit internal constructor(val isEnd: Boolean, internal val caret: Rect)

    var host: Host? = null

    /** Pixels per dp. */
    var density: Float = 1f

    /** The list's coordinates: touches, handles, the magnifier and the menu are placed in them. */
    var container: LayoutCoordinates? = null

    var document: TranscriptSelectionDocument? = null
        private set

    // Bumped when the document changes under the same ends, so the highlights redraw.
    private var revision by mutableIntStateOf(0)
    private var ends by mutableStateOf<Ends?>(null)
    private var drag: Drag? = null
    private var movesEnd = true
    private var hasMoved = false
    private var finger = Offset.Unspecified

    var isDragging by mutableStateOf(false)
        private set

    /** Where the magnifier looks, in the list's space; unspecified when it's hidden. */
    var magnifierCenter by mutableStateOf(Offset.Unspecified)
        private set

    /** The menu shows while this is set, nothing is dragging and the list is still. */
    var menuWanted by mutableStateOf(false)
        private set

    /** Bumped each time the menu is presented anew, so it re-reads its items. */
    var menuRequest by mutableIntStateOf(0)
        private set

    private val anchors = HashMap<TranscriptSelectionLeafKey, TranscriptSelectionAnchorNode>()
    internal var overlay: TranscriptReplySelectionOverlayNode? = null

    val isSelected: Boolean get() = ends != null

    private var pending: Pending? = null
    private var restoreAttempted = false

    // MARK: Anchors

    internal fun register(node: TranscriptSelectionAnchorNode) {
        anchors[node.key] = node
    }

    internal fun unregister(node: TranscriptSelectionAnchorNode) {
        if (anchors[node.key] === node) anchors.remove(node.key)
    }

    /** A leaf moved (the list scrolled, a row grew): the handles follow in the same frame. */
    internal fun anchorMoved() {
        if (ends != null) overlay?.invalidateDraw()
    }

    /** The drawn part of [key]'s text to highlight, or null. Read in the leaf's draw phase. */
    internal fun drawnRange(key: TranscriptSelectionLeafKey): TextRange? {
        val ends = ends ?: return null
        if (revision < 0) return null
        val document = document ?: return null
        val range = range(document, ends) ?: return null
        val index = document.index(key) ?: return null
        val leaf = document.leaves[index]
        val start = document.starts[index]
        val lower = maxOf(range.start, start) - start
        val upper = minOf(range.end, start + leaf.length) - start
        if (upper <= lower) return null
        val drawnLower = leaf.drawnOffset(lower)
        val drawnUpper = leaf.drawnOffset(upper)
        return TextRange(drawnLower, drawnUpper - drawnLower)
    }

    // MARK: Selection

    /** The selected range of [document], or null. */
    fun range(): TextRange? {
        val ends = ends ?: return null
        val document = document ?: return null
        return range(document, ends)
    }

    private fun range(document: TranscriptSelectionDocument, ends: Ends): TextRange? {
        val start = offset(document, ends.start) ?: return null
        val end = offset(document, ends.end) ?: return null
        val lower = minOf(start, end)
        return TextRange(lower, abs(end - start))
    }

    private fun offset(document: TranscriptSelectionDocument, end: End): Int? {
        val index = document.index(end.key) ?: return null
        return document.offset(index, end.local)
    }

    fun copiedText(): String? {
        val range = range() ?: return null
        return document?.copiedText(range)
    }

    fun quotedText(): String? {
        val range = range() ?: return null
        return document?.quotedText(range)
    }

    private fun select(range: TextRange) {
        val document = document ?: return
        val widened = if (range.length > 0) document.composed(range) else range
        val start = document.locate(widened.start) ?: return
        val end = document.locate(widened.end) ?: return
        val next = Ends(
            End(document.leaves[start.first].key, start.second),
            End(document.leaves[end.first].key, end.second),
        )
        setEnds(next, byReader = false)
    }

    private fun setEnds(next: Ends?, byReader: Boolean) {
        val was = ends != null
        ends = next
        val now = next != null
        if (was != now) host?.activeChanged(now, byReader)
    }

    /** Ends the magnifier, any drag and the menu too. */
    fun clear(byReader: Boolean = false) {
        // The list's first configuration clears before the saved selection is put back.
        if (restoreAttempted) pending = null
        drag = null
        isDragging = false
        hasMoved = false
        magnifierCenter = Offset.Unspecified
        menuWanted = false
        if (ends != null) setEnds(null, byReader)
    }

    fun selectAll() {
        val document = document ?: return
        select(TextRange(0, document.length))
        presentMenu()
    }

    fun presentMenu() {
        if (ends == null) return
        menuWanted = true
        menuRequest += 1
    }

    fun dismissMenu() {
        menuWanted = false
    }

    /**
     * The reply's text changed: rebuild its document and keep the selection on the same leaves, or
     * drop it when they're gone.
     */
    fun refresh() {
        val ends = ends ?: return
        val next = host?.document(ends.start.key.itemID)
        if (next == null) {
            clear()
            return
        }
        val start = next.index(ends.start.key)
        val end = next.index(ends.end.key)
        if (start == null || end == null) {
            clear()
            return
        }
        document = next
        revision += 1
        val startLocal = minOf(ends.start.local, next.leaves[start].length)
        val endLocal = minOf(ends.end.local, next.leaves[end].length)
        this.ends = Ends(End(ends.start.key, startLocal), End(ends.end.key, endLocal))
    }

    /**
     * Puts back the selection [Saver] kept across the activity being recreated, once the list has
     * its reply; dropped if its leaves are gone, or once the reader selects, sends or jumps first.
     * Called after each list update.
     */
    fun restorePending() {
        restoreAttempted = true
        val pending = pending ?: return
        val next = host?.document(pending.ends.start.key.itemID) ?: return
        this.pending = null
        val start = next.index(pending.ends.start.key) ?: return
        val end = next.index(pending.ends.end.key) ?: return
        document = next
        revision += 1
        val startLocal = minOf(pending.ends.start.local, next.leaves[start].length)
        val endLocal = minOf(pending.ends.end.local, next.leaves[end].length)
        setEnds(Ends(End(pending.ends.start.key, startLocal), End(pending.ends.end.key, endLocal)), byReader = false)
        if (pending.menu) presentMenu()
    }

    /** Uses [document] for what comes next, dropping a selection in another reply first. */
    private fun adopt(document: TranscriptSelectionDocument) {
        pending = null
        if (document === this.document) return
        if (ends != null) clear()
        this.document = document
        revision += 1
    }

    // MARK: Hit testing

    /** The reply text nearest [point] within reach. */
    fun hit(point: Offset): Hit? {
        val container = container ?: return null
        var best: TranscriptSelectionAnchorNode? = null
        var bestLocal = Offset.Zero
        var bestDistance = Float.MAX_VALUE
        for (node in anchors.values) {
            val local = node.local(container, point) ?: continue
            val distance = node.distance(local)
            if (distance < bestDistance) {
                best = node
                bestLocal = local
                bestDistance = distance
            }
        }
        if (best == null || bestDistance > HIT_REACH * density) return null
        var document = this.document
        if (document == null || ends == null || document.index(best.key) == null) {
            document = host?.document(best.key.itemID) ?: return null
        }
        val index = document.index(best.key) ?: return null
        val leaf = document.leaves[index]
        val local = leaf.textOffset(best.drawnOffset(bestLocal))
        val offset = document.nearestBoundary(document.offset(index, local))
        val liftsWhenSelected = leaf.role is TranscriptSelectionLeaf.Role.Code || leaf.role == TranscriptSelectionLeaf.Role.Math
        return Hit(document, offset, leaf.isLink(local), liftsWhenSelected)
    }

    /**
     * A hold at [point] (in the list) selects reply text. Code and formula drag sources ask this
     * first, so their lift can't take a hold that selects.
     */
    fun holdSelects(point: Offset): Boolean {
        val host = host ?: return false
        if (point.y < host.visibleTop || host.isCoasting) return false
        val hit = hit(point) ?: return false
        return holdSelects(hit)
    }

    /**
     * A hold on a link opens its menu instead, and one on code or a formula that's already
     * selected lifts its block to drag.
     */
    internal fun holdSelects(hit: Hit): Boolean {
        if (hit.isLink) return false
        if (!hit.liftsWhenSelected || hit.document !== document) return true
        val range = range() ?: return true
        if (range.length == 0) return true
        val insideSelection = hit.offset >= range.start && hit.offset <= range.end
        return !insideSelection
    }

    /** The document offset nearest [point] among the reply's leaves on screen, at any distance. */
    private fun offsetAt(point: Offset): Int? {
        val container = container ?: return null
        val document = document ?: return null
        var best: TranscriptSelectionAnchorNode? = null
        var bestLocal = Offset.Zero
        var bestDistance = Float.MAX_VALUE
        for (node in anchors.values) {
            if (document.index(node.key) == null) continue
            val local = node.local(container, point) ?: continue
            val distance = node.distance(local)
            if (distance < bestDistance) {
                best = node
                bestLocal = local
                bestDistance = distance
            }
        }
        if (best == null) return null
        val index = document.index(best.key) ?: return null
        val leaf = document.leaves[index]
        val local = leaf.textOffset(best.drawnOffset(bestLocal))
        return document.nearestBoundary(document.offset(index, local))
    }

    /** A handle within [HANDLE_REACH] of [point], the nearer one when both are. */
    fun handleAt(point: Offset): HandleHit? {
        if (ends == null) return null
        val reach = HANDLE_REACH * density
        var best: HandleHit? = null
        var bestDistance = reach
        for (isEnd in booleanArrayOf(false, true)) {
            val caret = caret(isEnd) ?: continue
            val center = handleCenter(caret, isEnd)
            var distance = (point - center).getDistance()
            // The caret's line counts too: a handle's stem.
            if (point.y >= caret.top && point.y <= caret.bottom) {
                distance = minOf(distance, abs(point.x - caret.left))
            }
            if (distance <= bestDistance) {
                best = HandleHit(isEnd, caret)
                bestDistance = distance
            }
        }
        return best
    }

    // MARK: Drags

    /** A hold on a word: selects it and sweeps as the finger moves. */
    fun beginSweep(hit: Hit, point: Offset): Boolean {
        adopt(hit.document)
        val document = document ?: return false
        val word = document.wordRange(hit.offset)
        if (word.length == 0) return false
        select(word)
        drag = Drag.Sweep(word)
        isDragging = true
        menuWanted = false
        movesEnd = true
        // The magnifier shows from the hold on, as the system's does.
        hasMoved = true
        finger = point
        magnifierCenter = magnifierPoint()
        return true
    }

    /** A double tap: the word under it, with the menu. */
    fun selectWord(hit: Hit) {
        adopt(hit.document)
        val document = document ?: return
        val word = document.wordRange(hit.offset)
        if (word.length == 0) return
        select(word)
        movesEnd = true
        presentMenu()
    }

    fun beginHandleDrag(handle: HandleHit, point: Offset) {
        val ends = ends ?: return
        val fixed = if (handle.isEnd) ends.start else ends.end
        val caretMiddle = Offset(handle.caret.left, handle.caret.center.y)
        drag = Drag.Handle(fixed, point - caretMiddle)
        isDragging = true
        menuWanted = false
        movesEnd = handle.isEnd
        // A touch on a handle shows no magnifier; it comes with the first move.
        hasMoved = false
        finger = point
    }

    fun moveDrag(point: Offset) {
        if (drag == null) return
        finger = point
        hasMoved = true
        updateDrag()
        host?.dragMoved()
    }

    /** Re-reads the finger against the text, as the list autoscrolls under a still finger. */
    fun updateDrag() {
        val drag = drag ?: return
        val document = document ?: return
        when (drag) {
            is Drag.Sweep -> {
                val offset = offsetAt(finger)
                if (offset != null) {
                    movesEnd = offset >= drag.base.start
                    val lower = minOf(drag.base.start, offset)
                    val upper = maxOf(drag.base.end, offset)
                    select(TextRange(lower, upper - lower))
                }
            }
            is Drag.Handle -> {
                val offset = offsetAt(finger - drag.grab)
                val fixed = offset(document, drag.fixed)
                if (offset != null && fixed != null && offset != fixed) {
                    movesEnd = offset > fixed
                    select(TextRange(minOf(offset, fixed), abs(offset - fixed)))
                }
            }
        }
        if (hasMoved) magnifierCenter = magnifierPoint()
    }

    fun endDrag() {
        if (drag == null) return
        drag = null
        isDragging = false
        hasMoved = false
        magnifierCenter = Offset.Unspecified
        presentMenu()
    }

    /** The finger, for the autoscroll; unspecified when nothing drags. */
    val dragPoint: Offset get() = if (drag == null) Offset.Unspecified else finger

    private fun magnifierPoint(): Offset {
        val caret = caret(movesEnd) ?: return Offset.Unspecified
        return Offset(caret.left, caret.center.y)
    }

    // MARK: Geometry

    /**
     * One end's caret in the list's space (a zero-width rect over its line), when the reader can
     * see it: a code band scrolled sideways, or the list, hides it.
     */
    internal fun caret(isEnd: Boolean): Rect? {
        val ends = ends ?: return null
        val document = document ?: return null
        val container = container ?: return null
        val range = range(document, ends) ?: return null
        val offset = if (isEnd) range.end else range.start
        val located = document.locate(offset) ?: return null
        var index = located.first
        var local = located.second
        // An end on a leaf's first character draws at the end of the leaf before; a start at a
        // leaf's end draws at the start of the next one.
        if (isEnd && local == 0 && index > 0 && offset > range.start) {
            index -= 1
            local = document.leaves[index].length
        } else if (!isEnd && local == document.leaves[index].length && index < document.leaves.size - 1 && offset < range.end) {
            index += 1
            local = 0
        }
        val leaf = document.leaves[index]
        val node = anchors[leaf.key] ?: return null
        val caret = node.caret(leaf.drawnOffset(local), isEnd) ?: return null
        val placed = node.toContainer(container, caret) ?: return null
        val visible = node.visibleBounds(container) ?: return null
        if (placed.left < visible.left - 1f || placed.left > visible.right + 1f) return null
        if (placed.bottom < visible.top - 1f || placed.top > visible.bottom + 1f) return null
        return placed
    }

    internal fun handleCenter(caret: Rect, isEnd: Boolean): Offset {
        val radius = HANDLE_RADIUS * density
        val x = if (isEnd) caret.left + radius else caret.left - radius
        return Offset(x, caret.bottom + radius)
    }

    /** The selection's visible bounds for the menu. */
    internal fun menuBounds(): Rect? {
        val ends = ends ?: return null
        val document = document ?: return null
        val container = container ?: return null
        val range = range(document, ends) ?: return null
        val top = host?.visibleTop ?: 0f
        val bottom = host?.visibleBottom ?: container.size.height.toFloat()
        val first = document.locate(range.start)?.first ?: return null
        val last = document.locate(range.end)?.first ?: return null
        var union: Rect? = null
        for (index in first..last) {
            val leaf = document.leaves[index]
            val node = anchors[leaf.key] ?: continue
            val drawn = drawnRange(leaf.key) ?: continue
            val bounds = node.bounds(drawn) ?: continue
            val placed = node.toContainer(container, bounds) ?: continue
            val visible = node.visibleBounds(container) ?: continue
            var clipped = placed.intersect(visible)
            clipped = clipped.intersect(Rect(clipped.left, top, clipped.right, bottom))
            if (clipped.width < 0f || clipped.height <= 0f) continue
            val current = union
            union = if (current == null) {
                clipped
            } else {
                Rect(minOf(current.left, clipped.left), minOf(current.top, clipped.top), maxOf(current.right, clipped.right), maxOf(current.bottom, clipped.bottom))
            }
        }
        val bounds = union ?: return null
        if (bounds.height <= (bottom - top) * TALL_SELECTION) return bounds
        val caret = caret(movesEnd)
        if (caret != null && caret.top >= top && caret.bottom <= bottom) return caret
        val middle = (top + bottom) / 2f
        return Rect(bounds.left, middle, bounds.right, middle)
    }

    companion object {
        /**
         * For the list's `rememberSaveable`: the selected ends (and whether the menu was up)
         * outlive the activity being recreated. A drag in progress, the magnifier and the document
         * are rebuilt, not saved.
         */
        val Saver: Saver<TranscriptReplySelection, List<Any>> = Saver(
            save = { selection ->
                val saved = selection.ends?.let { Pending(it, selection.menuWanted) } ?: selection.pending
                saved?.let { (listOf(it.ends.start, it.ends.end).flatMap { end -> listOf(end.key.itemID, end.key.blockID, end.key.part, end.local) }) + it.menu }
                    ?: emptyList()
            },
            restore = { saved ->
                TranscriptReplySelection().apply {
                    if (saved.size == 9) {
                        fun end(at: Int) = End(TranscriptSelectionLeafKey(saved[at] as String, saved[at + 1] as Int, saved[at + 2] as Int), saved[at + 3] as Int)
                        pending = Pending(Ends(end(0), end(4)), saved[8] as Boolean)
                    }
                }
            },
        )

        /** How far from text a hold or double tap still lands (vertical counts 4×). */
        const val HIT_REACH = 12f

        const val HANDLE_REACH = 24f

        /** Material's selection handle: a 25 dp drop, as Compose draws it. */
        const val HANDLE_RADIUS = 12.5f

        /** A hold selects after 0.4 s. */
        const val HOLD_MILLIS = 400L

        /** A selection taller than 60% of the visible list puts the menu at a caret. */
        const val TALL_SELECTION = 0.6f
    }
}

/** The list's reply selection, for the leaves inside its rows to anchor to. */
val LocalTranscriptReplySelection = staticCompositionLocalOf<TranscriptReplySelection?> { null }

// MARK: Handles

/**
 * Draws the selection's two handles over the list, in Material's teardrop shape and handle
 * colour, under the session header and composer like the text. Redrawn as anchors move, never
 * recomposed.
 */
internal fun Modifier.transcriptReplySelectionHandles(selection: TranscriptReplySelection): Modifier =
    this then TranscriptReplySelectionOverlayElement(selection)

private class TranscriptReplySelectionOverlayElement(val selection: TranscriptReplySelection) :
    ModifierNodeElement<TranscriptReplySelectionOverlayNode>() {
    override fun create() = TranscriptReplySelectionOverlayNode(selection)

    override fun update(node: TranscriptReplySelectionOverlayNode) {
        node.update(selection)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "transcriptReplySelectionHandles"
    }

    override fun equals(other: Any?): Boolean = other is TranscriptReplySelectionOverlayElement && other.selection === selection

    override fun hashCode(): Int = selection.hashCode()
}

internal class TranscriptReplySelectionOverlayNode(private var selection: TranscriptReplySelection) :
    Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    fun update(selection: TranscriptReplySelection) {
        if (selection === this.selection) return
        if (this.selection.overlay === this) this.selection.overlay = null
        this.selection = selection
        if (isAttached) selection.overlay = this
        invalidateDraw()
    }

    override fun onAttach() {
        selection.overlay = this
    }

    override fun onDetach() {
        if (selection.overlay === this) selection.overlay = null
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!selection.isSelected) return
        selection.host?.observeLayout()
        val color = currentValueOf(LocalTextSelectionColors).handleColor
        val radius = TranscriptReplySelection.HANDLE_RADIUS * selection.density
        for (isEnd in booleanArrayOf(false, true)) {
            val caret = selection.caret(isEnd) ?: continue
            val center = selection.handleCenter(caret, isEnd)
            drawCircle(color, radius, center)
            // The square corner points at the caret: up-right for the start, up-left for the end.
            val left = if (isEnd) caret.left else caret.left - radius
            drawRect(color, Offset(left, caret.bottom), Size(radius, radius))
        }
    }
}

// MARK: Gestures

/**
 * A hold (0.4 s) on reply text selects its word and sweeps as the finger moves; a double tap
 * selects a word with the menu; a touch on a handle drags it; a tap elsewhere clears the selection
 * (and still reaches what it tapped). A touch under the header, or one that catches the list
 * coasting, only does what it would have done.
 */
internal fun Modifier.transcriptReplySelectionGestures(selection: TranscriptReplySelection): Modifier =
    pointerInput(selection) {
        var lastTapUptime = 0L
        var lastTapPoint = Offset.Unspecified
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val point = down.position
            val host = selection.host
            if (host == null || point.y < host.visibleTop || host.isCoasting) return@awaitEachGesture

            val handle = selection.handleAt(point)
            if (handle != null) {
                down.consume()
                selection.beginHandleDrag(handle, point)
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) {
                        change?.consume()
                        break
                    }
                    if (change.position != change.previousPosition) selection.moveDrag(change.position)
                    change.consume()
                }
                selection.endDrag()
                return@awaitEachGesture
            }

            // Wait for the hold, a lift (a tap) or a move (the list's).
            val lifted = withTimeoutOrNull(TranscriptReplySelection.HOLD_MILLIS) { awaitLiftOrMove(down.id, point) }
            if (lifted == null) {
                val hit = selection.hit(point)
                // A hold on a link opens its menu instead; one on selected code lifts its block.
                if (hit == null || !selection.holdSelects(hit)) return@awaitEachGesture
                if (!selection.beginSweep(hit, point)) return@awaitEachGesture
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) {
                        change?.consume()
                        break
                    }
                    if (change.position != change.previousPosition) selection.moveDrag(change.position)
                    change.consume()
                }
                selection.endDrag()
                return@awaitEachGesture
            }
            if (!lifted) return@awaitEachGesture

            val now = down.uptimeMillis
            val isDoubleTap = lastTapPoint != Offset.Unspecified &&
                now - lastTapUptime <= viewConfiguration.doubleTapTimeoutMillis &&
                (point - lastTapPoint).getDistance() <= viewConfiguration.touchSlop * 4
            lastTapUptime = now
            lastTapPoint = point
            if (isDoubleTap) {
                lastTapPoint = Offset.Unspecified
                val hit = selection.hit(point)
                if (hit != null && !hit.isLink) {
                    selection.selectWord(hit)
                    return@awaitEachGesture
                }
            }
            if (selection.isSelected) selection.clear(byReader = true)
        }
    }

/** True when the finger lifts where it went down (a tap), false when it moves away or another joins. */
private suspend fun AwaitPointerEventScope.awaitLiftOrMove(id: PointerId, start: Offset): Boolean {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == id }
        if (change == null || event.changes.size > 1) return false
        if (!change.pressed) return true
        if ((change.position - start).getDistance() > viewConfiguration.touchSlop) return false
    }
}

// MARK: Menu

/**
 * The selection's menu: Copy, Quote and Select All in the platform's floating text toolbar, above
 * what's seen of the selection. Hidden while anything drags or the list moves; shown again when it
 * settles.
 */
@Composable
internal fun TranscriptReplySelectionMenu(
    selection: TranscriptReplySelection,
    isScrolling: () -> Boolean,
    onCopy: (String) -> Unit,
    onQuote: (String) -> Unit,
) {
    val provider = LocalTextContextMenuToolbarProvider.current ?: return
    val resources = LocalContext.current.resources
    val copyLabel = resources.getString(android.R.string.copy)
    val selectAllLabel = resources.getString(android.R.string.selectAll)
    val quoteLabel = stringResource(R.string.transcript_quote)
    val currentCopy by rememberUpdatedState(onCopy)
    val currentQuote by rememberUpdatedState(onQuote)
    val currentIsScrolling by rememberUpdatedState(isScrolling)
    val data = remember(selection, copyLabel, selectAllLabel, quoteLabel) {
        object : TextContextMenuDataProvider {
            override fun position(destinationCoordinates: LayoutCoordinates): Offset {
                val bounds = contentBounds(destinationCoordinates)
                return Offset(bounds.center.x, bounds.top)
            }

            override fun contentBounds(destinationCoordinates: LayoutCoordinates): Rect {
                val container = selection.container ?: return Rect.Zero
                val bounds = selection.menuBounds() ?: return Rect.Zero
                if (!container.isAttached || !destinationCoordinates.isAttached) return Rect.Zero
                val topLeft = destinationCoordinates.localPositionOf(container, bounds.topLeft)
                return Rect(topLeft, bounds.size)
            }

            override fun data(): TextContextMenuData {
                val items = ArrayList<TextContextMenuComponent>()
                items += TextContextMenuItem(key = TextContextMenuKeys.CopyKey, label = copyLabel) {
                    val text = selection.copiedText()
                    selection.dismissMenu()
                    close()
                    if (text != null) currentCopy(text)
                }
                val quoted = selection.quotedText()
                if (quoted != null && MarkdownQuote.blockquote(quoted) != null) {
                    items += TextContextMenuItem(key = QuoteKey, label = quoteLabel) {
                        selection.clear()
                        close()
                        currentQuote(quoted)
                    }
                }
                val range = selection.range()
                val length = selection.document?.length ?: 0
                if (range != null && range.length < length) {
                    items += TextContextMenuItem(key = TextContextMenuKeys.SelectAllKey, label = selectAllLabel) {
                        close()
                        selection.selectAll()
                    }
                }
                return TextContextMenuData(items)
            }
        }
    }
    LaunchedEffect(provider, data) {
        snapshotFlow {
            val shows = selection.menuWanted && selection.isSelected && !selection.isDragging && !currentIsScrolling()
            if (shows) selection.menuRequest else -1
        }
            .distinctUntilChanged()
            .collectLatest { request ->
                if (request < 0) return@collectLatest
                provider.showTextContextMenu(data)
                // Closed by one of its items or by the system: wait for the next presentation.
                if (selection.menuRequest == request) selection.dismissMenu()
            }
    }
}

private object QuoteKey
