package ai.opencode.server

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.withDependenciesSuspending
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.shared
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.live.DataPayload
import ai.opencode.server.live.EventStreamWatchdog
import ai.opencode.server.live.ServerInfoPayload
import ai.opencode.server.live.request
import ai.opencode.server.retry.NetworkPathMonitor
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RetryEnvironment
import ai.opencode.server.retry.RetryPolicy
import ai.opencode.server.retry.TransportFailure
import ai.opencode.server.testing.WireFixture
import ai.opencode.server.testing.WireFixture.Reply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Which of a server's addresses the app uses ([ServerLinkStore]), how a saved server keeps its
 * address order, and the network path and event stream signals that decide when to look again.
 */
class ServerLinkTests {
    @get:Rule val isolation = TestIsolationRule()

    companion object {
        /** Paired at home, so its LAN address is first and the tailnet address second. */
        const val lan = "http://192.168.1.155:49374"
        const val tailnet = "http://100.114.13.77:49374"
        val saved = OpenCodeServer(
            url = lan, username = "opencode", password = "1790000000.token", alternateURLs = listOf(tailnet), name = "Adams Mini",
        )

        /** Away from home: the LAN address never answers, the tailnet one does. */
        fun awayFromHome(lanCancelled: AtomicBoolean, calls: MutableList<String> = Collections.synchronizedList(mutableListOf())): suspend (OpenCodeServer) -> Unit = { server ->
            calls += server.url
            if (server.url == lan) {
                try {
                    delay(30.seconds)
                } catch (cancelled: CancellationException) {
                    lanCancelled.set(true)
                    throw cancelled
                }
                throw OpenCodeClientError.unreachable(TransportFailure.TimedOut)
            }
            delay(20.milliseconds)
        }
    }

    @Test fun aSavedLANPrimaryThatDoesNotAnswerLosesToTheTailnetAtOnce() = runTest {
        val lanCancelled = AtomicBoolean(false)
        val store = ServerLinkStore(health = awayFromHome(lanCancelled))
        val started = testScheduler.currentTime

        val winner = store.probe(saved)

        assertEquals(tailnet, winner.url)
        assertEquals("Adams Mini", winner.name)
        assertTrue("The tailnet wins without waiting out the LAN address", testScheduler.currentTime - started < 2_000)
        assertTrue("The losing attempt is cancelled, not left holding a socket", lanCancelled.get())
        assertEquals(tailnet, store.resolve(saved).url)
        assertEquals(tailnet, store.route(saved).url)
        // The saved server itself is untouched: the winner lives in memory.
        assertEquals(lan, saved.url)
    }

    @Test fun theFastestVerifiedAnswerWinsWhateverItsRoute() = runTest {
        val store = ServerLinkStore(health = { server -> delay(if (server.url == lan) 5.milliseconds else 150.milliseconds) })
        assertEquals(lan, store.probe(saved).url)
    }

    // Preferred addresses

    /** Paired from `opencode pair --url <tailnet>`: the server also reported its LAN and tunnel addresses. */
    private val tunnel = "https://k7f3m2qz9x.opentunnel.dev"
    private val pairedByURL = OpenCodeServer(
        url = tailnet, username = "opencode", password = "1790000000.token",
        alternateURLs = listOf(lan, tunnel), preferredURLs = listOf(tailnet),
    )

    @Test fun thePairedAddressWinsWhenItAnswersEvenIfAReportedOneIsFaster() = runTest {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val store = ServerLinkStore(health = { server ->
            calls += server.url
            delay(if (server.url == tailnet) 400.milliseconds else 5.milliseconds)
        })
        assertEquals(tailnet, store.probe(pairedByURL).url)
        assertEquals("Reported addresses wait out the head start", listOf(tailnet), calls.toList())
    }

