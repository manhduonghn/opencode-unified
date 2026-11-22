package ai.opencode.feature.session

import ai.opencode.core.ui.AppTheme
import ai.opencode.core.ui.OpenCodeColors
import org.junit.Assert.assertEquals
import org.junit.Test

/** `.primary`/`.secondary` are the palette's text and secondary text. */
class MarkdownBlockTests {
    @Test
    fun increaseContrastUsesSecondaryOnHeaderProjectLine() {
        for (theme in listOf(AppTheme.System, AppTheme.OpenCode)) {
            for (isDark in listOf(false, true)) {
                val standard = OpenCodeColors.resolve(theme, isDark, highContrast = false)
                assertEquals(standard.text.copy(alpha = 0.75f), headerProjectColor(standard))
                val increased = OpenCodeColors.resolve(theme, isDark, highContrast = true)
                assertEquals(increased.secondaryText, headerProjectColor(increased))
            }
        }
    }
}
