package ai.opencode.feature.session

import ai.opencode.core.ui.openCodeHairline
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.diagnostics.LabMarks
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticEffect
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionTransition
import ai.opencode.core.motion.motionFrameLoop
import ai.opencode.core.motion.rememberFrameDurationSeconds
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.motion.transitionBlur
import ai.opencode.core.strings.localized
import ai.opencode.core.strings.localizedPlural
import ai.opencode.core.tuning.InternalBuild
import ai.opencode.core.ui.OpenCodeFonts
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.RollingNumber
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.AccessibilityFocusRequest
import ai.opencode.core.ui.accessibilityFocusRequest
import ai.opencode.core.ui.appFormattingLocale
import ai.opencode.core.ui.rememberAccessibilityFocusRequest
import ai.opencode.feature.transcript.list.TranscriptBorrowedDrag
import ai.opencode.feature.transcript.list.passesDragsToTranscript
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeShell
import androidx.activity.compose.BackHandler
import androidx.annotation.PluralsRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// IOS reads `SessionFeature` state inside the presentation; here every view takes immutable values
// and action lambdas so `SessionView` can wire it (`activeSubagents`, `backgroundShells`,
// `waitingSessionIDs`, `shellGlyphProbe`, and `.subagentTapped` / `.shellOutputTapped`).
// SessionView presents the shell output sheet.
//
// Liquid Glass → one M3 surface (`surfaceContainerHigh`) whose bounds morph between the
// capsule's and the panel's on the same springs. Lengths are dp.

// MARK: Anchor and covered pills

/**
 * The background-activity capsule's bounds, so the panel can grow out of it. Create one per session
 * screen with [rememberBackgroundWorkAnchor], mark the capsule's place with
 * [backgroundActivityAnchor], and hand the same anchor to [BackgroundWorkPresentation].
 */
@Stable
class BackgroundWorkAnchor {
    /** Root coordinates in px; null while the capsule's place isn't laid out. */
    internal var bounds by mutableStateOf<Rect?>(null)

    /** The capsule's place as laid out now (read at draw time, after this frame's layout). */
    internal var coordinates: androidx.compose.ui.layout.LayoutCoordinates? = null

    /**
     * What moves the capsule's place frame by frame (the request panel's room growing above the
     * composer), read while the surface draws so it redraws in the frame its place moves.
     */
    var motion: () -> Float = { 0f }
}

@Composable
fun rememberBackgroundWorkAnchor(): BackgroundWorkAnchor = remember { BackgroundWorkAnchor() }

/**
 * Marks the capsule the background-work panel anchors to. The panel never keeps a capsule, or its
 * touch target, where the place last was.
 */
fun Modifier.backgroundActivityAnchor(anchor: BackgroundWorkAnchor): Modifier = this then BackgroundActivityAnchorElement(anchor)

private data class BackgroundActivityAnchorElement(val anchor: BackgroundWorkAnchor) : ModifierNodeElement<BackgroundActivityAnchorNode>() {
    override fun create() = BackgroundActivityAnchorNode(anchor)

    override fun update(node: BackgroundActivityAnchorNode) {
        if (node.anchor === anchor) return
        node.release()
        node.anchor = anchor
    }
}

private class BackgroundActivityAnchorNode(var anchor: BackgroundWorkAnchor) : Modifier.Node(), GlobalPositionAwareModifierNode {
    private var marked: LayoutCoordinates? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        marked = coordinates
        anchor.coordinates = coordinates
        anchor.bounds = coordinates.boundsInRoot()
    }

    override fun onDetach() = release()

    /** Clears the mark unless another place has taken it since. */
    fun release() {
        val mine = marked ?: return
        marked = null
        if (anchor.coordinates !== mine) return
        anchor.coordinates = null
        anchor.bounds = null
    }
}

/** The open panel covers the composer pills beside and above the capsule. */
val LocalBackgroundWorkCoversPills = compositionLocalOf { false }

/** A composer pill the open panel grows over: it steps aside rather than show through the panel. */
fun Modifier.coveredByBackgroundWork(): Modifier = composed {
    val covered = LocalBackgroundWorkCoversPills.current
    if (covered) {
        this.alpha(0f).clearAndSetSemantics { }.pointerInput(Unit) { detectTapGestures { } }
    } else {
        this
    }
}

// MARK: Copy and sizes

/** The panel's copy and sizing rules. */
object BackgroundWorkSummary {
    /** “1 subagent · 2 shells”, as the capsule reads. */
    fun summary(agents: List<OpenCodeSession>, shells: List<OpenCodeShell>, scripts: List<BackgroundScript> = emptyList()): String =
        summary(agents.size, shells.size, scripts.size)

    fun summary(agents: Int, shells: Int, scripts: Int = 0): String {
        val parts = listOfNotNull(
            if (agents > 0) localizedPlural(R.plurals.session_background_work_subagent_count, agents, agents) else null,
            if (scripts > 0) localizedPlural(R.plurals.session_background_work_script_count, scripts, scripts) else null,
            if (shells > 0) localizedPlural(R.plurals.session_background_work_shell_count, shells, shells) else null,
        )
        return parts.reduceOrNull { a, b -> localized(R.string.session_background_work_summary, a, b) } ?: ""
    }

    /** Before the first measurement: rows, headers, and padding (dp). */
    fun estimatedHeight(agents: List<OpenCodeSession>, shells: List<OpenCodeShell>, scripts: List<BackgroundScript> = emptyList()): Float =
        (agents.size + shells.size + scripts.size) * 48f + (if (agents.isEmpty()) 0f else 30f) + (if (shells.isEmpty()) 0f else 30f) +
            (if (scripts.isEmpty()) 0f else 30f) + 20f
}

private const val PILL_GLYPH_GAP = 5f

private val countsStyle: TextStyle
    get() = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")

// MARK: Capsule

