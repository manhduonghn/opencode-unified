package ai.opencode.feature.drawer.motion

import ai.opencode.core.motion.transitionBlur

import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.MotionTunables
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeMinTapTarget
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.rememberHighContrast
import ai.opencode.feature.drawer.R
import ai.opencode.feature.session.DrawerBadge
import ai.opencode.feature.session.InAppNotice
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * The session header's drawer button, its badge dot, and the In-App Notice capsule it grows into.
 *
 * Used by the session header: `:feature:session` sits below `:feature:drawer`, so Home passes
 * [DrawerButton] into the session as a slot rather than the session importing it.
 */

/** Cross-fades the label when another event updates it in place. */
private val InAppNotice.identity: String get() = events.joinToString(",") { "${it.sessionID}|${it.kind}" }

private object DrawerBadgeColors {
    fun blue(isDark: Boolean) = if (isDark) Color(0xFF0A84FF) else Color(0xFF007AFF)
}

@Composable
private fun isDarkTheme(): Boolean = OpenCodeTheme.colors.isDark

/**
 * The capsule grows from the button on `MotionRole.PanelOpen` and folds back on its exit
 * (`panelClose`); under Reduce Motion it cross-fades (`MotionRole.Reduced`).
 */
private fun <T> grow(reduceMotion: Boolean = false) = MotionRole.PanelOpen.animation<T>(MotionPhase.Enter, reduceMotion)
private fun <T> fold(reduceMotion: Boolean = false) = MotionRole.PanelOpen.animation<T>(MotionPhase.Exit, reduceMotion)

/**
 * The session header's drawer button. With In-App Notices on, it grows into a capsule that names
 * another session's update (“Upload tests · Needs your input”), then folds back into its dot. The
 * glyph still opens the drawer; the label opens what it names.
 *
 * @param maxWidth how wide the capsule may grow: up to the header's trailing controls.
 * @param size The button's face.
 * @param onNoticeTapped the label was tapped: open what it names.
 */
@Composable
fun DrawerButton(
    badge: DrawerBadge?,
    notice: InAppNotice?,
    maxWidth: Dp,
    onOpenSessions: () -> Unit,
    onNoticeTapped: (InAppNotice.Target) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = OpenCodeMinTapTarget,
) {
    val reducedMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    /** What the label shows, kept while it folds away. */
    var shown by remember { mutableStateOf<InAppNotice?>(null) }
    var expanded by remember { mutableStateOf(false) }
    /** The label's natural width in px, measured off screen. */
    var labelWidthPx by remember { mutableIntStateOf(0) }
    /** How much of the label the capsule uncovers (dp); springs with the capsule. */
    val reveal = remember { Animatable(0f) }
    var foldGeneration by remember { mutableIntStateOf(0) }

    val labelFrame = max(0f, min(with(density) { labelWidthPx.toDp().value }, maxWidth.value - size.value))

    LaunchedEffect(notice) {
        foldGeneration += 1
        val generation = foldGeneration
        if (notice != null) {
            shown = notice
            expanded = true
            if (!reducedMotion) reveal.animateTo(labelFrame, grow())
        } else if (expanded) {
            expanded = false
            if (!reducedMotion) {
                reveal.animateTo(0f, fold())
            } else {
                kotlinx.coroutines.delay(motionMillis(MotionRole.PanelOpen.curve(MotionPhase.Exit, reduceMotion = true).duration).toLong())
            }
            if (!expanded && generation == foldGeneration) shown = null
        } else {
            shown = null
        }
    }
    // The label measured a new width: the capsule grows to it.
    LaunchedEffect(labelWidthPx) {
        if (expanded && !reducedMotion) scope.launch { reveal.animateTo(labelFrame, grow()) }
    }
    // The header gave it more or less room: the capsule takes it at once.
    LaunchedEffect(maxWidth) {
        if (expanded && !reducedMotion) scope.launch { reveal.snapTo(labelFrame) }
    }

    val dotBadge = if (notice == null && shown == null) badge else null
    Box(modifier.size(size), contentAlignment = Alignment.CenterStart) {
        // Off-screen measure of the label's natural width.
        val measured = notice ?: shown
        if (measured != null) {
            Box(
                Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .graphicsLayer { alpha = 0f }
                    .clearAndSetSemantics {},
            ) {
                DrawerNoticeLabel(measured, Modifier.onSizeChanged { labelWidthPx = it.width })
            }
        }
        if (reducedMotion) {
            // Cross-fades in place: the capsule over the button, at full width.
            DrawerButtonCapsule(
                width = size,
                size = size,
                badge = badge,
                shown = null,
                expanded = false,
                labelFrame = labelFrame.dp,
                reducedMotion = true,
                hiddenFromAccessibility = expanded,
                onOpenSessions = onOpenSessions,
                onNoticeTapped = onNoticeTapped,
            )
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(grow(reduceMotion = true)),
                exit = fadeOut(fold(reduceMotion = true)),
                modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
            ) {
                DrawerButtonCapsule(
                    width = size + labelFrame.dp,
                    size = size,
                    badge = badge,
                    shown = shown,
                    expanded = true,
                    labelFrame = labelFrame.dp,
                    reducedMotion = true,
                    hiddenFromAccessibility = false,
                    onOpenSessions = onOpenSessions,
                    onNoticeTapped = onNoticeTapped,
                )
            }
        } else {
            DrawerButtonCapsule(
                width = null,
                widthDp = { size.value + reveal.value },
                size = size,
                badge = badge,
                shown = shown,
                expanded = expanded,
                labelFrame = labelFrame.dp,
                reducedMotion = false,
                hiddenFromAccessibility = false,
                onOpenSessions = onOpenSessions,
                onNoticeTapped = onNoticeTapped,
            )
        }
        // Outside the clipped capsule.
        Box(Modifier.size(size), contentAlignment = Alignment.TopEnd) {
            DrawerBadgeDot(dotBadge)
        }
    }
}

