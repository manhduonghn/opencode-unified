package ai.opencode.feature.transcript.links

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.strings.localized
import ai.opencode.core.strings.localizedPlural
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.media.GitHubLink
import ai.opencode.feature.media.GitHubPreview
import ai.opencode.feature.media.LinkPreview
import ai.opencode.feature.media.LinkPreviewBitmap
import ai.opencode.feature.media.LocalLinkPreviewRevision
import ai.opencode.feature.media.LocalLinkPreviewStore
import ai.opencode.feature.media.RichLinkSource
import ai.opencode.feature.media.SiteIcon
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.media.withinNodeLimit
import ai.opencode.feature.transcript.LocalTranscriptRowContext
import ai.opencode.feature.transcript.R
import ai.opencode.feature.transcript.TranscriptAction
import ai.opencode.feature.transcript.TranscriptDisclosureState
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.feature.transcript.markdown.ui.DisclosedContent
import ai.opencode.feature.transcript.markdown.ui.HairlineRule
import ai.opencode.feature.transcript.markdown.ui.MarkdownLinks
import ai.opencode.feature.transcript.markdown.ui.MarkdownStyle
import ai.opencode.feature.transcript.rows.DisclosureMotion
import ai.opencode.feature.transcript.rows.ReplyCopyGlyph
import ai.opencode.feature.transcript.rows.isAccessibilityTextSize
import ai.opencode.feature.transcript.rows.rowHitTarget
import ai.opencode.feature.transcript.user.TranscriptDragPayload
import ai.opencode.feature.transcript.user.transcriptDragSource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import ai.opencode.core.ui.OpenCodeFonts
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Every link under a finished reply, in reading order, as one compact line each (one line for a
 * lone link) (iOS `LinkPreviewReplyEnd`). Past [LinkPreviewEntries.shown] the rest wait behind
 * “N more ⌄” at the right end of the Copy row. No nested scroller:
 * the transcript is the only scrolling surface.
 *
 * [texts] are the reply's texts (`TranscriptEntry.ReplyEnd.texts`), parsed once here; [key] is
 * the row's list identity, [entryID] the entry's; [actions] is the Copy row's content
 * (`ReplyActions`).
 */
@Composable
fun LinkPreviewReplyEnd(
    texts: List<String>,
    key: String,
    entryID: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    val store = LocalLinkPreviewStore.current
    if (store == null) {
        Box(modifier) { actions() }
        return
    }
    val blocks = remember(texts) { texts.flatMap { OpenCodeMarkdownBlock.blocks(it) }.linkTextBlocks() }
    // The row's revision (set by the list when its links' metadata or icons arrive) redraws them.
    @Suppress("UNUSED_VARIABLE") val revision = LocalLinkPreviewRevision.current
    val targets = store.targets(blocks, entryID)
    if (targets.isEmpty()) {
        Box(modifier) { actions() }
        return
    }
    val previews = targets.map(store::preview)
    val disclosure = LocalTranscriptRowContext.current.disclosure
    LaunchedEffect(store, key, targets) {
        for (target in targets) store.appeared(target, key)
    }
    Column(
        modifier
            // Links stand apart from prose like other blocks (20), and the reply-end row already
            // sits 16 below it.
            .padding(top = MarkdownStyle.blockGap - TranscriptMetrics.flowHalf * 2),
        verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered),
    ) {
        LinkPreviewEntries(previews, isOpen = disclosure.isOpen(TranscriptDisclosureState.Kind.LinkLists, key), presentation = store.presentation(key) { LinkPreviewRowPresentation() })
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions()
            Spacer(Modifier.weight(1f))
            if (previews.size > LinkPreviewEntries.shown) {
                LinkPreviewMoreButton(count = previews.size, key = key, entryID = entryID)
            }
        }
    }
}

/**
 * The links as compact lines between hairlines inset to the text column (iOS
 * `LinkPreviewEntries`): the first [shown], then the rest when the Copy row's chevron opens them,
 * on the transcript's disclosure motion.
 */
object LinkPreviewEntries {
    const val shown = 3
}

