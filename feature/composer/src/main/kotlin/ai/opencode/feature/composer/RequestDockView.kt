package ai.opencode.feature.composer

import ai.opencode.core.R as CoreR
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionReduce
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.motion.transitionBlurEffect
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.RollingNumber
import ai.opencode.core.ui.RollingNumberAlignment
import ai.opencode.core.ui.SFSymbol
import ai.opencode.server.model.OpenCodeFormField
import ai.opencode.server.model.OpenCodeFormFieldType
import ai.opencode.server.model.OpenCodeFormOption
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodePermissionReply
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The panel's measures. */
object RequestDockMetrics {
    /** What a squeezed part still shows, at least: two lines of a question. */
    val questionFloor: Dp = 44.dp
    /** About one choice, or three lines of a command. */
    val scrollFloor: Dp = 64.dp
    /** One edge for the caption, the question, the marks, and the bands. */
    val edge: Dp = 20.dp
    /** The composer's surface, which the panel shares. */
    val cornerRadius: Dp = 12.dp
    /** × is a circle centred on the glass's top-trailing corner arc, so its edge runs concentric with the glass's corner. */
    val closeSize: Dp = 34.dp
    /** The header row's inset from the top. */
    val headerTop: Dp = 6.dp
    /** How far a request's content rises out of the bar as it arrives. */
    val rise: Dp = 28.dp
    /** The blur a request's content sharpens in from and blurs away to. */
    val revealBlur: Dp = 8.dp
    /** Caps scaled down with small text, never up: larger text scrolls inside them. */
    val questionCap: Dp = 220.dp
    val rowsCap: Dp = 300.dp
    val commandCap: Dp = 300.dp
    /** The actions stop growing at the first accessibility size. */
    const val actionsMaxFontScale = 1.65f
}

/**
 * The request panel's glass growing out of the composer and folding back: the panel roles. Its
 * content arrives on `Motion.panelOpen` and leaves on `Motion.microOut`.
 */
object RequestPanelReveal {
    fun <T> animation(opening: Boolean, reducedMotion: Boolean): FiniteAnimationSpec<T> =
        Motion.panelOpen.animation(if (opening) MotionPhase.Enter else MotionPhase.Exit, reducedMotion)
}

/** What the dock reads from the session beyond its own state. */
data class RequestDockContext(
    val currentSessionID: String? = null,
    /** A draft that just became this session keeps what's shown. */
    val draftBecameSessionID: String? = null,
    val familySessions: List<OpenCodeSession> = emptyList(),
    /** The session's items followed by its family transcript cache, to find the tool call that asks. */
    val transcripts: List<List<OpenCodeTranscriptItem>> = emptyList(),
    /** The live screen, never an inert Catch Up preview. */
    val isVisible: Boolean = true,
    /** Something is being dictated (`hasLiveDictation || dictationHold != nil`): a pick never auto-advances then. */
    val isDictating: Boolean = false,
)

/**
 * A pending request's panel, grown up out of the composer on its surface. The composer's own row
 * below it is your answer: typing or dictating there answers, and its Send sends ([RequestBar]).
 * The panel holds the header (with × for questions), the question, which scrolls on its own, and
 * the choices, which stay in reach; or a permission's action, a one- or two-line summary that
 * expands to the whole command or diff, and Allow Once beside Always Allow
 * ([RequestTunables]). Deny sits in the bar beside “Tell it what to do instead”, so a reason costs
 * no step.
 *
 * Only the content: the session draws the surface, grows it (animating its size), and caps
 * it with [requestPanelCap].
 *
 * @param isKeyboardUp the bar has the keyboard: the question and choices take less height.
 */
