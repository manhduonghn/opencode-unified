// Module list is fixed in Phase 0 so parallel area agents never edit this file.
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // termux-am-library used by the copied Termux runtime.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "opencode-unified"

include(
    ":app",
    ":core",
    ":server",
    ":feature:session",
    ":feature:transcript",
    ":feature:composer",
    ":feature:drawer",
    ":feature:settings",
    ":feature:voice",
    ":feature:media",
    ":feature:system",
    ":devtools",
    ":feature:terminal",
    // Unified-app additions: Termux runtime and terminal libraries.
    ":termux-shared",
    ":terminal-emulator",
    ":terminal-view",
    ":runtime",
)
