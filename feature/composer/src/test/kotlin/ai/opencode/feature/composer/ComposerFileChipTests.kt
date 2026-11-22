package ai.opencode.feature.composer

import ai.opencode.core.ui.OpenCodeAppTheme
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * The file chip keeps 76 dp as its least height and grows with the font scale. Android's largest
 * font scale is 2×.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComposerFileChipTests {
    private val statuses = listOf(
        ComposerFileAttachment.Status.Uploading(sent = 60_000),
        ComposerFileAttachment.Status.Uploaded(path = "/srv/quarterly-report.pdf"),
        ComposerFileAttachment.Status.Failed("The upload stopped. Tap to try again."),
    )

    @Test fun growsWithDynamicTypeInsteadOfClipping() {
        for (status in statuses) {
            val regular = height(status, fontScale = 1f)
            val largest = height(status, fontScale = 2f)
            val least = ComposerFileChip.height.value
            assertTrue("At the default size the chip keeps its 76 dp ($regular dp, $status)", regular >= least - 0.5f)
            assertTrue("At the largest font scale the chip grows to fit its text ($largest dp, $status)", largest > least + 10f)
        }
    }

    private fun height(status: ComposerFileAttachment.Status, fontScale: Float): Float {
        val file = ComposerFileAttachment(
            id = UUID.randomUUID(),
            name = "quarterly-report.pdf",
            mime = "application/pdf",
            size = 120_000,
            localPath = "/tmp/quarterly-report.pdf",
            status = status,
        )
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val density = activity.resources.displayMetrics.density
        val view = ComposeView(activity)
        activity.setContentView(view)
        view.setContent {
            OpenCodeAppTheme {
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                    ComposerFileChip(file = file, canRetry = true, onRetry = {})
                }
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        val width = (ComposerFileChip.width.value * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return view.measuredHeight / density
    }
}