@Composable
fun RequestDockView(
    dock: RequestDockFeature.State<*>,
    context: RequestDockContext,
    isKeyboardUp: Boolean,
    send: (RequestDockFeature.Action) -> Unit,
    modifier: Modifier = Modifier,
    gesture: RequestPanelGesture? = null,
) {
    val reducedMotion = rememberReduceMotion()
    val ownGesture = remember { RequestPanelGesture() }
    val panelGesture = gesture ?: ownGesture
    val live = remember(dock, context.transcripts) { liveShown(dock, context) }
    var lastShown by remember { mutableStateOf<Shown?>(null) }
    var arrivedID by remember { mutableStateOf<String?>(null) }
    var previousSessionID by remember { mutableStateOf(context.currentSessionID) }
    val state = remember { DockViewState() }
    val pickScope = rememberCoroutineScope()
    val latestDock by rememberUpdatedState(dock)
    val latestDictating by rememberUpdatedState(context.isDictating)

    // A new session: what's shown is the new one's, without a reveal.
    if (previousSessionID != context.currentSessionID) {
        val old = previousSessionID
        SideEffect {
            previousSessionID = context.currentSessionID
            if (!(old == null && context.currentSessionID != null && context.currentSessionID == context.draftBecameSessionID)) {
                lastShown = live
                arrivedID = live?.id
            }
        }
    }
    SideEffect { if (live != null && live != lastShown) lastShown = live }
    // Mounted hidden for its first frame, so it rises in.
    LaunchedEffect(live?.id) {
        val id = live?.id
        if (id != null) arrivedID = id
        // Another request docked, or none: a drag or settle under way was the last one's.
        panelGesture.reset()
    }
    DisposableEffect(Unit) { onDispose { panelGesture.reset() } }
    // The request left: it blurs and sinks away where it was, ahead of the glass folding over it,
    // and is dropped once that fade lands (the alpha's finish below), unless another request docked
    // meanwhile. Cleared, so the same request docking again rises again.
    LaunchedEffect(live == null) {
        if (live != null) return@LaunchedEffect
        val leaving = lastShown ?: return@LaunchedEffect
        if (arrivedID != leaving.id) {
            // Never arrived, so there's no fade to wait for.
            lastShown = null
            return@LaunchedEffect
        }
        arrivedID = null
    }
    val latestLive by rememberUpdatedState(live)
    // Each request starts folded, with nothing sent.
    val focus = remember { FocusRequester() }
    val androidContext = LocalContext.current
    val accessibility = remember(androidContext) { androidContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager }
    val screenReaderOn = { accessibility?.isTouchExplorationEnabled == true }
    LaunchedEffect(dock.dockRequestID) {
        state.sentAction = null
        state.expandedPermissionID = null
        state.scopeShownForID = null
        if (dock.dockRequestID == null || !context.isVisible || !screenReaderOn()) return@LaunchedEffect
        delay(400)
        runCatching { focus.requestFocus() }
    }
    val idle = dock.respondingRequestIDs.isEmpty()
    val dismissText = stringResource(CoreR.string.common_dismiss)
    LaunchedEffect(idle) {
        val id = dock.dockRequestID
        if (idle && id != null && dock.requestFailures[id] == RequestDockFeature.State.RequestFailure.Reply) state.sentAction = null
    }

    val shown = live ?: lastShown ?: return
    key(shown.id) {
        // Arrived and not yet leaving.
        val showing = arrivedID == shown.id
        // Arriving it grows in on `panelOpen`; leaving it fades on `microOut`.
        val reveal: FiniteAnimationSpec<Float> =
            if (showing) Motion.panelOpen.animation(reduceMotion = reducedMotion) else Motion.microOut.animation(reduceMotion = reducedMotion)
        val alpha by animateFloatAsState(
            if (showing) 1f else 0f,
            reveal,
            label = "requestAlpha",
            finishedListener = { value ->
                if (value == 0f && latestLive == null && lastShown?.id == shown.id) lastShown = null
            },
        )
        val riseTarget = when {
            reducedMotion || showing -> 0f
            live == null -> RequestDockMetrics.rise.value / 2
            else -> RequestDockMetrics.rise.value
        }
        val rise by animateFloatAsState(riseTarget, reveal, label = "requestRise")
        // Arriving it sharpens in as the surface grows; leaving it blurs away in place just
        // ahead of the surface folding over it (one motion). Reduce Motion only fades.
        val blurTarget = if (reducedMotion || showing) 0f else RequestDockMetrics.revealBlur.value
        val blur by animateFloatAsState(blurTarget, reveal, label = "requestBlur")
        val leaving = live == null
        Box(
            modifier
                .fillMaxWidth()
                .padding(horizontal = RequestDockMetrics.edge)
                .graphicsLayer {
                    this.alpha = alpha
                    translationY = rise.dp.toPx()
                    val radius = blur.dp.toPx()
                    renderEffect = transitionBlurEffect(radius)
                }
                .testTag(if (leaving) "request-dock-leaving" else "request-dock")
                .semantics {
                    if (leaving) hideFromAccessibility()
                    // TalkBack's escape: dismiss at once, as × does (Deny, for a permission).
                    customActions = listOf(CustomAccessibilityAction(dismissText) { send(RequestDockFeature.Action.DismissTapped); true })
                },
        ) {
            val scope = DockScope(
                dock, context, isKeyboardUp, send, state, focus, reducedMotion,
                enabled = !leaving, pickScope = pickScope, latest = { latestDock to latestDictating },
                screenReaderOn = screenReaderOn, gesture = panelGesture,
            )
            scope.Content(shown)
        }
    }
}

/**
 * Warms the request panel's first build once per process: a beat after it appears, a sample
 * two-page question is composed through [RequestDockView] for a moment, one screen below the
 * window, transparent and outside semantics (no touches, no focus, no haptics, nothing sent),
 * then dropped. The first real request then composes code that has already run (class loading,
 * verification, JIT) instead of paying for it in the frame its panel grows. Call it from the
 * session screen alongside the dock; it draws nothing.
 */
@Composable
fun RequestDockPrewarm() {
    var phase by remember { mutableStateOf(if (requestDockPrewarmed) 2 else 0) }
    LaunchedEffect(Unit) {
        if (phase == 2) return@LaunchedEffect
        delay(REQUEST_PREWARM_DELAY_MILLIS)
        if (requestDockPrewarmed) {
            phase = 2
            return@LaunchedEffect
        }
        requestDockPrewarmed = true
        phase = 1
        // Long enough for its arrival effects and first animation frames to run once.
        delay(REQUEST_PREWARM_HOLD_MILLIS)
        phase = 2
    }
    if (phase != 1) return
    val focusManager = LocalFocusManager.current
    Layout(
        content = {
            // Both first arrivals: a question's panel and a permission's (folded, with Always Allow).
            RequestDockView(dock = RequestDockSample.questionDock, context = RequestDockSample.context, isKeyboardUp = false, send = {})
            RequestDockView(dock = RequestDockSample.permissionDock, context = RequestDockSample.context, isKeyboardUp = false, send = {})
        },
        modifier = Modifier
            .graphicsLayer { alpha = 0f }
            .clearAndSetSemantics {}
            .inertToFocus(focusManager),
    ) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 400.dp.roundToPx()
        val placeables = measurables.map { it.measure(Constraints(maxWidth = width)) }
        layout(0, 0) { placeables.forEach { it.place(0, REQUEST_PREWARM_OFFSET) } }
    }
}

private var requestDockPrewarmed = false

/**
 * No descendant can take keyboard or D-pad focus: focus moving or requested into the group is
 * cancelled (`onEnter`, which `requestFocus` on a descendant also goes through), and focus
 * already inside when it turns inert is cleared.
 */
private fun Modifier.inertToFocus(focusManager: FocusManager): Modifier = this
    .onFocusChanged { if (it.hasFocus) focusManager.clearFocus(force = true) }
    .focusProperties { onEnter = { cancelFocusChange() } }
    .focusGroup()
private const val REQUEST_PREWARM_DELAY_MILLIS = 1_200L
private const val REQUEST_PREWARM_HOLD_MILLIS = 400L

/** Far below any window, so no pointer reaches the sample. */
private const val REQUEST_PREWARM_OFFSET = 100_000

