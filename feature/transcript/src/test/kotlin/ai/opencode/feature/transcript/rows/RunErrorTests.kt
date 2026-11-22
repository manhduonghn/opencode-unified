package ai.opencode.feature.transcript.rows

import ai.opencode.server.model.OpenCodePromptSource
import ai.opencode.server.model.OpenCodeRunError
import ai.opencode.server.model.OpenCodeRunError.Action
import ai.opencode.server.model.OpenCodeTranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The transcript's item-level [RunErrorState] and `OpenCodeRunError`'s kinds. */
class RunErrorTests {
    private val filter = OpenCodeRunError(type = "provider.content-filter", message = "Provider blocked the response")

    private val failedTurn: List<OpenCodeTranscriptItem>
        get() = listOf(
            OpenCodeTranscriptItem.UserText(id = "msg_earlier", text = "Hi"),
            OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_hello-text-0", text = "Hello"),
            OpenCodeTranscriptItem.UserText(id = "msg_ask", text = "Tighten the drawer"),
            OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_reply-text-0", text = "On it"),
            OpenCodeTranscriptItem.RunError(id = "msg_reply-error", error = filter),
            OpenCodeTranscriptItem.RunError(id = "msg_idle", error = OpenCodeRunError(type = filter.type, message = filter.message, beforeReply = true)),
        )

    private fun actionableID(items: List<OpenCodeTranscriptItem>, isAwaitingReply: Boolean = false) =
        RunErrorState.actionableRunErrorID(
            items, hasSession = true, isRunning = isAwaitingReply, hasFailedSends = false, hasQueuedInputs = false, revertMessageID = null,
        )

    private fun editable(items: List<OpenCodeTranscriptItem>) = RunErrorState.editableLastMessage(items, optimisticPromptIDs = emptySet())

    private fun actions(items: List<OpenCodeTranscriptItem>) =
        RunErrorState.actionableRunErrorActions(items, actionableID(items), canEditLastMessage = editable(items) != null)

    @Test fun theNewestErrorOffersItsActionUntilThereIsSomethingNewer() {
        val items = failedTurn.toMutableList()
        assertEquals("msg_reply-error", actionableID(items))
        assertEquals(listOf(Action.Edit, Action.TryAgain, Action.SwitchModel), actions(items))
        assertEquals("msg_ask", editable(items)?.first)

        items += OpenCodeTranscriptItem.UserText(id = "msg_next", text = "Never mind")
        assertNull("Prompting again leaves only the record", actionableID(items))
        items.removeAt(items.lastIndex)

        assertNull("Nothing to act on while a run goes", actionableID(items, isAwaitingReply = true))

        // A stop is no failure: nothing to do about it.
        items[4] = OpenCodeTranscriptItem.RunError(id = "msg_reply-error", error = OpenCodeRunError(type = "aborted", message = "Aborted"))
        items.removeAt(items.lastIndex)
        assertNull(actionableID(items))
    }

    @Test fun editIsOfferedOnlyForTheReadersOwnWords() {
        val withImage = failedTurn.toMutableList()
        withImage.add(2, OpenCodeTranscriptItem.UserImages(id = "msg_ask-images", images = emptyList()))
        assertNull("An image couldn't come back with the words", editable(withImage))
        assertEquals(listOf(Action.TryAgain, Action.SwitchModel), actions(withImage))

        val fromAgent = failedTurn.toMutableList()
        fromAgent[2] = OpenCodeTranscriptItem.UserText(id = "msg_ask", text = "Tighten the drawer", source = OpenCodePromptSource.Session("ses_other"))
        assertNull(editable(fromAgent))
    }

    @Test fun eachKindNamesItsWayForward() {
        fun error(type: String, message: String = "", status: Int? = null) = OpenCodeRunError(type = type, message = message, status = status)
        assertEquals(Action.SwitchModel, error("provider.quota").action)
        assertEquals(Action.SwitchModel, error("provider.auth").action)
        assertEquals(Action.Edit, error("provider.invalid-request", "Bad tool schema").action)
        assertEquals(Action.Compact, error("provider.invalid-request", "This model's maximum context length is 200000 tokens").action)
        assertEquals("The model is overloaded", error("provider.internal", "Overloaded", status = 529).line)
        assertEquals("The provider had a problem", error("provider.internal", "Internal server error", status = 500).line)
        assertEquals(Action.TryAgain, error("provider.timeout").action)
        assertEquals("The run stopped with an error", error("something.new").line)
        assertTrue(error("permission.rejected").isStop)
    }
}
