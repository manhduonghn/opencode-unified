package ai.opencode.feature.media

import ai.opencode.core.R as CoreR
import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.drawerPanHorizontalScroller
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import ai.opencode.core.ui.OpenCodeFonts
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object MermaidDiagramMetrics {
    val maxHeight: Dp = 360.dp
    val placeholderHeight: Dp = 180.dp

    /** Fits the card's width and the height cap, never larger than drawn (all in dp). */
    fun displaySize(natural: Size, width: Float): Size {
        if (natural.width <= 0 || natural.height <= 0 || width <= 0) return Size.Zero
        val scale = min(1f, min(width / natural.width, maxHeight.value / natural.height))
        return Size((natural.width * scale).roundToInt().toFloat(), (natural.height * scale).roundToInt().toFloat())
    }

    /** Whether a completed diagram render alters the laid-out height from [laidOutSize]. */
    fun revisesRow(laidOutSize: Size?, renderedSize: Size): Boolean {
        if (laidOutSize == null) return true
        return abs(laidOutSize.width - renderedSize.width) >= 0.5f || abs(laidOutSize.height - renderedSize.height) >= 0.5f
    }
}

/**
 * A finished Mermaid block as a card showing the diagram at its natural aspect, at most
 * [MermaidDiagramMetrics.maxHeight] tall. It holds a steady height while the diagram renders (its
 * final one once Pele has sized the source), marking its row revised before any change, opens full
 * screen (zoomable, sharp) on a tap, and shows its source from the hold menu. A diagram Pele
 * rejects shows as the code it is; one whose render was interrupted is tried once more, then shows
 * the code until it appears again or is tapped.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MermaidDiagramCard(
    source: String,
    modifier: Modifier = Modifier,
    onPreview: (TranscriptImagePreview) -> Unit = {},
) {
    val renderer = MermaidRenderer.shared
    val theme = if (OpenCodeTheme.colors.isDark) MermaidTheme.Dark else MermaidTheme.Light
    val density = LocalDensity.current.density
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val presenter = LocalMediaPresenter.current
    val focusManager = LocalFocusManager.current
    val reduceMotion = rememberReduceMotion()
    val scope = rememberCoroutineScope()

    val diagramKey = remember(source, theme) { MermaidCacheKey(source, theme) }
    val mediaID = "mermaid|${diagramKey.string}"
    val diagramType = remember(source) { MermaidSyntax.diagramType(source) ?: "Mermaid" }
    // The diagram's natural size, once drawn (also cached in the renderer, so a reused row
    // lays out at its final height at once).
    var natural by remember { mutableStateOf<Pair<MermaidCacheKey, Size>?>(null) }
    var image by remember { mutableStateOf<Pair<MermaidCacheKey, Bitmap>?>(null) }
    var failed by remember { mutableStateOf<MermaidCacheKey?>(null) }
    // Rendered twice without the page holding: shown as code, a tap retries.
    var interrupted by remember { mutableStateOf<MermaidCacheKey?>(null) }
    var retries by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    // Marks the hosting row revised before a fallback to code, retry, or uncached size changes height.
    val row = remember { AgentImageRowAnchor() }
    AgentImageRowAnchor.Placement(row)

    // The draw (below) sets this, straight from the renderer's cache when it's there.
    val isRendered = natural?.first == diagramKey
    val naturalSize: Size? = natural?.takeIf { it.first == diagramKey }?.second
        ?: renderer.naturalSize(source, theme)
    val hasFailed = failed == diagramKey || renderer.cachedFailure(source, theme)
    val previewLabel = stringResource(R.string.media_mermaid_preview_label, diagramType)
    val viewerScreenName = stringResource(R.string.media_mermaid_viewer_screen_title)
    val retryLabel = stringResource(CoreR.string.common_retry)
    val diagramDescription = stringResource(R.string.media_mermaid_diagram_description)
    val loadingState = stringResource(R.string.media_mermaid_loading_state, diagramType)
    val viewSourceLabel = stringResource(R.string.media_mermaid_view_source)

    fun retry() {
        row.revised()
        interrupted = null
        retries += 1
    }

    /** Full screen at a resolution that stays sharp when zoomed. */
    fun open() {
        scope.launch {
            val full = runCatching {
                renderer.viewerImage(source, theme, density, configuration.screenWidthDp.toFloat(), configuration.screenHeightDp.toFloat())
            }.getOrNull() ?: return@launch
            onPreview(TranscriptImagePreview(mediaID, full, previewLabel, labelIsPlaceholder = true))
        }
    }

    fun showSource() {
        focusManager.clearFocus()
        presenter?.present(sourceID = mediaID, isMedia = false, reducesMotion = reduceMotion, screenName = viewerScreenName) { viewer ->
            MermaidSourceView(source, viewer)
        }
    }

    when {
        hasFailed -> MermaidCode(source, modifier)
        interrupted == diagramKey -> MermaidCode(
            source,
            modifier
                .clickable(onClick = ::retry)
                .semantics { customActions = listOf(CustomAccessibilityAction(retryLabel) { retry(); true }) },
        )
        else -> BoxWithConstraints(modifier.fillMaxWidth()) {
            val width = maxWidth.value
            val display = naturalSize?.let { MermaidDiagramMetrics.displaySize(it, width) } ?: Size.Zero
            // The card's width is what it's offered; its height follows the diagram's aspect
            // (a steady placeholder height until that's known).
            val height = if (naturalSize == null) MermaidDiagramMetrics.placeholderHeight else max(display.height, 44f).dp
            val key = MermaidCacheKey(source, theme, display.width)
            val shown = image?.takeIf { it.first == key }?.second ?: renderer.cachedImage(key)
            val pixels = remember(key) { Animatable(if (shown != null) 1f else 0f) }
            LaunchedEffect(shown != null) {
                if (shown != null) pixels.animateTo(1f, MotionRole.Dim.animation(reduceMotion = reduceMotion))
            }
            val shape = RoundedCornerShape(TranscriptMetrics.cardCornerRadius)
            val dragSource = LocalTranscriptImageDragSource.current
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .clip(shape)
                    .background(MermaidRenderer.background(theme), shape)
                    .then(
                        // The transcript's drag source owns the hold: it opens the menu, and a
                        // move after it drags the diagram out (closing the menu).
                        if (dragSource != null && shown != null) {
                            dragSource(shown, "diagram.png", TranscriptMetrics.cardCornerRadius, { menuOpen = true }, { menuOpen = false })
                                .clickable(onClick = ::open)
                        } else {
                            Modifier.combinedClickable(onClick = { if (shown != null) open() }, onLongClick = { menuOpen = true })
                        },
                    )
                    .clearAndSetSemantics {
                        contentDescription = diagramDescription
                        stateDescription = if (!isRendered) loadingState else diagramType
                        role = Role.Button
                        onClick { open(); true }
                        customActions = listOf(CustomAccessibilityAction(viewSourceLabel) { showSource(); true })
                    }
                    .testTag("mermaid-diagram"),
                contentAlignment = Alignment.Center,
            ) {
                if (shown != null) {
                    MediaThumbnail(
                        image = shown,
                        ids = listOf(mediaID),
                        fits = true,
                        maxHeight = display.height.dp,
                        cornerRadius = if (display.width >= width - 1) TranscriptMetrics.cardCornerRadius else 0.dp,
                        modifier = Modifier.size(display.width.dp, display.height.dp).graphicsLayer { alpha = pixels.value },
                    )
                } else {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, properties = ai.opencode.feature.media.liftMenuProperties()) {
                    DropdownMenuItem(
                        text = { Text(viewSourceLabel) },
                        leadingIcon = { OpenCodeIcon("chevron.left.forwardslash.chevron.right", contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            showSource()
                        },
                    )
                }
            }
            // Waits for the draw: the size can be known before the diagram renders, so a failed
            // first render followed by a good retry wouldn't change `key`.
            LaunchedEffect(key, isRendered) {
                if (!isRendered || shown != null || display.width <= 0) return@LaunchedEffect
                val drawn = try {
                    renderer.image(source, theme, display.width, display.height, density)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    return@LaunchedEffect
                }
                image = key to drawn
            }
        }
    }

    LaunchedEffect(diagramKey, retries) {
        renderer.useHost(view)
        val key = diagramKey
        if (isRendered || hasFailed) return@LaunchedEffect
        val initialSize = naturalSize
        for (attempt in 0 until 2) {
            try {
                val diagram = renderer.diagram(source, theme)
                val size = Size(diagram.width, diagram.height)
                if (MermaidDiagramMetrics.revisesRow(initialSize, size)) row.revised()
                natural = key to size
                return@LaunchedEffect
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: MermaidRenderError.InvalidDiagram) {
                row.revised()
                failed = key
                return@LaunchedEffect
            } catch (_: Throwable) {
                // Interrupted (or the page couldn't load): once more, then code.
                if (attempt == 0) delay(1_000)
            }
        }
        row.revised()
        interrupted = key
    }
}

