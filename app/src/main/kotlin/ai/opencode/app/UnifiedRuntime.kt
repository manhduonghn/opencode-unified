package ai.opencode.app

import ai.opencode.core.log.AppLog
import android.app.Activity
import android.content.Intent
import com.termux.app.OpenCodeBootstrap
import com.termux.app.OpenCodeForegroundService
import com.termux.app.TermuxInstaller

/**
 * Bridges the Compose shell to the local runtime inherited from OpenCode for Termux.
 *
 * The runtime owns bootstrap extraction, installation and process supervision; the shell keeps
 * ownership of pairing, sessions and the server contract. Nothing here opens a WebView.
 */
object UnifiedRuntime {
    fun initialize(activity: Activity) {
        val app = activity.applicationContext
        // The bootstrap installer may show its progress dialog, so it must start on the UI thread.
        activity.runOnUiThread {
            runCatching {
                TermuxInstaller.setupBootstrapIfNeeded(activity) {
                    OpenCodeBootstrap.restoreState()
                    OpenCodeBootstrap.setupIfNeeded(app) {
                        if (!OpenCodeBootstrap.isRuntimeComplete()) return@setupIfNeeded
                        val intent = Intent(app, OpenCodeForegroundService::class.java)
                            .setAction(OpenCodeForegroundService.ACTION_START)
                        runCatching { app.startForegroundService(intent) }
                            .onFailure { AppLog.app.notice { "Local runtime service was not started" } }
                    }
                }
            }.onFailure {
                AppLog.app.notice { "Local runtime setup could not be completed" }
            }
        }
    }
}
