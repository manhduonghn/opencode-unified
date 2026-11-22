package ai.opencode.feature.voice

import ai.opencode.core.R as CoreR
import ai.opencode.core.tuning.Tunable
import ai.opencode.core.tuning.TuningStore
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Voice Provider's sizes (Settings › Voice, and a failed call's card). */
object VoiceProviderTunables {
    val rowTile = Tunable("settings.voiceProvider.rowTile", "Provider row tile", default = 52.0, range = 36.0..72.0, step = 1.0)
    val headerTile = Tunable("settings.voiceProvider.headerTile", "Provider page tile", default = 72.0, range = 48.0..96.0, step = 1.0)
    val listTile = Tunable("settings.voiceProvider.listTile", "Provider list tile", default = 36.0, range = 24.0..48.0, step = 1.0)
    val cornerRatio = Tunable("settings.voiceProvider.cornerRatio", "Tile corner ratio", default = 0.27, range = 0.15..0.5, step = 0.01)
    val symbolRatio = Tunable("settings.voiceProvider.symbolRatio", "Tile symbol ratio", default = 0.44, range = 0.3..0.6, step = 0.01)
    val unavailableOpacity = Tunable("settings.voiceProvider.unavailableOpacity", "Unavailable tile opacity", default = 0.6, range = 0.3..1.0, step = 0.05)
    val failureTile = Tunable("voice.failure.tile", "Failure tile", default = 40.0, range = 28.0..56.0, step = 1.0)
    val failureBadge = Tunable("voice.failure.badge", "Failure badge", default = 18.0, range = 12.0..26.0, step = 1.0)
    val failureBadgeOffset = Tunable("voice.failure.badgeOffset", "Failure badge offset", default = 5.0, range = 0.0..10.0, step = 0.5)
    val failureCorner = Tunable("voice.failure.corner", "Failure card corner", default = 22.0, range = 12.0..32.0, step = 1.0)
    val connectingTile = Tunable("voice.connecting.tile", "Connecting tile", default = 18.0, range = 12.0..28.0, step = 1.0)

    fun register(store: TuningStore = TuningStore.shared) {
        store.register(rowTile, headerTile, listTile, cornerRatio, symbolRatio)
        store.register(unavailableOpacity)
        store.register(failureTile, failureBadge, failureBadgeOffset, failureCorner, connectingTile)
    }
}

/**
 * A logo-free tile: a waveform for OpenAI, a person for your own account, code brackets for
 * OpenCode, a sparkle for Gemini, the device for a server that can't run calls.
 */
sealed interface VoiceProviderTileKind {
    data object openAI : VoiceProviderTileKind
    data object openAIAccount : VoiceProviderTileKind
    data object openCode : VoiceProviderTileKind
    data object gemini : VoiceProviderTileKind
    data class device(val deviceSymbol: String) : VoiceProviderTileKind

    val symbol: String
        get() = when (this) {
            openAI -> "waveform"
            openAIAccount -> "person.fill"
            openCode -> "chevron.left.forwardslash.chevron.right"
            gemini -> "sparkle"
            is device -> deviceSymbol
        }

    /** Top to bottom. */
    val colors: List<Color>
        get() = when (this) {
            openAI, openAIAccount -> listOf(Color(0.13f, 0.72f, 0.56f), Color(0.04f, 0.52f, 0.42f))
            openCode -> listOf(white(0.32f), white(0.12f))
            gemini -> listOf(Color(0.36f, 0.47f, 1f), Color(0.52f, 0.3f, 0.93f))
            is device -> listOf(white(0.62f), white(0.5f))
        }

    companion object {
        /** Nil is a server that can't say who'd run its calls. */
        fun of(brand: VoiceProviderOption.Brand?): VoiceProviderTileKind = when (brand) {
            VoiceProviderOption.Brand.openAI -> openAI
            VoiceProviderOption.Brand.openAIAccount -> openAIAccount
            VoiceProviderOption.Brand.openCode -> openCode
            VoiceProviderOption.Brand.gemini -> gemini
            null -> device("desktopcomputer")
        }

        /** This phone is always your own account. */
        fun of(option: VoiceProviderOption): VoiceProviderTileKind =
            if (option.place == VoiceProviderOption.Place.Phone) openAIAccount else of(option.brand)

        private fun white(value: Float) = Color(value, value, value)
    }
}

@Composable
fun VoiceProviderTile(kind: VoiceProviderTileKind, modifier: Modifier = Modifier, size: Double = 36.0) {
    SideEffect { VoiceProviderTunables.register() }
    val shape = RoundedCornerShape((size * VoiceProviderTunables.cornerRatio.value).dp)
    Box(
        modifier
            .size(size.dp)
            .background(Brush.verticalGradient(kind.colors), shape)
            .border(0.5.dp, Color.White.copy(alpha = 0.12f), shape)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        OpenCodeIcon(kind.symbol, null, size = (size * VoiceProviderTunables.symbolRatio.value).dp, tint = Color.White)
    }
}

@Composable
fun VoiceProviderTile(brand: VoiceProviderOption.Brand?, modifier: Modifier = Modifier, size: Double = 36.0) =
    VoiceProviderTile(VoiceProviderTileKind.of(brand), modifier, size)

@Composable
fun VoiceProviderTile(option: VoiceProviderOption, modifier: Modifier = Modifier, size: Double = 36.0) =
    VoiceProviderTile(VoiceProviderTileKind.of(option), modifier, size)

/**
 * A provider's state as a symbol and a word, so it never rests on colour alone. Nothing while
 * the state isn't known.
 */