/**
 * The background-work capsule's place in the composer pill row: its size, invisible. The capsule
 * itself is drawn, and tapped, in [BackgroundWorkPresentation] above, where its surface can morph
 * into the panel's. Put [backgroundActivityAnchor] on this.
 */
@Composable
fun BackgroundWorkCapsuleSpace(agents: Int, shells: Int, modifier: Modifier = Modifier, scripts: Int = 0) {
    BackgroundWorkCapsuleLabel(
        agents = agents,
        shells = shells,
        scripts = scripts,
        modifier = modifier.padding(vertical = BackgroundWorkMotion.capsuleInset.dp).alpha(0f).clearAndSetSemantics { },
        sizingOnly = true,
    ) { Box(Modifier.width(18.dp)) }
}

/**
 * The capsule's glyph and counts, laid out like a composer pill's.
 *
 * @param sizingOnly An invisible layout copy: its counts don't roll.
 */
@Composable
fun BackgroundWorkCapsuleLabel(
    agents: Int,
    shells: Int,
    modifier: Modifier = Modifier,
    scripts: Int = 0,
    sizingOnly: Boolean = false,
    leading: @Composable () -> Unit,
) {
    Row(
        // The glyph's ink as far from the surface's leading edge as from its top and bottom,
        // concentric with the capsule's end.
        modifier
            .heightIn(min = 34.dp)
            .padding(start = BackgroundWorkTunables.glyphLeading.dp, end = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(PILL_GLYPH_GAP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(glyphWidth(agents, shells).dp).clearAndSetSemantics { }, contentAlignment = Alignment.CenterStart) { leading() }
        if (sizingOnly) {
            Text(BackgroundWorkSummary.summary(agents, shells, scripts), style = countsStyle, maxLines = 1, softWrap = false)
        } else {
            BackgroundWorkCounts(agents, shells, Modifier.offset(y = BackgroundWorkTunables.countNudge.dp), scripts)
        }
    }
}

/** One 18 dp disc, or the orb and the shell disc side by side. */
fun glyphWidth(agents: Int, shells: Int): Float = if (agents > 0 && shells > 0) 36 + BackgroundWorkTunables.pairGap else 18f

/**
 * “1 subagent · 2 shells”, animated where it stands as the counts change: digits roll, a group,
 * or the dot between groups opens or closes its own width on the resize spring
 * while it fades and blurs, and the rest slides over. Under Reduce Motion the words cross-fade.
 */
@Composable
fun BackgroundWorkCounts(agents: Int, shells: Int, modifier: Modifier = Modifier, scripts: Int = 0) {
    val reduceMotion = rememberReduceMotion()
    // What a group last counted, shown while it closes.
    var lastAgents by remember { mutableIntStateOf(max(1, agents)) }
    var lastShells by remember { mutableIntStateOf(max(1, shells)) }
    var lastScripts by remember { mutableIntStateOf(max(1, scripts)) }
    if (agents > 0 && agents != lastAgents) lastAgents = agents
    if (shells > 0 && shells != lastShells) lastShells = shells
    if (scripts > 0 && scripts != lastScripts) lastScripts = scripts
    if (reduceMotion) {
        Crossfade(BackgroundWorkSummary.summary(agents, shells, scripts), modifier, animationSpec = BackgroundWorkMotion.fade(), label = "BackgroundWorkCounts") {
            Text(it, style = countsStyle, maxLines = 1, softWrap = false)
        }
        return
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Opening(agents > 0) { CountGroup(if (agents > 0) agents else lastAgents, R.plurals.session_background_work_subagent_count) }
        // Desktop's order: subagents · scripts · shells.
        Opening(agents > 0 && scripts > 0) { Text(" · ", style = countsStyle, maxLines = 1, softWrap = false) }
        Opening(scripts > 0) { CountGroup(if (scripts > 0) scripts else lastScripts, R.plurals.session_background_work_script_count) }
        Opening((agents > 0 || scripts > 0) && shells > 0) { Text(" · ", style = countsStyle, maxLines = 1, softWrap = false) }
        Opening(shells > 0) { CountGroup(if (shells > 0) shells else lastShells, R.plurals.session_background_work_shell_count) }
    }
}

@Composable
private fun CountGroup(count: Int, @PluralsRes textRes: Int) {
    // The whole phrase (“2 shells”) comes from the plural resource; the number inside it rolls.
    val text = pluralStringResource(textRes, count, count)
    val number = count.toString()
    val numberAt = text.indexOf(number)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (numberAt < 0) {
            Text(text, style = countsStyle, maxLines = 1, softWrap = false)
            return@Row
        }
        val before = text.substring(0, numberAt)
        val after = text.substring(numberAt + number.length)
        if (before.isNotEmpty()) Text(before, style = countsStyle, maxLines = 1, softWrap = false)
        RollingNumber(count, style = countsStyle)
        if (after.isNotEmpty()) Text(after, style = countsStyle, maxLines = 1, softWrap = false)
    }
}

/**
 * Opens to its own width or closes to none, clipped to it, fading and blurring as it goes on
 * `swap`: quickly out, so what the clip crosses is already faint, and in a little behind the opening.
 */
@Composable
private fun Opening(isOpen: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = isOpen,
        enter = expandHorizontally(BackgroundWorkMotion.resize(), expandFrom = Alignment.Start) +
            fadeIn(Motion.swap.animation(MotionPhase.Enter)),
        exit = shrinkHorizontally(BackgroundWorkMotion.resize(), shrinkTowards = Alignment.Start) +
            fadeOut(Motion.swap.animation(MotionPhase.Exit)),
    ) {
        Box(Modifier.blurringWith(this)) { content() }
    }
}

/** Blurs 3 dp while arriving or leaving, never at rest. */
private fun Modifier.blurringWith(scope: AnimatedVisibilityScope): Modifier = composed {
    val blur by scope.transition.animateFloat(label = "blur") { if (it == EnterExitState.Visible) 0f else 3f }
    transitionBlur(blur.dp)
}

/**
 * The capsule's glyph: its most telling subagent's orb, the shell glyph driven by its shells'
 * combined output, or both side by side, the same disc placed alike.
 */