@Composable
fun LinkPreviewEntries(previews: List<LinkPreview>, isOpen: Boolean, presentation: LinkPreviewRowPresentation? = null) {
    val scaledIcon = LinkPreviewEntry.iconSide * LocalDensity.current.fontScale
    val inset = min(scaledIcon, LinkPreviewEntry.maximumIconSide.value).dp + LinkPreviewEntry.iconSpacing

    /** A hairline from the text column to the trailing edge. */
    @Composable
    fun separator() = HairlineRule(Modifier.padding(start = inset))

    @Composable
    fun entry(preview: LinkPreview) {
        if (presentation != null) {
            ArrivingLinkPreview(preview, presentation = presentation)
        } else {
            LinkPreviewButton(preview) { LinkPreviewEntry(preview) }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        previews.take(LinkPreviewEntries.shown).forEachIndexed { index, preview ->
            key(preview.id) {
                if (index > 0) separator()
                entry(preview)
            }
        }
        DisclosedContent(isOpen && previews.size > LinkPreviewEntries.shown) {
            Column(Modifier.fillMaxWidth()) {
                previews.drop(LinkPreviewEntries.shown).forEach { preview ->
                    key(preview.id) {
                        separator()
                        entry(preview)
                    }
                }
            }
        }
    }
}

/**
 * “4 more ⌄” at the right end of the Copy row; open, the chevron alone, turned up (iOS
 * `LinkPreviewMoreButton`). Its label and value say what it does and how many show.
 */
@Composable
fun LinkPreviewMoreButton(count: Int, key: String, entryID: String) {
    val disclosure = LocalTranscriptRowContext.current.disclosure
    val reducedMotion = rememberReduceMotion()
    val open = disclosure.isOpen(TranscriptDisclosureState.Kind.LinkLists, key)
    val toggle = { disclosure.toggle(TranscriptDisclosureState.Kind.LinkLists, key, entryID, crossFades = true) }
    val fontScale = LocalDensity.current.fontScale
    val rotation by animateFloatAsState(
        if (!reducedMotion && open) 180f else 0f,
        if (reducedMotion) snap() else DisclosureMotion.animation(open),
        label = "LinkPreviewMoreChevron",
    )
    val label = if (open) stringResource(R.string.transcript_link_preview_show_fewer) else stringResource(R.string.transcript_link_preview_show_all)
    val value = if (open) pluralStringResource(R.plurals.transcript_link_preview_shown, count, count, count) else pluralStringResource(R.plurals.transcript_link_preview_shown, count, LinkPreviewEntries.shown, count)
    val color = OpenCodeTheme.colors.secondaryText
    Row(
        Modifier
            .rowHitTarget(
                top = TranscriptMetrics.flowHalf,
                bottom = ReplyCopyGlyph.targetBottom,
                target = Modifier
                    .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = toggle)
                    .clearAndSetSemantics {
                        contentDescription = label
                        stateDescription = value
                        role = Role.Button
                        testTag = "link-preview-more"
                        onClick { toggle(); true }
                    },
            )
            .padding(start = 12.dp)
            .widthIn(min = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // As tall as the Copy glyph beside it, so both targets match.
        Spacer(Modifier.width(0.dp).height((17f * min(fontScale, 1.35f)).dp))
        AnimatedVisibility(!open, enter = fadeIn(), exit = fadeOut()) {
            Text(
                stringResource(R.string.transcript_link_preview_more, count - LinkPreviewEntries.shown),
                style = OpenCodeTextStyles.footnote.copy(fontFeatureSettings = "tnum"),
                color = color,
            )
        }
        OpenCodeIcon(
            if (reducedMotion && open) "chevron.up" else "chevron.down",
            contentDescription = null,
            modifier = Modifier.rotate(rotation),
            size = (13f * fontScale).dp,
            tint = color,
        )
    }
}

/**
 * One link; a tap opens it (in the in-app browser when that's on), and a hold previews its full
 * card over the link menu (iOS `LinkPreviewButton` + `LinkPreviewPressStyle`: 0.6 opacity
 * pressed). The menu's actions go to the session as `TranscriptAction.LinkMenu`.
 */
@Composable
fun LinkPreviewButton(preview: LinkPreview, label: @Composable () -> Unit) {
    val send = LocalTranscriptRowContext.current.send
    val haptics = remember { dependency(HapticsClient) }
    var menu by remember { mutableStateOf(false) }
    val url = preview.url
    val actions = remember(url) { MarkdownLinks.menuActions(url) }
    val open = { send(TranscriptAction.LinkTapped(url)) }
    Box(
        Modifier
            .transcriptDragSource(TranscriptDragPayload.Url(url), cornerRadius = 8.dp, onLift = {
                // The hold answers the finger, as the inline links' menu does (iOS: the context menu's own).
                haptics.play(Haptic.tap)
                menu = true
            })
            // Android divergence: a ripple answers the press (iOS dims the card to 60 %).
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = open)
            .clearAndSetSemantics {
                contentDescription = preview.accessibilityLabel
                testTag = "link-preview-card"
                onClick { open(); true }
                customActions = actions.map { action ->
                    CustomAccessibilityAction(action.title.replace("…", "")) { send(TranscriptAction.LinkMenu(action, url)); true }
                }.withinNodeLimit()
            },
    ) {
        label()
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, properties = ai.opencode.feature.media.liftMenuProperties()) {
            Box(Modifier.width(340.dp).padding(horizontal = 8.dp, vertical = 4.dp)) { LinkPreviewCard(preview) }
            for (action in actions) {
                DropdownMenuItem(
                    text = { Text(action.title) },
                    leadingIcon = { OpenCodeIcon(action.systemImage, contentDescription = null) },
                    onClick = {
                        menu = false
                        send(TranscriptAction.LinkMenu(action, url))
                    },
                )
            }
        }
    }
}

