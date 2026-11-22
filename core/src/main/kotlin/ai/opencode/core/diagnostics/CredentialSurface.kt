package ai.opencode.core.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.util.concurrent.atomic.AtomicInteger

/**
 * What's on screen that screenshots must skip. `sheets` skip every screenshot; `screens` skip only
 * problem reports.
 */
object CredentialSurface {
    data class Marks(val sheets: Int = 0, val screens: Int = 0)

    enum class Kind { sheets, screens }

    private val sheets = AtomicInteger(0)
    private val screens = AtomicInteger(0)

    val marks: Marks get() = Marks(sheets = sheets.get(), screens = screens.get())

    /** What problem reports skip on: every marked surface. */
    val visible: Int get() = ScreenshotPolicy.problemReport.markedSurfaces(marks)

    fun appeared(kind: Kind = Kind.screens) {
        counter(kind).incrementAndGet()
    }

    fun disappeared(kind: Kind = Kind.screens) {
        counter(kind).updateAndGet { maxOf(0, it - 1) }
    }

    private fun counter(kind: Kind) = when (kind) {
        Kind.sheets -> sheets
        Kind.screens -> screens
    }
}

/**
 * Problem reports leave the phone, so they skip every marked surface; the voice tool skips only
 * credential sheets.
 */
enum class ScreenshotPolicy {
    problemReport,
    voice;

    fun markedSurfaces(marks: CredentialSurface.Marks): Int = when (this) {
        problemReport -> marks.sheets + marks.screens
        voice -> marks.sheets
    }
}

/** Keeps the screen composing this out of problem report screenshots while it's composed. */
@Composable
fun HidesFromProblemReports() = CredentialMark(CredentialSurface.Kind.screens)

/** Keeps this credential view out of every screenshot, voice ones included. */
@Composable
fun HidesFromScreenshots() = CredentialMark(CredentialSurface.Kind.sheets)

/** Keeps every screenshot out while [shown] (an alert with no lifecycle of its own). */
@Composable
fun HidesFromScreenshots(shown: Boolean) {
    if (shown) HidesFromScreenshots()
}

@Composable
private fun CredentialMark(kind: CredentialSurface.Kind) {
    DisposableEffect(kind) {
        CredentialSurface.appeared(kind)
        onDispose { CredentialSurface.disappeared(kind) }
    }
}
