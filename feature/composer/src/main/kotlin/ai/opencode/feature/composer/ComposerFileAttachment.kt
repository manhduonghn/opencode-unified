package ai.opencode.feature.composer

import ai.opencode.core.files.ComposerStagedFile
import ai.opencode.server.model.OpenCodeFileReference
import java.util.UUID

/**
 * One composer file chip. Images keep their inline path; everything else uploads and is sent as a
 * path reference once it lands. The reducer is [ComposerAttachmentsFeature].
 */
data class ComposerFileAttachment(
    val id: UUID,
    val name: String,
    val mime: String,
    val size: Long,
    /** Absolute path of the local copy. */
    val localPath: String,
    val status: Status = Status.Uploading(sent = 0),
) {
    sealed interface Status {
        data class Uploading(val sent: Long) : Status
        data class Uploaded(val path: String) : Status
        data class Failed(val message: String) : Status
    }

    constructor(file: ComposerStagedFile, status: Status = Status.Uploading(sent = 0)) :
        this(id = file.id, name = file.name, mime = file.mime, size = file.size, localPath = file.url.path, status = status)

    /** A file already on the server, as a sent message references it. */
    constructor(uploaded: OpenCodeFileReference, id: UUID = UUID.randomUUID()) :
        this(
            id = id,
            name = uploaded.name,
            mime = uploaded.mime,
            size = 0,
            localPath = uploaded.path,
            status = Status.Uploaded(uploaded.path),
        )

    val reference: OpenCodeFileReference?
        get() = (status as? Status.Uploaded)?.let { OpenCodeFileReference(name = name, mime = mime, path = it.path) }

    val isUploading: Boolean get() = status is Status.Uploading

    val isFailed: Boolean get() = status is Status.Failed

    /** 0…1, for the chip's progress bar. */
    val progress: Double
        get() = when (val s = status) {
            is Status.Uploading -> if (size > 0) minOf(1.0, s.sent.toDouble() / size.toDouble()) else 0.0
            is Status.Uploaded -> 1.0
            is Status.Failed -> 0.0
        }
}