/** The colours a summary paints with (iOS semantic and system colours). */
@Immutable
data class LinkPreviewPalette(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val green: Color,
    val purple: Color,
    val red: Color,
) {
    companion object {
        @Composable
        fun current(): LinkPreviewPalette {
            val colors = OpenCodeTheme.colors
            val dark = colors.isDark
            return LinkPreviewPalette(
                primary = colors.text,
                secondary = colors.secondaryText,
                tertiary = colors.tertiaryText,
                // UIKit `systemGreen` / `systemPurple`; `destructive` is `systemRed`.
                green = if (dark) Color(0.1882f, 0.8196f, 0.3451f) else Color(0.2039f, 0.7804f, 0.349f),
                purple = if (dark) Color(0.749f, 0.3529f, 0.949f) else Color(0.6863f, 0.3216f, 0.8706f),
                red = colors.destructive,
            )
        }
    }
}

/** UIKit `secondarySystemBackground`, the hold card's surface. */
@Composable
private fun secondarySystemBackground(): Color =
    if (OpenCodeTheme.colors.isDark) Color(0.1098f, 0.1098f, 0.1176f) else Color(0.949f, 0.949f, 0.9686f)

/** UIKit `tertiarySystemFill`: rgba(118,118,128) at 0.12 light / 0.24 dark. */
@Composable
private fun tertiarySystemFill(): Color = Color(0.4627f, 0.4627f, 0.502f, if (OpenCodeTheme.colors.isDark) 0.24f else 0.12f)

/**
 * What a link shows, whatever it's drawn as: a site line, a title, one line of facts, and a
 * picture when the page has one (iOS `LinkPreviewSummary`). Before metadata arrives it is what
 * the URL already says, in tertiary grey; after a failure, the domain and path.
 */
class LinkPreviewSummary(preview: LinkPreview, palette: LinkPreviewPalette) {
    /** The bold name leading the site line (a post's author). */
    var name: String? = null
    var site: String = ""
    /** A reference after the site (`#1251`, a short SHA), quieter. */
    var reference: String? = null
    var referenceIsCode = false
    var title: String? = null
    /** The title is the URL standing in for metadata on its way. */
    var isPending = false
    /** The title is the path of a link without metadata. */
    var isPlain = false
    /** One line of facts under the title. */
    var facts: AnnotatedString? = null
    /** A post's text, in place of a title. */
    var text: String? = null
    var image: LinkPreviewBitmap? = null
    var isVideo = false

