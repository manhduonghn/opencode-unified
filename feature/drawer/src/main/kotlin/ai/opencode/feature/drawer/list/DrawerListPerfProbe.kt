package ai.opencode.feature.drawer.list

import ai.opencode.core.log.AppLog
import android.os.Debug
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The drawer list's first render and memory. Marks `drawer_list_first_frame` on the first frame
 * that lays out four or more rows ([frame]), then `drawer_list_footprint` once a second for 20 s,
 * through [AppLog.perf]. A lab harness turns on [isEnabled] before the drawer is built; off,
 * nothing runs. Footprint is the process's PSS (`Debug.getPss()`, KB).
 */
object DrawerListPerfProbe {
    @Volatile
    var isEnabled: Boolean = false

    private var mountTime: Long? = null
    private var mountFootprint = 0L
    private var marked = false

    /** The list was made. */
    fun mountStarted() {
        if (!isEnabled || mountTime != null) return
        mountTime = SystemClock.elapsedRealtime()
        mountFootprint = Debug.getPss()
    }

    /** A frame laid out [visibleRows] rows; marks the first with four or more. */
    fun frame(visibleRows: Int, scope: CoroutineScope) {
        if (!isEnabled || marked) return
        val start = mountTime ?: return
        if (visibleRows < 4) return
        marked = true
        val footprint = Debug.getPss()
        val ms = SystemClock.elapsedRealtime() - start
        AppLog.perf.notice {
            "drawer_list_first_frame ms=$ms footprintKB=$footprint deltaKB=${footprint - mountFootprint} cells=$visibleRows"
        }
        scope.launch {
            for (second in 1..20) {
                delay(1_000)
                AppLog.perf.notice { "drawer_list_footprint footprintKB=${Debug.getPss()} second=$second" }
            }
        }
    }
}
