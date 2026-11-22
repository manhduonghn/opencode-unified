package ai.opencode.core.capture

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.tuning.Tunable
import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import java.lang.ref.WeakReference

/**
 * The flash and shutter when the app captures its own screen. [ScreenCaptureFeedback.capture]
 * captures first and flashes after, so the flash is never in the picture.
 */
class ScreenCaptureFeedbackClient(
    /** The app just captured its own screen in [Activity] (null when unknown): flash it and play the shutter. */
    val shutter: (Activity?) -> Unit = {},
) {
    companion object Key : DependencyKey<ScreenCaptureFeedbackClient> {
        override val liveValue: ScreenCaptureFeedbackClient by lazy {
            val live = LiveScreenCaptureFeedback()
            ScreenCaptureFeedbackClient { live.shutter(it) }
        }
        override val testValue: ScreenCaptureFeedbackClient get() = ScreenCaptureFeedbackClient()
    }
}

/**
 * The shutter outside the flash. Its sounds live in `:feature:voice`, so the app installs this at
 * the root; until then a capture only flashes.
 */
fun interface ScreenCaptureShutterSound {
    fun play()

    companion object Key : DependencyKey<ScreenCaptureShutterSound> {
        override val liveValue: ScreenCaptureShutterSound = ScreenCaptureShutterSound {}
        override val testValue: ScreenCaptureShutterSound = ScreenCaptureShutterSound {}
    }
}

object ScreenCaptureFeedback {
    /**
     * Runs [capture] in [scene], then, when it succeeded, flashes that activity and plays the
     * shutter.
     */
    inline fun <T> capture(scene: Activity?, capture: () -> Result<T>): Result<T> {
        val result = capture()
        if (result.isSuccess) dependency(ScreenCaptureFeedbackClient).shutter(scene)
        return result
    }

    /** The same for a capture that returns nothing when refused. */
    inline fun <T : Any> captureOptional(scene: Activity?, capture: () -> T?): T? {
        val image = capture()
        if (image != null) dependency(ScreenCaptureFeedbackClient).shutter(scene)
        return image
    }
}

/**
 * Tuned on the phone (Settings → Diagnostics → Developer Mode → Tuning). The flash decays on
 * `Motion.dim` (`Motion.reduced` under Reduce Motion).
 */
object ScreenFlashTunables {
    val opacity = Tunable("motion.screenshotFlash.opacity", "Screenshot flash peak", default = 0.85, range = 0.0..1.0, step = 0.05)
    val reducedOpacity = Tunable("motion.screenshotFlash.reducedOpacity", "Reduce Motion: dim peak", default = 0.18, range = 0.0..0.6, step = 0.02)
}

/** How the flash looks under the person's accessibility settings. */
data class ScreenFlashStyle(
    val tone: Tone,
    val peak: Double,
    /** Reduce Motion: the dim decays on `Motion.reduced`. */
    val reduceMotion: Boolean,
) {
    enum class Tone { white, dim }

    val duration: Double get() = MotionRole.Dim.curve(reduceMotion = reduceMotion).duration

    companion object {
        /**
         * A white flash; with Reduce Motion a softer, shorter dim; with Dim Flashing Lights none.
         * Android has no Dim Flashing Lights, so the live flash passes `false`.
         */
        fun resolve(reduceMotion: Boolean, dimFlashingLights: Boolean): ScreenFlashStyle? {
            if (dimFlashingLights) return null
            if (reduceMotion) {
                return ScreenFlashStyle(Tone.dim, ScreenFlashTunables.reducedOpacity.value, reduceMotion = true)
            }
            return ScreenFlashStyle(Tone.white, ScreenFlashTunables.opacity.value, reduceMotion = false)
        }
    }
}

/**
 * The live flash and shutter. Runs on the main thread; a capture finishing elsewhere posts there.
 */
private class LiveScreenCaptureFeedback {
    private val main = Handler(Looper.getMainLooper())
    private val flash = ScreenFlash()
    private val activities = ForegroundActivities()

    init {
        (dependency(AppContext).applicationContext as? Application)?.registerActivityLifecycleCallbacks(activities)
    }

    fun shutter(captured: Activity?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { shutter(captured) }
            return
        }
        val scene = foreground(captured) ?: activities.flashScene()
        val style = scene?.let { ScreenFlashStyle.resolve(reduceMotion = isReduceMotion(it), dimFlashingLights = false) }
        if (scene != null && style != null) flash.play(style, scene)
        dependency(ScreenCaptureShutterSound).play()
    }

    private fun foreground(activity: Activity?): Activity? =
        activity?.takeIf { activities.isResumed(it) && !it.isFinishing }

    private fun isReduceMotion(activity: Activity): Boolean =
        Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}

/** The app's resumed activities, for a flash whose capture didn't say where. */
private class ForegroundActivities : Application.ActivityLifecycleCallbacks {
    private val resumed = ArrayList<WeakReference<Activity>>()

    fun isResumed(activity: Activity): Boolean = resumed.any { it.get() === activity }

    /** When the capture didn't say: the activity with window focus, else the only resumed one. */
    fun flashScene(): Activity? {
        val scenes = resumed.mapNotNull { it.get() }.filter { !it.isFinishing }
        scenes.firstOrNull { it.hasWindowFocus() }?.let { return it }
        return scenes.singleOrNull()
    }

    override fun onActivityResumed(activity: Activity) {
        if (!isResumed(activity)) resumed += WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        resumed.removeAll { it.get() == null || it.get() === activity }
    }

    override fun onActivityDestroyed(activity: Activity) = onActivityPaused(activity)
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}

/**
 * A window over everything, including the IME, for one flash. Not touchable or focusable, hidden
 * from accessibility, and removed when the decay ends so it holds no activity alive.
 */
private class ScreenFlash {
    private var window: View? = null
    private var host: WeakReference<Activity>? = null
    private var generation = 0

    fun play(style: ScreenFlashStyle, scene: Activity) {
        val view = window(scene) ?: return
        generation += 1
        val current = generation
        view.animate().cancel()
        view.setBackgroundColor(if (style.tone == ScreenFlashStyle.Tone.white) Color.WHITE else Color.BLACK)
        view.alpha = style.peak.toFloat()
        // Ease out, or ease in-out under Reduce Motion.
        view.animate()
            .alpha(0f)
            .setDuration((style.duration * 1_000).toLong())
            .setInterpolator(if (style.reduceMotion) AccelerateDecelerateInterpolator() else DecelerateInterpolator())
            .withEndAction { if (generation == current) detach() }
            .start()
    }

    private fun window(scene: Activity): View? {
        window?.let { if (host?.get() === scene) return it }
        detach()
        val view = View(scene).apply {
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isClickable = false
            isFocusable = false
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "OpenCodeScreenFlash"
            windowAnimations = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val added = runCatching { scene.windowManager.addView(view, params) }.isSuccess
        if (!added) return null
        window = view
        host = WeakReference(scene)
        return view
    }

    private fun detach() {
        val view = window ?: return
        view.animate().cancel()
        val activity = host?.get()
        if (activity != null && view.isAttachedToWindow) runCatching { activity.windowManager.removeViewImmediate(view) }
        window = null
        host = null
    }
}
