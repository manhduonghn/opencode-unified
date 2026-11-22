package ai.opencode.feature.media

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.motion.MotionRole
import ai.opencode.server.retry.NetworkPathMonitor
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.PowerManager
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Where an image is named: one reply (or one place) that shows it. Each new one rechecks the
 * file, since agents overwrite renders in place.
 */
data class AgentImageSighting(val key: AgentImageKey, val occurrence: String) {
    /** The media ID this thumbnail and its full-screen page share. */
    val mediaID: String get() = "agent-image:$occurrence|${key.path}"
}

/** A previewed file: its server (`OpenCodeServer.persistenceID`) and absolute path (iOS `AgentImagePreviews.Key`). */
data class AgentImageKey(val server: String, val path: String)

/** The process's monotonic clock in seconds (iOS `Date.timeIntervalSinceReferenceDate` within one run); the frame clock's base. */
internal fun agentImageNow(): Double = System.nanoTime() / 1e9

/**
 * The previews of images a reply names (iOS `AgentImagePreviews`): each file's shape, then its
 * pixels, shared by every place that shows it. Reads only what's on screen
 * ([AgentImageVisibility]), a few at a time, and lets go of a read nobody is waiting on. Each new
 * place an image is named rechecks it (with the server's validators when it sent any) while the
 * pixels already read stay up; a missing or unreadable image is tried again there too, and a
 * read that failed on the way is retried with backoff while it's on screen.
 *
 * Main-thread only (iOS `@MainActor`). Observable state is Compose snapshot state; the rest is
 * plain (iOS `@ObservationIgnored`).
 */
