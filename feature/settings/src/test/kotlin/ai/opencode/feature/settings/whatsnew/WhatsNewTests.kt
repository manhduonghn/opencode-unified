package ai.opencode.feature.settings.whatsnew

import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.ui.sfSymbol
import ai.opencode.feature.settings.ConnectionStatus
import ai.opencode.feature.settings.SettingsFeature
import ai.opencode.feature.settings.SettingsFeature.SettingsDelegate
import ai.opencode.feature.settings.SettingsPage
import ai.opencode.feature.settings.SettingsTestShared
import ai.opencode.feature.settings.settingsPage
import ai.opencode.server.OpenCodeServer
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `WhatsNewDotSymbolTests` rasterizes the symbol's vector drawable on a `Canvas` ([DotField]), so it
 * runs under Robolectric (native graphics, the app's resources).
 */
class WhatsNewTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")

    private fun feature(id: String, day: String = "2026-10-06") = WhatsNewEntry.feature(
        id = id, day = day, title = id, symbol = "sparkles", tint = WhatsNewEntry.Tint.blue, summary = "s", details = emptyList(), example = "e",
    )

    private fun smaller(id: String, day: String = "2026-10-06") =
        WhatsNewEntry.smaller(id = id, day = day, title = id, symbol = "sparkles", summary = "s")

    /** Settings' state with What's New as its root, open. */
    private fun whatsNewSettings(entries: List<WhatsNewEntry>) =
        SettingsFeature.State.whatsNew(server).copy(whatsNew = WhatsNewFeature.State(entries = entries, isPresented = true))

    private fun TestScope.settingsStore(state: SettingsFeature.State, shared: SettingsTestShared) =
        TestStore(state, SettingsFeature(), this) { this[SharedStore] = shared.store }

    private val seenIDsKey = WhatsNewFeature.seenIDsKey

    @Test
    fun onlyUnseenFeaturesShowTheButton() {
        val shared = SettingsTestShared()
        shared.set(seenIDsKey, setOf("a"))
        val state = WhatsNewFeature.State(entries = listOf(smaller("fix"), feature("b"), feature("a")))
        shared.read {
            assertEquals(listOf("b"), state.unseenFeatures.map { it.id })
            assertTrue(state.showsButton)
        }

        shared.set(seenIDsKey, setOf("a", "b"))
        shared.read {
            assertTrue(state.unseenFeatures.isEmpty())
            assertFalse("An unseen smaller change never brings the row back", state.showsButton)
            assertFalse("Smaller changes carry no dot", state.isNew(state.entries[0]))
        }
    }

    @Test
    fun whatsNewStaysMarkedWhileReadingAndIsSeenOnClosing() = runTest {
        val shared = SettingsTestShared()
        shared.set(seenIDsKey, emptySet())
        val store = TestStore(WhatsNewFeature.State(entries = listOf(smaller("fix"), feature("b"), feature("a"))), WhatsNewFeature(), this) {
            this[SharedStore] = shared.store
        }
        store.send(WhatsNewFeature.Action.ButtonTapped) { it.copy(isPresented = true) }
        store.send(WhatsNewFeature.Action.Viewed) { it.copy(wasViewed = true) }
        store.withDependencies {
            assertTrue("The dot stays while the page is open", store.state.isNew(store.state.features[0]))
            assertTrue(store.state.showsButton)
        }

        store.send(WhatsNewFeature.Action.Closed) { it.copy(isPresented = false, wasViewed = false) }
        assertEquals(setOf("fix", "a", "b"), shared.get(seenIDsKey))
        store.withDependencies { assertFalse(store.state.showsButton) }
        store.finish()
    }

    @Test
    fun closingWithoutReadingMarksNothing() = runTest {
        val shared = SettingsTestShared()
        shared.set(seenIDsKey, emptySet())
        val store = TestStore(WhatsNewFeature.State(entries = listOf(feature("a"))), WhatsNewFeature(), this) {
            this[SharedStore] = shared.store
        }
        store.send(WhatsNewFeature.Action.Closed)
        store.withDependencies { assertTrue(store.state.showsButton) }
        store.finish()
    }

    @Test
    fun hidingTurnsTheButtonOffUntilSettingsBringsItBack() = runTest {
        val shared = SettingsTestShared()
        shared.set(seenIDsKey, emptySet())
        val store = TestStore(WhatsNewFeature.State(entries = listOf(feature("a"))), WhatsNewFeature(), this) {
            this[SharedStore] = shared.store
        }
        store.send(WhatsNewFeature.Action.HideTapped)
        assertFalse(shared.get(WhatsNewFeature.buttonEnabledKey))
        store.withDependencies {
            assertFalse(store.state.showsButton)
            assertEquals("Hiding doesn’t mark anything seen", listOf("a"), store.state.unseenFeatures.map { it.id })
        }

        store.send(WhatsNewFeature.Action.ButtonEnabledChanged(true))
        assertTrue(shared.get(WhatsNewFeature.buttonEnabledKey))
        store.withDependencies { assertTrue(store.state.showsButton) }
        store.finish()
    }

    @Test
    fun anUpdatesNewFeatureBringsTheButtonBackButASmallerChangeDoesNot() {
        val shared = SettingsTestShared()
        shared.set(seenIDsKey, setOf("a"))
        shared.read {
            val withFix = WhatsNewFeature.State(entries = listOf(smaller("fix", day = "2026-10-20"), feature("a")))
            assertFalse(withFix.showsButton)
            val withFeature = WhatsNewFeature.State(entries = listOf(feature("b", day = "2026-10-20"), feature("a")))
            assertEquals(listOf("b"), withFeature.unseenFeatures.map { it.id })
            assertTrue(withFeature.showsButton)
        }
    }

    @Test
    fun splitDayAdditionsEachNameAnEntry() {
        val all = WhatsNewEntry.all.map { it.id }.toSet()
        val missing = (addedOnSplitDay - all).sorted()
        assertTrue("No entry with this ID: $missing", missing.isEmpty())
        assertTrue(addedOnSplitDay.intersect(previousIDs).isEmpty())
    }

    /**
     * Someone who saw every entry before the split sees nothing new: merged features kept their
     * newest entry’s ID.
     */
    @Test
    fun seenIDsCarryOverFromTheEntriesBeforeTheSplit() {
        val shared = SettingsTestShared()
        // Entries shipped after the split: new to everyone, not carried over.
        val added = addedSinceSplit.toSet()
        val addedFeatures = WhatsNewEntry.features.filter { it.id in added }.map { it.id }
        val shipped = WhatsNewEntry.all.map { it.id }.toSet() - added
        assertTrue("Every entry keeps an ID people may already have seen: ${shipped - previousIDs}", previousIDs.containsAll(shipped))

        shared.set(seenIDsKey, previousIDs)
        shared.read {
            assertEquals("Only features shipped since are new", addedFeatures, WhatsNewFeature.State().unseenFeatures.map { it.id })
        }

        // Seen everything but the newest of the merged voice entries: Voice Calls is new.
        shared.set(seenIDsKey, previousIDs + added - setOf("call-voice"))
        shared.read { assertEquals(listOf("call-voice"), WhatsNewFeature.State().unseenFeatures.map { it.id }) }

        // Seen only that newest one: the whole merged feature counts as seen.
        shared.set(seenIDsKey, previousIDs + added - setOf("voice-gpt-live", "voice-live-activity", "hold-stop-to-call"))
        shared.read {
            assertTrue(WhatsNewFeature.State().unseenFeatures.isEmpty())
            assertFalse(WhatsNewFeature.State().showsButton)
        }
    }

    /**
     * The page the drawer's row pushes keeps its row until it closes; Back at its root closes it
     * (Home then drops Settings) and marks what was read seen.
     */
    @Test
    fun homePushesWhatsNewFromTheDrawerAndClosingMarksItSeen() = runTest {
        val shared = SettingsTestShared()
        shared.set(seenIDsKey, emptySet())
        val store = settingsStore(whatsNewSettings(listOf(feature("a"))), shared)
        store.withDependencies { assertTrue("The row stays until the page closes", store.state.whatsNew.showsButton) }

        store.send(SettingsFeature.Action.WhatsNew(WhatsNewFeature.Action.Viewed)) { it.copy(whatsNew = it.whatsNew.copy(wasViewed = true)) }
        // Back, as the back gesture and TalkBack's escape close it.
        store.send(SettingsFeature.Action.BackTapped) { it.copy(whatsNew = it.whatsNew.copy(isPresented = false, wasViewed = false)) }
        store.receive(SettingsFeature.Action.Delegate(SettingsDelegate.Close))
        store.withDependencies {
            assertFalse(store.state.whatsNew.isPresented)
            assertFalse(store.state.whatsNew.showsButton)
        }
        store.finish()
    }

    @Test
    fun readingItInSettingsMarksItSeenWhenSettingsCloses() = runTest {
        val shared = SettingsTestShared()
        shared.set(seenIDsKey, emptySet())
        val store = settingsStore(SettingsFeature.State(server = server, whatsNew = WhatsNewFeature.State(entries = listOf(feature("a")))), shared)

        store.send(SettingsFeature.Action.PagePushed(SettingsPage.WhatsNew)) { it.copy(path = listOf(SettingsPage.WhatsNew)) }
        assertFalse(store.state.whatsNew.isPresented)
        store.send(SettingsFeature.Action.WhatsNew(WhatsNewFeature.Action.Viewed)) { it.copy(whatsNew = it.whatsNew.copy(wasViewed = true)) }
        store.withDependencies { assertTrue(store.state.whatsNew.showsButton) }
        store.send(SettingsFeature.Action.BackTapped) { it.copy(path = emptyList()) }
        store.withDependencies { assertTrue("Still in Settings", store.state.whatsNew.showsButton) }
        store.send(SettingsFeature.Action.BackTapped) { it.copy(whatsNew = it.whatsNew.copy(wasViewed = false)) }
        store.receive(SettingsFeature.Action.Delegate(SettingsDelegate.Close))
        store.withDependencies { assertFalse(store.state.whatsNew.showsButton) }
        store.finish()
    }

    /**
     * Every Settings page an entry opens pushes on What’s New’s own stack, over the entry’s page
     * it was tapped on, and Back pops just that page. (Android: Settings' stack; a smaller change
     * is tapped on the Smaller Changes page.)
     */
    @Test
    fun openPushesItsSettingsPageOverTheEntrysPage() = runTest {
        val pages = listOf(
            WhatsNewEntry.SettingsDestination.Themes, WhatsNewEntry.SettingsDestination.Sessions, WhatsNewEntry.SettingsDestination.Voice,
            WhatsNewEntry.SettingsDestination.Notifications, WhatsNewEntry.SettingsDestination.Servers, WhatsNewEntry.SettingsDestination.Experimental,
            WhatsNewEntry.SettingsDestination.Experiment("in-app-browser"), WhatsNewEntry.SettingsDestination.Developer,
        )
        val entryPages = mapOf("a" to SettingsPage.WhatsNewDetail("a"), "fix" to SettingsPage.WhatsNewSmallerChanges)
        val shared = SettingsTestShared()
        val store = settingsStore(whatsNewSettings(listOf(feature("a"), smaller("fix"))), shared)
        for (page in pages) {
            for ((entryID, entryPage) in entryPages) {
                store.send(SettingsFeature.Action.PagePushed(entryPage)) { it.copy(path = listOf(entryPage)) }
                store.send(SettingsFeature.Action.WhatsNew(WhatsNewFeature.Action.TryTapped(WhatsNewEntry.Destination.Open(page), entryID))) {
                    it.copy(path = listOf(entryPage, page.settingsPage()))
                }
                store.send(SettingsFeature.Action.BackTapped) { it.copy(path = listOf(entryPage)) }
                store.send(SettingsFeature.Action.BackTapped) { it.copy(path = emptyList()) }
            }
        }
        store.finish()
    }

    @Test
    fun eachDayIsOneSectionHoldingExactlyItsEntries() {
        val entries = listOf(
            feature("c", day = "2026-10-07"), feature("b"), feature("a"),
            feature("z", day = "2026-10-05"), feature("late", day = "2026-10-07"),
        )
        val days = WhatsNewDay.group(entries)
        assertEquals(listOf("2026-10-07", "2026-10-06", "2026-10-05"), days.map { it.id })
        assertEquals(listOf(listOf("c", "late"), listOf("b", "a"), listOf("z")), days.map { day -> day.entries.map { it.id } })
    }

    @Test
    fun shippedFeaturesAndSmallerChangesGroupIntoUniqueDays() {
        for (list in listOf(WhatsNewEntry.features, WhatsNewEntry.smallerChanges)) {
            val days = WhatsNewDay.group(list)
            assertEquals(days.size, days.map { it.id }.toSet().size)
            assertEquals("Every entry appears once, in its day, in order", list, days.flatMap { it.entries })
        }
    }

    @Test
    fun developerFeaturesStayOutOfTheMainListAndUnseenBadge() {
        val dev = WhatsNewEntry.feature(
            id = "dev-tool", day = "2026-10-13", audience = WhatsNewEntry.Audience.developer, title = "Dev Tool",
            symbol = "hammer", tint = WhatsNewEntry.Tint.orange, summary = "s", details = listOf("d"), example = "e",
        )
        val shared = SettingsTestShared()
        val state = WhatsNewFeature.State(entries = listOf(dev))
        shared.set(seenIDsKey, emptySet())
        shared.set(WhatsNewFeature.buttonEnabledKey, true)
        shared.read {
            assertTrue(state.features.isEmpty())
            assertEquals(listOf(dev), state.developerFeatures)
            assertTrue(state.unseenFeatures.isEmpty())
            assertFalse("Developer-only entries never bring back the drawer What’s New button", state.showsButton)
        }
        assertTrue(WhatsNewEntry.developerFeatures.map { it.id }.contains("mac-motion-playgrounds"))
        assertFalse(WhatsNewEntry.features.map { it.id }.contains("mac-motion-playgrounds"))
    }

    @Test
    fun shippedEntriesAreWellFormed() {
        val entries = WhatsNewEntry.all
        assertEquals("IDs are unique", entries.size, entries.map { it.id }.toSet().size)
        val dates = entries.map { it.day }
        assertEquals("Newest first", dates.sortedDescending(), dates)
        assertTrue(WhatsNewEntry.features.size >= 10)
        assertTrue(WhatsNewEntry.developerFeatures.isNotEmpty())
        assertTrue(WhatsNewEntry.smallerChanges.isNotEmpty())
        assertEquals(entries.size, WhatsNewEntry.features.size + WhatsNewEntry.developerFeatures.size + WhatsNewEntry.smallerChanges.size)
        assertFalse("Report a Problem is gone from the app", entries.any { it.id == "problem-reports" })
        val chat = Regex("\\bchat\\b", RegexOption.IGNORE_CASE)
        for (entry in entries) {
            assertTrue(entry.title.isNotEmpty() && entry.summary.isNotEmpty())
            val text = (listOf(entry.title, entry.summary, entry.example ?: "") + entry.details).joinToString(" ")
            for (stale in listOf(
                "Advanced settings", "Settings → Advanced", "Settings › Advanced", "Settings → Diagnostics",
                "Settings → Change", "Phone Reports", "Voice Mode", "Messages theme", "Rendering Theme",
                "Transcript Rhythm",
            )) {
                assertFalse("${entry.id} names $stale, which no longer exists", text.contains(stale))
            }
            assertFalse("${entry.id} says “chat”; the app says “session”", chat.containsMatchIn(text))
            when (entry.kind) {
                WhatsNewEntry.Kind.feature -> {
                    assertTrue("${entry.id} has details for its page", entry.details.isNotEmpty())
                    assertTrue("${entry.id} says what to try", entry.example?.isNotEmpty() == true)
                }
                WhatsNewEntry.Kind.smaller -> {
                    assertEquals(WhatsNewEntry.Audience.everyone, entry.audience)
                    assertTrue("${entry.id} is one line", entry.details.isEmpty() && entry.example == null)
                }
            }
            val destination = entry.destination
            if (destination is WhatsNewEntry.Destination.Open && destination.page is WhatsNewEntry.SettingsDestination.Experiment) {
                val id = (destination.page as WhatsNewEntry.SettingsDestination.Experiment).id
                assertTrue("${entry.id} opens an experiment that exists", ExperimentalFeature.fromRawValue(id) != null)
                assertTrue("${entry.id} opens a switch that's in the pane", ExperimentalFeature.fromRawValue(id)?.group != null)
            }
            if (destination is WhatsNewEntry.Destination.Prompt) {
                assertTrue("${entry.id} types a prompt", destination.text.isNotEmpty())
            }
        }
        assertTrue("Themes opens its page", entries.any { it.destination == WhatsNewEntry.Destination.Open(WhatsNewEntry.SettingsDestination.Themes) })
    }

    companion object {
        /** The entries shipped after the split: every day after it, plus the few added on the split day itself. */
        val addedSinceSplit: List<String>
            get() = WhatsNewEntry.all
                .filter { it.id in addedOnSplitDay || WhatsNewDay(it.day, emptyList()).id > "2026-10-12" }
                .map { it.id }

        val addedOnSplitDay: Set<String> = setOf(
            "whats-new-page", "transcript-day-stamps", "catch-up-returns-to-drawer", "rich-link-previews",
        )

        /**
         * Every ID What’s New listed before features and smaller changes.
         * Seen state is stored by these IDs.
         */
        val previousIDs: Set<String> = setOf(
            "transcript-keyboard-space", "share-sheet-sends", "run-error-line", "model-list-and-level-meter",
            "new-messages-divider", "answer-from-the-bar", "browser-push-in", "stop-runs-your-steer", "model-switch-rule",
            "new-session-recent-projects", "steer-moves-work-to-background", "ride-out-drops", "drawer-title-dims-offline",
            "paste-as-a-card", "drawer-one-edge", "call-voice", "share-opened-files", "connection-heartbeat", "shell-glyph",
            "server-switcher", "always-notify", "send-never-waits", "hold-stop-to-call", "working-orb", "math-and-diagrams",
            "session-switch-cache", "request-dock", "siri-shortcuts-spotlight", "dictation-keeps-going", "photos-zoom",
            "drawer-peek", "steering-menu", "link-previews", "retry-failed-send", "pair-from-anywhere", "mute-sessions",
            "tool-images-whole", "voice-webrtc-weak-connection", "status-orb-and-arrival-glow", "media-drag-to-dismiss",
            "attachment-menu-hold", "dictation-send", "voice-manages-sessions", "notify-when-done", "voice-messages",
            "catch-up-shortcuts", "voice-gpt-live", "voice-live-activity", "voice-call-captions", "problem-reports",
            "camera-controls", "voice-calls-keep-going", "voice-calls-from-composer", "app-icon-badge", "drawer-keeps-keyboard",
        )
    }
}

