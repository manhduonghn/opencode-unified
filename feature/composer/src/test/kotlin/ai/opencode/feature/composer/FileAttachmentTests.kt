package ai.opencode.feature.composer

import ai.opencode.core.store.TestStore
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.util.UUID

class FileAttachmentFeatureTests {
    @Test fun composerAttachmentsFeatureReducesLocationAndAttachmentRemovalExhaustively() = runTest {
        val place = SharedLocation(latitude = 37.77, longitude = -122.41, capturedAt = Instant.ofEpochSecond(1_000))
        val first = OpenCodeOutgoingAttachment(id = UUID(0, 1).toString(), data = byteArrayOf(1), mime = "image/png", name = "a.png")
        val second = OpenCodeOutgoingAttachment(id = UUID(0, 2).toString(), data = byteArrayOf(2), mime = "image/png", name = "b.png")
        val store = TestStore(
            ComposerAttachmentsFeature.State(pendingAttachments = listOf(first, second)),
            ComposerAttachmentsFeature(),
            this,
        ) {
            this[LocationClient] = LocationClient(current = { place })
        }

        store.send(ComposerAttachmentsFeature.Action.AttachmentRemoved(0)) {
            it.copy(pendingAttachments = listOf(second))
        }
        store.send(ComposerAttachmentsFeature.Action.LocationTapped) {
            it.copy(isLocating = true)
        }
        store.receive(ComposerAttachmentsFeature.Action.LocationResponse(ComposerAttachmentsFeature.Outcome.Success(place))) {
            it.copy(isLocating = false, pendingLocation = place)
        }
        store.send(ComposerAttachmentsFeature.Action.LocationRemoved) {
            it.copy(pendingLocation = null)
        }
        store.finish()
    }
}
