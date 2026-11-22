package ai.opencode.feature.settings.onboarding

import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.feature.settings.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private fun body(weight: FontWeight = FontWeight.Normal) = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = weight)

private val phoneText = WelcomeInk.hex(0xEDEDED)
private val phoneSecondary = WelcomeInk.label2
private val phoneTertiary = WelcomeInk.label3
/** The app's dark canvas, composer surface, bubble fill and hairline (the desktop palette, as the session draws them). */
private val phoneCanvas = WelcomeInk.hex(0x161616)
private val phoneSurface = WelcomeInk.hex(0x1C1C1C)
private val phoneSurfaceHigh = WelcomeInk.hex(0x2E2E2E)
private val phoneHairline = WelcomeInk.hex(0x2D2D2D)

/**
 * The phone's screen: this app's session in dark mode, at a logical 440 × 956 scaled into the
 * phone. The real transcript rows and scope bar live in modules above Settings, so the rows, header
 * and composer are drawn from the app's metrics. Pairing cross-fades to the scanner.
 */
@Composable
internal fun WelcomePhone(frame: WelcomeFrame, pairing: Float, reduced: Boolean, voice: WelcomeVoice, wall: Double, targets: WelcomeTargets?) {
    Box(Modifier.requiredSize(WelcomePhone.size.width.dp, WelcomePhone.size.height.dp).background(phoneCanvas)) {
        Box(Modifier.fillMaxSize().alpha(1 - pairing).welcomeBlur(if (reduced) 0f else 3f * pairing)) {
            Box(Modifier.fillMaxSize().alpha(frame.transcript.toFloat()).welcomeBlur(if (reduced || frame.loopBlur < 0.05) 0f else frame.loopBlur.toFloat())) {
                PhoneTranscript(frame, reduced, wall, targets)
            }
            PhoneHeader(frame)
            PhoneComposer(frame, reduced, voice, targets)
        }
        PhoneScanner(
            Modifier
                .graphicsLayer {
                    alpha = pairing
                    val scale = if (reduced) 1f else 1.04f - 0.04f * pairing
                    scaleX = scale
                    scaleY = scale
                }
                .welcomeBlur(if (reduced) 0f else 6f * (1 - pairing)),
        )
        PhoneStatusBar()
        Box(
            Modifier
                .offset(x = ((WelcomePhone.size.width - 108) / 2).dp, y = (WelcomePhone.size.height - 12).dp)
                .size(108.dp, 4.dp)
                .background(Color.White.copy(alpha = 0.8f), CircleShape),
        )
    }
}

internal object WelcomePhone {
    /** The phone's logical screen. */
    val size = Size(440f, 956f)

    /** The composer's bottom edge, above the home indicator. */
    const val COMPOSER_BOTTOM = 922f
    const val HEADER_TOP = 69f

    /** Where the transcript's rows start and end. */
    const val COLUMN_TOP = 122f
    const val COLUMN_BOTTOM = 858f

    /** The transcript's gutter and half flow gap. */
    const val GUTTER = 16f
    const val FLOW_HALF = 8f

    /** The screenshot tile's corners: the desktop card's 10. */
    const val TILE_RADIUS = 10f

    /** The session header's buttons. */
    const val BUTTON = 44f

    /** The composer's metrics. */
    const val MARGIN = 20f
    const val INNER = 6f
    const val INNER_VERTICAL = 6f
    const val TEXT_LEADING = 52f
    const val EXPANDED_TEXT_LEADING = 10f
    /** The composer's corners: the desktop dock's 12. */
    const val CORNER = 12f
    const val SLOT = 48f

    /** The agent's calls so far: reads, the browser, the edit, the screenshot. */
    fun tools(count: Int): List<String> = listOf("read", "grep", "browser", "edit", "browser").take(max(1, count))

    /** The reply's first `words` words, whole. */
    fun reply(words: Double): String {
        val count = min(WelcomeStory.REPLY_WORDS.size, max(1, ceil(words).toInt()))
        return WelcomeStory.REPLY_WORDS.take(count).joinToString(" ").replace("`", "")
    }
}

/**
 * An Android status bar: the time on the left, a centred punch-hole camera, and Wi-Fi, signal and
 * battery on the right.
 */
