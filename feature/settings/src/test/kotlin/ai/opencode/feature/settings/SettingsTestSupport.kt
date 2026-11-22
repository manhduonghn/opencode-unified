package ai.opencode.feature.settings

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.shared

/**
 * Every test gets a fresh in-memory [SharedStore] ([SettingsTestShared]) that its `TestStore` and
 * its reads share.
 */
class SettingsTestShared(val store: SharedStore = SharedStore.inMemory()) {
    private val values: DependencyValues = DependencyValues.testing().update { this[SharedStore] = store }

    /** Runs [block] reading and writing this store. */
    fun <R> read(block: () -> R): R = DependencyValues.withValues(values, block)

    fun <T> set(key: SharedKey<T>, value: T) = read { shared(key).set(value) }

    fun <T> get(key: SharedKey<T>): T = read { shared(key).value }
}

class Locked<T>(initial: T) {
    @Volatile private var current: T = initial
    val value: T get() = synchronized(this) { current }
    fun set(newValue: T) = synchronized(this) { current = newValue }
    fun update(block: (T) -> T) = synchronized(this) { current = block(current) }
}

/** The name of every haptic played, in order. */
class HapticsRecorder {
    private val played = Locked(listOf<String>())
    val value: List<String> get() = played.value
    val client = HapticsClient(play = { haptic -> played.update { it + haptic.name } })
}
