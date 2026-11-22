package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionTransition
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.observe
import ai.opencode.core.strings.localized
import ai.opencode.core.strings.localizedPlural
import ai.opencode.core.tuning.Tunable
import ai.opencode.core.tuning.TunableChoice
import ai.opencode.core.tuning.TuningPreset
import ai.opencode.core.tuning.TuningPresets
import ai.opencode.core.tuning.TuningStore
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The call floats over the app as one shape that is the orb at rest, the card, and the sheet. The
// composer's phone button breaks out into the orb (Lift and Spin Up, `FloatingCallConnectMotion`);
// the orb floats free (Floaty, `FloatingCallFloatMotion`), a tap grows it into the card and a drag
// steps it between the orb, the card and the sheet (`FloatingCallStageMotion`); hanging up flies it
// back into the call button. The stage store is `FloatingCallStageFeature`, the geometry and motion
// live in `FloatingCallStages.kt` and `FloatingCallFloat.kt`, and the card's content in
// `FloatingCallStageContent.kt`. Coordinates are window dp.

/** The resting shape: the orb, or a capsule with End beside it. */
@Suppress("EnumEntryName")
enum class FloatingCallStyle(override val rawValue: String) : TunableChoice {
    orb("orb"),
    capsule("capsule"),
}

/** Placement, the break-out's shadows, and Floaty's drag and throw. */
object FloatingCallTunables {
    // Placement and break-out
    val style = Tunable("call.float.kind", "Shape", default = FloatingCallStyle.orb)
    val capsuleWidth = Tunable("call.float.capsuleWidth", "Capsule width", default = 116.0, range = 88.0..180.0, step = 1.0)
    val sideInset = Tunable("call.float.sideInset", "Keep-out from the sides", default = 16.0, range = 4.0..48.0, step = 1.0)
    val topLimit = Tunable("call.float.topLimit", "Header keep-out (from the top)", default = 124.0, range = 60.0..240.0, step = 1.0)
    val composerGap = Tunable("call.float.composerGap", "Keep-out gap above the composer", default = 12.0, range = 0.0..80.0, step = 1.0)
    val liftShadow = Tunable("call.float.liftShadow", "Lift shadow radius", default = 22.0, range = 0.0..48.0, step = 1.0)
    val restShadow = Tunable("call.float.restShadow", "Rest shadow radius", default = 10.0, range = 0.0..32.0, step = 1.0)

    // Floaty (D-147 option 3): drag, tap and throw
    val tapSlop = Tunable("call.float.tap.slop", "Tap: moves under", default = 10.0, range = 4.0..24.0, step = 1.0)
    val tapTime = Tunable("call.float.tap.time", "Tap: lifts within (s)", default = 0.3, range = 0.12..0.6, step = 0.01)
    val catchSpeed = Tunable("call.float.tap.catchSpeed", "Tap only catches above (pt/s)", default = 120.0, range = 20.0..600.0, step = 10.0)
    val friction = Tunable("call.float.friction", "Friction (/s)", default = 3.2, range = 0.5..10.0, step = 0.1)
    val maxSpeed = Tunable("call.float.maxSpeed", "Fastest throw (pt/s)", default = 6000.0, range = 1000.0..10000.0, step = 100.0)
    val overshoot = Tunable("call.float.overshoot", "Runs past the keep-out, at most", default = 40.0, range = 0.0..160.0, step = 2.0)

    val styles: TuningPresets by lazy {
        TuningPresets(
            "call.float.style", "Shape",
            listOf(
                TuningPreset("Orb"),
                TuningPreset("Capsule with End", style.preset(FloatingCallStyle.capsule)),
            ),
        )
    }

    fun register(store: TuningStore = TuningStore.shared) {
        store.register(styles)
        store.register(style)
        store.register(capsuleWidth, sideInset, topLimit, composerGap, liftShadow, restShadow)
        store.register(tapSlop, tapTime, catchSpeed, friction, maxSpeed, overshoot)
    }
}

/**
 * UI tests and captures pick a look. Debug builds only, through [VoiceLabEnvironment]: `adb shell
 * setprop debug.opencode.lab_floating_call_style capsule`, `…lab_floating_scenes 1`.
 */
data class FloatingCallLab(
    val style: FloatingCallStyle? = null,
    val scenes: Boolean = false,
) {
    companion object {
        val none = FloatingCallLab()

        /** Reads the properties (a `getprop` each, cached): call off the main thread. */
        fun read(): FloatingCallLab = FloatingCallLab(
            style = VoiceLabEnvironment.string("LAB_FLOATING_CALL_STYLE")?.let { raw -> FloatingCallStyle.entries.firstOrNull { it.rawValue == raw } },
            scenes = VoiceLabEnvironment.flag("LAB_FLOATING_SCENES"),
        )
    }
}

/** Where the floating call takes touches; everything else reaches the app beneath. */
sealed interface FloatingCallTouchArea {
    data object None : FloatingCallTouchArea

    /** The orb or card, never smaller than a touch target. */
    data class Frame(val rect: Rect) : FloatingCallTouchArea

    /** The sheet: a touch on the dimmed session steps back. */
    data object Everywhere : FloatingCallTouchArea
}

/**
 * Where the floating call starts and rests. The session screen reports its call button and
 * composer, in window coordinates in dp ([floatingCallButton], [floatingCallComposer]); the overlay
 * reads them. One per process, behind its dependency key; main thread only.
 */
class FloatingCallCenter {
    /** A panel request: open or shut, and a counter bumped on each request. */
    data class Request(val open: Boolean, val count: Int)

    /** The composer's call button circle, as last laid out. */
    var callButton: Rect? = null

    /**
     * Where the call button was when it was tapped (the composer's or the drawer's): the
     * break-out starts there. Taken once; a call started any other way starts at
     * [landingButton] while it's fresh, else at its rest.
     */
    var breakoutFrom: Rect? = null

    /** The drawer's call button circle, where it stands or comes back to, while the drawer offers a call; null otherwise. */
    var drawerCallButton: Rect? = null

    /** The phone's drawer is open over the session: its composer is out of sight. */
    var isDrawerOpen: Boolean = false

    /**
     * Where a call ending now lands: with the drawer open its call button (or nowhere, so the
     * call fades where it rests), never the composer's button hidden under the drawer; otherwise
     * the composer's.
     */
    val landingButton: Rect?
        get() = (if (isDrawerOpen) drawerCallButton else callButton)?.takeIf { it.width > 0f }

    /** The floating call's screen, safe area and resting size while its overlay shows one, for [restingOrb]. */
    var orbLayout by mutableStateOf<FloatingCallOrbLayout?>(null)

    /**
     * The composer and what stands above it (pills, the jump button), as last laid out with the
     * session card at rest. The keyboard raises it.
     */
    var composer by mutableStateOf<Rect?>(null)

    /** The card's frame as last laid out, in window dp: whether it covers the composer ([FloatingCallStage.covers]). */
    var card by mutableStateOf<Rect?>(null)

    /**
     * The composer's readable column wherever it sits (beside a wide screen's sidebar too), for
     * whether the card covers it. Unlike [composer] it keeps off-centre frames.
     */
    var composerColumn by mutableStateOf<Rect?>(null)
        private set

    /** Playground scenes stand in for the call's voices and state. */
    var scene by mutableStateOf<FloatingCallScene?>(null)

    /** Bumped by the playground to open or close the card. */
    var panelRequest by mutableStateOf(Request(false, 0))
        private set

    /** Where the orb rests for this run, once dragged. The session makes room for it while it rests on the composer (`orbClearance`). */
    var place by mutableStateOf<FloatingCallPlace?>(null)

    /** The overlay's width, to tell a composer at rest from one sliding with the drawer. */
    var screenWidth: Float = 0f

    /** The ended call's shape is flying into the call button: its host keeps it until it lands. */
    var isLanding by mutableStateOf(false)

    /** Some host ([FloatingCallWindow]) floats a call. */
    var hasCall by mutableStateOf(false)
        private set

    /** The hosts floating a call: one closing leaves the others'. */
    private val callAnchors = mutableSetOf<Any>()


    fun noteCall(has: Boolean, anchor: Any) {
        if (has) callAnchors.add(anchor) else callAnchors.remove(anchor)
        if (hasCall == callAnchors.isEmpty()) hasCall = callAnchors.isNotEmpty()
    }

    /**
     * Kept only while the card rests shut: the composer slides with the drawer, and the orb stays
     * where it was. Beside a wide screen's sidebar the composer stands off-centre and doesn't
     * slide: kept there too.
     */
    fun noteComposer(frame: Rect, besideSidebar: Boolean = false) {
        if (frame.width <= 0 || !(besideSidebar || screenWidth == 0f || abs(frame.center.x - screenWidth / 2) < 2)) return
        if (composer != frame) composer = frame
    }