    @Test fun reportedAddressesAreTriedOnceThePairedOneHasHadItsHeadStart() = runTest {
        val tailnetCancelled = AtomicBoolean(false)
        val started = testScheduler.currentTime
        val store = ServerLinkStore(health = { server ->
            when (server.url) {
                tailnet -> try {
                    delay(30.seconds)
                } catch (cancelled: CancellationException) {
                    tailnetCancelled.set(true)
                    throw cancelled
                }
                lan -> delay(5.milliseconds)
                else -> delay(150.milliseconds)
            }
        })
        val winner = store.probe(pairedByURL)
        assertEquals(lan, winner.url)
        assertEquals(ServerLinkStore.preferredHeadStart.inWholeMilliseconds + 5, testScheduler.currentTime - started)
        assertTrue("The paired address still in flight is cancelled", tailnetCancelled.get())
        assertEquals(listOf(tailnet), winner.preferredURLs)
    }

    @Test fun reportedAddressesAreTriedAtOnceWhenEveryPairedOneFails() = runTest {
        val started = testScheduler.currentTime
        val store = ServerLinkStore(health = { server ->
            if (server.url == tailnet) {
                delay(10.milliseconds)
                throw OpenCodeClientError.unreachable(TransportFailure.NotSent)
            }
            delay(if (server.url == tunnel) 20.milliseconds else 30.seconds)
            if (server.url == lan) throw OpenCodeClientError.unreachable(TransportFailure.TimedOut)
        })
        assertEquals(tunnel, store.probe(pairedByURL).url)
        assertEquals(30L, testScheduler.currentTime - started)
    }

    @Test fun aCodeWithOnlyTheTunnelStillLetsADirectAddressWinAtHome() = runTest {
        // The desktop's QR and `opencode pair --remote` name only the tunnel.
        val pairedByTunnel = pairedByURL.copy(url = tunnel, alternateURLs = listOf(lan, tailnet), preferredURLs = listOf(tunnel))
        assertEquals(listOf(tunnel, lan, tailnet), pairedByTunnel.firstAttemptURLs)
        val store = ServerLinkStore(health = { server -> delay(if (server.url == lan) 5.milliseconds else 150.milliseconds) })
        assertEquals(lan, store.probe(pairedByTunnel).url)
        // A tailnet address the code named holds back both the LAN and the tunnel.
        assertEquals(listOf(tailnet), pairedByURL.firstAttemptURLs)
    }

    @Test fun aPreferredAddressThatIsNoLongerSavedOrEveryAddressPreferredRacesAsBefore() = runTest {
        val store = ServerLinkStore(health = { server -> delay(if (server.url == tunnel) 5.milliseconds else 150.milliseconds) })
        assertEquals(tunnel, store.probe(pairedByURL.copy(preferredURLs = listOf("http://10.9.9.9:4096"))).url)
        assertEquals(pairedByURL.candidateURLs, pairedByURL.copy(preferredURLs = pairedByURL.candidateURLs).firstAttemptURLs)
        assertEquals(tunnel, ServerLinkStore(health = { server -> delay(if (server.url == tunnel) 5.milliseconds else 150.milliseconds) })
            .probe(pairedByURL.copy(preferredURLs = pairedByURL.candidateURLs)).url)
    }

    @Test fun aForeignAnswerOnTheLANNeverBeatsTheRealServer() = runTest {
        val foreignAnswers = listOf(
            OpenCodeClientError("The server returned HTTP 302.", statusCode = 302),
            OpenCodeClientError("The server response does not match the current OpenCode V2 contract."),
            OpenCodeClientError("The server rejected these credentials.", statusCode = 401),
            OpenCodeClientError("A different OpenCode server answered at this address."),
        )
        for (foreign in foreignAnswers) {
            val store = ServerLinkStore(health = { server ->
                if (server.url == lan) throw foreign
                delay(30.milliseconds)
            })
            assertEquals(foreign.message, tailnet, store.probe(saved).url)
        }
    }

    @Test fun aRefusedSignInBeatsAnUnreachableAddressWhenNothingAnswers() = runTest {
        val store = ServerLinkStore(health = { server ->
            if (server.url == lan) throw OpenCodeClientError("Rejected", statusCode = 401)
            throw OpenCodeClientError.unreachable(TransportFailure.NotSent)
        })
        val error = try {
            store.probe(saved)
            null
        } catch (error: OpenCodeClientError) {
            error
        }
        assertEquals(401, error?.statusCode)
        assertEquals("Nothing answered: requests keep the saved order", saved, store.resolve(saved))
    }

