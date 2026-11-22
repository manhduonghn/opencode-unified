package ai.opencode.app

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.ServerSwitchNote
import ai.opencode.feature.session.ServerFlowTunables
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.friendlyName
import kotlin.time.Duration.Companion.seconds

// A switch from the drawer keeps the Home it left, and goes back to it if the server fails or
// doesn't answer in [ServerFlowTunables.timeout].

internal fun AppFeature.switchServer(server: OpenCodeServer, state: AppFeature.State): Reduced<AppFeature.State, AppFeature.Action> {
    val home = state.home ?: return Reduced(state)
    var next = state.copy(home = home.copy(serverSwitchNote = null))
    // Picking the server in use while another connects turns back.
    if (home.server.persistenceID == server.persistenceID) {
        return if (home.switchingTo == null) Reduced(next) else switchBack(next, reason = null)
    }
    if (home.switchingTo?.persistenceID == server.persistenceID) return Reduced(next)
    // A switch that fails leaves the picker offering the way back.
    next = next.copy(returnServer = home.server)
    val keepsHome = next.keepsHome(switchingTo = server)
    if (keepsHome) next = next.showingSwitch(server)
    val connecting = selectServer(server, next, keepingHome = keepsHome)
    val attempt = connecting.state.connectionAttempt
    if (!keepsHome || attempt == null) return connecting
    val clock = dependency(Clock)
    val seconds = ServerFlowTunables.timeout
    return Reduced(
        connecting.state.copy(connectionAttempt = attempt.copy(switchesBack = true)),
        Effect.merge(
            connecting.effect,
            Effect.run<AppFeature.Action> { send ->
                clock.sleep(seconds.seconds)
                send(AppFeature.Action.ServerSwitchTimedOut(attempt.id))
            }.cancellable(AppFeature.CancelID.SwitchTimeout, cancelInFlight = true),
        ),
    )
}

internal fun serverSwitchTimedOut(attemptID: Int, state: AppFeature.State): Reduced<AppFeature.State, AppFeature.Action> {
    val attempt = state.connectionAttempt
    if (attempt?.id != attemptID || !attempt.switchesBack) return Reduced(state)
    AppLog.network.notice { "Server switch timed out attempt=$attemptID" }
    return switchBack(state, reason = ServerSwitchNote.Reason.TimedOut(ServerFlowTunables.timeout))
}

/** A failed switch's error, as the note's reason. */
internal fun switchBack(state: AppFeature.State, after: OpenCodeClientError): Reduced<AppFeature.State, AppFeature.Action> {
    val rejected = after.statusCode == HomeFeature.credentialsRejectedStatus
    AppLog.network.notice { "Server switch failed status=${after.statusCode ?: 0}, switching back" }
    return switchBack(state, reason = if (rejected) ServerSwitchNote.Reason.SignInFailed else ServerSwitchNote.Reason.Unreachable)
}

/**
 * Ends the switch and keeps the Home it left. With a reason, it went back on its own: the note
 * says why, and the menu marks the server.
 */
internal fun switchBack(state: AppFeature.State, reason: ServerSwitchNote.Reason?): Reduced<AppFeature.State, AppFeature.Action> {
    val home = state.home ?: return Reduced(state)
    val target = home.switchingTo ?: return Reduced(state)
    var next = state.copy(
        connectionAttempt = null,
        connection = AppFeature.State.Connection.Connected,
        returnServer = null,
    ).load(home.server)
    // The target's health check remembered it as the server to reopen.
    if (next.savedServer.value?.persistenceID != home.server.persistenceID) next.savedServer.set(home.server)
    var kept = home.copy(switchingTo = null)
    if (reason != null) {
        val note = ServerSwitchNote(server = target, returnedTo = home.server.friendlyName, reason = reason)
        kept = kept.copy(
            serverSwitchNote = note,
            serverReachability = kept.serverReachability + (target.persistenceID to note.reachability),
        )
    }
    next = next.copy(home = kept)
    return Reduced(
        next,
        Effect.cancel(listOf(AppFeature.CancelID.Health, AppFeature.CancelID.Home, AppFeature.CancelID.SwitchTimeout)),
    )
}
