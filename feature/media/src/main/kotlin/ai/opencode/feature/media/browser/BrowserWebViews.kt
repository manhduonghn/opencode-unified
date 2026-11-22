package ai.opencode.feature.media

import ai.opencode.server.model.PageShareRequest
import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.scene.ActiveScene
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.server.model.OpenCodePageContext
import android.annotation.SuppressLint
import android.content.Context
import android.app.Activity
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * The in-app browser's web views, for reducers: read the open page at send, navigate, open a link
 * outside the app, and discard a page's web content.
 */
class PageReaderClient(
    /**
     * The page's URL, title, selection, visible text, and scroll position, read now. A page that
     * was discarded (or failed to answer) still gives its URL and title.
     */
    val read: suspend (PageShareRequest) -> OpenCodePageContext,
    val goBack: suspend (UUID) -> Unit,
    val goForward: suspend (UUID) -> Unit,
    /** Back to the top of the page (the bar's address). */
    val scrollToTop: suspend (UUID) -> Unit,
    /**
     * Opens a link in whatever claims it, from the window with this scene ID (or the one in use),
     * so the app it opens appears beside that window.
     */
    val openExternal: suspend (url: String, sceneID: String?) -> Unit,
    /** Forgets the page entirely. */
    val discard: suspend (UUID) -> Unit,
) {
    companion object Key : DependencyKey<PageReaderClient> {
        override val liveValue: PageReaderClient by lazy {
            PageReaderClient(
                read = { request -> withContext(Dispatchers.Main) { BrowserWebViews.shared.read(request) } },
                goBack = { id -> withContext(Dispatchers.Main) { BrowserWebViews.shared.goBack(id) } },
                goForward = { id -> withContext(Dispatchers.Main) { BrowserWebViews.shared.goForward(id) } },
                scrollToTop = { id -> withContext(Dispatchers.Main) { BrowserWebViews.shared.scrollToTop(id) } },
                openExternal = { url, sceneID -> ActiveScene.open(url, sceneID) },
                discard = { id -> withContext(Dispatchers.Main) { BrowserWebViews.shared.discard(id) } },
            )
        }
        override val testValue = PageReaderClient(
            read = { OpenCodePageContext(url = it.url, title = it.title) },
            goBack = {},
            goForward = {},
            scrollToTop = {},
            openExternal = { _, _ -> throw AssertionError("PageReaderClient.openExternal is unimplemented") },
            discard = {},
        )
    }
}

/**
 * Owns each browser visit's [WebView], so the page (and its scroll position) outlives the
 * composable that shows it, which the session's page stack may rebuild. Main thread only.
 *
 * Privacy: every page shares one WebView profile (`opencode-browser`, when the WebView supports
 * profiles) whose cookies, storage and cache are cleared the first time a page opens in each run,
 * so cookies and logins last until the app quits and never mix with other web content: pages open
 * signed out and what the agent can read is public by default. Only public `http(s)` pages load
 * ([BrowserLinkPolicy]), in the page and in its frames; other links go to the system. Media waits
 * for a tap.
 *
 * Android's WebView has no content worlds, so the page reader runs in the page's world, wrapped in
 * a function that leaves no globals behind.
 */
class BrowserWebViews private constructor() {
    class Entry(var url: String, val id: UUID) {
        var webView: WebView? = null
        var onEvent: ((BrowserFeature.Action) -> Unit)? = null
        /** The window (task) showing the page, for links it hands to the system. */
        var sceneID: String? = null
        /** The host's hold menu: a link held in the page, at a point in the web view. */
        var onLinkHeld: ((url: String, x: Float, y: Float) -> Unit)? = null
        var canGoBack = false
        var canGoForward = false
        var lastTouchX = 0f
        var lastTouchY = 0f
        /** The context a page needs for its dialogs and pickers: the host's while shown. */
        val context = MutableContextWrapper(dependency(AppContext))
        /** The app's appearance, which the page reads as `prefers-color-scheme` (see [BrowserThemeContext]). */
        val themed = BrowserThemeContext(context)
        /** The back button above and the bar below, in dp. */
        var insets = BrowserInsets()
        /** The insets' document-start script, replaced when they change. */
        var insetsScript: ScriptHandler? = null
        /** The width the insets were last given for, in dp (desktop pages scale them by it). */
        var insetsWidth = 0f
        /** A video playing full screen, and how to end it. */
        var fullscreen: View? = null
        var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    }

