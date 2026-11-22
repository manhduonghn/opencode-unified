package ai.opencode.feature.media

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.strings.localized
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeTransport
import ai.opencode.server.model.OpenCodeMediaReference
import ai.opencode.server.model.AgentFileReference
import ai.opencode.core.files.AttachmentFileType
import ai.opencode.server.retry.ServerContact
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun OpenCodeServer.fileReadRequest(target: AgentFileReference.Target): Request? =
    fileReadRequest(location = target.location, relativePath = target.relativePath)

/** Why a file the agent named couldn't open. */
sealed class AgentFileError : Exception() {
    data object Unreadable : AgentFileError()
    data object NotFound : AgentFileError()
    data object Unauthorized : AgentFileError()
    data class Server(val status: Int) : AgentFileError()
    data class Network(val description: String) : AgentFileError()

    val title: String
        get() = when (this) {
            NotFound -> localized(R.string.media_file_error_not_found_title)
            else -> localized(R.string.media_file_error_open_failed_title)
        }

    val detail: String
        get() = when (this) {
            Unreadable -> localized(R.string.media_file_error_unreadable)
            NotFound -> localized(R.string.media_file_error_not_found)
            Unauthorized -> localized(R.string.media_file_error_unauthorized)
            is Server -> localized(R.string.media_file_error_server, status)
            is Network -> description
        }

    companion object {
        fun from(status: Int): AgentFileError? = when (status) {
            in 200 until 300, 304 -> null
            404 -> NotFound
            401, 403 -> Unauthorized
            else -> Server(status)
        }

        /**
         * A transport failure in words the viewer shows. Never the address: Android exception
         * messages carry hosts and paths.
         */
        fun network(error: Throwable): AgentFileError = Network(
            when (error) {
                is SocketTimeoutException -> localized(R.string.media_file_error_timed_out)
                else -> OpenCodeClientError.UNREACHABLE_MESSAGE
            },
        )

        internal val unexpectedResponse: Network get() = Network(localized(R.string.media_file_error_unexpected_response))
    }
}

/** What the server says about a file before the phone commits to it. */
data class AgentFileProbe(
    val size: Long?,
    val mime: String?,
    /** The server answered a byte range with 206, so media can stream. */
    val supportsRanges: Boolean,
    val entityTag: String? = null,
    val lastModified: String? = null,
) {
    companion object {
        /**
         * Reads the response to `Range: bytes=0-1`. A server without range support answers 200
         * with the whole file (the caller cancels it).
         */
        fun of(response: Response): AgentFileProbe {
            val contentRange = response.header("Content-Range")
            val supportsRanges = response.code == 206
            val size = when {
                supportsRanges -> contentRange?.let(::totalLength)
                response.code == 416 -> contentRange?.let(::totalLength) ?: 0L
                else -> response.body.contentLength().takeIf { it >= 0 } ?: response.header("Content-Length")?.trim()?.toLongOrNull()
            }
            // The media type without parameters.
            val mime = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()?.ifEmpty { null }
            return AgentFileProbe(size, mime, supportsRanges, response.header("ETag"), response.header("Last-Modified"))
        }

        // `bytes 0-1/48213` → 48213; `bytes */48213` → 48213; `*` totals are unknown.
        fun totalLength(contentRange: String): Long? {
            val slash = contentRange.lastIndexOf('/')
            if (slash < 0) return null
            return contentRange.substring(slash + 1).trim(' ', '\t').toLongOrNull()
        }
    }
}

/**
 * Downloads into the cache with progress, revalidating with the server's validators when it sends
 * them. One read per call; the viewer owns trying again.
 */
object AgentFileLoader {
    /** Above this, a file that can't stream asks before downloading. */
    const val confirmationBytes: Long = 100L * 1024 * 1024

    /** Text above this skips per-line highlighting in the code viewer. */
    const val maxHighlightedBytes = 2 * 1024 * 1024

    /** Text above this opens as a document rather than the lazy text viewer. */
    const val maxTextBytes = 16 * 1024 * 1024

