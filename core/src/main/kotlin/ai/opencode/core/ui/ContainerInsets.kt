package ai.opencode.core.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How far the window's edges lie outside the container content is laid out in. A container provides
 * the window insets it covers and consumes them; code laying out against its edges reads insets
 * through [inContainer].
 */
val LocalContainerInsets = staticCompositionLocalOf { NoContainerInsets }

private val NoContainerInsets = WindowInsets(0, 0, 0, 0)

/** These insets as they reach into the current container: less what lies outside it ([LocalContainerInsets]). */
@Composable
fun WindowInsets.inContainer(): WindowInsets {
    val outside = LocalContainerInsets.current
    if (outside === NoContainerInsets) return this
    return exclude(outside)
}
