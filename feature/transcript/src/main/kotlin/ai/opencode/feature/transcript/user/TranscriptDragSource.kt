package ai.opencode.feature.transcript.user

import ai.opencode.core.files.AttachmentFileType
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.media.AgentFileDragProvider
import ai.opencode.feature.media.MediaDragContext
import ai.opencode.feature.media.MediaDragPayload
import ai.opencode.feature.media.TranscriptLiftHold
import ai.opencode.feature.media.fileReadRequest
import ai.opencode.server.OpenCodePairingLink
import android.content.Context
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draganddrop.DragAndDropSourceModifierNode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.layout.LayoutCoordinates
import ai.opencode.feature.transcript.selection.LocalTranscriptReplySelection
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import android.view.View
import android.webkit.MimeTypeMap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import java.util.Locale

/**
 * Server and directory context for resolving agent file references dragged out of a transcript row.
 */
@Immutable
data class TranscriptDragContext(val server: OpenCodeServer? = null, val directory: String? = null)

/** Visual framing used when lifting a drag preview. */
enum class TranscriptDragSurfaceStyle {
    /** A rounded card or image tile. */
    Card,
    /** A full-width code or equation band (rounded when lifted). */
    Band,
    /** A user message bubble (bubble corner radius). */
    Bubble,
    /** An inline file or web link inside prose. */
    InlineLink,
}

/**
 * What a transcript surface provides when dragged into another app or the composer. Android carries
 * it as [ClipData] ([clipData]).
 */
@Immutable
sealed interface TranscriptDragPayload {
    /** A fenced code block ([source] without fences, plus optional fence [language]). */
    data class Code(val source: String, val language: String?) : TranscriptDragPayload

    /** An image attachment with raw bytes already in memory (`OpenCodeImageAttachment`). */
    class ImageAttachment(val id: String, val data: ByteArray, val name: String) : TranscriptDragPayload {
        override fun equals(other: Any?): Boolean = other is ImageAttachment && other.id == id && other.name == name && other.data.contentEquals(data)
        override fun hashCode(): Int = id.hashCode() * 31 + name.hashCode()
    }

    /** A decoded image already in memory (Markdown images, Mermaid cards), optionally backed by a server file. */
    data class Image(
        val image: ImageBitmap,
        val name: String,
        val file: AgentFileReference? = null,
        val server: OpenCodeServer? = null,
        val directory: String? = null,
    ) : TranscriptDragPayload

    /** A file or image named by the agent (`AgentFileReference`) or attached to a user message. */
    data class AgentFile(
        val file: AgentFileReference,
        val server: OpenCodeServer? = null,
        val directory: String? = null,
        val mime: String? = null,
        val displayName: String? = null,
        val cachedImage: ImageBitmap? = null,
    ) : TranscriptDragPayload

    /** A long paste card (`PastedTextCard`). */
    data class PastedText(val text: String, val name: String) : TranscriptDragPayload

    /** A web or pairing URL. */
    data class Url(val url: String) : TranscriptDragPayload

    /** Plain message or reply text (`TranscriptUserBubble`). */
    data class Text(val text: String) : TranscriptDragPayload

    /** Reply blocks serialized lazily when the drag starts (`ReplyActions`). */
    data class MarkdownBlocks(val blocks: List<OpenCodeMarkdownBlock>) : TranscriptDragPayload

    /** Inline `opencode-file://` file links inside a paragraph, list item, or quote. */
    data class InlineFileLinks(val text: AnnotatedString, val lineSpacing: Float) : TranscriptDragPayload

    /** The file name the drop destination sees. */
    val suggestedName: String
        get() = when (this) {
            is Code -> "snippet.${fileExtension(language) ?: "txt"}"
            is ImageAttachment -> sanitizedSuggestedName(name, "image", imageMimeType(data, name))
            is Image -> sanitizedSuggestedName(name, "image", "image/png")
            is AgentFile -> {
                val fileName = displayName ?: file.name
                sanitizedSuggestedName(fileName, "file", fileMimeType(fileName, mime ?: file.mime, file.kind))
            }
            is PastedText -> sanitizedSuggestedName(name, "pasted-text", "text/plain")
            is Url -> Uri.parse(OpenCodePairingLink.shareableURL(url) ?: url).host ?: "link"
            is Text, is MarkdownBlocks -> "message.txt"
            is InlineFileLinks -> "message.txt"
        }