/** The Settings half: Try It keeps the page for what can't go now. */
class WhatsNewTryItTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")

    /** What's New pushed over the drawer. */
    private fun whatsNewSettings() =
        SettingsFeature.State.whatsNew(server).copy(whatsNew = WhatsNewFeature.State(isPresented = true))

    private fun TestScope.store(state: SettingsFeature.State, shared: SettingsTestShared = SettingsTestShared()) =
        TestStore(state, SettingsFeature(), this) { this[SharedStore] = shared.store }

    /** Nothing leaves for Home. */
    @Test
    fun aNewSessionWhileOfflineKeepsThePage() = runTest {
        val store = store(whatsNewSettings().copy(connectionStatus = ConnectionStatus.disconnected))
        for (destination in listOf(WhatsNewEntry.Destination.NewSession, WhatsNewEntry.Destination.Prompt("Draw a flowchart"))) {
            store.send(SettingsFeature.Action.WhatsNew(WhatsNewFeature.Action.TryTapped(destination, "a")))
            assertTrue(store.state.whatsNew.isPresented)
            assertTrue("What’s New stays up", store.state.path.isEmpty())
        }
        store.finish()
    }

    @Test
    fun aCallBeforeVoiceIsSetUpPushesVoiceSettingsOnThePage() = runTest {
        val shared = SettingsTestShared()
        shared.set(SharedKeys.voiceModeEnabled, false)
        shared.set(SharedKeys.openAIAPIKey, null)
        val store = store(whatsNewSettings(), shared)
        store.send(SettingsFeature.Action.WhatsNew(WhatsNewFeature.Action.TryTapped(WhatsNewEntry.Destination.Call, "call-voice"))) {
            it.copy(path = listOf(SettingsPage.Voice))
        }
        assertTrue("The page stays What’s New, never a latched Settings", store.state.whatsNew.isPresented)
        assertEquals(SettingsFeature.State.Root.whatsNew, store.state.root)

        store.send(SettingsFeature.Action.BackTapped) { it.copy(path = emptyList()) }
        store.finish()
    }

    /** The push shows Settings' root. */
    @Test
    fun settingsAskedForOverWhatsNewShowsSettings() = runTest {
        val store = store(whatsNewSettings())
        store.send(SettingsFeature.Action.ShowSettingsRoot) {
            it.copy(root = SettingsFeature.State.Root.settings, whatsNew = it.whatsNew.copy(isPresented = false))
        }
        store.finish()
    }
}

/** The page's [DotField] over the symbol's Material Symbol drawable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WhatsNewDotSymbolTests {
    @get:Rule val isolation = TestIsolationRule()

    /** A feature's symbol lights its own shape on the grid, not its bounds. */
    @Test
    fun aSymbolLightsItsShapeOnTheGrid() {
        val drawable = RuntimeEnvironment.getApplication().getDrawable(sfSymbol("plus"))
        assertNotNull(drawable)
        val field = DotField.make(drawable!!, width = 350f, height = 220f, pitch = 3.5f)
        assertTrue(field.columns == 100 && field.rows == 62)
        val lit = field.lit.map { it.row * field.columns + it.column }.toSet()
        assertTrue("The plus's centre is lit", 31 * 100 + 50 in lit)
        val rows = field.lit.map { it.row }
        val columns = field.lit.map { it.column }
        val top = rows.min()
        val bottom = rows.max()
        val left = columns.min()
        val right = columns.max()
        assertTrue("Its bounding box's corners stay dark", top * 100 + left !in lit && bottom * 100 + right !in lit)
        assertTrue(field.lit.size.toDouble() < (bottom - top + 1) * (right - left + 1) * 0.6)
    }
}
