package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.composer.ComposerAttachmentsFeature
import ai.opencode.feature.composer.DraftState
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeatureTestSupport.Harness
import ai.opencode.feature.drawer.HomeFeatureTestSupport.client
import ai.opencode.feature.drawer.HomeFeatureTestSupport.date
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receiveBounded
import ai.opencode.feature.drawer.HomeFeatureTestSupport.snapshot
import ai.opencode.feature.drawer.catchup.CatchUpFeature
import ai.opencode.feature.media.BrowserFeature
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.isDraft
import ai.opencode.feature.session.isSubmittingPrompt
import ai.opencode.feature.session.pendingAttachments
import ai.opencode.feature.session.setTyped
import ai.opencode.feature.session.withDraft
import ai.opencode.feature.settings.SettingsFeature
import ai.opencode.feature.settings.SettingsPage
import ai.opencode.feature.settings.settingsPage
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewFeature
import ai.opencode.feature.settings.whatsnew.all
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** `.settingsScreen(.presented(action))`: What's New lives on Settings' stack on Android. */
private fun settingsAction(action: SettingsFeature.Action): Action = Action.SettingsScreen(PresentationAction.Presented(action))

/** What's New's page action, through the Settings push it lives on. */
private fun whatsNewPage(action: WhatsNewFeature.Action): Action = settingsAction(SettingsFeature.Action.WhatsNew(action))

private fun tryTapped(destination: WhatsNewEntry.Destination, entryID: String): Action =
    whatsNewPage(WhatsNewFeature.Action.TryTapped(destination, entryID))

/** On Android Settings asks Home with `TryWhatsNew`. */
private suspend fun TestStore<HomeFeature.State, Action>.receiveTryWhatsNew(update: ((HomeFeature.State) -> HomeFeature.State)? = null) =
    receiveBounded(
        { ((((it as? Action.SettingsScreen)?.action as? PresentationAction.Presented)?.action as? SettingsFeature.Action.Delegate)?.action) is SettingsFeature.SettingsDelegate.TryWhatsNew },
        "settingsScreen.delegate.tryWhatsNew",
        update = update,
    )

/** What's New pushed over the drawer as its row opens it (`state.whatsNew.isPresented = true; state.isSettingsShown = true`). */
private fun HomeFeature.State.presentingWhatsNew(): HomeFeature.State {
    val page = SettingsFeature.State.whatsNew(server)
    return presentingSettings(page.copy(whatsNew = page.whatsNew.copy(isPresented = true)))
}

