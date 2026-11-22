package ai.opencode.feature.media

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.server.retry.NetworkPathMonitor
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RetryPolicy
import android.graphics.Bitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Direct image links are an opt-in exception to page-metadata fetching. The exact URL is used
 * (query intact); it never goes to an icon service.
 */
object ImageLinkPolicy {
    /** One plan for the entire message, before rendering its individual blocks: block ID → its image links. */
    fun plan(blocks: List<LinkTextBlock>, growing: Boolean): Map<Int, List<String>> {
        val seen = HashSet<String>()
        val result = LinkedHashMap<Int, MutableList<String>>()
        for (block in blocks) {
            for (url in links(block, growing = growing && block.id == blocks.lastOrNull()?.id)) {
                if (seen.size >= 3) return result
                if (seen.add(url)) result.getOrPut(block.id) { mutableListOf() }.add(url)
            }
        }
        return result
    }

    fun allows(url: String): Boolean {
        // Of the last path component, a trailing slash ignored.
        val last = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull { it.isNotEmpty() } ?: return false
        val pathExtension = if (last.contains('.')) last.substringAfterLast('.').lowercase() else ""
        return pathExtension in setOf("png", "jpg", "jpeg", "gif", "webp") &&
            LinkPreviewPolicy.isPublicWebURL(url, allowsTailnetImages = true)
    }

    /**
     * A growing block's last link may still gain a query or path suffix. Keep it as text until
     * another character, or settlement, proves it whole. Only prose blocks (paragraphs, items,
     * blockquotes, headings) are read.
     */
    fun links(block: LinkTextBlock, growing: Boolean): List<String> {
        val runs = block.prose ?: return emptyList()
        val total = runs.sumOf { it.text.length }
        val seen = HashSet<String>()
        val result = mutableListOf<String>()
        fun add(link: String, endsText: Boolean) {
            if (result.size >= 3 || !allows(link) || (growing && endsText) || !seen.add(link)) return
            result += link
        }
        var offset = 0
        for (run in runs) {
            val runEnd = offset + run.text.length
            if (run.link != null) {
                add(run.link, endsText = runEnd == total)
            } else if (!run.isCode && run.text.contains("://")) {
                for ((url, range) in LinkDetector.matches(run.text)) {
                    if (!run.text.substring(range.first, range.last + 1).contains("://")) continue
                    add(url, endsText = runEnd == total && range.last + 1 == run.text.length)
                }
            }
            offset = runEnd
        }
        return result
    }
}

/** Bounded direct-image reads for the Image Link Previews experiment. */
class ImageLinkPreviewClient(
    val cached: (String) -> Bitmap? = { null },
    val load: suspend (url: String, onSize: (AgentImageSize) -> Unit) -> Bitmap? = { _, _ -> null },
) {
    companion object Key : DependencyKey<ImageLinkPreviewClient> {
        override val liveValue: ImageLinkPreviewClient by lazy {
            val cache = dependency(DecodedImageCache)
            val server = LabLaunch.serverURL?.toHttpUrlOrNull()
            if (server != null) {
                // All lab image traffic stays on the fixture, never on a public host.
                val loader = ImageLinkLoader(cache, fetch = { request, onSize ->
                    val url = request.request.url
                    if (url.host != "images.example.com") throw AgentImageDownload.FailureException(AgentImageDownload.Failure.Failed)
                    val local = server.newBuilder().addPathSegments("lab/image-link").addPathSegment(url.pathSegments.last()).build()
                    AgentImageDownload.load(
                        request.copy(request = request.request.newBuilder().url(local).build()),
                        validators = null, maxBytes = ImageLinkLoader.MAX_BYTES, requiresImageContentType = true,
                        client = LinkPreviewTransport.client(request.request), onSize = onSize,
                    )
                })
                return@lazy ImageLinkPreviewClient(cached = { cache.image(ImageLinkLoader.key(it)) }, load = loader::load)
            }
            val loader = ImageLinkLoader.shared
            ImageLinkPreviewClient(cached = { cache.image(ImageLinkLoader.key(it)) }, load = loader::load)
        }

        override val testValue: ImageLinkPreviewClient get() = ImageLinkPreviewClient()
    }
}

