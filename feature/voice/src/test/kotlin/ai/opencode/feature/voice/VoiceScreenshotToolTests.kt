package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.diagnostics.ProblemReportDevice
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeFileReference
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeUploadEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `capture_app_screenshot`: Developer Mode only, the app's own window sent
 * as an image with a secret-free app state, and an honest result.
 *
 * Dropped: `refusesAnyWindowShowingAPasswordFieldMarkedOrNot`, `onlyAPasswordFieldOnScreenRefuses`,
 * `aPasswordFieldMidAnimationStillRefuses`, `settingsCredentialsAreMarkedForEveryScreenshot`,
 * `problemReportsAndVoiceScreenshotsKeepSeparatePolicies`,
 * `captureDrawsTheHostedWindowNotABlankImage`.
 */
class VoiceScreenshotToolTests {
    private val server = OpenCodeServer(url = "https://kit:hunter2@studio.example.ts.net:4096", username = "kit", password = "hunter2", name = "Studio")
    private val session = OpenCodeSession(id = "ses_voice", serverTitle = "OpenCode iOS", projectID = "p", directory = "/Users/kit/opencode-ios", updatedAt = Instant.parse("0001-01-01T00:00:00Z"))
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x01, 0x02, 0xFF.toByte(), 0xD9.toByte())
    private val device = ProblemReportDevice(appVersion = "1.0", build = "1", distribution = "Debug", system = "Android 16 (API 36)", model = "Google Pixel 8")

    private data class Sent(
        val sessionID: String,
        val text: String,
        val attachments: List<OpenCodeOutgoingAttachment>,
        val files: List<OpenCodeFileReference>,
    )

    private fun appState(): VoiceAppState {
        val call = SpeechTestShared().run {
            VoiceFeature.State(server, session).withPhase(VoiceFeature.State.Phase.Live)
        }
        return VoiceAppState(call, experiments = listOf("in-app-browser"), developerMode = true, device = device)
    }

    private fun client(sent: Locked<List<Sent>>): OpenCodeClient = OpenCodeClient.testValue.copy(
        getSession = { _, _ -> session },
        familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
        moveToBackground = { _, _ -> },
        sendPrompt = { _, sessionID, text, attachments, files, _, _ ->
            sent.update { it + Sent(sessionID = sessionID, text = text, attachments = attachments, files = files) }
        },
        uploadFile = { _, _, _, name -> flowOf(OpenCodeUploadEvent.Finished(path = "/tmp/uploads/$name")) },
    )

    private fun workspace(
        client: OpenCodeClient,
        developerMode: Boolean = true,
        capture: suspend () -> VoiceScreenshotClient.Result,
        board: VoiceSessionBoard? = null,
        callLog: VoiceCallLog? = null,
        appLog: List<String> = emptyList(),
    ): VoiceDelegatorWorkspace = VoiceDelegatorWorkspace(
        client = client,
        start = VoiceDelegatorWorkspace.Start(
            server = server,
            viewing = session,
            draftDirectory = session.directory,
            draftModel = null,
            transcriptDelta = listOf("user: look at this"),
            explained = emptySet(),
            board = { board },
            callLogSnapshot = { callLog },
            developerMode = developerMode,
            appState = appState(),
            captureScreenshot = capture,
            appLog = { appLog },
            latency = VoiceDelegatorWorkspace.Latency(total = 5.seconds, optional = 200.milliseconds, upload = 500.milliseconds),
        ),
        effects = VoiceDelegatorWorkspace.Effects(open = {}, explained = {}, note = {}),
    )

    private suspend fun result(workspace: VoiceDelegatorWorkspace, arguments: String): JsonObject {
        val output = workspace.run(VoiceDelegator.ToolCall(callID = "c", name = "capture_app_screenshot", arguments = arguments))
        return runCatching { jsonObject(output) }.getOrNull() ?: throw AssertionError(output)
    }

    private fun appStateJSON(text: String): JsonObject {
        val start = text.indexOf('{')
        assertTrue(text, start >= 0)
        val body = Json.parseToJsonElement(text.substring(start)) as JsonObject
        return body["app_state"] as JsonObject
    }

    private fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
    private fun JsonElement.name(): String? = ((this as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull

    // Schema

    @Test fun theToolIsInTheSchemaOnlyInDeveloperMode() {
        val shared = SpeechTestShared()
        shared.run {
            val developerMode = shared(SharedKeys.developerMode)
            fun names(): List<String> = VoiceDelegator.tools.mapNotNull { it.name() }
            fun liveNames(): List<String> = (VoiceDelegator.liveTools as? JsonArray)?.mapNotNull { it.name() } ?: emptyList()

            developerMode.set(false)
            assertFalse(names().contains("capture_app_screenshot"))
            assertFalse(liveNames().contains("capture_app_screenshot"))
            val bodyTools = VoiceDelegator.requestBody(emptyList(), previousResponseID = null)["tools"] as? JsonArray ?: JsonArray(emptyList())
            assertFalse(bodyTools.any { it.name() == "capture_app_screenshot" })

            developerMode.set(true)
            assertEquals("capture_app_screenshot", names().last())
            assertEquals("capture_app_screenshot", liveNames().last())
            val tool = VoiceDelegator.tools.last()
            val properties = tool.obj("parameters")?.obj("properties")
            assertEquals(setOf("text", "session_id", "attach_call_log", "attach_app_log"), properties?.keys)
            assertTrue(VoiceDelegator.instructions.contains("only when its result has screenshot_attached true"))
        }
    }

    // Workspace

    @Test fun sendsTheScreenshotAppStateAndLogsInOneVoiceMessage() = runBlocking<Unit> {
        val sent = Locked(listOf<Sent>())
        val home = VoiceAppState.Home(
            drawerOpen = false, settingsShown = true, catchUpShown = false,
            composer = VoiceAppState.Composer(draftCharacters = 12, dictation = "idle", images = 1, files = 0, pendingPermissions = 0, pendingQuestions = 1),
        )
        val board = VoiceSessionBoard(
            serverID = server.persistenceID, sessions = listOf(session), busyIDs = emptySet(), attentionIDs = emptySet(), unreadIDs = emptySet(),
            pinnedIDs = emptyList(), attentionDirectories = emptySet(), isLive = true,
        ).copy(home = home)
        val log = VoiceCallLog(id = UUID.randomUUID(), startedAt = Instant.ofEpochSecond(1_000), title = "Voice lab", engine = "gptLive")
            .let { it.copy(notes = it.notes + VoiceCallLog.Note(id = 1, t = 1.0, kind = VoiceCallLog.Note.Kind.marker, text = "Marker one")) }
        val capture = VoiceScreenshotClient.Capture(jpeg = jpeg, screen = "Settings › Voice", appearance = VoiceAppState.Appearance(style = "dark", dynamicType = "L"))
        val tools = workspace(
            client(sent),
            capture = { VoiceScreenshotClient.Result.Taken(capture) },
            board = board,
            callLog = log,
            appLog = listOf("2026-10-25T10:00:00Z info network: GET https://studio.example.ts.net:4096/api/info 200", "2026-10-25T10:00:01Z info voice: call live"),
        )

        val result = result(tools, """{"text":"The voice row is misaligned.","attach_call_log":true,"attach_app_log":true}""")

        assertEquals(result.toString(), true, result.bool("screenshot_attached"))
        assertEquals("OpenCode iOS", result.string("sent_to"))
        assertEquals("Settings › Voice", result.string("screen"))
        assertEquals(true, result.bool("call_log_attached"))
        assertEquals(true, result.bool("app_log_attached"))
        assertEquals(1, sent.value.size)
        val message = sent.value.first()
        assertEquals(session.id, message.sessionID)
        assertEquals(listOf("image/jpeg", "text/plain"), message.attachments.map { it.mime })
        assertTrue(message.attachments.first().data.contentEquals(jpeg))
        val appLog = message.attachments.last().data.toString(Charsets.UTF_8)
        assertTrue(appLog, appLog.contains("voice: call live"))
        assertFalse("Addresses are masked", appLog.contains("studio.example"))
        assertEquals("The call log rides its upload", listOf("application/json"), message.files.map { it.mime })

        val parsed = assertNotNullValue(VoiceHandoff.parse(message.text))
        assertEquals("The voice row is misaligned.", parsed.displayText)
        assertTrue(parsed.callLogAttached)
        val state = appStateJSON(message.text)
        assertEquals("Settings › Voice", state.string("screen"))
        assertEquals("Studio", state.string("server"))
        assertEquals(true, state.obj("home")?.bool("settingsShown"))
        assertEquals("dark", state.obj("appearance")?.string("style"))
        assertTrue(message.text, message.text.contains(""""screenshot_attached" : true"""))
    }

    @Test fun aRefusedCaptureSaysSoAndSendsNothing() = runBlocking<Unit> {
        val sent = Locked(listOf<Sent>())
        val tools = workspace(client(sent), capture = { VoiceScreenshotClient.Result.Failed(VoiceScreenshotClient.Failure.credentials) })

        val result = result(tools, """{"text":"Look at this"}""")

        assertEquals(false, result.bool("screenshot_attached"))
        assertEquals(VoiceScreenshotClient.Failure.credentials.message, result.string("error"))
        assertTrue(result.string("error")?.startsWith("Couldn’t take a screenshot") == true)
        assertTrue(sent.value.isEmpty())
    }

    @Test fun withoutDeveloperModeTheToolNeverCaptures() = runBlocking<Unit> {
        val sent = Locked(listOf<Sent>())
        val captured = Locked(0)
        val tools = workspace(client(sent), developerMode = false, capture = {
            captured.update { it + 1 }
            VoiceScreenshotClient.Result.Taken(VoiceScreenshotClient.Capture(jpeg = jpeg, screen = null, appearance = null))
        })

        val result = result(tools, """{"text":"Look at this"}""")

        assertTrue(result.toString(), result.string("error")?.contains("Developer Mode") == true)
        assertNull(result["screenshot_attached"])
        assertEquals(0, captured.value)
        assertTrue(sent.value.isEmpty())
    }

    @Test fun aFailedSendIsNotReportedAsSent() = runBlocking<Unit> {
        val client = client(Locked(emptyList())).copy(
            sendPrompt = { _, _, _, _, _, _, _ -> throw OpenCodeClientError(message = "Couldn’t reach the OpenCode server") },
        )
        val tools = workspace(client, capture = {
            VoiceScreenshotClient.Result.Taken(VoiceScreenshotClient.Capture(jpeg = jpeg, screen = null, appearance = null))
        })

        val result = result(tools, """{"text":"Look at this"}""")

        assertEquals("Couldn’t reach the OpenCode server", result.string("error"))
        assertNull(result["screenshot_attached"])
    }

    // App state

    @Test fun appStateCarriesNamesAndCountsButNoSecrets() {
        // `HomeFeature.State` with a session detail whose draft is this, and Settings shown.
        val draft = "my secret plan for the launch"
        val home = VoiceAppState.Home(
            drawerOpen = false, settingsShown = true, catchUpShown = false,
            composer = VoiceAppState.Composer(draftCharacters = draft.length, dictation = "idle", images = 0, files = 0, pendingPermissions = 0, pendingQuestions = 0),
        )
        val state = appState().copy(
            home = home,
            screen = "Settings",
            appearance = VoiceAppState.Appearance(style = "light", dynamicType = "AccessibilityXL"),
        )

        val json = state.json
        val decoded = Json.parseToJsonElement(json) as JsonObject
        assertEquals(setOf("appVersion", "appearance", "build", "call", "developerMode", "device", "experiments", "home", "screen", "server", "session"), decoded.keys)
        assertEquals("Studio", decoded.string("server"))
        assertEquals("ses_voice", decoded.obj("session")?.string("id"))
        assertEquals("OpenCode iOS", decoded.obj("session")?.string("title"))
        assertEquals("live", decoded.obj("call")?.string("phase"))
        assertEquals(listOf("in-app-browser"), (decoded["experiments"] as? JsonArray)?.map { (it as JsonPrimitive).content })
        val composer = assertNotNullValue(decoded.obj("home")?.obj("composer"))
        assertEquals(draft.length, (composer["draftCharacters"] as? JsonPrimitive)?.intOrNull)
        for (secret in listOf("hunter2", "kit:", "studio.example", "ts.net", "4096", "https://", "secret plan")) {
            assertFalse(secret, json.contains(secret))
        }
    }

    @Test fun appLogMasksAddressesAndCredentialsAndKeepsTheNewest() {
        val lines = listOf(
            "info network: GET https://kit:pw@studio.example.ts.net:4096/api/info 200",
            "info network: Authorization: Bearer abc.def-123 sent",
            "info network: password=hunter2 token: tok_9 host 100.64.0.7:4096 on mini.local",
            "info voice: call live",
        )
        val attachment = assertNotNullValue(VoiceAppLog.attachment(lines))
        val text = attachment.data.toString(Charsets.UTF_8)
        assertEquals(VoiceAppLog.fileName, attachment.name)
        for (secret in listOf("kit:pw", "studio.example", "abc.def-123", "hunter2", "tok_9", "100.64.0.7", "mini.local")) {
            assertFalse(secret, text.contains(secret))
        }
        assertTrue(text, text.contains("GET <url> 200"))
        assertTrue(text, text.endsWith("info voice: call live\n"))

        val many = (0 until 1_000).map { "info app: line $it " + "x".repeat(200) }
        val bounded = assertNotNullValue(VoiceAppLog.attachment(many))
        assertTrue(bounded.data.size <= VoiceAppLog.maxBytes)
        assertTrue(bounded.data.toString(Charsets.UTF_8).contains("line 999 "))
        assertNull(VoiceAppLog.attachment(emptyList()))
    }

    // Reducer

    @Test fun theDelegatorSendsACapturedScreenshotThroughTheCall() = runBlocking<Unit> {
        val shared = SpeechTestShared()
        val voice = shared.run {
            shared(SharedKeys.developerMode).set(true)
            shared(SharedKeys.openAIAPIKey).set("sk-test")
            VoiceFeature.State(server, session).withPhase(VoiceFeature.State.Phase.Live).copy(usesDelegator = true)
                .recording(VoiceTranscriptTail.Role.user, "Look at this, the orb overlaps the composer", id = "u1")
        }

        val sent = Locked(listOf<Sent>())
        val toolOutputs = Locked(listOf<String>())
        val schemas = Locked(listOf<String>())
        val effects = detachedScope(coroutineContext)
        val store = TestStore(voice, VoiceFeature(), effects) {
            this[SharedStore] = shared.store
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            this[VoiceScreenshotClient] = VoiceScreenshotClient {
                VoiceScreenshotClient.Result.Taken(
                    VoiceScreenshotClient.Capture(jpeg = jpeg, screen = "Session", appearance = VoiceAppState.Appearance(style = "dark", dynamicType = "L")),
                )
            }
            this[VoiceDelegatorClient] = VoiceDelegatorClient(isEnabled = true) { _, input, _ ->
                schemas.update { it + VoiceDelegator.tools.mapNotNull { tool -> tool.name() } }
                val first = input.firstOrNull()
                if (first is VoiceDelegator.InputItem.ToolOutput) {
                    toolOutputs.update { it + first.output }
                    VoiceDelegator.Response(id = "r2", calls = emptyList(), text = "Took a screenshot and sent it to OpenCode iOS.")
                } else {
                    VoiceDelegator.Response(
                        id = "r1",
                        calls = listOf(VoiceDelegator.ToolCall(callID = "t1", name = "capture_app_screenshot", arguments = """{"text":"The orb overlaps the composer."}""")),
                        text = "",
                    )
                }
            }
            this[OpenCodeClient] = client(sent)
            this[VoiceWorkClient] = VoiceWorkClient.testValue
            this[VoiceCallClient] = speechVoiceCall()
        }
        store.exhaustive = false

        store.send(VoiceFeature.Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = JsonObject(emptyMap()))))
        store.receive(::isDelegatorFinished, timeout = 5.seconds)
        store.skipReceivedActions()

        assertTrue(schemas.value.toString(), schemas.value.contains("capture_app_screenshot"))
        val output = assertNotNullValue(toolOutputs.value.firstOrNull())
        assertTrue(output, output.contains(""""screenshot_attached":true"""))
        val message = assertNotNullValue(sent.value.firstOrNull())
        assertEquals(1, message.attachments.size)
        assertTrue(message.attachments.single().data.contentEquals(jpeg))
        assertEquals("Session", appStateJSON(message.text).string("screen"))
        assertEquals("gpt-live", appStateJSON(message.text).obj("call")?.string("engine"))
        store.skipInFlightEffects()
        effects.skipInFlightEffects()
    }

    // Capture

    @Test fun picksTheCallsSceneElseTheLastTouchedNeverAGuess() {
        val idle = VoiceScreenshot.SceneCandidate(isForegroundActive = true, hasCall = false, isTouched = false)
        val calling = VoiceScreenshot.SceneCandidate(isForegroundActive = true, hasCall = true, isTouched = false)
        val touched = VoiceScreenshot.SceneCandidate(isForegroundActive = true, hasCall = false, isTouched = true)
        val background = VoiceScreenshot.SceneCandidate(isForegroundActive = false, hasCall = true, isTouched = true)
        val severalWindows = null to VoiceScreenshotClient.Failure.severalWindows
        val noWindow = null to VoiceScreenshotClient.Failure.noWindow

        assertEquals(0 to null, VoiceScreenshot.pickScene(listOf(idle)))
        assertEquals("The call's window, even when another was touched last", 1 to null, VoiceScreenshot.pickScene(listOf(touched, calling)))
        assertEquals("Whatever order the scenes come in", 0 to null, VoiceScreenshot.pickScene(listOf(calling, touched)))
        assertEquals(1 to null, VoiceScreenshot.pickScene(listOf(idle, touched)))
        assertEquals(1 to null, VoiceScreenshot.pickScene(listOf(calling, VoiceScreenshot.SceneCandidate(isForegroundActive = true, hasCall = true, isTouched = true))))
        assertEquals("A background scene is never drawn", 1 to null, VoiceScreenshot.pickScene(listOf(background, idle)))
        assertEquals(severalWindows, VoiceScreenshot.pickScene(listOf(idle, idle)))
        assertEquals(severalWindows, VoiceScreenshot.pickScene(listOf(calling, calling)))
        assertEquals(noWindow, VoiceScreenshot.pickScene(listOf(background)))
        assertEquals(noWindow, VoiceScreenshot.pickScene(emptyList()))
    }

    private fun <T : Any> assertNotNullValue(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