    @Test fun concurrentCallersShareOneRace() = runTest {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val store = ServerLinkStore(health = { server ->
            calls += server.url
            delay(80.milliseconds)
        })
        val winners = (0 until 5).map { async { store.probe(saved).url } }.awaitAll()
        assertEquals(1, winners.toSet().size)
        assertEquals("One request per address, not one per caller", listOf(tailnet, lan).sorted(), calls.sorted())
    }

    @Test fun aRequestWaitsForTheRaceInFlightAndASingleAddressSkipsIt() = runTest {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val store = ServerLinkStore(health = awayFromHome(AtomicBoolean(false), calls))
        // No address has answered yet: the request runs the race and follows it.
        assertEquals(tailnet, store.route(saved).url)
        assertEquals(2, calls.size)
        // Known now: no second race.
        assertEquals(tailnet, store.route(saved).url)
        assertEquals(2, calls.size)
        val single = OpenCodeServer(url = "http://studio.local:4096", username = "opencode", password = "pw")
        assertEquals(single, store.route(single))
        assertEquals(2, calls.size)
    }

    // A warm store whose address stopped answering

    /** Home at home: the LAN address won. Then the phone leaves Wi-Fi. */
    private fun warmOnLAN(calls: MutableList<String>, lanAnswers: AtomicBoolean = AtomicBoolean(false)): ServerLinkStore {
        val store = ServerLinkStore(health = { server ->
            calls += server.url
            if (server.url == lan && !lanAnswers.get()) throw OpenCodeClientError.unreachable(TransportFailure.TimedOut)
        })
        store.adopt(saved)
        return store
    }

    @Test fun aFailedAddressInUseIsForgottenAndTheNextRequestRaces() = runTest {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val store = warmOnLAN(calls)
        store.route(saved)
        assertTrue("Known and not racing: no race", calls.isEmpty())
        store.invalidate(saved, tailnet)
        store.route(saved)
        assertTrue("Another address failing says nothing about this one", calls.isEmpty())
        store.invalidate(saved, lan)
        assertEquals(tailnet, store.route(saved).url)
        assertEquals(2, calls.size)
    }

    /** The LAN address in use, with its event stream on it. */
    private fun streamingOnLAN(calls: MutableList<String>, now: AtomicReference<Duration>): ServerLinkStore {
        val store = ServerLinkStore(health = { server -> calls += server.url }, now = { now.get() })
        store.adopt(saved)
        store.noteStreamActivity(saved)
        return store
    }

    /**
     * A request failing on an address whose stream delivered a line after it went goes straight
     * back to it (no race, no extra round trip); a second in a row forgets it.
     */
    @Test fun aFailureWhileTheStreamKeepsTalkingKeepsTheAddressOnce() = runTest {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val now = AtomicReference(100.seconds)
        val store = streamingOnLAN(calls, now)
        val sent = now.get()
        now.set(now.get() + 16.seconds)
        // An idle stream's heartbeat, after the request went.
        store.noteStreamActivity(saved)
        store.requestFailed(saved, lan, sent)
        assertEquals(lan, store.route(saved).url)
        assertTrue("No race after one failure", calls.isEmpty())
        store.requestFailed(saved, lan, sent)
        store.route(saved)
        assertTrue("Two in a row race again", calls.isNotEmpty())
    }

    @Test fun anAnswerFromTheServerStartsTheCountAgain() = runTest {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val now = AtomicReference(100.seconds)
        val store = streamingOnLAN(calls, now)
        val sent = now.get()
        now.set(now.get() + 1.seconds)
        store.noteStreamActivity(saved)
        store.requestFailed(saved, lan, sent)
        // A probe's answer, or another server's, says nothing about this address.
        store.noteAnswer(null)
        store.noteAnswer("other")
        store.noteAnswer(saved.persistenceID)
        store.requestFailed(saved, lan, sent)
        assertEquals(lan, store.route(saved).url)
        assertTrue(calls.isEmpty())
    }

