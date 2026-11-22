package ai.opencode.feature.composer

import ai.opencode.core.files.AttachmentFileType
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.Store
import ai.opencode.core.strings.localized
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.composer.plusmenu.formatVideoDuration
import ai.opencode.feature.media.CachedImage
import ai.opencode.feature.media.ImageDrawingAdd
import ai.opencode.feature.media.MediaFlightAccessory
import ai.opencode.feature.media.MediaZoomSources
import ai.opencode.feature.media.TranscriptImagePreview
import ai.opencode.feature.media.FullscreenViewerFeature
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.feature.media.AgentFileDestination
import ai.opencode.feature.media.MediaReferenceResolver
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.nio.file.Files
import java.util.IdentityHashMap
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The composer's pending photos in the transcript's full-screen viewer: the same flight out of the
 * tile and back, paging and zoom, plus each photo's dimensions and size and a Remove action.
 *
 * The viewer's Remove (`FullscreenViewerFeature.State.allowsRemove`, `removeTapped`, the
 * `removed` delegate, `isDiscarded`) and the flight's accessories (`MediaFlightAccessory`) are
 * `:feature:media`'s; [ComposerPhotoViewerFeature] hands a removed page back to the composer.
 */
object ComposerPhotoViewer {
    private const val PREFIX = "composer-photo-"

    /** The tile's media ID. Prefixed so it never matches a sent image that keeps the attachment's ID. */
    fun mediaID(id: String): String = PREFIX + id

    /** Whether the photo is up full screen, its tile standing aside. */
    fun isShowing(id: String): Boolean = MediaZoomSources.shared.isHidden(listOf(mediaID(id)))

    /** Where the tile's × sits: its 44 pt (48 dp) target nudged out past the corner. */
    val removeBadgeOffset = DpOffset(8.dp, (-8).dp)

    /** The × badge's circle and glyph, inside its [REMOVE_TARGET] dp target. */
    private const val REMOVE_TARGET = 48f
    private const val REMOVE_DISC = 22f
    private const val REMOVE_GLYPH = 13f
    private const val REMOVE_DISC_OPACITY = 0.65f

    /** The tile's ×, drawn the same on the tile and on the viewer's flight. */
    @Composable
    fun RemoveBadge(modifier: Modifier = Modifier) {
        Box(
            modifier.size(REMOVE_DISC.dp).background(Color.Black.copy(alpha = REMOVE_DISC_OPACITY), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            OpenCodeIcon(SFSymbol.Xmark, contentDescription = null, size = REMOVE_GLYPH.dp, tint = Color.White)
        }
    }

    /**
     * The tile's × and a video's duration, carried by the viewer's flight at their own size: they
     * fade out as the photo flies up and back in as it flies home, while the tile itself stands
     * aside. They're drawn into bitmaps with the same metrics as [RemoveBadge] and
     * `VideoDurationBadge`.
     */
    fun flightAccessories(mediaID: String, attachments: List<OpenCodeOutgoingAttachment>, context: Context): List<MediaFlightAccessory> {
        val id = attachmentID(mediaID) ?: return emptyList()
        val attachment = attachments.firstOrNull { it.id == id } ?: return emptyList()
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val accessories = mutableListOf<MediaFlightAccessory>()
        renderRemoveBadge(context, density)?.let { image ->
            accessories += MediaFlightAccessory(
                image = image,
                corner = MediaFlightAccessory.Corner.TopTrailing,
                offset = Offset(removeBadgeOffset.x.value * density, removeBadgeOffset.y.value * density),
            )
        }
        attachment.videoDuration?.let { duration ->
            val fontScale = context.resources.configuration.fontScale
            renderVideoDurationBadge(context, formatVideoDuration(duration), density, fontScale)?.let { image ->
                accessories += MediaFlightAccessory(image = image, corner = MediaFlightAccessory.Corner.BottomLeading)
            }
        }
        return accessories
    }

    private fun renderRemoveBadge(context: Context, density: Float): Bitmap? {
        val side = (REMOVE_TARGET * density).roundToInt().takeIf { it > 0 } ?: return null
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = side / 2f
        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb((REMOVE_DISC_OPACITY * 255).roundToInt(), 0, 0, 0) }
        canvas.drawCircle(center, center, REMOVE_DISC * density / 2f, disc)
        val glyph = ContextCompat.getDrawable(context, SFSymbol.Xmark.drawable)?.mutate()
        if (glyph != null) {
            val half = (REMOVE_GLYPH * density / 2f).roundToInt()
            glyph.setBounds((center - half).roundToInt(), (center - half).roundToInt(), (center + half).roundToInt(), (center + half).roundToInt())
            glyph.setTint(android.graphics.Color.WHITE)
            glyph.draw(canvas)
        }
        return bitmap
    }