/** Home reads the shipped ones here, so "a" stands for any unseen feature. */
class WhatsNewTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")

    private fun Harness.home(projects: List<OpenCodeProject> = emptyList()): HomeFeature.State =
        state { HomeFeature.State(server, snapshot(emptyList(), projects = projects)) }

    private fun Harness.whatsNewStore(state: HomeFeature.State, exhaustive: Boolean = false): TestStore<HomeFeature.State, Action> =
        store(state) {
            this[OpenCodeClient] = client().copy(
                createSession = { _, _, _ -> throw AssertionError("Try It must not create a session") },
                sendPrompt = { _, _, _, _, _, _, _ -> throw AssertionError("Try It must not send") },
            )
        }.also { it.exhaustive = exhaustive }

    @Test
    fun homePushesWhatsNewFromTheDrawerAndClosingMarksItSeen() = homeTest { h ->
        h.setShared(WhatsNewFeature.seenIDsKey, emptySet())
        val store = h.whatsNewStore(h.home())

        store.send(Action.WhatsNew(WhatsNewFeature.Action.ButtonTapped))
        assertTrue("It pushes like Settings", store.state.isSettingsShown)
        assertEquals(true, store.state.settings?.whatsNew?.isPresented)
        assertTrue("The row stays until the page closes", store.state.showsWhatsNewButton)

        store.send(whatsNewPage(WhatsNewFeature.Action.Viewed))
        // Back (the binding), as the back swipe and TalkBack's escape close it.
        store.send(Action.SettingsShownChanged(false))
        assertFalse(store.state.isSettingsShown)
        assertNull(store.state.settings?.whatsNew)
        assertFalse(store.state.showsWhatsNewButton)
        assertTrue(h.shared(WhatsNewFeature.seenIDsKey).containsAll(WhatsNewEntry.all.map { it.id }))
    }

    @Test
    fun readingItInSettingsMarksItSeenWhenSettingsCloses() = homeTest { h ->
        h.setShared(WhatsNewFeature.seenIDsKey, emptySet())
        val store = h.whatsNewStore(h.home())

        store.send(Action.SettingsTapped)
        assertEquals(false, store.state.settings?.whatsNew?.isPresented)
        store.send(whatsNewPage(WhatsNewFeature.Action.Viewed))
        assertTrue(store.state.showsWhatsNewButton)
        store.send(Action.SettingsShownChanged(false))
        assertFalse(store.state.isSettingsShown)
        assertFalse(store.state.showsWhatsNewButton)
    }

    @Test
    fun tryItTypesAPromptInANewSessionWithoutSendingIt() = homeTest { h ->
        val store = h.whatsNewStore(h.home())
        store.send(Action.WhatsNew(WhatsNewFeature.Action.ButtonTapped))

        store.send(tryTapped(WhatsNewEntry.Destination.Prompt("Draw a flowchart"), "a"))
        store.receiveTryWhatsNew()
        assertFalse(store.state.isSettingsShown)
        assertEquals("The drawer closes onto the session", 1, store.state.drawerCloseRequests)
        assertEquals(true, store.state.sessionDetail?.isDraft)
        assertEquals("Draw a flowchart", store.state.sessionDetail?.draft)
        assertNotEquals(true, store.state.sessionDetail?.isSubmittingPrompt)
    }

    @Test
    fun closingSettingsWithoutWhatsNewSendsNothingMore() = homeTest { h ->
        val store = h.whatsNewStore(h.home(), exhaustive = true)
        store.send(Action.SettingsTapped) { it.presentingSettings(SettingsFeature.State(server = server)).syncingTranscriptCover() }
        store.send(Action.SettingsShownChanged(false)) { it.copy(settings = null).syncingTranscriptCover() }
    }

    /**
     * Every Settings page an entry opens pushes on What's New's own stack, over the entry's page
     * it was tapped on, and Back pops just that page.
     */
    @Test
    fun openPushesItsSettingsPageOverTheEntrysPage() = homeTest { h ->
        val pages = listOf(
            WhatsNewEntry.SettingsDestination.Themes,
            WhatsNewEntry.SettingsDestination.Sessions,
            WhatsNewEntry.SettingsDestination.Voice,
            WhatsNewEntry.SettingsDestination.Notifications,
            WhatsNewEntry.SettingsDestination.Servers,
            WhatsNewEntry.SettingsDestination.Experimental,
            WhatsNewEntry.SettingsDestination.Experiment("in-app-browser"),
            WhatsNewEntry.SettingsDestination.Developer,
        )
        val entryIDs = listOf("a", "fix")
        val store = h.whatsNewStore(h.home().presentingWhatsNew(), exhaustive = true)
        fun HomeFeature.State.path(path: List<SettingsPage>) = copy(settings = settings!!.copy(path = path))
        for (page in pages) {
            for (entryID in entryIDs) {
                // The entry's page the action is on.
                val entryPage = SettingsPage.WhatsNewDetail(entryID)
                store.send(settingsAction(SettingsFeature.Action.PagePushed(entryPage))) { it.path(listOf(entryPage)) }
                store.send(tryTapped(WhatsNewEntry.Destination.Open(page), entryID)) { it.path(listOf(entryPage, page.settingsPage())) }
                store.send(settingsAction(SettingsFeature.Action.BackTapped)) { it.path(listOf(entryPage)) }
                store.send(settingsAction(SettingsFeature.Action.BackTapped)) { it.path(emptyList()) }
            }
        }
    }

    /** Closing What's New over a pushed Settings page drops the page too. */
    @Test
    fun closingWhatsNewDropsItsSettingsPage() = homeTest { h ->
        val store = h.whatsNewStore(h.home().presentingWhatsNew(), exhaustive = true)
        store.send(tryTapped(WhatsNewEntry.Destination.Open(WhatsNewEntry.SettingsDestination.Themes), "a")) {
            it.copy(settings = it.settings!!.copy(path = listOf(SettingsPage.Themes)))
        }
        store.send(Action.SettingsShownChanged(false)) { it.copy(settings = null) }
    }

    /** Try It opens a new session on this server, in the most recent project, with the prompt typed in and nothing sent. */
    @Test
    fun tryItTypesThePromptInANewSessionOnThisServerWithoutSendingIt() = homeTest { h ->
        val projects = listOf(
            OpenCodeProject(id = "p1", name = "web", directory = "/srv/web"),
            OpenCodeProject(id = "p2", name = "api", directory = "/srv/api"),
        )
        val state = h.home(projects).presentingWhatsNew()
        val generation = state.detailGeneration
        val store = h.whatsNewStore(state, exhaustive = true)

        store.send(tryTapped(WhatsNewEntry.Destination.Prompt("List my sessions"), "a"))
        store.receiveTryWhatsNew { home ->
            val draft = store.withDependencies {
                SessionFeature.State.draft(server = server, draftProject = projects[0], projects = projects, navigationID = generation + 1)
                    .setTyped("List my sessions")
            }
            home.copy(settings = null, drawerCloseRequests = 1, detailGeneration = generation + 1, sessionDetail = draft)
        }
        assertEquals(true, store.state.sessionDetail?.isDraft)
        assertEquals("List my sessions", store.state.sessionDetail?.draft)
        assertNotEquals(true, store.state.sessionDetail?.isSubmittingPrompt)
    }

    /** There's one new-session draft: Try It over one with words in it keeps them and types the prompt below, on the same screen. */
    @Test
    fun tryItKeepsTheOpenDraftsWordsAndTypesThePromptBelowThem() = homeTest { h ->
        val projects = listOf(OpenCodeProject(id = "p1", name = "web", directory = "/srv/web"))
        val state = h.home(projects).let { home ->
            h.state {
                home.copy(sessionDetail = SessionFeature.State.draft(server = server, draftProject = projects[0], projects = projects).withDraft("Half a thought"))
            }
        }.presentingWhatsNew()
        val store = h.whatsNewStore(state, exhaustive = true)

        store.send(tryTapped(WhatsNewEntry.Destination.Prompt("List my sessions"), "a"))
        store.receiveTryWhatsNew {
            it.copy(settings = null, drawerCloseRequests = 1, sessionDetail = it.sessionDetail!!.withDraft("Half a thought\n\nList my sessions"))
        }
    }

    /** Tapping Try It again types its prompt once: the words, their spacing and the attachment stay as they were. */
    @Test
    fun tryItAgainLeavesTheDraftAsItIs() = homeTest { h ->
        val projects = listOf(OpenCodeProject(id = "p1", name = "web", directory = "/srv/web"))
        val photo = OpenCodeOutgoingAttachment(
            id = UUID.fromString("00000000-0000-0000-0000-000000000041").toString(),
            data = "photo".toByteArray(),
            mime = "image/png",
            name = "photo.png",
        )
        val state = h.home(projects).let { home ->
            h.state {
                val draft = SessionFeature.State.draft(server = server, draftProject = projects[0], projects = projects).withDraft("  Half a thought \n")
                home.copy(sessionDetail = draft.copy(composerAttachments = ComposerAttachmentsFeature.State(pendingAttachments = listOf(photo))))
            }
        }.presentingWhatsNew()
        val store = h.whatsNewStore(state, exhaustive = true)

        store.send(tryTapped(WhatsNewEntry.Destination.Prompt("List my sessions"), "a"))
        store.receiveTryWhatsNew {
            it.copy(settings = null, drawerCloseRequests = 1, sessionDetail = it.sessionDetail!!.withDraft("  Half a thought \n\n\nList my sessions"))
        }
        store.send(Action.WhatsNew(WhatsNewFeature.Action.ButtonTapped)) { it.presentingWhatsNew().syncingTranscriptCover() }
        store.send(tryTapped(WhatsNewEntry.Destination.Prompt(" List my sessions\n"), "a"))
        store.receiveTryWhatsNew { it.copy(settings = null, drawerCloseRequests = 2).syncingTranscriptCover() }
        assertEquals(listOf(photo), store.state.sessionDetail?.pendingAttachments)
    }

    /** A repeat tap after Try It opened a fresh draft doesn't type the prompt twice. */
    @Test
    fun tryItTwiceFromNoDraftTypesThePromptOnce() = homeTest { h ->
        val projects = listOf(OpenCodeProject(id = "p1", name = "web", directory = "/srv/web"))
        val state = h.home(projects).presentingWhatsNew()
        val generation = state.detailGeneration
        val store = h.whatsNewStore(state, exhaustive = true)

        store.send(tryTapped(WhatsNewEntry.Destination.Prompt("List my sessions"), "a"))
        store.receiveTryWhatsNew { home ->
            val draft = store.withDependencies {
                SessionFeature.State.draft(server = server, draftProject = projects[0], projects = projects, navigationID = generation + 1)
                    .withDraft("List my sessions")
            }
            home.copy(settings = null, drawerCloseRequests = 1, detailGeneration = generation + 1, sessionDetail = draft)
        }
        store.send(Action.WhatsNew(WhatsNewFeature.Action.ButtonTapped)) { it.presentingWhatsNew().syncingTranscriptCover() }
        store.send(tryTapped(WhatsNewEntry.Destination.Prompt("List my sessions"), "a"))
        store.receiveTryWhatsNew { it.copy(settings = null, drawerCloseRequests = 2).syncingTranscriptCover() }
    }

    /** A saved new-session draft comes back when Try It opens one, with the prompt below its words. */
    @Test
    fun tryItKeepsASavedDraftsWordsAndTypesThePromptBelowThem() = homeTest { h ->
        val projects = listOf(OpenCodeProject(id = "p1", name = "web", directory = "/srv/web"))
        h.composerPersistence.scheduleDraft(server.persistenceID, null, null, DraftState(text = "Half a thought"))
        val state = h.home(projects).copy(sessionDetail = null).presentingWhatsNew()
        val generation = state.detailGeneration
        val store = h.whatsNewStore(state, exhaustive = true)

        store.send(tryTapped(WhatsNewEntry.Destination.Prompt("List my sessions"), "a"))
        store.receiveTryWhatsNew { home ->
            val draft = store.withDependencies {
                SessionFeature.State.draft(server = server, draftProject = projects[0], projects = projects, navigationID = generation + 1)
                    .withDraft("Half a thought\n\nList my sessions")
            }
            home.copy(settings = null, drawerCloseRequests = 1, detailGeneration = generation + 1, sessionDetail = draft)
        }
    }
}

