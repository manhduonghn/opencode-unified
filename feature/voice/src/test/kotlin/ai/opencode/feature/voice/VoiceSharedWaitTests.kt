package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.withDependenciesSuspending
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.feature.voice.VoiceFeatureTestSupport.copy
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * A session has one `/wait` however many wait on it: its reply watches and
 * the delegator's `wait_for_session` share it, each leaves on its own, and
 * the poll stops with the last of them or with the call.
 */
class VoiceSharedWaitTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val otherServer = OpenCodeServer(url = "http://studio:4096", username = "", password = "")
    private val home = OpenCodeSession(id = "ses_home", serverTitle = "HOME", projectID = "home", directory = "/Users/kit/code/home", updatedAt = Instant.ofEpochSecond(400))

    /** A `/wait` stub that runs until its session's outcome is set or it's cancelled. */
    private class Poll {
        val calls = Locked(listOf<String>())
        val cancelled = Locked(listOf<String>())
        val outcomes = Locked(mapOf<String, Result<Unit>>())

        fun settle(sessionID: String, result: Result<Unit> = Result.success(Unit)) {
            outcomes.update { it + (sessionID to result) }
        }

        val client: OpenCodeClient
            get() = OpenCodeClient.testValue.copy(
                waitForIdle = { _, sessionID ->
                    calls.update { it + sessionID }
                    try {
                        var outcome = outcomes.value[sessionID]
                        while (outcome == null) {
                            delay(5)
                            outcome = outcomes.value[sessionID]
                        }
                        outcome.getOrThrow()
                    } catch (error: CancellationException) {
                        cancelled.update { it + sessionID }
                        throw error
                    }
                },
            )
    }

    private suspend fun until(condition: suspend () -> Boolean): Boolean {
        repeat(500) {
            if (condition()) return true
            delay(10)
        }
        return condition()
    }

    /**
     * The same session ID on two servers: the local server's settlement and
     * active-session snapshot end only the local wait, never the remote one
     * (which would read as `finished: true` before it finished).
     */
    @Test fun aSettlementEndsOnlyItsOwnServersWait() = runBlocking<Unit> {
        val coordinator = VoiceWorkCoordinator()
        val poll = Poll()
        val client = poll.client
        val localDone = Locked(false)
        val remoteDone = Locked(false)
        val local = async {
            coordinator.waitForIdleOrSettlement(server, sessionID = "ses_home", client = client)
            localDone.set(true)
        }
        val remote = async {
            coordinator.waitForIdleOrSettlement(otherServer, sessionID = "ses_home", client = client)
            remoteDone.set(true)
        }
        assertTrue(until { coordinator.openWaitCount() == 2 })

        coordinator.noteActiveSessions(server, setOf("ses_other"))
        local.await()
        assertTrue(localDone.value)
        coordinator.noteSessionSettled(server, "ses_home")
        delay(100)
        assertFalse(remoteDone.value)
        assertEquals(1, coordinator.waiterCount(otherServer, sessionID = "ses_home"))

        coordinator.noteActiveSessions(otherServer, setOf("ses_home"))
        delay(50)
        assertFalse(remoteDone.value)
        coordinator.noteSessionSettled(otherServer, "ses_home")
        remote.await()
        assertTrue(remoteDone.value)
        assertTrue(until { coordinator.openWaitCount() == 0 })
    }

    @Test fun waitsOnOneSessionShareOneWait() = runBlocking<Unit> {
        val coordinator = VoiceWorkCoordinator()
        val poll = Poll()
        val client = poll.client
        val first = async { coordinator.sharedWaitForIdle(server, sessionID = "ses_home", client = client) }
        val second = async { coordinator.sharedWaitForIdle(server, sessionID = "ses_home", client = client) }
        assertTrue(until { coordinator.waiterCount(server, sessionID = "ses_home") == 2 })
        assertEquals(listOf("ses_home"), poll.calls.value)

        poll.settle("ses_home")
        first.await()
        second.await()
        assertEquals(0, coordinator.openWaitCount())
        assertTrue(poll.cancelled.value.isEmpty())

        // A wait after it settled is a fresh one.
        coordinator.sharedWaitForIdle(server, sessionID = "ses_home", client = client)
        assertEquals(listOf("ses_home", "ses_home"), poll.calls.value)
        assertEquals(0, coordinator.openWaitCount())
    }

    @Test fun aFailedWaitFailsEveryoneOnIt() = runBlocking<Unit> {
        val coordinator = VoiceWorkCoordinator()
        val poll = Poll()
        val client = poll.client
        val first = async { runCatching { coordinator.sharedWaitForIdle(server, sessionID = "ses_home", client = client) } }
        val second = async { runCatching { coordinator.sharedWaitForIdle(server, sessionID = "ses_home", client = client) } }
        assertTrue(until { coordinator.waiterCount(server, sessionID = "ses_home") == 2 })

        poll.settle("ses_home", Result.failure(OpenCodeClientError(message = "HTTP 500")))
        val results = listOf(first.await(), second.await())
        assertEquals(listOf("HTTP 500", "HTTP 500"), results.map { (it.exceptionOrNull() as? OpenCodeClientError)?.message })
        assertEquals(listOf("ses_home"), poll.calls.value)
        assertEquals(0, coordinator.openWaitCount())
    }

    @Test fun eachWaiterLeavesOnItsOwnAndTheLastStopsThePoll() = runBlocking<Unit> {
        val coordinator = VoiceWorkCoordinator()
        val poll = Poll()
        val client = poll.client
        val first = async { coordinator.sharedWaitForIdle(server, sessionID = "ses_home", client = client) }
        val second = async { coordinator.sharedWaitForIdle(server, sessionID = "ses_home", client = client) }
        assertTrue(until { coordinator.waiterCount(server, sessionID = "ses_home") == 2 })

        // One leaving returns at once and leaves the other waiting.
        first.cancel()
        assertTrue(runCatching { first.await() }.isFailure)
        assertTrue(until { coordinator.waiterCount(server, sessionID = "ses_home") == 1 })
        delay(50)
        assertTrue(poll.cancelled.value.isEmpty())

        // The last one leaving stops the poll.
        second.cancel()
        assertTrue(runCatching { second.await() }.isFailure)
        assertTrue(until { poll.cancelled.value == listOf("ses_home") })
        assertEquals(0, coordinator.openWaitCount())
        assertEquals(listOf("ses_home"), poll.calls.value)

        // Already cancelled: it never opens a wait. An unstructured task: its `leftWait` doesn't
        // fail this test's scope.
        val late = detachedScope(coroutineContext).async {
            coroutineContext.job.cancel()
            coordinator.sharedWaitForIdle(server, sessionID = "ses_home", client = client)
        }
        assertTrue(runCatching { late.await() }.isFailure)
        delay(50)
        assertEquals(listOf("ses_home"), poll.calls.value)
        assertEquals(0, coordinator.openWaitCount())
    }

    @Test fun otherSessionsAndServersWaitApart() = runBlocking<Unit> {
        val coordinator = VoiceWorkCoordinator()
        val poll = Poll()
        val client = poll.client
        val home = async { coordinator.sharedWaitForIdle(server, sessionID = "ses_home", client = client) }
        val ios = async { coordinator.sharedWaitForIdle(server, sessionID = "ses_ios", client = client) }
        val studioHome = async { coordinator.sharedWaitForIdle(otherServer, sessionID = "ses_home", client = client) }
        assertTrue(until { coordinator.openWaitCount() == 3 })
        assertTrue(until { poll.calls.value.size == 3 })
        assertEquals(listOf("ses_home", "ses_home", "ses_ios"), poll.calls.value.sorted())

        poll.settle("ses_ios")
        ios.await()
        assertEquals(2, coordinator.openWaitCount())
        assertEquals(1, coordinator.waiterCount(server, sessionID = "ses_home"))
        assertEquals(1, coordinator.waiterCount(otherServer, sessionID = "ses_home"))

        poll.settle("ses_home")
        home.await()
        studioHome.await()
        assertEquals(0, coordinator.openWaitCount())
    }

    /**
     * The report's case: a reply watch and the delegator's `wait_for_session`
     * on one session made two `/wait`s. Now both ride one.
     */
    @Test fun aReplyWatchAndWaitForSessionShareOneWait() = runBlocking<Unit> {
        val coordinator = VoiceWorkCoordinator()
        val poll = Poll()
        val client = poll.client.copy(
            assistantReply = { _, _, promptID -> if (promptID == "msg_inbox") "Inbox is clear." else null },
        )
        val work = VoiceWorkClient.live(coordinator)
        withDependenciesSuspending({
            this[OpenCodeClient] = client
            this[Clock] = ImmediateClock()
        }) {
            coroutineScope {
                val reply = async { work.awaitReply(server, "ses_home", "msg_inbox") }
                val idle = async { work.awaitIdle(server, "ses_home") }
                assertTrue(until { coordinator.waiterCount(server, sessionID = "ses_home") == 2 })
                assertEquals(listOf("ses_home"), poll.calls.value)
                poll.settle("ses_home")
                assertEquals("Inbox is clear.", reply.await())
                idle.await()
            }
        }
        assertEquals(listOf("ses_home"), poll.calls.value)
        assertEquals(0, coordinator.openWaitCount())
    }

    /**
     * `wait_for_session` waits through the call's shared wait, not a `/wait`
     * of its own.
     */
    @Test fun waitForSessionUsesTheCallsWait() = runBlocking<Unit> {
        val issues = Locked(listOf<String>())
        val client = OpenCodeClient.testValue.copy(
            getSession = { _, _ -> home },
            waitForIdle = { _, _ -> issues.update { it + "wait_for_session made its own /wait" } },
            transcript = { _, _, _ -> listOf(assistantMarkdown("msg_reply", "Inbox is clear.")) },
        )
        val waited = Locked(listOf<String>())
        val workspace = VoiceDelegatorWorkspace(
            client = client,
            start = VoiceDelegatorWorkspace.Start(server = server, viewing = home, draftDirectory = home.directory, draftModel = null, transcriptDelta = emptyList(), explained = emptySet()),
            effects = VoiceDelegatorWorkspace.Effects(
                open = {},
                explained = {},
                awaitIdle = { _, sessionID -> waited.update { it + sessionID } },
                note = {},
            ),
        )
        val output = workspace.run(VoiceDelegator.ToolCall(callID = "c", name = "wait_for_session", arguments = "{}"))
        assertTrue(output, output.contains(""""finished":true"""))
        assertEquals(listOf("ses_home"), waited.value)
        assertEquals(emptyList<String>(), issues.value)
    }

    /**
     * Two watched prompts in one session make one `/wait`, and ending the call
     * stops it: no effect is left running and the poll is cancelled.
     */
    @Test fun endingTheCallStopsTheSessionsWait() = runBlocking<Unit> {
        val coordinator = VoiceWorkCoordinator()
        val poll = Poll()
        val issues = Locked(listOf<String>())
        val shared = SpeechTestShared()
        val voice = shared.run {
            shared(SharedKeys.openAIAPIKey).set("sk-test")
            VoiceFeature.State(server, home).withPhase(VoiceFeature.State.Phase.Live)
        }
        val effects = detachedScope(coroutineContext)
        val store = TestStore(voice, VoiceFeature(), effects) {
            this[SharedStore] = shared.store
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            this[OpenCodeClient] = poll.client
            this[VoiceWorkClient] = VoiceWorkClient.live(coordinator).copy(
                activeSessionIDs = { throw OpenCodeClientError(message = "Offline") },
                checkSessionActive = { _, _ -> true },
                recentMessages = { _, _, _ -> emptyList() },
            )
            this[VoiceCallClient] = speechVoiceCall(
                announce = { _, _, _ -> issues.update { it + "nothing settled, so nothing is said" } },
                cancelPendingAnnouncements = {},
            )
        }
        store.exhaustive = false
        store.send(
            VoiceFeature.Action.ReplyWatch(
                ReplyWatch.Event.Sent(
                    listOf(
                        ReplyWatch.Prompt(promptID = "msg_inbox", sessionID = "ses_home", title = "HOME", topic = "Check my inbox"),
                        ReplyWatch.Prompt(promptID = "msg_dms", sessionID = "ses_home", title = "HOME", topic = "Read my DMs"),
                    ),
                ),
            ),
        )
        assertTrue(until { coordinator.waiterCount(server, sessionID = "ses_home") == 2 })
        assertEquals(listOf("ses_home"), poll.calls.value)

        store.send(VoiceFeature.Action.CallDropped)
        store.skipReceivedActions()
        store.exhaustive = true
        store.finish(timeout = 3.seconds)
        assertTrue(until { poll.cancelled.value == listOf("ses_home") })
        assertEquals(0, coordinator.openWaitCount())
        assertEquals(listOf("ses_home"), poll.calls.value)
        assertEquals(emptyList<String>(), issues.value)
        effects.skipInFlightEffects()
    }
}
