plugins {
    id("opencode.android.library")
}

android {
    namespace = "com.termux"
    ndkVersion = "29.0.14206865"

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }

    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    api(project(":termux-shared"))
    implementation("androidx.annotation:annotation:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
}