@Composable
private fun PhoneStatusBar() {
    Box(Modifier.fillMaxWidth().height(44.dp)) {
        Text(
            "9:30",
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
            color = Color.White,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 28.dp),
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .size(22.dp)
                .background(Color.Black, CircleShape)
                .border(0.75.dp, Color.White.copy(alpha = 0.1f), CircleShape),
        )
        Row(
            Modifier.align(Alignment.CenterEnd).padding(end = 26.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenCodeIcon("wifi", null, size = 16.dp, tint = Color.White)
            // Cellular signal as Android draws it: a filled right triangle.
            Canvas(Modifier.size(14.dp, 14.dp)) {
                val triangle = Path()
                triangle.moveTo(size.width, 0f)
                triangle.lineTo(size.width, size.height)
                triangle.lineTo(0f, size.height)
                triangle.close()
                drawPath(triangle, Color.White)
            }
            // A full battery: a rounded body with a small cap on the right.
            Canvas(Modifier.size(24.dp, 12.dp)) {
                val cap = 2.dp.toPx()
                val body = Size(size.width - cap - 1.dp.toPx(), size.height)
                drawRoundRect(Color.White, Offset.Zero, body, androidx.compose.ui.geometry.CornerRadius(3.5.dp.toPx()))
                drawRoundRect(
                    Color.White.copy(alpha = 0.6f),
                    Offset(body.width + 1.dp.toPx(), size.height * 0.3f),
                    Size(cap, size.height * 0.4f),
                    androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()),
                )
            }
        }
    }
}

/**
 * The session header as this app draws it on the desktop palette: the drawer and More icons on the
 * canvas, the title and project beside them.
 */
@Composable
private fun PhoneHeader(frame: WelcomeFrame) {
    val size = WelcomePhone.BUTTON
    Row(
        Modifier.offset(y = WelcomePhone.HEADER_TOP.dp).fillMaxWidth().height(size.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
            OpenCodeIcon("line.3.horizontal", null, size = 22.dp, tint = phoneText)
        }
        Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.Center) {
            WelcomeStack(
                labels = listOf(stringResource(R.string.settings_onboarding_phone_new_session), WelcomeStory.TITLE),
                weights = if (frame.named) listOf(0.0, 1.0) else listOf(1.0, 0.0),
                style = TextStyle(fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
                color = phoneText,
            )
            Text(WelcomeStory.PROJECT, style = TextStyle(fontSize = 13.sp, lineHeight = 16.sp), color = phoneSecondary, maxLines = 1)
        }
        Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
            OpenCodeIcon("ellipsis", null, size = 20.dp, tint = phoneText)
        }
    }
}

@Composable
private fun PhoneTranscript(frame: WelcomeFrame, reduced: Boolean, wall: Double, targets: WelcomeTargets?) {
    val height = WelcomePhone.COLUMN_BOTTOM - WelcomePhone.COLUMN_TOP
    Box(
        Modifier
            .offset(y = WelcomePhone.COLUMN_TOP.dp)
            .requiredSize(WelcomePhone.size.width.dp, height.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // Rows scroll under the header like the app's edge.
                val fade = 24.dp.toPx()
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = -fade, endY = -fade + (size.height + fade) * 0.06f),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        WelcomeColumn(Modifier.fillMaxSize(), liftDrop = WelcomePhone.COMPOSER_BOTTOM - 74 - WelcomePhone.COLUMN_BOTTOM) {
            UserBubble(WelcomeStory.PROMPT, Modifier.alpha((frame.prompt * 3).clamped01().toFloat()).welcomeRow(frame.prompt, lift = if (reduced) 0.0 else 1 - frame.prompt))
            ToolGroup(WelcomePhone.tools(frame.toolCount), Modifier.welcomeShow(frame.tools, dy = 4f, reduced = reduced).welcomeRow(if (frame.toolCount > 0) frame.tools else 0.0))
            Box(
                Modifier
                    .padding(start = WelcomePhone.GUTTER.dp, end = WelcomePhone.GUTTER.dp, bottom = WelcomePhone.FLOW_HALF.dp)
                    .welcomeRow(frame.screenshotSlot),
            ) {
                WelcomeScreenshot.Image(
                    Modifier
                        .fillMaxWidth(0.72f)
                        .aspectRatio(WelcomeSitePage.size.width / WelcomeSitePage.size.height)
                        .welcomeTarget(WelcomeTarget.PhoneShot, targets)
                        .alpha(frame.screenshot.toFloat())
                        .clip(RoundedCornerShape(WelcomePhone.TILE_RADIUS.dp)),
                )
            }
            Text(
                WelcomePhone.reply(frame.replyWords),
                style = body(),
                color = phoneText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = WelcomePhone.GUTTER.dp, vertical = WelcomePhone.FLOW_HALF.dp)
                    .welcomeShow(frame.reply, dy = 4f, blur = 0f, reduced = reduced)
                    .welcomeRow(if (frame.replyWords > 0) frame.reply else 0.0),
            )
            Working(frame.working, wall, reduced)
            UserBubble(WelcomeStory.SHIP_IT, Modifier.alpha((frame.ship * 3).clamped01().toFloat()).welcomeRow(frame.ship, lift = if (reduced) 0.0 else 1 - frame.ship))
            Working(frame.working2, wall, reduced)
        }
    }
}

