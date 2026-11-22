plugins {
    id("opencode.android.feature")
}

android {
    namespace = "ai.opencode.feature.terminal"

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation(project(":terminal-view"))
    implementation(project(":termux-shared"))
    implementation(libs.androidx.activity.compose)
}
