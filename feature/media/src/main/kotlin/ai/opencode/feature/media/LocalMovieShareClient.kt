package ai.opencode.feature.media

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.files.AttachmentFileType
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Remote movies use the existing file loader's authenticated export, not this dependency. */
internal class LocalMovieShareClient(val share: suspend (Uri, String) -> Unit) {
    companion object Key : DependencyKey<LocalMovieShareClient> {
        override val liveValue = LocalMovieShareClient { source, name -> shareOriginal(dependency(AppContext), source, name) }
        override val testValue = LocalMovieShareClient { _, _ -> }
    }
}

private suspend fun shareOriginal(context: Context, source: Uri, name: String) {
    var staged: File? = null
    var handedOff = false
    try {
        val uri = when (source.scheme) {
            "content" -> source
            "file" -> withContext(Dispatchers.IO) {
                val original = File(requireNotNull(source.path))
                // Independent of the composer lease and the image-share cache. A receiving app
                // may not open its grant until after this page and its draft have disappeared.
                val root = File(context.cacheDir, "movie-share")
                val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
                root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
                val dir = File(root, UUID.randomUUID().toString())
                check(dir.mkdirs())
                staged = dir
                val copy = File(dir, AttachmentFileType.sanitizedName(name))
                try {
                    Files.createLink(copy.toPath(), original.toPath())
                } catch (_: IOException) {
                    original.inputStream().use { input -> copy.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                        }
                    } }
                }
                FileProvider.getUriForFile(context, MediaFileProvider.authority(context), copy)
            }
            else -> error("Expected a local movie")
        }
        withContext(Dispatchers.Main.immediate) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = context.contentResolver.getType(uri)?.takeIf { it.startsWith("video/") } ?: "video/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(null, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            handedOff = true
        }
    } finally {
        if (!handedOff && staged != null) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            staged?.deleteRecursively()
        }
    }
}