/**
 * Reuses the metadata pipeline's request coalescing and the Markdown pixel cache. One download at a
 * time across hosts, at most three links per message.
 */
class ImageLinkLoader(
    private val cache: DecodedImageCache,
    private val deferred: () -> Boolean = { false },
    private val fetch: suspend (AgentImageRequest, (AgentImageSize) -> Unit) -> AgentImageResponse = { request, onSize ->
        AgentImageDownload.load(
            request, validators = null, maxBytes = MAX_BYTES, requiresImageContentType = true,
            client = LinkPreviewTransport.client(request.request), onSize = onSize,
        )
    },
) {
    private val queue = CoalescedReadQueue<Bitmap?, AgentImageSize>()
    /** URL → when it last failed (epoch ms). */
    private val failures = HashMap<String, Long>()

    suspend fun load(url: String, onSize: (AgentImageSize) -> Unit = {}): Bitmap? {
        if (!ImageLinkPolicy.allows(url)) return null
        val key = key(url)
        cache.image(key)?.let { return it }
        if (deferred() || !currentCoroutineContext().isActive) return null
        val lastFailure = synchronized(failures) { failures[url] }
        if (lastFailure != null && System.currentTimeMillis() - lastFailure < FAILURE_SUPPRESSION) return null
        return try {
            queue.load(key, onSize) { report -> resolve(url, report) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun resolve(url: String, onSize: (AgentImageSize) -> Unit): Bitmap? {
        currentCoroutineContext().ensureActive()
        val key = key(url)
        cache.image(key)?.let { return it }
        if (deferred()) return null
        val request = AgentImageRequest(
            Request.Builder()
                .url(url)
                .tag(LinkPreviewTimeout::class.java, LinkPreviewTimeout(10, TimeUnit.SECONDS))
                .header("Accept", "image/*")
                .build(),
            allowsConstrainedNetworkAccess = false,
        )
        return try {
            val response = NetworkRetry.policy.withValue(RetryPolicy.once) { fetch(request, onSize) }
            currentCoroutineContext().ensureActive()
            val data = (response as? AgentImageResponse.File)?.data
                ?: throw AgentImageDownload.FailureException(AgentImageDownload.Failure.NotImage)
            val image = AgentImagePreviews.decode(data, MAX_PIXEL_SIZE)
                ?: throw AgentImageDownload.FailureException(AgentImageDownload.Failure.NotImage)
            currentCoroutineContext().ensureActive()
            cache.insert(image, key)
            image
        } catch (error: AgentImageDownload.FailureException) {
            if (error.failure == AgentImageDownload.Failure.Constrained) return null
            currentCoroutineContext().ensureActive()
            failed(url)
        } catch (error: CancellationException) {
            // Off-screen cancellation and network deferrals never poison the next sighting.
            throw error
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            failed(url)
        }
    }

    private fun failed(url: String): Bitmap? {
        AppLog.network.debug("Image link preview unavailable")
        synchronized(failures) {
            failures[url] = System.currentTimeMillis()
            if (failures.size > 64) failures.minByOrNull { it.value }?.key?.let(failures::remove)
        }
        return null
    }

    companion object {
        /** Enough for the inline display at 3× without decoding a full-size original. */
        const val MAX_PIXEL_SIZE = 1200
        /** Direct image links read at most 25 MiB. */
        const val MAX_BYTES = 25 * 1024 * 1024
        /** A failed link isn't asked again for 30 s. */
        const val FAILURE_SUPPRESSION: Long = 30_000

        fun key(url: String): String = "image-link:$url"

        internal val shared: ImageLinkLoader by lazy {
            ImageLinkLoader(
                cache = dependency(DecodedImageCache),
                deferred = { NetworkPathMonitor.shared?.shouldDeferLargePrefetch == true },
            )
        }
    }
}