@Composable
fun BackgroundWorkGlyph(
    agents: List<OpenCodeSession>,
    shells: List<OpenCodeShell>,
    waiting: Set<String>,
    probe: ShellGlyphProbe?,
    modifier: Modifier = Modifier,
    /** Held still (the panel covers the capsule). */
    isPaused: Boolean = false,
    scripts: List<BackgroundScript> = emptyList(),
) {
    val reduceMotion = rememberReduceMotion()
    // Swapped in place as subagents come and go, like the words beside it. The disc's dot field
    // sits a little high in its frame: dropped, it is concentric with the capsule's end.
    Row(modifier.offset(y = BackgroundWorkTunables.orbGlyphNudgeY.dp), horizontalArrangement = Arrangement.spacedBy(BackgroundWorkTunables.pairGap.dp)) {
        val swap = MotionTransition.swap(reduceMotion)
        AnimatedVisibility(agents.isNotEmpty(), enter = swap.enter, exit = swap.exit) {
            AgentActivityGlyph(
                source = AgentActivitySource.summary(agents, emptyList(), waiting),
                isPaused = isPaused,
            )
        }
        // Scripts alone show the plain busy orb, as the desktop's chip does; their rows keep `</>`.
        AnimatedVisibility(agents.isEmpty() && shells.isEmpty() && scripts.isNotEmpty(), enter = swap.enter, exit = swap.exit) {
            AgentActivityGlyph(source = AgentActivitySource.Running, isPaused = isPaused)
        }
        AnimatedVisibility(shells.isNotEmpty(), enter = swap.enter, exit = swap.exit) {
            val ids = shells.map { it.id }
            ShellGlyph(
                shellIDs = ids,
                key = "capsule|" + ids.joinToString(","),
                probe = probe,
                isPaused = isPaused,
            )
        }
    }
}

@Composable
private fun ScriptGlyph() {
    // The 18 dp footprint of the orb and the shell disc, centred like them.
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        OpenCodeIcon(SFSymbol.ChevronLeftForwardslashChevronRight, contentDescription = null, size = 14.dp, tint = OpenCodeTheme.colors.secondaryText)
    }
}

// MARK: Presentation

/**
 * The surface's shape: the panel's corners ([BackgroundWorkMotion.panelRadius]), never more than
 * half its height.
 */
private class BackgroundWorkShape(private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = min(max(0f, radius * density.density), max(0f, size.height) / 2)
        return Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r)))
    }
}

/** What the surface last showed. When the work ends, the capsule and its rows leave the session at once; the panel closes over what it had. */
private class Retained {
    var capsule: Rect? = null
    var agents: List<OpenCodeSession> = emptyList()
    var shells: List<OpenCodeShell> = emptyList()
    var scripts: List<BackgroundScript> = emptyList()
}

/**
 * The background-work panel above the composer. Wraps [content] (the session screen) and draws the
 * capsule, the scrim and the panel over it, anchored to the capsule's place marked with
 * [backgroundActivityAnchor].
 *
 * @param anchor the capsule's place, shared with [BackgroundWorkCapsuleSpace].
 * @param sessionID the session shown; a change resets the surface at once (the drawer swaps sessions in place).
 * @param agents running subagents (`SessionFeature.State.activeSubagents`).
 * @param shells background shells (`SessionFeature.State.backgroundShells`).
 * @param waitingSessionIDs subagents waiting on the user (`waitingSessionIDs`).
 * @param probe where shells' output is read (`shellGlyphProbe`).
 * @param isPresented the panel is open; [onPresentedChange] asks to change it (the capsule, a
 *   tap on the scrim, a drag, Back, or the work ending).
 * @param isCovered a panel grown out of the composer (the model list, the level picker) stands
 *   over the capsule's place, so it hides like the pills.
 * @param isPaused the session is covered (a pushed page, Settings): the glyphs hold still and stop probing.
 * @param inCall a voice call starts: the panel closes.
 * @param onToggle before the capsule toggles the panel (the composer gives up focus).
 * @param onOpenSubagent a subagent row tapped (`.subagentTapped(id)`); the panel closes first.
 * @param onOpenShell a shell row tapped (`.shellOutputTapped(id)`); the panel closes first.
 */
