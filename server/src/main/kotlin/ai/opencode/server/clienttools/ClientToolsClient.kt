package ai.opencode.server.clienttools

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeTransport
import ai.opencode.server.ServerLinkClient
import ai.opencode.server.live.logDescription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * Where the phone offers its tools: one server's location, listed to sessions in [directory] as
 * `android_<tool>`. Equal when made of the same parts, so only a real change reconnects.
 */
class ClientToolsTarget(
    val server: OpenCodeServer,
    val directory: String,
    /** What `tools/list` offers there: the experiments that are on. */
    val tools: Set<ClientTool>,
) {
    override fun equals(other: Any?): Boolean {
        if (other !is ClientToolsTarget) return false
        return directory == other.directory && tools == other.tools && sameConnection(server, other.server)
    }

    override fun hashCode(): Int {
        var result = directory.hashCode()
        result = 31 * result + tools.hashCode()
        result = 31 * result + server.url.hashCode()
        return result
    }

    /** No address or credentials: targets show up in test failures and logs. */
    override fun toString(): String = "ClientToolsTarget(tools=${tools.map { it.rawValue }.sorted()})"

    companion object {
        /** The same server as far as a socket goes: address, credentials, headers. */
        fun sameConnection(lhs: OpenCodeServer, rhs: OpenCodeServer): Boolean =
            lhs.url == rhs.url && lhs.username == rhs.username && lhs.password == rhs.password && lhs.headers == rhs.headers
    }
}

/**
 * The tools the phone hosts, all for a live call: navigation during a session-delegator call, links
 * and audio, and the opt-in screen.
 */
enum class ClientTool(val rawValue: String) {
    voiceOpenSession("voice_open_session"),
    voiceRequestScreen("voice_request_screen"),
    voiceOpenURL("voice_open_url"),
    voicePlayAudio("voice_play_audio"),
    voiceAudioControl("voice_audio_control"),
    ;

    val description: String
        get() = when (this) {
            voiceOpenSession ->
                "Open a session on the phone during a live voice call. Returns opened only after navigation and drawer closure."
            voiceRequestScreen ->
                "During a voice call, ask the user to share the OpenCode Android app's screen. They see a card with Share and Not Now; on Share you get a JPEG of the app's own window (never other apps). Refused when they say no, don't answer within a minute, the call ends, or a password, pairing or Settings screen shows."
            voicePlayAudio ->
                "During a live voice call in the OpenCode Android app, play music or a sound clip on the call (MP3, M4A/AAC, WAV, OGG or FLAC, up to 200 MB). The phone downloads the https URL and returns once it is playing, replacing anything already playing; it ducks while anyone speaks and stops when the call ends. For a file on this machine, make it reachable over https first (upload it, or serve it where the phone can reach it). Refused when there's no live call."
            voiceAudioControl ->
                "Pause, resume or stop the audio playing on the user's voice call (started with voice_play_audio)."
            voiceOpenURL ->
                "During a voice call, open a link on the user's Android phone: an https link (app links open their app when it's installed, such as https://m.uber.com/ul/…, else the browser) or an app's own link scheme. They see a card with Open and Not Now; you hear whether it opened. Refused when they say no, don't answer within a minute, the call ends, the phone isn't showing OpenCode (then text them the link), or the link isn't https or an app link (no http, javascript, file, content, intent, data, tel, sms or mailto)."
        }

    /** MCP hints: only the screen request is read-only. */
    val annotations: JsonObject
        get() = buildJsonObject {
            when (this@ClientTool) {
                voiceRequestScreen -> put("readOnlyHint", true)
                voiceOpenURL -> {
                    put("readOnlyHint", false)
                    put("destructiveHint", false)
                    put("openWorldHint", true)
                }
                voicePlayAudio, voiceAudioControl, voiceOpenSession -> put("readOnlyHint", false)
            }
        }

