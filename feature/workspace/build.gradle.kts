plugins {
    id("opencode.android.feature")
}

android {
    namespace = "ai.opencode.feature.workspace"
}

dependencies {
    implementation(project(":runtime"))
    implementation(libs.androidx.activity.compose)
}
