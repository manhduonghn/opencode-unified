package ai.opencode.feature.session

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.asContextElement
import ai.opencode.core.files.ComposerFileStaging
import ai.opencode.core.store.Store
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.session.SessionTestSupport.testServer
import ai.opencode.feature.session.SessionTestSupport.testSession
import ai.opencode.feature.transcript.user.TranscriptDragPayload
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.model.OpenCodeUploadEvent
import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * Android's drop is a `ClipData` the composer sorts in
 * [SessionAttachmentImporter.handleComposerDrop], so this drops onto a real store under Robolectric
 * and checks where each part landed. The transcript's `.agentFile` drags are `content://` URIs
 * served from the agent's server; local files stand in as `file://` URIs with the payload's MIME
 * types.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptDragTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun idleUntil(condition: () -> Boolean) {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
    }

    @Test fun composerDropCategorizesImageFileAndTextProviders() {
        val context = RuntimeEnvironment.getApplication()
        val dependencies = DependencyValues.testing().update {
            this[AppContext] = context
            this[OpenCodeClient] = this[OpenCodeClient].copy(uploadFile = { _, _, _, _ -> flow<OpenCodeUploadEvent> { awaitCancellation() } })
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + dependencies.asContextElement())
        val store = Store(SessionFeature.State.session(testServer, testSession(), cachedItems = emptyList()), SessionFeature(), dependencies, scope)
        val importer = SessionAttachmentImporter(store, scope)
        val folder = Files.createTempDirectory("TranscriptComposerFileDrop-").toFile()
        try {
            // A code block's drag is text: it joins the draft.
            val code = TranscriptDragPayload.Code(source = "let x = 1", language = "swift").clipData()!!
            val codeTypes = (0 until code.description.mimeTypeCount).map(code.description::getMimeType)
            assertTrue(DependencyValues.withValues(dependencies) { importer.handleComposerDrop(code, codeTypes) })
            assertEquals("let x = 1", store.state.draft)
            assertTrue("Text stages no file", store.state.pendingFiles.isEmpty() && !importer.isLoadingFiles && !importer.isLoadingImages)

            val pdf = File(folder, "report.pdf").apply { writeBytes("%PDF-1.7 sample".toByteArray()) }
            val swift = File(folder, "Agent.swift").apply { writeBytes("struct Agent {}\n".toByteArray()) }
            val files = ClipData(ClipDescription("files", arrayOf("application/pdf", "text/x-swift")), ClipData.Item(Uri.fromFile(pdf)))
            files.addItem(ClipData.Item(Uri.fromFile(swift)))
            assertTrue(DependencyValues.withValues(dependencies) { importer.handleComposerDrop(files, listOf("application/pdf", "text/x-swift")) })
            assertTrue("A file is no image", !importer.isLoadingImages)
            idleUntil { store.state.pendingFiles.size == 2 }
            val staged = store.state.pendingFiles
            assertEquals(listOf("report.pdf", "Agent.swift"), staged.map { it.name })
            assertEquals(listOf(pdf.length(), swift.length()), staged.map { it.size })
            assertEquals("Files leave the draft alone", "let x = 1", store.state.draft)
            DependencyValues.withValues(dependencies) { staged.forEach { ComposerFileStaging.discard(File(it.localPath)) } }
        } finally {
            folder.deleteRecursively()
            store.close()
            scope.cancel()
        }
    }
}
