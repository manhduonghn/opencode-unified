package ai.opencode.feature.transcript

import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodeQueuedInput
import ai.opencode.server.model.OpenCodeTranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionFeatureTestsFailedSends {
    companion object {
        const val SENT_ID = "msg_00000000000100000000000000"
    }

    @Test
    fun aFailedMessageRetriedMidRunKeepsItsRowIdentity() {
        val failed = TranscriptEntry.build(listOf(OpenCodeTranscriptItem.UserText(id = SENT_ID, text = "hello")))
        val pending = listOf(OpenCodeQueuedInput(input = OpenCodePendingInput(id = SENT_ID, text = "hello", delivery = OpenCodeDelivery.Queue), images = emptyList()))
        val plan = TranscriptRowIdentityPlan.make(
            previousEntries = failed, previousQueued = emptyList(),
            previousEntryStableIDs = mapOf("user-$SENT_ID" to "row-1"), previousQueuedStableIDs = emptyMap(),
            entries = emptyList(), queued = pending,
        )
        assertEquals("row-1", plan.queuedStableIDs[SENT_ID])
    }
}