@Composable
fun BackgroundWorkPresentation(
    anchor: BackgroundWorkAnchor,
    sessionID: String?,
    agents: List<OpenCodeSession>,
    shells: List<OpenCodeShell>,
    waitingSessionIDs: Set<String>,
    probe: ShellGlyphProbe?,
    isPresented: Boolean,
    onPresentedChange: (Boolean) -> Unit,
    onOpenSubagent: (String) -> Unit,
    onOpenShell: (String) -> Unit,
    modifier: Modifier = Modifier,
    scripts: List<BackgroundScript> = emptyList(),
    onStopScript: (String) -> Unit = {},
    onStopSubagent: (String) -> Unit = {},
    stoppingIDs: Set<String> = emptySet(),
    isCovered: Boolean = false,
    isPaused: Boolean = false,
    inCall: Boolean = false,
    transcript: TranscriptBorrowedDrag? = null,
    onToggle: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val hasWork = agents.isNotEmpty() || shells.isNotEmpty() || scripts.isNotEmpty()
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val dark = OpenCodeTheme.colors.isDark
    val scope = rememberCoroutineScope()
    val frameDuration = rememberFrameDurationSeconds()
    val haptics = remember { dependency(HapticsClient) }
    val dragController = remember { BackgroundWorkDragController(haptics) }
    dragController.reduceMotion = reduceMotion
    val scrollState = rememberScrollState()

    var stage by remember { mutableStateOf(BackgroundWorkStage.closed) }
    // Landed open: accessibility meets the panel. (The orbs run throughout.)
    var settled by remember { mutableStateOf(false) }
    // The rows' height (dp), measured while the capsule shows, so the panel is laid out at its real height before it morphs.
    var contentHeight by remember { mutableStateOf<Float?>(null) }
    val contentHeightAnim = remember { Animatable(0f) }
    val retained = remember { mutableStateOf(Retained()) }
    // The session whose work the surface shows.
    var glassSession by remember { mutableStateOf<String?>(null) }
    // Each open, close, or reset starts a new motion; a superseded one's completion must not settle or close the surface mid-flight.
    var motion by remember { mutableIntStateOf(0) }
    // A session that opens on running work shows its capsule at once.
    var capsuleShown by remember { mutableStateOf(hasWork) }
    var capsuleMounted by remember { mutableStateOf(hasWork) }
    // TalkBack's way back to the capsule once the panel has folded into it.
    var capsuleFocus by remember { mutableStateOf<Int?>(null) }
    val capsuleFocusRequest = rememberAccessibilityFocusRequest(capsuleFocus)
    // 0 = the capsule's bounds, 1 = the panel's.
    val progress = remember { Animatable(0f) }
    val capsuleAlpha = remember { Animatable(if (hasWork) 1f else 0f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var overlayWidth by remember { mutableFloatStateOf(0f) }

    val latestPresented by rememberUpdatedState(isPresented)
    val latestHasWork by rememberUpdatedState(hasWork)
    val latestSession by rememberUpdatedState(sessionID)
    val latestCovered by rememberUpdatedState(isCovered)

    fun setCapsuleShown(shown: Boolean, animated: Boolean, completion: (() -> Unit)? = null) {
        capsuleShown = shown
        scope.launch {
            try {
                // The capsule's surface arrives on `microIn` and dematerializes on `microOut`.
                val spec = if (shown) Motion.microIn.animation<Float>(reduceMotion = reduceMotion) else Motion.microOut.animation(reduceMotion = reduceMotion)
                if (animated) capsuleAlpha.animateTo(if (shown) 1f else 0f, spec) else capsuleAlpha.snapTo(if (shown) 1f else 0f)
                completion?.invoke()
            } catch (_: CancellationException) {
            }
        }
    }

    fun displayedContentHeight(): Float? = contentHeight?.let { if (contentHeightAnim.isRunning) contentHeightAnim.value else it }

    // The rows' height. Open, a change (work starting or ending) moves the panel's top on `layout`.
    fun measured(height: Float) {
        if (height == contentHeight) return
        val animate = stage == BackgroundWorkStage.panel && contentHeight != null
        val grows = height > (contentHeight ?: 0f)
        val from = displayedContentHeight() ?: height
        contentHeight = height
        scope.launch {
            if (animate) {
                contentHeightAnim.snapTo(from)
                contentHeightAnim.animateTo(height, Motion.layout.animation(if (grows) MotionPhase.Enter else MotionPhase.Exit, reduceMotion))
            } else {
                contentHeightAnim.snapTo(height)
            }
        }
    }

    // The drawer swaps sessions in place: nothing of the last session's surface, mid-morph or measured, carries over to the next.
    fun reset() {
        motion += 1
        dragController.reset()
        scope.launch { scrollState.scrollTo(0) }
        if (latestPresented) onPresentedChange(false)
        stage = BackgroundWorkStage.closed
        settled = false
        contentHeight = null
        capsuleShown = latestHasWork
        capsuleMounted = latestHasWork
        scope.launch {
            progress.snapTo(0f)
            capsuleAlpha.snapTo(if (latestHasWork) 1f else 0f)
        }
        retained.value = Retained()
        glassSession = null
    }

    // The capsule's surface becomes the panel's. Mid-close, it turns back.
    fun open() {
        glassSession = latestSession
        motion += 1
        val current = motion
        settled = false
        scope.launch { scrollState.scrollTo(0) }
        dragController.reset()
        stage = BackgroundWorkStage.panel
        scope.launch {
            try {
                progress.animateTo(1f, BackgroundWorkMotion.morph(opening = true, reduceMotion = reduceMotion))
                if (current == motion && latestPresented && stage == BackgroundWorkStage.panel) settled = true
            } catch (_: CancellationException) {
            }
        }
    }

    // The work ended with the capsule at rest: its surface fades, and the overlay leaves once it has.
    fun dismissCapsule() {
        motion += 1
        val current = motion
        setCapsuleShown(false, animated = true) {
            if (current == motion && !latestHasWork) capsuleMounted = false
        }
    }

    // The panel morphs back into the capsule on the spring it opened on. Without a capsule to land in, it leaves where it is.
    fun close(releaseVelocity: Float = 0f, remainingDistance: Float = 180f) {
        if (stage == BackgroundWorkStage.closed) return
        // Opening a subagent swaps the session before the close begins.
        if (latestSession != glassSession) return reset()
        dragController.stopReturn()
        settled = false
        motion += 1
        val current = motion
        if (!latestHasWork) {
            // The panel's frame and rows are kept (stage `capsule`) until it has left.
            stage = BackgroundWorkStage.capsule
            capsuleShown = false
            scope.launch {
                try {
                    launch { capsuleAlpha.animateTo(0f, Motion.microOut.animation(reduceMotion = reduceMotion)) }
                    progress.animateTo(0f, Motion.microOut.animation(reduceMotion = reduceMotion))
                    if (current == motion && !latestPresented && stage == BackgroundWorkStage.capsule) {
                        stage = BackgroundWorkStage.closed
                        dragController.reset()
                        if (!latestHasWork) capsuleMounted = false
                    }
                } catch (_: CancellationException) {
                }
            }
            return
        }
        if (InternalBuild.isDebug) LabMarks.mark("background_work_close")
        val (spec, velocity) = BackgroundWorkMotion.closeAnimation(releaseVelocity, remainingDistance, reduceMotion)
        stage = BackgroundWorkStage.capsule
        if (latestCovered) setCapsuleShown(false, animated = !reduceMotion)
        scope.launch {
            try {
                progress.animateTo(0f, spec, initialVelocity = -velocity)
                if (current == motion && !latestPresented && stage == BackgroundWorkStage.capsule) {
                    stage = BackgroundWorkStage.closed
                    dragController.reset()
                    if (!latestCovered && latestHasWork) {
                        setCapsuleShown(true, animated = false)
                        capsuleFocus = current
                    }
                }
            } catch (_: CancellationException) {
            }
        }
    }

    fun finishDrag(velocity: Float, remainingDistance: Float? = null) {
        dragController.end(velocity) { releaseVelocity ->
            val remaining = remainingDistance ?: max(40f, (contentHeight ?: 180f) - dragController.drag.offset)
            close(releaseVelocity, remaining)
            onPresentedChange(false)
        }
    }

    OnChange(isPresented) { _, presented ->
        if (presented) {
            if (stage != BackgroundWorkStage.panel) {
                open()
            }
        } else if (stage == BackgroundWorkStage.panel) {
            close()
        }
    }
    OnChange(hasWork) { _, work ->
        if (work) {
            // Work returning mid-fade starts a capsule at rest.
            motion += 1
            if (!latestPresented) {
                stage = BackgroundWorkStage.closed
                dragController.reset()
                scope.launch { progress.snapTo(0f) }
            }
            capsuleMounted = true
            setCapsuleShown(true, animated = true)
        } else if (latestPresented) {
            // The presented change closes it.
            onPresentedChange(false)
        } else if (stage != BackgroundWorkStage.closed) {
            // Mid-close: the surface leaves where it is.
            close()
        } else {
            dismissCapsule()
        }
    }
    // Opening taps, closing is quiet, like the attachment menu.
    HapticEffect(Haptic.tap, isPresented) { _, open -> open }
    OnChange(sessionID) { _, _ -> reset() }
    OnChange(inCall) { _, call -> if (call) onPresentedChange(false) }
    OnChange(isCovered) { _, covered ->
        if (covered) {
            if (latestPresented) onPresentedChange(false) else if (stage != BackgroundWorkStage.closed) close()
        } else if (latestHasWork) {
            setCapsuleShown(true, animated = !reduceMotion)
        }
    }
    // The drag's return spring, stepped on the frame clock.
    LaunchedEffect(dragController.isReturning) {
        if (!dragController.isReturning) return@LaunchedEffect
        motionFrameLoop(frameDurationSeconds = frameDuration) { tick ->
            if (dragController.stepReturn(tick.dt)) MotionFrameAction.Stop else MotionFrameAction.Continue
        }
    }
    BackHandler(enabled = stage == BackgroundWorkStage.panel && isPresented) { onPresentedChange(false) }

    val isPanel = stage == BackgroundWorkStage.panel

    Box(modifier.onGloballyPositioned { origin = it.positionInRoot(); overlayWidth = it.size.width.toFloat() }) {
        CompositionLocalProvider(LocalBackgroundWorkCoversPills provides isPanel) { content() }

        // The capsule's frame (dp, overlay-local), or while the panel is up or closing, where it last was.
        // The place's top (root px) this composition laid the surface out from.
        val composedTop = anchor.bounds?.top
        val live = anchor.bounds?.let { b ->
            Rect((b.left - origin.x) / density.density, (b.top - origin.y) / density.density, (b.right - origin.x) / density.density, (b.bottom - origin.y) / density.density)
        }
        val keep = retained.value
        if (live != null) keep.capsule = live
        val capsule = live ?: if (stage == BackgroundWorkStage.closed && !capsuleMounted) null else keep.capsule
        if (capsule == null && !capsuleMounted) return@Box

        // The rows the work has, or once it has ended, the rows it last had.
        val rows: Triple<List<OpenCodeSession>, List<OpenCodeShell>, List<BackgroundScript>> = if (hasWork) {
            keep.agents = agents
            keep.shells = shells
            keep.scripts = scripts
            Triple(agents, shells, scripts)
        } else if (stage == BackgroundWorkStage.closed && !capsuleMounted) {
            Triple(emptyList(), emptyList(), emptyList())
        } else {
            Triple(keep.agents, keep.shells, keep.scripts)
        }
        val (rowAgents, rowShells, rowScripts) = rows

        // Scrim.
        val scrimAlpha by androidx.compose.animation.core.animateFloatAsState(if (isPanel) 1f else 0f, Motion.dim.animation(if (isPanel) MotionPhase.Enter else MotionPhase.Exit, reduceMotion), label = "scrim")
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha }
                .background(Color.Black.copy(alpha = if (dark) 0.32f else 0.1f))
                .then(if (isPanel) Modifier.pointerInput(Unit) { detectTapGestures { onPresentedChange(false) } } else Modifier)
                .clearAndSetSemantics { },
        )

        if (capsule == null) return@Box
        val safeTop = WindowInsets.safeDrawing.getTop(density) / density.density - origin.y / density.density
        val pill = Rect(capsule.left, capsule.top + BackgroundWorkMotion.capsuleInset, capsule.right, capsule.bottom - BackgroundWorkMotion.capsuleInset)
        val maxHeight = max(160f, pill.bottom - max(0f, safeTop) - 72f)
        val measuredHeight = displayedContentHeight()
        val height = max(pill.height, min(measuredHeight ?: BackgroundWorkSummary.estimatedHeight(rowAgents, rowShells, rowScripts), maxHeight))
        // Rooted on the capsule: its bottom and leading edges are the capsule's, and it widens across the composer's column and up.
        val widthDp = overlayWidth / density.density
        val panel = Rect(pill.left, pill.bottom - height, pill.left + max(pill.width, widthDp - 2 * pill.left), pill.bottom)
        val isScrollable = (contentHeight ?: 0f) > maxHeight + 0.5f
        // Like the pills it grows from, it hides under a panel grown out of the composer.
        val hidden = isCovered
        val hiddenAlpha by androidx.compose.animation.core.animateFloatAsState(if (hidden) 0f else 1f, Motion.reducedFadeSpec(), label = "hidden")
        val summary = BackgroundWorkSummary.summary(rowAgents, rowShells, rowScripts)
        val showWorkLabel = stringResource(R.string.session_background_work_show_label)
        val collapsedDescription = stringResource(R.string.session_background_work_collapsed_description)
        val surfaceColor = OpenCodeSurfaces.color
        val shape = remember { BackgroundWorkShape(BackgroundWorkMotion.panelRadius) }

        val nestedScroll = remember(isScrollable) {
            object : NestedScrollConnection {
                var translation = 0f

                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput || stage != BackgroundWorkStage.panel) return Offset.Zero
                    translation += available.y / density.density
                    dragController.update(translation, 0f, scrollState.value / density.density, isScrollable)
                    return if (dragController.drag.isDragging) available else Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    translation = 0f
                    if (!dragController.drag.isDragging || stage != BackgroundWorkStage.panel) return Velocity.Zero
                    val velocity = available.y / density.density
                    val (placed, _) = dragController.drag.placed(panel, pill, reduceMotion)
                    finishDrag(velocity, max(40f, pill.top - placed.top))
                    return available
                }
            }
        }

        // The surface: one shape morphing between the capsule's bounds and the (dragged) panel's.
        Box(
            Modifier
                .graphicsLayer { alpha = hiddenAlpha * max(capsuleAlpha.value, progress.value) }
                .layout { measurable, _ ->
                    val t = progress.value
                    val (placed, _) = dragController.drag.copy(offset = dragController.offset).placed(panel, pill, reduceMotion)
                    val target = if (stage == BackgroundWorkStage.closed) pill else placed
                    val left = lerp(pill.left, target.left, t)
                    val top = lerp(pill.top, target.top, t)
                    val right = lerp(pill.right, target.right, t)
                    val bottom = lerp(pill.bottom, target.bottom, t)
                    val w = max(0, ((right - left) * density.density).roundToInt())
                    val h = max(0, ((bottom - top) * density.density).roundToInt())
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(0, 0) { placeable.place((left * density.density).roundToInt(), (top * density.density).roundToInt()) }
                }
                // At rest, the capsule rides its place in the frame the place moves: the bounds
                // above are last frame's, so the drawing makes up the difference from where the
                // place is laid out now. The next composition catches up.
                .drawWithContent {
                    anchor.motion()
                    val now = anchor.coordinates?.takeIf { it.isAttached }?.positionInRoot()
                    val dy = if (stage == BackgroundWorkStage.closed && progress.value == 0f && composedTop != null && now != null) now.y - composedTop else 0f
                    if (dy == 0f) drawContent() else translate(top = dy) { this@drawWithContent.drawContent() }
                }
                .shadow(if (isPanel) OpenCodeSurfaces.popoverShadow else OpenCodeSurfaces.raisedShadow, shape, clip = false)
                .clip(shape)
                .background(surfaceColor)
                .openCodeHairline(shape)
                .then(if (hidden) Modifier.clearAndSetSemantics { } else Modifier),
        ) {
            // The capsule's label, a button while the surface is at rest. Kept composed under the open
            // panel (held still), so closing never composes it in the fold's first frame.
            val labelShown = stage != BackgroundWorkStage.panel || progress.value < 1f
            run {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = 1f - progress.value }
                        .accessibilityFocusRequest(capsuleFocusRequest)
                        .then(
                            // A capsule fading with the work, or gone, takes no touch.
                            if (stage == BackgroundWorkStage.closed && !hidden && capsuleShown) {
                                Modifier
                                    // A vertical drag on the capsule scrolls the transcript.
                                    .passesDragsToTranscript(transcript)
                                    .clickable(onClickLabel = showWorkLabel) {
                                        onToggle()
                                        onPresentedChange(!isPresented)
                                    }
                                    .testTag("background-activity")
                                    .semantics {
                                        contentDescription = summary
                                        // Open, the panel replaces it, so it only ever reads collapsed.
                                        stateDescription = collapsedDescription
                                    }
                            } else {
                                Modifier.clearAndSetSemantics { }
                            },
                        ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BackgroundWorkCapsuleLabel(rowAgents.size, rowShells.size, Modifier.clearAndSetSemantics { }, scripts = rowScripts.size) {
                        BackgroundWorkGlyph(rowAgents, rowShells, waitingSessionIDs, probe, isPaused = !labelShown || isPaused, scripts = rowScripts)
                    }
                }
            }
            // The rows run from the morph's first frame, both ways: only accessibility waits for the panel to land.
            // Kept composed (held still, out of sight and touch) while the capsule rests, so opening
            // never composes the rows in the morph's first frame: that cost 30–40 ms on the main
            // thread and the panel's first frames jumped.
            val rowsOpen = stage != BackgroundWorkStage.closed || progress.value > 0f
            run {
                Box(
                    Modifier
                        .layout { measurable, constraints ->
                            val w = (panel.width * density.density).roundToInt()
                            val h = (panel.height * density.density).roundToInt()
                            val placeable = measurable.measure(Constraints.fixed(w, h))
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                // Closed, far outside the screen: it takes no touch and draws nothing.
                                if (rowsOpen) placeable.place(0, 0) else placeable.place(-100_000, 0)
                            }
                        }
                        .graphicsLayer {
                            alpha = progress.value
                            val scale = dragController.drag.copy(offset = dragController.offset).placed(panel, pill, reduceMotion).second
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                        .nestedScroll(nestedScroll)
                        .then(if (settled && !hidden) Modifier.testTag("background-work-panel") else Modifier.clearAndSetSemantics { }),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    BackgroundWorkPanel(
                        agents = rowAgents,
                        waitingSessionIDs = waitingSessionIDs,
                        shells = rowShells,
                        scripts = rowScripts,
                        stoppingIDs = stoppingIDs,
                        onStopScript = { id -> if (!dragController.drag.isDragging) onStopScript(id) },
                        onStopSubagent = { id -> if (!dragController.drag.isDragging) onStopSubagent(id) },
                        probe = probe,
                        scrollState = scrollState,
                        isLive = rowsOpen && !isPaused,
                        // Landed open, TalkBack moves into the panel.
                        focusRequest = rememberAccessibilityFocusRequest(if (settled && !hidden) motion else null),
                        onContentHeight = ::measured,
                        onOpenSubagent = { id ->
                            if (!dragController.drag.isDragging) {
                                onPresentedChange(false)
                                onOpenSubagent(id)
                            }
                        },
                        onOpenShell = { id ->
                            if (!dragController.drag.isDragging) {
                                onPresentedChange(false)
                                onOpenShell(id)
                            }
                        },
                    )
                }
            }
        }

        // The rows above stay composed while the capsule rests and report their natural height
        // (`onContentHeight`), so opening lays the panel out at its real height at once.
    }
}

