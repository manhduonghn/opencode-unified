plugins {
    id("opencode.android.feature")
}

android {
    namespace = "ai.opencode.feature.session"
    // Robolectric resolves the composer's drawables and fonts (ComposerIdentityTests hosts SessionView).
    testOptions.unitTests.isIncludeAndroidResources = true
}

// The graph is acyclic: media, voice, system < transcript, composer, settings < session < drawer <
// app.
dependencies {
    api(project(":feature:transcript"))
    api(project(":feature:composer"))
    api(project(":feature:voice"))
    api(project(":feature:media"))
    api(project(":feature:system"))
    // PredictiveBackHandler / LocalOnBackPressedDispatcherOwner for SessionPageStack's back.
    implementation(libs.androidx.activity.compose)
    // ProviderIcon: OpenCode's provider logos ship, drawn by Coil.
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    // android.icu (SessionContextText's compact window) in the ported SessionSheetTests line.
    testImplementation(libs.robolectric)
}