    init {
        when (val display = preview.display) {
            LinkPreview.Display.Pending -> {
                // What the URL says the link is; its title is on its way.
                isPending = true
                when (val source = preview.richSource) {
                    is RichLinkSource.Github -> {
                        val link = source.link
                        site = if (link.kind == GitHubLink.Kind.Repository) "github.com" else link.repository
                        reference = link.reference?.let { if (link.kind == GitHubLink.Kind.Commit) it.take(7) else "#$it" }
                        referenceIsCode = link.kind == GitHubLink.Kind.Commit
                        title = if (link.kind == GitHubLink.Kind.Repository) link.repository else LinkPreview.kindName(link.kind)
                    }
                    is RichLinkSource.Youtube -> {
                        site = "YouTube"
                        title = localized(R.string.transcript_link_preview_video)
                        isVideo = true
                    }
                    null -> {
                        site = preview.handle?.let { "@$it" } ?: preview.domain
                        title = localized(R.string.transcript_link_preview_post)
                    }
                }
            }
            is LinkPreview.Display.Post -> {
                name = display.author
                site = display.handle?.let { "@$it" } ?: preview.domain
                text = display.text ?: (preview.domain + preview.path)
            }
            is LinkPreview.Display.Github -> {
                title = display.title
                val details = display.details
                when (details.kind) {
                    GitHubLink.Kind.Repository -> {
                        site = "github.com"
                        facts = (display.text ?: LinkPreview.repositoryFacts(details))?.let(::AnnotatedString)
                    }
                    GitHubLink.Kind.Issue, GitHubLink.Kind.PullRequest, GitHubLink.Kind.Commit -> {
                        site = details.repository
                        reference = details.reference?.let { if (details.kind == GitHubLink.Kind.Commit) it.take(7) else "#$it" }
                        referenceIsCode = details.kind == GitHubLink.Kind.Commit
                        facts = gitHubFacts(details, palette)
                    }
                }
            }
            is LinkPreview.Display.Video -> {
                title = display.title
                site = display.site ?: preview.domain
                facts = display.author?.let(::AnnotatedString)
                image = preview.image
                isVideo = true
            }
            is LinkPreview.Display.Page -> {
                title = display.title
                site = display.site ?: preview.domain
                facts = display.text?.let(::AnnotatedString)
                image = preview.image
            }
            LinkPreview.Display.Plain -> {
                isPlain = true
                if (preview.isXPost) {
                    site = preview.handle?.let { "@$it" } ?: preview.domain
                    title = localized(R.string.transcript_link_preview_post_unavailable)
                } else {
                    val readable = LinkPreview.readableTitle(preview.url)
                    if (readable != null) {
                        site = preview.domain
                        title = readable
                    } else {
                        // Nothing readable in the path: the domain alone.
                        site = ""
                        title = preview.domain
                    }
                }
            }
        }
    }

    companion object {
        /** State in its colour, then the diff or the discussion. Quiet otherwise. */
        private fun gitHubFacts(details: GitHubPreview, palette: LinkPreviewPalette): AnnotatedString? {
            val parts = ArrayList<AnnotatedString>()
            details.state?.let { state ->
                parts += buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = stateColor(state, palette))) { append(state.title) }
                }
            }
            when (details.kind) {
                GitHubLink.Kind.PullRequest, GitHubLink.Kind.Commit -> {
                    if (details.kind == GitHubLink.Kind.Commit) details.author?.let { parts += AnnotatedString(it) }
                    val additions = details.additions
                    val deletions = details.deletions
                    if (additions != null && deletions != null) {
                        parts += AnnotatedString("+$additions −$deletions", SpanStyle(fontFeatureSettings = "tnum"))
                    } else if (details.kind == GitHubLink.Kind.PullRequest) {
                        details.author?.let { parts += AnnotatedString(it) }
                    }
                }
                GitHubLink.Kind.Issue -> {
                    val comments = details.comments
                    if (comments != null && comments > 0) {
                        parts += AnnotatedString(localizedPlural(R.plurals.transcript_link_preview_comments, comments, LinkPreview.formatted(comments)))
                    }
                    val labels = details.labels
                    if (!labels.isNullOrEmpty()) parts += AnnotatedString(labels.take(2).joinToString(", "))
                }
                GitHubLink.Kind.Repository -> Unit
            }
            return joined(parts, palette)
        }

        private fun joined(parts: List<AnnotatedString>, palette: LinkPreviewPalette): AnnotatedString? {
            if (parts.isEmpty()) return null
            return buildAnnotatedString {
                parts.forEachIndexed { index, part ->
                    if (index > 0) withStyle(SpanStyle(color = palette.tertiary)) { append(" · ") }
                    append(part)
                }
            }
        }

        fun stateColor(state: GitHubPreview.State, palette: LinkPreviewPalette): Color = when (state) {
            GitHubPreview.State.Open -> palette.green
            GitHubPreview.State.Merged, GitHubPreview.State.Completed -> palette.purple
            GitHubPreview.State.Closed -> palette.red
            GitHubPreview.State.Draft, GitHubPreview.State.NotPlanned -> palette.secondary
        }
    }
}

