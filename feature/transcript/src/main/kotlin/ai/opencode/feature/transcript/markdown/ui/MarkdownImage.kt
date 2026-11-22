package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.core.R as CoreR
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.media.AgentImagePlan
import ai.opencode.feature.media.AgentImageRowAnchor
import ai.opencode.feature.media.AgentImageStatusCard
import ai.opencode.feature.media.DecodedImageCache
import ai.opencode.feature.media.MediaThumbnail
import ai.opencode.feature.media.R as MediaR
import ai.opencode.feature.media.TranscriptImagePreview
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.LocalTranscriptRowContext
import ai.opencode.feature.transcript.R
import ai.opencode.feature.transcript.TranscriptAction
import ai.opencode.feature.transcript.user.TranscriptDragPayload
import ai.opencode.feature.transcript.user.transcriptDragSource
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import ai.opencode.server.model.FileReadTarget
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A Markdown image. A web image loads through Coil; a `data:` image loads through the server
 * client's Markdown image reader (`OpenCodeClient.markdownImage`, desktop's `readLocalImage`) once
 * it's on screen, and a file outside the session's folder waits for a tap first. Tapping it opens
 * the zoom viewer; one that didn't load says so, and a tap tries again.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarkdownImage(
    source: String,
    alt: String,
    modifier: Modifier = Modifier,
    server: OpenCodeServer? = LocalTranscriptRowContext.current.server,
    directory: String? = LocalTranscriptRowContext.current.directory,
    /** The local file a failed load shows as a card instead. */
    fallbackFile: AgentFileReference? = null,
) {
    val send = LocalTranscriptRowContext.current.send
    val colors = OpenCodeTheme.colors
    val context = LocalContext.current
    val client = remember { dependency(OpenCodeClient) }
    // A `data:` source can be megabytes, so the key is built once per source.
    val cacheKey = remember(server, directory, source) {
        server?.let { OpenCodeClient.markdownImageKey(it, directory, source) } ?: source
    }
    val decodedImages = remember { dependency(DecodedImageCache) }
    // Bumped by Try Again, to load once more.
    var attempt by remember { mutableIntStateOf(0) }
    var image by remember(cacheKey) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(cacheKey) { mutableStateOf(false) }
    var allowsOutside by remember(cacheKey) { mutableStateOf(false) }
    val outside = remember(cacheKey, allowsOutside, fallbackFile) {
        if (allowsOutside || server == null) null else MarkdownImage.outsideFile(source, fallbackFile, server, directory)
    }
    // Marks the hosting row revised before an async load, failure, or retry resizes it.
    val row = remember { AgentImageRowAnchor() }
    AgentImageRowAnchor.Placement(row)
    // Android keeps the decoded copy, so a row coming back on screen doesn't decode on the main
    // thread.
    val cachedImage = remember(cacheKey) { decodedImages.image(cacheKey)?.asImageBitmap() }
    val shown = image ?: cachedImage
    val reduceMotion = rememberReduceMotion()
    val pixels = remember(cacheKey) { Animatable(if (shown != null) 1f else 0f) }
    LaunchedEffect(shown != null) {
        if (shown != null) pixels.animateTo(1f, MotionRole.Dim.animation(reduceMotion = reduceMotion))
    }
    fun applyState(nextImage: ImageBitmap?, nextFailed: Boolean, hadImage: Boolean) {
        if (MarkdownImage.revisesRow(hadImage, failed, nextImage != null, nextFailed)) row.revised()
        image = nextImage
        failed = nextFailed
    }
    LaunchedEffect(cacheKey, attempt, outside == null) {
        val hadImage = shown != null
        if (shown != null) {
            applyState(shown, nextFailed = false, hadImage = hadImage)
            return@LaunchedEffect
        }
        if (failed) applyState(null, nextFailed = false, hadImage = hadImage)
        val loaded: ImageBitmap? = withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = if (source.startsWith("http://", ignoreCase = true) || source.startsWith("https://", ignoreCase = true)) {
                    val result = SingletonImageLoader.get(context).execute(
                        ImageRequest.Builder(context).data(source).memoryCacheKey(DecodedImageCache.memoryKey(cacheKey)).build(),
                    )
                    (result as? SuccessResult)?.image?.toBitmap()
                } else {
                    val bytes = client.cachedMarkdownImage(cacheKey)
                        ?: if (outside != null) null else server?.let { server ->
                            val fallback = fallbackFile?.target(directory)?.let { FileReadTarget(it.location, it.relativePath) }
                            client.markdownImage(server, directory, source, fallback)
                        }
                    bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
                bitmap?.also { decodedImages.insert(it, cacheKey) }?.asImageBitmap()
            }.getOrNull()
        }
        if (loaded == null && outside != null) return@LaunchedEffect
        applyState(loaded, nextFailed = loaded == null, hadImage = hadImage)
    }
    val state = when {
        shown != null -> ImageLoad.Loaded(shown)
        outside != null -> ImageLoad.Outside(outside)
        failed -> ImageLoad.Failed
        else -> ImageLoad.Loading
    }
    val shape = RoundedCornerShape(TranscriptMetrics.cardCornerRadius)
    val imageLabel = stringResource(R.string.transcript_image)
    val openDescription = if (alt.isEmpty()) stringResource(R.string.transcript_image_open_description) else stringResource(R.string.transcript_open_named, alt)
    when (val load = state) {
        is ImageLoad.Loaded -> {
            val label = alt.ifEmpty { imageLabel }
            Box(
                modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .transcriptDragSource(
                        TranscriptDragPayload.Image(
                            load.image,
                            name = fallbackFile?.name ?: alt.ifEmpty { "image" },
                            file = fallbackFile,
                            server = server,
                            directory = directory,
                        ),
                        cornerRadius = TranscriptMetrics.cardCornerRadius,
                    )
                    .clip(shape)
                    .background(colors.userBubble, shape)
                    .clickable { send(TranscriptAction.PreviewImage(TranscriptImagePreview(cacheKey, load.image.asAndroidBitmap(), label, labelIsPlaceholder = alt.isEmpty()))) }
                    .semantics { contentDescription = openDescription },
                contentAlignment = Alignment.Center,
            ) {
                MediaThumbnail(load.image.asAndroidBitmap(), listOf(cacheKey), Modifier.heightIn(max = 360.dp).graphicsLayer { alpha = pixels.value },
                    fits = true, maxHeight = 360.dp, cornerRadius = TranscriptMetrics.cardCornerRadius)
            }
        }
        ImageLoad.Failed -> FailedImageCard(
            name = fallbackFile?.path?.substringAfterLast('/') ?: alt.ifEmpty { imageLabel },
            fallbackFile = fallbackFile,
            onRetry = {
                if (failed) row.revised()
                failed = false
                attempt += 1
            },
            modifier = modifier,
        )
        is ImageLoad.Outside -> {
            val loadLabel = stringResource(MediaR.string.media_image_load_label)
            val value = AgentImagePlan.outsideAccessibilityValue(load.file, directory)
            val allow = {
                row.revised()
                allowsOutside = true
            }
            AgentImageStatusCard(
                load.file,
                AgentImageStatusCard.Status.Outside,
                modifier
                    .clip(shape)
                    .clickable(onClickLabel = loadLabel, role = Role.Button) { allow() }
                    .clearAndSetSemantics {
                        role = Role.Button
                        contentDescription = load.file.name
                        stateDescription = value
                        testTag = "markdown-image-outside"
                        onClick(label = loadLabel) {
                            allow()
                            true
                        }
                    },
            )
        }
        ImageLoad.Loading -> Box(
            modifier.fillMaxWidth().heightIn(min = 120.dp).background(colors.userBubble, shape),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
    }
}

