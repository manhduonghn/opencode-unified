package ai.opencode.feature.media.lab

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.store.Store
import ai.opencode.core.store.collectState
import ai.opencode.core.ui.OpenCodeAppTheme
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.media.AgentFileDestination
import ai.opencode.feature.media.AgentImageGallery
import ai.opencode.feature.media.AgentImageViewport
import ai.opencode.feature.media.AgentImageVisibility
import ai.opencode.feature.media.BrowserFeature
import ai.opencode.feature.media.BrowserPage
import ai.opencode.feature.media.ImageLinkPolicy
import ai.opencode.feature.media.ImageLinkPreviewClient
import ai.opencode.feature.media.LinkMenu
import ai.opencode.feature.media.LinkMenuAction
import ai.opencode.feature.media.LinkMenuHandler
import ai.opencode.feature.media.LinkMenuPopup
import ai.opencode.feature.media.LinkPreview
import ai.opencode.feature.media.LinkPreviewStore
import ai.opencode.feature.media.LinkTextBlock
import ai.opencode.feature.media.LinkTextRun
import ai.opencode.feature.media.LocalAgentImageBlock
import ai.opencode.feature.media.LocalAgentImageOccurrence
import ai.opencode.feature.media.LocalAgentImageRow
import ai.opencode.feature.media.LocalAgentImageSiblings
import ai.opencode.feature.media.LocalAgentImageViewport
import ai.opencode.feature.media.LocalLinkMenu
import ai.opencode.feature.media.LocalMediaPresenter
import ai.opencode.feature.media.MediaBackHandler
import ai.opencode.feature.media.MediaThumbnail
import ai.opencode.feature.media.MediaView
import ai.opencode.feature.media.MermaidDiagramCard
import ai.opencode.feature.media.TranscriptImagePreview
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.media.browserBackAction
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import ai.opencode.core.ui.OpenCodeFonts
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * DEBUG-ONLY harness for the media area until the transcript port hosts it: loads a session-lab
 * session and renders each reply plainly with the media pieces it names (tool images, named files
 * through the gallery and file viewer, Mermaid cards, link rows with the hold menu, image links)
 * plus the in-app browser. Not product UI.
 *
 * `adb shell am start -n ai.opencode.mobilecode.debug/ai.opencode.feature.media.lab.MediaLabActivity
 *   --es server http://10.0.2.2:4118 [--es session <id>] [--es section images|files|mermaid|links|tools]`
 */
class MediaLabActivity : ComponentActivity() {
    private val browser = Store(BrowserFeature.State(), BrowserFeature())