/**
 * The site line: its icon, the author or site, and a quieter reference (iOS
 * `LinkPreviewSiteLine`). Under the hold preview's picture: the site's icon and who made it.
 */
@Composable
private fun LinkPreviewSiteLine(preview: LinkPreview, site: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        SiteIconSlot(preview.icon, side = 14.dp)
        Text(
            site,
            style = OpenCodeTextStyles.footnote,
            color = OpenCodeTheme.colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
}

/**
 * A link as one compact line, a size below the reply's prose (iOS `LinkPreviewEntry`): its
 * favicon (GitHub's state glyph in its colour, X's mark, or the site's icon) in a fixed leading
 * column, the title, and one quiet fact at the trailing end (the state in its colour, a diff,
 * the site). At least 44 pt (48 dp) tall.
 */
object LinkPreviewEntry {
    val iconSide: Float = 20f
    val maximumIconSide: Dp = 28.dp
    val iconSpacing: Dp = 10.dp

    internal fun title(preview: LinkPreview, summary: LinkPreviewSummary, palette: LinkPreviewPalette): AnnotatedString {
        if (summary.isPending) {
            // What the URL says it is: `acme/widget #1240`, `@handle`, YouTube.
            val source = preview.richSource
            if (source is RichLinkSource.Github && source.link.kind == GitHubLink.Kind.Repository) {
                return AnnotatedString(source.link.repository, SpanStyle(color = palette.tertiary))
            }
            val name = listOfNotNull(summary.site, summary.reference).joinToString(" ")
            return AnnotatedString(name.ifEmpty { summary.title ?: "" }, SpanStyle(color = palette.tertiary))
        }
        summary.text?.let { text ->
            return buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(summary.name ?: summary.site) }
                append(" ")
                withStyle(SpanStyle(color = palette.secondary)) { append(text) }
            }
        }
        val title = summary.title ?: summary.site
        return if (summary.isPlain) AnnotatedString(title, SpanStyle(color = palette.secondary))
        else AnnotatedString(title, SpanStyle(fontWeight = FontWeight.Medium))
    }

    /** One quiet fact: GitHub's state in its colour, a commit's diff, a repository's stars, or where the link lives. */
    internal fun trailing(preview: LinkPreview, summary: LinkPreviewSummary, palette: LinkPreviewPalette): AnnotatedString? =
        when (val display = preview.display) {
            LinkPreview.Display.Pending -> {
                val source = preview.richSource
                if (source is RichLinkSource.Github) AnnotatedString(LinkPreview.kindName(source.link.kind))
                else summary.title?.let(::AnnotatedString)
            }
            is LinkPreview.Display.Github -> {
                val details = display.details
                val state = details.state
                if (state != null) {
                    AnnotatedString(state.title, SpanStyle(fontWeight = FontWeight.SemiBold, color = LinkPreviewSummary.stateColor(state, palette)))
                } else {
                    when (details.kind) {
                        GitHubLink.Kind.Commit -> {
                            val additions = details.additions
                            val deletions = details.deletions
                            if (additions != null && deletions != null) AnnotatedString("+$additions −$deletions")
                            else details.author?.let(::AnnotatedString)
                        }
                        GitHubLink.Kind.Repository -> LinkPreview.repositoryFacts(details)?.let(::AnnotatedString)
                        else -> null
                    }
                }
            }
            is LinkPreview.Display.Video -> AnnotatedString(display.site ?: preview.domain)
            is LinkPreview.Display.Page -> AnnotatedString(display.site ?: preview.domain)
            is LinkPreview.Display.Post -> null
            LinkPreview.Display.Plain -> if (summary.site.isEmpty()) null else AnnotatedString(summary.site)
        }
}