object MarkdownImage {
    /** A local image outside the session's folder: it waits for a tap, since reading it starts a server instance for that folder. */
    fun outsideFile(source: String, file: AgentFileReference?, server: OpenCodeServer, directory: String?): AgentFileReference? {
        file ?: return null
        val target = file.target(directory) ?: return null
        if (AgentImagePlan.isInside(target, directory)) return null
        if (server.imageRequest(source, directory) != null) return null
        return file
    }

    /** Whether changing between placeholder, loaded image, and failed card alters the row's height. */
    fun revisesRow(hadImage: Boolean, wasFailed: Boolean, nextImage: Boolean, nextFailed: Boolean): Boolean =
        hadImage != nextImage || (!nextImage && wasFailed != nextFailed)
}

private sealed interface ImageLoad {
    data object Loading : ImageLoad
    data object Failed : ImageLoad
    class Outside(val file: AgentFileReference) : ImageLoad
    class Loaded(val image: ImageBitmap) : ImageLoad
}

/**
 * An image that didn't load says so: a broken-photo glyph, its name, and “Couldn’t load
 * image”. A tap tries again; holding offers Try Again and Open (the viewer explains what went
 * wrong) when it's a file.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FailedImageCard(name: String, fallbackFile: AgentFileReference?, onRetry: () -> Unit, modifier: Modifier) {
    val colors = OpenCodeTheme.colors
    val send = LocalTranscriptRowContext.current.send
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(TranscriptMetrics.cardCornerRadius)
    val loadFailed = stringResource(R.string.transcript_image_load_failed)
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Row(
            Modifier
                .width(260.dp)
                .heightIn(min = 48.dp)
                .clip(shape)
                .background(colors.userBubble, shape)
                // TalkBack reads “Double-tap to try again”.
                .combinedClickable(onClickLabel = stringResource(R.string.transcript_image_retry_click_label), onClick = onRetry, onLongClick = { menu = true })
                .semantics {
                    contentDescription = name
                    stateDescription = loadFailed
                    testTag = "markdown-image-failed"
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OpenCodeIcon(SFSymbol.PhotoBadgeExclamationmark, contentDescription = null, tint = colors.secondaryText)
            Column(Modifier.weight(1f)) {
                Text(name, style = OpenCodeTextStyles.subheadline, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(loadFailed, style = OpenCodeTextStyles.footnote, color = colors.secondaryText)
            }
            OpenCodeIcon(SFSymbol.ArrowClockwise, contentDescription = null, tint = colors.tertiaryText)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(CoreR.string.common_try_again)) },
                leadingIcon = { OpenCodeIcon(SFSymbol.ArrowClockwise, contentDescription = null) },
                onClick = { menu = false; onRetry() },
            )
            if (fallbackFile != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.transcript_image_open)) },
                    leadingIcon = { OpenCodeIcon(SFSymbol.DocTextMagnifyingglass, contentDescription = null) },
                    onClick = { menu = false; send(TranscriptAction.OpenFile(fallbackFile)) },
                )
            }
        }
    }
}
