package ai.opencode.server.retry

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.UrlErrorCode
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.testing.WireFixture
import ai.opencode.server.testing.WireFixture.Reply
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Form replies and dismissals read the form back before failing. */
class NetworkRetryFormTests {
    @get:Rule val isolation = TestIsolationRule()

    private val client get() = OpenCodeClient.liveValue
    private val yes = mapOf<String, OpenCodeFormValue>("q0" to OpenCodeFormValue.Str("Yes"))

    /** Requests under the shared policy, with an instant clock and fixed jitter (as `NetworkRetryWireTests`). */
    private suspend fun <T> retrying(operation: suspend () -> T): T =
        NetworkRetry.policy.withValue(RetryPolicy.request) {
            NetworkRetry.clock.withValue(ScriptedClock()) {
                NetworkRetry.environment.withValue(RetryEnvironment.online()) { operation() }
            }
        }

    private inline fun <T> WireFixture.use(block: (WireFixture) -> T): T = try { block(this) } finally { close() }

    private suspend fun expectClientError(block: suspend () -> Unit): OpenCodeClientError {
        try {
            block()
        } catch (error: OpenCodeClientError) {
            return error
        }
        fail("expected an OpenCodeClientError")
        error("unreachable")
    }

    private fun formReply(status: String, extra: String? = null): Reply {
        val settled = extra ?: if (status == "answered") ""","answer":{"q0":"Yes"}""" else ""
        return Reply.Json(200, """{"data":{"id":"frm_1","sessionID":"ses_1","title":"Q","fields":[],"state":{"status":"$status"$settled}}}""")
    }

    @Test
    fun aFormReplyOnADeadConnectionIsReadBackAndSentAgainOnlyWhilePending() = runTest {
        // A pooled connection the phone's sleep left dead fails the first POST while the
        // event stream stays up. The form, still pending, gets the reply once more.
        val status = AtomicReference("pending")
        val posts = AtomicInteger(0)
        WireFixture { request ->
            when (request.route) {
                "POST /api/session/ses_1/form/frm_1/reply" -> {
                    if (posts.incrementAndGet() <= 1) {
                        Reply.Failure(UrlErrorCode.NETWORK_CONNECTION_LOST)
                    } else {
                        status.set("answered")
                        Reply.status(204)
                    }
                }
                "GET /api/session/ses_1/form/frm_1" -> formReply(status.get())
                else -> Reply.status(404)
            }
        }.use { stale ->
            retrying { client.replyForm(stale.server, "ses_1", "frm_1", yes) }
            assertEquals(
                listOf("POST /api/session/ses_1/form/frm_1/reply", "GET /api/session/ses_1/form/frm_1", "POST /api/session/ses_1/form/frm_1/reply"),
                stale.requests.map { it.route },
            )
        }

        // The reply landed and only its answer was lost: read back, not repeated.
        WireFixture { request ->
            if (request.route == "GET /api/session/ses_1/form/frm_1") formReply("answered") else Reply.Failure(UrlErrorCode.TIMED_OUT)
        }.use { lost ->
            retrying { client.replyForm(lost.server, "ses_1", "frm_1", yes) }
            assertEquals(1, lost.requests.count { it.method == "POST" })
        }

        // A 409 for a form answered meanwhile is settled; for one dismissed, a failure.
        WireFixture { request ->
            if (request.route == "GET /api/session/ses_1/form/frm_1") formReply("answered") else Reply.Json(409, "{}")
        }.use { answered ->
            retrying { client.replyForm(answered.server, "ses_1", "frm_1", yes) }
        }
        WireFixture { request ->
            if (request.route == "GET /api/session/ses_1/form/frm_1") formReply("cancelled") else Reply.Json(409, "{}")
        }.use { dismissed ->
            expectClientError { retrying { client.replyForm(dismissed.server, "ses_1", "frm_1", yes) } }
            assertEquals(1, dismissed.requests.count { it.method == "POST" })
        }
    }

    @Test
    fun aFormSettledWithAnotherAnswerIsAFinalFailure() = runTest {
        // A 409 is success only when the form holds what we sent.
        WireFixture { request ->
            if (request.route == "GET /api/session/ses_1/form/frm_1") formReply("answered", ""","answer":{"q0":"No"}""") else Reply.Json(409, "{}")
        }.use { elsewhere ->
            val error = expectClientError { retrying { client.replyForm(elsewhere.server, "ses_1", "frm_1", yes) } }
            assertTrue(error.requestSettled)
            assertEquals("Already answered elsewhere", error.message)
            assertEquals(1, elsewhere.requests.count { it.method == "POST" })
        }

        // A form already gone answers 404: final, and nothing reads it back.
        WireFixture { Reply.Json(404, "{}") }.use { gone ->
            val error = expectClientError { retrying { client.replyForm(gone.server, "ses_1", "frm_1", yes) } }
            assertTrue(error.requestSettled)
            assertEquals(listOf("POST"), gone.requests.map { it.method })
        }

        // A dismissal: the same reason (or none) is success, another is final.
        WireFixture { request ->
            if (request.route == "GET /api/session/ses_1/form/frm_1") formReply("cancelled", ""","message":"Later"""") else Reply.Json(409, "{}")
        }.use { sameReason ->
            retrying { client.cancelForm(sameReason.server, "ses_1", "frm_1", "Later") }
            expectClientError { retrying { client.cancelForm(sameReason.server, "ses_1", "frm_1", null) } }
        }
    }

    @Test
    fun aFormReplyStillPendingAfterTwoDeadConnectionsStaysAFailure() = runTest {
        WireFixture { request ->
            if (request.route == "GET /api/session/ses_1/form/frm_1") formReply("pending") else Reply.Failure(UrlErrorCode.NETWORK_CONNECTION_LOST)
        }.use { fixture ->
            expectClientError { retrying { client.replyForm(fixture.server, "ses_1", "frm_1", yes) } }
            assertEquals(2, fixture.requests.count { it.method == "POST" })
        }
    }

    @Test
    fun aDismissalWhoseResponseWasLostIsReadBack() = runTest {
        WireFixture { request ->
            if (request.route == "GET /api/session/ses_1/form/frm_1") formReply("cancelled") else Reply.Failure(UrlErrorCode.NETWORK_CONNECTION_LOST)
        }.use { fixture ->
            retrying { client.cancelForm(fixture.server, "ses_1", "frm_1", null) }
            assertEquals(listOf("DELETE /api/session/ses_1/form/frm_1", "GET /api/session/ses_1/form/frm_1"), fixture.requests.map { it.route })
        }
    }
}
