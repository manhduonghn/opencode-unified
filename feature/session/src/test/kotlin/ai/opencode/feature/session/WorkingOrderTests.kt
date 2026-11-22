package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.feature.composer.PendingPrompt
import ai.opencode.feature.composer.PromptOutbox
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.testServer
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.user.TranscriptUserMessage
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptFile
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * A message sent into a session from outside its composer (the voice agent,
 * another client) must show before, or with, the Working line it starts:
 * the run's start arrives over the event stream at once, while the page read
 * that has the message can take seconds on a session heavy with images.
 */
class WorkingOrderTests {
    private val server = testServer
    private val session = OpenCodeSession(id = "ses_blog", serverTitle = "Blog game", projectID = "p", directory = "/notes", updatedAt = Instant.EPOCH)
    private val earlier: List<OpenCodeTranscriptItem> = listOf(
        OpenCodeTranscriptItem.UserText(id = "msg_1", text = "Add a lockbox to the desk"),
        OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_2-text-0", text = ""),
    )
    private val voiceText = "Voice delegation. Instruction is the user's own spoken words since the previous delegation.\n{\n  \"instruction\" : \"improve the desk lockbox\"\n}"

    private val voiceInput: OpenCodePendingInput
        get() = OpenCodePendingInput(id = "msg_VOICE", text = voiceText, delivery = OpenCodeDelivery.Steer)

    private fun promptIndex(id: String, items: List<OpenCodeTranscriptItem>): List<Int> =
        items.indices.filter { TranscriptEntry.promptID(items[it]) == id }

    private fun Harness.sessionState(
        cachedItems: List<OpenCodeTranscriptItem> = earlier,
        initialTranscriptRequestIsExternal: Boolean = false,
        isRunning: Boolean = false,
    ) = state {
        SessionFeature.State.session(
            server = server,
            session = session,
            cachedItems = cachedItems,
            initialTranscriptRequestIsExternal = initialTranscriptRequestIsExternal,
            isRunning = isRunning,
        )
    }

    private fun voicePending() = PendingPrompt(messageID = "msg_VOICE", text = voiceText, attachments = emptyList(), requestEpoch = 0)

