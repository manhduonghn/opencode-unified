package ai.opencode.devtools

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.diagnostics.ProblemReportBuilder
import ai.opencode.core.diagnostics.ProblemReportBundle
import ai.opencode.core.diagnostics.ProblemReportDevice
import ai.opencode.core.log.AppLog
import ai.opencode.core.tuning.InternalBuild
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A session-lab fixture's state (`GET /lab/state`): only what the switcher shows. */
@Serializable
data class LabState(
    val lab: String? = null,
    val checkpoint: String? = null,
    val generation: Int? = null,
    val checkpoints: List<Checkpoint> = emptyList(),
) {
    @Serializable
    data class Checkpoint(val id: String, val title: String, val description: String = "")
}

/** The developer tools' side effects, for [DevToolsFeature]. Test value is inert. */
class DevToolsClient(
    /** Recent `AppLog` lines from this process, oldest first. */
    val appLog: () -> List<String> = { emptyList() },
    /** Opens the share sheet with [text] (subject [title]). */
    val shareText: suspend (title: String, text: String) -> Unit = { _, _ -> },
    /** Opens the share sheet with a file written by [writeBundle]. */
    val shareFile: suspend (file: File, mimeType: String) -> Unit = { _, _ -> },
    /** Zips a diagnostics bundle into the cache; returns the file. */
    val writeBundle: suspend (bundle: ProblemReportBundle) -> File = { File("") },
    /** This phone and build, for reports. */
    val device: () -> ProblemReportDevice = { ProblemReportDevice("?", "?", "Debug", "Android", "?") },
    /** Fetches a session-lab's state; throws unless it's a session-rendering lab. */
    val labState: suspend (address: String) -> LabState = { throw UnsupportedOperationException() },
    /** Selects a session-lab checkpoint. */
    val labSelect: suspend (address: String, checkpoint: String) -> LabState = { _, _ -> throw UnsupportedOperationException() },
    /** Relaunches the app (so a changed server or launch state takes effect). */
    val restart: () -> Unit = {},
) {
    companion object Key : DependencyKey<DevToolsClient> {
        override val liveValue: DevToolsClient by lazy { live(dependency(AppContext).applicationContext) }
        override val testValue: DevToolsClient = DevToolsClient()

        private val json = Json { ignoreUnknownKeys = true }

        private fun live(context: Context): DevToolsClient {
            val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
            fun labURL(address: String, path: String): String {
                val base = address.trim().trimEnd('/')
                return (if (base.startsWith("http://") || base.startsWith("https://")) base else "http://$base") + path
            }
            suspend fun labCall(request: Request): LabState = withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    val state = json.decodeFromString(LabState.serializer(), response.body.string())
                    // Prove target identity before showing controls; never drive a real server.
                    if (state.lab != "session-rendering-v1") throw IllegalStateException("Not a session-rendering lab")
                    state
                }
            }
            fun share(intent: Intent, title: String) {
                val chooser = Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            }
            return DevToolsClient(
                appLog = { AppLog.recentLines(Instant.now().minusSeconds(ai.opencode.core.diagnostics.DiagnosticsRecorder.DEFAULT_RETENTION_SECONDS.toLong())) },
                shareText = { title, text ->
                    withContext(Dispatchers.Main) {
                        share(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text), title)
                    }
                },
                shareFile = { file, mimeType ->
                    withContext(Dispatchers.Main) {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.devtools.files", file)
                        share(
                            Intent(Intent.ACTION_SEND).setType(mimeType).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            file.name,
                        )
                    }
                },
                writeBundle = { bundle ->
                    withContext(Dispatchers.IO) {
                        val directory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
                        directory.listFiles()?.forEach { it.delete() }
                        val file = File(directory, "opencode-diagnostics-${bundle.report.shortID}.zip")
                        ZipOutputStream(file.outputStream()).use { zip ->
                            zip.putNextEntry(ZipEntry(ProblemReportBuilder.attachmentName(bundle.report)))
                            zip.write(ProblemReportBuilder.attachment(bundle).toByteArray())
                            zip.closeEntry()
                            for ((name, data) in bundle.files) {
                                zip.putNextEntry(ZipEntry(name))
                                zip.write(data)
                                zip.closeEntry()
                            }
                        }
                        file
                    }
                },
                device = {
                    val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
                    ProblemReportDevice(
                        appVersion = info?.versionName ?: "?",
                        build = info?.longVersionCode?.toString() ?: "?",
                        distribution = when {
                            InternalBuild.isDebug -> "Debug"
                            InternalBuild.isEnabled -> "Internal"
                            else -> "Play Store"
                        },
                        system = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                        model = "${Build.MANUFACTURER} ${Build.MODEL}" + if (Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu")) " (Emulator)" else "",
                    )
                },
                labState = { address -> labCall(Request.Builder().url(labURL(address, "/lab/state")).build()) },
                labSelect = { address, checkpoint ->
                    val body = """{"checkpoint":${Json.encodeToString(String.serializer(), checkpoint)}}"""
                    labCall(Request.Builder().url(labURL(address, "/lab/select")).post(body.toRequestBody("application/json".toMediaType())).build())
                },
                restart = {
                    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    if (launch != null) context.startActivity(launch)
                    Process.killProcess(Process.myPid())
                },
            )
        }
    }
}

/** Uploads every saved voice call log. `:app` installs the live value; the default does nothing. */
class SavedVoiceLogUploads(
    /** Starts the uploads and returns at once; they outlive the screen that asked. */
    val uploadAll: () -> Unit = {},
) {
    companion object Key : DependencyKey<SavedVoiceLogUploads> {
        override val liveValue: SavedVoiceLogUploads = SavedVoiceLogUploads()
        override val testValue: SavedVoiceLogUploads = SavedVoiceLogUploads()
    }
}
