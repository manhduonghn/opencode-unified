package ai.opencode.feature.voice

import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeFormField
import ai.opencode.server.model.OpenCodeFormFieldType
import ai.opencode.server.model.OpenCodeFormOption
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** A session waiting on a question form takes no steered messages. */
class VoicePendingRequestTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val workshop = OpenCodeSession(id = "ses_workshop", serverTitle = "WORKSHOP", projectID = "p", directory = "/Users/kit/workshop", updatedAt = Instant.parse("0001-01-01T00:00:00Z"))
    private val iconForm: OpenCodeFormRequest
        get() = OpenCodeFormRequest(
            id = "form_icon", sessionID = workshop.id, title = "Banshee icon",
            fields = listOf(OpenCodeFormField(key = "q0", type = OpenCodeFormFieldType.String, title = "Which icon should ship?", options = listOf(OpenCodeFormOption(value = "a", label = "Wail"), OpenCodeFormOption(value = "b", label = "Veil")))),
            metadata = mapOf("kind" to OpenCodeJSONValue.Str("question")),
        )

    private fun workspace(client: OpenCodeClient, viewingNeedsInput: Boolean = false, board: VoiceSessionBoard? = null): VoiceDelegatorWorkspace =
        VoiceDelegatorWorkspace(
            client = client,
            start = VoiceDelegatorWorkspace.Start(
                server = server, viewing = workshop, draftDirectory = workshop.directory, draftModel = null, transcriptDelta = emptyList(), explained = emptySet(),
                board = { board }, viewingNeedsInput = viewingNeedsInput,
            ),
            effects = VoiceDelegatorWorkspace.Effects(open = {}, explained = {}, note = {}),
        )

    private fun call(arguments: String, name: String = "send_message") =
        VoiceDelegator.ToolCall(callID = "c", name = name, arguments = arguments)

    private fun JsonObject.bool(key: String): Boolean? = get(key)?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.string(key: String): String? = (get(key) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private fun JsonObject.firstFormID(): String? =
        ((get("questions") as? JsonArray)?.firstOrNull() as? JsonObject)?.string("form_id")

    private fun board(busyIDs: Set<String>, attentionIDs: Set<String>) = VoiceSessionBoard(
        serverID = server.persistenceID, sessions = listOf(workshop), busyIDs = busyIDs, attentionIDs = attentionIDs,
        unreadIDs = emptySet(), pinnedIDs = emptyList(), attentionDirectories = setOf(workshop.directory), isLive = true, attentionKnown = true,
    )

    @Test fun aSteerIntoASessionWaitingOnAQuestionIsntSent() = runBlocking<Unit> {
        val prompts = Locked(0)
        val readIDs = Locked(listOf<List<String>>())
        val client = OpenCodeClient.testValue.copy(
            getSession = { _, _ -> workshop },
            familyPendingRequests = { _, ids, _ ->
                readIDs.update { it + listOf(ids) }
                OpenCodePendingRequests(permissions = emptyList(), forms = listOf(iconForm))
            },
            sendDeliveredPrompt = { _, _, _, _ -> prompts.update { it + 1 }; "msg_1" },
        )
        val tools = workspace(client, viewingNeedsInput = true)

        val held = jsonObject(tools.run(call("""{"text":"Make more icon prototypes"}""")))
        assertEquals(true, held.bool("not_sent"))
        assertEquals("form_icon", held.firstFormID())
        val note = held.string("note")
        assertTrue(note, note?.contains("dismiss_question") == true)
        assertTrue(note, note?.contains("A message never answers it. When the user approved, picked, or answered, that is the answer: answer_question") == true)
        assertTrue(note, note?.contains("A request for more or other work isn't an answer") == true)
        assertEquals(0, prompts.value)
        assertTrue("Only the target is read", readIDs.value.all { it == listOf("ses_workshop") })

        // A deliberate queue waits behind the form anyway.
        val queued = jsonObject(tools.run(call("""{"text":"Then tidy up","delivery":"queue"}""")))
        assertNull(queued["not_sent"])
        assertEquals(1, prompts.value)
    }

    @Test fun aGlobalFormDoesntHoldASteer() = runBlocking<Unit> {
        val prompts = Locked(0)
        val client = OpenCodeClient.testValue.copy(
            getSession = { _, _ -> workshop },
            familyPendingRequests = { _, _, _ ->
                OpenCodePendingRequests(
                    permissions = emptyList(),
                    forms = listOf(OpenCodeFormRequest(id = "form_mcp", sessionID = "global", title = "Sign in to Linear", fields = listOf(OpenCodeFormField(key = "token", type = OpenCodeFormFieldType.String)))),
                )
            },
            sendDeliveredPrompt = { _, _, _, _ -> prompts.update { it + 1 }; "msg_1" },
        )
        val sent = jsonObject(workspace(client).run(call("""{"text":"Make more icon prototypes"}""")))
        assertNull(sent["not_sent"])
        assertEquals(1, prompts.value)
    }

    @Test fun aSessionHomeFlagsStaysHeldWhenItsRequestsCantBeRead() = runBlocking<Unit> {
        val prompts = Locked(0)
        val client = OpenCodeClient.testValue.copy(
            getSession = { _, _ -> workshop },
            familyPendingRequests = { _, _, _ -> throw OpenCodeClientError(message = "Offline") },
            sendDeliveredPrompt = { _, _, _, _ -> prompts.update { it + 1 }; "msg_1" },
        )
        val board = board(busyIDs = setOf(workshop.id), attentionIDs = setOf(workshop.id))
        val held = jsonObject(workspace(client, board = board).run(call("""{"text":"Pick b"}""")))
        assertEquals(true, held.bool("not_sent"))
        assertTrue(held.toString(), held.string("note")?.contains("list_requests") == true)
        assertEquals(0, prompts.value)
    }

    /** A form approved by voice is answered, never steered into HOME as a message. */
    @Test fun aSpokenApprovalIsHeldEvenWhenHomeHasntSeenTheRequest() = runBlocking<Unit> {
        val reads = Locked(0)
        val prompts = Locked(0)
        val client = OpenCodeClient.testValue.copy(
            getSession = { _, _ -> workshop },
            familyPendingRequests = { _, _, _ -> reads.update { it + 1 }; OpenCodePendingRequests(permissions = emptyList(), forms = listOf(iconForm)) },
            sendDeliveredPrompt = { _, _, _, _ -> prompts.update { it + 1 }; "msg_1" },
        )
        // Home's flags lag: no attention on the session yet.
        val board = board(busyIDs = setOf(workshop.id), attentionIDs = emptySet())
        val tools = workspace(client, board = board)

        val steered = jsonObject(tools.run(call("""{"text":"User approves Wail"}""")))
        assertEquals(true, steered.bool("not_sent"))
        assertEquals("form_icon", steered.firstFormID())
        val log = jsonObject(tools.run(call("""{"note":"Approved Wail"}""", name = "send_call_log")))
        assertEquals(true, log.bool("not_sent"))
        assertEquals(2, reads.value)
        assertEquals(0, prompts.value)
    }

    @Test fun aSessionWithNoRequestsIsSentAfterOneRead() = runBlocking<Unit> {
        val reads = Locked(0)
        val prompts = Locked(0)
        val readIDs = Locked(listOf<List<String>>())
        val client = OpenCodeClient.testValue.copy(
            getSession = { _, _ -> workshop },
            familyPendingRequests = { _, ids, _ ->
                readIDs.update { it + listOf(ids) }
                reads.update { it + 1 }
                OpenCodePendingRequests(permissions = emptyList(), forms = emptyList())
            },
            sendDeliveredPrompt = { _, _, _, _ -> prompts.update { it + 1 }; "msg_1" },
        )
        val board = board(busyIDs = emptySet(), attentionIDs = emptySet())
        val sent = jsonObject(workspace(client, board = board).run(call("""{"text":"Ship it"}""")))
        assertEquals("msg_1", sent.string("message_id"))
        assertEquals(1, reads.value)
        assertEquals(1, prompts.value)
        assertTrue("Only the target is read", readIDs.value.all { it == listOf("ses_workshop") })
    }

    @Test fun anUnflaggedSessionWhoseRequestsCantBeReadIsStillSent() = runBlocking<Unit> {
        val prompts = Locked(0)
        val client = OpenCodeClient.testValue.copy(
            getSession = { _, _ -> workshop },
            familyPendingRequests = { _, _, _ -> throw OpenCodeClientError(message = "Offline") },
            sendDeliveredPrompt = { _, _, _, _ -> prompts.update { it + 1 }; "msg_1" },
        )
        val board = board(busyIDs = emptySet(), attentionIDs = emptySet())
        val sent = jsonObject(workspace(client, board = board).run(call("""{"text":"Ship it"}""")))
        assertEquals("msg_1", sent.string("message_id"))
        assertEquals(1, prompts.value)
    }

    @Test fun theDelegatorIsToldAnApprovalIsAnAnswerNeverAMessage() {
        assertTrue(VoiceDelegator.instructions.contains("The user's approval, pick, or answer to a pending request goes only through answer_question or reply_permission, never send_message"))
        val watch = ReplyWatch(callPhase = ReplyWatch.CallPhase.live)
        val summary = watch.statusSummary(viewing = workshop, needsInput = true, draftDirectory = workshop.directory)
        assertTrue(summary, summary.contains("It is waiting on a pending request: the user's approval or answer goes through list_requests and answer_question or reply_permission, never send_message."))
    }

    @Test fun dismissingAQuestionOrRejectingAPermissionCarriesTheUsersNote() = runBlocking<Unit> {
        val cancelled = Locked(listOf<String?>())
        val replies = Locked(listOf<String?>())
        val client = OpenCodeClient.testValue.copy(
            getSession = { _, _ -> workshop },
            cancelForm = { _, _, _, message -> cancelled.update { it + message } },
            replyPermission = { _, _, _, _, message -> replies.update { it + message } },
        )
        val tools = workspace(client)

        tools.run(call("""{"form_id":"form_icon","note":"  Show me more icon prototypes first "}""", name = "dismiss_question"))
        tools.run(call("""{"form_id":"form_icon"}""", name = "dismiss_question"))
        assertEquals(listOf("Show me more icon prototypes first", null), cancelled.value)

        tools.run(call("""{"request_id":"perm_1","decision":"reject","note":"Use the staging bucket"}""", name = "reply_permission"))
        tools.run(call("""{"request_id":"perm_1","decision":"once","note":"ignored"}""", name = "reply_permission"))
        assertEquals(listOf("Use the staging bucket", null), replies.value)
    }
}
