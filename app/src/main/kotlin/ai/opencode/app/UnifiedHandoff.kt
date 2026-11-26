package ai.opencode.app

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.sharing.SharedStore
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.remember
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.termux.app.UnifiedSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connects the native chat client to the daemon the fork just started, without ever showing the
 * native pairing screen.
 *
 * The fork's one-time link points at `127.0.0.1`. The client's pasted-code path deliberately
 * rejects loopback-only links (they are useless for a remote phone), so this bridge validates the
 * link with [OpenCodePairingLink.decode] and redeems it through the same client the pairing screen
 * uses. That yields the session token, which [remember] persists as the current server before
 * `MainActivity` connects its chat, so the restored window is already connected.
 *
 * It deliberately does not launch an activity. `MainActivity` stays alive underneath the setup
 * screen (or, for a prepared runtime, underneath the connecting chat) and resumes as soon as
 * [onConnected] reports that the token is stored. Starting a new task here is what used to make a
 * home-icon tap re-run bootstrap.
 */
internal object UnifiedHandoff {
    /** Set once the token is durable, so further windows in this process skip setup. */
    @Volatile
    var isConnected: Boolean = false
        private set

    /** The server from the last successful handoff, for the chat to connect to at once. */
    @Volatile
    var lastPairedServer: OpenCodeServer? = null
        private set

    /** Forget the in-process handoff so the next connection asks the runtime to start again. */
    fun reset() {
        isConnected = false
        lastPairedServer = null
    }

    fun connect(
        activity: ComponentActivity,
        result: UnifiedSetup.Result,
        onError: (String) -> Unit,
        onConnected: () -> Unit,
    ) {
        val link = OpenCodePairingLink.decode(result.pairUrl)
        if (link == null) {
            onError("The local service returned a pairing link the chat client cannot read.")
            return
        }

        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val server = dependency(OpenCodeClient).redeemPairing(link)
                server.remember()
                // Writes are asynchronous; make sure the token survives a process restart before
                // the setup screen goes away.
                dependency(SharedStore).flush()
                withContext(Dispatchers.Main) {
                    if (activity.isFinishing || activity.isDestroyed) return@withContext
                    lastPairedServer = server
                    isConnected = true
                    onConnected()
                }
            } catch (error: Throwable) {
                val message = error.message?.takeIf { it.isNotBlank() }
                    ?: "The chat client could not connect to the local service."
                withContext(Dispatchers.Main) { onError(message) }
            }
        }
    }
}
