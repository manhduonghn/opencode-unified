package ai.opencode.feature.system.share

import com.termux.app.RuntimeStorage
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves the current share into the runtime home's `~/Downloads`, matching the fork's
 * `FileReceiverActivity`. The native share sheet stays the only exported share receiver; this is
 * just another action inside it.
 *
 * Files are copied from the share sheet's staged copies (already local, so the temporary content
 * URI grant no longer matters), images from their re-encoded JPEG bytes, and received text to a
 * timestamped `.txt`. Names are sanitised and made unique so a shared file can never escape the
 * Downloads directory or overwrite an earlier share.
 */
internal object ShareDownloads {

    /** Returns the number of items written into `~/Downloads`. */
    fun save(state: ShareModel.State): Int {
        val downloads = RuntimeStorage.downloads()
        var saved = 0

        state.files.forEach { staged ->
            val target = uniqueTarget(downloads, safeName(staged.name))
            runCatching {
                staged.url.inputStream().use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            }.onSuccess { saved++ }.onFailure { target.delete() }
        }

        state.images.forEachIndexed { index, image ->
            val target = uniqueTarget(downloads, "shared-image-${timestamp()}-${index + 1}.jpg")
            runCatching {
                FileOutputStream(target).use { it.write(image.jpeg) }
            }.onSuccess { saved++ }.onFailure { target.delete() }
        }

        val text = state.sharedText.filter { it.isNotBlank() }
        if (text.isNotEmpty()) {
            val target = uniqueTarget(downloads, "shared-${timestamp()}.txt")
            runCatching {
                FileOutputStream(target).use { it.write(text.joinToString("\n\n").toByteArray(Charsets.UTF_8)) }
            }.onSuccess { saved++ }.onFailure { target.delete() }
        }

        return saved
    }

    /** Rejects path separators, control characters and leading dots, mirroring the fork. */
    private fun safeName(name: String): String {
        var safe = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim()
        if (safe.isEmpty() || safe == "." || safe == "..") safe = "shared"
        if (safe.startsWith(".")) safe = "shared$safe"
        if (safe.length > 120) {
            val dot = safe.lastIndexOf('.')
            val extension = if (dot > 0) safe.substring(dot) else ""
            safe = safe.substring(0, maxOf(1, 120 - extension.length)) + extension
        }
        return safe
    }

    private fun uniqueTarget(directory: File, name: String): File {
        val first = File(directory, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (index < 10_000) {
            val candidate = File(directory, "$base ($index)$extension")
            if (!candidate.exists()) return candidate
            index++
        }
        return File(directory, "$base-${System.currentTimeMillis()}$extension")
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}
