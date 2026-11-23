plugins {
    id("opencode.android.library")
}

android {
    // A separate namespace from :runtime (com.termux) avoids a duplicate generated R class.
    namespace = "com.termux.ui"

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation(project(":termux-shared"))
    implementation(project(":terminal-view"))
    implementation("androidx.annotation:annotation:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.viewpager:viewpager:1.0.0")
    implementation("com.google.android.material:material:1.12.0")
}
