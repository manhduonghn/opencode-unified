package ai.opencode.feature.settings.onboarding

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class WelcomeFeatureTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun storyTimeWaitsThenLoops() {
        val story = WelcomeStory()
        assertEquals(-1.0, storyTime(-1.0, story.loop), 0.0)
        assertEquals(0.5, storyTime(story.loop + 0.5, story.loop), 1e-9)
        // The loop starts from what it faded to: an empty session, no lights.
        val start = story.frame(0.0)
        assertTrue(start.prompt == 0.0 && start.deskPrompt == 0.0 && start.deskWorking == 0.0 && start.dictation == 0.0)
        assertTrue(start.outbound == null && start.inbound == null && start.outbound2 == null)
        val faded = story.frame(story.loop - 0.001)
        assertTrue(faded.transcript < 0.01 && faded.deskNamed < 0.01 && !faded.named)
        assertTrue(abs(faded.laptopDim - start.laptopDim) < 0.01)
    }

    @Test
    fun storyDictatesThenShowsTheScreenshotThenShipsIt() {
        val story = WelcomeStory()
        assertTrue(story.loop in 12.0..16.0)
        // The words arrive while the mic is open, and the bar leaves as it sends.
        val dictating = story.frame(story.listen + story.firstWord + 1)
        assertTrue(dictating.dictation > 0.9 && dictating.heard > 3 && dictating.heard < WelcomeStory.PROMPT_WORDS.size)
        assertTrue(dictating.voice > 0 && dictating.utterance == 0)
        assertTrue(story.frame(story.send + 1).dictation < 0.05)
        // The browser opens, the button grows, and the screenshot's flash fires.
        assertEquals(0.0, story.frame(story.edit - 0.05).buttonGrow, 0.0)
        assertTrue(story.frame(story.shot - 0.05).buttonGrow > 0.95)
        assertTrue(story.frame(story.shot + 0.05).flash > 0.9)
        // The screenshot flies to the phone and shows on its tile when it lands.
        val flying = story.frame(story.back + story.travel / 2)
        assertTrue(flying.inbound != null && flying.screenshot == 0.0)
        assertEquals(1.0, story.frame(story.land + 0.2).screenshot, 0.0)
        // Reduce Motion's still is the proof on both screens, with no lights.
        val still = story.frame(story.still, reduced = true)
        assertTrue(still.screenshot == 1.0 && still.reply > 0.99 && still.deskReply > 0.99)
        assertTrue(still.inbound == null && still.flash == 0.0 && still.laptopDim == 0.0)
        // “Ship it” is the second utterance, and it reaches the laptop.
        assertEquals(1, story.frame(story.again + 0.5).utterance)
        assertTrue(story.frame(story.end - 0.01).deskShip > 0.99)
    }

    @Test
    fun metaSiteScreenshotIsDarkAndCarrierProgressIsMonotonic() {
        assertEquals("opencode", WelcomeStory.PROJECT)
        assertEquals("opencode.png", WelcomeScreenshot.NAME)
        assertTrue(WelcomeScreenshot.pixelWidth > 0 && WelcomeScreenshot.pixelHeight > 0)
        var previous = 0.0
        for (step in 0..40) {
            val tau = 0.75 * step / 40
            val value = WelcomeCarriers.along(tau, travel = 0.75)
            assertTrue(value >= previous)
            previous = value
        }
        assertTrue(abs(previous - 1) < 1e-9)
        // Reload bar cools smoothly instead of cutting from 1 to 0.
        val story = WelcomeStory()
        val cooling = story.frame(story.grown + 0.14).reloading
        assertTrue(cooling > 0.1 && cooling < 0.9)
    }

    @Test
    fun storyVoiceNeverTouchesTheLiveDictationMeter() {
        val voice = WelcomeVoice()
        val other = WelcomeVoice()
        assertNotSame(other.levels, voice.levels)
        val story = WelcomeStory()
        var t = 0.0
        while (t <= story.loop) {
            voice.play(story.frame(t).voice)
            t += 0.25
        }
        voice.play(0.8)
        assertEquals(0.8f, voice.levels.level, 1e-6f)
        assertEquals(0f, other.levels.level, 0f)
        assertNotNull(voice.levels.history.lastOrNull())
        assertFalse(other.levels.history.any { it != 0f })
        assertNull(story.frame(0.0).inbound)
    }
}
