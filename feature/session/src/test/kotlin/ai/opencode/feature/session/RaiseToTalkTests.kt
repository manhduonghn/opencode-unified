package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.TestStore
import ai.opencode.feature.composer.DictationClient
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.feature.composer.DraftState
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.settle
import ai.opencode.feature.voice.LiveClient
import ai.opencode.feature.voice.LiveConnection
import ai.opencode.feature.voice.RealtimeClient
import ai.opencode.feature.voice.RealtimePreflightClient
import ai.opencode.feature.voice.RealtimeSessionConfig
import ai.opencode.feature.voice.VoiceAudioClient
import ai.opencode.feature.voice.VoiceCallClient
import ai.opencode.feature.voice.VoiceCallEvent
import ai.opencode.feature.voice.VoiceCallHost
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceServerChoice
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.feature.voice.VoiceWorkClient
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RaiseToTalkTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_ear", serverTitle = "Ear", projectID = "p", directory = "/Users/kit/project", updatedAt = SessionTestSupport.distantPast)

    private fun Harness.earState(voice: Boolean = true, apiKey: String? = "sk-test"): SessionFeature.State {
        sharedStore.reference(SharedKeys.voiceModeEnabled).set(voice)
        sharedStore.reference(SharedKeys.openAIAPIKey).set(apiKey)
        return state { SessionFeature.State.session(server = server, session = session, cachedItems = emptyList()) }
    }

    private fun callClient(
        start: suspend (String, RealtimeSessionConfig) -> Flow<VoiceCallEvent> = { _, _ -> emptyFlow() },
        setAtEar: suspend (Boolean) -> Unit = {},
        end: suspend () -> Unit = {},
    ) = VoiceCallClient(
        start = start,
        completeFunction = { _, _ -> },
        setMuted = {},
        setAtEar = setAtEar,
        updateInstructions = { _, _ -> },
        end = end,
    )

    private fun voice(action: VoiceFeature.Action): Action = Action.Voice(PresentationAction.Presented(action))

    private fun isVoice(match: (VoiceFeature.Action) -> Boolean): (Action) -> Boolean =
        { (it as? Action.Voice)?.action.let { p -> p is PresentationAction.Presented && match(p.action) } }

    private val anyVoice: (Action) -> Boolean = { it is Action.Voice }

    private fun DependencyValues.Builder.dictation(
        start: suspend (Int) -> kotlinx.coroutines.channels.ReceiveChannel<String>,
        stop: suspend (Int) -> Unit = {},
        cancel: suspend (Int) -> Unit = {},
    ) {
        this[DictationClient] = DictationClient(start = start, stop = stop, cancel = cancel)
    }

    @Test fun raisingStartsACallAtTheEarAndNeverEndsOne() = sessionTest { h ->
        val routes = LockIsolated(listOf<Boolean>())
        val store = h.store(h.earState()) {
            this[VoiceCallClient] = callClient(setAtEar = { routes.append(it) })
        }
        store.exhaustive = false

        store.send(Action.RaisedToEar)
        store.receive(Action.VoiceTapped::class)
        store.receive(Action.AtEarChanged::class)
        store.receive(anyVoice, "voice")
        assertNotNull(store.state.voice)
        assertEquals(true, store.state.voice?.isAtEar)

        // Raised again mid-call: still the same call.
        store.send(Action.RaisedToEar)
        assertNotNull(store.state.voice)

        // Lowered: the call stays and moves to the speaker.
        store.send(Action.AtEarChanged(false))
        store.receive(anyVoice, "voice")
        assertEquals(false, store.state.voice?.isAtEar)
        settle()
        h.skipInFlightEffects()
        assertEquals(false, routes.value.lastOrNull())
    }

    /**
     * Lifting the phone toward the face while dictating (held, locked, or
     * finalizing to send) starts no call, and the recording carries on.
     */
    @Test fun raisingWhileDictatingDoesNothingAndTheRecordingCarriesOn() = sessionTest { h ->
        val transcripts = Channel<String>(Channel.UNLIMITED)
        val cancels = LockIsolated(0)
        val store = h.store(h.earState()) {
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(0))
            // Its last words never land, so it stays finalizing.
            dictation(start = { transcripts }, stop = {}, cancel = { cancels.increment() })
        }
        store.exhaustive = false

        store.send(Action.micPressBegan)
        store.receive({ it == Action.dictationReady }, "dictationReady")
        store.send(Action.RaisedToEar)
        store.send(Action.AtEarChanged(true))
        assertNull(store.state.voice)
        assertEquals(DictationFeature.Phase.Recording, store.state.dictationPhase)

        transcripts.send("hello")
        store.receive({ (it as? Action.Dictation)?.action is DictationFeature.Action.Updated }, "dictationUpdated")
        assertEquals("hello", store.state.dictationHold?.text)

        // Locked, hands-free: still nothing.
        store.send(Action.micPressLocked)
        store.send(Action.RaisedToEar)
        assertNull(store.state.voice)
        assertEquals(DictationFeature.Phase.Recording, store.state.dictationPhase)

        // Let go to send, last words still finalizing: still nothing.
        store.send(Action.dictationSendTapped) { it.withDictationPhase(DictationFeature.Phase.Finishing(send = OpenCodeDelivery.Steer)) }
        store.send(Action.RaisedToEar)
        assertNull(store.state.voice)
        assertTrue(store.state.isFinishingDictation)

        h.skipInFlightEffects()
        assertEquals("The raise never cancelled the recording", 0, cancels.value)
    }

    /**
     * Every live phase blocks a raise (preparing too, before any words), as
     * does an earlier recording still finalizing to send; then the grace.
     */
    @Test fun dictationBlocksARaiseInEveryLivePhaseAndForTheGrace() = sessionTest { h ->
        var state = h.earState()
        val ended = Instant.ofEpochSecond(1_000)
        assertFalse(state.dictationBlocksRaise { ended })
        val phases = listOf(
            DictationFeature.Phase.Preparing,
            DictationFeature.Phase.Recording,
            DictationFeature.Phase.Finishing(send = null),
            DictationFeature.Phase.Finishing(send = OpenCodeDelivery.Steer),
        )
        for (phase in phases) {
            state = state.withDictationPhase(phase)
            assertTrue("$phase", state.dictationBlocksRaise { ended })
        }
        state = state.withDictationPhase(DictationFeature.Phase.Idle)
            .withSendingDictation(DictationFeature.Sending(recording = 0, draft = DraftState(), delivery = OpenCodeDelivery.Steer, sessionID = session.id))
        assertTrue(state.dictationBlocksRaise { ended })
        state = state.withSendingDictation(null).withDictationEndedAt(ended)
        val grace = (SessionFeature.State.raiseToTalkGrace * 1000).toLong()
        assertTrue(state.dictationBlocksRaise { ended.plusMillis(grace - 100) })
        assertFalse(state.dictationBlocksRaise { ended.plusMillis(grace) })
    }

    /**
     * Once a recording ends, a raise within the grace (lifting the phone to
     * read the words) still starts nothing; after it, the gesture works.
     */
    @Test fun raisingAfterDictationEndsStartsACallOnceTheGracePasses() = sessionTest { h ->
        val now = LockIsolated(Instant.ofEpochSecond(1_000))
        val transcripts = Channel<String>(Channel.UNLIMITED)
        val store = h.store(h.earState()) {
            this[DateClient] = DateClient { now.value }
            dictation(start = { transcripts }, stop = {
                transcripts.send("read this back")
                transcripts.close()
            })
        }
        store.exhaustive = false

        // Tapped dictation, stopped into the draft.
        store.send(Action.micTapped)
        store.receive({ it == Action.dictationReady }, "dictationReady")
        store.send(Action.micTapped)
        store.receive({ (it as? Action.Dictation)?.action is DictationFeature.Action.Updated }, "dictationUpdated")
        store.receive({ (it as? Action.Dictation)?.action is DictationFeature.Action.Stopped }, "dictationStopped")
        assertEquals(DictationFeature.Phase.Idle, store.state.dictationPhase)
        assertEquals("read this back", store.state.draft)
        assertEquals(now.value, store.state.dictationEndedAt)

        val grace = (SessionFeature.State.raiseToTalkGrace * 1000).toLong()
        now.update { it.plusMillis(grace - 500) }
        store.send(Action.RaisedToEar)
        assertNull(store.state.voice)

        now.update { it.plusMillis(500) }
        store.send(Action.RaisedToEar)
        store.receive(Action.VoiceTapped::class)
        store.receive(Action.AtEarChanged::class)
        store.receive(anyVoice, "voice")
        assertEquals(true, store.state.voice?.isAtEar)
        assertEquals("read this back", store.state.draft)
        h.skipInFlightEffects()
    }

    @Test fun withoutVoiceARaiseDoesNothing() = sessionTest { h ->
        val store = h.store(h.earState(voice = false))
        store.send(Action.RaisedToEar)
        assertNull(store.state.voice)
        store.finish()
    }

    /**
     * Stop's slot is the call button's: holding it mid-run starts a call
     * without stopping the run.
     */
    @Test fun holdingStopStartsACallWithoutStoppingTheRun() = sessionTest { h ->
        val interrupts = LockIsolated(0)
        val store = h.store(h.earState().copy(isExternallyActive = true)) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(interrupt = { _, _ -> interrupts.increment(); true })
        }
        store.exhaustive = false

        store.send(Action.StopLongPressed)
        store.receive(Action.VoiceTapped::class)
        assertEquals("ses_ear", store.state.voice?.target?.sessionID)
        assertEquals("The call knows the session is working", true, store.state.voice?.replyWatch?.activeSessionIDs?.contains("ses_ear"))
        assertTrue(store.state.isRunning)
        assertFalse(store.state.isInterrupting)
        // Held again mid-call: Stop's hold only ever starts one.
        store.send(Action.StopLongPressed)
        assertNotNull(store.state.voice)
        h.skipInFlightEffects()
        assertEquals(0, interrupts.value)
    }

    @Test fun tappingStopStillStops() = sessionTest { h ->
        val interrupts = LockIsolated(0)
        val store = h.store(h.earState().copy(isExternallyActive = true)) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(interrupt = { _, _ -> interrupts.increment(); true })
            this[Clock] = TestClock()
        }
        store.exhaustive = false

        store.send(Action.InterruptTapped) { it.copy(isInterrupting = true) }
        store.receive(Action.InterruptAcknowledged::class)
        assertEquals(1, interrupts.value)
        assertNull(store.state.voice)
        h.skipInFlightEffects()
    }

    @Test fun holdingStopWithoutVoiceDoesNothing() = sessionTest { h ->
        val store = h.store(h.earState(voice = false, apiKey = null).copy(isExternallyActive = true))
        store.send(Action.StopLongPressed)
        assertNull(store.state.voice)
        assertFalse(store.state.isInterrupting)
        store.finish()

        // Nor when Send holds the place, or nothing is running.
        val typed = h.earState().copy(isExternallyActive = true).withDraft("steer this")
        val typing = h.store(typed)
        typing.send(Action.StopLongPressed)
        assertNull(typing.state.voice)
        typing.finish()
        val idle = h.store(h.earState())
        idle.send(Action.StopLongPressed)
        assertNull(idle.state.voice)
        idle.finish()
    }

    // region +Routing

    /** The `SessionFeature` half: `.raisedToEar` → `.start` carries `isAtEar == true` in the start config. */
    @Test fun setAtEarBeforeRunningIsNotLost() = sessionTest { h ->
        val startedAtEar = LockIsolated(listOf<Boolean>())
        val store = h.store(h.earState()) {
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(0))
            this[Clock] = ImmediateClock()
            // Android: every call start mints its notification identity.
            this[ai.opencode.core.dependencies.UuidClient] = ai.opencode.core.dependencies.UuidClient.incrementing()
            this[RealtimePreflightClient] = RealtimePreflightClient(verify = { _, _ -> })
            this[VoiceCallClient] = callClient(start = { _, config ->
                startedAtEar.append(config.isAtEar)
                flow { awaitCancellation() }
            })
        }
        store.exhaustive = false

        store.send(Action.RaisedToEar)
        store.receive(Action.VoiceTapped::class)
        store.receive(Action.AtEarChanged::class)
        store.send(voice(VoiceFeature.Action.Start))
        settle()
        h.skipInFlightEffects()
        assertEquals(listOf(true), startedAtEar.value)
    }

    @Test fun failedPreflightFollowedByManualCallStartsOnSpeaker() = sessionTest { h ->
        h.sharedStore.reference(VoiceSharedKeys.voiceServerChoice).set(VoiceServerChoice.Phone)
        val host = VoiceCallHost()
        val shouldFailPreflight = LockIsolated(true)
        val startedAtEar = LockIsolated(listOf<Boolean>())
        val store = h.store(h.earState()) {
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(0))
            this[Clock] = ImmediateClock()
            this[ai.opencode.core.dependencies.UuidClient] = ai.opencode.core.dependencies.UuidClient.incrementing()
            this[RealtimePreflightClient] = RealtimePreflightClient(verify = { _, _ ->
                if (shouldFailPreflight.value) throw Exception("Preflight failed")
            })
            this[VoiceCallClient] = callClient(
                setAtEar = { host.setAtEar(it) },
                end = { host.end() },
                start = { apiKey, config ->
                    startedAtEar.append(config.isAtEar)
                    host.start(
                        apiKey = apiKey,
                        config = config,
                        realtime = RealtimeClient.testValue,
                        live = LiveClient { _, _ -> LiveConnection(events = flow { awaitCancellation() }, send = {}, close = {}) },
                        audio = VoiceAudioClient(
                            start = { VoiceAudioClient.testValue.start() },
                            startAtEar = { atEar ->
                                startedAtEar.append(atEar)
                                VoiceAudioClient.testValue.start()
                            },
                        ),
                    )
                },
            )
        }
        store.exhaustive = false

        // 1. Raise-start fails during preflight before `voiceCall.start` is reached.
        store.send(Action.RaisedToEar)
        store.receive(Action.VoiceTapped::class)
        store.receive(Action.AtEarChanged::class)
        store.send(voice(VoiceFeature.Action.Start))
        store.receive(isVoice { it is VoiceFeature.Action.CallEnded }, "voice.callEnded")
        assertEquals(VoiceFeature.State.Phase.Failed("Preflight failed"), store.state.voice?.phase)
        assertTrue(startedAtEar.value.isEmpty())

        // Dismiss the failed card and start a manual call via the call button.
        store.send(voice(VoiceFeature.Action.EndTapped))
        store.receive(isVoice { it == VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended) }, "voice.delegate.ended")
        assertNull(store.state.voice)

        shouldFailPreflight.setValue(false)
        store.send(Action.VoiceTapped)
        assertEquals(false, store.state.voice?.isAtEar)
        store.send(voice(VoiceFeature.Action.Start))
        eventuallyTrue { startedAtEar.value.size >= 2 }
        h.skipInFlightEffects()
        // Both config.isAtEar and the audio engine's startAtEar must be `false` (speaker).
        assertEquals(listOf(false, false), startedAtEar.value)
        host.end()
    }

    @Test fun loweringDuringPreflightStartsOnSpeakerAndConnectedReappliesCurrentState() = sessionTest { h ->
        h.sharedStore.reference(VoiceSharedKeys.voiceServerChoice).set(VoiceServerChoice.Phone)
        val preflight = CompletableDeferred<Unit>()
        val startedConfigs = LockIsolated(listOf<Boolean>())
        val setAtEarCalls = LockIsolated(listOf<Boolean>())
        val callEvents = Channel<VoiceCallEvent>(Channel.UNLIMITED)
        val store = h.store(h.earState()) {
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(0))
            this[Clock] = ImmediateClock()
            // Android: every call start mints its notification identity.
            this[ai.opencode.core.dependencies.UuidClient] = ai.opencode.core.dependencies.UuidClient.incrementing()
            this[RealtimePreflightClient] = RealtimePreflightClient(verify = { _, _ -> preflight.await() })
            this[VoiceCallClient] = callClient(
                setAtEar = { setAtEarCalls.append(it) },
                start = { _, config ->
                    startedConfigs.append(config.isAtEar)
                    callEvents.receiveAsFlow()
                },
            )
        }
        store.exhaustive = false

        // Raise to ear starts the call, then user lowers during preflight before `voiceCall.start`.
        store.send(Action.RaisedToEar)
        store.receive(Action.VoiceTapped::class)
        store.receive(Action.AtEarChanged::class)
        store.send(voice(VoiceFeature.Action.Start))
        store.send(Action.AtEarChanged(false))
        store.receive(isVoice { it is VoiceFeature.Action.AtEarChanged }, "voice.atEarChanged")
        assertEquals(false, store.state.voice?.isAtEar)

        preflight.complete(Unit)
        settle()
        assertEquals(listOf(false), startedConfigs.value)

        // `.connected` re-applies the current `state.isAtEar` (`false`), not only when `true`.
        setAtEarCalls.setValue(emptyList())
        callEvents.send(VoiceCallEvent.Connected)
        store.receive(isVoice { it is VoiceFeature.Action.CallEvent }, "voice.callEvent")
        callEvents.close()
        settle()
        h.skipInFlightEffects()
        assertEquals(listOf(false), setAtEarCalls.value)
    }

    // endregion

    /** Waits (real time, bounded) for work on the call host's own dispatcher. */
    private suspend fun eventuallyTrue(condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            kotlinx.coroutines.delay(10)
        }
    }
}
