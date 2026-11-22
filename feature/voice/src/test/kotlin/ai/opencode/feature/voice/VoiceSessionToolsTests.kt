package ai.opencode.feature.voice

import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.friendlyName
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement?.strings(): List<String>? = (this as? JsonArray)?.map { it.jsonPrimitive.content }
private fun JsonObject.keyNames(): List<String> = keys.sorted()
private val distantPast: Instant = Instant.parse("0001-01-01T00:00:00Z")

/**
 * The delegator's session discovery tools (`list_sessions` with the drawer's unread, waiting and
 * running state, and `search_sessions`).
 */
class VoiceSessionToolsTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val earpiece = OpenCodeSession(id = "ses_ear", serverTitle = "Fix the earpiece routing", projectID = "ios", directory = "/Users/kit/opencode-ios", updatedAt = Instant.ofEpochSecond(400))
    private val upload = OpenCodeSession(id = "ses_upload", serverTitle = "Upload retries", projectID = "ios", directory = "/Users/kit/opencode-ios", updatedAt = Instant.ofEpochSecond(300))
    private val docs = OpenCodeSession(id = "ses_docs", serverTitle = "Docs refresh", projectID = "web", directory = "/Users/kit/website", updatedAt = Instant.ofEpochSecond(200))
    private val math = OpenCodeSession(id = "ses_math", serverTitle = "Math bug", projectID = "math", directory = "/Users/kit/math", updatedAt = Instant.ofEpochSecond(100))

    private val sessions: List<OpenCodeSession> get() = listOf(earpiece, upload, docs, math)

    /**
     * Earpiece and upload run; upload waits on a permission; docs is unread; math and earpiece are
     * pinned, math first. Only the iOS folder is in Home's attention set.
     */
    private fun board(attentionKnown: Boolean = true, isLive: Boolean = true, directories: Set<String> = setOf("/Users/kit/opencode-ios")) = VoiceSessionBoard(
        serverID = server.persistenceID,
        sessions = sessions,
        busyIDs = setOf(earpiece.id, upload.id),
        attentionIDs = setOf(upload.id),
        unreadIDs = setOf(docs.id),
        pinnedIDs = listOf(math.id, earpiece.id),
        attentionDirectories = directories,
        isLive = isLive,
        attentionKnown = attentionKnown,
    )

    /**
     * Counts every read, so tests can prove the tools never fan
     * location-scoped reads out over the session history.
     */
    private class Reads {
        val counts = Locked(mapOf<String, Int>())
        fun hit(name: String) = counts.update { it + (name to ((it[name] ?: 0) + 1)) }
        operator fun get(name: String): Int = counts.value[name] ?: 0
        val locationScoped: Int get() = this["homeAttention"] + this["familyPendingRequests"] + this["runningShells"] + this["modelCatalog"]
    }

    private fun client(reads: Reads, active: Set<String> = setOf("ses_ear", "ses_upload"), search: (String) -> List<OpenCodeSession> = { emptyList() }): OpenCodeClient {
        val sessions = sessions
        return OpenCodeClient.testValue.copy(
            homeSnapshot = {
                reads.hit("homeSnapshot")
                OpenCodeHomeSnapshot(projects = emptyList(), sessions = sessions, activeSessionIDs = active)
            },
            searchSessions = { _, query, _ ->
                reads.hit("searchSessions")
                reads.hit("search:$query")
                search(query)
            },
            homeAttention = { _, _, _ -> reads.hit("homeAttention"); emptySet() },
            familyPendingRequests = { _, _, _ -> reads.hit("familyPendingRequests"); OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
            runningShells = { _, _ -> reads.hit("runningShells"); emptyList() },
            modelCatalog = { _, _ -> reads.hit("modelCatalog"); throw OpenCodeClientError("unused") },
            getSession = { _, id -> reads.hit("getSession"); throw OpenCodeClientError("unused $id") },
            transcript = { _, _, _ -> reads.hit("transcript"); emptyList() },
            isSessionActive = { _, _ -> reads.hit("isSessionActive"); false },
        )
    }

    private fun workspace(
        client: OpenCodeClient,
        board: VoiceSessionBoard?,
        viewing: OpenCodeSession? = null,
        viewingNeedsInput: Boolean = false,
        pinnedOrder: List<String> = emptyList(),
    ): VoiceDelegatorWorkspace = VoiceDelegatorWorkspace(
        client = client,
        start = VoiceDelegatorWorkspace.Start(
            server = server,
            viewing = viewing,
            draftDirectory = "/Users/kit/opencode-ios",
            draftModel = null,
            transcriptDelta = emptyList(),
            explained = emptySet(),
            pinned = pinnedOrder.toSet(),
            pinnedOrder = pinnedOrder,
            board = { board },
            viewingNeedsInput = viewingNeedsInput,
        ),
        effects = VoiceDelegatorWorkspace.Effects(open = {}, explained = {}, note = {}),
    )

    private suspend fun rows(workspace: VoiceDelegatorWorkspace, name: String, arguments: String = "{}"): List<JsonObject> {
        val output = workspace.run(VoiceDelegator.ToolCall(callID = "c", name = name, arguments = arguments))
        val parsed = runCatching { Json.parseToJsonElement(output) as JsonArray }.getOrNull() ?: throw AssertionError(output)
        return parsed.map { it.jsonObject }
    }

    private fun row(rows: List<JsonObject>, id: String): JsonObject =
        rows.firstOrNull { it["id"].string() == id } ?: throw AssertionError("No row $id")

    // MARK: Schemas

    @Test fun schemasAddFiltersAndSearchWithoutDroppingAnyTool() {
        val tools = VoiceDelegator.tools
        val names = tools.mapNotNull { it["name"].string() }
        for (name in listOf(
            "list_sessions", "pin_session", "unpin_session", "new_session_draft", "list_projects", "open_session",
            "read_session", "send_message", "send_call_log", "list_queued", "steer_queued", "cancel_queued",
            "list_requests", "reply_permission", "answer_question", "dismiss_question", "end_call",
            "create_session", "wait_for_session", "interrupt_session", "search_sessions", "list_servers",
        )) {
            assertTrue(name, names.contains(name))
        }
        val list = tools.first { it["name"].string() == "list_sessions" }
        val listProperties = list["parameters"]!!.jsonObject["properties"]!!.jsonObject
        assertNotNull(listProperties["limit"])
        assertNotNull(listProperties["pinned_only"])
        val filter = listProperties["filter"]!!.jsonObject
        assertEquals(listOf("pinned", "unread", "needs_input", "running"), filter["enum"].strings())
        val description = list["description"].string()!!
        for (field in listOf("working", "unread", "needs_input", "pinned_order", "on_screen", "unknown")) {
            assertTrue(field, description.contains(field))
        }

        val search = tools.first { it["name"].string() == "search_sessions" }
        val searchParameters = search["parameters"]!!.jsonObject
        assertEquals(listOf("query"), searchParameters["required"].strings())
        assertEquals(listOf("limit", "query", "server_id"), searchParameters["properties"]?.jsonObject?.keyNames())
        assertTrue(VoiceDelegator.instructions.contains("search_sessions"))
        assertTrue(VoiceDelegator.instructions.contains("list_sessions with that filter"))
    }

    // MARK: list_sessions

    @Test fun listSessionsMapsUnreadNeedsInputRunningAndPins() = runBlocking {
        val reads = Reads()
        val rows = rows(workspace(client(reads), board = board(), viewing = docs), "list_sessions")
        assertEquals(listOf(earpiece.id, upload.id, docs.id, math.id), rows.map { it["id"].string() })

        val ear = row(rows, earpiece.id)
        assertEquals(true, ear["working"].bool())
        assertEquals(false, ear["needs_input"].bool())
        assertEquals(false, ear["unread"].bool())
        assertEquals(true, ear["pinned"].bool())
        assertEquals(2, ear["pinned_order"].int())
        assertEquals("opencode-ios", ear["project"].string())

        val waiting = row(rows, upload.id)
        assertEquals(true, waiting["working"].bool())
        assertEquals(true, waiting["needs_input"].bool())
        assertEquals(false, waiting["pinned"].bool())
        assertNull(waiting["pinned_order"])

        val unread = row(rows, docs.id)
        assertEquals(true, unread["unread"].bool())
        assertEquals(false, unread["needs_input"].bool())
        assertEquals(false, unread["working"].bool())
        assertEquals(true, unread["on_screen"].bool())

        assertEquals(1, row(rows, math.id)["pinned_order"].int())
        // One session list, no location-scoped reads.
        assertEquals(1, reads["homeSnapshot"])
        assertEquals(0, reads.locationScoped)
        assertTrue(reads["getSession"] == 0 && reads["transcript"] == 0 && reads["isSessionActive"] == 0)
    }

    @Test fun listSessionsFilters() = runBlocking {
        val reads = Reads()
        val tools = workspace(client(reads), board = board(directories = emptySet()))
        val pinned = rows(tools, "list_sessions", """{"filter":"pinned"}""")
        assertEquals(listOf(math.id, earpiece.id), pinned.map { it["id"].string() })
        val pinnedOnly = rows(tools, "list_sessions", """{"pinned_only":true}""")
        assertEquals(listOf(math.id, earpiece.id), pinnedOnly.map { it["id"].string() })
        val unread = rows(tools, "list_sessions", """{"filter":"unread"}""")
        assertEquals(listOf(docs.id), unread.map { it["id"].string() })
        val running = rows(tools, "list_sessions", """{"filter":"running"}""")
        assertEquals(listOf(earpiece.id, upload.id), running.map { it["id"].string() })
        // The waiting session leads, and the other running one can't be ruled out.
        val waiting = rows(tools, "list_sessions", """{"filter":"needs_input"}""")
        assertEquals(listOf(upload.id, earpiece.id), waiting.map { it["id"].string() })
        assertEquals(listOf<JsonElement?>(JsonPrimitive(true), JsonPrimitive("unknown")), waiting.map { it["needs_input"] })
        val limited = rows(tools, "list_sessions", """{"limit":2}""")
        assertEquals(2, limited.size)
        val unknownFilter = tools.run(VoiceDelegator.ToolCall(callID = "c", name = "list_sessions", arguments = """{"filter":"muted"}"""))
        assertTrue(unknownFilter.contains("error"))
        assertEquals(0, reads.locationScoped)
    }

    @Test fun aFailedRequestSnapshotIsUnknownNotNone() = runBlocking {
        val reads = Reads()
        val rows = rows(workspace(client(reads), board = board(attentionKnown = false)), "list_sessions")
        // Home still shows the waiting session it already knew about.
        assertEquals(true, row(rows, upload.id)["needs_input"].bool())
        // The other running session might be waiting too.
        assertEquals("unknown", row(rows, earpiece.id)["needs_input"].string())
        // A session blocked on a request is running, so idle ones have none.
        assertEquals(false, row(rows, docs.id)["needs_input"].bool())
        assertEquals(0, reads.locationScoped)
    }

    @Test fun withoutTheDrawerStatusIsUnknownRatherThanGuessed() = runBlocking {
        val reads = Reads()
        val disconnected = rows(workspace(client(reads), board = board(isLive = false)), "list_sessions")
        assertEquals("unknown", row(disconnected, upload.id)["needs_input"].string())
        assertEquals("unknown", row(disconnected, docs.id)["unread"].string())

        val seen = docs.copy(idleMilliseconds = 5_000, viewedMilliseconds = 1_000)
        var tools = workspace(client(reads), board = null, viewing = earpiece, viewingNeedsInput = true, pinnedOrder = listOf(math.id))
        var listed = rows(tools, "list_sessions")
        assertEquals(true, row(listed, earpiece.id)["needs_input"].bool())
        assertEquals("unknown", row(listed, upload.id)["needs_input"].string())
        assertEquals(false, row(listed, docs.id)["needs_input"].bool())
        assertEquals("unknown", row(listed, docs.id)["unread"].string())
        assertEquals(1, row(listed, math.id)["pinned_order"].int())

        // Outside the drawer's list, the server's own read state answers.
        val snapshotClient = client(reads).copy(
            homeSnapshot = { OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(seen), activeSessionIDs = emptySet()) },
        )
        tools = workspace(snapshotClient, board = null)
        listed = rows(tools, "list_sessions")
        assertEquals(true, row(listed, docs.id)["unread"].bool())
        assertEquals(0, reads.locationScoped)
    }

    @Test fun pinsMadeDuringTheRequestShowBeforeTheDrawerCatchesUp() = runBlocking {
        val reads = Reads()
        val docs = docs
        val pinning = client(reads).copy(getSession = { _, _ -> docs })
        val board = board()
        val tools = VoiceDelegatorWorkspace(
            client = pinning,
            start = VoiceDelegatorWorkspace.Start(server = server, viewing = null, draftDirectory = "/", draftModel = null, transcriptDelta = emptyList(), explained = emptySet(), board = { board }),
            effects = VoiceDelegatorWorkspace.Effects(open = {}, setPinned = { _, _ -> }, explained = {}, note = {}),
        )
        tools.run(VoiceDelegator.ToolCall(callID = "p", name = "pin_session", arguments = """{"session_id":"ses_docs"}"""))
        val pinned = rows(tools, "list_sessions", """{"filter":"pinned"}""")
        assertEquals(listOf(math.id, earpiece.id, docs.id), pinned.map { it["id"].string() })
        assertEquals(3, row(pinned, docs.id)["pinned_order"].int())
    }

    // MARK: search_sessions

    @Test fun searchMatchesSpokenQueries() {
        val search = { query: String -> VoiceSessionSearch(query) }
        assertEquals(100, search("Fix the earpiece routing").score(earpiece))
        assertNotNull(search("earpiece").score(earpiece))
        // Word order, filler words, prefixes, and a project name.
        assertNotNull(search("the routing earpiece session").score(earpiece))
        assertNotNull(search("rout ear").score(earpiece))
        assertNotNull(search("opencode ios upload").score(upload))
        // Run-together and misheard words.
        assertEquals(100, search("docs refresh").score(docs))
        assertNotNull(search("docsrefresh").score(docs))
        assertNotNull(search("uplaod retries").score(upload))
        assertEquals(100, search("Math-bug!").score(math))
        assertNotNull(search("ÉARPIECE").score(earpiece))
        // Unrelated words don't match.
        assertNull(search("database migration").score(earpiece))
        assertNull(search("the").score(math))
        // An exact title ranks above a partial one.
        assertTrue(search("upload retries").score(upload)!! > search("upload").score(upload)!!)
    }

    @Test fun searchSessionsMergesServerAndDrawerMatchesWithStatus() = runBlocking {
        val reads = Reads()
        val older = OpenCodeSession(id = "ses_old", serverTitle = "Earpiece audio route (old)", projectID = "ios", directory = "/Users/kit/opencode-ios", updatedAt = Instant.ofEpochSecond(1))
        val tools = workspace(client(reads) { query -> if (query == "earpiece") listOf(older) else emptyList() }, board = board())
        val rows = rows(tools, "search_sessions", """{"query":"the earpiece session"}""")
        assertEquals(listOf(earpiece.id, older.id), rows.map { it["id"].string() })
        val ear = row(rows, earpiece.id)
        assertEquals(true, ear["working"].bool())
        assertEquals(false, ear["needs_input"].bool())
        assertEquals(2, ear["pinned_order"].int())
        assertEquals(false, ear["unread"].bool())
        // Outside the drawer: not running, so not waiting; read state unknown.
        val old = row(rows, older.id)
        assertEquals(false, old["working"].bool())
        assertEquals(false, old["needs_input"].bool())
        assertEquals("unknown", old["unread"].string())
        // The phrase first, then its most specific word; the drawer's list
        // instead of another snapshot; nothing location-scoped.
        assertEquals(1, reads["search:the earpiece session"])
        assertEquals(1, reads["search:earpiece"])
        assertEquals(2, reads["searchSessions"])
        assertEquals(0, reads["homeSnapshot"])
        assertEquals(0, reads.locationScoped)
    }

    @Test fun searchFallsBackToRecentSessionsWhenTheServerFails() = runBlocking {
        val reads = Reads()
        val tools = workspace(client(reads) { throw OpenCodeClientError("offline") }, board = null)
        val rows = rows(tools, "search_sessions", """{"query":"math"}""")
        assertEquals(listOf(math.id), rows.map { it["id"].string() })
        assertEquals(1, reads["homeSnapshot"])
        val none = tools.run(VoiceDelegator.ToolCall(callID = "c", name = "search_sessions", arguments = """{"query":"kubernetes"}"""))
        assertTrue(none.contains("Couldn’t search sessions"))
        val missing = tools.run(VoiceDelegator.ToolCall(callID = "c", name = "search_sessions", arguments = "{}"))
        assertTrue(missing.contains("Missing query"))
        assertEquals(0, reads.locationScoped)
    }

    // MARK: Other servers

    /** A second saved server with its own credentials, whose only session is running. */
    private val studio = OpenCodeServer(url = "https://studio.tailnet.ts.net:4096", username = "opencode", password = "studio-secret", name = "Studio")
    private val render = OpenCodeSession(id = "ses_render", serverTitle = "Render farm", projectID = "farm", directory = "/srv/farm", updatedAt = Instant.ofEpochSecond(500))

    /** Every server a read went to (by URL) and the password it carried. */
    private fun multiServerClient(seen: Locked<List<String>>): OpenCodeClient {
        val home = server
        val studio = studio
        val local = sessions
        val render = render
        return OpenCodeClient.testValue.copy(
            homeSnapshot = { requested ->
                seen.update { it + "homeSnapshot ${requested.url} ${requested.password}" }
                if (requested.url == studio.url) {
                    OpenCodeHomeSnapshot(projects = listOf(OpenCodeProject(id = "farm", name = "farm", directory = "/srv/farm")), sessions = listOf(render), activeSessionIDs = setOf(render.id))
                } else {
                    OpenCodeHomeSnapshot(projects = emptyList(), sessions = local, activeSessionIDs = emptySet())
                }
            },
            getSession = { requested, id ->
                seen.update { it + "getSession ${requested.url} $id" }
                if (requested.url != studio.url || id != render.id) {
                    throw OpenCodeClientError("Not found on ${if (requested.url == home.url) "home" else "studio"}")
                }
                render
            },
            isSessionActive = { requested, _ -> requested.url == studio.url },
            transcript = { requested, _, _ ->
                seen.update { it + "transcript ${requested.url}" }
                emptyList()
            },
        )
    }

    private fun multiServerWorkspace(client: OpenCodeClient, viewing: OpenCodeSession? = null): VoiceDelegatorWorkspace = VoiceDelegatorWorkspace(
        client = client,
        start = VoiceDelegatorWorkspace.Start(
            server = server,
            viewing = viewing,
            draftDirectory = "/Users/kit/opencode-ios",
            draftModel = null,
            transcriptDelta = emptyList(),
            explained = emptySet(),
            servers = listOf(studio, server),
            pinnedSessionKeys = listOf("${studio.persistenceID}|${render.id}", "${server.persistenceID}|${math.id}"),
        ),
        effects = VoiceDelegatorWorkspace.Effects(open = { fail("Reading another server never moves the screen") }, explained = {}, note = {}),
    )

    private suspend fun obj(workspace: VoiceDelegatorWorkspace, name: String, arguments: String): JsonObject {
        val output = workspace.run(VoiceDelegator.ToolCall(callID = "c", name = name, arguments = arguments))
        return runCatching { Json.parseToJsonElement(output) as JsonObject }.getOrNull() ?: throw AssertionError(output)
    }

    @Test fun listServersNamesEachSavedServerOnceWithoutItsAddressOrCredentials() = runBlocking {
        val workspace = multiServerWorkspace(multiServerClient(Locked(emptyList())))
        val output = workspace.run(VoiceDelegator.ToolCall(callID = "c", name = "list_servers", arguments = "{}"))
        val listed = Json.parseToJsonElement(output).jsonArray.map { it.jsonObject }
        assertEquals(listOf(server.friendlyName, "Studio"), listed.map { it["name"].string() })
        assertEquals(listOf(true, false), listed.map { it["current"].bool() })
        assertEquals(2, listed.mapNotNull { it["server_id"].string() }.toSet().size)
        assertTrue(!output.contains("tailnet") && !output.contains("studio-secret") && !output.contains("localhost"))
    }

    @Test fun anotherServersSessionsAreListedWithItsOwnCredentialsAndNamed() = runBlocking {
        val seen = Locked(listOf<String>())
        val workspace = multiServerWorkspace(multiServerClient(seen))
        val rows = rows(workspace, "list_sessions", """{"server_id":"Studio","filter":"pinned"}""")
        val remote = row(rows, render.id)
        assertEquals(1, rows.size)
        assertEquals("Studio", remote["server"].string())
        assertEquals(VoiceDelegatorWorkspace.serverID(studio), remote["server_id"].string())
        assertEquals(true, remote["working"].bool())
        assertEquals(false, remote["on_screen"].bool())
        assertEquals(listOf("homeSnapshot ${studio.url} studio-secret"), seen.value)
    }

    @Test fun aSessionOnAnotherServerIsReadThereByItsServerID() = runBlocking {
        val seen = Locked(listOf<String>())
        val workspace = multiServerWorkspace(multiServerClient(seen), viewing = docs)
        val id = VoiceDelegatorWorkspace.serverID(studio)
        val read = obj(workspace, "read_session", """{"server_id":"$id","session_id":"ses_render"}""")
        assertEquals("Render farm", read["title"].string())
        assertEquals(true, read["working"].bool())
        assertEquals("Studio", read["server"].string())
        assertEquals(listOf("getSession ${studio.url} ses_render", "transcript ${studio.url}"), seen.value)
    }

    @Test fun withoutAServerIDToolsStayOnThisCallsServer() = runBlocking {
        val seen = Locked(listOf<String>())
        val workspace = multiServerWorkspace(multiServerClient(seen))
        val rows = rows(workspace, "list_sessions")
        assertEquals(sessions.map { it.id }, rows.map { it["id"].string() })
        assertTrue(rows.all { it["server"] == null })
        assertEquals(listOf("homeSnapshot ${server.url} "), seen.value)
    }

    @Test fun actingOnAnotherServerIsRefusedBeforeAnyRequest() = runBlocking {
        val seen = Locked(listOf<String>())
        val workspace = multiServerWorkspace(multiServerClient(seen), viewing = docs)
        val refused = obj(workspace, "send_message", """{"server_id":"Studio","session_id":"ses_render","text":"stop"}""")
        assertEquals(true, refused["error"].string()?.contains("Studio open in the app"))
        assertTrue(seen.value.isEmpty())
    }

    @Test fun anUnknownServerIsRefusedRatherThanGuessed() = runBlocking {
        val seen = Locked(listOf<String>())
        val workspace = multiServerWorkspace(multiServerClient(seen))
        val refused = obj(workspace, "read_session", """{"server_id":"https://evil.example","session_id":"ses_render"}""")
        assertEquals(true, refused["error"].string()?.contains("list_servers"))
        assertTrue(seen.value.isEmpty())
    }
}

