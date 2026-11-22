package ai.opencode.feature.session

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.files.ComposerStagedFile
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.feature.composer.ComposerAttachmentsFeature
import ai.opencode.feature.composer.ComposerFileAttachment
import ai.opencode.feature.composer.DictationClient
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.feature.composer.DraftState
import ai.opencode.feature.composer.RequestBar
import ai.opencode.feature.composer.RequestDockFeature
import ai.opencode.feature.composer.SharedComposerDraft
import ai.opencode.feature.composer.SharedLocation
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeUploadEvent
import android.content.ContextWrapper
import androidx.compose.ui.geometry.Offset
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pending request's panel grows out of the composer, and the composer's row is your own answer.
 *
 * `inlineRequestBarMultilineDictationKeepsTopAscendersAndPinsToTheEndWithoutStep`,
 * `disabledQuestionBarTrailingControlHasReadableLightContrast`,
 * `requestBarDictationFoldAndUnfoldTraceHasNoStepOrOpaqueControlCollision`.
 */
class RequestAnswerTests {
    private val session = OpenCodeSession(id = "ses_answer", serverTitle = "Answer", projectID = "proj", directory = "/tmp/answer", updatedAt = Instant.EPOCH)
    private val server = OpenCodeServer(url = "http://127.0.0.1:4105", username = "", password = "")
    private val json = Json { ignoreUnknownKeys = true }

    private fun form(id: String = "frm_answer", fields: String): OpenCodeFormRequest = json.decodeFromString(
        OpenCodeFormRequest.serializer(),
        """{"id":"$id","sessionID":"ses_answer","title":"Questions","metadata":{"kind":"question"},"fields":$fields}""",
    )

    private val slack: OpenCodeFormRequest
        get() = form(fields = """[{"key":"q0","type":"string","title":"Send in Slack","description":"May I bring Slack forward?","options":[{"value":"Bring Slack forward","label":"Bring Slack forward"},{"value":"Draft it instead","label":"Draft it instead"}],"custom":true}]""")

    private val twoQuestions: OpenCodeFormRequest
        get() = form(fields = """[{"key":"q0","type":"string","title":"Interface","description":"Which?","options":[{"value":"Native","label":"Native"}],"custom":true},{"key":"q1","type":"multiselect","title":"Notes","description":"Which notes?","options":[{"value":"Parking","label":"Parking"}],"custom":true}]""")

    private val shell = OpenCodePermissionRequest(id = "per_answer", sessionID = "ses_answer", action = "shell", resources = listOf("rm -rf .lab-output"), save = listOf("rm *"))

    private val empty = OpenCodePendingRequests(permissions = emptyList(), forms = emptyList())

    private fun Harness.state(
        draft: String = "",
        forms: List<OpenCodeFormRequest> = emptyList(),
        permissions: List<OpenCodePermissionRequest> = emptyList(),
        answering: Boolean = true,
    ): SessionFeature.State = state {
        var state = SessionFeature.State.session(server = server, session = session, cachedItems = emptyList())
            .withDraft(draft)
            .updateRequestDock { dock ->
                dock.copy(
                    formRequests = forms,
                    permissionRequests = permissions,
                    formAnswers = dock.formAnswers + forms.associate { it.id to it.defaultAnswers },
                )
            }
        if (answering) state = state.beginAnswering()
        state
    }

    private fun SessionFeature.State.withFormAnswer(formID: String, key: String, value: OpenCodeFormValue?): SessionFeature.State =
        updateRequestDock { dock ->
            val answers = dock.formAnswers[formID] ?: return@updateRequestDock dock
            dock.copy(formAnswers = dock.formAnswers + (formID to (if (value == null) answers - key else answers + (key to value))))
        }

    private fun draft(text: String): Action = Action.Binding(BindingAction.Draft(text))

    private fun replyClient(sent: LockIsolated<Map<String, OpenCodeFormValue>?>): DependencyValues.Builder.() -> Unit = {
        this[OpenCodeClient] = this[OpenCodeClient].copy(
            replyForm = { _, _, _, answer -> sent.setValue(answer) },
            familyPendingRequests = { _, _, _ -> empty },
        )
    }

    private fun Harness.returnKeySends(on: Boolean) = sharedStore.reference(SharedKeys.returnKeySends).set(on)

    private val requestResponseFinished: (Action) -> Boolean =
        { it is Action.RequestDock && it.action is RequestDockFeature.Action.ResponseFinished }

