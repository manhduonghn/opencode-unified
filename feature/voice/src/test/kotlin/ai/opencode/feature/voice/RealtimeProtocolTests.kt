package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.asContextElement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RealtimeProtocolTests {
    private fun json(text: String): RealtimeJSON = VoiceWireJson.parseToJsonElement(text)
    private fun decode(bytes: ByteArray): RealtimeJSON = VoiceWireJson.parseToJsonElement(bytes.decodeToString())
    private val s = RealtimeJSONs::string
    private fun n(value: Int) = RealtimeJSONs.number(value)

    private inline fun expectThrows(expected: Throwable, body: () -> Unit) {
        try {
            body()
        } catch (error: Throwable) {
            assertEquals(expected, error)
            return
        }
        fail("Expected $expected to be thrown")
    }

    @Test fun preflightErrorsAreReadable() {
        assertEquals(RealtimePreflightError.RejectedAPIKey, RealtimePreflightError.response(statusCode = 401, data = ByteArray(0)))
        val quota = """{"error":{"message":"Your project has no remaining quota.","type":"insufficient_quota"}}""".toByteArray()
        assertEquals(RealtimePreflightError.Service("Your project has no remaining quota."), RealtimePreflightError.response(statusCode = 429, data = quota))
        assertEquals(RealtimePreflightError.NoConnection, RealtimePreflightError.transport(IOException("The Internet connection appears to be offline.")))
    }

    /** xAI's answers to `GET /v1/api-key`, as the live service sent them. */
    @Test fun xaiPreflightReadsTheKeyCheck() {
        val working = """{"redacted_api_key":"xai-…","acls":["api-key:endpoint:*"],"team_blocked":false,"api_key_blocked":false,"api_key_disabled":false}""".toByteArray()
        RealtimePreflightError.checkXAI(statusCode = 200, data = working)
        val incorrect = """{"code":"invalid-argument","error":"Incorrect API key provided. You can obtain an API key from https://console.x.ai."}""".toByteArray()
        expectThrows(RealtimePreflightError.Service("xAI rejected the API key.")) {
            RealtimePreflightError.checkXAI(statusCode = 400, data = incorrect)
        }
        val disabled = """{"team_blocked":false,"api_key_blocked":false,"api_key_disabled":true}""".toByteArray()
        expectThrows(RealtimePreflightError.Service("This xAI API key is disabled.")) {
            RealtimePreflightError.checkXAI(statusCode = 200, data = disabled)
        }
        val blocked = """{"team_blocked":true,"api_key_blocked":false,"api_key_disabled":false}""".toByteArray()
        expectThrows(RealtimePreflightError.Service("This xAI API key is blocked.")) {
            RealtimePreflightError.checkXAI(statusCode = 200, data = blocked)
        }
        expectThrows(RealtimePreflightError.Service("xAI couldn’t verify this key (HTTP 503).")) {
            RealtimePreflightError.checkXAI(statusCode = 503, data = ByteArray(0))
        }
    }

    /**
     * Grok Voice's documented `session.update`, checked against the live
     * service: it delegated through these tools and spoke the result.
     */
    @Test fun grokSessionUpdateUsesXAIsShape() {
        val config = RealtimeSessionConfig(
            engine = VoiceEngine.grok,
            model = VoiceEngine.grok.modelID,
            voice = CallVoice.eve,
            instructions = "Be brief.",
            tools = VoiceInstructions.tools,
        )
        assertEquals("wss://api.x.ai/v1/realtime?model=grok-voice-3.0", config.webSocketURL)
        assertEquals("wss://api.openai.com/v1/realtime?model=gpt-realtime-2.1", RealtimeSessionConfig(instructions = "", tools = emptyList()).webSocketURL)
        val json = decode(RealtimeCommand.SessionUpdate(config).encoded())
        assertEquals(s("session.update"), json["type"])
        val session = json["session"] ?: return fail("session missing")
        assertNull(session["type"])
        assertNull("The model rides in the socket's query", session["model"])
        assertEquals(s("eve"), session["voice"])
        assertEquals(s("Be brief."), session["instructions"])
        assertEquals(RealtimeJSONs.obj("type" to s("server_vad")), session["turn_detection"])
        assertEquals(RealtimeJSONs.obj("type" to s("audio/pcm"), "rate" to n(24_000)), session["audio"]?.get("input")?.get("format"))
        assertEquals(RealtimeJSONs.obj("type" to s("audio/pcm"), "rate" to n(24_000)), session["audio"]?.get("output")?.get("format"))
        assertNull("Grok transcribes the user's turns without it", session["audio"]?.get("input")?.get("transcription"))
        val tools = session["tools"] as? JsonArray ?: return fail("tools missing")
        assertEquals(listOf(s("delegate"), s("end_voice_session")), tools.map { it["name"] })
        assertTrue(tools.all { it["type"] == s("function") })
        assertEquals(config.sessionUpdate, config.sessionUpdate(includeAudioFormat = false))
    }

    @Test fun decodesTheEventsTheCallDependsOn() {
        val cases: List<Pair<String, RealtimeServerEvent>> = listOf(
            """{"type":"session.created","session":{}}""" to RealtimeServerEvent.SessionCreated(expiresAt = null),
            """{"type":"session.created","session":{"expires_at":1756324625}}""" to RealtimeServerEvent.SessionCreated(expiresAt = Instant.ofEpochSecond(1756324625)),
            """{"type":"input_audio_buffer.speech_stopped"}""" to RealtimeServerEvent.SpeechStopped,
            """{"type":"input_audio_buffer.speech_started","item_id":"item_1","audio_start_ms":10}""" to RealtimeServerEvent.SpeechStarted,
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"item_1","transcript":"hello"}""" to RealtimeServerEvent.InputTranscript(itemID = "item_1", text = "hello", final = true),
            """{"type":"response.output_audio.delta","response_id":"resp_1","item_id":"item_2","delta":"AAD//w=="}""" to RealtimeServerEvent.OutputAudio(responseID = "resp_1", itemID = "item_2", pcm = byteArrayOf(0, 0, -1, -1)),
            """{"type":"response.output_audio_transcript.done","response_id":"resp_1","item_id":"item_2","transcript":"On it."}""" to RealtimeServerEvent.OutputTranscriptDone(itemID = "item_2", text = "On it."),
            """{"type":"response.function_call_arguments.done","call_id":"call_1","name":"delegate","arguments":"{\"text\":\"Inspect the test\"}"}""" to RealtimeServerEvent.FunctionCall(callID = "call_1", name = "delegate", arguments = RealtimeJSONs.obj("text" to s("Inspect the test"))),
            """{"type":"response.done","response":{"id":"resp_1","status":"cancelled"}}""" to RealtimeServerEvent.ResponseDone(id = "resp_1"),
            """{"type":"error","error":{"message":"bad","code":"bad_state","type":"invalid_request_error"}}""" to RealtimeServerEvent.Error(RealtimeServerError(message = "bad", code = "bad_state", type = "invalid_request_error")),
            """{"type":"session.updated","session":{}}""" to RealtimeServerEvent.Ignored,
            """{"type":"rate_limits.updated"}""" to RealtimeServerEvent.Ignored,
        )
        for ((text, expected) in cases) {
            assertEquals(text, expected, RealtimeServerEvent.decode(text.toByteArray()))
        }
    }

    @Test fun sessionUpdateUsesGAShapeWithServerDrivenTurns() {
        val config = RealtimeSessionConfig(instructions = "Be brief.", tools = VoiceInstructions.tools)
        val json = decode(RealtimeCommand.SessionUpdate(config).encoded())
        val session = json["session"] ?: return fail("session missing")
        assertEquals(s("realtime"), session["type"])
        assertEquals(s("gpt-realtime-2.1"), session["model"])
        assertEquals(n(24_000), session["audio"]?.get("input")?.get("format")?.get("rate"))
        assertEquals(RealtimeJSONs.bool(true), session["audio"]?.get("input")?.get("turn_detection")?.get("create_response"))
        assertEquals(s("gpt-live-transcribe"), session["audio"]?.get("input")?.get("transcription")?.get("model"))
        val tools = session["tools"] as? JsonArray ?: return fail("tools missing")
        assertEquals(listOf(s("delegate"), s("end_voice_session")), tools.map { it["name"] })

        val truncate = decode(RealtimeCommand.Truncate(itemID = "item_2", audioEndMilliseconds = 1_250).encoded())
        assertEquals(n(1_250), truncate["audio_end_ms"])
        assertEquals(n(0), truncate["content_index"])
    }

    @Test fun sessionUpdateWaitsForAFinishedThoughtInEnglish() {
        val update = RealtimeSessionConfig(instructions = "Be brief.", tools = emptyList()).sessionUpdate
        val input = update["session"]?.get("audio")?.get("input")
        assertEquals(s("semantic_vad"), input?.get("turn_detection")?.get("type"))
        assertEquals(s("low"), input?.get("turn_detection")?.get("eagerness"))
        assertEquals(RealtimeJSONs.bool(true), input?.get("turn_detection")?.get("interrupt_response"))
        assertEquals(RealtimeJSONs.array(listOf(s("en"))), input?.get("transcription")?.get("languages"))
        assertNull("The device's voice processing already cleans the input", input?.get("noise_reduction"))
    }

    @Test fun playbackBufferScalesInt16ToFloat() {
        val pcm = ByteArray(6)
        for ((index, sample) in listOf(0, Short.MAX_VALUE.toInt(), Short.MIN_VALUE.toInt()).withIndex()) {
            pcm[index * 2] = sample.toByte()
            pcm[index * 2 + 1] = (sample shr 8).toByte()
        }
        val channel = PCM.playbackBuffer(pcm) ?: return fail("no buffer")
        assertEquals(3, channel.size)
        assertEquals(0f, channel[0])
        assertEquals(1f, channel[1])
        assertTrue(channel[2] < -0.99f)
    }

    @Test fun playbackTimeIsPerItemAndClampedToEnqueuedAudio() {
        val tracker = PlayedAudioTracker()
        tracker.enqueue(itemID = "long", frames = 331_200, playerSampleTime = 0) // 13.8s
        assertEquals(13_800, tracker.playedMilliseconds(playerSampleTime = 331_200, sampleRate = 24_000.0, outputLatency = 0.0))

        tracker.reset() // natural drain
        tracker.enqueue(itemID = "short", frames = 20_400, playerSampleTime = 331_200) // 850ms
        assertEquals(850, tracker.playedMilliseconds(playerSampleTime = 351_600, sampleRate = 24_000.0, outputLatency = 0.0))
        assertEquals(850, tracker.playedMilliseconds(playerSampleTime = 500_000, sampleRate = 24_000.0, outputLatency = 0.0))
    }

    @Test fun playbackTimeStartsAtEachNewItemEvenWhenAlreadyQueued() {
        val tracker = PlayedAudioTracker()
        tracker.enqueue(itemID = "first", frames = 24_000, playerSampleTime = 0)
        tracker.enqueue(itemID = "second", frames = 12_000, playerSampleTime = 6_000)

        assertEquals(0, tracker.playedMilliseconds(playerSampleTime = 24_000, sampleRate = 24_000.0, outputLatency = 0.0))
        assertEquals(250, tracker.playedMilliseconds(playerSampleTime = 30_000, sampleRate = 24_000.0, outputLatency = 0.0))
        assertEquals(500, tracker.playedMilliseconds(playerSampleTime = 40_000, sampleRate = 24_000.0, outputLatency = 0.0))
    }

    private data class Arrival(val frame: Long, val sentence: Int)

    /**
     * Measured against gpt-live-1 (`scripts/live-audio-pacing.ts`): 100 ms
     * frames arrive in real time, each up to ~150 ms late, in order. Between
     * sentences the speech gate lets the player run dry, so every sentence is
     * a new run: 20 sentences of 30 frames, 1.5 s apart.
     */
    private fun realTimeSentences(): List<Arrival> {
        var seed: ULong = 12_345u
        val arrivals = mutableListOf<Arrival>()
        var origin = 10_000L
        for (sentence in 0 until 20) {
            var previous = 0L
            for (index in 0 until 30) {
                seed = (seed * 1_103_515_245u + 12_345u) % (1uL shl 31)
                val late = if (index == 0) 0L else (seed % 3_601u).toLong()
                val arrival = maxOf(previous, origin + index * 2_400L + late)
                arrivals += Arrival(arrival, sentence)
                previous = arrival
            }
            origin += 30 * 2_400 + 36_000
        }
        return arrivals
    }

    /** Gaps a listener hears mid-sentence when each frame plays as it arrives. */
    private fun gapsWithoutPreroll(arrivals: List<Arrival>): Int {
        var end: Long? = null
        var sentence: Int? = null
        var gaps = 0
        for (arrival in arrivals) {
            val current = end
            if (current != null && arrival.frame < current) {
                end = current + 2_400
            } else {
                if (end != null && sentence == arrival.sentence) gaps += 1
                end = arrival.frame + 2_400
            }
            sentence = arrival.sentence
        }
        return gaps
    }

    @Test fun realTimeAudioStartsBehindAPrerollThatAbsorbsJitter() {
        val arrivals = realTimeSentences()
        assertTrue("Scheduling on arrival chops this stream", gapsWithoutPreroll(arrivals) > 50)

        val scheduler = PlaybackScheduler()
        var underruns = 0
        var starts = 0
        for (arrival in arrivals) {
            val decision = scheduler.schedule(frames = 2_400, itemID = "live-a-${arrival.sentence}", hasSpeech = true, now = arrival.frame)
            if (decision.underrunFrames != null) underruns += 1
            if (decision.placement is PlaybackScheduler.Placement.start) starts += 1
        }
        assertEquals("One run a sentence, each 200 ms behind its first frame", 20, starts)
        assertEquals(0, underruns)
        assertEquals(PlaybackScheduler.initialPreroll, scheduler.preroll)
    }

    @Test fun aFrameLateMidSpeechRestartsBehindALongerPreroll() {
        val scheduler = PlaybackScheduler()
        assertEquals(PlaybackScheduler.Placement.start(atFrame = 4_800), scheduler.schedule(frames = 2_400, itemID = "a", hasSpeech = true, now = 0).placement)
        val queued = scheduler.schedule(frames = 2_400, itemID = "a", hasSpeech = true, now = 2_400)
        assertEquals(PlaybackScheduler.Decision(placement = PlaybackScheduler.Placement.queued, aheadFrames = 4_800), queued)
        // Queued audio ran out at 9,600; the next frame lands 50 ms after.
        val late = scheduler.schedule(frames = 2_400, itemID = "a", hasSpeech = true, now = 10_800)
        assertEquals(1_200L, late.underrunFrames)
        assertEquals(PlaybackScheduler.Placement.start(atFrame = 10_800L + 7_200), late.placement)
        assertEquals(7_200L, scheduler.preroll)

        for (step in 0 until 10) {
            val now = 1_000_000L + step * 30_000
            scheduler.schedule(frames = 2_400, itemID = "a", hasSpeech = true, now = now)
            scheduler.schedule(frames = 2_400, itemID = "a", hasSpeech = true, now = now + 23_000)
        }
        assertEquals(PlaybackScheduler.maximumPreroll, scheduler.preroll)

        scheduler.stop()
        assertEquals("A flush keeps what the call learned", PlaybackScheduler.maximumPreroll, scheduler.preroll)
    }

    @Test fun pausesNewRepliesAndStallsAreNotUnderruns() {
        val scheduler = PlaybackScheduler()
        scheduler.schedule(frames = 2_400, itemID = "a", hasSpeech = true, now = 0)
        // The hangover's silence ends the run: what follows is a pause.
        scheduler.schedule(frames = 2_400, itemID = "a", hasSpeech = false, now = 2_400)
        assertNull(scheduler.schedule(frames = 2_400, itemID = "a", hasSpeech = true, now = 20_000).underrunFrames)
        // A new reply.
        assertNull(scheduler.schedule(frames = 2_400, itemID = "b", hasSpeech = true, now = 40_000).underrunFrames)
        // A stream that stopped for over a second stalled; that's not jitter.
        assertNull(scheduler.schedule(frames = 2_400, itemID = "b", hasSpeech = true, now = 80_000).underrunFrames)
        assertEquals(PlaybackScheduler.initialPreroll, scheduler.preroll)
    }

    private data class Frame(val arrival: Long, val speech: Boolean)

    /**
     * Plays `frames` (50 ms each, arriving in real time) through the scheduler
     * and returns where each lands on the player's timeline.
     */
    private fun playbackStarts(frames: List<Frame>, scheduler: PlaybackScheduler): List<Long> {
        var end = 0L
        return frames.map { frame ->
            val decision = scheduler.schedule(frames = 1_200, itemID = "a", hasSpeech = frame.speech, now = frame.arrival)
            val start = when (val placement = decision.placement) {
                PlaybackScheduler.Placement.queued -> end
                is PlaybackScheduler.Placement.start -> placement.atFrame
            }
            end = start + 1_200
            start
        }
    }

    @Test fun aPauseLongerThanTheHangoverPlaysAtItsRealLength() {
        // 500 ms of speech, then a 450 ms pause: the coordinator sends the first
        // 300 ms as silence and drops the rest, then speech resumes.
        val frames = mutableListOf<Frame>()
        for (index in 0 until 10) frames += Frame(index * 1_200L, true)
        for (index in 10 until 16) frames += Frame(index * 1_200L, false)
        for (index in 19 until 22) frames += Frame(index * 1_200L, true)
        val scheduler = PlaybackScheduler()
        val starts = playbackStarts(frames, scheduler)

        val speechEnds = starts[9] + 1_200
        val resumes = starts[16]
        assertEquals("The listener hears the whole 450 ms pause", 10_800L, resumes - speechEnds)
        assertEquals("The next sentence starts with the full pre-roll ahead", PlaybackScheduler.initialPreroll, resumes - frames[16].arrival)
        assertTrue("and plays on without a gap", starts[17] == resumes + 1_200 && starts[18] == resumes + 2_400)
        assertEquals("A pause is not an underrun", PlaybackScheduler.initialPreroll, scheduler.preroll)
    }

    @Test fun aShortPauseQueuesStraightOn() {
        // A 100 ms pause inside the hangover: the stream sent all of it as silence.
        val frames = mutableListOf<Frame>()
        for (index in 0 until 4) frames += Frame(index * 1_200L, true)
        for (index in 4 until 6) frames += Frame(index * 1_200L, false)
        frames += Frame(6 * 1_200L, true)
        val scheduler = PlaybackScheduler()
        val starts = playbackStarts(frames, scheduler)
        assertEquals(2_400L, starts[6] - (starts[3] + 1_200))
        assertEquals(PlaybackScheduler.initialPreroll, starts[6] - frames[6].arrival)
    }

    @Test fun aLateSilentFrameIsNotAnUnderrun() {
        val scheduler = PlaybackScheduler()
        scheduler.schedule(frames = 2_400, itemID = "a", hasSpeech = true, now = 0)
        // The run ends at 7,200; the hangover's first silent frame lands 50 ms after.
        val silence = scheduler.schedule(frames = 2_400, itemID = "a", hasSpeech = false, now = 8_400)
        assertNull(silence.underrunFrames)
        assertEquals(PlaybackScheduler.initialPreroll, scheduler.preroll)
    }

    @Test fun aRestartedPlayerTimelineStartsANewRun() {
        val scheduler = PlaybackScheduler()
        scheduler.schedule(frames = 2_400, itemID = "a", hasSpeech = true, now = 500_000)
        // The engine restarted: player time is back near zero.
        val decision = scheduler.schedule(frames = 2_400, itemID = "a", hasSpeech = true, now = 1_000)
        assertEquals(PlaybackScheduler.Placement.start(atFrame = 5_800), decision.placement)
        assertNull(decision.underrunFrames)
    }

    @Test fun renderedGapsCountSilenceInsideSpeechOnly() {
        val counter = RenderedGapCounter()
        val tone = FloatArray(2_400) { (sin((it + 1) * 0.0576) * 0.1).toFloat() }
        fun feed(samples: FloatArray) = counter.add(samples)
        feed(FloatArray(4_800)) // pre-roll before the first word
        feed(tone)
        feed(FloatArray(480)) // the player starved for 20 ms
        feed(tone)
        feed(FloatArray(24_000)) // a pause between turns
        feed(tone)
        assertEquals(1, counter.gaps)
        assertEquals(480, counter.gapSamples)
    }

    @Test fun realtimeErrorsAreRacesExpiryOrFatal() {
        assertEquals(
            RealtimeServerError.Disposition.harmless,
            RealtimeServerError(message = "Audio content of 850ms is already shorter than 13859ms", code = "invalid_value", type = "invalid_request_error").disposition,
        )
        assertEquals(
            RealtimeServerError.Disposition.harmless,
            RealtimeServerError(message = "Conversation already has an active response", code = "conversation_already_has_active_response", type = "invalid_request_error").disposition,
        )
        assertEquals(
            RealtimeServerError.Disposition.sessionExpired,
            RealtimeServerError(message = "Your session hit the maximum duration of 60 minutes.", code = "session_expired", type = "invalid_request_error").disposition,
        )
        assertEquals(
            RealtimeServerError.Disposition.fatal,
            RealtimeServerError(message = "You exceeded your current quota", code = "insufficient_quota", type = "insufficient_quota").disposition,
        )
    }

    private class CoordinatorHarness(
        val serverEvents: Channel<RealtimeServerEvent>,
        val audioEvents: Channel<VoiceAudioEvent>,
        val teardowns: Channel<Unit>,
        val coordinator: VoiceCallCoordinator,
    ) {
        val commandList = mutableListOf<RealtimeCommand>()
        val commands: List<RealtimeCommand> get() = synchronized(commandList) { commandList.toList() }
        val flushes = AtomicInteger(0)
        val enqueues = AtomicInteger(0)
        val closed = AtomicInteger(0)
        val stopped = AtomicInteger(0)
        lateinit var consumer: kotlinx.coroutines.Job

        companion object {
            suspend fun start(flushMilliseconds: Int = 0): Pair<CoordinatorHarness, ReceiveChannel<VoiceCallEvent>> {
                val harness = CoordinatorHarness(
                    serverEvents = Channel(Channel.UNLIMITED),
                    audioEvents = Channel(Channel.UNLIMITED),
                    teardowns = Channel(Channel.UNLIMITED),
                    coordinator = VoiceCallCoordinator(),
                )
                val realtime = RealtimeClient { _, _ ->
                    RealtimeConnection(
                        events = harness.serverEvents.receiveAsFlow(),
                        send = { command -> synchronized(harness.commandList) { harness.commandList += command } },
                        close = {
                            harness.closed.incrementAndGet()
                            harness.teardowns.trySend(Unit)
                        },
                    )
                }
                val audio = VoiceAudioClient(start = {
                    VoiceAudioSession(
                        capture = emptyFlow(),
                        events = harness.audioEvents.receiveAsFlow(),
                        enqueue = { _, _ -> harness.enqueues.incrementAndGet() },
                        flush = {
                            harness.flushes.incrementAndGet()
                            flushMilliseconds
                        },
                        setMuted = {},
                        stop = {
                            harness.stopped.incrementAndGet()
                            harness.teardowns.trySend(Unit)
                        },
                    )
                })
                val events = harness.coordinator.start(
                    apiKey = "test",
                    config = RealtimeSessionConfig(instructions = "", tools = emptyList()),
                    realtime = realtime,
                    audio = audio,
                )
                // Collects the call's events on their own coroutine, as the reducer's effect does.
                val channel = Channel<VoiceCallEvent>(Channel.UNLIMITED)
                harness.consumer = scope.launch {
                    try { events.collect { channel.send(it) } } finally { channel.close() }
                }
                return harness to channel
            }

            lateinit var scope: CoroutineScope
        }
    }

    /** Runs a coordinator test with test dependencies; the coordinator's own dispatcher is real. */
    private fun coordinatorTest(body: suspend CoroutineScope.() -> Unit): Unit = runBlocking {
        val values = DependencyValues.testing().update {
            this[Clock] = TestClock()
            this[ai.opencode.core.dependencies.DateClient] = ai.opencode.core.dependencies.DateClient { Instant.now() }
        }
        withContext(values.asContextElement()) {
            withTimeout(1.minutes) {
                kotlinx.coroutines.coroutineScope {
                    CoordinatorHarness.scope = this
                    body()
                    coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
                }
            }
        }
    }

    private suspend fun started(flushMilliseconds: Int = 0): Pair<CoordinatorHarness, ReceiveChannel<VoiceCallEvent>> {
        val (harness, events) = CoordinatorHarness.start(flushMilliseconds)
        // Replies and tool results wait for a confirmed session.
        harness.serverEvents.send(RealtimeServerEvent.SessionCreated(expiresAt = null))
        events.receive()
        return harness to events
    }

    private fun responseCreates(harness: CoordinatorHarness): Int = harness.commands.count { it == RealtimeCommand.ResponseCreate }

    /** The coordinator here runs on its own thread, so wait for it to go idle. */
    private suspend fun settle(harness: CoordinatorHarness) {
        repeat(20) { yield() }
        delay(50)
        harness.coordinator.serialized { }
    }

    @Test fun aDelegateResultNeverStartsAReplyWhileTheUserIsTalking() = coordinatorTest {
        val (harness, events) = started()
        harness.serverEvents.send(RealtimeServerEvent.SpeechStarted)
        assertEquals(VoiceCallEvent.UserSpeechStarted, events.receive())

        harness.coordinator.completeFunction(callID = "call_1", output = "[COMPLETE] Done.")
        harness.coordinator.completeFunction(callID = "call_2", output = "[COMPLETE] Also done.")
        assertEquals(2, harness.commands.count { it is RealtimeCommand.FunctionOutput })
        assertEquals("No reply over the user's voice", 0, responseCreates(harness))

        // The user finishes; server VAD answers the turn, covering both results.
        harness.serverEvents.send(RealtimeServerEvent.SpeechStopped)
        harness.serverEvents.send(RealtimeServerEvent.ResponseCreated(id = "response_1"))
        harness.serverEvents.send(RealtimeServerEvent.ResponseDone(id = "response_1"))
        settle(harness)
        assertEquals("The server's own reply covers the owed results", 0, responseCreates(harness))

        harness.serverEvents.close()
        harness.coordinator.end()
    }

    @Test fun aResultThatArrivesMidReplyIsQueuedBehindIt() = coordinatorTest {
        val (harness, events) = started()
        harness.serverEvents.send(RealtimeServerEvent.ResponseCreated(id = "response_1"))
        harness.serverEvents.send(RealtimeServerEvent.OutputAudio(responseID = "response_1", itemID = "item_1", pcm = byteArrayOf(0, 0)))
        harness.serverEvents.send(RealtimeServerEvent.ResponseDone(id = "response_1"))
        settle(harness)

        harness.coordinator.completeFunction(callID = "call_1", output = "[COMPLETE] Second result.")
        assertEquals("Doesn't cut into the reply still playing", 0, responseCreates(harness))
        harness.audioEvents.send(VoiceAudioEvent.playbackDrained)
        assertEquals(VoiceCallEvent.AssistantPlaybackFinished, events.receive())
        settle(harness)
        assertEquals("Spoken once the first reply finishes", 1, responseCreates(harness))

        harness.serverEvents.close()
        harness.coordinator.end()
    }

    @Test fun bargeInAfterGenerationEndedStillTruncatesWhatWasHeard() = coordinatorTest {
        val (harness, events) = started(flushMilliseconds = 1_200)
        harness.serverEvents.send(RealtimeServerEvent.ResponseCreated(id = "response_1"))
        harness.serverEvents.send(RealtimeServerEvent.OutputAudio(responseID = "response_1", itemID = "item_1", pcm = byteArrayOf(0, 0)))
        harness.serverEvents.send(RealtimeServerEvent.ResponseDone(id = "response_1"))
        harness.serverEvents.send(RealtimeServerEvent.SpeechStarted)
        assertEquals(VoiceCallEvent.UserSpeechStarted, events.receive())

        assertEquals("The speaker stops", 1, harness.flushes.get())
        assertTrue(harness.commands.contains(RealtimeCommand.Truncate(itemID = "item_1", audioEndMilliseconds = 1_200)))
        assertFalse("Nothing left to cancel", harness.commands.contains(RealtimeCommand.ResponseCancel))

        harness.serverEvents.close()
        harness.coordinator.end()
    }

    @Test fun bargeInFlushesTruncatesCancelsAndDropsLateAudio() = coordinatorTest {
        val (harness, events) = started(flushMilliseconds = 850)

        harness.serverEvents.send(RealtimeServerEvent.ResponseCreated(id = "response_1"))
        harness.serverEvents.send(RealtimeServerEvent.OutputAudio(responseID = "response_1", itemID = "item_1", pcm = byteArrayOf(0, 0)))
        harness.serverEvents.send(RealtimeServerEvent.SpeechStarted)
        assertEquals(VoiceCallEvent.UserSpeechStarted, events.receive())
        harness.serverEvents.send(RealtimeServerEvent.ResponseCreated(id = "response_2"))
        harness.serverEvents.send(RealtimeServerEvent.OutputAudio(responseID = "response_1", itemID = "item_1", pcm = byteArrayOf(0, 0)))
        settle(harness)

        assertEquals(1, harness.flushes.get())
        assertEquals(1, harness.enqueues.get())
        assertTrue(harness.commands.contains(RealtimeCommand.Truncate(itemID = "item_1", audioEndMilliseconds = 850)))
        assertTrue(harness.commands.contains(RealtimeCommand.ResponseCancel))

        harness.serverEvents.close()
        harness.coordinator.end()
    }

    @Test fun bargeInRacesStaySilent() = coordinatorTest {
        val (harness, events) = started()

        // The cancel lost the race with response.done: no event reaches the call.
        harness.serverEvents.send(
            RealtimeServerEvent.Error(
                RealtimeServerError(
                    message = "Cancellation failed: no active response found",
                    code = "response_cancel_not_active",
                    type = "invalid_request_error",
                ),
            ),
        )
        harness.serverEvents.send(RealtimeServerEvent.SpeechStarted)
        assertEquals(VoiceCallEvent.UserSpeechStarted, events.receive())
        harness.serverEvents.close()
        harness.coordinator.end()
    }

    @Test fun abandonedEventStreamHangsUpTheCall() = coordinatorTest {
        val (harness, events) = started()

        // The reducer effect that consumes the events is cancelled when the
        // chat is left; nobody calls `end`.
        harness.consumer.cancel()

        // Wait for both teardown callbacks: closing the socket and stopping audio.
        harness.teardowns.receive()
        harness.teardowns.receive()
        assertEquals(1, harness.closed.get())
        assertEquals(1, harness.stopped.get())

        // A later explicit end is a no-op, not a second teardown.
        harness.coordinator.end()
        assertEquals(1, harness.closed.get())
        assertEquals(1, harness.stopped.get())
        harness.serverEvents.close()
    }
}