    /** The MIME types offered, richest first. */
    val mimeTypes: List<String>
        get() = when (this) {
            is Code -> {
                val code = codeMimeType(language)
                when {
                    code != null && code != ClipDescription.MIMETYPE_TEXT_PLAIN -> listOf(code, ClipDescription.MIMETYPE_TEXT_PLAIN)
                    else -> listOf(ClipDescription.MIMETYPE_TEXT_PLAIN)
                }
            }
            is ImageAttachment -> listOf(imageMimeType(data, name))
            is Image -> listOf("image/png")
            is AgentFile -> listOf(fileMimeType(displayName ?: file.name, mime ?: file.mime, file.kind))
            is Url -> listOf(ClipDescription.MIMETYPE_TEXT_URILIST, ClipDescription.MIMETYPE_TEXT_PLAIN)
            is PastedText, is Text, is MarkdownBlocks, is InlineFileLinks -> listOf(ClipDescription.MIMETYPE_TEXT_PLAIN)
        }

    /**
     * The drag's data, built when the drag starts. Text travels as text; images and files as
     * `content://` URIs from media's [AgentFileDragProvider], which reads a server file only when
     * the drop target opens it. A path no server resolves drags as its plain text. [appContext]
     * serves the URIs; without it (no drag in progress) only text payloads have data.
     */
    fun clipData(context: TranscriptDragContext = TranscriptDragContext(), appContext: Context? = null): ClipData? {
        val label = suggestedName
        fun plain(text: String, mimes: List<String> = mimeTypes) =
            ClipData(ClipDescription(label, mimes.toTypedArray()), ClipData.Item(text))
        return when (this) {
            is Code -> plain(source)
            is PastedText -> plain(text)
            is Text -> plain(text)
            is MarkdownBlocks -> plain(OpenCodeMarkdownBlock.markdown(blocks))
            is InlineFileLinks -> plain(text.text)
            is Url -> {
                val share = OpenCodePairingLink.shareableURL(url) ?: url
                ClipData(ClipDescription(label, mimeTypes.toTypedArray()), ClipData.Item(share, null, Uri.parse(share)))
            }
            is ImageAttachment -> appContext?.let { app ->
                // Decoded once the drag starts; media's drag provider writes the PNG when read.
                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)?.let { decoded ->
                    imageClip(app, sanitizedSuggestedName(name.substringBeforeLast('.'), "image", "image/png"), decoded)
                }
            }
            is Image -> {
                val resolvedServer = server ?: context.server
                val resolvedDirectory = directory ?: context.directory
                if (file != null && resolvedServer != null && file.target(resolvedDirectory) != null) {
                    AgentFile(file, resolvedServer, resolvedDirectory, displayName = name, cachedImage = image).clipData(context, appContext)
                } else {
                    appContext?.let { imageClip(it, label, image.asAndroidBitmap()) }
                }
            }
            // Media's `.agentFile` payload: served lazily through `AgentFileDragProvider`.
            is AgentFile -> appContext?.let {
                MediaDragPayload.AgentFile(file, server, directory, mime, displayName, cachedImage?.asAndroidBitmap())
                    .clipData(it, MediaDragContext(context.server, context.directory))
            } ?: plain(file.path, listOf(ClipDescription.MIMETYPE_TEXT_PLAIN))
        }
    }

    /** An in-memory image as a PNG `content://` URI from media's drag provider. */
    private fun imageClip(appContext: Context, name: String, image: android.graphics.Bitmap): ClipData {
        val uri = AgentFileDragProvider.register(appContext, AgentFileDragProvider.Source.Image(name), mime = "image/png", suggestedName = name, cachedImage = image)
        return ClipData(ClipDescription(name, arrayOf("image/png")), ClipData.Item(uri))
    }

    companion object {
        /** Maps a Markdown code fence language tag to a file extension. */
        fun fileExtension(language: String?): String? {
            val raw = language?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
            val token = raw.split(Regex("[\\s,;{]")).firstOrNull { it.isNotEmpty() } ?: raw
            val cleaned = token.removePrefix(".").takeIf { it.isNotEmpty() } ?: return null
            return when (cleaned) {
                "javascript", "js", "node", "mjs", "cjs" -> "js"
                "jsx" -> "jsx"
                "typescript", "ts", "mts", "cts" -> "ts"
                "tsx" -> "tsx"
                "python", "py", "python3" -> "py"
                "swift" -> "swift"
                "rust", "rs" -> "rs"
                "golang", "go" -> "go"
                "ruby", "rb" -> "rb"
                "shell", "bash", "sh", "zsh", "fish", "console", "terminal" -> "sh"
                "markdown", "md", "mdx" -> "md"
                "objective-c", "objc", "obj-c" -> "m"
                "objective-cpp", "objcpp" -> "mm"
                "kotlin", "kt", "kts" -> "kt"
                "csharp", "c#", "cs" -> "cs"
                "cplusplus", "c++", "cpp", "cxx", "cc" -> "cpp"
                "c" -> "c"
                "header", "h" -> "h"
                "hpp" -> "hpp"
                "java" -> "java"
                "json", "jsonc", "jsonl" -> "json"
                "yaml", "yml" -> "yaml"
                "toml" -> "toml"
                "xml", "plist", "svg" -> cleaned
                "html", "htm" -> "html"
                "css", "scss", "sass", "less" -> cleaned
                "sql", "postgres", "postgresql", "mysql", "sqlite" -> "sql"
                "dockerfile", "docker" -> "dockerfile"
                "makefile", "make" -> "makefile"
                "tex", "latex", "math" -> "tex"
                "diff", "patch" -> "diff"
                "plaintext", "text", "txt", "plain" -> "txt"
                else -> if (cleaned.all { it.isLetterOrDigit() || it == '-' || it == '+' }) cleaned else null
            }
        }

        /** The MIME type for a code block's fence language. */
        fun codeMimeType(language: String?): String? {
            val ext = fileExtension(language) ?: return null
            return when (ext) {
                "swift" -> "text/x-swift"
                "py" -> "text/x-python"
                "js", "jsx" -> "text/javascript"
                "sh" -> "application/x-sh"
                "rb" -> "text/x-ruby"
                "c" -> "text/x-csrc"
                "cpp" -> "text/x-c++src"
                "h", "hpp" -> "text/x-chdr"
                "m" -> "text/x-objcsrc"
                "mm" -> "text/x-objc++src"
                "json" -> "application/json"
                "yaml" -> "application/yaml"
                "xml" -> "application/xml"
                "svg" -> "image/svg+xml"
                "html" -> "text/html"
                "plist" -> "application/x-plist"
                "txt" -> ClipDescription.MIMETYPE_TEXT_PLAIN
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.takeIf { it.startsWith("text/") }
                    ?: ClipDescription.MIMETYPE_TEXT_PLAIN
            }
        }

        /** Sniffs the MIME type of image bytes from magic headers (or file extension) without decoding pixels. */
        fun imageMimeType(data: ByteArray, fileName: String? = null): String {
            fun b(i: Int) = data[i].toInt() and 0xFF
            val n = data.size
            if (n >= 8 && b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47 && b(4) == 0x0D && b(5) == 0x0A && b(6) == 0x1A && b(7) == 0x0A) return "image/png"
            if (n >= 3 && b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF) return "image/jpeg"
            if (n >= 4 && b(0) == 0x47 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x38) return "image/gif"
            if (n >= 12 && b(0) == 0x52 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x46 && b(8) == 0x57 && b(9) == 0x45 && b(10) == 0x42 && b(11) == 0x50) return "image/webp"
            if (n >= 11 && b(4) == 0x66 && b(5) == 0x74 && b(6) == 0x79 && b(7) == 0x70 && b(8) == 0x68 && b(9) == 0x65 && b(10) == 0x69) return "image/heic"
            val prefix = String(data.copyOfRange(0, minOf(64, n)), Charsets.UTF_8).trim().lowercase(Locale.ROOT)
            if (prefix.startsWith("<svg") || prefix.contains("<svg")) return "image/svg+xml"
            if (fileName != null) {
                val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(AttachmentFileType.fileExtension(fileName))
                if (type != null && type.startsWith("image/")) return type
            }
            return "image/png"
        }

        /** The MIME type for an agent or attached file. */
        fun fileMimeType(fileName: String, mime: String?, kind: AgentFileReference.Kind = AgentFileReference.Kind.of(fileName)): String {
            val ext = AttachmentFileType.fileExtension(fileName)
            if (ext.isNotEmpty()) {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
                val code = codeMimeType(ext)
                if (code != null && (code != ClipDescription.MIMETYPE_TEXT_PLAIN || kind == AgentFileReference.Kind.Text)) return code
            }
            if (mime != null && mime != "application/octet-stream") return mime
            return when (kind) {
                AgentFileReference.Kind.Image -> "image/png"
                AgentFileReference.Kind.Pdf -> "application/pdf"
                AgentFileReference.Kind.Video -> "video/*"
                AgentFileReference.Kind.Audio -> "audio/*"
                AgentFileReference.Kind.Text -> ClipDescription.MIMETYPE_TEXT_PLAIN
                AgentFileReference.Kind.Document -> "application/octet-stream"
            }
        }

        fun sanitizedSuggestedName(name: String, defaultName: String, mime: String): String {
            val sanitized = AttachmentFileType.sanitizedName(name.ifEmpty { defaultName })
            if (AttachmentFileType.fileExtension(sanitized).isNotEmpty()) return sanitized
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: if (mime.startsWith("text/")) "txt" else null
            return if (ext != null) "$sanitized.$ext" else sanitized
        }
    }
}

