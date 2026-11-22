package ai.opencode.feature.system

import android.content.res.Configuration
import java.util.Locale

/**
 * Keeps a window's configuration left-to-right while the app ships English only.
 * `supportsRtl="false"` resolves views to LTR, but under an RTL locale the configuration still says
 * RTL, and Compose windows (popups, dialogs) take their layout direction from it, so their text
 * keeps an RTL paragraph direction. Each activity applies this in `attachBaseContext`. Revisit when
 * RTL translations ship.
 */
object LeftToRight {
    fun configuration(): Configuration = Configuration().apply { setLayoutDirection(Locale.ENGLISH) }
}
