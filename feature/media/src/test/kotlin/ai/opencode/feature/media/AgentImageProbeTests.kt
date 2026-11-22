package ai.opencode.feature.media

import ai.opencode.core.testing.TestIsolationRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Shapes first, from a header probe. */
class AgentImageProbeTests {
    @get:Rule val isolation = TestIsolationRule()

    /** A prober the test answers by hand. */
    private class Prober {
        class Call(val request: AgentImageRequest, val entityTag: String?, val continuation: Continuation<AgentImageProbeResult>)

        private val pending = ArrayList<Call>()
        var count = 0
            private set

        val probe: AgentImageProber = { request, tag ->
            suspendCoroutine { continuation ->
                synchronized(this) {
                    count += 1
                    pending += Call(request, tag, continuation)
                }
            }
        }

        fun take(): Call? = synchronized(this) { if (pending.isEmpty()) null else pending.removeAt(0) }
    }

    private val key = AgentImageKey(server = "s", path = "/game/render.png")
    private val request = request("render.png")

    /** Reads never answer (they sleep a minute and are cancelled). */
    private fun TestScope.previews(prober: Prober, gate: Gate, sizes: AgentImageSizeCache = AgentImageSizeCache.inMemory) = AgentImagePreviews(
        sizes = sizes,
        loader = { _, _, _ ->
            delay(60_000)
            throw CancellationException()
        },
        decoder = AgentImageFixtures.decoder,
        sleep = { gate.wait() },
        prober = prober.probe,
        pathUpdates = { emptyFlow() },
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun theProbesShapeArrivesBeforeAnyPixels() = runTest {
        val prober = Prober()
        val sizes = AgentImageSizeCache.inMemory
        val previews = previews(prober, Gate(), sizes)
        var revised = 0
        previews.onLayoutChange = { revised += 1 }
        previews.probe(key, request)
        settle { prober.count == 1 }
        assertNull("held: no shape yet", previews.entry(key).size)
        prober.take()!!.continuation.resume(AgentImageProbeResult.Size(AgentImageSize(640.0, 360.0), entityTag = "\"v1\""))
        settle { previews.entry(key).size != null }
        assertEquals(AgentImageSize(640.0, 360.0), previews.entry(key).size)
        assertEquals("pixels are read separately", AgentImagePreviews.Phase.Waiting, previews.entry(key).phase)
        assertFalse(previews.entry(key).overdue)
        assertEquals("one layout change: the image enters", 1, revised)
        assertEquals(AgentImageSize(640.0, 360.0), sizes.size(key))
        assertEquals("\"v1\"", sizes.entityTag(key))
        previews.close()
    }

    @Test
    fun eachImageIsProbedOncePerRun() = runTest {
        val prober = Prober()
        val previews = previews(prober, Gate())
        previews.probe(key, request)
        previews.probe(key, request)
        settle { prober.count == 1 }
        settle()
        assertEquals(1, prober.count)
        previews.close()
    }

    @Test
    fun aSilentServerGetsAPlaceholderAfterTheDeadline() = runTest {
        val prober = Prober()
        val gate = Gate()
        val previews = previews(prober, gate)
        var revised = 0
        previews.onLayoutChange = { revised += 1 }
        previews.probe(key, request)
        settle { prober.count == 1 }
        assertFalse(previews.entry(key).overdue)
        gate.open()
        settle { previews.entry(key).overdue }
        assertTrue("a placeholder stands in", previews.entry(key).overdue)
        assertEquals(1, revised)
        // The shape lands late: one more change, the placeholder takes it.
        prober.take()!!.continuation.resume(AgentImageProbeResult.Size(AgentImageSize(300.0, 600.0), entityTag = null))
        settle { previews.entry(key).size != null }
        assertEquals(AgentImageSize(300.0, 600.0), previews.entry(key).size)
        assertEquals(2, revised)
        previews.close()
    }

    @Test
    fun aShapeInTimeNeverShowsAPlaceholder() = runTest {
        val prober = Prober()
        val gate = Gate()
        val previews = previews(prober, gate)
        previews.probe(key, request)
        settle { prober.count == 1 }
        prober.take()!!.continuation.resume(AgentImageProbeResult.Size(AgentImageSize(4.0, 3.0), entityTag = null))
        settle { previews.entry(key).size != null }
        gate.open()
        settle()
        assertFalse(previews.entry(key).overdue)
        previews.close()
    }

    @Test
    fun aMissingFileIsNotFoundFromItsProbe() = runTest {
        val prober = Prober()
        val previews = previews(prober, Gate())
        previews.probe(key, request)
        settle { prober.count == 1 }
        prober.take()!!.continuation.resume(AgentImageProbeResult.NotFound)
        settle { previews.entry(key).phase == AgentImagePreviews.Phase.Missing }
        assertEquals(AgentImagePreviews.Phase.Missing, previews.entry(key).phase)
        previews.close()
    }

    @Test
    fun aRememberedShapeIsRevalidatedByItsETag() = runTest {
        val prober = Prober()
        val sizes = AgentImageSizeCache.inMemory
        sizes.store(AgentImageSize(640.0, 360.0), key)
        sizes.storeEntityTag("\"v1\"", key)
        val previews = previews(prober, Gate(), sizes)
        previews.probe(key, request)
        settle { prober.count == 1 }
        val call = prober.take()!!
        assertEquals("\"v1\"", call.entityTag)
        assertEquals("shown at once at its remembered shape", AgentImageSize(640.0, 360.0), previews.entry(key).size)
        // The file changed shape since: the new one replaces it.
        call.continuation.resume(AgentImageProbeResult.Size(AgentImageSize(360.0, 640.0), entityTag = "\"v2\""))
        settle { previews.entry(key).size?.width == 360.0 }
        assertEquals(AgentImageSize(360.0, 640.0), previews.entry(key).size)
        assertEquals("\"v2\"", sizes.entityTag(key))
        previews.close()
    }

    private class ProbeHarness(val loader: ManualImageLoader, val prober: Prober, val previews: AgentImagePreviews)

    private fun TestScope.probeHarness(sizes: AgentImageSizeCache = AgentImageSizeCache.inMemory, gate: Gate? = null): ProbeHarness {
        val loader = ManualImageLoader()
        val prober = Prober()
        val previews = makePreviews(
            loader,
            sizes = sizes,
            sleep = { if (gate != null) gate.wait() else delay(60_000) },
            prober = prober.probe,
        )
        return ProbeHarness(loader, prober, previews)
    }

    @Test
    fun aLateProbeNeverOverridesANewerRead() = runTest {
        val sizes = AgentImageSizeCache.inMemory
        with(probeHarness(sizes = sizes)) {
            val sighting = AgentImageSighting(key, "reply-1")
            previews.probe(key, request)
            previews.show(sighting, request)
            settle { prober.count == 1 && loader.count == 1 }
            // The file was overwritten: the read's header (version B) arrives first.
            val read = loader.take("render.png")!!
            read.onSize(AgentImageSize(30.0, 40.0))
            settle { previews.entry(key).size != null }
            // The probe that started earlier answers with version A.
            prober.take()!!.continuation.resume(AgentImageProbeResult.Size(AgentImageSize(40.0, 30.0), entityTag = "\"a\""))
            settle()
            assertEquals("the newer read's shape stays", AgentImageSize(30.0, 40.0), previews.entry(key).size)
            assertNull("no old validator paired with the new shape", sizes.entityTag(key))
            read.resume(AgentImageResponse.File(AgentImageFixtures.blue, AgentImageValidators(entityTag = "\"b\"")))
            settle { previews.entry(key).phase == AgentImagePreviews.Phase.Loaded }
            assertEquals(AgentImageSize(30.0, 40.0), sizes.size(key))
            assertEquals("\"b\"", sizes.entityTag(key))
            previews.close()
        }
    }

    @Test
    fun aProbe404AfterANewerReadIsIgnored() = runTest {
        with(probeHarness()) {
            previews.probe(key, request)
            previews.show(AgentImageSighting(key, "reply-1"), request)
            settle { prober.count == 1 && loader.count == 1 }
            loader.take("render.png")!!.onSize(AgentImageSize(30.0, 40.0))
            settle { previews.entry(key).size != null }
            prober.take()!!.continuation.resume(AgentImageProbeResult.NotFound)
            settle()
            assertEquals("the file the read is getting exists", AgentImagePreviews.Phase.Loading, previews.entry(key).phase)
            previews.close()
        }
    }

    @Test
    fun aNewerReadsHeaderOverridesAProbes404() = runTest {
        with(probeHarness()) {
            previews.probe(key, request)
            previews.show(AgentImageSighting(key, "reply-1"), request)
            settle { prober.count == 1 && loader.count == 1 }
            // The probe found nothing; the file appears, and the read's header says so.
            prober.take()!!.continuation.resume(AgentImageProbeResult.NotFound)
            settle { previews.entry(key).phase == AgentImagePreviews.Phase.Missing }
            val read = loader.take("render.png")!!
            read.onSize(AgentImageSize(30.0, 40.0))
            settle { previews.entry(key).phase != AgentImagePreviews.Phase.Missing }
            assertEquals(AgentImagePreviews.Phase.Loading, previews.entry(key).phase)
            assertEquals(AgentImageSize(30.0, 40.0), previews.entry(key).size)
            read.resume(AgentImageResponse.File(AgentImageFixtures.blue, AgentImageValidators()))
            settle { previews.entry(key).phase == AgentImagePreviews.Phase.Loaded }
            assertEquals(AgentImagePreviews.Phase.Loaded, previews.entry(key).phase)
            previews.close()
        }
    }

    @Test
    fun anImageHeldAgainGetsANewDeadline() = runTest {
        val gate = Gate()
        with(probeHarness(gate = gate)) {
            val sighting = AgentImageSighting(key, "reply-1")
            previews.probe(key, request)
            previews.show(sighting, request)
            settle { prober.count == 1 && loader.count == 1 }
            prober.take()!!.continuation.resume(AgentImageProbeResult.Unknown)
            // The deadline passes, and every read fails: a card, still overdue.
            gate.open()
            settle { previews.entry(key).overdue }
            repeat(4) {
                settle { loader.waiting.size == 1 }
                loader.take("render.png")?.fail(AgentImageDownload.Failure.Failed)
                settle()
            }
            assertEquals(AgentImagePreviews.Phase.Unavailable, previews.entry(key).phase)
            assertTrue("the placeholder's deadline isn't forgotten", previews.entry(key).overdue)
            assertFalse(AgentImagePreviews.isHeld(previews.entry(key)))
            // A later reply names it; its read fails on the way again: never held.
            previews.hide(sighting)
            previews.show(AgentImageSighting(key, "reply-2"), request)
            settle { loader.waiting.size == 1 }
            loader.take("render.png")?.fail(AgentImageDownload.Failure.Failed)
            settle()
            assertFalse("a placeholder stands in, not nothing", AgentImagePreviews.isHeld(previews.entry(key)))
            previews.close()
        }
    }

    @Test
    fun aProbeAsksForTheFirstBytesOnly() {
        val ranged = request.request.newBuilder().header("Range", "bytes=0-${AgentImageProbe.BYTES - 1}").build()
        assertEquals("bytes=0-131071", ranged.header("Range"))
        assertNull(AgentImageProbe.client.cache)
        assertTrue("never waits behind pixel reads", AgentImageProbe.client.connectionPool !== AgentImageDownload.client.connectionPool)
    }
}
