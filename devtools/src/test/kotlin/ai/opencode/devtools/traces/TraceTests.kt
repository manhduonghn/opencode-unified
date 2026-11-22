package ai.opencode.devtools.traces

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.diagnostics.DiagnosticsEvent
import ai.opencode.core.diagnostics.DiagnosticsJournal
import ai.opencode.core.diagnostics.DiagnosticsRecorder
import ai.opencode.core.diagnostics.ProblemReportBuilder
import ai.opencode.core.diagnostics.ProblemReportBundle
import ai.opencode.core.diagnostics.ProblemReportContext
import ai.opencode.core.diagnostics.ProblemReportDevice
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.devtools.DevToolsClient
import ai.opencode.devtools.Troubleshooting
import ai.opencode.devtools.traces.TraceUploadsFeature.Action
import ai.opencode.devtools.traces.TraceUploadsFeature.State
import ai.opencode.server.DeveloperModeUploads
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeFileReference
import ai.opencode.server.model.OpenCodeUploadEvent
import ai.opencode.server.model.PromptContext
import ai.opencode.core.dependencies.withDependenciesSuspending
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RetryEnvironment
import ai.opencode.server.retry.RetryPolicy
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.util.Base64
import java.util.UUID

private fun event(
    name: String,
    at: Double = 0.0,
    wall: Instant = Instant.ofEpochSecond(1_000),
    kind: DiagnosticsEvent.Kind = DiagnosticsEvent.Kind.Mark,
): DiagnosticsEvent = DiagnosticsEvent(id = UUID.randomUUID(), kind = kind, monotonicSeconds = at, wallDate = wall, name = name)

private class Recorded<T> {
    private val items = mutableListOf<T>()
    val value: List<T> get() = synchronized(this) { items.toList() }
    fun append(item: T) = synchronized(this) { items += item }
}

private class Gate {
    private val channel = Channel<Unit>(Channel.UNLIMITED)
    suspend fun await() {
        channel.receiveCatching()
    }
    fun yield() {
        channel.trySend(Unit)
    }
}

/** JVM tests can't read the app's debuggable flag. */
class TraceUploadsTests {
    @get:Rule val isolation = TestIsolationRule()

    private val context = ProblemReportContext(
        sessionID = "ses_1", serverID = "srv_1",
        screen = "Session", project = "opencode-ios", connection = "Connected",
    )
    private val server = OpenCodeServer(url = "https://box.ts.net:4096", username = "opencode", password = "pw")
    private val id = UUID(0, 0)

    private val haptics = Recorded<String>()

    private fun storeTest(block: suspend CoroutineScope.(Job) -> Unit) = runBlocking {
        val job = Job()
        try {
            block(job)
        } finally {
            job.cancel()
        }
    }

    private fun CoroutineScope.store(
        job: Job,
        state: State = State(),
        dependencies: ai.opencode.core.dependencies.DependencyValues.Builder.() -> Unit,
    ): TestStore<State, Action> = TestStore(state, TraceUploadsFeature(), CoroutineScope(coroutineContext + job)) {
        this[SharedStore] = SharedStore.inMemory()
        this[UuidClient] = UuidClient.incrementing()
        this[HapticsClient] = HapticsClient(play = { haptics.append(it.name) })
        dependencies()
    }

    private fun report(id: UUID = this.id, destination: ProblemReportDestination? = null) =
        State.Report(id = id, context = context, destination = destination)

    @Test
    fun nothingOpensOrUploadsWithDeveloperModeOff() = storeTest { job ->
        val uploads = Recorded<ProblemReportBundle>()
        val store = store(job) {
            this[TraceUploader] = TraceUploader(isEnabled = { false }, upload = { bundle -> uploads.append(bundle); true })
        }
        store.send(Action.Task)
        store.send(Action.ReportProblemTapped(context, null))
        assertTrue(uploads.value.isEmpty())
    }

