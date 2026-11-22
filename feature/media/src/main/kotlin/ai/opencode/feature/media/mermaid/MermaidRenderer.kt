package ai.opencode.feature.media

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A Mermaid diagram drawn once per source and theme: its SVG, at its natural size in dp (padding
 * included).
 */
class MermaidDiagram(val svg: String, val width: Float, val height: Float)

sealed class MermaidRenderError : Exception() {
    /** Pele rejected the source (a syntax error): the same every time, so it's the only failure that's cached. */
    data object InvalidDiagram : MermaidRenderError()
    /**
     * The page went away mid-render (a timeout, or the renderer process ending under memory
     * pressure or in the background): worth a retry.
     */
    data object Interrupted : MermaidRenderError()
    data object Unavailable : MermaidRenderError()
    data object InvalidResult : MermaidRenderError()
    data object ImageFailed : MermaidRenderError()
}

/**
 * Renders Mermaid with the bundled `assets/mermaid/pele.global.js` (Pele 1.7.0, MIT) in one shared
 * offscreen [WebView], one diagram at a time: Pele lays the source out and writes its SVG
 * (theme-independent, kept by source with its natural size), the page themes it, and it's drawn at
 * whatever size a view asks for (the page scales the SVG; the web view draws into a bitmap).
 * Android has no in-process JavaScript engine, so Pele runs in the page and a size is synchronous
 * once the source has been prepared ([naturalSize]). The page is offline (a strict Content Security
 * Policy, network loads blocked, no navigation) and has no JavaScript interfaces: nothing in it can
 * reach the app; results are read back by polling the page. The web view goes [idleTimeout] after
 * the last render or drawing, and on memory pressure with the rasters. Main thread only.
 */
class MermaidRenderer private constructor() {
    /** Diagrams by source and theme. Small: SVGs of a few kilobytes. In insertion order, at most 64. */
    private val diagrams = LinkedHashMap<MermaidCacheKey, Result<MermaidDiagram>>()
    /** Pele's SVG and natural size by source hash (theme-independent). In insertion order, at most 64. */
    private val prepared = LinkedHashMap<String, Result<PreparedDiagram>>()
    private val running = HashMap<MermaidCacheKey, Deferred<MermaidDiagram>>()
    /** Renders (and drawings) wait for the one before them. */
    private val queue = Mutex()
    private val images = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var webView: WebView? = null
    private var pageLoad: CompletableDeferred<Unit>? = null
    /** The window to render in. */
    private var host: WeakReference<ViewGroup>? = null

    /** How long the web view outlives the last render. */
    var idleTimeout: Duration = 30.seconds
    private var idle: Job? = null
    /** Renders and drawings queued or under way. */
    private var pending = 0
    private var watchesMemory = false

    /** Whether the offscreen web view (and its renderer process) is up. */
    val hasWebView: Boolean get() = webView != null

    /**
     * Rasters and the page (Pele's JavaScript heap) go; drawn SVGs (a few kilobytes each) and
     * laid out sizes stay, so cards keep their height and redraw from the SVG.
     */
    fun purge() {
        images.evictAll()
        if (pending == 0) reset()
    }

    /**
     * The window the shared web view lives in, off screen: a web view in no window can have its
     * renderer deprioritised mid-render. Cards hand theirs over as they appear.
     */
    fun useHost(view: View) {
        val root = view.rootView as? ViewGroup ?: return
        if (host?.get() === root && webView?.parent === root) return
        host = WeakReference(root)
        val webView = webView ?: return
        // A new window (the activity recreated): the web view moves with it.
        if (webView.parent !== root && root.isAttachedToWindow) attach(webView, root)
    }

    // Cache

    fun cachedDiagram(source: String, theme: MermaidTheme): MermaidDiagram? =
        diagrams[MermaidCacheKey(source, theme)]?.getOrNull()

    /**
     * The natural size in dp (including the 16 dp card padding), so a diagram card lays out at
     * its final aspect height without a placeholder jump: a drawn diagram's, else Pele's for a
     * source it has prepared (in either theme).
     */
    fun naturalSize(source: String, theme: MermaidTheme): Size? {
        cachedDiagram(source, theme)?.let { return Size(it.width, it.height) }
        return prepared[MermaidSyntax.hash(source)]?.getOrNull()?.let { Size(it.width, it.height) }
    }

