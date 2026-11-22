package ai.opencode.feature.composer

import ai.opencode.core.store.TestStore
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodePermissionRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only `RequestDockFeature`'s own suite, with the fold's pure rules (`RequestCollapse`,
 * `RequestPanelGesture`, `RequestErrorLine`).
 */
class RequestAnswerTests {
    private val json = Json { ignoreUnknownKeys = true }

    private fun form(id: String = "frm_answer", fields: String): OpenCodeFormRequest = json.decodeFromString(
        OpenCodeFormRequest.serializer(),
        """{"id":"$id","sessionID":"ses_answer","title":"Questions","metadata":{"kind":"question"},"fields":$fields}""",
    )

    private val twoQuestions: OpenCodeFormRequest
        get() = form(
            fields = """[{"key":"q0","type":"string","title":"Interface","description":"Which?","options":[{"value":"Native","label":"Native"}],"custom":true},{"key":"q1","type":"multiselect","title":"Notes","description":"Which notes?","options":[{"value":"Parking","label":"Parking"}],"custom":true}]""",
        )

    private val shell = OpenCodePermissionRequest(id = "per_answer", sessionID = "ses_answer", action = "shell", resources = listOf("rm -rf .lab-output"), save = listOf("rm *"))

    @Test fun requestDockFeatureReducesSelectionPagingAndAnswerEditsExhaustively() = runTest {
        val twoQuestions = twoQuestions
        val store = TestStore(
            RequestDockFeature.State<Unit>(
                permissionRequests = listOf(shell),
                formRequests = listOf(twoQuestions),
                formAnswers = mapOf(twoQuestions.id to twoQuestions.defaultAnswers),
                requestErrors = mapOf(twoQuestions.id to "Required"),
                requestFailures = mapOf(twoQuestions.id to RequestDockFeature.State.RequestFailure.Reply),
            ),
            RequestDockFeature(),
            this,
        )

        store.send(RequestDockFeature.Action.ReviewRequestSelected(shell.id)) {
            it.copy(selectedRequestID = shell.id)
        }
        store.send(RequestDockFeature.Action.ReviewRequestSelected(twoQuestions.id)) {
            it.copy(selectedRequestID = twoQuestions.id)
        }
        store.send(RequestDockFeature.Action.QuestionPageChanged(requestID = twoQuestions.id, index = 1)) {
            it.copy(questionPageIndices = it.questionPageIndices + (twoQuestions.id to 1))
        }
        store.send(
            RequestDockFeature.Action.FormValueChanged(formID = twoQuestions.id, key = "q1", value = OpenCodeFormValue.Strings(listOf("Parking"))),
        ) {
            it.copy(
                formAnswers = it.formAnswers + (twoQuestions.id to (it.formAnswers.getValue(twoQuestions.id) + ("q1" to OpenCodeFormValue.Strings(listOf("Parking"))))),
                requestErrors = it.requestErrors - twoQuestions.id,
                requestFailures = it.requestFailures - twoQuestions.id,
            )
        }
        store.finish()
    }

    @Test fun aDockedQuestionFoldsToItsBarAndBackExhaustively() = runTest {
        val twoQuestions = twoQuestions
        val store = TestStore(
            RequestDockFeature.State<Unit>(
                permissionRequests = listOf(shell),
                formRequests = listOf(twoQuestions),
                formAnswers = mapOf(twoQuestions.id to twoQuestions.defaultAnswers),
            ),
            RequestDockFeature(),
            this,
        )
        store.send(RequestDockFeature.Action.CollapseChanged(requestID = twoQuestions.id, collapsed = true)) {
            it.copy(collapsedRequestIDs = setOf(twoQuestions.id))
        }
        // Already folded, a permission (its Allow would hide), or gone: nothing.
        store.send(RequestDockFeature.Action.CollapseChanged(requestID = twoQuestions.id, collapsed = true))
        store.send(RequestDockFeature.Action.CollapseChanged(requestID = shell.id, collapsed = true))
        store.send(RequestDockFeature.Action.CollapseChanged(requestID = "frm_gone", collapsed = true))
        store.send(RequestDockFeature.Action.CollapseChanged(requestID = twoQuestions.id, collapsed = false)) {
            it.copy(collapsedRequestIDs = emptySet())
        }
        store.finish()
    }

    @Test fun aFailedSendsReasonFitsItsOneLine() {
        assertEquals("HTTP 500", RequestErrorLine.brief("The server returned HTTP 500."))
        assertEquals("Request timed out", RequestErrorLine.brief("Request timed out."))
        assertNull(RequestErrorLine.detail("Couldn’t send answer", OpenCodeClientError.UNREACHABLE_MESSAGE))
    }

    @Test fun thePanelGestureResets() {
        val gesture = RequestPanelGesture()
        gesture.isTracking = true
        assertTrue(gesture.isTracking)
        assertTrue(gesture.takeFollows())
        // A still finger keeps holding; only the drag's end or a reset lets go.
        gesture.reset()
        assertFalse(gesture.isTracking)
        gesture.settling(1f)
        assertTrue(gesture.takeFollows())
        gesture.reset()
        assertFalse(gesture.takeFollows())
        gesture.presented = 0.5f
        assertTrue(gesture.isMidway)
        gesture.presented = 1f
        assertFalse(gesture.isMidway)
    }

    @Test fun theFoldFollowsTheFingerAndSettlesPastItsThreshold() {
        assertEquals(0.25f, RequestCollapse.progress(start = 0f, translation = 50f, travel = 200f))
        assertEquals(1f, RequestCollapse.progress(start = 1f, translation = 40f, travel = 200f))
        assertEquals(0f, RequestCollapse.progress(start = 1f, translation = -300f, travel = 200f))
        // Open: past 30% folds, short of it snaps back; a flick goes its way.
        assertTrue(RequestCollapse.settlesCollapsed(progress = 0.35f, velocity = 0f, startedCollapsed = false, threshold = 0.3f, flick = 700f))
        assertFalse(RequestCollapse.settlesCollapsed(progress = 0.2f, velocity = 0f, startedCollapsed = false, threshold = 0.3f, flick = 700f))
        assertTrue(RequestCollapse.settlesCollapsed(progress = 0.1f, velocity = 900f, startedCollapsed = false, threshold = 0.3f, flick = 700f))
        assertFalse(RequestCollapse.settlesCollapsed(progress = 0.5f, velocity = -900f, startedCollapsed = false, threshold = 0.3f, flick = 700f))
        // Folded: dragged up past 30% opens.
        assertFalse(RequestCollapse.settlesCollapsed(progress = 0.6f, velocity = 0f, startedCollapsed = true, threshold = 0.3f, flick = 700f))
        assertTrue(RequestCollapse.settlesCollapsed(progress = 0.8f, velocity = 0f, startedCollapsed = true, threshold = 0.3f, flick = 700f))
        // The settle carries on at the finger's speed, in distances per second.
        assertEquals(4f, RequestCollapse.settleVelocity(400f, from = 0.5f, to = 1f, travel = 200f))
        assertEquals(1, RequestCollapse.remaining(twoQuestions, mapOf("q0" to OpenCodeFormValue.Str("Native"))))
    }
}