/** A message the person sent: the transcript's bubble on the right. */
@Composable
private fun UserBubble(text: String, modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = WelcomePhone.GUTTER.dp, vertical = WelcomePhone.FLOW_HALF.dp), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            style = body(),
            color = phoneText,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(phoneSurfaceHigh, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

/** The agent's calls, as the transcript's collapsed tool group reads them. */
@Composable
private fun ToolGroup(tools: List<String>, modifier: Modifier) {
    val exploredReadSearch = stringResource(R.string.settings_onboarding_phone_tool_explored_read_search)
    val exploredRead = stringResource(R.string.settings_onboarding_phone_tool_explored_read)
    val browser = stringResource(R.string.settings_onboarding_tool_browser)
    val edit = stringResource(R.string.settings_onboarding_tool_edit)
    val label = buildList {
        val reads = tools.count { it == "read" || it == "grep" }
        if (reads > 0) add(if (tools.contains("grep")) exploredReadSearch else exploredRead)
        if (tools.contains("browser")) add(browser)
        if (tools.contains("edit")) add(edit)
    }.joinToString(" · ")
    Row(
        modifier.fillMaxWidth().padding(horizontal = WelcomePhone.GUTTER.dp, vertical = WelcomePhone.FLOW_HALF.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeIcon("hammer.fill", null, size = 16.dp, tint = phoneSecondary)
        Text(label, style = TextStyle(fontSize = 15.sp), color = phoneSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The transcript's working indicator. */
@Composable
private fun Working(open: Double, wall: Double, reduced: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = WelcomePhone.GUTTER.dp, vertical = WelcomePhone.FLOW_HALF.dp)
            .alpha((open * 1.5).clamped01().toFloat())
            .welcomeRow(open),
    ) {
        WelcomeWorkingDots(wall, dot = 4f, gap = 2.5f, color = phoneSecondary, reduced = reduced)
    }
}

/**
 * The composer: idle it's + · Message · mic, with Stop while the agent works; dictating, the +
 * turns into the recording's ×, a scope of the story's voice takes the row, and the words heard so
 * far show over it.
 */
@Composable
private fun PhoneComposer(frame: WelcomeFrame, reduced: Boolean, voice: WelcomeVoice, targets: WelcomeTargets?) {
    val shipping = frame.utterance == 1
    val words = if (shipping) WelcomeStory.SHIP_WORDS else WelcomeStory.PROMPT_WORDS
    val line = 34 * frame.lines
    val running = (max(frame.working, frame.working2).clamped01() * (1 - frame.dictation)).toFloat()
    val dictation = frame.dictation.toFloat()
    val width = WelcomePhone.size.width - 2 * WelcomePhone.MARGIN
    Box(Modifier.fillMaxWidth().height(WelcomePhone.COMPOSER_BOTTOM.dp), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .width(width.dp)
                .background(phoneSurface, RoundedCornerShape(WelcomePhone.CORNER.dp))
                .border(1.dp, phoneHairline, RoundedCornerShape(WelcomePhone.CORNER.dp))
                .padding(horizontal = WelcomePhone.INNER.dp, vertical = WelcomePhone.INNER_VERTICAL.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(line.dp).alpha((frame.draft * frame.lines).toFloat()).clip(androidx.compose.ui.graphics.RectangleShape), contentAlignment = Alignment.BottomStart) {
                Text(
                    WelcomeStreamedText.attributed(words, frame.heard, phoneText, fade = 1.4),
                    style = body(),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(start = WelcomePhone.EXPANDED_TEXT_LEADING.dp),
                )
            }
            Box(Modifier.fillMaxWidth().height(WelcomePhone.SLOT.dp)) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    // The +, turned into the recording's × on its fill.
                    Box(Modifier.size(WelcomePhone.SLOT.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .background(Color.White.copy(alpha = 0.12f * dictation), RoundedCornerShape(8.dp))
                                .graphicsLayer { rotationZ = 45 * dictation },
                            contentAlignment = Alignment.Center,
                        ) { OpenCodeIcon("plus", null, size = 22.dp, tint = phoneText) }
                    }
                    Text(
                        stringResource(R.string.settings_onboarding_phone_message_placeholder),
                        style = body(),
                        color = phoneTertiary,
                        modifier = Modifier.padding(start = (WelcomePhone.TEXT_LEADING - WelcomePhone.INNER - WelcomePhone.SLOT).coerceAtLeast(0f).dp).alpha(1 - dictation),
                    )
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(WelcomePhone.SLOT.dp).alpha(1 - dictation), contentAlignment = Alignment.Center) {
                        OpenCodeIcon("mic", null, size = 24.dp, tint = phoneSecondary)
                    }
                    Box(
                        Modifier
                            .size((WelcomePhone.SLOT * running).dp, WelcomePhone.SLOT.dp)
                            .graphicsLayer {
                                alpha = running
                                val scale = if (reduced) 1f else 0.7f + 0.3f * running
                                scaleX = scale
                                scaleY = scale
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.requiredSize(34.dp).background(Color.White, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(11.dp).background(Color.Black, RoundedCornerShape(2.dp)))
                        }
                    }
                }
                if (dictation > 0.001f) {
                    WelcomeScope(
                        voice,
                        Modifier
                            .fillMaxSize()
                            .padding(start = (WelcomePhone.SLOT + 8).dp, end = 12.dp)
                            .graphicsLayer {
                                alpha = dictation
                                val scale = if (reduced) 1f else 0.96f + 0.04f * dictation
                                scaleX = scale
                                scaleY = scale
                            },
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 5.dp)
                        .size(34.dp)
                        .welcomeTarget(WelcomeTarget.PhoneSend, targets),
                )
            }
        }
    }
}