    internal data class Original(val request: Request, val cacheKey: String)

    internal fun original(server: OpenCodeServer, reference: AgentFileReference, directory: String?): Original? {
        reference.media?.let { media ->
            val request = MediaReferenceResolver.originalRequest(server, media.hash) ?: return null
            return Original(request, OpenCodeMediaReference.uri(media.hash))
        }
        val target = reference.target(directory) ?: return null
        return Original(server.fileReadRequest(target) ?: return null, target.absolutePath)
    }

    internal val overLimit get() = AgentFileError.Network(localized(R.string.media_file_error_over_limit))

    /** Adds the server's credentials to a request for one of its origins. */
    fun authorizedRequest(request: Request, server: OpenCodeServer?): Request {
        if (server == null || !server.matchesOrigin(request.url)) return request
        return server.authorize(request.newBuilder()).build()
    }

    private fun noteContact(contact: ServerContact, server: OpenCodeServer?, request: Request, response: Response) {
        if (server == null || !server.matchesOrigin(request.url)) return
        contact.heard(server.persistenceID, response.request.url)
    }

    /**
     * The transport's pool with a 60 s idle interval. Sent once: the viewer's Try Again is the
     * retry.
     */
    internal fun client(transport: OpenCodeTransport = dependency(OpenCodeTransport)): OkHttpClient =
        transport.request.newBuilder()
            // A redirected original could forward custom server credentials to another host.
            .followRedirects(false)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    suspend fun probe(
        request: Request,
        server: OpenCodeServer? = null,
        contact: ServerContact = ServerContact.shared,
        client: OkHttpClient = client(),
    ): AgentFileProbe {
        val ranged = authorizedRequest(request, server).newBuilder().header("Range", "bytes=0-1").build()
        try {
            // Headers are all the probe needs; an old server is already sending the whole file.
            // Closed off the main thread: closing an unread body drains the connection.
            return withContext(Dispatchers.IO) {
                client.newCall(ranged).await().use { response ->
                    if (response.code != 416) AgentFileError.from(response.code)?.let { throw it }
                    noteContact(contact, server, ranged, response)
                    AgentFileProbe.of(response)
                }
            }
        } catch (error: AgentFileError) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            coroutineContext.ensureActive()
            throw AgentFileError.network(error)
        }
    }

    @Serializable
    private data class Validators(val entityTag: String? = null, val lastModified: String? = null)

    /** The validators stored with a downloaded copy, if any. */
    fun storedValidators(destination: File): AgentImageValidators? =
        readValidators(File(destination.path + ".validators"))?.let { AgentImageValidators(it.entityTag, it.lastModified) }

    private fun readValidators(sidecar: File): Validators? = runCatching {
        Json.decodeFromString(Validators.serializer(), sidecar.readText())
    }.getOrNull()

    /** `<cache>/AgentFiles/<hash of server + path>/<name>`. */
    fun cacheURL(server: OpenCodeServer, absolutePath: String, name: String, cacheDirectory: File = dependency(AppContext).cacheDir): File =
        cacheURL(server.persistenceID, absolutePath, name, cacheDirectory)

    fun cacheURL(serverID: String, absolutePath: String, name: String, cacheDirectory: File): File {
        val digest = MessageDigest.getInstance("SHA-256").digest("$serverID|$absolutePath".toByteArray())
        val key = digest.take(12).joinToString("") { "%02x".format(it) }
        return File(File(File(cacheDirectory, "AgentFiles"), key), AttachmentFileType.sanitizedName(name))
    }

    /**
     * Writes bytes another reader already downloaded (a preview) to [destination] with their
     * validators, as [download] would have, so the viewer's next read is a 304 or none at all.
     */
    fun store(destination: File, data: ByteArray, entityTag: String?, lastModified: String?) {
        val sidecar = File(destination.path + ".validators")
        val temporary = File(destination.parentFile ?: return, "agent-file-${UUID.randomUUID().toString().uppercase()}")
        try {
            destination.parentFile?.mkdirs()
            temporary.writeBytes(data)
            sidecar.delete()
            destination.delete()
            if (!temporary.renameTo(destination)) temporary.copyTo(destination, overwrite = true)
            if (entityTag != null || lastModified != null) {
                sidecar.writeText(Json.encodeToString(Validators.serializer(), Validators(entityTag, lastModified)))
            }
        } finally {
            temporary.delete()
        }
    }

    /**
     * Downloads [request] to [destination], reporting `(received, expected)`. A cached copy
     * with validators is reused when the server answers 304.
     */
    suspend fun download(
        request: Request,
        destination: File,
        server: OpenCodeServer? = null,
        contact: ServerContact = ServerContact.shared,
        client: OkHttpClient = client(),
        temporaryDirectory: File = destination.parentFile ?: File("."),
        maxBytes: Long = Long.MAX_VALUE,
        /** The answer's HTTP status: 304 when the cached copy was kept. */
        onStatus: ((Int) -> Unit)? = null,
        progress: (received: Long, expected: Long?) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val sidecar = File(destination.path + ".validators")
        val builder = authorizedRequest(request, server).newBuilder()
        if (destination.exists()) {
            readValidators(sidecar)?.let { validators ->
                validators.entityTag?.let { builder.header("If-None-Match", it) }
                validators.lastModified?.let { builder.header("If-Modified-Since", it) }
            }
        }
        val authorized = builder.build()
        val temporary = File(temporaryDirectory, "agent-file-${UUID.randomUUID().toString().uppercase()}")
        try {
            val response = try {
                client.newCall(authorized).await()
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                ensureActive()
                throw AgentFileError.network(error)
            }
            response.use {
                if (response.code == 304) {
                    if (!destination.isFile || (authorized.header("If-None-Match") == null && authorized.header("If-Modified-Since") == null)) {
                        throw AgentFileError.unexpectedResponse
                    }
                    if (destination.length() > maxBytes) throw overLimit
                    noteContact(contact, server, authorized, response)
                    onStatus?.invoke(304)
                    return@withContext destination
                }
                AgentFileError.from(response.code)?.let { throw it }
                try {
                    if (response.code == 206) throw AgentFileError.unexpectedResponse
                    transfer(response, temporary, maxBytes, progress)
                    onStatus?.invoke(response.code)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: IOException) {
                    ensureActive()
                    throw AgentFileError.network(error)
                }
                noteContact(contact, server, authorized, response)
                destination.parentFile?.mkdirs()
                sidecar.delete() // Never associate stale validators with the replacement.
                destination.delete()
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                }
                val probe = AgentFileProbe.of(response)
                if (probe.entityTag != null || probe.lastModified != null) {
                    runCatching {
                        sidecar.writeText(Json.encodeToString(Validators.serializer(), Validators(probe.entityTag, probe.lastModified)))
                    }
                }
                destination
            }
        } finally {
            // Moved into the cache on success; removed on every other path.
            temporary.delete()
        }
    }

    /** Writes the body, reporting progress at most every 0.5 % (at least every 128 KB) and at the end. */
    private suspend fun transfer(response: Response, file: File, maxBytes: Long, progress: (Long, Long?) -> Unit) {
        val body = response.body
        val expected = body.contentLength().takeIf { it > 0 }
        if (expected != null && expected > maxBytes) throw overLimit
        val step = maxOf((expected ?: 0) / 200, 128L * 1024)
        var written = 0L
        var lastReported = -1L
        file.parentFile?.mkdirs()
        body.byteStream().use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > maxBytes - written) throw overLimit
                    output.write(buffer, 0, read)
                    written += read
                    if (written == expected || written - lastReported >= step) {
                        lastReported = written
                        progress(written, expected)
                    }
                }
            }
        }
    }

    /** UTF-8 text for the viewer, or null for binary or oversized files. */
    fun readText(file: File): String? {
        val size = file.length()
        if (!file.isFile || size > maxTextBytes) return null
        val data = runCatching { file.readBytes() }.getOrNull() ?: return null
        val sniff = minOf(data.size, 8192)
        for (i in 0 until sniff) if (data[i] == 0.toByte()) return null
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data))
                .toString()
        } catch (_: CharacterCodingException) {
            String(data, Charsets.ISO_8859_1)
        }
    }
}

