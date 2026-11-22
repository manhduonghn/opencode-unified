import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Downloads the Termux bootstrap archives embedded by `termux-bootstrap-zip.S` with `.incbin`.
 *
 * This lives in `build-logic` rather than the runtime build script so the task action never
 * captures a Gradle script object, which the configuration cache cannot serialize.
 */
abstract class DownloadBootstrapsTask : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    /** architecture -> SHA-256 of the release archive. */
    @get:Input
    abstract val checksums: MapProperty<String, String>

    @get:Internal
    abstract val outputDirectory: DirectoryProperty

    init {
        // Always re-check the local files; a corrupt cache must not silently produce a broken APK.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun download() {
        val directory = outputDirectory.get().asFile
        checksums.get().forEach { (arch, expected) ->
            val file = File(directory, "bootstrap-$arch.zip")
            if (file.exists()) {
                val localDigest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        localDigest.update(buffer, 0, read)
                    }
                }
                val localChecksum = hex(localDigest.digest())
                if (localChecksum == expected) return@forEach
                logger.lifecycle("Deleting stale ${file.name}: checksum mismatch")
                file.delete()
            }

            val url = URL(
                "https://github.com/termux/termux-packages/releases/download/" +
                    "bootstrap-${version.get()}/bootstrap-$arch.zip",
            )
            logger.lifecycle("Downloading $url")
            val digest = MessageDigest.getInstance("SHA-256")
            file.parentFile.mkdirs()
            val connection = url.openConnection()
            if (connection is HttpURLConnection) connection.instanceFollowRedirects = true
            DigestInputStream(connection.getInputStream(), digest).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val actual = hex(digest.digest())
            if (actual != expected) {
                file.delete()
                throw GradleException("Wrong checksum for $url: expected $expected, actual $actual")
            }
        }
    }

    private fun hex(bytes: ByteArray): String =
        BigInteger(1, bytes).toString(16).padStart(64, '0')
}

/** Wires [DownloadBootstrapsTask] into a module that uses ndk-build. */
class BootstrapDownloadPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val bootstrapDir = layout.projectDirectory.dir("src/main/cpp").asFile

        val download = tasks.register("downloadBootstraps", DownloadBootstrapsTask::class.java) {
            description = "Download and verify the Termux bootstrap archives embedded by ndk-build."
            version.set("2026.02.12-r1%2Bapt.android-7")
            checksums.set(
                mapOf(
                    "aarch64" to "ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3",
                    "x86_64" to "b7fd0f2e3a4de534be3144f9f91acc768630fc463eaf134ab2e64c545e834f7a",
                ),
            )
            outputDirectory.set(layout.projectDirectory.dir("src/main/cpp"))
        }

        tasks.matching { it.name.startsWith("buildNdkBuild") }.configureEach {
            dependsOn(download)
        }

        tasks.named("clean") {
            doLast {
                bootstrapDir.listFiles { _, name -> name.startsWith("bootstrap-") && name.endsWith(".zip") }
                    ?.forEach { it.delete() }
            }
        }
    }
}
