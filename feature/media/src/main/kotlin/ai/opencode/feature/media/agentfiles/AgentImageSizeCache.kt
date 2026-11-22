package ai.opencode.feature.media

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

// Shapes of images read before, so a preview opens at its own shape. Keyed by a hash of the server
// and path, at most [LIMIT] entries, oldest out first, saved at most once a second. The file is
// read once, off the main thread, when the cache is made; [size] only reads memory.
class AgentImageSizeCache(private val file: File?) {
    @Serializable
    private data class Stored(
        val sizes: MutableMap<String, List<Double>> = LinkedHashMap(),
        val order: MutableList<String> = ArrayList(),
        /** The ETag each shape was read under, to revalidate it with. */
        var tags: MutableMap<String, String>? = LinkedHashMap(),
    )

    private val lock = Any()
    private var stored = Stored()

    /** The file's shapes are in [stored]: until then [size] answers null rather than reading it. */
    private var warmed = false
    private var reading: Job? = null
    private var pendingSave = false

    init {
        warm()
    }

    /** Reads the shapes remembered on disk, on an IO coroutine, once. */
    fun warm(): Job? = synchronized(lock) {
        reading?.let { return@synchronized it }
        if (file == null) return@synchronized null
        val job = saveScope.launch { readFromDisk() }
        reading = job
        job
    }

    fun size(key: AgentImageKey): AgentImageSize? = synchronized(lock) {
        val pair = stored.sizes[hash(key)] ?: return null
        if (pair.size != 2 || pair[0] <= 0 || pair[1] <= 0) return null
        AgentImageSize(pair[0], pair[1])
    }

    fun store(size: AgentImageSize, key: AgentImageKey) {
        val changed = synchronized(lock) {
            val hash = hash(key)
            val value = listOf(size.width, size.height)
            if (stored.sizes[hash] == value) return@synchronized false
            if (stored.sizes[hash] == null) stored.order.add(hash)
            stored.sizes[hash] = value
            while (stored.order.size > LIMIT) {
                val oldest = stored.order.removeAt(0)
                stored.sizes.remove(oldest)
                stored.tags?.remove(oldest)
            }
            true
        }
        if (changed) save()
    }

    fun entityTag(key: AgentImageKey): String? = synchronized(lock) { stored.tags?.get(hash(key)) }

    /** The ETag the stored shape belongs to (null forgets it). */
    fun storeEntityTag(entityTag: String?, key: AgentImageKey) {
        val changed = synchronized(lock) {
            val hash = hash(key)
            if (stored.sizes[hash] == null || stored.tags?.get(hash) == entityTag) return@synchronized false
            val tags = stored.tags ?: LinkedHashMap<String, String>().also { stored.tags = it }
            if (entityTag == null) tags.remove(hash) else tags[hash] = entityTag
            true
        }
        if (changed) save()
    }

    /**
     * The file's shapes arrive under whatever was stored while it was read. The file is read and
     * decoded outside the lock, so a composable asking [size] while the launch warm runs never
     * waits on the disk.
     */
    private fun readFromDisk() {
        val started = synchronized(lock) {
            if (warmed) return@synchronized false
            warmed = true
            true
        }
        if (!started) return
        val disk = file?.takeIf { it.exists() }
            ?.let { runCatching { json.decodeFromString<Stored>(it.readText()) }.getOrNull() }
            ?: Stored()
        val merged = synchronized(lock) {
            val pending = stored
            if (pending.sizes.isEmpty() && pending.order.isEmpty()) {
                stored = disk
                return@synchronized false
            }
            disk.order.removeAll { pending.sizes.containsKey(it) }
            disk.order.addAll(pending.order)
            disk.sizes.putAll(pending.sizes)
            val newerTags = pending.tags
            if (newerTags != null) {
                val tags = disk.tags ?: LinkedHashMap<String, String>().also { disk.tags = it }
                tags.putAll(newerTags)
            }
            stored = disk
            true
        }
        // Shapes stored before the file was read are in the file it now holds.
        if (merged) save()
    }

    private fun save() {
        val file = file ?: return
        val scheduled = synchronized(lock) {
            // Before the file is read, a save would write away the shapes it holds.
            if (!warmed) return@synchronized false
            val wasPending = pendingSave
            pendingSave = true
            !wasPending
        }
        if (!scheduled) return
        saveScope.launch {
            delay(1.seconds)
            val text = synchronized(lock) {
                pendingSave = false
                val it = stored
                json.encodeToString(Stored.serializer(), it.copy(sizes = LinkedHashMap(it.sizes), order = ArrayList(it.order), tags = it.tags?.let(::LinkedHashMap)))
            }
            runCatching {
                val temporary = File(file.parentFile, file.name + ".tmp")
                temporary.writeText(text)
                temporary.renameTo(file)
            }
        }
    }

    companion object {
        const val LIMIT = 600

        private val json = Json { ignoreUnknownKeys = true }
        private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * `cacheDir/AgentImageSizes.json`; in memory where no context is installed, or in lab runs
         * with `debug.opencode.lab_disable_transcript_disk_cache` = 1 (debuggable builds only).
         */
        val live: AgentImageSizeCache by lazy {
            val disabled = LabLaunch.property("debug.opencode.lab_disable_transcript_disk_cache") == "1"
            AgentImageSizeCache(
                if (disabled) null else runCatching { File(dependency(AppContext).cacheDir, "AgentImageSizes.json") }.getOrNull(),
            )
        }

        val inMemory: AgentImageSizeCache get() = AgentImageSizeCache(null)

        /** SHA-256 of `server|path`, first 10 bytes as hex. */
        private fun hash(key: AgentImageKey): String =
            MessageDigest.getInstance("SHA-256").digest("${key.server}|${key.path}".toByteArray())
                .take(10).joinToString("") { "%02x".format(it) }
    }
}