    /**
     * The real server's order for a steer into an idle session: enqueued,
     * execution started, delivered, all within a millisecond, while the inbox
     * read comes back empty and the page read is still on its way.
     */
    @Test fun anOutsideSendShowsAboveWorkingBeforeThePageRead() = sessionTest { h ->
        val page = Channel<List<OpenCodeTranscriptItem>>(Channel.UNLIMITED)
        val pageReads = LockIsolated(0)
        val store = h.store(h.sessionState()) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                pendingInputs = { _, _ -> emptyList() },
                message = { _, _, _ -> throw OpenCodeClientError(message = "Offline") },
                getSession = { _, _ -> session },
                transcript = { _, _, _ ->
                    pageReads.increment()
                    page.receiveCatching().getOrNull() ?: emptyList()
                },
            )
        }
        store.exhaustive = false

        // Idle, the server delivers it at once: it joins the transcript from its
        // admission, never waiting below Working as steering.
        store.send(Action.InputEnqueued(voiceInput))
        assertEquals("The message shows from its own event", listOf(earlier.size), promptIndex("msg_VOICE", store.state.items))
        assertTrue(store.state.queuedInputs.isEmpty())
        store.send(Action.ExternalRunStateChanged(isRunning = true))
        assertTrue(store.state.isRunning)
        store.send(Action.InputQueueChanged(deliveredID = "msg_VOICE"))

        // Delivered: it's in the transcript, which Working closes, while the
        // page read is still out.
        assertNotNull(store.state.refresh)
        assertEquals(listOf(earlier.size), promptIndex("msg_VOICE", store.state.items))
        assertTrue(store.state.queuedInputs.isEmpty())
        val message = TranscriptEntry.build(store.state.items, isRunning = true).mapNotNull { TranscriptUserMessage.from(it) }.lastOrNull()
        assertEquals("msg_VOICE", message?.id)
        assertNotNull("Still a Voice message", message?.voiceDelegation)

        // The page read lands with the server's copy: one row, in its place.
        page.send(
            earlier + listOf(
                OpenCodeTranscriptItem.UserText(id = "msg_VOICE", text = voiceText),
                OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_3-text-0", text = ""),
            ),
        )
        store.receive(Action.RefreshResponse::class)
        assertEquals(listOf(earlier.size), promptIndex("msg_VOICE", store.state.items))
        assertTrue(store.state.echoedPromptIDs.isEmpty())
        assertTrue(store.state.queuedInputs.isEmpty())
        page.close()
        store.finish()
        assertTrue(pageReads.value >= 1)
    }

    /**
     * A page read that started before the delivery doesn't have the message;
     * it mustn't take the delivered row away again.
     */
    @Test fun anEarlierPageReadKeepsTheDeliveredMessage() = sessionTest { h ->
        val store = h.store(h.sessionState(isRunning = true)) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                pendingInputs = { _, _ -> emptyList() },
                message = { _, _, _ -> throw OpenCodeClientError(message = "Offline") },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> earlier },
            )
        }
        store.exhaustive = false

        store.send(Action.InputEnqueued(voiceInput))
        store.send(Action.InputQueueChanged(deliveredID = "msg_VOICE"))
        store.receive(Action.RefreshResponse::class)
        store.skipReceivedActions()
        assertEquals(listOf(earlier.size), promptIndex("msg_VOICE", store.state.items))
        assertTrue("Never also a pending row", store.state.queuedInputs.isEmpty())
        store.finish()
    }

    /**
     * Mid-run, a steering message waits in the inbox (below Working) until the
     * agent's next step takes it.
     */
    @Test fun aSteerIntoABusySessionWaitsAsSteeringUntilDelivered() = sessionTest { h ->
        val input = voiceInput
        val store = h.store(h.sessionState(isRunning = true)) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                pendingInputs = { _, _ -> listOf(input) },
                message = { _, _, _ -> throw OpenCodeClientError(message = "Offline") },
            )
        }
        store.exhaustive = false

        store.send(Action.InputEnqueued(input))
        store.receive(Action.PendingInputsResponse::class)
        assertEquals(listOf("msg_VOICE"), store.state.queuedInputRows.map { it.id })
        assertEquals("A dim steering bubble in the transcript", false, store.state.queuedInputRows.firstOrNull()?.waitsInQueuedPill)
        assertTrue(promptIndex("msg_VOICE", store.state.items).isEmpty())
        // A second event for the same message (or a delivery change) adds no row.
        store.send(Action.InputEnqueued(input))
        assertEquals(1, store.state.queuedInputs.size)
        store.finish()
    }

    /** Withdrawn before the idle run took it, it goes again. */
    @Test fun anIdleAdmissionCancelledBeforeDeliveryLeaves() = sessionTest { h ->
        val store = h.store(h.sessionState()) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                pendingInputs = { _, _ -> emptyList() },
                message = { _, _, _ -> throw OpenCodeClientError(message = "Offline") },
            )
        }
        store.exhaustive = false
        store.send(Action.InputEnqueued(voiceInput))
        assertEquals(listOf(earlier.size), promptIndex("msg_VOICE", store.state.items))
        store.send(Action.InputCancelled("msg_VOICE"))
        assertEquals(earlier, store.state.items)
        store.finish()
    }

    /** This screen's own sends keep their own rows; an event never adds another. */
    @Test fun anEventForThisScreensOwnSendAddsNothing() = sessionTest { h ->
        val state = h.sessionState(cachedItems = earlier + OpenCodeTranscriptItem.UserText(id = "msg_MINE", text = "Typed"))
            .copy(optimisticPromptIDs = setOf("msg_MINE"))
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(pendingInputs = { _, _ -> emptyList() })
        }
        store.exhaustive = false
        store.send(Action.InputEnqueued(OpenCodePendingInput(id = "msg_MINE", text = "Typed")))
        assertTrue(store.state.queuedInputs.isEmpty())
        assertEquals(1, promptIndex("msg_MINE", store.state.items).size)
        store.finish()
    }

    /**
     * Off screen: the voice agent sent into a busy session; opening it shows
     * the message as steering at once, before any read.
     */
    @Test fun aVoiceSendWaitingOffScreenShowsOnEntry() = sessionTest { h ->
        val outbox = PromptOutbox()
        outbox.recordAdmitted(voicePending(), sessionID = session.id, serverKey = server.persistenceID)
        val store = h.store(h.sessionState(initialTranscriptRequestIsExternal = true, isRunning = true)) {
            this[PromptOutbox] = outbox
        }
        store.exhaustive = false
        store.send(Action.Task)
        assertEquals(listOf("msg_VOICE"), store.state.queuedInputRows.map { it.id })
        assertEquals(false, store.state.queuedInputRows.firstOrNull()?.waitsInQueuedPill)
        assertNotNull(TranscriptUserMessage.from(store.state.queuedInputRows[0])?.voiceDelegation)
        assertFalse("A voice row restored on entry must not own the composer run", store.state.hasPendingLocalPrompt)
        assertFalse(store.state.hasMovingLocalPrompt)
        assertFalse(store.state.hasSteerOnItsWay)
        store.finish()
    }

    /**
     * Off screen: it was delivered (the server said so) before the session was
     * opened, and the cached transcript predates it: it shows as sent, ending
     * the transcript Working closes.
     */
    @Test fun aVoiceSendDeliveredOffScreenShowsAsSentOnEntry() = sessionTest { h ->
        val outbox = PromptOutbox()
        // The delivery can be heard before the send's own response.
        outbox.markDelivered("msg_VOICE")
        outbox.recordAdmitted(voicePending(), sessionID = session.id, serverKey = server.persistenceID)
        val store = h.store(h.sessionState(initialTranscriptRequestIsExternal = true, isRunning = true)) {
            this[PromptOutbox] = outbox
        }
        store.exhaustive = false
        store.send(Action.Task)
        assertTrue(store.state.queuedInputs.isEmpty())
        assertEquals(listOf(earlier.size), promptIndex("msg_VOICE", store.state.items))
        store.finish()
    }

    @Test fun mountedVoiceAdmissionIgnoresOtherSessions() = sessionTest { h ->
        val store = h.store(h.sessionState())
        store.send(Action.VoicePromptAdmitted(sessionID = "ses_elsewhere"))
        store.finish()
    }

    /**
     * Delivery can beat the send callback. Restoring that admission twice
     * must keep one delivered row even while REST still has the old page.
     */
    @Test fun mountedVoiceAdmissionPreservesEarlyDeliveryAndDeduplicates() = sessionTest { h ->
        val outbox = PromptOutbox()
        outbox.markDelivered("msg_VOICE")
        outbox.recordAdmitted(voicePending(), sessionID = session.id, serverKey = server.persistenceID)
        val store = h.store(h.sessionState()) {
            this[PromptOutbox] = outbox
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                pendingInputs = { _, _ -> emptyList() },
                transcript = { _, _, _ -> earlier },
                getSession = { _, _ -> session },
            )
        }
        store.exhaustive = false
        store.send(Action.VoicePromptAdmitted(sessionID = session.id))
        store.send(Action.VoicePromptAdmitted(sessionID = session.id))
        store.finish()
        assertTrue(store.state.queuedInputs.isEmpty())
        assertEquals(listOf(earlier.size), promptIndex("msg_VOICE", store.state.items))
    }

    /**
     * A message that is only a Pasted Text card still shows from its
     * admission, card included.
     */
    @Test fun anOutsideSendOfOnlyAPastedCardShowsItsCard() = sessionTest { h ->
        val card = OpenCodeTranscriptFile(id = "msg_PASTE-inline-0", name = "Pasted text", mime = "text/plain", text = "a long paste")
        val store = h.store(h.sessionState()) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                pendingInputs = { _, _ -> emptyList() },
                message = { _, _, _ -> throw OpenCodeClientError(message = "Offline") },
            )
        }
        store.exhaustive = false
        store.send(Action.InputEnqueued(OpenCodePendingInput(id = "msg_PASTE", text = "", inlineFiles = listOf(card))))
        assertEquals(OpenCodeTranscriptItem.UserFiles(id = "msg_PASTE-files", files = listOf(card)), store.state.items.last())
        store.finish()
    }

    /**
     * Delivered into a session whose history is still loading, the echo is
     * the newest row once the page lands, never above the history.
     */
    @Test fun anEchoIntoALoadingSessionStaysAfterTheHistory() {
        val merged = SessionFeature.mergeTranscript(
            fetched = earlier,
            existing = listOf(OpenCodeTranscriptItem.UserText(id = "msg_VOICE", text = "x")),
            echoedPromptIDs = setOf("msg_VOICE"),
        )
        assertEquals(earlier.map { it.id } + "msg_VOICE", merged.map { it.id })
    }
}
