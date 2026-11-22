package ai.opencode.feature.session

import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.store.TestStore
import ai.opencode.feature.composer.DictationClient
import ai.opencode.feature.composer.DictationError
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.feature.composer.DraftState
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.Gate
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertEquals
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertNull
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertTrue
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.testSession
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import ai.opencode.server.model.OpenCodeJSONValue
import org.junit.Test

class SessionFeatureTestsRecordingLock {
    private fun hold(text: String, isLocked: Boolean = false, armed: DictationFeature.Hold.Arm? = null) =
        DictationFeature.Hold(text = text, isLocked = isLocked, armed = armed)

    private fun State.withHold(transform: (DictationFeature.Hold) -> DictationFeature.Hold): State =
        withDictationHold(dictationHold?.let(transform))

    private fun finishing(send: OpenCodeDelivery?) = DictationFeature.Phase.Finishing(send = send)

    /** The effect throws, which fails the test at the next store call. */
    private fun issue(message: String): Nothing = throw AssertionError(message)

    /**
     * Let go to send, then pressed again before the last words landed: the
     * first message still goes out whole, and the second records meanwhile.
     */
    @Test
    fun aPressWhileTheLastMessageFinalizesRecordsTheNext() = sessionTest { h ->
        val first = Channel<String>(Channel.UNLIMITED)
        val second = Channel<String>(Channel.UNLIMITED)
        val streams = LockIsolated(listOf(first, second))
        val sent = LockIsolated<List<String>>(emptyList())
        val store = h.store(h.makeState(draft = "Hey")) {
            this[UuidClient] = UuidClient.incrementing()
            this[DictationClient] = DictationClient(start = { streams.withValue { it.drop(1) to it.first() } })
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, text, _, _, _, _ -> sent.append(text) },
                waitForIdle = { _, _ -> },
                getSession = { _, _ -> testSession() },
                transcript = { _, _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.micPressBegan)
        store.receive(Action.dictationReady) { it.withDictationPhase(DictationFeature.Phase.Recording) }
        first.trySend("first message")
        store.receive(Action.dictationUpdated("first message"))
        store.send(Action.micPressReleased) { it.withDictationPhase(finishing(OpenCodeDelivery.Steer)) }

        store.send(Action.micPressBegan) {
            it.withDictationPhase(DictationFeature.Phase.Preparing)
                .withDictationHold(hold(""))
                .withDictationRecording(1)
                .withDraftState(DraftState())
        }
        assertEquals("Hey first message", store.state.sendingDictation?.draft?.text)
        store.receive(Action.dictationReady) { it.withDictationPhase(DictationFeature.Phase.Recording) }

        // The first recording's last words land, and it goes out on its own.
        first.trySend("first message, all of it")
        store.receive(Action.dictationUpdated("first message, all of it", recording = 0))
        first.close()
        store.receive(Action.dictationStopped(errorMessage = null, recording = 0))
        // The dictation actor finalizes on a real thread: wait in real time (bounded), not a yield count.
        for (i in 0 until 300) {
            if (sent.value.isNotEmpty()) break
            store.skipReceivedActions()
            delay(10)
        }
        assertEquals(listOf("Hey first message, all of it"), sent.value)
        assertNull(store.state.sendingDictation)
        assertEquals(DictationFeature.Phase.Recording, store.state.dictationPhase, "the second recording keeps going")
        assertEquals("", store.state.draft)

        second.trySend("second")
        store.receive(Action.dictationUpdated("second", recording = 1)) { it.withHold { hold -> hold.copy(text = "second") } }
        h.skipInFlightEffects()
    }

