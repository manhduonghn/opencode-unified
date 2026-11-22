package ai.opencode.feature.transcript.user

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SharedLocationTests {
    private val prompt = listOf(
        "<shared-location>",
        "Place: Apple Park",
        "Address: 1 Apple Park Way, Cupertino, CA 95014",
        "Coordinates: 37.334900, -122.009020",
        "Accuracy: ±12 m",
        "Shared: 2027-01-15T08:00:00Z",
        "Map: https://maps.apple.com/?ll=37.334900,-122.009020&q=Apple%20Park",
        "</shared-location>",
    ).joinToString("\n") + "\n\nHere"

    @Test
    fun theTranscriptShowsTheCardAndOnlyTheTypedText() {
        val message = TranscriptUserMessage(id = "msg_1", text = prompt, images = emptyList(), files = emptyList(), pending = null)
        assertEquals("Apple Park", message.location?.place)
        assertEquals(Instant.ofEpochSecond(1_800_000_000), message.location?.capturedAt)
        assertEquals("Here", message.displayText)
    }
}
