package ai.opencode.feature.session

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.experiments.key
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeNoticePresence
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.voice.LiveSocketWarmer
import ai.opencode.feature.voice.LiveSocketWarmerClient
import ai.opencode.feature.voice.RaiseToEarClient
import ai.opencode.feature.voice.VoiceCallEnded
import ai.opencode.feature.voice.VoiceCallFailure
import ai.opencode.feature.voice.VoiceConnectingCaption
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceLabDemo
import ai.opencode.feature.voice.VoiceStoreOf
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The session screen's call: which store is the screen's call (`callStore`), its presentation
// (`callPresentation`/`voiceStore`), the failed and ended cards above the composer, and the tasks
// that start it once, listen for Raise to Talk and keep a GPT-Live socket warm. The call floats
// over the screen (Living Orb,:feature:voice's `FloatingCallOverlay`) and the composer stays a
// composer.

/**
 * The call on this screen, its own (`store.scope(\.voice, action: \.voice.presented)`) or the one
 * its root owns ([hostedCall], a pushed subagent's).
 */
@Composable
internal fun StoreOf<State, Action>.rememberCallStore(hostedCall: VoiceStoreOf?): VoiceStoreOf? =
    rememberOptionalScope<VoiceFeature.State, VoiceFeature.Action>({ it.voice }, { Action.voicePresented(it) }) ?: hostedCall

/**
 * The screen's call tasks.
 * - once per call, the call starts (`.startIfNeeded`): it keeps running while the drawer swaps sessions;
 * - Raise to Talk listens on the front screen (or the call's own screen) while the setting is on,
 *   Voice is available and the app is active; the reducer decides what a raise does;
 * - a GPT-Live socket kept open (no session started, so free) for a minute after a call could start
 *   here, so a tap skips the connection setup.
 */
@Composable
internal fun SessionCallTasks(store: StoreOf<State, Action>, callStore: VoiceStoreOf?, hasCall: Boolean, isCatchUpCard: Boolean) {
    val currentCall by rememberUpdatedState(callStore)
    LaunchedEffect(hasCall) { currentCall?.send(VoiceFeature.Action.StartIfNeeded) }

    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val raiseToTalk by remember { shared(SharedKeys.raiseToTalk).flow }.collectAsState(shared(SharedKeys.raiseToTalk).value)
    val isVoiceAvailable by store.observe { it.isVoiceAvailable }
    val ownsCall by store.observe { it.voice != null }
    val isCovered by store.observe { it.isCovered }
    val listensForRaise = raiseToTalk && isVoiceAvailable && active && !isCatchUpCard && (ownsCall || (!hasCall && !isCovered))
    LaunchedEffect(listensForRaise) {
        if (!listensForRaise) return@LaunchedEffect
        dependency(RaiseToEarClient).monitor(
            {
                // The reducer decides; its sends run before this returns.
                store.send(Action.RaisedToEar)
                store.state.voice != null
            },
            { near -> store.send(Action.AtEarChanged(near)) },
            // A live call moves to the receiver on the looser held-up posture.
            { store.state.voice != null },
        )
    }

    val routeWarms by store.observe { it.voiceRoute.warmsPhoneConnection }
    val currentHasCall by rememberUpdatedState(hasCall)
    val warmsPhoneConnection = routeWarms && isVoiceAvailable && !hasCall && active && !isCovered && !isCatchUpCard
    LaunchedEffect(warmsPhoneConnection) {
        if (!warmsPhoneConnection) return@LaunchedEffect
        // VoiceLabEnvironment answers null in release ones.
        val stored = store.state.openAIAPIKey.value ?: withContext(Dispatchers.IO) { VoiceLabDemo.labAPIKey }
        val key = stored?.trim()?.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        val warmer = dependency(LiveSocketWarmerClient)
        try {
            LiveSocketWarmer.warmWindow { warmer.warm(key) }
        } finally {
            if (!currentHasCall) warmer.cool()
        }
    }
}

/**
 * A call that couldn't connect ([VoiceCallFailure]: the provider's failure card, or Check API
 * Key/Open Settings and Try Again), whose connection was lost ([VoiceCallEnded]: Close and Rejoin),
 * or that is connecting through a server ([VoiceConnectingCaption]: “Connecting via My Mac…”,
 * fading in and out).
 */
@Composable
internal fun SessionCallCards(callStore: VoiceStoreOf?) {
    var shownCall by remember { mutableStateOf(callStore) }
    if (callStore != null) shownCall = callStore
    val call = shownCall ?: return
    val phase by call.observe { it.phase }
    val place by call.observe { it.callPlace }
    val statusText by call.observe { it.statusText }
    val card = phase.takeIf { callStore != null && (it is VoiceFeature.State.Phase.Failed || it == VoiceFeature.State.Phase.Dropped) }
    OpenCodeNoticePresence(card) { current ->
        when (current) {
            is VoiceFeature.State.Phase.Failed -> VoiceCallFailure(
                store = call,
                message = current.message,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
            )
            VoiceFeature.State.Phase.Dropped -> VoiceCallEnded(call, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp))
            else -> Unit
        }
    }
    val connecting = place?.takeIf { callStore != null && phase == VoiceFeature.State.Phase.Connecting }?.let { it to statusText }
    // The caption fades out as it last read, not as the next phase.
    var shown by remember { mutableStateOf(connecting) }
    if (connecting != null && connecting != shown) shown = connecting
    AnimatedVisibility(visible = connecting != null, enter = fadeIn(), exit = fadeOut()) {
        shown?.let { (place, text) -> VoiceConnectingCaption(place, text) }
    }
}
