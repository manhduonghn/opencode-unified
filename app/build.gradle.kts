plugins {
    id("opencode.android.application")
}

// The languages the app is translated into (docs/translating.md).
val appLanguages = listOf(
    "en", "am", "az", "bg", "bn", "bs", "ca", "cs", "da", "de", "dz", "el", "es", "et", "fi", "fo",
    "fr", "hi", "hr", "hu", "hy", "in", "is", "it", "ja", "ka", "km", "ko", "lo", "lt", "lv", "mk",
    "mn", "ms", "my", "nb", "ne", "nl", "pl", "pt-rBR", "ro", "ru", "si", "sk", "sl", "sq", "sr",
    "sv", "tg", "th", "tk", "tr", "uk", "uz", "vi", "zh-rCN", "zh-rTW",
)

android {
    namespace = "ai.opencode.app"
    defaultConfig {
        // Must stay `com.termux`: the bootstrap and its binaries hardcode
        // /data/data/com.termux/files/usr. See ATTRIBUTION.md.
        applicationId = "com.termux"
        // CI stamps builds so testers can tell them apart: the version code (each build installs
        // over the last), the release's version (OC_VERSION_NAME) for releases, else the short
        // commit SHA in the name.
        versionCode = providers.environmentVariable("OC_VERSION_CODE").orNull?.toIntOrNull() ?: 1
        versionName = providers.environmentVariable("OC_VERSION_NAME").orNull
            ?: ("0.1.0" + (providers.environmentVariable("OC_VERSION_SUFFIX").orNull?.let { "-$it" } ?: ""))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // The release bundle is built unsigned; release.yml signs it with the Play upload key.
    buildTypes {
        debug {
            // No suffix: Termux binaries hardcode the package path.
        }
        release {
            // R8 is what makes Compose fast in release: it strips the debug-only paths, inlines
            // and devirtualises the runtime. Every library we use ships its own keep rules.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isProfileable = true
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        // :termux-shared publishes an AAR that requires core library desugaring.
        isCoreLibraryDesugaringEnabled = true
    }
    androidResources {
        // Library (Material, AndroidX) translations are kept only for the app's own languages, so
        // their labels, e.g. a sheet's "Drag handle", never read in a language the app isn't in.
        localeFilters += appLanguages
        // Android's per-app language setting lists the languages that have a values-xx folder.
        generateLocaleConfig = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation(project(":core"))
    implementation(project(":server"))
    implementation(project(":feature:session"))
    implementation(project(":feature:transcript"))
    implementation(project(":feature:composer"))
    implementation(project(":feature:drawer"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:voice"))
    implementation(project(":feature:media"))
    implementation(project(":feature:system"))
    implementation(project(":devtools"))
    implementation(project(":feature:terminal"))
    implementation(project(":feature:workspace"))
    // Unified app: local OpenCode runtime/bootstrap inherited from the Termux-based app.
    implementation(project(":runtime"))
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.process)
    debugImplementation(libs.compose.ui.tooling)
    // TestIsolationGuard, WireFixture and the network block (see AGENTS.md "Tests").
    testImplementation(testFixtures(project(":server")))
    // AppWideWorkTests: real window stores, whose sends need Android's main looper.
    testImplementation(libs.robolectric)
    constraints {
        implementation(libs.androidx.fragment) {
            because("Firebase Messaging's Play services pull in fragment 1.1.0, which Google Play flags as outdated")
        }
    }
}
