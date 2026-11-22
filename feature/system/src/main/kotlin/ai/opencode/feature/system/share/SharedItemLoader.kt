package ai.opencode.feature.system.share

import ai.opencode.core.files.ComposerFileStaging
import ai.opencode.core.files.ComposerStagedFile
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.IntentCompat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Classifies and stages what another app shared (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`:
 * `EXTRA_STREAM` URIs or `ClipData`, plus `EXTRA_TEXT`) while the sender's URI grant is live.
 * Images are re-encoded at once, so only the small JPEG is kept; other files are copied (capped)
 * into `ComposerFiles`.
 *
 * Android has no `NSItemProvider` representations to fall back through: a stream is a
 * `content://` URI read once through its grant, and text arrives as text.
 */
object SharedItemLoader {
    class Image(val jpeg: ByteArray, val preview: Bitmap)

    sealed interface LoadedItem {
        data class Image(val image: SharedItemLoader.Image?) : LoadedItem
        data class File(val file: ComposerStagedFile?) : LoadedItem
        data class Text(val text: String?) : LoadedItem
    }

    /** One shared item. */
    sealed interface Provider {
        /** A shared stream; [declaredMime] is the intent's type when it names one concrete type. */
        data class Stream(val uri: Uri, val declaredMime: String?) : Provider
        data class Text(val text: String) : Provider
    }

    /**
     * The items an `ACTION_SEND`/`ACTION_SEND_MULTIPLE` intent carries: its streams
     * (`EXTRA_STREAM`, else payload `ClipData` URIs), then its text (`EXTRA_TEXT`, else `ClipData`
     * text, else `EXTRA_SUBJECT` when nothing else came).
     */
    fun providers(intent: Intent): List<Provider> {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return emptyList()
        val type = intent.type?.lowercase()?.takeIf { !it.contains('*') }
        val streams = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(streams::add)
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(streams::addAll)
        }
        val clip = intent.clipData
        // Text shares can grant the chooser a ClipData thumbnail. It isn't an attachment.
        val clipCarriesStreams = !intent.hasExtra(Intent.EXTRA_TEXT) || intent.type?.startsWith("text/", ignoreCase = true) == false
        if (streams.isEmpty() && clip != null && clipCarriesStreams) {
            for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(streams::add)
        }
        val texts = mutableListOf<String>()
        val text = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT) }.getOrNull()
        if (text != null) {
            texts.add(text.toString())
        } else {
            runCatching { intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT) }.getOrNull()?.mapTo(texts) { it.toString() }
        }
        if (texts.isEmpty() && clip != null) {
            for (index in 0 until clip.itemCount) {
                val item = clip.getItemAt(index)
                if (item.uri == null) item.text?.toString()?.let(texts::add)
            }
        }
        if (texts.isEmpty() && streams.isEmpty()) {
            intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()?.let(texts::add)
        }
        val streamType = if (streams.size == 1) type else null
        return streams.distinct().map { Provider.Stream(it, streamType) } + texts.map { Provider.Text(it) }
    }

    suspend fun load(
        context: Context,
        provider: Provider,
        canAddImage: Boolean = true,
        canAddFile: Boolean = true,
        maximumFileBytes: Long = ShareSender.MAXIMUM_FILE_BYTES,
    ): LoadedItem? = withContext(Dispatchers.IO) {
        when (provider) {
            is Provider.Text -> LoadedItem.Text(provider.text)
            is Provider.Stream -> {
                val resolver = context.contentResolver
                // Only other apps' content grants: a file:// path or our own provider could
                // read this app's private files into a message.
                if (!isForeignContent(context, provider.uri)) {
                    return@withContext null
                }
                val name = displayName(resolver, provider.uri)
                val mime = runCatching { resolver.getType(provider.uri) }.getOrNull()?.lowercase()
                    ?: provider.declaredMime
                    ?: name?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(pathExtension(it).lowercase()) }
                if (mime != null && mime.startsWith("image/")) {
                    if (!canAddImage) return@withContext LoadedItem.Image(null)
                    return@withContext LoadedItem.Image(image(resolver, provider.uri))
                }
                if (!canAddFile) return@withContext LoadedItem.File(null)
                LoadedItem.File(file(resolver, provider.uri, name, mime, maximumFileBytes))
            }
        }
    }

    /** Re-encodes while the grant is live, so only the small JPEG is kept. */
    suspend fun image(resolver: ContentResolver, uri: Uri): Image? {
        val jpeg = SharedImageEncoder.jpeg(resolver, uri) ?: return null
        if (!currentCoroutineContext().isActive) return null
        val preview = SharedImageEncoder.thumbnail(jpeg, maxPixelSize = 900) ?: return null
        return Image(jpeg, preview)
    }

    /**
     * Copies the shared stream (bytes untouched) into a fresh staging folder, at most
     * [maximumFileBytes]. JSON keeps a `.json` name (compound names like
     * `opencode-diagnostics.txt.json` included).
     */
    suspend fun file(
        resolver: ContentResolver,
        uri: Uri,
        displayName: String?,
        mime: String?,
        maximumFileBytes: Long = ShareSender.MAXIMUM_FILE_BYTES,
    ): ComposerStagedFile? {
        val suggested = displayName?.trim()
        val fallback = uri.lastPathSegment?.substringAfterLast('/')
        val name = resolvedFileName(suggested, fallbackName = if (suggested.isNullOrEmpty()) fallback else null, type = mime)
        val context = currentCoroutineContext()
        return when (
            val outcome = ComposerFileStaging.stageBounded(
                resolver, uri, name, maximumBytes = maximumFileBytes, isCancelled = { !context.isActive },
            )
        ) {
            is ComposerFileStaging.BoundedStageResult.Staged -> {
                if (!context.isActive) {
                    ComposerFileStaging.discard(outcome.file.url)
                    null
                } else {
                    outcome.file
                }
            }
            else -> null
        }
    }

    /**
     * For JSON shares, appends `.json` when [suggested] lacks a `.json` suffix so compound
     * names like `opencode-diagnostics.txt.json` keep `.json`. For all other file types, keeps
     * the suggested name, adding the fallback's or the type's extension when it has none.
     */
    fun resolvedFileName(suggested: String?, fallbackName: String?, type: String?): String {
        val trimmedSuggested = suggested?.trim() ?: ""
        val trimmedFallback = fallbackName?.trim() ?: ""
        val fallbackExt = pathExtension(trimmedFallback)
        val preferredExt = preferredExtension(type)
        if (isJSONType(type) || fallbackExt.equals("json", ignoreCase = true)) {
            for (candidate in listOf(trimmedSuggested, trimmedFallback)) {
                if (candidate.isEmpty()) continue
                return if (pathExtension(candidate).equals("json", ignoreCase = true)) candidate else "$candidate.json"
            }
            return "file.json"
        }
        if (trimmedSuggested.isNotEmpty()) {
            if (pathExtension(trimmedSuggested).isEmpty() && fallbackExt.isNotEmpty()) return "$trimmedSuggested.$fallbackExt"
            if (pathExtension(trimmedSuggested).isEmpty() && preferredExt.isNotEmpty()) return "$trimmedSuggested.$preferredExt"
            return trimmedSuggested
        }
        if (trimmedFallback.isNotEmpty()) return trimmedFallback
        return if (preferredExt.isEmpty()) "file" else "file.$preferredExt"
    }

    private fun preferredExtension(type: String?): String {
        val mime = type?.split(";")?.first()?.trim()?.lowercase() ?: return ""
        if (isJSONType(mime)) return "json"
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: ""
    }

    /** UTType `.json` conformance. */
    private fun isJSONType(type: String?): Boolean {
        val mime = type?.split(";")?.first()?.trim()?.lowercase() ?: return false
        return mime == "application/json" || mime == "text/json" || mime.endsWith("+json")
    }

    /** `NSString.pathExtension`: the last component's extension, case kept; none for a dotfile. */
    private fun pathExtension(name: String): String {
        val base = name.trimEnd('/').substringAfterLast('/')
        val dot = base.lastIndexOf('.')
        if (dot <= 0 || dot == base.lastIndex) return ""
        return base.substring(dot + 1)
    }

    /** `OpenableColumns.DISPLAY_NAME`. */
    private fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun isForeignContent(context: Context, uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val authority = uri.authority ?: return false
        val owner = runCatching { context.packageManager.resolveContentProvider(authority, 0)?.packageName }.getOrNull()
        return owner != context.packageName
    }
}
