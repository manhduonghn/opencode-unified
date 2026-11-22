package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.transcript.R
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.feature.transcript.selection.LocalTranscriptReplySelection
import ai.opencode.feature.transcript.selection.TranscriptSelectionLeafKey
import ai.opencode.feature.transcript.selection.transcriptSelectionAnchor
import ai.opencode.feature.transcript.user.TranscriptDragPayload
import ai.opencode.feature.transcript.user.TranscriptDragSurfaceStyle
import ai.opencode.feature.transcript.user.transcriptDragSource
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import ru.noties.jlatexmath.awt.AndroidGraphics2D
import ru.noties.jlatexmath.awt.Color as TeXColor

/**
 * TeX typeset into template images (alpha masks) that take the colour of the text around them via a
 * tint, so light, dark and a done task's secondary all reuse one image. Null for source jlatexmath
 * can't parse.
 */
object MathRenderer {
    class Rendered(
        val image: ImageBitmap,
        /** How far the math reaches below its baseline, in px. */
        val descent: Float,
    )

    private class Entry(val rendered: Rendered?)

    private val cache = object : LruCache<String, Entry>(400) {}

    /**
     * Whether jlatexmath typeset each source, whatever size it was drawn at: a formula parses or
     * doesn't, so asking never renders it again.
     */
    private val typesetCache = object : LruCache<String, Boolean>(400) {}

    /** jlatexmath renders run, for tests. */
    @Volatile
    var rendersForTesting = 0
        private set

    /** [fontSizePx] is the typeset size in pixels. */
    fun render(source: String, fontSizePx: Float, display: Boolean): Rendered? {
        val key = "${if (display) "display" else "inline"}|$fontSizePx|$source"
        cache.get(key)?.let { return it.rendered }
        rendersForTesting += 1
        val rendered = runCatching {
            val icon = TeXFormula(source).TeXIconBuilder()
                .setStyle(if (display) TeXConstants.STYLE_DISPLAY else TeXConstants.STYLE_TEXT)
                .setSize(fontSizePx)
                .setFGColor(TeXColor(0xFF000000.toInt()))
                .build()
            val width = icon.iconWidth
            val height = icon.iconHeight
            if (width <= 0 || height <= 0) return@runCatching null
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val graphics = AndroidGraphics2D()
            graphics.setCanvas(Canvas(bitmap))
            icon.paintIcon(null, graphics, 0, 0)
            Rendered(bitmap.asImageBitmap(), icon.iconDepth.toFloat())
        }.getOrNull()
        cache.put(key, Entry(rendered))
        typesetCache.put(typesetKey(source, display), rendered != null)
        return rendered
    }

    /**
     * Whether jlatexmath typesets [source] (at any size): drawn as an image, rather than as its
     * source. Answered from the render that drew it; not drawn yet, it typesets at [fontSizePx],
     * the size the text draws it at.
     */
    fun typesets(source: String, display: Boolean, fontSizePx: Float): Boolean {
        typesetCache.get(typesetKey(source, display))?.let { return it }
        return render(source, fontSizePx, display) != null
    }

    private fun typesetKey(source: String, display: Boolean): String = "${if (display) "display" else "inline"}|$source"
}

/**
 * The size inline math typesets at, and how a run reads. Typesetting into the text is part of the
 * Markdown presentation (`MarkdownPresentation`), as inline content on the baseline at the text's
 * size.
 */
object InlineMath {
    /** Latin Modern sits smaller than SF at the same size; this evens it out (`(pointSize * 1.1).rounded()`). */
    fun fontSize(pointSize: Float): Float = Math.round(pointSize * 1.1f).toFloat()

    /** [fontSize] for a text size, in px for [density]. Rounded at the scaled size. */
    fun fontSizePx(textSize: TextUnit, density: Density): Float {
        val scaledPoints = with(density) { textSize.toPx() } / density.density
        return fontSize(scaledPoints) * density.density
    }
}

/**
 * Display math on the full-width band code blocks use, scrolling sideways when it's wider than the
 * screen. Source jlatexmath can't typeset shows as code, as written.
 *
 * @param selection The block's key in a reply: the equation joins the reply's one selection, a
 *     typeset one as one whole piece of its source, source shown as code as text.
 */
@Composable
fun MathDisplayView(math: OpenCodeMarkdownBlock.MathBlock, selection: TranscriptSelectionLeafKey? = null, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val colors = OpenCodeTheme.colors
    val fontSizePx = InlineMath.fontSizePx(MarkdownStyle.bodyTextSize, density) * 1.15f
    val rendered = remember(math.source, fontSizePx) { MathRenderer.render(math.source, fontSizePx, display = true) }
    // In a reply a hold selects the equation; a hold on one already selected lifts it to drag as
    // its TeX.
    val equationDescription = stringResource(R.string.transcript_equation_description)
    var drag: Modifier = Modifier
    if (selection != null) {
        drag = Modifier.transcriptDragSource(
            TranscriptDragPayload.Code(math.raw, "tex"),
            cornerRadius = 10.dp,
            surface = TranscriptDragSurfaceStyle.Band,
        )
    }
    MarkdownBand(
        modifier.clearAndSetSemantics {
            contentDescription = equationDescription
            stateDescription = math.source
        },
        bandModifier = drag,
    ) {
        val replySelection = if (selection != null) LocalTranscriptReplySelection.current else null
        if (rendered != null) {
            var image: Modifier = Modifier
            if (replySelection != null && selection != null) {
                image = Modifier.transcriptSelectionAnchor(selection, replySelection, layout = null)
            }
            Image(
                bitmap = rendered.image,
                contentDescription = null,
                colorFilter = ColorFilter.tint(colors.text),
                modifier = Modifier.padding(vertical = 4.dp).then(image),
            )
        } else if (replySelection != null && selection != null) {
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
            Text(
                math.raw,
                style = MarkdownStyle.codeTextStyle(codeLeading = 0f),
                color = colors.text,
                softWrap = false,
                onTextLayout = { layout = it },
                modifier = Modifier.transcriptSelectionAnchor(selection, replySelection) { layout },
            )
        } else {
            SelectionContainer {
                Text(math.raw, style = MarkdownStyle.codeTextStyle(codeLeading = 0f), color = colors.text, softWrap = false)
            }
        }
    }
}
