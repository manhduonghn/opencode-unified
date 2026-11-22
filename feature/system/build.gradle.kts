plugins {
    id("opencode.android.feature")
}

android {
    namespace = "ai.opencode.feature.system"
}

// The graph is acyclic: media, voice, system < transcript, composer, settings < session < drawer <
// app.
dependencies {
    // Save shared items into the runtime home's Downloads folder.
    implementation(project(":runtime"))
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.datastore.preferences)
    // Share encoder pixels, AppSearch documents, MimeTypeMap and the live inbox's Context in the ported JVM tests.
    testImplementation(libs.robolectric)
}
