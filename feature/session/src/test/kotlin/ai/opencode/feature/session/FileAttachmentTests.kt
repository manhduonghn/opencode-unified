package ai.opencode.feature.session

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.files.ComposerStagedFile
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.feature.composer.ComposerAttachmentsFeature
import ai.opencode.feature.composer.ComposerFileAttachment
import ai.opencode.feature.composer.SharedComposerDraft
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.HapticsRecorder
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeFileReference
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptFile
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.OpenCodeUploadEvent
import android.content.ContextWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

class FileAttachmentFeatureTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_1", serverTitle = "Files", projectID = "p", directory = "/project", updatedAt = Instant.EPOCH)

    private fun staged(name: String, mime: String = "application/pdf", size: Long = 100, id: Long = 1) = ComposerStagedFile(
        id = UUID(0, id),
        name = name,
        mime = mime,
        size = size,
        url = File("/nonexistent/ComposerFiles/${UUID(0, id)}/$name"),
    )

    private fun uploadPath(name: String) = "/tmp/opencode/uploads/u/$name"

    private fun Harness.fileState(): SessionFeature.State = state { SessionFeature.State.session(server = server, session = session, cachedItems = emptyList()) }

    /**
     * Android's staging folder hangs off the app context's cache, which a finished upload's cleanup
     * reads.
     */
    private val appContext = object : ContextWrapper(null) {
        override fun getCacheDir(): File = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
    }

    private fun uploadFinished(id: UUID, result: ComposerAttachmentsFeature.Outcome<String, OpenCodeClientError>) = Action.fileUploadFinished(id, result)

    @Test fun uploadReportsProgressThenBecomesAPathReference() = sessionTest { h ->
        val upload = Channel<OpenCodeUploadEvent>(Channel.UNLIMITED)
        val requests = LockIsolated(listOf<String>())
        val file = staged("Report.pdf")
        val store = h.store(h.fileState()) {
            this[AppContext] = appContext
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                uploadFile = { _, directory, url, name ->
                    requests.append("${directory ?: "-"} ${url.name} $name")
                    upload.receiveAsFlow()
                },
            )
        }

        store.send(Action.filesStaged(listOf(file))) { it.withPendingFiles(listOf(ComposerFileAttachment(file))) }
        assertTrue(store.state.hasPromptContent)
        upload.send(OpenCodeUploadEvent.Progress(sent = 40))
        store.receive(Action.fileUploadProgress(file.id, 40)) {
            it.withPendingFiles(listOf(it.pendingFiles[0].copy(status = ComposerFileAttachment.Status.Uploading(sent = 40))))
        }
        assertEquals(0.4, store.state.pendingFiles[0].progress, 1e-9)
        upload.send(OpenCodeUploadEvent.Finished(path = uploadPath("Report.pdf")))
        store.receive(uploadFinished(file.id, ComposerAttachmentsFeature.Outcome.Success(uploadPath("Report.pdf")))) {
            it.withPendingFiles(listOf(it.pendingFiles[0].copy(status = ComposerFileAttachment.Status.Uploaded(uploadPath("Report.pdf")))))
        }
        upload.close()
        assertEquals(listOf("/project Report.pdf Report.pdf"), requests.value)
        assertEquals(
            OpenCodeFileReference(name = "Report.pdf", mime = "application/pdf", path = uploadPath("Report.pdf")),
            store.state.pendingFiles[0].reference,
        )
        store.finish()
    }

    @Test fun sendWaitsForUploadsAndCarriesTheirReferences() = sessionTest { h ->
        val upload = Channel<OpenCodeUploadEvent>(Channel.UNLIMITED)
        val sent = LockIsolated(listOf<Pair<String, List<OpenCodeFileReference>>>())
        val file = staged("clip.mov", mime = "video/quicktime")
        val haptics = HapticsRecorder()
        val store = h.store(h.fileState().withDraft("What happens here?")) {
            this[AppContext] = appContext
            this[UuidClient] = UuidClient.incrementing()
            this[HapticsClient] = haptics.client
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                uploadFile = { _, _, _, _ -> upload.receiveAsFlow() },
                sendPrompt = { _, _, text, _, files, _, _ -> sent.append(text to files) },
                waitForIdle = { _, _ -> },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
                pendingInputs = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
            )
        }
        store.exhaustive = false

        store.send(Action.filesStaged(listOf(file)))
        store.send(Action.SendTapped) { it.withSendAfterUploads(OpenCodeDelivery.Steer) }
        assertEquals("The draft stays put until the message goes", "What happens here?", store.state.draft)
        assertTrue(store.state.promptStates.isEmpty())
        assertFalse(store.state.canSendPrompt)

        upload.send(OpenCodeUploadEvent.Finished(path = uploadPath("clip.mov")))
        upload.close()
        store.receive({ (it as? Action.ComposerAttachments)?.action is ComposerAttachmentsFeature.Action.FileUploadFinished }, "fileUploadFinished")
        store.receive(Action.DeferredSendReady::class) {
            it.withSendAfterUploads(null).withPendingFiles(emptyList()).withDraft("")
        }
        assertEquals(
            "Send tapped once, at the press, not again when the upload lands",
            listOf("selection", "tap"),
            haptics.log.map { it.name },
        )
        val messageID = "msg_00000000000100000000000000"
        assertEquals(
            listOf(
                OpenCodeTranscriptItem.UserFiles(
                    id = "$messageID-files",
                    files = listOf(OpenCodeTranscriptFile(id = "$messageID-file-0", name = "clip.mov", mime = "video/quicktime", path = uploadPath("clip.mov"))),
                ),
                OpenCodeTranscriptItem.UserText(id = messageID, text = "What happens here?"),
            ),
            store.state.items,
        )
        store.receive(Action.PromptAdmitted::class)
        assertEquals(listOf("What happens here?"), sent.value.map { it.first })
        assertEquals(
            listOf(listOf(OpenCodeFileReference(name = "clip.mov", mime = "video/quicktime", path = uploadPath("clip.mov")))),
            sent.value.map { it.second },
        )
        store.finish()
    }

    @Test fun failedUploadBlocksSendUntilRetried() = sessionTest { h ->
        val attempts = LockIsolated(0)
        val file = staged("notes.txt", mime = "text/plain")
        val store = h.store(h.fileState()) {
            this[AppContext] = appContext
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                uploadFile = { _, _, _, _ ->
                    val attempt = attempts.increment()
                    flow {
                        if (attempt == 1) throw OpenCodeClientError(message = "The server returned HTTP 500.", statusCode = 500)
                        emit(OpenCodeUploadEvent.Finished(path = "/tmp/opencode/uploads/u/notes.txt"))
                    }
                },
            )
        }

        store.send(Action.filesStaged(listOf(file))) { it.withPendingFiles(listOf(ComposerFileAttachment(file))) }
        store.receive(
            uploadFinished(file.id, ComposerAttachmentsFeature.Outcome.Failure(OpenCodeClientError(message = "The server returned HTTP 500.", statusCode = 500))),
        ) {
            it.withPendingFiles(listOf(it.pendingFiles[0].copy(status = ComposerFileAttachment.Status.Failed("Couldn’t upload"))))
        }
        assertFalse(store.state.canSendPrompt)
        store.send(Action.SendTapped)
        assertTrue(store.state.promptStates.isEmpty())

        store.send(Action.fileRetryTapped(file.id)) {
            it.withPendingFiles(listOf(it.pendingFiles[0].copy(status = ComposerFileAttachment.Status.Uploading(sent = 0))))
        }
        store.receive(uploadFinished(file.id, ComposerAttachmentsFeature.Outcome.Success("/tmp/opencode/uploads/u/notes.txt"))) {
            it.withPendingFiles(listOf(it.pendingFiles[0].copy(status = ComposerFileAttachment.Status.Uploaded("/tmp/opencode/uploads/u/notes.txt"))))
        }
        assertEquals(2, attempts.value)
        assertTrue(store.state.canSendPrompt)
        store.finish()
    }

    @Test fun removingAnUploadingFileCancelsItAndReleasesAWaitingSend() = sessionTest { h ->
        val terminated = LockIsolated(false)
        val issues = LockIsolated(listOf<String>())
        val file = staged("big.zip", mime = "application/zip")
        val store = h.store(h.fileState().withDraft("Go")) {
            this[AppContext] = appContext
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                uploadFile = { _, _, _, _ ->
                    flow<OpenCodeUploadEvent> {
                        try {
                            awaitCancellation()
                        } finally {
                            terminated.setValue(true)
                        }
                    }
                },
                sendPrompt = { _, _, _, _, files, _, _ -> if (files.isNotEmpty()) issues.append("files sent: $files") },
                waitForIdle = { _, _ -> },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
                pendingInputs = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
            )
        }
        store.exhaustive = false

        store.send(Action.filesStaged(listOf(file)))
        store.send(Action.SendTapped) { it.withSendAfterUploads(OpenCodeDelivery.Steer) }
        store.send(Action.fileRemoved(file.id)) { it.withPendingFiles(emptyList()) }
        repeat(100) { if (!terminated.value) yield() }
        assertTrue("Removing the chip cancels its transfer", terminated.value)
        store.receive(Action.DeferredSendReady::class)
        store.receive(Action.PromptAdmitted::class)
        store.finish()
        assertTrue(issues.value.isEmpty())
    }

    @Test fun missingRouteHidesFileOptionsForThatServer() = sessionTest { h ->
        val file = staged("a.pdf")
        val serverID = server.persistenceID
        val store = h.store(h.fileState()) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                uploadFile = { _, _, _, _ -> flow { throw OpenCodeClientError.uploadsUnsupported } },
            )
        }
        assertTrue(store.state.supportsFileUploads)

        store.send(Action.filesStaged(listOf(file))) { it.withPendingFiles(listOf(ComposerFileAttachment(file))) }
        store.receive(uploadFinished(file.id, ComposerAttachmentsFeature.Outcome.Failure(OpenCodeClientError.uploadsUnsupported))) {
            // `uploadUnsupportedServerIDs` is shared: the reducer wrote [serverID] through it.
            it.withPendingFiles(listOf(it.pendingFiles[0].copy(status = ComposerFileAttachment.Status.Failed("Server doesn’t support files"))))
        }
        assertEquals(setOf(serverID), h.sharedStore.reference(SharedKeys.uploadUnsupportedServerIDs).value)
        assertFalse(store.state.supportsFileUploads)
        // Retrying can't help until the server is updated.
        store.send(Action.fileRetryTapped(file.id))
        // Another screen on the same server starts out without file options.
        assertFalse(h.state { SessionFeature.State.session(server = server, session = session) }.supportsFileUploads)
        store.finish()
    }

    @Test fun pdfTravelsInlineWhenTheModelReadsPDFs() = sessionTest { h ->
        val folder = Files.createTempDirectory("FileAttachmentTests-").toFile()
        try {
            val file = File(folder, "Spec.pdf").apply { writeText("%PDF-1.7") }
            val staged = ComposerStagedFile(id = UUID(0, 7), name = "Spec.pdf", mime = "application/pdf", size = 8, url = file)
            val model = OpenCodeModel(modelID = "m", providerID = "p", name = "Reader", inputs = setOf("text", "image", "pdf"))
            val issues = LockIsolated(listOf<String>())
            val state = h.fileState().copy(models = listOf(model), currentModel = model.ref)
            val store = h.store(state) {
                this[AppContext] = appContext
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    uploadFile = { _, _, _, _ ->
                        issues.append("A PDF the model reads natively must not upload")
                        emptyFlow()
                    },
                )
            }

            val inline = OpenCodeOutgoingAttachment(id = UUID(0, 7).toString(), data = "%PDF-1.7".toByteArray(), mime = "application/pdf", name = "Spec.pdf")
            store.send(Action.filesStaged(listOf(staged)))
            store.receive(Action.attachmentAdded(inline)) { it.withPendingAttachments(listOf(inline)) }
            assertTrue(store.state.pendingFiles.isEmpty())
            store.finish()
            assertTrue(issues.value.isEmpty())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun sharedFilesStartUploadingOnceAbsorbed() = sessionTest { h ->
        val calls = LockIsolated(0)
        val file = staged("memo.docx", mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        val state = h.state { h.fileState().absorb(SharedComposerDraft(text = "From Files", files = listOf(file))) }
        assertEquals(listOf(ComposerFileAttachment(file)), state.pendingFiles)
        assertEquals(listOf(file.id), state.unstartedUploadIDs)
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                uploadFile = { _, _, _, _ ->
                    calls.increment()
                    flow { throw CancellationException() }
                },
            )
        }
        store.exhaustive = false

        store.send(Action.queuedUploadsStarted) { it.withUnstartedUploadIDs(emptyList()) }
        repeat(100) { if (calls.value == 0) yield() }
        assertEquals(1, calls.value)
        store.finish()
    }
}
