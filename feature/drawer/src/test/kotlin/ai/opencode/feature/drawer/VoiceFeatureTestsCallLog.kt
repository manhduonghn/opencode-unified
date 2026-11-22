package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.feature.drawer.HomeFeatureTestSupport.LockIsolated
import ai.opencode.feature.drawer.HomeFeatureTestSupport.append
import ai.opencode.feature.drawer.VoiceHomeTestSupport.arguments
import ai.opencode.feature.drawer.VoiceHomeTestSupport.call
import ai.opencode.feature.drawer.VoiceHomeTestSupport.copy
import ai.opencode.feature.drawer.VoiceHomeTestSupport.delegator
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
import ai.opencode.feature.voice.ReplyWatch
import ai.opencode.feature.voice.VoiceCallEvent
import ai.opencode.feature.voice.VoiceDelegator
import ai.opencode.feature.voice.VoiceDelegatorClient
import ai.opencode.feature.voice.VoiceTranscriptTail
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServerEvent
import kotlinx.coroutines.awaitCancellation
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.testing.eventually
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.isDraft
import ai.opencode.feature.voice.VoiceCallClient
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import java.time.Instant
import java.util.Collections
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VoiceFeatureTestsCallLog {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_voice", serverTitle = "Voice lab", projectID = "p", directory = "/Users/kit/project", updatedAt = Instant.EPOCH)

    private fun liveCall(home: HomeFeature.State, on: OpenCodeSession): HomeFeature.State =
        home.copy(sessionDetail = home.sessionDetail!!.copy(voice = VoiceFeature.State(server, on).withPhase(VoiceFeature.State.Phase.Live)))

    private val targetChanged = { action: HomeFeature.Action ->
        action == HomeFeature.Action.detail(SessionFeature.Action.Voice(PresentationAction.Presented(VoiceFeature.Action.TargetChanged)))
    }

    @Test
    fun switchingSessionsInHomeKeepsAnActiveCallAndRetargetsIt() = runTest {
        val other = OpenCodeSession(id = "ses_other", serverTitle = "iOS UI", projectID = "p", directory = "/Users/kit/opencode-ios", updatedAt = Instant.EPOCH)
        var home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session, other), activeSessionIDs = setOf(other.id)))
        home = home.copy(
            transcriptCache = home.transcriptCache +
                (session.id to listOf(OpenCodeTranscriptItem.UserText(id = "m1", text = "first"))) +
                (other.id to listOf(OpenCodeTranscriptItem.UserText(id = "m2", text = "second"))),
        )
        home = home.openingDetail(session, cachedItems = home.transcriptCache[session.id])
        home = liveCall(home, session)
        val initialNavID = home.sessionDetail?.navigationID

        val instructions = Collections.synchronizedList(mutableListOf<String>())
        val store = TestStore(home, HomeFeature(), backgroundScope) {
            this[DateClient] = DateClient { Instant.EPOCH }
            this[VoiceCallClient] = VoiceCallClient.testValue.let { inert ->
                VoiceCallClient(
                    start = inert.start, completeFunction = inert.completeFunction,
                    closeDelegation = inert.closeDelegation, setMuted = inert.setMuted, end = inert.end,
                    updateInstructions = { full, _ -> instructions += full },
                )
            }
        }
        store.exhaustive = false

        store.send(HomeFeature.Action.SessionTapped(other.id))
        assertEquals(other.id, store.state.sessionDetail?.session?.id)
        assertEquals(initialNavID, store.state.sessionDetail?.navigationID)
        assertEquals(VoiceFeature.State.Phase.Live, store.state.sessionDetail?.voice?.phase)
        assertEquals(other.id, store.state.sessionDetail?.voice?.target?.sessionID)
        assertEquals("iOS UI", store.state.sessionDetail?.voice?.sessionTitle)
        store.receive(targetChanged, "voice.targetChanged")
        // The voice model learns which session it now works on.
        assertTrue(eventually { instructions.lastOrNull()?.contains("\"iOS UI\"") == true })
    }

    @Test
    fun newSessionKeepsTheCallAndMovesItToTheDraft() = runTest {
        val project = OpenCodeProject(id = "p", name = "opencode-ios", directory = "/Users/kit/opencode-ios")
        var home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listOf(project), sessions = listOf(session), activeSessionIDs = emptySet()))
        home = liveCall(home.openingDetail(session), session)
        val initialNavID = home.sessionDetail?.navigationID

        val instructions = Collections.synchronizedList(mutableListOf<String>())
        val store = TestStore(home, HomeFeature(), backgroundScope) {
            this[DateClient] = DateClient { Instant.EPOCH }
            this[VoiceCallClient] = VoiceCallClient.testValue.let { inert ->
                VoiceCallClient(
                    start = inert.start, completeFunction = inert.completeFunction,
                    closeDelegation = inert.closeDelegation, setMuted = inert.setMuted, end = inert.end,
                    updateInstructions = { full, _ -> instructions += full },
                )
            }
        }
        store.exhaustive = false

        store.send(HomeFeature.Action.NewSessionTapped)
        assertEquals(true, store.state.sessionDetail?.isDraft)
        // Same identity, so the call's effects survive the swap.
        assertEquals(initialNavID, store.state.sessionDetail?.navigationID)
        assertEquals(VoiceFeature.State.Phase.Live, store.state.sessionDetail?.voice?.phase)
        assertNull(store.state.sessionDetail?.voice?.target?.sessionID)
        assertEquals(project.directory, store.state.sessionDetail?.voice?.target?.directory)
        store.receive(targetChanged, "voice.targetChanged")
        assertTrue(eventually { instructions.lastOrNull()?.contains("has not started yet") == true })
    }

    @Test
    fun delegatorSendsToOffScreenSessionAndSSEExecutionEndedAnnouncesReplyWhileViewingAnotherSession() = voiceHomeTest { h ->
        val blog = OpenCodeSession(id = "ses_blog", serverTitle = "Blog game", projectID = "p", directory = "/Users/kit/blog-game", updatedAt = HomeFeatureTestSupport.distantPast)
        val session = session.copy(updatedAt = HomeFeatureTestSupport.distantPast)
        h.setShared(SharedKeys.openAIAPIKey, "sk-test")
        val home = h.state {
            var home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session, blog), activeSessionIDs = emptySet()))
            home = home.copy(transcriptCache = home.transcriptCache + (session.id to emptyList()) + (blog.id to emptyList()))
            home = home.openingDetail(session, cachedItems = emptyList())
            home.withCall(
                VoiceFeature.State(server, session).live().copy(usesDelegator = true)
                    .recording(VoiceTranscriptTail.Role.user, "Tell the blog game session to finish the scoring loop", id = "u1"),
            )
        }

        val announced = LockIsolated(listOf<String>())
        val settled = LockIsolated(listOf<String>())
        val sentTo = LockIsolated(listOf<String>())
        val fetched = LockIsolated(listOf<Pair<String, String>>())
        val store = h.store(home) {
            this[Clock] = ImmediateClock()
            this[VoiceDelegatorClient] = delegator { _, input, _ ->
                when (input.firstOrNull()) {
                    is VoiceDelegator.InputItem.User -> response(
                        "r1",
                        listOf(call("t1", "send_message", """{"session_id":"ses_blog","text":"Finish the scoring loop."}""")),
                    )
                    else -> response("r2", text = "Started the scoring loop in Blog game.")
                }
            }
            this[OpenCodeClient] = OpenCodeClient.testValue.openingScreens { id -> if (id == blog.id) blog else session }.copy(
                homeSnapshot = { _ -> OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session, blog), activeSessionIDs = emptySet()) },
                homeAttention = { _, _, _ -> emptySet() },
                sendDeliveredPrompt = { _, sessionID, _, _ ->
                    sentTo.append(sessionID)
                    "msg_blog"
                },
                familyPendingRequests = { _, _, _ -> throw OpenCodeClientError(message = "Offline") },
            )
            voiceWork {
                copy(
                    awaitReply = { _, _, _ -> awaitCancellation() },
                    fetchReply = { _, sessionID, promptID ->
                        fetched.append(sessionID to promptID)
                        "Finished the scoring loop and all tests pass."
                    },
                    noteSessionSettled = { _, sessionID -> settled.append(sessionID) },
                )
            }
            voiceCall { copy(completeFunction = { _, _ -> }, announce = { _, _, text -> announced.append(text) }) }
        }
        store.exhaustive = false

        store.send(voiceEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = arguments())))
        store.receiveVoice(VoiceFeature.Action.DelegatorFinished::class)
        assertEquals("Screen stays on the viewed session", session.id, store.state.sessionDetail?.session?.id)
        assertEquals(listOf("msg_blog"), store.state.sessionDetail?.voice?.replyWatch?.watched?.map { it.promptID })
        assertEquals(listOf(blog.id), sentTo.value)

        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionEnded(sessionID = blog.id)))
        store.receiveVoice(VoiceFeature.Action.ReplyWatch::class)
        store.receiveVoice(VoiceFeature.Action.ReplyWatch::class)
        VoiceHomeTestSupport.eventually { announced.value.isNotEmpty() }
        store.skipReceivedActions()

        assertEquals(listOf(blog.id to "msg_blog"), fetched.value)
        assertEquals(listOf(blog.id), settled.value)
        assertEquals(true, store.state.sessionDetail?.voice?.replyWatch?.watched?.isEmpty())
        assertEquals(1, announced.value.size)
        assertEquals(true, announced.value.firstOrNull()?.contains("Blog game"))
        assertEquals(true, announced.value.firstOrNull()?.contains("Finished the scoring loop"))
    }

    @Test
    fun reconnectSnapshotCatchesUpWatchedSessionThatFinishedWhileOffline() = voiceHomeTest { h ->
        val blog = OpenCodeSession(id = "ses_blog", serverTitle = "Blog game", projectID = "p", directory = "/Users/kit/blog-game", updatedAt = HomeFeatureTestSupport.distantPast)
        val session = session.copy(updatedAt = HomeFeatureTestSupport.distantPast)
        val home = h.state {
            var home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session, blog), activeSessionIDs = setOf(blog.id)))
            home = home.openingDetail(session, cachedItems = emptyList())
            var voice = VoiceFeature.State(server, session).live().copy(usesDelegator = true)
            voice = voice.copy(
                replyWatch = voice.replyWatch.transition(
                    ReplyWatch.Event.Sent(listOf(ReplyWatch.Prompt(promptID = "msg_blog", sessionID = blog.id, title = blog.title))),
                ).first,
            )
            home.withCall(voice)
        }

        val announced = LockIsolated(listOf<String>())
        val fetched = LockIsolated(listOf<Pair<String, String>>())
        val store = h.store(home) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.openingScreens { id -> if (id == blog.id) blog else session }.copy(
                homeAttention = { _, _, _ -> emptySet() },
                familyPendingRequests = { _, _, _ -> throw OpenCodeClientError(message = "Offline") },
            )
            voiceWork {
                copy(fetchReply = { _, sessionID, promptID ->
                    fetched.append(sessionID to promptID)
                    "Completed while the phone was reconnecting."
                })
            }
            voiceCall { copy(announce = { _, _, text -> announced.append(text) }) }
        }
        store.exhaustive = false

        store.send(HomeFeature.Action.SnapshotRefreshed(OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session, blog), activeSessionIDs = emptySet())))
        store.receiveVoice(VoiceFeature.Action.ReplyWatch::class)
        store.receiveVoice(VoiceFeature.Action.ReplyWatch::class)
        VoiceHomeTestSupport.eventually { announced.value.isNotEmpty() }
        store.skipReceivedActions()

        assertEquals(listOf(blog.id to "msg_blog"), fetched.value)
        assertEquals(true, store.state.sessionDetail?.voice?.replyWatch?.watched?.isEmpty())
        assertEquals(1, announced.value.size)
        assertEquals(true, announced.value.firstOrNull()?.contains("Completed while the phone was reconnecting."))
    }
}
