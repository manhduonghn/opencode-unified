package ai.opencode.feature.composer.plusmenu

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** The activity hosting [this] context, unwrapping wrappers (theme, dialog). */
internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current != null) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

/**
 * Requests [permissions] from [activity] and returns once the system answered (on resume, or after
 * [IMMEDIATE_ANSWER_MS] when it answers without a screen). Uses the framework call because
 * `androidx.activity`'s result registry isn't on this module's classpath.
 */
internal suspend fun requestPermissionsAndAwait(activity: Activity, lifecycle: Lifecycle, permissions: Array<String>) {
    withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            var paused = false
            lateinit var observer: LifecycleEventObserver
            fun finish() {
                lifecycle.removeObserver(observer)
                handler.removeCallbacksAndMessages(null)
                if (continuation.isActive) continuation.resume(Unit)
            }
            observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> paused = true
                    Lifecycle.Event.ON_RESUME -> if (paused) finish()
                    Lifecycle.Event.ON_DESTROY -> finish()
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            continuation.invokeOnCancellation {
                handler.post {
                    lifecycle.removeObserver(observer)
                    handler.removeCallbacksAndMessages(null)
                }
            }
            activity.requestPermissions(permissions, PERMISSION_REQUEST_CODE)
            handler.postDelayed({ if (!paused) finish() }, IMMEDIATE_ANSWER_MS)
        }
    }
}

private const val PERMISSION_REQUEST_CODE = 0x0C4D
private const val IMMEDIATE_ANSWER_MS = 700L
