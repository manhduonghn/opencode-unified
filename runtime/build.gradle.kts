import org.gradle.api.GradleException
import java.io.File
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest

plugins {
    id("opencode.android.library")
}

android {
    namespace = "com.termux"
    ndkVersion = "29.0.14206865"

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }

    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

// termux-bootstrap-zip.S includes these archives with `.incbin`, so they must exist before
// any ndk-build invocation. They are downloaded (and checksum-verified) rather than committed.
val bootstrapVersion = "2026.02.12-r1%2Bapt.android-7"
val bootstrapChecksums = mapOf(
    "aarch64" to "ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3",
    "x86_64" to "b7fd0f2e3a4de534be3144f9f91acc768630fc463eaf134ab2e64c545e834f7a",
)
val bootstrapDir = layout.projectDirectory.dir("src/main/cpp").asFile

val downloadBootstraps = tasks.register("downloadBootstraps") {
    description = "Download and verify the Termux bootstrap archives embedded by ndk-build."
    // Always re-check the local files; a corrupt cache must not silently produce a broken APK.
    outputs.upToDateWhen { false }

    doLast {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return BigInteger(1, digest.digest()).toString(16).padStart(64, '0')
        }

        bootstrapChecksums.forEach { (arch, expected) ->
            val file = File(bootstrapDir, "bootstrap-$arch.zip")
            if (file.exists() && sha256(file) == expected) return@forEach

            if (file.exists()) {
                println("Deleting stale ${file.name}: checksum mismatch")
                file.delete()
            }

            val url = URL(
                "https://github.com/termux/termux-packages/releases/download/" +
                    "bootstrap-$bootstrapVersion/bootstrap-$arch.zip",
            )
            println("Downloading $url")
            val digest = MessageDigest.getInstance("SHA-256")
            file.parentFile.mkdirs()
            val connection = url.openConnection()
            if (connection is HttpURLConnection) connection.instanceFollowRedirects = true
            DigestInputStream(connection.getInputStream(), digest).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val actual = BigInteger(1, digest.digest()).toString(16).padStart(64, '0')
            if (actual != expected) {
                file.delete()
                throw GradleException("Wrong checksum for $url: expected $expected, actual $actual")
            }
        }
    }
}

tasks.matching { it.name.startsWith("buildNdkBuild") }.configureEach {
    dependsOn(downloadBootstraps)
}

tasks.named("clean") {
    doLast {
        bootstrapDir.listFiles { _, name -> name.startsWith("bootstrap-") && name.endsWith(".zip") }
            ?.forEach { it.delete() }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    api(project(":termux-shared"))
    implementation("androidx.annotation:annotation:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
}
