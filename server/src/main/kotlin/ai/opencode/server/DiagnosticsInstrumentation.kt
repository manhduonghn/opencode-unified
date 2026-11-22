package ai.opencode.server

import ai.opencode.core.diagnostics.DiagnosticsErrorCategory
import ai.opencode.core.diagnostics.DiagnosticsFailurePhase
import ai.opencode.core.diagnostics.DiagnosticsNetworkAddress
import ai.opencode.core.diagnostics.DiagnosticsNetworkMetrics
import ai.opencode.core.diagnostics.DiagnosticsNetworkPhases
import ai.opencode.core.diagnostics.DiagnosticsRecorder
import ai.opencode.core.diagnostics.LabMarks
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Feeds the diagnostics timeline (`DiagnosticsRecorder`) from OkHttp. Records only method, redacted
 * path, status, sizes and timings; never headers, bodies or the host. Inert (one volatile read per
 * call) while recording is off.
 */
internal object DiagnosticsInstrumentation {
    private val inFlight = AtomicInteger(0)

    /** Each measured call's phase evidence, for the failure phase of a transport error. */
    private val phasesByCall = ConcurrentHashMap<Call, DiagnosticsNetworkPhases>()

    fun OkHttpClient.Builder.recordingDiagnostics(): OkHttpClient.Builder =
        addInterceptor(RecordingInterceptor).eventListenerFactory { call -> if (LabMarks.needsNetworkMetrics) MetricsListener(call.request()) else EventListener.NONE }

    private fun ids(request: Request): Triple<String, String?, Int?> = Triple(
        request.header("X-OpenCode-Req") ?: UUID.randomUUID().toString().uppercase(),
        request.header("X-OpenCode-Op"),
        request.header("X-OpenCode-Attempt")?.toIntOrNull(),
    )

    private object RecordingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val recorder = DiagnosticsRecorder.shared
            val request = chain.request()
            if (!recorder.isEnabled) return chain.proceed(request)
            val (id, operation, attempt) = ids(request)
            val url = request.url.toString()
            recorder.requestStarted(id, request.method, url, reason = null, operationID = operation, attempt = attempt, inFlightRequests = inFlight.incrementAndGet())
            val response = try {
                chain.proceed(request)
            } catch (error: IOException) {
                inFlight.decrementAndGet()
                val category = DiagnosticsErrorCategory.of(error)
                recorder.requestEnded(
                    id, request.method, url, statusCode = null, errorCategory = category, decodedBytes = null, contentEncoding = null,
                    operationID = operation, attempt = attempt,
                    failurePhase = if (category == DiagnosticsErrorCategory.Cancelled) {
                        DiagnosticsFailurePhase.Cancelled
                    } else {
                        phasesByCall[chain.call()]?.let { synchronized(it) { it.unfinished } }
                    },
                )
                throw error
            }
            val status = response.code
            val failed = status !in 200..299
            val encoding = response.header("Content-Encoding")
            var ended = false
            val end = { bytes: Long?, error: IOException? ->
                if (!ended) {
                    ended = true
                    inFlight.decrementAndGet()
                    recorder.requestEnded(
                        id, request.method, url, statusCode = status,
                        errorCategory = if (failed) DiagnosticsErrorCategory.of(status) else error?.let { DiagnosticsErrorCategory.of(it) },
                        decodedBytes = if (error == null) bytes else null, contentEncoding = encoding,
                        operationID = operation, attempt = attempt,
                        failurePhase = when {
                            failed -> DiagnosticsFailurePhase.HttpStatus
                            error != null -> DiagnosticsFailurePhase.BodyRead
                            else -> null
                        },
                    )
                }
            }
            val body = response.body
            val counting = object : ForwardingSource(body.source()) {
                var count = 0L
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val read = try {
                        super.read(sink, byteCount)
                    } catch (error: IOException) {
                        end(null, error)
                        throw error
                    }
                    if (read == -1L) end(count, null) else count += read
                    return read
                }

