package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.transcript.SessionLinkTarget
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant
import java.util.Collections
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * `aRowWhoseLookupFailedAsksAgainWhenItNextAppears` drives the screen with `sessionLinksAppeared`
 * as the transcript's rows send it.
 */
class SessionLinkTests {
    @get:Rule val isolation = TestIsolationRule()

    private companion object {
        const val ID = "ses_ea0cb2871ffeNzq62aRonBKB2l"
        const val OTHER = "ses_e9ff44bffffe07ia9B2fLdhYb2"
        const val ROOT = "ses_rootrootrootrootrootrootro"

        fun found(id: String) = OpenCodeSession(
            id = id, serverTitle = "T${id.last()}", projectID = "p", directory = "/p", updatedAt = Instant.ofEpochSecond(5),
        )
    }

    private val server = OpenCodeServer(url = "https://links.example", username = "", password = "")

    private fun session(id: String, title: String = "Home", parent: String? = null) = OpenCodeSession(
        id = id, serverTitle = title, projectID = "project", directory = "/project", updatedAt = Instant.ofEpochSecond(10), parentID = parent,
    )

    private fun home(): HomeFeature.State {
        val root = session(ROOT, title = "Root")
        val listed = session(OTHER, title = "HOME")
        val home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(root, listed), activeSessionIDs = emptySet()))
            .copy(transcriptCache = mapOf(root.id to emptyList(), listed.id to emptyList()))
        val (state, detail) = home.makeDetail(root, cachedItems = emptyList())
        return state.copy(sessionDetail = detail)
    }

    private fun HomeFeature.State.withLinks(links: Map<String, SessionLinkTarget>) =
        copy(sessionDetail = sessionDetail!!.copy(sessionLinks = links))

    private fun HomeFeature.State.links(update: (Map<String, SessionLinkTarget>) -> Map<String, SessionLinkTarget>) =
        withLinks(update(sessionDetail!!.sessionLinks))

    private fun resolve(ids: List<String>) = HomeFeature.Action.detail(SessionFeature.Action.Delegate(SessionFeature.Delegate.ResolveSessionLinks(ids)))
    private fun open(id: String) = HomeFeature.Action.detail(SessionFeature.Action.Delegate(SessionFeature.Delegate.OpenSessionLink(id)))

    @Test
    fun homeAnswersFromItsListThenFetchesEachUnknownIDOnce() = runTest {
        val fetched = Collections.synchronizedList(mutableListOf<String>())
        val state = home().withLinks(mapOf(OTHER to SessionLinkTarget.Resolving, ID to SessionLinkTarget.Resolving))
        val store = TestStore(state, HomeFeature(), this) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(getSession = { _, id ->
                fetched += id
                OpenCodeSession(id = id, serverTitle = "OPENCODE", projectID = "p", directory = "/p", updatedAt = Instant.now())
            })
        }
        store.exhaustive = false
        store.send(resolve(listOf(OTHER, ID)))
        assertEquals(SessionLinkTarget.Session("HOME", null), store.state.sessionDetail?.sessionLinks?.get(OTHER))
        // A second ask while the first is in flight fetches nothing more.
        store.send(resolve(listOf(ID)))
        store.finish()
        assertEquals(listOf(ID), fetched.toList())
        assertEquals(SessionLinkTarget.Session("OPENCODE", null), store.state.sessionDetail?.sessionLinks?.get(ID))
        assertEquals("OPENCODE", store.state.linkedSessions[ID]?.title)
        // Cached: asked again, it answers without a fetch.
        store.send(resolve(listOf(ID)))
        assertEquals(listOf(ID), fetched.toList())
    }

    @Test
    fun anIDTheServerDoesNotKnowStaysShort() = runTest {
        val state = home().withLinks(mapOf(ID to SessionLinkTarget.Resolving))
        val store = TestStore(state, HomeFeature(), this) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(getSession = { _, _ -> throw OpenCodeClientError(message = "Not found", statusCode = 404) })
        }
        store.exhaustive = false
        store.send(resolve(listOf(ID)))
        store.receive({ it is HomeFeature.Action.LinkedSessionFetched }, "linkedSessionFetched")
        assertEquals(SessionLinkTarget.Missing, store.state.sessionDetail?.sessionLinks?.get(ID))
        assertEquals(setOf(ID), store.state.missingLinkedSessionIDs)
    }

    @Test
    fun tappingASubagentLinkPushesItAndARootOpensIt() = runTest {
        val child = session(ID, title = "Explore", parent = "ses_elsewhereelsewhereelsewh")
        val store = TestStore(home(), HomeFeature(), backgroundScope) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(getSession = { _, _ -> child })
        }
        store.exhaustive = false
        store.send(open(ID))
        store.receive({ it is HomeFeature.Action.LinkedSessionOpened }, "linkedSessionOpened")
        assertEquals("A subagent opens over the screen", ID, store.state.subagentStack.lastOrNull()?.session?.id)
        assertEquals(ROOT, store.state.sessionDetail?.session?.id)

        store.send(HomeFeature.Action.SubagentsPopped(0))
        store.send(open(OTHER))
        store.receive(HomeFeature.Action.SessionTapped(OTHER))
        assertEquals("A listed root opens without a fetch", OTHER, store.state.sessionDetail?.session?.id)
    }

    @Test
    fun aLinkThatCannotOpenPlaysAnError() = runTest {
        val played = Collections.synchronizedList(mutableListOf<Haptic>())
        val store = TestStore(home(), HomeFeature(), this) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(getSession = { _, _ -> throw OpenCodeClientError(message = "Not found", statusCode = 404) })
            this[HapticsClient] = HapticsClient(play = { played += it })
        }
        store.exhaustive = false
        store.send(open(ID))
        store.receive({ it is HomeFeature.Action.LinkedSessionOpened }, "linkedSessionOpened")
        assertEquals(listOf(Haptic.error), played.toList())
        assertEquals(ROOT, store.state.sessionDetail?.session?.id)
    }

    @Test
    fun linkLookupsQueueBeyondSixAndAFailureAsksAgain() = runTest {
        val clock = TestClock()
        val ids = (1..7).map { "ses_queue$it" }
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val state = home().withLinks(ids.associateWith { SessionLinkTarget.Resolving })
        val store = TestStore(state, HomeFeature(), this) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(getSession = { _, id ->
                val attempt = synchronized(calls) { calls += id; calls.count { it == id } }
                // The nth ID answers n seconds after it starts.
                clock.sleep((ids.indexOf(id) + 1).seconds)
                if (id == ids[1] && attempt == 1) throw OpenCodeClientError(message = "Unavailable", statusCode = 503)
                found(id)
            })
        }
        fun fetched(id: String) = { action: HomeFeature.Action -> action is HomeFeature.Action.LinkedSessionFetched && action.id == id }

        store.send(resolve(ids)) {
            it.copy(linkedSessionFetches = ids.take(6).toSet(), linkedSessionQueue = listOf(ids[6]))
        }
        kotlinx.coroutines.yield()
        clock.advance()
        assertEquals("Six at a time", 6, calls.size)

        clock.advance(1.seconds)
        store.receive(fetched(ids[0]), "linkedSessionFetched(${ids[0]})") {
            it.copy(
                linkedSessionFetches = ids.drop(1).toSet(),
                linkedSessionQueue = emptyList(),
                linkedSessions = it.linkedSessions + (ids[0] to found(ids[0])),
            ).links { links -> links + (ids[0] to SessionLinkTarget.Session("T1", null)) }
        }
        clock.advance(1.seconds)
        store.receive(fetched(ids[1]), "linkedSessionFetched(${ids[1]})") {
            it.copy(linkedSessionFetches = it.linkedSessionFetches - ids[1]).links { links -> links - ids[1] }
        }
        for (index in 2..5) {
            clock.advance(1.seconds)
            store.receive(fetched(ids[index]), "linkedSessionFetched(${ids[index]})") {
                it.copy(
                    linkedSessionFetches = it.linkedSessionFetches - ids[index],
                    linkedSessions = it.linkedSessions + (ids[index] to found(ids[index])),
                ).links { links -> links + (ids[index] to SessionLinkTarget.Session("T${index + 1}", null)) }
            }
        }
        // The queued ID started when the first finished, at one second.
        clock.advance(2.seconds)
        store.receive(fetched(ids[6]), "linkedSessionFetched(${ids[6]})") {
            it.copy(
                linkedSessionFetches = emptySet(),
                linkedSessions = it.linkedSessions + (ids[6] to found(ids[6])),
            ).links { links -> links + (ids[6] to SessionLinkTarget.Session("T7", null)) }
        }

        // The failed ID, seen again, asks again.
        store.send(HomeFeature.Action.detail(SessionFeature.Action.SessionLinksAppeared(listOf(ids[1])))) {
            it.links { links -> links + (ids[1] to SessionLinkTarget.Resolving) }
        }
        store.receive(resolve(listOf(ids[1]))) {
            it.copy(linkedSessionFetches = setOf(ids[1]))
        }
        clock.advance(2.seconds)
        store.receive(fetched(ids[1]), "linkedSessionFetched(${ids[1]})") {
            it.copy(
                linkedSessionFetches = emptySet(),
                linkedSessions = it.linkedSessions + (ids[1] to found(ids[1])),
            ).links { links -> links + (ids[1] to SessionLinkTarget.Session("T2", null)) }
        }
        store.finish()
        assertEquals(ids.take(6).toSet(), calls.take(6).toSet())
        assertEquals(listOf(ids[6], ids[1]), calls.drop(6))
    }

    @Test
    fun aLinkLookupThatLandsAfterLeavingItsScreenOpensNothing() = runTest {
        val clock = TestClock()
        val target = session(ID, title = "Elsewhere")
        val state = home().pushingSubagent(session("ses_subsubsubsubsubsubsubsubsu", title = "Explore", parent = ROOT))
        val subagent = state.subagentStack.last().navigationID
        val screen = state.linkScreen
        val store = TestStore(state, HomeFeature(), this) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(getSession = { _, _ ->
                clock.sleep(1.seconds)
                target
            })
        }
        store.send(HomeFeature.Action.SubagentStack(subagent, SessionFeature.Action.Delegate(SessionFeature.Delegate.OpenSessionLink(ID)))) {
            it.copy(pendingLinkedSessionOpen = HomeFeature.PendingLinkedSessionOpen(ID, screen))
        }
        // Back to the root before the lookup lands.
        store.exhaustive = false
        store.send(HomeFeature.Action.SubagentsPopped(0))
        store.skipReceivedActions()
        store.exhaustive = true
        clock.advance(1.seconds)
        store.receive({ it is HomeFeature.Action.LinkedSessionOpened }, "linkedSessionOpened") {
            it.copy(linkedSessions = it.linkedSessions + (ID to target), pendingLinkedSessionOpen = null)
        }
        store.finish()
        assertTrue(store.state.subagentStack.isEmpty())
        assertEquals(ROOT, store.state.sessionDetail?.session?.id)
    }

    @Test
    fun aRowWhoseLookupFailedAsksAgainWhenItNextAppears() = runTest {
        var failures = 1
        val store = TestStore(home(), HomeFeature(), this) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(getSession = { _, id ->
                if (failures-- > 0) throw OpenCodeClientError(message = "Unavailable", statusCode = 503)
                OpenCodeSession(id = id, serverTitle = "OPENCODE", projectID = "p", directory = "/p", updatedAt = Instant.now())
            })
        }
        store.exhaustive = false
        val appear = HomeFeature.Action.detail(SessionFeature.Action.SessionLinksAppeared(listOf(ID)))

        store.send(appear)
        store.receive({ it is HomeFeature.Action.LinkedSessionFetched }, "linkedSessionFetched")
        assertNull("The failed lookup is forgotten", store.state.sessionDetail?.sessionLinks?.get(ID))

        store.send(appear)
        store.receive(resolve(listOf(ID)))
        assertEquals("The row asked again", SessionLinkTarget.Resolving, store.state.sessionDetail?.sessionLinks?.get(ID))
        store.receive({ it is HomeFeature.Action.LinkedSessionFetched }, "linkedSessionFetched")
        assertEquals(SessionLinkTarget.Session("OPENCODE", null), store.state.sessionDetail?.sessionLinks?.get(ID))

        // Answered: appearing again asks nothing.
        store.exhaustive = true
        store.send(appear)
        store.finish()
    }
}