    @Test fun aRequestTakesTheComposerAndGivesItBackAsItWas() = sessionTest { h ->
        val store = h.store(h.state(draft = "Half a message", answering = false)) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                replyForm = { _, _, _, _ -> },
                familyPendingRequests = { _, _, _ -> empty },
            )
        }
        store.exhaustive = false
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = listOf(slack)))))
        assertTrue(store.state.isAnsweringRequest)
        assertEquals("Half a message", store.state.parkedComposer?.text)
        assertEquals("", store.state.draft)
        assertEquals(RequestBar.Answer(multiple = false, isLast = true, ready = false, isCustom = false), store.state.requestBar)
        // Saved elsewhere, the session keeps its own message, not the answer.
        assertEquals("Half a message", store.state.sessionComposer.text)

        store.send(Action.formValueChanged(slack.id, "q0", OpenCodeFormValue.Str("Bring Slack forward")))
        store.send(Action.SendTapped)
        store.receive(requestResponseFinished, "requestResponseFinished")
        assertFalse(store.state.isAnsweringRequest)
        assertEquals("Half a message", store.state.draft)
    }

    @Test fun typingInTheBarAnswersAndTheMarkSetsItAside() = sessionTest { h ->
        val store = h.store(h.state(forms = listOf(slack)))
        store.exhaustive = false
        store.send(draft("Text them I’m 10 minutes out"))
        assertEquals(OpenCodeFormValue.Str("Text them I’m 10 minutes out"), store.state.formAnswers[slack.id]?.get("q0"))
        assertEquals(RequestBar.Answer(multiple = false, isLast = true, ready = true, isCustom = true), store.state.requestBar)
        // A choice wins; the words stay in the bar, set aside.
        store.send(Action.formValueChanged(slack.id, "q0", OpenCodeFormValue.Str("Draft it instead")))
        assertEquals(RequestBar.Answer(multiple = false, isLast = true, ready = true, isCustom = false), store.state.requestBar)
        assertEquals("Text them I’m 10 minutes out", store.state.draft)
        // The mark takes them back as the answer, and sets them aside again.
        store.send(Action.requestCustomMarkTapped)
        assertEquals(OpenCodeFormValue.Str("Text them I’m 10 minutes out"), store.state.formAnswers[slack.id]?.get("q0"))
        store.send(Action.requestCustomMarkTapped)
        assertNull(store.state.formAnswers[slack.id]?.get("q0"))
        assertEquals(false, store.state.requestBar?.isReady)
    }

    @Test fun dictatingInTheBarAnswersAndItsSendSends() = sessionTest { h ->
        // The composer's held mic, recording; its words show in the scope bar.
        val initial = h.state(forms = listOf(slack))
            .withDictationPhase(DictationFeature.Phase.Recording)
            .withDictationHold(DictationFeature.Hold(text = "", isLocked = true))
        val sent = LockIsolated<Map<String, OpenCodeFormValue>?>(null)
        val store = h.store(initial, replyClient(sent))
        store.exhaustive = false
        store.send(Action.dictationUpdated("Draft it and I’ll post it after lunch", recording = initial.dictationRecording))
        assertEquals("Draft it and I’ll post it after lunch", store.state.dictationHold?.text)
        // The bar's ↑: the last words land, then the answer goes.
        store.send(Action.dictationSendTapped)
        store.send(Action.dictationStopped(errorMessage = null, recording = store.state.dictationRecording))
        assertEquals(OpenCodeFormValue.Str("Draft it and I’ll post it after lunch"), store.state.formAnswers[slack.id]?.get("q0"))
        // Bound to the request it was recorded for.
        store.receive({ it is Action.RequestDock && it.action is RequestDockFeature.Action.BarSent }, "requestBarSent")
        store.receive(requestResponseFinished, "requestResponseFinished")
        assertEquals(mapOf("q0" to OpenCodeFormValue.Str("Draft it and I’ll post it after lunch")), sent.value)
        assertFalse(store.state.isAnsweringRequest)
    }

    @Test fun denyingWithTheBarsWordsIsOneStep() = sessionTest { h ->
        val initial = h.state(permissions = listOf(shell)).withDraft("Write the new run to .lab-output/run-2")
        val sent = LockIsolated(listOf<String>())
        val store = h.store(initial) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                replyPermission = { _, _, _, reply, message -> sent.append("${reply.name.lowercase()}:${message ?: "nil"}") },
                familyPendingRequests = { _, _, _ -> empty },
            )
        }
        store.exhaustive = false
        assertEquals(RequestBar.Decline(RequestBar.Verb.Deny), store.state.requestBar)
        store.send(Action.SendTapped)
        store.receive(requestResponseFinished, "requestResponseFinished")
        assertEquals(listOf("reject:Write the new run to .lab-output/run-2"), sent.value)
        // The composer comes back empty: the reason went with the denial.
        assertEquals("", store.state.draft)
    }

    @Test fun questionsPageAndEachPageKeepsItsWords() = sessionTest { h ->
        val store = h.store(h.state(forms = listOf(twoQuestions)))
        store.exhaustive = false
        assertEquals(false, store.state.requestBar?.isLast)
        // Send waits for an answer, then moves on.
        store.send(Action.SendTapped)
        assertEquals(0, store.state.questionPageIndices[twoQuestions.id] ?: 0)
        store.send(draft("My own way"))
        store.send(Action.SendTapped)
        assertEquals(1, store.state.questionPageIndices[twoQuestions.id])
        assertEquals("", store.state.draft)
        assertEquals(RequestBar.Answer(multiple = true, isLast = true, ready = false, isCustom = false), store.state.requestBar)
        store.send(Action.formValueChanged(twoQuestions.id, "q1", OpenCodeFormValue.Strings(listOf("Parking"))))
        store.send(draft("Sam: tree nuts"))
        assertEquals(OpenCodeFormValue.Strings(listOf("Parking", "Sam: tree nuts")), store.state.formAnswers[twoQuestions.id]?.get("q1"))
        store.send(Action.questionPageChanged(twoQuestions.id, 0))
        assertEquals("My own way", store.state.draft)
        store.send(Action.questionPageChanged(twoQuestions.id, 1))
        assertEquals("Sam: tree nuts", store.state.draft)
    }

    @Test fun closeDismissesAtOnceWithTheBarsWords() = sessionTest { h ->
        val sent = LockIsolated(listOf<String>())
        val store = h.store(h.state(forms = listOf(slack))) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                cancelForm = { _, _, _, message -> sent.append(message ?: "nil") },
                familyPendingRequests = { _, _, _ -> empty },
            )
        }
        store.exhaustive = false
        store.send(Action.requestDismissTapped)
        store.receive(requestResponseFinished, "requestResponseFinished")
        assertEquals(listOf("nil"), sent.value)
        assertFalse(store.state.isAnsweringRequest)
    }

    @Test fun aDictatedMessageOnItsWayIsNeverAnAnswer() = sessionTest { h ->
        // The words finish into the draft to be sent just as a request lands:
        // they stay the message, and the request waits for them.
        val initial = h.state(draft = "", answering = false)
            .withDictationPhase(DictationFeature.Phase.Finishing(send = OpenCodeDelivery.Steer))
            .withDictationHold(DictationFeature.Hold(text = "Ship it", isLocked = true))
        val store = h.store(initial)
        store.exhaustive = false
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = listOf(shell), forms = emptyList()))))
        assertFalse(store.state.isAnsweringRequest)
        store.send(Action.dictationStopped(errorMessage = null, recording = initial.dictationRecording))
        assertFalse(store.state.isAnsweringRequest)
        assertTrue(store.state.dictationSendPending)
        store.receive(Action.DeferredSendReady::class)
        // Unsent behind the request (as before), it waits with the message.
        assertTrue(store.state.isAnsweringRequest)
        assertEquals("Ship it", store.state.parkedComposer?.text)
        assertEquals(listOf(shell.id), store.state.permissionRequests.map { it.id })
    }

    // region The parked message is a composer of its own

    private fun file(name: String, id: Long = 1, status: ComposerFileAttachment.Status = ComposerFileAttachment.Status.Uploading(sent = 0)) =
        ComposerFileAttachment(
            ComposerStagedFile(id = UUID(0, id), name = name, mime = "application/pdf", size = 100, url = File("/nonexistent/$name")),
            status = status,
        )

    @Test fun anUploadFinishingWhileParkedLandsAndItsMessageGoesAfter() = sessionTest { h ->
        val initial = h.state { h.state(draft = "Report attached", forms = listOf(slack), answering = false)
            .withPendingFiles(listOf(file("Report.pdf")))
            .withSendAfterUploads(OpenCodeDelivery.Steer)
            .beginAnswering() }
        val sent = LockIsolated(listOf<String>())
        val store = h.store(initial) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, text, _, _, _, _ -> sent.append(text) },
                waitForIdle = { _, _ -> },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
                pendingInputs = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> empty },
            )
        }
        store.exhaustive = false
        store.send(Action.fileUploadFinished(UUID(0, 1), ComposerAttachmentsFeature.Outcome.Success("/tmp/u/Report.pdf")))
        assertEquals(ComposerFileAttachment.Status.Uploaded("/tmp/u/Report.pdf"), store.state.parkedComposer?.files?.firstOrNull()?.status)
        // The message waits while the bar answers.
        assertEquals(OpenCodeDelivery.Steer, store.state.sendAfterUploads)
        assertTrue(sent.value.isEmpty())
        store.send(Action.requestResponseFinished(session.id, slack.id, null))
        assertFalse(store.state.isAnsweringRequest)
        store.receive(Action.DeferredSendReady::class)
        store.receive(Action.PromptAdmitted::class)
        assertEquals(listOf("Report attached"), sent.value)
    }

    @Test fun anUploadFailingWhileParkedFailsThereAndStopsTheWaitingSend() = sessionTest { h ->
        val initial = h.state { h.state(draft = "Report attached", forms = listOf(slack), answering = false)
            .withPendingFiles(listOf(file("Report.pdf")))
            .withSendAfterUploads(OpenCodeDelivery.Steer)
            .beginAnswering() }
        val store = h.store(initial)
        store.exhaustive = false
        store.send(
            Action.fileUploadFinished(UUID(0, 1), ComposerAttachmentsFeature.Outcome.Failure(OpenCodeClientError(message = "HTTP 500", statusCode = 500))),
        )
        assertEquals(true, store.state.parkedComposer?.files?.firstOrNull()?.isFailed)
        assertNull(store.state.sendAfterUploads)
    }

    @Test fun aShareWhileAnsweringWaitsWithTheMessageAndUploads() = sessionTest { h ->
        val staged = ComposerStagedFile(id = UUID(0, 7), name = "Shot.pdf", mime = "application/pdf", size = 10, url = File("/nonexistent/Shot.pdf"))
        val initial = h.state { h.state(draft = "Half a message", forms = listOf(slack)).absorb(SharedComposerDraft(text = "From Safari", files = listOf(staged))) }
        assertEquals("The answer bar stays as it was", "", initial.draft)
        val parkedText = initial.parkedComposer?.text
        assertTrue(parkedText == "Half a message\n\nFrom Safari" || parkedText?.endsWith("From Safari") == true)
        val store = h.store(initial) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                uploadFile = { _, _, _, _ -> flowOf(OpenCodeUploadEvent.Finished(path = "/tmp/u/Shot.pdf")) },
            )
            // Android's staging folder hangs off the app context's cache, so the finished upload's
            // cleanup needs one.
            this[AppContext] = object : ContextWrapper(null) {
                override fun getCacheDir(): File = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
            }
        }
        store.exhaustive = false
        store.send(Action.queuedUploadsStarted)
        store.receive({ it is Action.ComposerAttachments && it.action is ComposerAttachmentsFeature.Action.FileUploadFinished }, "fileUploadFinished")
        assertEquals(ComposerFileAttachment.Status.Uploaded("/tmp/u/Shot.pdf"), store.state.parkedComposer?.files?.firstOrNull()?.status)
        assertTrue(store.state.pendingFiles.isEmpty())
    }

    @Test fun aLocationAskedForTheMessageWaitsWithIt() = sessionTest { h ->
        val initial = h.state { h.state(forms = listOf(slack)).withIsLocating(true) }
        val place = SharedLocation(latitude = 1.0, longitude = 2.0, capturedAt = Instant.ofEpochSecond(0))
        val store = h.store(initial) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                cancelForm = { _, _, _, _ -> },
                familyPendingRequests = { _, _, _ -> empty },
            )
        }
        store.exhaustive = false
        store.send(Action.locationResponse(ComposerAttachmentsFeature.Outcome.Success(place)))
        assertEquals(place, store.state.parkedComposer?.location)
        assertNull(store.state.pendingLocation)
        store.send(Action.requestDismissTapped)
        store.receive(requestResponseFinished, "requestResponseFinished")
        assertEquals("Giving the composer back keeps the location", place, store.state.pendingLocation)
    }

    @Test fun aPreparedPhotoBatchFinishingWhileAnsweringWaitsWithTheMessage() = sessionTest { h ->
        val photos = listOf(1, 2).map { OpenCodeOutgoingAttachment(id = UUID(0, it.toLong()).toString(), data = byteArrayOf(it.toByte()), mime = "image/jpeg") }
        val store = h.store(h.state(draft = "Half a message", forms = listOf(slack)))
        store.send(Action.ComposerAttachments(ComposerAttachmentsFeature.Action.AttachmentLanded(photos[0]))) { s ->
            s.withParkedComposer(checkNotNull(s.parkedComposer).copy(attachments = listOf(photos[0])))
        }
        store.send(Action.ComposerAttachments(ComposerAttachmentsFeature.Action.AttachmentLanded(photos[1]))) { s ->
            s.withParkedComposer(checkNotNull(s.parkedComposer).copy(attachments = photos))
        }
        assertTrue(store.state.pendingAttachments.isEmpty())
        assertEquals(photos, store.state.sessionComposer.attachments)
        store.finish()
    }

    @Test fun anImportFinishingWhileAnsweringWaitsWithTheMessage() = sessionTest { h ->
        val store = h.store(h.state(draft = "Half a message", forms = listOf(slack))) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(uploadFile = { _, _, _, _ -> emptyFlow() })
        }
        store.exhaustive = false
        val photo = OpenCodeOutgoingAttachment(id = UUID(0, 9).toString(), data = byteArrayOf(1), mime = "image/png", name = "Photo.png")
        store.send(Action.attachmentAdded(photo))
        assertTrue("Never part of the answer", store.state.pendingAttachments.isEmpty())
        assertEquals(listOf(photo), store.state.parkedComposer?.attachments)
        val staged = ComposerStagedFile(id = UUID(0, 10), name = "Notes.zip", mime = "application/zip", size = 10, url = File("/nonexistent/Notes.zip"))
        store.send(Action.filesStaged(listOf(staged)))
        assertTrue(store.state.pendingFiles.isEmpty())
        assertEquals(listOf(UUID(0, 10)), store.state.parkedComposer?.files?.map { it.id })
        assertEquals("Saved with the session on a switch", listOf(photo), store.state.sessionComposer.attachments)
    }

    @Test fun wordsKeptAsADraftWhileAnsweringWaitWithTheMessage() = sessionTest { h ->
        val answering = h.state { h.state(draft = "Half a message", forms = listOf(slack)).keepAsDraft(DraftState(text = "Dictated words")) }
        assertEquals("", answering.draft)
        assertEquals(true, answering.parkedComposer?.text?.endsWith("Dictated words"))
    }

    // endregion

    // region Dictation and the answer

    @Test fun cancellingAHeldRecordingLeavesTheChoiceAsItWas() = sessionTest { h ->
        val initial = h.state(forms = listOf(slack))
            .withFormAnswer(slack.id, "q0", OpenCodeFormValue.Str("Bring Slack forward"))
            .withDictationPhase(DictationFeature.Phase.Recording)
            .withDictationHold(DictationFeature.Hold(text = ""))
        val store = h.store(initial)
        store.exhaustive = false
        store.send(Action.dictationUpdated("Actually draft it", recording = initial.dictationRecording))
        assertEquals("A held mic's words stay in its bar", OpenCodeFormValue.Str("Bring Slack forward"), store.state.formAnswers[slack.id]?.get("q0"))
        store.send(Action.micPressCancelled)
        assertEquals(OpenCodeFormValue.Str("Bring Slack forward"), store.state.formAnswers[slack.id]?.get("q0"))
        assertEquals("", store.state.draft)
        assertEquals(RequestBar.Answer(multiple = false, isLast = true, ready = true, isCustom = false), store.state.requestBar)
    }

    @Test fun pagingDuringALockedRecordingKeepsItsWordsOnTheirPage() = sessionTest { h ->
        val initial = h.state(forms = listOf(twoQuestions))
            .withDictationPhase(DictationFeature.Phase.Recording)
            .withDictationHold(DictationFeature.Hold(text = "Native, mostly", isLocked = true))
        val store = h.store(initial) {
            this[DictationClient] = DictationClient(cancel = { _ -> })
        }
        store.exhaustive = false
        store.send(Action.questionPageChanged(twoQuestions.id, 1))
        assertFalse(store.state.hasLiveDictation)
        assertEquals("", store.state.draft)
        store.send(Action.questionPageChanged(twoQuestions.id, 0))
        assertEquals("Native, mostly", store.state.draft)
    }

    @Test fun closeDuringALockedRecordingSendsItsWordsAsTheReason() = sessionTest { h ->
        val initial = h.state(forms = listOf(slack))
            .withDictationPhase(DictationFeature.Phase.Recording)
            .withDictationHold(DictationFeature.Hold(text = "I’ll post it myself", isLocked = true))
        val sent = LockIsolated(listOf<String>())
        val store = h.store(initial) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                cancelForm = { _, _, _, message -> sent.append(message ?: "nil") },
                familyPendingRequests = { _, _, _ -> empty },
            )
        }
        store.exhaustive = false
        store.send(Action.requestDismissTapped)
        assertFalse(store.state.hasLiveDictation)
        store.receive(requestResponseFinished, "requestResponseFinished")
        assertEquals(listOf("I’ll post it myself"), sent.value)
    }

    @Test fun returnSendsTheChosenAnswerWithoutRewritingIt() = sessionTest { h ->
        h.returnKeySends(true)
        val initial = h.state(forms = listOf(slack)).withFormAnswer(slack.id, "q0", OpenCodeFormValue.Str("Bring Slack forward"))
        val sent = LockIsolated<Map<String, OpenCodeFormValue>?>(null)
        val store = h.store(initial, replyClient(sent))
        store.exhaustive = false
        // The text view appends Return's newline, then the view sends Return.
        store.send(draft("\n"))
        assertEquals(OpenCodeFormValue.Str("Bring Slack forward"), store.state.formAnswers[slack.id]?.get("q0"))
        store.send(Action.ReturnKeyTapped)
        store.receive(requestResponseFinished, "requestResponseFinished")
        assertEquals(mapOf("q0" to OpenCodeFormValue.Str("Bring Slack forward")), sent.value)
    }

    @Test fun returnAfterTypingSendsTheTypedAnswer() = sessionTest { h ->
        h.returnKeySends(true)
        val sent = LockIsolated<Map<String, OpenCodeFormValue>?>(null)
        val store = h.store(h.state(forms = listOf(slack)), replyClient(sent))
        store.exhaustive = false
        store.send(draft("Text them"))
        store.send(draft("Text them\n"))
        store.send(Action.ReturnKeyTapped)
        store.receive(requestResponseFinished, "requestResponseFinished")
        assertEquals(mapOf("q0" to OpenCodeFormValue.Str("Text them")), sent.value)
    }

    @Test fun returnKeyWhenOffKeepsNewlineInAnswerBarAndCommandReturnSendsAnswer() = sessionTest { h ->
        assertFalse(h.sharedStore.reference(SharedKeys.returnKeySends).value)
        val sent = LockIsolated<Map<String, OpenCodeFormValue>?>(null)
        val store = h.store(h.state(forms = listOf(slack)), replyClient(sent))
        store.exhaustive = false
        store.send(draft("Line one"))
        store.send(draft("Line one\n"))
        assertEquals(OpenCodeFormValue.Str("Line one\n"), store.state.formAnswers[slack.id]?.get("q0"))
        store.send(Action.ReturnKeyTapped)
        assertNull(sent.value)
        store.send(draft("Line one\nLine two"))
        store.send(Action.CommandReturnTapped)
        store.receive(requestResponseFinished, "requestResponseFinished")
        assertEquals(mapOf("q0" to OpenCodeFormValue.Str("Line one\nLine two")), sent.value)
    }

    @Test fun shiftReturnThenTextThenReturnSubmitsMultiLineAnswer() = sessionTest { h ->
        h.returnKeySends(true)
        val initial = h.state(forms = listOf(slack)).withFormAnswer(slack.id, "q0", OpenCodeFormValue.Str("Bring Slack forward"))
        val sent = LockIsolated<Map<String, OpenCodeFormValue>?>(null)
        val store = h.store(initial, replyClient(sent))
        store.exhaustive = false

        store.send(draft("First line"))
        // ⇧↩ records the non-send newline draft before the binding updates.
        store.send(Action.NonSendNewlineInserted("First line\n"))
        store.send(draft("First line\n"))
        assertEquals(OpenCodeFormValue.Str("First line\n"), store.state.formAnswers[slack.id]?.get("q0"))
        assertEquals("First line\n", store.state.answerBarBaseline)
        // Even if Return were dispatched on that newline, it does not submit.
        store.send(Action.ReturnKeyTapped)
        assertNull(sent.value)

        // Typing the second line and pressing Return submits the full multi-line answer.
        store.send(draft("First line\nSecond line"))
        store.send(draft("First line\nSecond line\n"))
        store.send(Action.ReturnKeyTapped)
        store.receive(requestResponseFinished, "requestResponseFinished")
        assertEquals(mapOf("q0" to OpenCodeFormValue.Str("First line\nSecond line")), sent.value)
    }

    @Test fun shiftReturnThenBackspaceThenChoosingChipAndReturnKeepsTheChoice() = sessionTest { h ->
        h.returnKeySends(true)
        val sent = LockIsolated<Map<String, OpenCodeFormValue>?>(null)
        val store = h.store(h.state(forms = listOf(slack)), replyClient(sent))
        store.exhaustive = false

        // ⇧↩ inserts a newline, then Backspace removes it: baseline and formAnswers clear.
        store.send(Action.NonSendNewlineInserted("\n"))
        store.send(draft("\n"))
        assertEquals(OpenCodeFormValue.Str("\n"), store.state.formAnswers[slack.id]?.get("q0"))
        store.send(draft(""))
        assertEquals(OpenCodeFormValue.Str(""), store.state.formAnswers[slack.id]?.get("q0"))
        assertEquals("", store.state.answerBarBaseline)

        // Choosing a chip and pressing Return keeps the chosen chip.
        store.send(Action.formValueChanged(slack.id, "q0", OpenCodeFormValue.Str("Bring Slack forward")))
        store.send(draft("\n"))
        assertEquals(OpenCodeFormValue.Str("Bring Slack forward"), store.state.formAnswers[slack.id]?.get("q0"))
        store.send(Action.ReturnKeyTapped)
        store.receive(requestResponseFinished, "requestResponseFinished")
        assertEquals(mapOf("q0" to OpenCodeFormValue.Str("Bring Slack forward")), sent.value)
    }

    @Test fun commandReturnAnswersRequestWhileParkedUploadIsLoading() = sessionTest { h ->
        val stagedFile = ComposerStagedFile(id = UUID(0, 1), name = "clip.mov", mime = "video/quicktime", size = 100, url = File("/nonexistent/clip.mov"))
        val initial = h.state { h.state(draft = "Message waiting on upload", answering = false)
            .withPendingFiles(listOf(ComposerFileAttachment(stagedFile)))
            .withSendAfterUploads(OpenCodeDelivery.Steer) }
        val sent = LockIsolated<Map<String, OpenCodeFormValue>?>(null)
        val store = h.store(initial, replyClient(sent))
        store.exhaustive = false

        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = listOf(slack)))))
        assertTrue(store.state.isAnsweringRequest)
        store.send(Action.formValueChanged(slack.id, "q0", OpenCodeFormValue.Str("Bring Slack forward")))
        store.send(Action.CommandReturnTapped)
        store.receive(requestResponseFinished, "requestResponseFinished")
        assertEquals(mapOf("q0" to OpenCodeFormValue.Str("Bring Slack forward")), sent.value)
    }

    @Test fun anAnswersRecordingNeverFollowsToAnotherSession() = sessionTest { h ->
        val answering = h.state(forms = listOf(slack))
            .withDictationPhase(DictationFeature.Phase.Recording)
            .withDictationHold(DictationFeature.Hold(text = "My own answer", isLocked = true))
            .withDictationPress(4)
        val carried = answering.carriedDictation
        assertEquals(true, carried?.isAnswer)
        val next = h.state {
            SessionFeature.State.session(
                server = answering.server,
                session = OpenCodeSession(id = "ses_other", serverTitle = "Other", projectID = "proj", directory = "/tmp/other", updatedAt = Instant.EPOCH),
                cachedItems = emptyList(),
            ).withDraft("Typed there").adoptDictation(carried!!)
        }
        assertEquals(DictationFeature.Phase.Idle, next.dictationPhase)
        assertNull(next.dictationHold)
        assertEquals("Typed there", next.draft)
        assertEquals(DictationFeature.Discarded(recording = answering.dictationRecording, press = 4), next.discardedDictation)
    }

    @Test fun aMessageRecordingCarriedOntoAnAnsweringScreenEndsTheAnswerFirst() = sessionTest { h ->
        val recording = h.state(draft = "", answering = false)
            .withDictationPhase(DictationFeature.Phase.Recording)
            .withDictationHold(DictationFeature.Hold(text = "Ship it", isLocked = true))
        var next = h.state(draft = "Typed there", forms = listOf(slack))
        assertTrue(next.isAnsweringRequest)
        next = h.state { next.adoptDictation(recording.carriedDictation!!) }
        // The words are a message: the request waits for them.
        assertFalse(next.isAnsweringRequest)
        assertEquals(true, next.dictationHold?.isLocked)
        assertFalse(next.wantsRequestDock)
    }

    @Test fun aMessageFinalizingToSendGoesAsAMessageBeforeTheRequestDocks() = sessionTest { h ->
        val initial = h.state(draft = "", answering = false)
            .withSendingDictation(
                DictationFeature.Sending(recording = 2, draft = DraftState(text = "Ship it"), delivery = OpenCodeDelivery.Steer, sessionID = session.id),
            )
            .withDictationRecording(3)
        val sent = LockIsolated(listOf<String>())
        val store = h.store(initial) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, text, _, _, _, _ -> sent.append(text) },
                waitForIdle = { _, _ -> },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
                pendingInputs = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> empty },
            )
        }
        store.exhaustive = false
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = listOf(shell), forms = emptyList()))))
        assertFalse("The request waits for the message", store.state.isAnsweringRequest)
        store.send(Action.dictationStopped(errorMessage = null, recording = 2))
        store.receive(Action.PromptAdmitted::class)
        assertEquals(listOf("Ship it"), sent.value)
        assertTrue(store.state.isAnsweringRequest)
        assertEquals("", store.state.parkedComposer?.text)
    }

    @Test fun aDictatedAnswerWhoseRequestWentSendsNothing() = sessionTest { h ->
        // The bar's ↑ ends in a send bound to the request it answered…
        val answering = h.state { h.state(draft = "Half a message", forms = listOf(slack), answering = false).beginAnswering() }
            .withDictationPhase(DictationFeature.Phase.Finishing(send = OpenCodeDelivery.Steer))
            .withDictationHold(DictationFeature.Hold(text = "Draft it", isLocked = true))
        val bound = h.store(answering) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                replyForm = { _, _, _, _ -> },
                familyPendingRequests = { _, _, _ -> empty },
            )
        }
        bound.exhaustive = false
        bound.send(Action.dictationStopped(errorMessage = null, recording = answering.dictationRecording))
        bound.receive(Action.requestBarSent(slack.id))
        bound.receive(requestResponseFinished, "requestResponseFinished")

        // …so landing after that request went (answered elsewhere, the message
        // back in the composer), or once another took its place, sends nothing.
        val gone = h.state(draft = "Half a message", answering = false).withFormRequests(emptyList())
        val store = h.store(gone)
        store.send(Action.requestBarSent(slack.id))
        store.finish()
        val other = h.state(permissions = listOf(shell)).withDraft("Not for this one")
        val replaced = h.store(other)
        replaced.send(Action.requestBarSent(slack.id))
        replaced.finish()
    }

    @Test fun restoringASnapshotAnswersFromTheFirstFrame() = sessionTest { h ->
        val saved = h.state { SessionFeature.SwitchSnapshot.from(h.state(forms = listOf(slack))) }
        assertNotNull(saved)
        var screen = h.state { saved!!.restore(h.state(draft = "Typed before", answering = false)) }
        assertTrue(screen.isAnsweringRequest)
        assertEquals("Typed before", screen.parkedComposer?.text)
        assertNotNull(screen.requestBar)
        // Catch Up giving its composer back lands with the message.
        screen = h.state { screen.restoreDraft(DraftState(text = "From Catch Up")) }
        assertEquals("From Catch Up", screen.parkedComposer?.text)
        assertEquals("", screen.draft)
        // Without a request, it's the composer.
        val plain = h.state { h.state(draft = "", answering = false).restoreDraft(DraftState(text = "From Catch Up")) }
        assertEquals("From Catch Up", plain.draft)
    }

    // endregion

    // region The fold

    @Test fun aFoldedQuestionStaysFoldedAcrossASwitchUntilItSettles() = sessionTest { h ->
        val twoQuestions = twoQuestions
        val left = h.state { h.state(forms = listOf(twoQuestions)).updateRequestDock { it.copy(collapsedRequestIDs = setOf(twoQuestions.id)) } }
        val saved = h.state { requireNotNull(SessionFeature.SwitchSnapshot.from(left)) }
        assertEquals(setOf(twoQuestions.id), saved.collapsedRequests)
        var screen = h.state { saved.restore(h.state(answering = false)) }
        assertEquals(setOf(twoQuestions.id), screen.requestDock.collapsedRequestIDs)

        // Answered elsewhere, its fold goes with it, in the snapshot and on screen.
        val pruned = saved.pruneRequests { it.id == twoQuestions.id }
        assertTrue(pruned.collapsedRequests.isEmpty())
        screen = h.state { screen.pruneRequests(removing = { id, _ -> id == twoQuestions.id }) }
        assertTrue(screen.requestDock.collapsedRequestIDs.isEmpty())
    }

    @Test fun aFailedAnswerTurnsSendIntoRetryAndTheBarSendsItAgain() = sessionTest { h ->
        val attempts = LockIsolated(0)
        val slack = slack
        val store = h.store(h.state(forms = listOf(slack))) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                replyForm = { _, _, _, _ ->
                    val attempt = attempts.withValue { Pair(it + 1, it + 1) }
                    if (attempt == 1) throw OpenCodeClientError(message = OpenCodeClientError.UNREACHABLE_MESSAGE)
                },
                familyPendingRequests = { _, _, _ ->
                    OpenCodePendingRequests(permissions = emptyList(), forms = if (attempts.value < 2) listOf(slack) else emptyList())
                },
            )
        }
        store.exhaustive = false
        store.send(Action.formValueChanged(slack.id, "q0", OpenCodeFormValue.Str("Draft it instead")))
        store.send(Action.requestCollapseChanged(slack.id, collapsed = true))
        store.send(Action.SendTapped)
        store.receive(requestResponseFinished, "requestResponseFinished")
        assertTrue(store.state.requestDock.dockReplyFailed)
        assertEquals(OpenCodeClientError.UNREACHABLE_MESSAGE, store.state.requestErrors[slack.id])
        // Still docked, unfolded to show the error, the answer kept and the bar ready.
        assertTrue(store.state.isAnsweringRequest)
        assertTrue(store.state.requestDock.collapsedRequestIDs.isEmpty())
        assertEquals(true, store.state.requestBar?.isReady)

        store.send(Action.SendTapped)
        assertFalse(store.state.requestDock.dockReplyFailed)
        store.receive(requestResponseFinished, "requestResponseFinished")
        assertEquals(2, attempts.value)
        assertTrue(store.state.formRequests.isEmpty())
        assertTrue(store.state.requestDock.collapsedRequestIDs.isEmpty())
        assertFalse(store.state.isAnsweringRequest)
    }

    @Test fun aFailedDismissalReadsAsOneAndLeavesSendAlone() = sessionTest { h ->
        val slack = slack
        val store = h.store(h.state(forms = listOf(slack))) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                cancelForm = { _, _, _, _ -> throw OpenCodeClientError(message = OpenCodeClientError.UNREACHABLE_MESSAGE) },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = listOf(slack)) },
            )
        }
        store.exhaustive = false
        store.send(Action.formValueChanged(slack.id, "q0", OpenCodeFormValue.Str("Draft it instead")))
        store.send(Action.requestCollapseChanged(slack.id, collapsed = true))
        store.send(Action.requestDismissTapped)
        store.receive(requestResponseFinished, "requestResponseFinished")
        assertEquals(mapOf(slack.id to RequestDockFeature.State.RequestFailure.Dismissal), store.state.requestDock.requestFailures)
        assertFalse(store.state.requestDock.dockReplyFailed)
        assertEquals("Couldn’t dismiss request", store.state.requestDock.replyFailureTitle(slack.id, isForm = true))
        // Folded, it opens to show the line.
        assertTrue(store.state.requestDock.collapsedRequestIDs.isEmpty())
        store.receive(Action.PendingRequestsResponse::class)
        assertEquals(listOf(slack.id), store.state.formRequests.map { it.id })
    }

    @Test fun anAnswerTheServerHadAlreadySettledStaysUntilDismissedLocally() = sessionTest { h ->
        val slack = slack
        val sends = LockIsolated(0)
        val dismissals = LockIsolated(0)
        val initial = h.state { h.state(forms = listOf(slack)).copy(familySessions = listOf(session)) }
        val store = h.store(initial) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                replyForm = { _, _, _, _ ->
                    sends.withValue { Pair(it + 1, Unit) }
                    throw OpenCodeClientError(message = "Already answered elsewhere", statusCode = 409, requestSettled = true)
                },
                cancelForm = { _, _, _, _ -> dismissals.withValue { Pair(it + 1, Unit) } },
                familyPendingRequests = { _, _, _ -> empty },
            )
        }
        store.exhaustive = false
        store.send(Action.formValueChanged(slack.id, "q0", OpenCodeFormValue.Str("Draft it instead")))
        store.send(Action.SendTapped)
        store.receive(requestResponseFinished, "requestResponseFinished")
        assertEquals(mapOf(slack.id to RequestDockFeature.State.RequestFailure.Settled), store.state.requestDock.requestFailures)
        assertFalse(store.state.requestDock.dockReplyFailed)
        assertEquals("Couldn’t send answer", store.state.requestDock.replyFailureTitle(slack.id, isForm = true))
        // The refresh no longer lists it, but it stays with its error and words.
        store.receive(Action.PendingRequestsResponse::class)
        assertEquals(listOf(slack.id), store.state.formRequests.map { it.id })
        assertEquals("Already answered elsewhere", store.state.requestErrors[slack.id])
        assertEquals(OpenCodeFormValue.Str("Draft it instead"), store.state.formAnswers[slack.id]?.get("q0"))
        // An edit keeps the error, and nothing sends again.
        store.send(Action.formValueChanged(slack.id, "q0", OpenCodeFormValue.Str("Bring Slack forward")))
        assertEquals("Already answered elsewhere", store.state.requestErrors[slack.id])
        store.send(Action.SendTapped)
        assertEquals(1, sends.value)
        // × clears it here, with no DELETE.
        store.send(Action.requestDismissTapped)
        assertTrue(store.state.formRequests.isEmpty())
        assertTrue(store.state.requestDock.requestFailures.isEmpty())
        assertEquals(0, dismissals.value)
    }

    @Test fun aFoldedFormsValidationErrorUnfoldsIt() = sessionTest { h ->
        val required = form("frm_required", fields = """[{"key":"answer","type":"string","title":"Name","required":true}]""")
        val store = h.store(h.state(forms = listOf(required)))
        store.exhaustive = false
        store.send(Action.requestCollapseChanged(required.id, collapsed = true))
        store.send(Action.formSubmitTapped(required.id))
        assertNotNull(store.state.requestErrors[required.id])
        assertTrue(store.state.requestDock.collapsedRequestIDs.isEmpty())
    }

    @Test fun theKeyboardsSwipeYieldsToTheDockedPanel() {
        assertTrue(SessionComposerEnv.composerSwipeYields(requestPanelTracking = true))
        assertTrue(SessionComposerEnv.composerSwipeYields(requestPanelBottom = 600f, start = Offset(100f, 500f)))
        assertFalse(SessionComposerEnv.composerSwipeYields(requestPanelBottom = 600f, start = Offset(100f, 700f)))
        assertFalse(SessionComposerEnv.composerSwipeYields(start = Offset(100f, 500f)))
    }

    // endregion
}
