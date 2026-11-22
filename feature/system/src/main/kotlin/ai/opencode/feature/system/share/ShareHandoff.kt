package ai.opencode.feature.system.share

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.files.ComposerStagedFile
import ai.opencode.core.sharing.InstantSerializer
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

// The share target sends what was shared itself (`ShareSender`); what it can't send, or what you
// take on in the app (Continue in OpenCode), it re-encodes and files in `filesDir/Shares`, and the
// app picks it up on its next activation.

/**
 * Sessions the share sheet can offer, published by the app for its current server whenever it
 * leaves the foreground. No credentials are included: those travel in the keychain
 * ([ShareCredentials]).
 */
@Serializable
data class ShareTargets(
    val version: Int = CURRENT_VERSION,
    val serverID: String,
    val serverName: String,
    val lastViewedSessionID: String? = null,
    /** Where New Session drafts start: the most recently used project. */
    val newSessionProject: Project? = null,
    val pinned: List<Session>,
    val recents: List<Session>,
    /** Projects a New Session can start in, most recently used first. */
    val projects: List<Project>? = null,
) {
    @Serializable
    data class Session(
        val id: String,
        val title: String,
        val projectID: String,
        val projectName: String,
        /** Where the session works: files shared to it upload there. */
        val directory: String? = null,
    )

    @Serializable
    data class Project(
        val id: String,
        val name: String,
        val directory: String? = null,
    )

    /** Every session offered, pinned first. */
    val sessions: List<Session> get() = pinned + recents

    /** Where a New Session can start: [projects], else the one remembered. */
    val newSessionProjects: List<Project>
        get() {
            if (!projects.isNullOrEmpty()) return projects
            return listOfNotNull(newSessionProject)
        }

    /** `false` on failure: the caller logs it. */
    fun write(file: File = fileURL): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        temporary.writeText(ShareJson.encodeToString(serializer(), this))
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }.isSuccess

    companion object {
        const val CURRENT_VERSION = 1
        const val MAXIMUM_RECENTS = 20
        const val MAXIMUM_PROJECTS = 12

        /** `filesDir/share-targets.json`. */
        val fileURL: File get() = File(dependency(AppContext).filesDir, "share-targets.json")

        fun load(file: File = fileURL): ShareTargets? {
            val text = runCatching { file.readText() }.getOrNull() ?: return null
            val targets = runCatching { ShareJson.decodeFromString(serializer(), text) }.getOrNull() ?: return null
            return targets.takeIf { it.version == CURRENT_VERSION }
        }

        fun remove(file: File = fileURL) {
            file.delete()
        }
    }
}

/** One share-sheet submission waiting for the app. */
@Serializable
data class ShareHandoff(
    val version: Int = CURRENT_VERSION,
    @Serializable(with = UUIDSerializer::class) val id: UUID,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    val serverID: String,
    val target: Target,
    /** The optional message followed by any shared text and links. */
    val text: String,
    val images: List<Image> = emptyList(),
    val files: List<FileEntry>? = null,
    /** What the app does with it; null is [Delivery.draft]. */
    val delivery: Delivery? = null,
    /** The ID the share sheet tried to send it under, so the app's send can't land twice. */
    val messageID: String? = null,
    /** The New Session the share sheet may have created trying, so the app's send finds it. */
    val newSessionID: String? = null,
) {
    @Serializable
    sealed interface Target {
        /** The project travels along so a deleted session can fall back to a New Session draft in the same place. */
        @Serializable @SerialName("session")
        data class Session(val id: String, val projectID: String) : Target

        @Serializable @SerialName("newSession")
        data class NewSession(val projectID: String? = null) : Target
    }

    @Serializable
    data class Image(
        @Serializable(with = UUIDSerializer::class) val id: UUID,
        val fileName: String,
        val mime: String,
    )

    /** A non-image file (PDF, document, video, audio) the app uploads. */
    @Serializable
    data class FileEntry(
        @Serializable(with = UUIDSerializer::class) val id: UUID,
        /** The stored name inside the share directory. */
        val fileName: String,
        /** The original name, shown on the chip and kept on the server. */
        val name: String,
        val mime: String,
    )

    @Serializable
    enum class Delivery {
        /** Waits in the session's composer (shares from before the sheet sent). */
        @SerialName("draft") draft,
        /** Couldn't be sent from the sheet: the app sends it when it next comes to the foreground. */
        @SerialName("send") send,
        /** Continue in OpenCode: the app opens the session with it in the composer and the keyboard up. */
        @SerialName("openInComposer") openInComposer,
    }

    val resolvedDelivery: Delivery get() = delivery ?: Delivery.draft

    companion object {
        const val CURRENT_VERSION = 1
        const val MAXIMUM_IMAGES = 10
        const val MAXIMUM_FILES = 10
    }
}

data class ReceivedShare(
    val handoff: ShareHandoff,
    val images: List<Image>,
    val files: List<ComposerStagedFile> = emptyList(),
) {
    class Image(val id: UUID, val data: ByteArray, val mime: String) {
        override fun equals(other: Any?): Boolean =
            other is Image && other.id == id && other.mime == mime && other.data.contentEquals(data)

        override fun hashCode(): Int = (id.hashCode() * 31 + mime.hashCode()) * 31 + data.contentHashCode()
    }
}

