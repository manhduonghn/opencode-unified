package ai.opencode.feature.voice

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.log.AppLog
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.strings.localized
import ai.opencode.core.store.cancellable
import ai.opencode.server.clienttools.ClientToolCall
import ai.opencode.server.clienttools.ClientToolResult
import ai.opencode.server.clienttools.ClientToolsClient
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import java.net.URI
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An agent asking, during a call, to open a link on the phone (`android_voice_open_url`, Open Links
 * from Calls). The person answers on a card above the composer: Open hands the link to the system,
 * which opens a verified app link in its app when it's installed (else the browser) and an app's
 * own scheme in its app; Not Now, a minute's silence or the call ending return a refusal. Nothing
 * opens without a tap.
 */
class VoiceOpenLinkFeature : Reducer<VoiceOpenLinkFeature.State, VoiceOpenLinkFeature.Action> {
    data class State(
        /** The request on the card. */
        val pending: ClientToolCall? = null,
    )

    sealed interface Action {
        data class Requested(val call: ClientToolCall) : Action
        data object OpenTapped : Action
        data object NotNowTapped : Action
        data object TimedOut : Action

        /** The call ended or failed with the card up. */
        data object CallEnded : Action

        /**
         * The socket the request came in on closed or was torn down: the card goes without a
         * reply, which the client already sent.
         */
        data class ConnectionClosed(val connection: UUID) : Action
    }

    object Refusal {
        const val declined = "The user chose not to open the link."
        const val timedOut = "The user didn’t answer within a minute, so the link wasn’t opened."
        const val callEnded = "The call ended before the user answered, so the link wasn’t opened."
        const val busy = "The user is already answering another request on the phone, so the link wasn’t opened."
        const val noCall = "Opening links works only during a voice call in the OpenCode Android app."
        const val background = "The phone isn’t showing OpenCode, so I couldn’t open it. Text the link instead."
        const val covered = "The user has another screen open in the OpenCode Android app, so they can’t see the request. Text the link instead."
        const val missing = "Pass the link to open as `url`."
        const val invalid = "That isn’t a link the phone can open. Pass a full https link or an app link."
        const val failed = "The user tapped Open, but nothing on the phone could open the link. Text it instead."

        fun scheme(scheme: String): String = "Only https links and app links can be opened, not $scheme: links."
    }

    /** Why a link can't be opened. */
    data class Failure(val message: String)

    sealed interface Link {
        data class Valid(val url: URI) : Link
        data class Invalid(val failure: Failure) : Link
    }

    enum class CancelID { timeout }

    override fun reduce(state: State, action: Action): Reduced<State, Action> {
        val clientTools = dependency(ClientToolsClient)
        val haptics = dependency(HapticsClient)
        val clock = dependency(Clock)
        return when (action) {
            is Action.Requested -> {
                val call = action.call
                if (state.pending != null) return Reduced(state, reply(clientTools, call, ClientToolResult.failure(Refusal.busy)))
                // `AppFeature` refuses a bad link before it gets here; never a card for one.
                val link = link(call)
                if (link is Link.Invalid) return Reduced(state, reply(clientTools, call, ClientToolResult.failure(link.failure.message)))
                AppLog.voice.info { "Open link request shown" }
                val timeout = Effect.run<Action> { send ->
                    clock.sleep(TIMEOUT)
                    send(Action.TimedOut)
                }.cancellable(CancelID.timeout, cancelInFlight = true)
                Reduced(state.copy(pending = call), timeout)
            }

            Action.OpenTapped -> {
                val call = state.pending ?: return Reduced(state)
                val link = link(call) as? Link.Valid ?: return Reduced(state)
                haptics.play(Haptic.tap)
                AppLog.voice.info { "Open link request accepted" }
                val openURL = dependency(VoiceOpenURLClient)
                // Not cancelled with the call: the person said yes, and the client's reply
                // outlives a torn-down caller.
                val open = Effect.run<Action> {
                    val opened = openURL.open(link.url.toString())
                    AppLog.voice.info { "Open link ${if (opened) "opened" else "failed"}" }
                    val answer = if (opened) {
                        ClientToolResult(listOf(ClientToolResult.Content.Text(openedNote(link.url, call.title))))
                    } else {
                        ClientToolResult.failure(Refusal.failed)
                    }
                    clientTools.reply(call, answer)
                }
                Reduced(state.copy(pending = null), Effect.merge(Effect.cancel(CancelID.timeout), open))
            }

            Action.NotNowTapped -> {
                val call = state.pending ?: return Reduced(state)
                haptics.play(Haptic.cancel)
                AppLog.voice.info { "Open link request declined" }
                Reduced(
                    state.copy(pending = null),
                    Effect.merge(Effect.cancel(CancelID.timeout), reply(clientTools, call, ClientToolResult.failure(Refusal.declined))),
                )
            }

            Action.TimedOut -> {
                val call = state.pending ?: return Reduced(state)
                AppLog.voice.info { "Open link request timed out" }
                Reduced(state.copy(pending = null), reply(clientTools, call, ClientToolResult.failure(Refusal.timedOut)))
            }

            Action.CallEnded -> endCall(state)

            is Action.ConnectionClosed -> {
                if (state.pending?.connection != action.connection) return Reduced(state)
                AppLog.voice.info { "Open link request dropped with its socket" }
                Reduced(state.copy(pending = null), Effect.cancel(CancelID.timeout))
            }
        }
    }

