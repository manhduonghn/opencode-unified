package ai.opencode.core.capture

import ai.opencode.core.dependencies.withDependencies
import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Every self-capture flashes and plays the shutter after it captures, and only when it captured
 * something. `shutterWavIsMono16BitPCM` pins `ShutterSystemSound`, voice's shutter (it scales
 * `VoiceShutterSound`), so it ports with it.
 */
class ScreenCaptureFeedbackTests {
    @get:Rule val isolation = TestIsolationRule()

    private class Refused : Exception()

    @Test
    fun shuttersAfterASuccessfulCapture() {
        val events = mutableListOf<String>()
        val result = withDependencies({
            this[ScreenCaptureFeedbackClient] = ScreenCaptureFeedbackClient { events += "shutter" }
        }) {
            ScreenCaptureFeedback.capture(null) {
                events += "capture"
                Result.success(1)
            }
        }
        assertEquals(1, result.getOrThrow())
        assertEquals("The flash is never in the picture", listOf("capture", "shutter"), events)
    }

    @Test
    fun refusedCapturesShowNothing() {
        var shutters = 0
        withDependencies({
            this[ScreenCaptureFeedbackClient] = ScreenCaptureFeedbackClient { shutters += 1 }
        }) {
            ScreenCaptureFeedback.capture<Int>(null) { Result.failure(Refused()) }
            ScreenCaptureFeedback.captureOptional<Int>(null) { null }
        }
        assertEquals(0, shutters)
    }

    @Test
    fun optionalCapturesShutterWhenTheyCapture() {
        var shutters = 0
        val image = withDependencies({
            this[ScreenCaptureFeedbackClient] = ScreenCaptureFeedbackClient { shutters += 1 }
        }) {
            ScreenCaptureFeedback.captureOptional(null) { 7 }
        }
        assertEquals(7, image)
        assertEquals(1, shutters)
    }

    @Test
    fun flashFollowsAccessibilitySettings() {
        assertNull(ScreenFlashStyle.resolve(reduceMotion = false, dimFlashingLights = true))
        assertNull(ScreenFlashStyle.resolve(reduceMotion = true, dimFlashingLights = true))
        val flash = ScreenFlashStyle.resolve(reduceMotion = false, dimFlashingLights = false)
        assertEquals(ScreenFlashStyle(ScreenFlashStyle.Tone.white, ScreenFlashTunables.opacity.value, reduceMotion = false), flash)
        val dim = ScreenFlashStyle.resolve(reduceMotion = true, dimFlashingLights = false)
        assertEquals(ScreenFlashStyle(ScreenFlashStyle.Tone.dim, ScreenFlashTunables.reducedOpacity.value, reduceMotion = true), dim)
        assertTrue("Softer and shorter", dim!!.peak < flash!!.peak && dim.duration < flash.duration)
    }
}
