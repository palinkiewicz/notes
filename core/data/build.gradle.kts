plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "pl.dakil.notes.data"
    // androidx.core 1.19.0 requires callers to compile against API 37 or later.
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:format"))
    api(project(":core:sync"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
