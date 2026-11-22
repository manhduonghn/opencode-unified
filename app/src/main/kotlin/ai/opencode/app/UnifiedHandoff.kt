package ai.opencode.app

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.sharing.SharedStore
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.server.remember
import android.content.Intent
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
 * uses. That yields the session token, which [remember] persists as the current server *before*
 * `MainActivity` creates its store — cold start therefore restores an already-connected Home.
 */
internal object UnifiedHandoff {
    fun openChat(
        activity: ComponentActivity,
        result: UnifiedSetup.Result,
        onError: (String) -> Unit,
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
                // the setup activity goes away.
                dependency(SharedStore).flush()
                withContext(Dispatchers.Main) {
                    if (activity.isFinishing || activity.isDestroyed) return@withContext
                    activity.startActivity(
                        Intent(activity, MainActivity::class.java).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                        ),
                    )
                    activity.finish()
                }
            } catch (error: Throwable) {
                val message = error.message?.takeIf { it.isNotBlank() }
                    ?: "The chat client could not connect to the local service."
                withContext(Dispatchers.Main) { onError(message) }
            }
        }
    }
}
