package ai.opencode.feature.media

import ai.opencode.core.R as CoreR
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.motion.Motion
import ai.opencode.core.scene.ActiveScene
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.OpenCodeMinTapTarget
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.server.OpenCodePairingLink
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.UUID
import kotlin.math.roundToInt

object BrowserMetrics {
    /** The controls, like the session header's: Android's 48 dp minimum touch target. */
    val controlSize = OpenCodeMinTapTarget
    /** The back button's row at the top, where the session header's sits. */
    val headerClearance = TranscriptMetrics.clustered + controlSize + 8.dp
    /** The bottom bar's height. */
    val barHeight = 52.dp
}

/**
 * The page pushed over the session: the web view edge to edge, a back button where
 * the drawer button sits, and a bar at the bottom (the page's back and forward, its title and host,
 * Share, Open in Browser, and Ask). Ask raises a composer over the bar; the question goes to the
 * session with the page attached, and a capsule shows the reply's progress. Going back from the
 * first page closes it: the back button, or the stack's back gesture (which steps back through the
 * page's history first: see [browserBackAction]). The session passes its scoped browser store and
 * how the reply to the last question is going.
 *
 * @param replyStatus The reply capsule's state; null hides it.
 * @param workingIndicator the session's `WorkingIndicator`, shown in the capsule while the
 *   agent works on the question (`needsInput`: a request waiting on you).
 */
