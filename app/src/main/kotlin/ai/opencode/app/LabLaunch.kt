package ai.opencode.app

import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.experimental
import ai.opencode.core.sharing.shared
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.ModelVisibility
import ai.opencode.server.remember
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug lab launch hooks, read from the launch intent's extras (`--es lab-server-url …`). Debug
 * builds only, applied before anything reads the store.
 */
internal object LabLaunch {
    fun apply(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        if (intent.getBooleanExtra("lab-fresh-install", false)) {
            shared(ServerSharedKeys.savedServer).set(null)
            shared(ServerSharedKeys.savedServers).set(emptyList())
            AppLog.app.info { "lab fresh install: saved servers forgotten" }
        }
        val labServerURL = intent.getStringExtra("lab-server-url")
        val isLab = labServerURL != null
        if (labServerURL != null) OpenCodeServer(url = labServerURL, username = "opencode", password = "").remember()
        if (isLab && intent.getBooleanExtra("lab-reset-model-preferences", false)) {
            shared(SharedKeys.favoriteModelIDs).set(emptySet())
            shared(SharedKeys.recentModelIDs).set(emptyList())
            shared(ModelVisibility.modelVisibilityByServer).set(emptyMap())
        }
        if (isLab) intent.getStringExtra("lab-pasteboard-text")?.let(::pasteboardText)?.let { text ->
            val pasteboard = dependency(PasteboardClient)
            CoroutineScope(Dispatchers.Main).launch { pasteboard.setString(text) }
        }
        seedVoiceKeys(intent)
        setExperiments(intent.getStringExtra("lab-experiments-on"), true)
        setExperiments(intent.getStringExtra("lab-experiments-off"), false)
    }

    private fun setExperiments(names: String?, on: Boolean) {
        if (names == null) return
        for (name in names.split(",")) {
            val feature = ExperimentalFeature.fromRawValue(name) ?: continue
            val stored = shared(experimental(feature))
            if (stored.value != on) stored.set(on)
        }
    }

    private fun seedVoiceKeys(intent: Intent) {
        listOf("lab-openai-key" to SharedKeys.openAIAPIKey, "lab-xai-key" to SharedKeys.xaiAPIKey).forEach { (extra, key) ->
            if (!intent.hasExtra(extra)) return@forEach
            val next = intent.getStringExtra(extra)?.takeUnless { it.isEmpty() || it == "NONE" }
            val stored = shared(key)
            if (stored.value != next) stored.set(next)
        }
    }

    private fun pasteboardText(kind: String): String? = when (kind) {
        "log" -> (0 until 40).joinToString("\n") { index ->
            if (index == 0) "Fatal error: Index out of range in UploadQueue.drain()"
            else "$index  UploadLab  0x${(0x100f00000L + index * 0x2c).toString(16)}  UploadQueue.drain(batch:) + ${index * 12}"
        }
        // 5 MB on one line, as a minified bundle or a base64 blob pastes.
        "big" -> "Fatal error: minified bundle " + "0123456789abcdef".repeat(5 * 1024 * 1024 / 16)
        "lines" -> (0 until 25_000).joinToString("\n") { "$it  UploadQueue.drain(batch:) retrying upload" }
        "paragraph" -> "When I pick three photos and send while the agent is still working, the first one uploads but the others wait at zero percent until I leave the app and come back. "
            .repeat(5).trim()
        else -> null
    }
}