/** The diagram as the code it is: a highlighted, horizontally scrolling band. */
@Composable
private fun MermaidCode(source: String, modifier: Modifier = Modifier) {
    val palette = SyntaxHighlighter.Palette.current
    val highlighted = remember(source, palette) { SyntaxHighlighter.highlight(source, "mermaid", palette) }
    Column(modifier.fillMaxWidth().background(OpenCodeTheme.colors.codeBand)) {
        HorizontalDivider(thickness = 0.5.dp, color = OpenCodeTheme.colors.border)
        val scroll = rememberScrollState()
        Box(Modifier.drawerPanHorizontalScroller(scroll).horizontalScroll(scroll).padding(horizontal = TranscriptMetrics.contentGutter, vertical = 12.dp)) {
            SelectionContainer {
                Text(highlighted, style = codeStyle(), color = OpenCodeTheme.colors.text)
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = OpenCodeTheme.colors.border)
    }
}

/** Subheadline, monospaced. */
@Composable
private fun codeStyle() = OpenCodeTextStyles.subheadline.copy(fontFamily = OpenCodeFonts.monospace)

/** A diagram's Mermaid source, highlighted and selectable. */
@Composable
private fun MermaidSourceView(source: String, viewer: MediaViewerState) {
    val scope = rememberCoroutineScope()
    val palette = SyntaxHighlighter.Palette.current
    val highlighted = remember(source, palette) { SyntaxHighlighter.highlight(source, "mermaid", palette) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    dependency(HapticsClient).play(Haptic.tap)
                    val pasteboard = dependency(PasteboardClient)
                    scope.launch { pasteboard.setString(source) }
                }) {
                    OpenCodeIcon("doc.on.doc", contentDescription = null, size = 18.dp)
                    Text(stringResource(CoreR.string.common_copy), Modifier.padding(start = 6.dp))
                }
                Text(
                    stringResource(R.string.media_mermaid_source_title),
                    style = OpenCodeTextStyles.headline,
                    modifier = Modifier.weight(1f).semantics { },
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = { viewer.dismiss() }) { Text(stringResource(CoreR.string.common_done)) }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .testTag("mermaid-source"),
            ) {
                SelectionContainer {
                    Text(
                        highlighted,
                        style = codeStyle(),
                        color = OpenCodeTheme.colors.text,
                        softWrap = false,
                        modifier = Modifier.padding(TranscriptMetrics.contentGutter),
                    )
                }
            }
        }
    }
}