    /** `VideoDurationBadge` with its `VIDEO_BADGE_INSET` margin. */
    private fun renderVideoDurationBadge(context: Context, duration: String, density: Float, fontScale: Float): Bitmap? {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 11f * density * fontScale
            typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            fontFeatureSettings = "tnum"
        }
        val inset = PlusMenuTunables.VIDEO_BADGE_INSET * density
        val play = 8f * density
        val gap = 4f * density
        val padH = 6f * density
        val padV = 3f * density
        val metrics = text.fontMetrics
        val textHeight = metrics.descent - metrics.ascent
        val contentHeight = maxOf(textHeight, play)
        val pillWidth = padH * 2 + play + gap + text.measureText(duration)
        val pillHeight = padV * 2 + contentHeight
        val width = ceil(pillWidth + inset * 2).toInt()
        val height = ceil(pillHeight + inset * 2).toInt()
        if (width <= 0 || height <= 0) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val pill = RectF(inset, inset, inset + pillWidth, inset + pillHeight)
        val radius = PlusMenuTunables.VIDEO_BADGE_RADIUS * density
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb((PlusMenuTunables.VIDEO_BADGE_OPACITY * 255).roundToInt(), 0, 0, 0)
        }
        canvas.drawRoundRect(pill, radius, radius, background)
        val glyph = ContextCompat.getDrawable(context, SFSymbol.PlayFill.drawable)?.mutate()
        if (glyph != null) {
            val left = pill.left + padH
            val top = pill.centerY() - play / 2f
            glyph.setBounds(left.roundToInt(), top.roundToInt(), (left + play).roundToInt(), (top + play).roundToInt())
            glyph.setTint(android.graphics.Color.WHITE)
            glyph.draw(canvas)
        }
        val baseline = pill.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(duration, pill.left + padH + play + gap, baseline, text)
        return bitmap
    }

    /** The attachment a viewer page stands for: only a prefixed UUID. */
    fun attachmentID(mediaID: String): String? {
        if (!mediaID.startsWith(PREFIX)) return null
        val id = mediaID.removePrefix(PREFIX)
        return runCatching { UUID.fromString(id) }.getOrNull()?.let { id }
    }

    fun isVisualFile(file: ComposerFileAttachment): Boolean =
        file.mime.startsWith("video/", ignoreCase = true) || AgentFileReference(file.name).kind == AgentFileReference.Kind.Video

    /** Only the tapped photo is decoded. Other pages hold their original (or immutable blob address). */
    fun preview(
        attachments: List<OpenCodeOutgoingAttachment>,
        selected: String,
        files: List<ComposerFileAttachment> = emptyList(),
        server: OpenCodeServer? = null,
        directory: String? = null,
        decode: (OpenCodeOutgoingAttachment) -> Bitmap? = ::decode,
    ): TranscriptImagePreview? {
        val images = attachments.filter { it.isImage && (it.data.isNotEmpty() || it.mediaHash != null) }
        val pages = images.mapIndexed { index, attachment ->
            val original = attachment.data.takeIf { it.isNotEmpty() } ?: MediaReferenceResolver.cachedData(attachment.mediaHash)
            val image = if (attachment.id == selected) decode(attachment) else null
            val detail = if (image != null) detail(attachment, image) else original?.let {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0) detail(attachment, bounds.outWidth, bounds.outHeight) else null
            }
            TranscriptImagePreview.Page(
                mediaID(attachment.id), image, attachment.name ?: localized(R.string.composer_photo_viewer_image_label, index + 1, images.size),
                detail = detail, mediaHash = attachment.mediaHash, original = original,
                labelIsPlaceholder = attachment.name == null,
                isVideo = attachment.videoDuration != null || attachment.localVideoURI != null,
                hasDecodedOriginal = image != null,
                videoURL = attachment.localVideoURI?.let(Uri::parse),
            )
        }.toMutableList()
        val leases = mutableListOf<File>()
        for (file in files.filter(::isVisualFile)) {
            val destination = file.reference?.let { AgentFileDestination(AgentFileReference(it.path), directory) }
            // Upload completion unlinks the staging file. Lease a second name for its inode
            // in the staging root, outside its per-upload folder; never copy the movie.
            val lease = if (destination == null) leaseMovie(File(file.localPath)) ?: continue else null
            lease?.let(leases::add)
            pages += TranscriptImagePreview.Page(mediaID(file.id.toString()), null, file.name, name = file.name,
                file = destination, isVideo = true, videoURL = lease?.let(Uri::fromFile))
        }
        val id = mediaID(selected)
        if (pages.none { it.id == id }) {
            leases.forEach { it.delete() }
            return null
        }
        return TranscriptImagePreview(id, pages, server).also { if (leases.isNotEmpty()) movieLinks[it] = leases }
    }

    private fun leaseMovie(staged: File): File? {
        val root = staged.parentFile?.parentFile ?: return null
        val link = File(root, ".viewer-${UUID.randomUUID()}.${staged.extension}")
        return runCatching { Files.createLink(link.toPath(), staged.toPath()); link }.getOrNull()
    }

    private val movieLinks = IdentityHashMap<TranscriptImagePreview, List<File>>()

    /** Called for the opening snapshot on dismissal (and when presentation was rejected). */
    fun release(preview: TranscriptImagePreview) {
        movieLinks.remove(preview)?.forEach { it.delete() }
    }

    /** “JPEG · 1568 × 1176 · 412 KB”. */
    fun detail(attachment: OpenCodeOutgoingAttachment, pixelWidth: Int, pixelHeight: Int): String {
        val format = attachment.mime.split("/").lastOrNull()?.uppercase() ?: localized(R.string.composer_photo_viewer_image_format)
        val size = AttachmentFileType.byteCount(attachment.byteCount ?: attachment.data.size.toLong())
        return "$format · $pixelWidth × $pixelHeight · $size"
    }

    /** [detail] for a decoded [image]. */
    fun detail(attachment: OpenCodeOutgoingAttachment, image: Bitmap): String = detail(attachment, image.width, image.height)

    /** The attachment's bytes, decoded. */
    private fun decode(attachment: OpenCodeOutgoingAttachment): Bitmap? =
        runCatching { CachedImage.decoded(attachment.id, attachment.data, attachment.mediaHash) }.getOrNull()

    /**
     * The viewer's store: the transcript viewer with Remove, which hands the photo back through
     * [remove], and Draw on Image for [addDrawing]'s prompt.
     */
    fun store(
        preview: TranscriptImagePreview,
        addDrawing: ImageDrawingAdd? = null,
        remove: (String) -> Unit,
    ): Store<FullscreenViewerFeature.State, FullscreenViewerFeature.Action> = Store(
        FullscreenViewerFeature.State(preview, isShowingControls = true, allowsRemove = true, allowsDrawing = addDrawing != null),
        ComposerPhotoViewerFeature(addDrawing, remove),
    )
}

/**
 * The transcript viewer, whose Remove hands the photo back to the composer ([remove], on the main
 * thread) and whose Draw on Image adds to `addDrawing`'s prompt.
 */
class ComposerPhotoViewerFeature(
    addDrawing: ImageDrawingAdd? = null,
    private val remove: (String) -> Unit,
) : Reducer<FullscreenViewerFeature.State, FullscreenViewerFeature.Action> {
    private val viewer = FullscreenViewerFeature(addDrawing)

    override fun reduce(
        state: FullscreenViewerFeature.State,
        action: FullscreenViewerFeature.Action,
    ): Reduced<FullscreenViewerFeature.State, FullscreenViewerFeature.Action> {
        val reduced = viewer.reduce(state, action)
        val removed = (action as? FullscreenViewerFeature.Action.Delegate)?.action as? FullscreenViewerFeature.Action.DelegateAction.Removed
            ?: return reduced
        val id = ComposerPhotoViewer.attachmentID(removed.id) ?: return reduced
        return Reduced(reduced.state, Effect.merge(reduced.effect, Effect.run { _ -> remove(id) }))
    }
}