/** The prewarm's inert samples, in their own session: a two-page single-choice question, and a command's permission. */
private object RequestDockSample {
    private const val SESSION = "request-dock-prewarm"
    private val question = OpenCodeFormRequest(
        id = "request-dock-prewarm",
        sessionID = SESSION,
        title = "Question",
        fields = listOf("first", "second").map { key ->
            OpenCodeFormField(
                key = key,
                type = OpenCodeFormFieldType.String,
                title = "Question",
                description = "Which one?",
                options = listOf(
                    OpenCodeFormOption("a", "First choice", "A short description"),
                    OpenCodeFormOption("b", "Second choice"),
                    OpenCodeFormOption("c", "Third choice"),
                ),
            )
        },
        metadata = mapOf("kind" to OpenCodeJSONValue.Str("question")),
    )
    private val permission = OpenCodePermissionRequest(
        id = "request-dock-prewarm-permission",
        sessionID = SESSION,
        action = "bash",
        resources = listOf("ls -la"),
        save = listOf("ls *"),
    )
    val questionDock = RequestDockFeature.State<Unit>(formRequests = listOf(question), parkedComposer = Unit)
    val permissionDock = RequestDockFeature.State<Unit>(permissionRequests = listOf(permission), parkedComposer = Unit)
    val context = RequestDockContext(currentSessionID = SESSION, isVisible = false)
}

/** What the panel shows: the docked request, its answers and page. */
private data class Shown(
    val id: String,
    val permission: OpenCodePermissionRequest? = null,
    val form: OpenCodeFormRequest? = null,
    val answers: Map<String, OpenCodeFormValue> = emptyMap(),
    val page: Int = 0,
    val call: OpenCodeToolCall? = null,
)

private fun liveShown(dock: RequestDockFeature.State<*>, context: RequestDockContext): Shown? {
    if (!dock.isAnswering) return null
    val id = dock.dockRequestID ?: return null
    dock.permissionRequests.firstOrNull { it.id == id }?.let {
        return Shown(id, permission = it, call = RequestDockAnswer.toolCall(it, context.transcripts))
    }
    dock.formRequests.firstOrNull { it.id == id }?.let {
        return Shown(id, form = it, answers = dock.formAnswers[id] ?: emptyMap(), page = dock.questionPageIndices[id] ?: 0)
    }
    return null
}

private enum class DockAction { Once, Always }

/** A question page as it was drawn: what [DockScope.LeavingPage] keeps on screen as it leaves. */
private data class PageSnapshot(
    val key: String,
    val field: OpenCodeFormField?,
    val answers: Map<String, OpenCodeFormValue>,
    val autoAdvance: Int?,
)

/** Measured and drawn at its own height, but takes none in its parent: an overlay over what follows. */
private fun Modifier.overlayWithoutHeight(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    layout(placeable.width, 0) { placeable.place(0, 0) }
}

/** The view's own per-request state. */
private class DockViewState {
    /** The action last sent, so only its button shows progress. */
    var sentAction by mutableStateOf<DockAction?>(null)
    /** The permission whose full command or diff is shown (folded by default). */
    var expandedPermissionID by mutableStateOf<String?>(null)
    /** The permission whose Always Allow scope is showing, to confirm. */
    var scopeShownForID by mutableStateOf<String?>(null)
}