    override fun onCreate(savedInstanceState: Bundle?) {
        DependencyValues.installRoot {
            this[ExperimentalFeatures] = ExperimentalFeatures.enabling(setOf(ExperimentalFeature.inAppBrowser))
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val server = OpenCodeServer(intent.getStringExtra("server") ?: "http://10.0.2.2:4118", "", "")
        val sessionID = intent.getStringExtra("session")
        val section = intent.getStringExtra("section")
        setContent {
            OpenCodeAppTheme {
                Surface(Modifier.fillMaxSize(), color = OpenCodeTheme.colors.canvas) {
                    MediaView {
                        LabScreen(server, sessionID, section, browser)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        browser.close()
        super.onDestroy()
    }
}

private class LabViewport : AgentImageViewport {
    var rect: Rect by mutableStateOf(Rect.Zero)
    override val imageViewportVisibleRect: Rect get() = rect
    override val imageViewportIsSettling: Boolean get() = false
    override val imageViewportIsVisible: Boolean get() = true
    override fun imageContentRevised(row: Any?) {}
}

@Composable
private fun LabScreen(server: OpenCodeServer, initialSession: String?, section: String?, browser: Store<BrowserFeature.State, BrowserFeature.Action>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var sessions by remember { mutableStateOf<List<OpenCodeSession>>(emptyList()) }
    var selected by remember { mutableStateOf(initialSession) }
    var items by remember { mutableStateOf<List<OpenCodeTranscriptItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloads by remember { mutableIntStateOf(0) }
    val browserState by browser.collectState()

    LaunchedEffect(reloads) {
        runCatching { dependency(OpenCodeClient).homeSnapshot(server) }
            .onSuccess { snapshot ->
                sessions = snapshot.sessions
                if (selected == null) selected = snapshot.sessions.firstOrNull { it.parentID == null }?.id ?: snapshot.sessions.firstOrNull()?.id
            }
            .onFailure { error = "Home: ${it.message}" }
    }
    LaunchedEffect(selected, reloads) {
        val id = selected ?: return@LaunchedEffect
        runCatching { dependency(OpenCodeClient).transcript(server, id, 200) }
            .onSuccess { items = it; error = null }
            .onFailure { error = "Transcript: ${it.message}" }
    }
    val directory = sessions.firstOrNull { it.id == selected }?.directory

    val viewport = remember { LabViewport() }
    val visibility = remember { dependency(AgentImageVisibility) }
    DisposableEffect(viewport) {
        visibility.register(viewport)
        onDispose { visibility.unregister(viewport) }
    }
    var linkRevision by remember { mutableIntStateOf(0) }
    val links = remember { LinkPreviewStore() }
    DisposableEffect(links) {
        links.onArrival = { linkRevision++ }
        onDispose { links.close() }
    }
    val linkMenu = remember {
        LinkMenuHandler { action, url ->
            when (action) {
                LinkMenuAction.OpenInAppBrowser -> browser.send(BrowserFeature.Action.Open(url))
                else -> Toast.makeText(context, "${action.title} (lab)", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val openLink: (String) -> Unit = { url -> browser.send(BrowserFeature.Action.Open(url)) }

    CompositionLocalProvider(LocalAgentImageViewport provides viewport, LocalLinkMenu provides linkMenu) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { reloads++ }) { Text("Reload") }
                    TextButton(onClick = { openLink("https://example.com/") }, modifier = Modifier.padding(0.dp)) { Text("Browser") }
                    for (session in sessions) {
                        FilterChip(selected = session.id == selected, onClick = { selected = session.id }, label = { Text(session.title, maxLines = 1) })
                    }
                }
                error?.let { Text(it, color = OpenCodeTheme.colors.secondaryText, modifier = Modifier.padding(16.dp)) }
                LazyColumn(
                    Modifier.fillMaxSize().onGloballyPositioned { viewport.rect = it.boundsInWindow(); visibility.setNeedsUpdate() },
                    verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.standard),
                ) {
                    items(items, key = { it.id }) { item ->
                        CompositionLocalProvider(LocalAgentImageRow provides item.id, LocalAgentImageOccurrence provides item.id) {
                            Box(Modifier.padding(horizontal = TranscriptMetrics.contentGutter)) {
                                LabItem(item, server, directory, section, links, linkRevision, openLink)
                            }
                        }
                    }
                }
            }
            if (browserState.isOpen) {
                MediaBackHandler(enabled = true) { browser.send(browserBackAction(browser.state)) }
                BrowserPage(
                    store = browser,
                    replyStatus = null,
                    workingIndicator = {},
                )
            }
        }
    }
}

@Composable
private fun LabItem(
    item: OpenCodeTranscriptItem,
    server: OpenCodeServer,
    directory: String?,
    section: String?,
    links: LinkPreviewStore,
    linkRevision: Int,
    openLink: (String) -> Unit,
) {
    val colors = OpenCodeTheme.colors
    when (item) {
        is OpenCodeTranscriptItem.UserText ->
            if (section == null) {
                Text(item.text, style = OpenCodeTextStyles.body, color = colors.text, modifier = Modifier.fillMaxWidth().background(colors.userBubble, RoundedCornerShape(16.dp)).padding(12.dp))
            }
        is OpenCodeTranscriptItem.Tool -> {
            val images = item.call.imageAttachments
            if (images.isNotEmpty() && (section == null || section == "tools")) ToolImages(item, images.map { it.id to it.data })
            else if (section == null) Text("${item.call.displayName} · ${item.call.status.rawValue}", style = OpenCodeTextStyles.footnote, color = colors.secondaryText)
        }
        is OpenCodeTranscriptItem.AssistantMarkdown -> LabReply(item, server, directory, section, links, linkRevision, openLink)
        else -> if (section == null) Text(item::class.simpleName ?: "", style = OpenCodeTextStyles.footnote, color = colors.tertiaryText)
    }
}

@Composable
private fun ToolImages(item: OpenCodeTranscriptItem.Tool, attachments: List<Pair<String, ByteArray>>) {
    val presenter = LocalMediaPresenter.current
    val decoded = remember(item.id) { attachments.map { (id, data) -> id to BitmapFactory.decodeByteArray(data, 0, data.size) } }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((id, bitmap) in decoded) {
            bitmap ?: continue
            Box(
                Modifier.clip(RoundedCornerShape(TranscriptMetrics.cardCornerRadius)).clickable {
                    TranscriptImagePreview.fromImages(decoded, id, "Tool image")?.let { presenter?.presentPreview(it) }
                },
            ) {
                MediaThumbnail(bitmap, listOf(id), Modifier.size(if (decoded.size == 1) 240.dp else 140.dp), cornerRadius = TranscriptMetrics.cardCornerRadius)
            }
        }
    }
}

private val fence = Regex("```(\\w*)\\n([\\s\\S]*?)```")
private val linkPattern = Regex("!?\\[([^\\]]*)\\]\\(([^)\\s]+)\\)")
private val codePattern = Regex("`([^`\\n]+)`")
private val bareURL = Regex("https?://[^\\s)>\\]]+")

private sealed interface Piece {
    data class Prose(val text: String, val index: Int) : Piece
    data class Code(val language: String, val source: String) : Piece
}

private fun pieces(markdown: String): List<Piece> {
    val result = mutableListOf<Piece>()
    var index = 0
    var cursor = 0
    fun prose(text: String) {
        for (paragraph in text.split(Regex("\\n\\s*\\n"))) {
            if (paragraph.isNotBlank()) result += Piece.Prose(paragraph.trim(), index++)
        }
    }
    for (match in fence.findAll(markdown)) {
        prose(markdown.substring(cursor, match.range.first))
        result += Piece.Code(match.groupValues[1], match.groupValues[2].trimEnd())
        cursor = match.range.last + 1
    }
    prose(markdown.substring(cursor))
    return result
}

/** The paragraph's text with Markdown links as `LinkAnnotation.Url`s, its runs, and its named files. */
private class Paragraph(val text: AnnotatedString, val runs: List<LinkTextRun>, val files: List<AgentFileReference>)

private fun paragraph(source: String): Paragraph {
    val files = mutableListOf<AgentFileReference>()
    val runs = mutableListOf<LinkTextRun>()
    val text = buildAnnotatedString {
        var cursor = 0
        val tokens = (linkPattern.findAll(source).map { it to true } + codePattern.findAll(source).map { it to false } + bareURL.findAll(source).map { it to null })
            .sortedBy { it.first.range.first }
        for ((match, isLink) in tokens) {
            if (match.range.first < cursor) continue
            val before = source.substring(cursor, match.range.first)
            append(before)
            runs += LinkTextRun(before)
            when (isLink) {
                true -> {
                    val label = match.groupValues[1]
                    val destination = match.groupValues[2]
                    if (destination.startsWith("http://") || destination.startsWith("https://")) {
                        withLink(LinkAnnotation.Url(destination)) { withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(label.ifEmpty { destination }) } }
                        runs += LinkTextRun(label.ifEmpty { destination }, link = destination)
                    } else {
                        AgentFileReference.fromLink(destination)?.let { files += it }
                        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(label.ifEmpty { destination }) }
                        runs += LinkTextRun(label)
                    }
                }
                false -> {
                    val code = match.groupValues[1]
                    AgentFileReference.fromInlineCode(code)?.let { files += it }
                    withStyle(SpanStyle(fontFamily = OpenCodeFonts.monospace)) { append(code) }
                    runs += LinkTextRun(code, isCode = true)
                }
                null -> {
                    withLink(LinkAnnotation.Url(match.value)) { append(match.value) }
                    runs += LinkTextRun(match.value, link = match.value)
                }
            }
            cursor = match.range.last + 1
        }
        val rest = source.substring(cursor)
        append(rest)
        runs += LinkTextRun(rest)
    }
    return Paragraph(text, runs.filter { it.text.isNotEmpty() }, files.distinctBy { it.path })
}

@Composable
private fun LabReply(
    item: OpenCodeTranscriptItem.AssistantMarkdown,
    server: OpenCodeServer,
    directory: String?,
    section: String?,
    links: LinkPreviewStore,
    linkRevision: Int,
    openLink: (String) -> Unit,
) {
    val presenter = LocalMediaPresenter.current
    val parsed = remember(item.text) { pieces(item.text).map { piece -> piece to (piece as? Piece.Prose)?.let { paragraph(it.text) } } }
    val siblings = remember(parsed) { parsed.flatMap { it.second?.files.orEmpty() }.filter { it.kind == AgentFileReference.Kind.Image } }
    val blocks = remember(parsed) {
        parsed.mapNotNull { (piece, paragraph) -> paragraph?.let { LinkTextBlock((piece as Piece.Prose).index, prose = it.runs) } }
    }
    val targets = remember(blocks) { links.targets(blocks, item.id) }
    val imageLinks = remember(blocks) { ImageLinkPolicy.plan(blocks, growing = false).values.flatten() }
    LaunchedEffect(targets) { targets.forEach { links.appeared(it, item.id) } }

    fun openFile(file: AgentFileReference) {
        presenter?.presentPreview(TranscriptImagePreview(AgentFileDestination(file, directory), server))
    }

    CompositionLocalProvider(LocalAgentImageSiblings provides siblings) {
        Column(verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered)) {
            for ((piece, paragraph) in parsed) {
                when (piece) {
                    is Piece.Code -> if (piece.language == "mermaid") {
                        if (section == null || section == "mermaid") MermaidDiagramCard(piece.source, onPreview = { presenter?.presentPreview(it) })
                    } else if (section == null) {
                        Text(piece.source, style = OpenCodeTextStyles.footnote.copy(fontFamily = OpenCodeFonts.monospace), color = OpenCodeTheme.colors.text)
                    }
                    is Piece.Prose -> {
                        paragraph ?: continue
                        if (section == null || section == "links") ProseText(paragraph.text, openLink)
                        if (paragraph.files.isNotEmpty() && (section == null || section == "images" || section == "files")) {
                            CompositionLocalProvider(LocalAgentImageBlock provides piece.index) {
                                AgentImageGallery(
                                    files = paragraph.files,
                                    server = server,
                                    directory = directory,
                                    onPreviewImage = { presenter?.presentPreview(it) },
                                    onOpenFile = ::openFile,
                                )
                            }
                        }
                    }
                }
            }
            if (section == null || section == "links") {
                for (url in imageLinks) ImageLinkThumbnail(url)
                @Suppress("UNUSED_EXPRESSION") linkRevision
                for (target in targets) LinkRow(links.preview(target), openLink)
            }
        }
    }
}

