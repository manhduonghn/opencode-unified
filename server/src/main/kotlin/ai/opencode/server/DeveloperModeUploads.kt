package ai.opencode.server

import ai.opencode.core.diagnostics.ProblemReportBuilder
import ai.opencode.core.diagnostics.ProblemReportBundle
import ai.opencode.core.diagnostics.ProblemReportContext
import ai.opencode.core.diagnostics.ProblemReportDevice
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RequestIdempotency
import ai.opencode.server.retry.RetryPolicy
import ai.opencode.server.retry.TransportFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * With Developer Mode on, finished voice calls and problem reports go to the log server under this
 * phone's random owner key. Anyone with [webURL] can view them; they expire after 30 days.
 */
object DeveloperModeUploads {
    const val BASE = "https://opencode-voice-logs.anomalyco.workers.dev"

    /** This phone's owner key, made once (32 random bytes, hex). Never log it. Call from a reducer or effect. */
    fun owner(): String {
        val stored = shared(SharedKeys.developerModeOwner)
        stored.value?.takeIf { it.length == 64 }?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val made = bytes.joinToString("") { "%02x".format(it) }
        stored.set(made)
        return made
    }

    /** The owner's page of uploads. Carries the owner key: never log it. */
    fun webURL(): String = "$BASE/c/${owner()}"

    /** One trace's page. Carries the owner key: never log it. */
    fun traceURL(id: UUID): String = "${webURL()}/t/${id.toString().uppercase(Locale.ROOT)}"

    private val client by lazy { OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build() }

    /**
     * Uploads a problem report (`PUT v1/traces/<owner>/<id>`, 201 expected) under the send retry
     * policy. [perform] stands in for the HTTP call, throwing an [IOException] for a transport
     * failure; null sends it.
     */
    suspend fun upload(bundle: ProblemReportBundle, perform: (suspend (Request) -> Response)? = null): Boolean {
        val body = traceBody(bundle).toString().toByteArray()
        return put("v1/traces", bundle.report.id, body, RetryPolicy.send, perform)
    }

    /** Uploads a voice call log (already JSON-encoded by its owner) under the request retry policy. */
    suspend fun uploadVoiceLog(id: UUID, json: ByteArray): Boolean = put("v1/logs", id, json, RetryPolicy.request)

    /** The worker's trace shape: the report, a readable summary, and its files (text as text, the screenshot as base64). */
    fun traceBody(bundle: ProblemReportBundle): JsonObject {
        val report = bundle.report.copy(context = bundle.report.context.copy(serverID = ProblemReportBuilder.safeServerID(bundle.report.context.serverID)))
        val json = Json { encodeDefaults = true }
        return buildJsonObject {
            put("id", report.id.toString().uppercase(Locale.ROOT))
            put("createdAt", DateTimeFormatter.ISO_INSTANT.format(report.createdAt.truncatedTo(ChronoUnit.SECONDS)))
            put("trigger", if (report.crashedLastRun) "crash" else "report")
            put("title", report.title)
            put("summary", ProblemReportBuilder.summary(report, hasScreenshot = bundle.files[ProblemReportBundle.File.SCREENSHOT] != null))
            put("device", json.encodeToJsonElement(ProblemReportDevice.serializer(), report.device))
            put("context", json.encodeToJsonElement(ProblemReportContext.serializer(), report.context))
            put("highlights", kotlinx.serialization.json.JsonArray(report.highlights.map(::JsonPrimitive)))
            put("files", JsonObject(bundle.files.mapValues { (name, data) ->
                JsonPrimitive(if (name.endsWith(".jpg")) Base64.getEncoder().encodeToString(data) else String(data))
            }))
            report.note.trim().takeIf { it.isNotEmpty() }?.let { put("note", it) }
            report.context.sessionID?.let { put("sessionID", it) }
            if (report.context.serverID.isNotEmpty()) put("serverID", report.context.serverID)
        }
    }

    private suspend fun put(
        path: String,
        id: UUID,
        body: ByteArray,
        policy: RetryPolicy,
        perform: (suspend (Request) -> Response)? = null,
    ): Boolean {
        val owner = owner()
        val url = "$BASE/$path/$owner/${id.toString().uppercase(Locale.ROOT)}"
        return try {
            NetworkRetry.run(NetworkRetry.currentPolicy(policy), idempotency = RequestIdempotency.Idempotent, label = "PUT /$path") {
                val request = Request.Builder().url(url).put(body.toRequestBody("application/json".toMediaType())).build()
                val (status, retryAfter) = try {
                    val response = perform?.invoke(request) ?: withContext(Dispatchers.IO) { client.newCall(request).execute() }
                    response.use { it.code to it.header("Retry-After") }
                } catch (error: IOException) {
                    val transport = TransportFailure.of(error)
                    throw transport?.let(OpenCodeClientError::unreachable) ?: OpenCodeClientError(OpenCodeClientError.UNREACHABLE_MESSAGE)
                }
                if (status != 201) {
                    throw OpenCodeClientError("The server returned HTTP $status.", statusCode = status, retryAfter = OpenCodeClientError.retryAfter(retryAfter))
                }
                true
            }
        } catch (error: OpenCodeClientError) {
            false
        }
    }
}
