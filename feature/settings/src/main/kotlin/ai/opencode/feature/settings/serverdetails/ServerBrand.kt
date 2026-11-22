package ai.opencode.feature.settings.serverdetails

import ai.opencode.core.motion.AmbientTimeline
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.BrandAssets
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.TemplateAssetImage
import ai.opencode.feature.settings.SettingsTint
import ai.opencode.server.model.OpenCodeAccount
import ai.opencode.server.model.OpenCodeMCPServer
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * How an MCP server or provider account looks in Server Details: its brand mark on its brand
 * colour, or a monogram tile when the app doesn't know it. The marks are bundled SVGs in `:core`
 * assets ([BrandAssets]).
 */
data class ServerBrand(
    val name: String,
    val asset: String?,
    val fill: Long,
    val glyph: Long,
    val monogram: String,
) {
    private data class Known(
        val matches: List<String>,
        val name: String,
        val asset: String,
        val fill: Long,
        val glyph: Long = 0xFFFFFF,
    )

    companion object {
        private val knownMCP = listOf(
            Known(listOf("github"), "GitHub", "mcp-github", 0x2B3137),
            Known(listOf("linear"), "Linear", "mcp-linear", 0x5E6AD2),
            Known(listOf("sentry"), "Sentry", "mcp-sentry", 0x40346B),
            Known(listOf("figma"), "Figma", "mcp-figma", 0xF24E1E),
            Known(listOf("notion"), "Notion", "mcp-notion", 0xF4F4F4, glyph = 0x111111),
            Known(listOf("slack"), "Slack", "mcp-slack", 0x4A154B),
            Known(listOf("stripe"), "Stripe", "mcp-stripe", 0x635BFF),
            Known(listOf("supabase"), "Supabase", "mcp-supabase", 0x1C1C1C, glyph = 0x3ECF8E),
            Known(listOf("cloudflare"), "Cloudflare", "mcp-cloudflare", 0xF38020),
            Known(listOf("atlassian", "jira", "confluence"), "Atlassian", "mcp-atlassian", 0x0052CC),
            Known(listOf("postgres"), "Postgres", "mcp-postgresql", 0x336791),
            Known(listOf("docker"), "Docker", "mcp-docker", 0x2496ED),
            Known(listOf("chrome"), "Chrome DevTools", "mcp-googlechrome", 0x1A73E8),
            Known(listOf("playwright"), "Playwright", "mcp-playwright", 0x2EAD33),
        )

        /** Provider fills for `provider-<integrationID>` marks; others sit on a neutral tile. */
        private val providerFills: Map<String, Pair<Long, Long>> = mapOf(
            "openai" to (0xFFFFFFL to 0x0D0D0DL),
            "anthropic" to (0xD97757L to 0x1D1007L),
            "opencode" to (0xF4F4F5L to 0x121212L),
            "opencode-go" to (0xF4F4F5L to 0x121212L),
            "github-copilot" to (0x2B3137L to 0xFFFFFFL),
            "google" to (0xFFFFFFL to 0x1A73E8L),
            "xai" to (0x111111L to 0xFFFFFFL),
            "openrouter" to (0x6467F2L to 0xFFFFFFL),
        )

        /** Muted tile colours for monograms, picked by a stable hash of the name. */
        private val monogramFills: List<Long> = listOf(0x46526A, 0x4F5D4A, 0x6A4A5E, 0x4A5F6A, 0x6A5A46, 0x584A6A)

        /** The mark is bundled. */
        private val bundledAssets: (String) -> Boolean = BrandAssets::exists

        fun mcp(name: String, assetExists: (String) -> Boolean = bundledAssets): ServerBrand {
            val lower = name.lowercase()
            val known = knownMCP.firstOrNull { k -> k.matches.any { lower.contains(it) } }
            if (known != null && assetExists(known.asset)) {
                return ServerBrand(known.name, known.asset, known.fill, known.glyph, monogram(name))
            }
            return monogramBrand(name)
        }

        fun account(account: OpenCodeAccount, assetExists: (String) -> Boolean = bundledAssets): ServerBrand {
            val asset = "provider-${account.integrationID}"
            if (!assetExists(asset)) return monogramBrand(account.provider)
            val (fill, glyph) = providerFills[account.integrationID] ?: (0x3A3A3CL to 0xFFFFFFL)
            return ServerBrand(account.provider, asset, fill, glyph, monogram(account.provider))
        }

        fun monogramBrand(name: String): ServerBrand {
            // Djb2 over UTF-8 bytes with UInt32 wrapping.
            var hash = 5381u
            for (byte in name.toByteArray(Charsets.UTF_8)) hash = hash * 33u + byte.toUByte().toUInt()
            val fill = monogramFills[(hash % monogramFills.size.toUInt()).toInt()]
            return ServerBrand(name, null, fill, 0xFFFFFF, monogram(name))
        }

        /** “browser-control” → “BC”, “exa” → “Ex”. */
        fun monogram(name: String): String {
            val words = name.split('-', '_', '.', ' ', '/').filter { it.isNotEmpty() }
            if (words.size >= 2) return words.take(2).joinToString("") { it.first().toString().uppercase() }
            val word = words.firstOrNull() ?: return "?"
            return word.take(1).uppercase() + word.drop(1).take(1).lowercase()
        }
    }
}