    @Test fun noLineSinceTheRequestWentOrAnEndedStreamForgetsTheAddress() = runTest {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val now = AtomicReference(100.seconds)
        // The stream's last line was before the request went: no proof it's up.
        val quiet = streamingOnLAN(calls, now)
        now.set(now.get() + 1.seconds)
        quiet.requestFailed(saved, lan, now.get())
        quiet.route(saved)
        assertTrue("A line from before the request says nothing about it", calls.isNotEmpty())

        calls.clear()
        val ended = streamingOnLAN(calls, now)
        val sent = now.get()
        now.set(now.get() + 1.seconds)
        ended.noteStreamActivity(saved)
        ended.noteStreamEnded(saved)
        ended.requestFailed(saved, lan, sent)
        ended.route(saved)
        assertTrue("Once the stream drops, a failure forgets the address", calls.isNotEmpty())

        calls.clear()
        val elsewhere = streamingOnLAN(calls, now)
        now.set(now.get() + 1.seconds)
        elsewhere.noteStreamActivity(saved.at(tailnet))
        elsewhere.requestFailed(saved, lan, sent)
        elsewhere.route(saved)
        assertTrue("A stream on another address vouches only for that one", calls.isNotEmpty())
    }

    /** Settling on an address (the stream adopting it, a race) starts its count over. */
    @Test fun adoptingAnAddressStartsItsCountOver() = runTest {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val now = AtomicReference(100.seconds)
        val store = streamingOnLAN(calls, now)
        val sent = now.get()
        now.set(now.get() + 1.seconds)
        store.noteStreamActivity(saved)
        store.requestFailed(saved, lan, sent)
        store.adopt(saved)
        store.requestFailed(saved, lan, sent)
        assertEquals(lan, store.route(saved).url)
        assertTrue(calls.isEmpty())
    }

    @Test fun aRouteChangeForgetsTheAddressWhateverItWas() = runTest {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val store = warmOnLAN(calls)
        store.invalidate(saved, null)
        assertEquals(tailnet, store.route(saved).url)
    }

    @Test fun aRaceNothingAnswersForgetsTheAddressItReplaced() = runTest {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val store = ServerLinkStore(health = { server ->
            calls += server.url
            throw OpenCodeClientError.unreachable(TransportFailure.NotSent)
        })
        store.adopt(saved.at(tailnet))
        runCatching { store.probe(saved) }
        assertEquals(saved, store.resolve(saved))
        calls.clear()
        store.route(saved)
        assertEquals("The next request races instead of trusting the old address", 2, calls.size)
    }

    /** A request on the warm LAN address fails; its retry races and lands on the tailnet address. */
    @Test fun aRequestThatFailsOnTheAddressInUseRetriesOnTheOneThatAnswers() = runTest {
        val lanFixture = WireFixture { Reply.Failure(UrlErrorCode.CANNOT_CONNECT_TO_HOST) }
        val tailnetFixture = WireFixture { Reply.Json(200, """{"data":[]}""") }
        try {
            val server = OpenCodeServer(url = lanFixture.server.url, username = "user", password = "secret", alternateURLs = listOf(tailnetFixture.server.url))
            val store = ServerLinkStore(health = { candidate ->
                if (candidate.url == lanFixture.server.url) throw OpenCodeClientError.unreachable(TransportFailure.NotSent)
            })
            store.adopt(server)
            withDependenciesSuspending({
                this[ServerLinkClient] = ServerLinkClient.live(store)
                this[Clock] = ImmediateClock()
            }) {
                NetworkRetry.policy.withValue(RetryPolicy.request) {
                    NetworkRetry.environment.withValue(RetryEnvironment.online(unit = 0.0)) {
                        request<DataPayload<List<String>>>("/api/session", server = server)
                    }
                }
            }
            assertEquals(1, lanFixture.requests.size)
            assertEquals(listOf("/api/session"), tailnetFixture.requests.map { it.path })
            assertEquals(tailnetFixture.server.url, store.resolve(server).url)
        } finally {
            lanFixture.close()
            tailnetFixture.close()
        }
    }