/** The dictation bar's scope, listening to the story's voice as it would the mic. */
@Composable
private fun WelcomeScope(voice: WelcomeVoice, modifier: Modifier) {
    Canvas(modifier) {
        val history = voice.levels.history
        val bar = 3.dp.toPx()
        val gap = 2.dp.toPx()
        val count = min(history.size, ((size.width + gap) / (bar + gap)).toInt())
        val mid = size.height / 2
        for (index in 0 until count) {
            val level = history[history.size - count + index]
            val h = max(bar, size.height * 0.6f * level)
            val x = size.width - (count - index) * (bar + gap) + gap
            drawLine(Color.White, Offset(x + bar / 2, mid - h / 2), Offset(x + bar / 2, mid + h / 2), bar, StrokeCap.Round)
        }
    }
}

/** The pairing step: the camera finds the laptop's code. */
@Composable
private fun PhoneScanner(modifier: Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .drawWithContent {
                drawRect(
                    Brush.radialGradient(
                        listOf(WelcomeInk.hex(0x1A1A1C), WelcomeInk.hex(0x050505)),
                        center = Offset(size.width * 0.5f, size.height * 0.45f),
                        radius = 520.dp.toPx(),
                    ),
                )
                drawContent()
            },
    ) {
        Box(Modifier.offset(16.dp, WelcomePhone.HEADER_TOP.dp).size(WelcomePhone.BUTTON.dp).background(phoneSurface, RoundedCornerShape(10.dp)).border(1.dp, phoneHairline, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            OpenCodeIcon("xmark", null, size = 18.dp, tint = phoneText)
        }
        Box(Modifier.offset(90.dp, 330.dp).size(260.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawPath(WelcomeViewfinderCorners.path(Rect(Offset.Zero, size), 12.dp.toPx()), Color.White, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            }
            Box(
                Modifier
                    .size(172.dp)
                    .graphicsLayer {
                        alpha = 0.7f
                        rotationX = 10f
                        rotationZ = -4f
                        cameraDistance = 6f * density
                    }
                    .background(WelcomeInk.hex(0x141414), RoundedCornerShape(10.dp))
                    .padding(12.dp),
            ) { WelcomeQRCode(Modifier.fillMaxSize()) }
        }
    }
}

/** A camera viewfinder's four rounded corners. */
internal object WelcomeViewfinderCorners {
    fun path(rect: Rect, radius: Float): Path {
        val arm = rect.width * 0.22f
        val path = Path()
        for (corner in 0 until 4) {
            val flipX = corner % 2 == 1
            val flipY = corner >= 2
            val x = if (flipX) rect.right else rect.left
            val y = if (flipY) rect.bottom else rect.top
            val dx = if (flipX) -1f else 1f
            val dy = if (flipY) -1f else 1f
            path.moveTo(x, y + dy * arm)
            path.lineTo(x, y + dy * radius)
            path.quadraticTo(x, y, x + dx * radius, y)
            path.lineTo(x + dx * arm, y)
        }
        return path
    }
}
