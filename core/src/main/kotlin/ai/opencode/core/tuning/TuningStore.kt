package ai.opencode.core.tuning

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** What changed in a [TuningStore]. */
sealed interface TuningEvent {
    /** A key's value; null is back to its default. */
    data class Changed(val key: String, val value: TuneValue?) : TuningEvent
    /** A whole preset, committed together. */
    data class Values(val values: Map<String, TuneValue?>) : TuningEvent
    /** A tunable read for the first time while tuning is on. */
    data class Registered(val descriptor: TunableDescriptor) : TuningEvent
    /** What's on screen changed. */
    data class Context(val context: TuningContext) : TuningEvent
    /** The settled groups changed. */
    data class HiddenGroups(val groups: List<String>) : TuningEvent
    /** The groups whose last dice roll can be undone changed. */
    data class RollUndoGroups(val groups: List<String>) : TuningEvent
    /** A preset set registered. */
    data class Presets(val presets: TuningPresets) : TuningEvent
}

/**
 * Where overrides and hidden groups persist. Null in a [TuningStore] keeps everything in memory.
 */
interface TuningPersistence {
    fun loadOverrides(): String?
    fun saveOverrides(json: String?)
    fun loadHiddenGroups(): Set<String>
    fun saveHiddenGroups(groups: Set<String>)
}

/**
 * The one place tuned values live. Overrides persist across launches. Readable from any thread;
 * [revision] is snapshot state, so reading composables recompose.
 */
