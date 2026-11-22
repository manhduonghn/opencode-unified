package ai.opencode.feature.media

import ai.opencode.core.dependencies.Dependency
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.scene.SceneWindowClient
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.server.model.PageShareRequest
import java.util.UUID

/**
 * The in-app browser (Experimental → In-App Browser): a web page pushed onto the session's stack
 * like a detail screen. Its bar goes back and forward, shares, opens in the browser, and asks about
 * the page: the question goes to the session with the page attached. Going back from the first page
 * closes it. The web view lives in [BrowserWebViews], keyed by `pageID`.
 */
class BrowserFeature : Reducer<BrowserFeature.State, BrowserFeature.Action> {
    data class State(
        /** One browser visit's web view. A new link starts a new one. */
        val pageID: UUID? = null,
        /** The link that opened this visit, and where the page is now. */
        val openedURL: String? = null,
        val url: String? = null,
        val title: String = "",
        val canGoBack: Boolean = false,
        val canGoForward: Boolean = false,
        val isLoading: Boolean = false,
        /** The Ask composer over the bar, and what's typed in it. */
        val isAsking: Boolean = false,
        val askDraft: String = "",
        /** The window (task) showing the open page, so links it hands out open beside it. */
        val sceneID: String? = null,
        /** The last question asked from this page; its reply shows in the capsule. */
        val peekMessageID: String? = null,
    ) {
        val isOpen: Boolean get() = pageID != null

        /**
         * Whether [url] is this visit's page, compared without tracking parameters, fragments,
         * or a trailing slash.
         */
        fun shows(url: String): Boolean {
            val key = LinkPreviewPolicy.normalized(url) ?: url
            return listOf(openedURL, this.url).any { candidate ->
                candidate != null && (LinkPreviewPolicy.normalized(candidate) ?: candidate) == key
            }
        }

        val host: String get() = url?.let(BrowserLinkPolicy::displayHost) ?: ""

        /** The title the bar shows: the page's, or its host while loading. */
        val displayTitle: String get() = title.trim().ifEmpty { host }

        /** What a question from here asks the page reader for. */
        val shareRequest: PageShareRequest?
            get() {
                val pageID = pageID ?: return null
                val url = url ?: return null
                return PageShareRequest(pageID = pageID, url = url, title = displayTitle)
            }

        /** What the voice agent is told you're looking at. */
        val viewing: VoiceViewingPage?
            get() {
                val url = url ?: return null
                if (pageID == null) return null
                return VoiceViewingPage(url = url, title = displayTitle, isOpen = true)
            }

        val canSendAsk: Boolean get() = askDraft.isNotBlank()

        /** What the page shows: the open visit, or the one sliding off the stack after it closed. */
        data class Snapshot(
            val pageID: UUID,
            val openedURL: String,
            val url: String,
            val displayTitle: String,
            val host: String,
            val isLoading: Boolean,
            val canGoBack: Boolean,
            val canGoForward: Boolean,
            val isAsking: Boolean,
            val askDraft: String,
        ) {
            val canSendAsk: Boolean get() = askDraft.isNotBlank()

            companion object {
                /** The open visit while there is one, or [closing] while a closed page slides off the stack. */
                fun displayed(live: Snapshot?, closing: Snapshot?): Snapshot? = live ?: closing
            }
        }

        val snapshot: Snapshot?
            get() {
                val pageID = pageID ?: return null
                val url = url ?: return null
                return Snapshot(
                    pageID = pageID,
                    openedURL = openedURL ?: url,
                    url = url,
                    displayTitle = displayTitle,
                    host = host,
                    isLoading = isLoading,
                    canGoBack = canGoBack,
                    canGoForward = canGoForward,
                    isAsking = isAsking,
                    askDraft = askDraft,
                )
            }

        /**
         * A new visit for [url]. The caller discards this state's [pageID], the web view it
         * replaces.
         */
        fun open(url: String, id: UUID): State =
            State(pageID = id, openedURL = url, url = url, isLoading = true)

        /** No visit. The caller discards this state's [pageID]. */
        fun close(): State = State()

        /**
         * Ask's draft taken for sending with the open page: the trimmed text, the page and
         * [messageID], with the state after (draft cleared, Ask put away, the capsule tracking
         * [messageID]'s reply). Null when there's nothing to send.
         */
        fun takeAsk(messageID: () -> String): TakenAsk? {
            val text = askDraft.trim()
            if (text.isEmpty()) return null
            val page = shareRequest ?: return null
            val id = messageID()
            return TakenAsk(
                text = text,
                page = page,
                messageID = id,
                state = copy(askDraft = "", isAsking = false, peekMessageID = id),
            )
        }
    }

    /** What [State.takeAsk] hands the session to send, and the browser after it. */
    data class TakenAsk(val text: String, val page: PageShareRequest, val messageID: String, val state: State)

