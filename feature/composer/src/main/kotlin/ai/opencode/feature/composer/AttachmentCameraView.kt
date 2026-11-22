package ai.opencode.feature.composer

import ai.opencode.core.R as CoreR
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticEffect
import ai.opencode.core.log.AppLog
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionReduce
import ai.opencode.core.motion.MotionTransition
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.strings.localized
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.composer.plusmenu.findActivity
import ai.opencode.feature.composer.plusmenu.requestPermissionsAndAwait
import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.Settings
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A still camera presented full screen; the preview fills the display while the controls stay
 * inside the safe area.
 *
 * @param onCapture the JPEG (with its EXIF orientation; front photos unmirrored).
 * @param onBack Close: the full-screen camera closes itself first.
 * @param isEmbeddedInSheet the + sheet keeps this same preview mounted from tile to full screen.
 * @param isActive the camera runs (and asks for access) only while active.
 */
@Composable
fun AttachmentCameraView(
    onCapture: (ByteArray) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    isEmbeddedInSheet: Boolean = false,
    isActive: Boolean = true,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    val view = LocalView.current
    val camera = remember { AttachmentCameraState(context.applicationContext, scope) }
    val showsControls = isActive
    val closesOnBack = !isEmbeddedInSheet
    val latestOnCapture by rememberUpdatedState(onCapture)
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val sceneActive = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    camera.reduceMotion = reduceMotion
    camera.displayRotation = { view.display?.rotation ?: Surface.ROTATION_0 }
    camera.activity = context.findActivity()
    camera.lifecycle = lifecycleOwner

    LaunchedEffect(isActive, sceneActive) {
        val activity = AttachmentCameraActivity(
            visible = isActive,
            sceneActive = sceneActive,
            authorized = camera.isAuthorized(),
            requestsAccess = isActive,
        )
        camera.isVisible = activity.shouldActivate
        if (activity.shouldActivate) {
            camera.onCapture = { latestOnCapture(it) }
            if (!camera.canCapture && !camera.isActivating) camera.activate()
        } else {
            camera.pause()
        }
    }
    DisposableEffect(camera) { onDispose { camera.close() } }

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(
            modifier
                .fillMaxSize()
                .graphicsLayer { clip = true },
        ) {
            if (!isEmbeddedInSheet || isActive || camera.isVisible || camera.canCapture) {
                AttachmentCameraPreview(
                    camera = camera,
                    isInteractive = showsControls && camera.canCapture && camera.zoom != null,
                )
            }

            val message = camera.message
            if (showsControls && message != null) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(start = 16.dp, end = 16.dp, bottom = 72.dp)
                        .background(Color.Black.copy(alpha = 0.65f), OpenCodeShape.panel)
                        .border(OpenCodeSurfaces.hairline, CameraChromeHairline, OpenCodeShape.panel)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(message, style = OpenCodeTextStyles.subheadline, textAlign = TextAlign.Center, color = Color.White)
                    if (camera.needsSettings) {
                        CameraMessageButton(stringResource(CoreR.string.common_open_settings), onClick = camera::openSettings)
                    } else if (camera.canRetry) {
                        CameraMessageButton(stringResource(CoreR.string.common_try_again), onClick = camera::activate)
                    }
                }
            }

            if (showsControls) {
                AttachmentCameraControls(
                    canCapture = camera.canCapture,
                    canFlip = camera.canFlip,
                    flash = if (camera.hasFlash) camera.flash else null,
                    zoom = camera.zoom,
                    zoomFactor = camera.zoomState.shownFactor,
                    isPinching = camera.zoomState.isPinching,
                    isFocusLocked = camera.isFocusLocked,
                    onClose = {
                        if (closesOnBack) camera.close()
                        onBack()
                    },
                    onShutter = camera::takePhoto,
                    onFlip = camera::flip,
                    onFlash = { camera.flash = camera.flash.next },
                    onLens = camera::select,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                )
            }
            if (!showsControls && !camera.canCapture) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    OpenCodeIcon(SFSymbol.Camera, contentDescription = null, size = 22.dp, tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** The tile never prompts for permission and no hidden or background camera runs. */
data class AttachmentCameraActivity(
    val visible: Boolean,
    val sceneActive: Boolean,
    val authorized: Boolean,
    val requestsAccess: Boolean,
) {
    val shouldActivate: Boolean get() = visible && sceneActive && (authorized || requestsAccess)
}

/** The viewfinder with pinch, tap to focus and long-press AE/AF Lock. */
@Composable
private fun AttachmentCameraPreview(camera: AttachmentCameraState, isInteractive: Boolean) {
    val transformer = remember { MutableCoordinateTransformer() }
    val request = camera.capture.surfaceRequest
    val zoom = camera.zoom
    val canAdjust = camera.canCapture && zoom?.canZoom == true
    val viewfinderDescription = stringResource(R.string.composer_camera_viewfinder_description)
    val base = LocalViewConfiguration.current
    val viewConfiguration = remember(base) {
        object : ViewConfiguration by base {
            override val longPressTimeoutMillis: Long = 500
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                if (canAdjust && zoom != null) {
                    val shown = zoom.displayed(camera.zoomState.shownFactor)
                    Modifier.semantics {
                        contentDescription = viewfinderDescription
                        stateDescription = CameraZoomModel.label(shown, active = true)
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            current = camera.zoomState.shownFactor.toFloat(),
                            range = zoom.range.start.toFloat()..zoom.range.endInclusive.toFloat(),
                        )
                        setProgress { target ->
                            camera.stepZoom(up = target > camera.zoomState.shownFactor)
                            true
                        }
                    }
                } else {
                    Modifier.clearAndSetSemantics {}
                },
            ),
    ) {
        if (request != null) {
            CompositionLocalProvider(LocalViewConfiguration provides viewConfiguration) {
                CameraXViewfinder(
                    surfaceRequest = request,
                    coordinateTransformer = transformer,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(isInteractive) {
                            if (!isInteractive) return@pointerInput
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var started = false
                                var startDistance = 0f
                                do {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.size >= 2) {
                                        val distance = hypot(
                                            pressed[0].position.x - pressed[1].position.x,
                                            pressed[0].position.y - pressed[1].position.y,
                                        )
                                        if (!started) {
                                            started = true
                                            startDistance = max(distance, 1f)
                                            camera.pinch(Pinch.Began)
                                            camera.pinch(Pinch.Changed(1.0))
                                        } else {
                                            camera.pinch(Pinch.Changed((distance / startDistance).toDouble()))
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                                if (started) camera.pinch(Pinch.Ended)
                            }
                        }
                        .pointerInput(isInteractive, request) {
                            if (!isInteractive) return@pointerInput
                            fun focus(point: Offset, lock: Boolean) {
                                // Accounts for scaling, rotation and front mirroring.
                                val surfacePoint = with(transformer) { point.transform() }
                                val resolution = request.resolution
                                val x = surfacePoint.x / resolution.width
                                val y = surfacePoint.y / resolution.height
                                if (x !in 0f..1f || y !in 0f..1f) return
                                camera.focus(point, surfacePoint, IntSize(resolution.width, resolution.height), lock)
                            }
                            detectTapGestures(
                                onTap = { focus(it, lock = false) },
                                onLongPress = { focus(it, lock = true) },
                            )
                        },
                )
            }
        }
        AttachmentFocusIndicator(mark = camera.focusMark)
    }
}

sealed interface Pinch {
    data object Began : Pinch
    data class Changed(val scale: Double) : Pinch
    data object Ended : Pinch
}

/**
 * The camera's controls, independent of the capture session so they can render over a placeholder.
 *
 * @param flash null hides the flash button (front camera, or no flash).
 */
@Composable
fun AttachmentCameraControls(
    canCapture: Boolean,
    canFlip: Boolean,
    flash: AttachmentCameraFlash?,
    zoom: CameraZoomModel?,
    zoomFactor: Double,
    modifier: Modifier = Modifier,
    isPinching: Boolean = false,
    isFocusLocked: Boolean = false,
    onClose: () -> Unit = {},
    onShutter: () -> Unit = {},
    onFlip: () -> Unit = {},
    onFlash: () -> Unit = {},
    onLens: (CameraZoomModel.Preset) -> Unit = {},
) {
    val inert = if (canCapture) 1f else 0.35f
    val reduceMotion = rememberReduceMotion()
    val takePhotoDescription = stringResource(R.string.composer_camera_take_photo_description)
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Column(modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (flash != null) {
                    CameraCircleButton(
                        symbol = flash.systemImage,
                        label = stringResource(R.string.composer_camera_flash_description),
                        value = flash.accessibilityValue,
                        tint = if (flash == AttachmentCameraFlash.Off) Color.White else CameraYellow,
                        enabled = canCapture,
                        modifier = Modifier.alpha(inert),
                        onClick = onFlash,
                    )
                } else {
                    Spacer(Modifier.size(48.dp))
                }
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(
                    visible = isFocusLocked,
                    enter = MotionTransition.micro(reduceMotion).enter,
                    exit = MotionTransition.micro(reduceMotion).exit,
                ) {
                    Text(
                        stringResource(R.string.composer_camera_focus_lock),
                        color = Color.Black,
                        style = OpenCodeTextStyles.caption1.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier
                            .background(CameraYellow, RoundedCornerShape(OpenCodeRadius.xs))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(48.dp))
            }

            Spacer(Modifier.weight(1f))

            if (zoom != null && zoom.canZoom) {
                AttachmentLensPicker(
                    zoom = zoom,
                    factor = zoomFactor,
                    isPinching = isPinching,
                    enabled = canCapture,
                    onSelect = onLens,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .alpha(inert)
                        .padding(bottom = 14.dp),
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CameraCircleButton(symbol = SFSymbol.Xmark, label = stringResource(R.string.composer_camera_close_description), onClick = onClose)
                Spacer(Modifier.weight(1f))
                // Android camera apps' shutter size. The face is sized after the tap target, which
                // measures what follows it with no minimum: an empty Box sized before it drew only
                // its 5 dp padding.
                Box(
                    Modifier
                        .alpha(inert)
                        .clip(CircleShape)
                        .clickable(enabled = canCapture, role = Role.Button, onClick = onShutter)
                        .openCodeTapTarget()
                        .clearAndSetSemantics {
                            contentDescription = takePhotoDescription
                            role = Role.Button
                            if (!canCapture) disabled()
                            onClick { onShutter(); true }
                        }
                        .size(SHUTTER_SIZE)
                        .border(3.dp, Color.White, CircleShape)
                        .padding(6.dp)
                        .background(Color.White, CircleShape),
                )
                Spacer(Modifier.weight(1f))
                if (canFlip) {
                    CameraCircleButton(symbol = SFSymbol.ArrowTriangle2Circlepath, label = stringResource(R.string.composer_camera_switch_description), enabled = canCapture, onClick = onFlip)
                } else {
                    Spacer(Modifier.size(48.dp))
                }
            }
        }
    }
}

private val CameraYellow = Color(0xFFFFD60A)
private val SHUTTER_SIZE = 72.dp

/** The controls' translucent dark fill over the preview: light enough to let the photo through. */
private val CameraChromeFill = Color.Black.copy(alpha = 0.3f)

/** The controls' hairline over the preview, which keeps them visible over bright scenes. */
private val CameraChromeHairline = Color.White.copy(alpha = 0.22f)

/** The camera message's action: a plain white label with the desktop's control corners. */
@Composable
private fun CameraMessageButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shape = OpenCodeShape.control,
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
    ) {
        Text(label, style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium))
    }
}

