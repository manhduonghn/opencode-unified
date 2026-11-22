package ai.opencode.feature.settings

import ai.opencode.core.motion.MotionPresence
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.ui.OpenCodeTopBar
import ai.opencode.core.ui.OpenCodeTopBarBackButton
import ai.opencode.core.ui.rememberOverscrollIfContentScrolls
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.shared
import ai.opencode.core.ui.AppTheme
import ai.opencode.core.ui.AppearanceColorScheme
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.ThemeDefinition
import ai.opencode.core.ui.rememberThemeDefinition
import ai.opencode.core.ui.ThemeSharedKeys
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import ai.opencode.core.ui.OpenCodeFonts
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import kotlin.math.max
import kotlin.math.min

/**
 * Settings → Appearance: System / Light / Dark mode override and a `Theme` row with a three-dot
 * palette swatch that pushes [ThemesSettingsView] (`Settings › Appearance › Themes`). Choosing a
 * theme or mode re-themes Settings in place: `OpenCodeAppTheme` at the root observes the keys,
 * and Compose cross-fades the colours.
 */
@Composable
fun AppearanceSettingsView(store: SettingsStore) {
    val appTheme by remember { shared(ThemeSharedKeys.appTheme).flow }.collectAsState()
    val scheme by remember { shared(ThemeSharedKeys.appearanceColorScheme).flow }.collectAsState()
    val isDark = scheme.resolveIsDark(isSystemInDarkTheme())
    val send = { action: HomeSettingsFeature.Action -> store.send(SettingsFeature.Action.Settings(action)) }
    // Back from the Theme picker puts TalkBack back on the Theme row.
    val returnFocus = rememberSettingsReturnFocus(store, page = SettingsPage.Appearance)
    val modeHeader = stringResource(R.string.settings_appearance_mode_header)

    SettingsPageScaffold(title = stringResource(R.string.settings_appearance_title), store = store) {
        settingsSection(key = "mode", header = modeHeader) {
            SettingsSegmentedControl(
                options = AppearanceColorScheme.entries,
                selected = scheme,
                title = { it.title },
                onSelect = { send(HomeSettingsFeature.Action.AppearanceColorSchemeChanged(it)) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        settingsSection(key = "theme") {
            // The clickable row merges its texts: "Theme", then the theme.
            SettingsRowFrame(
                modifier = returnFocus.on(SettingsPage.Themes),
                onClick = {
                    returnFocus.opened(SettingsPage.Themes)
                    store.send(SettingsFeature.Action.PagePushed(SettingsPage.Themes))
                },
            ) {
                val swatch = @Composable {
                    ThemeSwatchTile(
                        rememberThemeDefinition(appTheme),
                        isDark = isDark,
                        size = ThemeSwatchTile.Size.Compact,
                        cardBackground = OpenCodeTheme.colors.groupedCard,
                    )
                }
                if (isAccessibilitySize()) {
                    // The label stacks over the swatch and value at the accessibility sizes.
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.settings_appearance_theme), style = SettingsText.title, color = OpenCodeTheme.colors.text)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            swatch()
                            Text(appTheme.title, style = SettingsText.summary, color = OpenCodeTheme.colors.secondaryText)
                            SettingsDropdownChevron()
                        }
                    }
                } else {
                    Text(stringResource(R.string.settings_appearance_theme), style = SettingsText.title, color = OpenCodeTheme.colors.text, modifier = Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        swatch()
                        Text(appTheme.title, style = SettingsText.summary, color = OpenCodeTheme.colors.secondaryText, maxLines = 1)
                        SettingsDropdownChevron()
                    }
                }
            }
        }
    }
}

