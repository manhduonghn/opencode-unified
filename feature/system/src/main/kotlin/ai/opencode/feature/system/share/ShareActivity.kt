package ai.opencode.feature.system.share

import ai.opencode.feature.system.LeftToRight
import ai.opencode.feature.system.R
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.ui.OpenCodeAppTheme
import ai.opencode.feature.system.intents.SystemActionActivity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The share target's entry point. Hosts [ShareView], sends from here ([ShareSender]), and hands the
 * rest to the app (ShareHandoff.kt). The [ShareModel] lives in a ViewModel so a rotation or theme
 * change keeps what was loaded and a send in flight.
 */
class ShareActivity : ComponentActivity() {
    private val holder: ShareHolder by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        applyOverrideConfiguration(LeftToRight.configuration())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val model = holder.model ?: createModel()

        setContent {
            val ime = WindowInsets.ime
            val density = LocalDensity.current
            val keyboard = LocalSoftwareKeyboardController.current
            LaunchedEffect(ime, density, keyboard) {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    holder.finishRequested.first { it }
                    // Translucent activities share IME geometry with the window behind them.
                    // Finish after the actual hide, not its request or animation target.
                    if (hasWindowFocus() && ime.getBottom(density) > 0) {
                        keyboard?.hide()
                        // A keyboard that doesn't honor hide must not trap the caller here.
                        withTimeoutOrNull(1000.milliseconds) { snapshotFlow { ime.getBottom(density) }.first { it == 0 } }
                    }
                    finish()
                }
            }
            OpenCodeAppTheme {
                val state by model.state.collectAsStateWithLifecycle()
                // Back (and the back gesture) cancel, as a swipe down does.
                BackHandler { cancel() }
                ShareView(
                    state = state,
                    actions = ShareActions(
                        onMessageChange = model::setMessage,
                        onChoose = model::choose,
                        onCancel = ::cancel,
                        onSend = ::send,
                        onSendLater = { if (!holder.completed && model.sendLater()) holder.finishShare() },
                        onSaveToDownloads = ::saveToDownloads,
                        onContinue = ::continueInApp,
                        onDone = holder::finishShare,
                        onDismissSaveError = model::dismissSaveError,
                    ),
                )
            }
        }
    }

    private fun createModel(): ShareModel {
        // A sharing shortcut names its session (`ShareSuggestion`).
        val conversation = ShareSuggestion.conversation(intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID))
        val model = ShareModel(
            targets = ShareTargets.load(),
            credentials = ShareCredentialStore.live.load(),
            store = ShareHandoffStore.live,
            conversation = conversation,
            haptics = dependency(HapticsClient),
        )
        holder.model = model
        val providers = SharedItemLoader.providers(intent)
        val context = applicationContext
        holder.loading = holder.viewModelScope.launch { model.load(context, providers) }
        return model
    }

    /** From Send until it closes, the sheet is the send's to end. */
    private fun cancel() {
        val model = holder.model ?: return
        if (model.current.isBusy || holder.completed) return
        model.discardFiles()
        setResult(RESULT_CANCELED)
        holder.finishShare()
    }

    /** Waits for the server to take it, shows the checkmark, then goes. */
    private fun send() {
        val model = holder.model ?: return
        if (holder.completed || !model.current.canSend) return
        val context = applicationContext
        holder.viewModelScope.launch {
            val state = model.current
            val images = state.images.size
            val files = state.files.size
            val text = state.composedText
            val sent = model.send() ?: return@launch
            model.discardFiles()
            // The suggestion and the notification go, side by side and off the main thread,
            // while the checkmark lands; the sheet closes once both have, or 720 ms after the
            // close delay at most. They carry on either way.
            val body = ShareComposition.notificationBody(text, images, files)
            val followUp = followUps.launch {
                val donated = async { ShareSuggestion.donate(context, sent.serverKey, sent.sessionID) }
                val notified = async { ShareNotifier.sent(context, sent.title, body, sent.serverKey, sent.sessionID) }
                donated.await()
                notified.await()
            }
            delay(ShareComposition.SendMotion.closeDelay)
            withTimeoutOrNull(720.milliseconds) { followUp.join() }
            holder.finishShare()
        }
    }

    /**
     * Copies the shared files, images and text into the runtime home's Downloads, matching the
     * fork's share receiver without adding a second exported target. The sheet stays open so the
     * same share can still be sent afterwards.
     */
    private fun saveToDownloads() {
        val model = holder.model ?: return
        if (holder.completed || !model.current.canSaveToDownloads) return
        val context = applicationContext
        holder.viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { ShareDownloads.save(model.current) }
            val message = if (saved > 0) R.string.oc_share_saved_downloads
            else R.string.oc_share_save_downloads_failed
            withContext(Dispatchers.Main) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Saves the share as the session's draft, then opens the app on it. The notification (and
     * "Saved. Open OpenCode to continue") only stand in if launching fails.
     */
    private fun continueInApp() {
        val model = holder.model ?: return
        if (holder.completed || !model.saveToContinue()) return
        val title = model.current.destination?.title ?: "OpenCode"
        val opened = packageManager.getLaunchIntentForPackage(packageName) != null &&
            runCatching { SystemActionActivity.openApp(this) }.isSuccess
        if (opened) {
            holder.finishShare()
            return
        }
        val context = applicationContext
        holder.viewModelScope.launch {
            if (withContext(Dispatchers.IO) { ShareNotifier.continueInApp(context, title) }) {
                holder.finishShare()
            } else {
                model.continueManually()
            }
        }
    }

    private companion object {
        /** Work that outlives the sheet (the suggestion and notification after a send). */
        val followUps = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/**
 * Keeps the share sheet's model across configuration changes. When the sheet goes for good
 * (any way, Cancel and system dismissal included) loading stops and its own copies of shared
 * files go with it; a saved share keeps its own.
 */
class ShareHolder(application: Application) : AndroidViewModel(application) {
    var model: ShareModel? = null
    var loading: Job? = null

    /** The request completes or cancels once. */
    var completed = false
    val finishRequested = MutableStateFlow(false)

    fun finishShare() {
        if (completed) return
        completed = true
        finishRequested.value = true
    }

    override fun onCleared() {
        loading?.cancel()
        model?.discardFiles()
    }
}