    /** JSON Schema for the tool's arguments. */
    val inputSchema: JsonObject
        get() = buildJsonObject {
            put("type", "object")
            when (this@ClientTool) {
                voiceOpenSession -> {
                    putJsonObject("properties") {
                        putJsonObject("session_id") { put("type", "string") }
                        putJsonObject("tool_call_id") { put("type", "string") }
                    }
                    putJsonArray("required") { add(JsonPrimitive("session_id")) }
                }
                voiceRequestScreen -> putJsonObject("properties") {
                    putJsonObject("reason") {
                        put("type", "string")
                        put("description", "One short sentence the user sees on the card, saying why you want to see the screen.")
                    }
                }
                voiceOpenURL -> {
                    putJsonObject("properties") {
                        putJsonObject("url") {
                            put("type", "string")
                            put("description", "The link to open: https (an app link opens its app) or an app's own scheme.")
                        }
                        putJsonObject("title") {
                            put("type", "string")
                            put("description", "What it opens, in a word or two, shown on the card as “Open <title>?”, e.g. “Uber”.")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("url")) }
                }
                voicePlayAudio -> {
                    putJsonObject("properties") {
                        putJsonObject("url") {
                            put("type", "string")
                            put("description", "An https URL of the audio file.")
                        }
                        putJsonObject("title") {
                            put("type", "string")
                            put("description", "A short title the user sees on the player, e.g. the song's name.")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("url")) }
                }
                voiceAudioControl -> {
                    putJsonObject("properties") {
                        putJsonObject("action") {
                            put("type", "string")
                            putJsonArray("enum") {
                                add(JsonPrimitive("pause"))
                                add(JsonPrimitive("resume"))
                                add(JsonPrimitive("stop"))
                            }
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("action")) }
                }
            }
        }

    companion object {
        /**
         * The client MCP server's name, so agents see `android_voice_request_screen`. Distinct from
         * iOS's `ios` so both phones' tools coexist on one server.
         */
        const val SERVER_NAME = "android"

        fun fromRawValue(rawValue: String): ClientTool? {
            for (tool in entries) {
                if (tool.rawValue == rawValue) return tool
            }
            return null
        }
    }
}

/** A JSON-RPC request ID, number or string, echoed in the reply. */
sealed interface JsonRpcID {
    data class Number(val value: Long) : JsonRpcID
    data class Text(val value: String) : JsonRpcID

    val json: JsonPrimitive
        get() = when (this) {
            is Number -> JsonPrimitive(value)
            is Text -> JsonPrimitive(value)
        }

    companion object {
        fun of(element: JsonElement?): JsonRpcID? {
            if (element !is JsonPrimitive || element is JsonNull) return null
            if (element.isString) return Text(element.content)
            // Booleans aren't IDs.
            if (element.booleanOrNull != null) return null
            val number = element.longOrNull ?: element.content.toDoubleOrNull()?.toLong() ?: return null
            return Number(number)
        }
    }
}

/** A tool call from the server, answered on the socket it came in on. */
data class ClientToolCall(
    val connection: UUID,
    val id: JsonRpcID,
    val tool: ClientTool,
    /** The OpenCode session that called it. */
    val sessionID: String? = null,
    /** `voice_request_screen`'s `reason`, trimmed; null when empty. */
    val reason: String? = null,
    /** `voice_open_url`'s and `voice_play_audio`'s `url` and `title`, trimmed; null when empty. */
    val url: String? = null,
    val title: String? = null,
    /** `voice_audio_control`'s `action`. */
    val action: String? = null,
    /** Navigation destination; distinct from the caller's [sessionID] metadata. */
    val targetSessionID: String? = null,
    /** Original server tool-call ID, shared by MCP and the SSE fallback. */
    val toolCallID: String? = null,
) {
    companion object {
        /** The voice lab's stand-in socket: its replies count as sent (debug builds only use it). */
        val labConnection: UUID = UUID.fromString("00000000-0000-0000-0000-00000000ab1e")
    }
}

/** A tool's answer. */
data class ClientToolResult(val content: List<Content>, val isError: Boolean = false) {
    sealed interface Content {
        data class Text(val text: String) : Content

        class Jpeg(val data: ByteArray) : Content {
            override fun equals(other: Any?): Boolean = other is Jpeg && data.contentEquals(other.data)
            override fun hashCode(): Int = data.contentHashCode()
            override fun toString(): String = "Jpeg(${data.size} bytes)"
        }
    }