    @Test
    fun noAutomaticTriggerUploadsAnythingAndACrashIsSavedForTheNextReport() = storeTest { job ->
        val uploads = Recorded<ProblemReportBundle>()
        val copied = Recorded<String>()
        val screenshotCalls = Recorded<Unit>()
        val directory = Files.createTempDirectory("reports-test-").toFile()
        try {
            // The run before: one event, long ago.
            val earlier = DiagnosticsJournal(directory = directory, build = "1 (1)")
            earlier.open(recording = true)
            earlier.append(event("previous_run_step", wall = Instant.ofEpochSecond(0)))
            earlier.flush()
            val recorder = DiagnosticsRecorder(enabled = true, journal = DiagnosticsJournal(directory = directory, build = "1 (1)"))
            recorder.recordMark("app_launch", sessionID = null)

            val store = store(job, State(crashedLastRun = true)) {
                this[DateClient] = DateClient.constant(Instant.ofEpochSecond(10_000))
                this[PasteboardClient] = PasteboardClient(setURL = {}, setString = { copied.append(it) })
                this[ProblemReportCapture] = ProblemReportCapture {
                    screenshotCalls.append(Unit)
                    byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
                }
                this[DiagnosticsRecorder] = recorder
                this[DevToolsClient] = DevToolsClient(appLog = { listOf("2026-10-18T12:00:00Z info app: Launch") })
                this[TraceUploader] = TraceUploader(isEnabled = { true }, upload = { bundle -> uploads.append(bundle); true })
            }

            // Launching does not upload anything automatically, even with a crash noted.
            store.send(Action.Task)
            assertTrue(uploads.value.isEmpty())

            // Opening the sheet and pressing Send uploads the report with the previous run attached,
            // and no screenshot when the toggle is left off by default.
            store.send(Action.ReportProblemTapped(context, null)) { it.copy(report = report()) }
            store.send(Action.NoteChanged("Stream hung on resume")) { it.copy(report = it.report?.copy(note = "Stream hung on resume")) }
            store.send(Action.SendTapped) { it.copy(report = it.report?.copy(phase = State.Report.Phase.Sending)) }
            store.receive(Action.Uploaded(id, true), timeout = kotlin.time.Duration.parse("5s")) {
                it.copy(crashedLastRun = false, report = it.report?.copy(phase = State.Report.Phase.Sent))
            }
            assertEquals(listOf("confirm"), haptics.value)
            assertEquals("Screenshot is off by default", 0, screenshotCalls.value.size)

            val bundle = uploads.value.first()
            assertTrue(bundle.report.crashedLastRun)
            assertEquals("Stream hung on resume", bundle.report.note)
            assertEquals("ses_1", bundle.report.context.sessionID)
            assertEquals("srv_1", bundle.report.context.serverID)
            assertNotNull(bundle.files[ProblemReportBundle.File.PREVIOUS_RUN])
            assertNotNull(bundle.files[ProblemReportBundle.File.APP_LOG])
            assertNull(bundle.files[ProblemReportBundle.File.SCREENSHOT])

            store.send(Action.CopyReportIDTapped) { it.copy(report = it.report?.copy(copiedReportID = true)) }
            assertEquals(listOf("confirm", "tap"), haptics.value)
            store.send(Action.CancelTapped) { it.copy(report = null) }
            store.finish()
            assertEquals(listOf("00000000"), copied.value)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedSendShowsErrorAndRetriesOnTryAgainWithOptionalScreenshot() = storeTest { job ->
        val uploads = Recorded<ProblemReportBundle>()
        var attempt = 0
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val store = store(job) {
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(1_000))
            this[ProblemReportCapture] = ProblemReportCapture { jpeg }
            this[TraceUploader] = TraceUploader(isEnabled = { true }, upload = { bundle ->
                uploads.append(bundle)
                synchronized(this@TraceUploadsTests) { attempt += 1; attempt > 1 }
            })
        }

        store.send(Action.ReportProblemTapped(context, null)) { it.copy(report = report()) }
        store.send(Action.IncludeScreenshotChanged(true)) { it.copy(report = it.report?.copy(includesScreenshot = true)) }
        store.send(Action.SendTapped) { it.copy(report = it.report?.copy(phase = State.Report.Phase.Sending)) }
        store.receive(Action.Uploaded(id, false)) { it.copy(report = it.report?.copy(phase = State.Report.Phase.Failed)) }
        assertEquals(listOf("confirm"), haptics.value)

        store.send(Action.TryAgainTapped) { it.copy(report = it.report?.copy(phase = State.Report.Phase.Sending)) }
        store.receive(Action.Uploaded(id, true)) { it.copy(report = it.report?.copy(phase = State.Report.Phase.Sent)) }
        assertEquals(listOf("confirm", "confirm"), haptics.value)
        assertEquals(2, uploads.value.size)
        assertArrayEquals(jpeg, uploads.value.last().files[ProblemReportBundle.File.SCREENSHOT])
    }

    private data class Prompt(val sessionID: String, val text: String, val files: List<OpenCodeFileReference>, val messageID: String, val delivery: OpenCodeDelivery)

    @Test
    fun aSentReportReachesItsSessionWithTheReportAttached() = storeTest { job ->
        val prompts = Recorded<Prompt>()
        val uploads = Recorded<Triple<String?, String, String>>()
        val clock = TestClock()
        val gate = Gate()
        val destination = ProblemReportDestination(server = server, sessionID = "ses_1", directory = "/work/app")
        val recorder = DiagnosticsRecorder(enabled = true).apply { recordMark("app_launch", sessionID = null) }
        val store = store(job) {
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(10_000))
            this[Clock] = clock
            this[DiagnosticsRecorder] = recorder
            this[TraceUploader] = TraceUploader(isEnabled = { true }, upload = { true }, traceURL = { id -> "https://logs.example/c/owner/t/${id.toString().uppercase()}" })
            // The composer's upload, then a prompt that carries the file.
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                uploadFile = { _, directory, file, name ->
                    uploads.append(Triple(directory, name, runCatching { file.readText() }.getOrDefault("")))
                    flowOf(OpenCodeUploadEvent.Finished("/tmp/opencode/uploads/1/$name"))
                },
                sendPrompt = { _, sessionID, text, _, files, messageID, delivery ->
                    prompts.append(Prompt(sessionID, text, files, messageID, delivery))
                    gate.await()
                },
            )
        }

