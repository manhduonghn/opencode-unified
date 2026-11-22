package ai.opencode.feature.drawer.icons

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeTransport
import ai.opencode.server.model.ProjectMark
import ai.opencode.server.retry.NetworkPathMonitor
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RequestIdempotency
import ai.opencode.server.retry.ServerContact
import ai.opencode.server.retry.TransportFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** An entry that expires; kept on disk until [retentionDate]. */
interface ExpiringCacheEntry {
    val expires: Instant
    val retentionDate: Instant get() = expires
}

/**
 * Memory and SHA-256-named JSON disk cache. Thread-safe; disk I/O runs on the caller's thread, so
 * call it off the main thread.
 */
class CoalescedDiskCache<M : ExpiringCacheEntry, D : ExpiringCacheEntry>(
    val directory: File?,
    val now: () -> Instant = Instant::now,
    private val encode: (D) -> String,
    private val decode: (String) -> D?,
) {
    private val lock = Any()
    private val memory = HashMap<String, M>()

    fun memory(key: String): M? = synchronized(lock) { memory[key] }

    fun putMemory(key: String, entry: M) = synchronized(lock) { memory[key] = entry }

    fun freshMemory(key: String): M? {
        val current = now()
        return memory(key)?.takeIf { it.expires > current }
    }

    fun store(entry: M, record: D, key: String, memoryLimit: Int? = null) {
        synchronized(lock) {
            memory[key] = entry
            if (memoryLimit != null && memory.size > memoryLimit) {
                memory.entries.sortedBy { it.value.expires }.take(memory.size - memoryLimit).map { it.key }
                    .forEach { memory.remove(it) }
            }
        }
        writeDisk(record, key)
    }

    fun file(key: String): File? {
        val directory = directory ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return File(directory, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    fun readDisk(key: String): D? {
        val file = file(key) ?: return null
        return try { decode(file.readText(Charsets.UTF_8)) } catch (_: Exception) { null }
    }

    fun writeDisk(record: D, key: String) {
        val directory = directory ?: return
        val file = file(key) ?: return
        try {
            directory.mkdirs()
            val temporary = File(directory, file.name + ".tmp")
            temporary.writeText(encode(record), Charsets.UTF_8)
            if (!temporary.renameTo(file)) temporary.delete()
        } catch (_: Exception) {
        }
    }

    /** Drops expired files, then the oldest written beyond [maxBytes]. */
    fun sweep(maxBytes: Long? = null) {
        val directory = directory ?: return
        val files = directory.listFiles()?.filter { it.extension == "json" } ?: return
        val current = now()
        for (file in files) {
            val record = try { decode(file.readText(Charsets.UTF_8)) } catch (_: Exception) { null }
            if ((record?.retentionDate ?: Instant.MIN) <= current) file.delete()
        }
        maxBytes ?: return
        var size = 0L
        for (file in files.filter { it.exists() }.sortedByDescending { it.lastModified() }) {
            size += file.length()
            if (size > maxBytes) file.delete()
        }
    }
}

/**
 * Memory and SHA-256 disk cache for project icons, coalescing in-flight fetches per icon key and
 * decoding off the main thread. Reached only through [ProjectIconClient].
 */
class ProjectIconLoader(
    private val fetch: Fetch,
    directory: File?,
    now: () -> Instant = Instant::now,
    private val isConstrained: () -> Boolean = { false },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /** One HTTP exchange: the body, status and headers. */
    class FetchResponse(val data: ByteArray, val statusCode: Int, val headers: Headers)

    fun interface Fetch {
        suspend fun fetch(request: Request, server: OpenCodeServer, isServerRoute: Boolean): FetchResponse
    }

    @Serializable
    data class Record(
        /** The PNG, base64. */
        val png: String? = null,
        val backing: String? = null,
        val etag: String? = null,
        val revalidates: Boolean = false,
        /** Epoch milliseconds. */
        val expiresAt: Long,
    ) : ExpiringCacheEntry {
        override val expires: Instant get() = Instant.ofEpochMilli(expiresAt)

        companion object {
            fun of(icon: ProjectIcon?, etag: String? = null, revalidates: Boolean = false, expires: Instant) = Record(
                png = icon?.png?.let { Base64.getEncoder().encodeToString(it) },
                backing = icon?.backing?.rawValue,
                etag = etag,
                revalidates = revalidates,
                expiresAt = expires.toEpochMilli(),
            )
        }
    }

    class Entry(
        val icon: ProjectIcon?,
        val etag: String? = null,
        val revalidates: Boolean = false,
        override val expires: Instant,
    ) : ExpiringCacheEntry {
        val result: ProjectIconResult get() = icon?.let(ProjectIconResult::Icon) ?: ProjectIconResult.Missing
    }

    private val cache = CoalescedDiskCache<Entry, Record>(
        directory = directory,
        now = now,
        encode = { RecordJson.encodeToString(Record.serializer(), it) },
        decode = { RecordJson.decodeFromString(Record.serializer(), it) },
    )
    private val inFlight = HashMap<String, Deferred<ProjectIconResult>>()

    fun cached(request: ProjectIconRequest): ProjectIconResult? {
        val entry = cache.freshMemory(request.cacheKey) ?: return null
        if (entry.revalidates) return null
        return entry.result
    }

    /**
     * The icon, from memory, disk or the network. Fetches coalesce per key, and a waiter cancelling
     * never cancels the shared fetch.
     */
    suspend fun load(request: ProjectIconRequest): ProjectIconResult {
        val key = request.cacheKey
        cached(request)?.let { return it }
        val task = synchronized(inFlight) {
            inFlight[key] ?: scope.async(start = CoroutineStart.LAZY) { resolve(request, key) }.also { task ->
                inFlight[key] = task
                task.invokeOnCompletion { synchronized(inFlight) { if (inFlight[key] === task) inFlight.remove(key) } }
                task.start()
            }
        }
        return task.await()
    }

    private suspend fun resolve(request: ProjectIconRequest, key: String): ProjectIconResult {
        var priorIcon: ProjectIcon? = null
        var priorETag: String? = null
        val mem = cache.memory(key)
        if (mem != null) {
            if (!mem.revalidates && mem.expires > cache.now()) return mem.result
            priorIcon = mem.icon
            priorETag = mem.etag
        } else {
            val record = cache.readDisk(key)
            if (record != null && record.expires > cache.now()) {
                val decoded = decodeDiskRecord(record)
                if (!record.revalidates) {
                    val entry = Entry(icon = decoded, etag = record.etag, revalidates = false, expires = record.expires)
                    cache.putMemory(key, entry)
                    return entry.result
                }
                priorIcon = decoded
                priorETag = record.etag
            }
        }
        return resolveSource(request.source, request.isVersioned, request.server, key, priorIcon, priorETag)
    }

    private suspend fun resolveSource(
        rawSource: String,
        isVersioned: Boolean,
        server: OpenCodeServer,
        key: String,
        priorIcon: ProjectIcon?,
        priorETag: String?,
    ): ProjectIconResult {
        val source = rawSource.trim()
        if (source == ProjectMark.OPEN_CODE_FAVICON_URL) {
            val icon = decodeBytes(ProjectMark.openCodeFaviconSVG)
            val entry = Entry(icon = icon, expires = cache.now() + immutableLifetime)
            cache.store(entry, Record.of(icon, expires = entry.expires), key, memoryLimit = MEMORY_LIMIT)
            return entry.result
        }
        if (source.lowercase().startsWith("data:image/")) {
            val icon = decodeDataURI(source)
            val lifetime = if (icon == null) missingLifetime else immutableLifetime
            val entry = Entry(icon = icon, expires = cache.now() + lifetime)
            cache.store(entry, Record.of(icon, expires = entry.expires), key, memoryLimit = MEMORY_LIMIT)
            return entry.result
        }

        val built = buildURLRequest(source, server, ifNoneMatch = if (isVersioned) null else priorETag)
            ?: return ProjectIconResult.Missing
        if (isConstrained()) return priorIcon?.let(ProjectIconResult::Icon) ?: ProjectIconResult.Deferred

        val response = try {
            fetch.fetch(built.first, server, built.second)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return priorIcon?.let(ProjectIconResult::Icon) ?: ProjectIconResult.Deferred
        }

        val statusCode = response.statusCode
        val responseETag = response.headers["ETag"] ?: priorETag
        val cacheControl = response.headers["Cache-Control"]?.lowercase() ?: ""
        val isImmutable = isVersioned || cacheControl.contains("immutable")
        val revalidates = !isImmutable && cacheControl.contains("no-cache")

        if (statusCode == 304 && priorIcon != null) {
            val entry = Entry(
                icon = priorIcon,
                etag = responseETag,
                revalidates = revalidates,
                expires = cache.now() + if (isImmutable) immutableLifetime else iconLifetime,
            )
            cache.store(entry, Record.of(priorIcon, responseETag, revalidates, entry.expires), key, memoryLimit = MEMORY_LIMIT)
            return ProjectIconResult.Icon(priorIcon)
        }

        val icon: ProjectIcon? = when (statusCode) {
            in 200 until 300 -> decodeBytes(response.data)
            400, 403, 404, 410, 422 -> null
            else -> return priorIcon?.let(ProjectIconResult::Icon) ?: ProjectIconResult.Deferred
        }

        val lifetime = when {
            icon == null -> missingLifetime
            isImmutable -> immutableLifetime
            else -> iconLifetime
        }
        val entry = Entry(
            icon = icon,
            etag = if (icon == null) null else responseETag,
            revalidates = icon != null && revalidates,
            expires = cache.now() + lifetime,
        )
        cache.store(entry, Record.of(icon, entry.etag, entry.revalidates, entry.expires), key, memoryLimit = MEMORY_LIMIT)
        return entry.result
    }

    fun sweep() {
        cache.sweep(maxBytes = 8L * 1024 * 1024)
    }

    companion object {
        val immutableLifetime: Duration = Duration.ofSeconds(365L * 24 * 3600)
        val iconLifetime: Duration = Duration.ofSeconds(14L * 24 * 3600)
        val missingLifetime: Duration = Duration.ofSeconds(12L * 3600)
        private const val MEMORY_LIMIT = 128

        /** A 2 MB streaming cap for an image response. */
        const val maxResponseBytes = 2 shl 20

        private val RecordJson = Json { ignoreUnknownKeys = true }

        private suspend fun decodeDiskRecord(record: Record): ProjectIcon? = withContext(Dispatchers.Default) {
            val png = record.png?.let { try { Base64.getDecoder().decode(it) } catch (_: IllegalArgumentException) { null } }
                ?: return@withContext null
            ProjectIcon.decode(png, precomputedBacking = ProjectIcon.Backing.of(record.backing) ?: ProjectIcon.Backing.FullBleed)
        }

        private suspend fun decodeDataURI(dataURL: String): ProjectIcon? =
            withContext(Dispatchers.Default) { ProjectIcon.fromDataURL(dataURL) }

        private suspend fun decodeBytes(data: ByteArray): ProjectIcon? =
            withContext(Dispatchers.Default) { ProjectIcon.decode(data) }

        /**
         * The GET for [source]: a server-relative path on [server] (authorized), or an absolute
         * HTTP(S) URL (authorized only when it's the server's own origin). The second value is
         * whether it's a server route. Null for anything else.
         */
        fun buildURLRequest(source: String, server: OpenCodeServer, ifNoneMatch: String? = null): Pair<Request, Boolean>? {
            val trimmed = source.trim()
            if (trimmed.isEmpty()) return null

            val resolvedURL: okhttp3.HttpUrl
            val isServerRoute: Boolean
            if (trimmed.startsWith("/")) {
                val relative = ProjectIconURLs.relativeReference(trimmed) ?: return null
                resolvedURL = server.endpoint(relative.path, relative.query) ?: return null
                isServerRoute = true
            } else {
                val url = trimmed.toHttpUrlOrNull() ?: return null
                val scheme = url.scheme.lowercase()
                if ((scheme != "http" && scheme != "https") || url.host.isEmpty()) return null
                resolvedURL = url
                isServerRoute = server.matchesOrigin(url)
            }

            val builder = Request.Builder()
                .url(resolvedURL)
                .header("Accept", "image/svg+xml,image/*;q=0.9,*/*;q=0.5")
            if (!ifNoneMatch.isNullOrEmpty()) builder.header("If-None-Match", ifNoneMatch)
            if (isServerRoute) server.authorize(builder)
            return builder.build() to isServerRoute
        }

        /**
         * The live fetch: one retry layer ([NetworkRetry], idempotent), no redirects (so auth
         * never follows one), a 10 s timeout, no cookies, and a 2 MB streaming cap. 408, 429
         * and 5xx are retried as "Couldn’t load project icon".
         */
        fun liveFetch(client: OkHttpClient): Fetch = Fetch { request, server, isServerRoute ->
            val requestURL = request.url
            val contact = server.persistenceID
            NetworkRetry.run(
                idempotency = RequestIdempotency.Idempotent,
                label = "GET project icon",
                reachable = if (isServerRoute) ({ ServerContact.shared.next(contact) }) else null,
            ) {
                val response = try {
                    client.newCall(request).awaitCapped(maxResponseBytes)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: ResponseTooLarge) {
                    throw OpenCodeClientError(OpenCodeClientError.UNREACHABLE_MESSAGE)
                } catch (error: IOException) {
                    val failure = if (isServerRoute) {
                        TransportFailure.of(error, requestURL, ServerContact.shared)
                    } else {
                        TransportFailure.of(error)
                    }
                    throw failure?.let(OpenCodeClientError::unreachable)
                        ?: OpenCodeClientError(OpenCodeClientError.UNREACHABLE_MESSAGE)
                }
                if (isServerRoute) ServerContact.shared.heard(contact, requestURL)
                val status = response.statusCode
                if (status == 408 || status == 429 || status in 500..599) {
                    throw OpenCodeClientError(
                        message = "Couldn’t load project icon",
                        statusCode = status,
                        retryAfter = OpenCodeClientError.retryAfter(response.headers["Retry-After"]),
                    )
                }
                response
            }
        }

        /** The process-wide loader behind [ProjectIconClient.liveValue]. */
        internal val shared: ProjectIconLoader by lazy {
            // The installed root's context: AppContext's own liveValue only throws.
            val context = dependency(AppContext)
            val transport = dependency(OpenCodeTransport)
            val client = transport.request.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            ProjectIconLoader(
                fetch = liveFetch(client),
                directory = File(context.cacheDir, "project-icons"),
                isConstrained = { NetworkPathMonitor.shared?.isConstrained == true },
            ).also { loader -> loader.scope.launch(Dispatchers.IO) { loader.sweep() } }
        }
    }
}

private class ResponseTooLarge : IOException("Project icon exceeds the size limit") {
    override fun fillInStackTrace(): Throwable = this
}

/** Runs the call, streaming the body up to [limit] bytes on OkHttp's thread; cancelling cancels the call. */
private suspend fun Call.awaitCapped(limit: Int): ProjectIconLoader.FetchResponse = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use {
                    val body = it.body
                    if (body.contentLength() > limit) throw ResponseTooLarge()
                    val source = body.source()
                    val buffer = okio.Buffer()
                    while (true) {
                        val read = source.read(buffer, 8192)
                        if (read == -1L) break
                        if (buffer.size > limit) throw ResponseTooLarge()
                    }
                    ProjectIconLoader.FetchResponse(buffer.readByteArray(), it.code, it.headers)
                }
            } catch (e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
                return
            }
            continuation.resume(result)
        }
    })
}

/** Loads and caches project icons. */
class ProjectIconClient(
    /** A fresh in-memory result, without I/O: safe on the main thread. */
    val cached: (ProjectIconRequest) -> ProjectIconResult? = { null },
    val load: suspend (ProjectIconRequest) -> ProjectIconResult = { ProjectIconResult.Missing },
) {
    constructor(loader: ProjectIconLoader) : this(cached = loader::cached, load = { loader.load(it) })

    /**
     * The request's icon to observe: the cached one at once, else null and then whatever
     * loading brings (null for missing or deferred). Cold; collect it with `collectAsState`.
     */
    fun observe(request: ProjectIconRequest): Flow<ProjectIcon?> = flow {
        val cached = cached(request)
        if (cached != null) {
            emit(cached.icon)
            return@flow
        }
        emit(load(request).icon)
    }

    companion object Key : DependencyKey<ProjectIconClient> {
        override val liveValue: ProjectIconClient by lazy { ProjectIconClient(ProjectIconLoader.shared) }
        override val testValue: ProjectIconClient = ProjectIconClient()
    }
}
