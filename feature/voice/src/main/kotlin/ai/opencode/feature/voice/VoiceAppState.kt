package ai.opencode.feature.voice

import ai.opencode.core.capture.ScreenCaptureFeedback
import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.diagnostics.CredentialSurface
import ai.opencode.core.diagnostics.DiagnosticsRecorder
import ai.opencode.core.diagnostics.ProblemReportDevice
import ai.opencode.core.diagnostics.ScreenshotPolicy
import ai.opencode.core.log.AppLog
import ai.opencode.server.friendlyName
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * What the app showed when the voice delegator took a screenshot (`capture_app_screenshot`,
 * Developer Mode only), sent with it so the session can tell what the user meant. Names, flags and
 * counts only: never a server address, credentials, pairing codes, or message or draft text.
 */
data class VoiceAppState(
    /** The screen's name, at the capture ([VisibleScreen]). */
    val screen: String? = null,
    /** The server's friendly name, never its address. */
    val server: String,
    val session: Session,
    val appVersion: String,
    val build: String,
    val experiments: List<String>,
    val developerMode: Boolean,
    val call: Call,
    /** Home's drawer and composer, while it publishes them. */
    val home: Home? = null,
    val appearance: Appearance? = null,
    val device: Device,
) {
    data class Session(
        /** Null on a new session draft. */
        val id: String?,
        val title: String,
        val draft: Boolean,
    ) {
        val jsonObject: Map<String, Any>
            get() = sortedMapOf<String, Any>("draft" to draft, "title" to title).apply { id?.let { put("id", it) } }
    }

    data class Call(
        val engine: String?,
        val phase: String,
        /** The floating call: orb, card, or full. */
        val stage: String,
        val muted: Boolean,
    ) {
        val jsonObject: Map<String, Any>
            get() = sortedMapOf<String, Any>("muted" to muted, "phase" to phase, "stage" to stage)
                .apply { engine?.let { put("engine", it) } }
    }

    data class Home(
        val drawerOpen: Boolean,
        val settingsShown: Boolean,
        val catchUpShown: Boolean,
        /** The top session screen's composer; null without one. */
        val composer: Composer? = null,
    ) {
        val jsonObject: Map<String, Any>
            get() = sortedMapOf<String, Any>(
                "catchUpShown" to catchUpShown,
                "drawerOpen" to drawerOpen,
                "settingsShown" to settingsShown,
            ).apply { composer?.let { put("composer", it.jsonObject) } }
    }

    data class Composer(
        /** The draft's length, never its text. */
        val draftCharacters: Int,
        val dictation: String,
        val images: Int,
        val files: Int,
        val pendingPermissions: Int,
        val pendingQuestions: Int,
    ) {
        val jsonObject: Map<String, Any>
            get() = sortedMapOf(
                "dictation" to dictation,
                "draftCharacters" to draftCharacters,
                "files" to files,
                "images" to images,
                "pendingPermissions" to pendingPermissions,
                "pendingQuestions" to pendingQuestions,
            )
    }

    data class Appearance(
        /** "dark" or "light". */
        val style: String,
        /** The font scale: "1.0", "1.3". */
        val dynamicType: String,
    ) {
        val jsonObject: Map<String, Any>
            get() = sortedMapOf("dynamicType" to dynamicType, "style" to style)

        companion object {
            fun of(configuration: Configuration) = Appearance(
                style = if (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) "dark" else "light",
                dynamicType = configuration.fontScale.toString(),
            )
        }
    }

    data class Device(val model: String, val system: String) {
        val jsonObject: Map<String, Any>
            get() = sortedMapOf("model" to model, "system" to system)
    }

    /** Nil fields are left out. */
    val jsonObject: Map<String, Any>
        get() = sortedMapOf<String, Any>(
            "appVersion" to appVersion,
            "build" to build,
            "call" to call.jsonObject,
            "developerMode" to developerMode,
            "device" to device.jsonObject,
            "experiments" to experiments,
            "server" to server,
            "session" to session.jsonObject,
        ).apply {
            screen?.let { put("screen", it) }
            home?.let { put("home", it.jsonObject) }
            appearance?.let { put("appearance", it.jsonObject) }
        }

    /** Pretty, sorted JSON for the message. */
    val json: String get() = PrettyJSON.obj(jsonObject)

    companion object {
        /**
         * What the call knows when the delegator starts a request. The screen, appearance and
         * Home's part are filled in at the capture.
         */
        operator fun invoke(
            call: VoiceFeature.State,
            experiments: List<String>,
            developerMode: Boolean,
            device: ProblemReportDevice = currentDevice(),
        ) = VoiceAppState(
            screen = null,
            server = call.target.server.friendlyName,
            session = Session(id = call.target.sessionID, title = call.sessionTitle, draft = call.isOnDraft),
            appVersion = device.appVersion,
            build = device.build,
            experiments = experiments,
            developerMode = developerMode,
            call = Call(
                engine = call.activeEngine?.rawValue,
                phase = phase(call.phase),
                stage = call.floatingStage.stage.name,
                muted = call.isMuted,
            ),
            home = null,
            appearance = null,
            device = Device(model = device.model, system = device.system),
        )

        private fun phase(phase: VoiceFeature.State.Phase): String = when (phase) {
            VoiceFeature.State.Phase.Connecting -> "connecting"
            VoiceFeature.State.Phase.Live -> "live"
            is VoiceFeature.State.Phase.EndingAfterGoodbye -> "ending"
            VoiceFeature.State.Phase.Ended -> "ended"
            is VoiceFeature.State.Phase.Failed -> "failed"
            VoiceFeature.State.Phase.Dropped -> "dropped"
        }

        /**
         * The app's version and the device, as `DevToolsClient` builds them for problem reports.
         * "?" where it can't be read (JVM tests have no package manager).
         */
        fun currentDevice(): ProblemReportDevice {
            val info = runCatching {
                val context = dependency(AppContext)
                context.packageManager.getPackageInfo(context.packageName, 0)
            }.getOrNull()
            return ProblemReportDevice(
                appVersion = info?.versionName ?: "?",
                build = info?.longVersionCode?.toString() ?: "?",
                distribution = "?",
                system = "Android ${Build.VERSION.RELEASE ?: "?"} (API ${Build.VERSION.SDK_INT})",
                model = listOfNotNull(Build.MANUFACTURER, Build.MODEL).joinToString(" ").ifEmpty { "?" },
            )
        }
    }
}

