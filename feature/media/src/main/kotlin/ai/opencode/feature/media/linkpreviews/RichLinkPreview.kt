package ai.opencode.feature.media

import ai.opencode.core.strings.localized
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Only service-owned identifiers leave the phone. No arbitrary OG fetches, response-provided image
 * URLs, credentials, query strings or redirects.
 */
sealed interface RichLinkSource {
    data class Github(val link: GitHubLink) : RichLinkSource
    data class Youtube(val id: String) : RichLinkSource

    val request: Request
        get() = when (this) {
            is Github -> request("https://api.github.com/repos/${link.repository}${link.apiPath}")
            is Youtube -> request(
                "https://www.youtube.com/oembed".toHttpUrl().newBuilder()
                    .addQueryParameter("url", "https://www.youtube.com/watch?v=$id")
                    .addQueryParameter("format", "json")
                    .build()
                    .toString(),
            )
        }

    fun metadata(data: ByteArray): LinkPreviewMetadata? {
        if (data.size > 1 shl 20) return null
        return when (this) {
            is Github -> link.metadata(data)
            is Youtube -> {
                val video = runCatching { LinkPreviewJson.decodeFromString(Video.serializer(), data.decodeToString()) }
                    .getOrNull() ?: return null
                if (video.title.isEmpty()) return null
                LinkPreviewMetadata(
                    kind = LinkPreviewMetadata.Kind.Video,
                    title = video.title.take(300),
                    text = video.authorName?.take(100),
                    site = "YouTube",
                )
            }
        }
    }

    val imageURL: String?
        get() = (this as? Youtube)?.let { "https://i.ytimg.com/vi/${it.id}/hqdefault.jpg" }

    @Serializable
    class Video internal constructor(val title: String, @SerialName("author_name") val authorName: String? = null)

    companion object {
        fun of(url: String): RichLinkSource? {
            if (!LinkPreviewPolicy.isPreviewable(url)) return null
            GitHubLink.of(url)?.let { return Github(it) }
            val parsed = url.toHttpUrlOrNull() ?: return null
            val host = parsed.host.lowercase()
            // Decoded.
            val parts = parsed.pathSegments.filter { it.isNotEmpty() }
            val id: String? = when (host) {
                "youtu.be" -> if (parts.size == 1) parts[0] else null
                "youtube.com", "www.youtube.com", "m.youtube.com" -> when {
                    parts == listOf("watch") -> {
                        val values = parsed.queryParameterValues("v")
                        if (values.size == 1) values[0] else null
                    }
                    parts.size == 2 && parts[0] in listOf("shorts", "embed", "live") -> parts[1]
                    else -> null
                }
                else -> null
            }
            if (id == null || id.length != 11 || !id.all { identifierByte(it) || it == '_' }) return null
            return Youtube(id)
        }

        fun identifierByte(char: Char): Boolean =
            char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '-'

        /**
         * A first-party JSON request: 6 s, no cookies, refused on a constrained path (the
         * transport). Android identifies itself as `OpenCode-Android-LinkPreview`.
         */
        fun request(url: String): Request = Request.Builder()
            .url(url)
            .tag(LinkPreviewTimeout::class.java, LinkPreviewTimeout(6, TimeUnit.SECONDS))
            .header("Accept", "application/json")
            .header("User-Agent", "OpenCode-Android-LinkPreview")
            .build()
    }
}

/** A public GitHub repository, issue, pull request or commit URL. */
data class GitHubLink(val repository: String, val kind: Kind, val reference: String? = null) {
    @Serializable
    enum class Kind {
        @SerialName("repository") Repository,
        @SerialName("issue") Issue,
        @SerialName("pullRequest") PullRequest,
        @SerialName("commit") Commit,
    }

    val apiPath: String
        get() = when (kind) {
            Kind.Repository -> ""
            Kind.Issue -> "/issues/$reference"
            Kind.PullRequest -> "/pulls/$reference"
            Kind.Commit -> "/commits/$reference"
        }

    /** What the URL alone says: `acme/widget #1251`, before any metadata. */
    val caption: String get() = GitHubPreview(repository = repository, kind = kind, reference = reference).caption