    /** Only a diagram Pele rejected; an interrupted render is tried again. */
    fun cachedFailure(source: String, theme: MermaidTheme): Boolean =
        diagrams[MermaidCacheKey(source, theme)]?.isFailure == true ||
            prepared[MermaidSyntax.hash(source)]?.exceptionOrNull() == MermaidRenderError.InvalidDiagram

    fun cachedImage(key: MermaidCacheKey): Bitmap? = images.get(key.string)

    /** The diagram drawn [width] × [height] dp (its natural aspect), cached by source, theme, and width. */
    suspend fun image(source: String, theme: MermaidTheme, width: Float, height: Float, density: Float): Bitmap =
        withContext(Dispatchers.Main.immediate) {
            val key = MermaidCacheKey(source, theme, width)
            cachedImage(key)?.let { return@withContext it }
            val diagram = diagram(source, theme)
            val image = rasterize(diagram, width, height, density, theme) ?: throw MermaidRenderError.ImageFailed
            images.put(key.string, image)
            image
        }

    /**
     * Sharp when zoomed: as many pixels as the viewer can use, within a 4096-pixel side and
     * 16 megapixels. [screenWidth] and [screenHeight] are in dp.
     */
    suspend fun viewerImage(source: String, theme: MermaidTheme, density: Float, screenWidth: Float, screenHeight: Float): Bitmap =
        withContext(Dispatchers.Main.immediate) {
            val diagram = diagram(source, theme)
            val longest = max(diagram.width, diagram.height) * density
            val wanted = max(longest * 3, max(screenWidth, screenHeight) * density * 2)
            var factor = min(wanted, 4096f) / longest
            val area = diagram.width * diagram.height * density * density * factor * factor
            if (area > 16_000_000f) factor *= sqrt(16_000_000f / area)
            val width = (diagram.width * factor).roundToInt().toFloat()
            val height = (diagram.height * factor).roundToInt().toFloat()
            rasterize(diagram, width, height, density, theme) ?: throw MermaidRenderError.ImageFailed
        }

    // Rendering

    suspend fun diagram(source: String, theme: MermaidTheme): MermaidDiagram = withContext(Dispatchers.Main.immediate) {
        val key = MermaidCacheKey(source, theme)
        diagrams[key]?.let { return@withContext it.getOrThrow() }
        val task = running[key] ?: scope.async {
            beginWork()
            val result = runCatching { queue.withLock { render(source, theme) } }
            endWork()
            running.remove(key)
            if (caches(result)) remember(result, key)
            result.getOrThrow()
        }.also { running[key] = it }
        task.await()
    }

    private fun beginWork() {
        pending += 1
        idle?.cancel()
    }

    private fun endWork() {
        pending -= 1
        if (pending == 0) scheduleIdle()
    }

    /** The web view goes once nothing has rendered or drawn for [idleTimeout]. */
    private fun scheduleIdle() {
        idle?.cancel()
        idle = scope.launch {
            delay(idleTimeout)
            if (pending == 0) reset()
        }
    }

    private fun remember(result: Result<MermaidDiagram>, key: MermaidCacheKey) {
        diagrams.remove(key)
        diagrams[key] = result
        while (diagrams.size > 64) diagrams.remove(diagrams.keys.first())
    }

    /** Pele's SVG and natural size (theme-independent). */
    private class PreparedDiagram(val svg: String, val width: Float, val height: Float)

    /** Pele's layout of [source], from the cache or the page; a rejection is cached. */
    private suspend fun prepare(webView: WebView, source: String): PreparedDiagram {
        val hash = MermaidSyntax.hash(source)
        prepared[hash]?.let { return it.getOrThrow() }
        val call = "(() => { try { return window.__renderPele(${JSONObject.quote(source)}); } " +
            "catch (e) { return { failed: true }; } })()"
        val job = evaluateJSON(webView, call)
        val svg = job.optString("svg")
        val width = job.optDouble("width", 0.0)
        val height = job.optDouble("height", 0.0)
        if (job.optBoolean("failed") || svg.isEmpty() || !(width > 0) || !(height > 0)) {
            rememberPrepared(Result.failure(MermaidRenderError.InvalidDiagram), hash)
            throw MermaidRenderError.InvalidDiagram
        }
        val fit = min(1.0, min(4000.0 / width, 4000.0 / height))
        val diagram = PreparedDiagram(svg, ceil(width * fit + 32).toFloat(), ceil(height * fit + 32).toFloat())
        rememberPrepared(Result.success(diagram), hash)
        return diagram
    }

