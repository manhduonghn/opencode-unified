package ai.opencode.feature.settings.servers

import ai.opencode.core.R as CoreR
import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.ui.AppearanceColorScheme
import ai.opencode.core.ui.OpenCodeAlertDialog
import ai.opencode.core.ui.OpenCodeButtonDefaults
import ai.opencode.core.ui.OpenCodeFonts
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.ui.OpenCodeSecondaryButton
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.OpenCodeTopBar
import ai.opencode.core.ui.accessibilityFocusRequest
import ai.opencode.core.ui.openCodeHairline
import ai.opencode.core.ui.rememberAccessibilityFocusRequest
import ai.opencode.core.ui.rememberAppAppearance
import ai.opencode.feature.settings.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * The camera scanner for the QR code `opencode pair` prints, presented full screen by `:app` (“Scan
 * Pairing Code”).
 *
 * Honest about the camera, with a paste path every time: without a camera it asks for the pairing
 * link to be pasted; with access off it offers Open Settings (when Android would no longer ask) and
 * Paste Pairing Link. Otherwise it asks for access once, then shows the camera under a viewfinder,
 * with Paste in the bar. One code per presentation.
 *
 * The scanner doesn't validate: [onScanned] gets the raw text (a scanned code or the clipboard's
 * non-blank text: `{"code","urls"}` JSON, an `opencode.ai/pair` page, a deep link or a single
 * `…/auth/connect/…` https URL), which `:app` decodes with `OpenCodePairingLink.decode`, plays
 * its success or error haptic for, and redeems. Never log it.
 *
 * Place it where a [CameraPermissionHost] can register (this view adds its own).
 *
 * @param onScanned the code's or clipboard's text.
 * @param onCancel Cancel in the bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScannerView(
    onScanned: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CameraPermissionHost()
    val camera = remember { dependency(ServerCameraClient) }
    val pasteboard = remember { dependency(PasteboardClient) }
    val isCameraAvailable = remember { camera.isAvailable() }
    var cameraAccess by remember { mutableStateOf(camera.authorization()) }
    var pasteFailed by rememberSaveable { mutableStateOf(false) }
    val isAccessDenied = cameraAccess == CameraAuthorization.denied
    // Coming back from system Settings with access turned on shows the camera.
    LifecycleResumeEffect(Unit) {
        if (cameraAccess == CameraAuthorization.denied && camera.authorization() == CameraAuthorization.authorized) {
            cameraAccess = CameraAuthorization.authorized
        }
        onPauseOrDispose {}
    }

    fun paste() {
        val text = pasteboard.string()
        if (text == null || text.isBlank()) {
            pasteFailed = true
            return
        }
        onScanned(text)
    }

    // The capture session starts only once access is granted: asked while the prompt is up, it
    // wouldn't start after Allow, so the camera waits for the answer.
    LaunchedEffect(Unit) {
        if (cameraAccess != CameraAuthorization.notDetermined || !isCameraAvailable) return@LaunchedEffect
        camera.authorize()
        cameraAccess = camera.authorization()
    }

    // VoiceOver starts on the navigation title, a header, and comes back to it once the camera
    // prompt is answered; TalkBack would start on Cancel.
    val titleFocus = rememberAccessibilityFocusRequest(cameraAccess)

    val appearance = rememberAppAppearance()
    OpenCodeTheme(theme = appearance.theme, appearance = AppearanceColorScheme.Dark) {
        Scaffold(
            modifier = modifier,
            containerColor = OpenCodeTheme.colors.canvas,
            topBar = {
                OpenCodeTopBar(
                    centerTitle = true,
                    hairline = false,
                    containerColor = OpenCodeTheme.colors.canvas,
                    contentColor = OpenCodeTheme.colors.text,
                    title = {
                        Text(
                            stringResource(R.string.settings_servers_scan_pairing_code),
                            style = OpenCodeTextStyles.headline,
                            modifier = Modifier.accessibilityFocusRequest(titleFocus).semantics { heading() },
                        )
                    },
                    navigation = { ScannerBarButton(stringResource(CoreR.string.common_cancel), onClick = onCancel) },
                    actions = {
                        if (isCameraAvailable && !isAccessDenied) {
                            ScannerBarButton(stringResource(R.string.settings_pairing_paste), onClick = ::paste)
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().background(OpenCodeTheme.colors.canvas)) {
                when {
                    !isCameraAvailable -> PairingScannerFallback(
                        symbol = "camera.metering.unknown",
                        title = stringResource(R.string.settings_pairing_no_camera_title),
                        message = stringResource(R.string.settings_pairing_no_camera_message),
                        settings = false,
                        onOpenSettings = camera.openSettings,
                        onPaste = ::paste,
                        modifier = Modifier.padding(padding),
                    )
                    isAccessDenied -> PairingScannerFallback(
                        symbol = "camera.fill",
                        title = stringResource(R.string.settings_pairing_camera_off_title),
                        message = stringResource(R.string.settings_pairing_camera_off_message),
                        settings = true,
                        onOpenSettings = camera.openSettings,
                        onPaste = ::paste,
                        modifier = Modifier.padding(padding),
                    )
                    else -> {
                        if (cameraAccess == CameraAuthorization.authorized) {
                            QRCameraPreview(isRunning = true, once = true, onScanned = onScanned, modifier = Modifier.fillMaxSize())
                        }
                        PairingViewfinder(Modifier.padding(padding))
                    }
                }
            }
        }
        if (pasteFailed) {
            OpenCodeAlertDialog(
                onDismissRequest = { pasteFailed = false },
                confirmButton = { TextButton(onClick = { pasteFailed = false }) { Text(stringResource(CoreR.string.common_ok)) } },
                title = { Text(stringResource(R.string.settings_pairing_nothing_to_paste_title)) },
                text = {
                    Text(stringResource(R.string.settings_pairing_nothing_to_paste_message))
                },
            )
        }
    }
}

/** The bar's text controls: plain labels with the desktop's control corners for the press state. */
@Composable
private fun ScannerBarButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shape = OpenCodeShape.control,
        colors = ButtonDefaults.textButtonColors(contentColor = OpenCodeTheme.colors.text),
    ) {
        Text(label, style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun PairingViewfinder(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(250.dp)
                .drawBehind { drawViewfinder(Color.White) }
                .clearAndSetSemantics {},
        )
        Spacer(Modifier.weight(1f))
        Column(
            Modifier
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
                .background(OpenCodeSurfaces.color.copy(alpha = 0.92f), OpenCodeShape.panel)
                .openCodeHairline(OpenCodeShape.panel)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.settings_pairing_point_camera), style = OpenCodeTextStyles.headline, color = OpenCodeTheme.colors.text, textAlign = TextAlign.Center)
            val command = "opencode pair"
            val hint = stringResource(R.string.settings_pairing_computer_hint, command)
            val commandStart = hint.indexOf(command)
            Text(
                buildAnnotatedString {
                    if (commandStart < 0) {
                        append(hint)
                    } else {
                        append(hint.substring(0, commandStart))
                        withStyle(SpanStyle(fontFamily = OpenCodeFonts.monospace, fontSize = 14.sp, color = OpenCodeTheme.colors.text)) {
                            append(command)
                        }
                        append(hint.substring(commandStart + command.length))
                    }
                },
                style = OpenCodeTextStyles.subheadline,
                color = OpenCodeTheme.colors.secondaryText,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The viewfinder: four bold corner marks around a window with the desktop's sheet radius, and the
 * scene outside the window dimmed. The marks sit just outside the window, on the dimmed scene, so
 * they stay legible over bright scenes.
 */
private fun DrawScope.drawViewfinder(color: Color) {
    val radius = OpenCodeRadius.sheet.toPx()
    val dim = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(-size.width * 8, -size.height * 8, size.width * 9, size.height * 9))
        addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(radius)))
    }
    drawPath(dim, Color.Black.copy(alpha = 0.4f))

    val stroke = 5.dp.toPx()
    val arm = 44.dp.toPx()
    // The stroke's centre line runs half a stroke outside the window edge.
    val left = -stroke / 2
    val top = -stroke / 2
    val right = size.width + stroke / 2
    val bottom = size.height + stroke / 2
    val r = radius + stroke / 2
    val corners = Path()
    // Top left
    corners.moveTo(left, top + arm)
    corners.arcTo(Rect(left, top, left + 2 * r, top + 2 * r), 180f, 90f, false)
    corners.lineTo(left + arm, top)
    // Top right
    corners.moveTo(right - arm, top)
    corners.arcTo(Rect(right - 2 * r, top, right, top + 2 * r), 270f, 90f, false)
    corners.lineTo(right, top + arm)
    // Bottom right
    corners.moveTo(right, bottom - arm)
    corners.arcTo(Rect(right - 2 * r, bottom - 2 * r, right, bottom), 0f, 90f, false)
    corners.lineTo(right - arm, bottom)
    // Bottom left
    corners.moveTo(left + arm, bottom)
    corners.arcTo(Rect(left, bottom - 2 * r, left + 2 * r, bottom), 90f, 90f, false)
    corners.lineTo(left, bottom - arm)
    drawPath(corners, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
}

@Composable
private fun PairingScannerFallback(
    symbol: String,
    title: String,
    message: String,
    settings: Boolean,
    onOpenSettings: () -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        OpenCodeIcon(symbol, contentDescription = null, size = 28.dp, tint = OpenCodeTheme.colors.secondaryText)
        Text(title, style = OpenCodeTextStyles.headline, color = OpenCodeTheme.colors.text, textAlign = TextAlign.Center)
        Text(
            inlineCode(message),
            style = OpenCodeTextStyles.subheadline,
            color = OpenCodeTheme.colors.secondaryText,
            textAlign = TextAlign.Center,
        )
        Column(Modifier.padding(top = 8.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (settings) {
                PasteButton(prominent = true, onClick = onOpenSettings) { Text(stringResource(CoreR.string.common_open_settings)) }
            }
            PasteButton(prominent = !settings, onClick = onPaste) {
                OpenCodeIcon("doc.on.clipboard", contentDescription = null, size = 20.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_pairing_paste_link))
            }
        }
    }
}

/** Prominent is a white primary button with black text; otherwise secondary. */
@Composable
private fun PasteButton(prominent: Boolean, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    if (prominent) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().heightIn(min = OpenCodeButtonDefaults.height),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            contentPadding = OpenCodeButtonDefaults.contentPadding,
            content = content,
            shape = OpenCodeShape.control,
        )
    } else {
        OpenCodeSecondaryButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/** Markdown's `code` spans in monospace. */
internal fun inlineCode(text: String): AnnotatedString = buildAnnotatedString {
    text.split('`').forEachIndexed { index, part ->
        if (index % 2 == 1) withStyle(SpanStyle(fontFamily = OpenCodeFonts.monospace, fontWeight = FontWeight.Normal)) { append(part) }
        else append(part)
    }
}
