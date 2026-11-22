package ai.opencode.feature.composer

import ai.opencode.core.store.TestStore
import ai.opencode.server.model.OpenCodeDelivery
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Only its `DictationFeature` store test lives here. */
class DictationLiveActivityTests {
    @Test fun dictationFeatureReducesHoldArmingLockingAndFinishExhaustively() = runTest {
        var stoppedPress: Int? = null
        val store = TestStore(
            DictationFeature.State(
                phase = DictationFeature.Phase.Preparing,
                press = 7,
                hold = DictationFeature.Hold(text = "hello"),
            ),
            DictationFeature,
            this,
        ) {
            this[DictationClient] = DictationClient(stop = { press -> stoppedPress = press })
        }

        store.send(DictationFeature.Action.Ready) {
            it.copy(phase = DictationFeature.Phase.Recording)
        }
        store.send(DictationFeature.Action.MicPressArmed(DictationFeature.Hold.Arm.Lock)) {
            it.copy(hold = it.hold?.copy(armed = DictationFeature.Hold.Arm.Lock))
        }
        store.send(DictationFeature.Action.MicPressLocked) {
            it.copy(hold = it.hold?.copy(isLocked = true, armed = null))
        }
        store.send(DictationFeature.Action.SendTapped) {
            it.copy(phase = DictationFeature.Phase.Finishing(send = OpenCodeDelivery.Steer))
        }
        store.finish()
        assertEquals(7, stoppedPress)
    }
}