@Composable
private fun DrawerButtonCapsule(
    width: Dp?,
    size: Dp,
    badge: DrawerBadge?,
    shown: InAppNotice?,
    expanded: Boolean,
    labelFrame: Dp,
    reducedMotion: Boolean,
    hiddenFromAccessibility: Boolean,
    onOpenSessions: () -> Unit,
    onNoticeTapped: (InAppNotice.Target) -> Unit,
    widthDp: (() -> Float)? = null,
) {
    // Takes the button's place in the header; the label grows over the title, at its own width.
    val widthModifier = if (width != null) {
        Modifier.requiredWidth(width)
    } else {
        Modifier.wrapContentWidth(Alignment.Start, unbounded = true).layoutWidth { widthDp?.invoke() ?: size.value }
    }
    // At rest the button is a plain top-app-bar icon (no glass circle); the capsule's fill shows
    // only while it grows into a notice.
    val openSessionsDescription = stringResource(R.string.drawer_button_open_sessions_description)
    val attentionState = stringResource(R.string.drawer_button_attention_state)
    val unreadState = stringResource(R.string.drawer_button_unread_state)
    val fill by animateColorAsState(
        targetValue = if (shown != null && expanded) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
        animationSpec = if (expanded) grow() else fold(),
        label = "drawer-button-fill",
    )
    Surface(
        shape = OpenCodeShape.control,
        color = fill,
        contentColor = OpenCodeTheme.colors.text,
        modifier = widthModifier
            .height(size)
            .then(if (hiddenFromAccessibility) Modifier.clearAndSetSemantics {} else Modifier),
    ) {
        Row(Modifier.fillMaxHeight().clipToBounds(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(size)
                    .clickable(role = Role.Button, onClickLabel = null, onClick = onOpenSessions)
                    .semantics {
                        contentDescription = openSessionsDescription
                        stateDescription = when (badge) {
                            DrawerBadge.Attention -> attentionState
                            DrawerBadge.Unread -> unreadState
                            null -> ""
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                OpenCodeIcon(SFSymbol.Line3Horizontal, contentDescription = null)
            }
            if (shown != null) {
                val labelAlpha by animateFloatAsState(
                    targetValue = if (expanded) 1f else 0f,
                    animationSpec = if (expanded) grow() else fold(),
                    label = "drawer-notice-alpha",
                )
                Box(
                    Modifier
                        .width(labelFrame)
                        .fillMaxHeight()
                        .graphicsLayer { alpha = labelAlpha }
                        .then(
                            if (expanded) {
                                Modifier
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = if (shown.target == InAppNotice.Target.Drawer) stringResource(R.string.drawer_notice_open_sessions_action) else stringResource(R.string.drawer_notice_open_session_action),
                                        onClick = { onNoticeTapped(shown.target) },
                                    )
                                    .semantics { contentDescription = shown.announcement }
                            } else {
                                Modifier.semantics { hideFromAccessibility() }
                            },
                        )
                        .testTag("drawer-notice"),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    AnimatedContent(
                        targetState = shown,
                        contentKey = { it.identity },
                        // The new label swaps in on the frame the old one starts to leave.
                        transitionSpec = {
                            fadeIn(MotionRole.Swap.animation(MotionPhase.Enter, reducedMotion)) togetherWith
                                fadeOut(MotionRole.Swap.animation(MotionPhase.Exit, reducedMotion))
                        },
                        label = "drawer-notice",
                    ) { model ->
                        DrawerNoticeLabel(model, Modifier.wrapContentWidth(Alignment.Start, unbounded = true))
                    }
                }
            }
        }
    }
}

/** Lays the element out at [widthDp] (read at layout, so springing it never recomposes). */
private fun Modifier.layoutWidth(widthDp: () -> Float): Modifier = layout { measurable, constraints ->
    val width = widthDp().dp.roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(width, placeable.height) { placeable.place(0, 0) }
}

/** “Upload tests · Needs your input”: orange while it needs you, quiet when done. */
@Composable
fun DrawerNoticeLabel(notice: InAppNotice, modifier: Modifier = Modifier) {
    val isDark = isDarkTheme()
    val orange = OpenCodeTheme.colors.warning
    val colors = OpenCodeTheme.colors
    Row(
        modifier.padding(start = 2.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(8.dp)) {
            drawCircle(if (notice.needsInput) orange else DrawerBadgeColors.blue(isDark))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            notice.title,
            style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        Text("·", style = OpenCodeTextStyles.subheadline, color = colors.tertiaryText)
        Spacer(Modifier.width(6.dp))
        Text(
            notice.status,
            style = OpenCodeTextStyles.subheadline.copy(fontWeight = if (notice.needsInput) FontWeight.SemiBold else FontWeight.Normal),
            color = if (notice.needsInput) orange else colors.secondaryText,
            maxLines = 1,
        )
    }
}

/**
 * The drawer button's dot: another session needs input (orange) or is unread (blue). It arrives on
 * `MotionRole.MicroIn` and leaves on `microOut`; one already showing when the button appears
 * (opening or restoring a session) is simply there, and a change of colour while it shows lands in
 * place.
 */
@Composable
fun DrawerBadgeDot(badge: DrawerBadge?, modifier: Modifier = Modifier) {
    val reducedMotion = rememberReduceMotion()
    val highContrast = rememberHighContrast()
    val isDark = isDarkTheme()
    val orange = OpenCodeTheme.colors.warning
    val background = MaterialTheme.colorScheme.background
    // The last colour shown, so the dot keeps it while it leaves.
    var lastBadge by remember { mutableStateOf(badge) }
    if (badge != null) lastBadge = badge
    val scale = if (reducedMotion) 1f else MotionTunables.microInScale.value
    // A 10 dp slot, offset (-3, 3) into the button's corner.
    Box(modifier.offset(x = (-3).dp, y = 3.dp).size(10.dp)) {
        AnimatedVisibility(
            visible = badge != null,
            enter = fadeIn(MotionRole.MicroIn.animation(MotionPhase.Enter, reducedMotion)) +
                scaleIn(MotionRole.MicroIn.animation(MotionPhase.Enter, reducedMotion), initialScale = scale),
            exit = fadeOut(MotionRole.MicroIn.animation(MotionPhase.Exit, reducedMotion)) +
                scaleOut(MotionRole.MicroIn.animation(MotionPhase.Exit, reducedMotion), targetScale = scale),
        ) {
            Canvas(Modifier.size(10.dp)) {
                val color = if (lastBadge == DrawerBadge.Attention) orange else DrawerBadgeColors.blue(isDark)
                val radius = size.minDimension / 2
                drawCircle(color, radius = radius)
                if (highContrast) {
                    drawCircle(background, radius = radius - 0.625.dp.toPx(), style = Stroke(1.25.dp.toPx()))
                }
            }
        }
    }
}

/**
 * The header's title while the drawer button's label covers it: fades and blurs out as the label
 * grows, comes back as it folds, and stops taking touches and TalkBack focus while aside.
 */
fun Modifier.steppingAside(aside: Boolean, reducedMotion: Boolean): Modifier = composed {
    // Swaps out on `swap`'s exit (quicker than the capsule grows) on the frame the capsule starts
    // to grow, and back in on the frame it starts to fold.
    val spec = MotionRole.Swap.animation<Float>(if (aside) MotionPhase.Exit else MotionPhase.Enter, reducedMotion)
    val progress by animateFloatAsState(if (aside) 1f else 0f, spec, label = "stepping-aside")
    val blurred = if (!reducedMotion && progress > 0.001f) Modifier.transitionBlur((6 * progress).dp) else Modifier
    this
        .then(blurred)
        .graphicsLayer { alpha = 1 - progress }
        .then(
            if (aside) {
                Modifier
                    .clearAndSetSemantics {}
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
            } else {
                Modifier
            },
        )
}

/** Seconds → whole milliseconds for `tween` (core's `Motion.millis` is internal to `:core`). */
internal fun motionMillis(seconds: Double): Int = kotlin.math.round(seconds * 1000).toInt()