    private fun rememberPrepared(result: Result<PreparedDiagram>, hash: String) {
        prepared.remove(hash)
        prepared[hash] = result
        while (prepared.size > 64) prepared.remove(prepared.keys.first())
    }

    private suspend fun render(source: String, theme: MermaidTheme): MermaidDiagram {
        val webView = readyWebView()
        val preparedDiagram = prepare(webView, source)
        val background = cssColor(background(theme))
        val job = evaluateJSON(
            webView,
            "(() => { try { return window.renderPeleSvg(${JSONObject.quote(preparedDiagram.svg)}, " +
                "'${if (theme == MermaidTheme.Dark) "dark" else "light"}', '$background'); } " +
                "catch (e) { return { failed: true }; } })()",
        )
        tidy(webView)
        if (job.optBoolean("failed") || !(job.optDouble("width", 0.0) > 0) || !(job.optDouble("height", 0.0) > 0)) {
            throw MermaidRenderError.InvalidResult
        }
        return MermaidDiagram(preparedDiagram.svg, preparedDiagram.width, preparedDiagram.height)
    }

    /**
     * Runs [script] in the page and reads its object result. A script stuck in the page takes the
     * page with it, which fails the call (interrupted) rather than leaving the queue waiting.
     */
    private suspend fun evaluateJSON(webView: WebView, script: String): JSONObject {
        val value = withTimeoutOrNull(RENDER_TIMEOUT_MS) { evaluate(webView, script) }
        if (value == null || this.webView !== webView) {
            if (value == null && this.webView === webView) {
                webView.stopLoading()
                reset()
            }
            throw MermaidRenderError.Interrupted
        }
        return runCatching { JSONObject(value) }.getOrNull() ?: JSONObject()
    }

    /** Draws [diagram] [width] × [height] dp big at [density], on the card's colour. */
    private suspend fun rasterize(diagram: MermaidDiagram, width: Float, height: Float, density: Float, theme: MermaidTheme): Bitmap? {
        if (width < 1 || height < 1) return null
        beginWork()
        try {
            return queue.withLock { draw(diagram, width, height, density, theme) }
        } finally {
            endWork()
        }
    }