/**
 * The screen named on top now, for the voice delegator's screenshots: the most recently shown one
 * still visible. Screens report themselves with [set] as they appear, rename and disappear.
 */
object VisibleScreen {
    private val screens = mutableListOf<Pair<UUID, String>>()

    val current: String? get() = synchronized(screens) { screens.lastOrNull()?.second }

    fun set(name: String?, token: UUID, promote: Boolean) = synchronized(screens) {
        val index = screens.indexOfFirst { it.first == token }
        if (name == null) {
            if (index >= 0) screens.removeAt(index)
            return@synchronized
        }
        if (index >= 0 && !promote) {
            screens[index] = token to name
        } else {
            if (index >= 0) screens.removeAt(index)
            screens.add(token to name)
        }
    }
}

/**
 * Recent `AppLog` lines for a screenshot's message, the newest, bounded, with anything address- or
 * credential-shaped masked as a second line of defence behind the logging rules.
 */
object VoiceAppLog {
    const val maxLines = 300
    const val maxBytes = 60_000
    const val fileName = "app-log.txt"

    /** The app's recent lines over the diagnostics retention window, oldest first. */
    fun recentLines(): List<String> =
        AppLog.recentLines(Instant.now().minusSeconds(DiagnosticsRecorder.DEFAULT_RETENTION_SECONDS.toLong()))

    fun attachment(lines: List<String>): OpenCodeOutgoingAttachment? {
        val kept = mutableListOf<String>()
        var bytes = 0
        for (line in lines.takeLast(maxLines).asReversed()) {
            val clean = scrubbed(line)
            bytes += clean.toByteArray(Charsets.UTF_8).size + 1
            if (bytes > maxBytes) break
            kept.add(clean)
        }
        if (kept.isEmpty()) return null
        val text = kept.asReversed().joinToString("\n") + "\n"
        return OpenCodeOutgoingAttachment(data = text.toByteArray(Charsets.UTF_8), mime = "text/plain", name = fileName)
    }

