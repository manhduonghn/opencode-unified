package ai.opencode.core.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder

/**
 * A provider's logo, tinted like text. Decorative. [canonical] stands in when its own ID has no
 * icon.
 */
@Composable
fun ProviderIcon(providerID: String, modifier: Modifier = Modifier, canonical: String? = null, tint: Color = Color.Unspecified) {
    val name = remember(providerID, canonical) { ProviderIcon.assetName(providerID, canonical) }
    TemplateAssetImage(name, modifier, tint)
}

object ProviderIcon {
    /**
     * The provider's icon, else its canonical's, else a Console connection's upstream, else the
     * placeholder. Returns `provider-<id>`.
     */
    fun assetName(providerID: String, canonical: String?): String {
        val candidates = listOfNotNull(providerID, canonical, if (providerID.startsWith("console-")) providerID.drop(8) else null)
        return candidates.map { "provider-$it" }.firstOrNull(BrandAssets::exists) ?: "provider-synthetic"
    }

    /** The icons in `assets/provider-icons`. */
    val available: Set<String> = setOf(
        "302ai", "abacus", "aihubmix", "alibaba-cn", "alibaba", "amazon-bedrock", "anthropic", "azure-cognitive-services", "azure",
        "bailing", "baseten", "berget", "cerebras", "chutes", "cloudferro-sherlock", "cloudflare-ai-gateway", "cloudflare-workers-ai",
        "cohere", "cortecs", "deepinfra", "deepseek", "digitalocean", "evroc", "fastrouter", "fireworks-ai", "firmware", "friendli",
        "github-copilot", "github-models", "gitlab", "google-vertex-anthropic", "google-vertex", "google", "groq", "helicone",
        "huggingface", "iflowcn", "inception", "inference", "io-net", "jiekou", "kilo", "kimi-for-coding", "kuae-cloud-coding-plan",
        "llama", "lmstudio", "lucidquery", "meganova", "minimax-cn-coding-plan", "minimax-cn", "minimax-coding-plan", "minimax",
        "mistral", "moark", "modelscope", "moonshotai-cn", "moonshotai", "morph", "nano-gpt", "nebius", "nova", "novita-ai", "nvidia",
        "ollama-cloud", "openai", "opencode-go", "opencode", "openrouter", "ovhcloud", "perplexity", "poe", "privatemode-ai",
        "qihang-ai", "qiniu-ai", "requesty", "sap-ai-core", "scaleway", "siliconflow-cn", "siliconflow", "stackit", "stepfun",
        "submodel", "synthetic", "togetherai", "upstage", "v0", "venice", "vercel", "vivgrid", "vultr", "wandb", "xai", "xiaomi",
        "zai-coding-plan", "zai", "zenmux", "zhipuai-coding-plan", "zhipuai",
    )
}

/**
 * Bundled provider and MCP artwork, by asset name, under `assets/provider-icons` and
 * `assets/mcp-icons`.
 */
object BrandAssets {
    /** The `mcp-*` marks in `assets/mcp-icons`. */
    val mcp: Set<String> = setOf(
        "atlassian", "cloudflare", "docker", "figma", "github", "googlechrome", "linear", "notion", "playwright",
        "postgresql", "sentry", "slack", "stripe", "supabase",
    )

    fun exists(asset: String): Boolean = path(asset) != null

    /** The `file:///android_asset/…` URI for an asset name, or null when none is bundled. */
    fun path(asset: String): String? = when {
        asset.startsWith("provider-") -> asset.removePrefix("provider-").takeIf { it in ProviderIcon.available }
            ?.let { "file:///android_asset/provider-icons/$it.svg" }
        asset.startsWith("mcp-") -> asset.removePrefix("mcp-").takeIf { it in mcp }
            ?.let { "file:///android_asset/mcp-icons/$it.svg" }
        else -> null
    }
}

/**
 * A bundled asset drawn as a template image: its shape in [tint], or [LocalContentColor] when
 * unspecified. Decorative.
 */
@Composable
fun TemplateAssetImage(asset: String, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {
    val context = LocalContext.current
    val color = if (tint.isSpecified) tint else LocalContentColor.current
    val request = remember(asset) {
        ImageRequest.Builder(context)
            .data(BrandAssets.path(asset))
            .decoderFactory(SvgDecoder.Factory())
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier.clearAndSetSemantics {},
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(color),
    )
}
