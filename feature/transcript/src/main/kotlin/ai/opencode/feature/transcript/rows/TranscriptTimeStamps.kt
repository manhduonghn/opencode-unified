package ai.opencode.feature.transcript.rows

import ai.opencode.core.strings.localized
import ai.opencode.core.ui.appFormattingLocale
import ai.opencode.feature.transcript.R
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.server.model.OpenCodeTranscriptItem
import android.icu.text.DateFormat
import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.text.SimpleDateFormat
import android.icu.util.TimeZone
import android.icu.util.ULocale
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Centred times over the messages that open a new day or resume after a pause: the day over the
 * first message of each day (“Wednesday 8:24 AM”) and the time alone (“2:10 PM”) over one sent an
 * hour or more after the session last moved.
 *
 * Times come from OpenCode's ascending IDs. A message this phone sent has a client-made ID that
 * carries no time, so it takes its reply's (seconds later); one still waiting for its reply was
 * sent just now. The clock ([now]), zone and locale are parameters, so callers inject them
 * (`DateClient`) instead of reading the system's.
 */
object TranscriptTimeStamps {
    /** Rows of centred times; their text is "<day>\t<time>", or the time alone. */
    const val PREFIX = "stamp-"
    const val pauseGapSeconds: Long = 60 * 60

    private data class StampsCacheKey(
        val itemIDs: List<String>,
        val promptIDs: List<String?>,
        val sentNow: Boolean?,
        val nowBucket: Long,
        val zoneID: String,
        val localeID: String,
        val hour24: Boolean?,
    )

    private val lock = Any()
    private var lastStamps: Pair<StampsCacheKey, Map<String, String>>? = null

    /**
     * A stamp's text by prompt ID, for the prompts in [items] that get one. A prompt with no time
     * of its own (the app's `msg_<UUID>`) takes the first timed item of its own turn, before the
     * next prompt (its reply, seconds later). Only the last prompt, with nothing timed after it,
     * may count as sent now, and only where [sentNow] allows: true where it can only be a send
     * still waiting for its reply (the session has timed IDs, or it's new), false for fixtures
     * whose IDs carry no times at all. Any other prompt without a time gets no stamp and doesn't
     * count as activity. [hour24] is the device's 24-hour setting (null: the locale's).
     */
    fun stamps(
        items: List<OpenCodeTranscriptItem>,
        sentNow: Boolean? = null,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = appFormattingLocale(),
        hour24: Boolean? = null,
    ): Map<String, String> {
        val promptIDs = items.map(TranscriptEntry::promptID)
        // When the transcript ends on an unanswered prompt, `sentNow` stamps it at `now`, so keep
        // millisecond resolution there; otherwise `now` only anchors day boundaries and skew.
        val nowBucket = if (promptIDs.lastOrNull() != null) now.toEpochMilli() else now.epochSecond
        val cacheKey = StampsCacheKey(items.map { it.id }, promptIDs, sentNow, nowBucket, zone.id, locale.toLanguageTag(), hour24)
        synchronized(lock) { lastStamps?.let { if (it.first == cacheKey) return it.second } }

        val explicit = items.map { date(it.id, now) }
        val resolvedSentNow = sentNow ?: explicit.any { it != null }
        // Each prompt's rows: a prompt's text, images, and files are one message.
        val prompts = mutableListOf<Pair<String, IntRange>>()
        var index = 0
        while (index < items.size) {
            val promptID = promptIDs[index]
            if (promptID == null) {
                index++
                continue
            }
            var end = index + 1
            while (end < items.size && promptIDs[end] == promptID) end++
            prompts += promptID to (index until end)
            index = end
        }
        val result = mutableMapOf<String, String>()
        var lastPrompt: Instant? = null
        var lastActivity: Instant? = null
        var activityIndex = 0
        fun advanceActivity(end: Int) {
            while (activityIndex < end) {
                val time = explicit[activityIndex]
                if (promptIDs[activityIndex] == null && time != null) lastActivity = maxOf(lastActivity ?: time, time)
                activityIndex++
            }
        }
        for ((position, prompt) in prompts.withIndex()) {
            val (promptID, range) = prompt
            advanceActivity(range.first)
            val turnEnd = if (position + 1 < prompts.size) prompts[position + 1].second.first else items.size
            val isLast = position == prompts.lastIndex
            val time = date(promptID, now)
                ?: (range.last + 1 until turnEnd).firstNotNullOfOrNull { explicit[it] }
                ?: if (resolvedSentNow && isLast) now else null
            if (time == null) continue
            stamp(time, lastPrompt, lastActivity, now, zone, locale, hour24)?.let { result[promptID] = it }
            lastPrompt = time
            lastActivity = maxOf(lastActivity ?: time, time)
        }
        synchronized(lock) { lastStamps = cacheKey to result }
        return result
    }

