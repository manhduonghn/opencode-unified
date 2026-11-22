package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.eventually
import ai.opencode.feature.composer.PromptOutbox
import ai.opencode.feature.composer.UndeliveredPrompts
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.settle
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.UrlErrorCode
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.retry.AppPhase
import ai.opencode.server.retry.NetworkPathClient
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RetryPolicy
import ai.opencode.server.retry.TransportFailure
import ai.opencode.server.testing.WireFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Sending never waits for the server: each send shows at once, and the
 * outbox delivers a session's sends one at a time, in order.
 */
class SendOutboxTests {
    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_1", serverTitle = "Chat", projectID = "p", directory = "/project", updatedAt = SessionTestSupport.distantPast)
    private val first = "msg_00000000000100000000000000"
    private val second = "msg_00000000000200000000000000"
    private val third = "msg_00000000000300000000000000"

    /** POSTs in arrival order, each with its delivery. */
    private class Posts {
        private val value = LockIsolated(listOf<Pair<String, OpenCodeDelivery>>())
        val all: List<Pair<String, OpenCodeDelivery>> get() = value.value
        val ids: List<String> get() = all.map { it.first }
        val deliveries: List<OpenCodeDelivery> get() = all.map { it.second }
        val count: Int get() = all.size

        /** Records a POST; returns how many times this ID has been posted. */
        fun record(id: String, delivery: OpenCodeDelivery): Int =
            value.withValue { posts -> val next = posts + (id to delivery); next to next.count { it.first == id } }
    }

    /** A gate a test opens to let a held request finish. */
    private class Gate {
        private val opened = CompletableDeferred<Unit>()
        suspend fun wait() = opened.await()
        fun open() { opened.complete(Unit) }
    }

    /** Failed at the end of the test. */
    private val issues = LockIsolated(listOf<String>())

    /** Here each test's outboxes run their lanes on the test's own thread for the same order. */
    private lateinit var lanes: CoroutineScope

    private fun newOutbox() = PromptOutbox(scope = lanes)

    private fun outboxTest(block: suspend CoroutineScope.(Harness) -> Unit) = sessionTest { h ->
        val job = SupervisorJob()
        lanes = CoroutineScope(coroutineContext.minusKey(Job) + job)
        try {
            block(h)
            assertEquals(emptyList<String>(), issues.value)
        } finally {
            job.cancel()
        }
    }