@Composable
private fun ProseText(text: AnnotatedString, openLink: (String) -> Unit) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    LinkMenu(text = text, textLayout = { layout }, onOpen = openLink) {
        Text(text, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text, onTextLayout = { layout = it })
    }
}

@Composable
private fun ImageLinkThumbnail(url: String) {
    val presenter = LocalMediaPresenter.current
    var image by remember(url) { mutableStateOf<Bitmap?>(dependency(ImageLinkPreviewClient).cached(url)) }
    var finished by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        if (image == null) image = dependency(ImageLinkPreviewClient).load(url) { }
        finished = true
    }
    val bitmap = image ?: return Text(
        if (finished) "Image link unavailable (stays a link)" else "Loading image link…",
        style = OpenCodeTextStyles.footnote,
        color = OpenCodeTheme.colors.secondaryText,
    )
    Box(Modifier.clip(RoundedCornerShape(TranscriptMetrics.cardCornerRadius)).clickable { presenter?.presentPreview(TranscriptImagePreview(url, bitmap, "Image link")) }) {
        MediaThumbnail(bitmap, listOf(url), Modifier.fillMaxWidth(), fits = true, maxHeight = 280.dp, cornerRadius = TranscriptMetrics.cardCornerRadius)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinkRow(preview: LinkPreview, openLink: (String) -> Unit) {
    val colors = OpenCodeTheme.colors
    val menu = LocalLinkMenu.current
    var held by remember { mutableStateOf(false) }
    val title = when (val display = preview.display) {
        is LinkPreview.Display.Post -> display.author
        is LinkPreview.Display.Page -> display.title
        is LinkPreview.Display.Github -> display.title
        is LinkPreview.Display.Video -> display.title
        LinkPreview.Display.Pending -> "…"
        LinkPreview.Display.Plain -> LinkPreview.readableTitle(preview.url) ?: preview.domain
    }
    val fact = when (val display = preview.display) {
        is LinkPreview.Display.Github -> LinkPreview.repositoryFacts(display.details) ?: display.details.caption
        is LinkPreview.Display.Post -> display.text ?: preview.domain
        else -> preview.domain
    }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(TranscriptMetrics.cardCornerRadius))
                .background(colors.userBubble)
                .combinedClickable(onClick = { openLink(preview.url) }, onLongClick = { held = true })
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = preview.icon
            if (icon != null) {
                Image(icon.image.asImageBitmap(), contentDescription = null, modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)))
            } else {
                OpenCodeIcon("globe", contentDescription = null, size = 22.dp, tint = colors.secondaryText)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium), color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(fact, style = OpenCodeTextStyles.caption1, color = colors.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            preview.image?.let { Image(it.image.asImageBitmap(), contentDescription = null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))) }
        }
        if (held && menu != null) {
            LinkMenuPopup(
                url = preview.url,
                actions = menu.actions(preview.url),
                expanded = true,
                onAction = { held = false; menu.perform(it, preview.url) },
                onOpen = { held = false; openLink(preview.url) },
                onDismiss = { held = false },
            )
        }
    }
}
