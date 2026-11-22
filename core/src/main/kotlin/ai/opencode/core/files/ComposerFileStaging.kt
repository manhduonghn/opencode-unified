package ai.opencode.core.files

import ai.opencode.core.R
import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.strings.localized
import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/**
 * A file copied into the app's temporary storage. Moved to `:core`: the composer and the share
 * target both stage files.
 */
data class ComposerStagedFile(
    val id: UUID,
    val name: String,
    val mime: String,
    val size: Long,
    val url: File,
) {
    /** Sniffs the MIME type from the staged copy. */
    constructor(id: UUID, name: String, size: Long, url: File) :
        this(id, name, AttachmentFileType.mime(url, name), size, url)
}

/**
 * Where picked and shared files wait while they upload. Upload streams read from these copies, so
 * the original's grant and lifetime don't matter once staging finishes. Each file gets a fresh
 * `<directory>/<UUID>/<name>` folder.
 *
 * [directory] is `cacheDir/ComposerFiles`; every function takes it as a parameter so callers and
 * tests can point elsewhere.
 */
object ComposerFileStaging {
    class FileTooLarge : IOException(localized(R.string.core_file_too_large))

    sealed interface BoundedStageResult {
        data class Staged(val file: ComposerStagedFile) : BoundedStageResult
        data object Rejected : BoundedStageResult
        data object Cancelled : BoundedStageResult
        data object Unavailable : BoundedStageResult
    }

    /** `cacheDir/ComposerFiles`; resolves the app context, so live paths only. */
    val directory: File get() = File(dependency(AppContext).cacheDir, "ComposerFiles")

    private const val CHUNK_SIZE = 64 * 1024

    private fun folderName(id: UUID) = id.toString().uppercase()

    /** Copies [source] into a fresh per-file directory that keeps the original name. */
    @Throws(IOException::class)
    fun stage(source: File, name: String? = null, id: UUID = UUID.randomUUID(), directory: File = this.directory): ComposerStagedFile {
        val fileName = AttachmentFileType.sanitizedName(name ?: source.name)
        val folder = File(directory, folderName(id))
        if (!folder.mkdirs() && !folder.isDirectory) throw IOException(localized(R.string.core_staging_folder_create_failed))
        val destination = File(folder, fileName)
        try {
            Files.copy(source.toPath(), destination.toPath())
        } catch (error: IOException) {
            folder.deleteRecursively()
            throw error
        }
        return ComposerStagedFile(id, fileName, destination.length(), destination)
    }

    /**
     * Opens [source] without following a terminal symlink, verifies it is a regular file within
     * [maximumBytes], and copies at most [maximumBytes] into a fresh staging directory. Never
     * mutates [source]; if cancelled or if the file grows past [maximumBytes] while reading
     * (TOCTOU), removes only the new staging folder.
     */
    fun stageBoundedRegularFile(
        source: File,
        name: String? = null,
        id: UUID = UUID.randomUUID(),
        maximumBytes: Long,
        isCancelled: () -> Boolean = { false },
        directory: File = this.directory,
    ): BoundedStageResult {
        if (isCancelled()) return BoundedStageResult.Cancelled
        val path = source.toPath()
        if (Files.isSymbolicLink(path) || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return BoundedStageResult.Rejected
        }
        val channel = try {
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) {
            return if (Files.isSymbolicLink(path) || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                BoundedStageResult.Rejected
            } else {
                BoundedStageResult.Unavailable
            }
        }
        return channel.use { input ->
            val sourceSize = runCatching { input.size() }.getOrNull()
            if (sourceSize == null || sourceSize > maximumBytes || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return@use BoundedStageResult.Rejected
            }
            if (isCancelled()) return@use BoundedStageResult.Cancelled
            val fileName = AttachmentFileType.sanitizedName(name ?: source.name)
            copyBounded(
                id = id,
                fileName = fileName,
                maximumBytes = maximumBytes,
                isCancelled = isCancelled,
                directory = directory,
                read = { buffer -> input.read(buffer) },
            )
        }
    }

