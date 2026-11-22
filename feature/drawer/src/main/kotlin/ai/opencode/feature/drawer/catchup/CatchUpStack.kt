package ai.opencode.feature.drawer.catchup

import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.LocalContainerInsets
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.drawer.container.clearAndMergeSemantics
import ai.opencode.feature.drawer.motion.DirectionalPan
import ai.opencode.feature.drawer.motion.DrawerPanRegions
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

/** Creates the stack's motion owner, bound to this composition's frame clock. */
@Composable
fun rememberCatchUpStackController(): CatchUpStackController {
    val scope = rememberCoroutineScope()
    return remember { CatchUpStackController().also { it.scope = scope } }
}

/**
 * The Catch Up card stack: the live session card inset between the chrome's header and footer, the
 * next session's preview beneath it. Drag the card right to Mark
 * as Read, left to Keep Unread; the chrome's buttons play the same flight through [proxy].
 *
 * Draw it edge to edge over [CatchUpChrome]; it insets the card itself (system bars + header,
 * system bars + footer, `sideInset` at the sides). The live card and the preview consume those
 * insets and provide them as [LocalContainerInsets], so the hosted session lays out against the
 * card's edges, and the keyboard reaches into the card only as far as it overlaps it.
 *
 * Swiping never recomposes [card] or [preview]: motion is snapshot state read in `graphicsLayer`.
 *
 * @param stage the queue and its actions. Changes are diffed: a new queue animates (rise, fade)
 *   unless a flight or drag is running, which settles against the latest stage when it lands.
 * @param revealsPreviewOnBegin Reveal the card behind as soon as the stack begins. A host that
 *     morphs into Catch Up (the session container) passes `false` and calls
 *     [CatchUpStackController.revealPreview] when its morph lands.
 * @param panRegions The drawer's pan regions: a horizontal scroller in the card that can still move
 *     the way the finger goes keeps the drag.
 * @param card the live card's body for the open session (`null` once the stack is empty).
 * @param preview An inert preview of the next session, with the host's preview revision so it can
 *     skip re-rendering for unrelated changes.
 */
