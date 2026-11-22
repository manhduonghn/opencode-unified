package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeatureTestSupport.Gate
import ai.opencode.feature.drawer.HomeFeatureTestSupport.LockIsolated
import ai.opencode.feature.drawer.HomeFeatureTestSupport.append
import ai.opencode.feature.drawer.VoiceHomeTestSupport.arguments
import ai.opencode.feature.drawer.VoiceHomeTestSupport.call
import ai.opencode.feature.drawer.VoiceHomeTestSupport.copy
import ai.opencode.feature.drawer.VoiceHomeTestSupport.delegator
import ai.opencode.feature.drawer.VoiceHomeTestSupport.eventually
import ai.opencode.feature.drawer.VoiceHomeTestSupport.live
import ai.opencode.feature.drawer.VoiceHomeTestSupport.openingScreens
import ai.opencode.feature.drawer.VoiceHomeTestSupport.receiveVoice
import ai.opencode.feature.drawer.VoiceHomeTestSupport.recording
import ai.opencode.feature.drawer.VoiceHomeTestSupport.response
import ai.opencode.feature.drawer.VoiceHomeTestSupport.voiceCall
import ai.opencode.feature.drawer.VoiceHomeTestSupport.voiceEvent
import ai.opencode.feature.drawer.VoiceHomeTestSupport.voiceHomeTest
import ai.opencode.feature.drawer.VoiceHomeTestSupport.voiceWork
import ai.opencode.feature.drawer.VoiceHomeTestSupport.withCall
import ai.opencode.feature.voice.VoiceCallEvent
import ai.opencode.feature.voice.VoiceDelegator
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceHandoff
import ai.opencode.feature.voice.VoiceRoute
import ai.opencode.feature.voice.VoiceTranscriptTail
import ai.opencode.feature.voice.VoiceWorkAdmission
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class VoiceFeatureTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val distantPast: Instant = HomeFeatureTestSupport.distantPast
    private val session = OpenCodeSession(id = "ses_voice", serverTitle = "Voice lab", projectID = "p", directory = "/Users/kit/project", updatedAt = distantPast)

    /** Drains until [condition] holds (bounded). */
    private suspend fun TestStore<HomeFeature.State, HomeFeature.Action>.drain(condition: () -> Boolean) {
        repeat(400) {
            skipReceivedActions()
            if (condition()) return
            delay(5)
        }
        skipReceivedActions()
    }

    /**
     * GPT-Live hands the request to the delegator, which moves the screen,
     * sends a Voice message into the session it opened, and answers; the
     * session's later reply is spoken too. The call follows the screen.
     */
    @Test fun delegatorMovesTheScreenSendsAVoiceMessageAndReportsTheReply() = voiceHomeTest { h ->
        val other = OpenCodeSession(id = "ses_math", serverTitle = "Math bug", projectID = "p", directory = "/Users/kit/math", updatedAt = distantPast)
        h.setShared(SharedKeys.openAIAPIKey, "sk-test")
        val home = h.state {
            var home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session, other), activeSessionIDs = emptySet()))
            home = home.copy(transcriptCache = home.transcriptCache + (session.id to emptyList()) + (other.id to emptyList()))
            home = home.openingDetail(session, cachedItems = emptyList())
            val voice = VoiceFeature.State(server, session).live().copy(usesDelegator = true)
                .recording(VoiceTranscriptTail.Role.user, "Open the math bug session and fix it", id = "u1")
            home.withCall(voice)
        }

        val requests = LockIsolated(listOf<List<VoiceDelegator.InputItem>>())
        val previousIDs = LockIsolated(listOf<String?>())
        val prompts = LockIsolated(listOf<Pair<String, String>>())
        val deliveries = LockIsolated(listOf<OpenCodeDelivery?>())
        val backgrounded = LockIsolated(listOf<String>())
        val waited = LockIsolated(listOf<Pair<String, String>>())
        val keys = LockIsolated(listOf<String>())
        val completed = LockIsolated(listOf<String>())
        val announced = LockIsolated(listOf<String>())
        val store = h.store(home) {
            this[Clock] = ImmediateClock()
            this[ai.opencode.feature.voice.VoiceDelegatorClient] = delegator { key, input, previous ->
                keys.append(key)
                val round = synchronized(requests) {
                    requests.append(input)
                    previousIDs.append(previous)
                    requests.value.size
                }
                when (round) {
                    1 -> response("resp_1", listOf(call("t1", "open_session", """{"session_id":"ses_math"}""")))
                    2 -> response("resp_2", listOf(call("t2", "send_message", """{"text":"Fix add so it adds."}""")))
                    else -> response("resp_3", text = "Opened the math bug session and started the fix.")
                }
            }
            this[OpenCodeClient] = OpenCodeClient.testValue.openingScreens { id -> if (id == other.id) other else session }.copy(
                sendDeliveredPrompt = { _, sessionID, text, delivery ->
                    deliveries.append(delivery)
                    prompts.append(sessionID to text)
                    "msg_fix"
                },
                moveToBackground = { _, id -> backgrounded.append(id) },
                message = { _, _, _ -> throw OpenCodeClientError(message = "Offline") },
            )
            voiceWork {
                copy(awaitReply = { _, sessionID, promptID ->
                    waited.append(sessionID to promptID)
                    "Fixed add: it now returns a + b."
                })
            }
            voiceCall {
                copy(
                    completeFunction = { _, output -> completed.append(output) },
                    announce = { _, _, text -> announced.append(text) },
                )
            }
        }
        store.exhaustive = false

        store.send(voiceEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = arguments("Opening the math bug session."))))
        store.receiveVoice(VoiceFeature.Action.DelegatorFinished::class)
        store.drain { announced.value.isNotEmpty() }

        assertTrue(keys.value.all { it == "sk-test" })
        assertNull(previousIDs.value.firstOrNull())
        assertEquals(listOf(OpenCodeDelivery.Steer), deliveries.value)
        assertTrue(backgrounded.value.all { it == other.id })
        assertTrue(waited.value.all { it == (other.id to "msg_fix") })
        // The delegator heard where the user was and what they said.
        val request = (requests.value.firstOrNull()?.firstOrNull() as? VoiceDelegator.InputItem.User)?.text
            ?: throw AssertionError("No request")
        assertTrue(request.contains("\"Voice lab\""))
        assertTrue(request.contains("Open the math bug session and fix it"))
        // The screen and the call moved to the session it opened.
        assertEquals(other.id, store.state.sessionDetail?.session?.id)
        assertEquals(other.id, store.state.sessionDetail?.voice?.target?.sessionID)
        assertEquals(VoiceFeature.State.Phase.Live, store.state.sessionDetail?.voice?.phase)
        // Its message reached that session as a Voice message.
        assertEquals(listOf(other.id), prompts.value.map { it.first })
        val delegation = requireNotNull(VoiceHandoff.parse(prompts.value.firstOrNull()?.second ?: ""))
        assertEquals("Fix add so it adds.", delegation.instruction)
        // The delegator's instruction is the request it wrote, so the session shows it,
        // not the spoken acknowledgement.
        assertNull(delegation.framing)
        assertEquals("Fix add so it adds.", delegation.displayText)
        assertTrue(completed.value.size == 1 && completed.value[0].contains("started the fix"))
        assertTrue(announced.value.size == 1 && announced.value[0].contains("Fixed add"))
        assertEquals("resp_3", store.state.sessionDetail?.voice?.delegatorResponseID)
        assertEquals(true, store.state.sessionDetail?.voice?.delegatorWork?.isEmpty())
    }

    /**
     * The drawer's pins change (a session outside Recents too), the screen moves to a draft in that
     * project, and the call follows.
     */
    @Test fun delegatorPinsUnpinsAndOpensADraft() = voiceHomeTest { h ->
        val math = OpenCodeSession(id = "ses_math", serverTitle = "Math bug", projectID = "p", directory = "/Users/kit/math", updatedAt = distantPast)
        val ios = OpenCodeProject(id = "ios", name = "opencode-ios", directory = "/Users/kit/opencode-ios")
        val other = OpenCodeProject(id = "p", name = "project", directory = "/Users/kit/project")
        h.setShared(SharedKeys.openAIAPIKey, "sk-test")
        h.setShared(SharedKeys.pinnedSessionKeys, listOf("${server.persistenceID}|${math.id}"))
        val home = h.state {
            var home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listOf(other, ios), sessions = listOf(session), activeSessionIDs = emptySet()))
            home = home.openingDetail(session, cachedItems = emptyList())
            val voice = VoiceFeature.State(server, session).live().copy(usesDelegator = true)
                .recording(VoiceTranscriptTail.Role.user, "Pin this, unpin the math one, and start something new in opencode-ios", id = "u1")
            home.withCall(voice)
        }

        val listed = LockIsolated("")
        val store = h.store(home) {
            this[Clock] = ImmediateClock()
            this[ai.opencode.feature.voice.VoiceDelegatorClient] = delegator { _, input, _ ->
                val first = input.firstOrNull()
                when {
                    first is VoiceDelegator.InputItem.User -> response(
                        "r1",
                        listOf(
                            call("t1", "list_sessions", """{"pinned_only":true}"""),
                            call("t2", "pin_session", "{}"),
                            call("t3", "unpin_session", """{"session_id":"ses_math"}"""),
                        ),
                    )
                    first is VoiceDelegator.InputItem.ToolOutput && first.callID == "t1" -> {
                        listed.setValue(first.output)
                        response("r2", listOf(call("t4", "new_session_draft", """{"directory":"/Users/kit/opencode-ios"}""")))
                    }
                    else -> response("r3", text = "Pinned this one, unpinned Math bug, and opened a new session in opencode-ios.")
                }
            }
            this[OpenCodeClient] = OpenCodeClient.testValue.openingScreens { id -> if (id == math.id) math else session }.copy(
                homeSnapshot = { _ -> OpenCodeHomeSnapshot(projects = listOf(other, ios), sessions = listOf(session, math), activeSessionIDs = emptySet()) },
            )
            voiceCall { copy(completeFunction = { _, _ -> }) }
        }
        store.exhaustive = false

        store.send(voiceEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = arguments())))
        store.receiveVoice(VoiceFeature.Action.DelegatorFinished::class)
        store.drain { store.state.sessionDetail?.session == null }

        // It saw the pin it was about to remove.
        assertTrue(listed.value, listed.value.contains("ses_math") && !listed.value.contains("ses_voice"))
        // The drawer's pins changed, even for a session outside Recents.
        assertTrue(store.state.isPinned(session.id))
        assertFalse(store.state.isPinned(math.id))
        // The screen is a draft in the requested project, and the call came along.
        assertNull(store.state.sessionDetail?.session)
        assertEquals(ios.directory, store.state.sessionDetail?.draftProject?.directory)
        assertEquals(VoiceFeature.State.Phase.Live, store.state.sessionDetail?.voice?.phase)
        assertEquals(true, store.state.sessionDetail?.voice?.isOnDraft)
    }

    /**
     * A request spoken on A and still on its way when the user switches to B
     * is delivered to A, and its result is still spoken.
     */
    @Test fun aSwitchMidDelegationStillDeliversToTheOriginalSession() = voiceHomeTest { h ->
        val a = OpenCodeSession(id = "ses_mid_a", serverTitle = "Upload", projectID = "p", directory = "/Users/kit/a", updatedAt = distantPast)
        val b = OpenCodeSession(id = "ses_mid_b", serverTitle = "Docs", projectID = "p", directory = "/Users/kit/b", updatedAt = distantPast)
        val project = OpenCodeProject(id = "p", name = "project", directory = "/Users/kit/a")
        val home = h.state {
            var home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listOf(project), sessions = listOf(a, b), activeSessionIDs = emptySet()))
            home = home.openingDetail(a)
            home.withCall(VoiceFeature.State(server, a).live().copy(activeRoute = VoiceRoute.PhoneKey(VoiceEngine.realtime)))
        }
        val submitted = LockIsolated(listOf<String?>())
        val waited = LockIsolated(listOf<String>())
        val completed = LockIsolated(listOf<String>())
        val admit = Gate()
        val store = h.store(home) {
            this[Clock] = ImmediateClock()
            voiceWork {
                copy(
                    submit = { target, _ ->
                        submitted.append(target.sessionID)
                        admit.await()
                        VoiceWorkAdmission(promptID = "msg_a")
                    },
                    awaitReply = { _, sessionID, _ ->
                        waited.append(sessionID)
                        "Fixed the upload test."
                    },
                    recentMessages = { _, _, _ -> emptyList() },
                )
            }
            voiceCall { copy(completeFunction = { _, output -> completed.append(output) }, updateInstructions = { _, _ -> }) }
            this[OpenCodeClient] = OpenCodeClient.testValue.openingScreens { id -> if (id == b.id) b else a }
        }
        store.exhaustive = false

        store.send(voiceEvent(VoiceCallEvent.UserTranscript(itemID = "u1", text = "Fix the upload test", final = true)))
        store.send(voiceEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = arguments("Fixing the upload test."))))
        eventually { submitted.value.isNotEmpty() }
        // Not admitted yet when the user switches.
        store.send(HomeFeature.Action.SessionTapped(b.id))
        store.receiveVoice(VoiceFeature.Action.TargetChanged::class)
        assertEquals(b.id, store.state.sessionDetail?.voice?.target?.sessionID)
        assertEquals(listOf("d1"), store.state.sessionDetail?.voice?.pendingWork?.map { it.callID })
        admit.yield()
        admit.finish()
        store.receiveVoice(VoiceFeature.Action.WorkAdmitted::class)
        store.receiveVoice(VoiceFeature.Action.WorkReplied::class)
        eventually { completed.value.isNotEmpty() }
        assertEquals(listOf<String?>(a.id), submitted.value)
        assertEquals(listOf(a.id), waited.value)
        assertEquals(listOf(VoiceHandoff.completion("Fixed the upload test.")), completed.value)
    }
}