        store.send(Action.ReportProblemTapped(context, destination)) { it.copy(report = report(destination = destination)) }
        store.send(Action.NoteChanged("Composer jumped")) { it.copy(report = it.report?.copy(note = "Composer jumped")) }
        store.send(Action.SendTapped) { it.copy(report = it.report?.copy(phase = State.Report.Phase.Sending)) }
        store.receive(Action.Uploaded(id, true)) { it.copy(report = it.report?.copy(phase = State.Report.Phase.Sent)) }
        // Closing the sheet once it's uploaded lets the session message finish.
        store.send(Action.Dismissed) { it.copy(report = null) }
        gate.yield()
        store.receive(Action.SentToSession(id, true), timeout = kotlin.time.Duration.parse("5s")) { it.copy(toast = State.Toast.SentToSession) }
        assertEquals(listOf("confirm", "success"), haptics.value)

        assertEquals(1, prompts.value.size)
        val (sessionID, text, files, messageID, delivery) = prompts.value.first()
        assertEquals("ses_1", sessionID)
        assertEquals(OpenCodeDelivery.Queue, delivery)
        assertEquals(ProblemReportBuilder.sessionMessageID(id, followUp = false), messageID)
        // The message stays short; the full report rides as a file the agent reads with its tools.
        assertEquals(listOf(OpenCodeFileReference(name = "report-00000000.md", mime = "text/markdown", path = "/tmp/opencode/uploads/1/report-00000000.md")), files)
        val lines = text.split("\n")
        assertTrue(lines[0], lines[0].startsWith("[problem report 00000000 · "))
        assertEquals(
            listOf(
                "Sent from Report a Problem in the OpenCode Android app (Developer Mode). Report ID: 00000000-0000-0000-0000-000000000000",
                "Trace (timeline, app log): https://logs.example/c/owner/t/00000000-0000-0000-0000-000000000000",
                "",
                "> Composer jumped",
                "",
                "The full report (device, build, screen, timeline and app log) is attached as report-00000000.md; read it with your file tools.",
            ),
            lines.drop(1),
        )
        assertFalse(text.contains("Recent timeline:"))
        assertEquals(1, uploads.value.size)
        val (directory, name, attachment) = uploads.value.first()
        assertEquals("/work/app", directory)
        assertEquals("report-00000000.md", name)
        assertTrue(attachment, attachment.startsWith("## OpenCode Android: Problem report"))
        assertTrue(attachment.contains("> Composer jumped"))
        assertTrue(attachment.contains("- Screen: Session `ses_1` in opencode-ios"))
        assertTrue(attachment.contains("Recent timeline:"))
        assertTrue(attachment.contains("## Timeline"))
        assertTrue(attachment.contains("app_launch"))
        assertFalse(attachment.contains("://"))
        assertFalse(attachment.contains("box.ts.net"))

