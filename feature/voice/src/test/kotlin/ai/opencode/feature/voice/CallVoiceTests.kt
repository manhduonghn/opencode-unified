package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.withDependenciesSuspending
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RetryPolicy
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.ConnectException
import java.net.SocketException
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class CallVoiceTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_voice", serverTitle = "Voice lab", projectID = "p", directory = "/Users/kit/project", updatedAt = Instant.parse("0001-01-01T00:00:00Z"))

    @Test fun defaultsToMarinAndPersists() {
        SpeechTestShared().run {
            val callVoice = shared(VoiceSharedKeys.callVoice)
            callVoice.set(CallVoice.default)
            assertEquals(CallVoice.marin, callVoice.value)

            callVoice.set(CallVoice.cedar)
            val reread = shared(VoiceSharedKeys.callVoice)
            assertEquals("Another reader of the key sees the choice", CallVoice.cedar, reread.value)

            callVoice.set(CallVoice.default)
        }
    }

    @Test fun eachEngineOffersItsDocumentedVoices() {
        assertEquals(
            listOf("alloy", "ash", "ballad", "cedar", "coral", "echo", "marin", "sage", "shimmer", "verse"),
            CallVoice.supported(VoiceEngine.realtime).map { it.rawValue },
        )
        assertEquals(22, CallVoice.supported(VoiceEngine.gptLive).size)
        assertEquals(
            listOf(
                "altair", "ara", "atlas", "aurora", "carina", "castor", "celeste", "cosmo", "eve", "helios", "helix", "iris", "kepler",
                "leo", "liora", "lumen", "luna", "lux", "naksh", "orion", "perseus", "rex", "rigel", "sal", "sirius", "ursa", "zagan", "zenith",
            ),
            CallVoice.supported(VoiceEngine.grok).map { it.rawValue },
        )
        assertEquals("Every voice belongs to OpenAI or xAI, never both", 50, CallVoice.allCases.size)
        for (voice in CallVoice.allCases) {
            assertNotEquals("$voice", voice.isSupported(VoiceEngine.gptLive), voice.isSupported(VoiceEngine.grok))
            assertTrue("$voice", voice.isSupported(if (voice.provider == VoiceProvider.xAI) VoiceEngine.grok else VoiceEngine.gptLive))
        }
        for (voice in listOf(
            CallVoice.quartz, CallVoice.ripple, CallVoice.vesper, CallVoice.willow, CallVoice.stone, CallVoice.gleam,
            CallVoice.meridian, CallVoice.bossa, CallVoice.tempo, CallVoice.beacon, CallVoice.delta, CallVoice.cinder,
        )) {
            assertFalse("$voice is GPT-Live only", voice.isSupported(VoiceEngine.realtime))
            assertNotNull(voice.accent)
        }
        assertNull(CallVoice.marin.accent)
    }

    @Test fun anUnsupportedChoiceFallsBackToTheDefault() {
        assertEquals(CallVoice.quartz, CallVoice.quartz.resolved(VoiceEngine.gptLive))
        assertEquals(CallVoice.marin, CallVoice.quartz.resolved(VoiceEngine.realtime))
        assertEquals(CallVoice.cedar, CallVoice.cedar.resolved(VoiceEngine.realtime))
        // Grok Voice falls back to xAI's own default, and OpenAI's to theirs.
        assertEquals(CallVoice.eve, CallVoice.marin.resolved(VoiceEngine.grok))
        assertEquals(CallVoice.rex, CallVoice.rex.resolved(VoiceEngine.grok))
        assertEquals(CallVoice.marin, CallVoice.rex.resolved(VoiceEngine.gptLive))
    }

    @Test fun startConfigsCarryTheVoice() {
        val live = LiveSessionConfig(voice = CallVoice.quartz, instructions = "Be concise.")
        val start = Json.parseToJsonElement(LiveCommand.SessionStart(live).encoded().decodeToString())
        assertEquals(JsonPrimitive("quartz"), start["session"]?.get("audio")?.get("output")?.get("voice"))
        assertEquals(JsonPrimitive("quartz"), live.sessionPayload(includeAudioFormat = false)["audio"]?.get("output")?.get("voice"))

        val realtime = RealtimeSessionConfig(voice = CallVoice.cedar, instructions = "Be brief.", tools = emptyList())
        assertEquals(JsonPrimitive("cedar"), realtime.sessionUpdate["session"]?.get("audio")?.get("output")?.get("voice"))
        assertEquals(JsonPrimitive("cedar"), realtime.sessionUpdate(includeAudioFormat = false)["session"]?.get("audio")?.get("output")?.get("voice"))
        assertEquals("GPT-Live's start is built from the same config", CallVoice.cedar, realtime.liveSessionConfig().voice)

        assertEquals(CallVoice.marin, LiveSessionConfig(instructions = "").voice)
        assertEquals(CallVoice.marin, RealtimeSessionConfig(instructions = "", tools = emptyList()).voice)
    }

    @Test fun callsStartWithTheChosenVoiceForTheirEngine() = runTest {
        val shared = SpeechTestShared()
        val started = Locked(listOf<RealtimeSessionConfig>())
        val store = TestStore(shared.run { VoiceFeature.State(server, session) }, VoiceFeature(), this) {
            this[SharedStore] = shared.store
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            // Android: the call's ongoing-notification ID.
            this[UuidClient] = UuidClient.incrementing()
            this[RealtimePreflightClient] = RealtimePreflightClient(verify = { _, _ -> })
            this[VoiceCallClient] = speechVoiceCall(start = { _, config ->
                started.update { it + config }
                emptyFlow()
            })
        }
        store.exhaustive = false
        val voiceEngine = store.withDependencies { shared(VoiceSharedKeys.voiceEngine) }
        val callVoice = store.withDependencies { shared(VoiceSharedKeys.callVoice) }
        store.withDependencies { shared(SharedKeys.openAIAPIKey).set("sk-test") }
        val callEnded = { action: VoiceFeature.Action -> action is VoiceFeature.Action.CallEnded }

        // GPT-Live speaks with its own regional voice.
        voiceEngine.set(VoiceEngine.gptLive)
        callVoice.set(CallVoice.quartz)
        store.send(VoiceFeature.Action.Start)
        store.receive(callEnded, timeout = 1.seconds)
        assertEquals(VoiceEngine.gptLive, started.value.lastOrNull()?.engine)
        assertEquals(CallVoice.quartz, started.value.lastOrNull()?.voice)
        assertEquals(JsonPrimitive("quartz"), started.value.lastOrNull()?.liveSessionConfig()?.sessionStart()?.get("session")?.get("audio")?.get("output")?.get("voice"))
        assertEquals(CallVoice.quartz, store.state.activeVoice)

        // Realtime doesn't offer it: the call uses the default, and the choice stays saved.
        voiceEngine.set(VoiceEngine.realtime)
        store.send(VoiceFeature.Action.Start)
        store.receive(callEnded, timeout = 1.seconds)
        assertEquals(VoiceEngine.realtime, started.value.lastOrNull()?.engine)
        assertEquals(CallVoice.marin, started.value.lastOrNull()?.voice)
        assertEquals(CallVoice.quartz, callVoice.value)

        // A voice Realtime offers goes into its session update.
        callVoice.set(CallVoice.cedar)
        store.send(VoiceFeature.Action.Start)
        store.receive(callEnded, timeout = 1.seconds)
        assertEquals(CallVoice.cedar, started.value.lastOrNull()?.voice)
        assertEquals(JsonPrimitive("cedar"), started.value.lastOrNull()?.sessionUpdate?.get("session")?.get("audio")?.get("output")?.get("voice"))

        // Grok Voice uses xAI's default for an OpenAI choice, and its own voice at the top of the update.
        voiceEngine.set(VoiceEngine.grok)
        store.withDependencies { shared(SharedKeys.xaiAPIKey).set("xai-test") }
        store.send(VoiceFeature.Action.Start)
        store.receive(callEnded, timeout = 1.seconds)
        assertEquals(VoiceEngine.grok, started.value.lastOrNull()?.engine)
        assertEquals(CallVoice.eve, started.value.lastOrNull()?.voice)
        callVoice.set(CallVoice.rex)
        store.send(VoiceFeature.Action.Start)
        store.receive(callEnded, timeout = 1.seconds)
        assertEquals(JsonPrimitive("rex"), started.value.lastOrNull()?.sessionUpdate?.get("session")?.get("voice"))
        store.withDependencies { shared(SharedKeys.xaiAPIKey).set(null) }

        voiceEngine.set(VoiceEngine.gptLive)
        callVoice.set(CallVoice.default)
        store.skipReceivedActions()
    }

    @Test fun theCallLogNamesTheVoice() = runTest {
        val shared = SpeechTestShared()
        shared.run {
            shared(VoiceSharedKeys.voiceEngine).set(VoiceEngine.gptLive)
            shared(VoiceSharedKeys.callVoice).set(CallVoice.cedar)
        }
        val directory = Files.createTempDirectory("call-voice-").toFile()
        try {
            val date = DateClient.constant(Instant.EPOCH)
            val recorder = DependencyValues.withValues(DependencyValues.testing().update { this[DateClient] = date }) {
                VoiceCallLogRecorder(directory = directory)
            }
            val id = UUID.randomUUID()
            val store = TestStore(shared.run { VoiceFeature.State(server, session) }, VoiceFeature(), this) {
                this[SharedStore] = shared.store
                this[DateClient] = date
                this[UuidClient] = UuidClient { id }
                this[RealtimePreflightClient] = RealtimePreflightClient(verify = { _, _ -> })
                this[VoiceCallClient] = speechVoiceCall(start = { _, _ -> emptyFlow() })
                this[VoiceCallLogClient] = VoiceCallLogClient(recorder = recorder)
            }
            store.exhaustive = false
            store.withDependencies { shared(SharedKeys.openAIAPIKey).set("sk-test") }

            store.send(VoiceFeature.Action.Start)
            store.receive({ it is VoiceFeature.Action.CallEnded }, timeout = 1.seconds)
            store.skipReceivedActions()
            val log = recorder.list().firstOrNull { it.id == id }
            assertNotNull(log)
            assertTrue(log!!.notes.any { it.kind == VoiceCallLog.Note.Kind.marker && it.text == "Voice: cedar" })

            val client = VoiceCallLogClient(recorder = recorder)
            assertEquals(listOf(id), client.list().map { it.id })
            client.delete(id)
            assertTrue(client.list().isEmpty())

            store.withDependencies { shared(VoiceSharedKeys.callVoice).set(CallVoice.default) }
        } finally {
            directory.deleteRecursively()
        }
    }

    // NetworkRetry sleeps between attempts; JVM tests resolve Clock's test value, so the retry needs an ImmediateClock.
    @Test fun previewSynthesizesWithProviderTTSRetriesAndCachesPerProviderAndVoiceOnDisk() = runBlocking {
      withDependenciesSuspending({ this[Clock] = ImmediateClock() }) {
        val directory = Files.createTempDirectory("voice-previews-").toFile()
        try {
            val cache = VoicePreviewCache(directory = directory)
            val requests = Locked(listOf<Request>())
            val playedBytes = Locked(listOf<String>())
            val failFirst = Locked(true)

            val client = VoicePreviewClient.makeLive(
                cache = cache,
                send = { request ->
                    requests.update { it + request }
                    if (failFirst.withValue { false to it }) throw ConnectException("Connection refused")
                    val tag = request.url.host
                    VoiceHttpResponse(statusCode = 200, headers = Headers.headersOf(), body = "mp3-$tag".toByteArray())
                },
                playAudio = { data -> playedBytes.update { it + data.decodeToString() } },
            )

            val retryPolicy = RetryPolicy(maxAttempts = 2, baseDelay = Duration.ZERO, maxDelay = Duration.ZERO, retryWindow = 5.seconds, waitsForNetwork = false)
            NetworkRetry.policy.withValue(retryPolicy) {
                client.play(CallVoice.marin, VoiceProvider.openAI, "sk-openai")
            }
            // First attempt failed with cannotConnectToHost (.notSent); second succeeded via NetworkRetry and cached to disk.
            assertEquals(2, requests.value.size)
            val openAIRequest = requests.value.last()
            assertEquals("https://api.openai.com/v1/audio/speech", openAIRequest.url.toString())
            assertEquals("Bearer sk-openai", openAIRequest.header("Authorization"))
            val openAIBody = Json.decodeFromString(VoicePreviewRequest.OpenAIPayload.serializer(), body(openAIRequest))
            assertEquals(
                VoicePreviewRequest.OpenAIPayload(model = "gpt-4o-mini-tts", voice = "marin", input = "Hi, I’m Marin. Ready when you are.", response_format = "mp3"),
                openAIBody,
            )
            assertEquals("mp3-api.openai.com", cache.load(CallVoice.marin, VoiceProvider.openAI)?.decodeToString())
            assertEquals(listOf("mp3-api.openai.com"), playedBytes.value)

            // Playing Marin on OpenAI a second time uses the on-disk cache without hitting the network.
            client.play(CallVoice.marin, VoiceProvider.openAI, "sk-openai")
            assertEquals("Cached clip is reused without another network request", 2, requests.value.size)
            assertEquals(2, playedBytes.value.size)

            // Playing Eve on xAI hits https://api.x.ai/v1/tts and caches separately by provider and voice.
            client.play(CallVoice.eve, VoiceProvider.xAI, "xai-key")
            assertEquals(3, requests.value.size)
            val xaiRequest = requests.value.last()
            assertEquals("https://api.x.ai/v1/tts", xaiRequest.url.toString())
            assertEquals("Bearer xai-key", xaiRequest.header("Authorization"))
            val xaiBody = Json.decodeFromString(VoicePreviewRequest.XAIPayload.serializer(), body(xaiRequest))
            assertEquals(VoicePreviewRequest.XAIPayload(text = "Hi, I’m Eve. Ready when you are.", voice_id = "eve", language = "en"), xaiBody)
            assertEquals("mp3-api.x.ai", cache.load(CallVoice.eve, VoiceProvider.xAI)?.decodeToString())

            // Ambiguous mid-flight failures (networkConnectionLost) must not retry a non-idempotent TTS POST.
            val interruptedClient = VoicePreviewClient.makeLive(
                cache = cache,
                send = { request ->
                    requests.update { it + request }
                    throw SocketException("Connection reset")
                },
                playAudio = {},
            )
            try {
                NetworkRetry.policy.withValue(retryPolicy) {
                    interruptedClient.play(CallVoice.cedar, VoiceProvider.openAI, "sk-openai")
                }
                fail("Expected VoicePreviewError.Failed")
            } catch (error: VoicePreviewError) {
                assertEquals(VoicePreviewError.Failed, error)
            }
            assertEquals("Ambiguous mid-flight failure is sent once and not retried", 4, requests.value.size)
        } finally {
            directory.deleteRecursively()
        }
      }
    }

    private fun body(request: Request): String = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
}
