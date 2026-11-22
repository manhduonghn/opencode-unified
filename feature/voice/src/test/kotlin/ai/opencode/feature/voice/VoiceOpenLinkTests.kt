package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.voice.VoiceFeatureTestSupport.EventStream
import ai.opencode.feature.voice.VoiceFeatureTestSupport.Harness
import ai.opencode.feature.voice.VoiceFeatureTestSupport.LockIsolated
import ai.opencode.feature.voice.VoiceFeatureTestSupport.append
import ai.opencode.feature.voice.VoiceFeatureTestSupport.copy
import ai.opencode.feature.voice.VoiceFeatureTestSupport.live
import ai.opencode.feature.voice.VoiceFeatureTestSupport.receive
import ai.opencode.feature.voice.VoiceFeatureTestSupport.receiveEnded
import ai.opencode.feature.voice.VoiceFeatureTestSupport.server
import ai.opencode.feature.voice.VoiceFeatureTestSupport.session
import ai.opencode.feature.voice.VoiceFeatureTestSupport.setAPIKey
import ai.opencode.feature.voice.VoiceFeatureTestSupport.settle
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceCall
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceTest
import ai.opencode.feature.voice.VoiceOpenLinkFeature.Action
import ai.opencode.feature.voice.VoiceOpenLinkFeature.Failure
import ai.opencode.feature.voice.VoiceOpenLinkFeature.Link
import ai.opencode.feature.voice.VoiceOpenLinkFeature.Refusal
import ai.opencode.feature.voice.VoiceOpenLinkFeature.State
import ai.opencode.server.clienttools.ClientTool
import ai.opencode.server.clienttools.ClientToolCall
import ai.opencode.server.clienttools.ClientToolResult
import ai.opencode.server.clienttools.ClientToolsClient
import ai.opencode.server.clienttools.JsonRpcID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/** Its feature tests (MARK: Feature) and its `VoiceFeatureTests` extension. */
class VoiceOpenLinkTests {
    @get:Rule val isolation = TestIsolationRule()

    private val connection = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val second = UUID.fromString("00000000-0000-0000-0000-000000000013")
    private val uber = "https://m.uber.com/ul/?action=setPickup&pickup=my_location"
    private val call = ClientToolCall(
        connection = connection, id = JsonRpcID.Number(1), tool = ClientTool.voiceOpenURL,
        sessionID = "ses_voice", url = uber, title = "Uber",
    )

    private fun makeStore(
        scope: CoroutineScope,
        clock: TestClock,
        replies: LockIsolated<List<ClientToolResult>>,
        haptics: LockIsolated<List<Haptic>>,
        opened: LockIsolated<List<String>> = LockIsolated(listOf()),
        opens: Boolean = true,
    ): TestStore<State, Action> = TestStore(State(), VoiceOpenLinkFeature(), scope) {
        this[Clock] = clock
        this[HapticsClient] = HapticsClient(play = { haptics.append(it) })
        this[VoiceOpenURLClient] = VoiceOpenURLClient { url ->
            opened.append(url)
            opens
        }
        this[ClientToolsClient] = ClientToolsClient(
            connect = { emptyFlow() },
            reply = { _, result ->
                replies.append(result)
                true
            },
        )
    }

    /** Effects run here, so a test's leftover timer is cancelled rather than waited on. */
    private fun effects(scope: CoroutineScope): CoroutineScope = CoroutineScope(scope.coroutineContext + Job())

    // region Feature

    @Test fun openHandsTheLinkToTheSystemAndSaysItOpened() = runTest {
        val clock = TestClock()
        val replies = LockIsolated(listOf<ClientToolResult>())
        val haptics = LockIsolated(listOf<Haptic>())
        val opened = LockIsolated(listOf<String>())
        val scope = effects(this)
        val store = makeStore(scope, clock, replies, haptics, opened)

        store.send(Action.Requested(call)) { it.copy(pending = call) }
        store.send(Action.OpenTapped) { it.copy(pending = null) }
        // The minute's timer went with the card.
        clock.advance(60.seconds)
        store.finish()
        settle()

        assertEquals(listOf(Haptic.tap), haptics.value)
        assertEquals(listOf(uber), opened.value)
        assertEquals(
            listOf(ClientToolResult(listOf(ClientToolResult.Content.Text("Opened Uber (m.uber.com) on the user's phone.")))),
            replies.value,
        )
        scope.cancel()
    }

