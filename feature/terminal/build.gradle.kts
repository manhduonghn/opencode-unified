plugins {
    id("opencode.android.feature")
}

android {
    namespace = "ai.opencode.feature.terminal"
}

dependencies {
    implementation(project(":terminal-view"))
    implementation(project(":termux-shared"))
    implementation(libs.androidx.activity.compose)
}
