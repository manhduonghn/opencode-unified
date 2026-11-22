package ai.opencode.app

import ai.opencode.core.log.AppLog
import android.app.Activity
import android.content.Context
import android.content.Intent
import com.termux.app.DownloadsCrashHandler
import com.termux.app.OpenCodeBootstrap
import com.termux.app.OpenCodeForegroundService
import com.termux.app.TermuxInstaller
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxBootstrap
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.crash.TermuxCrashUtils
import com.termux.shared.termux.file.TermuxFileUtils
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties
import com.termux.shared.termux.shell.TermuxShellManager
import com.termux.shared.termux.shell.am.TermuxAmSocketServer
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment

/**
 * Bridges the Compose shell to the local runtime inherited from OpenCode for Termux.
 *
 * The runtime owns bootstrap extraction, installation and process supervision; the shell keeps
 * ownership of pairing, sessions and the server contract. Nothing here opens a WebView.
 */
object UnifiedRuntime {
    /**
     * Process-wide plumbing inherited from the fork's `TermuxApplication.onCreate()`.
     *
     * The fork's theme/dynamic-color hooks are deliberately excluded: appearance belongs to the
     * native Compose shell. Only crash handling, logging, shell/socket environment and directory
     * setup are installed here.
     */
    fun install(context: Context) {
        // Install first so the Termux crash handler chains to it: a launch crash leaves a readable
        // report in Downloads even when the app never reaches its own UI.
        DownloadsCrashHandler.install(context)
        runCatching {
            TermuxCrashUtils.setDefaultCrashHandler(context)

            Logger.setDefaultLogTag(TermuxConstants.TERMUX_APP_NAME)
            TermuxAppSharedPreferences.build(context)?.let { preferences ->
                preferences.setLogLevel(null, preferences.getLogLevel())
            }

            // Matches the bootstrap archive version downloaded by :runtime.
            TermuxBootstrap.setTermuxPackageManagerAndVariant("apt-android-7")
            TermuxShellManager.init(context)

            // `TermuxAmSocketServer` reads properties, so they must be loaded before setup.
            TermuxAppSharedProperties.init(context)
            val filesAccessible = TermuxFileUtils.isTermuxFilesDirectoryAccessible(
                context,
                true,
                true,
            ) == null
            if (filesAccessible && TermuxFileUtils.isAppsTermuxAppDirectoryAccessible(true, true) == null) {
                TermuxAmSocketServer.setupTermuxAmSocketServer(context)
            }

            // Environment constants are exported to shells, so initialize them last.
            TermuxShellEnvironment.init(context)
            if (filesAccessible) TermuxShellEnvironment.writeEnvironmentToFile(context)
        }.onFailure {
            AppLog.app.notice { "Termux runtime initialization could not be completed" }
        }
    }

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
