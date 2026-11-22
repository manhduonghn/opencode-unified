plugins {
    id("opencode.android.library")
}

android {
    namespace = "com.termux.emulator"
    ndkVersion = "29.0.14206865"

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.0")
}