    @Test fun callersWithOtherCredentialsGetTheirOwnRaceAndKeepTheirServer() = runTest {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val store = ServerLinkStore(health = { server ->
            seen += server.password
            delay(60.milliseconds)
        })
        val other = saved.copy(password = "1800000000.other")
        val first = async { store.probe(saved) }
        delay(10.milliseconds)
        val second = async { store.probe(other) }
        assertEquals(saved.password, first.await().password)
        assertEquals(other.password, second.await().password)
        assertEquals(setOf(saved.password, other.password), seen.toSet())
    }

    @Test fun theEventStreamAdoptsTheAddressItConnectedThrough() {
        val store = ServerLinkStore(health = {})
        store.adopt(saved.at(tailnet))
        assertEquals(tailnet, store.resolve(saved).url)
        // An address the server no longer lists isn't used.
        val edited = OpenCodeServer(url = lan, username = "opencode", password = "pw")
        assertEquals(lan, store.resolve(edited).url)
    }

    @Test fun streamSilenceCountsFromTheLastLine() {
        val store = ServerLinkStore(health = {})
        val server = OpenCodeServer(url = "http://id", username = "", password = "")
        assertNull(store.streamSilence(server.persistenceID))
        store.noteStreamActivity(server)
        assertTrue((store.streamSilence(server.persistenceID) ?: 99.seconds) < 1.seconds)
    }

    // Identity

    @Test fun identityNeedsAnOverlappingAddressOnlyWithoutAPassword() {
        val open = OpenCodeServer(url = lan, username = "opencode", password = "", alternateURLs = listOf(tailnet))
        val ours = ServerInfoPayload(version = "2", pid = 1, urls = listOf("http://127.0.0.1:49374", "HTTP://100.114.13.77:49374/"))
        val foreign = ServerInfoPayload(version = "2", pid = 1, urls = listOf("http://192.168.1.155:4096"))
        val sameLANAddress = ServerInfoPayload(version = "2", pid = 1, urls = listOf("http://127.0.0.1:49374", lan))
        val silent = ServerInfoPayload(version = "2", pid = 1, urls = null)
        // Answering on the LAN address, it must list our tailnet one.
        assertTrue(ours.identifies(open))
        assertFalse("Someone else's OpenCode on a LAN", foreign.identifies(open))
        assertFalse("Another server at the same LAN address and port", sameLANAddress.identifies(open))
        assertFalse("A LAN answer that won't say where else it listens", silent.identifies(open))
        // Answering on the tailnet address: no café LAN can stand in for it.
        assertTrue(silent.identifies(open.at(tailnet)))
        assertTrue(ours.identifies(open.at(tailnet)))
        assertTrue("A password already proves who answered", foreign.identifies(saved))
        assertTrue("One address has nothing to confuse", foreign.identifies(OpenCodeServer(url = lan, username = "opencode", password = "")))
        // Two LAN addresses: the other one counts.
        val twoLAN = OpenCodeServer(url = lan, username = "opencode", password = "", alternateURLs = listOf("http://10.0.0.5:49374"))
        assertTrue(ServerInfoPayload(version = "2", pid = 1, urls = listOf("http://10.0.0.5:49374")).identifies(twoLAN))
        assertFalse(sameLANAddress.identifies(twoLAN))
    }

    @Test fun originsMatchAcrossSpellings() {
        assertEquals("http://box.local:80", OpenCodeServer.origin("HTTP://Box.local/"))
        assertEquals("https://box.ts.net:443", OpenCodeServer.origin("https://box.ts.net"))
        assertEquals("http://100.64.0.3:4096", OpenCodeServer.origin("http://100.64.0.3:4096/api"))
        assertNull(OpenCodeServer.origin("ftp://box"))
    }

    // Saved order