/** Runs [action] with the old and new value when [value] changes, never on first composition. */
@Composable
private fun <T> OnChange(value: T, action: (old: T, new: T) -> Unit) {
    val previous = remember { arrayOfNulls<Any?>(1).also { it[0] = value } }
    val latest by rememberUpdatedState(action)
    LaunchedEffect(value) {
        @Suppress("UNCHECKED_CAST")
        val old = previous[0] as T
        previous[0] = value
        if (old != value) latest(old, value)
    }
}

// MARK: Panel

/**
 * Running subagents and background shells, full width so titles never wrap.
 * [BackgroundWorkPresentation] supplies its surface and frame.
 *
 * @param agents running subagents, one row each (tap: [onOpenSubagent] with its session ID).
 * @param waitingSessionIDs subagents waiting on the user: their orbs show Waiting for Input.
 * @param shells background shells, one row each with its command and running time (tap: [onOpenShell]).
 * @param probe where the shells' output is read, for their glyphs.
 * @param scrollState the list's scroll position; scrolls once the rows outgrow the panel.
 * @param isLive the orbs, shell glyphs and clocks run. A hidden measuring copy holds still.
 * @param focusRequest moves TalkBack's focus onto the first row's section header, once.
 * @param onContentHeight the rows' natural height in dp, whenever it changes.
 */
