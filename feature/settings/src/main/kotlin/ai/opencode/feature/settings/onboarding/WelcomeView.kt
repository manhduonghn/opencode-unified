package ai.opencode.feature.settings.onboarding

import ai.opencode.core.R as CoreR
import ai.opencode.core.ui.OpenCodeErrorText
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeSheetHandle
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import ai.opencode.core.ui.openCodeHairline
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.StoreOf
import ai.opencode.core.strings.localized
import ai.opencode.core.store.observe
import ai.opencode.core.tuning.TuningContext
import ai.opencode.core.tuning.TuningContextEffect
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.settings.R
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import ai.opencode.core.ui.OpenCodeFonts
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

/** The welcome's words, in one place so the headline is easy to swap. */
object WelcomeCopy {
    val HEADLINE: String get() = localized(R.string.settings_onboarding_headline)
    val HEADLINE_SPOKEN: String get() = localized(R.string.settings_onboarding_headline_spoken)
    val SUBHEAD: String get() = localized(R.string.settings_onboarding_subhead)
    val INSTRUCTION: String get() = localized(R.string.settings_onboarding_instruction)
    const val PAIR_COMMAND = "opencode pair --remote"
    const val INSTALL_COMMAND = "curl -fsSL https://opencode.ai/v2/install | bash"
}

/**
 * The first-run welcome: the mark, the headline and the laptop-and-phone scene, then one pairing
 * step. Values from `AppFeature` come in as props: [progress] while a pairing runs, [problem] when
 * it failed. [mark] is the app mark, which lives in `:app`'s resources. Close (preview only) calls
 * [onClose] after sending `CloseTapped`, so the presenter dismisses it.
 */
@Composable
fun WelcomeView(
    store: StoreOf<WelcomeFeature.State, WelcomeFeature.Action>,
    progress: String?,
    problem: String?,
    mark: Painter?,
    onClose: () -> Unit = {},
) {
    val state by store.observe { it }
    val reduced = rememberReduceMotion()
    val pairing = state.step == WelcomeFeature.Step.pair
    var appeared by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        WelcomeTunables.register()
        appeared = true
    }
    TuningContextEffect(TuningContext.welcome)
    val entrance by animateFloatAsState(
        if (appeared) 1f else 0f,
        animationSpec = WelcomeTunables.intro.animation(reduced),
        label = "welcome-entrance",
    )
    val accessibilitySize = LocalDensity.current.fontScale >= 1.5f

    Column(
        Modifier
            .fillMaxSize()
            // Show Welcome lays it over Settings and Home, which must not show through.
            .background(OpenCodeTheme.colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // The actions sit below the scroll view, so the content centres in the space it can be seen in.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewport = maxHeight
            val sceneWidth = min(maxWidth.value, 520f) - 24f
            val share = if (accessibilitySize) 0.32f else 0.46f
            val layout = WelcomeSceneLayout(width = sceneWidth, maxHeight = max(120f, viewport.value * share))
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = viewport),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Header(
                    pairing = pairing,
                    isPreview = state.isPreview,
                    onBack = { store.send(WelcomeFeature.Action.BackTapped) },
                    onClose = {
                        store.send(WelcomeFeature.Action.CloseTapped)
                        onClose()
                    },
                )
                Box(
                    Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                        .heightIn(min = viewport - 52.dp)
                        .padding(top = 12.dp, bottom = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(if (accessibilitySize) 20.dp else 28.dp),
                    ) {
                        if (mark != null) {
                            Image(
                                mark,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp).welcomeEntrance(entrance, reduced, scale = 0.88f),
                            )
                        }
                        Copy(pairing, problem, reduced, Modifier.welcomeEntrance(entrance, reduced, dy = 10.dp))
                        WelcomeScene(layout, pairing)
                    }
                }
            }
        }
        Actions(
            pairing = pairing,
            progress = progress,
            reduced = reduced,
            modifier = Modifier.welcomeEntrance(entrance, reduced, dy = 14.dp),
            onPrimary = { store.send(if (pairing) WelcomeFeature.Action.ScanTapped else WelcomeFeature.Action.GetStartedTapped) },
            onOtherWays = { store.send(WelcomeFeature.Action.OtherWaysTapped) },
        )
    }

    if (state.isShowingOtherWays) {
        WelcomeOtherWays(
            pasteError = state.pasteError,
            send = store::send,
            onDismiss = {
                store.send(WelcomeFeature.Action.OtherWaysShownChanged(false))
                store.send(WelcomeFeature.Action.OtherWaysDismissed)
            },
        )
    }
}