    fun metadata(data: ByteArray): LinkPreviewMetadata? {
        val value = runCatching { LinkPreviewJson.decodeFromString(Response.serializer(), data.decodeToString()) }
            .getOrNull() ?: return null
        val title = when (kind) {
            Kind.Repository -> value.fullName
            Kind.Issue, Kind.PullRequest -> value.title
            Kind.Commit -> value.commit?.message?.split("\r\n", "\n", "\r", "\u2028", "\u2029", "\u0085")?.first()
        }
        if (title.isNullOrEmpty()) return null
        val state: GitHubPreview.State? = when (kind) {
            Kind.Issue -> when (value.state) {
                "open" -> GitHubPreview.State.Open
                "closed" -> if (value.stateReason == "not_planned") GitHubPreview.State.NotPlanned else GitHubPreview.State.Completed
                else -> null
            }
            Kind.PullRequest -> when {
                value.merged == true -> GitHubPreview.State.Merged
                value.state == "closed" -> GitHubPreview.State.Closed
                value.draft == true -> GitHubPreview.State.Draft
                value.state == "open" -> GitHubPreview.State.Open
                else -> null
            }
            else -> null
        }
        val user = value.user ?: value.author ?: value.owner
        val details = GitHubPreview(
            repository = repository, kind = kind, reference = reference, state = state,
            author = user?.login ?: value.commit?.author?.name,
            labels = value.labels?.take(3)?.map { it.name.take(40) },
            additions = value.additions ?: value.stats?.additions,
            deletions = value.deletions ?: value.stats?.deletions,
            comments = value.comments, stars = value.stargazersCount, language = value.language,
        )
        return LinkPreviewMetadata(
            kind = LinkPreviewMetadata.Kind.Github,
            title = title.take(300),
            text = value.description?.take(300),
            site = "GitHub",
            github = details,
        )
    }

    @Serializable
    private class Response(
        val title: String? = null,
        @SerialName("full_name") val fullName: String? = null,
        val description: String? = null,
        val state: String? = null,
        @SerialName("state_reason") val stateReason: String? = null,
        val merged: Boolean? = null,
        val draft: Boolean? = null,
        val user: User? = null,
        val owner: User? = null,
        val author: User? = null,
        val labels: List<Label>? = null,
        val additions: Int? = null,
        val deletions: Int? = null,
        val comments: Int? = null,
        @SerialName("stargazers_count") val stargazersCount: Int? = null,
        val language: String? = null,
        val commit: Commit? = null,
        val stats: Stats? = null,
    ) {
        @Serializable class User(val login: String)
        @Serializable class Label(val name: String)
        @Serializable class CommitAuthor(val name: String? = null)
        @Serializable class Commit(val message: String, val author: CommitAuthor? = null)
        @Serializable class Stats(val additions: Int? = null, val deletions: Int? = null)
    }

