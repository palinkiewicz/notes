plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // NoteKind and the note formats; :core:model arrives transitively for the JSON DOM.
    api(project(":core:format"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