    fun noteComposerColumn(frame: Rect) {
        if (frame.width <= 0 || composerColumn == frame) return
        composerColumn = frame
    }

    /**
     * Where the orb rests (or comes back to from the card) over a composer whose top is
     * [composerTop], in window dp: worked out from the composer as it's laid out, so the
     * session's room for it never reads a rest the overlay hasn't caught up to.
     */
    fun restingOrb(composerTop: Float): Rect? {
        val layout = orbLayout ?: return null
        val area = FloatingCallRestArea(layout.screen, layout.safeArea, composerTop, layout.size)
        return area.frame(place ?: FloatingCallPlace())
    }

    fun requestPanel(open: Boolean) {
        panelRequest = Request(open, panelRequest.count + 1)
    }

    companion object Key : DependencyKey<FloatingCallCenter> {
        override val liveValue: FloatingCallCenter by lazy { FloatingCallCenter() }
        override val testValue: FloatingCallCenter get() = FloatingCallCenter()
        val shared: FloatingCallCenter get() = dependency(Key)
    }
}

/** Reports this element (the session's composer and what stands above it) to [FloatingCallCenter.noteComposer]. */
fun Modifier.floatingCallComposer(): Modifier = this then FloatingCallFrameElement(FloatingCallFrameKind.composer)

/** Reports this element (the composer's call button circle) as [FloatingCallCenter.callButton]. */
fun Modifier.floatingCallButton(): Modifier = this then FloatingCallFrameElement(FloatingCallFrameKind.callButton)

/**
 * Reports this element (the drawer's call button circle) as [FloatingCallCenter.drawerCallButton],
 * cleared when it leaves. Apply it only while the drawer offers a call.
 */
fun Modifier.floatingCallDrawerButton(): Modifier = this then FloatingCallFrameElement(FloatingCallFrameKind.drawerCallButton)

/**
 * Reports this element (the composer's readable column, not the pane) to
 * [FloatingCallCenter.noteComposerColumn]: whether the call's card covers it. Apply it only while
 * the call floats and the screen shows.
 */
fun Modifier.floatingCallComposerColumn(): Modifier = this then FloatingCallFrameElement(FloatingCallFrameKind.composerColumn)

@Suppress("EnumEntryName")
private enum class FloatingCallFrameKind { composer, callButton, composerColumn, drawerCallButton }

private data class FloatingCallFrameElement(val kind: FloatingCallFrameKind) : ModifierNodeElement<FloatingCallFrameNode>() {
    override fun create() = FloatingCallFrameNode(kind)
    override fun update(node: FloatingCallFrameNode) { node.kind = kind }
    override fun InspectorInfo.inspectableProperties() { name = "floatingCallFrame" }
}

/** Reports its element's window bounds in dp. */
private class FloatingCallFrameNode(var kind: FloatingCallFrameKind) : Modifier.Node(), GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val density = currentValueOf(LocalDensity).density
        val bounds = coordinates.boundsInWindow()
        val frame = Rect(bounds.left / density, bounds.top / density, bounds.right / density, bounds.bottom / density)
        val center = FloatingCallCenter.shared
        when (kind) {
            FloatingCallFrameKind.composer -> center.noteComposer(frame)
            FloatingCallFrameKind.callButton -> center.callButton = frame
            FloatingCallFrameKind.composerColumn -> center.noteComposerColumn(frame)
            FloatingCallFrameKind.drawerCallButton -> center.drawerCallButton = frame
        }
    }

    override fun onDetach() {
        if (kind == FloatingCallFrameKind.drawerCallButton) FloatingCallCenter.shared.drawerCallButton = null
    }
}

/**
 * What a host floats: the call, the last one (kept while its shape lands), and where the overlay
 * takes touches, for a host that hosts it in a window of its own and must pass every other touch
 * through.
 */
@Stable
class FloatingCallWindowContent {
    var call: VoiceStoreOf?
        get() = callState
        set(value) {
            callState = value
            if (value != null) last = value
        }
    private var callState by mutableStateOf<VoiceStoreOf?>(null)

    var last by mutableStateOf<VoiceStoreOf?>(null)

    /** Written by the overlay as its shape moves. */
    var touchArea: FloatingCallTouchArea = FloatingCallTouchArea.None
}

/** The host the overlay reports its touch area to, if any. */
val LocalFloatingCallWindow = staticCompositionLocalOf<FloatingCallWindowContent?> { null }

/**
 * The floating call's host, mounted once at the app's root over everything else, full window (its
 * coordinates are the window's, in dp, as [floatingCallButton] and [floatingCallComposer] report
 * them), drawn edge to edge: it reads the system bars and cutout itself.
 *
 * The host passes the call to float (null when there's none or the experiment is off,
 * [FloatingCallCenter.isOn]). An ended call stays while its shape lands in the call button
 * ([FloatingCallCenter.isLanding], at most 4 s), then fades out; a new call gets its own surface.
 * Touches outside the shape reach the app beneath: the overlay has no pointer input but the
 * shape's own (and, with the sheet up, the dimmed backdrop's), so Compose passes the rest to
 * the content under it. A host that puts it in a separate window instead reads
 * [FloatingCallWindowContent.touchArea] to decide what that window takes.
 */
@Composable
fun FloatingCallWindow(
    call: VoiceStoreOf?,
    modifier: Modifier = Modifier,
    content: FloatingCallWindowContent = remember { FloatingCallWindowContent() },
) {
    val center = remember { FloatingCallCenter.shared }
    val anchor = remember { Any() }
    SideEffect { if (content.call !== call) content.call = call }
    DisposableEffect(call != null) {
        center.noteCall(call != null, anchor)
        onDispose {}
    }
    DisposableEffect(Unit) {
        onDispose {
            center.noteCall(false, anchor)
            content.touchArea = FloatingCallTouchArea.None
            content.last = null
        }
    }
    val reduceMotion = rememberReduceMotion()
    // Waits for the landing up to 40 × 0.1 s before taking the window down.
    var expired by remember { mutableStateOf(false) }
    LaunchedEffect(call) {
        expired = false
        if (call == null) {
            delay(4_000)
            expired = true
        }
    }
    val shown = call ?: if (center.isLanding && !expired) content.last else null
    CompositionLocalProvider(LocalFloatingCallWindow provides content) {
        AnimatedContent(
            targetState = shown,
            modifier = modifier.fillMaxSize(),
            transitionSpec = {
                // A hang-up waits for the composer's own button to come back under the landed
                // shape ([FLOATING_CALL_BUTTON_RETURN]) before fading.
                val fade = if (targetState == null) {
                    Motion.swap.animation<Float>(MotionPhase.Exit, reduceMotion, delaySeconds = FLOATING_CALL_BUTTON_RETURN)
                } else {
                    Motion.swap.animation(reduceMotion = reduceMotion)
                }
                EnterTransition.None togetherWith fadeOut(fade) using SizeTransform(clip = false)
            },
            label = "floatingCallWindow",
        ) { store ->
            if (store != null) FloatingCallOverlay(store)
        }
    }
}

/**
 * The card and the sheet, grown from the orb where it rests, on its side of the screen. Covering
 * (`call.stage.covers`), the card stands at the screen's foot with the same margin as its sides,
 * over the composer and its pills, so the keyboard never squeezes it; otherwise it stands above the
 * composer.
 */
internal fun floatingCallStageGeometry(area: FloatingCallRestArea, rest: Rect): FloatingCallStageGeometry {
    val covers = FloatingCallStageTunables.covers.value
    return FloatingCallStageGeometry(
        screen = area.screen,
        rest = rest,
        low = if (covers) {
            area.screen.height - FloatingCallStageTunables.coverInset.value
        } else {
            (area.bottom - FloatingCallTunables.composerGap.value.toFloat()).toDouble()
        },
        trailing = rest.center.x > area.screen.width / 2,
        card = Size(
            FloatingCallStageTunables.cardWidth.value.toFloat(),
            (if (covers) FloatingCallStageTunables.coverHeight.value else FloatingCallStageTunables.cardHeight.value).toFloat(),
        ),
    )
}

/**
 * The floating call over the whole window: drawn edge to edge, it reads the system bars and cutout
 * as its safe area. Mount it through [FloatingCallWindow], which keeps an ended call while it
 * lands; mounted on its own, keep it composed (with its store) until [FloatingCallCenter.isLanding]
 * goes false, or the hang-up flight is cut short.
 */
@Composable
fun FloatingCallOverlay(store: VoiceStoreOf, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        val scale = density.density
        val safeArea = FloatingCallEdgeInsets(
            top = insets.getTop(density) / scale,
            leading = insets.getLeft(density, direction) / scale,
            bottom = insets.getBottom(density) / scale,
            trailing = insets.getRight(density, direction) / scale,
        )
        FloatingCallSurface(store, Size(maxWidth.value, maxHeight.value), safeArea)
    }
}

