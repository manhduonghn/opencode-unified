package ai.opencode.feature.media

import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MathAndMermaidTests {
    @Test
    fun mermaidCacheKeys() {
        val source = "flowchart TD\n  A --> B"
        val key = MermaidCacheKey(source, MermaidTheme.Light, width = 320f)
        assertEquals(MermaidCacheKey(source, MermaidTheme.Light, width = 320.2f), key)
        assertNotEquals(MermaidCacheKey(source, MermaidTheme.Dark, width = 320f), key)
        assertNotEquals(MermaidCacheKey(source, MermaidTheme.Light, width = 300f), key)
        assertNotEquals(MermaidCacheKey("$source ", MermaidTheme.Light, width = 320f), key)
        assertEquals(MermaidCacheKey(source, MermaidTheme.Light), key.diagram)
        assertEquals(0, key.diagram.width)
        assertEquals(32, key.sourceHash.length)
        assertEquals("${key.sourceHash}|light|320", key.string)
        assertEquals(2, MermaidTheme.entries.map { MermaidCacheKey(source, it).string }.toSet().size)
    }

    @Test
    fun mermaidSourcesHashOncePerSource() {
        val source = "flowchart TD\n  A${java.util.UUID.randomUUID().toString().replace("-", "")} --> B"
        val first = MermaidSyntax.hash(source)
        val runs = MermaidSyntax.hashCount
        // A card asks for its keys on every recomposition.
        repeat(50) {
            assertEquals(first, MermaidCacheKey(source, MermaidTheme.Dark, width = 300f).sourceHash)
        }
        assertTrue("Only another test's sources may have hashed meanwhile", MermaidSyntax.hashCount - runs <= 1)
        assertEquals(32, first.length)
        assertTrue(first.all { it in "0123456789abcdef" })
    }

    @Test
    fun onlyRejectedDiagramsAreCachedAsFailures() {
        assertTrue(MermaidRenderer.caches(Result.success(MermaidDiagram(svg = "", width = 10f, height = 10f))))
        assertTrue(MermaidRenderer.caches(Result.failure(MermaidRenderError.InvalidDiagram)))
        for (transient in listOf(
            MermaidRenderError.Interrupted,
            MermaidRenderError.Unavailable,
            MermaidRenderError.InvalidResult,
            MermaidRenderError.ImageFailed,
        )) {
            assertFalse("$transient", MermaidRenderer.caches(Result.failure(transient)))
        }
        assertFalse(MermaidRenderer.caches(Result.failure(CancellationException())))
    }

    @Test
    fun mermaidDiagramTypes() {
        assertEquals("Flowchart", MermaidSyntax.diagramType("flowchart TD\nA-->B"))
        assertEquals("Flowchart", MermaidSyntax.diagramType("graph LR;A-->B"))
        assertEquals("Sequence diagram", MermaidSyntax.diagramType("%% a comment\n\nsequenceDiagram\nA->>B: Hi"))
        assertEquals("State diagram", MermaidSyntax.diagramType("---\ntitle: Flow\n---\nstateDiagram-v2\n[*] --> A"))
        assertEquals("Entity relationship diagram", MermaidSyntax.diagramType("erDiagram\nA ||--o{ B : has"))
        assertNull(MermaidSyntax.diagramType("not a diagram"))
    }

    @Test
    fun mermaidCardFitsTheWidthAndHeightCap() {
        assertEquals(Size(200f, 100f), MermaidDiagramMetrics.displaySize(Size(200f, 100f), width = 400f))
        assertEquals(Size(400f, 100f), MermaidDiagramMetrics.displaySize(Size(800f, 200f), width = 400f))
        assertEquals(Size(90f, 360f), MermaidDiagramMetrics.displaySize(Size(300f, 1200f), width = 400f))
    }
}
