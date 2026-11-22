package ai.opencode.feature.settings.onboarding

import ai.opencode.core.R as CoreR
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.feature.settings.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import ai.opencode.core.ui.OpenCodeFonts
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun ui(size: Float, weight: FontWeight = FontWeight.Normal, family: FontFamily? = null) =
    TextStyle(fontSize = size.sp, fontWeight = weight, fontFamily = family, letterSpacing = (-0.1).sp)

private val deskText = WelcomeInk.hex(0xEDEDED)
private val deskDim = WelcomeInk.hex(0x8F8F8F)

/**
 * The laptop's screen: the OpenCode desktop app in its dark theme, drawn from its captures at a
 * logical 720 × 450 and scaled into the lid. The session works through the story, with the browser
 * it drives in a window over it; pairing cross-fades to Settings › Pairing with its code. [pairing]
 * is the cross-fade's progress (0 the session, 1 pairing).
 */
@Composable
internal fun WelcomeDesktop(frame: WelcomeFrame, wall: Double, pairing: Float, reduced: Boolean, targets: WelcomeTargets?) {
    val working = (1 - pairing) * frame.deskWorking
    val named = pairing + (1 - pairing) * frame.deskNamed
    Box(Modifier.requiredSize(WelcomeDesktop.size.width.dp, WelcomeDesktop.size.height.dp).background(WelcomeInk.hex(0x080808))) {
        DesktopTitleBar(working, named, wall, reduced)
        val panel = WelcomeDesktop.panel
        val panelShape = RoundedCornerShape(9.dp)
        Box(
            Modifier
                .offset(panel.left.dp, panel.top.dp)
                .requiredSize(panel.width.dp, panel.height.dp)
                .clip(panelShape)
                .background(WelcomeInk.hex(0x161616))
                .border(0.5.dp, Color.White.copy(alpha = 0.07f), panelShape),
        ) {
            Box(Modifier.fillMaxSize().alpha(1 - pairing).welcomeBlur(if (reduced) 0f else 3f * pairing)) {
                DesktopSession(frame, wall, named, working, reduced, targets)
            }
            Box(Modifier.fillMaxSize().alpha(pairing).welcomeBlur(if (reduced) 0f else 3f * (1 - pairing))) {
                DesktopSettings(pairing, reduced)
            }
        }
        if (pairing < 1f) DesktopBrowser(frame, reduced, targets, Modifier.alpha(1 - pairing))
    }
}

internal object WelcomeDesktop {
    /** The desktop's logical screen. */
    val size = androidx.compose.ui.geometry.Size(720f, 450f)

    /** The panel below the title bar, inset 6 pt. */
    val panel = androidx.compose.ui.geometry.Rect(6f, 38f, 6f + 708f, 38f + 406f)

    /** The session's column, in panel coordinates, kept left of the phone that overlaps the lid. */
    val column = androidx.compose.ui.geometry.Rect(40f, 50f, 40f + 404f, 50f + 258f)

    /** The composer, in panel coordinates. */
    val composer = androidx.compose.ui.geometry.Rect(24f, 316f, 24f + 436f, 316f + 78f)

    /** The browser window, in screen coordinates: centred in the part of the screen the phone leaves in view, below the prompt's bubble. */
    val browser: androidx.compose.ui.geometry.Rect
        get() = androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset(84f, 110f), WelcomeBrowserWindow.size)
}