    companion object {
        fun failure(message: String): ClientToolResult = ClientToolResult(listOf(Content.Text(message)), isError = true)
    }
}

/** What one socket reports. */
sealed interface ClientToolsEvent {
    /** The server finished its handshake on [connection] and lists the tools. */
    data class Attached(val connection: UUID) : ClientToolsEvent

    data class Call(val call: ClientToolCall) : ClientToolsEvent

    /** The socket ended; [reason] carries no address or credentials. Its unanswered calls were refused on the way out. */
    data class Closed(val connection: UUID, val reason: String) : ClientToolsEvent

    /**
     * The handshake failed for good: the server has no such route
     * ([ClientMcpProtocol.PERMANENT_STATUSES]). Trying again won't help.
     */
    data class Unavailable(val connection: UUID, val status: Int) : ClientToolsEvent
}

/**
 * Hosts the phone's tools over OpenCode's client MCP socket (`/api/experimental/client-mcp`): the
 * server is the MCP client and the phone answers its JSON-RPC, one message per text frame.
 * [connect] runs one socket until it closes (its last event) or the flow is cancelled; reconnecting
 * is the reducer's (`ClientToolsFeature`).
 */
class ClientToolsClient(
    val connect: (ClientToolsTarget) -> Flow<ClientToolsEvent>,
    /**
     * A call's one reply: true when it went out, false when the call was already answered or its
     * socket is gone. It sends at once, so a cancelled caller still replies.
     */
    val reply: suspend (ClientToolCall, ClientToolResult) -> Boolean,
) {
    companion object Key : DependencyKey<ClientToolsClient> {
        override val liveValue: ClientToolsClient by lazy {
            val sockets = ClientMcpSockets()
            val transport = dependency(OpenCodeTransport)
            // The event stream's pool (no total deadline); OkHttp pings every 25 s and fails the
            // socket when a pong doesn't come.
            val client = transport.eventStream.newBuilder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .pingInterval(ClientMcpSocket.KEEP_ALIVE_SECONDS, TimeUnit.SECONDS)
                .build()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            ClientToolsClient(
                connect = { target ->
                    // To whichever of the server's addresses answers now (waiting for a race in
                    // flight, or running one when none has answered yet).
                    val link = dependency(ServerLinkClient)
                    flow {
                        val routed = ClientToolsTarget(link.route(target.server), target.directory, target.tools)
                        emitAll(ClientMcpSocket.connect(routed, client, sockets, scope))
                    }
                },
                reply = { call, result ->
                    if (call.connection == ClientToolCall.labConnection) true else sockets.reply(call, result)
                },
            )
        }

        override val testValue: ClientToolsClient
            get() = ClientToolsClient(connect = { emptyFlow() }, reply = { _, _ -> false })
    }
}

/**
 * The JSON-RPC the phone speaks on the socket, kept pure for tests: `initialize`, `ping` and
 * `tools/list` are answered here; `tools/call` goes to the reducer.
 */
object ClientMcpProtocol {
    const val PROTOCOL_VERSION = "2025-11-25"

    /** Handshake answers that mean the server has no client MCP route, not a passing failure. */
    val PERMANENT_STATUSES: Set<Int> = setOf(404, 405, 501)
    const val SESSION_ID_META_KEY = "ai.opencode/sessionID"

