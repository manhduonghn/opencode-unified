package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.feature.media.BrowserLinkPolicy
import ai.opencode.feature.media.LinkMenuAction
import ai.opencode.feature.media.LinkMenuText
import ai.opencode.feature.media.SiteIconDomain
import ai.opencode.server.model.AgentFileReference
import ai.opencode.server.model.SessionLink

/**
 * The private and web links rendered Markdown carries, as the renderer reads them. The link formats
 * live in `:server` (`SessionLink`, `AgentFileReference`); the menu's rules in `:feature:media`
 * (`LinkMenuAction`, `LinkMenuText`, `BrowserLinkPolicy`, `SiteIconDomain`).
 */
object MarkdownLinks {
    fun sessionID(url: String): String? = SessionLink.id(url)

    fun sessionTitle(url: String): String? = SessionLink.title(url)

    fun sessionURL(id: String, title: String? = null): String = SessionLink.url(id, title)

    fun shortID(id: String): String = SessionLink.shortID(id)

    /** `AgentFileReference(linkURL:)`: `opencode-file://open?path=…&line=…`. */
    fun fileReference(url: String): AgentFileReference? = AgentFileReference.fromLinkURL(url)

    fun isWeb(url: String): Boolean {
        val scheme = url.substringBefore(':', "").lowercase()
        return scheme == "http" || scheme == "https"
    }

    /** Only web links, session links and valid pairing links get the hold menu; file links keep opening the viewer. */
    fun offersMenu(url: String): Boolean = LinkMenuAction.offers(url)

    /**
     * What holding [url] offers, in order: read as the menu is built, so it follows the
     * Experimental switches.
     */
    fun menuActions(url: String): List<LinkMenuAction> = LinkMenuAction.actions(url, dependency(ExperimentalFeatures))

    /** A session link also opens from TalkBack's actions, named for its session. */
    fun accessibilityActions(url: String): List<LinkMenuAction> =
        (if (sessionID(url) != null) listOf(LinkMenuAction.OpenSession) else emptyList()) + menuActions(url)

    fun displayHost(url: String): String = LinkMenuText.displayHost(url)

    /** One link: the menu's own titles. Several: each names its host. */
    fun accessibilityName(action: LinkMenuAction, url: String, among: List<String>): String =
        LinkMenuText.accessibilityName(action, url, among)

    /**
     * The registrable domain the icon service may be asked about; null for a private, tailnet or
     * credentialed link, which keeps a neutral glyph.
     */
    fun iconDomain(url: String): String? = SiteIconDomain.registrable(url)

    fun isPublicWebPage(url: String): Boolean = BrowserLinkPolicy.isPublicWebPage(url)
}