    /**
     * The Android share path: streams a `content://` [uri] (another app's grant) into a fresh
     * staging directory with the same cap, rejecting it up front when the provider reports a
     * larger size, and removing only the new folder on reject or cancel.
     */
    fun stageBounded(
        contentResolver: ContentResolver,
        uri: Uri,
        name: String,
        id: UUID = UUID.randomUUID(),
        maximumBytes: Long,
        isCancelled: () -> Boolean = { false },
        directory: File = this.directory,
    ): BoundedStageResult {
        if (isCancelled()) return BoundedStageResult.Cancelled
        val descriptor = try {
            contentResolver.openAssetFileDescriptor(uri, "r")
        } catch (_: FileNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return BoundedStageResult.Unavailable
        return descriptor.use { asset ->
            val declared = asset.length
            if (declared != android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH && declared > maximumBytes) {
                return@use BoundedStageResult.Rejected
            }
            if (isCancelled()) return@use BoundedStageResult.Cancelled
            val stream = try {
                asset.createInputStream()
            } catch (_: IOException) {
                return@use BoundedStageResult.Unavailable
            }
            stream.use { input ->
                val bytes = ByteArray(CHUNK_SIZE)
                copyBounded(
                    id = id,
                    fileName = AttachmentFileType.sanitizedName(name),
                    maximumBytes = maximumBytes,
                    isCancelled = isCancelled,
                    directory = directory,
                    read = { buffer ->
                        val count = input.read(bytes, 0, minOf(bytes.size, buffer.remaining()))
                        if (count > 0) buffer.put(bytes, 0, count)
                        count
                    },
                )
            }
        }
    }

    /** The shared write loop: `O_CREAT | O_EXCL | O_NOFOLLOW`, 0600, capped at [maximumBytes]. */
    private fun copyBounded(
        id: UUID,
        fileName: String,
        maximumBytes: Long,
        isCancelled: () -> Boolean,
        directory: File,
        read: (ByteBuffer) -> Int,
    ): BoundedStageResult {
        val folder = File(directory, folderName(id))
        if (!folder.mkdirs() && !folder.isDirectory) return BoundedStageResult.Unavailable
        val destination = File(folder, fileName)
        val output = try {
            FileChannel.open(
                destination.toPath(),
                setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
            )
        } catch (_: Exception) {
            folder.deleteRecursively()
            return BoundedStageResult.Unavailable
        }
        var totalWritten = 0L
        val buffer = ByteBuffer.allocate(CHUNK_SIZE)
        val outcome: BoundedStageResult? = output.use { out ->
            while (true) {
                if (isCancelled()) return@use BoundedStageResult.Cancelled
                buffer.clear()
                val count = try {
                    read(buffer)
                } catch (_: IOException) {
                    return@use BoundedStageResult.Unavailable
                }
                if (count < 0) break
                if (count == 0) continue
                totalWritten += count
                if (totalWritten > maximumBytes) return@use BoundedStageResult.Rejected
                buffer.flip()
                try {
                    while (buffer.hasRemaining()) out.write(buffer)
                } catch (_: IOException) {
                    return@use BoundedStageResult.Unavailable
                }
            }
            val valid = runCatching { out.size() }.getOrNull() == totalWritten && totalWritten <= maximumBytes
            if (valid) null else BoundedStageResult.Rejected
        }
        if (outcome != null) {
            folder.deleteRecursively()
            return outcome
        }
        if (isCancelled()) {
            folder.deleteRecursively()
            return BoundedStageResult.Cancelled
        }
        return BoundedStageResult.Staged(ComposerStagedFile(id, fileName, totalWritten, destination))
    }

    /** Writes in-memory bytes into a fresh per-file directory without decoding or reformatting them. */
    @Throws(IOException::class)
    fun stage(
        data: ByteArray,
        name: String,
        id: UUID = UUID.randomUUID(),
        maximumBytes: Long? = null,
        directory: File = this.directory,
    ): ComposerStagedFile {
        if (maximumBytes != null && data.size.toLong() > maximumBytes) throw FileTooLarge()
        val fileName = AttachmentFileType.sanitizedName(name)
        val folder = File(directory, folderName(id))
        if (!folder.mkdirs() && !folder.isDirectory) throw IOException(localized(R.string.core_staging_folder_create_failed))
        val destination = File(folder, fileName)
        try {
            // Atomic: a temporary sibling renamed into place.
            val temporary = File(folder, ".$fileName.tmp")
            temporary.writeBytes(data)
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (error: IOException) {
            folder.deleteRecursively()
            throw error
        }
        return ComposerStagedFile(id, fileName, data.size.toLong(), destination)
    }

    fun rename(staged: ComposerStagedFile, newName: String, directory: File = this.directory): ComposerStagedFile {
        val fileName = AttachmentFileType.sanitizedName(newName)
        if (fileName == staged.name) return staged
        val folder = staged.url.parentFile ?: return staged
        if (!isInside(folder, directory)) return staged
        val destination = File(folder, fileName)
        val moved = runCatching { Files.move(staged.url.toPath(), destination.toPath()) }.isSuccess
        if (!moved) return staged
        return ComposerStagedFile(staged.id, fileName, staged.size, destination)
    }

    /** The byte size of [file] only when it is a regular file (not a symlink, directory, or special file). */
    fun regularFileSize(file: File): Long? {
        val path = file.toPath()
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        return runCatching { Files.size(path) }.getOrNull()?.takeIf { it >= 0 }
    }

    /** Removes [file]'s staging folder, only when it sits directly inside [directory]. */
    fun discard(file: File, directory: File = this.directory) {
        val folder = file.parentFile ?: return
        if (!isInside(folder, directory)) return
        folder.deleteRecursively()
    }

    private fun isInside(folder: File, directory: File): Boolean {
        val parent = folder.parentFile ?: return false
        return normalized(parent.toPath()) == normalized(directory.toPath())
    }

    private fun normalized(path: Path): Path = path.toAbsolutePath().normalize()
}