/** Enqueues the call, cancelling it with the coroutine; the response body is the caller's to close. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}

/**
 * Streams a file to ExoPlayer through authenticated range requests. Each byte range ExoPlayer asks
 * for is one request with the server's credentials; only 206 answers stream, anything else fails
 * the load.
 */
@OptIn(UnstableApi::class)
class StreamingFileLoader(
    request: Request,
    server: OpenCodeServer? = null,
    mime: String?,
    fileName: String,
    private val client: OkHttpClient = AgentFileLoader.client(),
    private val contact: ServerContact = ServerContact.shared,
) {
    private val request: Request = AgentFileLoader.authorizedRequest(request, server)
    private val serverPersistenceID: String? = server?.takeIf { it.matchesOrigin(request.url) }?.persistenceID
    private val contentType: String? = mime ?: AgentFileReference.mimeForExtension(AttachmentFileType.fileExtension(fileName))
    private val lock = Any()
    private val calls = HashSet<Call>()
    private var invalidated = false

    /** What ExoPlayer plays; its data comes from [dataSourceFactory], never the URL itself. */
    fun makeMediaItem(): MediaItem = MediaItem.Builder()
        .setUri("$SCHEME://stream".toUri())
        .setMimeType(contentType)
        .build()

    val dataSourceFactory: DataSource.Factory = DataSource.Factory { StreamingDataSource() }

    /** Cancels every range in flight; later reads fail. */
    fun invalidate() {
        val inFlight = synchronized(lock) {
            invalidated = true
            calls.toList().also { calls.clear() }
        }
        inFlight.forEach(Call::cancel)
    }

    private inner class StreamingDataSource : BaseDataSource(true) {
        private var call: Call? = null
        private var response: Response? = null
        private var stream: InputStream? = null
        private var uri: Uri? = null
        private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
        private var opened = false

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            transferInitializing(dataSpec)
            val toEnd = dataSpec.length == C.LENGTH_UNSET.toLong()
            val ranged = request.newBuilder()
                .header("Range", rangeHeader(dataSpec.position, dataSpec.length, toEnd))
                .build()
            val call = client.newCall(ranged)
            synchronized(lock) {
                if (invalidated) throw IOException("Streaming stopped")
                calls += call
            }
            this.call = call
            val response = call.execute()
            this.response = response
            if (response.code != 206) {
                throw StreamingFileException(AgentFileError.from(response.code) ?: AgentFileError.Server(response.code))
            }
            serverPersistenceID?.let { contact.heard(it, response.request.url) }
            val total = AgentFileProbe.of(response).size
            bytesRemaining = when {
                !toEnd -> dataSpec.length
                total != null -> maxOf(0, total - dataSpec.position)
                else -> C.LENGTH_UNSET.toLong()
            }
            stream = response.body.byteStream()
            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
            val wanted = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), bytesRemaining).toInt()
            val read = stream?.read(buffer, offset, wanted) ?: return C.RESULT_END_OF_INPUT
            if (read < 0) return C.RESULT_END_OF_INPUT
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
            bytesTransferred(read)
            return read
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            runCatching { stream?.close() }
            response?.close()
            call?.let { call -> synchronized(lock) { calls -= call } }
            stream = null
            response = null
            call = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    /** A range answered with something other than 206. */
    class StreamingFileException(val error: AgentFileError) : IOException(error.title)

    companion object {
        const val SCHEME = "opencode-stream"

        /** The Range header for one ExoPlayer request. */
        fun rangeHeader(offset: Long, length: Long, toEnd: Boolean): String {
            if (toEnd || length <= 0) return "bytes=$offset-"
            return "bytes=$offset-${offset + length - 1}"
        }
    }
}
