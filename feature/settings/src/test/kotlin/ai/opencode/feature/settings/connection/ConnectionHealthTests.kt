package ai.opencode.feature.settings.connection

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.settings.ConnectionStatus
import ai.opencode.feature.settings.SettingsTestShared
import ai.opencode.feature.settings.connection.ConnectionHealth.Display
import ai.opencode.feature.settings.connection.ConnectionHealth.Link
import ai.opencode.feature.settings.connection.ConnectionHealth.Outcome
import ai.opencode.feature.settings.connection.ConnectionHealth.Status
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.retry.ServerContact
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * The connection card's status machine and beat shapes: the status follows the trace, so each
 * change lands at its moment on it.
 */
class ConnectionHealthTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun near(a: Double, b: Double) = abs(a - b) < 1e-9

    /** A check that leaves at [t] and is answered [milliseconds] later. */
    private fun beat(health: ConnectionHealth, t: Double, milliseconds: Int): Double {
        val id = health.beginCheck(t)
        val arrival = t + milliseconds / 1000.0
        health.answer(id, milliseconds, arrival)
        return t + BeatShape(milliseconds).spike
    }

    @Test fun connectedLandsOnTheFirstFullBeatsSpike() {
        val health = ConnectionHealth(Link.Reconnecting, 0.0)
        assertEquals(Status.reconnecting, health.display.status)
        health.setLink(Link.Connected, 1.0)
        assertEquals("Reconnected, the card waits for the trace", Status.reconnecting, health.display.status)
        val due = health.nextCheck!!
        assertTrue("Checks at once on reconnect", near(due, 1.15))
        val spike = beat(health, due, 40)
        health.advance(spike - 0.001)
        assertEquals("Not before the spike", Status.reconnecting, health.display.status)
        health.advance(spike)
        assertEquals(Display(Status.connected, 40, null), health.display)
    }

    @Test fun reconnectingLandsWhenTheFirstUnansweredCheckWouldHaveBeenAnswered() {
        val health = ConnectionHealth(Link.Connected, 0.0, milliseconds = 38)
        health.setLink(Link.Reconnecting, 10.0)
        assertEquals("The link changed; the trace hasn't shown it yet", Status.connected, health.display.status)
        val due = health.nextCheck!!
        assertTrue(near(due, 10.3))
        health.beginCheck(due)
        health.advance(due + ConnectionHealth.reconnectingDelay - 0.01)
        assertEquals(Status.connected, health.display.status)
        health.advance(due + ConnectionHealth.reconnectingDelay)
        assertEquals(Display(Status.reconnecting, null, null), health.display)
        assertTrue(health.nextCheck?.let { near(it, due + ConnectionHealth.reconnectingCadence) } == true)
    }

    @Test fun anAnswerWhileReconnectingDrawsItsSpikeButKeepsTheStatus() {
        val health = ConnectionHealth(Link.Reconnecting, 0.0)
        val spike = beat(health, 0.3, 30)
        health.advance(spike + 1)
        assertEquals("Home's reconnect decides, not a check", Status.reconnecting, health.display.status)
        assertEquals(Outcome.Answered(30), health.beats.last().outcome)
    }

    @Test fun offlineShowsAtOnceAndDropsWaitingChanges() {
        val health = ConnectionHealth(Link.Connected, 0.0, milliseconds = 38)
        val spike = beat(health, 0.0, 500)
        health.setLink(Link.Offline, 0.4)
        assertEquals(Display(Status.offline, null, null), health.display)
        health.advance(spike + 1)
        assertEquals("A spike scheduled before the drop doesn't bring it back", Status.offline, health.display.status)
        assertNull("No checks while offline; Retry is the way back", health.nextCheck)
    }

    @Test fun redOnlyWhileActuallyOffline() {
        val health = ConnectionHealth(Link.Connected, 0.0)
        health.setLink(Link.Offline, 2.0)
        assertTrue(health.isRed(3.0))
        assertFalse(health.isRed(1.0))
        health.setLink(Link.Reconnecting, 5.0)
        assertTrue("The flat stretch stays flat", health.isOffline(3.0))
        assertFalse("but goes back to pearl once Retry starts", health.isRed(3.0))
        assertFalse(health.isOffline(5.5))
    }

    @Test fun switchingShowsAtOnceAndSweepsTheTraceClear() {
        val health = ConnectionHealth(Link.Connected, 0.0, milliseconds = 38)
        val before = health.origin(9.9)
        health.setLink(Link.Switching("studio"), 10.0)
        assertEquals(Display(Status.switching, null, "studio"), health.display)
        assertEquals(10.0, health.eraseStart)
        assertEquals(10 + ConnectionHealth.eraseDuration, health.clearAt, 0.0)
        assertEquals("The pen carries on while the old trace is erased", before, health.origin(10.5), 0.0)
        assertEquals("then starts again at the left", health.clearAt, health.origin(health.clearAt), 0.0)
        assertNull(health.nextCheck)
        health.setLink(Link.Connected, 11.0)
        assertEquals("The new server is named on its first beat", Status.switching, health.display.status)
        val spike = beat(health, 11.15, 6)
        health.advance(spike)
        assertEquals(Status.connected, health.display.status)
    }

    /** Race A: a Reconnecting scheduled for its moment on the trace must not land after the link has already come back. */
    @Test fun aFastReconnectCancelsTheWaitingReconnecting() {
        val health = ConnectionHealth(Link.Connected, 0.0, milliseconds = 38)
        health.setLink(Link.Reconnecting, 10.0)
        val due = health.nextCheck!!
        health.beginCheck(due)
        assertTrue("Reconnecting waits for its moment", health.changes.any { it.display.status == Status.reconnecting })
        health.setLink(Link.Connected, due + 0.1)
        assertFalse(health.changes.any { it.display.status == Status.reconnecting })
        health.advance(due + ConnectionHealth.reconnectingDelay + 0.01)
        assertEquals("Connected never flips back to Reconnecting", Status.connected, health.display.status)
        val spike = beat(health, health.nextCheck!!, 30)
        health.advance(spike)
        assertEquals(Display(Status.connected, 30, null), health.display)
    }

    /** Race B: a check that left while reconnecting and goes unanswered after the link came back says nothing about the new connection. */
    @Test fun aCheckFromBeforeTheConnectionDoesNotMakeItUnstable() {
        val health = ConnectionHealth(Link.Reconnecting, 0.0)
        val early = health.beginCheck(0.3)
        health.setLink(Link.Connected, 0.5)
        health.advance(0.3 + ConnectionHealth.answerDeadline + 0.01)
        assertEquals(Outcome.Unanswered, health.beats.first { it.id == early }.outcome)
        assertFalse(health.changes.any { it.display.status == Status.unstable })
        assertNotEquals(Status.unstable, health.display.status)
        val spike = beat(health, health.nextCheck!!, 35)
        health.advance(max(spike, 2.0))
        assertEquals(Display(Status.connected, 35, null), health.display)
        // The same for a check that fails outright after the link came back.
        val failed = ConnectionHealth(Link.Reconnecting, 0.0)
        val id = failed.beginCheck(0.3)
        failed.setLink(Link.Connected, 0.5)
        failed.fail(id, 0.6)
        failed.advance(0.6)
        assertNotEquals(Status.unstable, failed.display.status)
    }

    /** A switch: the old server's checks, still out, never mark the new one Unstable, and its first beat replaces Switching. */
    @Test fun aSwitchLeavesSwitchingOnTheNewServersFirstBeat() {
        val health = ConnectionHealth(Link.Connected, 0.0, milliseconds = 38)
        health.beginCheck(9.9)
        health.setLink(Link.Switching("studio"), 10.0)
        health.setLink(Link.Connected, 10.4)
        health.advance(11.2)
        assertEquals("The old server's dropped check doesn't end the switch", Status.switching, health.display.status)
        val spike = beat(health, health.nextCheck!!, 5)
        health.advance(spike)
        assertEquals(Display(Status.connected, 5, null), health.display)
    }

    /** A refused password: Sign-in failed at once, the trace flat and red, and no checks until the server is edited. */
    @Test fun aRefusedPasswordIsSignInFailedWithoutChecks() {
        val health = ConnectionHealth(Link.Connected, 0.0, milliseconds = 38)
        val spike = beat(health, 0.0, 400)
        health.setLink(Link.Rejected, 0.2)
        assertEquals(Display(Status.signInFailed, null, null), health.display)
        health.advance(spike + 1)
        assertEquals(Status.signInFailed, health.display.status)
        assertNull(health.nextCheck)
        assertTrue(health.isRed(0.5))
        val opened = ConnectionHealth(Link.Rejected, 0.0)
        assertTrue(opened.display.status == Status.signInFailed && opened.isRed(0.0))
        val shared = SettingsTestShared()
        shared.read {
            assertEquals(Link.Rejected, ConnectionMonitor.link(ConnectionStatus.disconnected, credentialsRejected = true))
            val studio = OpenCodeServer(url = "http://192.168.1.40:4096", username = "opencode", password = "", name = "studio")
            assertEquals("A switch wins", Link.Switching("studio"), ConnectionMonitor.link(ConnectionStatus.connected, switchingTo = studio))
        }
    }

    @Test fun anUnansweredCheckIsUnstableAtItsDeadlineKeepingTheLastNumber() {
        val health = ConnectionHealth(Link.Connected, 0.0)
        health.advance(beat(health, 0.0, 41))
        val id = health.beginCheck(2.0)
        health.advance(2 + ConnectionHealth.answerDeadline - 0.01)
        assertEquals(Status.connected, health.display.status)
        health.advance(2 + ConnectionHealth.answerDeadline + 0.01)
        assertEquals(Display(Status.unstable, 41, null), health.display)
        // A late answer stays a dropped beat.
        health.answer(id, 1300, 3.3)
        assertEquals(Outcome.Unanswered, health.beats.first { it.id == id }.outcome)
    }

    @Test fun unstableHoldsUntilFiveCleanChecks() {
        val health = ConnectionHealth(Link.Connected, 0.0)
        health.beginCheck(0.0)
        health.advance(1.5)
        assertEquals(Status.unstable, health.display.status)
        var t = 2.0
        for (k in 1..5) {
            health.advance(beat(health, t, 40))
            assertEquals("check $k", if (k < 5) Status.unstable else Status.connected, health.display.status)
            t += ConnectionHealth.cadence
        }
    }

    @Test fun aFailedCheckIsUnansweredAtOnce() {
        val health = ConnectionHealth(Link.Connected, 0.0)
        val id = health.beginCheck(0.0)
        health.fail(id, 0.1)
        health.advance(0.1)
        assertEquals(Status.unstable, health.display.status)
    }

    @Test fun slowFromTheMedianOfTheLastThreeRoundTrips() {
        val health = ConnectionHealth(Link.Connected, 0.0)
        health.advance(beat(health, 0.0, 40))
        health.advance(beat(health, 2.0, 480))
        assertEquals("One slow answer is a blip", Status.connected, health.display.status)
        health.advance(beat(health, 4.0, 520))
        assertEquals("The number is the average", Display(Status.slow, 347, null), health.display)
        health.advance(beat(health, 6.0, 35))
        health.advance(beat(health, 8.0, 30))
        assertEquals(Display(Status.connected, 195, null), health.display)
    }

    /**
     * Like a game's ping: the number is the rolling average of the last `averaged` answers, so
     * one slow answer nudges it instead of jolting it.
     */
    @Test fun theNumberIsARollingAverageOfTheLastThreeAnswers() {
        val health = ConnectionHealth(Link.Connected, 0.0)
        for ((k, pair) in listOf(30 to 30, 90 to 60, 30 to 50, 30 to 50, 30 to 30).withIndex()) {
            val (ms, shown) = pair
            health.advance(beat(health, k * ConnectionHealth.cadence, ms))
            assertEquals("check ${k + 1}", shown, health.display.milliseconds)
        }
        // A drop doesn't count toward the average; it keeps the number.
        health.beginCheck(10.0)
        health.advance(10 + ConnectionHealth.answerDeadline + 0.01)
        assertEquals(Display(Status.unstable, 30, null), health.display)
        health.advance(beat(health, 12.0, 80))
        assertEquals(47, health.display.milliseconds)
    }

    /**
     * The number Settings opens on averages in until three real answers arrive, so the first
     * check doesn't jump from it; a new link drops it.
     */
    @Test fun theOpeningNumberAveragesInUntilThreeAnswers() {
        val health = ConnectionHealth(Link.Connected, 0.0, milliseconds = 100)
        assertEquals(100, health.display.milliseconds)
        health.advance(beat(health, 0.0, 40))
        assertEquals(70, health.display.milliseconds)
        for (k in 1..2) health.advance(beat(health, k * ConnectionHealth.cadence, 40))
        assertEquals("Three answers fill the window", 40, health.display.milliseconds)

        val reconnected = ConnectionHealth(Link.Connected, 0.0, milliseconds = 100)
        reconnected.setLink(Link.Reconnecting, 1.0)
        reconnected.setLink(Link.Connected, 2.0)
        reconnected.advance(beat(reconnected, 2.15, 40))
        assertEquals("A new connection starts its own average", 40, reconnected.display.milliseconds)
    }

    @Test fun checkCadence() {
        val health = ConnectionHealth(Link.Connected, 0.0)
        assertEquals("Checks at once", 0.0, health.nextCheck)
        health.beginCheck(0.0)
        assertEquals(ConnectionHealth.cadence, health.nextCheck)
        health.setLink(Link.Reconnecting, 1.0)
        health.beginCheck(1.3)
        assertEquals(1.3 + ConnectionHealth.reconnectingCadence, health.nextCheck)
        health.setLink(Link.Offline, 2.0)
        assertNull(health.nextCheck)
    }

    @Test fun nextWakeIsTheEarliestDeadlineChangeOrCheck() {
        val health = ConnectionHealth(Link.Connected, 0.0)
        health.beginCheck(0.0)
        assertTrue(health.nextWake?.let { near(it, ConnectionHealth.answerDeadline + 0.01) } == true)
    }

    // MARK: Beat shapes

    @Test fun fastBeatsAreTallAndCrispSlowOnesLowAndWide() {
        val fast = BeatShape(30)
        val slow = BeatShape(600)
        assertTrue(fast.height > slow.height)
        assertTrue(fast.width < slow.width)
        assertTrue(fast.gap < slow.gap)
        var last = BeatShape(0)
        for (ms in 10..1200 step 10) {
            val shape = BeatShape(ms)
            assertTrue("$ms ms", shape.height <= last.height && shape.width >= last.width && shape.gap >= last.gap)
            last = shape
        }
    }

    @Test fun theSpikeNeverComesBeforeItsAnswer() {
        for (ms in 0..1200 step 25) {
            assertTrue("$ms ms", BeatShape(ms).gap >= ms / 1000.0)
        }
    }

    @Test fun anUnansweredBeatIsOnlyItsPWave() {
        val samples = (0..1000).map { it * 0.002 }
        val dropped = samples.maxOf { BeatShape.value(it, Outcome.Unanswered) }
        val answered = samples.maxOf { BeatShape.value(it, Outcome.Answered(38)) }
        val slow = samples.maxOf { BeatShape.value(it, Outcome.Answered(600)) }
        assertTrue(dropped < 0.25)
        assertTrue(answered > 0.8)
        assertTrue(slow < answered && slow > dropped)
    }

    @Test fun offlineFlattensTheTrace() {
        val beats = listOf(ConnectionHealth.Beat(id = 1, time = 0.0, outcome = Outcome.Answered(38)))
        val peak = BeatShape(38).spike
        assertTrue(ConnectionTrace.signal(peak, beats) { false } > 0.5)
        assertEquals(0.0, ConnectionTrace.signal(peak, beats) { true }, 0.0)
    }

    @Test fun stillTracesMatchTheirState() {
        val connected = ConnectionTrace.stillBeats(Display(Status.connected, milliseconds = 38), 100.0)
        assertTrue(connected.size == 5 && connected.all { it.outcome == Outcome.Answered(38) })
        val reconnecting = ConnectionTrace.stillBeats(Display(Status.reconnecting), 100.0)
        assertTrue(reconnecting.all { it.outcome == Outcome.Unanswered })
        assertTrue(ConnectionTrace.stillBeats(Display(Status.unstable, milliseconds = 80), 100.0).any { it.outcome == Outcome.Unanswered })
        assertTrue(ConnectionTrace.stillBeats(Display(Status.offline), 100.0).isEmpty())
        assertTrue(ConnectionTrace.stillBeats(Display(Status.switching, target = "studio"), 100.0).isEmpty())
    }

    // MARK: Names and routes

    @Test fun lookupAddressesAreParsedIPsOnly() {
        fun lookup(url: String): String? = ServerCardName.lookupAddress(OpenCodeServer(url = url, username = "opencode", password = ""))
        assertEquals("100.101.102.103", lookup("http://100.101.102.103:4096"))
        assertEquals("fd12::1", lookup("http://[fd12::1]:4096"))
        assertNull(lookup("https://fd.example.com"))
        assertNull(lookup("http://127.0.0.1:4096"))
        assertNull(lookup("http://[::1]:4096"))
        assertNull(lookup("http://[fe80::1%25en0]:4096"))
    }

    @Test fun theCardNamesTheMachineNotItsAddress() {
        val direct = OpenCodeServer(url = "http://100.101.102.103:4096", username = "opencode", password = "")
        assertEquals("100.101.102.103", ServerCardName.lookupAddress(direct))
        assertEquals("Server 103", ServerCardName.name(direct, resolved = null))
        assertEquals("My Mac", ServerCardName.name(direct, resolved = "my-mac"))
        val loopback = OpenCodeServer(url = "http://127.0.0.1:4117", username = "opencode", password = "")
        assertEquals("Server 1", ServerCardName.name(loopback, resolved = null))
        val named = direct.copy(name = "Work")
        assertEquals("The user's name wins", "Work", ServerCardName.name(named, resolved = "my-mac"))
        val tailnet = OpenCodeServer(
            url = "http://100.101.102.103:4096", username = "opencode", password = "",
            alternateURLs = listOf("https://my-mac.tail1234.ts.net"),
        )
        assertEquals("My Mac", ServerCardName.name(tailnet, resolved = null))
        val renamedTailnet = tailnet.copy(name = "studio")
        assertEquals("Custom name on renamed server is preserved", "studio", ServerCardName.name(renamedTailnet, resolved = null))
        assertNull(ServerCardName.lookupAddress(OpenCodeServer(url = "https://deadbeef.cafe", username = "", password = "")))
        assertNull(ServerCardName.lookupAddress(OpenCodeServer(url = "http://127.0.0.1:4096", username = "", password = "")))
    }

    @Test fun machineNamesFromReverseLookups() {
        assertEquals("my-mac", ServerCardName.machineName(fromReverse = "my-mac.tail1234.ts.net."))
        assertEquals("studio", ServerCardName.machineName(fromReverse = "studio.local"))
        assertNull(ServerCardName.machineName(fromReverse = "100.101.102.103"))
        assertNull(ServerCardName.machineName(fromReverse = ""))
    }

    @Test fun reverseLabelsThatSpellOutTheAddressAreNotMachineNames() {
        for (answer in listOf(
            "ip-10-0-2-2.ec2.internal.", "10-0-2-2.example.net", "ec2-3-89-50-194.compute-1.amazonaws.com",
            "host-192-168-1-5.lan", "c-73-12-34-56.hsd1.ca.comcast.net", "pool-71-191-23-4.washdc.fios.verizon.net",
            "ip10-0-2-2.isp.net", "194.50.89.3.static.example.com",
        )) assertNull(answer, ServerCardName.machineName(fromReverse = answer))
        assertEquals("ryans-macbook", ServerCardName.machineName(fromReverse = "ryans-macbook.local"))
        assertEquals("devbox-01", ServerCardName.machineName(fromReverse = "devbox-01.tail1234.ts.net"))
        assertEquals("rack-2-node-300", ServerCardName.machineName(fromReverse = "rack-2-node-300.lab"))
    }

    // MARK: Presentation

    @Test fun statusShowsAGlyphAndTheLiveNumber() {
        val connected = ConnectionStatusPresentation(Display(Status.connected, milliseconds = 38))
        assertTrue(connected.symbol.sfName == "waveform.path.ecg" && connected.milliseconds == 38)
        assertEquals("Connected, 38 milliseconds", connected.spokenStatus)
        val reconnecting = ConnectionStatusPresentation(Display(Status.reconnecting, milliseconds = 38))
        assertTrue(reconnecting.milliseconds == null && reconnecting.word == "Reconnecting")
        assertEquals("Switching to studio", ConnectionStatusPresentation(Display(Status.switching, target = "studio")).word)
        val rejected = ConnectionStatusPresentation(Display(Status.signInFailed))
        assertTrue(rejected.text == "Sign-in failed · 401" && rejected.symbol.sfName == "lock.slash" && rejected.isAlert)
        assertEquals("Sign-in failed, HTTP 401", rejected.spokenStatus)
    }

    /** WCAG contrast ratio between two sRGB colours. */
    private fun contrast(a: ScopeColor, b: ScopeColor): Float {
        fun linear(v: Float): Float = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
        fun luminance(c: ScopeColor): Float = 0.2126f * linear(c.r) + 0.7152f * linear(c.g) + 0.0722f * linear(c.b)
        val l1 = luminance(a)
        val l2 = luminance(b)
        return (maxOf(l1, l2) + 0.05f) / (minOf(l1, l2) + 0.05f)
    }

    /**
     * Connected draws in the system green, slow and unstable in orange, and the rest keep the
     * neutral trace (offline its red). On light it's the increased-contrast green, readable on the
     * scope's pale well.
     */
    @Test fun traceColoursFollowTheStatus() {
        val dark = ConnectionTraceTint.colors(Status.connected, dark = true, highContrast = false)!!.first
        assertTrue("Green", dark.g > dark.r && dark.g > dark.b)
        val light = ConnectionTraceTint.colors(Status.connected, dark = false, highContrast = false)!!.first
        assertTrue("Green", light.g > light.r && light.g > light.b)
        // Against the scope's well: systemGroupedBackground on light, near black on dark.
        val lightWell = ScopeColor(0xF2 / 255f, 0xF2 / 255f, 0xF7 / 255f)
        val darkWell = ScopeColor(0x13 / 255f, 0x13 / 255f, 0x13 / 255f)
        assertTrue("Readable on light: ${contrast(light, lightWell)}", contrast(light, lightWell) >= 3)
        assertTrue("Readable on dark: ${contrast(dark, darkWell)}", contrast(dark, darkWell) >= 3)
        val slow = ConnectionTraceTint.colors(Status.slow, dark = true, highContrast = false)!!.first
        assertTrue("Orange", slow.r > slow.g && slow.g > slow.b)
        for (status in listOf(Status.reconnecting, Status.switching, Status.offline, Status.signInFailed)) {
            assertNull("$status keeps its own colour", ConnectionTraceTint.colors(status, dark = true, highContrast = false))
        }
    }

    /**
     * An OkHttp interceptor on the probe's client answers. The policy is a coroutine-local bound
     * around the call, which OkHttp's dispatcher thread can't observe.
     */
    @Test fun probeSendsCustomHeadersAndRecordsServerContact() = runTest {
        val server = OpenCodeServer(
            url = "http://100.101.102.103:4096",
            username = "opencode",
            password = "secret",
            headers = listOf(
                OpenCodeServer.Header(name = "CF-Access-Client-Id", value = "id-123"),
                OpenCodeServer.Header(name = "CF-Access-Client-Secret", value = "sec-456"),
            ),
        )
        val contact = ServerContact()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("http://100.101.102.103:4096/api/info", request.url.toString())
            assertEquals("id-123", request.header("CF-Access-Client-Id"))
            assertEquals("sec-456", request.header("CF-Access-Client-Secret"))
            assertEquals(server.basicAuthorization, request.header("Authorization"))
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"version":"2.1.0"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val answer = ConnectionProbeClient.liveCheck(server, contact = contact, client = client)
        assertEquals("2.1.0", answer.version)
        assertTrue(answer.milliseconds >= 1)
        assertTrue(contact.hasAnsweredHTTPOrigin("http://100.101.102.103:4096/api/info".toHttpUrl()))
    }
}
