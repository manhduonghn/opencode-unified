package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Every prompt counts from the delegation that sent it. GPT-Live held
 * “save this call… don't wait for it” back and handed it over with “what's
 * in Slack from Bob?”, so its late result went out without saying it
 * answered the earlier request; such a prompt counts from its own turn,
 * but only when that's unambiguous. A correction (“Actually, Alice's
 * instead”) is a fresh request, never an earlier one.
 */
class VoiceTurnStampTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val ios = OpenCodeSession(id = "ses_ios", serverTitle = "OPENCODE IOS", projectID = "ios", directory = "/Users/kit/opencode-ios", updatedAt = Instant.ofEpochSecond(300))
    private val home = OpenCodeSession(id = "ses_home", serverTitle = "HOME", projectID = "home", directory = "/Users/kit/code/home", updatedAt = Instant.ofEpochSecond(400))
    private val saveTurn = "Ask the OpenCode iOS session to save this call as another example of the missed reply bug. Don't wait for it."
    private val slackTurn = "Okay. What's in Slack from Bob?"
    private val saveTopic = "Save this call as another example of the missed reply bug."
    private val slackTopic = "Check Slack for messages from Bob."
    private val bobTurn = "Check Slack for Bob's launch update."
    private val aliceTurn = "Actually, Alice's instead."
    private val aliceTopic = "Check Slack for Alice's launch update."

    // MARK: The stamp

    @Test fun oneTurnOrOnePromptIsFresh() {
        val save: Pair<String, String?> = "msg_ios" to saveTopic
        val slack: Pair<String, String?> = "msg_home" to slackTopic
        assertTrue(ReplyWatch.heldBackTurns(listOf(save, slack), among = listOf(saveTurn), now = 3).isEmpty())
        assertTrue(ReplyWatch.heldBackTurns(listOf(save, slack), among = emptyList(), now = 3).isEmpty())
        assertTrue(ReplyWatch.heldBackTurns(listOf(save), among = listOf(saveTurn, slackTurn), now = 3).isEmpty())
        assertTrue(ReplyWatch.heldBackTurns(emptyList(), among = listOf(saveTurn, slackTurn), now = 3).isEmpty())
        assertTrue(ReplyWatch.heldBackTurns(listOf("msg_ios" to null, slack), among = listOf(saveTurn, slackTurn), now = 3).isEmpty())
    }

    /** A prompt for an earlier turn, beside another prompt for each later one. */
    @Test fun aHeldBackTurnCountsFromItsTurn() {
        val prompts: List<Pair<String, String?>> = listOf("msg_ios" to saveTopic, "msg_home" to slackTopic)
        assertEquals(mapOf("msg_ios" to 2), ReplyWatch.heldBackTurns(prompts, among = listOf(saveTurn, slackTurn), now = 3))
        // Order of the prompts doesn't matter.
        assertEquals(mapOf("msg_ios" to 2), ReplyWatch.heldBackTurns(prompts.reversed(), among = listOf(saveTurn, slackTurn), now = 3))
        val three = listOf(saveTurn, "And have Conclave run its tests.", slackTurn)
        val conclave: Pair<String, String?> = "msg_conclave" to "Run the Conclave tests."
        assertEquals(mapOf("msg_ios" to 3, "msg_conclave" to 4), ReplyWatch.heldBackTurns(prompts + conclave, among = three, now = 5))
        // Case, curly apostrophes and possessives don't matter.
        val curly: Pair<String, String?> = "msg_home" to "Read Bob’s latest SLACK messages."
        assertEquals(mapOf("msg_ios" to 2), ReplyWatch.heldBackTurns(listOf(prompts[0], curly), among = listOf(saveTurn, slackTurn), now = 3))
    }

    /**
     * The reviewer's case: a fresh correction shares most of its words with
     * the turn it corrects, and is never stamped as that turn.
     */
    @Test fun aCorrectionIsFresh() {
        val turns = listOf(bobTurn, aliceTurn)
        assertTrue(ReplyWatch.heldBackTurns(listOf("msg_home" to aliceTopic), among = turns, now = 3).isEmpty())
        // Even beside a prompt for the turn it corrected: the later turn isn't
        // another request.
        assertTrue(ReplyWatch.heldBackTurns(listOf("msg_home" to aliceTopic, "msg_bob" to bobTurn), among = turns, now = 3).isEmpty())
        // A correction that shares no words with what it corrects.
        assertTrue(ReplyWatch.heldBackTurns(listOf("msg_ios" to saveTopic, "msg_home" to slackTopic), among = listOf(saveTurn, "No, the other one."), now = 3).isEmpty())
    }

    /**
     * When in doubt it's fresh: one shared word, words shared with two turns,
     * or a later turn no other prompt answers.
     */
    @Test fun anAmbiguousPromptIsFresh() {
        val slack: Pair<String, String?> = "msg_home" to slackTopic
        assertTrue(ReplyWatch.heldBackTurns(listOf("msg_ios" to "Save it.", slack), among = listOf(saveTurn, slackTurn), now = 3).isEmpty())
        assertTrue(ReplyWatch.heldBackTurns(listOf("msg_ios" to "Save the Slack call from Bob.", slack), among = listOf(saveTurn, slackTurn), now = 3).isEmpty())
        assertTrue(ReplyWatch.heldBackTurns(listOf("msg_ios" to saveTopic, "msg_home" to "Rename the project."), among = listOf(saveTurn, slackTurn), now = 3).isEmpty())
        // Only common words in common.
        assertTrue(ReplyWatch.heldBackTurns(listOf("msg_ios" to "Tell the session about this.", slack), among = listOf("Ask the session about this", slackTurn), now = 3).isEmpty())
    }

    /**
     * GPT-Live is told to send “don't wait for it” at once rather than with
     * the next turn; the delegator, that more work isn't a pick.
     */
    @Test fun thePromptsSayDontWaitMeansDelegateNowAndMoreWorkIsntAPick() {
        val context = VoiceInstructions.Context(sessionTitle = "HOME", directoryName = "home", recentMessages = emptyList(), previousTail = emptyList(), previousEndedAt = null)
        val live = VoiceInstructions.build(context, engine = VoiceEngine.gptLive)
        assertTrue(live.contains("\"Don't wait for it\" means delegate now: never hold a request back to send with the next one."))
        assertTrue(VoiceDelegator.instructions.contains("Answer a question only with an option the user named; a request for more or other work isn't an answer: dismiss_question with a note in their words, then send."))
    }

    // MARK: The reply watch

    private fun announcement(effects: List<ReplyWatch.Effect>): List<ReplyWatch.Effect> =
        effects.filter { it is ReplyWatch.Effect.Announce }

    private fun prompt(id: String, session: OpenCodeSession, topic: String, saidTurn: Int?): ReplyWatch.Prompt =
        ReplyWatch.Prompt(VoiceDelegator.SentPrompt(sessionID = session.id, title = session.title, promptID = id, topic = topic), saidTurn = saidTurn)

    private fun notice(reply: String, session: OpenCodeSession, topic: String): String =
        ReplyWatch.Outcome.Replied(reply).notice(session.title, answering = listOf(topic), alreadyTold = null)

    /**
     * Two turns and one delegation carrying both: once the held-back prompt
     * is restamped, its result says it answers the earlier request and names
     * it; the other's doesn't.
     */
    @Test fun aHeldBackRequestsResultIsLedAsEarlier() {
        val watch = Watch()
        repeat(3) { watch.noteUserActed() }
        watch.step(
            ReplyWatch.Event.Sent(
                listOf(
                    prompt("msg_ios", ios, topic = saveTopic, saidTurn = 3),
                    prompt("msg_home", home, topic = slackTopic, saidTurn = 3),
                ),
            ),
        )
        assertEquals(listOf(3, 3), watch.watched.map { it.turn })
        assertTrue(watch.step(ReplyWatch.Event.HeldBack(mapOf("msg_ios" to 2))).isEmpty())
        assertEquals(listOf(2, 3), watch.watched.map { it.turn })

        val saved = "Saved this call as a second example."
        assertEquals(
            listOf(ReplyWatch.Effect.Announce(sessionID = ios.id, promptIDs = listOf("msg_ios"), text = VoiceHandoff.earlierRequest(notice(saved, ios, topic = saveTopic), topic = saveTopic))),
            announcement(watch.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_ios", reply = saved))),
        )
        val bob = "Bob can do Monday or Tuesday."
        assertEquals(
            listOf(ReplyWatch.Effect.Announce(sessionID = home.id, promptIDs = listOf("msg_home"), text = notice(bob, home, topic = slackTopic))),
            announcement(watch.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_home", reply = bob))),
        )
    }

    /**
     * A result held for the delegator while it runs is restamped too, before
     * its answer releases it.
     */
    @Test fun aHeldResultIsRestampedBeforeItsRelease() {
        val watch = Watch()
        repeat(3) { watch.noteUserActed() }
        watch.step(ReplyWatch.Event.DelegatorStarted(requestID = "d1"))
        watch.step(
            ReplyWatch.Event.Sent(
                listOf(
                    prompt("msg_ios", ios, topic = saveTopic, saidTurn = 3),
                    prompt("msg_home", home, topic = slackTopic, saidTurn = 3),
                ),
            ),
        )
        val saved = "Saved."
        assertTrue(announcement(watch.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_ios", reply = saved))).isEmpty())
        assertEquals(listOf(3), watch.deferredNotices.map { it.turn })
        watch.step(ReplyWatch.Event.HeldBack(mapOf("msg_ios" to 2, "msg_unknown" to 0)))
        assertEquals(listOf(2), watch.deferredNotices.map { it.turn })
        assertEquals(listOf(3), watch.watched.map { it.turn })
        assertEquals(
            listOf(ReplyWatch.Effect.Announce(sessionID = ios.id, promptIDs = listOf("msg_ios"), text = VoiceHandoff.earlierRequest(notice(saved, ios, topic = saveTopic), topic = saveTopic))),
            announcement(watch.step(ReplyWatch.Event.DelegatorFinished(requestID = "d1", spoken = "Sent both."))),
        )
    }

    /** A restamp only moves a prompt earlier, never later. */
    @Test fun aRestampNeverMovesAPromptLater() {
        val watch = Watch()
        watch.noteUserActed()
        watch.step(ReplyWatch.Event.Sent(listOf(prompt("msg_ios", ios, topic = saveTopic, saidTurn = 1))))
        watch.step(ReplyWatch.Event.HeldBack(mapOf("msg_ios" to 9)))
        assertEquals(listOf(1), watch.watched.map { it.turn })
    }

    /**
     * The correction: “Check Slack for Bob's launch update”, then “Actually,
     * Alice's instead”, one delegation, one prompt. Its result is fresh.
     */
    @Test fun aCorrectionsResultIsFresh() {
        val watch = Watch()
        repeat(3) { watch.noteUserActed() }
        watch.step(ReplyWatch.Event.Sent(listOf(prompt("msg_home", home, topic = aliceTopic, saidTurn = watch.value.userTurns))))
        val heldBack = ReplyWatch.heldBackTurns(listOf("msg_home" to aliceTopic), among = listOf(bobTurn, aliceTurn), now = watch.value.userTurns)
        if (heldBack.isNotEmpty()) watch.step(ReplyWatch.Event.HeldBack(heldBack))
        val alice = "Alice posted the launch notes."
        assertEquals(
            listOf(ReplyWatch.Effect.Announce(sessionID = home.id, promptIDs = listOf("msg_home"), text = notice(alice, home, topic = aliceTopic))),
            announcement(watch.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_home", reply = alice))),
        )
    }

    /**
     * Without a stamp a prompt counts from when it was sent, as before; a
     * stamp never runs ahead of the call's turns.
     */
    @Test fun anUnstampedPromptCountsFromItsSend() {
        val watch = Watch()
        watch.noteUserActed()
        watch.step(
            ReplyWatch.Event.Sent(
                listOf(
                    prompt("msg_ios", ios, topic = saveTopic, saidTurn = null),
                    prompt("msg_home", home, topic = slackTopic, saidTurn = 9),
                ),
            ),
        )
        assertEquals(listOf(1, 1), watch.watched.map { it.turn })
        val saved = "Saved."
        assertEquals(
            listOf(ReplyWatch.Effect.Announce(sessionID = ios.id, promptIDs = listOf("msg_ios"), text = notice(saved, ios, topic = saveTopic))),
            announcement(watch.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_ios", reply = saved))),
        )
    }

    /**
     * The user spoke after the delegation but before its prompt went out:
     * the result answers an earlier request.
     */
    @Test fun wordsBetweenTheDelegationAndItsSendCount() {
        val watch = Watch()
        watch.noteUserActed()
        watch.noteUserActed()
        val stamp = watch.value.userTurns
        watch.noteUserActed()
        watch.step(ReplyWatch.Event.Sent(listOf(prompt("msg_ios", ios, topic = saveTopic, saidTurn = stamp))))
        val saved = "Saved."
        assertEquals(
            listOf(ReplyWatch.Effect.Announce(sessionID = ios.id, promptIDs = listOf("msg_ios"), text = VoiceHandoff.earlierRequest(notice(saved, ios, topic = saveTopic), topic = saveTopic))),
            announcement(watch.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_ios", reply = saved))),
        )
    }

    // MARK: The call

    private data class Send(val session: OpenCodeSession, val text: String, val reply: String)

    /**
     * One delegation of [turns] through the whole feature: the delegator
     * sends [sends] (session, text, reply), and each reply lands once its
     * answer is out. What was announced.
     */
    private fun call(turns: List<String>, sends: List<Send>): List<String> = runBlocking {
        val announced = Locked(listOf<String>())
        val answered = Locked(false)
        val shared = SpeechTestShared()
        val voice = shared.run {
            shared(SharedKeys.openAIAPIKey).set("sk-test")
            VoiceFeature.State(server, home)
                .withPhase(VoiceFeature.State.Phase.Live)
                .copy(usesDelegator = true)
        }
        val sessions = mapOf(ios.id to ios, home.id to home)
        val calls = sends.mapIndexed { index, send ->
            VoiceDelegator.ToolCall(callID = "t$index", name = "send_message", arguments = """{"session_id":"${send.session.id}","text":"${send.text}"}""")
        }
        val replies = sends.associate { "msg_${it.session.id}" to it.reply }
        val effects = detachedScope(coroutineContext)
        val store = TestStore(voice, VoiceFeature(), effects) {
            this[SharedStore] = shared.store
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            this[VoiceDelegatorClient] = VoiceDelegatorClient(isEnabled = true) { _, _, previous ->
                if (previous != null) {
                    VoiceDelegator.Response(id = "r2", calls = emptyList(), text = "Sent.")
                } else {
                    VoiceDelegator.Response(id = "r1", calls = calls, text = "")
                }
            }
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                getSession = { _, id -> sessions[id] ?: throw OpenCodeClientError(message = "No session") },
                sendDeliveredPrompt = { _, sessionID, _, _ -> "msg_$sessionID" },
                message = { _, _, _ -> throw OpenCodeClientError(message = "Offline") },
                transcript = { _, _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
            )
            this[VoiceWorkClient] = VoiceWorkClient(
                submit = { _, _ -> VoiceWorkAdmission(promptID = "msg_test") },
                awaitReply = { _, _, promptID ->
                    while (!answered.value) delay(5)
                    replies[promptID]
                },
                checkSessionActive = { _, _ -> false },
                activeSessionIDs = { emptySet() },
                recentMessages = { _, _, _ -> emptyList() },
            )
            this[VoiceCallClient] = speechVoiceCall(
                completeFunction = { _, _ -> answered.set(true) },
                announce = { _, _, text -> announced.update { it + text } },
                cancelPendingAnnouncements = {},
            )
        }
        store.exhaustive = false
        for ((index, turn) in turns.withIndex()) {
            store.send(VoiceFeature.Action.CallEvent(VoiceCallEvent.UserTranscript(itemID = "u$index", text = turn, final = true)))
        }
        store.send(VoiceFeature.Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = JsonObject(emptyMap()))))
        assertEquals(turns.size + 1, store.state.replyWatch.userTurns)
        store.receive(::isDelegatorFinished, timeout = 5.seconds)
        store.eventuallyReducing(timeout = 5.seconds) { announced.value.size == sends.size }
        store.skipReceivedActions()
        store.send(VoiceFeature.Action.CallDropped)
        store.skipReceivedActions()
        store.exhaustive = true
        store.finish(timeout = 3.seconds)
        effects.skipInFlightEffects()
        announced.value
    }

    /**
     * The report's call: two turns, then one delegation the delegator splits
     * into two prompts. Only the held-back turn's result is led as earlier.
     */
    @Test fun theCallLeadsOnlyTheHeldBackResult() {
        val announced = call(
            turns = listOf(saveTurn, slackTurn),
            sends = listOf(
                Send(ios, saveTopic, "Saved this call as a second example."),
                Send(home, slackTopic, "Bob can do Monday or Tuesday."),
            ),
        )
        val saved = announced.firstOrNull { it.contains("second example") } ?: throw AssertionError("No saved result in $announced")
        val bob = announced.firstOrNull { it.contains("Bob can do") } ?: throw AssertionError("No Slack result in $announced")
        assertTrue(saved, saved.startsWith(VoiceHandoff.earlierRequestLead + VoiceHandoff.earlierTopicLead + VoiceWorkClient.excerpt(saveTopic, limit = 90)))
        assertFalse(bob, bob.contains(VoiceHandoff.earlierRequestLead))
    }

    /** The correction through the whole call: Alice's update is fresh. */
    @Test fun theCallKeepsACorrectionFresh() {
        val announced = call(turns = listOf(bobTurn, aliceTurn), sends = listOf(Send(home, aliceTopic, "Alice posted the launch notes.")))
        val alice = announced.firstOrNull { it.contains("Alice posted") } ?: throw AssertionError("No result in $announced")
        assertFalse(alice, alice.contains(VoiceHandoff.earlierRequestLead))
    }
}
