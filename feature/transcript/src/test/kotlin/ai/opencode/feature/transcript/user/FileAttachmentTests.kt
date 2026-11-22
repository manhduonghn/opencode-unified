package ai.opencode.feature.transcript.user

import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.server.model.OpenCodeTranscriptFile
import ai.opencode.server.model.OpenCodeTranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Test

class PromptPresentationTests {
    @Test fun fileOnlyMessagesHaveNoBubble() {
        val items = listOf<OpenCodeTranscriptItem>(
            OpenCodeTranscriptItem.UserFiles(
                "msg_2-files",
                listOf(OpenCodeTranscriptFile(id = "msg_2-file-0", name = "clip.mov", mime = "video/quicktime", path = "/tmp/a/clip.mov")),
            ),
        )
        assertEquals("msg_2", TranscriptEntry.build(items).first().userMessageID)
    }
}