@Composable
private fun Header(pairing: Boolean, isPreview: Boolean, onBack: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pairing) {
            OpenCodeIconButton(SFSymbol.ChevronBackward, contentDescription = stringResource(CoreR.string.common_back), onClick = onBack, style = OpenCodeIconButtonStyle.Standard)
        }
        Spacer(Modifier.weight(1f))
        if (isPreview) {
            OpenCodeIconButton(SFSymbol.Xmark, contentDescription = stringResource(CoreR.string.common_close), onClick = onClose, modifier = Modifier.semantics { testTag = "welcome-close" }, style = OpenCodeIconButtonStyle.Standard)
        }
    }
}

/** The headline and subhead, or the pairing step's one line, in one place. */
@Composable
private fun Copy(pairing: Boolean, problem: String?, reduced: Boolean, modifier: Modifier) {
    Box(modifier.padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.welcomeSwap(!pairing, reduced),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                WelcomeCopy.HEADLINE,
                style = OpenCodeTextStyles.largeTitle.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = WelcomeCopy.HEADLINE_SPOKEN
                    heading()
                },
            )
            Text(
                WelcomeCopy.SUBHEAD,
                style = OpenCodeTextStyles.body,
                color = OpenCodeTheme.colors.secondaryText,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 340.dp),
            )
        }
        Column(
            Modifier.welcomeSwap(pairing, reduced),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                WelcomeCopy.INSTRUCTION,
                style = OpenCodeTextStyles.title2.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            if (problem != null) {
                OpenCodeErrorText(problem, color = OpenCodeTheme.colors.warning, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Actions(
    pairing: Boolean,
    progress: String?,
    reduced: Boolean,
    modifier: Modifier,
    onPrimary: () -> Unit,
    onOtherWays: () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 8.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OpenCodePrimaryButton(
            onClick = onPrimary,
            enabled = progress == null,
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().semantics { testTag = "welcome-primary" },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.settings_onboarding_get_started), style = OpenCodeTextStyles.headline, modifier = Modifier.welcomeSwap(!pairing, reduced))
                Row(
                    Modifier.welcomeSwap(pairing, reduced),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (progress != null) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Text(progress, style = OpenCodeTextStyles.headline)
                    } else {
                        OpenCodeIcon(SFSymbol.QrcodeViewfinder, contentDescription = null)
                        Text(stringResource(R.string.settings_onboarding_scan_pairing_code), style = OpenCodeTextStyles.headline)
                    }
                }
            }
        }
        Box(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .graphicsLayer { alpha = if (pairing) 1f else 0f }
                .then(if (pairing) Modifier.clickable(onClick = onOtherWays).openCodeTapTarget() else Modifier.clearAndSetSemantics {}),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.settings_onboarding_other_ways_title),
                style = OpenCodeTextStyles.body.copy(fontWeight = FontWeight.Medium),
                color = OpenCodeTheme.colors.secondaryText,
            )
        }
    }
}

