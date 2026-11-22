package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.server.OpenCodeTransport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException

/** Checks a phone key before a call. `verify` throws [RealtimePreflightError]. */
class RealtimePreflightClient(
    val verify: suspend (apiKey: String, model: String) -> Unit,
) {
    companion object Key : DependencyKey<RealtimePreflightClient> {
        override val liveValue: RealtimePreflightClient by lazy {
            // The lab's scripted call and stand-in servers need no real key (debug builds only).
            if (VoiceLabFlags.isEnabled || VoiceLabFlags.hasStandInServer) VoiceLabFlags.preflight else network
        }

        override val testValue: RealtimePreflightClient = RealtimePreflightClient(verify = { _, _ -> })

        private val network = RealtimePreflightClient(verify = { apiKey, model ->
            if (model == VoiceEngine.grok.modelID) {
                verifyXAI(apiKey)
            } else {
                val url = try {
                    "https://api.openai.com/v1/models".toHttpUrl().newBuilder().addPathSegment(model).build()
                } catch (_: IllegalArgumentException) {
                    throw RealtimePreflightError.Service("The voice model address is invalid.")
                }
                val response = fetchPreflight(Request.Builder().url(url), apiKey, provider = "OpenAI")
                if (response.statusCode !in 200..<300) {
                    throw RealtimePreflightError.response(response.statusCode, response.body)
                }
            }
        })

        /**
         * xAI lists no voice models, so this checks the key itself: `GET /v1/api-key`
         * answers for any working key, with flags for a blocked or disabled one.
         */
        private suspend fun verifyXAI(apiKey: String) {
            val response = fetchPreflight(Request.Builder().url("https://api.x.ai/v1/api-key"), apiKey, provider = "xAI")
            RealtimePreflightError.checkXAI(response.statusCode, response.body)
        }

        private suspend fun fetchPreflight(builder: Request.Builder, apiKey: String, provider: String): VoiceHttpResponse {
            val request = builder.header("Authorization", "Bearer $apiKey").get().build()
            return try {
                dependency(OpenCodeTransport).request.withVoiceTimeout(15).voiceSend(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw RealtimePreflightError.transport(error)
            }
            // OkHttp only ever returns HTTP responses.
        }
    }
}

/** Why a key check failed; [message] is the copy the call or Settings shows. */
sealed class RealtimePreflightError(override val message: String) : Exception(message) {
    data object RejectedAPIKey : RealtimePreflightError("OpenAI rejected the API key.")
    data object NoConnection : RealtimePreflightError("No connection.")
    data class Service(val text: String) : RealtimePreflightError(text)

    companion object {
        fun response(statusCode: Int, data: ByteArray): RealtimePreflightError {
            if (statusCode == 401) return RejectedAPIKey
            val message = decodeObject(data)?.let { it["error"] as? JsonObject }?.get("message")?.stringValue
            if (message != null && message.trim().isNotEmpty()) return Service(message)
            return Service("OpenAI couldn’t verify this key (HTTP $statusCode).")
        }

        /** A `URLError` is no connection; anything else carries its own description. */
        fun transport(error: Throwable): RealtimePreflightError =
            if (error is IOException) NoConnection else Service(error.localizedMessage ?: error.toString())

        /**
         * xAI answers a bad key with 400 `invalid-argument` (“Incorrect API key
         * provided”), not 401.
         */
        fun checkXAI(statusCode: Int, data: ByteArray) {
            val json = decodeObject(data)
            if (statusCode !in 200..<300) {
                val failure = json?.get("error")?.stringValue
                if (statusCode in listOf(400, 401, 403) && failure?.contains("api key", ignoreCase = true) == true) {
                    throw Service("xAI rejected the API key.")
                }
                val message = failure?.trim()
                if (!message.isNullOrEmpty()) throw Service(message)
                throw Service("xAI couldn’t verify this key (HTTP $statusCode).")
            }
            fun flag(key: String): Boolean = json?.get(key)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content == "true" } == true
            if (flag("api_key_disabled")) throw Service("This xAI API key is disabled.")
            if (flag("api_key_blocked") || flag("team_blocked")) throw Service("This xAI API key is blocked.")
        }

        private fun decodeObject(data: ByteArray): JsonObject? = try {
            VoiceWireJson.parseToJsonElement(data.decodeToString()) as? JsonObject
        } catch (_: Exception) {
            null
        }
    }
}