/** What a drag on the card or sheet does: step the stage, or nothing (a sideways drag). */
@Suppress("EnumEntryName")
private enum class FloatingCallDragMode { stage, ignored }

/**
 * A finger on the resting orb, from touch-down: where it held the orb, and whether it caught it
 * mid-drift.
 */
private class FloatingCallOrbTouch(
    val offset: Offset,
    val origin: Offset,
    val began: Long,
    val wasMoving: Boolean,
    var location: Offset,
    var moved: Long,
) {
    var travel = 0f
}

/**
 * The surface's state and logic: the three motions, the touch, the tag and the tasks. The body
 * reads its snapshot state; gestures, effects and the motions' callbacks call into it.
 */
@Stable
private class FloatingCallSurfaceModel(
    val store: VoiceStoreOf,
    val center: FloatingCallCenter,
    val scope: CoroutineScope,
) {
    val layout = FloatingCallLayout()
    val opacity = Animatable(0f)
    /** Where the orb rests: the call center's, so this call and the next share one value. */
    val place: FloatingCallPlace get() = center.place ?: FloatingCallPlace()
    val float = FloatingCallFloatMotion()
    val motion = FloatingCallStageMotion()
    val connect = FloatingCallConnectMotion()
    var tagText by mutableStateOf<String?>(null)
    var tagShown by mutableStateOf(false)
    var bloomMounted by mutableStateOf(false)
    var lab by mutableStateOf(FloatingCallLab.none)

    var touch: FloatingCallOrbTouch? = null
    var dragMode: FloatingCallDragMode? = null
    var tagJob: Job? = null
    var mountJob: Job? = null
    var lastPanelRequest = 0

    // Kept current by the body.
    var reduceMotion = false
    var window: FloatingCallWindowContent? = null
    var dismissKeyboard: () -> Unit = {}
    var density = 1f
    var root: LayoutCoordinates? = null
    var shapeCoordinates: LayoutCoordinates? = null

    val style: FloatingCallStyle get() = lab.style ?: FloatingCallTunables.style.value

    val restSize: Size
        get() {
            val height = WispOrbTunables.size.value.toFloat()
            val width = if (style == FloatingCallStyle.capsule) max(height, FloatingCallTunables.capsuleWidth.value.toFloat()) else height
            return Size(width, height)
        }

    /** Where the orb may be on the screen as it is when called, for motion that outlives a body. */
    fun liveRestArea(): FloatingCallRestArea = FloatingCallRestArea(layout.screen, layout.safeArea, center.composer?.top, restSize, layout.keyboardTop)

    val restFrame: Rect get() = liveRestArea().frame(place)

    fun restingFrame(area: FloatingCallRestArea = liveRestArea()): Rect = float.position?.let { area.frame(centeredAt = it) } ?: area.frame(place)

    fun stageGeometry(area: FloatingCallRestArea = liveRestArea()): FloatingCallStageGeometry = floatingCallStageGeometry(area, area.frame(place))

    fun send(action: FloatingCallStageFeature.Action) {
        store.send(VoiceFeature.Action.FloatingStage(action))
    }

    /** The screen or safe area changed (a rotation, a resize): motion reads the new bounds, and a drifting orb is kept on screen. */
    fun laidOut(screen: Size, safeArea: FloatingCallEdgeInsets) {
        layout.screen = screen
        layout.safeArea = safeArea
        center.screenWidth = screen.width
        val area = liveRestArea()
        float.clamp(into = area.reach.union(area.bounds))
    }

    fun appeared() {
        FloatingCallTunables.register()
        FloatingCallStageTunables.register()
        FloatingCallConnectTunables.register()
        WispOrbTunables.register()
        scope.launch {
            val read = withContext(Dispatchers.IO) { FloatingCallLab.read() }
            lab = read
            if (!read.scenes) return@launch
            val scenes = listOf(FloatingCallScene.idle, FloatingCallScene.listening, FloatingCallScene.thinking, FloatingCallScene.agentSpeaking, FloatingCallScene.bothSpeaking, FloatingCallScene.needsInput)
            while (isActive && !store.state.phase.isTerminal) {
                for (scene in scenes) {
                    center.scene = scene
                    delay(2_500)
                }
            }
        }
        lastPanelRequest = center.panelRequest.count
        // Carried over to another screen grown: it stays grown.
        val stage = store.state.floatingStage.stage
        if (stage != FloatingCallStage.orb) {
            bloomMounted = true
            motion.jump(stage)
        }
        mountJob = scope.launch {
            // After the break-out has landed, while the orb just rests.
            delay(1_200)
            bloomMounted = true
        }
        val state = store.state
        val fresh = state.phase == VoiceFeature.State.Phase.Connecting && state.connectedAt == null
        // A call just started always starts as the call button, however it was started.
        val from = center.breakoutFrom ?: if (fresh) center.landingButton else null
        center.breakoutFrom = null
        if (!fresh || reduceMotion || from == null || from.width <= 0f) {
            // Carried over, or Reduce Motion: it appears where it rests, and the bigger field mounts now.
            mountJob?.cancel()
            bloomMounted = true
            scope.launch { opacity.animateTo(1f, Motion.swap.animation(reduceMotion = reduceMotion)) }
            return
        }
        scope.launch { opacity.snapTo(1f) }
        connect.start(from = from, rest = { liveRestArea().frame(place) })
    }

    fun disappeared() {
        window?.touchArea = FloatingCallTouchArea.None
        if (center.isLanding) center.isLanding = false
        motion.stop()
        connect.stop()
        float.stop()
        mountJob?.cancel()
        tagJob?.cancel()
    }

    fun expand() {
        if (store.state.phase.isTerminal || float.position != null || dragMode != null) return
        send(FloatingCallStageFeature.Action.OrbTapped)
    }

    /** Drifting or held when something else takes the shape: it rests where it is. */
    fun settleFloat() {
        touch = null
        float.stop()?.let { rest(it) }
    }

    /** The stage changed without a drag (a tap, the chevron, TalkBack, the playground): the shape springs there. */
    fun grow(next: FloatingCallStage) {
        if (motion.isDragging || motion.target == next || store.state.phase.isTerminal) return
        connect.cancel()
        settleFloat()
        hideTag()
        bloomMounted = true
        if (next != FloatingCallStage.orb) dismissKeyboard()
        motion.animate(next, reduceMotion = reduceMotion)
    }

    /**
     * A keyboard rising in this window (a field the card doesn't cover, a sheet's search) would
     * bury Mute and End at the screen's foot: the card or sheet shrinks to the orb, which rides
     * above it.
     */
    fun keyboardRose() {
        if (store.state.floatingStage.stage == FloatingCallStage.orb || motion.isDragging || store.state.phase.isTerminal) return
        send(FloatingCallStageFeature.Action.KeyboardRose)
    }

    /**
     * A keyboard the composer doesn't ride (the drawer's search, a sheet's field) would hide the
     * orb resting above the composer: it rests above the keyboard instead, and back down as it
     * goes. [top] is in this overlay's space, null with no keyboard.
     */
    fun keyboardMoved(top: Float?) {
        if (layout.keyboardTop == top) return
        layout.keyboardTop = top
        val area = liveRestArea()
        float.clamp(into = area.reach.union(area.bounds))
    }

    /**
     * The call ended: from any stage, mid-lift included, the shape flies into the composer's
     * call button with the speed it has, or shrinks away where it rests when there's none.
     */
    fun leave() {
        hideTag()
        val geometry = stageGeometry()
        val flight = connect.hangUp(reduceMotion = reduceMotion)
        val shaping = motion.isActive
        val current = if (shaping) motion.frame(geometry) else flight?.frame ?: restingFrame()
        val drifting = float.velocity
        val velocity: List<Double>? = if (shaping) null else flight?.velocity ?: listOf(drifting.x.toDouble(), drifting.y.toDouble(), 0.0, 0.0)
        settleFloat()
        val rest = restFrame
        val button = center.landingButton
        val center = center
        val target: (() -> Rect?)? = if (button != null) ({ center.landingButton }) else null
        if (!reduceMotion && button != null) center.isLanding = true
        // Into the drawer's button it fades as it lands: the drawer covers where it rests.
        val fades = button == null || center.isDrawerOpen
        motion.land(
            rect = button ?: Rect(rest.left + rest.width * 0.25f, rest.top + rest.height * 0.25f, rest.right - rest.width * 0.25f, rest.bottom - rest.height * 0.25f),
            current = current,
            velocity = velocity,
            fades = fades,
            target = target,
            geometry = geometry,
            reduceMotion = reduceMotion,
        )
    }

    /** The voice agent moved the call: say where it talks now. The user's own switches stay quiet. */
    fun sessionMoved(title: String) {
        if (store.state.phase.isTerminal) return
        showTag(localized(R.string.voice_floating_now_talking_in, VoiceFeature.State.displayTitle(title)))
    }

    private fun showTag(text: String) {
        if (motion.isActive) return
        tagJob?.cancel()
        tagText = text
        tagShown = true
        tagJob = scope.launch {
            delay(2_200)
            hideTag()
        }
    }

    fun hideTag() {
        if (tagShown) tagShown = false
    }

    private fun release(velocity: Offset) {
        float.release(
            velocity = velocity,
            bounds = {
                val now = liveRestArea()
                FloatingCallFloatMotion.Bounds(now.bounds, now.reach)
            },
            reduceMotion = reduceMotion,
        ) { point -> rest(point) }
    }

    /** The orb rests at [point], for this call and the next. At the bottom of its space it follows the composer again. */
    fun rest(point: Offset) {
        val area = liveRestArea()
        val next = FloatingCallPlace(point.x, if (point.y >= area.bounds.bottom - 0.5f) null else point.y)
        if (center.place != next) center.place = next
    }

    /** 1 once the shape has all but reached the call button it lands in. */
    fun landedAmount(frame: Rect): Double {
        val landing = motion.landing
        if (!motion.landsInButton || landing == null) return 0.0
        val distance = maxOf(
            hypot(frame.center.x - landing.center.x, frame.center.y - landing.center.y),
            abs(frame.width - landing.width),
            abs(frame.height - landing.height),
        )
        return 1 - smooth(6.0, 40.0, distance.toDouble())
    }

    /** The orb (or capsule) takes touches where it's drawn, never smaller than a touch target; the card where it is, the sheet everywhere. */
    fun touchArea(frame: Rect, terminal: Boolean): FloatingCallTouchArea {
        if (opacity.value <= 0f || motion.landing != null || terminal) return FloatingCallTouchArea.None
        if (motion.isActive && motion.target == FloatingCallStage.full) return FloatingCallTouchArea.Everywhere
        if (motion.isActive || connect.frame == null) {
            val dx = min(0f, (frame.width - MIN_TOUCH) / 2)
            val dy = min(0f, (frame.height - MIN_TOUCH) / 2)
            return FloatingCallTouchArea.Frame(Rect(frame.left + dx, frame.top + dy, frame.right - dx, frame.bottom - dy))
        }
        return FloatingCallTouchArea.None
    }

    /** How much the session behind dims as the sheet fills. */
    fun dimAmount(): Double = FloatingCallStageTunables.dim.value * (motion.progress - 1).coerceIn(0.0, 1.0) * motion.opacity

    fun dimTapped() {
        if (dimAmount() > 0.001) send(FloatingCallStageFeature.Action.StepBack)
    }

    private fun toOverlay(local: Offset): Offset {
        val root = root
        val shape = shapeCoordinates
        val position = if (root != null && shape != null && root.isAttached && shape.isAttached) root.localPositionOf(shape, local) else local
        return position / density
    }

    // MARK: Dragging

    /**
     * The resting orb is under the finger from touch-down, 1:1, so a drag always moves it
     * (catching it mid-drift too), and what the touch was is judged only when it lifts
     * ([FloatingCallTouch]): a tap expands it, a tap on a drifting orb only stops it, and
     * anything else lets it go with the throw's speed.
     */
    suspend fun PointerInputScope.orbDrag() {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // A control on it (the capsule's End) took the touch.
            if (down.isConsumed) return@awaitEachGesture
            val tracker = VelocityTracker()
            val start = toOverlay(down.position)
            tracker.addPosition(down.uptimeMillis, start)
            orbChanged(start, start, down.uptimeMillis)
            var ended = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val location = toOverlay(change.position)
                    if (change.changedToUpIgnoreConsumed()) {
                        ended = true
                        if (change.isConsumed) {
                            orbCancelled()
                        } else {
                            tracker.addPosition(change.uptimeMillis, location)
                            val velocity = tracker.calculateVelocity()
                            orbEnded(change.uptimeMillis, Offset(velocity.x, velocity.y))
                        }
                        break
                    }
                    if (change.positionChanged()) {
                        tracker.addPosition(change.uptimeMillis, location)
                        orbChanged(location, start, change.uptimeMillis)
                        change.consume()
                    }
                }
            } finally {
                if (!ended) orbCancelled()
            }
        }
    }

    private fun orbChanged(location: Offset, start: Offset, time: Long) {
        if (connect.frame != null || motion.landing != null || store.state.phase.isTerminal) return
        if (touch == null) {
            val at = restingFrame().center
            val speed = float.velocity.getDistance()
            touch = FloatingCallOrbTouch(
                offset = at - start,
                origin = at,
                began = time,
                wasMoving = speed > FloatingCallTunables.catchSpeed.value,
                location = location,
                moved = time,
            )
            hideTag()
            float.hold(at)
        }
        val held = touch ?: return
        held.travel = max(held.travel, (location - start).getDistance())
        if ((location - held.location).getDistance() > 0.5f) {
            held.location = location
            held.moved = time
        }
        float.move(location + held.offset)
    }

    private fun orbEnded(time: Long, velocity: Offset) {
        val held = touch ?: return
        touch = null
        val kind = FloatingCallTouch(travel = held.travel.toDouble(), duration = (time - held.began) / 1000.0, wasMoving = held.wasMoving)
        when (kind) {
            FloatingCallTouch.tap -> {
                // It barely moved: it rests where it was, and grows from there.
                float.stop()
                if (held.origin != restFrame.center) rest(held.origin)
                expand()
            }
            FloatingCallTouch.caught -> release(Offset.Zero)
            FloatingCallTouch.drag -> {
                // Held still before it lifted, it's set down rather than thrown.
                val still = (time - held.moved) / 1000.0 > 0.06
                var thrown = if (still) Offset.Zero else velocity
                val speed = thrown.getDistance()
                val limit = FloatingCallTunables.maxSpeed.value.toFloat()
                if (speed > limit) thrown *= limit / speed
                release(thrown)
            }
        }
    }

    /** The touch went to something else: the orb is set down where it is. */
    private fun orbCancelled() {
        if (touch == null) return
        touch = null
        release(Offset.Zero)
    }

    /** On the card or the sheet a vertical drag holds the shape's top edge; a sideways one does nothing. */
    suspend fun PointerInputScope.shapeDrag() {
        val slop = 8f * density
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = toOverlay(down.position)
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, start)
            var began = false
            var ended = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val location = toOverlay(change.position)
                    tracker.addPosition(change.uptimeMillis, location)
                    // A quick flick can arrive whole with its lift: it still moves the shape.
                    if (!began && (change.position - down.position).getDistance() >= slop) began = true
                    if (began) {
                        shapeChanged(location, location - start)
                        if (dragMode == FloatingCallDragMode.stage) change.consume()
                    }
                    if (change.changedToUpIgnoreConsumed()) {
                        ended = true
                        if (began) shapeEnded(tracker.calculateVelocity().y)
                        break
                    }
                }
            } finally {
                // Cancelled (the system took the touch, the shape went away): it still settles on a stage.
                if (began && !ended) shapeEnded(0f)
                dragMode = null
            }
        }
    }

    private fun shapeChanged(location: Offset, translation: Offset) {
        if (connect.frame != null || motion.landing != null || store.state.phase.isTerminal) return
        val geometry = stageGeometry()
        if (dragMode == null) beginDrag(location, translation, geometry)
        if (dragMode != FloatingCallDragMode.stage) return
        motion.drag(location.y.toDouble(), geometry)
        val nearest = geometry.nearest(motion.progress)
        if (nearest != store.state.floatingStage.dragTarget) {
            send(FloatingCallStageFeature.Action.DragCrossed(nearest))
        }
    }

    private fun beginDrag(location: Offset, translation: Offset, geometry: FloatingCallStageGeometry) {
        // Held at the orb by a call row's request: a drag only moves it.
        val vertical = abs(translation.y) > abs(translation.x)
        dragMode = if (vertical && !store.state.floatingStage.isHeld) FloatingCallDragMode.stage else FloatingCallDragMode.ignored
        hideTag()
        if (dragMode != FloatingCallDragMode.stage) return
        bloomMounted = true
        dismissKeyboard()
        motion.beginDrag(location.y.toDouble(), geometry)
        send(FloatingCallStageFeature.Action.DragBegan)
    }

    private fun shapeEnded(velocityY: Float) {
        val mode = dragMode
        dragMode = null
        if (mode != FloatingCallDragMode.stage) return
        val settled = stageGeometry().settle(motion.progress, velocityY.toDouble())
        // A request that came up mid-drag holds it at the orb.
        val stage = if (store.state.floatingStage.isHeld) FloatingCallStage.orb else settled.stage
        send(FloatingCallStageFeature.Action.DragEnded(stage))
        motion.endDrag(stage, settled.velocity, reduceMotion)
    }

    // MARK: System back

    /** A predictive back gesture is pulling the card or sheet down a little, toward the stage below. */
    private var backPulling = false

    fun backStarted() {
        if (dragMode != null || motion.isDragging || motion.landing != null || store.state.phase.isTerminal) return
        backPulling = true
        motion.beginDrag(0.0, stageGeometry())
    }

    fun backProgressed(progress: Float) {
        if (!backPulling) return
        motion.drag(BACK_PULL * progress, stageGeometry())
    }

    /** Back steps one stage down. */
    fun backCommitted() {
        val next = store.state.floatingStage.stage.previous
        if (backPulling) {
            backPulling = false
            motion.endDrag(next ?: store.state.floatingStage.stage, 0.0, reduceMotion)
        }
        send(FloatingCallStageFeature.Action.StepBack)
    }

    fun backCancelled() {
        if (!backPulling) return
        backPulling = false
        motion.endDrag(store.state.floatingStage.stage, 0.0, reduceMotion)
    }

    companion object {
        /** How far a predictive back gesture pulls the shape down before it lets go, in dp. */
        const val BACK_PULL = 56.0

        /** Android's minimum touch target. */
        const val MIN_TOUCH = 48f
    }
}

