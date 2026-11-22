package ai.opencode.feature.session

import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.feature.media.LinkMenuAction
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.transcript.SessionLinkTarget
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.OpenCodeTranscriptPage
import ai.opencode.server.model.SessionLink
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SessionLinkTests {
    private companion object {
        const val ID = "ses_ea0cb2871ffeNzq62aRonBKB2l"
        const val OTHER = "ses_e9ff44bffffe07ia9B2fLdhYb2"
        const val ROOT = "ses_rootrootrootrootrootrootro"
    }

    private val server = OpenCodeServer(url = "https://links.example", username = "", password = "")

    private fun session(id: String, title: String = "Home", parent: String? = null) =
        OpenCodeSession(id = id, serverTitle = title, projectID = "project", directory = "/project", updatedAt = Instant.ofEpochSecond(10), parentID = parent)

    private fun Harness.sessionState(): State = state {
        val root = session(ROOT, title = "Root")
        State.session(server, root).copy(familySessions = listOf(root, session(OTHER, title = "Explore", parent = ROOT)))
    }

    @Test fun appearingIDsResolveFromTheFamilyAndAskHomeForTheRest() = sessionTest { h ->
        val store = h.store(h.sessionState())
        store.send(Action.SessionLinksAppeared(listOf(OTHER, ID))) {
            it.copy(sessionLinks = mapOf(OTHER to SessionLinkTarget.Session(title = "Explore", parentID = ROOT), ID to SessionLinkTarget.Resolving))
        }
        store.receive(Action.Delegate(SessionFeature.Delegate.ResolveSessionLinks(listOf(ID))))
        // Asked once: the same IDs again send nothing.
        store.send(Action.SessionLinksAppeared(listOf(ID, OTHER)))
        store.finish()
    }

    @Test fun aRESTWindowResolvesItsLinksBeforeItsRowsDraw() = sessionTest { h ->
        val state = h.sessionState().copy(isLoading = true)
        val store = h.store(state)
        store.exhaustive = false
        store.send(Action.TranscriptResponse(state.session!!.id, Result.success(listOf(OpenCodeTranscriptItem.AssistantMarkdown(id = "a", text = "See `$ID`")))))
        store.receive(Action.SessionLinksAppeared(listOf(ID)))
        assertEquals(SessionLinkTarget.Resolving, store.state.sessionLinks[ID])
    }

    @Test fun tappingALinkAsksHomeToOpenItAndCopyIDCopies() = sessionTest { h ->
        val copied = LockIsolated(listOf<String>())
        val played = LockIsolated(listOf<Haptic>())
        val store = h.store(h.sessionState()) {
            this[PasteboardClient] = PasteboardClient(setURL = {}, setString = { copied.append(it) })
            this[HapticsClient] = HapticsClient(play = { played.append(it) })
        }
        val url = SessionLink.url(id = ID, title = "HOME")
        store.send(Action.LinkTapped(url))
        store.receive(Action.Delegate(SessionFeature.Delegate.OpenSessionLink(ID)))
        store.send(Action.LinkMenu(LinkMenuAction.OpenSession, url))
        store.receive(Action.Delegate(SessionFeature.Delegate.OpenSessionLink(ID)))
        // The session on screen opens nothing.
        store.send(Action.LinkTapped(SessionLink.url(id = ROOT)))
        store.send(Action.LinkMenu(LinkMenuAction.CopySessionID, url))
        store.finish()
        assertEquals(listOf(ID), copied.value)
        assertEquals("Navigation is quiet; the copy taps", listOf(Haptic.tap), played.value)
    }

    @Test fun anOlderPageResolvesOnlyTheLinksItAdds() = sessionTest { h ->
        val linking = { id: String -> "See `$id`" }
        val state = h.state {
            State.session(
                server, session(ROOT, title = "Root"),
                cachedItems = listOf(OpenCodeTranscriptItem.UserText(id = "u2", text = "Latest"), OpenCodeTranscriptItem.AssistantMarkdown(id = "a2", text = linking(OTHER))),
            )
        }.copy(earlierHistory = State.EarlierHistory.Loading(cursor = "c1", reachedLoadedItems = true))
        val store = h.store(state)
        store.exhaustive = false
        val page = OpenCodeTranscriptPage(
            items = listOf(OpenCodeTranscriptItem.UserText(id = "u1", text = "Older"), OpenCodeTranscriptItem.AssistantMarkdown(id = "a1", text = linking(ID))),
            next = null,
        )
        store.send(Action.EarlierHistoryResponse(state.session!!.id, generation = 0, cursor = "c1", result = Result.success(page)))
        store.receive(Action.SessionLinksAppeared(listOf(ID)))
        assertEquals(listOf("u1", "a1", "u2", "a2"), store.state.items.map { it.id })
        store.finish()
    }
}
