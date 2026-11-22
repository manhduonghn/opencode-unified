package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DependencyKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Asks for `RECORD_AUDIO` when a call starts without it. The visible activity registers how (an
 * `ActivityResultLauncher` it owns) while it's resumed; with nothing registered the answer is no
 * and the call fails with `microphoneDenied`, as a denial does.
 */
class VoiceMicrophonePermission {
    private val lock = Any()
    private var requester: (suspend () -> Boolean)? = null

    /** Installs [requester] (it must ask the system and return the grant); returns its removal. */
    fun register(requester: suspend () -> Boolean): () -> Unit {
        synchronized(lock) { this.requester = requester }
        return { synchronized(lock) { if (this.requester === requester) this.requester = null } }
    }

    suspend fun request(): Boolean {
        val requester = synchronized(lock) { requester } ?: return false
        return withContext(Dispatchers.Main) { requester() }
    }

    companion object Key : DependencyKey<VoiceMicrophonePermission> {
        override val liveValue: VoiceMicrophonePermission by lazy { VoiceMicrophonePermission() }
        override val testValue: VoiceMicrophonePermission get() = VoiceMicrophonePermission()
    }
}