private fun floatingCallMood(state: VoiceFeature.State): FloatingCallMood = when (state.phase) {
    VoiceFeature.State.Phase.Connecting -> FloatingCallMood.connecting
    VoiceFeature.State.Phase.Ended, VoiceFeature.State.Phase.Dropped, is VoiceFeature.State.Phase.Failed -> FloatingCallMood.ended
    else -> when {
        state.isReconnecting -> FloatingCallMood.connecting
        state.targetNeedsInput || state.holdsClientToolCard -> FloatingCallMood.needsInput
        state.isWorking -> FloatingCallMood.thinking
        else -> FloatingCallMood.live
    }
}

/** Playground scenes name themselves. */
private fun floatingCallStatus(scene: FloatingCallScene?, status: String): String = when (scene) {
    FloatingCallScene.needsInput -> "Needs your input"
    FloatingCallScene.thinking -> "Working"
    FloatingCallScene.agentSpeaking -> "Speaking"
    FloatingCallScene.listening -> "Listening"
    FloatingCallScene.bothSpeaking -> "Both speaking"
    FloatingCallScene.idle -> "Idle"
    null -> status
}

private class FloatingCallPrevious<T>(var value: T)

/** Runs [action] after a composition in which [value] changed (not initially). */
@Composable
private fun <T> OnChange(value: T, action: (T) -> Unit) {
    val previous = remember { FloatingCallPrevious(value) }
    SideEffect {
        if (previous.value != value) {
            previous.value = value
            action(value)
        }
    }
}