        clock.advance(TraceUploadsFeature.TOAST_DURATION)
        store.receive(Action.ToastExpired) { it.copy(toast = null) }
    }

    @Test
    fun aFailedUploadStillSendsTheSummaryInlineWhereFilesArentSupported() = storeTest { job ->
        val prompts = Recorded<String>()
        val attachmentUploads = Recorded<Unit>()
        val messageIDs = Recorded<String?>()
        var attempt = 0
        val destination = ProblemReportDestination(server = server, sessionID = "ses_recent")
        val store = store(job) {
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(10_000))
            this[Clock] = ImmediateClock()
            this[TraceUploader] = TraceUploader(
                isEnabled = { true },
                upload = { synchronized(this@TraceUploadsTests) { attempt += 1; attempt > 1 } },
                traceURL = { id -> "https://logs.example/c/owner/t/${id.toString().uppercase()}" },
            )
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                uploadFile = { _, _, _, _ ->
                    attachmentUploads.append(Unit)
                    flow { throw OpenCodeClientError.uploadsUnsupported }
                },
                sendDeliveredPrompt = { _, _, text, _ ->
                    prompts.append(text)
                    val current = PromptContext.messageID.current()
                    messageIDs.append(current)
                    current ?: "msg_1"
                },
            )
        }

        store.send(Action.ReportProblemTapped(context, destination)) { it.copy(report = report(destination = destination)) }
        store.send(Action.SendTapped) { it.copy(report = it.report?.copy(phase = State.Report.Phase.Sending)) }
        // Try Again waits until the summary has settled in the session.
        store.receive(Action.Uploaded(id, false))
        // No file support: remembered, and the whole summary goes inline.
        store.receive(Action.UploadsUnsupported(server.persistenceID))
        store.receive(Action.SentToSession(id, true)) {
            it.copy(report = it.report?.copy(deliveredToSession = true, phase = State.Report.Phase.Failed), toast = State.Toast.SentToSession)
        }
        store.receive(Action.ToastExpired) { it.copy(toast = null) }
        assertEquals(1, prompts.value.size)
        assertTrue(prompts.value[0].contains("The trace upload failed, so only this summary arrived."))
        assertFalse(prompts.value[0].contains("https://logs.example"))
        assertTrue(prompts.value[0].contains("## OpenCode Android: Problem report"))

        store.send(Action.TryAgainTapped) { it.copy(report = it.report?.copy(phase = State.Report.Phase.Sending)) }
        store.receive(Action.Uploaded(id, true)) { it.copy(report = it.report?.copy(phase = State.Report.Phase.Sent)) }
        store.receive(Action.SentToSession(id, true)) { it.copy(toast = State.Toast.SentToSession) }
        store.receive(Action.ToastExpired) { it.copy(toast = null) }
        assertEquals(2, prompts.value.size)
        assertEquals(
            "[problem report 00000000 · trace uploaded]\nThe retry uploaded the trace for report 00000000-0000-0000-0000-000000000000: https://logs.example/c/owner/t/00000000-0000-0000-0000-000000000000",
            prompts.value[1],
        )
        assertEquals(listOf("confirm", "success", "confirm", "success"), haptics.value)
        // Stable per report, and the summary and the link differ, so the server admits each once
        // however often they're sent.
        assertEquals(
            listOf(ProblemReportBuilder.sessionMessageID(id, followUp = false), ProblemReportBuilder.sessionMessageID(id, followUp = true)),
            messageIDs.value,
        )
        assertNotEquals(messageIDs.value[0], messageIDs.value[1])
        assertTrue(messageIDs.value[0]?.startsWith("msg_") == true)

        // The next report to this server doesn't try to attach.
        val second = UUID(0, 1)
        store.send(Action.Dismissed) { it.copy(report = null) }
        store.send(Action.ReportProblemTapped(context, destination)) { it.copy(report = report(id = second, destination = destination)) }
        store.send(Action.SendTapped) { it.copy(report = it.report?.copy(phase = State.Report.Phase.Sending)) }
        store.receive(Action.Uploaded(second, true)) { it.copy(report = it.report?.copy(phase = State.Report.Phase.Sent)) }
        store.receive(Action.SentToSession(second, true)) {
            it.copy(report = it.report?.copy(deliveredToSession = true), toast = State.Toast.SentToSession)
        }
        store.receive(Action.ToastExpired) { it.copy(toast = null) }
        assertEquals(1, attachmentUploads.value.size)
        assertEquals(3, prompts.value.size)
        assertTrue(prompts.value[2].contains("## OpenCode Android: Problem report"))
    }

    @Test
    fun aFailedAttachmentUploadSendsTheReportInline() = storeTest { job ->
        val prompts = Recorded<String>()
        val destination = ProblemReportDestination(server = server, sessionID = "ses_1", directory = "/work/app")
        val store = store(job) {
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(10_000))
            this[Clock] = ImmediateClock()
            this[TraceUploader] = TraceUploader(isEnabled = { true }, upload = { true }, traceURL = { "https://logs.example/t" })
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                uploadFile = { _, _, _, _ -> flow { throw OpenCodeClientError("The server returned HTTP 500.", statusCode = 500) } },
                sendDeliveredPrompt = { _, _, text, _ ->
                    prompts.append(text)
                    "msg_1"
                },
            )
        }

        store.send(Action.ReportProblemTapped(context, destination)) { it.copy(report = report(destination = destination)) }
        store.send(Action.SendTapped) { it.copy(report = it.report?.copy(phase = State.Report.Phase.Sending)) }
        store.receive(Action.Uploaded(id, true)) { it.copy(report = it.report?.copy(phase = State.Report.Phase.Sent)) }
        // A one-off failure isn't remembered as unsupported.
        store.receive(Action.SentToSession(id, true)) {
            it.copy(report = it.report?.copy(deliveredToSession = true), toast = State.Toast.SentToSession)
        }
        store.receive(Action.ToastExpired) { it.copy(toast = null) }
        assertEquals(1, prompts.value.size)
        assertTrue(prompts.value[0].contains("Trace (timeline, app log): https://logs.example/t"))
        assertTrue(prompts.value[0].contains("## OpenCode Android: Problem report"))
    }

    @Test
    fun closingWhileTheSummaryIsStillSendingCancelsItAndTryAgainWaits() = storeTest { job ->
        val started = CompletableDeferred<Unit>()
        var starts = 0
        val destination = ProblemReportDestination(server = server, sessionID = "ses_1")
        val store = store(job) {
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(10_000))
            this[Clock] = ImmediateClock()
            this[TraceUploader] = TraceUploader(isEnabled = { true }, upload = { false }, traceURL = { "https://logs.example/t" })
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                uploadFile = { _, _, _, name -> flowOf(OpenCodeUploadEvent.Finished("/tmp/$name")) },
                sendPrompt = { _, _, _, _, _, _, _ ->
                    synchronized(this@TraceUploadsTests) { starts += 1 }
                    started.complete(Unit)
                    awaitCancellation()
                },
            )
        }

        store.send(Action.ReportProblemTapped(context, destination)) { it.copy(report = report(destination = destination)) }
        store.send(Action.SendTapped) { it.copy(report = it.report?.copy(phase = State.Report.Phase.Sending)) }
        store.receive(Action.Uploaded(id, false))
        started.await()
        // Still sending to the session: Try Again does nothing.
        store.send(Action.TryAgainTapped)
        store.send(Action.Dismissed) { it.copy(report = null) }
        store.finish()
        assertEquals(1, starts)
    }

    @Test
    fun aSessionThatCantBeReachedSaysSo() = storeTest { job ->
        val destination = ProblemReportDestination(server = server, sessionID = "ses_1")
        val store = store(job) {
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(10_000))
            this[Clock] = ImmediateClock()
            this[TraceUploader] = TraceUploader(isEnabled = { true }, upload = { true }, traceURL = { "https://logs.example/t" })
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                uploadFile = { _, _, _, name -> flowOf(OpenCodeUploadEvent.Finished("/tmp/$name")) },
                sendPrompt = { _, _, _, _, _, _, _ -> throw OpenCodeClientError("Session not found.", statusCode = 404) },
            )
        }

        store.send(Action.ReportProblemTapped(context, destination)) { it.copy(report = report(destination = destination)) }
        store.send(Action.SendTapped) { it.copy(report = it.report?.copy(phase = State.Report.Phase.Sending)) }
        store.receive(Action.Uploaded(id, true)) { it.copy(report = it.report?.copy(phase = State.Report.Phase.Sent)) }
        store.receive(Action.SentToSession(id, false)) { it.copy(toast = State.Toast.SessionFailed) }
        store.receive(Action.ToastExpired) { it.copy(toast = null) }
        assertEquals(listOf("confirm", "error"), haptics.value)
    }

    @Test
    fun noReportOutputCarriesTheServerAddressOrUsername() {
        val secret = OpenCodeServer(url = "https://secret-box.ts.net:4096", username = "kituser", password = "pw")
        val device = ProblemReportDevice(appVersion = "0.1.0", build = "37", distribution = "TestFlight", system = "iOS 26.0", model = "iPhone18,2")
        // An older caller's raw persistence ID is hashed too.
        for (serverID in listOf(ProblemReportBuilder.serverKey(secret.persistenceID), secret.persistenceID)) {
            val bundle = ProblemReportBuilder.make(
                id = id, note = "note", context = context.copy(serverID = serverID), device = device, now = Instant.ofEpochSecond(1_000),
                events = listOf(event("app_launch", kind = DiagnosticsEvent.Kind.Lifecycle)), screenshot = null,
            )
            val raw = bundle.copy(report = bundle.report.copy(context = bundle.report.context.copy(serverID = secret.persistenceID)))
            val outputs = listOf(
                ProblemReportBuilder.summary(bundle.report, hasScreenshot = false),
                ProblemReportBuilder.summary(raw.report, hasScreenshot = false),
                ProblemReportBuilder.sessionMessage(bundle, traceURL = null),
                ProblemReportBuilder.sessionMessage(raw, traceURL = null),
                DeveloperModeUploads.traceBody(bundle).toString(),
                DeveloperModeUploads.traceBody(raw).toString(),
            )
            for (output in outputs) {
                assertFalse(output, output.contains("://"))
                assertFalse(output, output.contains(":\\/\\/"))
                assertFalse(output.contains("kituser"))
                assertFalse(output.contains("secret-box"))
            }
        }
    }

    @Test
    fun traceUploadRidesSendRetryPolicy() = runBlocking {
        assertEquals(RetryPolicy.send, TraceUploadsFeature.RETRY_POLICY)
        val device = ProblemReportDevice(appVersion = "0.1.0", build = "37", distribution = "TestFlight", system = "iOS 26.0", model = "iPhone18,2")
        val bundle = ProblemReportBuilder.make(
            id = UUID.fromString("4DB11E09-4794-431A-897C-F9571032F194"),
            context = context, device = device, now = Instant.ofEpochSecond(1_000),
            events = emptyList(), screenshot = null,
        )
        val attempts = AtomicInteger(0)
        val clock = SleepRecordingClock()
        val ok = withDependenciesSuspending({ this[SharedStore] = SharedStore.inMemory() }) {
            NetworkRetry.policy.withValue(TraceUploadsFeature.RETRY_POLICY) {
                NetworkRetry.clock.withValue(clock) {
                    NetworkRetry.environment.withValue(RetryEnvironment.online(unit = 0.0)) {
                        DeveloperModeUploads.upload(bundle) { request ->
                            if (attempts.incrementAndGet() < 3) throw SocketException("Connection reset")
                            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(201).message("Created")
                                .body(ByteArray(0).toResponseBody()).build()
                        }
                    }
                }
            }
        }
        assertTrue(ok)
        assertEquals(3, attempts.get())
        assertEquals(2, clock.sleeps.size)
    }

    /** Sleeps return at once, each recorded. */
    private class SleepRecordingClock : Clock {
        private var time = Duration.ZERO
        val sleeps = mutableListOf<Duration>()
        override fun now(): Duration = synchronized(this) { time }
        override suspend fun sleep(duration: Duration) = synchronized(this) {
            sleeps += duration
            time += duration
        }
    }

    @Test
    fun theTraceBodyMatchesTheWorker() {
        val device = ProblemReportDevice(appVersion = "0.1.0", build = "37", distribution = "TestFlight", system = "iOS 26.0", model = "iPhone18,2")
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val bundle = ProblemReportBuilder.make(
            id = UUID.fromString("4DB11E09-4794-431A-897C-F9571032F194"),
            note = "Button did not respond",
            context = ProblemReportContext(sessionID = "ses_abc", serverID = "srv_1", screen = "Session"), device = device,
            now = Instant.ofEpochSecond(1_000),
            events = listOf(event("app_launch", kind = DiagnosticsEvent.Kind.Lifecycle)),
            appLog = listOf("2026-10-18T12:00:00Z info app: Launch"),
            screenshot = jpeg,
        )
        val body = DeveloperModeUploads.traceBody(bundle)
        fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content
        assertEquals("4DB11E09-4794-431A-897C-F9571032F194", body.string("id"))
        assertEquals("report", body.string("trigger"))
        assertEquals("Problem report", body.string("title"))
        assertEquals("Button did not respond", body.string("note"))
        assertEquals("ses_abc", body.string("sessionID"))
        assertEquals("srv_1", body.string("serverID"))
        assertEquals("ses_abc", body["context"]?.jsonObject?.string("sessionID"))
        assertEquals("srv_1", body["context"]?.jsonObject?.string("serverID"))
        assertEquals("iPhone18,2", body["device"]?.jsonObject?.string("model"))
        val files = body.getValue("files").jsonObject
        assertEquals(Base64.getEncoder().encodeToString(jpeg), files.string("screenshot.jpg"))
        assertTrue(files.string("timeline.json")?.contains("app_launch") == true)
        assertTrue(files.string("app-log.txt")?.contains("info app: Launch") == true)
    }
}

