package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import java.lang.ref.WeakReference

/** :app calls attach(activity) on resume and detach(activity) on pause. No wake lock.
 * The registry clears only flags it added; an existing activity hold is preserved.
 * Multi-window activities each receive the current hold state, never a stale Activity. */
class VoiceScreenActivityRegistry {
    private val handler = Handler(Looper.getMainLooper())
    private val activities = mutableListOf<WeakReference<Activity>>()
    private val changed = mutableSetOf<WeakReference<Activity>>()
    private var holds = 0
    private fun main(body: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) body() else handler.post(body) }
    fun attach(activity: Activity) = main {
        if (activities.none { it.get() === activity }) activities.add(WeakReference(activity))
        lastResumed = WeakReference(activity)
        apply()
    }
    fun detach(activity: Activity) = main {
        changed.filter { it.get() === activity }.forEach { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); changed.remove(it) }
        activities.removeAll { it.get() == null || it.get() === activity }
    }
    private var lastResumed: WeakReference<Activity>? = null

    /** The resumed activities, for a voice screenshot. Main thread. */
    val resumedActivities: List<Activity> get() = activities.mapNotNull { it.get() }

    /** The activity resumed last. */
    val lastTouched: Activity? get() = lastResumed?.get()
    fun acquire() = main { holds++; apply() }
    fun release() = main { holds = (holds - 1).coerceAtLeast(0); apply() }
    private fun apply() {
        activities.removeAll { it.get() == null }
        for (ref in activities) {
            val window = ref.get()?.window ?: continue
            if (holds > 0 && window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); changed.add(ref)
            } else if (holds == 0 && changed.remove(ref)) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        changed.removeAll { it.get() == null }
    }
    /** :app creates one registry and passes `this[VoiceScreenActivityRegistry] = registry`
     * to DependencyValues.installRoot before resolving ScreenAwakeClient. Its Activity
     * lifecycle callbacks call registry.attach(activity) on resume and detach on pause. */
    companion object Key : DependencyKey<VoiceScreenActivityRegistry> {
        override val liveValue by lazy { VoiceScreenActivityRegistry() }
    }
}
class ScreenAwakeClient(val set: (Boolean) -> Unit) {
    /** Uses :app's installed VoiceScreenActivityRegistry; no Activity is retained here.
     * Install the registry before this lazy live client is first resolved. */
    companion object Key : DependencyKey<ScreenAwakeClient> {
        override val liveValue by lazy {
            val registry = dependency(VoiceScreenActivityRegistry)
            var held = false; val lock = Any()
            ScreenAwakeClient { awake -> synchronized(lock) {
                if (awake != held) { held = awake; if (awake) registry.acquire() else registry.release() }
            } }
        }
        override val testValue = ScreenAwakeClient {}
    }
}
