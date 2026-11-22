package ai.opencode.core.experiments

import ai.opencode.core.R
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.strings.localized
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * In `:core`: Settings › Notifications writes it and `:feature:drawer` reads it. What the drawer
 * button says about other sessions while the app is open. Off by default. `shows(kind)` stays with
 * the drawer's notice kinds.
 */
@Serializable
enum class InAppNoticeMode(val rawValue: String) {
    @SerialName("off") off("off"),
    @SerialName("needs-input") needsInput("needs-input"),
    @SerialName("all") all("all");

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            off -> localized(R.string.core_in_app_notice_mode_off)
            needsInput -> localized(R.string.core_in_app_notice_mode_needs_input)
            all -> localized(R.string.core_in_app_notice_mode_all)
        }

    /** Off wherever the drawer button can't show the label (beside a large-screen sidebar). */
    fun effective(showsDrawerLabel: Boolean): InAppNoticeMode = if (showsDrawerLabel) this else off

    companion object {
        val key: SharedKey<InAppNoticeMode> by lazy { SharedKey.appStorage("experimental-in-app-notices", off) }
    }
}
