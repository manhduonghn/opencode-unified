package ai.opencode.feature.transcript.rows

import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.model
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSwitchRuleTests {
    private val opus = OpenCodeModelRef(id = "claude-opus", providerID = "anthropic")
    private val haiku = OpenCodeModelRef(id = "claude-haiku", providerID = "anthropic")
    private val models = listOf(
        OpenCodeModel(modelID = "claude-opus", providerID = "anthropic", name = "Claude Opus", variants = listOf("low", "high", "xhigh")),
        OpenCodeModel(modelID = "claude-haiku", providerID = "anthropic", name = "Claude Haiku"),
    )

    private fun ref(model: OpenCodeModelRef, variant: String?) = OpenCodeModelRef(model.id, model.providerID, variant)

    private fun ids(entries: List<TranscriptEntry>) = entries.map { it.id }

    @Test fun decodesTheServersModelSwitchedMessage() {
        val items = OpenCodeClient.decodeTranscript(
            """
            {"data":[
              {"id":"msg_user","type":"user","text":"Think harder","time":{"created":3}},
              {"id":"msg_level","type":"model-switched","model":{"id":"claude-opus","providerID":"anthropic","variant":"high"},
               "previous":{"id":"claude-opus","providerID":"anthropic"},"time":{"created":2}},
              {"id":"msg_agent","type":"agent-switched","agent":"plan","previous":"build","time":{"created":1}},
              {"id":"msg_first","type":"model-switched","model":{"id":"claude-opus","providerID":"anthropic"},"time":{"created":0}}
            ]}
            """.trimIndent().toByteArray(),
        )
        // Newest first on the wire; chronological here. An agent switch's
        // string `previous` neither decodes as a model nor fails the page.
        assertEquals(
            listOf(
                OpenCodeTranscriptItem.ModelSwitch(id = "msg_first", model = opus),
                OpenCodeTranscriptItem.ModelSwitch(id = "msg_level", model = ref(opus, "high"), previous = opus),
                OpenCodeTranscriptItem.UserText(id = "msg_user", text = "Think harder"),
            ),
            items,
        )
    }

    @Test fun readsLikeTheDesktopDivider() {
        fun label(model: OpenCodeModelRef, from: OpenCodeModelRef?, catalog: List<OpenCodeModel>? = null): String {
            val label = ModelSwitchLabel(model = model, previous = from, name = (catalog ?: models).model(model)?.name)
            return listOfNotNull(label.title, label.variant).joinToString(" · ")
        }
        assertEquals("Switched to Claude Haiku", label(haiku, from = opus))
        assertEquals("Switched to Claude Opus · High", label(ref(opus, "high"), from = haiku))
        // A thinking level alone names the model it stays on.
        assertEquals("Switched to Claude Opus · Extra High", label(ref(opus, "xhigh"), from = ref(opus, "high")))
        // Back to the default level says so, rather than reading as no change.
        assertEquals("Switched to Claude Opus · Default", label(opus, from = ref(opus, "high")))
        assertEquals("Switched to Claude Opus · Default", label(ref(opus, "default"), from = ref(opus, "low")))
        // Another model at its default level needs no level.
        assertEquals("Switched to Claude Haiku", label(haiku, from = ref(opus, "high")))
        // Until the catalog loads (or for a model it lacks), the model's ID.
        assertEquals("Switched to claude-opus · Low", label(ref(opus, "low"), from = null, catalog = emptyList()))
        assertEquals("Switched to Claude Opus", label(ref(opus, ""), from = opus))
        assertEquals(
            "Switched to Claude Opus, High thinking",
            ModelSwitchLabel(model = ref(opus, "high"), previous = opus, name = "Claude Opus").accessibilityLabel,
        )
        assertEquals(
            "Switched to Claude Opus, thinking off",
            ModelSwitchLabel(model = ref(opus, "none"), previous = opus, name = "Claude Opus").accessibilityLabel,
        )
    }

    @Test fun showsWhereTheSwitchLandedAndEndsTheReplyBefore() {
        val entries = TranscriptEntry.build(
            listOf(
                OpenCodeTranscriptItem.UserText(id = "u1", text = "First"),
                OpenCodeTranscriptItem.AssistantMarkdown(id = "a1", text = "Answer."),
                OpenCodeTranscriptItem.ModelSwitch(id = "switch", model = ref(opus, "high"), previous = opus),
                OpenCodeTranscriptItem.UserText(id = "u2", text = "Second"),
                OpenCodeTranscriptItem.AssistantMarkdown(id = "a2", text = "Again."),
            ),
            models = models,
        )
        // The first reply's copy button stays with it, above the rule.
        assertEquals(listOf("user-u1", "a1", "reply-end-a1", "switch", "user-u2", "a2", "reply-end-a2"), ids(entries))
        // The catalog's name rides on the row, so its arrival changes the row.
        assertEquals(
            TranscriptEntry.Single(OpenCodeTranscriptItem.ModelSwitch(id = "switch", model = ref(opus, "high"), previous = opus, name = "Claude Opus")),
            entries[3],
        )
        assertEquals(TranscriptMetrics.sectionHalf, entries[3].rowInsets.top)
        assertEquals(TranscriptMetrics.flowHalf, entries[3].rowInsets.bottom)
    }

    @Test fun aSwitchInsideARunningTurnLeavesItsReplyWhole() {
        val a1 = OpenCodeTranscriptItem.AssistantMarkdown(id = "a1", text = "Working on it.")
        val switched = OpenCodeTranscriptItem.ModelSwitch(id = "switch", model = ref(opus, "high"), previous = opus)
        val u1 = OpenCodeTranscriptItem.UserText(id = "u1", text = "Go")
        // A steering send applied a model while the reply runs; its message waits.
        assertEquals(listOf("user-u1", "a1", "switch"), ids(TranscriptEntry.build(listOf(u1, a1, switched), isRunning = true)))
        // The reply goes on below the rule: one copy button for the whole turn.
        val a2 = OpenCodeTranscriptItem.AssistantMarkdown(id = "a2", text = "Done.")
        assertEquals(listOf("user-u1", "a1", "switch", "a2", "reply-end-a2"), ids(TranscriptEntry.build(listOf(u1, a1, switched, a2))))
        // Once the run ends with nothing after it, the reply ends above it.
        assertEquals(listOf("user-u1", "a1", "reply-end-a1", "switch"), ids(TranscriptEntry.build(listOf(u1, a1, switched))))
    }

    @Test fun aSessionsFirstModelIsNoSwitch() {
        // The phone creates a session, then applies its model: the server records
        // that as a switch from nothing, ahead of the first message.
        val first = OpenCodeTranscriptItem.ModelSwitch(id = "first", model = opus)
        val hi = OpenCodeTranscriptItem.UserText(id = "u1", text = "Hi")
        assertEquals(listOf("user-u1"), ids(TranscriptEntry.build(listOf(first, hi))))
        // A switch from a known model shows even before any message.
        val known = OpenCodeTranscriptItem.ModelSwitch(id = "known", model = haiku, previous = opus)
        assertEquals(listOf("known", "user-u1"), ids(TranscriptEntry.build(listOf(known, hi))))
        // After a message, a session that never had a model switched from its default.
        val later = OpenCodeTranscriptItem.ModelSwitch(id = "later", model = haiku)
        assertEquals(listOf("user-u1", "later"), ids(TranscriptEntry.build(listOf(hi, later))))
        // Judged on everything loaded, not the window on screen: a window that
        // starts after the session's first message still shows a later one.
        val all = listOf(first, hi, later, OpenCodeTranscriptItem.UserText(id = "u2", text = "Again"))
        val starts = TranscriptEntry.sessionStartModelIDs(all)
        assertEquals(setOf("first"), starts)
        // Only ahead of everything: a window that opens on a reply keeps its switch.
        val reply = OpenCodeTranscriptItem.AssistantMarkdown(id = "a0", text = "Earlier.")
        assertTrue(TranscriptEntry.sessionStartModelIDs(listOf(reply, later)).isEmpty())
        assertEquals(listOf("later", "user-u2"), ids(TranscriptEntry.build(all.takeLast(2), sessionStartModels = starts)))
    }

    @Test fun persistsInTheTranscriptCache() {
        val item: OpenCodeTranscriptItem = OpenCodeTranscriptItem.ModelSwitch(id = "switch", model = ref(opus, "high"), previous = haiku)
        val data = Json.encodeToString(OpenCodeTranscriptItem.serializer(), item)
        assertEquals(item, Json.decodeFromString(OpenCodeTranscriptItem.serializer(), data))
    }
}
