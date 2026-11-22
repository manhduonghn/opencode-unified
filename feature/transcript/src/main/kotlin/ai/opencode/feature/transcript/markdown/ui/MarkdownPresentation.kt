package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.core.strings.localized
import ai.opencode.core.ui.OpenCodeColors
import ai.opencode.core.ui.OpenCodeFonts
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.media.SiteIcon
import ai.opencode.feature.transcript.R
import ai.opencode.feature.transcript.SessionLinkTarget
import ai.opencode.feature.transcript.markdown.InlineRun
import ai.opencode.feature.transcript.markdown.MathSyntax
import ai.opencode.feature.transcript.markdown.label
import ai.opencode.feature.transcript.markdown.resolvedTitle
import ai.opencode.feature.transcript.markdown.InlineText
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.feature.transcript.markdown.SessionLinkText
import ai.opencode.feature.transcript.markdown.SyntaxTokenKind
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A character edit the presentation made (a session ID shown as its title, a bare URL shown as its
 * clean address, an inline icon slot), in the original text's characters. Streamed arrivals map
 * through these.
 */
@Immutable
data class TextEdit(val start: Int, val removed: Int, val inserted: Int)

/** Where a character offset in the original text lands in the presented text. */
fun List<TextEdit>.mapOffset(original: Int): Int {
    var delta = 0
    for (edit in this) {
        if (original >= edit.start + edit.removed) {
            delta += edit.inserted - edit.removed
        } else if (original > edit.start) {
            return edit.start + delta + minOf(original - edit.start, edit.inserted)
        } else {
            break
        }
    }
    return original + delta
}

/** Drawn characters [start]..[end] that read as [text] when selected (an icon slot, typeset math). */
@Immutable
data class AtomicRun(val start: Int, val end: Int, val text: String)

/** One chip in a reply's text, on its padding and label. */
@Immutable
data class SessionChip(val id: String, val muted: Boolean, val isSubagent: Boolean, val start: Int, val end: Int, val url: String)

/**
 * A Markdown block's text as drawn: styled runs, session IDs as their titles (`SessionLinkText`),
 * then Link Icons, with inline math typeset in place.
 */
