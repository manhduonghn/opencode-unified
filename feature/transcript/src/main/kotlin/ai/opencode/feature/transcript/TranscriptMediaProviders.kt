package ai.opencode.feature.transcript

import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.feature.media.AgentFileMarkdown
import ai.opencode.feature.media.LinkMenuAction
import ai.opencode.feature.media.LinkShareSheet
import ai.opencode.feature.media.LocalAgentFileMarkdown
import ai.opencode.feature.media.OpenURLClient
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.feature.transcript.markdown.ui.InlineLinkStyle
import ai.opencode.feature.transcript.markdown.ui.LocalInlineLinkStyle
import ai.opencode.feature.transcript.markdown.ui.MarkdownBlocksView
import ai.opencode.feature.transcript.markdown.ui.MarkdownLinks
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What `:feature:media` draws with the transcript's renderer, provided once at the app root:
 * Markdown files in the file viewer render as a reply does.
 */
@Composable
fun TranscriptMediaProviders(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAgentFileMarkdown provides AgentFileMarkdownRenderer, content = content)
}

/**
 * A Markdown file's page: the document as history (no reveal), file cards rather than image
 * previews, links as written. File links push the file; web links open in the browser, and hold
 * menus act on the link here.
 */
private val AgentFileMarkdownRenderer: AgentFileMarkdown = { source, server, directory, onOpenFile ->
    AgentFileMarkdownDocument(source, server, directory, onOpenFile)
}

@Composable
private fun AgentFileMarkdownDocument(
    source: String,
    server: OpenCodeServer,
    directory: String?,
    onOpenFile: (AgentFileReference) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val blocks = remember(source) { OpenCodeMarkdownBlock.blocks(source) }
    val context = remember(server, directory, onOpenFile) {
        TranscriptRowContext(
            server = server,
            sessionID = null,
            directory = directory,
            disclosure = TranscriptDisclosureState(),
            send = { action -> perform(action, scope, onOpenFile) },
            imagePreviews = false,
        )
    }
    CompositionLocalProvider(
        LocalTranscriptRowContext provides context,
        LocalInlineLinkStyle provides InlineLinkStyle.Today,
    ) {
        MarkdownBlocksView(blocks, revealItem = null)
    }
}

/** The document's links and copies, outside a session. */
private fun perform(action: TranscriptAction, scope: CoroutineScope, onOpenFile: (AgentFileReference) -> Unit) {
    when (action) {
        is TranscriptAction.OpenFile -> onOpenFile(action.file)
        is TranscriptAction.LinkTapped -> {
            val file = MarkdownLinks.fileReference(action.url)
            if (file != null) onOpenFile(file) else scope.launch { dependency(OpenURLClient).open(action.url) }
        }
        is TranscriptAction.LinkMenu -> when (action.action) {
            LinkMenuAction.OpenInSafari -> scope.launch { dependency(OpenURLClient).openInBrowser(action.url) }
            LinkMenuAction.OpenInApp, LinkMenuAction.OpenInAppBrowser -> scope.launch { dependency(OpenURLClient).open(action.url) }
            LinkMenuAction.Copy -> scope.launch { dependency(PasteboardClient).setURL(action.url) }
            LinkMenuAction.Share -> dependency(LinkShareSheet).present(action.url)
            LinkMenuAction.OpenSession, LinkMenuAction.CopySessionID -> Unit
        }
        // A code block's Copy button (Android), as `SessionFeature` handles it in a session.
        is TranscriptAction.CopyTextTapped -> {
            if (action.text.isNotEmpty()) {
                dependency(HapticsClient).play(Haptic.tap)
                scope.launch { dependency(PasteboardClient).setString(action.text) }
            }
        }
        else -> Unit
    }
}
