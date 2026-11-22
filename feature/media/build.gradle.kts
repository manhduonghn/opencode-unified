plugins {
    id("opencode.android.feature")
}

android {
    namespace = "ai.opencode.feature.media"
}

// The graph is acyclic: media, voice, system < transcript, composer, settings < session < drawer <
// app.
dependencies {
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.coil.gif)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.webkit)
    // ComponentActivity is also used by the main-source picture-in-picture host.
    implementation(libs.androidx.activity.compose)
    // Real Bitmaps, `Patterns`, `LruCache` and the main Looper in the framework-bound JVM tests.
    testImplementation(libs.robolectric)
}
