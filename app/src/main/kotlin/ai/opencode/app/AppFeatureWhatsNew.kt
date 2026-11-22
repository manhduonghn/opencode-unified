package ai.opencode.app

import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.settings.whatsnew.WhatsNewFeature

/* Applied in `AppFeature.body` as `.keepingWhatsNewAcrossHomes()`. */

/**
 * What’s New stays as it was, or what was read is marked seen, as one Home replaces another. On
 * Android it lives on Settings' stack.
 */
internal class WhatsNewAcrossHomes(
    private val base: Reducer<AppFeature.State, AppFeature.Action>,
) : Reducer<AppFeature.State, AppFeature.Action> {
    override fun reduce(state: AppFeature.State, action: AppFeature.Action): Reduced<AppFeature.State, AppFeature.Action> {
        val before = state.home?.settings?.whatsNew
        val reduced = base.reduce(state, action)
        if (before == null || !(before.isPresented || before.wasViewed)) return reduced
        // Home settles What’s New on its own changes, so only what AppFeature does to Home in its
        // delegates and responses counts.
        if (action is AppFeature.Action.Home && action.action !is HomeFeature.Action.Delegate) return reduced
        val home = reduced.state.home
        val after = home?.settings?.whatsNew
        if (after != null && (after.isPresented || after.wasViewed)) return reduced
        val settings = home?.settings
        if (home != null && settings != null) {
            val whatsNew = settings.whatsNew.copy(isPresented = before.isPresented, wasViewed = before.wasViewed)
            return Reduced(reduced.state.copy(home = home.copy(settings = settings.copy(whatsNew = whatsNew))), reduced.effect)
        }
        before.markAllSeen()
        // The drawer's row reads the seen IDs once per Home; this one was built before they changed.
        val shows = WhatsNewFeature.State().showsButton
        if (home == null || home.showsWhatsNewButton == shows) return reduced
        return Reduced(reduced.state.copy(home = home.copy(showsWhatsNewButton = shows)), reduced.effect)
    }
}

internal fun Reducer<AppFeature.State, AppFeature.Action>.keepingWhatsNewAcrossHomes(): Reducer<AppFeature.State, AppFeature.Action> =
    WhatsNewAcrossHomes(this)
