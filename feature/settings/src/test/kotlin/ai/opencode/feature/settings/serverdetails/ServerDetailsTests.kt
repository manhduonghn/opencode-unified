package ai.opencode.feature.settings.serverdetails

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.settings.Locked
import ai.opencode.feature.settings.serverdetails.ServerDetailsFeature.Action
import ai.opencode.feature.settings.serverdetails.ServerDetailsFeature.SignIn
import ai.opencode.feature.settings.serverdetails.ServerDetailsFeature.State
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeAccount
import ai.opencode.server.model.OpenCodeAutocompleteSkill
import ai.opencode.server.model.OpenCodeMCPServer
import ai.opencode.server.model.OpenCodeMCPServer.Status
import ai.opencode.server.model.OpenCodeOAuthAttempt
import ai.opencode.server.model.OpenCodeOAuthStatus
import ai.opencode.server.model.OpenCodeServerCapabilities
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.net.URI
import kotlin.time.Duration.Companion.seconds

class ServerDetailsTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "secret")

    private val capabilities = OpenCodeServerCapabilities(
        location = "/Users/kit",
        mcpServers = listOf(
            OpenCodeMCPServer(name = "github", status = Status.Connected, integrationID = null),
            OpenCodeMCPServer(name = "playwright", status = Status.Disabled, integrationID = null),
        ),
        skills = listOf(OpenCodeAutocompleteSkill(id = "puckett", name = "puckett", description = "Interface Craft")),
        accounts = listOf(OpenCodeAccount(integrationID = "openai", provider = "OpenAI", label = "default", method = OpenCodeAccount.Method.OAuth)),
    )

    private val needsAuth = OpenCodeServerCapabilities(
        location = null,
        mcpServers = listOf(OpenCodeMCPServer(name = "linear", status = Status.NeedsAuth("Sign in"), integrationID = "linear")),
        skills = emptyList(),
        accounts = emptyList(),
    )

    @Test fun loadsOnceOnAppear() = runTest {
        val store = TestStore(State(server), ServerDetailsFeature(), this) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(serverCapabilities = { capabilities })
        }
        store.send(Action.Task) { it.copy(isLoading = true) }
        store.receive(Action.Loaded(capabilities)) { it.copy(isLoading = false, capabilities = capabilities) }
        store.send(Action.Task)
        store.finish()
    }

    @Test fun loadFailureKeepsTheError() = runTest {
        val store = TestStore(State(server), ServerDetailsFeature(), this) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(serverCapabilities = { throw OpenCodeClientError("Offline") })
        }
        store.send(Action.Task) { it.copy(isLoading = true) }
        store.receive(Action.LoadFailed("Offline")) { it.copy(isLoading = false, error = "Offline") }
        store.finish()
    }

    @Test fun connectingAnMCPServerRereadsTheList() = runTest {
        val calls = Locked(listOf<String>())
        val reloaded = capabilities.copy(
            mcpServers = capabilities.mcpServers.mapIndexed { index, mcp -> if (index == 1) mcp.copy(status = Status.Connected) else mcp },
        )
        val store = TestStore(State(server, capabilities = capabilities), ServerDetailsFeature(), this) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                setMCPConnected = { _, name, isConnected -> calls.update { it + "$name:$isConnected" } },
                serverCapabilities = { reloaded },
            )
        }
        store.send(Action.ConnectTapped("playwright")) { it.copy(changingMCP = setOf("playwright")) }
        store.receive(Action.MCPChanged("playwright")) { it.copy(changingMCP = emptySet(), isLoading = true) }
        store.receive(Action.Loaded(reloaded)) { it.copy(isLoading = false, capabilities = reloaded) }
        store.finish()
        assertEquals(listOf("playwright:true"), calls.value)
    }

    @Test fun failedMCPChangeSaysWhich() = runTest {
        val store = TestStore(State(server, capabilities = capabilities), ServerDetailsFeature(), this) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                setMCPConnected = { _, _, _ -> throw OpenCodeClientError("404") },
                serverCapabilities = { capabilities },
            )
        }
        store.send(Action.DisconnectTapped("github")) { it.copy(changingMCP = setOf("github")) }
        store.receive(Action.MCPChangeFailed("github", "404")) {
            it.copy(changingMCP = emptySet(), mcpError = "Couldn’t change github", isLoading = true)
        }
        store.receive(Action.Loaded(capabilities)) { it.copy(isLoading = false) }
        store.finish()
    }

    @Test fun problemsListSignInFirstAndHideWhileConnecting() {
        var state = State(
            server,
            capabilities = OpenCodeServerCapabilities(
                location = null,
                mcpServers = listOf(
                    OpenCodeMCPServer(name = "sentry", status = Status.Failed("exited"), integrationID = null),
                    OpenCodeMCPServer(name = "github", status = Status.Connected, integrationID = null),
                    OpenCodeMCPServer(name = "linear", status = Status.NeedsAuth("Sign in"), integrationID = "linear"),
                ),
                skills = emptyList(),
                accounts = emptyList(),
            ),
        )
        assertEquals(listOf("linear", "sentry"), state.problems.map { it.name })
        state = state.copy(changingMCP = setOf("sentry"))
        assertEquals(listOf("linear"), state.problems.map { it.name })
        assertEquals(Status.Pending, state.mcpServers.first { it.name == "sentry" }.status)
    }

    @Test fun signInOpensThePageWaitsForItThenConnects() = runTest {
        val clock = TestClock()
        val opened = Locked(listOf<URI>())
        val polls = Locked(0)
        val connected = Locked(listOf<String>())
        val attempt = OpenCodeOAuthAttempt(attemptID = "att_1", url = URI("https://linear.app/oauth"), instructions = "", mode = OpenCodeOAuthAttempt.Mode.Auto)
        val after = needsAuth.copy(mcpServers = needsAuth.mcpServers.map { it.copy(status = Status.Connected) })
        val store = TestStore(State(server, capabilities = needsAuth), ServerDetailsFeature(), this) {
            this[Clock] = clock
            this[OpenURLClient] = OpenURLClient { url -> opened.update { it + url } }
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                startIntegrationOAuth = { _, id ->
                    assertEquals("linear", id)
                    attempt
                },
                integrationOAuthStatus = { _, _, _ ->
                    polls.update { it + 1 }
                    if (polls.value >= 2) OpenCodeOAuthStatus.Complete else OpenCodeOAuthStatus.Pending
                },
                setMCPConnected = { _, name, _ -> connected.update { it + name } },
                serverCapabilities = { after },
            )
        }
        val signIn = SignIn(mcp = "linear", integrationID = "linear", attempt = attempt)
        store.send(Action.SignInTapped("linear")) { it.copy(changingMCP = setOf("linear"), signingIn = "linear") }
        store.receive(Action.SignInStarted(signIn)) { it.copy(signIn = signIn) }
        clock.advance(4.seconds)
        store.receive(Action.SignInFinished("linear", OpenCodeOAuthStatus.Complete)) { it.copy(signIn = null, signingIn = null) }
        store.receive(Action.MCPChanged("linear")) { it.copy(changingMCP = emptySet(), isLoading = true) }
        store.receive(Action.Loaded(after)) { it.copy(isLoading = false, capabilities = after) }
        store.finish()
        assertEquals(listOf(attempt.url), opened.value)
        assertEquals(listOf("linear"), connected.value)
    }

    @Test fun codeSignInCanBeCancelled() = runTest {
        val cancelled = Locked(listOf<String>())
        val attempt = OpenCodeOAuthAttempt(attemptID = "att_2", url = URI("https://example.com/device"), instructions = "Paste the code", mode = OpenCodeOAuthAttempt.Mode.Code)
        val store = TestStore(State(server, capabilities = needsAuth), ServerDetailsFeature(), this) {
            this[OpenURLClient] = OpenURLClient { }
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                startIntegrationOAuth = { _, _ -> attempt },
                cancelIntegrationOAuth = { _, _, id -> cancelled.update { it + id } },
            )
        }
        store.send(Action.SignInTapped("linear")) { it.copy(changingMCP = setOf("linear"), signingIn = "linear") }
        store.receive(Action.SignInStarted(SignIn(mcp = "linear", integrationID = "linear", attempt = attempt))) {
            it.copy(signIn = SignIn(mcp = "linear", integrationID = "linear", attempt = attempt), awaitingCode = true)
        }
        store.send(Action.CodeCancelled) {
            it.copy(awaitingCode = false, signIn = null, signingIn = null, changingMCP = emptySet())
        }
        store.finish()
        assertEquals(listOf("att_2"), cancelled.value)
    }

    @Test fun failedSignInSaysWhich() = runTest {
        val store = TestStore(State(server, capabilities = needsAuth), ServerDetailsFeature(), this) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(startIntegrationOAuth = { _, _ -> throw OpenCodeClientError("nope") })
        }
        store.send(Action.SignInTapped("linear")) { it.copy(changingMCP = setOf("linear"), signingIn = "linear") }
        store.receive(Action.SignInFinished("linear", OpenCodeOAuthStatus.Failed("nope"))) {
            it.copy(changingMCP = emptySet(), signingIn = null, mcpError = "Couldn’t sign in to linear")
        }
        store.finish()
    }

    @Test fun brandsUseKnownMarksElseMonograms() {
        val github = ServerBrand.mcp("github-enterprise") { true }
        assertEquals("GitHub", github.name)
        assertEquals("mcp-github", github.asset)
        val custom = ServerBrand.mcp("browser-control") { true }
        assertNull(custom.asset)
        assertEquals("BC", custom.monogram)
        assertEquals("Ex", ServerBrand.monogram("exa"))
        assertEquals("Monogram colours are stable", custom, ServerBrand.mcp("browser-control") { true })
    }

    @Test fun leavingThePageStopsSignInPolling() = runTest {
        val clock = TestClock()
        val polls = Locked(0)
        val cancelled = Locked(listOf<String>())
        val attempt = OpenCodeOAuthAttempt(attemptID = "att_3", url = URI("https://linear.app/oauth"), instructions = "", mode = OpenCodeOAuthAttempt.Mode.Auto)
        val store = TestStore(State(server, capabilities = needsAuth), ServerDetailsFeature(), this) {
            this[Clock] = clock
            this[OpenURLClient] = OpenURLClient { }
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                startIntegrationOAuth = { _, _ -> attempt },
                integrationOAuthStatus = { _, _, _ ->
                    polls.update { it + 1 }
                    OpenCodeOAuthStatus.Pending
                },
                cancelIntegrationOAuth = { _, _, id -> cancelled.update { it + id } },
            )
        }
        store.send(Action.SignInTapped("linear")) { it.copy(changingMCP = setOf("linear"), signingIn = "linear") }
        store.receive(Action.SignInStarted(SignIn(mcp = "linear", integrationID = "linear", attempt = attempt))) {
            it.copy(signIn = SignIn(mcp = "linear", integrationID = "linear", attempt = attempt))
        }
        clock.advance(2.seconds)
        store.send(Action.Disappeared) { it.copy(changingMCP = emptySet(), signingIn = null, signIn = null) }
        val pollsAtLeave = polls.value
        clock.advance(60.seconds)
        store.finish()
        assertEquals(pollsAtLeave, polls.value)
        assertEquals(listOf("att_3"), cancelled.value)
    }

    @Test fun leavingWhileLoadingCancelsTheRead() = runTest {
        val store = TestStore(State(server), ServerDetailsFeature(), this) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                serverCapabilities = {
                    delay(60.seconds)
                    capabilities
                },
            )
        }
        store.send(Action.Task) { it.copy(isLoading = true) }
        store.send(Action.Disappeared) { it.copy(isLoading = false) }
        store.finish()
    }
}