fun serverBrandColor(hex: Long): Color = Color(0xFF000000 or (hex and 0xFFFFFF))

/** A brand mark on its tile. Decorative: rows name the server themselves. */
@Composable
fun BrandTile(
    brand: ServerBrand,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    round: Boolean = false,
    muted: Boolean = false,
) {
    val isDark = OpenCodeTheme.colors.isDark
    // White and near-white brand fills vanish on a light card: sit them on the system fill
    // there instead, with a firmer rim.
    val r = ((brand.fill shr 16) and 0xFF).toDouble()
    val g = ((brand.fill shr 8) and 0xFF).toDouble()
    val b = (brand.fill and 0xFF).toDouble()
    val isPale = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255 > 0.9
    val shape = if (round) CircleShape else RoundedCornerShape(size * 0.28f)
    // Every colour drawn goes grey.
    fun Color.muted(): Color = if (muted) desaturated() else this
    val fill = serverBrandColor(brand.fill)
    val background = if (isPale && !isDark) {
        Brush.verticalGradient(listOf(SystemGray5Light, SystemGray5Light))
    } else {
        // A soft top-lit vertical gradient of the colour.
        Brush.verticalGradient(listOf(fill.lighten(0.08f).muted(), fill.darken(0.08f).muted()))
    }
    val glyph = serverBrandColor(brand.glyph).muted()
    val rimAlpha = if (isPale && !isDark) 0.16f else 0.1f
    val rimColor = if (isDark) Color.White else Color.Black
    Box(
        modifier
            .size(size)
            .clearAndSetSemantics {}
            .graphicsLayer { alpha = if (muted) 0.5f else 1f }
            .background(background, shape)
            .border(0.5.dp, rimColor.copy(alpha = rimAlpha), shape),
        contentAlignment = Alignment.Center,
    ) {
        val asset = brand.asset
        if (asset != null) {
            TemplateAssetImage(asset, Modifier.size(size * 0.5f), tint = glyph)
        } else {
            Text(
                brand.monogram,
                color = glyph,
                fontSize = (size.value * 0.38f).sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

private val SystemGray5Light = Color(0xFFE5E5EA)

private fun Color.lighten(amount: Float) = Color(
    red + (1 - red) * amount, green + (1 - green) * amount, blue + (1 - blue) * amount, alpha,
)

/** The colour's Rec. 709 luminance as grey. */
private fun Color.desaturated(): Color {
    val luma = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    return Color(luma, luma, luma, alpha)
}

private fun Color.darken(amount: Float) = Color(red * (1 - amount), green * (1 - amount), blue * (1 - amount), alpha)

val OpenCodeMCPServer.Status.title: String
    get() = when (this) {
        OpenCodeMCPServer.Status.Connected -> "Connected"
        OpenCodeMCPServer.Status.Pending -> "Connecting…"
        OpenCodeMCPServer.Status.Disabled -> "Off"
        is OpenCodeMCPServer.Status.Failed -> "Couldn’t connect"
        is OpenCodeMCPServer.Status.NeedsAuth -> "Needs sign-in"
        is OpenCodeMCPServer.Status.Unknown -> raw.capitalizedWords()
    }

/** Each word's first letter upper case, the rest lower case. */
private fun String.capitalizedWords(): String {
    val out = StringBuilder(length)
    var startOfWord = true
    for (ch in this) {
        if (ch.isLetterOrDigit()) {
            out.append(if (startOfWord) ch.titlecase(Locale.getDefault()) else ch.lowercase(Locale.getDefault()))
            startOfWord = false
        } else {
            out.append(ch)
            startOfWord = true
        }
    }
    return out.toString()
}

/** Null is the secondary text colour. */
val OpenCodeMCPServer.Status.tint: SettingsTint?
    get() = when (this) {
        OpenCodeMCPServer.Status.Connected -> SettingsTint.Green
        OpenCodeMCPServer.Status.Pending -> SettingsTint.Blue
        is OpenCodeMCPServer.Status.NeedsAuth -> SettingsTint.Orange
        is OpenCodeMCPServer.Status.Failed -> SettingsTint.Red
        OpenCodeMCPServer.Status.Disabled, is OpenCodeMCPServer.Status.Unknown -> null
    }

@Composable
fun OpenCodeMCPServer.Status.tintColor(): Color = tint?.color() ?: OpenCodeTheme.colors.secondaryText

val OpenCodeMCPServer.errorText: String?
    get() = when (val s = status) {
        is OpenCodeMCPServer.Status.Failed -> s.error.ifEmpty { null }
        is OpenCodeMCPServer.Status.NeedsAuth -> s.error.ifEmpty { null }
        else -> null
    }

/** The error's first line without its transport preamble, for a banner. */
val OpenCodeMCPServer.shortError: String?
    get() = errorText?.split('\n')?.firstOrNull { it.isNotEmpty() }?.replace("Connection closed: ", "")

/**
 * A brand tile inside a ring that carries the server's status: green when connected, dashed
 * orange when it needs sign-in, red when it failed, a travelling arc while connecting, and a
 * faint rim when off.
 */
@Composable
fun StatusRingTile(
    brand: ServerBrand,
    status: OpenCodeMCPServer.Status,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
) {
    val reduceMotion = rememberReduceMotion()
    val isOff = status == OpenCodeMCPServer.Status.Disabled
    val outer = size + 8.dp
    val cornerDp = size * 0.28f + 4.dp
    val target = when (status) {
        OpenCodeMCPServer.Status.Connected -> SettingsTint.Green.color()
        is OpenCodeMCPServer.Status.NeedsAuth -> SettingsTint.Orange.color()
        is OpenCodeMCPServer.Status.Failed -> SettingsTint.Red.color()
        OpenCodeMCPServer.Status.Pending -> SettingsTint.Blue.color()
        else -> OpenCodeTheme.colors.secondaryText.copy(alpha = 0.18f)
    }
    val ringColor by animateColorAsState(target, MotionRole.Swap.animation(reduceMotion = reduceMotion), label = "ring")
    val pending = status == OpenCodeMCPServer.Status.Pending
    val travelling = pending && !reduceMotion
    val dash = when {
        status is OpenCodeMCPServer.Status.NeedsAuth -> floatArrayOf(4f, 3f)
        pending && reduceMotion -> floatArrayOf(3f, 3f)
        else -> null
    }
    // A small spinner on the shared ambient clock: one turn every half of the shared sweep.
    AmbientTimeline(isActive = travelling, reduceMotion = reduceMotion) { sample ->
        val turn = if (travelling) ((sample.sweep * 2) % 1.0).toFloat() else 0f
        Box(
            modifier
                .size(outer)
                .drawWithContent {
                    drawContent()
                    val width = 2.dp.toPx()
                    val inset = width / 2
                    val corner = CornerRadius(cornerDp.toPx() - inset)
                    val topLeft = Offset(inset, inset)
                    val ringSize = Size(this.size.width - width, this.size.height - width)
                    if (travelling) {
                        drawRoundRect(Brush.sweepGradient(*travellingArc(ringColor, turn)), topLeft, ringSize, corner, style = Stroke(width))
                    } else {
                        val effect = dash?.let { d -> PathEffect.dashPathEffect(FloatArray(d.size) { d[it] * density }) }
                        drawRoundRect(ringColor, topLeft, ringSize, corner, style = Stroke(width, pathEffect = effect))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            BrandTile(brand, size = size, muted = isOff)
        }
    }
}

private const val ArcSamples = 48

/** Sampled stops, since a sweep brush can't be rotated without turning the ring with it. */
private fun travellingArc(color: Color, turn: Float): Array<Pair<Float, Color>> = Array(ArcSamples + 1) { i ->
    val position = i / ArcSamples.toFloat()
    val base = ((position - turn) % 1f + 1f) % 1f
    val alpha = if (base <= 0.6f) 0f else (base - 0.6f) / 0.4f
    position to color.copy(alpha = alpha * color.alpha)
}
