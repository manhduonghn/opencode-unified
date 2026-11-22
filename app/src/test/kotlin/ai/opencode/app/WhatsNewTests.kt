package ai.opencode.app

import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.settings.SettingsFeature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewFeature
import ai.opencode.feature.settings.whatsnew.all
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** What's New is Settings' page on Android, so `isSettingsShown` is `settings != null`. */
class WhatsNewTryItTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
    private val shared = AppTestShared()

    /** What's New pushed over the drawer. */
    private fun home(): HomeFeature.State {
        val page = SettingsFeature.State.whatsNew(server)
        return HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet()))
            .copy(settings = page.copy(whatsNew = page.whatsNew.copy(isPresented = true)))
    }

    /** A Home replacement (a server switched from the server page) keeps the page when the push stays up, and otherwise marks what was read seen. */
    @Test
    fun whatsNewSurvivesAHomeReplacement() = shared.run {
        val seenIDs = shared(WhatsNewFeature.seenIDsKey)
        seenIDs.set(emptySet())
        val other = OpenCodeServer(url = "http://localhost:10", username = "", password = "")
        fun replacing(keepsSettings: Boolean): AppFeature.State {
            val start = home()
            val settings = start.settings!!
            val state = AppFeature.State.initial().copy(
                home = start.copy(settings = settings.copy(whatsNew = settings.whatsNew.copy(wasViewed = true))),
            )
            val reducer = WhatsNewAcrossHomes(Reducer { s, _ ->
                val replaced = HomeFeature.State(other, OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet()))
                Reduced(s.copy(home = replaced.copy(settings = if (keepsSettings) SettingsFeature.State(server = other) else null)))
            })
            return reducer.reduce(state, AppFeature.Action.Task).state
        }

        val kept = replacing(keepsSettings = true)
        assertEquals(true, kept.home?.settings?.whatsNew?.isPresented)
        assertEquals(true, kept.home?.settings?.whatsNew?.wasViewed)
        assertTrue("Still being read", seenIDs.value.isEmpty())

        val left = replacing(keepsSettings = false)
        assertNull(left.home?.settings?.whatsNew)
        assertTrue("Read, then taken away: seen", seenIDs.value.containsAll(WhatsNewEntry.all.map { it.id }))
    }
}
