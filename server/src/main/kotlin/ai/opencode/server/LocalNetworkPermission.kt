package ai.opencode.server

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.strings.localized
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android 17 blocks the local network until the app holds `ACCESS_LOCAL_NETWORK` (Nearby devices).
 * The visible activity registers how to ask while it's resumed, as for the microphone.
 */
class LocalNetworkPermission(private val isGranted: () -> Boolean, val openSettings: () -> Unit) {
    private val lock = Any()
    private var requester: (suspend () -> Boolean)? = null

    /** [server] has a local network address and access is off. */
    fun blocks(server: OpenCodeServer): Boolean =
        !isGranted() && server.candidateURLs.any { ConnectionRoute.of(it) == ConnectionRoute.LocalNetwork }

    fun register(requester: suspend () -> Boolean): () -> Unit {
        synchronized(lock) { this.requester = requester }
        return { synchronized(lock) { if (this.requester === requester) this.requester = null } }
    }

    /** Shows the system dialog; true when it was allowed. */
    suspend fun request(): Boolean {
        val requester = synchronized(lock) { requester } ?: return false
        return withContext(Dispatchers.Main) { requester() }
    }

    companion object Key : DependencyKey<LocalNetworkPermission> {
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        val DENIED_MESSAGE: String get() = localized(R.string.server_local_network_denied)

        override val liveValue: LocalNetworkPermission by lazy {
            val context = dependency(AppContext).applicationContext
            LocalNetworkPermission(
                isGranted = { Build.VERSION.SDK_INT < 37 || context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED },
                openSettings = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
            )
        }
        override val testValue: LocalNetworkPermission get() = LocalNetworkPermission(isGranted = { true }, openSettings = {})
    }
}
