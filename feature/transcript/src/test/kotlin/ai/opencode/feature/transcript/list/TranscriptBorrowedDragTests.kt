package ai.opencode.feature.transcript.list

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BorrowedDragVerdictTests {
    /** Review finding: a sideways drag the drawer or back swipe takes is cancelled with no end; its verdict left the next vertical drag dead. */
    @Test
    fun aCancelledSidewaysDragDoesNotDecideTheNext() {
        val verdict = BorrowedDragVerdict()
        val sideways = verdict.isVertical(start = Offset(100f, 700f), translation = Offset(30f, 2f))
        val next = verdict.isVertical(start = Offset(140f, 705f), translation = Offset(1f, -20f))
        assertFalse(sideways)
        assertTrue(next)
    }

    @Test
    fun aDragKeepsTheVerdictItFirstReported() {
        val verdict = BorrowedDragVerdict()
        val start = Offset(100f, 700f)
        val first = verdict.isVertical(start, Offset(0f, -12f))
        val later = verdict.isVertical(start, Offset(80f, -14f))
        val ended = verdict.end(start, Offset(90f, -14f))
        assertTrue(first && later && ended)
    }
}