class AgentImagePreviews(
    private val sizes: AgentImageSizeCache = AgentImageSizeCache.live,
    private val loader: AgentImageLoader = { request, validators, onSize ->
        AgentImageDownload.load(request, validators, MAX_BYTES, onSize = onSize)
    },
    private val decoder: AgentImageDecoder = { decode(it, MAX_PIXEL_SIZE) },
    private val sleep: AgentImageSleep = { delay(it) },
    private val prober: AgentImageProber = { request, tag -> AgentImageProbe.load(request, tag) },
    private val shouldDeferPrefetch: () -> Boolean = { false },
    pathUpdates: () -> Flow<NetworkPathMonitor.Snapshot> = { NetworkPathMonitor.shared?.updates() ?: emptyFlow() },
    private val isLowPowerModeEnabled: () -> Boolean = { false },
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val now: () -> Double = ::agentImageNow,
    /**
     * Where a finished read also goes, for the viewer and the next launch: the shared freshness and
     * the disk copy (`AgentImageCache`). Null keeps reads to this cache.
     */
    private val shared: SharedImageStore? = null,
    /**
     * A copy of the file this device already holds, answered as a read (live: the viewer's disk
     * copy, used as is while fresh, otherwise confirmed by a conditional GET). Null reads the server.
     */
    private val local: (suspend (AgentImageKey, AgentImageRequest) -> LocalRead?)? = null,
) {
    /** A read answered from this device's copy; [checked]: the server confirmed or replaced it just now. */
    class LocalRead(val response: AgentImageResponse.File, val checked: Boolean)

    /** Hands a read to the files' shared cache (live: [AgentImageCache] and the viewer's disk copies). */
    fun interface SharedImageStore {
        /** [data] is null for a 304: the copy on disk, if any, still holds. */
        fun read(key: AgentImageKey, data: ByteArray?, validators: AgentImageValidators?)
    }

    /** Waits for a read of [key] under way, so another reader of the same file doesn't read it again. */
    suspend fun awaitRead(key: AgentImageKey) {
        reads[key]?.takeIf { !it.cancelled }?.job?.join()
    }

    enum class Phase {
        /** Not read yet: on screen it waits its turn. */
        Waiting,
        Loading,
        Loaded,
        Missing,
        /** Too large, or the server couldn't read it: today's card. */
        Unavailable,
    }

    data class Entry(
        val phase: Phase = Phase.Waiting,
        /** Display pixels, known from the header, a past read, or the image. */
        val size: AgentImageSize? = null,
        /** When the pixels landed ([agentImageNow]), so only an arrival fades in. */
        val loadedAt: Double? = null,
        /** A recheck is under way while what was read before stays up. */
        val rechecking: Boolean = false,
        /** The shape is still unknown past [SHAPE_DEADLINE]: a placeholder at a guessed shape stands in rather than nothing. */
        val overdue: Boolean = false,
    )

    private class Read(
        val id: Int,
        val job: Job,
        /** The sightings it reads for: showing one again doesn't ask twice. */
        val sightings: Set<AgentImageSighting>,
        var cancelled: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val entriesState = mutableStateMapOf<AgentImageKey, Entry>()
    val entries: Map<AgentImageKey, Entry> get() = entriesState

    /** Files outside the session's folder, or deferred under a constrained network, that someone tapped to load. */
    var allowed: Set<AgentImageKey> by mutableStateOf(emptySet())
        private set
    /** Keys whose automatic prefetch was deferred or refused on a constrained network. */
    var constrained: Set<AgentImageKey> by mutableStateOf(emptySet())
        private set
    /** Whether the active network path defers prefetches. */
    var isNetworkConstrained: Boolean by mutableStateOf(false)
        private set
    /** Sightings on screen, counted per marker: only these hold pixels. */
    private val visibleState = mutableStateMapOf<AgentImageSighting, Int>()
    val visible: Map<AgentImageSighting, Int> get() = visibleState
    /** Sightings on screen whose request may use a constrained network (a foreground screen the person opened). */
    private val onDemand = HashMap<AgentImageSighting, Int>()

    private fun isOnDemand(key: AgentImageKey): Boolean = onDemand.keys.any { it.key == key }

    /**
     * Called before an entry's change reaches any view when it changes the layout (a shape
     * becoming known, a file turning out missing), so the list can ease the rows it's in.
     */
    var onLayoutChange: ((AgentImageKey) -> Unit)? = null

    private val images = object : LruCache<String, Bitmap>(96 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    /** Pixels on screen now: the cache may let go of anything else. */
    private val pinned = HashMap<AgentImageKey, Bitmap>()
    private val requests = HashMap<AgentImageKey, AgentImageRequest>()
    private val interest = HashMap<AgentImageKey, Int>()
    private val queue = ArrayList<AgentImageKey>()
    /** Markers in view (not just in the margin) per key: their reads go first. */
    private val inView = HashMap<AgentImageKey, Int>()
    private val reads = HashMap<AgentImageKey, Read>()
    private var nextReadID = 0
    /** Sightings a read has answered: they don't read again. */
    private val fresh = HashSet<AgentImageSighting>()
    /** Sightings waiting for a read that starts after they were seen. */
    private val unanswered = HashMap<AgentImageKey, MutableSet<AgentImageSighting>>()
    private val validators = HashMap<AgentImageKey, AgentImageValidators>()
    private val failures = HashMap<AgentImageKey, Int>()
    private val retries = HashMap<AgentImageKey, Job>()
    /** Sightings a failed read didn't answer: the retry reads for them. */
    private val retrying = HashMap<AgentImageKey, MutableSet<AgentImageSighting>>()
    /** Keys probed this run: each is probed once (a new place rechecks with its read). */
    private val probed = HashSet<AgentImageKey>()
    private val deadlines = HashMap<AgentImageKey, Job>()
    /** Galleries (a reply's block) that have shown something: they keep it while an image named later fetches its shape, across reloads. */
    private val presented = HashSet<String>()
    private val presentedOrder = ArrayList<String>()
    /** Counts what full reads learned about each file's shape (a header, the whole file, a 404), so a probe that started before never overrides it. */
    private var readEvents = 0
    private val lastReadEvent = HashMap<AgentImageKey, Int>()
    private val constrainedOrder = ArrayList<AgentImageKey>()
    private val constrainedRetries = HashMap<AgentImageKey, Int>()
    private val constrainedSightings = HashMap<AgentImageKey, MutableSet<AgentImageSighting>>()
    private var pathJob: Job? = null

    init {
        isNetworkConstrained = shouldDeferPrefetch()
        pathJob = scope.launch {
            var wasConstrained = shouldDeferPrefetch()
            pathUpdates().collect { snapshot ->
                val snapshotConstrained = !snapshot.isSatisfied ||
                    NetworkPathMonitor.shouldDeferLargePrefetch(snapshot, isLowPowerModeEnabled())
                val deferred = snapshotConstrained || shouldDeferPrefetch()
                isNetworkConstrained = deferred
                if (!deferred && wasConstrained) retryDeferredOnUnconstrainedPath()
                wasConstrained = deferred
            }
        }
    }

    /** Stops observing the network path (iOS `deinit`). */
    fun close() {
        pathJob?.cancel()
    }

    fun entry(key: AgentImageKey): Entry = entriesState[key] ?: Entry(size = sizes.size(key))

    fun isAllowed(key: AgentImageKey): Boolean = key in allowed

    /**
     * True when automatic background prefetch for [key] is deferred by a constrained network
     * (Data Saver, or Battery Saver on a metered link) and the person hasn't explicitly tapped to
     * load it yet. Already-loaded images stay visible.
     */
    fun isDeferredByNetwork(key: AgentImageKey): Boolean {
        if (key in allowed || entry(key).phase == Phase.Loaded) return false
        @Suppress("UNUSED_VARIABLE") val observed = isNetworkConstrained
        return key in constrained || shouldDeferPrefetch()
    }

    private fun recordConstrained(key: AgentImageKey, sightings: Set<AgentImageSighting> = emptySet()) {
        if (key !in constrained) {
            constrainedOrder.add(key)
            var next = constrained
            while (constrainedOrder.size > MAXIMUM_DEFERRED_KEYS) {
                val evicted = constrainedOrder.removeAt(0)
                next = next - evicted
                constrainedSightings.remove(evicted)
            }
            constrained = next + key
        }
        if (sightings.isNotEmpty()) constrainedSightings.getOrPut(key) { HashSet() }.addAll(sightings)
    }

    /**
     * Retries visible/pending deferred image probes and prefetches (bounded by
     * [MAXIMUM_CONSTRAINED_RETRIES]) when the network path becomes unconstrained.
     */
    fun retryDeferredOnUnconstrainedPath() {
        val stillDeferred = shouldDeferPrefetch()
        isNetworkConstrained = stillDeferred
        if (stillDeferred) return
        val keys = constrained + constrainedSightings.keys
        if (keys.isEmpty()) return
        for (key in keys) {
            val attempts = constrainedRetries[key] ?: 0
            if (attempts >= MAXIMUM_CONSTRAINED_RETRIES) {
                constrained = constrained - key
                constrainedSightings.remove(key)
                continue
            }
            constrainedRetries[key] = attempts + 1
            val wasConstrained = key in constrained
            if (wasConstrained) constrained = constrained - key
            val pendingSightings = constrainedSightings.remove(key) ?: emptySet<AgentImageSighting>()
            if (wasConstrained && entry(key).phase != Phase.Loaded) onLayoutChange?.invoke(key)
            if (key !in probed && entry(key).phase != Phase.Loaded) requests[key]?.let { probe(key, it) }
            val onScreen = visibleState.keys.filter { it.key == key && it !in fresh }
            val wanting = pendingSightings.intersect(visibleState.keys) + onScreen
            if (wanting.isEmpty() || (interest[key] ?: 0) <= 0) continue
            unanswered.getOrPut(key) { HashSet() }.addAll(wanting)
            enqueue(key)
        }
    }

    fun isPresented(place: String): Boolean = place in presented

    /** When a tile's pixel fade began, by media ID and arrival, kept here so a reloaded row carries on (or shows settled pixels) rather than fading again. */
    private val fadeStarts = HashMap<String, Double>()
    private val tilesShown = HashMap<String, Double>()

    /** When the tile [mediaID] was first drawn this run (now, the first time). */
    fun tileFirstShown(mediaID: String, now: Double): Double {
        tilesShown[mediaID]?.let { return it }
        if (tilesShown.size > 2000) tilesShown.clear()
        tilesShown[mediaID] = now
        return now
    }

    /** The fade's start for pixels that landed at [loadedAt]: the first frame that asks, if that's soon after they landed, else their landing. */
    fun fadeStart(mediaID: String, loadedAt: Double, now: Double, fresh: Double, notBefore: Double = 0.0): Double {
        val key = "$mediaID|$loadedAt"
        fadeStarts[key]?.let { return it }
        val start = if (now - loadedAt < fresh) max(now, notBefore) else loadedAt
        if (fadeStarts.size > 2000) fadeStarts.clear()
        fadeStarts[key] = start
        return start
    }

    /**
     * Lists whose session is still opening: a gallery that couldn't show at its first sight waits
     * for the opening to settle, then enters on the row's growth, rather than changing rows while
     * the list lands. Held weakly.
     */
    private val entranceHolds: MutableSet<AgentImageViewport> = Collections.newSetFromMap(WeakHashMap())
    private var publishedEntranceHold = false
    private var entranceHoldsRevision by mutableIntStateOf(0)

    val holdsEntrances: Boolean
        get() {
            @Suppress("UNUSED_VARIABLE") val observed = entranceHoldsRevision
            return entranceHolds.toList().any { it.imageViewportIsVisible && it.imageViewportIsSettling }
        }

    fun holdEntrances(holder: AgentImageViewport, hold: Boolean) {
        if ((holder in entranceHolds) == hold) return
        if (hold) entranceHolds.add(holder) else entranceHolds.remove(holder)
        refreshEntranceHolds()
    }

    /** A holder may settle, become hidden, or disappear without a membership edit. */
    fun refreshEntranceHolds() {
        val held = entranceHolds.toList().any { it.imageViewportIsVisible && it.imageViewportIsSettling }
        if (held == publishedEntranceHold) return
        publishedEntranceHold = held
        entranceHoldsRevision += 1
    }

    /** Whether every image at `place` had a shape the first time it was drawn (remembered shapes): such a gallery shows from the first frame. */
    private val firstSight = HashMap<String, Boolean>()

    fun readyAtFirstSight(place: String, ready: Boolean): Boolean {
        firstSight[place]?.let { return it }
        if (firstSight.size > 2000) firstSight.clear()
        firstSight[place] = ready
        return ready
    }

    // Bentos

    /**
     * One decided arrangement of a gallery's images: their paths in the order named and the
     * shapes it was laid out for, kept whatever shapes arrive later (a placeholder's real one, a
     * recheck's), so nothing in it moves. [decidedAt]: when it was decided; its tiles' pixels
     * fade in no sooner, staggered.
     */
    data class BentoBatch(val paths: List<String>, val aspects: List<Double>, val decidedAt: Double) {
        fun aspect(path: String): Double? = paths.indexOf(path).takeIf { it >= 0 }?.let { aspects[it] }
    }

    private val bentos = HashMap<String, MutableList<BentoBatch>>()
    private val bentoOrder = ArrayList<String>()
    /** When each gallery's undecided images were first seen. */
    private val gatheringSince = HashMap<String, Double>()
    private val gatherWakes = HashMap<String, Job>()

    /** Bumped when a gallery's gathering ends with nothing else changing, so it decides (galleries read it). */
    var bentoRevision: Int by mutableIntStateOf(0)
        private set

    /** The batches decided at [place]. */
    fun bentoBatches(place: String): List<BentoBatch> = bentos[place]?.toList() ?: emptyList()

    /**
     * Decides the next batch at [place] from the previews not in one yet ([pending], in the order
     * named; aspect null while a shape isn't known): once every one has a shape, and the block
     * has stopped naming images (or [bentoGather] has passed since the first of them was seen).
     * Decided, a batch never changes; images named later make a batch of their own.
     */
    fun settleBento(place: String, pending: List<Pair<AgentImageKey, Double?>>, gathering: Boolean, now: Double): List<BentoBatch> {
        val decided = bentoBatches(place)
        if (pending.isEmpty()) {
            gatheringSince.remove(place)
            return decided
        }
        val since = gatheringSince[place] ?: now
        gatheringSince[place] = since
        if (!pending.all { it.second != null }) return decided
        val window = bentoGather.inWholeNanoseconds / 1e9
        if (gathering && now - since < window) {
            wakeAfterGathering(place, pending[0].first, since + window, now)
            return decided
        }
        val batch = BentoBatch(pending.map { it.first.path }, pending.map { it.second ?: AgentImagePlan.PLACEHOLDER_ASPECT }, now)
        if (bentos[place] == null) {
            bentoOrder.add(place)
            if (bentoOrder.size > 2000) bentos.remove(bentoOrder.removeAt(0))
        }
        bentos.getOrPut(place) { ArrayList() }.add(batch)
        gatheringSince.remove(place)
        trace("image-bento", "count" to batch.paths.size, "arrangement" to AgentImageBento(batch.aspects, 360.0).arrangement.rawValue)
        return decided + batch
    }

    /** At the end of [place]'s gathering, its row hears of the layout change first, then the gallery decides. */
    private fun wakeAfterGathering(place: String, key: AgentImageKey, time: Double, now: Double) {
        if (gatherWakes[place] != null) return
        val delay = max(0.0, time - now)
        gatherWakes[place] = scope.launch {
            runCatching { sleep((delay * 1000).toInt().milliseconds + 1.milliseconds) }
            gatherWakes.remove(place)
            onLayoutChange?.invoke(key)
            bentoRevision += 1
        }
    }

    /** The gallery at [place] showed something (bounded to recent places). */
    fun markPresented(place: String) {
        if (!presented.add(place)) return
        presentedOrder.add(place)
        if (presentedOrder.size > 2000) presented.remove(presentedOrder.removeAt(0))
    }

    fun allow(key: AgentImageKey) {
        val wasDeferred = isDeferredByNetwork(key)
        allowed = allowed + key
        constrained = constrained - key
        constrainedSightings.remove(key)
        constrainedRetries.remove(key)
        if (entry(key).phase != Phase.Loaded) fresh.removeAll { it.key == key }
        if (wasDeferred) onLayoutChange?.invoke(key)
    }

    fun image(key: AgentImageKey): Bitmap? = pinned[key] ?: images.get(cacheKey(key))

    /** Whether a full-screen page may show this sighting's pixels: read, and confirmed for its place. */
    fun isConfirmed(sighting: AgentImageSighting): Boolean =
        entry(sighting.key).phase == Phase.Loaded && sighting in fresh && image(sighting.key) != null

    /** Keeps the pixels of a key on screen out of the cache's reach. */
    private fun pin(key: AgentImageKey) {
        if ((interest[key] ?: 0) <= 0) return
        val image = images.get(cacheKey(key)) ?: pinned[key] ?: return
        pinned[key] = image
    }

    fun isVisible(sighting: AgentImageSighting): Boolean = (visibleState[sighting] ?: 0) > 0

    /** Whether a read since this sighting appeared has answered for it. */
    fun isFresh(sighting: AgentImageSighting): Boolean = sighting in fresh

    private fun store(image: Bitmap, key: AgentImageKey) {
        images.put(cacheKey(key), image)
        if (pinned[key] != null || (interest[key] ?: 0) > 0) pinned[key] = image
    }

    /** The file is gone or unreadable now: its old pixels go too. */
    private fun forget(key: AgentImageKey) {
        images.remove(cacheKey(key))
        pinned.remove(key)
        validators.remove(key)
    }

    /** Lets every unpinned pixel go (memory pressure, iOS `NSCache` eviction). */
    fun trimMemory() {
        images.evictAll()
    }

    /** Reads under way, for diagnostics: never more than [CONCURRENT_READS]. */
    internal val activeReads: Int get() = reads.size

    /** Stores [entry], telling the list first when it changes the layout. */
    private fun set(key: AgentImageKey, entry: Entry) {
        val old = entry(key)
        if (entriesState[key] == entry) return
        if (old.size != entry.size || shows(old.phase) != shows(entry.phase) || isHeld(old) != isHeld(entry)) {
            onLayoutChange?.invoke(key)
        }
        entriesState[key] = entry
        // Held again (a card whose retry starts with no shape): a fresh deadline.
        if (isHeld(entry) && !isHeld(old) && key in probed) armDeadline(key)
    }

    /**
     * Changes [key]'s entry where it stands: every write goes through [set], so a layout change
     * always reaches the list first.
     */
    private fun mutate(key: AgentImageKey, change: (Entry) -> Entry) {
        set(key, change(entry(key)))
    }

    /**
     * Settles [key] at [phase], keeping the shape and deadline it was laid out at: what it shows
     * is no longer the pixels that were up.
     */
    private fun settle(key: AgentImageKey, phase: Phase) {
        mutate(key) { it.copy(phase = phase, loadedAt = null, rechecking = false) }
    }

    // Shapes

    /**
     * Learns [key]'s shape from its first bytes, as soon as a reply names it (not when it's on
     * screen: the shape decides the layout around it). Once per run; a remembered shape is
     * revalidated by its ETag. An unknown shape gets [SHAPE_DEADLINE] before a placeholder stands in.
     */
    fun probe(key: AgentImageKey, request: AgentImageRequest) {
        if (requests[key] == null) requests[key] = request
        if (isDeferredByNetwork(key)) {
            recordConstrained(key)
            return
        }
        if (!probed.add(key)) return
        // Its entry from now, so a change is measured against what's drawn.
        if (entriesState[key] == null) entriesState[key] = entry(key)
        val known = entry(key).size != null
        val tag = if (known) sizes.entityTag(key) else null
        val started = now()
        val since = readEvents
        val sent = request.copy(allowsConstrainedNetworkAccess = isAllowed(key))
        scope.launch {
            val result = try {
                prober(sent, tag)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AgentImageProbeResult.Unknown
            }
            didProbe(key, result, since, now() - started)
        }
        if (!known) armDeadline(key)
    }

    /** An image held out of the layout gets [SHAPE_DEADLINE] before a placeholder stands in, whenever it becomes held. */
    private fun armDeadline(key: AgentImageKey) {
        if (deadlines[key] != null) return
        deadlines[key] = scope.launch {
            runCatching { sleep(SHAPE_DEADLINE) }
            if (!currentCoroutineContext().isActive) return@launch
            deadlines.remove(key)
            if (!isHeld(entry(key))) return@launch
            mutate(key) { it.copy(overdue = true) }
            trace("image-overdue")
        }
    }

    /** A full read learned something about [key]'s shape: older probes yield. */
    private fun noteRead(key: AgentImageKey) {
        readEvents += 1
        lastReadEvent[key] = readEvents
    }

    private fun didProbe(key: AgentImageKey, result: AgentImageProbeResult, since: Int, elapsed: Double) {
        val ms = (elapsed * 1000).toInt()
        val entry = entry(key)
        // A full read heard from the file since this probe started: it's newer.
        if ((lastReadEvent[key] ?: 0) > since || entry.phase == Phase.Loaded) {
            trace("image-probe", "ms" to ms, "result" to "superseded")
            return
        }
        when (result) {
            is AgentImageProbeResult.Size -> {
                if (entry.size != result.size) {
                    val wasOverdue = entry.overdue
                    mutate(key) { it.copy(size = result.size) }
                    trace("image-shape", "ms" to ms, "source" to "probe", "late" to wasOverdue)
                }
                sizes.store(result.size, key)
                sizes.storeEntityTag(result.entityTag, key)
            }
            AgentImageProbeResult.NotFound -> {
                forget(key)
                settle(key, Phase.Missing)
            }
            AgentImageProbeResult.NotModified, AgentImageProbeResult.Unknown -> Unit
        }
        trace("image-probe", "ms" to ms, "result" to result.traceName)
    }

    // On screen

    /** A marker of [sighting] came on screen: read it unless a read has answered for it already (a recheck when its pixels are up). */
    fun show(sighting: AgentImageSighting, request: AgentImageRequest) {
        val key = sighting.key
        visibleState[sighting] = (visibleState[sighting] ?: 0) + 1
        if (request.allowsConstrainedNetworkAccess) onDemand[sighting] = (onDemand[sighting] ?: 0) + 1
        interest[key] = (interest[key] ?: 0) + 1
        requests[key] = request
        if (entriesState[key] == null) entriesState[key] = entry(key)
        if (entry(key).phase == Phase.Loaded) pin(key)
        if (entry(key).phase == Phase.Loaded && image(key) == null) {
            // The cache let the pixels go: they're owed again, to every place that shows them,
            // until a read brings them (however it's cancelled meanwhile).
            fresh.removeAll { it.key == key }
            mutate(key) { it.copy(phase = Phase.Waiting, loadedAt = null) }
        }
        if (!isOnDemand(key) && isDeferredByNetwork(key)) {
            recordConstrained(key, setOf(sighting))
            return
        }
        if (sighting in fresh) return
        // Already asked for: by the read under way, or the retry waiting.
        reads[key]?.let { if (!it.cancelled && sighting in it.sightings) return }
        if (retrying[key]?.contains(sighting) == true && retries[key] != null) return
        // A new place, with no retry under way or queued for it: a fresh retry budget.
        if (retries[key] == null && retrying[key]?.contains(sighting) != true && unanswered[key]?.contains(sighting) != true) {
            failures.remove(key)
        }
        unanswered.getOrPut(key) { HashSet() }.add(sighting)
        enqueue(key)
    }

    /** A marker of [key] came into view: its waiting read goes ahead of the margin's. */
    fun enterView(key: AgentImageKey) {
        inView[key] = (inView[key] ?: 0) + 1
        pump()
    }

    /** A marker of [key] left the view (for the margin, or the screen). */
    fun leaveView(key: AgentImageKey) {
        val count = inView[key] ?: return
        if (count > 1) inView[key] = count - 1 else inView.remove(key)
    }

    /** A marker of [sighting] left the screen. */
    fun hide(sighting: AgentImageSighting) {
        val key = sighting.key
        visibleState[sighting]?.let { count -> if (count > 1) visibleState[sighting] = count - 1 else visibleState.remove(sighting) }
        onDemand[sighting]?.let { count -> if (count > 1) onDemand[sighting] = count - 1 else onDemand.remove(sighting) }
        interest[key] = max(0, (interest[key] ?: 0) - 1)
        if (interest[key] != 0) return
        scope.launch {
            runCatching { sleep(RELEASE_DELAY) }
            if (interest[key] != 0) return@launch
            pinned.remove(key)
            queue.removeAll { it == key }
            cancelRead(key)
        }
    }

    private fun cancelRead(key: AgentImageKey) {
        val read = reads[key] ?: return
        if (read.cancelled) return
        // It stays counted until its job has finished, so no more than CONCURRENT_READS ever run.
        read.cancelled = true
        read.job.cancel()
        mutate(key) { entry ->
            val phase = if (entry.phase == Phase.Loading) Phase.Waiting else entry.phase
            entry.copy(phase = phase, rechecking = false)
        }
    }

    private fun enqueue(key: AgentImageKey) {
        if (key !in queue && reads[key]?.cancelled != false) queue.add(key)
        pump()
    }

    private fun pump() {
        // What's in view first, then the margin, each in the order asked.
        while (reads.size < CONCURRENT_READS) {
            var index = queue.indexOfFirst { reads[it] == null && inView[it] != null }
            if (index < 0) index = queue.indexOfFirst { reads[it] == null }
            if (index < 0) break
            val key = queue.removeAt(index)
            val request = requests[key] ?: continue
            if ((interest[key] ?: 0) <= 0) continue
            start(key, request)
        }
    }

    private fun start(key: AgentImageKey, request: AgentImageRequest) {
        nextReadID += 1
        val id = nextReadID
        val sightings = unanswered.remove(key)?.toSet() ?: emptySet()
        val rechecks = entry(key).phase == Phase.Loaded && image(key) != null
        mutate(key) { entry ->
            if (rechecks) {
                entry.copy(rechecking = true)
            } else if (entry.phase == Phase.Waiting) {
                entry.copy(phase = Phase.Loading)
            } else {
                entry
            }
        }
        val sent = if (rechecks) validators[key]?.takeIf { !it.isEmpty } else null
        // The pixels a 304 confirms, kept until it answers whatever the cache does.
        val pinnedImage = if (rechecks) image(key) else null
        val sentRequest = request.copy(allowsConstrainedNetworkAccess = isAllowed(key) || isOnDemand(key))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            read(key, id, sentRequest, sent, pinnedImage, sightings)
        }
        reads[key] = Read(id, job, sightings)
        job.start()
    }

    private fun isCurrent(key: AgentImageKey, id: Int): Boolean = reads[key]?.let { it.id == id && !it.cancelled } ?: false

    private suspend fun read(
        key: AgentImageKey,
        id: Int,
        request: AgentImageRequest,
        sent: AgentImageValidators?,
        pinnedImage: Bitmap?,
        sightings: Set<AgentImageSighting>,
    ) {
        try {
            readBody(key, id, request, sent, pinnedImage, sightings)
        } finally {
            if (reads[key]?.id == id) reads.remove(key)
            // Sightings answered meanwhile (shown again during the read) don't ask again.
            unanswered[key]?.let { waiting ->
                waiting.removeAll(fresh)
                if (waiting.isEmpty()) unanswered.remove(key)
            }
            // A sighting seen while this read ran asks for one more, if it's still on screen.
            if (!unanswered[key].isNullOrEmpty() && (interest[key] ?: 0) > 0) enqueue(key) else pump()
        }
    }

    private suspend fun readBody(
        key: AgentImageKey,
        id: Int,
        request: AgentImageRequest,
        sent: AgentImageValidators?,
        pinnedImage: Bitmap?,
        sightings: Set<AgentImageSighting>,
    ) {
        fun answered() {
            fresh.addAll(sightings)
        }
        try {
            val held = if (sent == null && local != null) {
                try {
                    local.invoke(key, request)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: AgentImageDownload.FailureException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            } else null
            val response = held?.response ?: loader(request, sent) { size -> scope.launch { sized(key, size, id) } }
            if (!isCurrent(key, id) || !currentCoroutineContext().isActive) return requeue(key, sightings)
            when (response) {
                AgentImageResponse.NotModified -> {
                    shared?.read(key, null, null)
                    // Put back what the cache may have let go meanwhile.
                    if (image(key) == null && pinnedImage != null) store(pinnedImage, key)
                    mutate(key) { it.copy(rechecking = false) }
                }
                is AgentImageResponse.File -> {
                    val image = decoder(response.data)
                    if (image == null) {
                        if (!isCurrent(key, id)) return requeue(key, sightings)
                        forget(key)
                        settle(key, Phase.Unavailable)
                    } else {
                        if (!isCurrent(key, id) || !currentCoroutineContext().isActive) return requeue(key, sightings)
                        val size = AgentImageHeader.size(response.data, final = true)
                            ?: AgentImageSize(image.width.toDouble(), image.height.toDouble())
                        noteRead(key)
                        if (held == null || held.checked) shared?.read(key, response.data, response.validators)
                        val previous = entry(key)
                        store(image, key)
                        validators[key] = response.validators
                        sizes.storeEntityTag(response.validators.entityTag, key)
                        // A recheck that brought new pixels swaps them in without a fade.
                        val loadedAt = if (previous.phase == Phase.Loaded) previous.loadedAt else now()
                        set(key, Entry(phase = Phase.Loaded, size = size, loadedAt = loadedAt))
                        sizes.store(size, key)
                    }
                }
            }
            failures.remove(key)
            retrying.remove(key)
            constrainedRetries.remove(key)
            constrainedSightings.remove(key)
            answered()
        } catch (error: CancellationException) {
            requeue(key, sightings)
            throw error
        } catch (error: AgentImageDownload.FailureException) {
            when (error.failure) {
                AgentImageDownload.Failure.NotFound -> {
                    if (!isCurrent(key, id)) return requeue(key, sightings)
                    noteRead(key)
                    forget(key)
                    settle(key, Phase.Missing)
                    failures.remove(key)
                    answered()
                }
                AgentImageDownload.Failure.TooLarge -> {
                    if (!isCurrent(key, id)) return requeue(key, sightings)
                    forget(key)
                    settle(key, Phase.Unavailable)
                    answered()
                }
                AgentImageDownload.Failure.Constrained -> {
                    if (!isCurrent(key, id)) return requeue(key, sightings)
                    if (entry(key).phase == Phase.Loaded) {
                        // A recheck refused on a constrained network keeps what's shown,
                        // unconfirmed, so tapping fetches the current file.
                        mutate(key) { it.copy(rechecking = false) }
                        constrainedSightings.getOrPut(key) { HashSet() }.addAll(sightings)
                    } else {
                        onLayoutChange?.invoke(key)
                        recordConstrained(key, sightings)
                        deadlines.remove(key)?.cancel()
                        probed.remove(key)
                        mutate(key) { it.copy(phase = Phase.Waiting, overdue = false) }
                    }
                    failures.remove(key)
                    retrying.remove(key)
                }
                AgentImageDownload.Failure.NotImage, AgentImageDownload.Failure.Failed -> failedOnTheWay(key, id, sightings)
            }
        } catch (error: Exception) {
            failedOnTheWay(key, id, sightings)
        }
    }

    private fun failedOnTheWay(key: AgentImageKey, id: Int, sightings: Set<AgentImageSighting>) {
        if (!isCurrent(key, id)) return requeue(key, sightings)
        when {
            // A recheck that failed on the way keeps what's shown, unconfirmed.
            entry(key).phase == Phase.Loaded -> mutate(key) { it.copy(rechecking = false) }
            // A retry is coming: the preview waits at its shape rather than turning into a card and back.
            (failures[key] ?: 0) < RETRY_DELAYS.size -> mutate(key) { it.copy(phase = Phase.Waiting) }
            else -> settle(key, Phase.Unavailable)
        }
        // Not an answer: these sightings read again on the retry.
        retrying.getOrPut(key) { HashSet() }.addAll(sightings)
        scheduleRetry(key)
    }

    /** A read that was cancelled or replaced: its sightings still wait. */
    private fun requeue(key: AgentImageKey, sightings: Set<AgentImageSighting>) {
        val waiting = sightings.filter { it !in fresh }
        if (waiting.isEmpty()) return
        unanswered.getOrPut(key) { HashSet() }.addAll(waiting)
    }

    /** Another try after a failure on the way, with backoff, while it's on screen. */
    private fun scheduleRetry(key: AgentImageKey) {
        val attempt = failures[key] ?: 0
        if (attempt >= RETRY_DELAYS.size) return
        failures[key] = attempt + 1
        retries[key]?.cancel()
        retries[key] = scope.launch {
            runCatching { sleep(RETRY_DELAYS[attempt]) }
            if (!currentCoroutineContext().isActive) return@launch
            retries.remove(key)
            if ((interest[key] ?: 0) <= 0) return@launch
            val onScreen = visibleState.keys.filter { it.key == key && it !in fresh }
            val wanting = (retrying.remove(key) ?: emptySet<AgentImageSighting>()) + onScreen
            if (wanting.isEmpty()) return@launch
            unanswered.getOrPut(key) { HashSet() }.addAll(wanting)
            enqueue(key)
        }
    }

    private fun sized(key: AgentImageKey, size: AgentImageSize, id: Int) {
        if (!isCurrent(key, id)) return
        val entry = entry(key)
        // The current read's header is newer than a probe's 404: the file exists.
        if (entry.phase != Phase.Loading && entry.phase != Phase.Missing) return
        noteRead(key)
        val wasMissing = entry.phase == Phase.Missing
        if (!wasMissing && entry.size == size) return
        val wasOverdue = entry.overdue
        mutate(key) { it.copy(phase = Phase.Loading, size = size) }
        if (!wasMissing) trace("image-shape", "source" to "read", "late" to wasOverdue)
        // The read's validator comes with its file; until then the shape has none.
        sizes.store(size, key)
        sizes.storeEntityTag(null, key)
    }

    companion object Key : DependencyKey<AgentImagePreviews> {
        /** Above this, the image keeps its file card; the viewer still opens it. */
        const val MAX_BYTES = 25 * 1024 * 1024
        /** Reads at once; the rest wait their turn. */
        const val CONCURRENT_READS = 3
        /** Decoded at most this many pixels on the long side: sharp full screen. */
        const val MAX_PIXEL_SIZE = 2048
        /** A read nobody waits on stops this long after, so a row recomposed mid-scroll doesn't restart it. */
        val RELEASE_DELAY: Duration = 500.milliseconds
        /** Waits before another try at a read that failed on the way. */
        val RETRY_DELAYS: List<Duration> = listOf(2.seconds, 8.seconds, 30.seconds)
        /**
         * How long an image whose shape isn't known stays out of the layout before a placeholder
         * stands in. This bounds the blank wait even when the server sends no header. Lab runs
         * that watch a held image for longer stretch it (iOS `-lab-image-shape-deadline-ms`):
         * `debug.opencode.lab_image_shape_deadline_ms`, debuggable builds only.
         */
        val SHAPE_DEADLINE: Duration by lazy {
            LabLaunch.property("debug.opencode.lab_image_shape_deadline_ms")?.toIntOrNull()?.milliseconds ?: 700.milliseconds
        }
        /** How long a gallery whose block is still streaming gathers images named after its first before deciding their arrangement. */
        val bentoGather: Duration get() = SHAPE_DEADLINE
        const val MAXIMUM_DEFERRED_KEYS = 64
        const val MAXIMUM_CONSTRAINED_RETRIES = 3

        override val liveValue: AgentImagePreviews by lazy {
            val context = runCatching { dependency(AppContext) }.getOrNull()
            val power = context?.getSystemService(PowerManager::class.java)
            val files = dependency(AgentImageCache)
            val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            AgentImagePreviews(
                shouldDeferPrefetch = { NetworkPathMonitor.shared?.shouldDeferLargePrefetch == true },
                isLowPowerModeEnabled = { power?.isPowerSaveMode == true },
                shared = { key, data, validators ->
                    val identity = AgentImageCache.key(key)
                    files.validated(identity)
                    if (data != null && context != null) {
                        val cacheDir = context.cacheDir
                        val name = key.path.substringAfterLast('/')
                        writes.launch {
                            runCatching {
                                val copy = AgentFileLoader.cacheURL(key.server, key.path, name, cacheDir)
                                files.exclusive(copy.path) {
                                    val same = copy.isFile && copy.length() == data.size.toLong() && AgentFileLoader.storedValidators(copy) == validators
                                    if (!same) AgentFileLoader.store(copy, data, validators?.entityTag, validators?.lastModified)
                                }
                                files.trim(cacheDir)
                            }
                        }
                    }
                },
                local = { key, request ->
                    withContext(Dispatchers.IO) {
                        val copy = context?.let { AgentFileLoader.cacheURL(key.server, key.path, key.path.substringAfterLast('/'), it.cacheDir) }
                            ?.takeIf { it.isFile && it.length() <= MAX_BYTES } ?: return@withContext null
                        val stored = AgentFileLoader.storedValidators(copy)
                        if (files.isFresh(AgentImageCache.key(key))) {
                            return@withContext LocalRead(AgentImageResponse.File(copy.readBytes(), stored ?: AgentImageValidators()), checked = false)
                        }
                        val sent = stored?.takeIf { !it.isEmpty } ?: return@withContext null
                        val answer = try {
                            AgentImageDownload.load(request, sent, MAX_BYTES) { }
                        } catch (error: AgentImageDownload.FailureException) {
                            // Unreachable or refused: the copy stands.
                            if (error.failure != AgentImageDownload.Failure.Failed && error.failure != AgentImageDownload.Failure.Constrained) throw error
                            null
                        } catch (_: java.io.IOException) {
                            null
                        }
                        when (answer) {
                            null -> LocalRead(AgentImageResponse.File(copy.readBytes(), sent), checked = false)
                            AgentImageResponse.NotModified -> LocalRead(AgentImageResponse.File(copy.readBytes(), sent), checked = true)
                            is AgentImageResponse.File -> LocalRead(answer, checked = true)
                        }
                    }
                },
            ).also { previews ->
                context?.registerComponentCallbacks(object : ComponentCallbacks2 {
                    @Suppress("DEPRECATION")
                    override fun onTrimMemory(level: Int) {
                        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) previews.trimMemory()
                    }
                    override fun onConfigurationChanged(newConfig: Configuration) = Unit
                    @Deprecated("Deprecated in Java")
                    override fun onLowMemory() = previews.trimMemory()
                })
            }
        }

        /** Inert: reads nothing, probes nothing, keeps shapes in memory. */
        override val testValue: AgentImagePreviews
            get() = AgentImagePreviews(
                sizes = AgentImageSizeCache.inMemory,
                loader = { _, _, _ -> throw AgentImageDownload.FailureException(AgentImageDownload.Failure.Failed) },
                decoder = { null },
                prober = { _, _ -> AgentImageProbeResult.Unknown },
                pathUpdates = { emptyFlow() },
                dispatcher = Dispatchers.Unconfined,
            )

        /**
         * When the pixels of a decided batch's [index]th tile may begin to fade in: the shared
         * stagger (`staggerStep` apart, within `staggerCap`) from when it was decided.
         */
        fun pixelsNotBefore(batch: BentoBatch?, index: Int): Double {
            batch ?: return 0.0
            return batch.decidedAt + MotionRole.staggerDelay(index)
        }

        /** No shape to draw yet, nor a card: out of the layout. */
        fun isHeld(entry: Entry): Boolean = entry.size == null && !entry.overdue && shows(entry.phase) == 0

        /** What a phase shows: a preview, Not found, or a card. */
        private fun shows(phase: Phase): Int = when (phase) {
            Phase.Waiting, Phase.Loading, Phase.Loaded -> 0
            Phase.Missing -> 1
            Phase.Unavailable -> 2
        }

        private fun cacheKey(key: AgentImageKey): String = key.path + "|" + key.server

        /** Debug-level perf lines: counts and timings only. */
        private fun trace(name: String, vararg values: Pair<String, Any>) {
            AppLog.perf.debug { (listOf(name) + values.map { "${it.first}=${it.second}" }).joinToString(" ") }
        }

        /** Decodes at most [maxPixelSize] on the long side, with EXIF orientation applied (iOS `CGImageSourceCreateThumbnailAtIndex`). */
        suspend fun decode(data: ByteArray, maxPixelSize: Int): Bitmap? = withContext(Dispatchers.Default) {
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val width = info.size.width
                    val height = info.size.height
                    val long = max(width, height)
                    if (long > maxPixelSize) {
                        val scale = maxPixelSize.toDouble() / long
                        decoder.setTargetSize(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                null
            }
        }
    }
}

typealias AgentImageLoader = suspend (AgentImageRequest, AgentImageValidators?, (AgentImageSize) -> Unit) -> AgentImageResponse
typealias AgentImageDecoder = suspend (ByteArray) -> Bitmap?
typealias AgentImageSleep = suspend (Duration) -> Unit
typealias AgentImageProber = suspend (AgentImageRequest, String?) -> AgentImageProbeResult