@Immutable
class PresentedText(
    val text: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>,
    val edits: List<TextEdit>,
    val chips: List<SessionChip>,
    /** The session IDs shown, once each, in order (reported once per set). */
    val sessionIDs: List<String>,
    /** The text's links that get the hold menu (web and session), once each, in order. */
    val menuLinks: List<String>,
    /** Links whose icon may be fetched: finished, with a registrable domain. */
    val fetchableLinks: List<String>,
    /** Typeset math draws images the per-character fade can't follow; it appears at once. */
    val hasMath: Boolean,
    /** TalkBack's hint: “Links to …”. */
    val linkHint: String?,
    /**
     * Drawn characters that stand for other text in a reply selection: a link's icon slot reads
     * nothing, typeset math reads its source with its delimiters.
     */
    val atomics: List<AtomicRun> = emptyList(),
) {
    fun mapArrivals(arrivals: List<TranscriptTextReveal.Arrival>): List<TranscriptTextReveal.Arrival> =
        if (edits.isEmpty()) arrivals else arrivals.map { it.copy(offset = edits.mapOffset(it.offset)) }

    companion object {
        /** Text with no links, chips or math (a code block's highlighted source). */
        fun plain(text: AnnotatedString) = PresentedText(text, emptyMap(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false, null)
    }
}

/** How a block's text presents. */
@Immutable
data class PresentationOptions(
    /** The text's own colour (prose, a done task's secondary, a quote's secondary). */
    val foreground: Color,
    val textSize: TextUnit,
    /** A done task: its code dims too. */
    val secondary: Boolean = false,
    val headingLevel: Int? = null,
    /** Bold spans lift to full primary over softened prose (not quotes and tables). */
    val tonesBody: Boolean = true,
    val linkStyle: InlineLinkStyle = InlineLinkStyle.Today,
    /** The reply's growing tail: a link still streaming in at the end keeps the globe. */
    val isGrowing: Boolean = false,
)

/** Builds [PresentedText]. */
object MarkdownPresentation {
    private val plainRun = InlineRun("")
    private const val wordJoiner = "\u2060"
    /** No-break padding in the surrounding font: the glyph sits in a figure space. */
    const val chipLeadingPad = SessionLinkText.leadingPad
    const val chipTrailingPad = SessionLinkText.trailingPad
    /** A chip's domain and path, before its ellipsis. */
    const val chipLength = 30

    fun present(
        inline: InlineText,
        options: PresentationOptions,
        style: MarkdownStyle,
        targets: Map<String, SessionLinkTarget>,
        density: Density,
        links: LinkInteractionListener?,
        siteIcon: ((String) -> SiteIcon?)?,
    ): PresentedText {
        // Plain prose has no links, chips or math to present. Keep it span-free rather than
        // building groups and an empty SpanStyle over every run; Text supplies its base style.
        if (inline.runs.all { it.sameAttributes(plainRun) }) {
            return PresentedText.plain(AnnotatedString(inline.text))
        }
        val colors = style.colors
        val groups = group(inline.runs)
        val hasMathRun = inline.runs.any { MathSyntax.Delimiter.from(it.languageIdentifier) != null }
        // In the transcript only (its rows redraw when an icon arrives): [siteIcon] is the list's
        // store.
        val decoratesLinks = options.linkStyle != InlineLinkStyle.Today && siteIcon != null && !hasMathRun
        val inlineContent = mutableMapOf<String, InlineTextContent>()
        val edits = mutableListOf<TextEdit>()
        val chips = mutableListOf<SessionChip>()
        val sessionIDs = mutableListOf<String>()
        val menuLinks = mutableListOf<String>()
        val fetchable = mutableListOf<String>()
        val hosts = mutableListOf<String>()
        var originalOffset = 0
        var hasMath = false
        val atomics = mutableListOf<AtomicRun>()
        val totalLength = inline.text.length

        val text = buildAnnotatedString {
            for ((groupIndex, group) in groups.withIndex()) {
                val url = group.link
                val groupLength = group.runs.sumOf { it.text.length }
                val sessionID = url?.let(MarkdownLinks::sessionID)
                when {
                    url != null && sessionID != null -> {
                        val target = targets[sessionID] ?: SessionLinkTarget.Resolving
                        val chipURL = MarkdownLinks.sessionURL(sessionID, target.resolvedTitle)
                        val start = length
                        // Empty, not null: Material's Text gives a link without styles its default
                        // underline; the chip draws its own fill and press.
                        withLink(clickable(chipURL, TextLinkStyles(), links)) {
                            appendChip(sessionID, target, group.runs.first(), options, style)
                        }
                        val end = length
                        chips += SessionChip(
                            id = sessionID,
                            muted = target !is SessionLinkTarget.Session,
                            isSubagent = (target as? SessionLinkTarget.Session)?.parentID != null,
                            start = start, end = end, url = chipURL,
                        )
                        edits += TextEdit(originalOffset, groupLength, end - start)
                        if (sessionID !in sessionIDs) sessionIDs += sessionID
                        if (chipURL !in menuLinks) menuLinks += chipURL
                    }
                    url != null -> {
                        // Every link the hold menu offers (web, and a shareable pairing link);
                        // session links are chips above.
                        val slotted = MarkdownLinks.offersMenu(url)
                        val linkStyles = TextLinkStyles(SpanStyle(color = colors.link))
                        withLink(clickable(url, linkStyles, links)) {
                            if (decoratesLinks && slotted) {
                                val isOpen = options.isGrowing && originalOffset + groupLength >= totalLength
                                val domain = MarkdownLinks.iconDomain(url)
                                val id = "icon:$groupIndex"
                                atomics += AtomicRun(length, length + wordJoiner.length, "")
                                appendInlineContent(id, wordJoiner)
                                inlineContent[id] = linkIcon(url, domain, isOpen, options.textSize, colors, siteIcon!!)
                                edits += TextEdit(originalOffset, 0, 1)
                                if (!isOpen && domain != null) fetchable += url
                                val host = slotHost(url)
                                if (host !in hosts) hosts += host
                                val written = group.runs.joinToString("") { it.text }
                                val shown = if (written == url && !isOpen) displayed(url, options.linkStyle) else null
                                if (shown != null) {
                                    appendRun(group.runs.first().copy(text = shown), options, style, linked = true)
                                    edits += TextEdit(originalOffset, groupLength, shown.length)
                                } else {
                                    group.runs.forEach { appendRun(it, options, style, linked = true) }
                                }
                            } else {
                                group.runs.forEach { appendRun(it, options, style, linked = true) }
                            }
                        }
                        if (MarkdownLinks.offersMenu(url) && url !in menuLinks) menuLinks += url
                    }
                    else -> for ((runIndex, run) in group.runs.withIndex()) {
                        val delimiter = MathSyntax.Delimiter.from(run.languageIdentifier)
                        if (delimiter == null) {
                            appendRun(run, options, style, linked = false)
                            continue
                        }
                        val sizePx = InlineMath.fontSizePx(options.textSize, density)
                        val rendered = MathRenderer.render(run.text, sizePx, delimiter == MathSyntax.Delimiter.DoubleDollar)
                        if (rendered == null) {
                            // Source jlatexmath can't typeset reads as code, as written.
                            appendRun(run.copy(text = delimiter.open + run.text + delimiter.close), options, style, linked = false)
                        } else {
                            hasMath = true
                            val id = "math:$groupIndex:$runIndex"
                            atomics += AtomicRun(length, length + run.text.length, delimiter.open + run.text + delimiter.close)
                            appendInlineContent(id, run.text)
                            val tint = if (run.secondary) colors.secondaryText else options.foreground
                            inlineContent[id] = mathContent(rendered, tint, density)
                        }
                    }
                }
                originalOffset += groupLength
            }
        }
        return PresentedText(
            text = text,
            inlineContent = inlineContent,
            edits = edits,
            chips = chips,
            sessionIDs = sessionIDs,
            menuLinks = menuLinks,
            fetchableLinks = fetchable,
            hasMath = hasMath,
            linkHint = if (hosts.isEmpty()) null else localized(R.string.transcript_links_to_description, listFormatted(hosts)),
            atomics = atomics,
        )
    }

    /** Highlighted code: the source with its tokens' theme colours. */
    fun code(code: OpenCodeMarkdownBlock.HighlightedCode, colors: OpenCodeColors): AnnotatedString = buildAnnotatedString {
        append(code.source)
        for (token in code.tokens) {
            val start = token.start.coerceIn(0, code.source.length)
            val end = token.end.coerceIn(start, code.source.length)
            if (end > start) addStyle(SpanStyle(color = tokenColor(token.kind, colors)), start, end)
        }
    }

    fun tokenColor(kind: SyntaxTokenKind, colors: OpenCodeColors): Color = when (kind) {
        SyntaxTokenKind.Keyword -> colors.syntaxKeyword
        SyntaxTokenKind.String -> colors.syntaxString
        SyntaxTokenKind.Number -> colors.syntaxNumber
        SyntaxTokenKind.Comment -> colors.syntaxComment
        SyntaxTokenKind.Type -> colors.syntaxType
    }

    /** A bare URL as the style shows it, or null to keep it as written. */
    fun displayed(url: String, style: InlineLinkStyle): String? = when (style) {
        InlineLinkStyle.Today, InlineLinkStyle.Icon -> null
        InlineLinkStyle.Address -> {
            // An insecure link keeps saying so.
            if (!url.lowercase().startsWith("https://")) {
                null
            } else {
                var rest = url.drop("https://".length)
                if (rest.lowercase().startsWith("www.")) rest = rest.drop(4)
                if (rest.endsWith("/") && rest.indexOf('/') == rest.length - 1) rest = rest.dropLast(1)
                rest.ifEmpty { null }
            }
        }
        InlineLinkStyle.Chip -> {
            val parsed = runCatching { android.net.Uri.parse(url) }.getOrNull()
            if (parsed?.host == null) {
                null
            } else {
                var shown = slotHost(url) + (if (parsed.port >= 0) ":${parsed.port}" else "")
                var folded = parsed.query != null || parsed.fragment != null
                for ((index, segment) in parsed.pathSegments.withIndex()) {
                    if (index >= 2 || shown.length + segment.length + 1 > chipLength) {
                        folded = true
                        break
                    }
                    shown += "/$segment"
                }
                if (folded) shown += "…"
                // Word joiners between its characters: the chip never wraps.
                shown.toList().joinToString(wordJoiner)
            }
        }
    }

    /**
     * The URL's host, lowercased, without `www.`; the whole URL when it has none. TalkBack's “Links
     * to …” hint and a chip's domain.
     */
    fun slotHost(url: String): String {
        val host = runCatching { android.net.Uri.parse(url).host }.getOrNull()?.takeIf { it.isNotEmpty() }?.lowercase() ?: url
        return if (host.startsWith("www.")) host.drop(4) else host
    }

    private class Group(val link: String?, val runs: List<InlineRun>)

    private fun group(runs: List<InlineRun>): List<Group> {
        val groups = mutableListOf<Group>()
        var current = mutableListOf<InlineRun>()
        var link: String? = null
        for (run in runs) {
            if (run.text.isEmpty()) continue
            if (current.isNotEmpty() && run.link != link) {
                groups += Group(link, current)
                current = mutableListOf()
            }
            link = run.link
            current += run
        }
        if (current.isNotEmpty()) groups += Group(link, current)
        return groups
    }

    private fun clickable(url: String, styles: TextLinkStyles?, listener: LinkInteractionListener?) =
        LinkAnnotation.Clickable(tag = url, styles = styles, linkInteractionListener = listener)

    /**
     * Inline code at full strength on a faint fill, instead of dimmed; secondary text (a done
     * task) dims its code too; bold lifts to full primary over softened prose; links take the
     * link tint from their annotation.
     */
    private fun AnnotatedString.Builder.appendRun(run: InlineRun, options: PresentationOptions, style: MarkdownStyle, linked: Boolean) {
        val colors = style.colors
        val color = when {
            run.code && !linked -> if (options.secondary) colors.secondaryText else colors.strongText
            linked -> Color.Unspecified
            run.secondary -> colors.secondaryText
            run.strong && options.tonesBody && !options.secondary && options.headingLevel == null && style.liftsStrong -> colors.strongText
            else -> Color.Unspecified
        }
        val heading = options.headingLevel
        val span = SpanStyle(
            color = color,
            fontWeight = when {
                run.code && heading != null -> FontWeight.SemiBold
                run.strong -> FontWeight.Bold
                else -> null
            },
            fontStyle = if (run.emphasized) FontStyle.Italic else null,
            fontFamily = if (run.code) OpenCodeFonts.monospace else null,
            // In a heading it takes the heading's size.
            fontSize = if (run.code && heading == null) OpenCodeTextStyles.subheadline.fontSize else TextUnit.Unspecified,
            background = if (run.code) colors.inlineCodeFill else Color.Unspecified,
            textDecoration = if (run.strikethrough) TextDecoration.LineThrough else null,
        )
        withStyle(span) { append(run.text) }
    }

    /**
     * One chip: no-break padding in the surrounding font (room for the glyph first), then the
     * label, all one `SessionChip`. The short ID reads as code, muted: nothing named it yet.
     */
    private fun AnnotatedString.Builder.appendChip(
        id: String,
        target: SessionLinkTarget,
        first: InlineRun,
        options: PresentationOptions,
        style: MarkdownStyle,
    ) {
        val colors = style.colors
        val plain = SpanStyle(
            color = colors.text,
            fontWeight = if (first.strong) FontWeight.Bold else null,
            fontStyle = if (first.emphasized) FontStyle.Italic else null,
        )
        withStyle(plain) {
            append(chipLeadingPad)
            val label = target.label(id).replace(" ", "\u00A0")
            if (target.resolvedTitle == null) {
                withStyle(SpanStyle(fontFamily = OpenCodeFonts.monospace, fontSize = 15.sp, color = colors.secondaryText)) { append(label) }
            } else {
                append(label)
            }
            append(chipTrailingPad)
        }
    }

    /**
     * The icon's slot in a line of text: a little taller than the capitals (`capHeight × 1.25`,
     * Roboto's cap height ≈ 0.711 em), centred on the line, and a small gap before the link's text.
     * The site's icon is rounded like the cards (`side × 0.225`); a mark that would vanish gets a
     * plate (a dark one a light plate in dark mode, a light one a dark plate in light mode). Until
     * it arrives, or for a link still streaming in, the globe; a link the icon service is never
     * asked about, a neutral link glyph.
     */
    private fun linkIcon(
        url: String,
        domain: String?,
        isOpen: Boolean,
        textSize: TextUnit,
        colors: OpenCodeColors,
        siteIcon: (String) -> SiteIcon?,
    ): InlineTextContent {
        val size = textSize.value
        val side = (size * 0.711f * 1.25f).roundToInt().toFloat()
        val gap = max(3f, (side * 0.27f).roundToInt().toFloat())
        val placeholder = Placeholder((side + gap).sp, side.sp, PlaceholderVerticalAlign.TextCenter)
        val dark = colors.isDark
        return InlineTextContent(placeholder) {
            val icon = if (!isOpen && domain != null) siteIcon(url) else null
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxHeight().aspectRatio(1f), contentAlignment = Alignment.Center) {
                    if (icon != null) {
                        val plate = when {
                            icon.tone == SiteIcon.Tone.Dark && dark -> Color(0.92f, 0.92f, 0.92f)
                            icon.tone == SiteIcon.Tone.Light && !dark -> Color(0.2f, 0.2f, 0.2f)
                            else -> Color.Transparent
                        }
                        val bitmap = remember(icon) { icon.image.asImageBitmap() }
                        Image(
                            bitmap,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(SiteIconShape).background(plate),
                            filterQuality = FilterQuality.High,
                        )
                    } else {
                        // Decorative: TalkBack reads the link's text, not its icon.
                        OpenCodeIcon(
                            if (domain == null) SFSymbol.Link else SFSymbol.Globe,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(0.82f),
                            tint = colors.secondaryText,
                        )
                    }
                }
            }
        }
    }

    private val SiteIconShape = GenericShape { size, _ ->
        addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(size.minDimension * 0.225f)))
    }

    /** Inline math on the baseline: the placeholder holds the ascent; the descent hangs below it. */
    private fun mathContent(rendered: MathRenderer.Rendered, tint: Color, density: Density): InlineTextContent {
        val width = rendered.image.width.toFloat()
        val ascent = max(1f, rendered.image.height - rendered.descent)
        val placeholder = with(density) { Placeholder(width.toSp(), ascent.toSp(), PlaceholderVerticalAlign.AboveBaseline) }
        return InlineTextContent(placeholder) {
            Canvas(Modifier.fillMaxSize()) {
                drawImage(rendered.image, colorFilter = ColorFilter.tint(tint))
            }
        }
    }

    private fun listFormatted(items: List<String>): String = when (items.size) {
        1 -> items[0]
        2 -> localized(R.string.transcript_list_two, items[0], items[1])
        else -> {
            var leading = items[0]
            for (index in 1 until items.size - 1) {
                leading = localized(R.string.transcript_spoken_pair, leading, items[index])
            }
            localized(R.string.transcript_list_many, leading, items.last())
        }
    }
}