@Composable
fun CatchUpStack(
    stage: CatchUpStage,
    modifier: Modifier = Modifier,
    controller: CatchUpStackController = rememberCatchUpStackController(),
    proxy: CatchUpStageProxy? = null,
    previewRevision: Int = 0,
    revealsPreviewOnBegin: Boolean = true,
    isExiting: Boolean = false,
    panRegions: DrawerPanRegions? = null,
    /** What [proxy]'s buttons do. */
    dismissCard: (CatchUpDisposition) -> Unit = controller::fling,
    undoCard: () -> Unit = controller::undo,
    leavingCard: (@Composable () -> Unit)? = null,
    card: @Composable (sessionID: String?) -> Unit,
    preview: @Composable (sessionID: String, revision: Int) -> Unit,
) {
    val reducedMotion = rememberReduceMotion()
    val colors = OpenCodeTheme.colors
    val isDark = colors.isDark
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val insetPadding = insets.asPaddingValues()
    // The window's edges outside the card: the system bars plus the header, footer and sides. The
    // hosted session lays out against the card's edges, and the keyboard reaches into the card only
    // as far as it overlaps it.
    val cardInsets = remember(insets) {
        insets.add(
            WindowInsets(
                left = CatchUpMetrics.sideInset,
                top = CatchUpMetrics.headerHeight,
                right = CatchUpMetrics.sideInset,
                bottom = CatchUpMetrics.footerHeight,
            ),
        )
    }
    val cornerRadius = CatchUpMetrics.cornerRadius
    val borderWidth: Dp = if (isDark) 1.dp else with(density) { 1f.toDp() }
    val baseBorder = CatchUpMetrics.borderColor(isDark, colors.border)
    val canvas = colors.canvas

    SideEffect {
        controller.reducedMotion = reducedMotion
        controller.isDark = isDark
        controller.endEditing = { focusManager.clearFocus() }
        proxy?.dismiss = dismissCard
        proxy?.undo = undoCard
        if (!isExiting) {
            val current = controller.currentStage
            when {
                current == null -> {
                    controller.begin(stage)
                    if (revealsPreviewOnBegin) controller.revealPreview(animated = !reducedMotion)
                }
                current.queue != stage.queue || current.undoDirection != stage.undoDirection -> controller.update(stage)
                else -> controller.refreshCallbacks(stage)
            }
        }
    }
    DisposableEffect(controller) {
        onDispose {
            controller.end()
            controller.dispose()
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                val width = size.width / density.density
                controller.containerWidth = width
            },
    ) {
        Box(
            Modifier
                .padding(insetPadding)
                .padding(
                    start = CatchUpMetrics.sideInset,
                    end = CatchUpMetrics.sideInset,
                    top = CatchUpMetrics.headerHeight,
                    bottom = CatchUpMetrics.footerHeight,
                )
                .fillMaxSize()
                .onSizeChanged { size ->
                    val height = size.height / density.density
                    controller.cardHeight = height
                }
                .then(if (!isExiting) Modifier.catchUpPan(controller, panRegions) else Modifier),
        ) {
            // The card behind: the next session, dimmed, inert and hidden from TalkBack.
            Box(
                Modifier
                    .matchParentSize()
                    .clearAndMergeSemantics()
                    .catchUpCardSurface(
                        pose = { controller.previewPose() },
                        alpha = { controller.previewAlpha },
                        cornerRadius = cornerRadius,
                        skipWhenHidden = { controller.isPreviewHidden },
                    )
                    .background(canvas),
            ) {
                val previewID = controller.shownPreviewID
                if (previewID != null) {
                    // Laid out as the live card is, so it doesn't shift when it becomes the live one.
                    Box(Modifier.matchParentSize().consumeWindowInsets(cardInsets)) {
                        CompositionLocalProvider(LocalContainerInsets provides cardInsets) { preview(previewID, previewRevision) }
                    }
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                        .drawBehind {
                            drawRect(Color.Black.copy(alpha = controller.previewDim))
                            drawRoundRect(
                                color = baseBorder.copy(alpha = baseBorder.alpha * controller.previewBorderFactor),
                                cornerRadius = CornerRadius(cornerRadius.toPx()),
                                style = Stroke(borderWidth.toPx()),
                            )
                        },
                )
            }

            if (leavingCard != null) {
                leavingCard()
            }

            // The live card: the only interactive one.
            Box(
                Modifier
                    .matchParentSize()
                    .catchUpCardSurface(
                        pose = { controller.livePose() },
                        alpha = { controller.liveAlpha },
                        cornerRadius = cornerRadius,
                        skipWhenHidden = { isExiting },
                    ),
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(canvas)
                        .consumeWindowInsets(cardInsets),
                ) {
                    CompositionLocalProvider(LocalContainerInsets provides cardInsets) { card(stage.cardID) }
                }
                CatchUpSwipeOverlay(controller.readBadge, opaque = colors.highContrast)
                CatchUpSwipeOverlay(controller.keepBadge, opaque = colors.highContrast)
                Box(
                    Modifier
                        .matchParentSize()
                        .drawBehind {
                            drawRoundRect(
                                color = controller.dragBorderColor ?: baseBorder,
                                cornerRadius = CornerRadius(cornerRadius.toPx()),
                                style = Stroke(borderWidth.toPx()),
                            )
                        },
                )
                if (!controller.isContentEnabled) {
                    // Touches stop here; the stack's pan still sees them.
                    Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
                }
            }
        }
    }
}

/**
 * The stack's pan: waits 8 dp before choosing an axis, yields at once to a vertical move (the
 * transcript scrolls), and once horizontal wins it consumes the touch so a pending button in the
 * card never fires. While the card is inert (a flight or settle running) it reads touches before
 * the card's content does.
 */
private fun Modifier.catchUpPan(controller: CatchUpStackController, regions: DrawerPanRegions?): Modifier = composed {
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    onGloballyPositioned { coordinates[0] = it }.pointerInput(controller, regions, rtl) { catchUpPanGestures(controller, regions, coordinates, rtl) }
}