class TuningStore(
    private val persistence: TuningPersistence?,
    appBuild: () -> String = { "?" },
) {
    private class State {
        var overrides: MutableMap<String, TuneValue> = HashMap()
        val descriptors = HashMap<String, TunableDescriptor>()
        val order = ArrayList<String>()
        val presets = ArrayList<TuningPresets>()
        var hiddenGroups: MutableSet<String> = HashSet()
        /** Each group's values before its last dice roll (null means default). */
        val lastRollBefore = HashMap<String, Map<String, TuneValue?>>()
        var lastReadGroup: String? = null
        var appContext = TuningContext.session
        var playgroundOverride: Pair<UUID, TuningContext>? = null
        val effectiveContext: TuningContext get() = playgroundOverride?.second ?: appContext
        var catalogNotificationPending = false
    }

    private val enabled = AtomicBoolean(false)
    private val lock = Any()
    private val state = State()
    private val revisionState = mutableIntStateOf(0)
    private val catalogRevisionState = mutableIntStateOf(0)
    private val contextState = mutableStateOf(TuningContext.session)
    private val _events = MutableSharedFlow<TuningEvent>(extraBufferCapacity = 256)

    val appBuild: String by lazy(appBuild)

    init {
        persistence?.loadOverrides()?.let { json ->
            runCatching { Json.decodeFromString(overridesSerializer, json) }.getOrNull()?.let { state.overrides = it.toMutableMap() }
        }
        state.hiddenGroups = persistence?.loadHiddenGroups()?.toMutableSet() ?: HashSet()
    }

    // Reading

    val isEnabled: Boolean get() = enabled.get()

    /** Bumps on every value change; observed by every composable that reads a value. */
    val revision: Int get() = revisionState.intValue

    /** Bumps when tunables register; the panel observes it. */
    val catalogRevision: Int get() = catalogRevisionState.intValue

    fun <T> value(tunable: Tunable<T>): T {
        // Observed even while off, so a composable drawn then recomposes (and registers) when
        // tuning turns on.
        revisionState.intValue
        if (!enabled.get()) return tunable.defaultValue
        var sanitized = false
        val stored = synchronized(lock) {
            val group = tunable.key.substringBefore('.')
            if (state.lastReadGroup != group) state.lastReadGroup = group
            if (state.descriptors[tunable.key] == null) sanitized = registerLocked(tunable.descriptor)
            state.overrides[tunable.key]
        }
        if (sanitized) persist()
        return stored?.let(tunable.type.decode) ?: tunable.defaultValue
    }

    /** Registers without reading, so a tunable shows before its view first draws (a spring read only mid-animation). */
    fun register(vararg tunables: Tunable<*>) {
        revisionState.intValue
        if (!enabled.get()) return
        val sanitized = synchronized(lock) {
            var sanitized = false
            for (tunable in tunables) if (state.descriptors[tunable.key] == null) sanitized = registerLocked(tunable.descriptor) || sanitized
            sanitized
        }
        if (sanitized) persist()
    }

    fun register(spring: TunableSpring) = register(spring.duration, spring.bounce)

    /** Lists [presets] in the panel, with every tunable they name (while tuning is on). */
    fun register(presets: TuningPresets) {
        revisionState.intValue
        if (!enabled.get()) return
        val sanitized = synchronized(lock) {
            if (state.presets.any { it.key == presets.key }) return
            state.presets += presets
            emit(TuningEvent.Presets(presets))
            var sanitized = false
            val hadAllKeys = presets.descriptors.all { state.descriptors[it.key] != null }
            for (descriptor in presets.descriptors) {
                if (state.descriptors[descriptor.key] == null) sanitized = registerLocked(descriptor) || sanitized
            }
            // Listed together in the set's order, where its first already was, unless the
            // caller already registered every key in its own order.
            val keys = presets.keys
            val first = state.order.indexOfFirst { it in keys }
            if (!hadAllKeys && first >= 0) {
                state.order.removeAll { it in keys }
                state.order.addAll(minOf(first, state.order.size), keys)
            }
            notifyCatalogLocked()
            sanitized
        }
        if (sanitized) persist()
    }

    /** Removes registered tunables whose key starts with [prefix] (an experimental surface turned off). */
    fun unregister(prefix: String, excluding: String? = null) =
        unregisterWhere { it.startsWith(prefix) && (excluding == null || !it.startsWith(excluding)) }

    private fun unregisterWhere(predicate: (String) -> Boolean) {
        val removed = synchronized(lock) {
            val matching = state.order.filter(predicate)
            if (matching.isEmpty()) return@synchronized false
            matching.forEach { state.descriptors.remove(it) }
            state.order.removeAll(matching.toSet())
            true
        }
        if (removed) catalogRevisionState.intValue++
    }

    /** Every registered preset set, in registration order. */
    val presets: List<TuningPresets> get() = synchronized(lock) { state.presets.toList() }

    /** Every registered tunable, in registration order. */
    val descriptors: List<TunableDescriptor> get() = synchronized(lock) { state.order.mapNotNull { state.descriptors[it] } }

    fun descriptor(key: String): TunableDescriptor? = synchronized(lock) { state.descriptors[key] }

    /** Overrides only: keys at their default are absent. */
    val overrides: Map<String, TuneValue> get() = synchronized(lock) { state.overrides.toMap() }

    val observedOverrides: Map<String, TuneValue>
        get() {
            revisionState.intValue
            return overrides
        }

    fun currentValue(key: String): TuneValue? = synchronized(lock) { state.overrides[key] ?: state.descriptors[key]?.defaultValue }

    /** Groups settled and taken out of the panel (their values stay applied). Kept across launches. */
    val hiddenGroups: Set<String>
        get() {
            catalogRevisionState.intValue
            return synchronized(lock) { state.hiddenGroups.toSet() }
        }

    fun setHidden(group: String, hidden: Boolean) {
        val groups = synchronized(lock) {
            if (state.hiddenGroups.contains(group) == hidden) return
            if (hidden) state.hiddenGroups += group else state.hiddenGroups -= group
            emit(TuningEvent.HiddenGroups(state.hiddenGroups.sorted()))
            state.hiddenGroups.toSet()
        }
        catalogRevisionState.intValue++
        persistence?.saveHiddenGroups(groups)
    }

    /** What's on screen (`TuningContextEffect` says, or the active playground): the panel shows its groups. */
    val context: TuningContext get() = contextState.value

    fun setContext(context: TuningContext) = updateContext { it.appContext = context; true }

    fun setPlaygroundContext(context: TuningContext, token: UUID) = updateContext { it.playgroundOverride = token to context; true }

    fun clearPlaygroundContext(token: UUID) = updateContext {
        if (it.playgroundOverride?.first != token) return@updateContext false
        it.playgroundOverride = null
        true
    }

    private fun updateContext(mutate: (State) -> Boolean) {
        val next = synchronized(lock) {
            val before = state.effectiveContext
            if (!mutate(state)) return
            val after = state.effectiveContext
            if (after == before) return
            emit(TuningEvent.Context(after))
            after
        }
        contextState.value = next
    }

    /** The group of the tunable read most recently: what's likely on screen. */
    val lastReadGroup: String? get() = synchronized(lock) { state.lastReadGroup }

    // Changing

    fun setEnabled(isOn: Boolean) {
        if (enabled.get() == isOn) return
        if (!isOn) AnimationTimeScale.shared.reset()
        enabled.set(isOn)
        revisionState.intValue++
    }

    sealed interface SetResult {
        /** What's stored: null when the key is at its default. */
        data class Applied(val value: TuneValue?) : SetResult
        /** The wrong kind of value for the key. */
        data object Refused : SetResult
    }

    /** Sets [key] (null resets it). A registered key takes only a value of its kind, clamped to its range. */
    fun set(key: String, value: TuneValue?): SetResult {
        val started = TuningPerf.start()
        val result: SetResult = synchronized(lock) {
            val descriptor = state.descriptors[key]
            if (value == null || descriptor == null) return@synchronized SetResult.Applied(value)
            val valid = descriptor.accepting(value) ?: return@synchronized SetResult.Refused
            SetResult.Applied(if (descriptor.isDefault(valid)) null else valid)
        }
        if (result !is SetResult.Applied) return SetResult.Refused
        val stored = result.value
        val revision = synchronized(lock) {
            if (state.overrides[key] == stored) return result
            if (stored == null) state.overrides.remove(key) else state.overrides[key] = stored
            emit(TuningEvent.Changed(key, stored))
            revisionState.intValue + 1
        }
        revisionState.intValue = revision
        persist()
        TuningPerf.applied(started, keys = 1, revision = revision)
        return result
    }

    fun reset(key: String) {
        set(key, null)
    }

    /** Sets several keys at once (null resets one). They land in one update, so views recompose once. */
    fun set(values: Map<String, TuneValue?>) {
        val started = TuningPerf.start()
        val (revision, count) = synchronized(lock) {
            val changes = HashMap<String, TuneValue?>()
            for ((key, value) in values) {
                var stored = value
                val descriptor = state.descriptors[key]
                if (value != null && descriptor != null) {
                    val valid = descriptor.accepting(value) ?: continue
                    stored = if (descriptor.isDefault(valid)) null else valid
                }
                if (state.overrides[key] != stored) changes[key] = stored
            }
            if (changes.isEmpty()) return
            for ((key, value) in changes) if (value == null) state.overrides.remove(key) else state.overrides[key] = value
            emit(TuningEvent.Values(changes))
            (revisionState.intValue + 1) to changes.size
        }
        revisionState.intValue = revision
        persist()
        TuningPerf.applied(started, keys = count, revision = revision)
    }

    /** Applies the preset at [index] of the set registered as [key]. */
    fun applyPreset(key: String, index: Int) {
        val presets = synchronized(lock) { state.presets.firstOrNull { it.key == key } } ?: return
        set(presets.changes(index))
    }

    /** Resets every key, or those in [group]. */
    fun resetAll(group: String? = null) {
        val keys = synchronized(lock) { state.overrides.keys.filter { key -> group == null || key.substringBefore('.') == group } }
        takeRollUndo(group)
        set(keys.associateWith { null })
    }

    // Randomizing (dice) and undo

    fun canUndoRoll(group: String): Boolean {
        revisionState.intValue
        return synchronized(lock) { state.lastRollBefore[group] != null }
    }

    /** Rolls every registered tunable in [group] to a random valid value, as one undoable change. */
    fun randomize(group: String, random: Random = Random.Default) {
        val (rows, before) = synchronized(lock) {
            val rows = state.order.mapNotNull { state.descriptors[it] }.filter { it.group == group }
            rows to rows.associate { it.key to state.overrides[it.key] }
        }
        if (rows.isEmpty()) return
        val rolled = HashMap<String, TuneValue?>()
        for (descriptor in rows) {
            val value = descriptor.randomValue(random)
            rolled[descriptor.key] = if (descriptor.isDefault(value)) null else value
        }
        if (rolled == before) {
            rows.firstOrNull { it.hasMultipleValues }?.let { first ->
                val current = before[first.key] ?: first.defaultValue
                val alternate = first.alternateValue(current, random)
                rolled[first.key] = if (first.isDefault(alternate)) null else alternate
            }
        }
        recordRollUndo(group, before)
        set(rolled)
    }

    /** Restores [group] to the values it had before its last dice roll. */
    fun undoRoll(group: String) {
        val before = takeRollUndo(group) ?: return
        set(before)
    }

    private fun recordRollUndo(group: String, before: Map<String, TuneValue?>) {
        synchronized(lock) {
            val had = state.lastRollBefore[group] != null
            state.lastRollBefore[group] = before
            if (!had) emit(TuningEvent.RollUndoGroups(state.lastRollBefore.keys.sorted()))
        }
        revisionState.intValue++
    }

    private fun takeRollUndo(group: String?): Map<String, TuneValue?>? {
        val removed = synchronized(lock) {
            val had = if (group != null) state.lastRollBefore[group] != null else state.lastRollBefore.isNotEmpty()
            if (!had) return null
            val removed = if (group != null) state.lastRollBefore.remove(group) else null.also { state.lastRollBefore.clear() }
            emit(TuningEvent.RollUndoGroups(state.lastRollBefore.keys.sorted()))
            removed
        }
        revisionState.intValue++
        return removed
    }

    // Listening

    val events: SharedFlow<TuningEvent> get() = _events.asSharedFlow()

    // Copy as Kotlin / Copy Values

    /**
     * The changed values as Kotlin: each tunable's declaration with its tuned default (a spring's
     * as one `TunableSpring`), and what it was.
     */
    fun kotlinSnippet(group: String? = null): String {
        val (descriptors, overrides) = synchronized(lock) { state.order.mapNotNull { state.descriptors[it] } to state.overrides.toMap() }
        val byKey = descriptors.associateBy { it.key }
        val lines = mutableListOf<String>()
        val handled = HashSet<String>()
        for (descriptor in descriptors) {
            if (overrides[descriptor.key] == null || descriptor.key in handled) continue
            if (group != null && descriptor.group != group) continue
            val spring = springBase(descriptor.key)
            val duration = spring?.let { byKey["$it.duration"] }
            val bounce = spring?.let { byKey["$it.bounce"] }
            if (spring != null && duration != null && bounce != null) {
                handled += listOf(duration.key, bounce.key)
                val label = duration.label.removeSuffix(" duration")
                val tunedDuration = overrides[duration.key] ?: duration.defaultValue
                val tunedBounce = overrides[bounce.key] ?: bounce.defaultValue
                lines += "TunableSpring(${quoted(spring)}, ${quoted(label)}, duration = ${duration.kotlinLiteral(tunedDuration)}, bounce = ${bounce.kotlinLiteral(tunedBounce)})" +
                    "  // was duration = ${duration.kotlinLiteral(duration.defaultValue)}, bounce = ${bounce.kotlinLiteral(bounce.defaultValue)}"
                continue
            }
            handled += descriptor.key
            val tuned = overrides[descriptor.key] ?: continue
            var call = "Tunable(${quoted(descriptor.key)}, ${quoted(descriptor.label)}, default = ${descriptor.kotlinLiteral(tuned)}"
            descriptor.range?.let { range ->
                call += if (descriptor.valueType == "Int") {
                    ", range = ${range.start.toLong()}..${range.endInclusive.toLong()}"
                } else {
                    ", range = ${TunableDescriptor.decimalWithPoint(range.start)}..${TunableDescriptor.decimalWithPoint(range.endInclusive)}"
                }
            }
            descriptor.step?.let { step ->
                call += if (descriptor.valueType == "Int") ", step = ${step.toLong()}" else ", step = ${TunableDescriptor.decimalWithPoint(step)}"
            }
            lines += "$call)  // was ${descriptor.kotlinLiteral(descriptor.defaultValue)}"
        }
        return lines.joinToString("\n")
    }

    /**
     * A compact block of changed values (a `# Tuning · Group (build)` title and `key = value`
     * lines) to paste into chat, or into `python3 scripts/tune.py bake`.
     */
    fun valuesSnippet(group: String? = null, appBuild: String? = null): String {
        val (descriptors, overrides) = synchronized(lock) { state.order.mapNotNull { state.descriptors[it] } to state.overrides.toMap() }
        val lines = mutableListOf<String>()
        val groups = mutableListOf<String>()
        for (descriptor in descriptors) {
            if (group != null && descriptor.group != group) continue
            val tuned = overrides[descriptor.key] ?: continue
            if (descriptor.group !in groups) groups += descriptor.group
            lines += "${descriptor.key} = ${descriptor.valueText(tuned)}"
        }
        if (lines.isEmpty()) return ""
        val title = (group?.let { listOf(it) } ?: groups).joinToString(", ") { TuningTitle.format(it) }
        return (listOf("# Tuning · $title (${appBuild ?: this.appBuild})") + lines).joinToString("\n")
    }

    // Private

    /** Returns whether a saved override had to change, so the caller persists it once out of the lock. */
    private fun registerLocked(descriptor: TunableDescriptor): Boolean {
        state.descriptors[descriptor.key] = descriptor
        state.order += descriptor.key
        emit(TuningEvent.Registered(descriptor))
        // An override saved for another type or range (or a default since baked to it) is made
        // valid or dropped.
        var sanitized = false
        state.overrides[descriptor.key]?.let { stored ->
            val valid = descriptor.accepting(stored)?.takeUnless { descriptor.isDefault(it) }
            if (valid != stored) {
                if (valid == null) state.overrides.remove(descriptor.key) else state.overrides[descriptor.key] = valid
                emit(TuningEvent.Changed(descriptor.key, valid))
                sanitized = true
            }
        }
        notifyCatalogLocked()
        return sanitized
    }

    /**
     * Registration happens during composition; tell the panel afterwards to avoid a backwards
     * write.
     */
    private fun notifyCatalogLocked() {
        if (state.catalogNotificationPending) return
        state.catalogNotificationPending = true
        val bump: () -> Unit = {
            synchronized(lock) { state.catalogNotificationPending = false }
            catalogRevisionState.intValue += 1
        }
        val posted = runCatching { Handler(Looper.getMainLooper()).post(bump) }.getOrDefault(false)
        if (!posted) bump()
    }

    private fun emit(event: TuningEvent) {
        _events.tryEmit(event)
    }

    private fun persist() {
        val persistence = persistence ?: return
        val overrides = synchronized(lock) { state.overrides.toMap() }
        persistence.saveOverrides(if (overrides.isEmpty()) null else Json.encodeToString(overridesSerializer, overrides))
    }

    companion object Key : DependencyKey<TuningStore> {
        const val STORAGE_KEY = "tuning-overrides"
        const val HIDDEN_GROUPS_KEY = "tuning-hidden-groups"
        private val overridesSerializer = MapSerializer(String.serializer(), TuneValueSerializer)

        /** The process-wide store every [Tunable.value] reads. Falls back to memory when there's no app context (unit tests). */
        val shared: TuningStore by lazy {
            val context = runCatching { dependency(AppContext).applicationContext }.getOrNull()
            if (context == null) TuningStore(null) else TuningStore(SharedPreferencesTuningPersistence(context), appBuild = { defaultAppBuild(context) })
        }

        override val liveValue: TuningStore get() = shared
        override val testValue: TuningStore get() = TuningStore(null)

        /** "1.4 (231) · abc1234"-style build label, from the package's version. */
        fun defaultAppBuild(context: Context): String = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val version = info.versionName ?: "?"
            "$version (${info.longVersionCode})"
        }.getOrDefault("?")

        private fun springBase(key: String): String? =
            listOf(".duration", ".bounce").firstOrNull { key.endsWith(it) }?.let { key.removeSuffix(it) }

        private fun quoted(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
    }
}

