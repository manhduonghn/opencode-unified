package ai.opencode.feature.media

import ai.opencode.server.OpenCodePairingLink
import ai.opencode.server.model.AgentFileReference
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.IDN

/** Where a link tapped in a reply goes. URLs are strings. */
sealed interface BrowserLinkRoute {
    /** A file the agent named: the file viewer. */
    data class File(val reference: AgentFileReference) : BrowserLinkRoute

    /** A public web page, with the in-app browser on. */
    data class InAppBrowser(val url: String) : BrowserLinkRoute

    /** Everything else, as before: the browser or the app that owns the link. */
    data object System : BrowserLinkRoute
}

/**
 * Which links the in-app browser takes: public `http(s)` pages only. Local and private-network
 * hosts, links with credentials, and signed links (whose query is a bearer secret) keep opening the
 * way they always have, so the browser, and the page context it sends, never touch them.
 */
object BrowserLinkPolicy {
    fun route(url: String, inAppBrowser: Boolean): BrowserLinkRoute {
        AgentFileReference.fromLinkURL(url)?.let { return BrowserLinkRoute.File(it) }
        if (!inAppBrowser || !isPublicWebPage(url)) return BrowserLinkRoute.System
        return BrowserLinkRoute.InAppBrowser(url)
    }

    /**
     * Loopback pages the session lab serves count as public in debug lab runs
     * (`debug.opencode.lab_browser_loopback`, debuggable builds only).
     */
    val allowsLoopbackForLab: Boolean by lazy {
        if (!isDebuggable) return@lazy false
        System.getenv("OPENCODE_LAB_BROWSER_LOOPBACK") == "1" || systemProperty("debug.opencode.lab_browser_loopback") == "1"
    }

    /**
     * Public pages under the same host and credential rules as link previews
     * ([LinkPreviewPolicy]: no IP literals, single-label, local, or tailnet hosts, trailing dots
     * folded, no credentials or signed queries). Unlike a preview, any port may load.
     */
    fun isPublicWebPage(url: String, allowsLoopback: Boolean = allowsLoopbackForLab): Boolean =
        LinkPreviewPolicy.isPublicWebURL(url, allowsCustomPort = true, allowsLoopback = allowsLoopback)

    fun isLoopback(host: String): Boolean =
        host.trim('.') in setOf("localhost", "127.0.0.1", "::1", "[::1]")

    /**
     * The host a page is named by: Unicode rather than punycode, without `www.`; null when the
     * URL has none. The browser's bar and the link menu's header share it.
     */
    fun displayHost(url: String): String? {
        val parsed = url.toHttpUrlOrNull()?.host ?: return null
        val host = runCatching { IDN.toUnicode(parsed) }.getOrDefault(parsed)
        if (host.isEmpty()) return null
        return if (host.startsWith("www.")) host.drop(4) else host
    }

    private val isDebuggable: Boolean
        get() = runCatching {
            val context = ai.opencode.core.dependencies.dependency(ai.opencode.core.dependencies.AppContext)
            context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        }.getOrDefault(false)

    @Suppress("PrivateApi")
    private fun systemProperty(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String
    }.getOrNull()
}

/**
 * What the page's own navigations may do: public pages load, and nothing else ever loads in the
 * browser, in the page or in its frames. A link the reader taps to a private host or another app
 * goes to the system; one a script or a redirect follows is cancelled.
 */
object BrowserNavigationPolicy {
    sealed interface Decision {
        data object Allow : Decision
        data object Cancel : Decision
        data class OpenInSystem(val url: String) : Decision
        data class Pair(val url: String) : Decision
    }

    fun decide(
        url: String?,
        isMainFrame: Boolean,
        isUserTap: Boolean,
        allowsLoopback: Boolean = BrowserLinkPolicy.allowsLoopbackForLab,
    ): Decision {
        if (url == null) return Decision.Cancel
        val scheme = schemeOf(url)?.lowercase() ?: return Decision.Cancel
        if (OpenCodePairingLink.shouldHandleOpenURL(url)) {
            return if (isMainFrame && isUserTap) Decision.Pair(url) else Decision.Cancel
        }
        return when (scheme) {
            "about" -> if (!isMainFrame || url in setOf("about:blank", "about:srcdoc")) Decision.Allow else Decision.Cancel
            "data", "blob" -> if (isMainFrame) Decision.Cancel else Decision.Allow
            "http", "https" ->
                if (BrowserLinkPolicy.isPublicWebPage(url, allowsLoopback)) Decision.Allow else system(url, isMainFrame, isUserTap)
            else -> system(url, isMainFrame, isUserTap)
        }
    }

    private fun system(url: String, isMainFrame: Boolean, isUserTap: Boolean): Decision =
        if (isMainFrame && isUserTap) Decision.OpenInSystem(url) else Decision.Cancel

    /** A response the page can't show, or a download, belongs to the system. */
    fun handsToSystem(canShow: Boolean, disposition: String?): Boolean =
        !canShow || disposition?.trim(' ', '\t')?.lowercase()?.startsWith("attachment") == true

    /** `URL.scheme`: letters, digits, `+`, `-`, `.` before the first colon, starting with a letter. */
    private fun schemeOf(url: String): String? {
        val colon = url.indexOf(':')
        if (colon <= 0) return null
        val scheme = url.substring(0, colon)
        if (!scheme[0].isLetter() || !scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
        return scheme
    }
}