private class DockScope(
    val dock: RequestDockFeature.State<*>,
    val context: RequestDockContext,
    val isKeyboardUp: Boolean,
    val send: (RequestDockFeature.Action) -> Unit,
    val state: DockViewState,
    val focus: FocusRequester,
    val reducedMotion: Boolean,
    /** Leaving content takes no touches. */
    val enabled: Boolean,
    /** Where a pick's auto-advance waits, and the dock as it is when it wakes. */
    val pickScope: CoroutineScope,
    val latest: () -> Pair<RequestDockFeature.State<*>, Boolean>,
    /** TalkBack is on. */
    val screenReaderOn: () -> Boolean,
    /** The docked question's fold, which the session's surface follows. */
    val gesture: RequestPanelGesture,
) {
    fun responding(id: String) = id in dock.respondingRequestIDs

    @Composable
    fun Content(shown: Shown) {
        val permission = shown.permission
        val form = shown.form
        when {
            permission != null -> PermissionPanel(permission, shown.call)
            form != null -> if (form.fitsDock) FormPanel(form, shown.answers, shown.page) else SheetPanel(form)
        }
    }

    // Header

    @Composable
    fun Header(
        caption: AnnotatedString,
        spoken: String,
        closes: Boolean,
        progress: Pair<Int, Int>? = null,
        onBack: (() -> Unit)? = null,
        busy: Boolean = false,
        fold: (() -> Float)? = null,
    ) {
        val colors = OpenCodeTheme.colors
        val idle = dock.respondingRequestIDs.isEmpty()
        val backText = stringResource(CoreR.string.common_back)
        val needsInputText = stringResource(R.string.composer_request_needs_input_description, spoken)
        // Top-aligned, each item centred in its own 48 dp, so × stays on the corner arc however tall larger text makes the row.
        Row(
            Modifier.fillMaxWidth().padding(top = RequestDockMetrics.headerTop).heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (onBack != null) {
                Box(
                    Modifier
                        .outset(start = 14.dp, end = 8.dp)
                        .size(48.dp)
                        // The panel's `.disabled(responding)` covers Back too.
                        .clickable(enabled = enabled && !busy, onClickLabel = backText, role = Role.Button, onClick = onBack)
                        .semantics { contentDescription = backText }
                        .testTag("request-back"),
                    contentAlignment = Alignment.Center,
                ) {
                    OpenCodeIcon(SFSymbol.ChevronLeft, contentDescription = null, tint = colors.text)
                }
            }
            Box(Modifier.weight(1f).heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    caption,
                    style = OpenCodeTextStyles.footnote.copy(fontWeight = FontWeight.Medium),
                    color = colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .focusRequester(focus)
                        .focusable()
                        .semantics {
                            contentDescription = needsInputText
                            heading()
                        },
                )
            }
            if (progress != null && progress.second > 1) {
                val (page, count) = progress
                // Android: gone early in a fold, before the bar's count and arrow arrive in its place.
                val leaves = if (fold == null) Modifier else Modifier.graphicsLayer { alpha = RequestCollapseFade.opacity(fold(), isBar = false, window = 0f..0.3f) }
                Box(Modifier.heightIn(min = 48.dp).then(leaves), contentAlignment = Alignment.Center) {
                    RollingNumber(
                        text = stringResource(R.string.composer_request_page_progress, page + 1, count),
                        style = OpenCodeTextStyles.footnote.copy(fontFeatureSettings = "tnum"),
                        color = colors.secondaryText,
                        alignment = RollingNumberAlignment.End,
                        accessibilityLabel = stringResource(R.string.composer_request_page_progress_description, page + 1, count),
                    )
                }
            }
            if (dock.reviewRequestIDs.size > 1) QueueMenu()
            if (closes) CloseButton(busy)
        }
    }

    @Composable
    fun CloseButton(busy: Boolean) {
        val colors = OpenCodeTheme.colors
        val idle = dock.respondingRequestIDs.isEmpty()
        val dismissText = stringResource(CoreR.string.common_dismiss)
        // The desktop's small corner keeps that place.
        Box(
            Modifier
                .outset(end = RequestDockMetrics.edge - RequestDockMetrics.headerTop)
                .size(48.dp)
                // TalkBack reads “Double-tap to tell…”.
                .clickable(enabled = enabled && idle && !busy, onClickLabel = stringResource(R.string.composer_request_dismiss_action), role = Role.Button) {
                    send(RequestDockFeature.Action.DismissTapped)
                }
                .semantics { contentDescription = dismissText }
                .testTag("request-dismiss"),
            contentAlignment = Alignment.Center,
        ) {
            // The desktop's ghost icon button: a bare glyph at rest (a filled square read as a
            // second, heavier control beside the counter); the press ripple shows its target.
            Box(
                Modifier.size(RequestDockMetrics.closeSize),
                contentAlignment = Alignment.Center,
            ) {
                OpenCodeIcon(SFSymbol.Xmark, contentDescription = null, size = 16.dp, tint = colors.secondaryText)
            }
        }
    }

    /** A subagent's request names it; a global or MCP form names its server when present. */
    fun captionText(caption: String, sessionID: String, mcpServer: String? = null): String =
        RequestDockAnswer.caption(caption, sessionID, mcpServer, context.currentSessionID, context.familySessions)

    @Composable
    fun QueueMenu() {
        val colors = OpenCodeTheme.colors
        var open by remember { mutableStateOf(false) }
        val count = dock.reviewRequestIDs.size
        val pendingText = stringResource(R.string.composer_request_pending_description)
        val questionText = stringResource(R.string.composer_request_question)
        Box {
            Row(
                Modifier
                    .widthIn(min = 48.dp)
                    .heightIn(min = 48.dp)
                    .clickable(enabled = enabled && dock.respondingRequestIDs.isEmpty(), role = Role.Button) { open = true }
                    .semantics {
                        contentDescription = pendingText
                        stateDescription = "$count"
                    }
                    .testTag("request-queue"),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OpenCodeIcon(SFSymbol.TrayFull, contentDescription = null, size = 18.dp, tint = colors.secondaryText)
                Text("$count", style = OpenCodeTextStyles.footnote.copy(fontFeatureSettings = "tnum"), color = colors.secondaryText)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (form in dock.formRequests) {
                    val title = if (form.isQuestion) form.fields.firstOrNull()?.title?.ifEmpty { null } ?: questionText else form.title
                    DropdownMenuItem(text = { Text(title) }, onClick = {
                        open = false
                        send(RequestDockFeature.Action.ReviewRequestSelected(form.id))
                    })
                }
                for (permission in dock.permissionRequests) {
                    DropdownMenuItem(text = { Text(OpenCodeToolCall.displayName(permission.action)) }, onClick = {
                        open = false
                        send(RequestDockFeature.Action.ReviewRequestSelected(permission.id))
                    })
                }
            }
        }
    }

    // Questions and small forms

    @Composable
    fun FormPanel(form: OpenCodeFormRequest, answers: Map<String, OpenCodeFormValue>, storedPage: Int) {
        val collapseText = stringResource(R.string.composer_request_collapse_action)
        val fields = form.activeFields(answers)
        val pageCount = if (form.isQuestion) fields.size else 1
        val page = min(storedPage, max(pageCount - 1, 0))
        val field = fields.getOrNull(page)
        val caption = captionText(
            if (form.isQuestion) field?.title?.ifEmpty { null } ?: stringResource(R.string.composer_request_question) else form.title,
            form.sessionID,
            form.mcpServer,
        )
        val pageKey = "${form.id}/$page"
        val firstPage = remember { pageKey }
        val autoAdvance = if (form.isQuestion && page < pageCount - 1) page + 1 else null
        // The page on screen until the last composition, so a new page can slide the old one out.
        var lastPage by remember { mutableStateOf<PageSnapshot?>(null) }
        val leavingPage = remember(pageKey) { lastPage?.takeIf { it.key != pageKey } }
        val current = PageSnapshot(pageKey, field, answers, autoAdvance)
        SideEffect { lastPage = current }
        // TalkBack starts again at the caption.
        LaunchedEffect(pageKey) {
            if (pageKey != firstPage && enabled && screenReaderOn()) runCatching { focus.requestFocus() }
        }
        val collapsed = form.id in dock.collapsedRequestIDs
        val fold = rememberFold(form.id, collapsed)
        RequestCollapseLayout(
            progress = { fold.progress.value },
            gesture = gesture,
            collapsed = collapsed,
            modifier = fold.dragModifier(form.id, enabled),
            open = {
                Box(
                    Modifier
                        .graphicsLayer { alpha = RequestCollapseFade.opacity(fold.progress.value, isBar = false) }
                        .then(
                            if (collapsed) Modifier.clearAndSetSemantics {}
                            else Modifier.semantics {
                                customActions = listOf(CustomAccessibilityAction(collapseText) { fold.settle(form.id, collapsed = true); true })
                            },
                        ),
                ) {
                    OpenFormPanel(form, answers, page, pageCount, field, caption, leavingPage, pageKey, firstPage, autoAdvance, fold = { fold.progress.value })
                }
            },
            bar = {
                Box(
                    Modifier
                        .graphicsLayer { alpha = RequestCollapseFade.opacity(fold.progress.value, isBar = true) }
                        .then(if (collapsed) Modifier else Modifier.clearAndSetSemantics {}),
                ) {
                    CollapsedBar(form, caption, answers, progress = { fold.progress.value }) { fold.settle(form.id, collapsed = false) }
                }
            },
        )
    }

    @Composable
    fun OpenFormPanel(
        form: OpenCodeFormRequest,
        answers: Map<String, OpenCodeFormValue>,
        page: Int,
        pageCount: Int,
        field: OpenCodeFormField?,
        caption: String,
        leavingPage: PageSnapshot?,
        pageKey: String,
        firstPage: String,
        autoAdvance: Int?,
        fold: () -> Float,
    ) {
        RequestPanelFallback(Modifier.padding(bottom = 6.dp)) {
            Header(
                AnnotatedString(caption), caption, closes = true,
                progress = page to pageCount,
                fold = fold,
                onBack = if (page > 0) ({ changePage(form.id, page - 1) }) else null,
                busy = responding(form.id),
            )
            // The old page fades out sliding the other way, drawn over the new one (it takes no
            // room in the stack, so the panel measures the new page alone).
            if (leavingPage != null) {
                key("leaving/${leavingPage.key}/$pageKey") { LeavingPage(form, leavingPage) }
            }
            // Each page measures afresh and slides in from the side it comes from.
            key(pageKey) {
                val entrance = rememberEntrance(animate = pageKey != firstPage, slide = true)
                if (field != null) {
                    FieldView(field, form, answers, autoAdvance, entrance)
                }
            }
            ErrorLines(form.id, isForm = true, inline = field?.let { inlineValidation(it, answers) })
        }
    }

    /**
     * The question folded down: its caption, how many are left, and ×. A tap or a drag up brings
     * it back. Android adds the expand arrow Material's collapsed content shows.
     */
    @Composable
    fun CollapsedBar(
        form: OpenCodeFormRequest,
        caption: String,
        answers: Map<String, OpenCodeFormValue>,
        progress: () -> Float,
        onExpand: () -> Unit,
    ) {
        val colors = OpenCodeTheme.colors
        val left = RequestCollapse.remaining(form, answers)
        val count = if (form.isQuestion && left > 0) pluralStringResource(R.plurals.composer_request_questions_left, left, left) else null
        val needsInputText = stringResource(R.string.composer_request_needs_input_description, caption)
        Row(
            Modifier.fillMaxWidth().padding(top = RequestDockMetrics.headerTop, bottom = 6.dp).heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .outset(start = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled, onClickLabel = stringResource(R.string.composer_request_expand_action), role = Role.Button, onClick = onExpand)
                    .padding(start = 12.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = needsInputText
                        if (count != null) stateDescription = count
                    }
                    .testTag("request-expand"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    caption,
                    style = OpenCodeTextStyles.footnote.copy(fontWeight = FontWeight.Medium),
                    color = colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (count != null) {
                    Text(
                        count,
                        style = OpenCodeTextStyles.footnote.copy(fontFeatureSettings = "tnum"),
                        color = colors.secondaryText,
                        maxLines = 1,
                        modifier = Modifier.graphicsLayer { alpha = RequestCollapseFade.opacity(progress(), isBar = true, window = 0.7f..1f) },
                    )
                }
                OpenCodeIcon(
                    SFSymbol.ChevronUp,
                    contentDescription = null,
                    size = 20.dp,
                    tint = colors.secondaryText,
                    modifier = Modifier.graphicsLayer { alpha = RequestCollapseFade.opacity(progress(), isBar = true, window = 0.7f..1f) },
                )
            }
            CloseButton(busy = responding(form.id))
        }
    }

    @Composable
    fun rememberFold(formID: String, collapsed: Boolean): RequestFold {
        val haptics = remember { dependency(HapticsClient) }
        val density = LocalDensity.current.density
        val scope = rememberCoroutineScope()
        val fold = remember(formID) { RequestFold(if (collapsed) 1f else 0f) }
        fold.bind(gesture, scope, haptics, density, reducedMotion, send) { latest().first.collapsedRequestIDs }
        // The reducer folded or opened it (a failed send opens it): it springs there.
        LaunchedEffect(fold, collapsed) { fold.follow(collapsed) }
        DisposableEffect(fold) {
            onDispose {
                // The panel went mid-drag: its drag's end won't come.
                fold.drag = null
                gesture.reset()
            }
        }
        return fold
    }

    fun changePage(formID: String, page: Int) {
        send(RequestDockFeature.Action.QuestionPageChanged(formID, page))
    }

    /**
     * The page just left, inert and hidden from TalkBack, overlaid where the new page starts until
     * it has faded.
     */
    @Composable
    fun LeavingPage(form: OpenCodeFormRequest, snapshot: PageSnapshot) {
        val field = snapshot.field ?: return
        val progress = remember { Animatable(0f) }
        var done by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            progress.animateTo(1f, Motion.layout.animation(reduceMotion = reducedMotion))
            done = true
        }
        if (done) return
        val offset = if (reducedMotion) 0.dp else (-24).dp
        Column(
            Modifier
                .fillMaxWidth()
                .overlayWithoutHeight()
                .graphicsLayer {
                    alpha = 1f - progress.value
                    translationX = progress.value * offset.toPx()
                }
                .clearAndSetSemantics {},
        ) {
            FieldView(field, form, snapshot.answers, snapshot.autoAdvance, Modifier, interactive = false)
        }
    }

    @Composable
    fun FieldView(
        field: OpenCodeFormField,
        form: OpenCodeFormRequest,
        answers: Map<String, OpenCodeFormValue>,
        autoAdvance: Int?,
        entrance: Modifier,
        interactive: Boolean = true,
    ) {
        val colors = OpenCodeTheme.colors
        val options = RequestDockAnswer.options(field)
        val multiple = field.type == OpenCodeFormFieldType.Multiselect
        val fontScale = LocalDensity.current.fontScale
        val scale = min(fontScale, 1f)
        val formEnabled = interactive && enabled && !responding(form.id)
        // A long question scrolls on its own; the choices stay in reach.
        FadingScroll(
            cap = RequestDockMetrics.questionCap * scale * (if (isKeyboardUp) 0.5f else 1f),
            floor = RequestDockMetrics.questionFloor,
            priority = RequestPanelFit.Priority.question,
            identifier = "request-question",
            modifier = entrance,
        ) {
            Text(
                RequestDockAnswer.prompt(field, form),
                style = OpenCodeTextStyles.body,
                color = colors.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (options.isEmpty()) 10.dp else 4.dp)
                    .padding(horizontal = RequestDockMetrics.edge)
                    .semantics { heading() },
            )
        }
        if (options.isNotEmpty()) {
            FadingScroll(
                cap = RequestDockMetrics.rowsCap * scale * (if (isKeyboardUp) 0.5f else 1f),
                floor = RequestDockMetrics.scrollFloor,
                priority = RequestPanelFit.Priority.choices,
                identifier = "request-options",
                modifier = entrance,
            ) {
                Column(Modifier.padding(horizontal = RequestDockMetrics.edge)) {
                    options.forEachIndexed { index, option ->
                        if (index > 0) RequestOptionRowDivider()
                        RequestOptionRow(
                            title = option.label,
                            description = option.description,
                            selected = RequestDockAnswer.isSelected(option.value, answers[field.key]),
                            multiple = multiple,
                            enabled = formEnabled,
                            modifier = Modifier.testTag("request-option-$index"),
                        ) { pick(option.value, field, form, autoAdvance) }
                    }
                }
            }
            if (multiple) {
                Text(
                    stringResource(R.string.composer_request_choose_multiple),
                    style = OpenCodeTextStyles.footnote,
                    color = colors.secondaryText,
                    modifier = entrance.padding(bottom = 4.dp),
                )
            }
        }
    }

    /** A typed answer's problem, shown under the panel as you type. */
    fun inlineValidation(field: OpenCodeFormField, answers: Map<String, OpenCodeFormValue>): String? {
        if (!RequestDockAnswer.hasAnswer(answers[field.key])) return null
        return field.validationError(answers[field.key])
    }

    fun pick(value: String, field: OpenCodeFormField, form: OpenCodeFormRequest, autoAdvance: Int?) {
        val current = dock.formAnswers[form.id]?.get(field.key)
        val next = RequestDockAnswer.pickedValue(value, field, current)
        if (next != current) dependency(HapticsClient).play(Haptic.selection)
        send(RequestDockFeature.Action.FormValueChanged(form.id, field.key, next))
        // A single choice on an earlier page moves on, as the desktop's does.
        if (field.type == OpenCodeFormFieldType.Multiselect || autoAdvance == null) return
        pickScope.launch {
            delay(if (reducedMotion) 120 else 280)
            val (latestDock, dictating) = latest()
            // Only if the pick still stands and nothing is being dictated.
            if (latestDock.dockRequestID != form.id || (latestDock.questionPageIndices[form.id] ?: 0) != autoAdvance - 1) return@launch
            if (form.id in latestDock.respondingRequestIDs || dictating) return@launch
            if (!RequestDockAnswer.isSelected(value, latestDock.formAnswers[form.id]?.get(field.key))) return@launch
            changePage(form.id, autoAdvance)
        }
    }

    /** A form too large for the panel opens the request sheet. */
    @Composable
    fun SheetPanel(form: OpenCodeFormRequest) {
        val colors = OpenCodeTheme.colors
        val caption = captionText("", form.sessionID, form.mcpServer)
        Column(Modifier.fillMaxWidth()) {
            Header(AnnotatedString(caption.ifEmpty { stringResource(R.string.composer_request_form) }), spoken = caption.ifEmpty { form.title }, closes = true, busy = responding(form.id))
            Text(form.title, style = OpenCodeTextStyles.body, color = colors.text, modifier = Modifier.fillMaxWidth())
            ErrorLines(form.id, isForm = true)
            Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.CenterEnd) {
                RequestMonochromeButton(
                    onClick = { send(RequestDockFeature.Action.SheetTapped(form.id)) },
                    enabled = enabled && !responding(form.id),
                    modifier = Modifier.testTag("request-review"),
                ) { Text(stringResource(R.string.composer_request_review), maxLines = 1) }
            }
        }
    }

    // Permissions

    @Composable
    fun PermissionPanel(permission: OpenCodePermissionRequest, call: OpenCodeToolCall?) {
        val colors = OpenCodeTheme.colors
        val name = OpenCodeToolCall.displayName(permission.action)
        // What it does, in the tool call's own words; an edit names its file.
        val description = call?.input?.obj?.get("description")?.string?.trim()?.ifEmpty { null }
            ?: permission.filePreview?.let { lastPathComponent(it.first) }?.ifEmpty { null }
        val owner = captionText(name, permission.sessionID)
        val caption = buildAnnotatedString {
            append(owner)
            if (description != null) {
                append(" · ")
                withStyle(SpanStyle(color = colors.text)) { append(description) }
            }
        }
        val spoken = description?.let { "$owner, $it" } ?: owner
        val command = call?.input?.obj?.get("command")?.string?.ifEmpty { null }
        val expanded = state.expandedPermissionID == permission.id
        val fontScale = LocalDensity.current.fontScale
        RequestPanelFallback(
            // Opens on `layout`, closes on `collapse`. Animate the column, not the footer: the
            // caption rides its top and Allow stays on the bar.
            columnModifier = Modifier.animateContentSize(
                Motion.layout.animation(if (expanded) MotionPhase.Enter else MotionPhase.Exit, reducedMotion),
            ),
            footer = {
                // Allow stays in reach: past the floors the rest scrolls above it. The actions stop growing at the first accessibility size.
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, min(density.fontScale, RequestDockMetrics.actionsMaxFontScale))) {
                    Box(Modifier.padding(vertical = 10.dp)) { PermissionButtons(permission) }
                }
            },
        ) {
            Header(caption, spoken, closes = false)
            SummaryRow(permission, command, expanded)
            if (expanded) {
                val entrance = rememberEntrance(animate = true, slide = false)
                FadingScroll(
                    cap = RequestDockMetrics.commandCap * min(fontScale, 1f) * (if (isKeyboardUp) 0.6f else 1f),
                    floor = RequestDockMetrics.scrollFloor,
                    priority = RequestPanelFit.Priority.command,
                    identifier = "request-command",
                    modifier = entrance,
                ) {
                    PermissionPreview(permission, command)
                }
            }
            ErrorLines(permission.id, isForm = false)
        }
    }

    /** What's asked, in a line or two; tapping it shows the whole command or diff. */
    @Composable
    fun SummaryRow(permission: OpenCodePermissionRequest, command: String?, expanded: Boolean) {
        val colors = OpenCodeTheme.colors
        val summary = remember(permission, command) { PermissionSummary(permission, command) }
        // It turns over; under Reduce Motion the glyph swaps instead.
        val turn by animateFloatAsState(
            MotionReduce.rotation(if (expanded) 180f else 0f, reducedMotion),
            Motion.layout.animation(if (expanded) MotionPhase.Enter else MotionPhase.Exit, reducedMotion),
            label = "summaryChevron",
        )
        val expandedText = stringResource(R.string.composer_request_summary_expanded)
        val collapsedText = stringResource(R.string.composer_request_summary_collapsed)
        val hideDetailsText = stringResource(R.string.composer_request_summary_hide_action)
        val showCommandText = stringResource(R.string.composer_request_summary_show_command_action)
        val showDetailsText = stringResource(R.string.composer_request_summary_show_details_action)
        val chevron = if (reducedMotion && expanded) SFSymbol.ChevronUp else SFSymbol.ChevronDown
        val textStyle = if (summary.isCode) RequestTranscriptMetrics.toolFont else OpenCodeTextStyles.subheadline
        val firstLine = with(LocalDensity.current) { if (textStyle.lineHeight.isSp) textStyle.lineHeight.toDp() else 0.dp }
        // Centred in its 48 dp while one line; the chevron stays on the first line's baseline as it wraps.
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(enabled = enabled, role = Role.Button) {
                    state.expandedPermissionID = if (expanded) null else permission.id
                }
                .clearAndSetSemantics {
                    contentDescription = summary.text
                    stateDescription = if (expanded) expandedText else collapsedText
                    onClick(label = if (expanded) hideDetailsText else if (summary.isCode) showCommandText else showDetailsText) {
                        state.expandedPermissionID = if (expanded) null else permission.id
                        true
                    }
                    testTag = "request-summary"
                }
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Text(
                    summary.text,
                    style = textStyle,
                    color = colors.text,
                    maxLines = if (expanded) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.heightIn(min = firstLine), contentAlignment = Alignment.Center) {
                    // `.caption.weight(.semibold)`.
                    OpenCodeIcon(chevron, contentDescription = null, size = 12.dp, tint = colors.secondaryText, modifier = Modifier.rotate(turn))
                }
            }
        }
    }

    @Composable
    fun PermissionButtons(permission: OpenCodePermissionRequest) {
        val busy = responding(permission.id)
        val actionsEnabled = enabled && !busy
        val save = permission.save?.takeIf { it.isNotEmpty() }
        val showScopeText = stringResource(R.string.composer_request_show_scope_action)
        // Side by side, one size, while the titles fit; stacked at larger sizes.
        SideBySideOrStacked(spacing = 8.dp) {
            RequestPermissionButton(
                primary = false,
                enabled = actionsEnabled,
                onClick = {
                    state.sentAction = DockAction.Once
                    send(RequestDockFeature.Action.PermissionReplyTapped(permission.id, OpenCodePermissionReply.Once))
                },
                modifier = Modifier.testTag("request-allow-once"),
            ) { ButtonLabel(stringResource(R.string.composer_request_allow_once), sending = busy && state.sentAction == DockAction.Once) }
            if (save != null) {
                val scope = save.joinToString(", ")
                Box {
                    // A tap shows what it covers; the popover's Always Allow gives it.
                    RequestPermissionButton(
                        primary = true,
                        enabled = actionsEnabled,
                        onClick = { state.scopeShownForID = permission.id },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                stateDescription = scope
                                // Hint: “Shows what won’t ask again”.
                                onClick(label = showScopeText) {
                                    if (actionsEnabled) state.scopeShownForID = permission.id
                                    actionsEnabled
                                }
                            }
                            .testTag("request-always"),
                    ) { ButtonLabel(stringResource(R.string.composer_request_always_allow), sending = busy && state.sentAction == DockAction.Always) }
                    if (state.scopeShownForID == permission.id) {
                        Popup(
                            popupPositionProvider = with(LocalDensity.current) { remember(density) { AbovePositionProvider(16.dp.roundToPx()) } },
                            onDismissRequest = { state.scopeShownForID = null },
                            properties = PopupProperties(focusable = true),
                        ) {
                            Surface(
                                shape = OpenCodeSurfaces.popoverShape,
                                color = OpenCodeSurfaces.color,
                                border = OpenCodeSurfaces.border,
                                shadowElevation = OpenCodeSurfaces.popoverShadow,
                            ) {
                                PermissionScopeConfirm(save, confirm = {
                                    state.scopeShownForID = null
                                    state.sentAction = DockAction.Always
                                    send(RequestDockFeature.Action.PermissionReplyTapped(permission.id, OpenCodePermissionReply.Always))
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ButtonLabel(title: String, sending: Boolean) {
        val sendingText = stringResource(R.string.composer_request_sending_description)
        // With the button's own padding, a 48 dp target.
        Box(Modifier.fillMaxWidth().heightIn(min = RequestTunables.labelHeight), contentAlignment = Alignment.Center) {
            Text(
                title,
                style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                modifier = Modifier.graphicsLayer { alpha = if (sending) 0f else 1f },
            )
            if (sending) {
                CircularProgressIndicator(
                    Modifier.size(18.dp).semantics { contentDescription = sendingText },
                    color = LocalContentColor.current,
                    strokeWidth = 2.dp,
                )
            }
        }
    }

    // Shared

    @Composable
    fun ErrorLines(id: String, isForm: Boolean, inline: String? = null) {
        val error = dock.requestErrors[id]
        if (error != null) {
            val title = dock.replyFailureTitle(id, isForm)
            if (title != null) {
                // Send has become Retry (or × can go again), so a line is all it needs.
                RequestErrorLine(title, detail = error, modifier = Modifier.padding(vertical = 6.dp))
            } else if (error != inline) {
                RequestErrorLine(error, modifier = Modifier.padding(vertical = 6.dp))
            }
        }
        if (inline != null) RequestErrorLine(inline, modifier = Modifier.padding(vertical = 6.dp))
    }

    @Composable
    fun rememberEntrance(animate: Boolean, slide: Boolean): Modifier {
        val progress = remember { Animatable(if (animate) 0f else 1f) }
        LaunchedEffect(Unit) {
            if (progress.value < 1f) progress.animateTo(1f, Motion.layout.animation(reduceMotion = reducedMotion))
        }
        val offset = if (slide && !reducedMotion) 24.dp else 0.dp
        return Modifier.graphicsLayer {
            alpha = progress.value
            translationX = (1f - progress.value) * offset.toPx()
        }
    }
}

/**
 * Scrolls what's taller than [cap], sized to what it holds; its edges fade only where there's more,
 * so nothing slides under a hard edge. In a panel short of room it gives up height, down to
 * [floor], in [priority] order. It reaches the panel's edges, so bands inside run full width;
 * content pads itself to [RequestDockMetrics.edge].
 */
@Composable
internal fun FadingScroll(
    cap: Dp,
    floor: Dp,
    priority: Double,
    identifier: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scroll = rememberScrollState()
    val fade = 20.dp
    Box(
        modifier
            .requestPanelPart(cap, floor, priority)
            .heightIn(max = cap)
            .bleedHorizontally(RequestDockMetrics.edge)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // A question that fits never fades, even mid-transition.
                val scrolls = scroll.maxValue > 1
                val top = scrolls && scroll.value > 1
                val bottom = scrolls && scroll.maxValue - scroll.value > 1
                if (!top && !bottom || size.height <= 0f) return@drawWithContent
                val edge = min(fade.toPx() / size.height, 0.5f)
                drawRect(
                    Brush.verticalGradient(
                        0f to (if (top) Color.Transparent else Color.Black),
                        edge to Color.Black,
                        1f - edge to Color.Black,
                        1f to (if (bottom) Color.Transparent else Color.Black),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            .verticalScroll(scroll)
            .testTag(identifier),
    ) { content() }
}

/** Reaches [edge] past each side of what it's given. */
internal fun Modifier.bleedHorizontally(edge: Dp): Modifier = layout { measurable, constraints ->
    val extra = (edge * 2).roundToPx()
    val widened = if (constraints.hasBoundedWidth) {
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra)
    } else constraints
    val placeable = measurable.measure(widened)
    val width = (placeable.width - extra).coerceIn(constraints.minWidth, constraints.maxWidth)
    layout(width, placeable.height) { placeable.place(-edge.roundToPx(), 0) }
}

/** The monochrome prominent button the request surfaces use: the theme's Send button colours. */
@Composable
internal fun RequestMonochromeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val colors = OpenCodeTheme.colors
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = ai.opencode.core.ui.OpenCodeButtonDefaults.height),
        enabled = enabled,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = colors.sendButtonBackground,
            contentColor = colors.sendButtonForeground,
        ),
        contentPadding = ai.opencode.core.ui.OpenCodeButtonDefaults.contentPadding,
        content = content,
    )
}

@Composable
private fun SideBySideOrStacked(spacing: Dp, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = spacing.roundToPx()
        val count = measurables.size
        if (count == 0) return@Layout layout(0, 0) {}
        val slot = if (count > 1) (width - gap * (count - 1)) / count else width
        // ViewThatFits compares each action's ideal width: a title's whole line, not its longest word
        // (minIntrinsicWidth), which let “Always Allow” clip to “Always” at large font sizes.
        val fits = count == 1 || measurables.all { it.maxIntrinsicWidth(Constraints.Infinity) <= slot }
        if (fits) {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(slot).copy(maxHeight = constraints.maxHeight)) }
            val height = placeables.maxOf { it.height }
            layout(width, height) {
                placeables.forEachIndexed { index, placeable -> placeable.place(index * (slot + gap), (height - placeable.height) / 2) }
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(width)) }
            val height = placeables.sumOf { it.height } + gap * (count - 1)
            layout(width, height) {
                var y = 0
                placeables.forEach { it.place(0, y); y += it.height + gap }
            }
        }
    }
}

/** Reaches past its slot by [start] / [end] without taking their room. */
private fun Modifier.outset(start: Dp = 0.dp, end: Dp = 0.dp): Modifier = layout { measurable, constraints ->
    val reach = start.roundToPx() + end.roundToPx()
    // Measured with the reach added, so the reported width still meets a `weight`'s minimum; under it,
    // Compose would centre the row in its slot and shift it by half the reach.
    val placeable = measurable.measure(constraints.offset(horizontal = reach))
    val width = max(placeable.width - reach, 0)
    layout(width, placeable.height) { placeable.place(-start.roundToPx(), 0) }
}

/** Places the Always Allow scope popover above its button (below when there's no room), inside the window. */
/** Above the anchor, centred on it, kept [margin] px inside the window (like a popover inside the safe area). */
private class AbovePositionProvider(private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(margin, max(margin, windowSize.width - popupContentSize.width - margin))
        val above = anchorBounds.top - popupContentSize.height - margin
        val y = if (above >= margin) above else min(anchorBounds.bottom + margin, max(margin, windowSize.height - popupContentSize.height - margin))
        return IntOffset(x, y)
    }
}
