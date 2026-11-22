package ai.opencode.feature.composer

import ai.opencode.server.OpenCodeClientError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * Every other test in the file drives `SessionFeature` (the outbox's sends reach the store as
 * `queuedInputs`, `failedSends`, `deliveryNotes` and `promptStates`), so they can't live in this
 * module.
 */
class SendOutboxTests {
    // Retry-After

    @Test fun retryAfterIsReadAsSecondsOrADate() {
        assertEquals(7.0, OpenCodeClientError.retryAfter("7")!!, 0.0)
        assertNull(OpenCodeClientError.retryAfter(null))
        val now = Instant.ofEpochSecond(1_000_000)
        assertEquals(20.0, OpenCodeClientError.retryAfter("Mon, 12 Jan 1970 13:47:00 GMT", now = now)!!, 0.0)
    }
}
