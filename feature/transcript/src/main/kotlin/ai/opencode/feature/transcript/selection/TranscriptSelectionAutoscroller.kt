package ai.opencode.feature.transcript.selection

/**
 * How fast the transcript scrolls while a selection drag holds near the top or bottom of what the
 * reader can see (below the header, above the composer and keyboard). Values are in dp and dp per
 * second; the frame loop that applies them every frame (so a finger held still keeps the selection
 * growing) is `TranscriptListSelection`'s autoscroll.
 */
object TranscriptSelectionAutoscroller {
    /** How deep the edge zones reach into the visible list. */
    const val edge = 56f
    const val minimumSpeed = 60f
    const val maximumSpeed = 960f

    /**
     * dp per second (negative scrolls up) for a finger at [y] in a list whose visible text runs
     * from [top] to [bottom]: nothing in the middle, easing in quadratically across each edge
     * zone, full speed past it.
     */
    fun velocity(y: Float, top: Float, bottom: Float): Float {
        val zone = minOf(edge, maxOf(0f, (bottom - top) / 3f))
        if (zone <= 0f) return 0f
        if (y < top + zone) {
            val depth = minOf(1f, (top + zone - y) / zone)
            return -(minimumSpeed + (maximumSpeed - minimumSpeed) * depth * depth)
        }
        if (y > bottom - zone) {
            val depth = minOf(1f, (y - (bottom - zone)) / zone)
            return minimumSpeed + (maximumSpeed - minimumSpeed) * depth * depth
        }
        return 0f
    }

    /** The offset after [dt] seconds at [velocity], kept within [bounds]. */
    fun step(offset: Float, velocity: Float, dt: Float, bounds: ClosedFloatingPointRange<Float>): Float =
        (offset + velocity * dt).coerceIn(bounds.start, bounds.endInclusive)
}
