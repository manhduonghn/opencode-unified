package ai.opencode.feature.session

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.TestStore
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.HapticsRecorder
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.user.TranscriptUserMessage
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptFile
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.PastedText
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

private val pasteServer = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
private val pasteSession = OpenCodeSession(id = "ses_1", serverTitle = "Paste", projectID = "p", directory = "/project", updatedAt = Instant.EPOCH)

/** A big paste's summary is computed once, when it's pasted. */
class PastedTextOnceTests {
    @Test fun aMessageTakesPasteAttachmentsUntilRequestDockBMakesTheComposerAnAnswer() = sessionTest { h ->
        val state = h.state { SessionFeature.State.session(server = pasteServer, session = pasteSession, cachedItems = emptyList()) }
        assertFalse(state.isAnsweringRequest)
        assertTrue(state.takesPasteAttachments)
        val answering = h.state { state.beginAnswering() }
        assertFalse("A paste never becomes part of an answer", answering.takesPasteAttachments)
    }
}

/** Send → server echo → card: what the app posts is what V2 stores, and what it reads back renders as the same card. */
class PastedTextRoundTripTests {
    private val log = (1..30).joinToString("\n") { "2026-10-09 12:00:${"%02d".format(it)} worker[$it] retrying upload" }

    @Test fun theComposersRowsMatchTheEcho() {
        val paste = PastedText.attachment(log)
        val image = OpenCodeOutgoingAttachment(data = byteArrayOf(0x89.toByte(), 0x50), mime = "image/png")
        // Indices count images too, as the server's files do, so the card keeps its ID.
        assertEquals(
            listOf(OpenCodeTranscriptFile(id = "msg_1-file-inline-1", name = "Pasted Text", mime = "text/plain", text = log)),
            SessionFeature.inlineFiles("msg_1", listOf(image, paste)),
        )
    }
}

class PastedTextOutboxTests {
    private val log = (1..20).joinToString("\n") { "frame $it" }

    private fun Harness.store(state: SessionFeature.State, sent: LockIsolated<List<List<OpenCodeOutgoingAttachment>>>): TestStore<SessionFeature.State, Action> {
        val store = store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, attachments, _, _, _ -> sent.append(attachments) },
                waitForIdle = { _, _ -> awaitCancellation() },
                transcript = { _, _, _ -> emptyList() },
                pendingInputs = { _, _ -> emptyList() },
                message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
            )
        }
        store.exhaustive = false
        return store
    }

    @Test fun anIdleSendShowsTheCardAndPostsTheWholeText() = sessionTest { h ->
        val sent = LockIsolated(listOf<List<OpenCodeOutgoingAttachment>>())
        val state = h.state { SessionFeature.State.session(server = pasteServer, session = pasteSession, cachedItems = emptyList()).withDraft("Why?") }
        val store = h.store(state, sent)

        store.send(Action.attachmentAdded(PastedText.attachment(log)))
        store.send(Action.SendTapped)

        val id = store.state.items.lastOrNull()?.id ?: ""
        assertTrue(id.startsWith("msg_"))
        assertEquals(
            listOf(
                OpenCodeTranscriptItem.UserFiles(
                    id = "$id-files",
                    files = listOf(OpenCodeTranscriptFile(id = "$id-file-inline-0", name = "Pasted Text", mime = "text/plain", text = log)),
                ),
                OpenCodeTranscriptItem.UserText(id = id, text = "Why?"),
            ),
            store.state.items,
        )
        assertTrue(store.state.pendingAttachments.isEmpty())
        store.receive(Action.PromptAdmitted::class, timeout = 2.seconds)
        assertEquals(listOf(listOf(log)), sent.value.map { list -> list.map { it.pastedText } })
        h.skipInFlightEffects()
    }

    @Test fun aSteeringSendKeepsItsCardWhilePending() = sessionTest { h ->
        val sent = LockIsolated(listOf<List<OpenCodeOutgoingAttachment>>())
        val state = h.state {
            SessionFeature.State.session(
                server = pasteServer,
                session = pasteSession,
                cachedItems = listOf(OpenCodeTranscriptItem.UserText(id = "msg_earlier", text = "Earlier")),
            ).copy(isExternallyActive = true)
        }
        val store = h.store(state, sent)

        store.send(Action.attachmentAdded(PastedText.attachment(log)))
        store.send(Action.SendTapped)

        val pending = store.state.queuedInputRows.firstOrNull()?.let { TranscriptUserMessage.from(it) }
        assertEquals(listOf(log), pending?.files?.map { it.text })
        assertEquals(OpenCodeDelivery.Steer, pending?.pending)
        h.skipInFlightEffects()
    }
}

/** Past 12 lines a user bubble shows 8 and Show More; copying still copies all of it. */
class UserBubbleFoldTests {
    private fun lines(count: Int) = (1..count).joinToString("\n") { "Line $it" }

    @Test fun copyingASentUserMessageCopiesItsFullText() = sessionTest { h ->
        val foldedText = lines(30)
        val entries = TranscriptEntry.build(
            listOf(
                OpenCodeTranscriptItem.UserText(id = "msg_1", text = "Check the upload retry schedule."),
                OpenCodeTranscriptItem.UserFiles(
                    id = "msg_2-files",
                    files = listOf(OpenCodeTranscriptFile(id = "msg_2-file-0", name = "Pasted Text", mime = "text/plain", text = "stack trace")),
                ),
                OpenCodeTranscriptItem.UserText(id = "msg_2", text = foldedText),
            ),
        )
        val messages = entries.mapNotNull { TranscriptUserMessage.from(it) }
        assertEquals(listOf("Check the upload retry schedule.", foldedText), messages.map { it.displayText })
        assertEquals(1, messages.last().files.size)

        val copied = LockIsolated(listOf<String>())
        val haptics = HapticsRecorder()
        val store = h.store(h.state { SessionFeature.State.session(server = pasteServer, session = pasteSession.copy(serverTitle = "Copy"), cachedItems = emptyList()) }) {
            this[PasteboardClient] = PasteboardClient(setURL = {}, setString = { copied.append(it) })
            this[HapticsClient] = haptics.client
        }

        for (message in messages) store.send(Action.CopyTextTapped(message.displayText))
        store.finish()
        assertEquals(listOf("Check the upload retry schedule.", foldedText), copied.value)
        assertEquals("Each copy plays .tap", listOf("tap", "tap"), haptics.log.map { it.name })
    }
}
