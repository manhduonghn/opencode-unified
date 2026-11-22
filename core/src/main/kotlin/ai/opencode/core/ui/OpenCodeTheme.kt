package ai.opencode.core.ui

import ai.opencode.core.motion.OpenCodeMotionScheme
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.shared
import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.ContextWrapper
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat

/**
 * The app's theme: [MaterialExpressiveTheme] plus [OpenCodeColors] for colours Material has no role
 * for. At the app root use [OpenCodeAppTheme].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OpenCodeTheme(
    theme: AppTheme = AppTheme.System,
    appearance: AppearanceColorScheme = AppearanceColorScheme.System,
    highContrast: Boolean = rememberHighContrast(),
    strongBorders: Boolean = highContrast || rememberHighContrastText(),
    reduceMotion: Boolean = rememberReduceMotion(),
    content: @Composable () -> Unit,
) {
    val isDark = appearance.resolveIsDark(isSystemInDarkTheme())
    val context = LocalContext.current
    // A wallpaper change arrives as a configuration change.
    val configuration = LocalConfiguration.current
    val dynamicScheme: ColorScheme? = remember(theme, isDark, context, configuration) {
        if (theme == AppTheme.System) DynamicSystemTheme.colorScheme(context, isDark) else null
    }
    val colors = remember(theme, isDark, highContrast, strongBorders, dynamicScheme) {
        if (dynamicScheme != null) {
            DynamicSystemTheme.colors(dynamicScheme, isDark, highContrast, strongBorders)
        } else {
            OpenCodeColors.resolve(theme, isDark, highContrast, strongBorders)
        }
    }
    val colorScheme = remember(colors, dynamicScheme) { dynamicScheme ?: colors.toColorScheme() }
    val motionScheme = remember(reduceMotion) { OpenCodeMotionScheme(reduceMotion) }
    CompositionLocalProvider(LocalOpenCodeColors provides colors) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = motionScheme,
            shapes = OpenCodeShapes,
            typography = OpenCodeTypography,
            content = content,
        )
    }
}

/**
 * [OpenCodeTheme] from the persisted [ThemeSharedKeys]. Once per window root; the system bars
 * follow the app's appearance, not night mode.
 */
@Composable
fun OpenCodeAppTheme(content: @Composable () -> Unit) {
    val appearance = rememberAppAppearance()
    SystemBarAppearance(isDark = appearance.colorScheme.resolveIsDark(isSystemInDarkTheme()))
    OpenCodeTheme(theme = appearance.theme, appearance = appearance.colorScheme, content = content)
}

/**
 * Light or dark system bar content, applied only when the appearance changes so a screen that
 * restyles the bars keeps its style.
 */
@Composable
private fun SystemBarAppearance(isDark: Boolean) {
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    DisposableEffect(window, isDark) {
        if (window != null) {
            WindowCompat.getInsetsController(window, window.decorView).run {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
        onDispose {}
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The persisted Settings → Appearance choice. */
@Immutable
data class AppAppearance(val theme: AppTheme, val colorScheme: AppearanceColorScheme)

/** Observes [ThemeSharedKeys] (read-only; mutate from the Settings reducer). */
@Composable
fun rememberAppAppearance(): AppAppearance {
    val themeShared = remember { shared(ThemeSharedKeys.appTheme) }
    val schemeShared = remember { shared(ThemeSharedKeys.appearanceColorScheme) }
    val theme: State<AppTheme> = themeShared.flow.collectAsState()
    val scheme: State<AppearanceColorScheme> = schemeShared.flow.collectAsState()
    return AppAppearance(theme.value, scheme.value)
}

/**
 * Android's Increase Contrast: the system contrast level (Android 14+) at medium or high. Live.
 * Standard on Android 13.
 */
@Composable
fun rememberHighContrast(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
    val context = LocalContext.current
    val uiModeManager = remember(context) { context.getSystemService(UiModeManager::class.java) }
    var contrast by remember(uiModeManager) { mutableFloatStateOf(uiModeManager?.contrast ?: 0f) }
    DisposableEffect(uiModeManager) {
        val manager = uiModeManager ?: return@DisposableEffect onDispose {}
        val listener = UiModeManager.ContrastChangeListener { contrast = it }
        manager.addContrastChangeListener(ContextCompat.getMainExecutor(context), listener)
        onDispose { manager.removeContrastChangeListener(listener) }
    }
    return contrast >= HIGH_CONTRAST_THRESHOLD
}

private const val HIGH_CONTRAST_THRESHOLD = 0.5f

/**
 * Android's High contrast text accessibility setting (`Settings.Secure`
 * `high_text_contrast_enabled`), live-updating. Only hairlines follow it ([OpenCodeColors.strongBorders]).
 */
@Composable
fun rememberHighContrastText(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    fun read() = Settings.Secure.getInt(resolver, HIGH_TEXT_CONTRAST, 0) == 1
    var enabled by remember(resolver) { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = read() }
        }
        resolver.registerContentObserver(Settings.Secure.getUriFor(HIGH_TEXT_CONTRAST), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

private const val HIGH_TEXT_CONTRAST = "high_text_contrast_enabled"

/** Accessors for the design system inside [OpenCodeTheme]. */
object OpenCodeTheme {
    val colors: OpenCodeColors
        @Composable @ReadOnlyComposable get() = LocalOpenCodeColors.current

    /** Material roles. */
    val colorScheme: ColorScheme
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme

    val textStyles: OpenCodeTextStyles get() = OpenCodeTextStyles
}
