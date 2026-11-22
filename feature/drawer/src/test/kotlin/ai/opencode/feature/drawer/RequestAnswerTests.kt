package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.sharing.shared
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeatureTestSupport.detail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.VoiceHomeTestSupport.copy
import ai.opencode.feature.drawer.VoiceHomeTestSupport.voice
import ai.opencode.feature.drawer.VoiceHomeTestSupport.voiceCall
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.beginAnswering
import ai.opencode.feature.session.callOwnsAudio
import ai.opencode.feature.session.formRequests
import ai.opencode.feature.session.from
import ai.opencode.feature.session.isAnsweringRequest
import ai.opencode.feature.session.noteOpening
import ai.opencode.feature.session.parkedComposer
import ai.opencode.feature.session.permissionRequests
import ai.opencode.feature.session.questionPageIndices
import ai.opencode.feature.session.updateRequestDock
import ai.opencode.feature.session.wantsRequestDock
import ai.opencode.feature.session.withFormAnswers
import ai.opencode.feature.session.withFormRequests
import ai.opencode.feature.session.withQuestionPageIndices
import ai.opencode.feature.session.withRequestArrivalsAreLive
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceSessionBoardClient
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.feature.voice.VoiceTranscriptRecord
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RequestAnswerTests {
    @get:Rule val isolation = TestIsolationRule()

    private val session = OpenCodeSession(id = "ses_answer", serverTitle = "Answer", projectID = "proj", directory = "/tmp/answer", updatedAt = Instant.now())

    private fun SessionFeature.State.withPermissions(permissions: List<OpenCodePermissionRequest>): SessionFeature.State =
        copy(requestDock = requestDock.copy(permissionRequests = permissions))

    @Test
    fun aSubagentsFoldedQuestionStaysFoldedWhenOpened() = homeTest { h ->
        val server = OpenCodeServer(url = "https://stack.example", username = "", password = "")
        val root = OpenCodeSession(id = "root", serverTitle = "root", projectID = "project", directory = "/project", updatedAt = Instant.ofEpochSecond(10), parentID = null)
        val child = OpenCodeSession(id = "child", serverTitle = "child", projectID = "project", directory = "/project", updatedAt = Instant.ofEpochSecond(20), parentID = "root")
        val question = Json { ignoreUnknownKeys = true }.decodeFromString(
            OpenCodeFormRequest.serializer(),
            """{"id":"frm_child","sessionID":"child","title":"Questions","metadata":{"kind":"question"},"fields":[{"key":"q0","type":"string","title":"A","options":[{"value":"x","label":"x"}]},{"key":"q1","type":"string","title":"B","options":[{"value":"y","label":"y"}]}]}""",
        )
        val pushed = h.state {
            HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(root, child), activeSessionIDs = emptySet()))
                .openingDetail(root, cachedItems = emptyList())
                .updatingDetail { detail ->
                    detail.copy(familySessions = listOf(root, child))
                        .withFormRequests(listOf(question))
                        .withFormAnswers(mapOf(question.id to question.defaultAnswers))
                        .withQuestionPageIndices(mapOf(question.id to 1))
                        .updateRequestDock { it.copy(collapsedRequestIDs = setOf(question.id)) }
                }
                .pushingSubagent(child)
        }
        val detail = pushed.subagentStack.last()
        assertEquals(listOf(question.id), detail.formRequests.map { it.id })
        assertEquals(setOf(question.id), detail.requestDock.collapsedRequestIDs)
        assertEquals(mapOf(question.id to 1), detail.questionPageIndices)
    }
}
