package ai.opencode.core.scene

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * The window a store runs in. In use while one of the app's activities is resumed with window
 * focus; in front before any activity is seen. [sceneID] is its task's id.
 */
class SceneWindowClient(
    private val front: () -> Boolean = { true },
    private val scene: () -> String? = { null },
) {
    /** Whether this window is the one in use. */
    val isFront: Boolean get() = front()

    /** This window's id, or null when the store isn't a window's own. */
    val sceneID: String? get() = scene()

    companion object Key : DependencyKey<SceneWindowClient> {
        override val liveValue: SceneWindowClient by lazy {
            ActiveScene.install(dependency(AppContext))
            SceneWindowClient(front = { ActiveScene.isFront })
        }

        /** Inert: always in front and no scene. */
        override val testValue: SceneWindowClient get() = SceneWindowClient()
    }
}

/** The app's windows. Window focus is read when asked, so it is never stale. */
object ActiveScene {
    private val lock = Any()
    private val alive = ArrayList<WeakReference<Activity>>()
    private val resumed = ArrayList<WeakReference<Activity>>()
    private var hasSeenActivity = false
    private var installed = false

    /** Starts tracking the app's activities. Safe to call more than once. */
    fun install(context: Context) {
        val application = context.applicationContext as? Application ?: return
        synchronized(lock) {
            if (installed) return
            installed = true
        }
        application.registerActivityLifecycleCallbacks(Tracker)
    }

    /** A window's id: its task's. */
    fun sceneID(activity: Activity): String = activity.taskId.toString()

    /** The id of the window a view's [context] belongs to; null when it isn't an activity's. */
    fun sceneID(context: Context): String? {
        var current: Context? = context
        while (current != null) {
            if (current is Activity) return sceneID(current)
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }

    /** Whether one of the app's activities is resumed and has window focus (in front before any is seen). */
    val isFront: Boolean
        get() = synchronized(lock) {
            if (!hasSeenActivity) return true
            resumed.removeAll { it.get() == null }
            resumed.any { it.get()?.hasWindowFocus() == true }
        }

    /**
     * The window to open from: the one with [sceneID] while it's still alive, else the one in use.
     * Null when no window is known.
     */
    fun activity(sceneID: String?): Activity? = synchronized(lock) {
        alive.removeAll { it.get() == null }
        resumed.removeAll { it.get() == null }
        if (sceneID != null) {
            for (reference in alive) {
                val activity = reference.get() ?: continue
                if (!activity.isFinishing && sceneID(activity) == sceneID) return activity
            }
        }
        for (reference in resumed) {
            val activity = reference.get() ?: continue
            if (!activity.isFinishing && activity.hasWindowFocus()) return activity
        }
        null
    }

    /**
     * Opens [url] from the window with [sceneID] (or the one in use), so the app it opens appears
     * beside it. Returns whether something took it.
     */
    suspend fun open(url: String, sceneID: String?): Boolean = withContext(Dispatchers.Main) {
        val origin: Context = activity(sceneID) ?: dependency(AppContext)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            origin.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private object Tracker : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = synchronized(lock) {
            if (alive.none { it.get() === activity }) alive += WeakReference(activity)
        }

        override fun onActivityResumed(activity: Activity) = synchronized(lock) {
            hasSeenActivity = true
            if (alive.none { it.get() === activity }) alive += WeakReference(activity)
            if (resumed.none { it.get() === activity }) resumed += WeakReference(activity)
        }

        override fun onActivityPaused(activity: Activity) = synchronized(lock) {
            hasSeenActivity = true
            resumed.removeAll { it.get() == null || it.get() === activity }
            Unit
        }

        override fun onActivityDestroyed(activity: Activity) = synchronized(lock) {
            alive.removeAll { it.get() == null || it.get() === activity }
            resumed.removeAll { it.get() == null || it.get() === activity }
            Unit
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}