/**
 * A translucent circle with a hairline. Camera controls stay round, as on every phone camera,
 * rather than the desktop's squares.
 */
@Composable
private fun CameraCircleButton(
    symbol: SFSymbol,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    tint: Color = Color.White,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(CameraChromeFill)
            .border(OpenCodeSurfaces.hairline, CameraChromeHairline, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .openCodeTapTarget()
            .clearAndSetSemantics {
                contentDescription = label
                if (value != null) stateDescription = value
                role = Role.Button
                if (!enabled) disabled()
                onClick { onClick(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        OpenCodeIcon(symbol, contentDescription = null, size = 20.dp, tint = tint)
    }
}

/** Round lens buttons like Camera's: the active one is larger and shows the live zoom, the others their lens's factor. */
@Composable
private fun AttachmentLensPicker(
    zoom: CameraZoomModel,
    factor: Double,
    isPinching: Boolean,
    enabled: Boolean,
    onSelect: (CameraZoomModel.Preset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = zoom.activePreset(factor)
    val live = CameraZoomModel.label(zoom.displayed(factor), active = true)
    val reduceMotion = rememberReduceMotion()
    HapticEffect(Haptic.selection, trigger = active?.id) { _, _ -> isPinching }
    Row(
        modifier
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (preset in zoom.presets) {
            val isActive = preset == active
            val side by animateFloatAsState(
                targetValue = if (isActive) 38f else 30f,
                animationSpec = Motion.microIn.animation(reduceMotion = reduceMotion),
                label = "lens-side",
            )
            Box(
                Modifier
                    .clickable(enabled = enabled, role = Role.Button) { onSelect(preset) }
                    .openCodeTapTarget()
                    .clearAndSetSemantics {
                        contentDescription = preset.accessibilityLabel
                        stateDescription = if (isActive) live else ""
                        selected = isActive
                        role = Role.Button
                        if (!enabled) disabled()
                        onClick { onSelect(preset); true }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(side.dp)
                        .background(CameraChromeFill, CircleShape)
                        .border(OpenCodeSurfaces.hairline, CameraChromeHairline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (isActive) live else CameraZoomModel.label(preset.displayFactor, active = false),
                        color = if (isActive) CameraYellow else Color.White,
                        maxLines = 1,
                        style = OpenCodeTextStyles.caption1.copy(
                            fontSize = if (isActive) 13.sp else 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum",
                        ),
                    )
                }
            }
        }
    }
}

/** [point] in the preview's pixels. */
data class AttachmentFocusMark(val id: UUID = UUID.randomUUID(), val point: Offset, val locked: Boolean)

/** Camera's brief yellow square where a tap focused; it pulses once on a lock. */
@Composable
fun AttachmentFocusIndicator(mark: AttachmentFocusMark?) {
    val reduceMotion = rememberReduceMotion()
    var shown by remember { mutableStateOf<AttachmentFocusMark?>(null) }
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(mark?.id) {
        if (mark != null) {
            shown = mark
            // In from a larger square; out as a plain fade. Under Reduce Motion it only fades.
            val enter = Motion.microIn.animation<Float>(MotionPhase.Enter, reduceMotion)
            scale.snapTo(MotionReduce.scale(1.4f, reduceMotion))
            alpha.snapTo(0f)
            launch { alpha.animateTo(1f, enter) }
            scale.animateTo(1f, enter)
        } else if (shown != null) {
            alpha.animateTo(0f, Motion.microIn.animation(MotionPhase.Exit, reduceMotion))
            shown = null
        }
    }
    val current = shown ?: return
    val density = LocalDensity.current
    val side = if (current.locked) 92.dp else 76.dp
    val sidePx = with(density) { side.toPx() }
    Box(
        Modifier
            .offset { IntOffset((current.point.x - sidePx / 2).roundToInt(), (current.point.y - sidePx / 2).roundToInt()) }
            .size(side)
            .graphicsLayer {
                this.alpha = alpha.value
                scaleX = scale.value
                scaleY = scale.value
            }
            .border(1.5.dp, CameraYellow)
            .clearAndSetSemantics {},
    )
}

/**
 * Camera-local presentation state: only the camera's own state lives here; the parent owns
 * attachments.
 */
private class AttachmentCameraState(private val context: Context, private val scope: CoroutineScope) {
    var message: String? by mutableStateOf(localized(R.string.composer_camera_opening))
    var needsSettings by mutableStateOf(false)
    var canRetry by mutableStateOf(false)
    var canCapture by mutableStateOf(false)
    var canFlip by mutableStateOf(false)
    var isVisible by mutableStateOf(false)
    var isActivating by mutableStateOf(false)
    var zoom: CameraZoomModel? by mutableStateOf(null)

    /** Replaced (copied) on each change so readers recompose. */
    var zoomState: CameraZoomState by mutableStateOf(CameraZoomState())
    var hasFlash by mutableStateOf(false)
    var flash by mutableStateOf(AttachmentCameraFlash.Off)
    var focusMark: AttachmentFocusMark? by mutableStateOf(null)
    var isFocusLocked by mutableStateOf(false)
    var onCapture: ((ByteArray) -> Unit)? = null
    var reduceMotion = false
    var displayRotation: () -> Int = { Surface.ROTATION_0 }
    var activity: android.app.Activity? = null
    var lifecycle: LifecycleOwner? = null
    private var generation: UUID? = null
    private var focusClear: Job? = null

    val capture = AttachmentCaptureSession(context, scope) { generation, event -> handle(generation, event) }

    private inline fun mutateZoom(block: CameraZoomState.() -> Unit) {
        zoomState = zoomState.copy().apply(block)
    }

    private fun handle(eventGeneration: UUID, event: AttachmentCaptureSession.Event) {
        if (!isVisible || generation != eventGeneration) return
        when (event) {
            is AttachmentCaptureSession.Event.Ready -> {
                isActivating = false
                canCapture = true
                canFlip = event.readiness.canFlip
                message = null
                canRetry = false
                zoom = CameraZoomModel(event.readiness.device)
                mutateZoom { reset(event.readiness.zoom) }
                hasFlash = event.readiness.hasFlash
                isFocusLocked = false
            }
            is AttachmentCaptureSession.Event.Zoom -> mutateZoom { rampProgressed(event.ramp, event.factor) }
            AttachmentCaptureSession.Event.FocusReset -> isFocusLocked = false
            is AttachmentCaptureSession.Event.Failure -> {
                isActivating = false
                message = event.message
                canCapture = false
                canRetry = event.retry
                resetInteraction()
            }
            is AttachmentCaptureSession.Event.CaptureFailed -> {
                message = event.message
                canCapture = true
            }
            is AttachmentCaptureSession.Event.Photo -> {
                val callback = onCapture
                close()
                callback?.invoke(event.data)
            }
        }
    }

    fun isAuthorized(): Boolean = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun isRestricted(): Boolean =
        (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager)?.getCameraDisabled(null) == true

    fun activate() {
        if (!isVisible) return
        val current = UUID.randomUUID()
        generation = current
        isActivating = true
        canCapture = false
        canRetry = false
        needsSettings = false
        message = localized(R.string.composer_camera_opening)
        scope.launch {
            val prefs = context.getSharedPreferences("opencode.camera", Context.MODE_PRIVATE)
            if (!isAuthorized() && !isRestricted() && !prefs.getBoolean(ASKED, false)) {
                val host = activity
                val owner = lifecycle
                if (host != null && owner != null) {
                    requestPermissionsAndAwait(host, owner.lifecycle, arrayOf(Manifest.permission.CAMERA))
                    prefs.edit().putBoolean(ASKED, true).apply()
                }
            }
            if (!isVisible || generation != current) return@launch
            if (!isAuthorized()) {
                isActivating = false
                needsSettings = true
                message = if (isRestricted()) localized(R.string.composer_camera_access_restricted) else localized(R.string.composer_camera_access_needed)
                return@launch
            }
            val owner = lifecycle ?: return@launch
            capture.start(current, owner)
        }
    }

    fun pause() {
        generation = null
        isActivating = false
        canCapture = false
        resetInteraction()
        capture.stop()
    }

    /** Ends any pinch, ramp, focus square, and lock badge; the session resumes continuous focus when it next reports ready. */
    private fun resetInteraction() {
        mutateZoom { reset(factor) }
        focusMark = null
        isFocusLocked = false
    }

    fun close() {
        isVisible = false
        pause()
        onCapture = null
    }

    fun openSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { AppLog.app.error { "Couldn’t open Settings: ${it::class.java.simpleName}" } }
    }

    fun takePhoto() {
        val current = generation
        if (!canCapture || current == null) return
        canCapture = false
        message = null
        capture.takePhoto(current, if (hasFlash) flash else AttachmentCameraFlash.Off, displayRotation())
    }

    fun flip() {
        val current = generation
        if (!canCapture || !canFlip || current == null) return
        canCapture = false
        resetInteraction()
        val owner = lifecycle ?: return
        capture.flip(current, owner)
    }

    fun pinch(phase: Pinch) {
        val model = zoom
        val current = generation
        if (!canCapture || model == null || current == null) {
            mutateZoom { endPinch() }
            return
        }
        when (phase) {
            Pinch.Began -> mutateZoom { beginPinch() }
            is Pinch.Changed -> {
                var factor: Double? = null
                mutateZoom { factor = pinch(phase.scale, model) }
                factor?.let { capture.zoom(it, null, current) }
            }
            Pinch.Ended -> mutateZoom { endPinch() }
        }
    }

    fun select(preset: CameraZoomModel.Preset) {
        val model = zoom
        val current = generation
        if (!canCapture || model == null || current == null) return
        var selection: CameraZoomState.Selection? = null
        mutateZoom { selection = select(preset, model, animated = !reduceMotion) }
        selection?.let { capture.zoom(it.target, it.ramp, current) }
    }

    fun stepZoom(up: Boolean) {
        val model = zoom
        val current = generation
        if (!canCapture || model == null || current == null) return
        var factor = 0.0
        mutateZoom { factor = step(up, model) }
        capture.zoom(factor, null, current)
    }

    fun focus(point: Offset, surfacePoint: Offset, resolution: IntSize, lock: Boolean) {
        val current = generation
        if (!canCapture || zoom == null || current == null) return
        val mark = AttachmentFocusMark(point = point, locked = lock)
        focusMark = mark
        isFocusLocked = lock
        capture.focus(surfacePoint, resolution, lock, current)
        focusClear?.cancel()
        focusClear = scope.launch {
            delay(if (lock) 1_600 else 1_100)
            if (focusMark?.id == mark.id) focusMark = null
        }
    }

    companion object {
        private const val ASKED = "asked"
    }
}

/**
 * The CameraX session: binding, zoom (with lens ramps), focus and exposure, capture, and
 * camera-state reports, all on the main thread as CameraX requires. Events carry the generation
 * they belong to; stale ones are dropped by the state.
 */
private class AttachmentCaptureSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onEvent: (UUID, Event) -> Unit,
) {
    data class Readiness(val canFlip: Boolean, val device: CameraDeviceDescription, val zoom: Double, val hasFlash: Boolean)

    sealed interface Event {
        data class Ready(val readiness: Readiness) : Event

        /** A lens button's ramp progressing; direct zooms aren't echoed. */
        data class Zoom(val factor: Double, val ramp: UUID) : Event
        data object FocusReset : Event
        data class Failure(val message: String, val retry: Boolean) : Event
        data class CaptureFailed(val message: String) : Event
        class Photo(val data: ByteArray) : Event
    }

    /** What the viewfinder draws; CameraX hands it over when the preview binds. */
    var surfaceRequest: SurfaceRequest? by mutableStateOf(null)
        private set

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var position = CameraDeviceDescription.Position.Back
    private val preview = Preview.Builder().build().also { it.setSurfaceProvider { request -> surfaceRequest = request } }
    private val output = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    private var generation: UUID? = null
    private var pending = false
    private var interrupted = false

    /** CameraX ratio = device factor × [ratioBase] (the device's 1.0 is its widest lens). */
    private var ratioBase = 1.0
    private var deviceFactor = 1.0
    private var rampJob: Job? = null
    private val stateListener = androidx.lifecycle.Observer<CameraState> { state -> cameraStateChanged(state) }
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    private fun selector(at: CameraDeviceDescription.Position): CameraSelector =
        if (at == CameraDeviceDescription.Position.Front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    private fun has(provider: ProcessCameraProvider, at: CameraDeviceDescription.Position): Boolean =
        runCatching { provider.hasCamera(selector(at)) }.getOrDefault(false)

    fun start(generation: UUID, owner: LifecycleOwner) {
        this.generation = generation
        pending = false
        scope.launch {
            val cameras = provider ?: runCatching { ProcessCameraProvider.awaitInstance(context) }.getOrNull()
            if (this@AttachmentCaptureSession.generation != generation) return@launch
            if (cameras == null) {
                emit(Event.Failure(localized(R.string.composer_camera_open_failed), retry = true))
                return@launch
            }
            provider = cameras
            if (camera == null) {
                position = when {
                    has(cameras, CameraDeviceDescription.Position.Back) -> CameraDeviceDescription.Position.Back
                    has(cameras, CameraDeviceDescription.Position.Front) -> CameraDeviceDescription.Position.Front
                    else -> {
                        emit(Event.Failure(localized(R.string.composer_camera_none_available), retry = false))
                        return@launch
                    }
                }
            }
            if (!bind(cameras, owner, position)) {
                emit(Event.Failure(localized(R.string.composer_camera_unavailable), retry = true))
                return@launch
            }
            reportReadiness()
        }
    }

    private fun bind(cameras: ProcessCameraProvider, owner: LifecycleOwner, at: CameraDeviceDescription.Position): Boolean {
        camera?.cameraInfo?.cameraState?.removeObserver(stateListener)
        val bound = try {
            cameras.unbind(preview, output)
            cameras.bindToLifecycle(owner, selector(at), preview, output)
        } catch (error: Exception) {
            AppLog.app.error { "Camera bind failed: ${error::class.java.simpleName}" }
            camera = null
            return false
        }
        camera = bound
        position = at
        interrupted = false
        bound.cameraInfo.cameraState.observeForever(stateListener)
        // The camera is running: a lens or zoom query that fails only costs its lens buttons.
        try {
            prepare(bound)
        } catch (error: Exception) {
            AppLog.app.error { "Camera prepare failed: ${error::class.java.simpleName}" }
        }
        return true
    }

    fun stop() {
        generation = null
        pending = false
        rampJob?.cancel()
        rampJob = null
        camera?.cameraInfo?.cameraState?.removeObserver(stateListener)
        camera = null
        provider?.unbind(preview, output)
        surfaceRequest = null
    }

    fun takePhoto(generation: UUID, flash: AttachmentCameraFlash, rotation: Int) {
        if (this.generation != generation) return
        if (pending || camera == null || interrupted) {
            emit(Event.Failure(localized(R.string.composer_camera_temporarily_unavailable), retry = true))
            return
        }
        output.targetRotation = rotation
        output.flashMode = when (flash) {
            AttachmentCameraFlash.Off -> ImageCapture.FLASH_MODE_OFF
            AttachmentCameraFlash.Auto -> ImageCapture.FLASH_MODE_AUTO
            AttachmentCameraFlash.On -> ImageCapture.FLASH_MODE_ON
        }
        // Keep the camera's EXIF orientation; the parent decodes and resizes the image.
        // Front photos are unmirrored, like Camera.
        val bytes = ByteArrayOutputStream()
        val metadata = ImageCapture.Metadata().apply { isReversedHorizontal = false }
        val options = ImageCapture.OutputFileOptions.Builder(bytes).setMetadata(metadata).build()
        pending = true
        output.takePicture(
            options,
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (this@AttachmentCaptureSession.generation != generation || !pending) return
                    pending = false
                    val data = bytes.toByteArray()
                    if (data.isNotEmpty()) {
                        emit(Event.Photo(data))
                    } else {
                        emit(Event.CaptureFailed(localized(R.string.composer_camera_capture_failed)))
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    if (this@AttachmentCaptureSession.generation != generation || !pending) return
                    pending = false
                    AppLog.app.error { "Photo capture failed: code ${exception.imageCaptureError}" }
                    if (camera != null && !interrupted) {
                        emit(Event.CaptureFailed(localized(R.string.composer_camera_capture_failed)))
                    } else {
                        emit(Event.Failure(localized(R.string.composer_camera_temporarily_unavailable), retry = true))
                    }
                }
            },
        )
    }

    fun flip(generation: UUID, owner: LifecycleOwner) {
        val cameras = provider ?: return
        if (this.generation != generation || pending || camera == null) return
        val target = if (position == CameraDeviceDescription.Position.Front) CameraDeviceDescription.Position.Back else CameraDeviceDescription.Position.Front
        if (!has(cameras, target)) {
            emit(Event.CaptureFailed(localized(R.string.composer_camera_switch_failed)))
            return
        }
        val previous = position
        if (!bind(cameras, owner, target)) {
            if (!bind(cameras, owner, previous)) {
                emit(Event.Failure(localized(R.string.composer_camera_unavailable), retry = true))
                return
            }
            emit(Event.CaptureFailed(localized(R.string.composer_camera_switch_failed)))
        }
        reportReadiness()
    }

    /** Sets the zoom directly, or ramps to it when given a ramp ID, whose progress is then reported until it arrives. */
    fun zoom(factor: Double, ramp: UUID?, generation: UUID) {
        val bound = camera ?: return
        if (this.generation != generation) return
        val state = bound.cameraInfo.zoomState.value
        val minFactor = (state?.minZoomRatio?.toDouble() ?: 1.0) / ratioBase
        val maxFactor = (state?.maxZoomRatio?.toDouble() ?: 1.0) / ratioBase
        val target = min(max(factor, minFactor), maxFactor)
        rampJob?.cancel()
        rampJob = null
        if (ramp == null) {
            setFactor(bound, target)
            return
        }
        rampJob = scope.launch {
            var last = System.nanoTime()
            while (true) {
                delay(16)
                if (this@AttachmentCaptureSession.generation != generation) return@launch
                val now = System.nanoTime()
                val dt = min((now - last) / 1e9, 1.0 / 30)
                last = now
                val remaining = ln(target / deviceFactor) / ln(2.0)
                val step = ZOOM_RAMP_RATE * dt
                val next = if (abs(remaining) <= step) target else deviceFactor * 2.0.pow(if (remaining > 0) step else -step)
                setFactor(bound, next)
                emit(Event.Zoom(next, ramp))
                if (abs(next - target) < 0.001) return@launch
            }
        }
    }

    private fun setFactor(bound: Camera, factor: Double) {
        deviceFactor = factor
        bound.cameraControl.setZoomRatio((factor * ratioBase).toFloat())
    }

    /**
     * Focuses and exposes once at a surface point, then holds. A tap releases back to
     * continuous after CameraX's auto-cancel; a lock holds until the next tap.
     */
    fun focus(surfacePoint: Offset, resolution: IntSize, lock: Boolean, generation: UUID) {
        val bound = camera ?: return
        if (this.generation != generation) return
        val factory = SurfaceOrientedMeteringPointFactory(resolution.width.toFloat(), resolution.height.toFloat())
        val point = factory.createPoint(surfacePoint.x, surfacePoint.y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .apply { if (lock) disableAutoCancel() }
            .build()
        bound.cameraControl.startFocusAndMetering(action)
    }

    /** A newly bound camera starts at 1× (the wide lens) with continuous focus and exposure. */
    private fun prepare(bound: Camera) {
        val description = describe(bound)
        val model = CameraZoomModel(description)
        rampJob?.cancel()
        rampJob = null
        setFactor(bound, model.defaultFactor)
        bound.cameraControl.cancelFocusAndMetering()
    }

    /** Releases a tap's focus or an AE/AF Lock, as after a restart. */
    private fun resumeContinuousFocus() {
        camera?.cameraControl?.cancelFocusAndMetering()
    }

    /**
     * Maps CameraX's zoom space (1.0 = the main wide lens; a logical camera reaches its ultra wide
     * below 1) into one where 1.0 is the widest lens, with each physical lens's intrinsic zoom as
     * its switch-over point.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun describe(bound: Camera): CameraDeviceDescription {
        val info = bound.cameraInfo
        val state = info.zoomState.value
        val minRatio = state?.minZoomRatio?.toDouble() ?: 1.0
        val maxRatio = state?.maxZoomRatio?.toDouble() ?: 1.0
        ratioBase = min(1.0, minRatio)
        val lenses = mutableListOf<CameraDeviceDescription.Lens>()
        val switchOver = mutableListOf<Double>()
        if (ratioBase < 0.95) {
            lenses.add(CameraDeviceDescription.Lens.UltraWide)
            switchOver.add(1.0 / ratioBase)
        }
        lenses.add(CameraDeviceDescription.Lens.Wide)
        var tele: Double? = null
        for (ratio in physicalZoomRatios(info)) {
            if (ratio < TELEPHOTO_MIN_RATIO || ratio > maxRatio) continue
            if (tele == null || ratio < tele) tele = ratio
        }
        if (tele != null) {
            lenses.add(CameraDeviceDescription.Lens.Telephoto)
            switchOver.add(tele / ratioBase)
        }
        // A high-resolution main sensor crops a full-quality 2×, like Camera's.
        val pixels = runCatching {
            Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        }.getOrNull()
        val crops = if (pixels != null && pixels.width.toLong() * pixels.height >= NATIVE_CROP_MIN_PIXELS) listOf(2.0 / ratioBase) else emptyList()
        return CameraDeviceDescription(
            position = position,
            lenses = lenses,
            switchOverFactors = switchOver,
            minZoom = minRatio / ratioBase,
            maxZoom = maxRatio / ratioBase,
            nativeCropFactors = crops,
        )
    }

    /**
     * Each physical lens's zoom relative to the main lens. CameraX can't say for a physical camera
     * on every device (its adapter throws `UnsupportedOperationException` on Samsung's logical
     * camera), so Camera2's focal lengths stand in, and no answer means no telephoto button.
     */
    private fun physicalZoomRatios(info: CameraInfo): List<Double> {
        val ratios = mutableListOf<Double>()
        for (physical in runCatching { info.physicalCameraInfos }.getOrDefault(emptySet())) {
            val ratio = runCatching { physical.intrinsicZoomRatio.toDouble() }.getOrNull() ?: continue
            ratios.add(ratio)
        }
        if (ratios.isNotEmpty()) return ratios
        return focalLengthZoomRatios(info)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun focalLengthZoomRatios(info: CameraInfo): List<Double> {
        val manager = context.getSystemService(CameraManager::class.java) ?: return emptyList()
        val logicalID = runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull() ?: return emptyList()
        val logical = runCatching { manager.getCameraCharacteristics(logicalID) }.getOrNull() ?: return emptyList()
        val main = equivalentFocalLength(logical) ?: return emptyList()
        val ratios = mutableListOf<Double>()
        for (id in logical.physicalCameraIds) {
            val physical = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val focal = equivalentFocalLength(physical) ?: continue
            val ratio = focal / main
            // Focal lengths only approximate a lens's zoom (a 3× telephoto measures about 2.7):
            // a telephoto takes its nominal whole number, as the phone's own camera labels it.
            ratios.add(if (ratio >= TELEPHOTO_MIN_RATIO) round(ratio) else ratio)
        }
        return ratios
    }

    /** Focal length over the sensor's diagonal: lenses compare by it whatever their sensor size. */
    private fun equivalentFocalLength(characteristics: CameraCharacteristics): Double? {
        val focal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val size = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val diagonal = hypot(size.width, size.height)
        if (diagonal <= 0f) return null
        return focal.toDouble() / diagonal
    }

    private fun reportReadiness() {
        val bound = camera
        if (bound == null || interrupted) {
            emit(Event.Failure(localized(R.string.composer_camera_temporarily_unavailable), retry = true))
            return
        }
        if (pending) return
        val cameras = provider
        val opposite = if (position == CameraDeviceDescription.Position.Front) CameraDeviceDescription.Position.Back else CameraDeviceDescription.Position.Front
        emit(
            Event.Ready(
                Readiness(
                    canFlip = cameras != null && has(cameras, opposite),
                    device = describe(bound),
                    zoom = deviceFactor,
                    hasFlash = position == CameraDeviceDescription.Position.Back && bound.cameraInfo.hasFlashUnit(),
                ),
            ),
        )
    }

    private fun cameraStateChanged(state: CameraState) {
        if (generation == null) return
        val error = state.error
        if (error != null) {
            interrupted = true
            when (error.code) {
                CameraState.ERROR_CAMERA_IN_USE,
                CameraState.ERROR_MAX_CAMERAS_IN_USE,
                CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED,
                CameraState.ERROR_CAMERA_DISABLED,
                -> emit(Event.Failure(localized(R.string.composer_camera_interrupted), retry = false))
                else -> emit(Event.Failure(localized(R.string.composer_camera_stopped), retry = true))
            }
            AppLog.app.notice { "Camera state error ${error.code}" }
            return
        }
        if (state.type == CameraState.Type.OPEN && interrupted) {
            interrupted = false
            resumeContinuousFocus()
            emit(Event.FocusReset)
            reportReadiness()
        }
    }

    private fun emit(event: Event) {
        val current = generation ?: return
        onEvent(current, event)
    }

    companion object {
        /** Zoom ramps in powers of two per second: 0.5× to 3× in under half a second. */
        const val ZOOM_RAMP_RATE = 6.0

        /** A physical lens at least this much narrower than the main one counts as a telephoto. */
        const val TELEPHOTO_MIN_RATIO = 1.8

        /** Sensors this large (48 MP class) crop a native 2×. */
        const val NATIVE_CROP_MIN_PIXELS = 40_000_000L
    }
}