    private val masks: List<Pair<Regex, String>> = listOf(
        """[A-Za-z][A-Za-z0-9+.-]*://[^\s"'<>]+""" to "<url>",
        """(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]+""" to "$1 <redacted>",
        """(?i)\b(password|passwd|token|secret|api[_-]?key|authorization)\s*[=:]\s*[^\s,;&]+""" to "$1=<redacted>",
        """\b\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?\b""" to "<ip>",
        """\b[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.(?:ts\.net|local)\b""" to "<host>",
    ).map { (pattern, template) -> Regex(pattern) to template }

    fun scrubbed(line: String): String = masks.fold(line) { text, (regex, template) -> regex.replace(text, template) }
}

/**
 * The app's own window as a JPEG, for the voice delegator's `capture_app_screenshot`: the call's
 * activity window, never the device screen, another app, or another of the app's windows.
 */
class VoiceScreenshotClient(
    /**
     * The same window for an agent the person shared it with (`voice_request_screen`), under
     * problem reports' stricter rule: Settings and What's New are refused too. First, so the
     * trailing lambda stays [capture].
     */
    val captureShared: suspend () -> Result = { Result.Failed(Failure.noWindow) },
    val capture: suspend () -> Result,
) {
    data class Capture(
        val jpeg: ByteArray,
        val screen: String?,
        val appearance: VoiceAppState.Appearance?,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Capture && jpeg.contentEquals(other.jpeg) && screen == other.screen && appearance == other.appearance

        override fun hashCode(): Int = (jpeg.contentHashCode() * 31 + (screen?.hashCode() ?: 0)) * 31 + (appearance?.hashCode() ?: 0)
    }

    enum class Failure {
        noWindow, background, credentials, severalWindows, tooLarge;

        val message: String
            get() = when (this) {
                noWindow -> "Couldn’t take a screenshot: the app has no window on screen."
                background -> "Couldn’t take a screenshot: the app isn’t in the foreground."
                credentials -> "Couldn’t take a screenshot: a password or pairing screen is showing."
                severalWindows -> "Couldn’t take a screenshot: more than one OpenCode window is open."
                tooLarge -> "Couldn’t take a screenshot: the image came out too large."
            }
    }

    sealed interface Result {
        data class Taken(val capture: Capture) : Result
        data class Failed(val failure: Failure) : Result
    }

    companion object Key : DependencyKey<VoiceScreenshotClient> {
        override val liveValue by lazy {
            val registry = dependency(VoiceScreenActivityRegistry)
            // A taken picture flashes the activity it drew and plays the shutter; a refused one
            // shows nothing.
            suspend fun capture(policy: ScreenshotPolicy): Result {
                val scene = VoiceScreenshot.scene(registry)
                var failed: Result? = null
                return ScreenCaptureFeedback.captureOptional(scene) {
                    when (val result = VoiceScreenshot.capture(registry, policy)) {
                        is Result.Taken -> result
                        is Result.Failed -> { failed = result; null }
                    }
                } ?: failed ?: Result.Failed(Failure.noWindow)
            }
            VoiceScreenshotClient(captureShared = { capture(ScreenshotPolicy.problemReport) }) { capture(ScreenshotPolicy.voice) }
        }
        override val testValue = VoiceScreenshotClient { Result.Failed(Failure.noWindow) }
    }
}

/**
 * `drawHierarchy` → `PixelCopy` of a resumed activity's window (the floating call draws inside it
 * on Android, so it's in the picture). Refused while a credential surface is composed
 * ([CredentialSurface]) or from a `FLAG_SECURE` window.
 */
object VoiceScreenshot {
    const val maxBytes = 900_000

    data class SceneCandidate(
        val isForegroundActive: Boolean,
        val hasCall: Boolean,
        val isTouched: Boolean,
    )

    /**
     * The scene to draw, the same one however the scenes are ordered: among foreground
     * scenes, the one floating the call, else the one last touched, else the only one.
     * Several left with nothing to tell them apart refuse rather than guess another
     * session's window. Returns the index or the failure.
     */
    fun pickScene(scenes: List<SceneCandidate>): Pair<Int?, VoiceScreenshotClient.Failure?> {
        var indices = scenes.indices.filter { scenes[it].isForegroundActive }
        if (indices.isEmpty()) return null to VoiceScreenshotClient.Failure.noWindow
        val withCall = indices.filter { scenes[it].hasCall }
        if (withCall.isNotEmpty()) indices = withCall
        if (indices.size > 1) indices.firstOrNull { scenes[it].isTouched }?.let { return it to null }
        return if (indices.size == 1) indices[0] to null else null to VoiceScreenshotClient.Failure.severalWindows
    }

