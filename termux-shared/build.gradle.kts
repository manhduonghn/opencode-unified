plugins {
    id("opencode.android.library")
}

android {
    namespace = "com.termux.shared"
    ndkVersion = "29.0.14206865"

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.annotation:annotation:1.9.0")
    implementation("androidx.core:core:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.guava:guava:24.1-jre")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")
    implementation("io.noties.markwon:recycler:4.6.2")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("androidx.window:window:1.1.0")
    implementation("commons-io:commons-io:2.5")

    implementation(project(":terminal-view"))
    implementation("com.termux:termux-am-library:v2.0.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
