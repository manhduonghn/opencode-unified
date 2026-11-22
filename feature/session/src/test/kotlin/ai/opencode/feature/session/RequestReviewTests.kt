package ai.opencode.feature.session

import ai.opencode.feature.composer.RequestDockAnswer
import ai.opencode.feature.composer.RequestDockFeature
import ai.opencode.feature.composer.fitsDock
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodePermissionReply
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.PromptContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class RequestReviewTests {
    private val session = OpenCodeSession(id = "ses_review", serverTitle = "Review", projectID = "proj", directory = "/tmp/review", updatedAt = Instant.EPOCH)
    private val json = Json { ignoreUnknownKeys = true }

    private fun decodeForm(text: String): OpenCodeFormRequest = json.decodeFromString(OpenCodeFormRequest.serializer(), text)

    /** A question the agent asked, as current V2 sends it: a form. */
    private fun question(id: String = "frm_review", sessionID: String = "ses_review", metadataExtra: String = ""): OpenCodeFormRequest =
        decodeForm(
            """{"id":"$id","sessionID":"$sessionID","title":"Questions","metadata":{"kind":"question"$metadataExtra},"fields":[{"key":"q0","type":"string","title":"Approach","description":"Which approach?","options":[{"value":"Native","label":"Native"}],"custom":true}]}""",
        )

    private val request: OpenCodeFormRequest get() = question()

    private fun Harness.reviewState(): SessionFeature.State = state {
        SessionFeature.State.session(
            server = OpenCodeServer(url = "http://127.0.0.1:4105", username = "", password = ""),
            session = session,
            cachedItems = emptyList(),
        ).updateRequestDock {
            it.copy(
                formRequests = listOf(request),
                formAnswers = mapOf(request.id to mapOf("q0" to OpenCodeFormValue.Str("Native"))),
                selectedRequestID = request.id,
            )
        }.copy(activePicker = SessionFeature.State.ActivePicker.requests)
    }

    private fun Harness.client(transform: OpenCodeClient.() -> OpenCodeClient): ai.opencode.core.dependencies.DependencyValues.Builder.() -> Unit = {
        this[OpenCodeClient] = this[OpenCodeClient].transform()
    }

    private val empty = OpenCodePendingRequests(permissions = emptyList(), forms = emptyList())

    @Test fun successfulReplyCannotBeResubmittedThroughStalePoll() = sessionTest { h ->
        val pending = OpenCodePendingRequests(permissions = emptyList(), forms = listOf(request))
        val store = h.store(h.reviewState(), h.client { copy(familyPendingRequests = { _, _, _ -> pending }) })
        store.exhaustive = false
        store.send(Action.requestResponseFinished(session.id, request.id, null))
        assertNull(store.state.activePicker)
        assertNull(store.state.selectedRequestID)
        store.receive(Action.PendingRequestsResponse::class)
        assertTrue(store.state.formRequests.isEmpty())
        assertNull(store.state.activePicker)
        assertNull(store.state.selectedRequestID)
        // An unimplemented network dependency fails the test if a second reply is attempted.
        store.send(Action.formSubmitTapped(request.id))
        store.finish()
    }

    @Test fun successfulReplyDismissesReviewWhileOtherRequestsRemainPending() = sessionTest { h ->
        val permission = OpenCodePermissionRequest(id = "per_pending", sessionID = session.id, action = "shell", resources = listOf("git status"), save = null)
        val initial = h.reviewState().withPermissionRequests(listOf(permission))
        val pending = OpenCodePendingRequests(permissions = listOf(permission), forms = listOf(request))
        val store = h.store(initial, h.client { copy(familyPendingRequests = { _, _, _ -> pending }) })
        store.exhaustive = false

        store.send(Action.requestResponseFinished(session.id, request.id, null))
        assertNull(store.state.activePicker)
        assertNull(store.state.selectedRequestID)
        assertEquals(listOf(permission.id), store.state.reviewRequestIDs)
        store.receive(Action.PendingRequestsResponse::class)
        assertNull(store.state.activePicker)
        assertNull(store.state.selectedRequestID)
        assertEquals(listOf(permission.id), store.state.reviewRequestIDs)
        // The dock moves on to what's still waiting.
        assertEquals(permission.id, store.state.dockRequestID)
    }

    @Test fun successfulReplyDoesNotDismissUnrelatedPicker() = sessionTest { h ->
        val initial = h.reviewState().copy(activePicker = SessionFeature.State.ActivePicker.model)
        val store = h.store(initial, h.client { copy(familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) }) })
        store.exhaustive = false

        store.send(Action.requestResponseFinished(session.id, request.id, null))
        assertEquals(SessionFeature.State.ActivePicker.model, store.state.activePicker)
        assertNull(store.state.selectedRequestID)
        store.receive(Action.PendingRequestsResponse::class)
        assertEquals(SessionFeature.State.ActivePicker.model, store.state.activePicker)
    }

    @Test fun expiredRequestLeavesTheDockWithoutClaimingSuccess() = sessionTest { h ->
        val store = h.store(h.reviewState())
        store.exhaustive = false
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = emptyList()))))
        assertNull(store.state.selectedRequestID)
        assertNull(store.state.dockRequestID)
        assertNull(store.state.activePicker)
        assertTrue(store.state.completedRequestIDs.isEmpty())
        store.send(Action.formSubmitTapped(request.id))
        store.finish()
    }

    @Test fun newRequestDocksWithoutOpeningASheet() = sessionTest { h ->
        val initial = h.reviewState().withFormRequests(emptyList()).withSelectedRequestID(null).copy(activePicker = null)
        val store = h.store(initial)
        store.exhaustive = false
        val snapshot = OpenCodePendingRequests(permissions = emptyList(), forms = listOf(request))
        store.send(Action.PendingRequestsResponse(session.id, Result.success(snapshot)))
        assertNull(store.state.activePicker)
        assertEquals(request.id, store.state.dockRequestID)
        store.send(Action.PendingRequestsResponse(session.id, Result.success(snapshot)))
        assertNull(store.state.activePicker)
    }

    @Test fun settledRetryFailureIsVisibleEvenWithoutPendingRequests() = sessionTest { h ->
        val initial = h.reviewState().withFormRequests(emptyList()).withSelectedRequestID(null).copy(activePicker = null)
            .withRequestLoadAttempt(RequestDockFeature.State.LoadAttempt.Reconnect)
        val store = h.store(initial)
        store.exhaustive = false
        store.send(Action.PendingRequestsResponse(session.id, Result.failure(OpenCodeClientError(message = "Could not load requests"))))
        assertEquals("Could not load requests", store.state.requestLoadError)
    }

    @Test fun modernQuestionFormDocksAndKeepsItsToolIdentity() = sessionTest { h ->
        val form = question("frm_question", metadataExtra = ""","tool":{"messageID":"msg_1","id":"call_1"}""")
        val initial = h.reviewState().withFormRequests(emptyList()).withSelectedRequestID(null).copy(activePicker = null)
        val store = h.store(initial)
        store.exhaustive = false
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = listOf(form)))))
        assertTrue(form.isQuestion)
        assertNull(store.state.activePicker)
        assertEquals(form.id, store.state.dockRequestID)
        assertEquals(listOf(form.id), store.state.reviewRequestIDs)
    }

    @Test fun failureRetainsAnswerAndIsNotClearedByImmediateRefresh() = sessionTest { h ->
        val pending = OpenCodePendingRequests(permissions = emptyList(), forms = listOf(request))
        val store = h.store(h.reviewState(), h.client { copy(familyPendingRequests = { _, _, _ -> pending }) })
        store.exhaustive = false
        store.send(Action.requestResponseFinished(session.id, request.id, OpenCodeClientError(message = "Offline")))
        assertEquals(SessionFeature.State.ActivePicker.requests, store.state.activePicker)
        assertEquals(request.id, store.state.selectedRequestID)
        store.receive(Action.PendingRequestsResponse::class)
        assertEquals(SessionFeature.State.ActivePicker.requests, store.state.activePicker)
        assertEquals(request.id, store.state.selectedRequestID)
        assertEquals(mapOf("q0" to OpenCodeFormValue.Str("Native")), store.state.formAnswers[request.id])
        assertEquals("Offline", store.state.requestErrors[request.id])
        assertEquals(mapOf(request.id to RequestDockFeature.State.RequestFailure.Reply), store.state.requestDock.requestFailures)
        assertTrue(store.state.completedRequestIDs.isEmpty())
    }

    @Test fun settledReplyAndStalePollPruneOnlyRequestsNoLongerPending() = sessionTest { h ->
        val other = question("frm_other")
        val base = h.reviewState()
        val initial = base.updateRequestDock {
            it.copy(
                formRequests = listOf(request, other),
                formAnswers = it.formAnswers + (other.id to mapOf("q0" to OpenCodeFormValue.Str("Keep me"))),
                questionPageIndices = mapOf(request.id to 0, other.id to 0),
                requestErrors = it.requestErrors + (request.id to "Offline"),
            )
        }
        val store = h.store(initial, h.client { copy(familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) }) })
        store.exhaustive = false

        store.send(Action.requestResponseFinished(session.id, request.id, null))
        assertEquals(mapOf(other.id to mapOf("q0" to OpenCodeFormValue.Str("Keep me"))), store.state.formAnswers)
        assertEquals(mapOf(other.id to 0), store.state.questionPageIndices)
        assertTrue(store.state.requestErrors.isEmpty())

        store.receive(Action.PendingRequestsResponse::class)
        assertTrue(store.state.formRequests.isEmpty())
        assertTrue(store.state.formAnswers.isEmpty())
        assertTrue(store.state.questionPageIndices.isEmpty())
    }

    @Test fun inFlightAnswersCannotBeEditedOrSubmittedAgain() = sessionTest { h ->
        val initial = h.reviewState().withRespondingRequestIDs(setOf(request.id))
        val store = h.store(initial)
        store.send(Action.formValueChanged(request.id, "q0", OpenCodeFormValue.Str("Changed")))
        store.send(Action.formSubmitTapped(request.id))
        store.send(Action.formCancelTapped(request.id))
        store.send(Action.formCancelTapped(request.id, message = "Not now"))
        store.finish()
    }

    @Test fun alwaysAllowRequiresServerSuppliedSavePatterns() = sessionTest { h ->
        val permission = OpenCodePermissionRequest(id = "per_1", sessionID = session.id, action = "shell", resources = listOf("git status"), save = null)
        val initial = h.reviewState().withPermissionRequests(listOf(permission))
        val store = h.store(initial)
        store.send(Action.permissionReplyTapped(permission.id, OpenCodePermissionReply.Always))
        store.finish()
    }

    @Test fun keyboardSendCannotTurnAPendingAnswerIntoChatInput() = sessionTest { h ->
        val initial = h.reviewState().withDraft("Yes, you may")
        val store = h.store(initial)
        store.send(Action.SendTapped)
        store.finish()
    }

    @Test fun globalFormCompletionIsRetiredLikeSessionForms() = sessionTest { h ->
        val form = decodeForm("""{"id":"frm_global","sessionID":"global","title":"Confirm","fields":[{"key":"confirm","type":"boolean"}]}""")
        val initial = h.reviewState().withFormRequests(listOf(form)).withSelectedRequestID(form.id)
        val store = h.store(initial, h.client { copy(familyPendingRequests = { _, _, _ -> empty }) })
        store.exhaustive = false
        store.send(Action.requestResponseFinished("global", form.id, null))
        assertTrue(store.state.formRequests.isEmpty())
        assertTrue(form.id in store.state.completedRequestIDs)
        assertNull(store.state.selectedRequestID)
        assertNull(store.state.activePicker)
        store.receive(Action.PendingRequestsResponse::class)
    }

    @Test fun globalMcpFormSubmitsAndDismissesInSessionDirectoryAndLabelsServer() = sessionTest { h ->
        val mcpForm = decodeForm(
            """{"id":"frm_mcp","sessionID":"global","title":"context7 is requesting input","metadata":{"kind":"mcp-elicitation","server":"context7","message":"How would you like to continue?"},"fields":[{"key":"mode","type":"string","title":"How would you like to continue?","options":[{"value":"anon","label":"Continue anonymously with smaller limits"}]}]}""",
        )
        val plainGlobal = decodeForm("""{"id":"frm_signin","sessionID":"global","title":"Sign in","fields":[{"key":"token","type":"string","title":"Token"}]}""")
        val child = OpenCodeSession(id = "ses_child", serverTitle = "UI checks", projectID = "proj", directory = "/tmp/review", updatedAt = Instant.EPOCH, parentID = session.id)
        val family = listOf(session, child)

        assertEquals(
            "context7 is requesting input",
            RequestDockAnswer.caption(mcpForm.title, sessionID = mcpForm.sessionID, mcpServer = mcpForm.mcpServer, currentSessionID = session.id, familySessions = family),
        )
        assertEquals(
            "context7 · Sign in",
            RequestDockAnswer.caption("Sign in", sessionID = mcpForm.sessionID, mcpServer = mcpForm.mcpServer, currentSessionID = session.id, familySessions = family),
        )
        assertEquals(
            "context7",
            RequestDockAnswer.caption("", sessionID = mcpForm.sessionID, mcpServer = mcpForm.mcpServer, currentSessionID = session.id, familySessions = family),
        )
        assertEquals(
            "Sign in",
            RequestDockAnswer.caption(plainGlobal.title, sessionID = plainGlobal.sessionID, mcpServer = plainGlobal.mcpServer, currentSessionID = session.id, familySessions = family),
        )
        assertEquals("context7", RequestDockAnswer.origin(mcpForm, currentSessionID = session.id, familySessions = family))
        assertNull(RequestDockAnswer.origin(plainGlobal, currentSessionID = session.id, familySessions = family))

        val initial = h.dockState().updateRequestDock {
            it.copy(
                formRequests = listOf(mcpForm, plainGlobal),
                formAnswers = mapOf(
                    mcpForm.id to mapOf("mode" to OpenCodeFormValue.Str("anon")),
                    plainGlobal.id to mapOf("token" to OpenCodeFormValue.Str("secret")),
                ),
            )
        }
        assertTrue(initial.waitingSessionIDs.isEmpty())
        assertNull(h.state { initial.transcriptSubagents["global"] })

        val recordedCalls = LockIsolated(listOf<String>())
        val firstRefreshStarted = CompletableDeferred<Unit>()
        val firstRefreshCancelled = CompletableDeferred<Unit>()
        val refreshAttempt = LockIsolated(0)

        val store = h.store(
            initial,
            h.client {
                copy(
                    replyForm = { _, sessionID, formID, answer ->
                        val directory = PromptContext.formLocationDirectory.current() ?: "nil"
                        recordedCalls.append("reply:$sessionID:$formID:$directory")
                        assertEquals(mapOf("mode" to OpenCodeFormValue.Str("anon")), answer)
                    },
                    cancelForm = { _, sessionID, formID, message ->
                        val directory = PromptContext.formLocationDirectory.current() ?: "nil"
                        recordedCalls.append("cancel:$sessionID:$formID:$directory:${message ?: "nil"}")
                    },
                    familyPendingRequests = { _, _, directory ->
                        assertEquals("/tmp/review", directory)
                        val count = refreshAttempt.increment()
                        if (count == 1) {
                            firstRefreshStarted.complete(Unit)
                            try {
                                delay(60.seconds)
                            } finally {
                                firstRefreshCancelled.complete(Unit)
                            }
                        }
                        empty
                    },
                )
            },
        )

        store.send(Action.formSubmitTapped(mcpForm.id)) { it.withRespondingRequestIDs(setOf(mcpForm.id)) }
        store.receive(Action.requestResponseFinished("global", mcpForm.id, null)) {
            it.withRespondingRequestIDs(emptySet())
                .withCompletedRequestIDs(setOf(mcpForm.id))
                .withFormRequests(listOf(plainGlobal))
                .withFormAnswers(mapOf(plainGlobal.id to mapOf("token" to OpenCodeFormValue.Str("secret"))))
                .beginAnswering()
        }
        firstRefreshStarted.await()

        store.send(Action.formCancelTapped(plainGlobal.id, message = " Skip token ")) { it.withRespondingRequestIDs(setOf(plainGlobal.id)) }
        store.receive(Action.requestResponseFinished("global", plainGlobal.id, null, dismissal = true)) {
            it.withRespondingRequestIDs(emptySet())
                .withCompletedRequestIDs(setOf(mcpForm.id, plainGlobal.id))
                .withFormRequests(emptyList())
                .withFormAnswers(emptyMap())
                .endAnswering()
        }
        firstRefreshCancelled.await()
        store.receive(Action.PendingRequestsResponse(session.id, Result.success(empty))) { it.withRequestArrivalsAreLive(true) }

        assertEquals(
            listOf(
                "reply:global:frm_mcp:/tmp/review",
                "cancel:global:frm_signin:/tmp/review:Skip token",
            ),
            recordedCalls.value,
        )
        store.finish()
    }

    // region The dock

    /** A docked request, as it arrives: nothing selected, no sheet. */
    private fun Harness.dockState(): SessionFeature.State = reviewState().withSelectedRequestID(null).copy(activePicker = null)

    private fun Harness.shellDockState(): SessionFeature.State =
        dockState().withFormRequests(emptyList()).withFormAnswers(emptyMap()).withPermissionRequests(listOf(shell))

    private val threeQuestions: OpenCodeFormRequest
        get() = decodeForm(
            """
            {"id":"frm_three","sessionID":"ses_review","title":"Questions","metadata":{"kind":"question"},"fields":[
              {"key":"q0","type":"string","title":"Send in Slack","description":"May I bring Slack forward?","options":[{"value":"Bring Slack forward","label":"Bring Slack forward"}],"custom":true},
              {"key":"q1","type":"multiselect","title":"Menu notes","description":"Which notes?","options":[{"value":"Parking","label":"Parking"},{"value":"Allergies","label":"Allergies"}],"custom":true},
              {"key":"q2","type":"string","title":"ETA","description":"What ETA?","options":[],"custom":true}
            ]}
            """.trimIndent(),
        )

    private val shell: OpenCodePermissionRequest
        get() = OpenCodePermissionRequest(id = "per_shell", sessionID = session.id, action = "shell", resources = listOf("rm -rf .lab-output"), save = listOf("rm *"))

    private val OpenCodePermissionReply.rawValue: String get() = name.lowercase()

    @Test fun anAnswerSubmitsTheDockedQuestion() = sessionTest { h ->
        val store = h.store(
            h.dockState(),
            h.client {
                copy(
                    replyForm = { _, sessionID, formID, answer ->
                        assertEquals("ses_review", sessionID)
                        assertEquals("frm_review", formID)
                        assertEquals(mapOf("q0" to OpenCodeFormValue.Str("Native")), answer)
                    },
                    familyPendingRequests = { _, _, _ -> empty },
                )
            },
        )
        assertEquals(request.id, store.state.dockRequestID)
        store.send(Action.formSubmitTapped(request.id)) { it.withRespondingRequestIDs(setOf(request.id)) }
        store.receive(Action.requestResponseFinished(session.id, request.id, null)) {
            it.withRespondingRequestIDs(emptySet())
                .withCompletedRequestIDs(setOf(request.id))
                .withFormRequests(emptyList())
                .withFormAnswers(emptyMap())
        }
        store.receive(Action.PendingRequestsResponse(session.id, Result.success(empty))) { it.withRequestArrivalsAreLive(true) }
        assertNull(store.state.dockRequestID)
        assertNull(store.state.activePicker)
        store.finish()
    }

    @Test fun denyingWithAReasonSendsItSoTheAgentCarriesOn() = sessionTest { h ->
        val sent = LockIsolated(listOf<String>())
        val store = h.store(
            h.shellDockState(),
            h.client {
                copy(
                    replyPermission = { _, sessionID, requestID, reply, message ->
                        assertEquals("ses_review", sessionID)
                        assertEquals("per_shell", requestID)
                        sent.append("${reply.rawValue}:${message ?: "nil"}")
                    },
                    familyPendingRequests = { _, _, _ -> empty },
                )
            },
        )
        store.exhaustive = false
        store.send(Action.permissionReplyTapped(shell.id, OpenCodePermissionReply.Reject, message = "  Write to run-2 instead\n"))
        store.receive({ it is Action.RequestDock && it.action is RequestDockFeature.Action.ResponseFinished }, "requestResponseFinished")
        assertEquals(listOf("reject:Write to run-2 instead"), sent.value)
        assertTrue(store.state.permissionRequests.isEmpty())
        assertEquals(setOf(shell.id), store.state.completedRequestIDs)
    }

    /** One case per reply. */
    @Test fun onlyADenialCarriesAReason() = sessionTest { h ->
        val cases = listOf(
            Triple(OpenCodePermissionReply.Reject, "   ", "reject:nil"),
            Triple(OpenCodePermissionReply.Once, "Ignored", "once:nil"),
            Triple(OpenCodePermissionReply.Always, "Ignored", "always:nil"),
        )
        for ((reply, message, expected) in cases) {
            val sent = LockIsolated(listOf<String>())
            val store = h.store(
                h.shellDockState(),
                h.client {
                    copy(
                        replyPermission = { _, _, _, sentReply, sentMessage -> sent.append("${sentReply.rawValue}:${sentMessage ?: "nil"}") },
                        familyPendingRequests = { _, _, _ -> empty },
                    )
                },
            )
            store.exhaustive = false
            store.send(Action.permissionReplyTapped(shell.id, reply, message = message))
            store.receive({ it is Action.RequestDock && it.action is RequestDockFeature.Action.ResponseFinished }, "requestResponseFinished")
            assertEquals(listOf(expected), sent.value)
        }
    }

    @Test fun dismissingWithAReasonSendsItWithTheCancellation() = sessionTest { h ->
        val sent = LockIsolated(listOf<String>())
        val store = h.store(
            h.dockState(),
            h.client {
                copy(
                    cancelForm = { _, sessionID, formID, message ->
                        assertEquals("ses_review", sessionID)
                        assertEquals("frm_review", formID)
                        sent.append(message ?: "nil")
                    },
                    familyPendingRequests = { _, _, _ -> empty },
                )
            },
        )
        store.send(Action.formCancelTapped(request.id, message = " Draft it instead ")) { it.withRespondingRequestIDs(setOf(request.id)) }
        store.receive(Action.requestResponseFinished(session.id, request.id, null, dismissal = true)) {
            it.withRespondingRequestIDs(emptySet())
                .withCompletedRequestIDs(setOf(request.id))
                .withFormRequests(emptyList())
                .withFormAnswers(emptyMap())
        }
        store.receive(Action.PendingRequestsResponse(session.id, Result.success(empty))) { it.withRequestArrivalsAreLive(true) }
        assertEquals(listOf("Draft it instead"), sent.value)
        store.finish()
    }

    @Test fun aFailedReplyShowsUntilTheRetrySucceeds() = sessionTest { h ->
        val attempts = LockIsolated(0)
        val req = request
        val store = h.store(
            h.dockState(),
            h.client {
                copy(
                    replyForm = { _, _, _, _ ->
                        val attempt = attempts.increment()
                        if (attempt == 1) throw OpenCodeClientError(message = "HTTP 503")
                    },
                    familyPendingRequests = { _, _, _ ->
                        if (attempts.value < 2) OpenCodePendingRequests(permissions = emptyList(), forms = listOf(req)) else empty
                    },
                )
            },
        )
        store.send(Action.formSubmitTapped(request.id)) { it.withRespondingRequestIDs(setOf(request.id)) }
        store.receive(Action.requestResponseFinished(session.id, request.id, OpenCodeClientError(message = "HTTP 503"))) {
            it.withRespondingRequestIDs(emptySet())
                .withRequestErrors(it.requestErrors + (request.id to "HTTP 503"))
                .updateRequestDock { dock -> dock.copy(requestFailures = mapOf(request.id to RequestDockFeature.State.RequestFailure.Reply)) }
        }
        store.receive(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = listOf(request))))) { it.withRequestArrivalsAreLive(true) }
        // The dock keeps the answer for the retry.
        assertEquals(mapOf("q0" to OpenCodeFormValue.Str("Native")), store.state.formAnswers[request.id])
        store.send(Action.formSubmitTapped(request.id)) {
            it.withRespondingRequestIDs(setOf(request.id)).withRequestErrors(emptyMap()).updateRequestDock { dock -> dock.copy(requestFailures = emptyMap()) }
        }
        store.receive(Action.requestResponseFinished(session.id, request.id, null)) {
            it.withRespondingRequestIDs(emptySet())
                .withCompletedRequestIDs(setOf(request.id))
                .withFormRequests(emptyList())
                .withFormAnswers(emptyMap())
        }
        store.receive(Action.PendingRequestsResponse(session.id, Result.success(empty)))
        assertEquals(2, attempts.value)
        store.finish()
    }

    @Test fun severalQuestionsPageAndSubmitTogether() = sessionTest { h ->
        val form = threeQuestions
        val initial = h.dockState().withFormRequests(listOf(form)).withFormAnswers(mapOf(form.id to emptyMap()))
        val store = h.store(
            initial,
            h.client {
                copy(
                    replyForm = { _, _, _, answer ->
                        assertEquals(
                            mapOf(
                                "q0" to OpenCodeFormValue.Str("Bring Slack forward"),
                                "q1" to OpenCodeFormValue.Strings(listOf("Parking", "Sam: tree nuts")),
                                "q2" to OpenCodeFormValue.Str("7:50"),
                            ),
                            answer,
                        )
                    },
                    familyPendingRequests = { _, _, _ -> empty },
                )
            },
        )
        store.exhaustive = false
        store.send(Action.formValueChanged(form.id, "q0", OpenCodeFormValue.Str("Bring Slack forward")))
        store.send(Action.questionPageChanged(form.id, 1))
        assertEquals(1, store.state.questionPageIndices[form.id])
        store.send(Action.formValueChanged(form.id, "q1", OpenCodeFormValue.Strings(listOf("Parking", "Sam: tree nuts"))))
        store.send(Action.questionPageChanged(form.id, 2))
        // Past the last page is ignored.
        store.send(Action.questionPageChanged(form.id, 3))
        assertEquals(2, store.state.questionPageIndices[form.id])
        store.send(Action.formValueChanged(form.id, "q2", OpenCodeFormValue.Str("7:50")))
        store.send(Action.formSubmitTapped(form.id))
        store.receive({ it is Action.RequestDock && it.action is RequestDockFeature.Action.ResponseFinished }, "requestResponseFinished")
        assertTrue(store.state.formRequests.isEmpty())
    }

    @Test fun aSubagentsQuestionDocksAndAnswersItsOwner() = sessionTest { h ->
        val child = OpenCodeSession(id = "ses_child", serverTitle = "UI checks", projectID = "proj", directory = "/tmp/review", updatedAt = Instant.EPOCH, parentID = session.id)
        val form = question("frm_child", sessionID = "ses_child")
        val initial = h.dockState().withFormRequests(emptyList()).withFormAnswers(emptyMap()).copy(familySessions = listOf(child))
        val store = h.store(
            initial,
            h.client {
                copy(
                    replyForm = { _, sessionID, formID, answer ->
                        assertEquals("ses_child", sessionID)
                        assertEquals("frm_child", formID)
                        assertEquals(mapOf("q0" to OpenCodeFormValue.Str("Native")), answer)
                    },
                    familyPendingRequests = { _, _, _ -> empty },
                )
            },
        )
        store.exhaustive = false
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = listOf(form)))))
        assertEquals(form.id, store.state.dockRequestID)
        assertNull(store.state.activePicker)
        assertEquals(setOf("ses_child"), store.state.waitingSessionIDs)
        store.send(Action.formValueChanged(form.id, "q0", OpenCodeFormValue.Str("Native")))
        store.send(Action.formSubmitTapped(form.id))
        store.receive({ it is Action.RequestDock && it.action is RequestDockFeature.Action.ResponseFinished }, "requestResponseFinished")
        assertEquals(setOf(form.id), store.state.completedRequestIDs)
    }

    @Test fun theDockShowsTheSelectedRequestAndFallsBackWhenItLeaves() = sessionTest { h ->
        val initial = h.dockState().withPermissionRequests(listOf(shell))
        val store = h.store(initial)
        store.exhaustive = false
        assertEquals(request.id, store.state.dockRequestID)
        store.send(Action.reviewRequestSelected(shell.id))
        assertEquals(shell.id, store.state.dockRequestID)
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = listOf(request)))))
        assertNull(store.state.selectedRequestID)
        assertEquals(request.id, store.state.dockRequestID)
    }

    @Test fun theDockedRequestStaysUntilItsAnsweredWhileNewerOnesQueue() = sessionTest { h ->
        val req = request
        val store = h.store(
            h.shellDockState(),
            h.client {
                copy(
                    replyPermission = { _, _, _, _, _ -> },
                    familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = listOf(req)) },
                )
            },
        )
        store.exhaustive = false
        assertEquals(shell.id, store.state.dockRequestID)
        // A question arrives mid-permission; forms sort first, but it queues.
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = listOf(shell), forms = listOf(request)))))
        assertEquals(shell.id, store.state.dockRequestID)
        assertEquals(2, store.state.reviewRequestIDs.size)
        // A later poll leaves it there too.
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = listOf(shell), forms = listOf(request)))))
        assertEquals(shell.id, store.state.dockRequestID)
        // Answered, it gives way to the queued question.
        store.send(Action.permissionReplyTapped(shell.id, OpenCodePermissionReply.Once))
        store.receive({ it is Action.RequestDock && it.action is RequestDockFeature.Action.ResponseFinished }, "requestResponseFinished")
        assertEquals(request.id, store.state.dockRequestID)
    }

    @Test fun aDeletedSubagentsRequestsLeaveTheDock() = sessionTest { h ->
        val child = OpenCodeSession(id = "ses_child", serverTitle = "UI checks", projectID = "proj", directory = "/tmp/review", updatedAt = Instant.EPOCH, parentID = session.id)
        val childPermission = OpenCodePermissionRequest(id = "per_child", sessionID = child.id, action = "shell", resources = listOf("ls"), save = null)
        val childForm = question("frm_child", sessionID = "ses_child")
        val initial = h.dockState().copy(familySessions = listOf(session, child)).updateRequestDock {
            it.copy(
                formRequests = listOf(childForm),
                formAnswers = mapOf(childForm.id to mapOf("q0" to OpenCodeFormValue.Str("Draft"))),
                questionPageIndices = mapOf(childForm.id to 0),
                permissionRequests = listOf(childPermission, shell),
                selectedRequestID = childPermission.id,
            )
        }
        val shellRequest = shell
        val store = h.store(
            initial,
            h.client {
                copy(
                    familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = listOf(shellRequest), forms = emptyList()) },
                    pendingInputs = { _, _ -> emptyList() },
                )
            },
        )
        store.exhaustive = false
        store.send(Action.FamilySessionDeleted(child.id))
        assertEquals(listOf(shell), store.state.permissionRequests)
        assertTrue(store.state.formRequests.isEmpty())
        assertTrue(store.state.formAnswers.isEmpty())
        assertTrue(store.state.questionPageIndices.isEmpty())
        assertNull(store.state.selectedRequestID)
        assertEquals(shell.id, store.state.dockRequestID)
        // The surviving family's snapshot no longer lists the child; its
        // requests must not come back as an undiscovered owner's.
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = listOf(shell), forms = emptyList()))))
        assertEquals(listOf(shell.id), store.state.reviewRequestIDs)
        h.skipInFlightEffects()
    }

    @Test fun pickingAnotherRequestMovesThePin() = sessionTest { h ->
        val initial = h.dockState().withPermissionRequests(listOf(shell))
        val store = h.store(initial)
        store.exhaustive = false
        assertEquals(request.id, store.state.dockRequestID)
        store.send(Action.reviewRequestSelected(shell.id))
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = listOf(shell), forms = listOf(request)))))
        assertEquals(shell.id, store.state.dockRequestID)
    }

    @Test fun onlyALargeFormOpensTheSheet() = sessionTest { h ->
        val large = decodeForm(
            """
            {"id":"frm_deploy","sessionID":"ses_review","title":"Deploy","fields":[
              {"key":"env","type":"string","title":"Environment"},{"key":"note","type":"string","title":"Note"}
            ]}
            """.trimIndent(),
        )
        assertFalse(large.fitsDock)
        assertTrue(request.fitsDock)
        val initial = h.dockState().withFormRequests(listOf(large))
        val store = h.store(initial)
        store.send(Action.requestSheetTapped(large.id)) {
            it.withSelectedRequestID(large.id).copy(activePicker = SessionFeature.State.ActivePicker.requests)
        }
        // Permissions never open it.
        store.send(Action.requestSheetTapped("per_missing"))
        store.finish()
    }

    // endregion
}