    private fun stamp(
        time: Instant,
        lastPrompt: Instant?,
        lastActivity: Instant?,
        now: Instant,
        zone: ZoneId,
        locale: Locale,
        hour24: Boolean?,
    ): String? {
        // The first message we have, or the first of a new day, is dated.
        if (lastPrompt == null || time.atZone(zone).toLocalDate() != lastPrompt.atZone(zone).toLocalDate()) {
            return text(time, showsDay = true, now, zone, locale, hour24)
        }
        if (lastActivity == null || ChronoUnit.SECONDS.between(lastActivity, time) < pauseGapSeconds) return null
        return text(time, showsDay = false, now, zone, locale, hour24)
    }

    /** Puts each stamp just above the message it dates. */
    fun inserting(stamps: Map<String, String>, entries: List<TranscriptEntry>): List<TranscriptEntry> {
        if (stamps.isEmpty()) return entries
        val result = ArrayList<TranscriptEntry>(entries.size + stamps.size)
        for (entry in entries) {
            val id = entry.userMessageID
            val text = id?.let { stamps[it] }
            if (id != null && text != null) result += TranscriptEntry.Single(OpenCodeTranscriptItem.Info(PREFIX + id, text))
            result += entry
        }
        return result
    }

    fun isStamp(entry: TranscriptEntry): Boolean {
        val item = (entry as? TranscriptEntry.Single)?.item as? OpenCodeTranscriptItem.Info ?: return false
        return item.id.startsWith(PREFIX)
    }

    // Times

    /** How far ahead of the phone a server's clock may be: a day, in ID units. */
    private const val maximumSkew: Long = 24L * 60 * 60 * 1000 * 0x1000
    private const val mask: Long = 0xFFFF_FFFF_FFFFL

    /**
     * When an OpenCode ID was minted. After the prefix, the server's IDs are 12 lowercase hex
     * digits holding the low 48 bits of `milliseconds × 4096 + counter` and 14 random characters
     * (a part's item ID adds a `-` suffix). They wrap every ~795 days, so the time is recovered
     * relative to [now]: up to a day ahead of it (a server clock ahead of the phone's) reads as
     * now rather than wrapping ~795 days back, and anything else as that far in the past. Other
     * IDs, such as the app's own `msg_<UUID>`, have no time.
     */
    fun date(id: String, now: Instant): Instant? {
        val value = parsedValue(id) ?: return null
        return recoveredDate(value, now)
    }

    /** Whether [id] carries an OpenCode timestamp minted at or before [milliseconds]. */
    fun isAtOrBefore(milliseconds: Long, id: String): Boolean? {
        val ahead = millisecondsAhead(milliseconds, id) ?: return null
        return ahead == 0L || ahead > HALF_RANGE
    }

    /** Whether [id] carries an OpenCode timestamp minted strictly before [milliseconds]. */
    fun isBefore(milliseconds: Long, id: String): Boolean? {
        val ahead = millisecondsAhead(milliseconds, id) ?: return null
        return ahead > HALF_RANGE
    }

    /** The 36 bits of milliseconds an ID holds, and half of them: more than that ahead is really behind. */
    private const val MS_MASK: Long = 0xF_FFFF_FFFFL
    private const val HALF_RANGE: Long = MS_MASK shr 1

    /** How far [id]'s milliseconds are ahead of [milliseconds], wrapped to the ID's 36 bits. */
    private fun millisecondsAhead(milliseconds: Long, id: String): Long? {
        if (milliseconds <= 0) return null
        val value = parsedValue(id) ?: return null
        val itemMS = (value ushr 12) and MS_MASK
        val cutoffMS = milliseconds and MS_MASK
        return (itemMS - cutoffMS) and MS_MASK
    }

