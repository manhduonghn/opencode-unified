import org.gradle.api.artifacts.dsl.RepositoryHandler

fun RepositoryHandler.mavenCentralMirror() = maven {
    name = "GoogleMavenCentralMirror"
    setUrl("https://maven-central.storage-download.googleapis.com/maven2/")
    content {
        excludeGroupAndSubgroups("androidx")
        excludeGroupAndSubgroups("com.android")
        excludeGroupAndSubgroups("com.google.android")
        excludeGroupAndSubgroups("com.google.firebase")
        excludeGroupAndSubgroups("com.google.testing.platform")
        // The Termux library is JitPack-only; a 404 here would otherwise push resolution into
        // Central (and its occasional 429s) before JitPack gets a chance.
        excludeGroup("com.termux")
    }
}

gradle.beforeSettings {
    pluginManagement.repositories.mavenCentralMirror()
    dependencyResolutionManagement.repositories.mavenCentralMirror()
}

gradle.settingsEvaluated {
    // An explicit repository disables the implicit Plugin Portal used by build-logic.
    if (pluginManagement.repositories.size == 1) pluginManagement.repositories.gradlePluginPortal()
}
