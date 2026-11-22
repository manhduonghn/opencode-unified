package ai.opencode.feature.settings.whatsnew

import ai.opencode.feature.settings.whatsnew.entries.whatsNewShippedEntries
import java.time.LocalDate

/**
 * One change in What’s New: a **feature**, listed by day with its own page, or a **smaller
 * change** (a fix or nuance), listed together under Smaller Changes. IDs are permanent: a seen ID
 * never shows as new again, so reword an entry freely but give a new feature a new ID. Merging
 * entries keeps the newest one’s ID and day, so anyone who saw it doesn’t see it again.
 */
data class WhatsNewEntry(
    val id: String,
    /** The day it shipped; the list groups features by it. */
    val day: LocalDate,
    val kind: Kind = Kind.feature,
    val audience: Audience = Audience.everyone,
    /** Title Case, a few words. */
    val title: String,
    /** SF Symbol name (drawn with `OpenCodeIcon(sfName, …)`). */
    val symbol: String = "sparkles",
    val tint: Tint = Tint.blue,
    /** One or two short sentences. */
    val summary: String,
    /** A feature’s further details, one short sentence each. */
    val details: List<String> = emptyList(),
    /** A feature’s concrete thing to try. */
    val example: String? = null,
    /** What the entry’s row and page offer to open or try. */
    val destination: Destination? = null,
) {
    enum class Kind { feature, smaller }

    /** Who sees a feature: everyone on the main list, or Developer Mode under Developer Updates. */
    enum class Audience { everyone, developer }

    /** The tile’s colour, as a Settings row’s. */
    enum class Tint { blue, brown, cyan, gray, green, indigo, mint, orange, pink, purple, red, teal }

    /** Where an entry’s action goes, from its row and its page. Each only navigates: nothing is ever sent. */
    sealed interface Destination {
        /** A new session. */
        data object NewSession : Destination
        /** A new session with this prompt typed in its composer, not sent. */
        data class Prompt(val text: String) : Destination
        /** The open session’s model list. */
        data object ModelList : Destination
        /** A call in the open session; Voice’s settings while Voice isn’t set up. */
        data object Call : Destination
        /** A Settings page, pushed on What’s New’s own stack. */
        data class Open(val page: SettingsDestination) : Destination

        companion object {
            /** Settings’ server page, with the Servers section. */
            val servers: Destination = Open(SettingsDestination.Servers)
            /** Settings → Notifications. */
            val notifications: Destination = Open(SettingsDestination.Notifications)
        }
    }

    /** A Settings page an entry opens. */
    sealed interface SettingsDestination {
        /** Settings › Appearance › Themes. */
        data object Themes : SettingsDestination
        /** Settings › Sessions (the transcript and the composer). */
        data object Sessions : SettingsDestination
        /** Settings › Voice. */
        data object Voice : SettingsDestination
        /** Settings › Notifications. */
        data object Notifications : SettingsDestination
        /** Settings’ server page, with the Servers section. */
        data object Servers : SettingsDestination
        /** Settings › Experimental. */
        data object Experimental : SettingsDestination
        /**
         * Settings › Experimental, scrolled to this experiment’s row (`ExperimentalFeature.rawValue`;
         * `WhatsNewTests` checks each names one).
         */
        data class Experiment(val id: String) : SettingsDestination
        /** Settings › Developer. */
        data object Developer : SettingsDestination
    }

    companion object {
        fun feature(
            id: String,
            day: String,
            audience: Audience = Audience.everyone,
            title: String,
            symbol: String,
            tint: Tint,
            summary: String,
            details: List<String>,
            example: String,
            tryIt: Destination? = null,
        ) = WhatsNewEntry(
            id = id, day = LocalDate.parse(day), kind = Kind.feature, audience = audience, title = title,
            symbol = symbol, tint = tint, summary = summary, details = details, example = example, destination = tryIt,
        )

        fun smaller(
            id: String,
            day: String,
            title: String,
            symbol: String,
            summary: String,
            tryIt: Destination? = null,
        ) = WhatsNewEntry(
            id = id, day = LocalDate.parse(day), kind = Kind.smaller, audience = Audience.everyone, title = title,
            symbol = symbol, tint = Tint.gray, summary = summary, details = emptyList(), example = null, destination = tryIt,
        )

        val features: List<WhatsNewEntry> get() = all.filter { it.kind == Kind.feature && it.audience == Audience.everyone }
        val developerFeatures: List<WhatsNewEntry> get() = all.filter { it.kind == Kind.feature && it.audience == Audience.developer }
        val smallerChanges: List<WhatsNewEntry> get() = all.filter { it.kind == Kind.smaller }
    }
}

/** Every shipped entry, one file each in `entries/`; [all] lists them newest first. */
object WhatsNewShipped

/**
 * Newest first. A feature for each user-facing feature shipped, and a smaller change for a fix or
 * nuance worth a line.
 */
val WhatsNewEntry.Companion.all: List<WhatsNewEntry> get() = whatsNewShippedEntries

/** The entries that shipped on one day: one section in the list. */
data class WhatsNewDay(val day: LocalDate, val entries: List<WhatsNewEntry>) {
    /** `yyyy-MM-dd`, unique per day. */
    val id: String get() = day.toString()

    companion object {
        /**
         * Groups entries by day in the order each day first appears, so a day never yields two
         * sections even if its entries aren’t adjacent.
         */
        fun group(entries: List<WhatsNewEntry>): List<WhatsNewDay> {
            val days = LinkedHashMap<LocalDate, MutableList<WhatsNewEntry>>()
            for (entry in entries) days.getOrPut(entry.day) { mutableListOf() }.add(entry)
            return days.map { (day, list) -> WhatsNewDay(day, list) }
        }
    }
}