    sealed interface Action {
        /** A public web link tapped in a reply. */
        data class Open(val url: String) : Action
        /** Back from the first page: the page leaves the stack. */
        data object CloseTapped : Action
        data object BackTapped : Action
        data object ForwardTapped : Action
        /** The bar's address: back to the top of the page. */
        data object AddressTapped : Action
        /** The bar's Open in Browser. */
        data object SafariTapped : Action
        data object AskTapped : Action
        data object AskDismissed : Action
        data class AskDraftChanged(val text: String) : Action
        /** Ask's Send. `SessionFeature` sends the draft with the page attached. */
        data object AskSent : Action
        data class Navigated(val url: String, val title: String) : Action
        data class TitleChanged(val title: String) : Action
        data class LoadingChanged(val loading: Boolean) : Action
        data class CanGoBackChanged(val canGoBack: Boolean) : Action
        data class CanGoForwardChanged(val canGoForward: Boolean) : Action
        /** The page's web view moved to another window (or its first). */
        data class SceneChanged(val sceneID: String?) : Action
        /** A link the page hands to the system: another app's, a private host's, a download. */
        data class ExternalURLRequested(val url: String, val sceneID: String?) : Action
        data class PairingLinkTapped(val url: String) : Action
        data class LinkMenu(val action: LinkMenuAction, val url: String) : Action
    }

    private val uuid by Dependency(UuidClient)
    private val sceneWindow by Dependency(SceneWindowClient)
    private val pageReader by Dependency(PageReaderClient)

    override fun reduce(state: State, action: Action): Reduced<State, Action> {
        when (action) {
            is Action.Open -> {
                // The same page (a link card or the link again) is already on top.
                if (state.isOpen && state.shows(action.url)) return Reduced(state)
                return Reduced(state.open(action.url, uuid.next()), discard(state.pageID))
            }

            Action.CloseTapped -> return Reduced(state.close(), discard(state.pageID))

            Action.BackTapped -> {
                val id = state.pageID
                if (id == null || !state.canGoBack) return Reduced(state)
                val pageReader = pageReader
                return Reduced(state, Effect.run { pageReader.goBack(id) })
            }

            Action.ForwardTapped -> {
                val id = state.pageID
                if (id == null || !state.canGoForward) return Reduced(state)
                val pageReader = pageReader
                return Reduced(state, Effect.run { pageReader.goForward(id) })
            }

            Action.AddressTapped -> {
                val id = state.pageID ?: return Reduced(state)
                val pageReader = pageReader
                return Reduced(state, Effect.run { pageReader.scrollToTop(id) })
            }

            Action.SafariTapped -> {
                val url = state.url
                if (!state.isOpen || url == null || OpenCodePairingLink.shouldHandleOpenURL(url)) return Reduced(state)
                val pageReader = pageReader
                val sceneID = state.sceneID ?: sceneWindow.sceneID
                return Reduced(state, Effect.run { pageReader.openExternal(url, sceneID) })
            }

            Action.AskTapped -> {
                if (!state.isOpen) return Reduced(state)
                return Reduced(state.copy(isAsking = true))
            }

            Action.AskDismissed -> {
                if (!state.isOpen) return Reduced(state)
                return Reduced(state.copy(isAsking = false))
            }

            is Action.AskDraftChanged -> {
                if (!state.isOpen) return Reduced(state)
                return Reduced(state.copy(askDraft = action.text))
            }

            Action.AskSent -> return Reduced(state)

            is Action.Navigated -> {
                if (!state.isOpen) return Reduced(state)
                return Reduced(state.copy(url = action.url, title = action.title))
            }

            is Action.TitleChanged -> {
                if (!state.isOpen) return Reduced(state)
                return Reduced(state.copy(title = action.title))
            }

            is Action.LoadingChanged -> {
                if (!state.isOpen) return Reduced(state)
                return Reduced(state.copy(isLoading = action.loading))
            }

            is Action.CanGoBackChanged -> {
                if (!state.isOpen) return Reduced(state)
                return Reduced(state.copy(canGoBack = action.canGoBack))
            }

            is Action.CanGoForwardChanged -> {
                if (!state.isOpen) return Reduced(state)
                return Reduced(state.copy(canGoForward = action.canGoForward))
            }

            is Action.SceneChanged -> {
                if (!state.isOpen || state.sceneID == action.sceneID) return Reduced(state)
                return Reduced(state.copy(sceneID = action.sceneID))
            }

            is Action.ExternalURLRequested -> {
                if (!state.isOpen || OpenCodePairingLink.shouldHandleOpenURL(action.url)) return Reduced(state)
                val pageReader = pageReader
                val url = action.url
                val origin = action.sceneID ?: state.sceneID ?: sceneWindow.sceneID
                return Reduced(state, Effect.run { pageReader.openExternal(url, origin) })
            }

            is Action.PairingLinkTapped, is Action.LinkMenu -> return Reduced(state)
        }
    }

    private fun discard(id: UUID?): Effect<Action> {
        id ?: return Effect.none()
        val pageReader = pageReader
        return Effect.run { pageReader.discard(id) }
    }
}

/** How the reply to the last question asked from the open page is going. */
enum class BrowserReplyStatus {
    Working,
    NeedsInput,
    Ready;

    /** Once the agent waits on you or has answered, the capsule goes back to the session. */
    val goesBack: Boolean get() = this != Working
}

/**
 * The page the voice agent hears about. Declared in `:server` so the call (`:feature:voice`) reads
 * the same type the browser produces.
 */
typealias VoiceViewingPage = ai.opencode.server.model.VoiceViewingPage
