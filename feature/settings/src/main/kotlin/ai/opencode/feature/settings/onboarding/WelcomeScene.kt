package ai.opencode.feature.settings.onboarding

import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.tuning.AnimationTimeScale
import ai.opencode.feature.settings.R
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.max
import kotlin.math.min

/**
 * Where the laptop and phone sit: the phone stands in front of the lid's right edge, a little
 * taller than the laptop and centred on it, so neither leaves a dead band beside the other.
 * Everything scales with the width it's given, capped by `maxHeight`. Values are in dp.
 */
data class WelcomeSceneLayout(
    val size: Size,
    val lid: Rect,
    val laptopScreen: Rect,
    val base: Rect,
    val phone: Rect,
    val phoneScreen: Rect,
) {
    val laptopScale: Float get() = laptopScreen.width / WelcomeDesktop.size.width
    val phoneScale: Float get() = phoneScreen.width / WelcomePhone.size.width

    /** A point in the desktop's logical space, in the scene. */
    fun desktop(point: Offset): Offset =
        Offset(laptopScreen.left + point.x * laptopScale, laptopScreen.top + point.y * laptopScale)

    companion object {
        /** The phone's height over the laptop's. */
        const val PHONE_RATIO = 1.45f

        /** How much of the phone's width stands over the laptop. */
        const val OVERLAP = 0.8f

        operator fun invoke(width: Float, maxHeight: Float): WelcomeSceneLayout {
            // Laid out for a lid 1 dp wide, then scaled.
            val lidBezel = 0.016f
            val screen = Size(1 - 2 * lidBezel, (1 - 2 * lidBezel) * WelcomeDesktop.size.height / WelcomeDesktop.size.width)
            val lidHeight = screen.height + 2 * lidBezel
            val baseOverhang = 0.043f
            val baseHeight = 0.024f
            val laptopWidth = 1 + 2 * baseOverhang
            val laptopHeight = lidHeight + baseHeight
            val phoneHeight = laptopHeight * PHONE_RATIO
            val phoneBezelRatio = 0.02f
            val phoneAspect = WelcomePhone.size.height / WelcomePhone.size.width
            val phoneWidth = phoneHeight / ((1 - 2 * phoneBezelRatio) * phoneAspect + 2 * phoneBezelRatio)
            val unit = Size(laptopWidth + phoneWidth * (1 - OVERLAP), phoneHeight)
            val scale = max(0f, min(width / unit.width, maxHeight / unit.height))

            val size = Size(unit.width * scale, unit.height * scale)
            val laptopTop = (phoneHeight - laptopHeight) / 2 * scale
            val lid = Rect(Offset(baseOverhang * scale, laptopTop), Size(scale, lidHeight * scale))
            val laptopScreen = Rect(
                Offset(lid.left + lidBezel * scale, lid.top + lidBezel * scale),
                Size(screen.width * scale, screen.height * scale),
            )
            val base = Rect(Offset(0f, lid.bottom - 0.5f), Size(laptopWidth * scale, baseHeight * scale))
            val phone = Rect(Offset(size.width - phoneWidth * scale, 0f), Size(phoneWidth * scale, phoneHeight * scale))
            val phoneBezel = phoneWidth * phoneBezelRatio * scale
            val phoneScreen = phone.deflate(phoneBezel)
            return WelcomeSceneLayout(size, lid, laptopScreen, base, phone, phoneScreen)
        }
    }
}

private fun hex(value: Long, alpha: Float = 1f): Color = Color(0xFF000000 or value).copy(alpha = alpha)

/**
 * The welcome's illustration: the laptop running OpenCode and the phone steering it, telling
 * [WelcomeStory] on a loop: someone dictates a task on the phone, the laptop explores, opens its
 * browser, edits and takes a screenshot that flies back to the phone, then ships it. The pairing
 * step cross-fades both screens to Settings › Pairing and the camera finding its code. The clock
 * pauses under Reduce Motion (the story's still frame), while pairing, and while the app isn't in
 * front.
 */