@Composable
fun VoiceProviderStatus(state: VoiceProviderState, modifier: Modifier = Modifier, prominent: Boolean = false) {
    val label = state.label ?: return
    val symbol = state.symbol ?: return
    val color = when (state) {
        VoiceProviderState.ready -> VoiceProviderColors.green
        VoiceProviderState.noKey -> OpenCodeTheme.colors.warning
        VoiceProviderState.needsUpdate -> VoiceProviderColors.blue
        VoiceProviderState.offline, VoiceProviderState.unknown -> OpenCodeTheme.colors.secondaryText
    }
    if (prominent) {
        Row(
            modifier
                .background(color.copy(alpha = 0.14f), CircleShape)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenCodeIcon(symbol, null, size = 15.dp, tint = color)
            Text(label, style = OpenCodeTextStyles.footnote.copy(fontWeight = FontWeight.SemiBold), color = color)
        }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            OpenCodeIcon(symbol, null, size = 14.dp, tint = color)
            Text(label, color = OpenCodeTheme.colors.secondaryText)
        }
    }
}

/**
 * Above the call bar while a call connects through a server: who runs it and where, “Connecting via
 * My Mac…”. A small control-shaped panel on the call's surface with its hairline, like the failure
 * and ended cards in the same slot: it floats over the transcript, where bare text ran into the
 * messages behind it.
 */
@Composable
fun VoiceConnectingCaption(place: VoiceCallPlace, text: String, modifier: Modifier = Modifier) {
    SideEffect { VoiceProviderTunables.register() }
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .callSurface(OpenCodeShape.control, VoiceCallSurfaceDefaults.color, VoiceCallSurfaceDefaults.border)
                .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
                .semantics(mergeDescendants = true) {}
                .testTag("voice-connecting-caption"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VoiceProviderTile(place.brand, size = VoiceProviderTunables.connectingTile.value)
            Text(
                text,
                style = OpenCodeTextStyles.footnote,
                color = OpenCodeTheme.colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A failed call's card: the provider that couldn't start it, badged, one sentence, and the one button that fixes it.
 *
 * @param brand who ran the call; null on this phone, or a server that couldn't say.
 */
@Composable
fun VoiceProviderFailureCard(
    diagnosis: VoiceCallDiagnosis,
    brand: VoiceProviderOption.Brand?,
    onFix: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SideEffect { VoiceProviderTunables.register() }
    val badge = VoiceProviderTunables.failureBadge.value
    val offset = VoiceProviderTunables.failureBadgeOffset.value
    val tileSize = VoiceProviderTunables.failureTile.value
    val kind = if (diagnosis.place == null) VoiceProviderTileKind.openAIAccount else VoiceProviderTileKind.of(brand)
    val shape = RoundedCornerShape(VoiceProviderTunables.failureCorner.value.dp)
    Column(
        modifier
            .fillMaxWidth()
            .callSurface(shape, VoiceCallSurfaceDefaults.color, VoiceCallSurfaceDefaults.border)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box {
                VoiceProviderTile(kind, size = tileSize)
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(offset.dp, offset.dp)
                        .size(badge.dp)
                        .background(OpenCodeTheme.colors.warning, CircleShape)
                        .border(2.dp, OpenCodeTheme.colors.canvas, CircleShape)
                        .clearAndSetSemantics {},
                    contentAlignment = Alignment.Center,
                ) {
                    OpenCodeIcon(SFSymbol.Exclamationmark, null, size = (badge * 0.7).dp, tint = Color.White)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.voice_failure_start_title),
                    style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
                )
                SelectionContainer {
                    Text(
                        diagnosis.sentence,
                        style = OpenCodeTextStyles.subheadline,
                        color = OpenCodeTheme.colors.secondaryText,
                        modifier = Modifier.testTag("voice-failure-sentence"),
                    )
                }
            }
            VoiceFailureCloseButton(onClose)
        }
        OpenCodePrimaryButton(
            diagnosis.fix.title,
            onClick = onFix,
            modifier = Modifier.heightIn(min = 44.dp).testTag("voice-failure-fix"),
        )
    }
}

/** A failed call's ×: Close, as the bar's End. */
@Composable
fun VoiceFailureCloseButton(action: () -> Unit, modifier: Modifier = Modifier) {
    val closeDescription = stringResource(CoreR.string.common_close)
    Box(
        modifier
            // The target reaches into the card's corner without making its row taller.
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val trailing = 10.dp.roundToPx()
                val vertical = 12.dp.roundToPx()
                layout((placeable.width - trailing).coerceAtLeast(0), (placeable.height - 2 * vertical).coerceAtLeast(0)) {
                    placeable.placeRelative(0, -vertical)
                }
            }
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = action)
            .openCodeTapTarget()
            .semantics { contentDescription = closeDescription; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        OpenCodeIcon(SFSymbol.Xmark, null, tint = OpenCodeTheme.colors.secondaryText, size = 18.dp)
    }
}

/** System tints the provider status uses besides orange. */
private object VoiceProviderColors {
    /** `UIColor.systemGreen`: Ready. */
    val green: Color
        @Composable @ReadOnlyComposable get() =
            if (OpenCodeTheme.colors.isDark) Color(0.188f, 0.82f, 0.345f) else Color(0.204f, 0.78f, 0.349f)

    /** `UIColor.systemBlue`: Update OpenCode. */
    val blue: Color
        @Composable @ReadOnlyComposable get() =
            if (OpenCodeTheme.colors.isDark) Color(0.039f, 0.518f, 1f) else Color(0f, 0.478f, 1f)
}
