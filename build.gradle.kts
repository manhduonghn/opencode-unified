// Plugins come from build-logic (convention plugins: opencode.android.library,
// opencode.android.feature, opencode.android.application).

val robolectricSdkDir = layout.buildDirectory.dir("robolectric-sdks")
val prepareRobolectricSdks = tasks.register<Sync>("prepareRobolectricSdks") {
    // Resolve each SDK separately: Gradle would select just one version in a shared configuration.
    for (sdk in listOf(libs.android.all.api34, libs.android.all.api35)) {
        from(configurations.detachedConfiguration(sdk.get()).apply { isTransitive = false })
    }
    into(robolectricSdkDir)
}

subprojects {
    tasks.withType<Test>().configureEach {
        // Local runs enforce wall-clock budgets; don't reuse CI results for those runs.
        inputs.property("ci", providers.environmentVariable("CI").orElse("false"))
        if (configurations.getByName("testImplementation").dependencies.any { it.group == "org.robolectric" && it.name == "robolectric" }) {
            inputs.files(prepareRobolectricSdks).withPathSensitivity(PathSensitivity.RELATIVE)
            systemProperty("robolectric.offline", "true")
            systemProperty("robolectric.dependency.dir", robolectricSdkDir.get().asFile.relativeTo(workingDir).path)
        }
    }
}
