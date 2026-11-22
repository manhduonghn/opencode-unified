package ai.opencode.feature.media

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.strings.localized
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import ai.opencode.core.ui.OpenCodeFonts
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * What a block's named files show with Image Previews (iOS `AgentImagePlan`): images in the
 * session's folder as a bento (a lone one at most 360 dp tall) once their shape is known (a
 * header probe, `probe`), held out of the layout until then, so an image enters once at its own
 * size (past `SHAPE_DEADLINE`, a placeholder at a guessed shape stands in); one outside it as
 * Tap to load (it's never read before the tap: reading starts a server location for its folder),
 * a path that doesn't resolve as a muted Not found card, and everything else as today's file cards.
 */
data class AgentImagePlan(
    val previews: List<AgentFileReference> = emptyList(),
    val notFound: List<AgentFileReference> = emptyList(),
    val outside: List<AgentFileReference> = emptyList(),
    val cards: List<AgentFileReference> = emptyList(),
    /** Images whose read failed or was refused, shown as cards: still watched, so a retry or a new place can read them again. */
    val unavailable: List<AgentFileReference> = emptyList(),
    /** What each file shows, in the order they're named. */
    val items: List<AgentImageItem> = emptyList(),
) {
    /** Every image the app may read here: each keeps a marker on screen, so reads, rechecks and retries follow it whatever it shows now. */
    val watched: List<AgentFileReference> get() = previews + notFound + unavailable

    companion object {
        /** A lone image stands at most this tall, like a Markdown image. */
        val LONE_MAX_HEIGHT: Dp = 360.dp
        /** The shape a preview holds if its size is ever unknown in the gallery. */
        const val PLACEHOLDER_ASPECT: Double = 4.0 / 3.0

        /** [phase] is the read's state for a file the app may read; [allowed] says an outside file was tapped to load. */
        fun make(
            files: List<AgentFileReference>,
            directory: String?,
            phase: (AgentFileReference, AgentFileReference.Target) -> AgentImagePreviews.Phase,
            allowed: (AgentFileReference.Target) -> Boolean,
            sized: (AgentFileReference.Target) -> Boolean = { false },
            deferred: (AgentFileReference.Target) -> Boolean = { false },
        ): AgentImagePlan {
            val previews = ArrayList<AgentFileReference>()
            val notFound = ArrayList<AgentFileReference>()
            val outside = ArrayList<AgentFileReference>()
            val cards = ArrayList<AgentFileReference>()
            val unavailable = ArrayList<AgentFileReference>()
            val items = ArrayList<AgentImageItem>()
            for (file in files) {
                val target = if (file.kind == AgentFileReference.Kind.Image) file.target(directory) else null
                if (target == null) {
                    cards.add(file)
                    items.add(AgentImageItem.Card(watched = false))
                    continue
                }
                if (!((isInside(target, directory) && !deferred(target)) || allowed(target))) {
                    outside.add(file)
                    items.add(AgentImageItem.Outside)
                    continue
                }
                when (phase(file, target)) {
                    AgentImagePreviews.Phase.Missing -> {
                        notFound.add(file)
                        items.add(AgentImageItem.NotFound)
                    }
                    AgentImagePreviews.Phase.Unavailable -> {
                        cards.add(file)
                        unavailable.add(file)
                        items.add(AgentImageItem.Card(watched = true))
                    }
                    AgentImagePreviews.Phase.Waiting, AgentImagePreviews.Phase.Loading, AgentImagePreviews.Phase.Loaded -> {
                        previews.add(file)
                        items.add(AgentImageItem.Preview(sized = sized(target)))
                    }
                }
            }
            return AgentImagePlan(previews, notFound, outside, cards, unavailable, items)
        }

        /** Every preview with a shape in one decided batch. */
        fun segments(items: List<AgentImageItem>): List<AgentImageSegment> =
            segments(items) { if (items[it] == AgentImageItem.Preview(sized = true)) 0 else null }

        /**
         * The files in the order they're named: each decided batch of previews as one bento where
         * its first image is named ([batch]), each run of file cards together (one row), and each
         * Not found or Tap to load card alone, so a file never moves ahead of one named before it
         * whatever answers first. A preview in no batch yet is held out: nothing is drawn for it,
         * so it enters once, at its place in its bento.
         */
        fun segments(items: List<AgentImageItem>, batch: (Int) -> Int?): List<AgentImageSegment> {
            val segments = ArrayList<AgentImageSegment>()
            val bentos = HashMap<Int, Int>()
            for ((index, item) in items.withIndex()) {
                val last = segments.lastOrNull()
                when (item) {
                    is AgentImageItem.Preview -> {
                        val b = batch(index) ?: continue
                        val at = bentos[b]
                        val existing = at?.let { segments[it] as? AgentImageSegment.Run }
                        if (at != null && existing != null) {
                            segments[at] = AgentImageSegment.Run(existing.indices + index)
                        } else {
                            bentos[b] = segments.size
                            segments.add(AgentImageSegment.Run(listOf(index)))
                        }
                    }
                    is AgentImageItem.Card -> if (last is AgentImageSegment.Cards) {
                        segments[segments.size - 1] = AgentImageSegment.Cards(last.indices + index)
                    } else {
                        segments.add(AgentImageSegment.Cards(listOf(index)))
                    }
                    AgentImageItem.NotFound -> segments.add(AgentImageSegment.NotFound(index))
                    AgentImageItem.Outside -> segments.add(AgentImageSegment.Outside(index))
                }
            }
            return segments
        }

        /** Read from the session's own location, which is already running. */
        fun isInside(target: AgentFileReference.Target, directory: String?): Boolean {
            directory ?: return false
            return target.location == directory || target.location == AgentFileReference.normalized(directory)
        }

        /**
         * Accessibility value for a Tap to load card: distinguishes an in-project image deferred
         * by a constrained network from a path outside the project.
         */
        fun outsideAccessibilityValue(file: AgentFileReference, directory: String?): String {
            val target = file.target(directory) ?: return localized(R.string.media_image_outside_project_value)
            if (isInside(target, directory)) return localized(R.string.media_image_deferred_low_data_value)
            return localized(R.string.media_image_outside_project_value)
        }

        /** The images held out of the layout until their shape is known. */
        fun held(items: List<AgentImageItem>): List<Int> = items.indices.filter { items[it] == AgentImageItem.Preview(sized = false) }

        /** While a gallery holds its images, the ones it still reads (every preview, shaped or not, and Not found ones, so a retry or a new place reads them). */
        fun watchedWhileHeld(items: List<AgentImageItem>): List<Int> = items.indices.filter {
            when (val item = items[it]) {
                is AgentImageItem.Preview, AgentImageItem.NotFound -> true
                is AgentImageItem.Card -> item.watched
                AgentImageItem.Outside -> false
            }
        }

        /** A preview's shape: its pixels' when known, else a guess from its name. */
        fun aspect(size: AgentImageSize?, guessing: AgentFileReference? = null): Double {
            if (size == null || size.width <= 0 || size.height <= 0) return guessing?.let(::guessedAspect) ?: PLACEHOLDER_ASPECT
            return size.width / size.height
        }

        /** The shape a file's name suggests, for a placeholder standing in for a slow server (a remembered shape comes first, through the size cache). */
        fun guessedAspect(file: AgentFileReference): Double {
            val name = file.name.lowercase()
            fun has(words: List<String>) = words.any { name.contains(it) }
            if (has(listOf("screenshot", "screen-shot", "simulator", "iphone", "phone", "ios", "mobile"))) return 1179.0 / 2556
            if (has(listOf("pano", "banner", "strip", "header", "wide"))) return 3.0
            if (has(listOf("icon", "avatar", "logo", "square", "thumb"))) return 1.0
            return PLACEHOLDER_ASPECT
        }
    }
}

/** What one named file shows. */
sealed interface AgentImageItem {
    /** A preview; with a shape to draw (known, or guessed once overdue) or held out of the layout until it has one. */
    data class Preview(val sized: Boolean) : AgentImageItem
    data object NotFound : AgentImageItem
    data object Outside : AgentImageItem
    /** Today's file card; [watched] for an image whose read failed. */
    data class Card(val watched: Boolean) : AgentImageItem

    val isPreview: Boolean get() = this is Preview
}

sealed interface AgentImageSegment {
    data class Run(val indices: List<Int>) : AgentImageSegment
    data class NotFound(val index: Int) : AgentImageSegment
    data class Outside(val index: Int) : AgentImageSegment
    data class Cards(val indices: List<Int>) : AgentImageSegment

    /** The first file it shows: its identity. */
    val first: Int
        get() = when (this) {
            is Run -> indices[0]
            is Cards -> indices[0]
            is NotFound -> index
            is Outside -> index
        }
}

/** The gap above a gallery, which it keeps only while it shows something (iOS `agentImageGap`). */
val LocalAgentImageGap = staticCompositionLocalOf<Dp?> { null }
/** The gallery's block ends a reply still streaming, so more images may be named in it: its bento gathers them a moment before deciding. */
val LocalAgentImageGathering = compositionLocalOf { false }
/** Every image a reply names, in order, so full screen swipes through them. */
val LocalAgentImageSiblings = staticCompositionLocalOf<List<AgentFileReference>> { emptyList() }
/** The place these blocks are shown (a reply's item): each new place an image is named rechecks it, and its full-screen flight starts there. */
val LocalAgentImageOccurrence = staticCompositionLocalOf { "blocks" }

/**
 * A block's named files (iOS `AgentImageGallery`): previews as bentos in decided batches, Not
 * found and Tap to load cards, and file cards, in the order named. Tapping a loaded tile opens
 * [onPreviewImage] (the full-screen viewer, through `MediaPresenter`); anything else opens the
 * file viewer ([onOpenFile]).
 */
@Composable
fun AgentImageGallery(
    files: List<AgentFileReference>,
    server: OpenCodeServer,
    directory: String?,
    onPreviewImage: (TranscriptImagePreview) -> Unit,
    onOpenFile: (AgentFileReference) -> Unit,
    modifier: Modifier = Modifier,
) {
    val previews = remember { dependency(AgentImagePreviews) }
    val siblings = LocalAgentImageSiblings.current
    val occurrence = LocalAgentImageOccurrence.current
    val block = LocalAgentImageBlock.current
    val gap = LocalAgentImageGap.current
    val gathering = LocalAgentImageGathering.current
    val serverID = server.persistenceID
    val targets = remember(files, directory) { files.map { it.target(directory) } }
    val notFoundState = stringResource(R.string.media_image_not_found)
    val loadImageLabel = stringResource(R.string.media_image_load_label)
    fun key(target: AgentFileReference.Target) = AgentImageKey(serverID, target.absolutePath)
    fun sighting(target: AgentFileReference.Target) = AgentImageSighting(key(target), occurrence)
    fun request(target: AgentFileReference.Target): AgentImageRequest? =
        server.fileReadRequest(target)?.let { AgentImageRequest(it, previews.isAllowed(key(target))) }

    // Read so a gathering that ends with nothing else changing decides.
    @Suppress("UNUSED_VARIABLE") val revision = previews.bentoRevision
    val plan = AgentImagePlan.make(
        files = files,
        directory = directory,
        phase = { _, target -> previews.entry(key(target)).phase },
        allowed = { previews.isAllowed(key(it)) },
        sized = { target -> previews.entry(key(target)).let { it.size != null || it.overdue } },
        deferred = { previews.isDeferredByNetwork(key(it)) },
    )
    val place = "$serverID|$occurrence#${block ?: -1}"
    val isPresented = previews.isPresented(place)
    fun path(index: Int): String? = targets[index]?.absolutePath

    // Each batch of images is laid out once, as a bento, when every shape in it is known and the
    // block has stopped naming images (bounded by `bentoGather`): it never reshuffles as more
    // arrive. Until the first batch, the gallery shows nothing (cards wait too), so nothing
    // enters and then moves. While the list's session opens, a gallery that couldn't show at
    // once waits for the opening to settle (`holdsEntrances`).
    val held = AgentImagePlan.held(plan.items)
    val readyAtOnce = previews.readyAtFirstSight(place, held.isEmpty())
    val holdsOpening = !isPresented && previews.holdsEntrances && !readyAtOnce
    val batches = if (holdsOpening) {
        previews.bentoBatches(place)
    } else {
        val decided = previews.bentoBatches(place)
        val taken = decided.flatMap { it.paths }.toSet()
        val pending = plan.items.indices.mapNotNull { index ->
            val target = targets[index]
            if (!plan.items[index].isPreview || target == null || target.absolutePath in taken) return@mapNotNull null
            val entry = previews.entry(key(target))
            val aspect = if (entry.size != null || entry.overdue) AgentImagePlan.aspect(entry.size, files[index]) else null
            key(target) to aspect
        }
        previews.settleBento(place, pending, gathering && !readyAtOnce, agentImageNow())
    }
    val batchOf = HashMap<String, Int>().apply {
        for ((n, batch) in batches.withIndex()) for (p in batch.paths) if (p !in this) this[p] = n
    }
    val undecided = plan.items.indices.filter { plan.items[it].isPreview && path(it)?.let(batchOf::get) == null }
    val holdsAll = !isPresented && (holdsOpening || (batches.isEmpty() && undecided.isNotEmpty()))
    val segments = if (holdsAll) emptyList() else AgentImagePlan.segments(plan.items) { index -> path(index)?.let(batchOf::get) }

    // The images the app may read here, by key, with their requests: probed as soon as the
    // reply names them, for their shape.
    val requests = LinkedHashMap<AgentImageKey, AgentImageRequest>()
    for ((index, file) in files.withIndex()) {
        if (file.kind != AgentFileReference.Kind.Image) continue
        val target = targets[index] ?: continue
        val imageKey = key(target)
        val explicitlyAllowed = previews.isAllowed(imageKey)
        if (!((AgentImagePlan.isInside(target, directory) && !previews.isDeferredByNetwork(imageKey)) || explicitlyAllowed)) continue
        val request = server.fileReadRequest(target) ?: continue
        requests[imageKey] = AgentImageRequest(request, explicitlyAllowed)
    }
    val currentRequests = rememberUpdatedState(requests)
    val probeKeys = requests.keys.sortedBy { it.path }
    LaunchedEffect(probeKeys) {
        for ((imageKey, request) in currentRequests.value.entries.sortedBy { it.key.path }) previews.probe(imageKey, request)
    }

    @Composable
    fun marker(target: AgentFileReference.Target, modifier: Modifier) {
        val request = request(target) ?: return
        AgentImageMarker(sighting(target), request, modifier)
    }

    Box(
        modifier.onSizeChanged { size ->
            if (size.height > 0) previews.markPresented(place)
        },
    ) {
        Box(Modifier.matchParentSize()) {
            // Undecided images: no frame of their own, but read, and found when their batch lands.
            for (index in if (holdsAll) AgentImagePlan.watchedWhileHeld(plan.items) else undecided) {
                val target = targets[index] ?: continue
                key(index) { marker(target, Modifier.matchParentSize()) }
            }
        }
        // The gap above it only once something shows: until then the text around it lays out as if it weren't there.
        Column(
            Modifier.padding(top = if (segments.isEmpty()) 0.dp else gap ?: 0.dp),
            verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered),
        ) {
            for (segment in segments) {
                key(segment.first) {
                    when (segment) {
                        is AgentImageSegment.Run -> {
                            val run = segment.indices.mapNotNull { index -> targets[index]?.let { files[index] to it } }
                            val batch = run.firstOrNull()?.let { batchOf[it.second.absolutePath] }?.let { batches[it] }
                            val outer = TranscriptMetrics.cardCornerRadius
                            val inner = if (run.size == 1) outer else AgentImageTunables.innerRadius
                            val shape = RoundedCornerShape(outer)
                            AgentImageBentoLayout(
                                // The shapes it was decided for, whatever arrived since: nothing in it moves.
                                aspects = run.map { (file, target) ->
                                    batch?.aspect(target.absolutePath) ?: AgentImagePlan.aspect(previews.entry(key(target)).size, file)
                                },
                                spacing = AgentImageTunables.gutter,
                                loneMaxHeight = AgentImagePlan.LONE_MAX_HEIGHT,
                                // Its outer corners are a card's; inside, tiles meet at small ones.
                                modifier = Modifier
                                    .clip(shape)
                                    .border(0.5.dp, imageOutline(), shape)
                                    .testTag("agent-image-gallery"),
                            ) {
                                for ((n, pair) in run.withIndex()) {
                                    val (file, target) = pair
                                    key(file.id) {
                                        val sighting = sighting(target)
                                        val entry = previews.entry(sighting.key)
                                        // Off screen a tile holds no pixels: only the cache keeps them.
                                        val image = if (entry.phase == AgentImagePreviews.Phase.Loaded && previews.isVisible(sighting)) previews.image(sighting.key) else null
                                        AgentImageGalleryTile(
                                            file = file,
                                            drag = MediaDragPayload.AgentFile(file, server, directory, cachedImage = image),
                                            sighting = sighting,
                                            entry = entry,
                                            image = image,
                                            radius = inner,
                                            pixelsNotBefore = AgentImagePreviews.pixelsNotBefore(batch, n),
                                            marker = { marker(target, Modifier.matchParentSize()) },
                                            onClick = {
                                                // Until a read has answered for this place, the viewer reads the file itself.
                                                if (image != null && previews.isFresh(sighting)) {
                                                    onPreviewImage(openPreview(previews, sighting, image, file, siblings, files, directory, ::sighting))
                                                } else {
                                                    onOpenFile(file)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        is AgentImageSegment.NotFound -> {
                            val file = files[segment.index]
                            Box {
                                targets[segment.index]?.let { marker(it, Modifier.matchParentSize()) }
                                AgentImageStatusCard(
                                    file,
                                    AgentImageStatusCard.Status.NotFound,
                                    Modifier.clearAndSetSemantics {
                                        contentDescription = file.name
                                        stateDescription = notFoundState
                                        testTag = "agent-image-not-found"
                                    },
                                )
                            }
                        }
                        is AgentImageSegment.Outside -> {
                            val file = files[segment.index]
                            val value = AgentImagePlan.outsideAccessibilityValue(file, directory)
                            AgentImageStatusCard(
                                file,
                                AgentImageStatusCard.Status.Outside,
                                Modifier
                                    .clip(RoundedCornerShape(TranscriptMetrics.cardCornerRadius))
                                    .clickable(onClickLabel = loadImageLabel, role = Role.Button) {
                                        targets[segment.index]?.let { previews.allow(key(it)) }
                                    }
                                    // The button's own label and value, not the card's text (iOS `.accessibilityLabel` on the Button).
                                    .clearAndSetSemantics {
                                        role = Role.Button
                                        contentDescription = file.name
                                        stateDescription = value
                                        testTag = "agent-image-outside"
                                        onClick(label = loadImageLabel) {
                                            targets[segment.index]?.let { previews.allow(key(it)) }
                                            true
                                        }
                                    },
                            )
                        }
                        is AgentImageSegment.Cards -> Box {
                            Box(Modifier.matchParentSize()) {
                                // An image whose read failed keeps its marker, so it's read again.
                                for (index in segment.indices) {
                                    if (plan.items[index] != AgentImageItem.Card(watched = true)) continue
                                    val target = targets[index] ?: continue
                                    key(index) { marker(target, Modifier.matchParentSize()) }
                                }
                            }
                            // The row's drag context (iOS `\.transcriptDragContext`): cards drag the server's file.
                            CompositionLocalProvider(LocalMediaDragContext provides MediaDragContext(server, directory)) {
                                AgentFileCards(segment.indices.map { files[it] }, onOpenFile)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Full screen swipes through every image of the reply that's loaded, in order. */
private fun openPreview(
    previews: AgentImagePreviews,
    sighting: AgentImageSighting,
    image: Bitmap,
    file: AgentFileReference,
    siblings: List<AgentFileReference>,
    files: List<AgentFileReference>,
    directory: String?,
    sightingOf: (AgentFileReference.Target) -> AgentImageSighting,
): TranscriptImagePreview {
    val candidates = if (file in siblings) siblings else files
    val seen = HashSet<String>()
    val pages = candidates.mapNotNull { sibling ->
        if (sibling.kind != AgentFileReference.Kind.Image) return@mapNotNull null
        val target = sibling.target(directory) ?: return@mapNotNull null
        val page = sightingOf(target)
        // Only pages read and confirmed for this reply: never a file since gone.
        if (!seen.add(page.mediaID)) return@mapNotNull null
        if (page != sighting && !previews.isConfirmed(page)) return@mapNotNull null
        val pixels = previews.image(page.key) ?: return@mapNotNull null
        TranscriptImagePreview.Page(page.mediaID, pixels, sibling.name)
    }
    return TranscriptImagePreview(
        sighting.mediaID,
        if (pages.any { it.id == sighting.mediaID }) pages else listOf(TranscriptImagePreview.Page(sighting.mediaID, image, file.name)),
    )
}

@Composable
private fun AgentImageGalleryTile(
    file: AgentFileReference,
    sighting: AgentImageSighting,
    entry: AgentImagePreviews.Entry,
    image: Bitmap?,
    radius: Dp,
    pixelsNotBefore: Double,
    drag: MediaDragPayload,
    marker: @Composable BoxScope.() -> Unit,
    onClick: () -> Unit,
) {
    val appear = rememberAgentImageAppearance(sighting.mediaID)
    val loaded = entry.phase == AgentImagePreviews.Phase.Loaded
    val loadingState = stringResource(R.string.media_image_loading_state)
    Box(
        Modifier
            .fillMaxSize()
            .mediaDragSource(drag)
            .clickable(onClickLabel = stringResource(R.string.media_image_open_label), role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = file.name
                if (!loaded) stateDescription = loadingState
            }
            .testTag("agent-image")
            // A tile fades in where it first appears; the marker under it, so what's drawn is what's read.
            .graphicsLayer { alpha = appear.value },
    ) {
        marker()
        AgentImageTile(
            mediaID = sighting.mediaID,
            image = image,
            loadedAt = entry.loadedAt,
            aspect = entry.size?.let { it.width / max(it.height, 1.0) },
            radius = radius,
            pixelsNotBefore = pixelsNotBefore,
        )
    }
}

/**
 * One preview (iOS `AgentImageTile`): a quiet tile at its exact frame in the bento, whose pixels
 * cross-fade in where they land (a short fade under Reduce Motion; none when they were already
 * there). An image whose shape differs from its tile's (laid out at a guessed shape, or
 * rechecked into a new one) fills it, cropping gently, or shows whole in it when that would crop
 * too much.
 */
@Composable
private fun AgentImageTile(
    mediaID: String,
    image: Bitmap?,
    loadedAt: Double?,
    aspect: Double?,
    radius: Dp,
    pixelsNotBefore: Double,
) {
    val shape = RoundedCornerShape(radius)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clip(shape)
            // Behind transparent images, and while it reads.
            .background(OpenCodeTheme.colors.userBubble)
            // Keeps a white image's edge visible on a white transcript.
            .border(0.5.dp, imageOutline(), shape)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            val shown = aspect ?: (image.width.toDouble() / max(image.height, 1))
            AgentImagePixels(
                mediaID = mediaID,
                image = image,
                loadedAt = loadedAt,
                fitsWhole = AgentImageBento.fits(maxWidth.value.toDouble(), maxHeight.value.toDouble(), shown),
                radius = radius,
                notBefore = pixelsNotBefore,
            )
        } else {
            AgentImageWaiting()
        }
    }
}

/** Pixels first drawn this long after landing were already there (seconds). */
private const val PIXELS_FRESH = 1.0

/**
 * The pixels cross-fade in from the first frame that draws them (when that's within a second of
 * their landing, and no sooner than their stagger in the bento), drawn per frame; over a
 * shorter fade under Reduce Motion, and at once when they were already there (a recomposed row).
 */
@Composable
private fun AgentImagePixels(
    mediaID: String,
    image: Bitmap,
    loadedAt: Double?,
    fitsWhole: Boolean,
    radius: Dp,
    notBefore: Double,
) {
    val previews = remember { dependency(AgentImagePreviews) }
    val reduceMotion = rememberReduceMotion()
    val landed = loadedAt ?: 0.0
    // A tile-sized cross-fade from the placeholder, on the dim role's length.
    val fade = (if (reduceMotion) MotionRole.Reduced else MotionRole.Dim).curve().duration
    // Kept by the store: a recomposed row carries on from the first frame's clock.
    val begin = previews.fadeStart(mediaID, landed, agentImageNow(), PIXELS_FRESH, if (reduceMotion) 0.0 else notBefore)
    val opacity = rememberAgentImageFade(begin, fade)
    // Fitted, the image takes the image's own frame, which the flight starts from.
    MediaThumbnail(
        image = image,
        ids = listOf(mediaID),
        fits = fitsWhole,
        cornerRadius = radius,
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value },
    )
}

/** An eased (1 − (1 − t)²) opacity from [begin] over [duration] seconds, driven per frame and then left at 1. */
@Composable
internal fun rememberAgentImageFade(begin: Double, duration: Double): State<Float> {
    fun value(time: Double): Float {
        val t = min(max((time - begin) / duration, 0.0), 1.0)
        return (1 - (1 - t).pow(2)).toFloat()
    }
    val state = remember(begin, duration) { mutableFloatStateOf(value(agentImageNow())) }
    LaunchedEffect(begin, duration) {
        while (state.floatValue < 1f) {
            withFrameNanos { nanos -> state.floatValue = value(nanos / 1e9) }
        }
    }
    return state
}

/**
 * A tile fades in where it first appears, over the swap role's fade in (kept by the store, so a
 * recomposed row never fades again; iOS `AgentImageAppearance`): the bento's placeholders come up
 * as the row's soft edge passes them, and under Reduce Motion, where the row lands at once, this
 * is their cross-fade (the reduced role). Nothing in a bento moves once it's laid out.
 */
@Composable
internal fun rememberAgentImageAppearance(id: String): State<Float> {
    val previews = remember { dependency(AgentImagePreviews) }
    val reduceMotion = rememberReduceMotion()
    val shown = previews.tileFirstShown(id, agentImageNow())
    return rememberAgentImageFade(shown, MotionRole.Swap.curve(reduceMotion = reduceMotion).duration)
}

/** Nothing at first, so a quick read never flashes a spinner; a slow one shows a small one, faded in (iOS `AgentImageWaiting`). */
@Composable
private fun AgentImageWaiting() {
    val reduceMotion = rememberReduceMotion()
    val opacity = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(900)
        opacity.animateTo(1f, MotionRole.Swap.animation(reduceMotion = reduceMotion))
    }
    CircularProgressIndicator(
        modifier = Modifier
            .size(20.dp)
            .graphicsLayer { alpha = opacity.value }
            .clearAndSetSemantics {},
        color = OpenCodeTheme.colors.secondaryText,
        strokeWidth = 2.dp,
    )
}

/** A named image the gallery can't show: missing, or outside the project until tapped (iOS `AgentImageStatusCard`). */
object AgentImageStatusCard {
    enum class Status { NotFound, Outside }
}

@Composable
fun AgentImageStatusCard(file: AgentFileReference, status: AgentImageStatusCard.Status, modifier: Modifier = Modifier) {
    val notFound = status == AgentImageStatusCard.Status.NotFound
    val colors = OpenCodeTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(
                colors.userBubble.copy(alpha = colors.userBubble.alpha * if (notFound) 0.6f else 1f),
                RoundedCornerShape(TranscriptMetrics.cardCornerRadius),
            )
            .padding(horizontal = TranscriptMetrics.standard, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 28.dp, height = 24.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            OpenCodeIcon(
                if (notFound) "photo.badge.exclamationmark" else "arrow.down.circle",
                contentDescription = null,
                size = 22.dp,
                tint = colors.secondaryText,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (notFound) stringResource(R.string.media_image_not_found) else stringResource(R.string.media_image_tap_to_load),
                style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium),
                color = if (notFound) colors.secondaryText else colors.text,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Text(
                file.path,
                style = OpenCodeTextStyles.caption1.copy(fontFamily = OpenCodeFonts.monospace),
                color = if (notFound) colors.tertiaryText else colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        Spacer(Modifier.size(0.dp))
    }
}

/**
 * iOS `Color(.separator).opacity(0.6)`: UIKit's own separator whatever the app theme, so a
 * palette's opaque border doesn't make the outline heavier than iOS draws it.
 */
@Composable
private fun imageOutline(): Color =
    (if (OpenCodeTheme.colors.isDark) Color(0.3294f, 0.3294f, 0.3451f, 0.6f) else Color(0.2353f, 0.2353f, 0.2627f, 0.29f))
        .let { it.copy(alpha = it.alpha * 0.6f) }