@Composable
fun WelcomeScene(layout: WelcomeSceneLayout, pairing: Boolean, modifier: Modifier = Modifier) {
    val reduced = rememberReduceMotion()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val paused = reduced || pairing || !lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val appeared = remember { AnimationTimeScale.shared.now() }
    var now by remember { mutableDoubleStateOf(appeared) }
    // When the story's time 0 is, pushed back to replay from the start.
    var storyStart by remember { mutableDoubleStateOf(appeared + WelcomeTunables.lead.value) }
    val voice = remember { WelcomeVoice() }
    val targets = remember { WelcomeTargets() }
    LaunchedEffect(paused) {
        if (paused) return@LaunchedEffect
        while (true) withFrameNanos { now = AnimationTimeScale.shared.now() }
    }
    var wasPairing by remember { mutableStateOf(pairing) }
    LaunchedEffect(pairing) {
        // Back from pairing replays the story from its empty start.
        if (wasPairing && !pairing) storyStart = AnimationTimeScale.shared.now() + 0.35
        wasPairing = pairing
    }
    val pairingProgress by animateFloatAsState(
        if (pairing) 1f else 0f,
        animationSpec = WelcomeTunables.step.animation(reduced),
        label = "welcome-pairing",
    )

    val story = WelcomeStory.tuned
    val wall = now - appeared
    val devices = WelcomeTunables.devices.spring
    val laptopIn = if (reduced) 1.0 else WelcomeStory.progress(wall - 0.36, devices)
    val phoneIn = if (reduced) 1.0 else WelcomeStory.progress(wall - 0.56, devices)
    val focusOn = if (reduced) 1.0 else WelcomeStory.progress(wall - 1.1, WelcomeTunables.focus.spring)
    val t = if (reduced) story.still else storyTime(now - storyStart, story.loop)
    val frame = story.frame(t, focusOn = focusOn, reduced = reduced)
    // The dictation bar's scope listens to the story's voice, as it would the mic.
    SideEffect { voice.play(frame.voice) }

    val label = if (pairing) {
        stringResource(R.string.settings_onboarding_scene_pairing_description)
    } else {
        stringResource(R.string.settings_onboarding_scene_story_description)
    }
    Box(
        modifier
            .size(layout.size.width.dp, layout.size.height.dp)
            .onGloballyPositioned { targets.scene = it }
            .semantics {
                contentDescription = label
                role = Role.Image
            },
    ) {
        val lights = if (!reduced && !pairing) targets else null
        Laptop(layout, frame, wall, pairingProgress, reduced, lights, Modifier.welcomeSettle(laptopIn.toFloat(), 26.dp, reduced))
        Phone(layout, frame, wall, pairingProgress, reduced, voice, lights, Modifier.welcomeSettle(phoneIn.toFloat(), 54.dp, reduced))
        if (lights != null) WelcomeCarriers(frame, HashMap(lights.rects), layout)
    }
}

/** Story time for [elapsed] seconds since time 0: negative before the first loop, then wrapping. */
internal fun storyTime(elapsed: Double, loop: Double): Double {
    if (elapsed <= 0 || loop <= 0) return elapsed
    return elapsed % loop
}

/**
 * The story's voice, with its own level meter: the phone's dictation scope listens to it, never to
 * the live dictation meter, which lives in the composer.
 */
@Stable
internal class WelcomeVoice {
    val levels = WelcomeLevels()

    fun play(level: Double) {
        levels.set(level.toFloat())
    }
}

/** A dictation level meter: the latest level and a short history the scope draws. */
@Stable
internal class WelcomeLevels {
    var level by mutableFloatStateOf(0f)
        private set
    val history = mutableStateListOf<Float>().apply { repeat(HISTORY) { add(0f) } }

    fun set(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        this.level = clamped
        history.removeAt(0)
        history.add(clamped)
    }

    private companion object {
        const val HISTORY = 48
    }
}

/** A device settling in: it rises on a spring and fades in early. */
private fun Modifier.welcomeSettle(p: Float, dy: Dp, reduced: Boolean): Modifier = graphicsLayer {
    alpha = (p * 1.8f).coerceIn(0f, 1f)
    val scale = if (reduced) 1f else 0.965f + 0.035f * p
    scaleX = scale
    scaleY = scale
    transformOrigin = TransformOrigin(0.5f, 1f)
    translationY = if (reduced) 0f else dy.toPx() * (1 - p)
}

/** Places [content] at [rect] (dp) inside the scene, at exactly that size. */
@Composable
private fun Placed(rect: Rect, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .offset(rect.left.dp, rect.top.dp)
            .requiredSize(rect.width.dp, rect.height.dp),
    ) { content() }
}