/**
 * Holding it lifts it, and moving the held finger past the touch slop starts the drag (closing that
 * menu through [onDragStart]) with [payload]'s [ClipData]. A tap, a scroll, or a move before the
 * hold lands stays the surface's and the list's; once the hold lands, the rest of the gesture is
 * the drag source's, so the release doesn't also tap the surface under it and the list doesn't
 * scroll under the open menu.
 *
 * The surface as drawn, over its fill ([TranscriptDragSurfaceStyle.Card] on the card fill, the rest
 * on the transcript's background), clipped to [cornerRadius] (at least 10 dp for a band).
 */
fun Modifier.transcriptDragSource(
    payload: TranscriptDragPayload?,
    cornerRadius: Dp = TranscriptMetrics.cardCornerRadius,
    surface: TranscriptDragSurfaceStyle = TranscriptDragSurfaceStyle.Card,
    context: TranscriptDragContext = TranscriptDragContext(),
    onLift: (() -> Unit)? = null,
    onDragStart: (() -> Unit)? = null,
): Modifier {
    if (payload == null && onLift == null) return this
    return composed {
        val colors = OpenCodeTheme.colors
        val appContext = LocalContext.current.applicationContext
        val fill = if (surface == TranscriptDragSurfaceStyle.Card) colors.userBubble else colors.canvas
        val radius = if (surface == TranscriptDragSurfaceStyle.Band) maxOf(cornerRadius, 10.dp) else cornerRadius
        this then TranscriptDragSourceElement(payload, context, appContext, fill, radius, onLift, onDragStart)
    }
}

