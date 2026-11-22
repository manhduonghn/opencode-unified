package ai.opencode.server.testing

import ai.opencode.core.testing.TestIsolationGuard
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.TestNetworkBlock

/**
 * `:server`'s part of [TestIsolationGuard]: blocked network requests fail the test that made them,
 * and a [WireFixture] left open, or a server key written outside a store, is reported and reset.
 * Found through `META-INF/services`.
 */
class ServerTestIsolation : TestIsolationGuard.Plugin {
    override fun install(guard: TestIsolationGuard) {
        TestNetworkBlock.onBlocked = { method, scheme, host -> guard.recordBlockedNetworkRequest(scheme, host, method) }
        guard.register(
            TestIsolationGuard.Probe(
                name = "TestNetworkBlock.activeMockHosts",
                expected = "none",
                isDirty = { TestNetworkBlock.registeredMockHosts().isNotEmpty() },
                snapshot = { TestNetworkBlock.registeredMockHosts().sorted().joinToString(",").ifEmpty { "none" } },
                reset = { TestNetworkBlock.clearMocks() },
            ),
            TestIsolationGuard.sharedProbe("Shared.sessionAgents", ServerSharedKeys.sessionAgents, "{}"),
            TestIsolationGuard.sharedProbe("Shared.knownPrimaryAgents", ServerSharedKeys.knownPrimaryAgents, "[]"),
        )
    }
}
