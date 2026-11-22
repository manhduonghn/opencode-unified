package ai.opencode.feature.settings.servers

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred

/** Camera authorization status, as far as Android can tell it. */
enum class CameraAuthorization {
    /** Not asked yet in this process (or asked and the answer can still change). */
    notDetermined,
    authorized,

    /** Refused: asking again shows nothing, so the way back is the app's page in system Settings. */
    denied,
}

/**
 * Camera access for pairing. Honest about hardware: [isAvailable] is false on a device or emulator
 * without a camera, and [authorize] then answers false without asking.
 *
 * Android asks through an Activity, so the live [authorize] goes through the permission launcher
 * that [CameraPermissionHost] registers while a scanner is on screen; with no host it answers with
 * the current grant.
 */
class ServerCameraClient(
    /** A camera exists. */
    val isAvailable: () -> Boolean = { false },
    val authorization: () -> CameraAuthorization = { CameraAuthorization.notDetermined },
    /** Asks for access if it isn't decided yet; true when the camera may be used. */
    val authorize: suspend () -> Boolean = { false },
    /** Opens this app's page in system Settings, where camera access is turned back on. */
    val openSettings: () -> Unit = {},
) {
    companion object Key : DependencyKey<ServerCameraClient> {
        override val liveValue: ServerCameraClient by lazy {
            val context = dependency(AppContext)
            fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            fun available() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
            ServerCameraClient(
                isAvailable = ::available,
                authorization = {
                    when {
                        granted() -> CameraAuthorization.authorized
                        CameraPermissionBridge.wasRefused -> CameraAuthorization.denied
                        else -> CameraAuthorization.notDetermined
                    }
                },
                authorize = {
                    when {
                        !available() -> false
                        granted() -> true
                        else -> CameraPermissionBridge.request() ?: granted()
                    }
                },
                openSettings = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
            )
        }

        /** No camera, never allowed. */
        override val testValue: ServerCameraClient = ServerCameraClient()
    }
}

/**
 * Registers the system camera-permission prompt for the live [ServerCameraClient] while it's in
 * composition (the standard `rememberLauncherForActivityResult` pattern, behind the client). Place
 * it in any screen that may call [ServerCameraClient.authorize].
 */
@Composable
fun CameraPermissionHost() {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        CameraPermissionBridge.answer(allowed)
    }
    DisposableEffect(launcher) {
        val launch: () -> Unit = { launcher.launch(Manifest.permission.CAMERA) }
        CameraPermissionBridge.hosts.add(launch)
        onDispose {
            CameraPermissionBridge.hosts.remove(launch)
            if (CameraPermissionBridge.hosts.isEmpty()) CameraPermissionBridge.answer(null)
        }
    }
}

/** The live client's link to the launcher on screen. Main thread only. */
private object CameraPermissionBridge {
    val hosts = ArrayList<() -> Unit>()
    private var pending: CompletableDeferred<Boolean?>? = null

    /** A request this process made was refused; the next one would show nothing. */
    var wasRefused = false
        private set

    /** The person's answer, or null when no prompt could be shown. */
    suspend fun request(): Boolean? {
        pending?.let { return it.await() }
        val launch = hosts.lastOrNull() ?: return null
        val answer = CompletableDeferred<Boolean?>()
        pending = answer
        launch()
        return try {
            answer.await()
        } finally {
            if (pending === answer) pending = null
        }
    }

    fun answer(allowed: Boolean?) {
        if (allowed == false) wasRefused = true
        if (allowed == true) wasRefused = false
        pending?.complete(allowed)
        pending = null
    }
}
