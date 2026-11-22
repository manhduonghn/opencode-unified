plugins {
    id("opencode.android.feature")
}

android {
    namespace = "ai.opencode.feature.composer"
    // Robolectric resolves the Material Symbol drawables (ComposerFileChipTests measures the chip).
    testOptions.unitTests.isIncludeAndroidResources = true
}

// The graph is acyclic: media, voice, system < transcript, composer, settings < session < drawer <
// app.
dependencies {
    api(project(":feature:media"))
    api(project(":feature:voice"))
    implementation(libs.coil.compose)
    // Activity-result launchers for the Photos/Files pickers and the mic, camera and location prompts.
    implementation(libs.androidx.activity.compose)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.compose)
    // ContentResolver/Bitmap encode-decode in the ported loader and photo-fixture JVM tests.
    testImplementation(libs.robolectric)
}
