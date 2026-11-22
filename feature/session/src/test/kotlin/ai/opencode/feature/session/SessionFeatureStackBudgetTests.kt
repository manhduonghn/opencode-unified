package ai.opencode.feature.session

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertNull
import ai.opencode.feature.session.SessionTestSupport.distantPast
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.OpenCodeTranscriptPage
import org.junit.Test

/**
 * IOS paints the unused stack and measures how far a history page through `SessionFeature` wrote.
 * The JVM can't paint its stack, so this keeps the behaviour the probe guards.
 */
class SessionFeatureStackBudgetTests {
    companion object {
        /** Debug simulator: 582 KB with nested helpers, 187 KB run one by one. */
        const val BUDGET = 300 * 1024L
    }

    @Test
    fun historyPageThroughTheSessionReducerFitsItsStackBudget() = sessionTest { h ->
        val state = h.state {
            State.session(
                server = OpenCodeServer(url = "http://localhost:9", username = "", password = ""),
                session = OpenCodeSession(id = "ses_stack", serverTitle = "Stack", projectID = "p", directory = "/project", updatedAt = distantPast),
                cachedItems = listOf(OpenCodeTranscriptItem.UserText(id = "u1", text = "Hi")),
            )
        }.copy(earlierHistory = State.EarlierHistory.Loading(cursor = "older", reachedLoadedItems = true))
        val page = Action.EarlierHistoryResponse(
            sessionID = "ses_stack",
            generation = 0,
            cursor = "older",
            result = Result.success(OpenCodeTranscriptPage(items = listOf(OpenCodeTranscriptItem.UserText(id = "u0", text = "Earlier")), next = null)),
        )
        val values = h.values
        var failure: Throwable? = null
        val thread = Thread(null, {
            try {
                DependencyValues.withValues(values) { SessionFeature().reduce(state, page) }
            } catch (error: Throwable) {
                failure = error
            }
        }, "session-stack-budget", BUDGET)
        thread.start()
        thread.join()
        assertNull(failure, "A history page overflowed a ${BUDGET / 1024} KB stack: $failure")
    }
}
