package ai.opencode.feature.drawer

import ai.opencode.core.R as CoreR
import ai.opencode.core.strings.localized
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.appFormattingLocale
import ai.opencode.feature.session.ServerConnectionNotice
import ai.opencode.feature.session.ServerFlowTunables
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.friendlyName
import androidx.annotation.StringRes

/*
 * Server Flow: what the drawer and a session show of the servers' connections. Picking a server
 * swaps the drawer to it at once with Title Leads' motion: its name rolls in, ghost rows follow it
 * like a chain, and an underline shimmers under the name until the server answers. One that fails,
 * or doesn't answer in `ServerFlowTunables.timeout`, is switched back from on its own with a note
 * (the switch and switch-back are AppFeature's). A session whose server drops shows a pill above
 * the composer that retries.
 *
 * `ServerFlowTunables` and `ServerConnectionNotice` live in `:feature:session`
 * (`ServerConnectionPill.kt`), which reads the pill's palette and carries the notice in
 * `SessionListContext`.
 */

/** Why a server was last given up on, shown in the server menu. */
enum class ServerReachability { unreachable, signInFailed }

/** Left in the drawer after a switch went back on its own. */
data class ServerSwitchNote(
    /** The server that didn't answer. */
    val server: OpenCodeServer,
    /** The name of the server it went back to. */
    val returnedTo: String,
    val reason: Reason,
) {
    sealed interface Reason {
        /** It didn't answer within this many seconds. */
        data class TimedOut(val seconds: Double) : Reason
        data object Unreachable : Reason
        data object SignInFailed : Reason
    }

    val title: String get() = localized(R.string.drawer_server_switched_back_title, returnedTo)

    val detail: String
        get() {
            val name = server.friendlyName
            return when (reason) {
                is Reason.TimedOut -> {
                    val seconds = reason.seconds
                    val shown = if (seconds == Math.rint(seconds)) seconds.toLong().toString() else String.format(appFormattingLocale(), "%.1f", seconds)
                    localized(R.string.drawer_server_timed_out_detail, name, shown)
                }
                Reason.Unreachable -> localized(R.string.drawer_server_unreachable_detail, name)
                Reason.SignInFailed -> localized(R.string.drawer_server_sign_in_failed_detail, name)
            }
        }

    val systemImage: SFSymbol
        get() = when (reason) {
            is Reason.TimedOut -> SFSymbol.Clock
            Reason.Unreachable -> SFSymbol.ExclamationmarkCircleFill
            Reason.SignInFailed -> SFSymbol.KeyFill
        }

    /** The note's button: pair again for a sign-in the server refused, otherwise try again. */
    val action: String get() = if (reason == Reason.SignInFailed) localized(R.string.drawer_server_pair_again) else localized(CoreR.string.common_try_again)

    /** What the server menu shows for the server afterwards. */
    val reachability: ServerReachability
        get() = if (reason == Reason.SignInFailed) ServerReachability.signInFailed else ServerReachability.unreachable
}

/** A server's status in the server menu: a glyph and a word. */
enum class ServerMenuStatus(@StringRes private val titleRes: Int, val systemImage: SFSymbol) {
    connected(R.string.drawer_server_menu_connected, SFSymbol.CircleFill),
    connecting(R.string.drawer_server_menu_connecting, SFSymbol.ArrowTriangle2Circlepath),
    unreachable(R.string.drawer_server_menu_unreachable, SFSymbol.ExclamationmarkCircle),
    signInFailed(R.string.drawer_server_sign_in_failed, SFSymbol.Key),
    ;

    val title: String get() = localized(titleRes)

    companion object {
        fun of(
            server: OpenCodeServer,
            current: OpenCodeServer,
            status: HomeConnectionFeature.State.ConnectionStatus,
            credentialsRejected: Boolean,
            switchingTo: OpenCodeServer?,
            reachability: Map<String, ServerReachability>,
        ): ServerMenuStatus? {
            val id = server.persistenceID
            if (id == switchingTo?.persistenceID) return connecting
            if (id == current.persistenceID) {
                return when (status) {
                    HomeConnectionFeature.State.ConnectionStatus.connected -> connected
                    HomeConnectionFeature.State.ConnectionStatus.reconnecting -> connecting
                    HomeConnectionFeature.State.ConnectionStatus.disconnected -> if (credentialsRejected) signInFailed else unreachable
                }
            }
            return when (reachability[id]) {
                ServerReachability.unreachable -> unreachable
                ServerReachability.signInFailed -> signInFailed
                null -> null
            }
        }
    }
}

/**
 * Null while connected and while the drawer switches servers (the switch is the drawer's to show).
 * Pass [HomeConnectionFeature.State.displayedStatus], which reads connected while a reconnect is
 * still in its grace.
 */
operator fun ServerConnectionNotice.Companion.invoke(
    status: HomeConnectionFeature.State.ConnectionStatus,
    credentialsRejected: Boolean,
    errorMessage: String?,
    server: OpenCodeServer,
    isSwitching: Boolean,
): ServerConnectionNotice? = of(
    isConnected = status == HomeConnectionFeature.State.ConnectionStatus.connected,
    isReconnecting = status == HomeConnectionFeature.State.ConnectionStatus.reconnecting,
    credentialsRejected = credentialsRejected,
    errorMessage = errorMessage,
    serverName = server.friendlyName,
    isSwitching = isSwitching,
)

/** The server the drawer shows. */
data class ServerSwapTarget(
    /** Its persistence ID. */
    val id: String,
    /** Not answered yet: ghost rows hold the list. */
    val isConnecting: Boolean,
) {
    companion object {
        /** The server picked, from the moment it's picked. */
        operator fun invoke(current: OpenCodeServer, switchingTo: OpenCodeServer?): ServerSwapTarget {
            val shown = switchingTo ?: current
            return ServerSwapTarget(id = shown.persistenceID, isConnecting = switchingTo != null)
        }
    }
}