    private fun Harness.store(
        state: SessionFeature.State,
        posts: Posts,
        post: suspend (id: String, attempt: Int) -> Unit = { _, _ -> },
        dependencies: DependencyValues.Builder.() -> Unit = {},
    ): TestStore<SessionFeature.State, Action> {
        val store = store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(100))
            this[UndeliveredPrompts] = UndeliveredPrompts()
            this[PromptOutbox] = newOutbox()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, id, delivery ->
                    val attempt = posts.record(id, delivery)
                    post(id, attempt)
                },
                getSession = { _, id ->
                    OpenCodeSession(id = id, serverTitle = "Chat", projectID = "p", directory = "/project", updatedAt = SessionTestSupport.distantPast)
                },
                transcript = { _, _, _ -> emptyList() },
                sessionFamily = { _, root -> OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet()) },
                runningShells = { _, _ -> emptyList() },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                familyPendingRequests = { _, _, _ -> awaitCancellation() },
                message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
                pendingInputs = { _, _ -> emptyList() },
                setPendingInputDelivery = { _, _, _, _ -> issues.append("Nothing waiting in the outbox needs a PATCH") },
                cancelPendingInput = { _, _, _ -> issues.append("Nothing waiting in the outbox needs a DELETE") },
                // The reply's wait isn't part of these tests.
                waitForIdle = { _, _ -> awaitCancellation() },
                // After Stop, the server has nothing running.
                isSessionActive = { _, _ -> false },
            )
            dependencies()
        }
        store.exhaustive = false
        return store
    }

    private fun Harness.running(draft: String): SessionFeature.State = state {
        SessionFeature.State.session(
            server = server,
            session = session,
            cachedItems = listOf(OpenCodeTranscriptItem.UserText(id = "msg_earlier", text = "Earlier")),
        ).copy(isExternallyActive = true).withDraft(draft)
    }

    private fun Harness.idle(draft: String, on: OpenCodeServer = server): SessionFeature.State = state {
        SessionFeature.State.session(server = on, session = session).withDraft(draft)
    }

    private val project = OpenCodeProject(id = "p", name = "project", directory = "/project")

    private fun Harness.draftState(draft: String): SessionFeature.State = state {
        SessionFeature.State.draft(server = server, draftProject = project, projects = listOf(project)).withDraft(draft)
    }

    private suspend fun type(text: String, store: TestStore<SessionFeature.State, Action>) =
        store.send(Action.Binding(BindingAction.Draft(text)))

    /**
     * Waits for the server to have admitted each send. Delivery is in order,
     * but each send's report reaches the reducer from its own effect, so the
     * actions may arrive in either order.
     */
    private suspend fun waitForAdmission(ids: List<String>, store: TestStore<SessionFeature.State, Action>) {
        // A screen that came back follows a send too, so an older report of the
        // same send may arrive first and change nothing.
        repeat(ids.size * 2) {
            if (ids.all { store.state.promptStates[it]?.admission == SessionFeature.State.PromptState.Admission.admitted }) return@repeat
            store.receive(Action.PromptAdmitted::class, timeout = 2.seconds)
        }
        for (id in ids) {
            assertEquals("$id was admitted", SessionFeature.State.PromptState.Admission.admitted, store.state.promptStates[id]?.admission)
        }
    }

    private val requestFailed = Action.PromptFailed::class

    @Test fun twoQuickSendsWhileTheFirstPostIsSlowBothShowAndDeliverInOrder() = outboxTest { h ->
        val posts = Posts()
        val slow = Gate()
        val store = h.store(h.running(draft = "Use the staging bucket"), posts, post = { id, _ -> if (id == first) slow.wait() })

        store.send(Action.SendTapped)
        assertFalse("Nothing typed yet", store.state.canSendPrompt)
        type("Then check the share extension", store)
        assertTrue("The first send still on its way doesn't block the next", store.state.canSendPrompt)
        store.send(Action.SendTapped)
        // Both show at once, in order, and the composer is clear.
        assertEquals(listOf(first, second), store.state.queuedInputs.map { it.id })
        assertEquals(listOf(OpenCodeDelivery.Steer, OpenCodeDelivery.Steer), store.state.queuedInputs.map { it.delivery })
        assertTrue(store.state.draft.isEmpty())
        assertEquals(second, store.state.lastSentItemID)
        assertEquals("Each send flies from the composer", 2, store.state.sendGeneration)
        settle()
        assertEquals("The second waits for the first, in order", listOf(first), posts.ids)

        slow.open()
        waitForAdmission(listOf(first, second), store)
        assertEquals(listOf(first, second), posts.ids)
        assertFalse(store.state.isSubmittingPrompt)
        h.skipInFlightEffects()
    }

    /**
     * A send being tried again looks like any send on its way: no note, no
     * failure, Working as at its first moment, until the server has it.
     */
    @Test fun aTransientFailureRetriesQuietlyThenSucceeds() = outboxTest { h ->
        val posts = Posts()
        val identities = LockIsolated(listOf<NetworkRetry.Correlation>())
        val third = Gate()
        val store = h.store(h.idle(draft = "hello"), posts, post = { _, attempt ->
            NetworkRetry.manualOperation.current()?.current?.let { identities.append(it) }
            when (attempt) {
                1 -> throw OpenCodeClientError(message = "The server returned HTTP 503.", statusCode = 503)
                2 -> throw OpenCodeClientError.unreachable(TransportFailure.Interrupted)
                else -> third.wait()
            }
        })

        store.send(Action.SendTapped)
        assertEquals("The bubble shows at once", listOf(OpenCodeTranscriptItem.UserText(id = first, text = "hello")), store.state.items)
        val atFirst = Triple(store.state.isRunning, store.state.deliveryNotes, store.state.promptStates[first]?.admission)
        eventually { posts.count == 3 }
        settle()
        // Two failed attempts later, it reads exactly as it did when it left.
        assertTrue("No Sending… or retrying line", store.state.deliveryNotes.isEmpty())
        assertTrue(store.state.failedSends.isEmpty())
        assertEquals(atFirst.first, store.state.isRunning)
        assertEquals(atFirst.second, store.state.deliveryNotes)
        assertEquals(atFirst.third, store.state.promptStates[first]?.admission)
        third.open()
        waitForAdmission(listOf(first), store)
        assertTrue(store.state.deliveryNotes.isEmpty())
        assertTrue(store.state.failedSends.isEmpty())
        assertEquals("The same ID each time", listOf(first, first, first), posts.ids)
        assertEquals(listOf(1, 2, 3), identities.value.map { it.attempt })
        assertEquals(1, identities.value.map { it.operationID }.toSet().size)
        h.skipInFlightEffects()
    }

    /**
     * An immediate ATS -1022 from an HTTP origin that answered other requests, then success on
     * manual Retry. Drives the live client's error translation under the outbox's `.once` scope,
     * pinning quiet recovery and one owner of the retry together.
     */
    @Test fun anATSFailureOnAWorkingHTTPOriginRetriesSilentlyThroughTheLiveClient() = outboxTest { h ->
        val admissions = LockIsolated(listOf<String>())
        val wirePosts = LockIsolated(0)
        val fixture = WireFixture(scheme = "http") { request ->
            if (request.method != "POST") return@WireFixture WireFixture.Reply.Json(200, """{"data":[]}""")
            if (wirePosts.increment() == 1) return@WireFixture WireFixture.Reply.Failure(UrlErrorCode.APP_TRANSPORT_SECURITY_REQUIRES_SECURE_CONNECTION)
            runCatching { request.json()["id"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content } }.getOrNull()?.let { admissions.append(it) }
            WireFixture.Reply.Json(200, """{"data":{}}""")
        }
        try {
            // As during a real connection, a successful read establishes that ATS
            // allowed the exact origin. The send itself has never been admitted.
            OpenCodeClient.liveValue.pendingInputs(fixture.server, session.id)
            val posts = Posts()
            val retry = Gate()
            val wireServer = fixture.server
            val store = h.store(h.idle(draft = "hello", on = wireServer), posts, post = { id, attempt ->
                assertEquals(RetryPolicy.once, NetworkRetry.policy.current())
                if (attempt == 2) retry.wait()
                OpenCodeClient.liveValue.sendPrompt(wireServer, session.id, "hello", emptyList(), emptyList(), id, OpenCodeDelivery.Steer)
            })

            store.send(Action.SendTapped)
            eventually(wallClock = true) { posts.count == 2 }
            settle()
            assertEquals("Only the outbox retries; the client's once scope is preserved", 1, wirePosts.value)
            assertTrue(store.state.failedSends.isEmpty())
            assertTrue(store.state.deliveryNotes.isEmpty())
            assertTrue(store.state.isRunning)
            assertEquals(listOf(OpenCodeTranscriptItem.UserText(id = first, text = "hello")), store.state.items)
            retry.open()
            waitForAdmission(listOf(first), store)
            assertEquals(listOf(first, first), posts.ids)
            assertEquals(2, wirePosts.value)
            assertEquals("Exactly one admission, with the original message ID", listOf(first), admissions.value)
            val headers = fixture.requests.filter { it.method == "POST" }
            assertEquals(listOf("1", "2"), headers.map { it.header("X-OpenCode-Attempt") })
            assertEquals(headers[0].header("X-OpenCode-Op"), headers[1].header("X-OpenCode-Op"))
            assertNotEquals(headers[0].header("X-OpenCode-Req"), headers[1].header("X-OpenCode-Req"))
            assertTrue(store.state.failedSends.isEmpty())
            h.skipInFlightEffects()
        } finally {
            fixture.close()
        }
    }

    @Test fun aTerminalFailureOffersRetryAndHoldsLaterSends() = outboxTest { h ->
        val posts = Posts()
        val identities = LockIsolated(listOf<NetworkRetry.Correlation>())
        val slow = Gate()
        val store = h.store(h.running(draft = "Use the staging bucket"), posts, post = { id, attempt ->
            NetworkRetry.manualOperation.current()?.current?.let { identities.append(it) }
            if (id == first && attempt == 1) {
                slow.wait()
                throw OpenCodeClientError(message = "The server returned HTTP 400.", statusCode = 400)
            }
        })

        store.send(Action.SendTapped)
        type("Then check the share extension", store)
        store.send(Action.SendTapped)
        slow.open()
        store.receive(requestFailed)
        assertEquals(listOf(first), store.state.failedSends.ids)
        assertEquals("Its words stay in its bubble", OpenCodeTranscriptItem.UserText(id = first, text = "Use the staging bucket"), store.state.items.last())
        assertEquals("The later send is still shown", listOf(second), store.state.queuedInputs.map { it.id })
        assertEquals(mapOf(second to DeliveryNote.waiting), store.state.deliveryNotes)
        settle()
        assertEquals("The later send waits behind the failed one", listOf(first), posts.ids)

        store.send(Action.FailedSendRetryTapped(first))
        assertEquals("Shown ahead of the send that waited behind it", listOf(first, second), store.state.queuedInputs.map { it.id })
        waitForAdmission(listOf(first, second), store)
        assertEquals("Retried in its own place, ahead of the later send", listOf(first, first, second), posts.ids)
        assertEquals(listOf(1, 2, 1), identities.value.map { it.attempt })
        assertEquals(identities.value[0].operationID, identities.value[1].operationID)
        assertNotEquals(identities.value[1].operationID, identities.value[2].operationID)
        assertTrue(store.state.failedSends.isEmpty())
        h.skipInFlightEffects()
    }

    @Test fun deletingAFailedSendLetsTheLaterOnesGo() = outboxTest { h ->
        val posts = Posts()
        val slow = Gate()
        val store = h.store(h.running(draft = "Use the staging bucket"), posts, post = { id, _ ->
            if (id == first) {
                slow.wait()
                throw OpenCodeClientError(message = "The server returned HTTP 400.", statusCode = 400)
            }
        })

        store.send(Action.SendTapped)
        type("Then check the share extension", store)
        store.send(Action.SendTapped)
        slow.open()
        store.receive(requestFailed)
        store.send(Action.FailedSendDeleteTapped(first))
        waitForAdmission(listOf(second), store)
        assertEquals(listOf(first, second), posts.ids)
        h.skipInFlightEffects()
    }

    /**
     * Move to Queue and Delete on sends still waiting in the outbox change
     * what's posted, with no PATCH or DELETE afterwards.
     */
    @Test fun changesToWaitingSendsAreMadeBeforeTheyArePosted() = outboxTest { h ->
        val posts = Posts()
        val slow = Gate()
        val store = h.store(h.running(draft = "Use the staging bucket"), posts, post = { id, _ -> if (id == first) slow.wait() })

        store.send(Action.SendTapped)
        type("Then check the share extension", store)
        store.send(Action.SendTapped)
        type("Never mind", store)
        store.send(Action.SendTapped)
        store.send(Action.PendingInputMoveToQueueTapped(second))
        store.send(Action.PendingInputCancelTapped(third))
        assertEquals(listOf(first, second), store.state.queuedInputs.map { it.id })
        assertNull("A send never posted has nothing to wait for", store.state.promptStates[third])

        slow.open()
        waitForAdmission(listOf(first, second), store)
        settle()
        assertEquals("The deleted send was never posted", listOf(first, second), posts.ids)
        assertEquals("The moved send was posted queued", listOf(OpenCodeDelivery.Steer, OpenCodeDelivery.Queue), posts.deliveries)
        h.skipInFlightEffects()
    }

    @Test fun aDraftsSecondSendGoesToTheSessionItsFirstCreates() = outboxTest { h ->
        val created = OpenCodeSession(id = "ses_new", serverTitle = "New session", projectID = "p", directory = "/project", updatedAt = SessionTestSupport.distantPast)
        val posts = Posts()
        val sessions = LockIsolated(listOf<String>())
        val creations = LockIsolated(0)
        val slowCreate = Gate()
        val store = h.store(h.draftState("Find the flaky test"), posts, dependencies = {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                createSession = { _, _, _ ->
                    creations.increment()
                    slowCreate.wait()
                    created
                },
                sendPrompt = { _, sessionID, _, _, _, id, delivery ->
                    sessions.append(sessionID)
                    posts.record(id, delivery)
                },
                getSession = { _, _ -> created },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
            )
        })

        store.send(Action.SendTapped)
        type("And fix it", store)
        store.send(Action.SendTapped)
        // Both show while the session is still being created.
        assertEquals(listOf(first), store.state.items.map { it.id })
        assertEquals(listOf(second), store.state.queuedInputs.map { it.id })
        assertTrue(store.state.draft.isEmpty())

        slowCreate.open()
        waitForAdmission(listOf(first, second), store)
        assertEquals(created, store.state.session)
        assertEquals("One session for both", 1, creations.value)
        assertEquals(listOf(created.id, created.id), sessions.value)
        assertEquals(listOf(first, second), posts.ids)
        h.skipInFlightEffects()
    }

    /**
     * A screen opened after its session's sends left shows them, still on
     * their way, and hears when they land.
     */
    @Test fun sendsStillOnTheirWayComeBackWithTheirSession() = outboxTest { h ->
        val outbox = newOutbox()
        val posts = Posts()
        val slow = Gate()
        val sent = h.store(h.running(draft = "Use the staging bucket"), posts, post = { id, _ -> if (id == first) slow.wait() }, dependencies = {
            this[PromptOutbox] = outbox
        })
        sent.send(Action.SendTapped)
        h.skipInFlightEffects()

        reopenedHarness { h2 ->
            val reopened = h2.store(h2.running(draft = ""), posts, dependencies = { this[PromptOutbox] = outbox })
            reopened.send(Action.Task)
            assertEquals(listOf(first), reopened.state.queuedInputs.map { it.id })
            assertTrue(reopened.state.isSubmittingPrompt)
            slow.open()
            reopened.receive(Action.PromptAdmitted::class)
            assertFalse(reopened.state.isSubmittingPrompt)
            assertEquals("Sent once", listOf(first), posts.ids)
        }
    }

    // region Intent on the outbox item

    /**
     * Lets the first attempts fail and the send settle into waiting to try
     * again (nothing in the store marks it: a retry is silent).
     */
    private suspend fun waitForRetry(posts: Posts, store: TestStore<SessionFeature.State, Action>, attempts: Int = 1, id: String? = null) {
        val outbox = store.withDependencies { ai.opencode.core.dependencies.dependency(PromptOutbox) }
        val messageID = id ?: first
        eventually { posts.count >= attempts && outbox.isRetrying(messageID) }
        settle()
    }

    private val transient = OpenCodeClientError(message = "The server returned HTTP 503.", statusCode = 503)

    @Test fun deletingASendBeingRetriedMeansItsNeverPostedAgain() = outboxTest { h ->
        val posts = Posts()
        val clock = TestClock()
        val store = h.store(h.running(draft = "Use the staging bucket"), posts, post = { _, attempt -> if (attempt == 1) throw transient }, dependencies = {
            this[Clock] = clock
        })

        store.send(Action.SendTapped)
        waitForRetry(posts, store)
        store.send(Action.PendingInputCancelTapped(first))
        assertTrue(store.state.queuedInputs.isEmpty())
        assertNull(store.state.promptStates[first])
        clock.advance(60.seconds)
        settle()
        assertEquals("Deleted while it waited to try again: never posted again", listOf(first), posts.ids)
        store.finish()
    }

    @Test fun movingASendBeingRetriedToTheQueuePostsItQueued() = outboxTest { h ->
        val posts = Posts()
        val clock = TestClock()
        val store = h.store(h.running(draft = "Use the staging bucket"), posts, post = { _, attempt -> if (attempt == 1) throw transient }, dependencies = {
            this[Clock] = clock
        })

        store.send(Action.SendTapped)
        waitForRetry(posts, store)
        store.send(Action.PendingInputMoveToQueueTapped(first))
        settle()
        clock.advance(1.seconds)
        waitForAdmission(listOf(first), store)
        assertEquals("The next attempt posts it as it stands", listOf(OpenCodeDelivery.Steer, OpenCodeDelivery.Queue), posts.deliveries)
        h.skipInFlightEffects()
    }

    @Test fun aSendDeletedWhileBeingPostedStaysDeletedAcrossASwitchAndIsTakenBackOnAdmission() = outboxTest { h ->
        val root = session
        val child = OpenCodeSession(id = "ses_child", serverTitle = "Inspect", projectID = "p", directory = "/project", updatedAt = SessionTestSupport.distantPast, parentID = root.id)
        val state = h.running(draft = "Use the staging bucket").copy(familySessions = listOf(root, child))
        val posts = Posts()
        val cancels = LockIsolated(listOf<String>())
        val slow = Gate()
        val store = h.store(state, posts, post = { id, _ -> if (id == first) slow.wait() }, dependencies = {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                cancelPendingInput = { _, sessionID, id ->
                    if (NetworkRetry.manualOperation.current() != null) issues.append("Post-admission user intents have their own retry identity")
                    cancels.append("$sessionID/$id")
                },
            )
        })

        store.send(Action.SendTapped)
        settle()
        store.send(Action.PendingInputCancelTapped(first))
        assertTrue(store.state.queuedInputs.isEmpty())
        store.send(Action.FamilyPicked(child.id))
        store.send(Action.FamilyPicked(root.id))
        assertFalse("Coming back doesn't bring it back", store.state.queuedInputs.any { it.id == first })
        assertNull(store.state.promptStates[first])

        slow.open()
        assertTrue("The server is told to drop it once it's admitted", eventually { cancels.value == listOf("${root.id}/$first") })
        assertFalse(store.state.queuedInputs.any { it.id == first })
        h.skipInFlightEffects()
    }

    @Test fun aFamilySwitchRoundTripKeepsSendsStillOnTheirWay() = outboxTest { h ->
        val root = session
        val child = OpenCodeSession(id = "ses_child", serverTitle = "Inspect", projectID = "p", directory = "/project", updatedAt = SessionTestSupport.distantPast, parentID = root.id)
        val state = h.running(draft = "Use the staging bucket").copy(familySessions = listOf(root, child))
        val posts = Posts()
        val slow = Gate()
        val store = h.store(state, posts, post = { id, _ -> if (id == first) slow.wait() })

        store.send(Action.SendTapped)
        // Home's snapshot of the session, taken as the reader leaves it, never
        // holds a send still on its way.
        val snapshot = h.state { SessionFeature.SwitchSnapshot.from(store.state) }
        assertEquals(true, snapshot?.queued?.isEmpty())
        store.send(Action.FamilyPicked(child.id))
        store.send(Action.FamilyPicked(root.id))
        assertEquals(listOf(first), store.state.queuedInputs.map { it.id })
        assertEquals(SessionFeature.State.PromptState.Admission.sent, store.state.promptStates[first]?.admission)
        // Home restores its snapshot after the session's own switch: the outbox's send stays.
        val restored = h.state { snapshot?.restoreSessionData(store.state) ?: store.state }
        assertEquals(listOf(first), restored.queuedInputs.map { it.id })
        assertNotNull(restored.pendingPromptPositions[first])

        slow.open()
        waitForAdmission(listOf(first), store)
        h.skipInFlightEffects()
    }

    @Test fun aSendAdmittedWhileAwayIsThereOnTheFirstFrameBack() = outboxTest { h ->
        val root = session
        val child = OpenCodeSession(id = "ses_child", serverTitle = "Inspect", projectID = "p", directory = "/project", updatedAt = SessionTestSupport.distantPast, parentID = root.id)
        val state = h.running(draft = "Use the staging bucket").copy(familySessions = listOf(root, child))
        val outbox = newOutbox()
        val posts = Posts()
        val slow = Gate()
        val store = h.store(state, posts, post = { id, _ -> if (id == first) slow.wait() }, dependencies = { this[PromptOutbox] = outbox })

        store.send(Action.SendTapped)
        store.send(Action.FamilyPicked(child.id))
        slow.open()
        assertTrue(eventually { outbox.contents(server.persistenceID, root.id).admitted.map { it.messageID } == listOf(first) })
        store.send(Action.FamilyPicked(root.id))
        assertEquals("Shown until the transcript has it", listOf(first), store.state.queuedInputs.map { it.id })
        assertEquals(SessionFeature.State.PromptState.Admission.admitted, store.state.promptStates[first]?.admission)
        h.skipInFlightEffects()
    }

    // endregion

    // region A failed send in an idle session

    @Test fun anIdleSessionHeldByAFailedSendIsntWorkingAndRetryKeepsTheOrder() = outboxTest { h ->
        val posts = Posts()
        val store = h.store(h.idle(draft = "Use the staging bucket"), posts, post = { id, attempt ->
            if (id == first && attempt == 1) throw OpenCodeClientError(message = "The server returned HTTP 400.", statusCode = 400)
        })

        store.send(Action.SendTapped)
        store.receive(requestFailed)
        assertFalse(store.state.isRunning)
        type("Then check the share extension", store)
        store.send(Action.SendTapped)
        assertFalse("Nothing goes until Retry or Delete, so nothing is working", store.state.isRunning)
        assertEquals(listOf(first, second), store.state.items.map { it.id })
        assertEquals(mapOf(second to DeliveryNote.waiting), store.state.deliveryNotes)
        settle()
        assertEquals(listOf(first), posts.ids)

        store.send(Action.FailedSendRetryTapped(first))
        assertEquals("The retried bubble keeps its place", listOf(first, second), store.state.items.map { it.id })
        assertTrue(store.state.isRunning)
        waitForAdmission(listOf(first, second), store)
        assertEquals(listOf(first, first, second), posts.ids)
        assertEquals(listOf(first, second), store.state.items.map { it.id })
        h.skipInFlightEffects()
    }

    // endregion

    // region Stop

    @Test fun stopHoldsTheSendsNotYetPostedAndLetsThePostingOneFinish() = outboxTest { h ->
        val posts = Posts()
        val identities = LockIsolated(listOf<NetworkRetry.Correlation>())
        val interrupts = LockIsolated(0)
        val slow = Gate()
        val store = h.store(h.running(draft = "Use the staging bucket"), posts, post = { id, attempt ->
            NetworkRetry.manualOperation.current()?.current?.let { identities.append(it) }
            if (id == first && attempt == 1) slow.wait()
        }, dependencies = {
            this[OpenCodeClient] = this[OpenCodeClient].copy(interrupt = { _, _ -> interrupts.increment(); true })
        })

        store.send(Action.SendTapped)
        type("Then check the share extension", store)
        store.send(Action.SendTapped)
        type("And the widget", store)
        store.send(Action.SendTapped)
        settle()
        store.send(Action.InterruptTapped)
        assertEquals("Held, with Retry and Delete", listOf(second, third), store.state.failedSends.ids)
        assertEquals(listOf(SessionFeature.stoppedReason, SessionFeature.stoppedReason), store.state.failedSends.map { it.reason })
        assertEquals("The one being posted finishes its attempt", listOf(first), store.state.queuedInputs.map { it.id })
        store.receive(Action.InterruptAcknowledged::class)

        slow.open()
        waitForAdmission(listOf(first), store)
        settle()
        assertEquals("Stop never interrupts again on its own", 1, interrupts.value)
        assertEquals("Nothing else is posted after Stop", listOf(first), posts.ids)

        store.send(Action.FailedSendRetryTapped(second))
        waitForAdmission(listOf(second), store)
        settle()
        assertEquals("Only what's retried goes", listOf(first, second), posts.ids)
        assertEquals("A stopped unposted send's first request is not a retry", listOf(1, 1), identities.value.map { it.attempt })
        h.skipInFlightEffects()
    }

    @Test fun stopWithNothingRunningOnTheServerClearsAtOnce() = outboxTest { h ->
        val posts = Posts()
        val slow = Gate()
        val store = h.store(h.idle(draft = "hello"), posts, post = { _, _ -> slow.wait() }, dependencies = {
            this[OpenCodeClient] = this[OpenCodeClient].copy(interrupt = { _, _ -> false })
        })

        store.send(Action.SendTapped)
        assertTrue("Waiting for its send, the session looks busy", store.state.isRunning)
        store.send(Action.InterruptTapped) { it.copy(isInterrupting = true) }
        store.receive(Action.InterruptAcknowledged(sessionID = session.id, generation = 2, interrupted = false)) { it.copy(isInterrupting = false) }
        slow.open()
        h.skipInFlightEffects()
    }

    @Test fun stopOnADraftCallsOffItsCreationAndReturnsTheWords() = outboxTest { h ->
        val created = OpenCodeSession(id = "ses_new", serverTitle = "New session", projectID = "p", directory = "/project", updatedAt = SessionTestSupport.distantPast)
        val posts = Posts()
        val slowCreate = Gate()
        val store = h.store(h.draftState("Find the flaky test"), posts, dependencies = {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                createSession = { _, _, _ ->
                    slowCreate.wait()
                    created
                },
            )
        })

        store.send(Action.SendTapped)
        type("And fix it", store)
        store.send(Action.SendTapped)
        assertTrue(store.state.isRunning)
        store.send(Action.InterruptTapped)
        assertEquals("Their words come back, in order", "Find the flaky test\n\nAnd fix it", store.state.draft)
        assertTrue(store.state.items.isEmpty())
        assertTrue(store.state.queuedInputs.isEmpty())
        assertFalse(store.state.isRunning)
        slowCreate.open()
        settle()
        assertTrue("Nothing is posted", posts.all.isEmpty())
        assertNull(store.state.session)
        store.finish()
    }

    // endregion

    // region Creating the session

    @Test fun aRetriedSessionCreationUsesTheSameID() = outboxTest { h ->
        val posts = Posts()
        val ids = LockIsolated(listOf<String?>())
        val store = h.store(h.draftState("Find the flaky test"), posts, dependencies = {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                createSession = { _, directory, id ->
                    val count = ids.withValue { val next = it + id; next to next.size }
                    if (count == 1) throw OpenCodeClientError.unreachable(TransportFailure.Interrupted)
                    OpenCodeSession(id = id ?: "ses_server", serverTitle = "New session", projectID = "p", directory = directory, updatedAt = SessionTestSupport.distantPast)
                },
                getSession = { _, id ->
                    OpenCodeSession(id = id, serverTitle = "New session", projectID = "p", directory = "/project", updatedAt = SessionTestSupport.distantPast)
                },
            )
        })

        store.send(Action.SendTapped)
        store.receive(Action.SessionCreated::class)
        assertEquals(2, ids.value.size)
        assertEquals("A retry after a lost response can't make a second session", ids.value[0], ids.value[1])
        assertEquals(true, ids.value[0]?.startsWith("ses_"))
        assertEquals(ids.value[0], store.state.session?.id)
        h.skipInFlightEffects()
    }

    // endregion

    // region Retry-After

    @Test fun aTooManyRequestsWaitsAsLongAsTheServerAsks() = outboxTest { h ->
        val posts = Posts()
        val clock = TestClock()
        val store = h.store(h.idle(draft = "hello"), posts, post = { _, attempt ->
            if (attempt == 1) throw OpenCodeClientError(message = "The server returned HTTP 429.", statusCode = 429, retryAfter = 30.0)
        }, dependencies = { this[Clock] = clock })

        store.send(Action.SendTapped)
        waitForRetry(posts, store)
        settle()
        clock.advance(5.seconds)
        settle()
        assertEquals("Not before the 30 s it asked for", 1, posts.count)
        clock.advance(25.seconds)
        waitForAdmission(listOf(first), store)
        assertEquals(2, posts.count)
        h.skipInFlightEffects()
    }

    // endregion

    // region Stop, simplified

    @Test fun aSendStoppedMidPostThatFailsIsAFailedSendAndTheLaneGoesOn() = outboxTest { h ->
        val posts = Posts()
        val slow = Gate()
        val store = h.store(h.running(draft = "Use the staging bucket"), posts, post = { id, attempt ->
            if (id == first && attempt == 1) {
                slow.wait()
                throw transient
            }
        }, dependencies = {
            this[OpenCodeClient] = this[OpenCodeClient].copy(interrupt = { _, _ -> true })
        })

        store.send(Action.SendTapped)
        settle()
        store.send(Action.InterruptTapped)
        store.receive(Action.InterruptAcknowledged::class)
        slow.open()
        store.receive(requestFailed)
        assertEquals("Not silently held: a failed send with Retry", listOf(first), store.state.failedSends.ids)
        settle()
        assertEquals("Not tried again after Stop", listOf(first), posts.ids)

        store.send(Action.FailedSendRetryTapped(first))
        waitForAdmission(listOf(first), store)
        type("Then check the share extension", store)
        store.send(Action.SendTapped)
        waitForAdmission(listOf(second), store)
        assertEquals("The lane goes on", listOf(first, first, second), posts.ids)
        h.skipInFlightEffects()
    }

    @Test fun aSendStoppedWhileWaitingToRetryCanBeRetriedAtOnce() = outboxTest { h ->
        val posts = Posts()
        val clock = TestClock()
        val store = h.store(h.running(draft = "Use the staging bucket"), posts, post = { _, attempt -> if (attempt == 1) throw transient }, dependencies = {
            this[Clock] = clock
            this[OpenCodeClient] = this[OpenCodeClient].copy(interrupt = { _, _ -> true })
        })

        store.send(Action.SendTapped)
        waitForRetry(posts, store)
        store.send(Action.InterruptTapped)
        assertEquals("Held at once", listOf(first), store.state.failedSends.ids)
        assertEquals(SessionFeature.stoppedReason, store.state.failedSends.withID(first)?.reason)
        store.send(Action.FailedSendRetryTapped(first))
        waitForAdmission(listOf(first), store)
        settle()
        assertEquals(listOf(first, first), posts.ids)
        assertTrue(store.state.failedSends.isEmpty())
        h.skipInFlightEffects()
    }

    @Test fun aSendAdmittedLateAfterStopIsntInterruptedAgain() = outboxTest { h ->
        val posts = Posts()
        val interrupts = LockIsolated(0)
        val slow = Gate()
        val store = h.store(h.running(draft = "Use the staging bucket"), posts, post = { _, _ -> slow.wait() }, dependencies = {
            this[OpenCodeClient] = this[OpenCodeClient].copy(interrupt = { _, _ -> interrupts.increment(); true })
        })

        store.send(Action.SendTapped)
        settle()
        store.send(Action.InterruptTapped)
        store.receive(Action.InterruptAcknowledged::class)
        slow.open()
        waitForAdmission(listOf(first), store)
        settle()
        assertEquals("The run it starts is the reader's to stop", 1, interrupts.value)
        assertTrue("Delivered", store.state.failedSends.isEmpty())
        h.skipInFlightEffects()
    }

    @Test fun stopOnADraftWhoseSessionExistsIsThatSessionsStop() = outboxTest { h ->
        val created = OpenCodeSession(id = "ses_new", serverTitle = "New session", projectID = "p", directory = "/project", updatedAt = SessionTestSupport.distantPast)
        val posts = Posts()
        val creations = LockIsolated(0)
        val interrupted = LockIsolated(listOf<String>())
        val slow = Gate()
        val store = h.store(h.draftState("Find the flaky test"), posts, post = { id, _ -> if (id == first) slow.wait() }, dependencies = {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                createSession = { _, _, _ ->
                    creations.increment()
                    created
                },
                getSession = { _, _ -> created },
                interrupt = { _, id -> interrupted.append(id); true },
            )
        })

        store.send(Action.SendTapped)
        type("And fix it", store)
        store.send(Action.SendTapped)
        assertTrue("Created, and the first send is being posted", eventually { creations.value == 1 && posts.count == 1 })
        store.send(Action.InterruptTapped)
        store.receive(Action.SessionCreated::class)
        store.receive(Action.InterruptTapped)
        assertEquals("The screen moves to the session", created, store.state.session)
        assertTrue("Nothing goes back into a draft whose session exists", store.state.draft.isEmpty())
        assertEquals("Its unsent send is held, as any session's Stop does", listOf(second), store.state.failedSends.ids)
        assertTrue(eventually { interrupted.value == listOf(created.id) })

        slow.open()
        waitForAdmission(listOf(first), store)
        assertEquals(1, creations.value)
        assertEquals(listOf(first), posts.ids)
        h.skipInFlightEffects()
    }

    // endregion

    // region Deferred changes the server refuses

    @Test fun aRefusedMoveToQueueWhileAwayComesBackAsPosted() = outboxTest { h ->
        val root = session
        val child = OpenCodeSession(id = "ses_child", serverTitle = "Inspect", projectID = "p", directory = "/project", updatedAt = SessionTestSupport.distantPast, parentID = root.id)
        val state = h.running(draft = "Use the staging bucket").copy(familySessions = listOf(root, child))
        val outbox = newOutbox()
        val posts = Posts()
        val patches = LockIsolated(0)
        val slow = Gate()
        val store = h.store(state, posts, post = { id, _ -> if (id == first) slow.wait() }, dependencies = {
            this[PromptOutbox] = outbox
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                setPendingInputDelivery = { _, _, _, _ ->
                    patches.increment()
                    throw OpenCodeClientError(message = "The server returned HTTP 409.", statusCode = 409)
                },
            )
        })

        store.send(Action.SendTapped)
        settle()
        store.send(Action.PendingInputMoveToQueueTapped(first))
        store.send(Action.FamilyPicked(child.id))
        slow.open()
        assertTrue(eventually { outbox.contents(server.persistenceID, root.id).admitted.size == 1 })
        assertEquals(1, patches.value)
        assertEquals(listOf(OpenCodeDelivery.Steer), outbox.contents(server.persistenceID, root.id).admitted.map { it.delivery })
        store.send(Action.FamilyPicked(root.id))
        assertEquals(listOf(first), store.state.queuedInputs.map { it.id })
        assertEquals("As the server has it", listOf(OpenCodeDelivery.Steer), store.state.queuedInputs.map { it.delivery })
        h.skipInFlightEffects()
    }

    @Test fun aRefusedDeleteKeepsTheMessageAndReadsTheInboxAgain() = outboxTest { h ->
        val posts = Posts()
        val reads = LockIsolated(0)
        val admitted = LockIsolated(false)
        val slow = Gate()
        val store = h.store(h.running(draft = "Use the staging bucket"), posts, post = { id, _ ->
            if (id == first) slow.wait()
            admitted.setValue(true)
        }, dependencies = {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                cancelPendingInput = { _, _, _ -> throw OpenCodeClientError(message = "The server returned HTTP 409.", statusCode = 409) },
                pendingInputs = { _, _ ->
                    reads.increment()
                    if (admitted.value) listOf(OpenCodePendingInput(id = first, text = "Use the staging bucket", delivery = OpenCodeDelivery.Steer)) else emptyList()
                },
            )
        })

        store.send(Action.SendTapped)
        settle()
        store.send(Action.PendingInputCancelTapped(first))
        assertTrue(store.state.queuedInputs.isEmpty())
        val before = reads.value
        slow.open()
        store.receive(Action.PendingInputUpdateFailed::class)
        store.receive(Action.PendingInputsResponse::class, timeout = 2.seconds)
        assertTrue("The inbox is read again", reads.value > before)
        assertEquals("The server still has it, so it's back on screen", listOf(first), store.state.queuedInputs.map { it.id })
        h.skipInFlightEffects()
    }

    // endregion

    // region The retry budget

    private val offline = OpenCodeClientError.unreachable(TransportFailure.NotSent)

    private fun DependencyValues.Builder.offlinePath() {
        this[NetworkPathClient] = this[NetworkPathClient].copy(isOffline = { true }, available = { awaitCancellation() })
    }

    @Test fun offlinePastTheBudgetIsAFailedSendWithNoAttemptAfterIt() = outboxTest { h ->
        val posts = Posts()
        val probes = LockIsolated(0)
        val clock = TestClock()
        val store = h.store(h.idle(draft = "Ship it from the train"), posts, post = { _, _ -> throw offline }, dependencies = {
            this[Clock] = clock
            offlinePath()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                message = { _, _, _ ->
                    probes.increment()
                    throw OpenCodeClientError(message = "Missing", statusCode = 404)
                },
            )
        })

        store.send(Action.SendTapped)
        waitForRetry(posts, store)
        settle()
        val probesBefore = probes.value
        clock.advance(299.seconds)
        settle()
        assertEquals("Offline, it waits for the network rather than spending attempts", 1, posts.count)
        assertEquals("A request that never left isn't looked for", 0, probes.value)
        clock.advance(2.seconds)
        store.receive(requestFailed, timeout = 2.seconds)
        clock.advance(120.seconds)
        settle()
        assertEquals("No attempt once the budget ran out", 1, posts.count)
        assertEquals("Nor another look for it", probesBefore, probes.value)
        h.skipInFlightEffects()
    }

    @Test fun theServerAnsweringEndsAConnectionBackoffAtOnce() = outboxTest { h ->
        val posts = Posts()
        val clock = TestClock()
        val answers = Channel<Unit>(Channel.UNLIMITED)
        val lost = OpenCodeClientError.unreachable(TransportFailure.Interrupted)
        val store = h.store(h.idle(draft = "hello"), posts, post = { _, attempt -> if (attempt == 1) throw lost }, dependencies = {
            this[Clock] = clock
            this[NetworkPathClient] = this[NetworkPathClient].copy(serverAnswered = { _ -> answers.receive() })
        })

        store.send(Action.SendTapped)
        waitForRetry(posts, store)
        settle()
        assertEquals(1, posts.count)
        // Another request got through: the route is back. No clock advance.
        answers.send(Unit)
        waitForAdmission(listOf(first), store)
        assertEquals(2, posts.count)
        h.skipInFlightEffects()
    }

    @Test fun deletingASendWaitingForTheNetworkWithdrawsIt() = outboxTest { h ->
        val posts = Posts()
        val clock = TestClock()
        val store = h.store(h.running(draft = "Use the staging bucket"), posts, post = { _, _ -> throw offline }, dependencies = {
            this[Clock] = clock
            offlinePath()
        })

        store.send(Action.SendTapped)
        waitForRetry(posts, store)
        store.send(Action.PendingInputCancelTapped(first))
        clock.advance(60.seconds)
        settle()
        assertEquals(1, posts.count)
        assertNull(store.state.promptStates[first])
        assertTrue(store.state.queuedInputs.isEmpty())
        store.finish()
    }

    @Test fun stoppingASendWaitingForTheNetworkHoldsItAsStopped() = outboxTest { h ->
        val posts = Posts()
        val clock = TestClock()
        val store = h.store(h.running(draft = "Use the staging bucket"), posts, post = { _, _ -> throw offline }, dependencies = {
            this[Clock] = clock
            offlinePath()
            this[OpenCodeClient] = this[OpenCodeClient].copy(interrupt = { _, _ -> true })
        })

        store.send(Action.SendTapped)
        waitForRetry(posts, store)
        store.send(Action.InterruptTapped)
        clock.advance(60.seconds)
        settle()
        assertEquals(1, posts.count)
        assertEquals(SessionFeature.stoppedReason, store.state.failedSends.withID(first)?.reason)
        h.skipInFlightEffects()
    }

    // endregion

    // region Riding out an outage

    /**
     * A server down for two minutes (a Mac waking, a restart, a tunnel finding its way back)
     * doesn't fail the send: it keeps trying and goes once the server answers.
     */
    @Test fun aSendOutlastsAnOutageLongerThanTheOldBudget() = outboxTest { h ->
        val posts = Posts()
        val store = h.store(h.idle(draft = "Ship it after the outage"), posts, post = { _, attempt ->
            // About two minutes of 503s and dropped connections.
            if (attempt <= 12) {
                throw if (attempt % 2 == 0) {
                    OpenCodeClientError.unreachable(TransportFailure.Interrupted)
                } else {
                    OpenCodeClientError(message = "The server returned HTTP 503.", statusCode = 503)
                }
            }
        })

        store.send(Action.SendTapped)
        waitForAdmission(listOf(first), store)
        assertEquals(13, posts.count)
        assertTrue("Never shown as Couldn’t send", store.state.failedSends.isEmpty())
        h.skipInFlightEffects()
    }

    /** Validation, auth, and other answers sending again can't change still fail at once. */
    @Test fun aFailureThatCantPassStillFailsAtOnce() {
        for (status in listOf(400, 401, 404, 409, 422, 500)) {
            outboxTest { h ->
                val posts = Posts()
                val store = h.store(h.idle(draft = "hello"), posts, post = { _, _ ->
                    throw OpenCodeClientError(message = "The server returned HTTP $status.", statusCode = status)
                })

                store.send(Action.SendTapped)
                store.receive(requestFailed, timeout = 2.seconds)
                assertEquals("HTTP $status", 1, posts.count)
                assertEquals("HTTP $status", listOf(first), store.state.failedSends.ids)
                h.skipInFlightEffects()
            }
        }
    }

    /** After the window it's offered for Retry, having tried far more than five times. */
    @Test fun aSendStillFailingAfterItsWindowIsOfferedForRetry() = outboxTest { h ->
        val posts = Posts()
        val store = h.store(h.idle(draft = "hello"), posts, post = { _, _ ->
            throw OpenCodeClientError(message = "The server returned HTTP 503.", statusCode = 503)
        })

        store.send(Action.SendTapped)
        store.receive(requestFailed, timeout = 5.seconds)
        assertTrue("${posts.count} attempts", posts.count > 20)
        assertEquals(listOf(first), store.state.failedSends.ids)
        h.skipInFlightEffects()
    }

    /**
     * The server answering while an attempt was still out (the event stream
     * reconnecting during a POST that then times out) means the route is
     * back: it goes again at once rather than waiting out its backoff.
     */
    @Test fun theServerAnsweringDuringAnAttemptRetriesAtOnce() = outboxTest { h ->
        val posts = Posts()
        val clock = TestClock()
        val answers = Channel<Unit>(Channel.UNLIMITED)
        val attemptOut = Gate()
        val timeout = Gate()
        val store = h.store(h.idle(draft = "hello"), posts, post = { _, attempt ->
            if (attempt == 1) {
                attemptOut.open()
                timeout.wait()
                throw OpenCodeClientError.unreachable(TransportFailure.TimedOut)
            }
        }, dependencies = {
            this[Clock] = clock
            this[NetworkPathClient] = this[NetworkPathClient].copy(serverAnswered = { _ -> answers.receive() })
        })

        store.send(Action.SendTapped)
        attemptOut.wait()
        answers.send(Unit)
        settle()
        timeout.open()
        // No clock advance: the backoff never runs.
        waitForAdmission(listOf(first), store)
        assertEquals(2, posts.count)
        h.skipInFlightEffects()
    }

    /**
     * Time in the background doesn't spend the window: a send waiting for
     * the network when the app was suspended gets a fresh window when it
     * runs out, rather than failing the moment the app comes back.
     */
    @Test fun timeInTheBackgroundDoesNotSpendTheWindow() = outboxTest { h ->
        val posts = Posts()
        val clock = TestClock()
        val phases = Channel<AppPhase>(Channel.UNLIMITED)
        val store = h.store(h.idle(draft = "Ship it from the train"), posts, post = { _, _ -> throw offline }, dependencies = {
            this[Clock] = clock
            offlinePath()
            this[NetworkPathClient] = this[NetworkPathClient].copy(appPhases = { phases.receiveAsFlow() })
        })

        store.send(Action.SendTapped)
        waitForRetry(posts, store)
        settle()
        clock.advance(60.seconds)
        phases.send(AppPhase.Background)
        settle()
        // Suspended past the window; it runs out as the app comes back.
        clock.advance(250.seconds)
        settle()
        assertTrue("It waits again with a fresh window", store.state.failedSends.isEmpty())
        // Back in the foreground after another long suspension (no second
        // background entry: the window restarted while still in the background).
        clock.advance(200.seconds)
        phases.send(AppPhase.Active)
        settle()
        clock.advance(110.seconds)
        settle()
        assertTrue("Coming back starts the window over too", store.state.failedSends.isEmpty())
        // Five more minutes offline in the foreground do end it.
        clock.advance(301.seconds)
        store.receive(requestFailed, timeout = 2.seconds)
        assertEquals(1, posts.count)
        h.skipInFlightEffects()
    }

    /**
     * A server that answers while every attempt is out (reads get through,
     * this request doesn't) gets one immediate retry, then the backoff.
     */
    @Test fun aReconnectDuringEveryAttemptStillBacksOff() = outboxTest { h ->
        val posts = Posts()
        val clock = TestClock()
        val answers = Channel<Unit>(Channel.UNLIMITED)
        val store = h.store(h.idle(draft = "hello"), posts, post = { _, attempt ->
            if (attempt <= 2) {
                answers.send(Unit)
                delay(100.milliseconds)
                throw OpenCodeClientError.unreachable(TransportFailure.Interrupted)
            }
        }, dependencies = {
            this[Clock] = clock
            this[NetworkPathClient] = this[NetworkPathClient].copy(serverAnswered = { _ -> answers.receive() })
        })

        store.send(Action.SendTapped)
        waitForRetry(posts, store, attempts = 2)
        delay(200.milliseconds)
        settle()
        assertEquals("The first reconnect retried at once; the second waits", 2, posts.count)
        clock.advance(2.seconds)
        waitForAdmission(listOf(first), store)
        assertEquals(3, posts.count)
        h.skipInFlightEffects()
    }

    // endregion

    /** A second phase of a test on a fresh [Harness] (own scope and shared store) after the first's effects were skipped. */
    private suspend fun reopenedHarness(block: suspend (Harness) -> Unit) {
        kotlinx.coroutines.coroutineScope {
            val harness = Harness(this)
            try {
                block(harness)
            } finally {
                harness.skipInFlightEffects()
            }
        }
    }
}
