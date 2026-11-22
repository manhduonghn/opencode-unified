package ai.opencode.feature.session

import ai.opencode.feature.composer.RequestDockFeature
import ai.opencode.feature.composer.DraftState
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodePermissionRequest

// The request dock's state, forwarded from `requestDock`. Each get/set pair is a getter plus a
// `withX(value)` setter.

val State.permissionRequests: List<OpenCodePermissionRequest> get() = requestDock.permissionRequests
fun State.withPermissionRequests(value: List<OpenCodePermissionRequest>): State = updateRequestDock { it.copy(permissionRequests = value) }
val State.formRequests: List<OpenCodeFormRequest> get() = requestDock.formRequests
fun State.withFormRequests(value: List<OpenCodeFormRequest>): State = updateRequestDock { it.copy(formRequests = value) }
val State.formAnswers: Map<String, Map<String, OpenCodeFormValue>> get() = requestDock.formAnswers
fun State.withFormAnswers(value: Map<String, Map<String, OpenCodeFormValue>>): State = updateRequestDock { it.copy(formAnswers = value) }
val State.respondingRequestIDs: Set<String> get() = requestDock.respondingRequestIDs
fun State.withRespondingRequestIDs(value: Set<String>): State = updateRequestDock { it.copy(respondingRequestIDs = value) }
val State.questionPageIndices: Map<String, Int> get() = requestDock.questionPageIndices
fun State.withQuestionPageIndices(value: Map<String, Int>): State = updateRequestDock { it.copy(questionPageIndices = value) }
val State.requestLoadError: String? get() = requestDock.requestLoadError
fun State.withRequestLoadError(value: String?): State = updateRequestDock { it.copy(requestLoadError = value) }
val State.requestLoadAttempt: RequestDockFeature.State.LoadAttempt? get() = requestDock.requestLoadAttempt
/** Requests that show up from here on arrived while the screen watched (not a snapshot's backlog). */
val State.requestArrivalsAreLive: Boolean get() = requestDock.requestArrivalsAreLive
fun State.withRequestArrivalsAreLive(value: Boolean): State = updateRequestDock { it.copy(requestArrivalsAreLive = value) }
fun State.withRequestLoadAttempt(value: RequestDockFeature.State.LoadAttempt?): State = updateRequestDock { it.copy(requestLoadAttempt = value) }
val State.requestFailureAwaitingReconnect: Boolean get() = requestDock.requestFailureAwaitingReconnect
fun State.withRequestFailureAwaitingReconnect(value: Boolean): State =
    updateRequestDock { it.copy(requestFailureAwaitingReconnect = value) }
val State.requestsConnectionIsAvailable: Boolean get() = requestDock.requestsConnectionIsAvailable
fun State.withRequestsConnectionIsAvailable(value: Boolean): State = updateRequestDock { it.copy(requestsConnectionIsAvailable = value) }
/** Timers may read the server: Home has heard from it, the app is in front and nothing covers the screen. Otherwise Home's connection loop is the only thing that asks. */
val State.pollsServer: Boolean get() = requestsConnectionIsAvailable && !isBackgrounded && !isPollingPaused

val State.isSettled: Boolean
    get() = !isRunning && activeFamilySessionIDs.isEmpty() && queuedInputs.isEmpty() && !hasPendingRequests

const val SETTLED_PENDING_WORK_SECONDS = 60
const val SETTLED_WATCHDOG_SECONDS = 120

val State.pendingWorkPollCadence: Int
    get() {
        if (!streamIsHealthy) return 2
        return if (isSettled) SETTLED_PENDING_WORK_SECONDS else 12
    }

val State.watchdogSeconds: Int
    get() {
        if (streamIsHealthy && isSettled) return SETTLED_WATCHDOG_SECONDS
        if (streamIsHealthy && streamSupportsProjection) return 20
        return if (isRunning) 3 else 30
    }

data class TimedReadCadence(val pendingWork: Int, val watchdog: Int)

val State.timedReadCadence: TimedReadCadence get() = TimedReadCadence(pendingWork = pendingWorkPollCadence, watchdog = watchdogSeconds)
val State.requestErrors: Map<String, String> get() = requestDock.requestErrors
fun State.withRequestErrors(value: Map<String, String>): State = updateRequestDock { it.copy(requestErrors = value) }
/** While a pending request docks, the composer is its answer, and what was typed waits here. */
val State.parkedComposer: DraftState? get() = requestDock.parkedComposer
fun State.withParkedComposer(value: DraftState?): State = updateRequestDock { it.copy(parkedComposer = value) }
/** The bar's text for each question page or request it has answered. */
val State.answerDrafts: Map<String, String> get() = requestDock.answerDrafts
fun State.withAnswerDrafts(value: Map<String, String>): State = updateRequestDock { it.copy(answerDrafts = value) }
/** Whose words the bar holds now (an `answerTarget`). */
val State.answerShownTarget: String? get() = requestDock.answerShownTarget
fun State.withAnswerShownTarget(value: String?): State = updateRequestDock { it.copy(answerShownTarget = value) }
/** The bar's text as last loaded or applied. */
val State.answerBarBaseline: String get() = requestDock.answerBarBaseline
fun State.withAnswerBarBaseline(value: String): State = updateRequestDock { it.copy(answerBarBaseline = value) }
/** The pending request the dock shows; null shows the first. */
val State.selectedRequestID: String? get() = requestDock.selectedRequestID
fun State.withSelectedRequestID(value: String?): State = updateRequestDock { it.copy(selectedRequestID = value) }
/** A successful reply must not become actionable again through an older poll. */
val State.completedRequestIDs: Set<String> get() = requestDock.completedRequestIDs
fun State.withCompletedRequestIDs(value: Set<String>): State = updateRequestDock { it.copy(completedRequestIDs = value) }