class DiagnosticsPrivacyTests {
    @get:Rule val isolation = TestIsolationRule()

    /**
     * Application state is whether an activity is resumed; a secure first responder is a
     * `FLAG_SECURE` window.
     */
    @Test
    fun screenshotsOnlyInTheForegroundAndAwayFromCredentials() {
        assertTrue(WindowSnapshot.isAllowed(isActive = true, credentialSurfaces = 0, isSecureWindow = false))
        assertFalse(WindowSnapshot.isAllowed(isActive = false, credentialSurfaces = 0, isSecureWindow = false))
        assertFalse(WindowSnapshot.isAllowed(isActive = true, credentialSurfaces = 1, isSecureWindow = false))
        assertFalse(WindowSnapshot.isAllowed(isActive = true, credentialSurfaces = 0, isSecureWindow = true))
    }

    @Test
    fun diagnosticsIndexesMetricsByRequest() {
        val metrics = event("network_metrics", kind = DiagnosticsEvent.Kind.NetworkMetrics).copy(requestID = "r1")
        val end = event("request_end", kind = DiagnosticsEvent.Kind.RequestEnd).copy(requestID = "r1")
        assertEquals("network_metrics", Troubleshooting.metricsByRequest(listOf(end, metrics))["r1"]?.name)
    }
}
