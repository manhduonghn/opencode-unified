package ai.opencode.feature.session

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.StoreOf
import ai.opencode.feature.voice.VoiceCallAudioFeature
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceLabDemo
import ai.opencode.feature.voice.VoiceLabTrigger
import ai.opencode.feature.voice.VoiceOpenLinkFeature
import ai.opencode.feature.voice.VoiceScreenRequestFeature
import ai.opencode.server.clienttools.ClientTool
import ai.opencode.server.clienttools.ClientToolCall
import ai.opencode.server.clienttools.JsonRpcID
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * While the voice lab runs ([VoiceLabDemo.isEnabled], debug builds only), the visible session acts
 * on the lab's triggers so a test can start a call, open its transcript, or fire Raise to Talk
 * while something covers the call button or bar. Triggers are broadcasts named
 * [VoiceLabTrigger.notification] (`adb shell am broadcast -a
 * OpenCodeLab.ai.opencode.lab.voice-start`). They're broadcasts named
 * [VoiceLabTrigger.notification] (`adb shell am broadcast -a
 * OpenCodeLab.ai.opencode.lab.voice-start`).
 */
@Composable
internal fun SessionVoiceLabTriggers(store: StoreOf<SessionFeature.State, SessionFeature.Action>, isEnabled: Boolean) {
    val active = isEnabled && VoiceLabDemo.isEnabled
    DisposableEffect(store, active) {
        if (!active) return@DisposableEffect onDispose {}
        val context = dependency(AppContext).applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    VoiceLabTrigger.startCall.notification ->
                        if (store.state.voice == null) store.send(SessionFeature.Action.VoiceTapped)
                    VoiceLabTrigger.raiseToEar.notification -> store.send(SessionFeature.Action.RaisedToEar)
                    VoiceLabTrigger.endCall.notification ->
                        store.send(SessionFeature.Action.Voice(PresentationAction.Presented(VoiceFeature.Action.EndPressed)))
                    VoiceLabTrigger.playAudio.notification -> {
                        val call = labCall(1, ClientTool.voicePlayAudio, url = "https://example.com/audio/night-drive.mp3", title = "Night Drive")
                        store.send(voice(VoiceFeature.Action.CallAudio(VoiceCallAudioFeature.Action.PlayRequested(call))))
                    }
                    VoiceLabTrigger.requestScreen.notification -> {
                        val call = labCall(2, ClientTool.voiceRequestScreen, reason = "To check the layout.")
                        store.send(voice(VoiceFeature.Action.ScreenRequest(VoiceScreenRequestFeature.Action.Requested(call))))
                    }
                    VoiceLabTrigger.openLink.notification -> {
                        val call = labCall(3, ClientTool.voiceOpenURL, url = "https://m.uber.com/go/home", title = "Uber")
                        store.send(voice(VoiceFeature.Action.OpenLink(VoiceOpenLinkFeature.Action.Requested(call))))
                    }
                }
            }
        }
        val filter = IntentFilter().apply { VoiceLabTrigger.entries.forEach { addAction(it.notification) } }
        val registered = runCatching { context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) }.isSuccess
        onDispose { if (registered) runCatching { context.unregisterReceiver(receiver) } }
    }
}

private fun voice(action: VoiceFeature.Action): SessionFeature.Action =
    SessionFeature.Action.Voice(PresentationAction.Presented(action))

private fun labCall(id: Long, tool: ClientTool, reason: String? = null, url: String? = null, title: String? = null): ClientToolCall =
    ClientToolCall(
        connection = ClientToolCall.labConnection,
        id = JsonRpcID.Number(id),
        tool = tool,
        sessionID = null,
        reason = reason,
        url = url,
        title = title,
    )