/**
 * The delegator's model tools: `list_models` and `switch_model`, for the
 * session on screen, another session, and a draft.
 */
class VoiceModelToolsTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val fable = OpenCodeSession(
        id = "ses_fable", serverTitle = "Fable refactor", projectID = "ios", directory = "/Users/kit/opencode-ios", updatedAt = distantPast,
        model = OpenCodeModelRef(id = "gpt-5.4", providerID = "openai", variant = "high"),
    )
    private val docs = OpenCodeSession(id = "ses_docs", serverTitle = "Docs refresh", projectID = "web", directory = "/Users/kit/website", updatedAt = distantPast)

    private val catalog = OpenCodeModelCatalog(
        models = listOf(
            OpenCodeModel(modelID = "gpt-5.4", providerID = "openai", name = "GPT-5.4", variants = listOf("low", "high")),
            OpenCodeModel(modelID = "gpt-5.5", providerID = "openai", name = "GPT-5.5", variants = listOf("high", "low", "xhigh")),
            OpenCodeModel(modelID = "claude-opus-4-6", providerID = "anthropic", name = "Claude Opus 4.6", variants = listOf("max")),
            OpenCodeModel(modelID = "claude-sonnet-4-6", providerID = "anthropic", name = "Claude Sonnet 4.6"),
        ),
        defaultModel = null,
    )

    private class Calls {
        val switched = Locked(listOf<String>())
        val screen = Locked(listOf<String>())
        val applied = Locked(listOf<String>())
        val notes = Locked(listOf<String>())
        val prompts = Locked(listOf<String>())
    }

    private fun workspace(
        calls: Calls,
        viewing: OpenCodeSession?,
        draftModel: OpenCodeModelRef? = null,
        viewingModel: OpenCodeModelRef? = null,
        favorites: Set<String> = emptySet(),
        defaultModel: OpenCodeModel? = null,
        levels: Map<String, String> = emptyMap(),
    ): VoiceDelegatorWorkspace {
        val catalog = OpenCodeModelCatalog(models = catalog.models, defaultModel = defaultModel)
        val sessions = listOf(fable, docs)
        val client = OpenCodeClient.testValue.copy(
            modelCatalog = { _, _ -> catalog },
            getSession = { _, id -> sessions.firstOrNull { it.id == id } ?: throw OpenCodeClientError("No session $id") },
            switchModel = { _, id, ref -> calls.switched.update { it + "$id ${ref.providerID}/${ref.id} ${ref.variant ?: "-"}" } },
            createSession = { _, directory, _ -> OpenCodeSession(id = "ses_new", serverTitle = "New", projectID = "p", directory = directory, updatedAt = distantPast) },
            moveToBackground = { _, _ -> },
            setPendingInputDelivery = { _, _, _, _ -> },
            // Every steer reads its target's requests first: none here.
            familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
            sendDeliveredPrompt = { _, id, _, _ ->
                calls.prompts.update { it + id }
                calls.switched.update { it + "prompt $id" }
                "msg_${calls.prompts.value.size}"
            },
        )
        return VoiceDelegatorWorkspace(
            client = client,
            start = VoiceDelegatorWorkspace.Start(
                server = server,
                // The call names the session on screen without its model.
                viewing = viewing?.let { OpenCodeSession(id = it.id, serverTitle = it.title, projectID = "", directory = it.directory, updatedAt = distantPast) },
                draftDirectory = "/Users/kit/opencode-ios",
                draftModel = draftModel,
                viewingModel = viewingModel,
                transcriptDelta = emptyList(),
                explained = emptySet(),
                favoriteModelIDs = favorites,
                modelVariants = levels,
            ),
            effects = VoiceDelegatorWorkspace.Effects(
                open = {},
                switchedModel = { id, ref -> calls.screen.update { it + "${id ?: "draft"} ${ref.providerID}/${ref.id} ${ref.variant ?: "-"}" } },
                appliedPick = { id, ref -> calls.applied.update { it + "$id ${ref.providerID}/${ref.id} ${ref.variant ?: "-"}" } },
                explained = {},
                note = { note -> calls.notes.update { it + note } },
            ),
        )
    }

    private suspend fun obj(workspace: VoiceDelegatorWorkspace, name: String, arguments: String = "{}"): JsonObject {
        val output = workspace.run(VoiceDelegator.ToolCall(callID = "c", name = name, arguments = arguments))
        return runCatching { Json.parseToJsonElement(output) as JsonObject }.getOrNull() ?: throw AssertionError(output)
    }

    private fun JsonObject.models(): List<JsonObject>? = (this["models"] as? JsonArray)?.map { it.jsonObject }

    @Test fun schemasOfferModelTools() {
        val tools = VoiceDelegator.tools
        val list = tools.first { it["name"].string() == "list_models" }
        val listParameters = list["parameters"]!!.jsonObject
        assertEquals(listOf("limit", "query", "session_id"), listParameters["properties"]?.jsonObject?.keyNames())
        val switchModel = tools.first { it["name"].string() == "switch_model" }
        val switchParameters = switchModel["parameters"]!!.jsonObject
        assertEquals(listOf("model"), switchParameters["required"].strings())
        assertEquals(listOf("model", "session_id", "variant"), switchParameters["properties"]?.jsonObject?.keyNames())
        assertTrue(VoiceDelegator.instructions.contains("list_models"))
        assertTrue(VoiceDelegator.instructions.contains("switch_model"))
    }

    @Test fun spokenQueriesFindTheirModel() {
        val models = catalog.models
        assertEquals("openai/gpt-5.5", VoiceDelegatorWorkspace.matching(models, "gpt 5.5").firstOrNull()?.id)
        assertEquals("openai/gpt-5.5", VoiceDelegatorWorkspace.matching(models, "GPT-5.5").firstOrNull()?.id)
        assertEquals(listOf("anthropic/claude-opus-4-6"), VoiceDelegatorWorkspace.matching(models, "opus").map { it.id })
        assertEquals("anthropic/claude-sonnet-4-6", VoiceDelegatorWorkspace.matching(models, "anthropic sonnet").firstOrNull()?.id)
        assertTrue(VoiceDelegatorWorkspace.matching(models, "gemini").isEmpty())
        assertEquals(models.size, VoiceDelegatorWorkspace.matching(models, "  ").size)
    }

    @Test fun listModelsMarksTheCurrentModelAndListsFavoritesFirst() = runBlocking {
        val calls = Calls()
        val listed = obj(workspace(calls, viewing = fable, favorites = setOf("anthropic/claude-opus-4-6")), "list_models")
        assertEquals("Fable refactor", listed["session"].string())
        val models = listed.models()!!
        assertEquals(listOf("anthropic/claude-opus-4-6", "openai/gpt-5.4", "openai/gpt-5.5", "anthropic/claude-sonnet-4-6"), models.map { it["id"].string() })
        assertEquals(true, models[0]["favorite"].bool())
        // The current model is read from the server, not the call's bare session.
        assertEquals(true, models[1]["current"].bool())
        assertEquals("high", models[1]["current_variant"].string())
        assertEquals(false, models[2]["current"].bool())
        assertEquals(listOf("low", "high", "xhigh"), models[2]["variants"].strings())

        val found = obj(workspace(calls, viewing = fable), "list_models", """{"query":"opus","limit":5}""")
        assertEquals(listOf("anthropic/claude-opus-4-6"), found.models()?.map { it["id"].string() })
        assertEquals(1, found["matches"].int())
    }

    @Test fun switchModelChangesTheViewedSessionAndKeepsItsVariant() = runBlocking {
        val calls = Calls()
        val tools = workspace(calls, viewing = fable)
        val result = obj(tools, "switch_model", """{"model":"openai/gpt-5.5"}""")
        assertEquals("GPT-5.5", result["switched_to"].string())
        assertEquals("high", result["variant"].string())
        assertEquals(listOf("ses_fable openai/gpt-5.5 high"), calls.switched.value)
        assertEquals(listOf("ses_fable openai/gpt-5.5 high"), calls.screen.value)
        assertEquals(listOf("Switched “Fable refactor” to GPT-5.5 (High)."), calls.notes.value)

        // A variant the new model lacks falls back to its default; an asked-for one is checked.
        obj(tools, "switch_model", """{"model":"anthropic/claude-opus-4-6"}""")
        obj(tools, "switch_model", """{"model":"anthropic/claude-opus-4-6","variant":"max","session_id":"ses_docs"}""")
        assertEquals(listOf("ses_fable anthropic/claude-opus-4-6 -", "ses_docs anthropic/claude-opus-4-6 max"), calls.switched.value.takeLast(2))
    }

    /**
     * The screen's waiting pick goes with the delegator's next prompt into
     * that session, as a typed send's would, and only once.
     */
    @Test fun aPromptIntoTheViewedSessionAppliesTheScreensPick() = runBlocking {
        val calls = Calls()
        val pick = OpenCodeModelRef(id = "gpt-5.5", providerID = "openai", variant = "xhigh")
        val tools = workspace(calls, viewing = fable, viewingModel = pick)
        val listed = obj(tools, "list_models", """{"query":"gpt-5.5"}""")
        assertEquals("It reports the pick", "xhigh", listed.models()?.firstOrNull()?.get("current_variant").string())

        // Another session's prompt leaves it waiting.
        obj(tools, "send_message", """{"session_id":"ses_docs","text":"Update the docs"}""")
        assertEquals(listOf("prompt ses_docs"), calls.switched.value)

        obj(tools, "send_message", """{"text":"Fix it"}""")
        obj(tools, "send_message", """{"text":"And test it"}""")
        assertEquals(listOf("prompt ses_docs", "ses_fable openai/gpt-5.5 xhigh", "prompt ses_fable", "prompt ses_fable"), calls.switched.value)
        assertEquals(listOf("ses_fable openai/gpt-5.5 xhigh"), calls.applied.value)

        // An explicit switch replaces a pick that hasn't gone yet.
        val replaced = Calls()
        val other = workspace(replaced, viewing = fable, viewingModel = pick)
        obj(other, "switch_model", """{"model":"anthropic/claude-opus-4-6"}""")
        obj(other, "send_message", """{"text":"Fix it"}""")
        assertEquals(listOf("ses_fable anthropic/claude-opus-4-6 -", "prompt ses_fable"), replaced.switched.value)
        assertTrue(replaced.applied.value.isEmpty())
    }

    /**
     * A queued message mustn't switch the run it waits behind, as a typed
     * queued send doesn't: the pick waits for a steer.
     */
    @Test fun aQueuedPromptLeavesTheScreensPickWaiting() = runBlocking {
        val calls = Calls()
        val pick = OpenCodeModelRef(id = "gpt-5.5", providerID = "openai", variant = "xhigh")
        val tools = workspace(calls, viewing = fable, viewingModel = pick)
        obj(tools, "send_message", """{"text":"After this, update the docs","delivery":"queue"}""")
        assertEquals(listOf("prompt ses_fable"), calls.switched.value)
        assertTrue(calls.applied.value.isEmpty())
        obj(tools, "send_message", """{"text":"Fix it"}""")
        assertEquals(listOf("prompt ses_fable", "ses_fable openai/gpt-5.5 xhigh", "prompt ses_fable"), calls.switched.value)
        assertEquals(listOf("ses_fable openai/gpt-5.5 xhigh"), calls.applied.value)

        // Steering the queued message applies the pick first.
        val steered = Calls()
        val other = workspace(steered, viewing = fable, viewingModel = pick)
        obj(other, "send_message", """{"text":"After this, update the docs","delivery":"queue"}""")
        obj(other, "steer_queued", """{"message_id":"msg_1"}""")
        assertEquals(listOf("prompt ses_fable", "ses_fable openai/gpt-5.5 xhigh"), steered.switched.value)
        assertEquals(listOf("ses_fable openai/gpt-5.5 xhigh"), steered.applied.value)
    }

    @Test fun switchModelRefusesUnknownModelsAndVariants() = runBlocking {
        val calls = Calls()
        val tools = workspace(calls, viewing = fable)
        val unknown = obj(tools, "switch_model", """{"model":"opus"}""")
        assertEquals(true, unknown["error"].string()?.contains("list_models"))
        val variant = obj(tools, "switch_model", """{"model":"openai/gpt-5.5","variant":"max"}""")
        assertEquals("GPT-5.5 has no max variant; it offers low, high, xhigh.", variant["error"].string())
        val none = obj(tools, "switch_model", """{"model":"anthropic/claude-sonnet-4-6","variant":"high"}""")
        assertEquals("Claude Sonnet 4.6 has no variants; omit variant.", none["error"].string())
        assertTrue(calls.switched.value.isEmpty())
        assertTrue(calls.screen.value.isEmpty())
    }

    @Test fun onADraftTheModelWaitsForTheSessionItCreates() = runBlocking {
        val calls = Calls()
        val tools = workspace(calls, viewing = null, draftModel = OpenCodeModelRef(id = "gpt-5.4", providerID = "openai", variant = "low"))
        val listed = obj(tools, "list_models", """{"query":"gpt"}""")
        assertEquals("new session draft", listed["session"].string())
        assertEquals(true, listed.models()?.firstOrNull()?.get("current").bool())

        val result = obj(tools, "switch_model", """{"model":"openai/gpt-5.5"}""")
        assertEquals("new session draft", result["session"].string())
        assertTrue("A draft has no session to switch yet", calls.switched.value.isEmpty())
        assertEquals(listOf("draft openai/gpt-5.5 low"), calls.screen.value)

        obj(tools, "create_session", """{"directory":"/Users/kit/opencode-ios"}""")
        assertEquals(listOf("ses_new openai/gpt-5.5 low"), calls.switched.value)
    }

    @Test fun aSessionCreatedFromASessionStartsOnTheServersDefaultModel() = runBlocking {
        // Not the newest session's model (Fable's GPT-5.4 High): the server's
        // default, at the level last picked for it.
        val calls = Calls()
        val opus = catalog.models[2]
        val create = """{"directory":"/Users/kit/website"}"""
        obj(workspace(calls, viewing = docs, defaultModel = opus, levels = mapOf(opus.id to "max")), "create_session", create)
        assertEquals(listOf("ses_new anthropic/claude-opus-4-6 max"), calls.switched.value)

        // Nothing remembered for it: the model's own default level.
        obj(workspace(calls, viewing = docs, defaultModel = opus), "create_session", create)
        assertEquals("ses_new anthropic/claude-opus-4-6 -", calls.switched.value.last())

        // A draft model left over from before the call moved to a session is not used.
        val gpt = OpenCodeModelRef(id = "gpt-5.5", providerID = "openai", variant = "xhigh")
        obj(workspace(calls, viewing = docs, draftModel = gpt, defaultModel = opus), "create_session", create)
        assertEquals("ses_new anthropic/claude-opus-4-6 -", calls.switched.value.last())

        // A server that names no default picks the model itself.
        obj(workspace(calls, viewing = docs), "create_session", create)
        assertEquals(3, calls.switched.value.size)
    }

    @Test fun aDraftWhoseModelHasntLoadedStartsOnTheServersDefault() = runBlocking {
        val calls = Calls()
        val opus = catalog.models[2]
        obj(workspace(calls, viewing = null, defaultModel = opus, levels = mapOf(opus.id to "max")), "create_session", """{"directory":"/Users/kit/opencode-ios"}""")
        assertEquals(listOf("ses_new anthropic/claude-opus-4-6 max"), calls.switched.value)
    }
}
