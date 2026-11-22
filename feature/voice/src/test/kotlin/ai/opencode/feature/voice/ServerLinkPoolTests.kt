package ai.opencode.feature.voice

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.retry.NetworkPathMonitor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** A call log reads the network from the shared path monitor. */
class ServerLinkPoolTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test fun aCallLogNamesTheNetworkFromTheSharedPath() {
        val wifi = NetworkPathMonitor.Snapshot.Interface(type = "wifi", name = "en0")
        val cellular = NetworkPathMonitor.Snapshot.Interface(type = "cellular", name = "pdp_ip0")
        val tunnel = NetworkPathMonitor.Snapshot.Interface(type = "other", name = "utun4")
        val satisfied = NetworkPathMonitor.Snapshot.Status.Satisfied
        assertEquals("Wi-Fi", VoiceCallLogRecorder.describe(NetworkPathMonitor.Snapshot(satisfied, listOf(wifi), isExpensive = false, isConstrained = false)))
        assertEquals("Wi-Fi · VPN", VoiceCallLogRecorder.describe(NetworkPathMonitor.Snapshot(satisfied, listOf(wifi, tunnel), isExpensive = false, isConstrained = false)))
        assertEquals(
            "cellular · expensive · Low Data Mode",
            VoiceCallLogRecorder.describe(NetworkPathMonitor.Snapshot(satisfied, listOf(cellular), isExpensive = true, isConstrained = true)),
        )
        assertEquals(
            "waiting for a connection",
            VoiceCallLogRecorder.describe(NetworkPathMonitor.Snapshot(NetworkPathMonitor.Snapshot.Status.RequiresConnection, emptyList(), isExpensive = false, isConstrained = false)),
        )
        assertEquals(
            "no connection",
            VoiceCallLogRecorder.describe(NetworkPathMonitor.Snapshot(NetworkPathMonitor.Snapshot.Status.Unsatisfied, emptyList(), isExpensive = false, isConstrained = false)),
        )
    }
}
