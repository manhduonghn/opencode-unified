package ai.opencode.feature.drawer

import ai.opencode.core.strings.localized
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.feature.system.notifications.NotificationPermission
import ai.opencode.feature.system.notifications.NotificationPermissionClient
import ai.opencode.feature.system.notifications.SessionNotificationsClient
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeNotifyPluginStatus
import kotlinx.coroutines.CancellationException

/**
 * This phone's notification permission and the server's notify plugin, as Settings › Notifications
 * reads them. Home supplies the current server and keeps [Status] as `notificationState`.
 */
class HomeNotificationsFeature : Reducer<HomeNotificationsFeature.State, HomeNotificationsFeature.Action> {
    data class Status(
        /** Null until read, and while this build offers no notifications. */
        val notificationPermission: NotificationPermission? = null,
        val isRequestingNotifications: Boolean = false,
        val notificationSetupError: String? = null,
        /** The server half of notifications, read when Notifications opens. */
        val notifyPlugin: NotifyPluginCheck? = null,
    )

    sealed interface NotifyPluginCheck {
        data object Checking : NotifyPluginCheck
        data class Checked(val status: OpenCodeNotifyPluginStatus) : NotifyPluginCheck
        data object Failed : NotifyPluginCheck
    }

    data class State(
        val server: OpenCodeServer,
        val status: Status = Status(),
    )

    sealed interface Action {
        data object NotificationSettingsRequested : Action
        data class NotificationPermissionLoaded(val permission: NotificationPermission) : Action
        data object EnableNotificationsTapped : Action
        data class NotificationPermissionRequested(val permission: NotificationPermission) : Action
        data class NotificationSettingsOpened(val opened: Boolean) : Action
        data object NotifyPluginCheckRequested : Action
        data class NotifyPluginChecked(val status: OpenCodeNotifyPluginStatus?) : Action
    }

    private enum class CancelID { notifyPlugin }

    override fun reduce(state: State, action: Action): Reduced<State, Action> {
        val status = state.status
        return when (action) {
            Action.NotificationSettingsRequested -> {
                val sessionNotifications = dependency(SessionNotificationsClient)
                if (!sessionNotifications.isAvailable) return Reduced(state)
                val notificationPermission = dependency(NotificationPermissionClient)
                Reduced(state, Effect.run { send -> send(Action.NotificationPermissionLoaded(notificationPermission.status())) })
            }

            is Action.NotificationPermissionLoaded -> {
                val next = state.copy(status = status.copy(notificationPermission = action.permission))
                if (action.permission != NotificationPermission.Enabled) return Reduced(next)
                // Mint ahead so the next Send rarely waits on the relay.
                val sessionNotifications = dependency(SessionNotificationsClient)
                val server = state.server
                Reduced(next, Effect.run { sessionNotifications.prepare(server) })
            }

            Action.EnableNotificationsTapped -> {
                if (status.isRequestingNotifications) return Reduced(state)
                val next = state.copy(status = status.copy(notificationSetupError = null, isRequestingNotifications = true))
                val notificationPermission = dependency(NotificationPermissionClient)
                val permission = status.notificationPermission
                if (permission != null && permission != NotificationPermission.NotDetermined) {
                    // Disabled or enabled: only the system's settings can change it.
                    return Reduced(next, Effect.run { send -> send(Action.NotificationSettingsOpened(notificationPermission.openSettings())) })
                }
                Reduced(next, Effect.run { send -> send(Action.NotificationPermissionRequested(notificationPermission.request())) })
            }

            is Action.NotificationPermissionRequested -> {
                val error = if (action.permission == NotificationPermission.NotDetermined) {
                    localized(R.string.drawer_notifications_request_error)
                } else {
                    status.notificationSetupError
                }
                Reduced(
                    state.copy(status = status.copy(isRequestingNotifications = false, notificationSetupError = error)),
                    Effect.send(Action.NotificationPermissionLoaded(action.permission)),
                )
            }

            is Action.NotificationSettingsOpened -> {
                val error = if (!action.opened) {
                    localized(R.string.drawer_notifications_open_settings_error)
                } else {
                    status.notificationSetupError
                }
                Reduced(state.copy(status = status.copy(isRequestingNotifications = false, notificationSetupError = error)))
            }

            Action.NotifyPluginCheckRequested -> {
                val openCodeClient = dependency(OpenCodeClient)
                val server = state.server
                Reduced(
                    state.copy(status = status.copy(notifyPlugin = NotifyPluginCheck.Checking)),
                    Effect.run<Action> { send ->
                        val result = try {
                            openCodeClient.notifyPluginStatus(server)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            null
                        }
                        send(Action.NotifyPluginChecked(result))
                    }.cancellable(CancelID.notifyPlugin, cancelInFlight = true),
                )
            }

            is Action.NotifyPluginChecked -> Reduced(
                state.copy(status = status.copy(notifyPlugin = action.status?.let(NotifyPluginCheck::Checked) ?: NotifyPluginCheck.Failed)),
            )
        }
    }
}
