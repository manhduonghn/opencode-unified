package ai.opencode.feature.transcript.selection

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.text.TextLayoutResult

/**
 * One piece of reply text (a paragraph, a list item, a code block, a table cell, a display formula)
 * reporting where its characters are drawn, so the list's [TranscriptReplySelection] can hit-test
 * touches, place handles and the menu, and read across every leaf of a reply as one selection.
 *
 * The leaf draws its own part of the highlight, under its glyphs, reading the selection only in the
 * draw phase: changing the selection redraws the selected leaves and recomposes nothing.
 *
 * [layout] is the text's layout; null for a leaf drawn as an image (typeset display math), which
 * is one drawn character covering its bounds.
 */
internal fun Modifier.transcriptSelectionAnchor(
    key: TranscriptSelectionLeafKey,
    selection: TranscriptReplySelection,
    layout: (() -> TextLayoutResult?)?,
): Modifier = this then TranscriptSelectionAnchorElement(key, selection, layout)

private class TranscriptSelectionAnchorElement(
    val key: TranscriptSelectionLeafKey,
    val selection: TranscriptReplySelection,
    val layout: (() -> TextLayoutResult?)?,
) : ModifierNodeElement<TranscriptSelectionAnchorNode>() {
    override fun create() = TranscriptSelectionAnchorNode(key, selection, layout)

    override fun update(node: TranscriptSelectionAnchorNode) {
        node.update(key, selection, layout)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "transcriptSelectionAnchor"
    }

    // The layout lambda is remade on each composition; the key and the selection decide.
    override fun equals(other: Any?): Boolean =
        other is TranscriptSelectionAnchorElement && other.key == key && other.selection === selection && (other.layout == null) == (layout == null)

    override fun hashCode(): Int = key.hashCode() * 31 + selection.hashCode()
}

