package ai.opencode.feature.session

import ai.opencode.core.dependencies.UuidClient
import ai.opencode.feature.composer.UndoSnapshot
import ai.opencode.feature.media.MediaReferenceClient
import ai.opencode.feature.media.MediaReferenceLoadResult
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.testing.WireFixture
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * The session's part: re-sending a `media:<hash>` image restores it without its bytes, sends the
 * reference as is, and a revert loads the bytes.
 */
class MediaReferenceTests {
    private val hashA = "a1".repeat(32)
    private val pngData = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02)

    @Test fun restoringAndResendingMediaReferenceHydratesAsyncOnRevert() = sessionTest { h ->
        val items = listOf(
            OpenCodeTranscriptItem.UserImages(
                id = "msg_1-images",
                images = listOf(
                    OpenCodeImageAttachment(
                        id = "msg_1-img-0", data = ByteArray(0), mediaHash = hashA, name = "diagram.png",
                        mime = "image/png", byteCount = pngData.size.toLong(),
                    ),
                ),
            ),
            OpenCodeTranscriptItem.UserText(id = "msg_1", text = "Resend this"),
        )
        val fixture = WireFixture { WireFixture.Reply.Json(202, """{"admitted":true,"id":"msg_2"}""") }
        try {
            val session = OpenCodeSession(id = "ses_1", serverTitle = "Chat", projectID = "p", directory = "/project", updatedAt = Instant.EPOCH)
            val sessionState = h.state { SessionFeature.State.session(server = fixture.server, session = session, cachedItems = items) }

            val restored = checkNotNull(h.state { sessionState.restoredPrompt("msg_1", UuidClient.incrementing()) })
            assertEquals("Resend this", restored.text)
            assertEquals(1, restored.attachments.size)
            assertTrue(restored.attachments[0].data.isEmpty())
            assertEquals(hashA, restored.attachments[0].mediaHash)

            // `promptURI` and `sendPrompt` send `media:<hash>` without re-uploading.
            assertEquals("media:$hashA", restored.attachments[0].promptURI)
            OpenCodeClient.liveValue.sendPrompt(fixture.server, "ses_1", restored.text, restored.attachments, emptyList(), "msg_2", OpenCodeDelivery.Steer)

            val requests = fixture.requests
            assertEquals(1, requests.size)
            assertEquals("experimental-media-uri", requests[0].header("X-OpenCode-Capabilities"))
            val files = requests[0].json().getValue("files").jsonArray.map { file ->
                file.jsonObject.mapValues { it.value.jsonPrimitive.content }
            }
            assertEquals(listOf(mapOf("name" to "diagram.png", "uri" to "media:$hashA")), files)

            // `revertToHereTapped` checks only the memory cache synchronously; `stageRevert` then
            // loads the blob off the main thread and publishes it in `messageReverted`.
            val revertStore = h.store(sessionState) {
                this[UuidClient] = UuidClient.incrementing()
                this[OpenCodeClient] = this[OpenCodeClient].copy(stageRevert = { _, _, _ -> })
                this[MediaReferenceClient] = MediaReferenceClient(
                    cachedData = { null },
                    unavailable = { _, _ -> false },
                    loader = { _, hash -> if (hash == hashA) MediaReferenceLoadResult.Loaded(pngData) else MediaReferenceLoadResult.Unavailable },
                )
            }

            revertStore.send(Action.RevertToHereTapped("msg_1")) { it.copy(isUndoingLastMessage = true) }
            revertStore.receive(Action.MessageReverted::class) {
                it.copy(isUndoingLastMessage = false)
                    .setRevertMessageID("msg_1")
                    .withDraft("Resend this")
                    .withPendingAttachments(
                        listOf(
                            OpenCodeOutgoingAttachment(
                                id = UUID(0, 0).toString(), data = pngData, mime = "image/png", name = "diagram.png",
                                mediaHash = hashA, byteCount = pngData.size.toLong(),
                            ),
                        ),
                    )
                    .withUndoSnapshot(UndoSnapshot(restoredDraft = "Resend this", priorDraft = "", attachmentIDs = setOf(UUID(0, 0))))
                    .copy(composerFocusRequest = 1)
            }
            revertStore.receive(Action.AcceptedTranscriptUpdated::class)
            revertStore.finish()
        } finally {
            fixture.close()
        }
    }
}