private class TranscriptDragSourceElement(
    val payload: TranscriptDragPayload?,
    val context: TranscriptDragContext,
    val appContext: Context,
    val fill: Color,
    val radius: Dp,
    val onLift: (() -> Unit)?,
    val onDragStart: (() -> Unit)?,
) : ModifierNodeElement<TranscriptDragSourceNode>() {
    override fun create() = TranscriptDragSourceNode(payload, context, appContext, fill, radius, onLift, onDragStart)

    override fun update(node: TranscriptDragSourceNode) {
        node.payload = payload
        node.dragContext = context
        node.appContext = appContext
        node.fill = fill
        node.radius = radius
        node.onLift = onLift
        node.onDragStart = onDragStart
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "transcriptDragSource"
    }

    override fun equals(other: Any?): Boolean = other is TranscriptDragSourceElement && other.payload == payload &&
        other.context == context && other.fill == fill && other.radius == radius &&
        other.onLift === onLift && other.onDragStart === onDragStart

    override fun hashCode(): Int = ((payload.hashCode() * 31 + context.hashCode()) * 31 + fill.hashCode()) * 31 + radius.hashCode()
}

/**
 * One gesture for the hold, the menu and the drag, and the surface recorded as drawn for the drag's
 * lift preview.
 */
private class TranscriptDragSourceNode(
    var payload: TranscriptDragPayload?,
    var dragContext: TranscriptDragContext,
    var appContext: Context,
    var fill: Color,
    var radius: Dp,
    var onLift: (() -> Unit)?,
    var onDragStart: (() -> Unit)?,
) : DelegatingNode(), DrawModifierNode, LayoutAwareModifierNode, CompositionLocalConsumerModifierNode {
    private var layer: GraphicsLayer? = null
    private var size = IntSize.Zero
    private var coordinates: LayoutCoordinates? = null

    override fun onRemeasured(size: IntSize) {
        this.size = size
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
    }

    private val dragAndDrop = delegate(
        DragAndDropSourceModifierNode { _ ->
            val clip = payload?.clipData(dragContext, appContext) ?: return@DragAndDropSourceModifierNode
            onDragStart?.invoke()
            val hasUri = (0 until clip.itemCount).any { clip.getItemAt(it).uri?.scheme == "content" }
            val flags = View.DRAG_FLAG_GLOBAL or (if (hasUri) View.DRAG_FLAG_GLOBAL_URI_READ else 0)
            val shape = RoundedCornerShape(radius)
            val recorded = layer
            val shadowFill = fill
            startDragAndDropTransfer(DragAndDropTransferData(clip, flags = flags), size.toSize()) {
                // The surface over its fill, in its rounded shape.
                val outline = shape.createOutline(size, layoutDirection, this)
                val path = Path().apply { addOutline(outline) }
                clipPath(path) {
                    drawRect(shadowFill)
                    recorded?.let { drawLayer(it) }
                }
            }
        },
    )

    init {
        delegate(SuspendingPointerInputModifierNode { liftThenDrag() })
    }

    private suspend fun PointerInputScope.liftThenDrag() {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            if (selectionOwnsHold(longPress.position)) return@awaitEachGesture
            // The menu the lift opens stays unfocusable while held, so this touch isn't cancelled.
            TranscriptLiftHold.isHeld = onLift != null
            onLift?.invoke()
            val origin = longPress.position
            try {
                while (true) {
                    // First, so the surface's own tap (inside) and the list's scroll (outside) see
                    // the rest of the gesture taken.
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == longPress.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    if (payload != null && (change.position - origin).getDistance() > viewConfiguration.touchSlop) {
                        // Unspecified: Compose's own hit test against the node's size would drop
                        // a request from a finger that has already moved past its edge.
                        dragAndDrop.requestDragAndDropTransfer(androidx.compose.ui.geometry.Offset.Unspecified)
                        break
                    }
                }
            } finally {
                TranscriptLiftHold.isHeld = false
            }
        }
    }

    /**
     * A reply selection on the move owns its touch, and so does a hold that selects code or a
     * formula: nothing lifts from under them.
     */
    private fun selectionOwnsHold(position: androidx.compose.ui.geometry.Offset): Boolean {
        val selection = currentValueOf(LocalTranscriptReplySelection) ?: return false
        if (selection.isDragging) return true
        if (payload !is TranscriptDragPayload.Code) return false
        val list = selection.container ?: return false
        val coordinates = coordinates ?: return false
        if (!list.isAttached || !coordinates.isAttached) return false
        return selection.holdSelects(list.localPositionOf(coordinates, position))
    }

    override fun ContentDrawScope.draw() {
        if (payload == null) {
            drawContent()
            return
        }
        val recording = layer ?: requireGraphicsContext().createGraphicsLayer().also { layer = it }
        recording.record { this@draw.drawContent() }
        drawLayer(recording)
    }

    override fun onDetach() {
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        layer = null
    }
}
