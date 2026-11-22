package ai.opencode.feature.system.notifications

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Shows Android's notification permission dialog for [SessionNotifications.requestPermission],
 * reports the answer, and goes.
 */
class NotificationPermissionActivity : Activity() {
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST) return
        answer(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
        finish()
    }

    override fun onDestroy() {
        // Dismissed without an answer (the activity finished some other way).
        if (isFinishing && !isChangingConfigurations) answer(false)
        super.onDestroy()
    }

    private fun answer(granted: Boolean) {
        if (answered) return
        answered = true
        SessionNotifications.shared.permissionAnswered(granted)
    }

    private companion object {
        const val REQUEST = 1
    }
}