/**
 * Settings → Appearance → Themes: the top is a session preview
 * edge to edge in the theme's own canvas, pinned, and the curated theme list scrolls independently
 * beneath it, dissolving across a soft edge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemesSettingsView(store: SettingsStore) {
    val appTheme by remember { shared(ThemeSharedKeys.appTheme).flow }.collectAsState()
    val scheme by remember { shared(ThemeSharedKeys.appearanceColorScheme).flow }.collectAsState()
    val isDark = scheme.resolveIsDark(isSystemInDarkTheme())
    val reduceMotion = rememberReduceMotion()
    val colors = OpenCodeTheme.colors
    val canvas = rememberThemeDefinition(appTheme).variant(isDark).canvas.color
    val density = LocalDensity.current
    /** The pinned preview header's height (px), content plus its 14 pt bottom inset. */
    var headerHeight by remember { mutableIntStateOf(0) }

    Scaffold(
        containerColor = colors.groupedCanvas,
        topBar = {
            OpenCodeTopBar(
                title = { Text(stringResource(R.string.settings_themes_title), modifier = Modifier.semantics { heading() }) },
                navigation = { OpenCodeTopBarBackButton(onClick = { store.send(SettingsFeature.Action.BackTapped) }) },
                centerTitle = true,
                containerColor = canvas,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            val themesList = rememberLazyListState()
            LazyColumn(
                state = themesList,
                overscrollEffect = rememberOverscrollIfContentScrolls(themesList),
                contentPadding = PaddingValues(
                    top = with(density) { headerHeight.toDp() } + AppearanceTunables.softEdgeHeight + 4.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                modifier = Modifier.fillMaxSize(),
            ) {
                settingsSection(key = "themes") {
                    AppTheme.entries.forEachIndexed { index, theme ->
                        if (index > 0) SettingsDivider(inset = true)
                        ThemeOptionRow(
                            theme = theme,
                            definition = rememberThemeDefinition(theme),
                            isDark = isDark,
                            isSelected = appTheme == theme,
                            onClick = { store.send(SettingsFeature.Action.Settings(HomeSettingsFeature.Action.AppThemeChanged(theme))) },
                        )
                    }
                }
            }
            if (headerHeight > 0) {
                ThemesSoftEdgeBackdrop(
                    header = headerHeight,
                    topBackground = canvas,
                    bottomBackground = colors.groupedCanvas,
                    opaque = colors.highContrast,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    // No pull-up under the bar: it's opaque, so that would cut the bubble's top.
                    .padding(bottom = SoftEdgeBottomInset)
                    .onSizeChanged { headerHeight = it.height },
            ) {
                // A theme or light/dark change swaps the preview.
                Crossfade(
                    appTheme to isDark,
                    animationSpec = MotionRole.Swap.animation(reduceMotion = reduceMotion),
                    label = "theme-preview",
                ) { (theme, dark) ->
                    AppearanceTranscriptPreview(theme, dark)
                }
            }
        }
    }
}

/** The soft scroll edge under the pinned preview, at its defaults. */
internal object AppearanceTunables {
    /** Soft edge feather (pt). */
    val softEdgeHeight = 22.dp
    /** Not drawn on Android: the list fades under the gradient alone. */
    val softEdgeBlur = 12.dp
}

private val SoftEdgeBottomInset = 14.dp

/**
 * Soft scroll-edge backdrop behind the pinned split preview: full [topBackground] through
 * `fadeOrigin`, then a smoothstep gradient easing to transparent over the feather below the
 * preview, so scrolling theme rows dissolve before they reach it. Android doesn't blur scrolling
 * content (a plain fading edge, as Android lists use). Under high contrast it is opaque.
 */
@Composable
private fun ThemesSoftEdgeBackdrop(
    header: Int,
    topBackground: Color,
    bottomBackground: Color,
    opaque: Boolean,
) {
    val density = LocalDensity.current
    val bottomInset = with(density) { SoftEdgeBottomInset.toPx() }
    val feather = with(density) { AppearanceTunables.softEdgeHeight.toPx() }
    val headerPx = max(header.toFloat(), 1f)
    val fadeOrigin = max(headerPx - bottomInset, 1f)
    val span = if (opaque) 0f else max(feather, 1f)
    val blurBand = bottomInset + span
    val total = fadeOrigin + blurBand
    val splitStop = (fadeOrigin / total).coerceIn(0f, 1f)

    if (opaque) {
        Box(Modifier.fillMaxWidth().height(with(density) { headerPx.toDp() }).background(topBackground).clearAndSetSemantics { })
        return
    }
    // The feather: the preview's canvas, then the grouped canvas easing to transparent.
    val stops = remember(topBackground, bottomBackground, splitStop) {
        buildList {
            add(0f to topBackground)
            add(splitStop to topBackground)
            val rest = max(1 - splitStop, 0.001f)
            for (step in 1..8) {
                val t = step / 8f
                val ease = 1 - t * t * (3 - 2 * t)
                add(splitStop + rest * t to bottomBackground.copy(alpha = bottomBackground.alpha * ease))
            }
        }.toTypedArray()
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(with(density) { total.toDp() })
            .clearAndSetSemantics { }
            .background(Brush.verticalGradient(*stops)),
    )
}

@Composable
private fun ThemeOptionRow(
    theme: AppTheme,
    definition: ThemeDefinition,
    isDark: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    // The clickable row merges its title: the theme's name once, then selected.
    SettingsRowFrame(
        Modifier.semantics { selected = isSelected },
        onClick = onClick,
        role = Role.RadioButton,
    ) {
        ThemeSwatchTile(definition, isDark, ThemeSwatchTile.Size.Regular, cardBackground = OpenCodeTheme.colors.groupedCard)
        Text(
            theme.title,
            style = SettingsText.title.copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal),
            color = OpenCodeTheme.colors.text,
            maxLines = if (isAccessibilitySize()) Int.MAX_VALUE else 1,
            modifier = Modifier.weight(1f),
        )
        Box(contentAlignment = Alignment.Center) {
            MotionPresence(isOpen = !isSelected) {
                Box(
                    Modifier
                        .size(20.dp)
                        .border(1.5.dp, OpenCodeTheme.colors.tertiaryText.copy(alpha = 0.65f), CircleShape),
                )
            }
            MotionPresence(isOpen = isSelected) {
                OpenCodeIcon(SFSymbol.CheckmarkCircleFill, contentDescription = null, size = 22.dp, tint = OpenCodeTheme.colors.selection)
            }
        }
    }
}

/** The three overlapping palette dots (canvas, accent, syntax) with card knockout rings. */
object ThemeSwatchTile {
    enum class Size(val dotSize: Dp, val step: Dp, val frameHeight: Dp) {
        Regular(13.dp, 8.5.dp, 29.dp),
        Compact(10.5.dp, 7.dp, 24.dp),
    }
}

@Composable
fun ThemeSwatchTile(
    definition: ThemeDefinition,
    isDark: Boolean,
    size: ThemeSwatchTile.Size = ThemeSwatchTile.Size.Regular,
    cardBackground: Color = Color.Transparent,
) {
    val variant = definition.variant(isDark)
    val knockout = if (cardBackground == Color.Transparent) variant.swatchCanvas.color else cardBackground
    val dots = listOf(
        Triple(variant.swatchCanvas.color, variant.swatchRing.color.copy(alpha = 0.95f), 1.dp),
        Triple(variant.swatchAccent.color, variant.swatchRing.color.copy(alpha = 0.35f), 0.5.dp),
        Triple(variant.syntaxKeyword.color, variant.swatchRing.color.copy(alpha = 0.35f), 0.5.dp),
    )
    Box(
        Modifier.width(size.dotSize + size.step * 2).height(size.frameHeight).clearAndSetSemantics {},
        contentAlignment = Alignment.CenterStart,
    ) {
        dots.forEachIndexed { index, (fill, ring, ringWidth) ->
            Box(
                Modifier
                    .offset(x = size.step * index - 1.5.dp)
                    .zIndex(index.toFloat())
                    .size(size.dotSize + 3.dp)
                    .clip(CircleShape)
                    .background(knockout),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(size.dotSize)
                        .clip(CircleShape)
                        .background(fill)
                        .border(ringWidth, ring, CircleShape),
                )
            }
        }
    }
}