/**
 * Other Ways to Connect: the terminal command, a pasted link, a typed address, and the install line
 * for people without OpenCode yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WelcomeOtherWays(pasteError: String?, send: (WelcomeFeature.Action) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        dragHandle = { OpenCodeSheetHandle() },
        shape = OpenCodeShape.sheetTop,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.settings_onboarding_other_ways_title),
                style = OpenCodeTextStyles.headline,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).semantics { heading() },
            )
            OtherWaysSection(header = stringResource(R.string.settings_onboarding_other_ways_terminal_header), footer = stringResource(R.string.settings_onboarding_other_ways_terminal_footer)) {
                WelcomeCommand(WelcomeCopy.PAIR_COMMAND) { send(WelcomeFeature.Action.CopyTapped(it)) }
            }
            OtherWaysSection(header = null, footer = pasteError, footerColor = OpenCodeTheme.colors.warning) {
                OtherWaysButton(stringResource(R.string.settings_onboarding_other_ways_paste_link), SFSymbol.DocOnClipboard) { send(WelcomeFeature.Action.PasteTapped) }
                OtherWaysButton(stringResource(R.string.settings_onboarding_other_ways_enter_address), SFSymbol.Keyboard) { send(WelcomeFeature.Action.EnterAddressTapped) }
            }
            OtherWaysSection(header = stringResource(R.string.settings_onboarding_other_ways_install_header), footer = null) {
                WelcomeCommand(WelcomeCopy.INSTALL_COMMAND) { send(WelcomeFeature.Action.CopyTapped(it)) }
                OtherWaysButton(stringResource(R.string.settings_onboarding_other_ways_install), symbol = null) { send(WelcomeFeature.Action.OtherWaysToInstallTapped) }
            }
        }
    }
}

@Composable
private fun OtherWaysSection(
    header: String?,
    footer: String?,
    footerColor: Color = OpenCodeTheme.colors.secondaryText,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (header != null) {
            Text(header, style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText, modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() })
        }
        Surface(shape = RoundedCornerShape(OpenCodeRadius.card), color = OpenCodeSurfaces.color, border = OpenCodeSurfaces.border) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), content = content)
        }
        if (footer != null) {
            Text(footer, style = OpenCodeTextStyles.footnote, color = footerColor, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
private fun OtherWaysButton(title: String, symbol: SFSymbol?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .openCodeTapTarget(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (symbol != null) OpenCodeIcon(symbol, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = OpenCodeTextStyles.body, color = MaterialTheme.colorScheme.primary)
    }
}

/** A terminal command; tapping copies it. The checkmark stays 1.5 s. */
@Composable
private fun WelcomeCommand(command: String, onCopy: (String) -> Unit) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(1_500)
        copied = false
    }
    val copyDescription = stringResource(R.string.settings_onboarding_command_copy_description, command)
    val copiedDescription = stringResource(CoreR.string.common_copied)
    // The desktop's shell block: a clearly filled box with a hairline, as on the connection screen.
    val shape = RoundedCornerShape(OpenCodeRadius.fill)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(OpenCodeSurfaces.commandFill, shape)
            .openCodeHairline(shape)
            .clickable {
                onCopy(command)
                copied = true
            }
            .openCodeTapTarget()
            .clearAndSetSemantics {
                contentDescription = copyDescription
                stateDescription = if (copied) copiedDescription else ""
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(4.dp))
        Text(
            command,
            style = OpenCodeTextStyles.callout.copy(fontFamily = OpenCodeFonts.monospace),
            modifier = Modifier.weight(1f),
        )
        OpenCodeIcon(
            if (copied) SFSymbol.Checkmark else SFSymbol.SquareOnSquare,
            contentDescription = null,
            tint = OpenCodeTheme.colors.secondaryText,
            size = 16.dp,
        )
        Spacer(Modifier.width(4.dp))
    }
}

/**
 * One of two views sharing a place: it arrives as the other leaves, with a 0.96 scale and a fade;
 * leaving is quicker than arriving. Android has no cheap blur on API 31-, and the fade carries it,
 * so the blur is left out.
 */
@Composable
private fun Modifier.welcomeSwap(shown: Boolean, reduced: Boolean): Modifier {
    val step = WelcomeTunables.step.spring
    val progress by animateFloatAsState(
        if (shown) 1f else 0f,
        animationSpec = if (shown) step.animation(reduced) else MotionSpring(step.duration / 1.4, step.bounce).animation(reduced),
        label = "welcome-swap",
    )
    return this
        .graphicsLayer {
            alpha = progress
            val scale = if (reduced) 1f else 0.96f + 0.04f * progress
            scaleX = scale
            scaleY = scale
        }
        .then(if (shown) Modifier else Modifier.clearAndSetSemantics {})
}

/** The first appearance: rises on the intro spring. */
private fun Modifier.welcomeEntrance(p: Float, reduced: Boolean, dy: Dp = 6.dp, scale: Float = 1f): Modifier = graphicsLayer {
    alpha = p
    val s = if (reduced) 1f else scale + (1 - scale) * p
    scaleX = s
    scaleY = s
    translationY = if (reduced) 0f else dy.toPx() * (1 - p)
}