    private val entries = HashMap<UUID, Entry>()
    private var clearedThisRun = false

    /**
     * The page's web view, made on first use. [isDark] is the app's appearance; later changes come
     * through [setAppearance].
     */
    fun webView(id: UUID, url: String, isDark: Boolean = false): WebView {
        val entry = entries.getOrPut(id) { Entry(url, id) }
        entry.webView?.let { return it }
        entry.themed.isDark = isDark
        val webView = makeWebView(entry)
        entry.webView = webView
        webView.loadUrl(entry.url)
        return webView
    }

    /** Where the page's title, address, and loading state go while a view shows it. Null detaches. */
    fun setEventHandler(id: UUID, handler: ((BrowserFeature.Action) -> Unit)?) {
        entries[id]?.onEvent = handler
    }

    fun setLinkHeldHandler(id: UUID, handler: ((String, Float, Float) -> Unit)?) {
        entries[id]?.onLinkHeld = handler
    }

    /**
     * The app's appearance changed: the open page's `prefers-color-scheme` follows without a reload
     * (see [BrowserThemeContext]).
     */
    fun setAppearance(id: UUID, isDark: Boolean) {
        val entry = entries[id] ?: return
        if (entry.themed.isDark == isDark) return
        entry.themed.isDark = isDark
        entry.webView?.let { it.dispatchConfigurationChanged(it.resources.configuration) }
    }

    /**
     * The back button above and the bar below: the page draws edge to edge and scrolls under them,
     * and its first and last pixels still scroll clear of them.
     */
    fun setObscuredInsets(id: UUID, insets: BrowserInsets) {
        val entry = entries[id] ?: return
        val clamped = BrowserInsets.obscured(insets)
        if (entry.insets == clamped) return
        entry.insets = clamped
        entry.webView?.let { installInsets(it, entry) }
    }

    /** Pages held now: zero once every session has let go. */
    val entryCount: Int get() = entries.size
    fun hasEventHandler(id: UUID): Boolean = entries[id]?.onEvent != null

    /** Off screen, nothing plays. */
    fun pauseMedia(id: UUID) {
        val webView = entries[id]?.webView ?: return
        webView.evaluateJavascript(PAUSE_MEDIA_SCRIPT, null)
        webView.onPause()
    }

    /** Back on screen. */
    fun resume(id: UUID) {
        entries[id]?.webView?.onResume()
    }

    fun goBack(id: UUID) {
        entries[id]?.webView?.takeIf { it.canGoBack() }?.goBack()
    }

    fun goForward(id: UUID) {
        entries[id]?.webView?.takeIf { it.canGoForward() }?.goForward()
    }

    fun scrollToTop(id: UUID) {
        entries[id]?.webView?.pageUp(true)
    }

    fun discard(id: UUID) {
        val entry = entries.remove(id) ?: return
        exitFullscreen(entry)
        entry.webView?.let { tearDown(it, entry) }
    }

    /** The window (task) showing the page now, for the links it hands to the system. */
    fun noteScene(id: UUID, sceneID: String?) {
        entries[id]?.sceneID = sceneID
    }

    /** Lends the page the host's context (dialogs, pickers) while shown; the app's otherwise. */
    fun setHostContext(id: UUID, context: Context?) {
        val entry = entries[id] ?: return
        entry.context.baseContext = context ?: dependency(AppContext)
    }

    suspend fun read(request: PageShareRequest): OpenCodePageContext {
        var context = OpenCodePageContext(url = request.url, title = request.title)
        val webView = entries[request.pageID]?.webView ?: return context
        val raw = evaluate(webView, "(function(limit) {\n$READER_SCRIPT\n})(${OpenCodePageContext.VISIBLE_TEXT_LIMIT})")
        val result = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (result != null) {
            result.optString("url").takeIf { it.isNotEmpty() && BrowserLinkPolicy.isPublicWebPage(it) }
                ?.let { context = context.copy(url = it) }
            result.optString("title").trim().takeIf { it.isNotEmpty() }?.let { context = context.copy(title = it) }
            context = context.copy(
                visibleText = if (result.has("visible") && !result.isNull("visible")) result.optString("visible") else null,
                selection = result.optString("selection").takeIf { it.isNotEmpty() },
                scroll = if (result.has("scroll") && !result.isNull("scroll")) result.optDouble("scroll") else null,
            )
        }
        return context.bounded
    }

