package ai.opencode.feature.voice

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import android.content.pm.ApplicationInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * The voice lab's hooks; Android reads system properties, in debuggable builds only. Release builds
 * always answer null / false, so no lab hook can change a shipped app.
 *
 * `OPENCODE_LAB_VOICE_ROTATE_AFTER` maps to `debug.opencode.voice_rotate_after` (the
 * `OPENCODE_LAB_` prefix dropped, lowercased). Set one before launching the app:
 *
 * ```
 * adb shell setprop debug.opencode.voice_rotate_after 20
 * adb shell setprop debug.opencode.voice_mute_output 1
 * ```
 *
 * Clear it with `adb shell setprop debug.opencode.voice_rotate_after ""`. Each property is read
 * once per process (`getprop`) and cached, so restart the app after changing one.
 */
object VoiceLabEnvironment {
    private const val PREFIX = "OPENCODE_LAB_"
    private val cache = ConcurrentHashMap<String, String>()

    /** The variable's value, or null when unset, empty, or in a release build. */
    fun string(name: String): String? {
        if (!isDebuggable) return null
        val key = propertyKey(name)
        val value = cache.getOrPut(key) { readProperty(key) }
        return value.ifEmpty { null }
    }

    /** Whether the variable is "1". */
    fun flag(name: String): Boolean = string(name) == "1"

    /** `OPENCODE_LAB_VOICE_ROTATE_AFTER` → `debug.opencode.voice_rotate_after`. */
    fun propertyKey(name: String): String = "debug.opencode." + name.removePrefix(PREFIX).lowercase()

    private val isDebuggable: Boolean by lazy {
        try {
            (dependency(AppContext).applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (_: Exception) {
            false
        }
    }

    private fun readProperty(key: String): String = try {
        val process = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        output
    } catch (_: Exception) {
        ""
    }
}