internal class TranscriptSelectionAnchorNode(
    var key: TranscriptSelectionLeafKey,
    private var selection: TranscriptReplySelection,
    private var layout: (() -> TextLayoutResult?)?,
) : Modifier.Node(), DrawModifierNode, LayoutAwareModifierNode, CompositionLocalConsumerModifierNode {
    var coordinates: LayoutCoordinates? = null
        private set

    val isImage: Boolean get() = layout == null

    fun update(key: TranscriptSelectionLeafKey, selection: TranscriptReplySelection, layout: (() -> TextLayoutResult?)?) {
        if (isAttached && (key != this.key || selection !== this.selection)) {
            this.selection.unregister(this)
            this.key = key
            this.selection = selection
            selection.register(this)
        } else {
            this.key = key
            this.selection = selection
        }
        this.layout = layout
        invalidateDraw()
    }

    override fun onAttach() {
        selection.register(this)
    }

    override fun onDetach() {
        selection.unregister(this)
        coordinates = null
    }

    // Placed, not globally positioned: the coordinates stay live as the list scrolls, so a
    // fling pays no callback per leaf per frame (the handles redraw from the list's layout).
    override fun onPlaced(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        selection.anchorMoved()
    }

    override fun ContentDrawScope.draw() {
        val range = selection.drawnRange(key)
        if (range != null) {
            val color = currentValueOf(LocalTextSelectionColors).backgroundColor
            val result = layout?.invoke()
            if (result == null) {
                drawRect(color)
            } else {
                val length = result.layoutInput.text.length
                val start = range.start.coerceIn(0, length)
                val end = range.end.coerceIn(0, length)
                if (end > start) drawPath(result.getPathForRange(start, end), color)
            }
        }
        drawContent()
    }

    /** The text's layout when it is on screen and laid out; null for an image leaf. */
    private fun textLayout(): TextLayoutResult? {
        val result = layout?.invoke() ?: return null
        if (result.lineCount == 0) return null
        return result
    }

    /** Where [point] (in [container]) lies in this leaf's space, or null off screen. */
    fun local(container: LayoutCoordinates, point: Offset): Offset? {
        val own = coordinates ?: return null
        if (!own.isAttached || !container.isAttached) return null
        return own.localPositionOf(container, point)
    }

    /** Vertical distance counts four times a horizontal one. */
    fun distance(point: Offset): Float {
        val result = textLayout()
        val own = coordinates ?: return Float.MAX_VALUE
        if (result == null) {
            return boxDistance(point, Rect(0f, 0f, own.size.width.toFloat(), own.size.height.toFloat()))
        }
        var best = Float.MAX_VALUE
        for (line in 0 until result.lineCount) {
            val box = Rect(result.getLineLeft(line), result.getLineTop(line), result.getLineRight(line), result.getLineBottom(line))
            val distance = boxDistance(point, box)
            if (distance < best) best = distance
        }
        return best
    }

    private fun boxDistance(point: Offset, box: Rect): Float {
        var dy = 0f
        if (point.y < box.top) dy = box.top - point.y
        if (point.y > box.bottom) dy = point.y - box.bottom
        var dx = 0f
        if (point.x < box.left) dx = box.left - point.x
        if (point.x > box.right) dx = point.x - box.right
        return dy * 4f + dx
    }

    /** The caret nearest [point] on its nearest line. */
    fun drawnOffset(point: Offset): Int {
        val result = textLayout()
        if (result == null) {
            val width = coordinates?.size?.width ?: 0
            return if (point.x < width / 2f) 0 else 1
        }
        var nearest = 0
        var nearestDistance = Float.MAX_VALUE
        for (line in 0 until result.lineCount) {
            val top = result.getLineTop(line)
            val bottom = result.getLineBottom(line)
            var distance = 0f
            if (point.y < top) distance = top - point.y
            if (point.y > bottom) distance = point.y - bottom
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearest = line
            }
        }
        val middle = (result.getLineTop(nearest) + result.getLineBottom(nearest)) / 2f
        return result.getOffsetForPosition(Offset(point.x, middle))
    }

    /**
     * The caret as a vertical segment (x, top..bottom) in this leaf's space. An end caret at a soft
     * wrap sits at the end of the line before it, as the system's end handle does.
     */
    fun caret(drawn: Int, isEnd: Boolean): Rect? {
        val own = coordinates ?: return null
        val result = textLayout()
        if (result == null) {
            val x = if (drawn <= 0) 0f else own.size.width.toFloat()
            return Rect(x, 0f, x, own.size.height.toFloat())
        }
        val length = result.layoutInput.text.length
        val offset = drawn.coerceIn(0, length)
        if (isEnd && offset > 0) {
            val before = result.getLineForOffset(offset - 1)
            if (before != result.getLineForOffset(offset)) {
                val x = result.getLineRight(before)
                return Rect(x, result.getLineTop(before), x, result.getLineBottom(before))
            }
        }
        val line = result.getLineForOffset(offset)
        val x = result.getHorizontalPosition(offset, usePrimaryDirection = true)
        return Rect(x, result.getLineTop(line), x, result.getLineBottom(line))
    }

    /** The bounds of [drawn] in this leaf's space (for the menu). */
    fun bounds(drawn: TextRange): Rect? {
        val own = coordinates ?: return null
        val result = textLayout() ?: return Rect(0f, 0f, own.size.width.toFloat(), own.size.height.toFloat())
        val length = result.layoutInput.text.length
        val start = drawn.start.coerceIn(0, length)
        val end = drawn.end.coerceIn(0, length)
        if (end <= start) return null
        return result.getPathForRange(start, end).getBounds()
    }

    /** What of this leaf the reader can see (a code band clips it). */
    fun visibleBounds(container: LayoutCoordinates): Rect? {
        val own = coordinates ?: return null
        if (!own.isAttached || !container.isAttached) return null
        return container.localBoundingBoxOf(own, clipBounds = true)
    }

    /** [rect] in this leaf's space, moved into [container]'s space. */
    fun toContainer(container: LayoutCoordinates, rect: Rect): Rect? {
        val own = coordinates ?: return null
        if (!own.isAttached || !container.isAttached) return null
        val topLeft = container.localPositionOf(own, rect.topLeft)
        return Rect(topLeft.x, topLeft.y, topLeft.x + rect.width, topLeft.y + rect.height)
    }
}
