plugins {
    id("opencode.android.library")
}

android {
    namespace = "ai.opencode.core"
    // Shared test support (TestIsolationGuard, eventually) for every module's unit tests.
    testFixtures { enable = true }
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.animation)
    api(libs.compose.material3)
    api(libs.compose.ui.tooling.preview)
    debugApi(libs.compose.ui.tooling)
    api(libs.androidx.core.ktx)
    api(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    // ProviderIcon/TemplateAssetImage draw the bundled provider and MCP SVG marks (assets/).
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    testFixturesApi(libs.junit)
    testFixturesApi(libs.kotlinx.coroutines.test)
    // ProcessLifecycleOwner + Vibrator (LiveHaptics' foreground gate) in the ported LiveHapticsTests.
    testImplementation(libs.robolectric)
}
