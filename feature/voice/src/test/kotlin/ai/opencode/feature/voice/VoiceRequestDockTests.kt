package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** The voice half. */
class VoiceRequestDockTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_home", serverTitle = "HOME", projectID = "p", directory = "/Users/kit/home", updatedAt = VoiceFeatureTestSupport.distantPast)

    @Test fun theDelegatorsAnswerRereadsTheRequests() = runBlocking<Unit> {
        val shared = SpeechTestShared()
        val voice = shared.run {
            shared(SharedKeys.openAIAPIKey).set("sk-test")
            VoiceFeature.State(server, session).withPhase(VoiceFeature.State.Phase.Live).copy(usesDelegator = true)
                .recording(VoiceTranscriptTail.Role.user, "Yes, mark them done", id = "u1")
        }
        val answered = Locked(listOf<String>())
        val effects = detachedScope(coroutineContext)
        val store = TestStore(voice, VoiceFeature(), effects) {
            this[SharedStore] = shared.store
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            this[VoiceDelegatorClient] = VoiceDelegatorClient(isEnabled = true) { _, input, _ ->
                val answeredYet = input.any { it is VoiceDelegator.InputItem.ToolOutput }
                if (answeredYet) {
                    VoiceDelegator.Response(id = "r2", calls = emptyList(), text = "Marked them done.")
                } else {
                    VoiceDelegator.Response(
                        id = "r1",
                        calls = listOf(VoiceDelegator.ToolCall(callID = "t1", name = "answer_question", arguments = """{"form_id":"frm_cleanup","answers":{"q0":"done"}}""")),
                        text = "",
                    )
                }
            }
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                replyForm = { _, sessionID, formID, _ -> answered.update { it + "$sessionID:$formID" } },
            )
            this[VoiceWorkClient] = VoiceWorkClient.testValue
            this[VoiceCallClient] = speechVoiceCall()
        }
        store.exhaustive = false

        store.send(VoiceFeature.Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = JsonObject(emptyMap()))))
        store.receive({ it is VoiceFeature.Action.DelegatorSettledRequest }, timeout = 5.seconds)
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.RequestSettled(sessionID = "ses_home")))
        store.receive(::isDelegatorFinished, timeout = 5.seconds)
        assertEquals(listOf("ses_home:frm_cleanup"), answered.value)
        store.skipReceivedActions()
        store.skipInFlightEffects()
        effects.skipInFlightEffects()
    }
}
