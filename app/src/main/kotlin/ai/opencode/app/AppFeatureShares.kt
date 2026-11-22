package ai.opencode.app

import ai.opencode.core.files.ComposerFileStaging
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.strings.localized
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.system.share.ReceivedShare

/*
 * Share-sheet items, taken from the share inbox when the app becomes active, wait for their
 * server's Home and are handed to it.
 */

internal fun AppFeature.receiveShares(shares: List<ReceivedShare>, state: AppFeature.State): Reduced<AppFeature.State, AppFeature.Action> {
    LaunchBloomController.completeColdLaunch()
    val saved = state.knownServers
    val known = shares.filter { share -> saved.any { it.persistenceID == share.handoff.serverID } }
    var next = state
    if (known.size < shares.size) {
        next = next.copy(
            shareError = localized(R.string.app_share_server_no_longer_saved),
        )
        discardStagedFiles(shares.filter { share -> known.none { it.handoff.id == share.handoff.id } })
    }
    next = next.copy(pendingShares = (next.pendingShares + known).sortedBy { it.handoff.createdAt })
    val serverID = next.pendingShares.lastOrNull()?.handoff?.serverID ?: return Reduced(next)
    if (next.home?.server?.persistenceID == serverID) return deliverPendingShares(next)
    // Shares name the server whose sessions the sheet offered; switch to it.
    val server = saved.firstOrNull { it.persistenceID == serverID }
    if (server == null || next.connectingServerID == server.id) return Reduced(next)
    return selectServer(server, next)
}

/** Shares that will never reach a composer would otherwise leave their staged files behind. */
internal fun discardStagedFiles(shares: List<ReceivedShare>) {
    shares.flatMap { it.files }.forEach { ComposerFileStaging.discard(it.url) }
}

/** Hands the open server's shares to Home, which opens the newest target. */
internal fun deliverPendingShares(state: AppFeature.State): Reduced<AppFeature.State, AppFeature.Action> {
    val serverID = state.home?.server?.persistenceID ?: return Reduced(state)
    val ready = state.pendingShares.filter { it.handoff.serverID == serverID }
    if (ready.isEmpty()) return Reduced(state)
    return Reduced(
        state.copy(pendingShares = state.pendingShares.filterNot { it.handoff.serverID == serverID }),
        Effect.send(AppFeature.Action.Home(HomeFeature.Action.SharesReceived(ready))),
    )
}
