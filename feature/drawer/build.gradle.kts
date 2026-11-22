plugins {
    id("opencode.android.feature")
}

android {
    namespace = "ai.opencode.feature.drawer"
}

// The graph is acyclic: media, voice, system < transcript, composer, settings < session < drawer <
// app.
dependencies {
    api(project(":feature:session"))
    api(project(":feature:settings"))
    api(project(":feature:system"))
    // Report a Problem.
    implementation(project(":devtools"))
}

dependencies {
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    // Real Bitmap/Canvas/ImageDecoder pixels for the ported ProjectIcon rendering tests.
    testImplementation(libs.robolectric)
}