/**
 * The real transcript rows for the Themes stage, provided by a module that sees
 * `:feature:transcript` (Home provides it where it hosts Settings). Without one,
 * [ImitationTranscriptPreview] draws the same slice from the theme's palette.
 */
val LocalAppearanceTranscriptPreview = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

/** The Themes stage. */
@Composable
private fun AppearanceTranscriptPreview(theme: AppTheme, isDark: Boolean) {
    val rows = LocalAppearanceTranscriptPreview.current
    val density = LocalDensity.current
    val previewDescription = stringResource(R.string.settings_themes_preview_description)
    Box(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = previewDescription
                stateDescription = theme.title
            }
            // The preview takes no touches.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            },
    ) {
        OpenCodeTheme(theme = theme, appearance = if (isDark) AppearanceColorScheme.Dark else AppearanceColorScheme.Light) {
            CompositionLocalProvider(LocalDensity provides Density(density.density, min(density.fontScale, PreviewMaxFontScale))) {
                if (rows != null) rows() else ImitationTranscriptPreview(theme, isDark)
            }
        }
    }
}

/** XLarge body (19 sp) over Large (17 sp). */
private const val PreviewMaxFontScale = 19f / 17f

/** The palette-drawn stand-in for [LocalAppearanceTranscriptPreview] when no host provides rows. */
@Composable
private fun ImitationTranscriptPreview(theme: AppTheme, isDark: Boolean) {
    val v = rememberThemeDefinition(theme).variant(isDark)
    val mono = OpenCodeFonts.monospace
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(start = 48.dp), contentAlignment = Alignment.CenterEnd) {
            Surface(
                color = v.userBubble.color,
                shape = RoundedCornerShape(OpenCodeRadius.card),
            ) {
                Text(
                    stringResource(R.string.settings_themes_preview_user_message),
                    style = SettingsText.title,
                    color = v.text.color,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        val fileName = "SessionView.swift"
        val reply = stringResource(R.string.settings_themes_preview_reply, fileName)
        val fileNameStart = reply.indexOf(fileName)
        Text(
            buildAnnotatedString {
                if (fileNameStart < 0) {
                    append(reply)
                } else {
                    append(reply.substring(0, fileNameStart))
                    withStyle(SpanStyle(fontFamily = mono, background = v.surfaceStrong.color, fontSize = 15.sp)) {
                        append(fileName)
                    }
                    append(reply.substring(fileNameStart + fileName.length))
                }
            },
            style = SettingsText.title,
            color = v.bodyText.color,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(OpenCodeRadius.sm))
                .background(v.codeBand.color)
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = v.syntaxKeyword.color)) { append("struct ") }
                    withStyle(SpanStyle(color = v.syntaxType.color)) { append("SessionSummary") }
                    append(": ")
                    withStyle(SpanStyle(color = v.syntaxType.color)) { append("Equatable") }
                    append(" {\n  ")
                    withStyle(SpanStyle(color = v.syntaxKeyword.color)) { append("var ") }
                    append("title: ")
                    withStyle(SpanStyle(color = v.syntaxType.color)) { append("String") }
                    append("\n  ")
                    withStyle(SpanStyle(color = v.syntaxKeyword.color)) { append("var ") }
                    append("isRunning: ")
                    withStyle(SpanStyle(color = v.syntaxType.color)) { append("Bool") }
                    append("\n  ")
                    withStyle(SpanStyle(color = v.syntaxKeyword.color)) { append("var ") }
                    append("detail = ")
                    withStyle(SpanStyle(color = v.syntaxString.color)) {
                        append("\"A deliberately long line to inspect horizontal scrolling on a narrow phone display\"")
                    }
                    append("\n}")
                },
                style = SettingsText.summary.copy(fontFamily = mono),
                color = v.text.color,
                softWrap = false,
            )
        }
    }
}