@Composable
private fun DesktopTitleBar(working: Double, named: Double, wall: Double, reduced: Boolean) {
    Box(
        Modifier
            .offset(12.dp, 10.dp)
            .size(18.dp)
            .background(WelcomeInk.hex(0x3679FD), RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(7.dp, 10.dp).border(1.6.dp, Color.White, RoundedCornerShape(1.5.dp)))
    }
    OpenCodeIcon("square.grid.2x2", null, Modifier.offset(40.dp, 11.dp), size = 12.dp, tint = WelcomeInk.hex(0x8E8E8E))
    Row(
        Modifier
            .offset(62.dp, 5.dp)
            .size(180.dp, 28.dp)
            .background(WelcomeInk.hex(0x212121), RoundedCornerShape(7.dp))
            .padding(start = 7.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(15.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(15.dp).alpha((1 - working).toFloat()).background(WelcomeInk.hex(0x5A5A5A), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                Text("A", style = ui(10f, FontWeight.SemiBold), color = WelcomeInk.hex(0xE0E0E0))
            }
            WelcomeWorkingDots(wall, Modifier.alpha(working.toFloat()), dot = 2.6f, gap = 1.4f, color = WelcomeInk.hex(0xCFCFCF), reduced = reduced)
        }
        WelcomeStack(
            labels = listOf(stringResource(R.string.settings_onboarding_desktop_new_session), WelcomeStory.TITLE),
            weights = listOf(1 - named, named),
            style = ui(13f),
            color = deskText,
            modifier = Modifier.weight(1f).clip(androidx.compose.ui.graphics.RectangleShape),
            reduced = reduced,
        )
        OpenCodeIcon("xmark", null, size = 9.dp, tint = WelcomeInk.hex(0x7A7A7A))
    }
    OpenCodeIcon("plus", null, Modifier.offset(254.dp, 11.dp), size = 12.dp, tint = WelcomeInk.hex(0x8E8E8E))
}

@Composable
private fun DesktopSession(frame: WelcomeFrame, wall: Double, named: Double, working: Double, reduced: Boolean, targets: WelcomeTargets?) {
    // The session's header row: the screen glyph, its title and ···.
    Row(Modifier.offset(x = 14.dp).height(40.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        OpenCodeIcon("desktopcomputer", null, size = 11.dp, tint = WelcomeInk.hex(0x9A9A9A))
        WelcomeStack(listOf(stringResource(R.string.settings_onboarding_desktop_new_session), WelcomeStory.TITLE), listOf(1 - named, named), ui(13f, FontWeight.Medium), deskText, reduced = reduced)
        Text("···", style = ui(13f), color = WelcomeInk.hex(0x8A8A8A))
    }
    val column = WelcomeDesktop.column
    Box(
        Modifier
            .offset(column.left.dp, column.top.dp)
            .requiredSize(column.width.dp, column.height.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.08f to Color.Black), blendMode = BlendMode.DstIn)
            },
    ) {
        Box(Modifier.fillMaxSize().alpha(frame.transcript.toFloat()).welcomeBlur(if (reduced || frame.loopBlur < 0.05) 0f else frame.loopBlur.toFloat())) {
            DesktopColumn(frame, wall, reduced, targets)
        }
    }
    DesktopComposer(frame, working, reduced)
}

@Composable
private fun DesktopColumn(frame: WelcomeFrame, wall: Double, reduced: Boolean, targets: WelcomeTargets?) {
    val trailing = TransformOrigin(1f, 0.5f)
    WelcomeColumn(Modifier.fillMaxSize()) {
        Bubble(WelcomeStory.PROMPT, WelcomeTarget.DeskPrompt, targets, Modifier.welcomeShow(frame.deskPrompt, dy = 6f, scale = 0.95f, anchor = trailing, reduced = reduced).welcomeRow(frame.deskPrompt))
        ToolRow("magnifyingglass", Modifier.padding(top = 10.dp).welcomeShow(frame.explore, dy = 4f, reduced = reduced).welcomeRow(frame.explore)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.welcomeShimmer(1 - frame.explored, wall, reduced)) {
                WelcomeStack(listOf(stringResource(R.string.settings_onboarding_tool_exploring), stringResource(R.string.settings_onboarding_tool_explored)), listOf(1 - frame.explored, frame.explored), ui(13f), deskText, reduced = reduced)
                Text(stringResource(R.string.settings_onboarding_tool_explored_counts), style = ui(13f), color = deskDim, maxLines = 1)
            }
        }
        ToolRow("globe", Modifier.welcomeShow(frame.browse, dy = 4f, reduced = reduced).welcomeRow(frame.browse)) {
            Text(stringResource(R.string.settings_onboarding_tool_browser), style = ui(13f), color = deskText)
            Text("localhost:3000", style = ui(13f), color = deskDim)
        }
        ToolRow("pencil", Modifier.welcomeShow(frame.edit, dy = 4f, reduced = reduced).welcomeRow(frame.edit)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.welcomeShimmer(1 - frame.edited, wall, reduced)) {
                Text(stringResource(R.string.settings_onboarding_tool_edit), style = ui(13f), color = deskText)
                Text("Hero.tsx", style = ui(13f), color = deskDim)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.alpha(frame.edited.clamped01().toFloat())) {
                    Text("+6", style = ui(13f), color = WelcomeInk.hex(0x3FB950))
                    Text("−1", style = ui(13f), color = WelcomeInk.hex(0xF0605A))
                }
            }
        }
        ToolRow("camera.viewfinder", Modifier.welcomeShow(frame.screenshotRow, dy = 4f, reduced = reduced).welcomeRow(frame.screenshotRow)) {
            Text(stringResource(R.string.settings_onboarding_tool_screenshot), style = ui(13f), color = deskText)
            Text(WelcomeScreenshot.NAME, style = ui(13f), color = deskDim)
        }
        Text(
            WelcomeStreamedText.attributed(WelcomeStory.REPLY_WORDS, frame.deskWords, deskText, code = OpenCodeFonts.monospace),
            style = ui(13f).copy(lineHeight = 19.sp),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp).welcomeRow(frame.deskReply),
        )
        Bubble(WelcomeStory.SHIP_IT, WelcomeTarget.DeskShip, targets, Modifier.padding(top = 14.dp).welcomeShow(frame.deskShip, dy = 6f, scale = 0.95f, anchor = trailing, reduced = reduced).welcomeRow(frame.deskShip))
        ToolRow("terminal", Modifier.padding(top = 10.dp).welcomeShow(frame.run, dy = 4f, reduced = reduced).welcomeRow(frame.run)) {
            Text(stringResource(R.string.settings_onboarding_tool_running_commands), style = ui(13f), color = deskText, modifier = Modifier.welcomeShimmer(1.0, wall, reduced))
            Text("git push", style = ui(12.5f, family = OpenCodeFonts.monospace), color = deskDim)
        }
    }
}