    @Test fun rememberingTheWinnerKeepsTheSavedAddressOrder() {
        val values = DependencyValues.testing().update { this[SharedStore] = SharedStore.inMemory() }
        DependencyValues.withValues(values) {
            val savedServer = shared(ServerSharedKeys.savedServer)
            val savedServers = shared(ServerSharedKeys.savedServers)
            savedServer.set(saved)
            savedServers.set(listOf(saved))

            saved.at(tailnet).remember()
            assertEquals(lan, savedServer.value?.url)
            assertEquals(listOf(lan), savedServers.value.map { it.url })

            // A new password from pairing again is saved; the order still isn't touched.
            val repaired = saved.at(tailnet).copy(password = "1800000000.new")
            repaired.remember()
            assertEquals("1800000000.new", savedServer.value?.password)
            assertEquals(lan, savedServer.value?.url)

            // Editing the name from Home, whose copy is at the address in use, keeps it too.
            val edited = repaired.copy(name = "Mini")
            edited.remember(repaired)
            assertEquals("Mini", savedServer.value?.name)
            assertEquals(listOf(lan), savedServers.value.map { it.url })
        }
    }

    @Test fun preferredAddressesAreSavedAndAServerSavedWithoutThemStillLoads() {
        val values = DependencyValues.testing().update { this[SharedStore] = SharedStore.inMemory() }
        DependencyValues.withValues(values) {
            val savedServer = shared(ServerSharedKeys.savedServer)
            val savedServers = shared(ServerSharedKeys.savedServers)
            pairedByURL.remember()
            // Reconnecting at another address keeps the paired order and the preference.
            pairedByURL.at(lan).remember()
            assertEquals(listOf(tailnet), savedServer.value?.preferredURLs)
            assertEquals(tailnet, savedServer.value?.url)
            assertEquals(listOf(pairedByURL), savedServers.value)
        }
        // Entries saved before preferred addresses were kept read back with none, so they race as before.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
        val old = json.decodeFromString(
            OpenCodeServer.serializer(),
            """{"url":"$lan","username":"opencode","password":"1790000000.token","alternateURLs":["$tailnet"],"name":"Adams Mini","headers":[]}""",
        )
        assertEquals(saved, old)
        assertEquals(old.candidateURLs, old.firstAttemptURLs)
        assertEquals(pairedByURL, json.decodeFromString(OpenCodeServer.serializer(), json.encodeToString(OpenCodeServer.serializer(), pairedByURL)))
    }

    // Network path

    @Test fun aSplitTunnelComingUpRestartingOrGoingDownIsAReroute() {
        val wifi = NetworkPathMonitor.Snapshot.Interface(type = "wifi", name = "en0")
        val cellular = NetworkPathMonitor.Snapshot.Interface(type = "cellular", name = "pdp_ip0")
        val utun4 = NetworkPathMonitor.Snapshot.Interface(type = "other", name = "utun4")
        val utun5 = NetworkPathMonitor.Snapshot.Interface(type = "other", name = "utun5")
        val satisfied = NetworkPathMonitor.Snapshot.Status.Satisfied
        val unsatisfied = NetworkPathMonitor.Snapshot.Status.Unsatisfied
        val plain = NetworkPathMonitor.Snapshot(satisfied, listOf(wifi))
        val tunnel = NetworkPathMonitor.Snapshot(satisfied, listOf(wifi, utun4))
        val restarted = NetworkPathMonitor.Snapshot(satisfied, listOf(wifi, utun5))
        val down = NetworkPathMonitor.Snapshot(unsatisfied, emptyList())
        assertTrue("Tunnel up", tunnel.isReroute(plain))
        assertTrue("Tunnel restarted on a new interface", restarted.isReroute(tunnel))
        assertTrue("Tunnel down", plain.isReroute(tunnel))
        assertTrue("Wi-Fi to cellular", NetworkPathMonitor.Snapshot(satisfied, listOf(cellular, utun4)).isReroute(tunnel))
        assertFalse("Cellular waiting in reserve moves nothing", NetworkPathMonitor.Snapshot(satisfied, listOf(wifi, cellular, utun4)).isReroute(tunnel))
        assertFalse(tunnel.isReroute(tunnel))
        assertFalse("Losing the path isn't a route to try", down.isReroute(tunnel))
        assertTrue("The path came back", tunnel.isReroute(down))
    }