    private fun parsedValue(id: String): Long? {
        val underscore = id.indexOf('_')
        if (underscore < 0) return null
        val bodyStart = underscore + 1
        var bodyEnd = bodyStart
        while (bodyEnd < id.length && id[bodyEnd] != '-') bodyEnd++
        if (bodyEnd - bodyStart != 26) return null
        var value = 0L
        for (i in bodyStart until bodyStart + 12) {
            val c = id[i]
            val digit = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                else -> return null
            }
            value = (value shl 4) or digit.toLong()
        }
        for (i in bodyStart + 12 until bodyEnd) {
            val c = id[i]
            if (c !in '0'..'9' && c !in 'A'..'Z' && c !in 'a'..'z') return null
        }
        return value
    }

    private fun recoveredDate(value: Long, now: Instant): Instant {
        val nowValue = (maxOf(0L, now.toEpochMilli()) * 0x1000) and mask
        // A server clock ahead of the phone's: its IDs are from the phone's future, by up to a
        // day of skew, and read as now.
        val ahead = (value - nowValue) and mask
        if (ahead <= maximumSkew) return now
        val distance = (nowValue - value) and mask
        return now.minusNanos((distance.toDouble() / 0x1000 * 1_000_000).roundToLong())
    }

    // Text

    private val texts = MinuteTimestampCache()

    /**
     * "Today\t2:41 PM", "Yesterday\t…", a weekday within the week, else a short date; the time
     * alone when [showsDay] is false. Locale-aware.
     */
    fun text(
        time: Instant,
        showsDay: Boolean,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = appFormattingLocale(),
        hour24: Boolean? = null,
    ): String {
        // The transcript asks on every update; the same minute reads the same until the day it
        // falls on moves (Today becomes Yesterday at midnight).
        val days = daysBetween(time, now, zone)
        return texts.text(time, if (showsDay) days else -1, zone, locale, hour24) {
            uncachedText(time, showsDay, now, zone, locale, hour24)
        }
    }

    private fun daysBetween(time: Instant, now: Instant, zone: ZoneId): Int =
        ChronoUnit.DAYS.between(time.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate()).toInt()

    private fun format(skeleton: String, zone: ZoneId, locale: Locale, context: DisplayContext): DateFormat =
        DateFormat.getInstanceForSkeleton(skeleton, locale).apply {
            timeZone = TimeZone.getTimeZone(zone.id)
            setContext(context)
        }

    private fun uncachedText(time: Instant, showsDay: Boolean, now: Instant, zone: ZoneId, locale: Locale, hour24: Boolean?): String {
        // The locale's hour cycle unless the device overrides it.
        val skeleton = when (hour24) {
            true -> "Hm"
            false -> "hm"
            null -> "jm"
        }
        val clockFormat = format(skeleton, zone, locale, DisplayContext.CAPITALIZATION_FOR_STANDALONE)
        // Writes the hour at its default digits (“8:24”, never “08:24”), where ICU's best pattern
        // for some locales pads it (de: “HH:mm”).
        (clockFormat as? SimpleDateFormat)?.let { it.applyPattern(it.toPattern().replace("HH", "H").replace("hh", "h")) }
        val clock = clockFormat.format(Date.from(time))
        if (!showsDay) return clock
        return "${day(time, now, zone, locale)}\t$clock"
    }

    /** Today and Yesterday by calendar day, the weekday for the rest of the week, else “Mon, Oct 5”. */
    fun day(time: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = appFormattingLocale()): String {
        val days = daysBetween(time, now, zone)
        val context = DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE
        return when (days) {
            0, 1 -> relativeDay(days, locale)
            in 2 until 7 -> format("EEEE", zone, locale, context).format(Date.from(time))
            else -> format("EEEMMMd", zone, locale, context).format(Date.from(time))
        }
    }

    private val relativeDays = mutableMapOf<String, String>()

    /** The locale's own word for today or yesterday. */
    private fun relativeDay(daysAgo: Int, locale: Locale): String {
        val key = "${locale.toLanguageTag()}|$daysAgo"
        synchronized(relativeDays) { relativeDays[key]?.let { return it } }
        val formatter = RelativeDateTimeFormatter.getInstance(
            ULocale.forLocale(locale),
            null,
            RelativeDateTimeFormatter.Style.LONG,
            DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE,
        )
        val direction = if (daysAgo == 0) RelativeDateTimeFormatter.Direction.THIS else RelativeDateTimeFormatter.Direction.LAST
        val word = formatter.format(direction, RelativeDateTimeFormatter.AbsoluteUnit.DAY)
        synchronized(relativeDays) { relativeDays[key] = word }
        return word
    }

    /** What TalkBack reads: “Wednesday, 8:24 AM”. */
    fun accessibilityLabel(text: String): String {
        val parts = text.split("\t", limit = 2)
        if (parts.size < 2) return text
        return localized(R.string.transcript_spoken_pair, parts[0], parts[1])
    }
}

/** Bounded minute-and-day string cache shared by transcript and drawer timestamps. */
class MinuteTimestampCache {
    private val storage = HashMap<String, String>()

    fun text(date: Instant, dayBucket: Int, zone: ZoneId, locale: Locale, hour24: Boolean? = null, make: () -> String): String {
        val key = "${date.epochSecond / 60}|$dayBucket|${locale.toLanguageTag()}|${zone.id}|$hour24"
        synchronized(storage) { storage[key]?.let { return it } }
        val formatted = make()
        synchronized(storage) {
            if (storage.size > 2_000) storage.clear()
            storage[key] = formatted
        }
        return formatted
    }
}