    // Back swipe: the session stack's back gesture, while the page has history (`canGoBack`),
    // drives these; from the first page the stack pops instead, so there's only ever one back
    // gesture.

    /** The page follows the back gesture, [fraction] of its width. */
    fun backProgress(id: UUID, fraction: Float) {
        val webView = entries[id]?.webView ?: return
        webView.animate().cancel()
        webView.translationX = fraction.coerceIn(0f, 1f) * webView.width
    }

    /** The back gesture let go short: the page settles back. */
    fun backCancelled(id: UUID) {
        val webView = entries[id]?.webView ?: return
        webView.animate().translationX(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }

    /** The back gesture committed: the page slides away and the page before comes in from beneath. */
    fun backCommitted(id: UUID, reduceMotion: Boolean) {
        val webView = entries[id]?.webView ?: return
        webView.animate().cancel()
        if (reduceMotion) {
            webView.translationX = 0f
            arrive(webView, reduceMotion = true)
            return
        }
        webView.animate().translationX(webView.width.toFloat()).setDuration(220).setInterpolator(DecelerateInterpolator())
            .withEndAction { arrive(webView, reduceMotion = false) }.start()
    }

    /** The page before comes in from beneath, as a pop reveals its parent, on the release role. */
    private fun arrive(webView: WebView, reduceMotion: Boolean) {
        if (webView.canGoBack()) webView.goBack()
        val duration = Motion.millis(MotionRole.Release.curve(reduceMotion = reduceMotion).duration).toLong()
        if (reduceMotion) {
            webView.translationX = 0f
            webView.alpha = 0.4f
            webView.animate().alpha(1f).setDuration(duration).start()
            return
        }
        webView.translationX = -webView.width * PARALLAX
        webView.alpha = 0.6f
        webView.animate().translationX(0f).alpha(1f).setDuration(duration).setInterpolator(DecelerateInterpolator(1.5f)).start()
    }

    // Hold menu

    /** The hold menu's preview was tapped: what a tap on the link would do. */
    fun commitLinkPreview(id: UUID, url: String) {
        val entry = entries[id] ?: return
        when (val decision = BrowserNavigationPolicy.decide(url, isMainFrame = true, isUserTap = true)) {
            BrowserNavigationPolicy.Decision.Allow -> entry.webView?.loadUrl(url)
            BrowserNavigationPolicy.Decision.Cancel -> Unit
            is BrowserNavigationPolicy.Decision.Pair ->
                entry.onEvent?.invoke(BrowserFeature.Action.PairingLinkTapped(decision.url))
            is BrowserNavigationPolicy.Decision.OpenInSystem -> requestExternal(entry, decision.url)
        }
    }

    /** One of the hold menu's actions, on the link's shareable form. */
    fun performLinkMenu(id: UUID, action: LinkMenuAction, url: String) {
        val entry = entries[id] ?: return
        val shareURL = OpenCodePairingLink.shareableURL(url) ?: return
        if (action == LinkMenuAction.Share) dependency(LinkShareSheet).present(shareURL)
        entry.onEvent?.invoke(BrowserFeature.Action.LinkMenu(action, shareURL))
    }

    /**
     * What holding [url] in a page offers. A pairing link (valid or malformed) gets a static,
     * non-fetching card and only Copy and Share (or nothing); other links get the reply links' menu
     * without Open in App Browser (they're already in it). Android's WebView has none.
     */
    fun linkMenuActions(url: String): List<LinkMenuAction> {
        val noExperiments = ExperimentalFeatures { false }
        if (OpenCodePairingLink.shouldHandleOpenURL(url)) {
            return if (OpenCodePairingLink.shareableURL(url) != null) LinkMenuAction.actions(url, noExperiments) else emptyList()
        }
        return LinkMenuAction.actions(url, noExperiments)
    }

    // Web view

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun makeWebView(entry: Entry): WebView {
        val profile = prepareProfile()
        // Android's WebView reads the page's colour scheme from its context theme's `isLightTheme`.
        val webView = WebView(entry.themed)
        if (profile != null) WebViewCompat.setProfile(webView, profile)
        if (!clearedThisRun) {
            clearedThisRun = true
            // The previous run's cache goes too (cookies and storage went with the profile).
            webView.clearCache(true)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
            // Links that ask for a new window open in this one ([Chrome.onCreateWindow]).
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        // Shows a page in dark only when the page itself supports it (no algorithmic darkening).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
        }
        webView.isVerticalScrollBarEnabled = true
        webView.contentDescription = null
        if (isDebuggable) WebView.setWebContentsDebuggingEnabled(true)
        webView.webViewClient = Client(entry)
        webView.webChromeClient = Chrome(entry, profile)
        installInsets(webView, entry)
        // Desktop pages scale the insets by the web view's width: a new width gives them again.
        webView.addOnLayoutChangeListener { view, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft && entry.webView === view) installInsets(view as WebView, entry)
        }
        // Downloads and anything the page can't show go to the system.
        webView.setDownloadListener { url, _, contentDisposition, _, _ ->
            if (BrowserNavigationPolicy.handsToSystem(canShow = false, disposition = contentDisposition) &&
                BrowserLinkPolicy.isPublicWebPage(url)
            ) requestExternal(entry, url)
        }
        webView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                entry.lastTouchX = event.x
                entry.lastTouchY = event.y
            }
            false
        }
        webView.setOnLongClickListener { view ->
            val result = (view as WebView).hitTestResult
            when (result.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                    val url = result.extra ?: return@setOnLongClickListener false
                    entry.onLinkHeld?.invoke(url, entry.lastTouchX, entry.lastTouchY) ?: return@setOnLongClickListener false
                    true
                }
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    val handler = entry.onLinkHeld ?: return@setOnLongClickListener false
                    val x = entry.lastTouchX
                    val y = entry.lastTouchY
                    val message = Handler(Looper.getMainLooper()) { msg: Message ->
                        msg.data.getString("url")?.takeIf { it.isNotEmpty() }?.let { handler(it, x, y) }
                        true
                    }.obtainMessage()
                    view.requestFocusNodeHref(message)
                    true
                }
                else -> false
            }
        }
        return webView
    }

    /** The browser's own profile, cleared once per run. Null where the WebView has no profiles. */
    private fun prepareProfile(): String? {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            if (!clearedThisRun) {
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                android.webkit.WebStorage.getInstance().deleteAllData()
            }
            return null
        }
        val profile = ProfileStore.getInstance().getOrCreateProfile(PROFILE_NAME)
        if (!clearedThisRun) {
            profile.cookieManager.removeAllCookies(null)
            profile.webStorage.deleteAllData()
        }
        return PROFILE_NAME
    }

    private fun tearDown(webView: WebView, entry: Entry) {
        entry.onEvent = null
        entry.onLinkHeld = null
        entry.insetsScript?.remove()
        entry.insetsScript = null
        webView.evaluateJavascript(PAUSE_MEDIA_SCRIPT, null)
        webView.stopLoading()
        webView.setDownloadListener(null)
        webView.webChromeClient = null
        // A page sliding off the stack keeps drawing until its view goes
        // (the host's `onRelease` removes it then).
        if (!webView.isAttachedToWindow) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        } else {
            webView.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: android.view.View) = Unit
                override fun onViewDetachedFromWindow(v: android.view.View) {
                    webView.removeOnAttachStateChangeListener(this)
                    webView.destroy()
                }
            })
        }
        entry.webView = null
    }

    /** A link for the system: the reducer opens it, beside the page's window. */
    private fun requestExternal(entry: Entry, url: String) {
        entry.onEvent?.invoke(BrowserFeature.Action.ExternalURLRequested(url, entry.sceneID))
    }

    private fun emitHistory(entry: Entry, view: WebView) {
        val back = view.canGoBack()
        val forward = view.canGoForward()
        if (back != entry.canGoBack) {
            entry.canGoBack = back
            entry.onEvent?.invoke(BrowserFeature.Action.CanGoBackChanged(back))
        }
        if (forward != entry.canGoForward) {
            entry.canGoForward = forward
            entry.onEvent?.invoke(BrowserFeature.Action.CanGoForwardChanged(forward))
        }
    }

    private inner class Client(private val entry: Entry) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val decision = BrowserNavigationPolicy.decide(
                request.url?.toString(),
                isMainFrame = request.isForMainFrame,
                isUserTap = request.hasGesture() && !request.isRedirect,
            )
            return when (decision) {
                BrowserNavigationPolicy.Decision.Allow -> false
                BrowserNavigationPolicy.Decision.Cancel -> true
                is BrowserNavigationPolicy.Decision.Pair -> {
                    entry.onEvent?.invoke(BrowserFeature.Action.PairingLinkTapped(decision.url))
                    true
                }
                is BrowserNavigationPolicy.Decision.OpenInSystem -> {
                    // A private host, a signed link, or another app's link the reader tapped.
                    requestExternal(entry, decision.url)
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            entry.onEvent?.invoke(BrowserFeature.Action.LoadingChanged(true))
        }

        override fun onPageFinished(view: WebView, url: String?) {
            entry.onEvent?.invoke(BrowserFeature.Action.LoadingChanged(false))
            emitHistory(entry, view)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (url != null) {
                entry.url = url
                entry.onEvent?.invoke(BrowserFeature.Action.Navigated(url, view.title ?: ""))
            }
            emitHistory(entry, view)
        }

        /**
         * Embedded frames' first loads, which `shouldOverrideUrlLoading` skips. Only frame
         * navigations carry `Upgrade-Insecure-Requests`, so this judges just those; a blocked one
         * answers 204, leaving the frame as it was. Runs off the main thread.
         */
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.isForMainFrame || !isFrameNavigation(request)) return null
            val decision = BrowserNavigationPolicy.decide(request.url?.toString(), isMainFrame = false, isUserTap = false)
            return if (decision == BrowserNavigationPolicy.Decision.Allow) null else cancelledFrame()
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            // Without document-start scripts, the insets arrive once the page commits.
            if (!documentStartScripts) view.evaluateJavascript(insetsScript(entry), null)
        }

        /** The renderer went away (memory pressure): the page loads again in a fresh web view. */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (entries[entry.id] !== entry || entry.webView !== view) {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                return true
            }
            val parent = view.parent as? ViewGroup
            val index = parent?.indexOfChild(view) ?: -1
            val layoutParams = view.layoutParams
            parent?.removeView(view)
            exitFullscreen(entry)
            entry.insetsScript = null
            view.destroy()
            val fresh = makeWebView(entry)
            entry.webView = fresh
            fresh.loadUrl(entry.url)
            if (parent != null) parent.addView(fresh, index, layoutParams)
            return true
        }
    }

    private inner class Chrome(private val entry: Entry, private val profile: String?) : WebChromeClient() {
        override fun onReceivedTitle(view: WebView, title: String?) {
            entry.onEvent?.invoke(BrowserFeature.Action.TitleChanged(title ?: ""))
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (newProgress >= 100) entry.onEvent?.invoke(BrowserFeature.Action.LoadingChanged(false))
        }

        /**
         * Links that ask for a new window open in this one, if they may load at all. WebView only
         * tells the web view that takes the window its address, so a throwaway one hears it and is
         * let go.
         */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val probe = WebView(view.context)
            if (profile != null) WebViewCompat.setProfile(probe, profile)
            probe.webViewClient = object : WebViewClient() {
                private var heard = false

                private fun heard(window: WebView, url: String?) {
                    if (heard) return
                    heard = true
                    window.stopLoading()
                    if (url != null && BrowserLinkPolicy.isPublicWebPage(url) && entry.webView === view) view.loadUrl(url)
                    window.post { window.destroy() }
                }

                override fun shouldOverrideUrlLoading(window: WebView, request: WebResourceRequest): Boolean {
                    heard(window, request.url?.toString())
                    return true
                }

                override fun onPageStarted(window: WebView, url: String?, favicon: Bitmap?) = heard(window, url)
            }
            transport.webView = probe
            resultMsg.sendToTarget()
            return true
        }

        /** A video going full screen: over the whole window. */
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            val activity = activityOf(entry.context.baseContext)
            if (activity == null || entry.fullscreen != null) {
                callback.onCustomViewHidden()
                return
            }
            view.setBackgroundColor(Color.BLACK)
            (activity.window.decorView as ViewGroup).addView(
                view,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            activity.window.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars())
            }
            entry.fullscreen = view
            entry.fullscreenCallback = callback
        }

        override fun onHideCustomView() = exitFullscreen(entry)
    }

    /** Ends a full-screen video. */
    private fun exitFullscreen(entry: Entry) {
        val view = entry.fullscreen ?: return
        val callback = entry.fullscreenCallback
        entry.fullscreen = null
        entry.fullscreenCallback = null
        (view.parent as? ViewGroup)?.removeView(view)
        activityOf(entry.context.baseContext)?.window?.insetsController?.show(WindowInsets.Type.systemBars())
        callback?.onCustomViewHidden()
    }

    private fun activityOf(context: Context): Activity? {
        var current: Context? = context
        while (current != null) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }

    /**
     * Gives the page its obscured insets: a document-start script for every page, and the open page
     * now. WebView has no content insets, so the page's root gets them as border-box padding and
     * scroll padding.
     */
    private fun installInsets(webView: WebView, entry: Entry) {
        val metrics = webView.resources.displayMetrics
        entry.insetsWidth = (if (webView.width > 0) webView.width else metrics.widthPixels) / metrics.density
        val script = insetsScript(entry)
        if (documentStartScripts) {
            entry.insetsScript?.remove()
            entry.insetsScript = WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
        }
        webView.evaluateJavascript(script, null)
    }

    private fun insetsScript(entry: Entry): String {
        val insets = entry.insets
        fun number(value: Float) = String.format(Locale.ROOT, "%.2f", value)
        return "($INSETS_SCRIPT)(${number(insets.top)},${number(insets.left)},${number(insets.bottom)},${number(insets.right)},${number(entry.insetsWidth)});"
    }

    private val documentStartScripts: Boolean by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }

    private fun isFrameNavigation(request: WebResourceRequest): Boolean =
        request.requestHeaders.entries.any { (name, value) -> name.equals("Upgrade-Insecure-Requests", ignoreCase = true) && value.trim() == "1" }

    private fun cancelledFrame(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private suspend fun evaluate(webView: WebView, script: String): String? = suspendCancellableCoroutine { continuation ->
        webView.evaluateJavascript(script) { value ->
            if (continuation.isActive) continuation.resume(value?.takeIf { it != "null" })
        }
    }

    companion object {
        /** Process-wide; reducers reach it only through [PageReaderClient]. */
        val shared: BrowserWebViews by lazy { BrowserWebViews() }

        private const val PROFILE_NAME = "opencode-browser"
        private const val PARALLAX = 0.3f

        private val isDebuggable: Boolean
            get() = dependency(AppContext).applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

        /**
         * The obscured insets on the page's root (main frame only), in dp scaled to the page's
         * layout width (a desktop page laid out wider than the screen gets proportionally more),
         * applied as soon as the root exists and again as the page loads and resizes. State lives
         * on a registry symbol so a later call updates it without stacking listeners.
         */
        private val INSETS_SCRIPT = """
            function(t, l, b, r, w) {
              if (window.top !== window) return;
              var key = Symbol.for('opencode.browser.insets');
              var s = window[key];
              if (!s) {
                s = { t: 0, l: 0, b: 0, r: 0, w: 0 };
                Object.defineProperty(window, key, { value: s });
                s.apply = function() {
                  var root = document.documentElement;
                  if (!root) return;
                  var k = s.w > 0 ? Math.max(1, root.clientWidth / s.w) : 1;
                  var style = root.style;
                  style.setProperty('box-sizing', 'border-box', 'important');
                  style.setProperty('padding-top', (s.t * k) + 'px', 'important');
                  style.setProperty('padding-left', (s.l * k) + 'px', 'important');
                  style.setProperty('padding-bottom', (s.b * k) + 'px', 'important');
                  style.setProperty('padding-right', (s.r * k) + 'px', 'important');
                  style.setProperty('scroll-padding-top', (s.t * k) + 'px', 'important');
                  style.setProperty('scroll-padding-bottom', (s.b * k) + 'px', 'important');
                };
                if (!document.documentElement) {
                  new MutationObserver(function(_, observer) {
                    if (document.documentElement) { observer.disconnect(); s.apply(); }
                  }).observe(document, { childList: true });
                }
                document.addEventListener('DOMContentLoaded', s.apply);
                window.addEventListener('load', s.apply);
                window.addEventListener('resize', s.apply);
              }
              s.t = t; s.l = l; s.b = b; s.r = r; s.w = w;
              s.apply();
            }
        """.trimIndent()

        private const val PAUSE_MEDIA_SCRIPT =
            "document.querySelectorAll('video,audio').forEach(function(m){try{m.pause()}catch(e){}});"

        /**
         * Visible text (no form fields, hidden, or scripted content), the selection unless it's in
         * a form field, and the scroll position. WebView has no isolated content world, so it runs
         * in a function and leaves no globals behind.
         */
        val READER_SCRIPT = """
            const skip = 'input,textarea,select,option,button,script,style,noscript,template,svg,canvas,iframe,[contenteditable=""],[contenteditable="true"],[aria-hidden="true"],[type="password"]';
            const top = 0, bottom = window.innerHeight;
            const walker = document.createTreeWalker(document.body || document.documentElement, NodeFilter.SHOW_TEXT, {
              acceptNode(node) {
                const element = node.parentElement;
                if (!element || element.closest(skip) || !node.textContent.trim()) return NodeFilter.FILTER_REJECT;
                return NodeFilter.FILTER_ACCEPT;
              }
            });
            const range = document.createRange();
            const parts = [];
            let lastBlock = null, length = 0;
            while (walker.nextNode()) {
              const node = walker.currentNode;
              range.selectNodeContents(node);
              const rect = range.getBoundingClientRect();
              if (rect.width === 0 || rect.height === 0 || rect.bottom <= top || rect.top >= bottom) continue;
              const style = getComputedStyle(node.parentElement);
              if (style.visibility === 'hidden' || style.display === 'none' || Number(style.opacity) === 0) continue;
              const block = node.parentElement.closest('p,li,h1,h2,h3,h4,h5,h6,pre,blockquote,td,th,dd,dt,figcaption,caption,label,div,section,article,main');
              const text = node.textContent.replace(/\s+/g, ' ').trim();
              if (parts.length) parts.push(block === lastBlock ? ' ' : '\n');
              parts.push(text);
              lastBlock = block;
              length += text.length;
              if (length > limit * 2) break;
            }
            let selection = '';
            const picked = window.getSelection();
            if (picked && picked.rangeCount && !picked.isCollapsed) {
              const anchor = picked.anchorNode && (picked.anchorNode.nodeType === 1 ? picked.anchorNode : picked.anchorNode.parentElement);
              const active = document.activeElement;
              if (!(anchor && anchor.closest(skip)) && !(active && active.matches('input,textarea,[contenteditable]'))) selection = picked.toString();
            }
            const scrollable = Math.max(1, document.documentElement.scrollHeight - window.innerHeight);
            return { url: location.href, title: document.title, visible: parts.join(''), selection, scroll: Math.min(1, Math.max(0, window.scrollY / scrollable)) };
        """.trimIndent()
    }
}