    @Test fun aPathThatNeedsAConnectionIsTriedNotWaitedOn() {
        val dormant = NetworkPathMonitor.Snapshot(
            NetworkPathMonitor.Snapshot.Status.RequiresConnection,
            listOf(NetworkPathMonitor.Snapshot.Interface(type = "cellular", name = "pdp_ip0")),
        )
        assertFalse(dormant.isOffline)
        assertFalse(dormant.isSatisfied)
        assertTrue(NetworkPathMonitor.Snapshot(NetworkPathMonitor.Snapshot.Status.Unsatisfied, emptyList()).isOffline)
        assertFalse("The log names kinds, never interfaces", dormant.logSummary.contains("pdp_ip0"))
    }

    @Test fun aBurstOfPathChangesIsOneReroute() = runBlocking {
        val path = Channel<NetworkPathMonitor.Snapshot>(Channel.UNLIMITED)
        val reroutes = NetworkPathMonitor.reroutes(path.receiveAsFlow(), 60.milliseconds)
        val count = AtomicInteger(0)
        val listener = launch(Dispatchers.Default) { reroutes.collect { count.incrementAndGet() } }
        val satisfied = NetworkPathMonitor.Snapshot.Status.Satisfied
        val wifi = NetworkPathMonitor.Snapshot.Interface(type = "wifi", name = "en0")
        path.send(NetworkPathMonitor.Snapshot(satisfied, listOf(wifi)))
        // Wi-Fi joins, the tunnel attaches, then moves: one settled change.
        path.send(NetworkPathMonitor.Snapshot(satisfied, listOf(wifi, NetworkPathMonitor.Snapshot.Interface(type = "other", name = "utun3"))))
        path.send(NetworkPathMonitor.Snapshot(satisfied, listOf(wifi, NetworkPathMonitor.Snapshot.Interface(type = "other", name = "utun4"))))
        delay(300)
        assertEquals(1, count.get())
        // A change that drops the path before it settles isn't one.
        path.send(NetworkPathMonitor.Snapshot(satisfied, listOf(wifi)))
        path.send(NetworkPathMonitor.Snapshot(NetworkPathMonitor.Snapshot.Status.Unsatisfied, emptyList()))
        delay(300)
        assertEquals(1, count.get())
        path.close()
        listener.cancel()
    }

    // Event stream watchdog

    @Test fun aStreamThatNeverOpensExpiresAtItsConnectDeadline() = runTest {
        val clock = TestClock()
        val watchdog = EventStreamWatchdog(clock)
        val fired = AtomicInteger(0)
        watchdog.start(backgroundScope) { fired.incrementAndGet() }
        settle()
        clock.advance(EventStreamWatchdog.CONNECT_DEADLINE - 1.milliseconds)
        assertEquals(0, fired.get())
        clock.advance(1.milliseconds)
        settle(fired)
        assertEquals(1, fired.get())
        assertEquals(EventStreamWatchdog.Expiry.Connect, watchdog.expiry)
    }

    @Test fun heartbeatsKeepAStreamAliveAndSilenceEndsIt() = runTest {
        val clock = TestClock()
        val watchdog = EventStreamWatchdog(clock)
        val fired = AtomicInteger(0)
        watchdog.start(backgroundScope) { fired.incrementAndGet() }
        settle()
        watchdog.noteLine()
        clock.advance(EventStreamWatchdog.CONNECT_DEADLINE)
        // A heartbeat every 15 s for two minutes.
        repeat(8) {
            clock.advance(15.seconds)
            watchdog.noteLine()
        }
        assertEquals(0, fired.get())
        clock.advance(EventStreamWatchdog.IDLE_DEADLINE - 1.milliseconds)
        assertEquals(0, fired.get())
        clock.advance(1.milliseconds)
        settle(fired)
        assertEquals("Three missed heartbeats, 45 s after the last line exactly", 1, fired.get())
        assertEquals(EventStreamWatchdog.Expiry.Idle, watchdog.expiry)
    }

    /** A line between the watchdog's checks doesn't buy a second full wait: silence still ends the stream 45 s after that line. */
    @Test fun silenceCountsFromTheLastLineNotTheLastCheck() = runTest {
        val clock = TestClock()
        val watchdog = EventStreamWatchdog(clock)
        val fired = AtomicInteger(0)
        watchdog.start(backgroundScope) { fired.incrementAndGet() }
        settle()
        watchdog.noteLine()
        clock.advance(EventStreamWatchdog.CONNECT_DEADLINE)
        clock.advance(1.seconds)
        watchdog.noteLine()
        // The last line came 1 s after the check that began the wait.
        clock.advance(EventStreamWatchdog.IDLE_DEADLINE - 1.milliseconds)
        assertEquals(0, fired.get())
        clock.advance(1.milliseconds)
        settle(fired)
        assertEquals(1, fired.get())
    }