@Composable
fun LinkPreviewEntry(preview: LinkPreview, change: LinkPreviewRowPresentation.Change? = null, progress: Double = 1.0) {
    val large = isAccessibilityTextSize()
    val icon = min(LinkPreviewEntry.iconSide * LocalDensity.current.fontScale, LinkPreviewEntry.maximumIconSide.value).dp
    val palette = LinkPreviewPalette.current()
    val summary = LinkPreviewSummary(preview, palette)
    fun previousText(text: (LinkPreview, LinkPreviewSummary) -> AnnotatedString?): List<Pair<AnnotatedString?, Double>> =
        change?.previous?.map { text(it.preview, LinkPreviewSummary(it.preview, palette)) to it.opacity } ?: emptyList()
    val titleOf = { p: LinkPreview, s: LinkPreviewSummary -> LinkPreviewEntry.title(p, s, palette) }
    val trailingOf = { p: LinkPreview, s: LinkPreviewSummary -> LinkPreviewEntry.trailing(p, s, palette) }
    val trailing = trailingOf(preview, summary)
    val showsTrailing = trailing != null ||
        (change?.previous?.any { trailingOf(it.preview, LinkPreviewSummary(it.preview, palette)) != null } == true && progress < 1)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = TranscriptMetrics.minimumHitSide)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(LinkPreviewEntry.iconSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val baseline = if (large) Modifier.alignByBaseline() else Modifier
        // The first line supplies the baseline, so a wrapping title grows below its icon rather
        // than moving that shared glyph down to a new centre.
        Box(baseline.size(icon), contentAlignment = Alignment.Center) {
            Text(" ", Modifier.alpha(0f), style = OpenCodeTextStyles.subheadline, maxLines = 1)
            LinkPreviewFavicon(preview, side = icon, change = change, progress = progress)
        }
        LinkPreviewTextTransition(
            current = titleOf(preview, summary),
            previous = previousText(titleOf),
            progress = progress,
            modifier = baseline.weight(1f),
            style = OpenCodeTextStyles.subheadline.copy(color = palette.primary),
            maxLines = if (large) 3 else 1,
        )
        if (showsTrailing) {
            LinkPreviewTextTransition(
                current = trailing,
                previous = previousText(trailingOf),
                progress = progress,
                modifier = baseline.then(if (large) Modifier.widthIn(max = 120.dp) else Modifier),
                style = OpenCodeTextStyles.footnote.copy(color = palette.secondary, fontFeatureSettings = "tnum"),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                trailing = true,
            )
        }
    }
}

/**
 * The hold preview card's body for a link without a picture (iOS `LinkPreviewDetail`): its
 * favicon, a quiet site line, a title of up to two lines, and one line of facts.
 */
