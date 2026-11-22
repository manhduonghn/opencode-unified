package ai.opencode.feature.system.notifications

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app icon's badge, how many sessions Catch Up holds on the server in use.
 *
 * Android has no app-set icon count: launchers draw a dot while notifications show, and a count
 * from the notifications' `number`. So the count rides on the session notifications: the push
 * handler posts new ones with the ledger's count, and each publish re-posts the ones still showing
 * with the new count ([SessionPushNotifications.applyBadge]). The ledger keeps it close between
 * launches.
 */
class AppIconBadgeClient(
    /**
     * Records the ledger's count and leaves the ledger for the push handler, with the `ref`
     * of the server named by `persistenceID` filled in.
     */
    val publish: suspend (ledger: AppBadgeLedger, persistenceID: String) -> Unit,
    /** Clears the badge if it counts this server (by persistence ID), which is no longer saved under it. */
    val forget: suspend (persistenceID: String) -> Unit,
) {
    companion object Key : DependencyKey<AppIconBadgeClient> {
        override val liveValue: AppIconBadgeClient by lazy {
            AppIconBadgeClient(
                publish = { ledger, id -> AppIconBadge.shared.publish(ledger, id) },
                forget = { AppIconBadge.shared.forget(it) },
            )
        }
        override val testValue = AppIconBadgeClient(publish = { _, _ -> }, forget = {})
    }
}

internal class AppIconBadge private constructor() {
    private var generation = 0
    private var shownCount: Int? = null
    /** The server the badge last counted. */
    private var countedKey: String? = null

    /** The count the launcher shows from notifications. */
    var count: Int = 0
        private set

    suspend fun publish(ledger: AppBadgeLedger, persistenceID: String) = withContext(Dispatchers.Main.immediate) {
        val sessionNotifications = dependency(SessionNotificationsClient)
        val context = dependency(AppContext)
        generation += 1
        val current = generation
        countedKey = ledger.serverKey
        ledger.copy(ref = sessionNotifications.ref(persistenceID)).save(AppBadgeLedger.groupDefaults(context))
        sessionNotifications.includeBadges()
        // A later publish has the newer count.
        if (current != generation) return@withContext
        setCount(ledger.count)
    }

    suspend fun forget(persistenceID: String) = withContext(Dispatchers.Main.immediate) {
        val context = dependency(AppContext)
        val defaults = AppBadgeLedger.groupDefaults(context)
        val key = AppBadgeLedger.serverKey(persistenceID)
        // Load drops a ledger saved with a plaintext key.
        if (countedKey != key && AppBadgeLedger.load(defaults)?.serverKey != key) return@withContext
        generation += 1
        countedKey = null
        AppBadgeLedger.remove(defaults)
        setCount(0)
    }

    private fun setCount(count: Int) {
        if (shownCount != count) AppLog.app.info { "App badge count=$count" }
        shownCount = count
        this.count = count
        SessionPushNotifications.applyBadge(dependency(AppContext), count)
    }

    companion object {
        val shared = AppIconBadge()
    }
}