/** A message the person sent, in the app's navy bubble on the column's right. */
@Composable
private fun Bubble(message: String, target: WelcomeTarget, targets: WelcomeTargets?, modifier: Modifier) {
    val shape = RoundedCornerShape(7.dp)
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            message,
            style = ui(14f),
            color = WelcomeInk.hex(0xB9CEFF),
            modifier = Modifier
                .welcomeTarget(target, targets)
                .background(WelcomeInk.hex(0x021945), shape)
                .border(0.5.dp, WelcomeInk.hex(0x4E86FF, 0.22), shape)
                .padding(vertical = 7.dp, horizontal = 11.dp),
        )
    }
}

@Composable
private fun ToolRow(symbol: String, modifier: Modifier, label: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) { OpenCodeIcon(symbol, null, size = 11.dp, tint = deskDim) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { label() }
    }
}

/** The prompt box, as the captures show it: its placeholder, then + · the model · Send (Stop while it works). */
@Composable
private fun DesktopComposer(frame: WelcomeFrame, working: Double, reduced: Boolean) {
    val composer = WelcomeDesktop.composer
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .offset(composer.left.dp, composer.top.dp)
            .requiredSize(composer.width.dp, composer.height.dp)
            .background(WelcomeInk.hex(0x242424), shape)
            .border(0.5.dp, Color.White.copy(alpha = 0.06f), shape),
    ) {
        WelcomeStack(
            labels = listOf(stringResource(R.string.settings_onboarding_desktop_composer_placeholder), stringResource(R.string.settings_onboarding_desktop_composer_follow_up_placeholder)),
            weights = listOf(1 - frame.followUp, frame.followUp),
            style = ui(13f),
            color = WelcomeInk.hex(0x7E7E7E),
            modifier = Modifier.offset(14.dp, 13.dp),
            reduced = reduced,
        )
        Row(
            Modifier
                .offset(y = (composer.height - 8 - 24).dp)
                .fillMaxWidth()
                .height(24.dp)
                .padding(start = 14.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenCodeIcon("plus", null, size = 13.dp, tint = WelcomeInk.hex(0x8A8A8A))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp, 11.dp).border(1.2.dp, WelcomeInk.hex(0x9A9A9A), RoundedCornerShape(1.5.dp)))
                Text("Big Pickle", style = ui(13f), color = WelcomeInk.hex(0xC4C4C4))
                OpenCodeIcon("chevron.down", null, size = 8.dp, tint = WelcomeInk.hex(0x8A8A8A))
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(24.dp)) {
                Box(Modifier.fillMaxSize().alpha((1 - working).toFloat()).background(WelcomeInk.hex(0x727272), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                    OpenCodeIcon("arrow.up", null, size = 11.dp, tint = WelcomeInk.hex(0x2A2A2A))
                }
                Box(Modifier.fillMaxSize().alpha(working.toFloat()).background(deskText, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(8.dp).background(WelcomeInk.hex(0x161616), RoundedCornerShape(1.5.dp)))
                }
            }
        }
    }
}

/** The browser OpenCode drives, opening over the app and receding cleanly once the captured screenshot lifts off toward the phone. */
@Composable
private fun DesktopBrowser(frame: WelcomeFrame, reduced: Boolean, targets: WelcomeTargets?, modifier: Modifier) {
    val p = frame.browser.clamped01().toFloat()
    val recede = frame.browserRecede.toFloat()
    val blur = if (reduced) 0f else 3.5f * (1 - p)
    val browser = WelcomeDesktop.browser
    WelcomeBrowserWindow(
        frame,
        targets,
        modifier
            .offset(browser.left.dp, browser.top.dp)
            .graphicsLayer {
                alpha = p
                val scale = if (reduced) 1f else (0.95f + 0.05f * p) * (1 - 0.025f * recede)
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 1f)
                translationY = if (reduced) 0f else 8.dp.toPx() * (1 - p)
            }
            .welcomeBlur(blur),
    )
}

