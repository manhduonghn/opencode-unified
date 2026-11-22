plugins {
    id("opencode.android.library")
}

android {
    namespace = "ai.opencode.server"
    // Shared test support (WireFixture, the network block's guard plugin) for modules above.
    testFixtures { enable = true }
}

dependencies {
    api(project(":core"))
    api(libs.okhttp)
    implementation(libs.okhttp.sse)
    testImplementation(libs.okhttp.mockwebserver)
    testFixturesApi(testFixtures(project(":core")))
}