/** Lays its children out unconstrained at its origin; each places itself with `offset`. */
@Composable
private fun FloatingCallStack(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEach { it.place(0, 0) }
        }
    }
}

private fun Modifier.placed(x: Float, y: Float, width: Float, height: Float): Modifier =
    offset { IntOffset(x.dp.roundToPx(), y.dp.roundToPx()) }.size(max(0f, width).dp, max(0f, height).dp)

@Composable
private fun FloatingCallSurface(store: VoiceStoreOf, screen: Size, safeArea: FloatingCallEdgeInsets) {
    val center = remember { FloatingCallCenter.shared }
    val window = LocalFloatingCallWindow.current
    val reduceMotion = rememberReduceMotion()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val model = remember(store) { FloatingCallSurfaceModel(store, center, scope) }
    SideEffect {
        model.reduceMotion = reduceMotion
        model.window = window
        model.density = density
        model.dismissKeyboard = {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }

    val phase by store.observe { it.phase }
    val isMuted by store.observe { it.isMuted }
    val isSpeaking by store.observe { it.isAssistantSpeaking }
    val mood by store.observe { floatingCallMood(it) }
    val storeStatus by store.observe { it.statusText }
    val connected by store.observe { it.connectedAt != null }
    val unpromptedMove by store.observe { it.unpromptedMove }
    val stage by store.observe { it.floatingStage.stage }
    val runningTool by store.observe { it.sessionDelegator.runningTools.lastOrNull()?.name }
    val terminal = phase.isTerminal
    val scene = center.scene
    val statusText = floatingCallStatus(scene, storeStatus)
    val expandLabel = stringResource(R.string.voice_floating_expand)

    // The keyboard raises the composer in one layout pass: glide after it.
    val rawComposerTop = center.composer?.top
    val composerTop = if (rawComposerTop != null) {
        animateFloatAsState(
            rawComposerTop,
            Motion.layout.animation(reduceMotion = reduceMotion),
            label = "floatingCallComposer",
        ).value
    } else {
        null
    }

    val motion = model.motion
    val connect = model.connect
    val float = model.float
    val style = model.style
    val restSize = model.restSize
    // The window's keyboard top while one is up, in this overlay's space (it fills the window edge
    // to edge).
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current) / density
    val keyboardTop = if (imeBottom > 0f && imeBottom < screen.height) screen.height - imeBottom else null
    val restArea = FloatingCallRestArea(screen, safeArea, composerTop, restSize, keyboardTop)
    val restFrame = restArea.frame(model.place)
    val geometry = floatingCallStageGeometry(restArea, restFrame)
    // Where the shape is this frame changes every frame it moves: it's read only while laying out
    // and drawing ([frameOf], [FloatingCallSurfaceLook]), and only what switches a part on or off
    // is read here, so a drag or a flight recomposes nothing.
    val look = remember(model) { FloatingCallSurfaceLook(model, geometry, restArea) }
    look.geometry = geometry
    look.restArea = restArea
    look.restFrame = restFrame
    look.screen = screen
    val frameOf = look::frame
    val shaping by remember(look) { derivedStateOf { motion.isActive } }
    val lifting = !shaping && look.isLifting
    val lifted = connect.isBreakingOut || float.isHeld
    val trailing = look.isTrailing
    val dimmed = look.isDimmed
    val touchKind by remember(look, terminal) { derivedStateOf { look.touchKind(terminal) } }
    LaunchedEffect(look, window, terminal) {
        if (window == null) return@LaunchedEffect
        snapshotFlow { model.touchArea(look.frame(), terminal) }.collect { if (window.touchArea != it) window.touchArea = it }
    }
    SideEffect {
        if (center.card != geometry.cardFrame) center.card = geometry.cardFrame
        val orbLayout = FloatingCallOrbLayout(screen, safeArea, restSize)
        if (center.orbLayout != orbLayout) center.orbLayout = orbLayout
    }

    // A new screen or safe area lays out again.
    LaunchedEffect(model) {
        model.laidOut(screen, safeArea)
        model.appeared()
    }
    OnChange(screen to safeArea) { (size, insets) -> model.laidOut(size, insets) }
    DisposableEffect(model) {
        onDispose {
            if (center.orbLayout != null) center.orbLayout = null
            model.disappeared()
        }
    }
    motion.link.Drive()
    connect.link.Drive()
    float.link.Drive()

    OnChange(terminal) { if (it) model.leave() }
    OnChange(motion.hasLanded) { if (it && center.isLanding) center.isLanding = false }
    OnChange(connected) { if (it && !store.state.phase.isTerminal) connect.connected(reduceMotion = reduceMotion) }
    OnChange(unpromptedMove) { if (it != null) model.sessionMoved(store.state.sessionTitle) }
    OnChange(stage) { model.grow(it) }
    OnChange(keyboardTop != null) { if (it) model.keyboardRose() }
    OnChange(keyboardTop) { model.keyboardMoved(it) }
    // System back steps the card or sheet down, a predictive back pulling it a little first.
    PredictiveBackHandler(enabled = stage != FloatingCallStage.orb && !terminal) { events ->
        model.backStarted()
        try {
            events.collect { model.backProgressed(it.progress) }
            model.backCommitted()
        } catch (cancelled: CancellationException) {
            model.backCancelled()
            throw cancelled
        }
    }
    val panelRequest = center.panelRequest
    OnChange(panelRequest.count) { count ->
        if (count != model.lastPanelRequest) {
            model.lastPanelRequest = count
            model.send(FloatingCallStageFeature.Action.Requested(if (panelRequest.open) FloatingCallStage.card else FloatingCallStage.orb))
        }
    }

    val orbConfig = LivingOrbConfig(
        mood = mood,
        isMuted = isMuted,
        isSpeaking = isSpeaking,
        scene = scene,
        tool = runningTool,
    )

    FloatingCallStack(
        Modifier
            .fillMaxSize()
            .onPlaced { model.root = it }
            // TalkBack's dismiss steps one stage down.
            .semantics {
                dismiss {
                    model.send(FloatingCallStageFeature.Action.StepBack)
                    true
                }
            },
    ) {
        if (dimmed) {
            // The session behind dims as the sheet fills.
            Box(
                Modifier
                    .placed(0f, 0f, screen.width, screen.height)
                    .drawBehind { drawRect(Color.Black.copy(alpha = model.dimAmount().toFloat().coerceIn(0f, 1f))) }
                    .clearAndSetSemantics {},
            )
        }
        if (touchKind == FloatingCallTouchKind.everywhere) {
            // With the sheet up the window takes every touch; a tap on the dimmed session steps back.
            Box(
                Modifier
                    .placed(0f, 0f, screen.width, screen.height)
                    .pointerInput(model) { detectTapGestures { model.dimTapped() } }
                    .clearAndSetSemantics {},
            )
        }

        // The tag beside the orb naming the session the call now talks to. It appears on micro
        // and scales about itself.
        val tagVisible = model.tagShown && !lifting && look.isResting && !shaping
        Box(
            Modifier.placed {
                val frame = frameOf()
                Rect(Offset(if (trailing) frame.left - 10 - 280 else frame.right + 10, frame.center.y - 18), Size(280f, 36f))
            },
            contentAlignment = if (trailing) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            val micro = MotionTransition.micro(reduceMotion)
            AnimatedVisibility(
                visible = tagVisible,
                enter = micro.enter,
                exit = micro.exit,
                label = "floatingCallTag",
            ) {
                FloatingCallTag(model.tagText.orEmpty())
            }
        }

        val shape = look.shape
        val restShadow = if (lifted) FloatingCallTunables.liftShadow.value else FloatingCallTunables.restShadow.value
        // Resting as the orb, a touch holds it from touch-down; grown, a drag steps the card and sheet.
        val gestures = when {
            touchKind == FloatingCallTouchKind.none -> Modifier
            shaping -> Modifier.pointerInput(model, "shape") { with(model) { shapeDrag() } }
            else -> Modifier.pointerInput(model, "orb") { with(model) { orbDrag() } }
        }
        val darkShadow = OpenCodeTheme.colors.isDark
        val cardBorder = VoiceCallSurfaceDefaults.cardBorder
        val cardFill = VoiceCallSurfaceDefaults.card
        Box(
            Modifier
                .placed { frameOf() }
                .onPlaced { model.shapeCoordinates = it }
                // The grown card's soft shadow, all round (the elevation shadow barely reaches its top edge).
                .dropShadow(shape) {
                    radius = 20.dp.toPx()
                    offset = Offset(0f, 3.dp.toPx())
                    color = Color.Black.copy(alpha = (if (darkShadow) 0.5f else 0.1f) * look.cardShadow().toFloat() * look.alpha())
                }
                .graphicsLayer {
                    alpha = look.alpha()
                    this.shape = shape
                    // The orb's own shadow hands over to the card's, so the card never wears both.
                    shadowElevation = max(0.0, restShadow / 2 * (1 - look.buttonLook()) * (1 - look.cardShadow())).toFloat().dp.toPx()
                }
                .then(gestures),
        ) {
            val rides = shaping || lifting || !look.isResting
            // Growing, the orb's face keeps its own size and is scaled with the shape, as it is lifting out of the call button.
            val outer = {
                val frame = frameOf()
                if (rides) Rect(Offset.Zero, frame.size) else look.restFrame.translate(-frame.left, -frame.top)
            }
            // The shape's own layers, clipped to it; the orb's controls and the call button ride
            // above, so the mute badge can sit over the orb's rim.
            FloatingCallStack(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        this.shape = shape
                        clip = true
                    }
                    .background(cardFill)
                    .drawBehind {
                        // The sheet's backing deepens as it fills, so its text reads over anything.
                        val backing = look.backing()
                        if (backing > 0) drawRect(cardFill.copy(alpha = backing.toFloat().coerceIn(0f, 1f)))
                    }
                    .drawWithContent {
                        drawContent()
                        val edge = cardBorder.copy(alpha = cardBorder.alpha * (1 - look.buttonLook()).toFloat())
                        drawOutline(shape.createOutline(size, layoutDirection, this), edge, style = Stroke(1.dp.toPx()))
                    },
            ) {
                val contentMounted = look.isContentMounted

                if (model.bloomMounted) {
                    // The orb's dot field grown with the shape: one disc near the card's top, smaller
                    // and higher as the sheet fills. It keeps one size and moves with the bloom.
                    LivingOrbView(
                        orbConfig,
                        Modifier
                            .placed {
                                val (origin, _) = look.bloom()
                                val frame = frameOf()
                                Rect(Offset(origin.x - frame.left, origin.y - frame.top), Size(screen.width, FIELD_HEIGHT))
                            }
                            .graphicsLayer { alpha = look.bloomShown().toFloat() }
                            .clearAndSetSemantics {},
                        isHidden = look.isBloomHidden,
                        bloom = { look.bloom().second },
                    )
                }

                val scalesFace = (shaping || lifting) && style == FloatingCallStyle.orb
                Box(
                    Modifier
                        .placed(outer)
                        .graphicsLayer { alpha = look.faceShown().toFloat() }
                        .clearAndSetSemantics {},
                    contentAlignment = Alignment.Center,
                ) {
                    FloatingCallRestFace(
                        style = style,
                        orbSize = restSize.height,
                        trailing = look.restsTrailing,
                        config = orbConfig,
                        isHidden = look.isFaceHidden,
                        modifier = if (scalesFace) {
                            Modifier
                                .requiredSize(restFrame.width.dp, restFrame.height.dp)
                                .graphicsLayer {
                                    val frame = frameOf()
                                    val rest = look.restFrame
                                    val scale = min(frame.width / max(1f, rest.width), frame.height / max(1f, rest.height))
                                    scaleX = scale
                                    scaleY = scale
                                }
                        } else {
                            Modifier.fillMaxSize()
                        },
                    )
                }
                if (contentMounted) {
                    // The card's content is laid out at the card's size and pinned to the growing shape's
                    // bottom corner, revealed as the shape grows over it; past the card it lays out on the shape.
                    val interactive = look.isContentInteractive
                    FloatingCallStageContent(
                        store = store,
                        layout = look::contentLayout,
                        status = statusText,
                        onMinimize = { model.send(FloatingCallStageFeature.Action.MinimizeTapped) },
                        modifier = Modifier
                            .placed {
                                val frame = frameOf()
                                look.contentBox().translate(-frame.left, -frame.top)
                            }
                            .graphicsLayer { alpha = look.contentShown().toFloat() }
                            .then(if (interactive) Modifier else Modifier.blocksTouches())
                            .semantics {
                                // Already the sheet: nothing to expand to.
                                if (stage != FloatingCallStage.full) {
                                    customActions = listOf(
                                        CustomAccessibilityAction(expandLabel) {
                                            model.send(FloatingCallStageFeature.Action.Requested(FloatingCallStage.full))
                                            true
                                        },
                                    )
                                }
                            },
                    )
                }
            }
            FloatingCallStack(Modifier.fillMaxSize()) {
                if (look.showsRestControls) {
                    FloatingCallRestControls(
                        style = style,
                        orbSize = restSize.height,
                        trailing = look.restsTrailing,
                        isMuted = isMuted,
                        statusText = statusText,
                        reduceMotion = reduceMotion,
                        isEnabled = !lifting,
                        onExpand = model::expand,
                        onEnd = { store.send(VoiceFeature.Action.EndPressed) },
                        modifier = Modifier.placed(outer),
                    )
                }
                // The composer's call button: the shape starts as it, the light giving way to the
                // dark spinner as its glyph spins up, and landing it takes the button's look again.
                if (look.showsButton) {
                    Box(
                        Modifier
                            .placed { Rect(Offset.Zero, frameOf().size) }
                            .graphicsLayer { alpha = look.buttonLook().toFloat() }
                            .background(OpenCodeTheme.colors.text, CircleShape)
                            .clearAndSetSemantics {},
                        contentAlignment = Alignment.Center,
                    ) {
                        OpenCodeIcon(
                            SFSymbol.PhoneFill,
                            null,
                            Modifier.graphicsLayer { rotationZ = Math.toDegrees(connect.glyphAngle).toFloat() },
                            size = 20.dp,
                            tint = OpenCodeTheme.colors.canvas,
                        )
                    }
                }
            }
        }
    }
}