    sealed interface Step {
        data class Send(val text: String) : Step
        data object Attached : Step
        data class Call(val call: ClientToolCall) : Step
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun handle(text: String, connection: UUID, offered: Set<ClientTool> = ClientTool.entries.toSet()): Step? {
        val element = try { json.parseToJsonElement(text) } catch (_: Exception) { return null }
        val message = element as? JsonObject ?: return null
        val method = message.string("method") ?: return null
        val params = message["params"] as? JsonObject
        val id = JsonRpcID.of(message["id"])
        if (id == null) {
            return if (method == "notifications/initialized") Step.Attached else null
        }
        when (method) {
            "initialize" -> {
                val result = buildJsonObject {
                    put("protocolVersion", params?.string("protocolVersion") ?: PROTOCOL_VERSION)
                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                    putJsonObject("serverInfo") {
                        put("name", ClientTool.SERVER_NAME)
                        put("version", "1.0.0")
                    }
                }
                return Step.Send(encodeResult(id, result))
            }
            "ping" -> return Step.Send(encodeResult(id, JsonObject(emptyMap())))
            "tools/list" -> {
                val tools = buildJsonArray {
                    for (tool in ClientTool.entries) {
                        if (tool !in offered) continue
                        add(buildJsonObject {
                            put("name", tool.rawValue)
                            put("description", tool.description)
                            put("inputSchema", tool.inputSchema)
                            put("annotations", tool.annotations)
                        })
                    }
                }
                return Step.Send(encodeResult(id, buildJsonObject { put("tools", tools) }))
            }
            "tools/call" -> {
                val name = params?.string("name") ?: ""
                val tool = ClientTool.fromRawValue(name)
                if (tool == null || tool !in offered) {
                    return Step.Send(encodeError(id, -32602, "Unknown tool: $name"))
                }
                val meta = params?.get("_meta") as? JsonObject
                val arguments = params?.get("arguments") as? JsonObject
                return Step.Call(
                    ClientToolCall(
                        connection = connection,
                        id = id,
                        tool = tool,
                        sessionID = meta?.string(SESSION_ID_META_KEY),
                        reason = arguments.trimmedArgument("reason"),
                        url = arguments.trimmedArgument("url"),
                        title = arguments.trimmedArgument("title"),
                        action = arguments.trimmedArgument("action"),
                        targetSessionID = arguments.trimmedArgument("session_id"),
                        toolCallID = arguments.trimmedArgument("tool_call_id"),
                    ),
                )
            }
            else -> return Step.Send(encodeError(id, -32601, "Method not found: $method"))
        }
    }

    fun reply(id: JsonRpcID, result: ClientToolResult): String {
        val content = buildJsonArray {
            for (item in result.content) {
                when (item) {
                    is ClientToolResult.Content.Text -> add(buildJsonObject {
                        put("type", "text")
                        put("text", item.text)
                    })
                    is ClientToolResult.Content.Jpeg -> add(buildJsonObject {
                        put("type", "image")
                        put("data", Base64.getEncoder().encodeToString(item.data))
                        put("mimeType", "image/jpeg")
                    })
                }
            }
        }
        return encodeResult(id, buildJsonObject {
            put("content", content)
            put("isError", result.isError)
        })
    }

    /**
     * `ws(s)://…/api/experimental/client-mcp?location[directory]=…&name=android`, authorized like
     * every other request (Basic and the server's headers). OkHttp takes `http(s)` URLs for
     * web sockets and upgrades them to `ws(s)` itself.
     */
    fun request(target: ClientToolsTarget): Request? {
        val url = target.server.endpoint(
            "/api/experimental/client-mcp",
            listOf("location[directory]" to target.directory, "name" to ClientTool.SERVER_NAME),
        ) ?: return null
        return target.server.authorize(Request.Builder().url(url)).build()
    }

    private fun encodeResult(id: JsonRpcID, result: JsonElement): String =
        JsonObject(mapOf("jsonrpc" to JsonPrimitive("2.0"), "id" to id.json, "result" to result)).toString()

    private fun encodeError(id: JsonRpcID, code: Int, message: String): String {
        val error = buildJsonObject {
            put("code", code)
            put("message", message)
        }
        return JsonObject(mapOf("jsonrpc" to JsonPrimitive("2.0"), "id" to id.json, "error" to error)).toString()
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject?.trimmedArgument(key: String): String? {
        val value = this?.string(key)?.trim() ?: return null
        return value.ifEmpty { null }
    }
}

/**
 * The open sockets and each one's unanswered calls. Every `tools/call` gets exactly one reply: the
 * phone's, or, when its socket closes first, a refusal sent just before it goes. OkHttp's `send`
 * only enqueues.
 */
class ClientMcpSockets {
    private val lock = Any()
    private val senders = mutableMapOf<UUID, (String) -> Boolean>()
    private val unanswered = mutableMapOf<UUID, MutableSet<JsonRpcID>>()

    fun add(connection: UUID, send: (String) -> Boolean) {
        synchronized(lock) { senders[connection] = send }
    }