@Composable
fun BrowserPage(
    store: StoreOf<BrowserFeature.State, BrowserFeature.Action>,
    replyStatus: BrowserReplyStatus?,
    workingIndicator: @Composable (needsInput: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    var bottomHeight by remember { mutableStateOf(BrowserMetrics.barHeight) }
    val live by store.observe { it.snapshot }
    // The visit as it was last open: closing clears the store at once, while the page still
    // slides off the stack showing it.
    var closingSnapshot by remember { mutableStateOf(store.state.snapshot) }
    LaunchedEffect(live) {
        val snapshot = live
        if (snapshot != null) closingSnapshot = snapshot
    }
    val page = BrowserFeature.State.Snapshot.displayed(live = live, closing = closingSnapshot)
    val isAsking = page?.isAsking == true

    fun close() {
        if (!store.state.isOpen) return
        focusManager.clearFocus()
        store.send(BrowserFeature.Action.CloseTapped)
    }

    // The safe area.
    val safeArea = WindowInsets.safeDrawing.asPaddingValues()
    val safeTop = safeArea.calculateTopPadding()
    val canvas = OpenCodeTheme.colors.canvas
    val backToSessionDescription = stringResource(R.string.media_browser_back_to_session_description)
    Box(
        modifier
            .fillMaxSize()
            .background(canvas)
            .onPreviewKeyEvent { event ->
                val back = event.type == KeyEventType.KeyDown && event.key == Key.LeftBracket && (event.isMetaPressed || event.isCtrlPressed)
                if (back) close()
                back
            }
            .testTag("browser"),
    ) {
        if (page != null) {
            // Edge to edge; the back button above and the bar below are the page's obscured
            // insets, so it scrolls under them and fixed elements' first/last pixels clear them.
            BrowserWebViewHost(
                pageID = page.pageID,
                url = page.openedURL,
                insets = BrowserInsets(
                    top = (safeTop + BrowserMetrics.headerClearance).value,
                    left = safeArea.calculateLeftPadding(LayoutDirection.Ltr).value,
                    bottom = (safeArea.calculateBottomPadding() + bottomHeight + 8.dp).value,
                    right = safeArea.calculateRightPadding(LayoutDirection.Ltr).value,
                ),
                onEvent = { store.send(it) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Legibility behind the status bar and the back button, like the session header's wash.
        val surface = canvas
        Box(
            Modifier
                .fillMaxWidth()
                .height(safeTop + BrowserMetrics.headerClearance)
                .background(Brush.verticalGradient(0f to surface, 0.5f to surface.copy(alpha = 0.85f), 1f to surface.copy(alpha = 0f)))
                .clearAndSetSemantics {},
        )
        // Where the drawer button sits on the session: back to it.
        Surface(
            onClick = ::close,
            shape = RoundedCornerShape(OpenCodeRadius.card),
            contentColor = OpenCodeTheme.colors.text,
            // The desktop floating surface: flat fill, hairline ring.
            color = OpenCodeSurfaces.color,
            border = OpenCodeSurfaces.border,
            shadowElevation = OpenCodeSurfaces.raisedShadow,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.statusBars))
                .padding(start = 12.dp, top = TranscriptMetrics.clustered)
                .size(BrowserMetrics.controlSize)
                .semantics { contentDescription = backToSessionDescription }
                .testTag("browser-pop"),
        ) {
            Box(contentAlignment = Alignment.Center) {
                OpenCodeIcon("chevron.backward", contentDescription = null)
            }
        }
        // The bar and Ask rise with the keyboard; the page stays put beneath.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .onSizeChanged { bottomHeight = with(density) { it.height.toDp() } }
                .padding(horizontal = 16.dp)
                .padding(bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AnimatedVisibility(
                visible = replyStatus != null && !isAsking,
                enter = appear(reduceMotion),
                exit = disappear(reduceMotion),
            ) {
                if (replyStatus != null) ReplyCapsule(replyStatus, ::close, workingIndicator)
            }
            AnimatedVisibility(
                visible = isAsking,
                enter = appear(reduceMotion),
                exit = disappear(reduceMotion),
            ) {
                if (page != null) AskComposer(store, page, onSend = { focusManager.clearFocus() })
            }
            Bar(store, page)
        }
    }
}

/**
 * What the stack's back gesture does on this page: Ask goes away first, then the page's own
 * history, then the page leaves.
 */
fun browserBackAction(browser: BrowserFeature.State): BrowserFeature.Action = when {
    browser.isAsking -> BrowserFeature.Action.AskDismissed
    browser.canGoBack -> BrowserFeature.Action.BackTapped
    else -> BrowserFeature.Action.CloseTapped
}

/** The page's place in the session's stack: stable for one visit, and never a detail's identity. */
fun browserStackID(pageID: UUID): Long = pageID.mostSignificantBits or (1L shl 62)

/**
 * Ask and the reply capsule rise from the bottom as panels (panel open) and fold away on panel
 * close; under Reduce Motion, the reduced cross-fade.
 */
private fun appear(reduceMotion: Boolean): EnterTransition {
    if (reduceMotion) return fadeIn(MotionRole.PanelOpen.animation(reduceMotion = true))
    val role = MotionRole.PanelOpen
    return slideInVertically(role.animation(visibilityThreshold = IntOffset.VisibilityThreshold)) { it } + fadeIn(role.animation())
}

private fun disappear(reduceMotion: Boolean): ExitTransition {
    if (reduceMotion) return fadeOut(MotionRole.PanelOpen.animation(MotionPhase.Exit, reduceMotion = true))
    val role = MotionRole.PanelOpen
    return slideOutVertically(role.animation(MotionPhase.Exit, visibilityThreshold = IntOffset.VisibilityThreshold)) { it } +
        fadeOut(role.animation(MotionPhase.Exit))
}

@Composable
private fun Bar(store: StoreOf<BrowserFeature.State, BrowserFeature.Action>, page: BrowserFeature.State.Snapshot?) {
    val isAsking = page?.isAsking == true
    val cancelDescription = stringResource(CoreR.string.common_cancel)
    val askDescription = stringResource(R.string.media_browser_ask_description)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = OpenCodeShape.control,
            // The desktop floating surface: flat fill, hairline ring.
            color = OpenCodeSurfaces.color,
            border = OpenCodeSurfaces.border,
            shadowElevation = OpenCodeSurfaces.raisedShadow,
            modifier = Modifier
                .weight(1f)
                .height(BrowserMetrics.barHeight),
        ) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BarButton("chevron.left", stringResource(CoreR.string.common_back), "browser-back", page?.canGoBack == true) {
                    store.send(BrowserFeature.Action.BackTapped)
                }
                BarButton("chevron.right", stringResource(R.string.media_browser_forward_description), "browser-forward", page?.canGoForward == true) {
                    store.send(BrowserFeature.Action.ForwardTapped)
                }
                Address(page, onTap = { store.send(BrowserFeature.Action.AddressTapped) }, Modifier.weight(1f))
                if (page != null) {
                    // The system's share sheet, presented by the button itself.
                    BarButton("square.and.arrow.up", stringResource(CoreR.string.common_share), "browser-share", enabled = true) {
                        dependency(LinkShareSheet).present(page.url)
                    }
                    BarButton("safari", stringResource(R.string.media_browser_open_in_browser_description), "browser-safari", enabled = true) {
                        store.send(BrowserFeature.Action.SafariTapped)
                    }
                }
            }
        }
        Surface(
            onClick = { store.send(if (isAsking) BrowserFeature.Action.AskDismissed else BrowserFeature.Action.AskTapped) },
            shape = RoundedCornerShape(OpenCodeRadius.card),
            // The desktop floating surface: flat fill, hairline ring.
            color = OpenCodeSurfaces.color,
            border = OpenCodeSurfaces.border,
            shadowElevation = OpenCodeSurfaces.raisedShadow,
            modifier = Modifier
                .size(BrowserMetrics.barHeight)
                .semantics { contentDescription = if (isAsking) cancelDescription else askDescription }
                .testTag("browser-ask"),
        ) {
            Crossfade(isAsking, Modifier.fillMaxSize(), animationSpec = tween((Motion.fadeStandard * 1000).toInt()), label = "browser-ask") { asking ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    OpenCodeIcon(if (asking) "xmark" else "text.bubble", contentDescription = null, tint = OpenCodeTheme.colors.text)
                }
            }
        }
    }
}

