plugins {
    id("opencode.android.feature")
}

android {
    namespace = "ai.opencode.devtools"
}

dependencies {
    // BackHandler for the Developer screen's sub-pages.
    implementation(libs.androidx.activity.compose)
}