    @Test fun aLinkNothingOpensSaysSo() = runTest {
        val clock = TestClock()
        val replies = LockIsolated(listOf<ClientToolResult>())
        val opened = LockIsolated(listOf<String>())
        val scope = effects(this)
        val store = makeStore(scope, clock, replies, LockIsolated(listOf()), opened, opens = false)
        val app = call.copy(url = "priority-pass://lounges", title = null)

        store.send(Action.Requested(app)) { it.copy(pending = app) }
        store.send(Action.OpenTapped) { it.copy(pending = null) }
        store.finish()
        settle()

        assertEquals(listOf("priority-pass://lounges"), opened.value)
        assertEquals(listOf(ClientToolResult.failure(Refusal.failed)), replies.value)
        scope.cancel()
    }

    @Test fun notNowRefusesWithoutOpening() = runTest {
        val clock = TestClock()
        val replies = LockIsolated(listOf<ClientToolResult>())
        val haptics = LockIsolated(listOf<Haptic>())
        val opened = LockIsolated(listOf<String>())
        val scope = effects(this)
        val store = makeStore(scope, clock, replies, haptics, opened)

        store.send(Action.Requested(call)) { it.copy(pending = call) }
        store.send(Action.NotNowTapped) { it.copy(pending = null) }
        clock.advance(60.seconds)
        store.finish()
        settle()

        assertEquals(listOf(Haptic.cancel), haptics.value)
        assertTrue(opened.value.isEmpty())
        assertEquals(listOf(ClientToolResult.failure(Refusal.declined)), replies.value)
        scope.cancel()
    }

    @Test fun noAnswerWithinAMinuteTimesOut() = runTest {
        val clock = TestClock()
        val replies = LockIsolated(listOf<ClientToolResult>())
        val haptics = LockIsolated(listOf<Haptic>())
        val opened = LockIsolated(listOf<String>())
        val scope = effects(this)
        val store = makeStore(scope, clock, replies, haptics, opened)

        store.send(Action.Requested(call)) { it.copy(pending = call) }
        clock.advance(59.seconds)
        assertTrue(replies.value.isEmpty())
        clock.advance(1.seconds)
        store.receive(Action.TimedOut) { it.copy(pending = null) }
        // A late tap on a card that's gone opens nothing.
        store.send(Action.OpenTapped)
        store.finish()
        settle()

        assertTrue(haptics.value.isEmpty())
        assertTrue(opened.value.isEmpty())
        assertEquals(listOf(ClientToolResult.failure(Refusal.timedOut)), replies.value)
        scope.cancel()
    }

    @Test fun theCallEndingRefusesTheLink() = runTest {
        val clock = TestClock()
        val replies = LockIsolated(listOf<ClientToolResult>())
        val scope = effects(this)
        val store = makeStore(scope, clock, replies, LockIsolated(listOf()))

        store.send(Action.Requested(call)) { it.copy(pending = call) }
        store.send(Action.CallEnded) { it.copy(pending = null) }
        clock.advance(60.seconds)
        store.finish()
        settle()

        assertEquals(listOf(ClientToolResult.failure(Refusal.callEnded)), replies.value)
        scope.cancel()
    }

    @Test fun theSocketClosingTakesTheCardWithoutAReply() = runTest {
        val clock = TestClock()
        val replies = LockIsolated(listOf<ClientToolResult>())
        val scope = effects(this)
        val store = makeStore(scope, clock, replies, LockIsolated(listOf()))

        store.send(Action.Requested(call)) { it.copy(pending = call) }
        // Another socket closing leaves it.
        store.send(Action.ConnectionClosed(second))
        store.send(Action.ConnectionClosed(connection)) { it.copy(pending = null) }
        clock.advance(60.seconds)
        store.finish()

        assertTrue(replies.value.isEmpty())
        scope.cancel()
    }

    @Test fun aSecondRequestOrABadLinkIsRefusedWithoutACard() = runTest {
        val clock = TestClock()
        val replies = LockIsolated(listOf<ClientToolResult>())
        val scope = effects(this)
        val store = makeStore(scope, clock, replies, LockIsolated(listOf()))
        val bad = call.copy(id = JsonRpcID.Number(3), url = "javascript:alert(1)")
        store.send(Action.Requested(bad))
        settle()

        val next = call.copy(id = JsonRpcID.Number(2))
        store.send(Action.Requested(call)) { it.copy(pending = call) }
        store.send(Action.Requested(next))
        settle()
        store.send(Action.CallEnded) { it.copy(pending = null) }
        store.finish()
        settle()

        assertEquals(
            listOf(
                ClientToolResult.failure(Refusal.scheme("javascript")),
                ClientToolResult.failure(Refusal.busy),
                ClientToolResult.failure(Refusal.callEnded),
            ),
            replies.value,
        )
        scope.cancel()
    }

