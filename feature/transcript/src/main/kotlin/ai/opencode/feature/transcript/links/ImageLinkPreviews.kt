package ai.opencode.feature.transcript.links

import ai.opencode.core.dependencies.dependency
import ai.opencode.feature.media.AgentImageMarkerView
import ai.opencode.feature.media.AgentImageSize
import ai.opencode.feature.media.AgentImageVisibilityMarker
import ai.opencode.feature.media.ImageLinkPolicy
import ai.opencode.feature.media.ImageLinkPreviewClient
import ai.opencode.feature.media.MediaThumbnail
import ai.opencode.feature.media.TranscriptImagePreview
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.LocalTranscriptLayoutTuning
import ai.opencode.feature.transcript.LocalTranscriptRowContext
import ai.opencode.feature.transcript.R
import ai.opencode.feature.transcript.TranscriptAction
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.feature.transcript.markdown.ui.LocalLinkPreviewRow
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// The transcript's half of iOS Image Link Previews (`Transcript/ImageLinkPreviews.swift`): the
// policy and the bounded reads are `:feature:media`'s (`ImageLinkPolicy`, `ImageLinkPreviewClient`).

/**
 * The message's image-link plan when this surface previews images (iOS `MarkdownBlocksView`:
 * `imagePreviews ? ImageLinkPolicy.plan(blocks, growing:) : [:]`); empty otherwise.
 */
@Composable
fun rememberImageLinkPlan(blocks: List<OpenCodeMarkdownBlock>, isGrowing: Boolean): Map<Int, List<String>> {
    val enabled = LocalTranscriptRowContext.current.imagePreviews
    return remember(blocks, isGrowing, enabled) {
        if (enabled) ImageLinkPolicy.plan(blocks.linkTextBlocks(), isGrowing) else emptyMap()
    }
}

/**
 * Thumbnails for the complete direct-image links in [block]'s prose, under it (iOS
 * `BlockImageLinks`). [urls] is the block's entry in [rememberImageLinkPlan]; images open in the
 * zoom viewer through [onPreview] (`TranscriptAction.PreviewImage` by default).
 */
@Composable
fun BlockImageLinks(
    block: OpenCodeMarkdownBlock,
    urls: List<String>,
    modifier: Modifier = Modifier,
    onPreview: ((TranscriptImagePreview) -> Unit)? = null,
) {
    if (urls.isEmpty()) return
    val tuning = LocalTranscriptLayoutTuning.current
    val row = LocalLinkPreviewRow.current
    val send = LocalTranscriptRowContext.current.send
    ImageLinkPreviews(
        urls = urls,
        occurrence = "${row ?: ""}|${block.id}",
        maxHeight = tuning.linkMaxHeight,
        onPreview = onPreview ?: { send(TranscriptAction.PreviewImage(it)) },
        modifier = modifier,
    )
}

/**
 * The prose keeps its original links, including while a read fails or waits (iOS
 * `ImageLinkPreviews`). A visible image reserves its aspect-fit space as soon as its header
 * arrives.
 */
@Composable
fun ImageLinkPreviews(
    urls: List<String>,
    occurrence: String,
    maxHeight: Float,
    onPreview: (TranscriptImagePreview) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        for (url in urls) {
            key(url) {
                ImageLinkThumbnail(url, mediaID = "image-link:$occurrence|$url", maxHeight = maxHeight, onPreview = onPreview)
            }
        }
    }
}

/** iOS `ImageLinkThumbnailModel`: the cached image, else a bounded read while the thumbnail is on screen. */
private class ImageLinkThumbnailModel(url: String, private val client: ImageLinkPreviewClient) {
    var image by mutableStateOf<Bitmap?>(client.cached(url))
    var size by mutableStateOf(image?.let { AgentImageSize(it.width.toDouble(), it.height.toDouble()) })
    private var generation = Any()
    private val main = Handler(Looper.getMainLooper())