@Composable
private fun BarButton(systemImage: String, label: String, tag: String, enabled: Boolean, action: () -> Unit) {
    Box(
        Modifier
            .size(BrowserMetrics.controlSize)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = null, onClick = action)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        val colors = OpenCodeTheme.colors
        OpenCodeIcon(systemImage, contentDescription = null, tint = if (enabled) colors.text else colors.tertiaryText)
    }
}

/** The page's title over its host; a tap scrolls to the top. */
@Composable
private fun Address(page: BrowserFeature.State.Snapshot?, onTap: () -> Unit, modifier: Modifier) {
    val title = page?.displayTitle ?: ""
    val url = page?.url
    val scrollToTopLabel = stringResource(R.string.media_browser_scroll_to_top_label)
    Column(
        modifier
            .heightIn(min = BrowserMetrics.controlSize)
            .clickable(onClick = onTap)
            .clearAndSetSemantics {
                contentDescription = title
                stateDescription = url ?: ""
                role = Role.Button
                onClick(label = scrollToTopLabel) {
                    onTap()
                    true
                }
            }
            .testTag("browser-address"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
    ) {
        Text(
            title,
            style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
            color = OpenCodeTheme.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            val secondary = OpenCodeTheme.colors.secondaryText
            if (page?.isLoading == true) {
                CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = secondary)
            } else if (url?.startsWith("https:", ignoreCase = true) == true) {
                OpenCodeIcon("lock.fill", contentDescription = null, size = 11.dp, tint = secondary)
            }
            Text(page?.host ?: "", style = OpenCodeTextStyles.caption1, color = secondary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
    }
}

@Composable
private fun AskComposer(
    store: StoreOf<BrowserFeature.State, BrowserFeature.Action>,
    page: BrowserFeature.State.Snapshot,
    onSend: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val canSend = page.canSendAsk
    fun send() {
        if (!store.state.canSendAsk) return
        onSend()
        store.send(BrowserFeature.Action.AskSent)
    }
    Surface(
        shape = RoundedCornerShape(OpenCodeRadius.panel),
        // The desktop floating surface: flat fill, hairline ring.
        color = OpenCodeSurfaces.color,
        border = OpenCodeSurfaces.border,
        shadowElevation = OpenCodeSurfaces.raisedShadow,
        modifier = Modifier.fillMaxWidth().heightIn(min = BrowserMetrics.barHeight),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            TextField(
                value = page.askDraft,
                onValueChange = { text ->
                    // A multi-line field's Send key types a newline: that's Send.
                    if (text == page.askDraft + "\n") {
                        send()
                    } else if (store.state.isOpen) {
                        store.send(BrowserFeature.Action.AskDraftChanged(text))
                    }
                },
                placeholder = { Text(stringResource(R.string.media_browser_ask_placeholder)) },
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                    focusedIndicatorColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                ),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
                    .testTag("browser-ask-field"),
            )
            val colors = OpenCodeTheme.colors
            val sendDescription = stringResource(CoreR.string.common_send)
            Box(
                Modifier
                    .padding(end = 2.dp, bottom = 4.dp)
                    .clickable(enabled = canSend, role = Role.Button, onClick = ::send)
                    .openCodeTapTarget(BrowserMetrics.controlSize)
                    .semantics { contentDescription = sendDescription }
                    .testTag("browser-ask-send"),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .background(
                            if (canSend) colors.text else colors.secondaryText.copy(alpha = colors.secondaryText.alpha * 0.4f),
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    OpenCodeIcon("arrow.up", contentDescription = null, tint = colors.canvas)
                }
            }
        }
    }
    LaunchedEffect(Unit) { if (store.state.isOpen) focus.requestFocus() }
}

/**
 * What the agent is doing with the question asked here; once its reply lands, a tap goes back
 * to the session to read it.
 */