    @Test fun onlyHttpsAndAppLinksAreOpened() {
        fun check(url: String?): Link =
            VoiceOpenLinkFeature.link(ClientToolCall(connection = connection, id = JsonRpcID.Number(1), tool = ClientTool.voiceOpenURL, url = url))

        assertEquals(Link.Valid(URI("https://m.uber.com/ul/?action=setPickup")), check("https://m.uber.com/ul/?action=setPickup"))
        assertEquals(Link.Valid(URI("HTTPS://www.avianca.com/en/")), check("HTTPS://www.avianca.com/en/"))
        assertEquals(Link.Valid(URI("uber://?action=setPickup")), check("uber://?action=setPickup"))
        assertEquals(Link.Valid(URI("priority-pass://lounges")), check("priority-pass://lounges"))
        // Android adds its own ways into other apps' private surfaces: intent:, content:, android-app:.
        val refused = listOf(
            "javascript", "file", "data", "tel", "sms", "facetime", "mailto", "http", "shortcuts", "opencode", "itms-services",
            "intent", "content", "android-app",
        )
        for (scheme in refused) {
            assertEquals(scheme, Link.Invalid(Failure(Refusal.scheme(scheme))), check("$scheme://x/y"))
        }
        assertEquals(Link.Invalid(Failure(Refusal.scheme("tel"))), check("TEL:+15551234567"))
        assertEquals(Link.Invalid(Failure(Refusal.missing)), check(null))
        assertEquals(Link.Invalid(Failure(Refusal.invalid)), check("m.uber.com/ul"))
        assertEquals(Link.Invalid(Failure(Refusal.invalid)), check("https:///nohost"))
        assertEquals(Link.Invalid(Failure(Refusal.invalid)), check("not a link"))
    }

    @Test fun theCardNamesWhatItOpensAndWhere() {
        assertEquals("Open Uber?", VoiceOpenLinkFeature.cardTitle("Uber"))
        assertEquals("Open Uber?", VoiceOpenLinkFeature.cardTitle("Open Uber?"))
        assertEquals("Open Link?", VoiceOpenLinkFeature.cardTitle(null))
        assertEquals("m.uber.com", VoiceOpenLinkFeature.displayHost(URI(uber)))
        assertEquals("lounges", VoiceOpenLinkFeature.displayHost(URI("priority-pass://lounges")))
        assertEquals("uber", VoiceOpenLinkFeature.displayHost(URI("uber:?action=x")))
    }

    // endregion

    // region VoiceFeatureTests extension

    private lateinit var h: Harness

    private val linkCall = ClientToolCall(
        connection = UUID.fromString("00000000-0000-0000-0000-000000000004"), id = JsonRpcID.Number(1),
        tool = ClientTool.voiceOpenURL, sessionID = "ses_voice", url = "https://m.uber.com/ul/", title = "Uber",
    )

    /** A live call (Android asserts what each step changes by hand). */
    private suspend fun startLiveCall(
        configure: DependencyValues.Builder.() -> Unit,
    ): TestStore<VoiceFeature.State, VoiceFeature.Action> {
        val events = EventStream()
        val store = h.store(h.state { VoiceFeature.State(server, session) }) {
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            this[Clock] = ImmediateClock()
            voiceCall { copy(start = { _, _ -> events.events }, completeFunction = { _, _ -> }) }
            configure()
        }
        store.exhaustive = false
        store.setAPIKey("sk-test")
        store.send(VoiceFeature.Action.Start)
        events.yield(VoiceCallEvent.Connected)
        store.receive(VoiceFeature.Action.CallEvent::class) { it.live() }
        return store
    }

    @Test fun endingTheCallRefusesItsLinkAndReleasesTheOrb() = voiceTest { harness ->
        h = harness
        val replies = LockIsolated(listOf<ClientToolResult>())
        val store = startLiveCall {
            this[Clock] = TestClock()
            this[ClientToolsClient] = ClientToolsClient(
                connect = { emptyFlow() },
                reply = { _, result ->
                    replies.append(result)
                    true
                },
            )
        }
        store.send(VoiceFeature.Action.OpenLink(Action.Requested(linkCall)))
        assertEquals(linkCall, store.state.openLink.pending)
        assertTrue(store.state.floatingStage.isHeld)

        store.send(VoiceFeature.Action.EndTapped)
        assertNull(store.state.openLink.pending)
        assertFalse(store.state.floatingStage.isHeld)
        store.receiveEnded()
        settle()
        assertEquals(listOf(ClientToolResult.failure(Refusal.callEnded)), replies.value)
    }

    // endregion
}