                override fun close() {
                    end(count, null)
                    super.close()
                }
            }
            return response.newBuilder().body(counting.buffer().asResponseBody(body.contentType(), body.contentLength())).build()
        }
    }

    /** Per-call transport timings. */
    private class MetricsListener(private val request: Request) : EventListener() {
        private var callStart = 0L
        private var dnsStart = 0L; private var dns = 0L
        private var connectStart = 0L; private var connect = 0L
        private var tlsStart = 0L; private var tls = 0L
        private var requestStart = 0L; private var requestEnd = 0L
        private var responseStart = 0L; private var responseEnd = 0L
        private var requestHeaderBytes = 0L; private var requestBodyBytes = 0L
        private var responseHeaderBytes = 0L; private var responseBodyBytes = 0L
        private var protocol: Protocol? = null
        private var reused = true
        private var address: DiagnosticsNetworkAddress? = null
        /** The latest attempt's phase timestamps. */
        private val phases = DiagnosticsNetworkPhases()

        private fun phase(update: DiagnosticsNetworkPhases.(Long) -> Unit) {
            synchronized(phases) { phases.update(System.nanoTime()) }
        }

        private fun ms(from: Long, to: Long): Double? = if (from == 0L || to < from) null else (to - from) / 1e6

        override fun callStart(call: Call) {
            callStart = System.nanoTime()
            phasesByCall[call] = phases
        }
        override fun dnsStart(call: Call, domainName: String) {
            dnsStart = System.nanoTime()
            phase { dnsStart = it; dnsEnd = null }
        }
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
            dns += System.nanoTime() - dnsStart
            phase { dnsEnd = it }
        }
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            reused = false
            connectStart = System.nanoTime()
            address = DiagnosticsNetworkAddress.of(inetSocketAddress.address?.address)
            phase { connectStart = it; connectEnd = null; tlsStart = null; tlsEnd = null }
        }
        override fun secureConnectStart(call: Call) {
            tlsStart = System.nanoTime()
            phase { tlsStart = it }
        }
        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            tls += System.nanoTime() - tlsStart
            phase { tlsEnd = it }
        }
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            connect += System.nanoTime() - connectStart
            phase { connectEnd = it }
        }
        override fun connectionAcquired(call: Call, connection: Connection) {
            protocol = connection.protocol()
            if (address == null) address = DiagnosticsNetworkAddress.of(connection.route().socketAddress.address?.address)
        }
        override fun requestHeadersStart(call: Call) {
            requestStart = System.nanoTime()
            phase { requestStart = it; requestEnd = null; responseStart = null; responseEnd = null }
        }
        override fun requestHeadersEnd(call: Call, request: Request) {
            requestEnd = System.nanoTime()
            requestHeaderBytes += request.headers.byteCount()
            if (request.body == null) phase { requestEnd = it }
        }
        override fun requestBodyStart(call: Call) = phase { requestEnd = null }
        override fun requestBodyEnd(call: Call, byteCount: Long) {
            requestEnd = System.nanoTime()
            requestBodyBytes += byteCount
            phase { requestEnd = it }
        }
        override fun responseHeadersStart(call: Call) {
            responseStart = System.nanoTime()
            phase { responseStart = it }
        }
        override fun responseHeadersEnd(call: Call, response: Response) { responseHeaderBytes += response.headers.byteCount() }
        override fun responseBodyEnd(call: Call, byteCount: Long) {
            responseEnd = System.nanoTime()
            responseBodyBytes += byteCount
            phase { responseEnd = it }
        }
        override fun callEnd(call: Call) {
            phasesByCall.remove(call)
            report()
        }
        override fun callFailed(call: Call, ioe: IOException) {
            phasesByCall.remove(call)
            report()
        }

        private fun report() {
            if (callStart == 0L) return
            val (id, operation, attempt) = ids(request)
            LabMarks.networkTask(
                requestID = id,
                path = DiagnosticsRecorder.redactedPath(request.url.encodedPath),
                metrics = DiagnosticsNetworkMetrics(
                    wireBytes = responseBodyBytes,
                    protocolName = protocol?.toString(),
                    connectionReused = reused,
                    dnsMilliseconds = if (dns > 0) dns / 1e6 else null,
                    connectMilliseconds = if (connect > 0) connect / 1e6 else null,
                    tlsMilliseconds = if (tls > 0) tls / 1e6 else null,
                    firstByteMilliseconds = ms(requestEnd, responseStart),
                    downloadMilliseconds = ms(responseStart, responseEnd),
                    uploadMilliseconds = ms(requestStart, requestEnd),
                    requestBodyBytes = requestBodyBytes,
                    requestHeaderBytes = requestHeaderBytes,
                    responseHeaderBytes = responseHeaderBytes,
                    address = address,
                ),
                operationID = operation,
                attempt = attempt,
            )
        }
    }
}