    private suspend fun draw(diagram: MermaidDiagram, width: Float, height: Float, density: Float, theme: MermaidTheme): Bitmap? {
        val webView = runCatching { readyWebView() }.getOrNull() ?: return null
        val pixelWidth = (width * density).roundToInt().coerceAtLeast(1)
        val pixelHeight = (height * density).roundToInt().coerceAtLeast(1)
        val zoom = width / diagram.width
        val background = background(theme)
        val themeName = if (theme == MermaidTheme.Dark) "dark" else "light"
        evaluate(
            webView,
            "window.showDiagram(${JSONObject.quote(diagram.svg)}, '$themeName', '${cssColor(background)}', $zoom); 0",
        )
        // The capture covers the diagram, so the view must too.
        resize(webView, pixelWidth, pixelHeight)
        awaitVisualState(webView)
        if (this.webView !== webView) return null
        val bitmap = runCatching {
            Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                canvas.drawColor(background.toArgb())
                webView.draw(canvas)
            }
        }.getOrNull()
        tidy(webView)
        return bitmap
    }

    /**
     * Empties the page and shrinks the view between diagrams, so neither the last diagram's DOM
     * nor a large backing store outlives its render.
     */
    private suspend fun tidy(webView: WebView) {
        if (this.webView !== webView) return
        evaluate(webView, "document.getElementById('diagram').innerHTML = ''; 0")
        val resting = (RESTING_SIZE * webView.resources.displayMetrics.density).roundToInt()
        resize(webView, resting, resting)
    }

    private suspend fun resize(webView: WebView, width: Int, height: Int) {
        val params = webView.layoutParams
        if (params != null && params.width == width && params.height == height && webView.width == width && webView.height == height) return
        if (webView.parent != null) {
            webView.layoutParams = FrameLayout.LayoutParams(width, height)
            withTimeoutOrNull(LAYOUT_TIMEOUT_MS) {
                while (webView.width != width || webView.height != height) delay(POLL_MS)
            }
        }
        if (webView.width != width || webView.height != height) {
            webView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            webView.layout(webView.left, webView.top, webView.left + width, webView.top + height)
        }
    }

    /** Waits for the page's current content to be ready to draw (or a short while, off window). */
    private suspend fun awaitVisualState(webView: WebView) {
        withTimeoutOrNull(VISUAL_STATE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                webView.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                })
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun readyWebView(): WebView {
        val existing = webView
        val pending = pageLoad
        if (existing != null && pending != null) {
            pending.await()
            if (webView !== existing) throw MermaidRenderError.Interrupted
            return existing
        }
        val script = withContext(Dispatchers.IO) {
            runCatching {
                dependency(AppContext).assets.open("mermaid/pele.global.js").bufferedReader().use { it.readText() }
            }.getOrNull()
        } ?: throw MermaidRenderError.Unavailable
        val context = dependency(AppContext)
        watchMemory(context)
        val webView = WebView(context)
        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            blockNetworkImage = true
            domStorageEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        webView.isFocusable = false
        webView.isClickable = false
        webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        val load = CompletableDeferred<Unit>()
        webView.webViewClient = object : WebViewClient() {
            // Only the page itself, which is loaded from a string.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (request.url.toString().startsWith("data:")) null else WebResourceResponse("text/plain", "utf-8", null)

            override fun onPageFinished(view: WebView, url: String?) {
                if (load.isCompleted) return
                view.evaluateJavascript("$script\n;$PELE_BOOTSTRAP_SCRIPT\n;$RENDER_SCRIPT\n;0") { load.complete(Unit) }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) load.completeExceptionally(MermaidRenderError.Unavailable)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                AppLog.app.notice("Mermaid renderer ended crashed=${detail.didCrash()}")
                if (this@MermaidRenderer.webView === view) reset() else destroy(view)
                return true
            }
        }
        this.webView = webView
        pageLoad = load
        host?.get()?.takeIf { it.isAttachedToWindow }?.let { attach(webView, it) }
        val resting = (RESTING_SIZE * context.resources.displayMetrics.density).roundToInt()
        resize(webView, resting, resting)
        webView.loadDataWithBaseURL(null, PAGE, "text/html", "utf-8", null)
        try {
            load.await()
        } catch (error: Throwable) {
            if (this.webView === webView) reset()
            throw if (error is MermaidRenderError) error else MermaidRenderError.Unavailable
        }
        if (this.webView !== webView) throw MermaidRenderError.Interrupted
        return webView
    }

    /** Purges under memory pressure; registered once, with the first web view. */
    private fun watchMemory(context: android.content.Context) {
        if (watchesMemory) return
        watchesMemory = true
        // The application keeps the callbacks; the renderer lives as long as the app.
        context.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) scope.launch { purge() }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                scope.launch { purge() }
            }
        })
    }

    /** In a window but off screen. */
    private fun attach(webView: WebView, root: ViewGroup) {
        (webView.parent as? ViewGroup)?.removeView(webView)
        val resting = (RESTING_SIZE * webView.resources.displayMetrics.density).roundToInt()
        webView.translationX = OFFSCREEN_X
        root.addView(webView, 0, FrameLayout.LayoutParams(resting, resting))
    }

    private fun reset() {
        val webView = webView ?: return
        this.webView = null
        pageLoad?.let { if (!it.isCompleted) it.completeExceptionally(MermaidRenderError.Unavailable) }
        pageLoad = null
        destroy(webView)
    }

    private fun destroy(webView: WebView) {
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private suspend fun evaluate(webView: WebView, script: String): String? = suspendCancellableCoroutine { continuation ->
        webView.evaluateJavascript(script) { value ->
            if (continuation.isActive) continuation.resume(value?.takeIf { it != "null" })
        }
    }

    companion object {
        val shared: MermaidRenderer by lazy { MermaidRenderer() }

        private const val RENDER_TIMEOUT_MS = 15_000L
        private const val POLL_MS = 16L
        private const val LAYOUT_TIMEOUT_MS = 500L
        private const val VISUAL_STATE_TIMEOUT_MS = 1_000L
        private const val RESTING_SIZE = 320f
        private const val OFFSCREEN_X = -20_000f

        /**
         * A drawn diagram, or one Pele rejected. Interruptions, timeouts and a page that
         * wouldn't load are tried again on the next appearance.
         */
        fun caches(result: Result<MermaidDiagram>): Boolean =
            result.isSuccess || result.exceptionOrNull() == MermaidRenderError.InvalidDiagram

        /** The card's colour, drawn into the image so the image is the card. */
        fun background(theme: MermaidTheme): Color =
            if (theme == MermaidTheme.Dark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)

        private fun cssColor(color: Color): String {
            val argb = color.toArgb()
            return "#%02x%02x%02x".format((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
        }

        /** Pele's entry point: the SVG and natural size of one source, or a throw. */
        private const val PELE_BOOTSTRAP_SCRIPT = """
var __peleCount = 0;
function __renderPele(source) {
  __peleCount += 1;
  var result = pele.render(source, {
    idPrefix: 'mermaid-diagram-' + __peleCount,
    images: false,
    linkSchemes: ['http', 'https', 'mailto'],
    linkRel: 'noopener noreferrer',
  });
  if (!result || !result.svg || !(result.width > 0) || !(result.height > 0)) {
    throw new Error('No diagram');
  }
  return { svg: result.svg, width: result.width, height: result.height };
}
"""

        private const val PAGE = """<!doctype html>
<html><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'; style-src 'unsafe-inline'; img-src data:; font-src data:">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<style>
  html, body { margin: 0; padding: 0; overflow: hidden; }
  #diagram {
    display: inline-block;
    padding: 16px;
    line-height: 0;
    --pele-font: Roboto, "Noto Sans", sans-serif;
    --pele-font-mono: "Roboto Mono", "Droid Sans Mono", monospace;
    --pele-radius: 6px;
  }
  #diagram[data-theme="light"] {
    --pele-bg: #f2f2f7;
    --pele-fg: #1c1c1e;
    --pele-muted: #636366;
    --pele-line: #636366;
    --pele-surface: #ffffff;
    --pele-surface-alt: #e5e5ea;
    --pele-border: #c7c7cc;
    --pele-accent: #007aff;
    --pele-critical: #ff3b30;
    --pele-error: #ff3b30;
  }
  #diagram[data-theme="dark"] {
    --pele-bg: #1c1c1e;
    --pele-fg: #f2f2f7;
    --pele-muted: #98989d;
    --pele-line: #8e8e93;
    --pele-surface: #2c2c2e;
    --pele-surface-alt: #242426;
    --pele-border: #48484a;
    --pele-accent: #0a84ff;
    --pele-critical: #ff453a;
    --pele-error: #ff453a;
  }
  #diagram > svg { display: block; }
</style>
</head><body><div id="diagram"></div></body></html>"""

        /**
         * Themes one of Pele's SVGs in the page at its natural size, at most 4000 px a side, and
         * returns that size; [showDiagram] puts it back, themed and scaled, to capture.
         */
        private const val RENDER_SCRIPT = """
(() => {
  const place = (svg, theme, background) => {
    const host = document.getElementById('diagram');
    host.dataset.theme = theme === 'dark' ? 'dark' : 'light';
    host.style.setProperty('--pele-bg', background);
    host.style.zoom = '';
    document.documentElement.style.background = background;
    document.body.style.background = background;
    host.innerHTML = svg;
    const element = host.querySelector('svg');
    if (!element) throw new Error('No diagram');
    const box = element.viewBox && element.viewBox.baseVal;
    if (box && box.width > 0 && box.height > 0) {
      const fit = Math.min(1, 4000 / box.width, 4000 / box.height);
      element.setAttribute('width', box.width * fit);
      element.setAttribute('height', box.height * fit);
    }
    element.style.maxWidth = 'none';
    return host;
  };
  window.renderPeleSvg = (svg, theme, background) => {
    const rect = place(svg, theme, background).getBoundingClientRect();
    return { width: rect.width, height: rect.height };
  };
  window.showDiagram = (svg, theme, background, zoom) => {
    place(svg, theme, background).style.zoom = String(zoom);
  };
})();
"""
    }
}
