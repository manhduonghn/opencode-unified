package ai.opencode.feature.transcript.list

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.transcript.markdown.ui.InlineMath
import ai.opencode.feature.transcript.markdown.ui.MathRenderer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * Port of iOS `TranscriptRenderWorkTests`: counts the rendering work the transcript does, so it
 * can't silently grow back.
 *
 * Not ported: `aGrowingRowMeasuresSwiftUIOncePerFlushNotEveryFrame` and
 * `anIdleRepaintReloadsOnlyRowsNearTheScreen` count UICollectionView cell measurements and
 * reloads, which a LazyColumn doesn't have; `aTailDeltaRedrawsOnlyTheGrowingBlock` counts SwiftUI
 * bodies (Compose skips a finished `MarkdownBlockView` because its `@Immutable` block and other
 * arguments are equal, and there is no Compose UI test harness here to count recompositions).
 *
 * Dropped (deliberate Android divergence, see docs/parity/transcript.md):
 * `scrollingUpAsksForEarlierHistoryFurtherAhead` pinned `TranscriptScrollBehavior`'s lookahead in
 * screens. The bottom-anchored list asks for the earlier page when its oldest rows come into
 * view, which needs a LazyColumn layout to test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptRenderWorkTests {
    @get:Rule val isolation = TestIsolationRule()

    /**
     * A formula drawn in the text is typeset once: asking whether it typesets (building the
     * selection's text) reads that render, at any size.
     */
    @Test
    fun aFormulaIsTypesetOnce() {
        val n = Random.nextInt(10_000, 100_000)
        val valid = "x_{$n} + y^2"
        val invalid = "\\notacommandzz{$n}"
        val size = InlineMath.fontSize(17f)
        val before = MathRenderer.rendersForTesting
        // jlatexmath may not draw under Robolectric; whatever the render said is the answer.
        val drawsValid = MathRenderer.render(valid, size, display = false) != null
        val drawsInvalid = MathRenderer.render(invalid, size, display = false) != null
        assertEquals(drawsValid, MathRenderer.typesets(valid, display = false, fontSizePx = size * 2))
        assertEquals(drawsInvalid, MathRenderer.typesets(invalid, display = false, fontSizePx = size * 2))
        assertEquals("Rendered ${MathRenderer.rendersForTesting - before} times", 2, MathRenderer.rendersForTesting - before)
    }
}