@Composable
private fun ReplyCapsule(
    status: BrowserReplyStatus,
    close: () -> Unit,
    workingIndicator: @Composable (needsInput: Boolean) -> Unit,
) {
    val goesBack = status.goesBack
    val label = when (status) {
        BrowserReplyStatus.Ready -> stringResource(R.string.media_browser_reply_ready)
        BrowserReplyStatus.NeedsInput -> stringResource(R.string.media_browser_reply_needs_input)
        BrowserReplyStatus.Working -> stringResource(R.string.media_browser_reply_working)
    }
    val goBackLabel = stringResource(R.string.media_browser_go_back_to_session_label)
    Surface(
        shape = OpenCodeShape.control,
        // The desktop floating surface: flat fill, hairline ring.
        color = OpenCodeSurfaces.color,
        border = OpenCodeSurfaces.border,
        shadowElevation = OpenCodeSurfaces.raisedShadow,
        modifier = Modifier
            .heightIn(min = BrowserMetrics.controlSize)
            .then(if (goesBack) Modifier.clickable(role = Role.Button, onClickLabel = goBackLabel, onClick = close) else Modifier)
            .clearAndSetSemantics {
                contentDescription = label
                if (goesBack) {
                    role = Role.Button
                    onClick(label = goBackLabel) { close(); true }
                }
            }
            .testTag("browser-reply"),
    ) {
        Box(Modifier.padding(horizontal = 14.dp).heightIn(min = BrowserMetrics.controlSize), contentAlignment = Alignment.CenterStart) {
            if (status != BrowserReplyStatus.Ready) {
                // A request waiting on you (a permission, a question) is answered in the session.
                workingIndicator(status == BrowserReplyStatus.NeedsInput)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val secondary = OpenCodeTheme.colors.secondaryText
                    OpenCodeIcon("text.bubble", contentDescription = null, tint = secondary, size = 17.dp)
                    Text(stringResource(R.string.media_browser_reply_ready), style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                    Text(stringResource(R.string.media_browser_reply_back_to_read), style = OpenCodeTextStyles.subheadline, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Hosts the page's [android.webkit.WebView] from [BrowserWebViews], which outlives this view, so a
 * rebuilt page keeps its history and scroll position. Also shows the hold menu for links held in
 * the page.
 */
@Composable
fun BrowserWebViewHost(
    pageID: UUID,
    url: String,
    /** The back button above and the bar below; the page scrolls under them, clear at either end. */
    insets: BrowserInsets,
    onEvent: (BrowserFeature.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    class Coordinator(var pageID: UUID) {
        /** The window the page was last seen in, so a move to another is sent once. */
        var sceneID: String? = null
    }
    val coordinator = remember { Coordinator(pageID) }
    val isDark = OpenCodeTheme.colors.isDark
    var held by remember { mutableStateOf<Triple<String, Float, Float>?>(null) }
    val pages = BrowserWebViews.shared

    // Made once per page, on first show and when the page changes: a page discarded later (the page
    // closing) isn't made again while the view slides away.
    fun mount(container: FrameLayout) {
        val webView = pages.webView(pageID, url, isDark)
        if (webView.parent !== container) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            container.removeAllViews()
            container.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        pages.resume(pageID)
    }

    // On every update: the page's handlers, appearance and insets, and the window it's in.
    fun bind(container: FrameLayout) {
        pages.setHostContext(pageID, container.context)
        // The app's appearance, live.
        pages.setAppearance(pageID, isDark)
        pages.setObscuredInsets(pageID, insets)
        pages.setEventHandler(pageID, onEvent)
        pages.setLinkHeldHandler(pageID) { link, x, y -> held = Triple(link, x, y) }
        val sceneID = ActiveScene.sceneID(container.context)
        pages.noteScene(pageID, sceneID)
        if (sceneID != null && sceneID != coordinator.sceneID) {
            coordinator.sceneID = sceneID
            // After this update.
            container.post { onEvent(BrowserFeature.Action.SceneChanged(sceneID)) }
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { context ->
                FrameLayout(context).apply {
                    clipChildren = true
                    mount(this)
                }
            },
            update = { container ->
                if (coordinator.pageID != pageID) {
                    pages.setEventHandler(coordinator.pageID, null)
                    pages.setLinkHeldHandler(coordinator.pageID, null)
                    coordinator.pageID = pageID
                    coordinator.sceneID = null
                    mount(container)
                }
                bind(container)
            },
            // Off screen, the page plays nothing and keeps nothing of the screen.
            onRelease = { container ->
                container.removeAllViews()
                pages.pauseMedia(coordinator.pageID)
                pages.setEventHandler(coordinator.pageID, null)
                pages.setLinkHeldHandler(coordinator.pageID, null)
                pages.setHostContext(coordinator.pageID, null)
            },
            modifier = Modifier.fillMaxSize().testTag("browser-page"),
        )
        held?.let { (link, x, y) ->
            val previewURL = OpenCodePairingLink.shareableURL(link) ?: link
            Box(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.size(1.dp)) {
                LinkMenuPopup(
                    url = previewURL,
                    actions = pages.linkMenuActions(link),
                    expanded = true,
                    onAction = { action ->
                        held = null
                        pages.performLinkMenu(pageID, action, link)
                    },
                    onOpen = {
                        held = null
                        pages.commitLinkPreview(pageID, link)
                    },
                    onDismiss = { held = null },
                )
            }
        }
    }
}