    fun received(call: ClientToolCall) {
        synchronized(lock) {
            if (senders[call.connection] == null) return
            unanswered.getOrPut(call.connection) { mutableSetOf() }.add(call.id)
        }
    }

    fun reply(call: ClientToolCall, result: ClientToolResult): Boolean {
        val send: (String) -> Boolean
        synchronized(lock) {
            val removed = unanswered[call.connection]?.remove(call.id) == true
            val sender = senders[call.connection]
            if (!removed || sender == null) {
                AppLog.network.info { "client tools: reply dropped, already answered or socket gone" }
                return false
            }
            send = sender
        }
        if (send(ClientMcpProtocol.reply(call.id, result))) return true
        AppLog.network.notice { "client tools: reply failed, socket closing" }
        return false
    }

    /** Refuses what's still unanswered, then forgets the socket: a reply after this finds nothing to answer. */
    fun close(connection: UUID, refusal: String) {
        val calls: Set<JsonRpcID>
        val send: (String) -> Boolean
        synchronized(lock) {
            calls = unanswered.remove(connection) ?: emptySet()
            send = senders.remove(connection) ?: return
        }
        if (calls.isNotEmpty()) AppLog.network.info { "client tools: refusing ${calls.size} unanswered on close" }
        for (id in calls) {
            if (!send(ClientMcpProtocol.reply(id, ClientToolResult.failure(refusal)))) {
                AppLog.network.notice { "client tools: refusal on close failed, socket closing" }
            }
        }
    }
}

/** One client MCP socket over OkHttp. */
internal object ClientMcpSocket {
    /** Keeps NAT and proxy paths open between the server's own pings. */
    const val KEEP_ALIVE_SECONDS = 25L

    /** A torn-down socket stays this long, so replies already on their way land before the generic refusal. */
    private val closeGrace = 250.milliseconds

    const val REFUSAL = "The OpenCode Android app disconnected before the user answered."

    private const val NORMAL_CLOSURE = 1000

    fun connect(target: ClientToolsTarget, client: OkHttpClient, sockets: ClientMcpSockets, scope: CoroutineScope): Flow<ClientToolsEvent> =
        callbackFlow {
            val id = UUID.randomUUID()
            val request = ClientMcpProtocol.request(target)
            if (request == null) {
                trySend(ClientToolsEvent.Closed(id, "invalid server address"))
                close()
                return@callbackFlow
            }
            val finished = AtomicBoolean(false)
            fun finish(reason: String, unavailable: Int? = null) {
                if (!finished.compareAndSet(false, true)) return
                sockets.close(id, REFUSAL)
                if (unavailable != null) trySend(ClientToolsEvent.Unavailable(id, unavailable))
                else trySend(ClientToolsEvent.Closed(id, reason))
                close()
            }
            val listener = object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    handle(webSocket, text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handle(webSocket, bytes.utf8())
                }

                private fun handle(webSocket: WebSocket, text: String) {
                    when (val step = ClientMcpProtocol.handle(text, id, target.tools)) {
                        is ClientMcpProtocol.Step.Send -> webSocket.send(step.text)
                        ClientMcpProtocol.Step.Attached -> trySend(ClientToolsEvent.Attached(id))
                        is ClientMcpProtocol.Step.Call -> {
                            sockets.received(step.call)
                            trySend(ClientToolsEvent.Call(step.call))
                        }
                        null -> {}
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(NORMAL_CLOSURE, null)
                    finish("close code $code")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    finish("close code $code")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val status = response?.code
                    if (status != null && status != 101) {
                        val unavailable = if (status in ClientMcpProtocol.PERMANENT_STATUSES) status else null
                        finish("HTTP $status", unavailable)
                    } else {
                        finish(logDescription(t))
                    }
                }
            }
            val socket = client.newWebSocket(request, listener)
            sockets.add(id) { text -> socket.send(text) }
            AppLog.network.info { "client tools: socket opening" }
            awaitClose {
                // Torn down (a new target, off, backgrounded), or closed: unanswered calls are
                // refused after a grace, then the socket goes.
                scope.launch {
                    delay(closeGrace)
                    sockets.close(id, REFUSAL)
                    socket.close(NORMAL_CLOSURE, null)
                }
            }
        }
}