    companion object {
        private val reservedOwners = setOf(
            "about", "codespaces", "collections", "customer-stories", "discussions", "enterprise",
            "events", "explore", "features", "issues", "login", "marketplace", "new", "notifications",
            "orgs", "organizations", "pricing", "pulls", "readme", "search", "security", "settings",
            "signup", "site", "sponsors", "team", "topics", "trending", "users",
        )

        fun of(url: String): GitHubLink? {
            if (!LinkPreviewPolicy.isPreviewable(url)) return null
            val parsed = url.toHttpUrlOrNull() ?: return null
            if (parsed.host.lowercase() != "github.com" || parsed.encodedPath.contains("%")) return null
            val parts = parsed.encodedPath.split("/").drop(1)
            if (parts.size != 2 && parts.size != 4) return null
            val owner = parts[0]
            val name = parts[1]
            if (owner.length !in 1..39 || !owner.all(RichLinkSource::identifierByte)) return null
            if (owner.lowercase() in reservedOwners) return null
            if (name.length !in 1..100 || name == "." || name == "..") return null
            if (!name.all { RichLinkSource.identifierByte(it) || it == '_' || it == '.' }) return null
            val repository = "$owner/$name"
            if (parts.size == 2) return GitHubLink(repository, Kind.Repository)
            val reference = parts[3]
            val kind = when (parts[2]) {
                "issues", "pull" -> {
                    if (reference.length !in 1..10 || !reference.all { it in '0'..'9' }) return null
                    val number = reference.toULongOrNull() ?: return null
                    if (number == 0UL) return null
                    if (parts[2] == "pull") Kind.PullRequest else Kind.Issue
                }
                "commit" -> {
                    if (reference.length !in 7..40 || !reference.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                    Kind.Commit
                }
                else -> return null
            }
            return GitHubLink(repository, kind, reference)
        }
    }
}

/** What GitHub's REST answer says about a link. */
@Serializable
data class GitHubPreview(
    val repository: String,
    val kind: GitHubLink.Kind,
    val reference: String? = null,
    val state: State? = null,
    val author: String? = null,
    val labels: List<String>? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val comments: Int? = null,
    val stars: Int? = null,
    val language: String? = null,
) {
    @Serializable
    enum class State(val rawValue: String) {
        @SerialName("Open") Open("Open"),
        @SerialName("Closed") Closed("Closed"),
        @SerialName("Merged") Merged("Merged"),
        @SerialName("Draft") Draft("Draft"),
        @SerialName("Completed") Completed("Completed"),
        @SerialName("Not planned") NotPlanned("Not planned");

        /** What the preview shows for the state; [rawValue] stays the wire value. */
        val title: String
            get() = when (this) {
                Open -> localized(R.string.media_github_state_open)
                Closed -> localized(R.string.media_github_state_closed)
                Merged -> localized(R.string.media_github_state_merged)
                Draft -> localized(R.string.media_github_state_draft)
                Completed -> localized(R.string.media_github_state_completed)
                NotPlanned -> localized(R.string.media_github_state_not_planned)
            }
    }

    val caption: String
        get() {
            val reference = reference ?: return repository
            return repository + if (kind == GitHubLink.Kind.Commit) " · " + reference.take(7) else " #$reference"
        }
}

/** A decoded immutable thumbnail, prepared once when metadata arrives. */
class LinkPreviewBitmap private constructor(val image: Bitmap, private val data: ByteArray) {
    override fun equals(other: Any?): Boolean = other is LinkPreviewBitmap && other.data.contentEquals(data)
    override fun hashCode(): Int = data.contentHashCode()

    companion object {
        fun of(data: ByteArray): LinkPreviewBitmap? =
            LinkPreviewArtwork.decode(data, pixels = 960)?.let { LinkPreviewBitmap(it, data) }
    }
}

/**
 * Downsamples before decoding full pixels: even a huge source image never becomes a full-resolution
 * bitmap. Caches keep the small JPEG.
 */
object LinkPreviewArtwork {
    fun thumbnail(data: ByteArray, pixels: Int = 960): ByteArray? {
        val image = decode(data, pixels) ?: return null
        val output = ByteArrayOutputStream()
        if (!image.compress(Bitmap.CompressFormat.JPEG, 80, output)) return null
        return output.toByteArray()
    }

    internal fun decode(data: ByteArray, pixels: Int): Bitmap? {
        if (data.size > 2 shl 20) return null
        return ImageDownsampling.decode(data, pixels)
    }
}

/**
 * ImageIO's `CGImageSourceCreateThumbnailAtIndex` with `ThumbnailMaxPixelSize` and
 * `CreateThumbnailWithTransform`: the first frame, EXIF orientation applied, its longer side at
 * most [maxPixelSize], decoded at a power-of-two subsample first so full pixels never exist.
 */
internal object ImageDownsampling {
    fun decode(data: ByteArray, maxPixelSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val longest = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= maxPixelSize) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            data, 0, data.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        val scale = maxPixelSize.toFloat() / max(decoded.width, decoded.height)
        val matrix = orientation(data)
        if (scale < 1f) matrix.postScale(scale, scale)
        if (matrix.isIdentity) return decoded
        val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (result !== decoded) decoded.recycle()
        return result
    }

    private fun orientation(data: ByteArray): Matrix {
        val matrix = Matrix()
        val value = runCatching {
            ExifInterface(ByteArrayInputStream(data)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        when (value) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        return matrix
    }

}