/**
 * File-based queue in `filesDir/Shares`. Each share is a directory that appears atomically
 * (staged, then renamed into place), and consuming one first renames it out of the queue, so a
 * share is delivered at most once even if two activations race.
 */
class ShareHandoffStore(val root: File) {
    @Throws(IOException::class)
    fun save(handoff: ShareHandoff, images: List<ByteArray>, files: List<File> = emptyList()) {
        require(handoff.images.size == images.size)
        require((handoff.files ?: emptyList()).size == files.size)
        if (!root.mkdirs() && !root.isDirectory) throw IOException("Couldn’t create Shares")
        val name = handoff.id.toString().uppercase()
        val staging = File(root, STAGING_PREFIX + name)
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("Couldn’t create staging folder")
        try {
            for ((image, data) in handoff.images.zip(images)) File(staging, image.fileName).writeBytes(data)
            for ((file, source) in (handoff.files ?: emptyList()).zip(files)) {
                Files.copy(source.toPath(), File(staging, file.fileName).toPath())
            }
            File(staging, MANIFEST_NAME).writeText(ShareJson.encodeToString(ShareHandoff.serializer(), handoff))
            Files.move(staging.toPath(), File(root, name).toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw if (error is IOException) error else IOException(error)
        }
    }

    /**
     * Every deliverable share, oldest first. Expired, unreadable, and abandoned entries are
     * deleted rather than returned. Shared files move into `filesDirectory/<id>/<name>`;
     * without one they are dropped.
     */
    fun consumeAll(now: Instant, filesDirectory: File? = null): List<ReceivedShare> {
        val entries = root.listFiles() ?: return emptyList()
        val shares = mutableListOf<ReceivedShare>()
        for (entry in entries) {
            val name = entry.name
            if (name.startsWith(STAGING_PREFIX) || name.startsWith(CLAIMED_PREFIX)) {
                // A staging directory the share sheet is still writing is moments old.
                val modified = entry.lastModified().takeIf { it > 0 }?.let(Instant::ofEpochMilli)
                if (modified == null || Duration.between(modified, now).seconds > 60 * 60) entry.deleteRecursively()
                continue
            }
            if (runCatching { UUID.fromString(name) }.isFailure) continue
            val claimed = File(root, CLAIMED_PREFIX + name)
            val moved = runCatching {
                Files.move(entry.toPath(), claimed.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }.isSuccess
            if (!moved) continue
            try {
                val share = read(claimed, filesDirectory) ?: continue
                if (share.handoff.id.toString().uppercase() != name.uppercase()) continue
                if (Duration.between(share.handoff.createdAt, now).toMillis() > MAXIMUM_AGE.toMillis()) continue
                shares.add(share)
            } finally {
                claimed.deleteRecursively()
            }
        }
        return shares.sortedBy { it.handoff.createdAt }
    }

    private fun read(directory: File, filesDirectory: File?): ReceivedShare? {
        val text = runCatching { File(directory, MANIFEST_NAME).readText() }.getOrNull() ?: return null
        val handoff = runCatching { ShareJson.decodeFromString(ShareHandoff.serializer(), text) }.getOrNull() ?: return null
        if (handoff.version != ShareHandoff.CURRENT_VERSION ||
            handoff.images.size > ShareHandoff.MAXIMUM_IMAGES ||
            (handoff.files ?: emptyList()).size > ShareHandoff.MAXIMUM_FILES
        ) return null
        val images = mutableListOf<ReceivedShare.Image>()
        for (image in handoff.images) {
            // Only plain names inside this share's own directory.
            if (!isPlainName(image.fileName) || !image.mime.startsWith("image/")) continue
            val bytes = runCatching { File(directory, image.fileName).readBytes() }.getOrNull() ?: continue
            images.add(ReceivedShare.Image(image.id, bytes, image.mime))
        }
        val files = mutableListOf<ComposerStagedFile>()
        if (filesDirectory != null) {
            for (file in handoff.files ?: emptyList()) {
                if (!isPlainName(file.fileName)) continue
                val name = if (isPlainName(file.name)) file.name else file.fileName
                val source = File(directory, file.fileName)
                val folder = File(filesDirectory, file.id.toString().uppercase())
                val destination = File(folder, name)
                val moved = runCatching {
                    if (!folder.mkdirs() && !folder.isDirectory) throw IOException("mkdir")
                    Files.move(source.toPath(), destination.toPath())
                }.isSuccess
                if (!moved) {
                    folder.deleteRecursively()
                    continue
                }
                files.add(ComposerStagedFile(file.id, name, file.mime, destination.length(), destination))
            }
        }
        return ReceivedShare(handoff, images, files)
    }

    companion object {
        val MAXIMUM_AGE: Duration = Duration.ofHours(24)
        private const val MANIFEST_NAME = "share.json"
        private const val STAGING_PREFIX = ".staging-"
        private const val CLAIMED_PREFIX = ".claimed-"

        /** `filesDir/Shares`. */
        val live: ShareHandoffStore?
            get() = runCatching { ShareHandoffStore(File(dependency(AppContext).filesDir, "Shares")) }.getOrNull()

        private fun isPlainName(name: String): Boolean = name.isNotEmpty() && !name.contains("/") && !name.startsWith(".")
    }
}

/** Unknown keys ignored, nils omitted. */
internal val ShareJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** `UUID` as its string form. */
internal object UUIDSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("ai.opencode.UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString().uppercase())
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}
