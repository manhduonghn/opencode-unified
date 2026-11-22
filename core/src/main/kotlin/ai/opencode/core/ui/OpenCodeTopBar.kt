package ai.opencode.core.ui

import ai.opencode.core.R
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/** Sizes of the desktop-style title bar ([OpenCodeTopBar], [OpenCodeLargeTopBar]). */
object OpenCodeTopBarDefaults {
    /** The bar's row: the session header's 52 dp (desktop 48 px), room for 48 dp controls. It grows with large text. */
    val height: Dp = 52.dp

    /** A start-aligned title's inset when there's no navigation control (desktop 16 px). */
    val titleInset: Dp = 16.dp
}

/**
 * The OpenCode desktop's title bar (`packages/app` settings and session headers): the pane's own
 * canvas, a 16 sp medium title and plain icon controls, over a hairline instead of Material's
 * tonal scrolled colour. Replaces Material's `TopAppBar`/`CenterAlignedTopAppBar`.
 *
 * Without a [scrollBehavior] the hairline always shows (the desktop's mobile settings bar). With
 * one (Material's pinned behaviour) it fades in once content scrolls under the bar, on the
 * theme's effects spring, as the session header's bar does.
 *
 * @param navigation the leading control (Back, or a Cancel/Done text button); 48 dp targets.
 * @param centerTitle Centre the title between the controls, else start-aligned.
 * @param hairline false hides the hairline (a bar over a camera preview).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenCodeTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigation: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    centerTitle: Boolean = false,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    containerColor: Color = OpenCodeTheme.colors.canvas,
    contentColor: Color = OpenCodeTheme.colors.text,
    hairline: Boolean = true,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    val overlapped by remember(scrollBehavior) {
        derivedStateOf { scrollBehavior == null || scrollBehavior.state.overlappedFraction > 0.01f }
    }
    val lift by animateFloatAsState(
        targetValue = if (hairline && overlapped) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "top-bar-hairline",
    )
    val border = OpenCodeTheme.colors.border
    Box(
        modifier
            .fillMaxWidth()
            .background(containerColor)
            .drawWithContent {
                drawContent()
                drawBottomHairline(border, lift)
            }
            .windowInsetsPadding(windowInsets),
    ) {
        TopBarRow(
            title = title,
            navigation = navigation,
            actions = actions,
            centerTitle = centerTitle,
            contentColor = contentColor,
        )
    }
}

/**
 * The desktop title bar with a large page title under it (Settings pages): the title (title1,
 * 24 sp medium) scrolls up under the bar as the list scrolls, Material's exit-until-collapsed
 * behaviour, and the bar's own title and hairline fade in as it goes. Replaces Material 3
 * Expressive's `LargeFlexibleTopAppBar`; [scrollBehavior] must be
 * [rememberOpenCodeLargeTopBarScrollBehavior] (exit-until-collapsed, only while the page can
 * scroll), connected to the page's nested scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenCodeLargeTopBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
    navigation: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    containerColor: Color = OpenCodeTheme.colors.canvas,
    contentColor: Color = OpenCodeTheme.colors.text,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    val state = scrollBehavior.state
    // TalkBack reads whichever copy of the title is showing (Material's large bars do the same).
    val collapsed by remember(state) { derivedStateOf { state.collapsedFraction >= 0.5f } }
    val border = OpenCodeTheme.colors.border
    Column(
        modifier
            .fillMaxWidth()
            .background(containerColor)
            .drawWithContent {
                drawContent()
                drawBottomHairline(border, state.collapsedFraction)
            }
            .windowInsetsPadding(windowInsets),
    ) {
        TopBarRow(
            title = {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .graphicsLayer { alpha = ((state.collapsedFraction - 0.5f) * 2f).coerceIn(0f, 1f) }
                        .then(if (collapsed) Modifier.semantics { heading() } else Modifier.clearAndSetSemantics {}),
                )
            },
            navigation = navigation,
            actions = actions,
            centerTitle = false,
            contentColor = contentColor,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .clipToBounds()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                    val height = (placeable.height + state.heightOffset).roundToInt().coerceIn(0, placeable.height)
                    layout(placeable.width, height) { placeable.place(0, height - placeable.height) }
                },
        ) {
            Text(
                title,
                style = OpenCodeTextStyles.title1,
                color = contentColor,
                modifier = Modifier
                    .fillMaxWidth()
                    // The title's full height is how far the bar collapses.
                    .onSizeChanged { state.heightOffsetLimit = -it.height.toFloat() }
                    .padding(start = OpenCodeTopBarDefaults.titleInset, end = OpenCodeTopBarDefaults.titleInset, top = 4.dp, bottom = 12.dp)
                    .graphicsLayer { alpha = 1f - state.collapsedFraction }
                    .then(if (collapsed) Modifier.clearAndSetSemantics {} else Modifier.semantics { heading() }),
            )
        }
    }
}

/** The title bar's Back control: a plain chevron with a 48 dp target. */
@Composable
fun OpenCodeTopBarBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    OpenCodeIconButton(SFSymbol.ChevronBackward, contentDescription = stringResource(R.string.common_back), onClick = onClick, modifier = modifier, style = OpenCodeIconButtonStyle.Standard)
}

/** The bar's row: navigation, title, actions, at least [OpenCodeTopBarDefaults.height] tall. */
@Composable
private fun TopBarRow(
    title: @Composable () -> Unit,
    navigation: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    centerTitle: Boolean,
    contentColor: Color,
) {
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Layout(
            contents = listOf(
                { Box(Modifier.padding(start = 4.dp)) { navigation() } },
                { ProvideTextStyle(OpenCodeTextStyles.headline.copy(color = contentColor)) { title() } },
                { Row(Modifier.padding(end = 4.dp)) { actions() } },
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = OpenCodeTopBarDefaults.height),
        ) { (navigationMeasurables, titleMeasurables, actionMeasurables), constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val nav = navigationMeasurables.first().measure(loose)
            val action = actionMeasurables.first().measure(loose)
            val width = constraints.maxWidth
            // A navigation control brings its own padding; a bare title starts at the desktop inset.
            val navWidth = if (nav.width > 4.dp.roundToPx()) nav.width else OpenCodeTopBarDefaults.titleInset.roundToPx()
            val gap = 4.dp.roundToPx()
            val titleWidth = if (centerTitle) {
                width - 2 * max(navWidth, action.width) - 2 * gap
            } else {
                width - navWidth - action.width - 2 * gap
            }
            val text = titleMeasurables.first().measure(loose.copy(maxWidth = max(titleWidth, 0)))
            val height = max(constraints.minHeight, maxOf(nav.height, text.height, action.height))
            layout(width, height) {
                nav.place(0, (height - nav.height) / 2)
                action.place(width - action.width, (height - action.height) / 2)
                val x = if (centerTitle) (width - text.width) / 2 else navWidth + if (nav.width > 4.dp.roundToPx()) gap else 0
                text.place(x, (height - text.height) / 2)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBottomHairline(color: Color, alpha: Float) {
    if (alpha <= 0f) return
    val px = OpenCodeSurfaces.hairline.toPx()
    drawRect(color = color, topLeft = Offset(0f, size.height - px), size = Size(size.width, px), alpha = alpha.coerceIn(0f, 1f))
}
