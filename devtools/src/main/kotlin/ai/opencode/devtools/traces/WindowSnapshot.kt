package ai.opencode.devtools.traces

import ai.opencode.core.capture.ScreenCaptureFeedback
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.diagnostics.CredentialSurface
import ai.opencode.core.diagnostics.ProblemReportBuilder
import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The window as a JPEG within the report's budget, via `PixelCopy`. Never in the background, over a
 * [CredentialSurface], or from a `FLAG_SECURE` window.
 */
object WindowSnapshot {
    /** Resumed activities, last resumed last (several in multi-window). Main thread only. */
    private val resumed = ArrayList<WeakReference<Activity>>()

    /**
     * The window in use: with several windows, the one with window focus, the one last touched;
     * else the last resumed activity.
     */
    val keyWindow: Activity?
        get() {
            val activities = resumed.mapNotNull { it.get() }
            return activities.lastOrNull { it.hasWindowFocus() } ?: activities.lastOrNull()
        }

    /** Follows the resumed activities. Called once from `DevTools.install`. */
    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed.removeAll { it.get() == null || it.get() === activity }
                resumed += WeakReference(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                resumed.removeAll { it.get() == null || it.get() === activity }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun isAllowed(isActive: Boolean, credentialSurfaces: Int, isSecureWindow: Boolean): Boolean =
        isActive && credentialSurfaces == 0 && !isSecureWindow

    suspend fun jpeg(maxBytes: Int = ProblemReportBuilder.SCREENSHOT_LIMIT): ByteArray? {
        val bitmap = withContext(Dispatchers.Main) { capture() } ?: return null
        return withContext(Dispatchers.Default) {
            try {
                for (quality in listOf(70, 50, 30)) {
                    val out = ByteArrayOutputStream()
                    if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out) && out.size() <= maxBytes) return@withContext out.toByteArray()
                }
                null
            } finally {
                bitmap.recycle()
            }
        }
    }

    private suspend fun capture(): Bitmap? {
        val activity = keyWindow ?: return null
        val window = activity.window ?: return null
        val view = window.decorView
        val secure = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        if (view.width <= 0 || view.height <= 0 || !isAllowed(!activity.isFinishing, CredentialSurface.visible, secure)) return null
        // Renders at no more than 2× points.
        val density = activity.resources.displayMetrics.density
        val scale = min(1f, 2f / density)
        val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val copied = suspendCancellableCoroutine { continuation ->
            PixelCopy.request(window, full, { result -> continuation.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
        }
        if (!copied) {
            full.recycle()
            return null
        }
        if (scale >= 1f) return full
        val scaled = Bitmap.createScaledBitmap(full, (view.width * scale).roundToInt().coerceAtLeast(1), (view.height * scale).roundToInt().coerceAtLeast(1), true)
        full.recycle()
        return scaled
    }
}

/**
 * The app window at the moment of a trace, as a JPEG. A screenshot taken flashes the window and
 * plays the shutter after it (`ScreenCaptureFeedback`).
 */
class ProblemReportCapture(val screenshot: suspend () -> ByteArray? = { null }) {
    companion object Key : DependencyKey<ProblemReportCapture> {
        override val liveValue: ProblemReportCapture = ProblemReportCapture {
            ScreenCaptureFeedback.captureOptional(withContext(Dispatchers.Main) { WindowSnapshot.keyWindow }) { WindowSnapshot.jpeg() }
        }
        override val testValue: ProblemReportCapture = ProblemReportCapture()
    }
}
