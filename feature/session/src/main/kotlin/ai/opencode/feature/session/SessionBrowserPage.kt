package ai.opencode.feature.session

import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.store.rememberScope
import ai.opencode.feature.media.BrowserFeature
import ai.opencode.feature.media.BrowserPage
import ai.opencode.feature.media.browserBackAction
import ai.opencode.feature.media.browserStackID
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import java.util.UUID

// The session's glue for:feature:media's in-app browser. The drawer area's stack pushes
// [sessionBrowserStackPage] and draws [SessionBrowserPage] for it.

/**
 * The page pushed over the session: media's [BrowserPage] on the session's scoped `browser` store,
 * its [browserReplyStatus], and the session's [WorkingIndicator] in the reply capsule. System Back
 * steps back through the page's history, dismisses Ask, and from the first page closes it
 * ([browserBackAction]).
 */
@Composable
fun SessionBrowserPage(store: StoreOf<SessionFeature.State, SessionFeature.Action>, modifier: Modifier = Modifier) {
    val browser = store.rememberScope(state = { it.browser }, action = { child: BrowserFeature.Action -> SessionFeature.Action.Browser(child) })
    val isOpen by browser.observe { it.isOpen }
    val replyStatus by store.observe { it.browserReplyStatus }
    BackHandler(enabled = isOpen) {
        browser.send(browserBackAction(browser.state))
    }
    BrowserPage(
        store = browser,
        replyStatus = replyStatus,
        workingIndicator = { needsInput -> WorkingIndicator(WorkingActivity(needsInput = needsInput)) },
        modifier = modifier,
    )
}

/**
 * The browser's place in the session's stack while a page is open: stable for one visit, never a
 * subagent's identity.
 */
fun sessionBrowserStackPage(state: SessionFeature.State): SessionStackPage? {
    val pageID = state.browser.pageID ?: return null
    return SessionStackPage(
        id = browserStackPageID(pageID),
        paneTitle = state.browser.title.ifEmpty { null },
    )
}

/** [browserStackID] folded to the stack's `Int` ids: negative, so never a detail's (small positive) identity. */
private fun browserStackPageID(pageID: UUID): Int {
    val id = browserStackID(pageID)
    return ((id xor (id ushr 32)).toInt() and 0x3FFF_FFFF) or Int.MIN_VALUE
}