/** [content] laid out at `logical` size (dp) and scaled by [scale] from the top-left. */
@Composable
internal fun WelcomeScaled(logical: Size, scale: Float, content: @Composable () -> Unit) {
    Layout(
        content = {
            Box(
                Modifier
                    .requiredSize(logical.width.dp, logical.height.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
            ) { content() }
        },
    ) { measurables, constraints ->
        val placeable = measurables.single().measure(Constraints())
        layout(constraints.maxWidth, constraints.maxHeight) {
            // requiredSize centres an oversized child; undo that so it scales from the corner.
            placeable.place(0, 0)
        }
    }
}

@Composable
private fun Laptop(layout: WelcomeSceneLayout, frame: WelcomeFrame, wall: Double, pairing: Float, reduced: Boolean, targets: WelcomeTargets?, modifier: Modifier) {
    val lid = layout.lid
    val screen = layout.laptopScreen
    val base = layout.base
    val corner = lid.width * 0.035f
    val lidShape = RoundedCornerShape(corner.dp, corner.dp, (corner * 0.22f).dp, (corner * 0.22f).dp)
    val screenShape = RoundedCornerShape((corner * 0.56f).dp, (corner * 0.56f).dp, 0.dp, 0.dp)
    val baseShape = RoundedCornerShape(1.2.dp, 1.2.dp, (base.height * 0.92f).dp, (base.height * 0.92f).dp)
    Box(modifier.size(layout.size.width.dp, layout.size.height.dp)) {
        Placed(lid) {
            Box(
                Modifier
                    .fillMaxSize()
                    .shadow(22.dp, lidShape, ambientColor = Color.Black.copy(alpha = 0.42f), spotColor = Color.Black.copy(alpha = 0.42f))
                    .background(Brush.verticalGradient(listOf(hex(0x141416), hex(0x060607))), lidShape)
                    .border(
                        0.9.dp,
                        Brush.verticalGradient(0f to Color.White.copy(alpha = 0.28f), 0.35f to Color.White.copy(alpha = 0.11f), 1f to Color.White.copy(alpha = 0.04f)),
                        lidShape,
                    ),
            )
        }
        Placed(screen) {
            Box(Modifier.fillMaxSize().clip(screenShape).border(0.5.dp, Color.White.copy(alpha = 0.06f), screenShape)) {
                WelcomeScaled(WelcomeDesktop.size, layout.laptopScale) { WelcomeDesktop(frame, wall, pairing, reduced, targets) }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = frame.laptopDim.toFloat())))
            }
            // The camera notch.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .size((lid.width * 0.1f).dp, max(2.2f, lid.width * 0.016f).dp)
                    .background(hex(0x050506), RoundedCornerShape(0.dp, 0.dp, 2.5.dp, 2.5.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val dot = max(1.6f, lid.width * 0.0065f).dp
                Box(Modifier.size(dot).background(hex(0x182238), RoundedCornerShape(50)))
            }
        }
        Placed(base) {
            Box(
                Modifier
                    .fillMaxSize()
                    .shadow(14.dp, baseShape, ambientColor = Color.Black.copy(alpha = 0.4f), spotColor = Color.Black.copy(alpha = 0.55f))
                    .background(Brush.verticalGradient(0f to hex(0x4F4F55), 0.5f to hex(0x2E2E33), 1f to hex(0x151518)), baseShape)
                    .clip(baseShape)
                    .background(
                        // Cylindrical falloff at the curved aluminum ends.
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.36f),
                            0.07f to Color.Transparent,
                            0.93f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.36f),
                        ),
                    ),
            ) {
                // Hinge seam and the machined lip highlight.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(0.65.dp)
                        .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.34f), Color.Transparent))),
                )
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .size((base.width * 0.145f).dp, (base.height * 0.42f).dp)
                        .background(hex(0x1F1F23), RoundedCornerShape(0.dp, 0.dp, 3.dp, 3.dp)),
                )
            }
        }
    }
}

@Composable
private fun Phone(layout: WelcomeSceneLayout, frame: WelcomeFrame, wall: Double, pairing: Float, reduced: Boolean, voice: WelcomeVoice, targets: WelcomeTargets?, modifier: Modifier) {
    val phone = layout.phone
    val screen = layout.phoneScreen
    val corner = phone.width * 0.11f
    val outerShape = RoundedCornerShape(corner.dp)
    val innerShape = RoundedCornerShape(max(0f, corner - (screen.left - phone.left)).dp)
    Box(modifier.size(layout.size.width.dp, layout.size.height.dp)) {
        Placed(phone) {
            Box(
                Modifier
                    .fillMaxSize()
                    .shadow(22.dp, outerShape, ambientColor = Color.Black.copy(alpha = 0.52f), spotColor = Color.Black.copy(alpha = 0.55f))
                    .background(
                        Brush.linearGradient(0f to hex(0x525257), 0.38f to hex(0x252529), 0.78f to hex(0x3B3B40), 1f to hex(0x1E1E22)),
                        outerShape,
                    )
                    .border(
                        0.85.dp,
                        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.2f))),
                        outerShape,
                    ),
            )
        }
        Placed(screen) {
            Box(Modifier.fillMaxSize().clip(innerShape).border(0.5.dp, Color.White.copy(alpha = 0.06f), innerShape)) {
                WelcomeScaled(WelcomePhone.size, layout.phoneScale) { WelcomePhone(frame, pairing, reduced, voice, wall, targets) }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = frame.phoneDim.toFloat())))
            }
        }
    }
}