    /** The timer's coroutine acts on the wake a moment after the clock moves. */
    private suspend fun settle(fired: AtomicInteger? = null) {
        for (attempt in 0 until 200) {
            if (fired != null && fired.get() != 0) break
            yield()
        }
    }

    @Test fun aStoppedWatchdogNeverFires() = runTest {
        val clock = TestClock()
        val watchdog = EventStreamWatchdog(clock)
        val fired = AtomicInteger(0)
        watchdog.start(backgroundScope) { fired.incrementAndGet() }
        settle()
        watchdog.stop()
        clock.advance(120.seconds)
        assertEquals(0, fired.get())
    }

    /**
     * The tunnel goes away without closing the socket: the stream sent `server.connected`, then
     * nothing. It ends as a timeout, which the stream's owner retries, instead of waiting on a
     * dead read.
     */
    @Test fun aStreamStrandedOnADeadRouteEndsAsATimeout() {
        val fixture = WireFixture { Reply.Open(200, "data: {\"type\":\"server.connected\"}\n\n") }
        try {
            // Its first line arrives before the clock moves: silence counts from there.
            expectTimeout(fixture, EventStreamWatchdog.IDLE_DEADLINE, sendsALine = true)
        } finally {
            fixture.close()
        }
    }

    /** A blackholed address: no headers ever. It gives up at the connect deadline, not a minute later. */
    @Test fun aStreamThatNeverAnswersEndsAtTheConnectDeadline() {
        val fixture = WireFixture { Reply.Silent }
        try {
            expectTimeout(fixture, EventStreamWatchdog.CONNECT_DEADLINE, sendsALine = false)
        } finally {
            fixture.close()
        }
    }

    private fun expectTimeout(fixture: WireFixture, deadline: Duration, sendsALine: Boolean) = runBlocking {
        val clock = TestClock()
        val outcome = AtomicReference<OpenCodeClientError?>(null)
        val done = AtomicBoolean(false)
        val sawLine = AtomicBoolean(false)
        val reader = launch(Dispatchers.Default) {
            // The stream tells the link about each line; the test waits on that, not on a fixed
            // pause, so a slow machine can't read the first line after time has moved.
            withDependenciesSuspending({
                this[ServerLinkClient] = ServerLinkClient.testValue.copy(noteStreamActivity = { sawLine.set(true) })
            }) {
                NetworkRetry.clock.withValue(clock) {
                    try {
                        OpenCodeClient.liveValue.events(fixture.server).collect {}
                    } catch (error: OpenCodeClientError) {
                        outcome.set(error)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        fail("Unexpected $error")
                    }
                }
            }
            done.set(true)
        }
        // Let the request (and its first line, when the fixture sends one) land before time moves.
        for (attempt in 0 until 500) {
            val landed = if (sendsALine) sawLine.get() else fixture.requests.isNotEmpty()
            if (landed) break
            delay(10)
        }
        delay(50)
        clock.advance(deadline - 1.seconds)
        delay(50)
        assertFalse("Not before the deadline", done.get())
        // The watchdog wakes on another thread: under load it can read the clock before the jump
        // and register its next sleep after it, landing that sleep up to a deadline later. Keep
        // stepping (up to 100 s) until the stream ends rather than a fixed 20 s.
        for (attempt in 0 until 200) {
            if (done.get()) break
            clock.advance(500.milliseconds)
            delay(10)
        }
        // Past the deadline; a loaded machine can take a moment to wind the stream down.
        for (attempt in 0 until 500) {
            if (done.get()) break
            delay(10)
        }
        reader.cancel()
        assertTrue(done.get())
        assertEquals(TransportFailure.TimedOut, outcome.get()?.transport)
    }
}
