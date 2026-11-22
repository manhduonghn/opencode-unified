plugins {
    id("opencode.android.library")
}

android {
    namespace = "com.termux.view"
}

dependencies {
    api(project(":terminal-emulator"))
    implementation("androidx.annotation:annotation:1.9.0")
}
