package ai.opencode.feature.voice

import ai.opencode.core.dependencies.dependency
import ai.opencode.server.OpenCodeServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A server-brokered session. Backend instructions stay on the data channel;
 * this layer never touches microphone, speaker, audio formats or RTP. */
class VoiceBrokerTransport(val server: OpenCodeServer) {
    suspend fun exchange(config: LiveSessionConfig, sdp: String): String =
        exchange(config.model, config.sessionPayload(false), sdp, LiveRTC.dataChannelID)

    /** Refusals retain their status (0 without an answer, 404 without the route)
     * and must never trigger a direct WebSocket fallback. */
    suspend fun exchange(model: String, session: RealtimeJSON?, sdp: String, dcid: Int?): String {
        try {
            currentCoroutineContext().ensureActive()
            val request = ServerVoiceSessionRequest(model, session, ServerVoiceTransportRequest.Webrtc(sdp, dcid))
            return (dependency(ServerVoiceClient).createSession(server, request) as? ServerVoiceTransportResponse.Webrtc)?.sdp
                ?: throw ServerVoiceError.InvalidResponse
        } catch (error: CancellationException) { throw error
        } catch (error: ServerVoiceError) {
            val status = when (error) {
                is ServerVoiceError.Rejected -> error.status
                ServerVoiceError.UnsupportedRoute -> 404
                else -> 0
            }
            throw LiveRTCRejected(status, error.errorDescription)
        } catch (error: Exception) {
            throw LiveRTCRejected(0, ServerVoiceError.formattedMessage(error.localizedMessage, 0))
        }
    }
    suspend fun webSocket(model: String, session: RealtimeJSON?): ServerVoiceTransportResponse.Websocket {
        val transport = try {
            dependency(ServerVoiceClient).createSession(server, ServerVoiceSessionRequest(model, session, ServerVoiceTransportRequest.Websocket))
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { throw RealtimeClientError.Closed(error.localizedMessage ?: "") }
        return transport as? ServerVoiceTransportResponse.Websocket ?: throw RealtimeClientError.InvalidURL
    }
}