/** [TuningPersistence] in the app's `tuning` SharedPreferences, read synchronously at first use. */
class SharedPreferencesTuningPersistence(context: Context) : TuningPersistence {
    private val preferences: SharedPreferences = context.getSharedPreferences("tuning", Context.MODE_PRIVATE)

    override fun loadOverrides(): String? = preferences.getString(TuningStore.STORAGE_KEY, null)

    override fun saveOverrides(json: String?) {
        preferences.edit().apply { if (json == null) remove(TuningStore.STORAGE_KEY) else putString(TuningStore.STORAGE_KEY, json) }.apply()
    }

    override fun loadHiddenGroups(): Set<String> = preferences.getStringSet(TuningStore.HIDDEN_GROUPS_KEY, emptySet()).orEmpty()

    override fun saveHiddenGroups(groups: Set<String>) {
        preferences.edit().putStringSet(TuningStore.HIDDEN_GROUPS_KEY, groups.toSet()).apply()
    }
}

/** Content-free timing for the opt-in preset benchmark. */
object TuningPerf {
    @Volatile var enabled: Boolean = false

    fun start(): Long? = if (enabled) System.nanoTime() else null

    fun applied(start: Long?, keys: Int, revision: Int) {
        start ?: return
        val end = System.nanoTime()
        AppLog.app.info { "tuning_perf apply keys=$keys revision=$revision us=${(end - start) / 1000} at=$end" }
    }
}