@Composable
fun BackgroundWorkPanel(
    agents: List<OpenCodeSession>,
    shells: List<OpenCodeShell>,
    onOpenSubagent: (String) -> Unit,
    onOpenShell: (String) -> Unit,
    modifier: Modifier = Modifier,
    scripts: List<BackgroundScript> = emptyList(),
    onStopScript: (String) -> Unit = {},
    onStopSubagent: (String) -> Unit = {},
    stoppingIDs: Set<String> = emptySet(),
    waitingSessionIDs: Set<String> = emptySet(),
    probe: ShellGlyphProbe? = null,
    scrollState: ScrollState = rememberScrollState(),
    isLive: Boolean = true,
    focusRequest: AccessibilityFocusRequest? = null,
    onContentHeight: (Float) -> Unit = {},
) {
    val density = LocalDensity.current
    Box(modifier.fillMaxWidth().verticalScroll(scrollState)) {
        BackgroundWorkRows(
            agents, waitingSessionIDs, shells, scripts, probe, isLive, focusRequest, onOpenSubagent, onOpenShell, onStopScript, onStopSubagent, stoppingIDs,
            Modifier.onSizeChanged { onContentHeight(it.height / density.density) },
        )
    }
}

@Composable
private fun BackgroundWorkRows(
    agents: List<OpenCodeSession>,
    waitingSessionIDs: Set<String>,
    shells: List<OpenCodeShell>,
    scripts: List<BackgroundScript>,
    probe: ShellGlyphProbe?,
    isLive: Boolean,
    focusRequest: AccessibilityFocusRequest?,
    onOpenSubagent: (String) -> Unit,
    onOpenShell: (String) -> Unit,
    onStopScript: (String) -> Unit,
    onStopSubagent: (String) -> Unit,
    stoppingIDs: Set<String>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        if (agents.isNotEmpty()) {
            SectionHeader(stringResource(R.string.session_background_work_subagents_title), Modifier.accessibilityFocusRequest(focusRequest))
            for (agent in agents) {
                androidx.compose.runtime.key(agent.id) {
                    BackgroundWorkRow(
                        title = agent.title,
                        accessibilityHint = stringResource(R.string.session_background_work_open_subagent_label),
                        onTap = { onOpenSubagent(agent.id) },
                        stopLabel = stringResource(R.string.session_background_work_stop_subagent_label),
                        onStop = { onStopSubagent(agent.id) },
                        isStopping = agent.id in stoppingIDs,
                        modifier = Modifier.testTag("background-subagent-${agent.id}"),
                    ) {
                        AgentActivityGlyph(
                            source = AgentActivitySource.Subagent(
                                SubagentActivity(sessionID = agent.id, phase = SubagentActivity.Phase.running, needsInput = agent.id in waitingSessionIDs),
                            ),
                            isPaused = !isLive,
                        )
                    }
                }
            }
        }
        if (agents.isNotEmpty() && scripts.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = OpenCodeTheme.colors.border)
        }
        if (scripts.isNotEmpty()) {
            SectionHeader(
                stringResource(R.string.session_background_work_scripts_title),
                if (agents.isEmpty()) Modifier.accessibilityFocusRequest(focusRequest) else Modifier,
            )
            for (script in scripts) {
                androidx.compose.runtime.key(script.id) {
                    val stopLabel = stringResource(R.string.session_background_work_stop_script_label)
                    BackgroundWorkRow(
                        title = script.label,
                        isCommand = true,
                        truncatesTail = true,
                        startedAt = script.startedAt,
                        isLive = isLive,
                        accessibilityHint = stopLabel,
                        onTap = null,
                        stopLabel = stopLabel,
                        onStop = { onStopScript(script.id) },
                        isStopping = script.id in stoppingIDs,
                        modifier = Modifier.testTag("background-script-${script.id}"),
                    ) {
                        ScriptGlyph()
                    }
                }
            }
        }
        if ((agents.isNotEmpty() || scripts.isNotEmpty()) && shells.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = OpenCodeTheme.colors.border)
        }
        if (shells.isNotEmpty()) {
            SectionHeader(stringResource(R.string.session_background_work_shells_title), if (agents.isEmpty() && scripts.isEmpty()) Modifier.accessibilityFocusRequest(focusRequest) else Modifier)
            for (shell in shells) {
                androidx.compose.runtime.key(shell.id) {
                    BackgroundWorkRow(
                        title = shell.title,
                        isCommand = true,
                        startedAt = shell.startedAt,
                        isLive = isLive,
                        accessibilityHint = stringResource(R.string.session_background_work_show_shell_label),
                        onTap = { onOpenShell(shell.id) },
                        modifier = Modifier.testTag("background-shell-${shell.id}"),
                    ) {
                        ShellGlyph(shellIDs = listOf(shell.id), probe = probe, isPaused = !isLive)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = OpenCodeTextStyles.footnote.copy(fontWeight = FontWeight.SemiBold),
        color = OpenCodeTheme.colors.secondaryText,
        modifier = modifier
            .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 2.dp)
            .semantics { heading() },
    )
}

@Composable
private fun BackgroundWorkRow(
    title: String,
    accessibilityHint: String,
    onTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
    isCommand: Boolean = false,
    /** A script's first line reads from its start; a command keeps both ends. */
    truncatesTail: Boolean = false,
    startedAt: Instant? = null,
    isLive: Boolean = true,
    stopLabel: String? = null,
    onStop: (() -> Unit)? = null,
    isStopping: Boolean = false,
    leading: @Composable () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            // A ripple inset from the panel's edges answers the press.
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(if (onTap != null) Modifier.clickable(role = Role.Button, onClickLabel = accessibilityHint, onClick = onTap) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = title
                if (onTap != null) {
                    onClick(label = accessibilityHint) { onTap(); true }
                    if (onStop != null && stopLabel != null && !isStopping) {
                        customActions = listOf(CustomAccessibilityAction(stopLabel) { onStop(); true })
                    }
                } else if (onStop != null && !isStopping) {
                    onClick(label = stopLabel ?: accessibilityHint) { onStop(); true }
                }
            }
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(20.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) { leading() }
        Text(
            title,
            style = if (isCommand) OpenCodeTextStyles.subheadline.copy(fontFamily = OpenCodeFonts.monospace) else OpenCodeTextStyles.body,
            maxLines = 1,
            overflow = if (isCommand && !truncatesTail) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clearAndSetSemantics { },
        )
        if (startedAt != null) {
            Box(Modifier.clearAndSetSemantics { }) {
                ShellElapsedTime(
                    since = startedAt,
                    isPaused = !isLive,
                    style = OpenCodeTextStyles.subheadline.copy(fontFeatureSettings = "tnum"),
                    color = OpenCodeTheme.colors.secondaryText,
                )
            }
        }
        if (onStop != null) {
            OpenCodeIconButton(
                symbol = SFSymbol.Xmark,
                contentDescription = stopLabel ?: "",
                onClick = onStop,
                // The row's semantics carry Open's custom Stop action; a script row's own Stop stays its button.
                modifier = if (onTap != null) Modifier.clearAndSetSemantics { } else Modifier,
                style = OpenCodeIconButtonStyle.Standard,
                iconSize = 16.dp,
                enabled = !isStopping,
            )
        }
        if (onTap != null) OpenCodeIcon(SFSymbol.ChevronRight, contentDescription = null, size = 16.dp, tint = OpenCodeTheme.colors.tertiaryText)
    }
}

