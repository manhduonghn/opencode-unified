package ai.opencode.server.model

import kotlinx.serialization.Serializable

/** An immutable blob address. The name is for display and export, not for workspace reads. */
@Serializable
data class MediaFileOriginal(val hash: String, val name: String, val mime: String, val byteCount: Long?) {
    /** Never let stale metadata bypass download confirmation. */
    fun downloadSize(probed: Long?): Long? = listOfNotNull(probed, byteCount).filter { it >= 0 }.maxOrNull()
}
