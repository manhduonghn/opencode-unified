package ai.opencode.feature.session

import ai.opencode.core.R as CoreR
import ai.opencode.core.strings.localized
import ai.opencode.server.OpenCodeClientError
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeNotice
import ai.opencode.core.ui.OpenCodeNoticeTone
import ai.opencode.core.ui.SFSymbol
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics

/**
 * Server Flow's tunables, moved down from the drawer because the session's [ServerConnectionPill]
 * reads the pill's palette; the app reads the switch-back timeout. Constants here: Android has no Tuning Panel.
 */
object ServerFlowTunables {
    /** Switch back after (s). */
    const val timeout = 8.0
    /** Offline after this long with two failed attempts (s). */
    const val offlineGrace = 12.0
    /** Offline sooner when the phone has no network at all (s). */
    const val unsatisfiedOfflineGrace = 4.0
    /**
     * How long a reconnect stays quiet before “Reconnecting…” shows (s; the pill and the drawer
     * title), so the first connect at launch, a return to the app and a quick reconnect never
     * flash it (`HomeConnectionFeature.State.displayedStatus`).
     */
    const val grace = 2.0
}

/**
 * What a session's pill says while its server isn't connected. The drawer builds it from Home's
 * connection (`ServerConnectionNotice(status:…)` in `:feature:drawer`'s `ServerFlow.kt`).
 */
data class ServerConnectionNotice(
    val kind: Kind,
    val serverName: String,
    /** Why it failed, when the error says more than the title. */
    val detail: String? = null,
) {
    enum class Kind { reconnecting, failed, signInFailed }

    enum class Action { retry, pairAgain, editServer }

    val title: String
        get() = when (kind) {
            Kind.reconnecting -> localized(R.string.session_server_reconnecting, serverName)
            Kind.failed -> localized(R.string.session_server_connect_failed, serverName)
            Kind.signInFailed -> localized(R.string.session_server_sign_in_failed, serverName)
        }

    /** The pill's tap: retry, or pair again for a sign-in the server refused. */
    val primary: Action get() = if (kind == Kind.signInFailed) Action.pairAgain else Action.retry

    /** The word at the pill's end, naming its tap. */
    val action: String?
        get() = when (kind) {
            Kind.reconnecting -> null
            Kind.failed -> localized(CoreR.string.common_try_again)
            Kind.signInFailed -> localized(R.string.session_server_pair_again)
        }

    /** What TalkBack says the pill's tap does (“Double-tap to try again”). */
    val actionClickLabel: String?
        get() = when (kind) {
            Kind.reconnecting -> null
            Kind.failed -> localized(R.string.session_server_try_again_click_label)
            Kind.signInFailed -> localized(R.string.session_server_pair_again_click_label)
        }

    val systemImage: SFSymbol
        get() = when (kind) {
            Kind.reconnecting -> SFSymbol.ArrowTriangle2Circlepath
            Kind.failed -> SFSymbol.ExclamationmarkCircleFill
            Kind.signInFailed -> SFSymbol.KeyFill
        }

    companion object {
        /**
         * Null while connected and while the drawer switches servers (the switch is the drawer's to
         * show). Pass Home's displayed status, which reads connected while a reconnect is still in
         * its grace.
         */
        fun of(
            isConnected: Boolean,
            isReconnecting: Boolean,
            credentialsRejected: Boolean,
            errorMessage: String?,
            serverName: String,
            isSwitching: Boolean,
        ): ServerConnectionNotice? {
            if (isSwitching || isConnected) return null
            if (isReconnecting) return ServerConnectionNotice(Kind.reconnecting, serverName)
            if (credentialsRejected) return ServerConnectionNotice(Kind.signInFailed, serverName)
            // The pill already says the server couldn't be reached, so that message adds nothing as a detail.
            // The English check covers the other “Couldn’t …” messages; in other languages those show as the detail.
            val detail = errorMessage?.takeIf {
                it.isNotEmpty() && it != OpenCodeClientError.UNREACHABLE_MESSAGE && !it.startsWith("Couldn’t")
            }
            return ServerConnectionNotice(Kind.failed, serverName, detail)
        }
    }
}

/**
 * Server Flow: a pill right above the composer while the session's server isn't connected. It says
 * what's happening, and its tap retries (or pairs again, for a sign-in the server refused); a long
 * press offers Edit Server… then.
 */
@Composable
fun ServerConnectionPill(
    notice: ServerConnectionNotice,
    onAction: (ServerConnectionNotice.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    val offersEdit = notice.kind == ServerConnectionNotice.Kind.signInFailed
    var menuShown by remember { mutableStateOf(false) }
    val editServerLabel = stringResource(R.string.session_server_edit)
    Box(modifier) {
        OpenCodeNotice(
            title = notice.title,
            detail = notice.detail,
            tone = when (notice.kind) {
                ServerConnectionNotice.Kind.reconnecting -> OpenCodeNoticeTone.progress
                ServerConnectionNotice.Kind.failed -> OpenCodeNoticeTone.failure
                ServerConnectionNotice.Kind.signInFailed -> OpenCodeNoticeTone.attention
            },
            symbol = notice.systemImage,
            action = notice.action,
            onClickLabel = notice.actionClickLabel,
            onLongClick = if (offersEdit) ({ menuShown = true }) else null,
            onClick = { onAction(notice.primary) },
            modifier = Modifier
                .semantics {
                    if (offersEdit) {
                        customActions = listOf(CustomAccessibilityAction(editServerLabel) { onAction(ServerConnectionNotice.Action.editServer); true })
                    }
                }
                .testTag("server-connection-pill"),
        )
        DropdownMenu(expanded = menuShown && offersEdit, onDismissRequest = { menuShown = false }) {
            DropdownMenuItem(
                text = { Text(editServerLabel) },
                leadingIcon = { OpenCodeIcon(SFSymbol.Pencil, contentDescription = null) },
                onClick = {
                    menuShown = false
                    onAction(ServerConnectionNotice.Action.editServer)
                },
            )
        }
    }
}
