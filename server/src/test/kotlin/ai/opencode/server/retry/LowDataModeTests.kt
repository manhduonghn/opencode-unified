package ai.opencode.server.retry

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * `constrainedNetworkRefusalIsNeverRetryableTransportFailure` is dropped: it checks
 * `URLError.networkUnavailableReason ==.constrained`; Android has no such refusal error, so
 * `TransportFailure` has nothing to classify.
 */
class LowDataModeTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun networkPathSnapshotPreservesConstrainedAndExpensiveFlags() {
        val normal = NetworkPathMonitor.Snapshot(isSatisfied = true, primaryInterface = "wifi en0")
        assertFalse(normal.isConstrained)
        assertFalse(normal.isExpensive)
        assertFalse(NetworkPathMonitor.shouldDeferLargePrefetch(normal, isLowPowerModeEnabled = false))
        assertFalse(NetworkPathMonitor.shouldDeferLargePrefetch(normal, isLowPowerModeEnabled = true))

        val constrainedWiFi = NetworkPathMonitor.Snapshot(
            isSatisfied = true, primaryInterface = "wifi en0", isExpensive = false, isConstrained = true,
        )
        assertTrue(constrainedWiFi.isConstrained)
        assertFalse(constrainedWiFi.isExpensive)
        assertTrue(NetworkPathMonitor.shouldDeferLargePrefetch(constrainedWiFi, isLowPowerModeEnabled = false))

        val cellular = NetworkPathMonitor.Snapshot(
            isSatisfied = true, primaryInterface = "cellular pdp_ip0", isExpensive = true, isConstrained = false,
        )
        assertTrue(cellular.isExpensive)
        assertFalse(cellular.isConstrained)
        assertFalse(NetworkPathMonitor.shouldDeferLargePrefetch(cellular, isLowPowerModeEnabled = false))
        assertTrue(NetworkPathMonitor.shouldDeferLargePrefetch(cellular, isLowPowerModeEnabled = true))
    }
}
