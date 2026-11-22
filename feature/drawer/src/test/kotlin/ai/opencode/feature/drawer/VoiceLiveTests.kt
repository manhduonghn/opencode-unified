package ai.opencode.feature.drawer

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeatureTestSupport.LockIsolated
import ai.opencode.feature.drawer.HomeFeatureTestSupport.append
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receiveBounded
import ai.opencode.feature.drawer.VoiceHomeTestSupport.arguments
import ai.opencode.feature.drawer.VoiceHomeTestSupport.copy
import ai.opencode.feature.drawer.VoiceHomeTestSupport.live
import ai.opencode.feature.drawer.VoiceHomeTestSupport.openingScreens
import ai.opencode.feature.drawer.VoiceHomeTestSupport.receiveVoice
import ai.opencode.feature.drawer.VoiceHomeTestSupport.voiceCall
import ai.opencode.feature.drawer.VoiceHomeTestSupport.voiceEvent
import ai.opencode.feature.drawer.VoiceHomeTestSupport.voiceHomeTest
import ai.opencode.feature.drawer.VoiceHomeTestSupport.voiceWork
import ai.opencode.feature.drawer.VoiceHomeTestSupport.withCall
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.voice.VoiceCallEvent
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceWorkAdmission
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.model.OpenCodeCompaction
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VoiceLiveTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_voice", serverTitle = "Voice lab", projectID = "p", directory = "/Users/kit/project", updatedAt = HomeFeatureTestSupport.distantPast)

    /**
     * A compaction that happened while the call was elsewhere is seen when
     * the call arrives, not only when a later one lands.
     */
    @Test fun aCallArrivingInASessionKnowsItsLatestCompaction() = homeTest { h ->
        val other = OpenCodeSession(id = "ses_other", serverTitle = "Other", projectID = "p", directory = "/Users/kit/project", updatedAt = HomeFeatureTestSupport.distantPast)
        val project = OpenCodeProject(id = "p", name = "project", directory = "/Users/kit/project")
        val compacted = listOf(
            OpenCodeTranscriptItem.UserText(id = "msg_1", text = "Hello"),
            OpenCodeTranscriptItem.Compaction(id = "cmp_1", info = OpenCodeCompaction(status = OpenCodeCompaction.Status.Completed)),
        )
        val detail = h.state {
            var home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listOf(project), sessions = listOf(session, other), activeSessionIDs = emptySet()))
            home = home.copy(sessionDetail = SessionFeature.State.session(server, session, cachedItems = emptyList()))
            home = home.withCall(VoiceFeature.State(server, session).live())
            home.makeDetail(other, cachedItems = compacted).second
        }
        assertEquals("Moved by the drawer", "cmp_1", detail.voice?.compactions?.get("ses_other"))

        val store = h.sessionStore(h.state { SessionFeature.State.session(server, other, cachedItems = compacted) })
        store.exhaustive = false
        store.send(SessionFeature.Action.VoiceTapped)
        assertEquals("Started here", "cmp_1", store.state.voice?.compactions?.get("ses_other"))
        h.skipInFlightEffects()
    }

    @Test fun draftAdoptingCreatedSessionDoesNotRetargetAndRealRetargetSendsConciseDeltaAndSilentAbandon() = voiceHomeTest { h ->
        val created = OpenCodeSession(id = "ses_created", serverTitle = "Untitled session", projectID = "p", directory = "/Users/kit/project", updatedAt = HomeFeatureTestSupport.distantPast)
        val other = OpenCodeSession(id = "ses_other", serverTitle = "Other session", projectID = "p", directory = "/Users/kit/project", updatedAt = HomeFeatureTestSupport.distantPast)
        val project = OpenCodeProject(id = "p", name = "project", directory = "/Users/kit/project")
        val home = h.state {
            var home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listOf(project), sessions = listOf(other), activeSessionIDs = emptySet()))
            home = home.copy(sessionDetail = SessionFeature.State.draft(server, draftProject = project, projects = listOf(project)))
            home.withCall(VoiceFeature.State(server, draftDirectory = project.directory, model = null).live())
        }

        val updatedInstructions = LockIsolated(listOf<Pair<String, String?>>())
        val completed = LockIsolated(listOf<Pair<String, String>>())

        val store = h.store(home) {
            voiceCall {
                copy(
                    updateInstructions = { full, delta -> updatedInstructions.append(full to delta) },
                    completeFunction = { callID, output -> completed.append(callID to output) },
                )
            }
            voiceWork {
                copy(
                    submit = { _, _ -> VoiceWorkAdmission(promptID = "msg_1", createdSession = created) },
                    awaitReply = { _, _, _ -> awaitCancellation() },
                )
            }
            this[OpenCodeClient] = OpenCodeClient.testValue.openingScreens { id -> if (id == other.id) other else created }
        }
        store.exhaustive = false

        // 1. First request on a draft creates `ses_created` (initially "Untitled session") and adopts it:
        // uses "New session" instead of "Untitled session", and tells the voice agent it is now attached
        // without treating adoption as a user retarget ("Session changed.").
        store.send(voiceEvent(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Start the build.", final = true)))
        store.send(voiceEvent(VoiceCallEvent.FunctionCall(callID = "del_draft", name = "delegate", arguments = arguments())))
        store.receiveVoice(VoiceFeature.Action.SessionStarted::class)
        store.receiveVoice(VoiceFeature.Action.WorkAdmitted::class)
        // Effects the actions it reduces start run too.
        repeat(3) {
            delay(15)
            store.skipReceivedActions()
        }
        assertEquals("ses_created", store.state.sessionDetail?.voice?.target?.sessionID)
        assertEquals("New session", store.state.sessionDetail?.voice?.sessionTitle)
        assertEquals(updatedInstructions.value.toString(), 1, updatedInstructions.value.size)
        assertEquals(false, updatedInstructions.value.first().first.contains("has not started yet"))
        assertTrue(updatedInstructions.value.first().first.contains("You are attached to the coding session \"New session\" in the project folder \"project\"."))
        assertEquals("You are attached to the coding session \"New session\" in the project folder \"project\".", updatedInstructions.value.first().second)

        // 2. When the server's generated title arrives, the call and the voice agent pick it up.
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.SessionRenamed(sessionID = "ses_created", title = "Build pipeline")))
        store.receiveBounded(
            { it.detailAction is SessionFeature.Action.FamilySessionRenamed },
            "sessionDetail.familySessionRenamed",
        )
        // Effects the actions it reduces start run too.
        repeat(3) {
            delay(15)
            store.skipReceivedActions()
        }
        assertEquals("Build pipeline", store.state.sessionDetail?.voice?.sessionTitle)
        assertEquals(2, updatedInstructions.value.size)
        assertTrue(updatedInstructions.value.last().first.contains("You are attached to the coding session \"Build pipeline\" in the project folder \"project\"."))
        assertEquals("You are attached to the coding session \"Build pipeline\" in the project folder \"project\".", updatedInstructions.value.last().second)

        // 3. Switching to another session in the drawer IS a real retarget: keeps the full prompt for future
        // `session.start`s and sends a short delta via `session.instructions.append`. The delegation on its way keeps going.
        store.send(HomeFeature.Action.SessionTapped(other.id))
        store.receiveVoice(VoiceFeature.Action.TargetChanged::class)
        // Effects the actions it reduces start run too.
        repeat(3) {
            delay(15)
            store.skipReceivedActions()
        }
        assertEquals(3, updatedInstructions.value.size)
        val (full, delta) = updatedInstructions.value.last()
        assertTrue("Full prompt is preserved for future session.start", full.contains("Delegation policy:"))
        assertTrue(full.contains("\"Other session\""))
        assertEquals(true, delta?.startsWith("Session changed."))
        assertEquals(true, delta?.contains("\"Other session\""))
        assertTrue("Real retarget sends a short delta, not the full startup prompt", (delta?.length ?: 0) < 500)
        assertEquals(true, delta?.contains("the call and conversation continue"))
        assertEquals(listOf("del_draft"), store.state.sessionDetail?.voice?.pendingWork?.map { it.callID })
        assertTrue(completed.value.isEmpty())
    }
}
