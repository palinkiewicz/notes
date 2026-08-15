plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // Domain types are on the hot render path; keep them allocation-free where possible.
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    testImplementation(libs.junit)
}
