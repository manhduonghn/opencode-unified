package ai.opencode.core.tuning

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/** [isEnabled] is a debuggable build or one not installed by Google Play. */
object InternalBuild {
    val isDebug: Boolean by lazy {
        runCatching { dependency(AppContext).applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }.getOrDefault(false)
    }

    val isEnabled: Boolean by lazy {
        isDebug || runCatching {
            val context = dependency(AppContext)
            detect(isDebug = false, installingPackageName = context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName)
        }.getOrDefault(false)
    }

    /** Google Play's installer stands in for the App Store receipt. */
    fun detect(isDebug: Boolean, installingPackageName: String?): Boolean = isDebug || installingPackageName != PLAY_STORE

    private const val PLAY_STORE = "com.android.vending"
}

/**
 * Who gets tuning and the other developer tools. Debug builds always; any other build only while
 * Settings › Developer › Developer Mode is on.
 */
object TuningAccess {
    /** Debug lab runs set this (intent extra `tuning-as-release`) to check the gate. Ignored outside debug builds. */
    @Volatile var actsAsReleaseOverride: Boolean = false

    val actsAsRelease: Boolean get() = InternalBuild.isDebug && actsAsReleaseOverride

    val isAvailable: Boolean get() = (InternalBuild.isDebug && !actsAsRelease) || DeveloperMode.isEnabled
}

object DeveloperMode {
    val isEnabled: Boolean get() = runCatching { shared(SharedKeys.developerMode).value }.getOrDefault(false)
}

/** The Tuning switch, kept across launches. */
object TuningSharedKeys {
    val tuningEnabled: SharedKey<Boolean> = SharedKey.appStorage("tuning-enabled", false)
}

/**
 * Names what's on screen for the tuning panel, which then lists only this context's groups (unless
 * All is on). Put it once in a screen's root composable.
 */
@Composable
fun TuningContextEffect(context: TuningContext, isPlayground: Boolean = false) {
    LaunchedEffect(context, isPlayground) {
        if (!isPlayground) TuningStore.shared.setContext(context)
    }
}

