package ai.opencode.feature.media

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import ai.opencode.server.model.FileReadTarget
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap

/** Tests decode inline on the store's virtual scheduler; production never decodes on main. */
internal object VisualDecodeContext : ai.opencode.core.dependencies.DependencyKey<kotlin.coroutines.CoroutineContext> {
    override val liveValue = kotlinx.coroutines.Dispatchers.IO
    override val testValue = kotlin.coroutines.EmptyCoroutineContext
}

/** A cold description, resolved only while this page is selected. */
sealed interface SessionVisualImageSource {
    data class Markdown(val source: String, val directory: String?) : SessionVisualImageSource
    data class Link(val url: String) : SessionVisualImageSource
    data class Diagram(val source: String, val theme: MermaidTheme) : SessionVisualImageSource

    suspend fun load(server: OpenCodeServer?): Bitmap? = when (this) {
        is Markdown -> if (source.startsWith("https://", true) || source.startsWith("http://", true)) {
            val context = dependency(AppContext)
            val result = SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(source).build())
            (result as? SuccessResult)?.image?.toBitmap()
        } else server?.let {
            val fallback = AgentFileReference.fromLink(source)?.target(directory)?.let { target -> FileReadTarget(target.location, target.relativePath) }
            dependency(OpenCodeClient).markdownImage(it, directory, source, fallback)?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        }
        is Link -> dependency(ImageLinkPreviewClient).load(url) {}
        is Diagram -> {
            val resources = dependency(AppContext).resources
            MermaidRenderer.shared.viewerImage(source, theme, resources.displayMetrics.density,
                resources.configuration.screenWidthDp.toFloat(), resources.configuration.screenHeightDp.toFloat())
        }
    }
}
