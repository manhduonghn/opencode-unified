package ai.opencode.feature.transcript.rows

import ai.opencode.core.ui.appFormattingLocale
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.server.model.OpenCodePromptSource
import ai.opencode.server.model.OpenCodeTranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs

/**
 * Day and time stamps over the messages that open a day or resume after a pause.
 *
 * The stamp text comes from `android.icu` (`DateFormat` skeletons, `RelativeDateTimeFormatter`),
 * which is a stub in plain JVM tests, so the class runs under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptTimeStampsTests {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val locale = Locale.US

    private fun at(month: Int, day: Int, hour: Int, minute: Int, zone: ZoneId = this.zone): Instant =
        ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, zone).toInstant()

    private fun Instant.plusSeconds(seconds: Double): Instant = plusNanos((seconds * 1_000_000_000).toLong())

    /** An ID in OpenCode's ascending format, minted at [date]. */
    private fun id(date: Instant, counter: Long = 1): String {
        val milliseconds = date.toEpochMilli()
        val value = (milliseconds * 0x1000 + counter) and 0xFFFF_FFFF_FFFFL
        return "msg_" + String.format(Locale.ROOT, "%012x", value) + "StampsFixture1"
    }

    private fun user(id: String, text: String = "Question", source: OpenCodePromptSource? = null): OpenCodeTranscriptItem =
        OpenCodeTranscriptItem.UserText(id, text, source)

    private fun reply(messageID: String): OpenCodeTranscriptItem = OpenCodeTranscriptItem.AssistantMarkdown("$messageID-text-0", "Answer")

    /** A prompt sent at [date] and its reply 40 s later. */
    private fun turn(date: Instant, text: String = "Question"): List<OpenCodeTranscriptItem> =
        listOf(user(id(date), text), reply(id(date.plusSeconds(40), counter = 2)))

    /** Whitespace as the formatter writes it (a narrow space before AM). */
    private fun plain(stamps: Map<String, String>): Map<String, String> = stamps.mapValues { it.value.replace('\u202F', ' ') }

    private fun stamps(items: List<OpenCodeTranscriptItem>, sentNow: Boolean? = null, now: Instant): Map<String, String> =
        plain(TranscriptTimeStamps.stamps(items, sentNow = sentNow, now = now, zone = zone, locale = locale))

    private fun assertClose(expected: Instant, actual: Instant?) {
        assertNotNull(actual)
        assertTrue("$actual is not $expected", abs(Duration.between(expected, actual).toNanos()) < 10_000_000)
    }

    @Test
    fun datesTheFirstMessageOfEachDayAndTimesLongPauses() {
        val now = at(10, 12, 9, 41)
        val times = listOf(
            at(10, 5, 8, 25), at(10, 5, 8, 31), at(10, 6, 8, 18), at(10, 7, 8, 24), at(10, 7, 14, 10),
            at(10, 9, 8, 40), at(10, 11, 21, 5), at(10, 12, 8, 39),
        )
        val result = stamps(times.flatMap { turn(it) }, now = now)
        assertEquals(
            mapOf(
                id(times[0]) to "Mon, Oct 5\t8:25 AM",
                id(times[2]) to "Tuesday\t8:18 AM",
                id(times[3]) to "Wednesday\t8:24 AM",
                id(times[4]) to "2:10 PM",
                id(times[5]) to "Friday\t8:40 AM",
                id(times[6]) to "Yesterday\t9:05 PM",
                id(times[7]) to "Today\t8:39 AM",
            ),
            result,
        )
    }

    /** A pause is time without activity, not time since the last message: a long run leaves no gap before the next message. */
    @Test
    fun pausesAreMeasuredFromTheLastActivity() {
        val now = at(10, 12, 18, 0)
        val first = at(10, 12, 8, 0)
        val items = listOf(user(id(first)), reply(id(at(10, 12, 9, 30)))) + turn(at(10, 12, 9, 40))
        assertEquals(mapOf(id(first) to "Today\t8:00 AM"), stamps(items, now = now))

        // 59 minutes is no pause; an hour is.
        val short = turn(first) + turn(first.plusSeconds(40.0 + 59 * 60))
        assertEquals(1, stamps(short, now = now).size)
        val long = turn(first) + turn(first.plusSeconds(40.0 + 60 * 60 + 1))
        assertEquals("9:00 AM", stamps(long, now = now)[id(first.plusSeconds(40.0 + 60 * 60 + 1))])
    }

    @Test
    fun aNewDayIsDatedEvenRightAfterTheLastReply() {
        val now = at(10, 12, 9, 0)
        val late = at(10, 11, 23, 50)
        // The reply ran past midnight; the next message is the new day's first.
        val items = listOf(user(id(late)), reply(id(at(10, 12, 0, 5)))) + turn(at(10, 12, 0, 20))
        assertEquals("Today\t12:20 AM", stamps(items, now = now)[id(at(10, 12, 0, 20))])
    }

    @Test
    fun idsWithoutTimes() {
        val now = at(10, 12, 9, 41)
        // The app's own message IDs (`msg_` and a UUID) and fixture IDs carry no time.
        assertNull(TranscriptTimeStamps.date("msg_E621E1F8C36C495A93FC0C247A3E6E5F", now))
        assertNull(TranscriptTimeStamps.date("msg_00000000000000000000000000000000", now))
        assertNull(TranscriptTimeStamps.date("msg_lab_user", now))
        assertNull(TranscriptTimeStamps.date("u1", now))
        // A part's item ID carries its message's time.
        val sent = at(10, 7, 8, 24)
        assertClose(sent, TranscriptTimeStamps.date(id(sent) + "-tool-call_1", now))
    }

    @Test
    fun idsWithoutTimesStamps() {
        val now = at(10, 12, 9, 41)
        // A message sent from this phone takes its reply's time.
        val yesterday = at(10, 11, 21, 5)
        val phone = "msg_E621E1F8C36C495A93FC0C247A3E6E5F"
        val items = turn(yesterday) + listOf(user(phone), reply(id(at(10, 12, 8, 39), counter = 2)))
        assertEquals("Today\t8:39 AM", stamps(items, now = now)[phone])
        // One still waiting for its reply was sent just now.
        val waiting = turn(yesterday) + listOf(user(phone))
        assertEquals("Today\t9:41 AM", stamps(waiting, now = now)[phone])
        // A fixture with no times at all gets no stamps, unless it's a new session.
        val fixture = listOf(user("msg_lab_user"), reply("msg_lab_reply"), user("msg_lab_next"))
        assertTrue(stamps(fixture, now = now).isEmpty())
        assertEquals(mapOf(phone to "Today\t9:41 AM"), stamps(listOf(user(phone)), sentNow = true, now = now))
    }

    /** A server clock a little ahead of the phone's mints IDs from the phone's future: they read as now, never ~795 days back. */
    @Test
    fun aServerClockAheadOrBehindReadsAsNow() {
        val now = at(10, 12, 9, 41)
        for (skew in listOf(0.05, 40.0, 3600.0)) {
            val ahead = TranscriptTimeStamps.date(id(now.plusSeconds(skew)), now)
            assertEquals("$skew s ahead reads as now", now, ahead)
        }
        assertClose(now.minusSeconds(40), TranscriptTimeStamps.date(id(now.minusSeconds(40)), now))
        // Old IDs still read as old, up to the range's ~795 days.
        val old = now.minusSeconds(700L * 86_400)
        assertClose(old, TranscriptTimeStamps.date(id(old), now))
    }

    @Test
    fun aServerClockAheadOrBehindReadsAsNowStamps() {
        val now = at(10, 12, 9, 41)
        // A message from this phone whose reply the server dated 50 ms (or 40 s) ahead is
        // today's, and the next one isn't a new day.
        val phone = "msg_E621E1F8C36C495A93FC0C247A3E6E5F"
        val second = "msg_0A1B2C3D4E5F60718293A4B5C6D7E8F9"
        for (skew in listOf(0.05, 40.0)) {
            // Read on the phone the moment the reply lands, still ahead of its clock.
            val items = turn(at(10, 11, 21, 5)) + listOf(user(phone), reply(id(now.plusSeconds(skew), counter = 2)))
            val result = stamps(items, now = now)
            assertEquals("$skew s ahead", "Today\t9:41 AM", result[phone])
            assertEquals(2, result.size)
            // The next message, a minute on, is no new day.
            val more = items + listOf(user(second), reply(id(now.plusSeconds(60 + skew), counter = 2)))
            assertEquals("No spurious stamp $skew s ahead", 2, stamps(more, now = now.plusSeconds(60)).size)
        }
    }

    /** Across the 48-bit wrap: an ID minted just before it, read just after. */
    @Test
    fun acrossTheWrap() {
        // 2^36 ms is where `ms × 4096` wraps 48 bits; the 26th wrap falls in 2026.
        val wrap = Instant.ofEpochMilli(26L * (1L shl 36))
        val now = wrap.plusSeconds(0.01)
        val before = wrap.plusSeconds(-1.0)
        assertClose(before, TranscriptTimeStamps.date(id(before), now))
        assertEquals(now, TranscriptTimeStamps.date(id(wrap.plusSeconds(0.03)), now))
        val after = wrap.plusSeconds(-0.5)
        assertClose(after, TranscriptTimeStamps.date(id(after), wrap.plusSeconds(-0.4)))
    }

    /** A message from this phone that never got a reply (stopped, or failed) has no time: it gets no stamp, and never takes a later turn's time. */
    @Test
    fun anUnansweredMessageNeverTakesALaterTurnsTime() {
        val now = at(10, 12, 9, 41)
        val stopped = "msg_0A1B2C3D4E5F60718293A4B5C6D7E8F9"
        val next = "msg_E621E1F8C36C495A93FC0C247A3E6E5F"
        val yesterday = turn(at(10, 11, 21, 5)) + listOf(user(stopped))
        // The next day's message, waiting for its reply: it's the one sent now.
        val waiting = stamps(yesterday + listOf(user(next)), now = now)
        assertNull(waiting[stopped])
        assertEquals("Today\t9:41 AM", waiting[next])
        // Its reply arrives: the stopped one still doesn't take its time.
        val answered = stamps(yesterday + listOf(user(next), reply(id(at(10, 12, 9, 41), counter = 2))), now = now.plusSeconds(60))
        assertNull(answered[stopped])
        assertEquals("Today\t9:41 AM", answered[next])
        // Nor a later prompt's own time (another client's).
        val other = at(10, 12, 8, 0)
        val fromElsewhere = stamps(yesterday + turn(other), now = now)
        assertNull(fromElsewhere[stopped])
        assertEquals("Today\t8:00 AM", fromElsewhere[id(other)])
        // Only the latest unanswered message is sent now: of two, the earlier gets none.
        val two = stamps(turn(at(10, 11, 21, 5)) + listOf(user(stopped), user(next)), now = now)
        assertEquals(mapOf(id(at(10, 11, 21, 5)) to "Yesterday\t9:05 PM", next to "Today\t9:41 AM"), two)
    }

    /** A prompt's images and text are one message, dated once, over its first row. */
    @Test
    fun imagesAndTextAreOneMessage() {
        val now = at(10, 12, 9, 41)
        val sent = id(at(10, 12, 8, 0))
        val items = listOf(OpenCodeTranscriptItem.UserImages("$sent-images", emptyList()), user(sent))
        assertEquals(mapOf(sent to "Today\t8:00 AM"), stamps(items, now = now))
        val entries = TranscriptTimeStamps.inserting(stamps(items, now = now), TranscriptEntry.build(items))
        assertEquals(listOf(TranscriptTimeStamps.PREFIX + sent, "user-$sent-images"), entries.map { it.id })
        // `.userText` and `.userImages` with the same item ID derive distinct prompt IDs.
        assertEquals(mapOf("$sent-images" to "Today\t8:00 AM"), stamps(listOf(user("$sent-images")), now = now))
        assertEquals(mapOf(sent to "Today\t8:00 AM"), stamps(listOf(OpenCodeTranscriptItem.UserImages("$sent-images", emptyList())), now = now))
    }

    /** Voice delegations and messages another agent sent are messages in the session like any other. */
    @Test
    fun voiceAndAgentMessagesAreDatedToo() {
        val now = at(10, 12, 9, 41)
        val voice = at(10, 9, 8, 40)
        val agent = at(10, 12, 8, 0)
        val items = listOf(user(id(voice), "Voice delegation (continued).\n\nCheck the build"), reply(id(voice.plusSeconds(30), counter = 2))) +
            listOf(user(id(agent), "Forwarded", source = OpenCodePromptSource.Session("ses_other")), reply(id(agent.plusSeconds(20), counter = 2)))
        assertEquals(mapOf(id(voice) to "Friday\t8:40 AM", id(agent) to "Today\t8:00 AM"), stamps(items, now = now))
    }

    @Test
    fun daylightSavingTime() {
        val newYorkZone = ZoneId.of("America/New_York")
        fun newYork(items: List<OpenCodeTranscriptItem>, now: Instant): Map<String, String> =
            plain(TranscriptTimeStamps.stamps(items, now = now, zone = newYorkZone, locale = locale))
        // Clocks go back at 2:00 on November 1: 1:50 EDT, then 1:20 EST, is 30 minutes.
        val beforeFallBack = Instant.ofEpochSecond(1_793_512_200) // 2026-11-01 05:50 UTC, 1:50 EDT
        val afterFallBack = beforeFallBack.plusSeconds(30L * 60) // 1:20 EST
        val fallNow = beforeFallBack.plusSeconds(8L * 3600)
        assertEquals(listOf(id(beforeFallBack)), newYork(turn(beforeFallBack) + turn(afterFallBack), fallNow).keys.sorted())
        // Clocks go forward at 2:00 on March 8: 1:50 EST, then 3:10 EDT, is 20 minutes.
        val beforeSpring = Instant.ofEpochSecond(1_772_952_600) // 2026-03-08 06:50 UTC, 1:50 EST
        val afterSpring = beforeSpring.plusSeconds(20L * 60)
        val springNow = at(3, 12, 10, 0, newYorkZone)
        val spring = newYork(turn(beforeSpring) + turn(afterSpring), springNow)
        assertEquals(listOf(id(beforeSpring)), spring.keys.sorted())
        // Days are counted on the calendar across the change: March 6 is within the week of March 12.
        assertEquals("Sunday\t1:50 AM", spring[id(beforeSpring)])
        val friday = at(3, 6, 9, 0, newYorkZone)
        val thursday = at(3, 5, 9, 0, newYorkZone)
        assertEquals("Friday\t9:00 AM", newYork(turn(friday), springNow)[id(friday)])
        assertEquals("Thu, Mar 5\t9:00 AM", newYork(turn(thursday), springNow)[id(thursday)])
    }

    /** A week ago today is a date, never the weekday that also names today. */
    @Test
    fun aWeekAgoIsADate() {
        val now = at(10, 12, 9, 41)
        val lastMonday = at(10, 5, 10, 0)
        assertEquals("Mon, Oct 5\t10:00 AM", stamps(turn(lastMonday), now = now)[id(lastMonday)])
    }

    @Test
    fun insertsEachStampAboveItsMessage() {
        val now = at(10, 12, 9, 41)
        val items = turn(at(10, 11, 9, 0)) + turn(at(10, 12, 9, 0))
        val entries = TranscriptTimeStamps.inserting(stamps(items, now = now), TranscriptEntry.build(items))
        val kinds = entries.map { entry ->
            when {
                TranscriptTimeStamps.isStamp(entry) -> "stamp"
                entry is TranscriptEntry.UserGroup -> "user"
                entry is TranscriptEntry.ReplyEnd -> "end"
                else -> "reply"
            }
        }
        assertEquals(listOf("stamp", "user", "reply", "end", "stamp", "user", "reply", "end"), kinds)
        // Above the user message it dates: 16 over, 4 under.
        assertEquals(TranscriptMetrics.sectionHalf, entries[0].rowInsets.top)
        assertEquals(TranscriptMetrics.compact, entries[0].rowInsets.bottom)
    }

    @Test
    fun readsAsDayCommaTime() {
        assertEquals("Wednesday, 8:24 AM", TranscriptTimeStamps.accessibilityLabel("Wednesday\t8:24 AM"))
        assertEquals("2:10 PM", TranscriptTimeStamps.accessibilityLabel("2:10 PM"))
    }

    @Test
    fun followsTheLocale() {
        val german = Locale.GERMANY
        val now = at(10, 12, 9, 41)
        val wednesday = at(10, 7, 8, 24)
        val today = at(10, 12, 8, 0)
        val result = TranscriptTimeStamps.stamps(turn(wednesday) + turn(today), now = now, zone = zone, locale = german)
        assertEquals("Mittwoch\t8:24", result[id(wednesday)])
        assertEquals("Heute\t8:00", result[id(today)])
    }

    /** English words, the region's formats. */
    @Test
    fun writesEnglishInTheUsersRegion() {
        assertEquals(Locale.forLanguageTag("en-DE"), appFormattingLocale(Locale.GERMANY))
        assertEquals(Locale.forLanguageTag("en-EG"), appFormattingLocale(Locale.forLanguageTag("ar-EG")))
        assertEquals(Locale.forLanguageTag("en"), appFormattingLocale(Locale.forLanguageTag("ar")))
        assertEquals(Locale.forLanguageTag("en-TW-u-hc-h23"), appFormattingLocale(Locale.forLanguageTag("zh-Hant-TW-u-hc-h23")))
        assertEquals(Locale.US, appFormattingLocale(Locale.US))
        val now = at(10, 12, 9, 41)
        val wednesday = at(10, 7, 21, 5)
        val yesterday = at(10, 11, 21, 5)
        val items = turn(wednesday) + turn(yesterday)
        val german = TranscriptTimeStamps.stamps(items, now = now, zone = zone, locale = appFormattingLocale(Locale.GERMANY))
        assertEquals("Wednesday\t21:05", german[id(wednesday)])
        assertEquals("Yesterday\t21:05", german[id(yesterday)])
        val arabic = plain(TranscriptTimeStamps.stamps(items, now = now, zone = zone, locale = appFormattingLocale(Locale.forLanguageTag("ar"))))
        assertEquals("Yesterday\t9:05 PM", arabic[id(yesterday)])
    }
}