    /** Main thread. [revised] runs just before the reserved size changes (the row is about to grow). */
    suspend fun load(url: String, revised: () -> Unit) {
        if (image != null) return
        val generation = Any()
        this.generation = generation
        try {
            val loaded = client.cached(url) ?: client.load(url) { size ->
                main.post { if (this.generation === generation) resize(size, revised) }
            }
            if (!currentCoroutineContext().isActive || this.generation !== generation) return
            // Header dimensions keep their exact aspect through downsampling rounding.
            resize(if (loaded == null) null else size ?: AgentImageSize(loaded.width.toDouble(), loaded.height.toDouble()), revised)
            image = loaded
        } finally {
            if (this.generation === generation) this.generation = Any()
        }
    }

    private fun resize(size: AgentImageSize?, revised: () -> Unit) {
        if (this.size == size) return
        revised()
        this.size = size
    }
}

@Composable
private fun ImageLinkThumbnail(url: String, mediaID: String, maxHeight: Float, onPreview: (TranscriptImagePreview) -> Unit) {
    val model = remember(url) { ImageLinkThumbnailModel(url, dependency(ImageLinkPreviewClient)) }
    // Composed rows can be far off screen (a reply lays out all its blocks): read only while the
    // transcript's viewport says this one is on screen (iOS `ImageLinkVisibilityMarker`).
    var visible by remember { mutableStateOf(false) }
    val marker = remember { arrayOfNulls<AgentImageMarkerView>(1) }
    LaunchedEffect(model, visible) {
        if (visible) model.load(url) { marker[0]?.contentRevised() }
    }
    // iOS `url.lastPathComponent`, decoded.
    val label = remember(url) { url.toHttpUrlOrNull()?.pathSegments?.lastOrNull { it.isNotEmpty() } ?: url }
    val openDescription = stringResource(R.string.transcript_open_named, label)
    Box(Modifier.fillMaxWidth()) {
        val size = model.size
        if (size != null) {
            ImageLinkThumbnailLayout(
                width = size.width,
                height = size.height,
                maxHeight = maxHeight,
                modifier = Modifier.padding(top = TranscriptMetrics.standard),
            ) {
                val image = model.image
                if (image != null) {
                    val open = { onPreview(TranscriptImagePreview(id = mediaID, image = image, label = label)) }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(TranscriptMetrics.cardCornerRadius))
                            .clickable(onClick = open)
                            .clearAndSetSemantics {
                                contentDescription = openDescription
                                role = Role.Button
                                testTag = "image-link-preview"
                                onClick { open(); true }
                            },
                    ) {
                        MediaThumbnail(
                            image, listOf(mediaID), Modifier.fillMaxSize(), fits = true, maxHeight = maxHeight.dp,
                            cornerRadius = TranscriptMetrics.cardCornerRadius,
                        )
                    }
                } else {
                    Box(Modifier.fillMaxSize().clearAndSetSemantics {})
                }
            }
        }
        AgentImageVisibilityMarker(Modifier.matchParentSize()) { shown, view ->
            marker[0] = view
            visible = shown
        }
    }
}

/**
 * Exactly the thumbnail's aspect-fit size within the column's width and [maxHeight] (iOS
 * `ImageLinkThumbnailLayout` + `MediaZoomGeometry.fittedSize`).
 */
@Composable
private fun ImageLinkThumbnailLayout(width: Double, height: Double, maxHeight: Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val boundsWidth = if (constraints.hasBoundedWidth) constraints.maxWidth.toDouble() else width
        val boundsHeight = maxHeight.dp.toPx().toDouble()
        val (w, h) = if (width <= 0 || height <= 0 || boundsWidth <= 0 || boundsHeight <= 0) {
            0 to 0
        } else {
            val scale = min(boundsWidth / width, boundsHeight / height)
            (width * scale).roundToInt() to (height * scale).roundToInt()
        }
        val placeables = measurables.map { it.measure(Constraints.fixed(w, h)) }
        layout(w, h) { placeables.forEach { it.place(0, 0) } }
    }
}
