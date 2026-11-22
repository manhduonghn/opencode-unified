package ai.opencode.feature.system.liveactivity

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.system.liveactivity.VoiceCallActivityAttributes.ContentState.Phase
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * The intents' half (`intentsSendTheirCommand`) is [VoiceLiveActivityFrameworkTests].
 *
 * The activity runs on the main dispatcher, here [Dispatchers.Unconfined].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceLiveActivityTests {
    @get:Rule val isolation = TestIsolationRule()

    @Before fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)
    @After fun tearDown() = Dispatchers.resetMain()

    private val t0: Instant = Instant.ofEpochSecond(1_000)
    private val attributes = VoiceCallActivityAttributes(serverID = "srv", sessionID = "ses_voice")
    private val denied = LiveUpdateSystem.RequestError("NotificationsDisabled")

    private fun content(phase: Phase, isMuted: Boolean = false, startedAt: Instant? = null) =
        VoiceCallActivityAttributes.ContentState(phase, isMuted, startedAt)

    private class FakeActivities {
        val showing = mutableSetOf<String>()
        /** States that differ from what `showing` implies. */
        val states = mutableMapOf<String, LiveVoiceActivity.State>()
        var isEnabled = true
        var refusal: Exception? = null
        val requested = mutableListOf<String>()
        val updates = mutableListOf<Pair<String, VoiceCallActivityAttributes.ContentState>>()
        val ended = mutableListOf<String>()
        val recorded = mutableListOf<String>()
        val reports = mutableListOf<LiveActivityStatus>()
        val enablement = Channel<Boolean>(Channel.UNLIMITED)
        val foreground = Channel<Unit>(Channel.UNLIMITED)
        val stateUpdates = mutableMapOf<String, Channel<LiveVoiceActivity.State>>()

        fun system(clock: Clock) = LiveVoiceActivity.System(
            areActivitiesEnabled = { isEnabled },
            state = { id -> states[id] ?: if (id in showing) LiveVoiceActivity.State.Active else null },
            request = { _, _, _ ->
                refusal?.let { throw it }
                val id = "activity-${requested.size + 1}"
                requested += id
                showing += id
                id
            },
            update = { id, content, _ -> updates += id to content },
            end = { id, _ ->
                ended += id
                showing -= id
            },
            endAll = {
                ended += showing.sorted()
                showing.clear()
            },
            clock = clock,
            enablementUpdates = { enablement.receiveAsFlow() },
            foregroundEntries = { foreground.receiveAsFlow() },
            stateUpdates = { id -> Channel<LiveVoiceActivity.State>(Channel.UNLIMITED).also { stateUpdates[id] = it }.receiveAsFlow() },
            record = { name, detail -> recorded += "$name $detail" },
            report = { reports += it },
        )

        fun recorded(name: String) = recorded.filter { it.startsWith("$name ") }
    }

    private suspend fun eventually(condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            delay(5)
        }
    }

    // MARK: Commands

    @Test fun commandsReachEveryListener() = runBlocking {
        val commands = LiveActivityCommands<VoiceCallCommand>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { commands.stream().take(2).toList() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { commands.stream().take(2).toList() }
        // Each stream registers its listener once collected.
        repeat(5) { yield() }
        commands.send(VoiceCallCommand.ToggleMute)
        commands.send(VoiceCallCommand.End)
        assertEquals(listOf(VoiceCallCommand.ToggleMute, VoiceCallCommand.End), first.await())
        assertEquals(listOf(VoiceCallCommand.ToggleMute, VoiceCallCommand.End), second.await())
    }

    @Test fun aCommandNobodyReceivesEndsTheActivities() = runBlocking {
        val ends = AtomicInteger()
        val commands = LiveActivityCommands<VoiceCallCommand> { ends.incrementAndGet() }
        assertFalse(commands.send(VoiceCallCommand.End))
        commands.perform(VoiceCallCommand.ToggleMute)
        assertEquals(1, ends.get())

        val received = mutableListOf<VoiceCallCommand>()
        val listener = launch { commands.stream().collect { received += it } }
        while (!commands.send(VoiceCallCommand.ToggleMute)) yield()
        commands.perform(VoiceCallCommand.End)
        assertEquals(1, ends.get())

        // A listener that went away (Home left mid-call) no longer counts.
        listener.cancel()
        listener.join()
        commands.perform(VoiceCallCommand.End)
        assertEquals(2, ends.get())
    }

    // MARK: The activity

    @Test fun theHeartbeatStopsWithoutACall() = runBlocking {
        val fake = FakeActivities()
        val activity = LiveVoiceActivity(fake.system(TestClock()))
        activity.enqueue(LiveVoiceActivity.Request.Update(content(Phase.Live, startedAt = t0), attributes))
        assertTrue(activity.isHeartbeating)
        activity.enqueue(LiveVoiceActivity.Request.Update(null, null))
        assertFalse(activity.isHeartbeating)

        // Swiped away mid-call: the next beat finds nothing to keep fresh.
        activity.enqueue(LiveVoiceActivity.Request.Update(content(Phase.Live, startedAt = t0), attributes))
        assertTrue(activity.isHeartbeating)
        fake.showing.clear()
        val updates = fake.updates.size
        activity.enqueue(LiveVoiceActivity.Request.Refresh)
        assertFalse(activity.isHeartbeating)
        assertEquals(updates, fake.updates.size)
    }

    @Test fun aSwipedAwayCallStaysHiddenButARejoinStartsAfresh() = runBlocking {
        val fake = FakeActivities()
        val clock = TestClock()
        val activity = LiveVoiceActivity(fake.system(clock))

        // Swiped away during the call: it doesn't come back for this call.
        activity.enqueue(LiveVoiceActivity.Request.Update(content(Phase.Live, startedAt = t0), attributes))
        fake.showing.clear()
        activity.enqueue(LiveVoiceActivity.Request.Update(content(Phase.Live, isMuted = true, startedAt = t0), attributes))
        assertEquals(listOf("activity-1"), fake.requested)

        // Its end has nothing to show “Call ended” on.
        activity.enqueue(LiveVoiceActivity.Request.Update(content(Phase.Ended, startedAt = t0), null))
        assertFalse(fake.updates.any { it.second.phase == Phase.Ended })

        // The rejoined call gets its own.
        activity.enqueue(LiveVoiceActivity.Request.Update(content(Phase.Connecting), attributes))
        assertEquals(listOf("activity-1", "activity-2"), fake.requested)

        // “Call ended” swiped away during its linger, then a rejoin.
        activity.enqueue(LiveVoiceActivity.Request.Update(content(Phase.Ended, startedAt = t0), null))
        assertEquals("activity-2", fake.updates.last().first)
        assertEquals(Phase.Ended, fake.updates.last().second.phase)
        fake.showing.clear()
        activity.enqueue(LiveVoiceActivity.Request.Update(content(Phase.Connecting), attributes))
        assertEquals(listOf("activity-1", "activity-2", "activity-3"), fake.requested)
        assertTrue(activity.isHeartbeating)

        // The cancelled linger never ends the rejoined call's activity.
        clock.advance(LiveVoiceActivity.ENDED_LINGER)
        activity.enqueue(LiveVoiceActivity.Request.Refresh)
        assertTrue(fake.ended.isEmpty())
        assertEquals(setOf("activity-3"), fake.showing)
        activity.enqueue(LiveVoiceActivity.Request.Update(null, null))
    }

    // MARK: Background intent

    @Test fun theCallTakesOverABackgroundIntentsActivity() = runBlocking {
        val fake = FakeActivities()
        val activity = LiveVoiceActivity(fake.system(TestClock()))
        assertTrue(activity.prestart(attributes))
        assertEquals(listOf("activity-1"), fake.requested)

        val started = async(start = CoroutineStart.UNDISPATCHED) { activity.awaitCall(8.seconds) }
        activity.enqueue(LiveVoiceActivity.Request.Update(content(Phase.Connecting), attributes))
        assertTrue(started.await())
        // The same activity, never a second request from the background.
        assertEquals(listOf("activity-1"), fake.requested)
        assertEquals("activity-1", fake.updates.last().first)
        activity.enqueue(LiveVoiceActivity.Request.Update(null, null))
    }

    @Test fun aCallThatNeverStartsEndsTheIntentsActivity() = runBlocking {
        val fake = FakeActivities()
        val clock = TestClock()
        val activity = LiveVoiceActivity(fake.system(clock))
        assertTrue(activity.prestart(attributes))

        val started = async(start = CoroutineStart.UNDISPATCHED) { activity.awaitCall(8.seconds) }
        // Lets the timeout's sleep start before the clock moves.
        repeat(20) { yield() }
        clock.advance(8.seconds)
        assertFalse(started.await())
        assertEquals(listOf("activity-1"), fake.ended)
        assertTrue(fake.showing.isEmpty())
    }

    @Test fun aRefusedPrestartLeavesNothingWaiting() = runBlocking {
        val fake = FakeActivities()
        fake.refusal = denied
        val activity = LiveVoiceActivity(fake.system(TestClock()))
        assertFalse(activity.prestart(attributes))
        assertFalse(activity.awaitCall(8.seconds))
    }

    @Test fun aCallAlreadyShowingCountsAsStarted() = runBlocking {
        val fake = FakeActivities()
        val activity = LiveVoiceActivity(fake.system(TestClock()))
        activity.enqueue(LiveVoiceActivity.Request.Update(content(Phase.Live, startedAt = t0), attributes))
        assertTrue(activity.prestart(attributes))
        assertTrue(activity.awaitCall(8.seconds))
        assertEquals(listOf("activity-1"), fake.requested)
        activity.enqueue(LiveVoiceActivity.Request.Update(null, null))
    }

    // MARK: Settings and diagnostics

    @Test fun aCallAsksEvenWhenSettingsReadsOffAndRecordsWhy() = runBlocking {
        val fake = FakeActivities()
        fake.isEnabled = false
        fake.refusal = denied
        val activity = LiveVoiceActivity(fake.system(TestClock()))
        assertEquals(listOf("live_activity_enablement off"), fake.recorded)

        activity.enqueue(LiveVoiceActivity.Request.Update(content(Phase.Connecting), attributes))
        assertEquals(listOf("live_activity_failed enabled=false error=NotificationsDisabled"), fake.recorded("live_activity_failed"))
        assertEquals(LiveActivityStatus(isEnabled = false, last = "Couldn’t start: NotificationsDisabled"), fake.reports.last())
        assertEquals("Off in Settings · Couldn’t start: NotificationsDisabled", fake.reports.last().summary)
        assertFalse(activity.isHeartbeating)

        // The call's updates don't ask again while nothing changed.
        activity.enqueue(LiveVoiceActivity.Request.Update(content(Phase.Live, startedAt = t0), attributes))
        activity.enqueue(LiveVoiceActivity.Request.Update(content(Phase.Live, isMuted = true, startedAt = t0), attributes))
        assertEquals(1, fake.recorded("live_activity_failed").size)

        // Turned on in Settings mid-call: the call's activity starts.
        fake.isEnabled = true
        fake.refusal = null
        activity.enqueue(LiveVoiceActivity.Request.EnablementChanged(true))
        assertEquals(listOf("activity-1"), fake.requested)
        assertEquals(listOf("live_activity_retry enabled"), fake.recorded("live_activity_retry"))
        assertEquals(listOf("live_activity_started id=activity-1 enabled=true phase=live"), fake.recorded("live_activity_started"))
        assertEquals(LiveActivityStatus(isEnabled = true, last = "Started"), fake.reports.last())
        assertTrue(activity.isHeartbeating)
        activity.enqueue(LiveVoiceActivity.Request.Update(null, null))
        assertEquals(listOf("live_activity_ended id=activity-1"), fake.recorded("live_activity_ended"))
    }

    @Test fun anUpdateAsksAgainOnceSettingsChanged() = runBlocking {
        val fake = FakeActivities()
        fake.isEnabled = false
        fake.refusal = denied
        val activity = LiveVoiceActivity(fake.system(TestClock()))
        activity.enqueue(LiveVoiceActivity.Request.Update(content(Phase.Connecting), attributes))

        // No enablement update arrived (the app was in the background), but Settings now allows it.
        fake.isEnabled = true
        fake.refusal = null
        activity.enqueue(LiveVoiceActivity.Request.Update(content(Phase.Live, startedAt = t0), attributes))
        assertEquals(listOf("activity-1"), fake.requested)
        activity.enqueue(LiveVoiceActivity.Request.Update(null, null))
    }

    @Test fun returningToTheForegroundRetriesAFailedStart() = runBlocking {
        val fake = FakeActivities()
        // Refused while the app was away.
        fake.refusal = LiveUpdateSystem.RequestError("PermissionDenied")
        val activity = LiveVoiceActivity(fake.system(TestClock()))

        activity.enqueue(LiveVoiceActivity.Request.Update(content(Phase.Connecting), attributes))
        assertEquals(listOf("live_activity_failed enabled=true error=PermissionDenied"), fake.recorded("live_activity_failed"))

        fake.refusal = null
        fake.foreground.send(Unit)
        eventually { fake.requested.isNotEmpty() }
        assertEquals(listOf("activity-1"), fake.requested)
        assertEquals(listOf("live_activity_retry foreground"), fake.recorded("live_activity_retry"))

        // Once it shows, coming back again doesn't start another.
        fake.foreground.send(Unit)
        activity.enqueue(LiveVoiceActivity.Request.Refresh)
        assertEquals(listOf("activity-1"), fake.requested)
        activity.enqueue(LiveVoiceActivity.Request.Update(null, null))
    }

    @Test fun settingsChangesArriveFromTheSystem() = runBlocking {
        val fake = FakeActivities()
        fake.isEnabled = false
        fake.refusal = denied
        val activity = LiveVoiceActivity(fake.system(TestClock()))
        activity.enqueue(LiveVoiceActivity.Request.Update(content(Phase.Connecting), attributes))

        fake.isEnabled = true
        fake.refusal = null
        fake.enablement.send(true)
        eventually { fake.requested.isNotEmpty() }
        assertEquals(listOf("activity-1"), fake.requested)
        assertEquals(listOf("live_activity_enablement off", "live_activity_enablement on"), fake.recorded("live_activity_enablement"))
        activity.enqueue(LiveVoiceActivity.Request.Update(null, null))
    }

    @Test fun aDismissalTheSystemReportsKeepsTheCallsActivityAway() = runBlocking {
        val fake = FakeActivities()
        val activity = LiveVoiceActivity(fake.system(TestClock()))
        activity.enqueue(LiveVoiceActivity.Request.Update(content(Phase.Live, startedAt = t0), attributes))
        assertEquals(listOf("activity-1"), fake.requested)

        fake.showing.clear()
        fake.stateUpdates["activity-1"]?.send(LiveVoiceActivity.State.Dismissed)
        eventually { fake.recorded("live_activity_state").isNotEmpty() }
        activity.enqueue(LiveVoiceActivity.Request.Refresh)
        assertEquals(listOf("live_activity_state dismissed id=activity-1"), fake.recorded("live_activity_state"))
        assertEquals("dismissed during the call", fake.reports.last().last)
        assertFalse(activity.isHeartbeating)

        // Neither the call's updates nor a return to the foreground bring it back.
        activity.enqueue(LiveVoiceActivity.Request.Update(content(Phase.Live, isMuted = true, startedAt = t0), attributes))
        activity.enqueue(LiveVoiceActivity.Request.Retry)
        assertEquals(listOf("activity-1"), fake.requested)

        // The next call gets its own.
        activity.enqueue(LiveVoiceActivity.Request.Update(null, null))
        activity.enqueue(LiveVoiceActivity.Request.Update(content(Phase.Connecting), attributes))
        assertEquals(listOf("activity-1", "activity-2"), fake.requested)
        activity.enqueue(LiveVoiceActivity.Request.Update(null, null))
    }

    @Test fun aPendingActivityIsStillTheCalls() = runBlocking {
        val fake = FakeActivities()
        val activity = LiveVoiceActivity(fake.system(TestClock()))
        activity.enqueue(LiveVoiceActivity.Request.Update(content(Phase.Connecting), attributes))
        fake.states["activity-1"] = LiveVoiceActivity.State.Pending
        activity.enqueue(LiveVoiceActivity.Request.Update(content(Phase.Live, startedAt = t0), attributes))
        assertEquals(listOf("activity-1"), fake.requested)
        assertEquals("activity-1", fake.updates.last().first)
        assertTrue(fake.recorded("live_activity_dismissed").isEmpty())
        assertEquals(listOf("live_activity_update phase=live id=activity-1"), fake.recorded("live_activity_update"))
        activity.enqueue(LiveVoiceActivity.Request.Update(null, null))
    }

    @Test fun aSwipeTheAppNoticesIsRecorded() = runBlocking {
        val fake = FakeActivities()
        val activity = LiveVoiceActivity(fake.system(TestClock()))
        activity.enqueue(LiveVoiceActivity.Request.Update(content(Phase.Live, startedAt = t0), attributes))
        fake.showing.clear()
        activity.enqueue(LiveVoiceActivity.Request.Update(content(Phase.Live, isMuted = true, startedAt = t0), attributes))
        assertEquals(listOf("live_activity_dismissed state=gone id=activity-1"), fake.recorded("live_activity_dismissed"))
        activity.enqueue(LiveVoiceActivity.Request.Update(null, null))
    }
}

/**
 * The Live Update's buttons are [LiveActivityActionReceiver] broadcasts, which send their command
 * to `LiveActivityCommands.voiceCall` (Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceLiveActivityFrameworkTests {
    @Test fun intentsSendTheirCommand() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        // The manifest's receiver, registered here (library unit tests don't merge manifests).
        context.registerReceiver(
            LiveActivityActionReceiver(),
            IntentFilter().apply {
                addAction(LiveActivityActionReceiver.ACTION_TOGGLE_MUTE)
                addAction(LiveActivityActionReceiver.ACTION_END_CALL)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        val commands = async(start = CoroutineStart.UNDISPATCHED) { LiveActivityCommands.voiceCall.stream().take(2).toList() }
        repeat(5) { yield() }
        context.sendBroadcast(Intent(LiveActivityActionReceiver.ACTION_TOGGLE_MUTE).setPackage(context.packageName))
        shadowOf(Looper.getMainLooper()).idle()
        context.sendBroadcast(Intent(LiveActivityActionReceiver.ACTION_END_CALL).setPackage(context.packageName))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(VoiceCallCommand.ToggleMute, VoiceCallCommand.End), commands.await())
    }
}
