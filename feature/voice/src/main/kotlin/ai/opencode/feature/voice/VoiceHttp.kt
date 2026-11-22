package ai.opencode.feature.voice

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One HTTP exchange read whole, for the voice calls that talk to OpenAI, xAI or a server's voice
 * routes outside `OpenCodeClient`.
 */
class VoiceHttpResponse(val statusCode: Int, val headers: Headers, val body: ByteArray) {
    fun header(name: String): String? = headers[name]
}

internal val voiceJsonMediaType = "application/json".toMediaType()

/** [this] with every timeout set to [seconds]. */
internal fun OkHttpClient.withVoiceTimeout(seconds: Long): OkHttpClient = newBuilder()
    .connectTimeout(seconds, TimeUnit.SECONDS)
    .readTimeout(seconds, TimeUnit.SECONDS)
    .writeTimeout(seconds, TimeUnit.SECONDS)
    .build()

/** Sends [request], reading the whole body; cancelling the coroutine cancels the call. */
internal suspend fun OkHttpClient.voiceSend(request: Request): VoiceHttpResponse = newCall(request).voiceAwait()

private suspend fun Call.voiceAwait(): VoiceHttpResponse = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use { VoiceHttpResponse(it.code, it.headers, it.body.bytes()) }
            } catch (e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
                return
            }
            if (continuation.isActive) continuation.resume(result)
        }
    })
}