private suspend fun PointerInputScope.catchUpPanGestures(
    controller: CatchUpStackController,
    regions: DrawerPanRegions?,
    coordinates: Array<LayoutCoordinates?>,
    rtl: Boolean,
) {
    val slop = 8.dp.toPx()
    val scale = density
    fun dp(offset: Offset) = Offset(offset.x / scale, offset.y / scale)
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!controller.canBeginDrag) return@awaitEachGesture
        val pointerID = down.id
        // A horizontal scroller under the finger keeps the drags it can still scroll; the stack
        // decides first (Initial pass) so the scroller never takes one that is the card's.
        val pan = coordinates[0]
        val scrollers = if (regions != null && pan != null && pan.isAttached) regions.scrollersAt(pan, down.position) else emptyList()
        val origin = DirectionalPan.CardSwipeOrigin(
            onCard = true,
            scrollerOwnsRightwardDrag = scrollers.any { if (rtl) it.canScrollForward() else it.canScrollBackward() },
            scrollerOwnsLeftwardDrag = scrollers.any { if (rtl) it.canScrollBackward() else it.canScrollForward() },
        )
        var last: PointerInputChange = down
        var travelled: Offset
        while (true) {
            val pass = if (controller.isContentEnabled && scrollers.isEmpty()) PointerEventPass.Main else PointerEventPass.Initial
            val event = awaitPointerEvent(pass)
            val change = event.changes.firstOrNull { it.id == pointerID } ?: return@awaitEachGesture
            if (!change.pressed) return@awaitEachGesture
            if (pass == PointerEventPass.Main && change.isConsumed) return@awaitEachGesture
            travelled = change.position - down.position
            last = change
            when (DirectionalPan.cardSwipeDecision(origin, travelled.x, travelled.y, slop)) {
                DirectionalPan.Decision.Wait -> Unit
                DirectionalPan.Decision.Begin -> break
                else -> return@awaitEachGesture
            }
        }
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        tracker.addPosition(last.uptimeMillis, last.position)
        last.consume()
        if (!controller.panBegan(dp(travelled))) return@awaitEachGesture
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerID }
                if (change == null) {
                    controller.panCancelled()
                    return@awaitEachGesture
                }
                tracker.addPosition(change.uptimeMillis, change.position)
                val pan = dp(change.position - down.position)
                if (!change.pressed) {
                    val velocity = tracker.calculateVelocity()
                    controller.panEnded(pan, Offset(velocity.x / scale, velocity.y / scale))
                    return@awaitEachGesture
                }
                change.consume()
                controller.panChanged(pan)
            }
        } catch (cancellation: CancellationException) {
            controller.panCancelled()
            throw cancellation
        }
    }
}

/**
 * Draws a Catch Up card surface through a [GraphicsLayer] in the draw phase:
 * `Modifier.graphicsLayer` dispatches layout-change and `onGloballyPositioned` callbacks on every
 * positional frame, which re-lays out hosted `SessionView`s and floods TalkBack with change events.
 */
private fun Modifier.catchUpCardSurface(
    pose: () -> CatchUpPose,
    alpha: () -> Float,
    cornerRadius: Dp,
    skipWhenHidden: () -> Boolean = { false },
): Modifier = this then CatchUpCardSurfaceElement(pose, alpha, cornerRadius, skipWhenHidden)

private data class CatchUpCardSurfaceElement(
    val pose: () -> CatchUpPose,
    val alpha: () -> Float,
    val cornerRadius: Dp,
    val skipWhenHidden: () -> Boolean,
) : ModifierNodeElement<CatchUpCardSurfaceNode>() {
    override fun create() = CatchUpCardSurfaceNode(pose, alpha, cornerRadius, skipWhenHidden)
    override fun update(node: CatchUpCardSurfaceNode) {
        node.pose = pose
        node.alpha = alpha
        node.cornerRadius = cornerRadius
        node.skipWhenHidden = skipWhenHidden
    }
}

private class CatchUpCardSurfaceNode(
    var pose: () -> CatchUpPose,
    var alpha: () -> Float,
    var cornerRadius: Dp,
    var skipWhenHidden: () -> Boolean,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private var layer: GraphicsLayer? = null
    private var layerOwner: GraphicsContext? = null

    override fun ContentDrawScope.draw() {
        val alpha = alpha()
        if (skipWhenHidden() || alpha <= 0.001f || size.width <= 0f || size.height <= 0f) {
            if (skipWhenHidden() || size.width <= 0f || size.height <= 0f) dropLayer()
            return
        }
        val p = pose()
        val graphics = currentValueOf(LocalGraphicsContext)
        if (layerOwner !== graphics) dropLayer()
        val activeLayer = layer ?: graphics.createGraphicsLayer().also {
            layer = it
            layerOwner = graphics
        }
        activeLayer.translationX = p.tx * density
        activeLayer.translationY = p.ty * density
        activeLayer.rotationZ = Math.toDegrees(p.rotation.toDouble()).toFloat()
        activeLayer.scaleX = p.scale
        activeLayer.scaleY = p.scale
        activeLayer.alpha = alpha
        activeLayer.setRoundRectOutline(Offset.Zero, size, cornerRadius.toPx())
        activeLayer.clip = true
        activeLayer.record { this@draw.drawContent() }
        drawLayer(activeLayer)
    }

    override fun onReset() = dropLayer()

    override fun onDetach() = dropLayer()

    private fun dropLayer() {
        val existing = layer ?: return
        layer = null
        layerOwner?.releaseGraphicsLayer(existing)
        layerOwner = null
    }
}