/** The kinds of [FloatingCallTouchArea], without the frame that moves every frame. */
private enum class FloatingCallTouchKind { none, everywhere, frame }

/**
 * Everything about the floating call's shape that follows it frame by frame, read while laying
 * out and drawing (a lambda in a layout, offset, layer or draw modifier), so a drag or a flight
 * recomposes nothing. The flags that switch a part on or off are derived states: composition
 * reads them, and they change only when the part does.
 */
@Stable
private class FloatingCallSurfaceLook(
    private val model: FloatingCallSurfaceModel,
    geometry: FloatingCallStageGeometry,
    restArea: FloatingCallRestArea,
) {
    var geometry by mutableStateOf(geometry)
    var restArea by mutableStateOf(restArea)
    var restFrame by mutableStateOf(Rect.Zero)
    var screen by mutableStateOf(Size.Zero)

    private val motion get() = model.motion
    private val connect get() = model.connect
    private val float get() = model.float

    private fun shaping() = motion.isActive

    private fun restingFrame(): Rect {
        val at = float.position ?: return restFrame
        return restArea.frame(centeredAt = at)
    }

    fun frame(): Rect {
        if (shaping()) return motion.frame(geometry)
        return connect.frame ?: restingFrame()
    }

    private fun grown(): Double = if (shaping()) geometry.cardness(frame()) else 0.0

    private fun full(): Double = if (shaping()) geometry.fullness(frame()) else 0.0

    /** The shape's outline: its corners round as the card's and the sheet's, a circle as the orb. */
    val shape = object : Shape {
        override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
            val frame = frame()
            val short = min(frame.width, frame.height).toDouble()
            val radii = if (shaping()) geometry.radii(frame) else FloatingCallStageGeometry.Radii(short / 2, short / 2)
            val top = with(density) { max(0.0, radii.top).toFloat().dp.toPx() }
            val bottom = with(density) { max(0.0, radii.bottom).toFloat().dp.toPx() }
            return Outline.Rounded(
                RoundRect(
                    rect = Rect(Offset.Zero, size),
                    topLeft = CornerRadius(top),
                    topRight = CornerRadius(top),
                    bottomRight = CornerRadius(bottom),
                    bottomLeft = CornerRadius(bottom),
                ),
            )
        }
    }

    fun alpha(): Float = model.opacity.value * motion.opacity.toFloat()

    /** Looking like the composer's button, it casts no shadow of its own. */
    fun buttonLook(): Double {
        val lifting = !shaping() && connect.frame != null
        return max(if (lifting) connect.button else 0.0, model.landedAmount(frame()))
    }

    fun cardShadow(): Double = grown() * (1 - buttonLook())

    fun backing(): Double = lerp(FloatingCallStageTunables.cardFill.value, FloatingCallStageTunables.sheetFill.value, full()) * grown()

    /** The small face hands over to the bigger field as the orb starts to grow. */
    fun faceShown(): Double = 1 - smooth(0.03, 0.15, grown())

    fun bloomShown(): Double = smooth(0.0, 0.12, grown())

    /** The bloom's field's top-left corner, in the overlay, and the bloom placed in it. */
    fun bloom(): Pair<Offset, LivingOrbBloom> {
        val frame = frame()
        val full = full()
        val bloom = geometry.bloom(frame)
        val originX = bloom.centerX - screen.width / 2
        val originY = bloom.centerY - FIELD_ABOVE
        val bottom = min(geometry.visible(frame).bottom.toDouble(), bloom.centerY + bloom.radius * lerp(4.0, 1.35, full))
        val placed = LivingOrbBloom(
            center = Offset(screen.width / 2, FIELD_ABOVE.toFloat()),
            radius = bloom.radius.toFloat(),
            bottom = (bottom - originY).toFloat(),
            fade = if (full > 0) 40f else 0.5f,
            visible = frame.translate(-originX.toFloat(), -originY.toFloat()),
        )
        return Offset(originX.toFloat(), originY.toFloat()) to placed
    }

    /** Laid out as the card (pinned to the shape's bottom corner) or on the sheet's visible part. */
    private fun asCard(): Boolean =
        if (motion.landing == null) motion.progress <= 1 else frame().height <= geometry.cardFrame.height + 0.5f

    fun contentBox(): Rect {
        val frame = frame()
        val card = geometry.cardFrame
        if (!asCard()) return geometry.visible(frame)
        return Rect(Offset(if (geometry.trailing) frame.right - card.width else frame.left, frame.bottom - card.height), card.size)
    }

    fun contentLayout(): FloatingCallStageContentLayout {
        val box = contentBox()
        val layoutFull = if (asCard()) 0.0 else full()
        val bloom = FloatingCallStageGeometry.Bloom(
            y = lerp(geometry.cardBloomFitted.y, geometry.fullBloomFitted.y, layoutFull),
            radius = lerp(geometry.cardBloomFitted.radius, geometry.fullBloomFitted.radius, layoutFull),
        )
        return FloatingCallStageContentLayout(box.width, box.height, layoutFull.toFloat(), bloom, lineWidth = geometry.cardFrame.width)
    }

    fun contentShown(): Double = (if (asCard()) smooth(0.35, 0.92, grown()) else 1.0) * motion.content

    val isLifting by derivedStateOf { connect.frame != null }
    val isResting by derivedStateOf { float.position == null }
    val isTrailing by derivedStateOf { frame().center.x > screen.width / 2 }
    val restsTrailing by derivedStateOf { restingFrame().center.x > screen.width / 2 }
    val isDimmed by derivedStateOf { model.dimAmount() > 0.001 }
    val isFaceHidden by derivedStateOf { faceShown() <= 0 }
    val isBloomHidden by derivedStateOf { bloomShown() <= 0 }
    val showsButton by derivedStateOf { buttonLook() > 0 }
    val showsRestControls by derivedStateOf {
        !shaping() || (motion.target == FloatingCallStage.orb && motion.progress < 0.05 && motion.landing == null)
    }
    val isContentMounted by derivedStateOf {
        if (motion.landing == null) motion.progress > 0.01 || motion.target != FloatingCallStage.orb else motion.content > 0
    }
    val isContentInteractive by derivedStateOf { motion.target != FloatingCallStage.orb && motion.landing == null }

    fun touchKind(terminal: Boolean): FloatingCallTouchKind = when (model.touchArea(frame(), terminal)) {
        FloatingCallTouchArea.None -> FloatingCallTouchKind.none
        FloatingCallTouchArea.Everywhere -> FloatingCallTouchKind.everywhere
        is FloatingCallTouchArea.Frame -> FloatingCallTouchKind.frame
    }
}

