plugins {
    `kotlin-dsl`
}

dependencies {
    // On the build classpath (not compileOnly) so every module gets these exact versions.
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.compose.compiler.gradle.plugin)
    implementation(libs.kotlin.serialization.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("library") {
            id = "opencode.android.library"
            implementationClass = "OpenCodeLibraryPlugin"
        }
        register("feature") {
            id = "opencode.android.feature"
            implementationClass = "OpenCodeFeaturePlugin"
        }
        register("application") {
            id = "opencode.android.application"
            implementationClass = "OpenCodeApplicationPlugin"
        }
    }
}