    @Test
    fun slidingLeftCancelsAHeldMicWithoutSending() = sessionTest { h ->
        val feed = Channel<String>(Channel.UNLIMITED)
        val calls = LockIsolated<List<String>>(emptyList())
        val store = h.store(h.makeState(draft = "Typed")) {
            this[DictationClient] = DictationClient(
                start = { feed },
                stop = {
                    calls.append("stop")
                    feed.close()
                },
                // Discarding: the audio and the model's work are thrown away at once.
                cancel = {
                    calls.append("cancel")
                    feed.close()
                },
            )
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, _ -> issue("A cancelled recording must not send") },
            )
        }
        store.exhaustive = false

        store.send(Action.micPressBegan) {
            it.withDictationPhase(DictationFeature.Phase.Preparing).withDictationHold(hold("Typed"))
        }
        store.receive(Action.dictationReady) { it.withDictationPhase(DictationFeature.Phase.Recording) }
        feed.trySend("never mind")
        store.receive(Action.dictationUpdated("never mind")) { it.withHold { hold -> hold.copy(text = "Typed never mind") } }
        store.send(Action.micPressCancelled) {
            it.withDictationPhase(DictationFeature.Phase.Idle).withDictationHold(null)
        }
        assertEquals("Typed", store.state.draft)
        store.finish()
        assertTrue(store.state.items.isEmpty())
        assertEquals(listOf("cancel"), calls.value, "A cancel discards rather than finalizing")
    }

    @Test
    fun lockedRecordingIgnoresReleaseAndInsertsIntoDraftOnStop() = sessionTest { h ->
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = h.store(h.makeState(draft = "Check")) {
            this[DictationClient] = DictationClient(
                start = { feed },
                stop = {
                    feed.trySend("the failing test suite")
                    feed.close()
                },
            )
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, _ ->
                    issue("Stopping a locked recording must leave the transcription in the draft, not send it")
                },
            )
        }
        store.exhaustive = false

        store.send(Action.micPressBegan) {
            it.withDictationPhase(DictationFeature.Phase.Preparing).withDictationHold(hold("Check"))
        }
        store.receive(Action.dictationReady) { it.withDictationPhase(DictationFeature.Phase.Recording) }
        store.send(Action.micPressLocked) { it.withHold { hold -> hold.copy(isLocked = true) } }
        // The finger lifting after the tap/lock must not end or send the recording.
        store.send(Action.micPressReleased)
        assertTrue(store.state.isDictating)
        feed.trySend("the failing test")
        store.receive(Action.dictationUpdated("the failing test")) { it.withHold { hold -> hold.copy(text = "Check the failing test") } }
        assertEquals("Check", store.state.draft)
        store.send(Action.dictationInsertTapped) { it.withDictationPhase(finishing(null)) }
        store.receive(Action.dictationUpdated("the failing test suite")) {
            it.withHold { hold -> hold.copy(text = "Check the failing test suite") }
        }
        store.receive(Action.dictationStopped(errorMessage = null)) {
            it.withDictationPhase(DictationFeature.Phase.Idle).withDictationHold(null).withDraft("Check the failing test suite")
        }
        assertTrue(store.state.items.isEmpty())
        store.finish()
    }

    data class SentPrompt(val text: String, val attachments: Int, val delivery: OpenCodeDelivery)

    private class LockedRecording(
        val store: TestStore<State, Action>,
        val sent: LockIsolated<List<SentPrompt>>,
        val feed: Channel<String>,
    )

    /**
     * A locked recording (tapped, or slid up and released) whose engine says
     * `words` so far and `final` when stopped. Every send is recorded.
     */
    private suspend fun Harness.lockedRecording(
        state: State,
        words: String?,
        final: String?,
        finishesOnStop: Boolean = true,
    ): LockedRecording {
        val feed = Channel<String>(Channel.UNLIMITED)
        val sent = LockIsolated<List<SentPrompt>>(emptyList())
        val store = store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[DictationClient] = DictationClient(
                start = { feed },
                stop = {
                    if (finishesOnStop) {
                        if (final != null) feed.trySend(final)
                        feed.close()
                    }
                },
            )
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, text, attachments, _, _, delivery -> sent.append(SentPrompt(text, attachments.size, delivery)) },
                waitForIdle = { _, _ -> },
                getSession = { _, _ -> testSession() },
                transcript = { _, _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
                message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
            )
        }
        store.exhaustive = false
        store.send(Action.micPressBegan)
        store.receive(Action.dictationReady) { it.withDictationPhase(DictationFeature.Phase.Recording) }
        store.send(Action.micPressLocked) { it.withHold { hold -> hold.copy(isLocked = true) } }
        if (words != null) {
            feed.trySend(words)
            store.receive(::isDictationUpdated, "dictationUpdated")
        }
        return LockedRecording(store, sent, feed)
    }

    private fun isDictationUpdated(action: Action) = (action as? Action.Dictation)?.action is DictationFeature.Action.Updated
    private fun isDictationStopped(action: Action) = (action as? Action.Dictation)?.action is DictationFeature.Action.Stopped

    @Test
    fun lockedBarSendSendsTheWordsWithTheDraftAndAttachments() = sessionTest { h ->
        val state = h.makeState(draft = "Check")
            .withPendingAttachments(listOf(OpenCodeOutgoingAttachment(data = byteArrayOf(1, 2, 3), mime = "image/png")))
        val recording = h.lockedRecording(state, words = "the failing", final = "the failing test suite")
        val store = recording.store
        assertEquals("Check", store.state.draft, "Recording keeps its words off the draft")
        store.send(Action.dictationSendTapped) { it.withDictationPhase(finishing(OpenCodeDelivery.Steer)) }
        store.receive(::isDictationStopped, "dictationStopped")
        store.receive(Action.DeferredSendReady(OpenCodeDelivery.Steer))
        store.finish()
        assertEquals(listOf(SentPrompt("Check the failing test suite", 1, OpenCodeDelivery.Steer)), recording.sent.value)
        assertEquals("", store.state.draft)
        assertTrue(store.state.pendingAttachments.isEmpty())
        assertNull(store.state.dictationHold)
    }

    @Test
    fun lockedBarSendDuringARunSteersAndItsMenuQueues() {
        for ((action, delivery) in listOf(Action.dictationSendTapped to OpenCodeDelivery.Steer, Action.dictationQueueTapped to OpenCodeDelivery.Queue)) {
            sessionTest { h ->
                val recording = h.lockedRecording(h.makeState(isRunning = true), words = "Also check", final = "Also check the docs")
                val store = recording.store
                store.send(action) { it.withDictationPhase(finishing(delivery)) }
                store.receive(::isDictationStopped, "dictationStopped")
                store.finish()
                assertEquals(listOf(SentPrompt("Also check the docs", 0, delivery)), recording.sent.value)
            }
        }
    }

    @Test
    fun lockedBarSendBeforeAnyWordsSendsOnlyAnExistingDraft() = sessionTest { h ->
        val empty = h.lockedRecording(h.makeState(), words = null, final = null)
        empty.store.send(Action.dictationSendTapped)
        empty.store.receive(::isDictationStopped, "dictationStopped")
        empty.store.finish()
        assertTrue(empty.sent.value.isEmpty(), "Nothing to send: Send does nothing")
        assertEquals(DictationFeature.Phase.Idle, empty.store.state.dictationPhase)
        assertTrue(empty.store.state.items.isEmpty())

        val typed = h.lockedRecording(h.makeState(draft = "Typed first"), words = null, final = null)
        typed.store.send(Action.dictationSendTapped)
        typed.store.receive(::isDictationStopped, "dictationStopped")
        typed.store.finish()
        assertEquals(listOf(SentPrompt("Typed first", 0, OpenCodeDelivery.Steer)), typed.sent.value)
    }

    /** Stop, then Send before the engine has finished: the words go with the message. */
    @Test
    fun sendWhileFinishingAfterStopSendsTheWords() = sessionTest { h ->
        val recording = h.lockedRecording(h.makeState(), words = "Rename", final = null, finishesOnStop = false)
        val store = recording.store
        store.send(Action.dictationInsertTapped) { it.withDictationPhase(finishing(null)) }
        store.send(Action.dictationSendTapped) { it.withDictationPhase(finishing(OpenCodeDelivery.Steer)) }
        recording.feed.trySend("Rename the tab")
        recording.feed.close()
        store.receive(::isDictationStopped, "dictationStopped")
        store.finish()
        assertEquals(listOf(SentPrompt("Rename the tab", 0, OpenCodeDelivery.Steer)), recording.sent.value)
    }

    /** Sliding only arms; letting go commits what is armed. */
    @Test
    fun slidingArmsAndReleasingCommits() {
        for (arm in listOf(DictationFeature.Hold.Arm.Lock, DictationFeature.Hold.Arm.Cancel, null)) sessionTest { h ->
            val feed = Channel<String>(Channel.UNLIMITED)
            val sent = LockIsolated<List<String>>(emptyList())
            val store = h.store(h.makeState(draft = "Typed")) {
                this[UuidClient] = UuidClient.incrementing()
                this[DictationClient] = DictationClient(start = { feed }, stop = { feed.close() })
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    sendPrompt = { _, _, text, _, _, _, _ -> sent.append(text) },
                    waitForIdle = { _, _ -> },
                    getSession = { _, _ -> testSession() },
                    transcript = { _, _, _ -> emptyList() },
                    familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                    pendingInputs = { _, _ -> emptyList() },
                )
            }
            store.exhaustive = false
            store.send(Action.micPressBegan)
            store.receive(Action.dictationReady) { it.withDictationPhase(DictationFeature.Phase.Recording) }
            feed.trySend("and more")
            store.receive(::isDictationUpdated, "dictationUpdated")
            // Slide past both thresholds and back: arming alone commits nothing.
            store.send(Action.micPressArmed(DictationFeature.Hold.Arm.Lock)) { it.withHold { hold -> hold.copy(armed = DictationFeature.Hold.Arm.Lock) } }
            store.send(Action.micPressArmed(DictationFeature.Hold.Arm.Cancel)) { it.withHold { hold -> hold.copy(armed = DictationFeature.Hold.Arm.Cancel) } }
            store.send(Action.micPressArmed(null)) { it.withHold { hold -> hold.copy(armed = null) } }
            assertTrue(store.state.isDictating)
            assertEquals(false, store.state.dictationHold?.isLocked)
            if (arm != null) store.send(Action.micPressArmed(arm)) { it.withHold { hold -> hold.copy(armed = arm) } }

            store.send(Action.micPressReleased)
            when (arm) {
                DictationFeature.Hold.Arm.Lock -> {
                    assertEquals(hold("Typed and more", isLocked = true, armed = DictationFeature.Hold.Arm.Lock), store.state.dictationHold)
                    assertTrue(store.state.isDictating)
                    feed.close()
                    store.finish()
                    assertTrue(sent.value.isEmpty())
                }
                DictationFeature.Hold.Arm.Cancel -> {
                    store.receive(Action.micPressCancelled)
                    store.finish()
                    assertNull(store.state.dictationHold)
                    assertEquals("Typed", store.state.draft)
                    assertTrue(sent.value.isEmpty())
                }
                null -> {
                    store.finish()
                    assertEquals(listOf("Typed and more"), sent.value)
                }
            }
        }
    }

    /**
     * A held mic whose touch the system takes (Control Center, a call
     * banner, another finger, the app leaving) carries on locked: the user
     * chooses Stop or Send, whether nothing, lock, or cancel was armed.
     */
    @Test
    fun aStolenTouchLocksTheRecordingInsteadOfSending() {
        for (arm in listOf(null, DictationFeature.Hold.Arm.Lock, DictationFeature.Hold.Arm.Cancel)) sessionTest { h ->
            val feed = Channel<String>(Channel.UNLIMITED)
            val store = h.store(h.makeState(draft = "Check")) {
                this[DictationClient] = DictationClient(
                    start = { feed },
                    stop = {
                        feed.trySend("the failing test suite")
                        feed.close()
                    },
                )
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    sendPrompt = { _, _, _, _, _, _, _ -> issue("A stolen touch must not send") },
                )
            }
            store.exhaustive = false
            store.send(Action.micPressBegan)
            store.receive(Action.dictationReady) { it.withDictationPhase(DictationFeature.Phase.Recording) }
            feed.trySend("the failing")
            store.receive(::isDictationUpdated, "dictationUpdated")
            if (arm != null) store.send(Action.micPressArmed(arm))
            store.send(Action.micPressInterrupted) { it.withHold { hold -> hold.copy(isLocked = true, armed = null) } }
            assertEquals(DictationFeature.Phase.Recording, store.state.dictationPhase)
            store.send(Action.dictationInsertTapped)
            store.receive(::isDictationStopped, "dictationStopped")
            store.finish()
            assertNull(store.state.dictationHold)
            assertEquals("Check the failing test suite", store.state.draft, "armed: $arm")
            assertTrue(store.state.items.isEmpty())
        }
    }

    /**
     * The permission prompt takes the touch while setup waits on Allow:
     * recording carries on locked, as when the finger lifts then.
     */
    @Test
    fun aStolenTouchWhilePreparingLocks() = sessionTest { h ->
        val allow = Gate()
        val feed = Channel<String>(Channel.UNLIMITED)
        val stops = LockIsolated(0)
        val store = h.store(h.makeState()) {
            this[DictationClient] = DictationClient(
                start = {
                    allow.await()
                    feed
                },
                stop = { stops.increment() },
            )
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, _ -> issue("A stolen touch must not send") },
            )
        }
        store.exhaustive = false
        store.send(Action.micPressBegan)
        store.send(Action.micPressInterrupted) { it.withHold { hold -> hold.copy(isLocked = true) } }
        assertEquals(DictationFeature.Phase.Preparing, store.state.dictationPhase)
        assertEquals(0, stops.value)
        allow.open()
        store.receive(Action.dictationReady) { it.withDictationPhase(DictationFeature.Phase.Recording) }
        feed.close()
        store.finish()
        assertTrue(store.state.items.isEmpty())
    }

    /**
     * The touch is cancelled while the scene is merely inactive, before it reaches the background.
     * If that ever ends as a release, the send still waits on the engine, and leaving downgrades it
     * to Stop.
     */
    @Test
    fun backgroundingMidHoldNeverSends() = sessionTest { h ->
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = h.store(h.makeState(draft = "Check")) {
            this[DictationClient] = DictationClient(start = { feed }, stop = {})
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, _ -> issue("Leaving the app must not send a dictation") },
            )
        }
        store.exhaustive = false
        store.send(Action.micPressBegan)
        store.receive(Action.dictationReady) { it.withDictationPhase(DictationFeature.Phase.Recording) }
        feed.trySend("the failing")
        store.receive(::isDictationUpdated, "dictationUpdated")
        store.send(Action.micPressReleased) { it.withDictationPhase(finishing(OpenCodeDelivery.Steer)) }
        store.send(Action.SceneEnteredBackground) { it.withDictationPhase(finishing(null)) }
        feed.trySend("the failing test suite")
        feed.close()
        store.receive(::isDictationStopped, "dictationStopped")
        store.finish()
        assertEquals("Check the failing test suite", store.state.draft)
        assertTrue(store.state.items.isEmpty())
    }

    @Test
    fun tappedRecordingCancelDiscardsWordsAndKeepsTypedDraft() = sessionTest { h ->
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = h.store(h.makeState(draft = "Keep this")) {
            this[DictationClient] = DictationClient(start = { feed }, stop = { feed.close() })
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, _ -> issue("Cancelling a locked recording must not send") },
            )
        }
        store.exhaustive = false

        store.send(Action.micPressBegan) {
            it.withDictationPhase(DictationFeature.Phase.Preparing).withDictationHold(hold("Keep this"))
        }
        store.receive(Action.dictationReady) { it.withDictationPhase(DictationFeature.Phase.Recording) }
        store.send(Action.micPressLocked) { it.withHold { hold -> hold.copy(isLocked = true) } }
        feed.trySend("discard me")
        store.receive(Action.dictationUpdated("discard me")) { it.withHold { hold -> hold.copy(text = "Keep this discard me") } }
        store.send(Action.micPressCancelled) { it.withDictationPhase(DictationFeature.Phase.Idle).withDictationHold(null) }
        assertEquals("Keep this", store.state.draft)
        assertTrue(store.state.items.isEmpty())
        store.finish()
    }

    @Test
    fun dictationFailureSurfacesTheReason() = sessionTest { h ->
        val denied = DictationError.MicrophoneDenied().errorDescription
        val store = h.store(h.makeState()) {
            this[DictationClient] = DictationClient(start = { throw DictationError.MicrophoneDenied() }, stop = {})
        }
        store.exhaustive = false

        store.send(Action.micTapped) { it.withDictationPhase(DictationFeature.Phase.Preparing) }
        store.receive(Action.dictationStopped(errorMessage = denied)) {
            it.withDictationPhase(DictationFeature.Phase.Idle).withDictationError(denied)
        }
    }

    @Test
    fun failedDictationFinalizationDoesNotSendAnEmptyTranscript() = sessionTest { h ->
        val denied = DictationError.MicrophoneDenied().errorDescription
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = h.store(h.makeState()) {
            this[DictationClient] = DictationClient(start = { feed }, stop = { feed.close(DictationError.MicrophoneDenied()) })
        }
        store.exhaustive = false

        store.send(Action.micTapped) { it.withDictationPhase(DictationFeature.Phase.Preparing) }
        store.receive(Action.dictationReady) { it.withDictationPhase(DictationFeature.Phase.Recording) }
        store.send(Action.dictationSendTapped) { it.withDictationPhase(finishing(OpenCodeDelivery.Steer)) }
        store.receive(Action.dictationStopped(errorMessage = denied)) {
            it.withDictationPhase(DictationFeature.Phase.Idle).withDictationError(denied)
        }
        assertTrue(store.state.items.isEmpty())
        store.finish()
    }

    @Test
    fun returnKeyOnEmptyDraftJustClearsTheNewline() = sessionTest { h ->
        val state = h.makeState(draft = "\n")
        state.returnKeySends.set(true)
        val store = h.store(state)
        store.exhaustive = false

        store.send(Action.ReturnKeyTapped) { it.withDraft("") }
    }

    @Test
    fun shorterWindowSplicesInsteadOfFreezingTheTranscript() {
        val expanded = listOf(
            OpenCodeTranscriptItem.UserText(id = "m1", text = "one"),
            OpenCodeTranscriptItem.Tool(id = "m2", call = toolCall(name = "read")),
            OpenCodeTranscriptItem.Tool(id = "m3", call = toolCall(name = "edit")),
            OpenCodeTranscriptItem.UserText(id = "m4", text = "two"),
        )
        // A poll returns only the newest messages — fewer items than we hold.
        val window = listOf(
            OpenCodeTranscriptItem.UserText(id = "m4", text = "two"),
            OpenCodeTranscriptItem.UserText(id = "m5", text = "three"),
        )

        assertEquals(
            listOf(
                OpenCodeTranscriptItem.UserText(id = "m1", text = "one"),
                OpenCodeTranscriptItem.Tool(id = "m2", call = toolCall(name = "read")),
                OpenCodeTranscriptItem.Tool(id = "m3", call = toolCall(name = "edit")),
                OpenCodeTranscriptItem.UserText(id = "m4", text = "two"),
                OpenCodeTranscriptItem.UserText(id = "m5", text = "three"),
            ),
            SessionFeature.mergeTranscript(fetched = window, existing = expanded),
        )
    }

    private fun toolCall(name: String) = OpenCodeToolCall(
        callID = name, name = name, status = OpenCodeToolCall.Status.Completed, input = OpenCodeJSONValue.Null, output = emptyList(), metadata = emptyMap(),
    )
}
