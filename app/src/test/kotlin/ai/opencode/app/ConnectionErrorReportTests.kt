package ai.opencode.app

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.ConnectionFailureDetail
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.retry.TransportFailure
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant

class ConnectionErrorReportTests {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()

    @Test
    fun connectionFailurePreservesEveryCandidateInStateAndReport() = runTest {
        val server = OpenCodeServer(
            url = "https://studio.test.ts.net:4096", username = "opencode", password = "",
            alternateURLs = listOf("http://studio.local:4096"),
        )
        val details = listOf(
            ConnectionFailureDetail(server.url.toHttpUrl(), SocketTimeoutException("timeout"), addresses = listOf("34.117.59.81")),
            ConnectionFailureDetail("http://studio.local:4096".toHttpUrl(), UnknownHostException("fixture")),
        )
        val state = shared.appState().copy(connectionGeneration = 2)
        val store = shared.appStore(state, this) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                health = { candidate ->
                    throw OpenCodeClientError(
                        OpenCodeClientError.UNREACHABLE_MESSAGE,
                        connectionFailures = details.filter { it.endpoint == candidate.url },
                    )
                },
            )
        }
        val error = OpenCodeClientError(OpenCodeClientError.UNREACHABLE_MESSAGE, connectionFailures = details)
        store.send(AppFeature.Action.ServerTapped(server)) {
            it.load(server).copy(
                connection = AppFeature.State.Connection.Connecting,
                connectionGeneration = 3,
                connectionAttempt = AppFeature.ConnectionAttempt(id = 3, server = server),
            )
        }
        store.receiveCase<AppFeature.Action.HealthResponse> {
            it.copy(
                connectionAttempt = null,
                connection = AppFeature.State.Connection.Failed(
                    message = error.message,
                    context = AppFeature.FailureContext.of(server, AppFeature.FailureContext.Phase.Connect, 3, details),
                ),
            )
        }
        store.send(AppFeature.Action.HealthResponse(2, Result.failure(OpenCodeClientError.unreachable(TransportFailure.NotSent))))
        val report = store.state.errorReport(appVersion = "1", build = "2", generatedAt = Instant.EPOCH)!!
        assertTrue(report, report.contains("Candidate 1: URLError -1003 cannotFindHost · http://studio.local:4096"))
        assertTrue(report, report.contains("Candidate 2: URLError -1001 timedOut · https://studio.test.ts.net:4096"))
        assertTrue(report, report.contains("Resolved address (lookup after failure): 34.117.59.81"))
        store.finish()
    }

    @Test
    fun reportContainsOnlySafeTargetAndExplicitMetadata() {
        val server = OpenCodeServer(
            url = "https://url-user:url-password@box.example:4096/private/path?token=query-secret#fragment-secret",
            username = "auth-user", password = "auth-password",
        )
        val state = shared.appState().copy(
            connection = AppFeature.State.Connection.Failed(
                message = "Server returned HTTP 503.",
                context = AppFeature.FailureContext.of(server, AppFeature.FailureContext.Phase.LoadSessions, 7),
            ),
        )
        assertEquals(
            """
            OpenCode connection error
            App: OpenCode 1.2.3 (45)
            Report generated at: 1970-01-01T00:00:00Z
            Phase: Load sessions
            Server: box.example:4096
            Attempt: 7

            Message:
            Server returned HTTP 503.
            """.trimIndent(),
            state.errorReport(appVersion = "1.2.3", build = "45", generatedAt = Instant.EPOCH),
        )
    }

    @Test
    fun malformedAddressesNeverFallBackToRawInput() {
        for (url in listOf(
            "not a URL with private data",
            "https://",
            "https://example.com:invalid/private?secret=value",
            "file:///private/secret",
            "https://box%0Asecret.example/private",
            "https://box%40secret.example/private",
            "https://user:userinfo-secret@:invalid/path?token=query-secret",
            "not-a-url?token=query-secret",
        )) {
            val context = AppFeature.FailureContext.of(
                OpenCodeServer(url = url, username = "private", password = "secret"),
                AppFeature.FailureContext.Phase.Connect, 1,
                details = listOf(ConnectionFailureDetail("https://safe.example".toHttpUrl(), OpenCodeClientError("bad URL", urlErrorCode = -1000))),
            )
            assertEquals(url, "Unavailable (invalid address)", context.target)
            assertEquals(url, "server", context.friendlyName)
            val state = shared.appState().copy(connection = AppFeature.State.Connection.Failed(message = "Couldn’t connect", context = context))
            val report = state.errorReport(appVersion = "1", build = "2", generatedAt = Instant.EPOCH)!!
            assertTrue(url, report.contains("Couldn’t connect to server\n"))
            assertFalse(url, report.contains("userinfo-secret"))
            assertFalse(url, report.contains("query-secret"))
        }
    }

    @Test
    fun targetPreservesIPv6PortAndLongHostnameWithoutTruncation() {
        val ipv6 = AppFeature.FailureContext.of(
            OpenCodeServer(url = "http://[::1]:4096/private?token=secret", username = "", password = ""),
            AppFeature.FailureContext.Phase.Connect, 1,
        )
        assertEquals("[::1]:4096", ipv6.target)
        val host = "long-hostname.".repeat(12) + "example.com"
        val long = AppFeature.FailureContext.of(
            OpenCodeServer(url = "https://$host:8443/private", username = "", password = ""),
            AppFeature.FailureContext.Phase.Connect, 2,
        )
        assertEquals("$host:8443", long.target)
    }

    @Test
    fun acceptedFailureKeepsItsTargetAfterStaleResponsesAndFormEdits() = runTest {
        for (afterHealth in listOf(false, true)) {
            val first = OpenCodeServer(url = "https://a.example", username = "alice", password = "a-secret")
            val selected = OpenCodeServer(
                url = "https://b.example/private?token=secret", username = "bob", password = "b-secret",
                alternateURLs = listOf("https://b-alternate.example:4096"),
            )
            val winner = selected.at("https://b-alternate.example:4096")
            val state = shared.appState().copy(
                connectionGeneration = 2,
                connectionAttempt = AppFeature.ConnectionAttempt(id = 2, server = selected),
                connection = if (afterHealth) AppFeature.State.Connection.LoadingHome(winner) else AppFeature.State.Connection.Connecting,
                // The failure context must come from the attempt, not these unrelated fields.
                serverURL = "https://form.example/private?token=other-secret",
                username = "form-user",
                password = "form-secret",
            )
            val store = shared.appStore(state, this)
            store.exhaustive = false
            val error = OpenCodeClientError("Server returned HTTP 503.")

            if (afterHealth) {
                store.send(AppFeature.Action.HomeResponse(2, Result.failure(error)))
            } else {
                store.send(AppFeature.Action.HealthResponse(2, Result.failure(error)))
            }
            val expected = AppFeature.FailureContext.of(
                if (afterHealth) winner else selected,
                if (afterHealth) AppFeature.FailureContext.Phase.LoadSessions else AppFeature.FailureContext.Phase.Connect,
                2,
            )
            assertEquals(expected, store.state.errorContext)

            store.send(AppFeature.Action.HealthResponse(1, Result.success(first)))
            store.send(AppFeature.Action.HealthResponse(1, Result.failure(OpenCodeClientError("Stale A health failure"))))
            store.send(AppFeature.Action.HomeResponse(1, Result.success(emptySnapshot)))
            store.send(AppFeature.Action.HomeResponse(1, Result.failure(OpenCodeClientError("Stale A home failure"))))
            store.send(AppFeature.Action.ServerURLChanged("https://edited.example"))

            assertEquals(expected, store.state.errorContext)
            assertEquals(error.message, store.state.errorMessage)
            val report = store.state.errorReport(appVersion = "1", build = "2", generatedAt = Instant.EPOCH)!!
            assertTrue(report.contains("Server: ${if (afterHealth) "b-alternate.example:4096" else "b.example"}"))
            assertTrue(report.contains("Attempt: 2"))
            assertFalse(report.contains("secret"))
            assertFalse(report.contains("form.example"))
            assertFalse(report.contains("edited.example"))
            store.finish()
        }
    }

    @Test
    fun invalidPairingCodeReportsOnlyTheSafeMessage() = runTest {
        val store = shared.appStore(shared.appState(), this)
        store.exhaustive = false
        store.send(AppFeature.Action.PairingCodeScanned("invalid QR containing a private password"))
        assertNull(store.state.errorContext)
        val report = store.state.errorReport(appVersion = "1", build = "2", generatedAt = Instant.EPOCH)
        assertTrue(report?.contains("That isn’t an OpenCode pairing code.") == true)
        assertFalse(report?.contains("private password") == true)
        assertFalse(report?.contains("Server:") == true)
        assertFalse(report?.contains("Attempt:") == true)
        store.finish()
    }
}
