package ai.opencode.feature.system.share

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.ServerID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class ServerIDTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test fun shareSheetIDsUseTheSameShape() {
        val first = ShareSendRequest.newMessageID()
        val second = ShareSendRequest.newMessageID()
        assertTrue(matches(first, prefix = "msg_") && first < second)
        val date = Instant.ofEpochSecond(1_800_000_000)
        // The session's randomness is the message ID's random tail, not its time.
        assertEquals("ses_ce7a3afffffeQQuLgSGsIe7ZHF", ServerID.newSessionID(at = date, entropy = "msg_15cb96740001QQuLgSGsIe7ZHF"))
    }

    private companion object {
        fun matches(id: String, prefix: String): Boolean =
            Regex("(msg|ses)_[0-9a-f]{12}[0-9A-Za-z]{14}").matches(id) && id.startsWith(prefix)
    }
}
