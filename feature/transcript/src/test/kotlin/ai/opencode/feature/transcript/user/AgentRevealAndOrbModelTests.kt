package ai.opencode.feature.transcript.user

import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.server.model.OpenCodePromptSource
import ai.opencode.server.model.OpenCodeTranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The inner glow half (`AgentMessageReveal`, `TranscriptAgentMessageReveal`,
 * `TranscriptUserMessage.isFromAgent`).
 */
class AgentRevealAndOrbModelTests {
    // MARK: Inner glow

    @Test fun onlyAgentsMessagesReveal() {
        fun message(text: String, source: OpenCodePromptSource? = null) =
            TranscriptUserMessage(id = "msg", text = text, images = emptyList(), files = emptyList(), pending = null, source = source)
        // The header line, then the JSON body.
        val voice = "Voice delegation. The user is on a voice call with a voice agent, which hands their requests to you.\n" +
            """{"instruction":"check the build","transcript_delta":["user: check the build"]}"""
        assertTrue(message(voice).isFromAgent)
        assertTrue(message("Rerun the tests", source = OpenCodePromptSource.Session("ses_other")).isFromAgent)
        assertTrue(message("Audit the spacing", source = OpenCodePromptSource.ParentAgent).isFromAgent)
        assertFalse(message("Typed or dictated on this phone").isFromAgent)

        val entry = TranscriptEntry.UserGroup(
            groupID = "msg_forwarded",
            items = listOf(OpenCodeTranscriptItem.UserText(id = "msg_forwarded", text = "Rerun the tests", source = OpenCodePromptSource.Session("ses_other"))),
            startsTurn = true,
        )
        assertEquals(true, TranscriptUserMessage.from(entry)?.isFromAgent)
    }

    @Test fun revealRisesDecaysAndEndsOnThePlainBubble() {
        val peak = AgentMessageReveal.frame(AgentMessageReveal.rise)
        assertTrue(peak.light > 0.99)
        assertEquals(0.0, AgentMessageReveal.frame(0.0).body, 0.0)
        assertTrue(AgentMessageReveal.frame(0.4).light < 0.5)
        assertTrue(abs(AgentMessageReveal.duration - 1.39) < 0.02)
        assertEquals(AgentMessageReveal.Frame.rest, AgentMessageReveal.frame(AgentMessageReveal.duration))
        // The light eases to exactly nothing before the reveal ends.
        assertTrue(AgentMessageReveal.frame(AgentMessageReveal.duration - 0.001).light < 0.001)
    }

    @Test fun reduceMotionRevealOnlyFades() {
        var t = 0.0
        while (t < AgentMessageReveal.duration) {
            val frame = AgentMessageReveal.frame(t, reduceMotion = true)
            assertEquals(0.0, frame.light, 0.0)
            assertEquals(AgentMessageReveal.frame(t).body, frame.body, 0.0)
            t += 0.05
        }
    }

    @Test fun arrivalsAreRecordedOnce() {
        val reveal = TranscriptAgentMessageReveal()
        assertTrue(reveal.arrive("msg", 10.0))
        assertFalse(reveal.arrive("msg", 20.0))
        assertEquals(10.0, reveal.arrival("msg")!!, 0.0)
        assertNull(reveal.arrival("other"))
    }

    // analyticalInnerGlowShaderMatchesExponentialFalloffAndCorners: dropped. It samples the Metal
    // `agentMessageRevealGlow` shader through a CPU reference; Android's port is an AGSL
    // `RuntimeShader`, which only a device's GPU can evaluate (not a JVM unit test).
}
