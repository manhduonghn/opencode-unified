package ai.opencode.feature.workspace

import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Write operations for the workspace, ported from the fork's `FileManagerUtils` behaviour.
 *
 * The fork's protected paths, safe-name handling, symlink-as-link rule and zip-slip checks are
 * preserved. None of its legacy storage permissions or XML UI are carried over.
 */
object WorkspaceFiles {
    private val HOME_PROTECTED = setOf(".opencode", ".termux", "storage", "tmp", "usr")

    fun safeFileName(name: String): String {
        val cleaned = name.trim().replace('/', '_').replace('\u0000', '_')
        return cleaned.ifEmpty { "untitled" }.take(200)
    }

    fun uniqueTarget(directory: File, name: String): File {
        val safe = safeFileName(name)
        val first = File(directory, safe)
        if (!first.exists()) return first
        val dot = safe.lastIndexOf('.')
        val base = if (dot > 0) safe.substring(0, dot) else safe
        val extension = if (dot > 0) safe.substring(dot) else ""
        var index = 2
        while (true) {
            val candidate = File(directory, "$base ($index)$extension")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    /** Refuses to delete or rename the runtime roots, their ancestors and home's protected names. */
    fun isSafeToModify(file: File): Boolean = runCatching {
        val target = file.canonicalFile
        val roots = listOf(
            File("/"),
            File("/data"),
            File("/data/data"),
            TermuxConstants.TERMUX_FILES_DIR,
            TermuxConstants.TERMUX_HOME_DIR,
            TermuxConstants.TERMUX_PREFIX_DIR,
        ).map { it.canonicalFile }
        if (target in roots) return false
        val parent = target.parentFile?.canonicalFile ?: return false
        if (parent == TermuxConstants.TERMUX_HOME_DIR.canonicalFile && target.name in HOME_PROTECTED) {
            return false
        }
        true
    }.getOrDefault(false)

    fun createDirectory(directory: File, name: String): Result<File> = runCatching {
        val target = uniqueTarget(directory, name)
        if (!isSafeToModify(target)) throw IOException("Protected path")
        if (!target.mkdirs()) throw IOException("Could not create folder")
        target
    }

    fun createFile(directory: File, name: String): Result<File> = runCatching {
        val target = uniqueTarget(directory, name)
        if (!isSafeToModify(target)) throw IOException("Protected path")
        if (!target.createNewFile()) throw IOException("Could not create file")
        target
    }

    fun rename(source: File, name: String): Result<File> = runCatching {
        if (!isSafeToModify(source)) throw IOException("Protected path")
        val target = File(source.parentFile, safeFileName(name))
        if (target.exists()) throw IOException("A file with that name already exists")
        if (!source.renameTo(target)) throw IOException("Could not rename")
        target
    }

    fun delete(file: File): Boolean {
        if (!isSafeToModify(file)) return false
        return deleteTree(file)
    }

    private fun deleteTree(file: File): Boolean {
        // A symlink is removed as a link; its target is never followed.
        if (Files.isSymbolicLink(file.toPath())) return file.delete()
        if (file.isDirectory) file.listFiles()?.forEach { deleteTree(it) }
        return file.delete()
    }

    fun copy(source: File, destination: File): Result<Unit> = runCatching {
        if (!isSafeToModify(source)) throw IOException("Protected source")
        if (!isSafeToModify(destination)) throw IOException("Protected destination")
        copyTree(source, destination)
    }

    /** Renames when possible, otherwise copies then removes the source. */
    fun move(source: File, destination: File): Result<Unit> = runCatching {
        if (!isSafeToModify(source)) throw IOException("Protected source")
        if (!isSafeToModify(destination)) throw IOException("Protected destination")
        if (!source.renameTo(destination)) {
            copyTree(source, destination)
            if (!deleteTree(source)) throw IOException("Could not remove source")
        }
    }

    private fun copyTree(source: File, target: File) {
        if (Files.isSymbolicLink(source.toPath())) {
            Files.createSymbolicLink(target.toPath(), Files.readSymbolicLink(source.toPath()))
            return
        }
        if (source.isDirectory) {
            if (!target.exists() && !target.mkdirs()) throw IOException("Could not create folder")
            source.listFiles()?.forEach { copyTree(it, File(target, it.name)) }
        } else {
            source.inputStream().use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
        }
    }

    fun createZip(sources: List<File>, destination: File): Result<Int> = runCatching {
        var count = 0
        ZipOutputStream(FileOutputStream(destination)).use { zip ->
            sources.forEach { addToZip(it, it.name, zip) { count++ } }
        }
        count
    }

    private fun addToZip(file: File, entryName: String, zip: ZipOutputStream, onEntry: () -> Unit) {
        if (Files.isSymbolicLink(file.toPath())) return
        if (file.isDirectory) {
            zip.putNextEntry(ZipEntry(entryName.trimEnd('/') + "/"))
            zip.closeEntry()
            onEntry()
            file.listFiles()?.forEach { addToZip(it, "$entryName/${it.name}", zip, onEntry) }
        } else {
            zip.putNextEntry(ZipEntry(entryName))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            onEntry()
        }
    }

    fun extractZip(archive: File, destination: File): Result<Int> = runCatching {
        var count = 0
        val root = destination.canonicalFile
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val target = File(root, entry.name).canonicalFile
                if (!target.startsWith(root)) throw IOException("Unsafe archive entry: ${entry.name}")
                if (entry.isDirectory) {
                    if (!target.exists() && !target.mkdirs()) throw IOException("Could not create folder")
                } else {
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                }
                count++
            }
        }
        count
    }
}
