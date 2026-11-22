package ai.opencode.feature.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Hi, baby" to his son got "Hey. Still going?", and "Hi, you, boy" a laugh and "Hey.". GPT-Live
 * takes its turns itself (no `create_response` or turn-detection setting exists), so its
 * instructions are the only lever: speech that isn't for the voice agent gets nothing.
 */
class VoiceLayLowTests {
    private val context = VoiceInstructions.Context(
        sessionTitle = "HOME",
        directoryName = "home",
        recentMessages = emptyList(),
        previousTail = emptyList(),
        previousEndedAt = null,
        managesSessions = true,
    )

    private val prompts: List<String>
        get() = listOf(
            VoiceInstructions.buildLive(context),
            VoiceInstructions.build(context, engine = VoiceEngine.realtime),
            VoiceInstructions.build(context, engine = VoiceEngine.grok),
            VoiceInstructions.build(context, route = VoiceRoute.serverGeminiLive),
        )

    @Test fun speechThatIsntForTheAgentGetsNoReply() {
        val section = VoiceInstructions.whatYouHearSection
        for (prompt in prompts) assertTrue(prompt.contains(section))
        assertTrue(section.contains("Speech aimed at someone else (a child, a pet, another person, a TV)"))
        assertTrue(section.contains("a greeting or pet name mid-call (\"Hi, baby\", \"Hey, buddy\", \"Hi, you, boy\")"))
        assertTrue(section.contains("a muttered aside, background talk, and speech that makes no sense get no reply at all: keep listening"))
        assertTrue(section.contains("No greeting back, no laugh, no \"Still going?\", no comment, no question, no offer, and never delegate it."))
        assertTrue(section.contains("When you can't tell whether speech is for you, say nothing"))
        assertTrue(section.contains("A greeting at the start of the call, or one that names you, is for you."))
        assertTrue(section.contains("Only when the user is clearly asking you for something and you missed part of it, say \"Sorry, I didn't catch that.\""))
        assertTrue(section.contains("Never invent a request for speech you didn't follow"))
    }

    /**
     * The rules that let it answer: side talk got "a word at most",
     * commentary and every greeting a reply, and any unclear speech a
     * "didn't catch that".
     */
    @Test fun noRuleStillInvitesAReplyToSideTalk() {
        for (prompt in prompts) {
            assertFalse(prompt.contains("a word at most"))
            assertFalse(prompt.contains("Incomplete fragments and commentary get a conversational reply"))
            assertFalse(prompt.contains("If what you have is a fragment or unclear"))
            assertFalse(prompt.contains("such as greetings, small talk"))
            assertFalse(prompt.contains("Answer greetings, small talk"))
            assertTrue(prompt.contains("If a request meant for you is a fragment or unclear"))
            assertTrue(prompt.contains("Speech that makes no sense, or may be meant for someone else, gets silence instead."))
        }
    }

    /**
     * GPT-Live's documented "Selected requests only" control ("Otherwise,
     * keep listening"), ranked with its turn policies ahead of delegation,
     * and no backchannels over the user.
     */
    @Test fun gptLiveKeepsListeningUnlessTalkedTo() {
        val live = VoiceInstructions.buildLive(context)
        val attention = live.indexOf("Attention policy: Respond when the user talks to you or asks you for something. Otherwise, keep listening and say nothing")
        assertTrue(attention >= 0)
        val delegation = live.indexOf("Delegation policy:")
        assertTrue(delegation >= 0)
        assertTrue(attention < delegation)
        assertTrue(live.contains("Backchannel policy: No backchannels. While the user is talking, stay silent"))
        // "Okay." and a chuckle over his words, and an update spoken over his dictation.
        assertTrue(live.contains("Turn-taking policy: The user often dictates long requests and messages, pausing to think."))
        assertTrue(live.contains("if the user starts talking while you give one, stop at once"))
        assertTrue(live.contains("- The speech isn't meant for you or makes no sense: say nothing (What You Hear)."))
        assertTrue(live.contains("such as greetings meant for you, small talk"))
    }

    /**
     * Realtime answers every turn its VAD ends, so a goodbye to a child
     * mustn't end the call either.
     */
    @Test fun realtimeAnswersOnlyRequestsMadeToIt() {
        val realtime = VoiceInstructions.build(context, engine = VoiceEngine.realtime)
        assertTrue(realtime.contains("Delegate only actual requests made to you. Commentary to you gets a conversational reply; speech not meant for you gets none (What You Hear)."))
        assertTrue(realtime.contains("Answer greetings meant for you, small talk"))
        assertTrue(realtime.contains("A goodbye to someone else (\"Bye, Keanu\") isn't one."))
    }
}