    /**
     * The call is over: a request still on the card is refused now, in the same pass, so the
     * reply leaves before the socket does.
     */
    fun endCall(state: State): Reduced<State, Action> {
        val call = state.pending ?: return Reduced(state)
        val clientTools = dependency(ClientToolsClient)
        return Reduced(
            state.copy(pending = null),
            Effect.merge(Effect.cancel(CancelID.timeout), reply(clientTools, call, ClientToolResult.failure(Refusal.callEnded))),
        )
    }

    private fun reply(clientTools: ClientToolsClient, call: ClientToolCall, result: ClientToolResult): Effect<Action> =
        Effect.run { clientTools.reply(call, result) }

    companion object {
        /** How long the card waits for an answer, as a screen request's. */
        val TIMEOUT: Duration = 60.seconds

        private const val MAX_LENGTH = 8_192
        private val SCHEME = Regex("^[a-z][a-z0-9+.\\-]*$")

        /**
         * Schemes refused outright: `http`, script and file links, ones that act without a screen
         * (calls, texts, mail, shortcuts, installs), and the app's own (pairing and session links
         * aren't an agent's to follow). Android adds `intent` (it can name any component),
         * `content` and `android-app`.
         */
        val refusedSchemes: Set<String> = setOf(
            "http", "javascript", "vbscript", "file", "data", "blob", "about",
            "tel", "telprompt", "sms", "mms", "imessage", "facetime", "facetime-audio", "facetime-group",
            "mailto", "message", "shortcuts", "workflow", "itms-services",
            "opencode", "opencode-ios",
            "intent", "content", "android-app",
        )

        /** The link a call asks for, or why it can't be opened. */
        fun link(call: ClientToolCall): Link {
            val raw = call.url ?: return Link.Invalid(Failure(Refusal.missing))
            if (raw.length > MAX_LENGTH) return Link.Invalid(Failure(Refusal.invalid))
            val url = try {
                URI(raw)
            } catch (_: Exception) {
                return Link.Invalid(Failure(Refusal.invalid))
            }
            val scheme = url.scheme?.lowercase()
            if (scheme == null || !SCHEME.matches(scheme)) return Link.Invalid(Failure(Refusal.invalid))
            if (scheme in refusedSchemes) return Link.Invalid(Failure(Refusal.scheme(scheme)))
            if (scheme == "https" && url.host.isNullOrEmpty()) return Link.Invalid(Failure(Refusal.invalid))
            return Link.Valid(url)
        }

        /** What the agent hears once it opened: where, never the whole link. */
        fun openedNote(url: URI, title: String?): String {
            val place = displayHost(url)
            if (title != null) return "Opened $title ($place) on the user's phone."
            return "Opened $place on the user's phone."
        }

        /** The card's second line: the host, or an app link's scheme. */
        fun displayHost(url: URI): String {
            val host = url.host
            if (!host.isNullOrEmpty()) return host
            return url.scheme ?: url.toString()
        }

        /**
         * The card's title: “Open Uber?”, or “Open Link?” untitled. An agent's own “Open …” or
         * trailing “?” isn't doubled.
         */
        fun cardTitle(title: String?): String {
            var name = title ?: return localized(R.string.voice_open_link_card_title_untitled)
            if (name.lowercase().startsWith("open ")) name = name.drop(5)
            while (name.endsWith("?")) name = name.dropLast(1)
            name = name.trim()
            return if (name.isEmpty()) localized(R.string.voice_open_link_card_title_untitled) else localized(R.string.voice_open_link_card_title, name)
        }
    }
}

/**
 * Hands it to the system (`ACTION_VIEW`), which opens a verified app link in its app, else the
 * browser. False when nothing on the phone opens it.
 */
class VoiceOpenURLClient(val open: suspend (String) -> Boolean) {
    companion object Key : DependencyKey<VoiceOpenURLClient> {
        override val liveValue: VoiceOpenURLClient by lazy {
            VoiceOpenURLClient { url ->
                withContext(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        dependency(AppContext).startActivity(intent)
                        true
                    } catch (_: ActivityNotFoundException) {
                        false
                    }
                }
            }
        }
        override val testValue = VoiceOpenURLClient { false }
    }
}

/**
 * A client tool's card (a screen request or a link) is up above the composer: nothing of the
 * call's stands over it.
 */
val VoiceFeature.State.holdsClientToolCard: Boolean
    get() = screenRequest.pending != null || openLink.pending != null
