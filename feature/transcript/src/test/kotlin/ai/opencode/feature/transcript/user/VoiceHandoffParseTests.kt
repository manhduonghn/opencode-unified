package ai.opencode.feature.transcript.user

import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodeQueuedInput
import ai.opencode.server.model.OpenCodeTranscriptFile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Two of those also check a [TranscriptUserMessage]; those halves are here
 * (`aContinuedDelegationIsShortAndStillAVoiceMessage`,
 * `parseToleratesTrailingAttachedFileReferencesAndDetectsCallLogAttachment`).
 */
class VoiceHandoffParseTests {
    /** The header line, then the sorted JSON fields. */
    private fun format(
        instruction: String,
        transcriptDelta: List<String>,
        framing: String? = null,
        continued: Boolean = false,
        callLogAttached: Boolean = false,
    ): String {
        val header = if (continued) "Voice delegation (continued). Same fields as before." else "Voice delegation. The user is on a voice call with a voice agent, which hands their requests to you."
        val body = buildJsonObject {
            if (callLogAttached) put("call_log_attached", true)
            put("instruction", instruction)
            put("transcript_delta", JsonArray(transcriptDelta.map(::JsonPrimitive)))
            framing?.let { put("voice", it) }
        }
        return "$header\n$body"
    }

    @Test fun aContinuedDelegationIsShortAndStillAVoiceMessage() {
        val prompt = format(instruction = "and the retry test", transcriptDelta = listOf("user: and the retry test"), framing = "Checking the retry test.", continued = true)
        val message = TranscriptUserMessage(id = "msg_2", text = prompt, images = emptyList(), files = emptyList(), pending = null)
        assertEquals("Checking the retry test.", message.voiceDelegation?.displayText)
    }

    @Test fun promptsFromBeforeTheFramingStillRender() {
        val prompt = "Voice delegation. Instruction is the cleaned interpretation of the user's request.\n{\n  \"instruction\" : \"Check why the upload test keeps failing on CI.\",\n  \"transcript_delta\" : [\n    \"user: hey can you figure out why the upload test keeps failing\"\n  ]\n}"
        val message = TranscriptUserMessage(id = "msg_old", text = prompt, images = emptyList(), files = emptyList(), pending = null)
        assertNull(message.voiceDelegation?.framing)
        assertEquals("Check why the upload test keeps failing on CI.", message.voiceDelegation?.displayText)
    }

    @Test fun aDelegationProjectsToAVoiceMessageWhetherPendingOrDelivered() {
        val prompt = format(instruction = "check the upload test", transcriptDelta = listOf("user: check the upload test"), framing = "Checking the upload test.")
        val delivered = TranscriptUserMessage(id = "msg_1", text = prompt, images = emptyList(), files = emptyList(), pending = null)
        assertEquals("Checking the upload test.", delivered.voiceDelegation?.displayText)
        assertEquals("check the upload test", delivered.voiceDelegation?.instruction)
        val steering = TranscriptUserMessage.from(OpenCodeQueuedInput(OpenCodePendingInput(id = "msg_2", text = prompt, delivery = OpenCodeDelivery.Steer)))
        assertEquals("Checking the upload test.", steering?.voiceDelegation?.displayText)
        assertEquals(OpenCodeDelivery.Steer, steering?.pending)
        assertNull(TranscriptUserMessage(id = "msg_3", text = "Check the upload test", images = emptyList(), files = emptyList(), pending = null).voiceDelegation)
    }

    @Test fun aVoiceMessageWearsTheBadgeAndOnlyThePendingLabel() {
        val badge = VoiceMessageIndicator.CornerBadge
        assertEquals("Delivered: the badge alone", VoiceMessageIndicator.Chrome(badge = true, labels = emptyList()), badge.chrome(pending = null))
        assertEquals(
            VoiceMessageIndicator.Chrome(badge = true, labels = listOf(VoiceMessageIndicator.LabelPart.Pending(OpenCodeDelivery.Steer))),
            badge.chrome(pending = OpenCodeDelivery.Steer),
        )
        assertEquals(
            VoiceMessageIndicator.Chrome(badge = true, labels = listOf(VoiceMessageIndicator.LabelPart.Pending(OpenCodeDelivery.Queue))),
            badge.chrome(pending = OpenCodeDelivery.Queue),
        )
        assertEquals(VoiceMessageIndicator.CornerBadge, TranscriptVoiceMessage.indicator)
        // An attached call log is a caption under the bubble, never a label above it.
        assertEquals(
            VoiceMessageIndicator.Chrome(badge = true, labels = listOf(VoiceMessageIndicator.LabelPart.Pending(OpenCodeDelivery.Steer)), callLogCaption = true),
            badge.chrome(pending = OpenCodeDelivery.Steer, callLogAttached = true),
        )
        assertEquals(
            VoiceMessageIndicator.Chrome(badge = false, labels = listOf(VoiceMessageIndicator.LabelPart.Voice), callLogCaption = true),
            VoiceMessageIndicator.LabelRow.chrome(pending = null, callLogAttached = true),
        )
    }

    @Test fun theLabelRowFallbackLeadsWithThePendingPart() {
        val row = VoiceMessageIndicator.LabelRow
        assertEquals(VoiceMessageIndicator.Chrome(badge = false, labels = listOf(VoiceMessageIndicator.LabelPart.Voice)), row.chrome(pending = null))
        // Pending leads, so delivery removes it without moving Voice.
        assertEquals(
            VoiceMessageIndicator.Chrome(badge = false, labels = listOf(VoiceMessageIndicator.LabelPart.Pending(OpenCodeDelivery.Steer), VoiceMessageIndicator.LabelPart.Voice)),
            row.chrome(pending = OpenCodeDelivery.Steer),
        )
        assertEquals(
            VoiceMessageIndicator.Chrome(badge = false, labels = listOf(VoiceMessageIndicator.LabelPart.Pending(OpenCodeDelivery.Queue), VoiceMessageIndicator.LabelPart.Voice)),
            row.chrome(pending = OpenCodeDelivery.Queue),
        )
    }

    @Test fun parseToleratesTrailingAttachedFileReferencesAndDetectsCallLogAttachment() {
        val name = "voice-call-11111111-2222-3333-4444-555555555555.json"
        val path = "/tmp/uploads/1/$name"
        val formatted = format(
            instruction = "Investigate why the voice completion notice was missed.",
            transcriptDelta = listOf("user: tell the iOS session to investigate"),
            callLogAttached = true,
        )
        val transcriptFile = OpenCodeTranscriptFile(id = "f1", name = name, mime = "application/json", path = path)
        val message = TranscriptUserMessage(id = "msg_log", text = formatted, images = emptyList(), files = listOf(transcriptFile), pending = null)
        assertEquals(true, message.voiceDelegation?.callLogAttached)
        assertEquals("Investigate why the voice completion notice was missed.", message.voiceDelegation?.displayText)
    }
}