@Composable
private fun LinkPreviewDetail(preview: LinkPreview) {
    val large = isAccessibilityTextSize()
    val icon = min(LinkPreviewEntry.iconSide * LocalDensity.current.fontScale, LinkPreviewEntry.maximumIconSide.value).dp
    val palette = LinkPreviewPalette.current()
    val summary = LinkPreviewSummary(preview, palette)
    Row(
        Modifier.padding(horizontal = TranscriptMetrics.standard, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(TranscriptMetrics.standard),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LinkPreviewFavicon(preview, side = icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            DetailSiteLine(summary, palette)
            val text = summary.text
            val title = summary.title
            if (text != null) {
                Text(text, style = OpenCodeTextStyles.subheadline, color = palette.primary, maxLines = if (large) 4 else 2, overflow = TextOverflow.Ellipsis)
            } else if (title != null) {
                Text(
                    title,
                    style = if (summary.isPending || summary.isPlain) OpenCodeTextStyles.subheadline
                    else OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    color = if (summary.isPending) palette.tertiary else if (summary.isPlain) palette.secondary else palette.primary,
                    maxLines = if (large) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            summary.facts?.let { facts ->
                Text(facts, style = OpenCodeTextStyles.footnote, color = palette.secondary, maxLines = if (large) 2 else 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The author or site, and a quieter reference. */
@Composable
private fun DetailSiteLine(summary: LinkPreviewSummary, palette: LinkPreviewPalette) {
    val style = OpenCodeTextStyles.caption1
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        summary.name?.let { name ->
            Text(name, style = style.copy(fontWeight = FontWeight.SemiBold), color = palette.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (summary.site.isNotEmpty()) {
            Text(summary.site, Modifier.weight(1f, fill = false), style = style, color = palette.secondary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
        summary.reference?.let { reference ->
            // Only a SHA changes face, and it keeps the caption's size.
            Text(
                reference,
                style = if (summary.referenceIsCode) style.copy(fontFamily = OpenCodeFonts.monospace) else style,
                color = palette.tertiary,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * A card row's leading mark (iOS `LinkPreviewFavicon`): GitHub's state glyph in its colour, X's
 * mark, or the site's own icon (the globe until it arrives).
 */
@Composable
private fun LinkPreviewFavicon(preview: LinkPreview, side: Dp, change: LinkPreviewRowPresentation.Change? = null, progress: Double = 1.0) {
    val palette = LinkPreviewPalette.current()
    Box(Modifier.size(side).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        val mark = gitHubMark(preview, palette)
        if (mark != null) {
            OpenCodeIcon(mark.first, contentDescription = null, size = side * 0.85f, tint = markColor(mark.second, change, progress, palette))
        } else if (preview.isXPost) {
            Text(
                "𝕏",
                style = OpenCodeTextStyles.body.copy(fontSize = with(LocalDensity.current) { (side * 0.8f).toSp() }, fontWeight = FontWeight.Bold, lineHeight = 1.em),
                color = palette.primary,
                maxLines = 1,
            )
        } else {
            val shared = change?.previous?.filter { it.preview.icon == preview.icon }?.sumOf { it.opacity } ?: 0.0
            val iconOpacity = progress + shared * (1 - progress)
            SiteIconSlot(preview.icon, side = side, scales = false, modifier = Modifier.graphicsLayer { alpha = iconOpacity.toFloat() })
            if (progress < 1 && change != null) {
                for (layer in change.previous) {
                    if (layer.preview.icon != preview.icon) {
                        SiteIconSlot(
                            layer.preview.icon, side = side, scales = false,
                            modifier = Modifier.graphicsLayer { alpha = (layer.opacity * (1 - progress)).toFloat() },
                        )
                    }
                }
            }
        }
    }
}

private fun markColor(target: Color, change: LinkPreviewRowPresentation.Change?, progress: Double, palette: LinkPreviewPalette): Color {
    val previous = change?.previous
    if (previous.isNullOrEmpty() || progress >= 1) return target
    var color = target
    var weight = progress
    for (layer in previous) {
        val amount = layer.opacity * (1 - progress)
        if (amount <= 0) continue
        color = lerp(color, gitHubMark(layer.preview, palette)?.second ?: palette.secondary, (amount / (weight + amount)).toFloat())
        weight += amount
    }
    return color
}

private fun gitHubMark(preview: LinkPreview, palette: LinkPreviewPalette): Pair<String, Color>? {
    (preview.display as? LinkPreview.Display.Github)?.let { display ->
        return symbol(display.details) to (display.details.state?.let { LinkPreviewSummary.stateColor(it, palette) } ?: palette.secondary)
    }
    (preview.richSource as? RichLinkSource.Github)?.let { source ->
        return symbol(GitHubPreview(repository = source.link.repository, kind = source.link.kind)) to palette.secondary
    }
    return null
}

/** iOS `LinkPreviewFavicon.symbol(_:)`. */
internal fun symbol(details: GitHubPreview): String = when (details.kind) {
    GitHubLink.Kind.Repository -> "book.closed"
    GitHubLink.Kind.Commit -> "point.3.connected.trianglepath.dotted"
    GitHubLink.Kind.PullRequest -> when (details.state) {
        GitHubPreview.State.Merged -> "arrow.triangle.merge"
        GitHubPreview.State.Closed -> "xmark.circle"
        else -> "arrow.triangle.pull"
    }
    GitHubLink.Kind.Issue -> when (details.state) {
        GitHubPreview.State.Completed -> "checkmark.circle"
        GitHubPreview.State.NotPlanned -> "slash.circle"
        else -> "smallcircle.filled.circle"
    }
}

/**
 * A link's full card, shown over its hold menu (iOS `LinkPreviewCard`): the picture above (a
 * video or a page with an image), the title and site below; or, without a picture, its row with
 * a two-line title.
 */
@Composable
fun LinkPreviewCard(preview: LinkPreview, modifier: Modifier = Modifier) {
    val large = isAccessibilityTextSize()
    val palette = LinkPreviewPalette.current()
    val summary = LinkPreviewSummary(preview, palette)
    val shape = RoundedCornerShape(TranscriptMetrics.cardCornerRadius)
    Box(modifier.fillMaxWidth().clip(shape).background(secondarySystemBackground(), shape)) {
        val image = summary.image
        if (image != null) {
            Column {
                PictureBand(aspect = if (summary.isVideo) 16f / 9f else 1.91f, maxHeight = 240.dp) {
                    Box(Modifier.fillMaxSize().background(tertiarySystemFill()).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                        val bitmap = remember(image) { image.image.asImageBitmap() }
                        Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        if (summary.isVideo) {
                            Box(Modifier.background(Color.Black.copy(alpha = 0.4f), CircleShape).padding(14.dp)) {
                                OpenCodeIcon("play.fill", contentDescription = null, size = 20.dp, tint = Color.White)
                            }
                        }
                    }
                }
                Column(Modifier.padding(PaddingValues(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 12.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    summary.title?.let { title ->
                        Text(
                            title,
                            style = OpenCodeTextStyles.callout.copy(fontWeight = FontWeight.SemiBold),
                            color = palette.primary,
                            maxLines = if (large) 4 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    LinkPreviewSiteLine(preview, siteLine(preview, summary))
                }
            }
        } else {
            // Without a picture, the entry's row on the card's surface, as Messages draws a link
            // with only an icon.
            LinkPreviewDetail(preview)
        }
    }
}

/** Under a picture the site line names who made it: the channel, else the site. */
private fun siteLine(preview: LinkPreview, summary: LinkPreviewSummary): String {
    val display = preview.display
    if (!summary.isVideo || display !is LinkPreview.Display.Video) return summary.site
    return display.author ?: summary.site
}

/**
 * A picture across the card's whole width at its aspect ratio, at most [maxHeight] tall
 * (cropped, never narrowed, on a wide column) (iOS `PictureBand`).
 */
@Composable
private fun PictureBand(aspect: Float, maxHeight: Dp, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 320.dp.roundToPx()
        val height = min(width / aspect, maxHeight.toPx()).roundToInt()
        val placeables = measurables.map { it.measure(Constraints.fixed(width, height)) }
        layout(width, height) { placeables.forEach { it.place(0, 0) } }
    }
}

/**
 * A site's icon where the globe sits, in a slot of the same size whether or not it has arrived,
 * so an arrival moves nothing (iOS `SiteIconSlot`). Rounded like the cards; a dark mark gets a
 * light plate in dark mode, and a light one a dark plate in light mode, so it never vanishes.
 * [side] is at the default text size and scales with it, unless [scales] is false (inside a
 * favicon slot that has already scaled).
 */
@Composable
fun SiteIconSlot(icon: SiteIcon?, side: Dp, modifier: Modifier = Modifier, scales: Boolean = true) {
    val fontScale = LocalDensity.current.fontScale
    val size = if (scales) side * fontScale else side
    val dark = OpenCodeTheme.colors.isDark
    Box(modifier.size(size).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
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
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(size * 0.225f)).background(plate),
                filterQuality = FilterQuality.High,
            )
        } else {
            OpenCodeIcon("globe", contentDescription = null, size = size, tint = OpenCodeTheme.colors.secondaryText)
        }
    }
}
