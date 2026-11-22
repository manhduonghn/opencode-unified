package ai.opencode.core.sharing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What this install has been through, kept when servers are removed. In `:core` because `:server`
 * marks it [paired].
 */
@Serializable
enum class InstallHistory(val rawValue: String) {
    /** Not yet recorded: the first launch of this build is still starting. */
    @SerialName("unrecorded") unrecorded("unrecorded"),

    /** Started on this build and hasn't saved a computer yet. */
    @SerialName("fresh") fresh("fresh"),

    /** Has saved a computer, now or on an earlier build. */
    @SerialName("paired") paired("paired");

    companion object {
        /**
         * Preferences every launch of earlier builds wrote, so their presence marks an upgrade
         * even with no server left and no entry seen.
         */
        val legacyMarkers = listOf("console-sign-in-removed", "voice-engine-migrated-to-gpt-live")

        /**
         * `.appStorage("install-history")`, unrecorded by default. Lazy: the enum serializer isn't
         * ready during companion init.
         */
        val key: SharedKey<InstallHistory> by lazy { SharedKey.appStorage("install-history", unrecorded) }
    }
}

val SharedKeys.installHistory: SharedKey<InstallHistory> get() = InstallHistory.key