/** [placed] where [rect] says, read while laying out: it can move and resize every frame without recomposing. */
private fun Modifier.placed(rect: () -> Rect): Modifier = layout { measurable, _ ->
    val now = rect()
    val width = max(0f, now.width).dp.roundToPx()
    val height = max(0f, now.height).dp.roundToPx()
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(width, height) { placeable.place(now.left.dp.roundToPx(), now.top.dp.roundToPx()) }
}

/** Above the bloom's centre and the room below it in its field: enough for the card and the sheet's field at any stage between. */
/**
 * How long the composer's own button takes to come back under a landed shape: the incoming button
 * has to be there before the shape leaves.
 */
private const val FLOATING_CALL_BUTTON_RETURN = 0.15

private const val FIELD_ABOVE = 220.0
private const val FIELD_HEIGHT = 720f

/** The orb's face: the Wisp disc placed to fill it (the shape clips its dots), or kept within its own edge in the capsule. */
@Composable
private fun FloatingCallOrbFace(config: LivingOrbConfig, placed: Boolean, isHidden: Boolean, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val width = maxWidth.value
        val height = maxHeight.value
        val bloom = if (placed) LivingOrbBloom(center = Offset(width / 2, height / 2), radius = min(width, height) / 2) else null
        LivingOrbView(config.copy(bloom = bloom), Modifier.fillMaxSize(), isHidden = isHidden)
    }
}

/** The resting orb's face, laid out as [FloatingCallRestControls]. */
@Composable
private fun FloatingCallRestFace(
    style: FloatingCallStyle,
    orbSize: Float,
    trailing: Boolean,
    config: LivingOrbConfig,
    isHidden: Boolean,
    modifier: Modifier,
) {
    when (style) {
        FloatingCallStyle.orb -> FloatingCallOrbFace(config, placed = true, isHidden = isHidden, modifier = modifier)
        FloatingCallStyle.capsule -> {
            val pad = capsuleEndPadding(orbSize)
            Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                if (trailing) Spacer(Modifier.padding(start = pad.dp).size(FloatingCallSurfaceModel.MIN_TOUCH.dp))
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    FloatingCallOrbFace(config, placed = false, isHidden = isHidden, modifier = Modifier.width(orbSize.dp).fillMaxHeight())
                }
                if (!trailing) Spacer(Modifier.padding(end = pad.dp).size(FloatingCallSurfaceModel.MIN_TOUCH.dp))
            }
        }
    }
}

/** End sits toward the screen's middle, clear of the edge it rests on. */
private fun capsuleEndDiameter(orbSize: Float): Float = min(40f, orbSize - 12)