/**
 * The page's obscured insets in dp: the back button's row above, the bar (and Ask, and the
 * keyboard) below, and the side safe areas.
 */
data class BrowserInsets(val top: Float = 0f, val left: Float = 0f, val bottom: Float = 0f, val right: Float = 0f) {
    companion object {
        /**
         * Never negative (mid-rotation or mid-drawer-slide the safe area can briefly report one).
         */
        fun obscured(insets: BrowserInsets) = BrowserInsets(
            top = maxOf(0f, insets.top),
            left = maxOf(0f, insets.left),
            bottom = maxOf(0f, insets.bottom),
            right = maxOf(0f, insets.right),
        )
    }
}

/**
 * The browser's context theme, light or dark as the app is. Chromium rereads `isLightTheme` on a
 * configuration change, so switching [isDark] updates `prefers-color-scheme` in place. The base
 * stays the page's [MutableContextWrapper], so dialogs use the host's window.
 */
class BrowserThemeContext(base: Context) : ContextThemeWrapper(base, 0) {
    var isDark = false
    private var themes: Pair<Resources, Array<Resources.Theme?>>? = null

    override fun getTheme(): Resources.Theme {
        val resources = resources
        val cached = themes?.takeIf { it.first === resources }?.second ?: arrayOfNulls<Resources.Theme>(2).also { themes = resources to it }
        val index = if (isDark) 1 else 0
        return cached[index] ?: resources.newTheme().apply {
            applyStyle(if (isDark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar, true)
        }.also { cached[index] = it }
    }
}