/**
 * Try It lands through the paths the drawer, Catch Up and the call button use, and keeps the page
 * for what can't go now.
 */
class WhatsNewTryItTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")

    private fun session(id: String, updated: Long, parent: String? = null, idle: Long = 2_000, viewed: Long = 1_000) = OpenCodeSession(
        id = id, serverTitle = id, projectID = "p", directory = "/project",
        updatedAt = date(updated), idleMilliseconds = idle, viewedMilliseconds = viewed, parentID = parent,
    )

    /** "read" open, "newer" and "older" unread; [presentingWhatsNew] pushes What's New over the drawer. */
    private fun Harness.home(): HomeFeature.State = state {
        val sessions = listOf(
            session("older", updated = 10),
            session("read", updated = 40, idle = 1_000, viewed = 1_000),
            session("newer", updated = 20),
        )
        HomeFeature.State(server, snapshot(sessions)).copy(attentionSessionIDs = emptySet()).openingDetail(sessions[1])
    }

    private fun Harness.tryStore(state: HomeFeature.State): TestStore<HomeFeature.State, Action> =
        store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = client().copy(
                viewSession = { _, _, _ -> },
                transcript = { _, _, _ -> emptyList() },
                homeAttention = { _, _, _ -> emptySet() },
                modelCatalog = { _, _ -> throw OpenCodeClientError(message = "Offline in tests") },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
                runningShells = { _, _ -> emptyList() },
            )
        }.also { it.exhaustive = false }

    private fun isTop(id: Int, case: (SessionFeature.Action) -> Boolean) =
        { action: Action -> action is Action.SubagentStack && action.id == id && case(action.action) }

    @Test
    fun modelListFromCatchUpOpensTheCardAsTheSession() = homeTest { h ->
        val state = h.state {
            h.home().withCatchUp(CatchUpFeature.Stack(queue = listOf("newer", "older"), returnSessionID = "read")).openingCatchUpCard().first
        }.presentingWhatsNew()
        val store = h.tryStore(state)

        store.send(tryTapped(WhatsNewEntry.Destination.ModelList, "a"))
        store.receiveTryWhatsNew()
        assertNull(store.state.catchUp)
        assertFalse(store.state.isSettingsShown)
        assertEquals("newer", store.state.sessionDetail?.session?.id)
        assertEquals("The card reads as any open session", true, store.state.sessionDetail?.readsOnView)
        assertFalse(store.state.isUnread(store.state.sessions.first { it.id == "newer" }))
        store.receiveBounded({ it.detailAction == SessionFeature.Action.ModelButtonTapped }, "sessionDetail.modelButtonTapped")
        assertEquals(SessionFeature.State.ActivePicker.model, store.state.sessionDetail?.activePicker)
        h.skipInFlightEffects()
    }

    @Test
    fun modelListAfterAllCaughtUpReturnsWhereCatchUpWouldHave() = homeTest { h ->
        val state = h.state {
            val opened = h.home().withCatchUp(CatchUpFeature.Stack(queue = listOf("newer"), returnSessionID = "read")).openingCatchUpCard().first
            // The last card was dismissed, before Catch Up closes itself.
            opened.withCatchUp(opened.catchUp!!.copy(queue = emptyList()))
        }.presentingWhatsNew()
        val store = h.tryStore(state)

        // Home's Try It itself (Settings' `TryWhatsNew`): any action before it would let Catch Up's
        // reconcile refill this artificial empty queue (nothing was recorded as handled) with the
        // unread sessions.
        store.send(settingsAction(SettingsFeature.Action.Delegate(SettingsFeature.SettingsDelegate.TryWhatsNew(WhatsNewEntry.Destination.ModelList, "a"))))
        assertNull(store.state.catchUp)
        assertEquals("Back to the session Catch Up started from", "read", store.state.sessionDetail?.session?.id)
        assertEquals(true, store.state.sessionDetail?.readsOnView)
        assertFalse("Try It shows the session, not the drawer Back would return to", store.state.isDrawerOpen)
        h.skipInFlightEffects()
    }

    @Test
    fun modelListGoesToThePushedSubagentAndClosesItsPage() = homeTest { h ->
        val child = session("child", updated = 30, parent = "read")
        val pushed = h.state {
            val home = h.home()
            val root = home.sessionDetail!!.session!!
            home.updatingDetail { it.copy(familySessions = listOf(root, child)) }.pushingSubagent(child)
        }
        val top = pushed.subagentStack.last().navigationID
        val state = pushed.updatingSubagent(top) { it.copy(browser = it.browser.copy(pageID = UUID.randomUUID(), url = "https://example.com")) }
            .presentingWhatsNew()
        val store = h.tryStore(state)

        store.send(tryTapped(WhatsNewEntry.Destination.ModelList, "a"))
        store.receiveTryWhatsNew()
        store.receiveBounded(isTop(top) { (it as? SessionFeature.Action.Browser)?.action == BrowserFeature.Action.CloseTapped }, "subagentStack[$top].browser.closeTapped")
        store.receiveBounded(isTop(top) { it == SessionFeature.Action.ModelButtonTapped }, "subagentStack[$top].modelButtonTapped")
        assertEquals("The screen on top gets the list", SessionFeature.State.ActivePicker.model, store.state.subagentStack.lastOrNull()?.activePicker)
        assertEquals(false, store.state.subagentStack.lastOrNull()?.browser?.isOpen)
        assertNotEquals("Not the covered root", SessionFeature.State.ActivePicker.model, store.state.sessionDetail?.activePicker)
        h.skipInFlightEffects()
    }

    @Test
    fun modelListWithNothingOpenStartsADraft() = homeTest { h ->
        val state = h.home().copy(sessionDetail = null).presentingWhatsNew()
        val store = h.tryStore(state)
        store.send(tryTapped(WhatsNewEntry.Destination.ModelList, "a"))
        store.receiveTryWhatsNew()
        assertEquals(true, store.state.sessionDetail?.isDraft)
        store.receiveBounded({ it.detailAction == SessionFeature.Action.ModelButtonTapped }, "sessionDetail.modelButtonTapped")
        h.skipInFlightEffects()
    }

    @Test
    fun aNewSessionWhileOfflineKeepsThePage() = homeTest { h ->
        val home = h.home()
        val state = home.copy(
            connection = home.connection.copy(connectionStatus = HomeConnectionFeature.State.ConnectionStatus.disconnected),
        ).presentingWhatsNew()
        val store = h.tryStore(state)
        for (destination in listOf(WhatsNewEntry.Destination.NewSession, WhatsNewEntry.Destination.Prompt("Draw a flowchart"))) {
            store.send(tryTapped(destination, "a"))
            store.skipReceivedActions()
            assertTrue("What’s New stays up", store.state.isSettingsShown)
            assertEquals(true, store.state.settings?.whatsNew?.isPresented)
            assertEquals("The drawer stays open", 0, store.state.drawerCloseRequests)
            assertEquals("read", store.state.sessionDetail?.session?.id)
        }
        h.skipInFlightEffects()
    }

    @Test
    fun aCallBeforeVoiceIsSetUpPushesVoiceSettingsOnThePage() = homeTest { h ->
        h.setShared(SharedKeys.voiceModeEnabled, false)
        h.setShared(SharedKeys.openAIAPIKey, null)
        val store = h.tryStore(h.home().presentingWhatsNew())
        // The entry's page the action is on.
        store.send(settingsAction(SettingsFeature.Action.PagePushed(SettingsPage.WhatsNewDetail("call-voice"))))
        store.send(tryTapped(WhatsNewEntry.Destination.Call, "call-voice"))
        store.skipReceivedActions()
        assertTrue(store.state.isSettingsShown)
        assertEquals("The page stays What’s New, never a latched Settings", true, store.state.settings?.whatsNew?.isPresented)
        assertEquals(SettingsFeature.State.Root.whatsNew, store.state.settings?.root)
        assertEquals(listOf(SettingsPage.WhatsNewDetail("call-voice"), SettingsPage.Voice), store.state.settings?.path)
        assertEquals(0, store.state.drawerCloseRequests)
        assertNull(store.state.sessionDetail?.voice)

        store.send(settingsAction(SettingsFeature.Action.BackTapped))
        assertEquals(listOf(SettingsPage.WhatsNewDetail("call-voice")), store.state.settings?.path)
        h.skipInFlightEffects()
    }

    @Test
    fun settingsAskedForOverWhatsNewShowsSettings() = homeTest { h ->
        val store = h.tryStore(h.home().presentingWhatsNew())
        store.send(Action.SettingsTapped)
        assertTrue(store.state.isSettingsShown)
        assertEquals("The push shows Settings' root", false, store.state.settings?.whatsNew?.isPresented)
        assertEquals(SettingsFeature.State.Root.settings, store.state.settings?.root)
        h.skipInFlightEffects()
    }
}
