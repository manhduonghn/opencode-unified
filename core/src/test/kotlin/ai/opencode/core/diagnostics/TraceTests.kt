package ai.opencode.core.diagnostics

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.testing.eventually
import ai.opencode.core.tuning.InternalBuild
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

private fun temporaryDirectory(): File = Files.createTempDirectory("reports-test-").toFile()

private fun event(
    name: String,
    at: Double = 0.0,
    wall: Instant = Instant.ofEpochSecond(1_000),
    kind: DiagnosticsEvent.Kind = DiagnosticsEvent.Kind.Mark,
): DiagnosticsEvent = DiagnosticsEvent(id = UUID.randomUUID(), kind = kind, monotonicSeconds = at, wallDate = wall, name = name)

/** The recorder's two switches in memory. */
private class MemorySettings : DiagnosticsSettings {
    val values = HashMap<String, Boolean>()
    override fun bool(key: String): Boolean? = values[key]
    override fun set(key: String, value: Boolean) {
        values[key] = value
    }
}

private const val BUILD = "1 (1)"

class DiagnosticsJournalTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun rotatesWithinTwoSegmentsAndHandsTheTailToTheNextRun() {
        val directory = temporaryDirectory()
        try {
            val journal = DiagnosticsJournal(directory = directory, segmentBytes = 4_000, build = BUILD)
            assertFalse(journal.open(recording = true).endedUnexpectedly)
            for (index in 0 until 400) journal.append(event("event_$index", at = index.toDouble()))
            journal.flush()

            val total = directory.listFiles().orEmpty().sumOf { it.length() }
            assertTrue("$total", total <= 2 * 4_000)
            val written = journal.currentRunEvents()
            assertEquals("event_399", written.last().name)
            assertTrue(written.size < 400)

            // The next launch reads this run as its previous one, newest last.
            val next = DiagnosticsJournal(directory = directory, segmentBytes = 4_000, build = BUILD)
            next.open(recording = true)
            assertEquals(written.map { it.name }, next.previousRunEvents().map { it.name })
            assertTrue(next.currentRunEvents().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aRunThatEndsInTheForegroundIsUnexpected() {
        val directory = temporaryDirectory()
        try {
            val first = DiagnosticsJournal(directory = directory, build = BUILD)
            first.open(recording = true)
            first.append(event("launched"))
            first.markForeground()
            first.flush()
            assertTrue(DiagnosticsJournal(directory = directory, build = BUILD).open(recording = true).endedUnexpectedly)

            val second = DiagnosticsJournal(directory = directory, build = BUILD)
            second.open(recording = true)
            second.append(event("launched"))
            second.markForeground()
            second.markBackground(waits = true) // waited for, as at termination
            assertFalse(DiagnosticsJournal(directory = directory, build = BUILD).open(recording = true).endedUnexpectedly)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun recorderPersistsEventsAndForgetsThemWhenTurnedOff() {
        val directory = temporaryDirectory()
        try {
            val journal = DiagnosticsJournal(directory = directory, build = BUILD)
            val recorder = DiagnosticsRecorder(defaultEnabled = true, journal = journal)
            assertTrue(recorder.isEnabled)
            recorder.recordMark("app_launch", sessionID = null)
            journal.flush()
            assertEquals(listOf("app_launch"), journal.currentRunEvents().map { it.name })

            recorder.setEnabled(false)
            journal.flush()
            assertTrue(journal.currentRunEvents().isEmpty())
            assertTrue(journal.previousRunEvents().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}

/**
 * Android's `InternalBuild.detect` takes the installer package: a sideloaded or Firebase App
 * Distribution install (no Play installer) is internal.
 */
class InternalBuildTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun debugAndTestFlightRecordButTheAppStoreDoesNot() {
        assertTrue(InternalBuild.detect(isDebug = true, installingPackageName = null))
        assertTrue(InternalBuild.detect(isDebug = false, installingPackageName = "dev.firebase.appdistribution"))
        assertFalse(InternalBuild.detect(isDebug = false, installingPackageName = "com.android.vending"))
        assertTrue(InternalBuild.detect(isDebug = true, installingPackageName = "com.android.vending"))
    }

    @Test
    fun recordingDefaultsOnOnlyWhereAsked() {
        assertTrue(DiagnosticsRecorder(defaultEnabled = true).isEnabled)
        assertFalse(DiagnosticsRecorder().isEnabled)
        val defaults = MemorySettings()
        defaults.set(DiagnosticsRecorder.DEFAULTS_KEY, false)
        // Turning the toggle off sticks, even in builds that default on.
        assertFalse(DiagnosticsRecorder(settings = defaults, defaultEnabled = true).isEnabled)
    }

    @Test
    fun voiceExcerptsFollowIncludeTranscripts() {
        val defaults = MemorySettings()
        val recorder = DiagnosticsRecorder(enabled = true, settings = defaults, defaultEnabled = true)
        recorder.recordVoice("voice_user_transcript", sessionID = "ses_1", detail = "item=a", excerpt = "word ".repeat(60))
        val first = recorder.snapshot().first()
        assertEquals(DiagnosticsEvent.Kind.Voice, first.kind)
        assertEquals(DiagnosticsRecorder.EXCERPT_LIMIT, first.excerpt?.length)
        assertTrue(first.excerpt?.endsWith("…") == true)
        assertTrue(recorder.exportText().contains("voice_user_transcript item=a “word word"))
        recorder.setIncludesTranscripts(false)
        recorder.recordVoice("voice_user_transcript", sessionID = "ses_1", detail = "item=b", excerpt = "secret")
        val events = recorder.snapshot()
        assertEquals(listOf(DiagnosticsEvent.Kind.Voice, DiagnosticsEvent.Kind.Voice), events.map { it.kind })
        assertTrue(events.all { it.excerpt == null })
        assertFalse(recorder.exportText().contains("secret"))
    }
}

class ProblemReportBundleTests {
    @get:Rule val isolation = TestIsolationRule()

    private val device = ProblemReportDevice(appVersion = "0.1.0", build = "37", distribution = "TestFlight", system = "iOS 26.0", model = "iPhone18,2")

    @Test
    fun bundleCarriesContextTimelineAppLogAndScreenshotWithoutSecrets() {
        val context = ProblemReportContext(
            sessionID = "ses_abc", serverID = "srv_persistence_1",
            screen = "Session", project = "opencode-ios",
            connection = "Connected", call = "Reconnecting… · 1 request working",
        )
        val events = listOf(
            event("request_end", at = 1.0, kind = DiagnosticsEvent.Kind.RequestEnd),
            event("voice_connected", at = 2.0, kind = DiagnosticsEvent.Kind.Voice),
            event("voice_dropped", at = 3.0, kind = DiagnosticsEvent.Kind.Voice),
        )
        val bundle = ProblemReportBuilder.make(
            id = UUID.randomUUID(), note = "Stuck after reconnect",
            context = context, device = device, now = Instant.ofEpochSecond(2_000), events = events,
            appLog = listOf("2026-10-18T12:00:00Z notice network: GET /api/info: HTTP 200 after 42 ms"),
            screenshot = ByteArray(1_000) { 1 },
        )
        assertEquals(setOf("timeline.txt", "timeline.json", "app-log.txt", "screenshot.jpg"), bundle.files.keys)
        assertEquals("Successful requests aren't highlights", 2, bundle.report.highlights.size)
        val summary = ProblemReportBuilder.summary(bundle.report, hasScreenshot = true)
        for (expected in listOf(
            "Problem report", "> Stuck after reconnect",
            "0.1.0 (37), TestFlight", "iPhone18,2, iOS 26.0",
            "- Session: `ses_abc`", "- Server ID: `srv_persistence_1`",
            "`ses_abc` in opencode-ios", "Reconnecting… · 1 request working", "voice_dropped",
        )) {
            assertTrue(expected, summary.contains(expected))
        }
        val everything = summary + bundle.files.values.joinToString("") { String(it) }
        assertTrue(everything.contains("GET /api/info: HTTP 200 after 42 ms"))
        assertFalse(summary.contains("Live Activities"))
    }

    @Test
    fun summaryNamesTheLiveActivityState() {
        val device = device.copy(liveActivities = "Off in Settings · Couldn’t start: ActivityAuthorizationError.denied")
        val bundle = ProblemReportBuilder.make(
            id = UUID.randomUUID(), context = ProblemReportContext(),
            device = device, now = Instant.ofEpochSecond(2_000), events = emptyList(), screenshot = null,
        )
        val summary = ProblemReportBuilder.summary(bundle.report, hasScreenshot = false)
        assertTrue(summary.contains("- Live Activities: Off in Settings · Couldn’t start: ActivityAuthorizationError.denied"))
    }

    @Test
    fun staysWithinTheSizeBudget() {
        val reason = "x".repeat(400)
        val events = (0 until 8_000).map { index ->
            event("voice_assistant_done", at = index.toDouble(), kind = DiagnosticsEvent.Kind.Voice).copy(reason = reason)
        }
        val bundle = ProblemReportBuilder.make(
            id = UUID.randomUUID(), crashedLastRun = true, context = ProblemReportContext(), device = device,
            now = Instant.ofEpochSecond(2_000), events = events, previousRun = events,
            screenshot = ByteArray(ProblemReportBuilder.SCREENSHOT_LIMIT + 1),
        )
        val total = bundle.files.values.sumOf { it.size }
        assertTrue("$total", total < 2_000_000)
        assertNull("An oversized screenshot is left out", bundle.files["screenshot.jpg"])
        assertNotNull(bundle.files["previous-run.txt"])
        // The newest events survive the cut.
        val json = String(bundle.files["timeline.json"]!!)
        assertTrue(json.contains("\"monotonicSeconds\": 7999.0"))
    }

    @Test
    fun previousRunIsAttachedOnlyAfterACrashOrWhenRecent() {
        val old = listOf(event("old", wall = Instant.ofEpochSecond(0)))
        val now = Instant.ofEpochSecond(10_000)
        val report = ProblemReportBuilder.make(id = UUID.randomUUID(), context = ProblemReportContext(), device = device, now = now, events = emptyList(), previousRun = old, screenshot = null)
        assertNull(report.files["previous-run.txt"])
        val crash = ProblemReportBuilder.make(
            id = UUID.randomUUID(), crashedLastRun = true, context = ProblemReportContext(), device = device, now = now,
            events = emptyList(), previousRun = old, screenshot = null,
        )
        assertNotNull(crash.files["previous-run.txt"])
        assertTrue("Plain marks aren't highlights", crash.report.highlights.isEmpty())
    }
}

class DiagnosticsPrivacyTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun turningTranscriptsOffForgetsEveryExcerpt() {
        val directory = temporaryDirectory()
        try {
            val defaults = MemorySettings()

            // A previous run that kept an excerpt.
            val earlier = DiagnosticsJournal(directory = directory, build = BUILD)
            earlier.open(recording = true)
            earlier.append(event("voice_user_transcript", kind = DiagnosticsEvent.Kind.Voice).copy(excerpt = "my bank password is hunter2"))
            earlier.flush()

            val journal = DiagnosticsJournal(directory = directory, build = BUILD)
            val recorder = DiagnosticsRecorder(settings = defaults, defaultEnabled = true, journal = journal)
            recorder.recordVoice("voice_user_transcript", sessionID = null, excerpt = "call my mother")
            journal.flush()
            assertEquals("call my mother", recorder.snapshot().first().excerpt)
            assertEquals("my bank password is hunter2", recorder.previousRunEvents().first().excerpt)

            recorder.setIncludesTranscripts(false)
            journal.flush()
            assertTrue(recorder.snapshot().all { it.excerpt == null })
            assertTrue(recorder.previousRunEvents().all { it.excerpt == null })
            assertTrue(journal.currentRunEvents().all { it.excerpt == null })
            assertTrue(journal.previousRunEvents().all { it.excerpt == null })
            val onDisk = directory.listFiles().orEmpty().joinToString("") { runCatching { it.readText() }.getOrDefault("") }
            assertFalse(onDisk.contains("hunter2"))
            assertFalse(onDisk.contains("mother"))
            assertTrue("The marks stay; only what was said goes", onDisk.contains("voice_user_transcript"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun pairingCodesAndFilePathsNeverReachTheTimeline() {
        assertEquals("/auth/connect/<redacted>", DiagnosticsRecorder.redactedPath("/auth/connect/abc123-secret"))
        assertEquals("/api/fs/read/<redacted>", DiagnosticsRecorder.redactedPath("/api/fs/read/Users/kit/notes.md"))
        assertEquals("/api/session/ses_1/message", DiagnosticsRecorder.redactedPath("/api/session/ses_1/message"))
        val recorder = DiagnosticsRecorder(enabled = true)
        val url = "https://box.example/auth/connect/abc123-secret"
        recorder.requestStarted(id = "pair", method = "GET", url = url, reason = "/auth/connect/abc123-secret")
        recorder.requestEnded(
            id = "pair", method = "GET", url = url, statusCode = 401, errorCategory = DiagnosticsErrorCategory.Unauthorized,
            decodedBytes = 0, contentEncoding = null,
        )
        val export = recorder.exportJSON() + recorder.exportText()
        assertFalse(export.contains("abc123-secret"))
        assertTrue(export.contains("/auth/connect/<redacted>"))
    }

    @Test
    fun changeNotificationsAreCoalesced() = runBlocking {
        val recorder = DiagnosticsRecorder(enabled = true)
        for (index in 0 until 300) recorder.recordMark("mark_$index", sessionID = null)
        assertTrue(eventually(timeout = 3.seconds, wallClock = true) { recorder.changes.value > 0 })
        assertTrue("${recorder.changes.value} notifications for 300 events", recorder.changes.value in 1..2)
    }
}

class CrashReportTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun anUpdateIsNotACrash() {
        val directory = temporaryDirectory()
        try {
            val old = DiagnosticsJournal(directory = directory, build = "0.1.0 (36)")
            old.open(recording = true)
            old.append(event("launched"))
            old.markForeground()
            old.flush()
            // The update replaced the process while it was in the foreground.
            val updated = DiagnosticsJournal(directory = directory, build = "0.1.0 (37)").open(recording = true)
            assertFalse(updated.endedUnexpectedly)
        } finally {
            directory.deleteRecursively()
        }
    }
}