// MARK: Settings › Pairing

private val navigation: List<Pair<String, Int>?> = listOf(
    "slider.horizontal.3" to R.string.settings_onboarding_desktop_nav_preferences,
    "paintpalette" to R.string.settings_onboarding_desktop_nav_appearance,
    "bell" to R.string.settings_onboarding_desktop_nav_notifications,
    "keyboard" to R.string.settings_onboarding_desktop_nav_shortcuts,
    "laptopcomputer.and.iphone" to R.string.settings_onboarding_desktop_pairing_title, null,
    "folder" to R.string.settings_onboarding_desktop_nav_projects,
    "arrow.triangle.branch" to R.string.settings_onboarding_desktop_nav_worktrees,
    "cpu" to R.string.settings_onboarding_desktop_nav_providers,
    "sparkle" to R.string.settings_onboarding_desktop_nav_models,
)

@Composable
private fun DesktopSettings(pairing: Float, reduced: Boolean) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.offset(60.dp, 30.dp).width(150.dp)) {
            NavRow("chevron.backward", stringResource(R.string.settings_onboarding_desktop_back_to_app), WelcomeInk.hex(0xD0D0D0), Modifier.padding(bottom = 18.dp))
            NavRow("magnifyingglass", stringResource(CoreR.string.common_search), WelcomeInk.hex(0x7A7A7A), Modifier.padding(bottom = 8.dp))
            for (item in navigation) {
                if (item == null) {
                    Spacer(Modifier.height(10.dp))
                } else {
                    val selected = item.second == R.string.settings_onboarding_desktop_pairing_title
                    NavRow(
                        item.first,
                        stringResource(item.second),
                        if (selected) Color.White else WelcomeInk.hex(0xD0D0D0),
                        if (selected) Modifier.background(WelcomeInk.hex(0x2A2A2A), RoundedCornerShape(6.dp)) else Modifier,
                    )
                }
            }
        }
        Column(Modifier.offset(240.dp, 30.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.settings_onboarding_desktop_pairing_title), style = ui(15f, FontWeight.SemiBold), color = deskText)
            Text(stringResource(R.string.settings_onboarding_desktop_pairing_subtitle), style = ui(13f), color = WelcomeInk.hex(0xA0A0A0))
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        Box(Modifier.fillMaxWidth().offset(y = 22.dp).wrapContentSize(Alignment.Center)) {
            PairDialog(
                Modifier.graphicsLayer {
                    val scale = if (reduced) 1f else 0.96f + 0.04f * pairing
                    scaleX = scale
                    scaleY = scale
                },
            )
        }
    }
}

@Composable
private fun NavRow(symbol: String, title: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.size(150.dp, 26.dp).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) { OpenCodeIcon(symbol, null, size = 12.dp, tint = WelcomeInk.hex(0xA0A0A0)) }
        Text(title, style = ui(13f), color = color, maxLines = 1)
    }
}

@Composable
private fun PairDialog(modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .width(300.dp)
            .background(WelcomeInk.hex(0x1C1C1C), shape)
            .border(0.5.dp, Color.White.copy(alpha = 0.1f), shape)
            .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_onboarding_desktop_pair_device), style = ui(15f, FontWeight.Medium), color = deskText)
            Spacer(Modifier.weight(1f))
            OpenCodeIcon("xmark", null, size = 10.dp, tint = WelcomeInk.hex(0x9A9A9A))
        }
        Text(
            stringResource(R.string.settings_onboarding_desktop_pair_device_message),
            style = ui(13f).copy(lineHeight = 19.sp),
            color = WelcomeInk.hex(0xA8A8A8),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Box(Modifier.padding(top = 14.dp).size(210.dp, 200.dp).background(WelcomeInk.hex(0x141414), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            WelcomeQRCode(Modifier.size(146.dp))
        }
        Row(
            Modifier.padding(top = 12.dp).height(28.dp).background(WelcomeInk.hex(0x2B2B2B), RoundedCornerShape(7.dp)).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenCodeIcon("square.on.square", null, size = 11.dp, tint = deskText)
            Text(stringResource(R.string.settings_onboarding_desktop_copy_link), style = ui(13f), color = deskText)
        }
        Text(stringResource(R.string.settings_onboarding_desktop_pair_expires), style = ui(13f), color = WelcomeInk.hex(0xA8A8A8), modifier = Modifier.padding(top = 8.dp))
    }
}