private fun capsuleEndPadding(orbSize: Float): Float = max(0f, (orbSize - capsuleEndDiameter(orbSize)) / 2 - 2)

/** What you touch on the resting orb: mounted only while it rests as the orb. */
@Composable
private fun FloatingCallRestControls(
    style: FloatingCallStyle,
    orbSize: Float,
    trailing: Boolean,
    isMuted: Boolean,
    statusText: String,
    reduceMotion: Boolean,
    isEnabled: Boolean,
    onExpand: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier,
) {
    when (style) {
        FloatingCallStyle.orb -> FloatingCallOrbControls(isMuted, statusText, reduceMotion, onExpand, modifier)
        FloatingCallStyle.capsule -> {
            val diameter = capsuleEndDiameter(orbSize)
            val pad = capsuleEndPadding(orbSize)
            Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                if (trailing) FloatingCallEndButton(diameter, isEnabled, onEnd, Modifier.padding(start = pad.dp))
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    FloatingCallOrbControls(isMuted, statusText, reduceMotion, onExpand, Modifier.width(orbSize.dp).fillMaxHeight())
                }
                if (!trailing) FloatingCallEndButton(diameter, isEnabled, onEnd, Modifier.padding(end = pad.dp))
            }
        }
    }
}

/** The orb as one control: a tap is told from a drag by the shape's own gesture; TalkBack expands it. */
@Composable
private fun FloatingCallOrbControls(isMuted: Boolean, statusText: String, reduceMotion: Boolean, onExpand: () -> Unit, modifier: Modifier) {
    val orbDescription = stringResource(R.string.voice_floating_orb_description)
    val showControlsLabel = stringResource(R.string.voice_floating_orb_show_controls)
    Box(
        modifier.clearAndSetSemantics {
            role = Role.Button
            contentDescription = orbDescription
            stateDescription = statusText
            testTag = "floating-call-orb"
            onClick(label = showControlsLabel) {
                onExpand()
                true
            }
        },
    ) {
        AnimatedVisibility(
            visible = isMuted,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 2.dp, y = (-2).dp),
            enter = MotionTransition.micro(reduceMotion).enter,
            exit = MotionTransition.micro(reduceMotion).exit,
            label = "floatingCallMuteBadge",
        ) {
            Box(
                Modifier
                    .size(20.dp)
                    .background(VoiceCallSurfaceDefaults.controlFill, CircleShape)
                    .border(1.dp, VoiceCallSurfaceDefaults.controlBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                OpenCodeIcon(SFSymbol.MicSlashFill, null, size = 12.dp, tint = OpenCodeTheme.colors.text)
            }
        }
    }
}

/** The capsule's End: a red circle in a full touch target. */
@Composable
private fun FloatingCallEndButton(diameter: Float, isEnabled: Boolean, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val endDescription = stringResource(R.string.voice_floating_end_description)
    Box(
        modifier
            .size(FloatingCallSurfaceModel.MIN_TOUCH.dp)
            // The press shows on the round button, not the square touch target around it.
            .clip(CircleShape)
            .clickable(enabled = isEnabled, role = Role.Button, onClick = onEnd)
            .semantics { contentDescription = endDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(diameter.dp)
                .background(VoiceCallSurfaceDefaults.red, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            OpenCodeIcon(SFSymbol.PhoneDownFill, null, size = 18.dp, tint = Color.White)
        }
    }
}

/** The tag beside the orb naming the session the call talks to. */
@Composable
private fun FloatingCallTag(text: String) {
    Box(
        Modifier
            .height(36.dp)
            .background(VoiceCallSurfaceDefaults.color, CircleShape)
            .border(1.dp, VoiceCallSurfaceDefaults.border, CircleShape)
            .padding(horizontal = 14.dp)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium), color = OpenCodeTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * One of the card's controls: an equal circle, centred in an equal share of the row, so every
 * glyph sits on one line.
 *
 * @param isPlaceholder shown so the layout can be judged; needs plumbing that isn't built yet.
 * @param scale how much bigger the circle is drawn than [diameter], read while drawing: the card's
 * controls grow toward the sheet's without being laid out again.
 */
@Composable
internal fun RowScope.FloatingCallControl(
    name: String,
    symbol: SFSymbol,
    diameter: Float,
    isOn: Boolean = false,
    isEnabled: Boolean = true,
    isDestructive: Boolean = false,
    isPlaceholder: Boolean = false,
    scale: () -> Float = { 1f },
    action: () -> Unit,
) {
    val active = !isPlaceholder && isEnabled
    val placeholderState = stringResource(R.string.voice_floating_control_placeholder_state)
    // Mute turning on, or every control dimming as the call ends, eases rather than flipping.
    val reduceMotion = rememberReduceMotion()
    val dim by animateFloatAsState(if (isPlaceholder || !isEnabled) 0.35f else 1f, Motion.swap.animation(reduceMotion = reduceMotion), label = "floatingCallControlDim")
    val text = OpenCodeTheme.colors.text
    val background = OpenCodeTheme.colors.canvas
    val fill by animateColorAsState(
        if (isDestructive) VoiceCallSurfaceDefaults.red else if (isOn) text else VoiceCallSurfaceDefaults.controlFill,
        Motion.swap.animation(reduceMotion = reduceMotion),
        label = "floatingCallControlFill",
    )
    val edge by animateColorAsState(
        if (isDestructive || isOn) Color.Transparent else VoiceCallSurfaceDefaults.controlBorder,
        Motion.easeOut(0.18),
        label = "floatingCallControlEdge",
    )
    val tint = if (isDestructive) Color.White else if (isOn) background else text
    val shape = RoundedCornerShape(OpenCodeRadius.panel)
    // The whole share of the row takes the tap; the press shows on the plate only.
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .weight(1f)
            .heightIn(min = max(48f, diameter).dp)
            .clickable(interactionSource = interaction, indication = null, enabled = active, role = Role.Button, onClick = action)
            .semantics {
                contentDescription = name
                if (isPlaceholder) stateDescription = placeholderState
                if (!active) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        // The glyph takes the full dim and the plate only part of it, so a control that isn't
        // available still reads as a button on the light card.
        val plateDim = 1f - (1f - dim) * 0.4f
        Box(
            Modifier
                .size(diameter.dp)
                .graphicsLayer {
                    val grown = scale()
                    scaleX = grown
                    scaleY = grown
                }
                .alpha(plateDim)
                .clip(shape)
                .background(fill, shape)
                .indication(interaction, ripple())
                .border(1.dp, edge, shape),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(symbol, animationSpec = Motion.swap.animation(reduceMotion = reduceMotion), label = "floatingCallControlGlyph") { shown ->
                OpenCodeIcon(shown, null, tint = tint.copy(alpha = tint.alpha * dim / plateDim), size = (diameter * 0.38f + 4).dp)
            }
        }
    }
}

/** How long the call has run, or what it's doing before it connects. */
@Composable
internal fun FloatingCallDuration(connectedAt: Instant?, status: String, modifier: Modifier = Modifier) {
    val date = remember { dependency(DateClient) }
    var seconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(connectedAt) {
        if (connectedAt == null) return@LaunchedEffect
        while (true) {
            val millis = Duration.between(connectedAt, date.now()).toMillis()
            seconds = max(0L, millis / 1000)
            delay(1_000 - Math.floorMod(millis, 1_000L))
        }
    }
    val text = if (connectedAt == null) status else "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
    val durationDescription = stringResource(R.string.voice_floating_duration_description)
    Text(
        text,
        style = OpenCodeTextStyles.footnote.copy(fontFeatureSettings = "tnum"),
        color = OpenCodeTheme.colors.secondaryText,
        maxLines = 1,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = durationDescription
            // TalkBack reads "1:05" as a time of day; say how long it's been.
            stateDescription = if (connectedAt == null) text else floatingCallSpokenDuration(seconds)
        },
    )
}

/** A call's length as TalkBack should say it: "1 minute, 5 seconds". */
internal fun floatingCallSpokenDuration(seconds: Long): String {
    val minutes = seconds / 60
    val rest = seconds % 60
    if (minutes == 0L) return localizedPlural(R.plurals.voice_floating_spoken_seconds, rest.toInt(), rest.toInt())
    return localized(
        R.string.voice_floating_spoken_duration,
        localizedPlural(R.plurals.voice_floating_spoken_minutes, minutes.toInt(), minutes.toInt()),
        localizedPlural(R.plurals.voice_floating_spoken_seconds, rest.toInt(), rest.toInt()),
    )
}

private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

/** 0 below [low], 1 above [high], eased between. */
private fun smooth(low: Double, high: Double, value: Double): Double {
    val t = ((value - low) / (high - low)).coerceIn(0.0, 1.0)
    return t * t * (3 - 2 * t)
}
