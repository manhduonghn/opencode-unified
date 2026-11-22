package ai.opencode.server

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class OpenCodeClientFamilyTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun overlapsActiveStatusAndBoundsThirtyChildRequests() = runTest {
        val children = (1..30).map { "child-$it" }
        val fixture = FamilyHTTPFixture(
            children = mapOf("root" to children),
            active = listOf("child-2", "unrelated"),
            held = (listOf("root", "active") + children).toSet(),
        )
        try {
            val load = async { OpenCodeClient.liveValue.sessionFamily(fixture.server, fixture.root) }
            try {
                // Neither request may finish until both have started.
                fixture.waitForStarts(listOf("root", "active"))
                fixture.release(listOf("root"))
                for (start in 0 until 30 step 6) {
                    val batch = children.subList(start, start + 6)
                    fixture.waitForStarts(batch)
                    fixture.release(batch)
                }
                fixture.release(listOf("active"))

                val family = load.await()
                assertEquals(listOf("root") + children, family.sessions.map { it.id })
                assertEquals(setOf("child-2", "unrelated"), family.activeSessionIDs)
                assertEquals(33, fixture.requests.size) // root + 30 leaves + active status + unrelated's parent
                assertEquals(6, fixture.peakChildRequests)
                assertEquals(7, fixture.peakRequests) // six child lookups plus active status
                assertEquals(0, fixture.outstandingRequests)
            } finally {
                load.cancel()
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun discoversAllDepthsInStableBreadthFirstOrderDespiteCyclesAndDuplicateIDs() = runTest {
        val fixture = FamilyHTTPFixture(
            children = mapOf(
                "root" to listOf("a", "b", "a"),
                "a" to listOf("root", "shared", "a-leaf"),
                "b" to listOf("shared", "b-leaf"),
                "shared" to listOf("a"),
                "b-leaf" to listOf("deep"),
            ),
            held = setOf("a", "b"),
        )
        try {
            val load = async { OpenCodeClient.liveValue.sessionFamily(fixture.server, fixture.root) }
            try {
                fixture.waitForStarts(listOf("a", "b"))
                fixture.release(listOf("b"))
                fixture.release(listOf("a"))
                val family = load.await()

                assertEquals(listOf("root", "a", "b", "shared", "a-leaf", "b-leaf", "deep"), family.sessions.map { it.id })
                assertEquals(fixture.root, family.sessions.first())
                val shared = family.sessions.first { it.id == "shared" }
                assertEquals("a/shared", shared.title) // first parent in traversal order wins
                assertEquals("a", shared.parentID)
                assertEquals(OpenCodeModelRef(id = "model", providerID = "provider"), shared.model)
                assertEquals("explore", shared.agent)
                assertEquals(Instant.ofEpochSecond(1), shared.updatedAt)
                val requests = fixture.requests
                assertEquals(8, requests.size)
                assertEquals(
                    setOf("root", "a", "b", "shared", "a-leaf", "b-leaf", "deep", "active"),
                    requests.map { FamilyHTTPFixture.key(it.url) }.toSet(),
                )
                for (request in requests.filter { FamilyHTTPFixture.key(it.url) != "active" }) {
                    assertEquals("GET", request.method)
                    assertEquals("desc", request.url.queryParameter("order"))
                    assertEquals("100", request.url.queryParameter("limit"))
                }
            } finally {
                load.cancel()
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun emptyFamilyRetainsRoot() = runTest {
        val fixture = FamilyHTTPFixture(children = emptyMap())
        try {
            val family = OpenCodeClient.liveValue.sessionFamily(fixture.server, fixture.root)
            assertEquals(listOf(fixture.root), family.sessions)
            assertTrue(family.activeSessionIDs.isEmpty())
            assertEquals(2, fixture.requests.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun retainsWholeResponseAtTraversalCapWithoutRequestingMoreChildren() = runTest {
        val children = (1..100).map { "child-$it" }
        val fixture = FamilyHTTPFixture(children = mapOf("root" to children))
        try {
            val family = OpenCodeClient.liveValue.sessionFamily(fixture.server, fixture.root)
            assertEquals(listOf("root") + children, family.sessions.map { it.id })
            assertEquals(2, fixture.requests.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun capStopsAtTheSameParentAndCancelsSpeculativeRequests() = runTest {
        val children = (1..98).map { "child-$it" }
        val fixture = FamilyHTTPFixture(
            children = mapOf("root" to children, "child-1" to listOf("grandchild-1", "grandchild-2")),
            held = children.take(6).toSet(),
            overrides = mapOf("child-2" to FamilyHTTPFixture.Response(status = 503, body = "")),
        )
        try {
            val load = async { OpenCodeClient.liveValue.sessionFamily(fixture.server, fixture.root) }
            try {
                fixture.waitForStarts(children.take(6))
                // This error arrives first but belongs beyond the serial traversal's cap.
                fixture.release(listOf("child-2"))
                fixture.release(listOf("child-1"))
                val family = load.await()

                assertEquals(listOf("root") + children + listOf("grandchild-1", "grandchild-2"), family.sessions.map { it.id })
                assertEquals(8, fixture.requests.size) // root + active + bounded speculation
                fixture.waitForIdle()
                assertEquals(0, fixture.outstandingRequests) // four held lookups were cancelled
            } finally {
                load.cancel()
            }
        } finally {
            fixture.close()
        }
    }

    /**
     * A root past 100 subagents, or a running grandchild under a parent the walk never
     * listed: the TUI counts them, so the family must include them.
     */
    @Test
    fun runningSessionsPastTheWalkJoinThroughTheirParents() = runTest {
        val children = (1..100).map { "child-$it" }
        val fixture = FamilyHTTPFixture(
            children = mapOf("root" to children),
            active = listOf("root", "late", "grandchild", "deep", "elsewhere"),
            overrides = mapOf(
                "/api/session/late" to FamilyHTTPFixture.session("late", parent = "root"),
                "/api/session/grandchild" to FamilyHTTPFixture.session("grandchild", parent = "child-7"),
                "/api/session/deep" to FamilyHTTPFixture.session("deep", parent = "mid"),
                "/api/session/mid" to FamilyHTTPFixture.session("mid", parent = "root"),
                "/api/session/elsewhere" to FamilyHTTPFixture.session("elsewhere", parent = "other-root"),
                "/api/session/other-root" to FamilyHTTPFixture.session("other-root", parent = null),
            ),
        )
        try {
            val family = OpenCodeClient.liveValue.sessionFamily(fixture.server, fixture.root)

            // Ancestors before descendants, so every member's parent is known.
            assertEquals(listOf("root") + children + listOf("grandchild", "late", "mid", "deep"), family.sessions.map { it.id })
            assertEquals(listOf("child-7", "root", "root", "mid"), family.sessions.takeLast(4).map { it.parentID })
            assertEquals(setOf("root", "late", "grandchild", "deep", "elsewhere"), family.activeSessionIDs)
            val lookups = { fixture.requests.map { FamilyHTTPFixture.key(it.url) }.filter { it.startsWith("/api/session/") } }
            assertEquals(listOf("deep", "elsewhere", "grandchild", "late", "mid", "other-root").map { "/api/session/$it" }, lookups().sorted())

            // Parents are remembered: another family's session costs nothing next time.
            val again = OpenCodeClient.liveValue.sessionFamily(fixture.server, fixture.root)
            assertEquals(family.sessions.map { it.id }.toSet(), again.sessions.map { it.id }.toSet())
            assertEquals(listOf("deep", "grandchild", "late", "mid").map { "/api/session/$it" }, lookups().drop(6).sorted())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun aFailedAncestryLookupKeepsTheWalksFamily() = runTest {
        val fixture = FamilyHTTPFixture(
            children = mapOf("root" to listOf("a")),
            active = listOf("late"),
            overrides = mapOf("/api/session/late" to FamilyHTTPFixture.Response(status = 503, body = "")),
        )
        try {
            val family = OpenCodeClient.liveValue.sessionFamily(fixture.server, fixture.root)
            assertEquals(listOf("root", "a"), family.sessions.map { it.id })
            assertEquals(setOf("late"), family.activeSessionIDs)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun propagatesHTTPFailuresFromEveryRequiredEndpoint() = runTest {
        for (endpoint in listOf("root", "a", "active")) {
            val fixture = FamilyHTTPFixture(
                children = mapOf("root" to listOf("a")),
                overrides = mapOf(endpoint to FamilyHTTPFixture.Response(status = 503, body = "")),
            )
            try {
                expectFailure("The server returned HTTP 503.") {
                    OpenCodeClient.liveValue.sessionFamily(fixture.server, fixture.root)
                }
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun malformedChildResponseFailsInsteadOfReturningAPartialFamily() = runTest {
        val fixture = FamilyHTTPFixture(
            children = mapOf("root" to listOf("a")),
            overrides = mapOf("a" to FamilyHTTPFixture.Response(body = """{"data":[{"id":"broken"}]}""")),
        )
        try {
            expectFailure("The server response does not match the current OpenCode V2 contract.") {
                OpenCodeClient.liveValue.sessionFamily(fixture.server, fixture.root)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun requiredFailureCancelsUnfinishedSiblingAndActiveRequests() = runTest {
        val fixture = FamilyHTTPFixture(
            children = mapOf("root" to listOf("a", "b")),
            held = setOf("a", "b", "active"),
            overrides = mapOf("a" to FamilyHTTPFixture.Response(status = 503, body = "")),
        )
        try {
            val load = async {
                try {
                    Result.success(OpenCodeClient.liveValue.sessionFamily(fixture.server, fixture.root))
                } catch (error: OpenCodeClientError) {
                    Result.failure(error)
                }
            }
            try {
                fixture.waitForStarts(listOf("a", "b", "active"))
                fixture.release(listOf("a"))
                expectFailure("The server returned HTTP 503.") { load.await().getOrThrow() }
                fixture.waitForIdle()
                assertEquals(0, fixture.outstandingRequests)
            } finally {
                load.cancel()
            }
        } finally {
            fixture.close()
        }
    }

    /** A cancelled Kotlin job throws `CancellationException`. */
    @Test
    fun callerCancellationCancelsHeldHTTPRequests() = runTest {
        val fixture = FamilyHTTPFixture(children = emptyMap(), held = setOf("root", "active"))
        try {
            val load = async { OpenCodeClient.liveValue.sessionFamily(fixture.server, fixture.root) }
            try {
                fixture.waitForStarts(listOf("root", "active"))
                load.cancel()
                try {
                    load.await()
                    fail("Expected the cancelled load to fail")
                } catch (_: CancellationException) {
                    // Expected.
                }
                fixture.waitForIdle()
                assertEquals(0, fixture.outstandingRequests)
            } finally {
                load.cancel()
            }
        } finally {
            fixture.close()
        }
    }

    private suspend fun expectFailure(message: String, operation: suspend () -> OpenCodeSessionFamily) {
        try {
            operation()
            fail("Expected the client to fail: $message")
        } catch (error: OpenCodeClientError) {
            assertEquals(message, error.message)
        }
    }
}

/**
 * Exercises the live client, including request construction and decoding. Every test has its
 * own host; this interceptor never answers other traffic. Held responses and request-start
 * signals provide ordering, not sleeps.
 */
private class FamilyHTTPFixture(
    children: Map<String, List<String>>,
    active: List<String> = emptyList(),
    held: Set<String> = emptySet(),
    overrides: Map<String, Response> = emptyMap(),
) : AutoCloseable {
    data class Response(val status: Int = 200, val body: String)

    private class Connection(val request: Request) {
        val done = CountDownLatch(1)
        @Volatile var response: Response? = null
    }

    val host = "family-${UUID.randomUUID().toString().lowercase()}.invalid"
    val server: OpenCodeServer get() = OpenCodeServer(url = "https://$host", username = "", password = "")
    val root = OpenCodeSession(
        id = "root", serverTitle = "Root", projectID = "project", directory = "/fixture", updatedAt = Instant.parse("0001-01-01T00:00:00Z"),
    )

    private val responses: Map<String, Response>
    private val lock = Any()
    private val held = held.toMutableSet()
    private val started = mutableListOf<Request>()
    private val running = LinkedHashSet<Connection>()
    private val waiters = HashMap<String, MutableList<CompletableDeferred<Unit>>>()
    private val idleWaiters = mutableListOf<CompletableDeferred<Unit>>()
    private var peakChildren = 0
    private var peakAll = 0

    init {
        val responses = HashMap<String, Response>()
        for (id in (listOf("root") + children.keys + children.values.flatten()).toSet()) {
            val sessions = buildJsonArray {
                for (child in children[id] ?: emptyList()) {
                    add(buildJsonObject {
                        put("id", child)
                        put("title", "$id/$child")
                        put("projectID", "project")
                        put("parentID", id)
                        putJsonObject("location") { put("directory", "/fixture") }
                        putJsonObject("time") { put("updated", 1_000) }
                        putJsonObject("model") { put("id", "model"); put("providerID", "provider") }
                        put("agent", "explore")
                    })
                }
            }
            responses[id] = Response(body = JsonObject(mapOf("data" to sessions)).toString())
        }
        responses["active"] = Response(
            body = JsonObject(mapOf("data" to JsonObject(active.associateWith { JsonObject(mapOf("type" to JsonPrimitive("running"))) }))).toString(),
        )
        responses.putAll(overrides)
        this.responses = responses
        TestNetworkBlock.registerMock(host, Interceptor { chain -> intercept(chain) })
    }

    val requests: List<Request> get() = synchronized(lock) { started.toList() }
    val peakChildRequests: Int get() = synchronized(lock) { peakChildren }
    val peakRequests: Int get() = synchronized(lock) { peakAll }
    val outstandingRequests: Int get() = synchronized(lock) { running.size }

    private fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val connection = Connection(chain.request())
        start(connection)
        while (!connection.done.await(5, TimeUnit.MILLISECONDS)) {
            if (chain.call().isCanceled()) {
                remove(connection)
                throw IOException("Canceled")
            }
        }
        val response = connection.response ?: throw IOException("Canceled")
        return okhttp3.Response.Builder()
            .request(connection.request)
            .protocol(Protocol.HTTP_1_1)
            .code(response.status)
            .message("")
            .header("Content-Type", "application/json")
            .body(response.body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private fun start(connection: Connection) {
        val startedKey = key(connection.request.url)
        val (ready, respond) = synchronized(lock) {
            started += connection.request
            running += connection
            peakAll = maxOf(peakAll, running.size)
            peakChildren = maxOf(peakChildren, running.count { key(it.request.url) != "active" })
            (waiters.remove(startedKey) ?: mutableListOf()) to (startedKey !in held)
        }
        for (waiter in ready) waiter.complete(Unit)
        if (respond) finish(connection)
    }

    private fun finish(connection: Connection) {
        if (!remove(connection)) return
        connection.response = responses[key(connection.request.url)] ?: Response(status = 404, body = "")
        connection.done.countDown()
    }

    private fun remove(connection: Connection): Boolean {
        val (removed, idle) = synchronized(lock) {
            val removed = running.remove(connection)
            val idle = if (running.isEmpty()) idleWaiters.toList().also { idleWaiters.clear() } else emptyList()
            removed to idle
        }
        for (waiter in idle) waiter.complete(Unit)
        return removed
    }

    fun release(keys: List<String>) {
        val connections = synchronized(lock) {
            held.removeAll(keys.toSet())
            running.filter { key(it.request.url) in keys }
        }
        for (connection in connections) finish(connection)
    }

    /** Waits (in real time: requests run on OkHttp's threads) until every key's request has started. */
    suspend fun waitForStarts(keys: List<String>) {
        val pending = synchronized(lock) {
            keys.mapNotNull { wanted ->
                if (started.any { key(it.url) == wanted }) return@mapNotNull null
                CompletableDeferred<Unit>().also { waiters.getOrPut(wanted) { mutableListOf() } += it }
            }
        }
        if (pending.isEmpty()) return
        // Timeout is a deadlock guard, not a performance assertion.
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { pending.awaitAll() } }
    }

    suspend fun waitForIdle() {
        val waiter = synchronized(lock) {
            if (running.isEmpty()) return
            CompletableDeferred<Unit>().also { idleWaiters += it }
        }
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { waiter.await() } }
    }

    override fun close() {
        val connections = synchronized(lock) { running.toList().also { running.clear() } }
        for (connection in connections) connection.done.countDown()
        TestNetworkBlock.unregisterMock(host)
    }

    companion object {
        /** `GET /api/session/{id}`. */
        fun session(id: String, parent: String?): Response {
            val session = buildJsonObject {
                put("id", id)
                put("title", id)
                put("projectID", "project")
                putJsonObject("location") { put("directory", "/fixture") }
                putJsonObject("time") { put("updated", 1_000) }
                parent?.let { put("parentID", it) }
            }
            return Response(body = JsonObject(mapOf("data" to session)).toString())
        }

        fun key(url: HttpUrl): String {
            if (url.encodedPath == "/api/session/active") return "active"
            if (url.encodedPath != "/api/session") return url.encodedPath
            return url.queryParameter("parentID") ?: "missing-parent"
        }
    }
}