    suspend fun capture(registry: VoiceScreenActivityRegistry, policy: ScreenshotPolicy = ScreenshotPolicy.voice): VoiceScreenshotClient.Result {
        val (activity, failure) = pickedScene(registry)
        if (failure != null || activity == null) return VoiceScreenshotClient.Result.Failed(failure ?: VoiceScreenshotClient.Failure.noWindow)
        return capture(activity, policy)
    }

    /** The activity [capture] draws, for its flash (`ScreenCaptureFeedback`); null when it would refuse. */
    suspend fun scene(registry: VoiceScreenActivityRegistry): Activity? = pickedScene(registry).first

    private suspend fun pickedScene(registry: VoiceScreenActivityRegistry): Pair<Activity?, VoiceScreenshotClient.Failure?> {
        val activities = withContext(Dispatchers.Main) { registry.resumedActivities }
        // Drawing in the background touches the GPU; no resumed activity is the background.
        if (activities.isEmpty()) return null to VoiceScreenshotClient.Failure.background
        val (index, failure) = pickScene(
            activities.map { activity ->
                SceneCandidate(isForegroundActive = !activity.isFinishing, hasCall = false, isTouched = activity === registry.lastTouched)
            },
        )
        if (failure != null || index == null) return null to (failure ?: VoiceScreenshotClient.Failure.noWindow)
        return activities[index] to null
    }

    /**
     * One window's picture, refused while a surface [policy] skips shows: the credential sheets
     * always, Settings and What's New only under `.problemReport` (a shared screen).
     */
    private suspend fun capture(activity: Activity, policy: ScreenshotPolicy): VoiceScreenshotClient.Result {
        val window = activity.window ?: return VoiceScreenshotClient.Result.Failed(VoiceScreenshotClient.Failure.noWindow)
        val view = window.decorView
        if (view.width <= 0 || view.height <= 0) return VoiceScreenshotClient.Result.Failed(VoiceScreenshotClient.Failure.noWindow)
        val secure = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        if (policy.markedSurfaces(CredentialSurface.marks) > 0 || secure) return VoiceScreenshotClient.Result.Failed(VoiceScreenshotClient.Failure.credentials)
        val full = withContext(Dispatchers.Main) {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val copied = suspendCancellableCoroutine { continuation ->
                PixelCopy.request(window, bitmap, { result -> continuation.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
            }
            if (copied) bitmap else null.also { bitmap.recycle() }
        } ?: return VoiceScreenshotClient.Result.Failed(VoiceScreenshotClient.Failure.noWindow)
        val density = activity.resources.displayMetrics.density
        val jpeg = withContext(Dispatchers.Default) { jpeg(full, density) }
        return if (jpeg == null) {
            VoiceScreenshotClient.Result.Failed(VoiceScreenshotClient.Failure.tooLarge)
        } else {
            VoiceScreenshotClient.Result.Taken(
                VoiceScreenshotClient.Capture(jpeg, VisibleScreen.current, VoiceAppState.Appearance.of(activity.resources.configuration)),
            )
        }
    }

    /** The window at most 2× (dp), within [maxBytes]: lower quality first, then 1×. */
    private fun jpeg(full: Bitmap, density: Float, maxBytes: Int = VoiceScreenshot.maxBytes): ByteArray? {
        try {
            for (scale in listOf(min(1f, 2f / density), 1f / density)) {
                val width = (full.width * scale).roundToInt().coerceAtLeast(1)
                val height = (full.height * scale).roundToInt().coerceAtLeast(1)
                val image = if (scale >= 1f) full else Bitmap.createScaledBitmap(full, width, height, true)
                try {
                    for (quality in listOf(70, 50, 30)) {
                        val out = ByteArrayOutputStream()
                        if (image.compress(Bitmap.CompressFormat.JPEG, quality, out) && out.size() <= maxBytes) return out.toByteArray()
                    }
                } finally {
                    if (image !== full) image.recycle()
                }
            }
            return null
        } finally {
            full.recycle()
        }
    }
}