// MARK: Elapsed time

/**
 * Running time as a clock (`0:42`, `12:03`, `1:02:03`), ticking each second.
 *
 * @param since when the shell started.
 * @param until when it ended; a fixed time then.
 * @param isPaused hidden or moving: shows the time once instead of ticking.
 */
@Composable
fun ShellElapsedTime(
    since: Instant,
    modifier: Modifier = Modifier,
    until: Instant? = null,
    isPaused: Boolean = false,
    style: TextStyle = OpenCodeTextStyles.subheadline.copy(fontFeatureSettings = "tnum"),
    color: Color = Color.Unspecified,
) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(since, until, isPaused) {
        now = Instant.now()
        if (until != null || isPaused) return@LaunchedEffect
        while (true) {
            // Ticks on the whole seconds since it started.
            val elapsedMillis = Duration.between(since, Instant.now()).toMillis()
            delay(1000 - Math.floorMod(elapsedMillis, 1000L) + 1)
            now = Instant.now()
        }
    }
    val seconds = max(0L, Duration.between(since, until ?: now).seconds)
    Text(
        ShellElapsedTimeFormat.clock(seconds),
        style = style,
        color = color,
        maxLines = 1,
        modifier = modifier.clearAndSetSemantics { contentDescription = ShellElapsedTimeFormat.spoken(seconds) },
    )
}

object ShellElapsedTimeFormat {
    fun clock(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val locale = appFormattingLocale()
        return if (seconds >= 3600) "%d:%02d:%02d".format(locale, h, m, s) else "%d:%02d".format(locale, seconds / 60, s)
    }

    fun spoken(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val parts = listOfNotNull(
            if (h > 0) localizedPlural(R.plurals.session_shell_elapsed_hours, h.toInt(), h.toInt()) else null,
            if (m > 0) localizedPlural(R.plurals.session_shell_elapsed_minutes, m.toInt(), m.toInt()) else null,
            if (s > 0) localizedPlural(R.plurals.session_shell_elapsed_seconds, s.toInt(), s.toInt()) else null,
        )
        return when (parts.size) {
            0 -> localizedPlural(R.plurals.session_shell_elapsed_seconds, 0, 0)
            1 -> parts[0]
            2 -> localized(R.string.session_list_two, parts[0], parts[1])
            else -> localized(R.string.session_list_three, parts[0], parts[1], parts[2])
        }
    }
}
