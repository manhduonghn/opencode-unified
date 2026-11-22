package ai.opencode.feature.drawer

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeatureTestSupport.date
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.HomeFeatureTestSupport.snapshot
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.transcript.SessionSeenWatermark
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Opening or marking a session read clears its unread indicator at once. */
class HomeFeatureTestsUnread {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")

    private fun session(id: String, title: String, updatedAt: Instant, idle: Long? = null, viewed: Long? = null) =
        OpenCodeSession(id = id, serverTitle = title, projectID = "p", directory = "/project", updatedAt = updatedAt, idleMilliseconds = idle, viewedMilliseconds = viewed)

    private fun key(id: String) = "${server.persistenceID}|$id"

    @Test
    fun markReadClearsServerUnreadIndicatorBeforeReceiptCompletes() = homeTest { h ->
        val session = session("ses_unread", "Unread", Instant.now(), idle = 2_000, viewed = 1_000)
        val store = h.store(h.state { HomeFeature.State(server, snapshot(listOf(session))) }) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(viewSession = { _, _, _ -> throw OpenCodeClientError("Offline") })
        }
        assertEquals(HomeFeature.State.Indicator.unread, store.state.indicator(session))
        store.send(Action.MarkRead(session.id)) {
            it.copy(
                sessions = listOf(it.sessions[0].copy(viewedMilliseconds = 2_000)),
                locallyViewedMilliseconds = it.locallyViewedMilliseconds + (session.id to 2_000L),
                sessionReadDates = it.sessionReadDates + (key(session.id) to session.updatedAt),
            ).settingSeenWatermark(SessionSeenWatermark(itemID = null, viewedMilliseconds = 2_000), session.id)
        }
        assertEquals(HomeFeature.State.Indicator.none, store.state.indicator(store.state.sessions[0]))
        store.finish()
    }

    @Test
    fun openingSessionClearsUnreadInTheSelectionStep() = homeTest { h ->
        val session = session("ses_open", "Open me", date(10), idle = 2_000, viewed = 1_000)
        val state = h.state {
            HomeFeature.State(server, snapshot(listOf(session)))
                .copy(transcriptCache = mapOf(session.id to emptyList()))
                .updatingManuallyUnreadSessionKeys { it + key(session.id) }
        }
        val store = h.store(state) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(viewSession = { _, _, _ -> throw OpenCodeClientError("Offline") })
        }

        store.send(Action.SessionTapped(session.id)) {
            val viewed = session.copy(viewedMilliseconds = 2_000)
            it.copy(
                sessions = listOf(viewed),
                locallyViewedMilliseconds = it.locallyViewedMilliseconds + (session.id to 2_000L),
                lastViewedSessionIDs = it.lastViewedSessionIDs + (server.persistenceID to session.id),
                sessionReadDates = it.sessionReadDates + (key(session.id) to session.updatedAt),
                manuallyUnreadSessionKeys = it.manuallyUnreadSessionKeys - key(session.id),
                detailGeneration = 2,
                sessionDetail = h.state {
                    SessionFeature.State.session(server, session, cachedItems = emptyList(), navigationID = 2)
                        .copy(newMessagesBaseline = emptySet(), newMessagesUnread = true)
                },
            )
        }
        assertEquals(HomeFeature.State.Indicator.none, store.state.indicator(store.state.sessions[0]))
        store.finish()
    }
}
